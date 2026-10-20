package org.company.nianglin.service;

import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;

/**
 * 平台运营驾驶舱服务（V3.5 · T3）。
 *
 * <h3>本模块的三条硬性约束</h3>
 *
 * <ol>
 *   <li><b>异常中心复用 V3.1 的风险判定</b>（决策 5.1）：「需人工介入订单数」与
 *       「超匹配预警时间订单数」直接取 {@code AdminService#fulfillmentSummary()}，
 *       <b>不另建一套风险判定</b>。另建一套的结果是：两个页面的数字对不上，
 *       而没有人能说清哪个是对的。</li>
 *   <li><b>「履约率」与「完成率（按时）」是两个指标</b>（偏差 D-3.5-1）：
 *       履约率 = 完成 ÷（完成 + 取消）；完成率 = 按时完成 ÷ 已完成。
 *       两个名字一个数是口径分裂的另一种形态，同样禁止。</li>
 *   <li><b>区间一律走共享的 {@link DateRangeQuery}</b>（默认 30 天 / 上限 366 天 /
 *       超限 9002 / 闭区间），不自己写一套收口逻辑。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
public interface OperationsService {

    /**
     * 驾驶舱概览：八项指标 + 统计区间。
     *
     * @param query 统计区间入参（可空，缺省最近 30 天）
     * @return 概览
     */
    OperationsOverviewVO overview(DateRangeQuery query);

    /**
     * 异常中心：四类异常的数量与处理入口。
     *
     * @param query 统计区间入参（可空，缺省最近 30 天；只影响用药漏服一项）
     * @return 异常中心
     */
    OperationsExceptionVO exceptions(DateRangeQuery query);

    /**
     * 趋势：六条维度之一，无数据的桶补 0。
     *
     * @param query 维度 / 区间档位 / 自定义区间
     * @return 趋势
     * @throws org.company.nianglin.exception.BusinessException 维度或区间档位非法（400）
     */
    OperationsTrendVO trend(OperationsTrendQuery query);

    /**
     * 健康度：六个并列指标，<b>不合成总分</b>。
     *
     * @param query 统计区间入参（可空，缺省最近 30 天）
     * @return 健康度
     */
    OperationsHealthVO health(DateRangeQuery query);
}
