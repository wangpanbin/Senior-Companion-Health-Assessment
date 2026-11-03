package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 结算核对列表查询入参（{@code docs/api/08-admin.md} §25）。
 *
 * <p>与 {@link AdminOrderQuery} 的差别有两处，都是业务语义决定的：</p>
 *
 * <ol>
 *   <li>台账只收服务已完成的订单，状态条件由服务端固定为
 *       {@code COMPLETED / REVIEWED}，因此这里<b>没有</b> {@code status} 参数；</li>
 *   <li>周期按 {@code finish_time}（服务完成时间）过滤，
 *       而不是 {@link AdminOrderQuery} 的下单时间 ——
 *       结算依据是服务完成，不是下单。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "结算核对列表查询入参")
public class AdminSettlementQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "结算状态：UNPAID-未结算 / SETTLED-已结算；不传为全部", example = "UNPAID")
    private String paymentStatus;

    @Schema(description = "订单号 / 医院 / 科室模糊搜索", example = "NL20260915")
    private String keyword;

    @Schema(description = "服务完成时间起（yyyy-MM-dd，含当天）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "服务完成时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
