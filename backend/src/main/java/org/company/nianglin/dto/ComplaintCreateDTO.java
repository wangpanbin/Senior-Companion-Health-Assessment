package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交投诉入参（{@code docs/api/06-review-complaint.md} §5）。
 *
 * <h3>投诉人与被投诉人由订单关系推导</h3>
 *
 * <p>入参里刻意没有 {@code complainantId} / {@code targetUserId}。
 * 文档 §5 实现要点写得很直白：「由订单关系自动推导，<b>不由前端传入</b>（防伪造）」。
 * 允许前端传被投诉人，就等于允许「A 投诉 C」，而 C 与这笔订单毫无关系 ——
 * 管理员会先被这条假投诉浪费一轮排查。</p>
 *
 * <h3>证据只能是 URL 数组</h3>
 *
 * <p>图片先由上传接口落盘拿到 URL，再随投诉一起提交。
 * 不在投诉接口里直接收 multipart：一旦一个接口同时接收
 * JSON 字段与二进制文件，前端就必须改用 {@code FormData}，
 * 而 {@code FormData} 里所有字段都变成字符串，
 * {@code orderId} 的类型校验会静默失效。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Schema(description = "提交投诉入参")
public class ComplaintCreateDTO {

    @Schema(description = "关联订单 ID（须为当前用户相关订单）", example = "1001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "订单 ID 不能为空")
    private Long orderId;

    @Schema(description = "投诉类型：LATE / ATTITUDE / INCOMPLETE / FEE_DISPUTE / PRIVACY / OTHER",
            example = "LATE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "投诉类型不能为空")
    private String type;

    @Schema(description = "投诉内容，10–1000 字符", example = "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "投诉内容不能为空")
    @Size(min = 10, max = 1000, message = "投诉内容长度应为 10–1000 个字符")
    private String content;

    @Schema(description = "证据材料 URL 列表，最多 6 张", example = "[\"/uploads/202609/ghi789.jpg\"]")
    @Size(max = 6, message = "证据材料最多 6 张")
    private List<@Size(max = 255, message = "证据 URL 过长") String> evidence;
}
