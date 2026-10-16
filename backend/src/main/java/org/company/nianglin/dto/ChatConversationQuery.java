package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 我的会话列表分页入参（{@code GET /api/chat/conversations}）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md} §3。除通用分页外没有筛选参数：
 * 会话就是「我作为下单家属 / 接单陪诊员的已接单订单」，范围由令牌决定，
 * 不给前端留一个能指定他人的 {@code userId}。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的会话列表分页入参")
public class ChatConversationQuery extends PageQuery<CompanionOrder> {

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
