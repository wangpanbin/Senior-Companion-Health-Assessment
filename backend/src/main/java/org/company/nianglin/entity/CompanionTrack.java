package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CompanionTrack —— 对应表 {@code companion_track}。
 *
 * <p>陪诊轨迹点表</p>
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
@TableName("companion_track")
public class CompanionTrack extends BaseEntity {

    /**
     * 订单 ID
     */
    @Schema(description = "订单 ID")
    private Long orderId;

    /**
     * 陪诊员用户 ID
     */
    @Schema(description = "陪诊员用户 ID")
    private Long companionId;

    /**
     * 关联打卡节点，非打卡产生的点为 NULL
     */
    @Schema(description = "关联打卡节点，非打卡产生的点为 NULL")
    private String node;

    /**
     * 经度
     */
    @Schema(description = "经度")
    private BigDecimal longitude;

    /**
     * 纬度
     */
    @Schema(description = "纬度")
    private BigDecimal latitude;

    /**
     * 定位精度（米），用于剔除漂移点
     */
    @Schema(description = "定位精度（米），用于剔除漂移点")
    private BigDecimal accuracy;

    /**
     * 速度（米/秒），用于判断是否在移动
     */
    @Schema(description = "速度（米/秒），用于判断是否在移动")
    private BigDecimal speed;

    /**
     * 定位记录时间
     */
    @Schema(description = "定位记录时间")
    private LocalDateTime recordTime;

}
