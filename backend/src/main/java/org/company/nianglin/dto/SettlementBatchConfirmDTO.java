package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量结算核定入参（{@code docs/api/08-admin.md} §27）。
 *
 * <p>入参是<b>订单 ID 列表</b>而不是「单个 or 全部」的开关：
 * 管理员在页面上勾选哪些单，就核定哪些单，
 * 避免出现「点一次批量按钮，把全平台未结算单都核了」这种不可撤销的误操作。</p>
 *
 * <p>上限 100 与分页上限一致，是单次人工核对的合理边界；
 * 重复 ID 由 Service 去重并保留首次出现顺序。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Schema(description = "批量结算核定入参")
public class SettlementBatchConfirmDTO {

    @NotEmpty(message = "请选择至少一笔订单")
    @Size(max = 100, message = "单次最多核定 100 笔订单")
    @Schema(description = "订单 ID 列表，1–100 条", example = "[1001, 1002]")
    private List<@NotNull(message = "订单 ID 不能为空") Long> orderIds;
}
