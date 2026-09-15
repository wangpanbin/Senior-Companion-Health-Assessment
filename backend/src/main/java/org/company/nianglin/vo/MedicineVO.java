package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.MedicineDict;

/**
 * 药品字典（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §1（列表）/ §2（详情）。</p>
 *
 * <h3>字段是「只减不增」的</h3>
 *
 * <p>文档里明确点名了三个<b>不允许出现</b>的字段：
 * {@code suggestedDosage}（建议剂量）、{@code indications}（适应症判断）、
 * {@code alternatives}（替代药）。它们不是「暂时没做」，而是合规红线 ——
 * 平台一旦在药品页面上给出剂量或适应症，就从「记录工具」变成了
 * 「提供用药意见的主体」，责任性质完全变了。</p>
 *
 * <p>因此本类做成两个静态工厂：{@link #ofList} 只给列表需要的通用字段，
 * {@link #ofDetail} 才补上注意事项与储存条件。
 * <b>两个工厂的共同点是都带 {@code disclaimer}</b> —— 免责声明不是可选项，
 * 任何一条药品数据流出时都必须挂着它。</p>
 *
 * <h3>为什么不做 VO 字段级的动态拼接</h3>
 *
 * <p>曾考虑过「列表不返回 precautions」之类更细的裁剪。放弃的原因：
 * 这里省下的是几个字节，付出的是「某天有人给列表加了一个字段，
 * 忘了它也含隐私/合规内容」的风险。药品是公开资料，不做字段级裁剪，
 * 只区分列表与详情两种粒度。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@Schema(description = "药品字典条目")
public class MedicineVO {

    /** 药品字典缺失免责声明时的兜底文案，保证「必有 disclaimer」这条不依赖数据库内容 */
    public static final String DEFAULT_DISCLAIMER =
            "本信息仅为药品通用资料，不构成任何用药建议。具体用法用量请遵医嘱或咨询药师。";

    @Schema(description = "药品 ID", example = "9001")
    private Long id;

    @Schema(description = "通用名", example = "苯磺酸氨氯地平片")
    private String name;

    @Schema(description = "商品名，可为空", example = "络活喜")
    private String tradeName;

    @Schema(description = "规格", example = "5mg × 28 片")
    private String specification;

    @Schema(description = "剂型：TABLET / CAPSULE / INJECTION / LIQUID / OTHER", example = "TABLET")
    private String dosageForm;

    @Schema(description = "剂型中文名", example = "片剂")
    private String dosageFormLabel;

    @Schema(description = "通用服用说明（仅通用表述，不含剂量建议）", example = "口服，具体用法用量请遵医嘱")
    private String commonUsage;

    @Schema(description = "注意事项；列表不返回，详情返回", example = "可能引起头晕、踝部水肿；如出现不适请及时就医")
    private String precautions;

    @Schema(description = "储存条件；列表不返回，详情返回", example = "遮光，密封，在 30℃ 以下保存")
    private String storage;

    @Schema(description = "免责声明（必返，前端必须展示）",
            example = "本信息仅为药品通用资料，不构成任何用药建议。具体用法用量请遵医嘱或咨询药师。")
    private String disclaimer;

    /**
     * 列表口径：只给「一眼认出这是什么药」所需的字段。
     *
     * <p>通用服用说明保留在列表里：让家属在选药时就看到
     * 「具体用法用量请遵医嘱」，比进详情页再看到更能减少误解。</p>
     */
    public static MedicineVO ofList(MedicineDict entity) {
        if (entity == null) {
            return null;
        }
        return new MedicineVO()
                .setId(entity.getId())
                .setName(entity.getName())
                .setTradeName(entity.getTradeName())
                .setSpecification(entity.getSpecification())
                .setDosageForm(entity.getDosageForm())
                .setDosageFormLabel(org.company.nianglin.constant.DosageForm.labelOf(entity.getDosageForm()))
                .setCommonUsage(entity.getCommonUsage())
                .setDisclaimer(disclaimer(entity));
    }

    /** 详情口径：在列表基础上补注意事项与储存条件 */
    public static MedicineVO ofDetail(MedicineDict entity) {
        if (entity == null) {
            return null;
        }
        return ofList(entity)
                .setPrecautions(entity.getPrecautions())
                .setStorage(entity.getStorage());
    }

    /**
     * 免责声明兜底。
     *
     * <p>数据库列允许为空，但「必有 disclaimer」是接口契约。
     * 如果某条种子数据漏填，这里补上默认文案而不是返回 {@code null} ——
     * 少一个字段会让前端整块免责声明消失，属于静默的合规缺口。</p>
     */
    private static String disclaimer(MedicineDict entity) {
        String text = entity.getDisclaimer();
        return (text == null || text.isBlank()) ? DEFAULT_DISCLAIMER : text;
    }
}
