package org.company.nianglin.service;

import jakarta.servlet.http.HttpServletResponse;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.StatisticsQuery;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.CompanionRankVO;
import org.company.nianglin.vo.MedicationMissedVO;
import org.company.nianglin.vo.OrderStatusStatVO;
import org.company.nianglin.vo.StatisticsOverviewVO;

import java.util.List;

/**
 * 数据统计与导出服务。
 *
 * <p>对应 {@code docs/api/09-statistics-export.md} §1 ~ §7。</p>
 *
 * <h3>通用约定（§一，每个方法都要守住）</h3>
 *
 * <ul>
 *   <li>时间区间：{@code yyyy-MM-dd} <b>闭区间</b>，默认最近 30 天，上限 366 天，超限 {@code 9002}；</li>
 *   <li>空数据：返回空数组或 0，<b>不返回 {@code null}，不报错</b>；</li>
 *   <li>比率：字符串百分数，两位小数，如 {@code "94.59%"}；</li>
 *   <li>粒度：{@code DAY} / {@code WEEK} / {@code MONTH}，<b>无数据的日期补 0</b>；</li>
 *   <li>权限：全部接口仅 {@code ADMIN}（由 Controller 类级注解收口）。</li>
 * </ul>
 *
 * <h3>关于「1 万条订单下 &lt; 2s」这条性能验收</h3>
 *
 * <p>本期<b>不做缓存</b>（ADR 默认策略：不缓存 + 索引优化）。理由：统计页是
 * 管理员低频打开的页面，为了它引入缓存，就要额外处理「订单状态变了、
 * 缓存什么时候失效」这一整套问题；而聚合 SQL 只要走
 * {@code create_time} / {@code status} 上的索引，1 万行量级的
 * {@code GROUP BY} 在毫秒级就能返回。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
public interface StatisticsService {

    /* ==================== 统计 ==================== */

    /** 总览指标（订单 / 用户 / 陪诊员 / 用药四组） */
    StatisticsOverviewVO overview(StatisticsQuery query);

    /** 订单趋势（三条序列：新增 / 完成 / 取消，无数据日期补 0） */
    ChartDataVO orderTrend(StatisticsQuery query);

    /** 订单状态分布（六种状态全部返回，数量为 0 的也返回） */
    List<OrderStatusStatVO> orderStatus(StatisticsQuery query);

    /** 陪诊员接单排行（只统计资质已通过的陪诊员，接单数为 0 的不进榜） */
    List<CompanionRankVO> companionRank(StatisticsQuery query);

    /** 漏服率统计（图表 + 区间汇总，口径为 {@code was_missed = 1}） */
    MedicationMissedVO medicationMissed(StatisticsQuery query);

    /* ==================== 导出 ==================== */

    /**
     * 导出订单 Excel，筛选条件与 {@code /api/admin/order} 完全一致。
     *
     * @throws org.company.nianglin.exception.BusinessException 9001 超过行数上限 / 9002 时间区间不合法
     */
    void exportOrders(AdminOrderQuery query, HttpServletResponse response);

    /**
     * 导出用户 Excel，筛选条件与 {@code /api/admin/user} 完全一致。
     *
     * <p>导出内容<b>绝不包含</b>密码、身份证号、完整手机号。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException 9001 超过行数上限
     */
    void exportUsers(AdminUserQuery query, HttpServletResponse response);
}
