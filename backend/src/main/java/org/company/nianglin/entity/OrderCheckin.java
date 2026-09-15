package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OrderCheckin —— 对应表 {@code order_checkin}。
 *
 * <p>陪诊打卡记录表</p>
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
@TableName("order_checkin")
public class OrderCheckin extends BaseEntity {

    /**
     * 订单 ID
     */
    @Schema(description = "订单 ID")
    private Long orderId;

    /**
     * 打卡陪诊员用户 ID
     */
    @Schema(description = "打卡陪诊员用户 ID")
    private Long companionId;

    /**
     * 打卡节点：DEPART-出发 / ARRIVE-到院 / IN_CONSULT-就诊中 / TAKE_MEDICINE-取药 / LEAVE-离院 / FINISH-完成
     */
    @Schema(description = "打卡节点：DEPART-出发 / ARRIVE-到院 / IN_CONSULT-就诊中 / TAKE_MEDICINE-取药 / LEAVE-离院 / FINISH-完成")
    private String node;

    /**
     * 节点顺序值 1-6，用于校验「可跳过不可回退」
     */
    @Schema(description = "节点顺序值 1-6，用于校验「可跳过不可回退」")
    private Integer nodeSort;

    /**
     * 打卡经度
     */
    @Schema(description = "打卡经度")
    private BigDecimal longitude;

    /**
     * 打卡纬度
     */
    @Schema(description = "打卡纬度")
    private BigDecimal latitude;

    /**
     * 打卡位置文字描述（一期由前端传入或留空，不做逆地理编码）
     */
    @Schema(description = "打卡位置文字描述（一期由前端传入或留空，不做逆地理编码）")
    private String address;

    /**
     * 与订单地址的直线距离（米），Haversine 计算
     */
    @Schema(description = "与订单地址的直线距离（米），Haversine 计算")
    private Integer distance;

    /**
     * 是否异常打卡（超出阈值）：0-正常 1-异常
     */
    @TableField("is_abnormal")
    @Schema(description = "是否异常打卡（超出阈值）：0-正常 1-异常")
    private Integer isAbnormal;

    /**
     * 现场照片 URL 数组，最多 6 张
     */
    @Schema(description = "现场照片 URL 数组，最多 6 张")
    private String photos;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 打卡时间
     */
    @Schema(description = "打卡时间")
    private LocalDateTime checkinTime;

}
