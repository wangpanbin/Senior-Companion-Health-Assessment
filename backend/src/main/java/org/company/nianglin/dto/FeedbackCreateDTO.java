package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交产品反馈入参（{@code docs/api/15-product-feedback.md} §1）。
 *
 * <h3>为什么没有 submitterId / submitterRole</h3>
 *
 * <p>提交人与角色一律取自登录态（{@code SecurityUtils.currentUser()}）。
 * 允许前端传「我是谁」，就等于允许陪诊员以家属身份提交、或替别人提交 ——
 * 而这条通道的价值恰恰建立在「谁提的」可追溯上。</p>
 *
 * <h3>为什么 images 只是 URL 数组</h3>
 *
 * <p>截图先由上传接口落盘拿到 URL，再随反馈一起提交。不在本接口里直接收
 * multipart：一个接口同时接收 JSON 字段与二进制文件时，前端必须改用
 * {@code FormData}，而 {@code FormData} 里所有字段都变成字符串，
 * {@code type} 这类枚举校验会静默失效（与 {@code ComplaintCreateDTO} 同一取舍）。</p>
 *
 * <h3>为什么正文不过医疗红线词表</h3>
 *
 * <p>用户写「希望用药页能提醒我别吃错」是<b>正当的产品建议</b>，
 * 套 {@code ComplianceCheckUtil} 的词表（诊断 / 剂量 / 建议服用）会被误杀。
 * 入参只过 {@code SensitiveWordUtil}；平台自己的产出口径（管理员处理说明）
 * 才过两套词表 —— 见 {@code FeedbackServiceImpl#handle}。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@Schema(description = "提交产品反馈入参")
public class FeedbackCreateDTO {

    @Schema(description = "分类：FEATURE-功能建议 / EXPERIENCE-使用体验 / DEFECT-缺陷问题 / OTHER-其他",
            example = "EXPERIENCE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请选择反馈分类")
    private String type;

    @Schema(description = "反馈内容，10–1000 字符",
            example = "订单列表的字有点小，老人模式下也还是偏小，希望能再大一号。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请描述您希望改进的地方")
    @Size(min = 10, max = 1000, message = "反馈内容长度应为 10–1000 个字符")
    private String content;

    @Schema(description = "截图 URL 列表，最多 3 张（先经 POST /api/file/upload，bizType=FEEDBACK）",
            example = "[\"/uploads/202611/9f8e7d6c5b4a39281706fedcba987654.jpg\"]")
    @Size(max = 3, message = "截图最多 3 张")
    private List<@Size(max = 255, message = "截图 URL 过长") String> images;
}
