package org.company.nianglin.service;

import org.company.nianglin.dto.CompanionTrendQuery;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionServiceProfileVO;
import org.company.nianglin.vo.CompanionTrendVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;

/**
 * 陪诊员工作台统计服务（V3.3）。
 *
 * <h3>通用约定（每个方法都要守住）</h3>
 *
 * <ul>
 *   <li><b>陪诊员身份从登录态取</b>（{@code SecurityUtils.currentUserId()}），
 *       <b>不接收 {@code companionId} 入参</b> —— 少一个可以出错的地方；</li>
 *   <li>时间区间：闭区间，默认最近 30 天，上限 366 天，超限 {@code 9002}；</li>
 *   <li>空数据：返回 0 或空数组，<b>不返回 {@code null}，不报错</b>；</li>
 *   <li>比率：百分数字符串，两位小数；金额：两位小数字符串；</li>
 *   <li>「已完成」= {@code COMPLETED} + {@code REVIEWED}；</li>
 *   <li>趋势分桶：{@code DAY} / {@code WEEK} / {@code MONTH}，无数据的桶补 0，
 *       {@code categories} 与每条 {@code series.data} 长度严格一致。</li>
 * </ul>
 *
 * <h3>两套口径的分工</h3>
 *
 * <p>{@link #overview} 与 {@link #income} 是<b>统计区间内</b>的口径；
 * {@link #profile} 是<b>历史累计</b>口径。两者的字段名已自带口径
 * （{@code total*} 前缀 = 累计），指标字典里也各占一个 key。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
public interface CompanionStatisticsService {

    /** 工作台概览（区间口径）：完成单数 / 服务时长 / 完成率 / 平均评分 / 区间收入 */
    CompanionWorkbenchOverviewVO overview(DateRangeQuery query);

    /** 趋势（区间口径）：维度 ORDER / INCOME / RATING，粒度 DAY / WEEK / MONTH */
    CompanionTrendVO trend(CompanionTrendQuery query);

    /** 服务画像（累计口径）：评分 / 累计完成单数 / 服务老人数 / 准时率 / 累计完成率 / 累计服务时长 / 擅长服务 */
    CompanionServiceProfileVO profile();

    /** 收入中心（区间口径）：订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计 */
    CompanionIncomeVO income(DateRangeQuery query);
}
