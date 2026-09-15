package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 全部订单查询入参（{@code docs/api/08-admin.md} §8）。
 *
 * <p>{@code status} 支持逗号分隔多值（如 {@code IN_SERVICE,COMPLETED}）：
 * 管理员排查时最常见的诉求是「看所有还没结束的单」，
 * 单选一个状态的筛选框做不到这件事。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "全部订单查询入参")
public class AdminOrderQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "订单状态，可多值逗号分隔：PENDING,ACCEPTED,IN_SERVICE,COMPLETED,REVIEWED,CANCELLED",
            example = "IN_SERVICE")
    private String status;

    @Schema(description = "订单号 / 医院 / 姓名模糊搜索", example = "NL20260915")
    private String keyword;

    @Schema(description = "下单时间起（yyyy-MM-dd）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "下单时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    @Schema(description = "只看有投诉的订单（纠纷优先处理）", example = "true")
    private Boolean hasComplaint;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
