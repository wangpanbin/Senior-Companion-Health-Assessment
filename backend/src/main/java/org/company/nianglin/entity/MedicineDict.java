package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

/**
 * MedicineDict —— 对应表 {@code medicine_dict}。
 *
 * <p>药品字典表（仅通用信息，不含用药建议）</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("medicine_dict")
public class MedicineDict extends BaseEntity {

    /**
     * 通用名，如「苯磺酸氨氯地平片」
     */
    @Schema(description = "通用名，如「苯磺酸氨氯地平片」")
    private String name;

    /**
     * 商品名，如「络活喜」，可为空
     */
    @Schema(description = "商品名，如「络活喜」，可为空")
    private String tradeName;

    /**
     * 规格，如「5mg × 28 片」
     */
    @Schema(description = "规格，如「5mg × 28 片」")
    private String specification;

    /**
     * 剂型：TABLET-片剂 / CAPSULE-胶囊 / INJECTION-注射剂 / LIQUID-口服液 / OTHER-其他
     */
    @Schema(description = "剂型：TABLET-片剂 / CAPSULE-胶囊 / INJECTION-注射剂 / LIQUID-口服液 / OTHER-其他")
    private String dosageForm;

    /**
     * 通用服用说明（**仅「口服，具体用法用量请遵医嘱」这类通用表述**）
     */
    @Schema(description = "通用服用说明（**仅「口服，具体用法用量请遵医嘱」这类通用表述**）")
    private String commonUsage;

    /**
     * 注意事项（如「可能引起嗜睡」「需避光保存」）
     */
    @Schema(description = "注意事项（如「可能引起嗜睡」「需避光保存」）")
    private String precautions;

    /**
     * 储存条件
     */
    @Schema(description = "储存条件")
    private String storage;

    /**
     * 生产厂家
     */
    @Schema(description = "生产厂家")
    private String manufacturer;

    /**
     * 批准文号
     */
    @Schema(description = "批准文号")
    private String approvalNo;

    /**
     * 处方类别：OTC-非处方药 / PRESCRIPTION-处方药
     */
    @Schema(description = "处方类别：OTC-非处方药 / PRESCRIPTION-处方药")
    private String otcType;

    /**
     * 是否常用药（前端优先展示）：0-否 1-是
     */
    @TableField("is_common")
    @Schema(description = "是否常用药（前端优先展示）：0-否 1-是")
    private Integer isCommon;

    /**
     * 免责声明（**接口必返、前端必展示**）
     */
    @Schema(description = "免责声明（**接口必返、前端必展示**）")
    private String disclaimer;

}
