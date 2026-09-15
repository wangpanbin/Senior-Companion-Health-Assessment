package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 待接单订单大厅查询入参。
 *
 * <p>对应 {@code GET /api/order/hall}（{@code docs/api/03-order.md} §3）。</p>
 *
 * <h3>{@code area} 筛的是地址文本，不是行政区划</h3>
 *
 * <p>订单表里没有「服务区域」字段，只有家属填写的医院地址文本。所以这里的
 * {@code area} 做的是对 {@code address} 的模糊匹配 —— 陪诊员输入「美兰区」
 * 能筛出美兰区的单子，依赖的是家属填地址时写全了。这是<b>一期用文字地址
 * 替代地图导航</b>这个产品决策的直接后果，写进文档的「已知限制」里，
 * 不假装它是一个准确的地理筛选。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "待接单订单大厅查询入参")
public class OrderHallQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "服务区域（对医院地址做模糊匹配）", example = "美兰区")
    @Size(max = 50, message = "区域关键字不能超过 50 个字符")
    private String area;

    @Schema(description = "医院名称关键字", example = "人民医院")
    @Size(max = 50, message = "医院关键字不能超过 50 个字符")
    private String hospital;

    @Schema(description = "就诊日期起（含），格式 yyyy-MM-dd", example = "2026-09-20")
    private String startDate;

    @Schema(description = "就诊日期止（含），格式 yyyy-MM-dd", example = "2026-09-25")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
