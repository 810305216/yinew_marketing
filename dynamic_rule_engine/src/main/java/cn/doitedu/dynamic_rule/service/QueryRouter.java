package cn.doitedu.dynamic_rule.service;

import cn.doitedu.dynamic_rule.pojo.LogBean;
import cn.doitedu.dynamic_rule.pojo.RuleAtomicParam;
import cn.doitedu.dynamic_rule.pojo.RuleParam;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.flink.api.common.state.ListState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * @author 涛哥
 * @nick_name "deep as the sea"
 * @contact qq:657270652 wx:doit_edu
 * @site www.doitedu.cn
 * @date 2021-03-31
 * @desc 查询路由模块
 */
public class QueryRouter {

    private UserProfileQueryService userProfileQueryService;

    private UserActionCountQueryService userActionCountQueryStateService;
    private UserActionSequenceQueryService userActionSequenceQueryStateService;

    private UserActionCountQueryService userActionCountQueryClickhouseService;
    private UserActionSequenceQueryService userActionSequenceQueryClickhouseService;


    public QueryRouter() throws Exception {

        userProfileQueryService = new UserProfileQueryServiceHbaseImpl();

        /**
         * 构造底层的核心STATE查询服务
         */
        userActionCountQueryStateService = new UserActionCountQueryServiceStateImpl();
        userActionSequenceQueryStateService = new UserActionSequenceQueryServiceStateImpl();

        /**
         * 构造底层的核心CLICKHOUSE查询服务
         */
        userActionCountQueryClickhouseService = new UserActionCountQueryServiceClickhouseImpl();
        userActionSequenceQueryClickhouseService = new UserActionSequenceQueryServiceClickhouseImpl();
    }


    // 控制画像条件查询路由
    public boolean profileQuery(LogBean logBean, RuleParam ruleParam) {

        boolean profileIfMatch = userProfileQueryService.judgeProfileCondition(logBean.getDeviceId(), ruleParam);
        if (!profileIfMatch) return false;
        return true;
    }


    /**
     * 控制count类条件查询路由
     *
     * @param logBean
     * @param ruleParam
     * @param eventState
     * @return
     * @throws Exception
     */
    public boolean countConditionQuery(LogBean logBean, RuleParam ruleParam, ListState<LogBean> eventState) throws Exception {


        // 计算查询分界点timestamp
        // 当前时间对小时取整，-1
        long splitPoint = DateUtils.addHours(DateUtils.ceiling(new Date(), Calendar.HOUR), -2).getTime();


        // 遍历规则中的事件count类条件，按照时间跨度，分成3组
        ArrayList<RuleAtomicParam> farRangeParams = new ArrayList<>();  // 只查远期的条件组
        ArrayList<RuleAtomicParam> nearRangeParams = new ArrayList<>();  // 只查近期的条件组
        ArrayList<RuleAtomicParam> crossRangeParams = new ArrayList<>();  // 只查近期的条件组

        // 取出规则中的count类条件
        List<RuleAtomicParam> userActionCountParams = ruleParam.getUserActionCountParams();

        // 条件分组
        for (RuleAtomicParam userActionCountParam : userActionCountParams) {
            if (userActionCountParam.getRangeEnd() < splitPoint) {
                // 如果条件结束时间<分界点，放入远期条件组
                farRangeParams.add(userActionCountParam);
            } else if (userActionCountParam.getRangeStart() >= splitPoint) {
                // 如果条件起始时间>=分界点，放入近期条件组
                nearRangeParams.add(userActionCountParam);
            } else {
                // 否则，就放入跨界条件组
                crossRangeParams.add(userActionCountParam);
            }
        }

        /**
         * 近期条件组查询
         */
        if (nearRangeParams.size() > 0) {
            // 将规则总参数对象中的“次数类条件”覆盖成： 近期条件组
            ruleParam.setUserActionCountParams(nearRangeParams);
            // 交给stateService对这一组条件进行计算
            boolean countMatch = userActionCountQueryStateService.queryActionCounts("", eventState, ruleParam);
            if (!countMatch) return false;
        }

        /**
         * 远期条件组查询
         */
        if (farRangeParams.size() > 0) {
            // 将规则总参数对象中的“次数类条件”覆盖成： 远期条件组
            ruleParam.setUserActionCountParams(farRangeParams);
            boolean b = userActionCountQueryClickhouseService.queryActionCounts(logBean.getDeviceId(), null, ruleParam);
            if (!b) return false;
        }


        /**
         * 跨界count条件组查询
         */
        RuleParam copyParam1 = new RuleParam(); // 分界点右边分段的count参数
        RuleParam copyParam2 = new RuleParam(); // 分界点左边分段的count参数

        for (RuleAtomicParam crossRangeParam : crossRangeParams) {
            long originRangeStart = crossRangeParam.getRangeStart();

            // 将参数对象的rangeStart换成分界点，去state service中查询一下
            crossRangeParam.setRangeStart(splitPoint);
            boolean b = userActionCountQueryStateService.queryActionCounts(logBean.getDeviceId(), eventState, crossRangeParam);
            if (b) continue;

            // 如果上面不满足，则将rangeEnd换成分界点，去clickhouse service查询
            crossRangeParam.setRangeStart(originRangeStart);
            crossRangeParam.setRangeEnd(splitPoint);
            boolean b1 = userActionCountQueryClickhouseService.queryActionCounts(logBean.getDeviceId(), eventState, crossRangeParam);

            if (!b1) return false;
        }
        return true;
    }


