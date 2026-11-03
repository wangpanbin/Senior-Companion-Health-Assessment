package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * VitalSignRecord —— 对应表 {@code vital_sign_record}（V4 · P2-2）。
 *
 * <p>三类体征共用一张表：血压存 {@code systolic / diastolic}，血糖存
 * {@code glucoseValue / glucoseContext}，体重存 {@code weightKg}，其余数值列为空。</p>
 *
 * <p>⚠️ 合规红线：本表<b>只存测量数值与事实</b>，没有备注、分级、区间、目标值字段；
 * 实体也不得直接作为接口返回值，统一转 {@code VitalSignVO}。</p>
 *
 * <p>⚠️ 表上的生成列 {@code dedup_key} 故意<b>不映射</b>到实体 ——
 * 它是「老人 + 类型 + 分钟」的唯一键，由数据库自行计算；
 * 映射成字段会让 {@code INSERT} 尝试写入生成列而失败。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vital_sign_record")
public class VitalSignRecord extends BaseEntity {

    /**
     * 老人档案 ID（由路径 / 订单推导，不接受请求体传入）
     */
    @Schema(description = "老人档案 ID")
    private Long elderId;

    /**
     * 体征类型：{@link org.company.nianglin.constant.VitalType}
     */
    @Schema(description = "体征类型：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT")
    private String vitalType;

    /**
     * 测量时间（分钟精度，秒固定 00）
     */
    @Schema(description = "测量时间（分钟精度）")
    private LocalDateTime measuredAt;

    /**
     * 收缩压（mmHg），仅 BLOOD_PRESSURE
     */
    @Schema(description = "收缩压（mmHg），仅血压")
    private Integer systolic;

    /**
     * 舒张压（mmHg），仅 BLOOD_PRESSURE
     */
    @Schema(description = "舒张压（mmHg），仅血压")
    private Integer diastolic;

    /**
     * 血糖值（mmol/L），仅 BLOOD_GLUCOSE
     */
    @Schema(description = "血糖值（mmol/L），仅血糖")
    private BigDecimal glucoseValue;

    /**
     * 血糖场景：{@link org.company.nianglin.constant.GlucoseContext}，仅 BLOOD_GLUCOSE
     */
    @Schema(description = "血糖场景：FASTING / POSTPRANDIAL / RANDOM")
    private String glucoseContext;

    /**
     * 体重（kg），仅 WEIGHT
     */
    @Schema(description = "体重（kg），仅体重")
    private BigDecimal weightKg;

    /**
     * 录入来源：{@link org.company.nianglin.constant.VitalSource}
     */
    @Schema(description = "录入来源：FAMILY_MANUAL / COMPANION_SERVICE")
    private String sourceType;

    /**
     * 录入人用户 ID（服务端从登录态写入）
     */
    @Schema(description = "录入人用户 ID")
    private Long operatorUserId;

    /**
     * 陪诊订单 ID；仅陪诊员代录时有值
     */
    @Schema(description = "陪诊订单 ID；仅陪诊员代录时有值")
    private Long orderId;
}
