package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 管理端聊天订单检索入参（{@code GET /api/admin/chat/orders}）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md} §6：仲裁查看器的左栏订单列表，
 * 只列<b>有聊天记录</b>的订单。只读接口，不落 {@code admin_oper_log}（M9 口径：写操作才落）。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端聊天订单检索入参")
public class AdminChatOrderQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "模糊匹配订单号 / 医院", example = "海南省人民医院")
    @Size(max = 50, message = "关键词不能超过 50 字")
    private String keyword;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