    /**
     * 控制sequence类条件查询路由
     */
    public boolean sequenceConditionQuery(LogBean logBean, RuleParam ruleParam, ListState<LogBean> eventState) throws Exception {

        // 计算查询分界点timestamp
        // 当前时间对小时取整，-1
        long splitPoint = DateUtils.addHours(DateUtils.ceiling(new Date(), Calendar.HOUR), -2).getTime();

        // 取出规则中的序列条件
        List<RuleAtomicParam> userActionSequenceParams = ruleParam.getUserActionSequenceParams();

        // 取出规则中的序列的总步骤数
        int totalSteps = userActionSequenceParams.size();

        // 如果序列条件有内容，才开始计算
        if(userActionSequenceParams!=null && userActionSequenceParams.size()>0){
            long rangeStart = userActionSequenceParams.get(0).getRangeStart();
            long rangeEnd = userActionSequenceParams.get(0).getRangeEnd();

            // 如果条件的时间窗口起始点>分界点，则在state中查询
            if(rangeStart>=splitPoint){
                boolean b = userActionSequenceQueryStateService.queryActionSequence("", eventState, ruleParam);
                return b;
            }


            // 如果条件的时间窗口结束点<分界点，则在clickhouse中查询
            else if(rangeEnd<splitPoint){
                boolean b = userActionSequenceQueryClickhouseService.queryActionSequence(logBean.getDeviceId(), null, ruleParam);
                return b;
            }


            // 否则就是跨界查询
            else{
                // 重设时间窗口，先查state碰运气
                modifyTimeRange(userActionSequenceParams,splitPoint,rangeEnd);

                boolean b = userActionSequenceQueryStateService.queryActionSequence(logBean.getDeviceId(), eventState, ruleParam);
                if(b) return true;

                // 如果运气没碰上，则按正统思路查（先查clickhouse，再查state，再整合结果）
                // 更新一下时间分段
                modifyTimeRange(userActionSequenceParams,rangeStart,splitPoint);
                // 交给clickhouse service去查远期部分
                // 条件如： [A    B     C]
                boolean b1 = userActionSequenceQueryClickhouseService.queryActionSequence(logBean.getDeviceId(), eventState, ruleParam);
                int farMaxStep = ruleParam.getUserActionSequenceQueriedMaxStep();
                if(b1) return true;

                // 如果远期部分不足以满足整个条件，则将条件截短
                // 更新一下时间分段
                modifyTimeRange(userActionSequenceParams,splitPoint,rangeEnd);
                // 截短序列
                ruleParam.setUserActionSequenceParams(userActionSequenceParams.subList(farMaxStep,userActionSequenceParams.size()));
                // 查询
                boolean b2 = userActionSequenceQueryStateService.queryActionSequence(logBean.getDeviceId(), eventState, ruleParam);
                int nearMaxStep = ruleParam.getUserActionSequenceQueriedMaxStep();
                return farMaxStep+nearMaxStep>=totalSteps;
            }

        }


        return true;
    }


    /**
     * 更新条件时间窗口起始点的工具方法
     * @param userActionSequenceParams
     * @param newStart
     * @param newEnd
     */
    private void modifyTimeRange(List<RuleAtomicParam> userActionSequenceParams,long newStart,long newEnd){
        for (RuleAtomicParam userActionSequenceParam : userActionSequenceParams) {
            userActionSequenceParam.setRangeStart(newStart);
            userActionSequenceParam.setRangeEnd(newEnd);
        }
    }
}
