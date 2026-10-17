package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 履约风险列表查询入参（V3.1 · T6，{@code docs/api/03-order.md} / spec §2.6）。
 *
 * <p>只带一个业务筛选 {@code risk}：本页的目的就是「按严重度看哪几单要处理」，
 * 日期、关键词这类筛选不解决这个动作，加进来只会让筛选条变成摆设。</p>
 *
 * <p><b>排序不开放</b>：默认序（等级降序 → 等待时长降序 → id 降序）是这个页面的
 * 唯一正确读法 —— 管理员要先看最坏的那几单。开放 sortField 会得到
 * 「按创建时间正序」这种对本页没有意义的视角，却让后端多背一份白名单校验。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "履约风险订单列表查询入参（V3.1）")
public class FulfillmentRiskQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "风险等级，四选一；缺省返回全部等级（含 NORMAL）", example = "URGENT")
    private String risk;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
