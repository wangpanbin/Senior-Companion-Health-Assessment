package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 我的订单列表查询入参。
 *
 * <p>对应 {@code GET /api/order}（{@code docs/api/03-order.md} §2）。</p>
 *
 * <h3>为什么没有 {@code familyId} / {@code companionId}</h3>
 *
 * <p>「查谁的订单」由令牌里的角色和用户 ID 决定，不由前端决定，参数里传了也不认。
 * 这个模块是全局瓶颈、被 5 个模块依赖，一旦这里开了个口子，
 * 后面每个改这块代码的人都得重新判断一次「这个参数会不会被用来越权」。</p>
 *
 * <p>{@code elderId} 是个例外 —— 它是<b>范围内筛选</b>而不是范围本身：
 * 家属只能看自己下的单，再加一层「只看某位老人的」只是缩小结果集。
 * 两种参数的区别在于：去掉它，结果依然是安全的。</p>
 *
 * <h3>日期用 String 而不是 LocalDate</h3>
 *
 * <p>为了能给出「startDate 日期格式应为 yyyy-MM-dd」这样的提示。
 * 交给 Spring 做类型转换时，格式错误会走到
 * {@code MethodArgumentTypeMismatchException} 的通用文案，
 * 用户看到的是「参数类型不正确」，不知道该改哪个参数。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的订单列表查询入参")
public class OrderQuery extends PageQuery<CompanionOrder> {

    @Schema(description = "订单状态，可多值逗号分隔", example = "PENDING,ACCEPTED")
    @Size(max = 100, message = "状态参数过长")
    private String status;

    @Schema(description = "下单日期起（含），格式 yyyy-MM-dd", example = "2026-09-01")
    private String startDate;

    @Schema(description = "下单日期止（含），格式 yyyy-MM-dd", example = "2026-09-30")
    private String endDate;

    @Schema(description = "按就诊老人筛选（仅在当前用户可见范围内生效）", example = "401")
    private Long elderId;

    @Schema(description = "订单号 / 医院名称模糊搜索", example = "NL20260915")
    @Size(max = 100, message = "搜索关键字不能超过 100 个字符")
    private String keyword;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionOrder> toMpPage() {
        return toPage(new Page<>());
    }
}
