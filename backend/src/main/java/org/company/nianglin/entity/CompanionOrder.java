package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CompanionOrder —— 对应表 {@code companion_order}。
 *
 * <p>陪诊订单主表</p>
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
@TableName("companion_order")
public class CompanionOrder extends BaseEntity {

    /**
     * 订单号，格式 NL + yyyyMMdd + 6 位序列，如 NL20260915000001
     */
    @Schema(description = "订单号，格式 NL + yyyyMMdd + 6 位序列，如 NL20260915000001")
    private String orderNo;

    /**
     * 下单家属用户 ID
     */
    @Schema(description = "下单家属用户 ID")
    private Long familyId;

    /**
     * 就诊老人档案 ID
     */
    @Schema(description = "就诊老人档案 ID")
    private Long elderId;

    /**
     * 接单陪诊员用户 ID，未接单为 NULL
     */
    @Schema(description = "接单陪诊员用户 ID，未接单为 NULL")
    private Long companionId;

    /**
     * 医院名称
     */
    @Schema(description = "医院名称")
    private String hospital;

    /**
     * 就诊科室
     */
    @Schema(description = "就诊科室")
    private String department;

    /**
     * 就诊时间，必须晚于下单时间
     */
    @Schema(description = "就诊时间，必须晚于下单时间")
    private LocalDateTime visitTime;

    /**
     * 医院地址（**文字地址，一期不做地图导航**）
     */
    @Schema(description = "医院地址（**文字地址，一期不做地图导航**）")
    private String address;

    /**
     * 订单地址经度，用于打卡距离校验（不渲染地图）
     */
    @Schema(description = "订单地址经度，用于打卡距离校验（不渲染地图）")
    private BigDecimal longitude;

    /**
     * 订单地址纬度
     */
    @Schema(description = "订单地址纬度")
    private BigDecimal latitude;

    /**
     * 家属备注（如「老人听力不好，请大声沟通」）
     */
    @Schema(description = "家属备注（如「老人听力不好，请大声沟通」）")
    private String remark;

    /**
     * 订单状态：PENDING-待接单 / ACCEPTED-已接单 / IN_SERVICE-服务中 / COMPLETED-已完成 / REVIEWED-已评价 / CANCELLED-已取消
     */
    @Schema(description = "订单状态：PENDING-待接单 / ACCEPTED-已接单 / IN_SERVICE-服务中 / COMPLETED-已完成 / REVIEWED-已评价 / CANCELLED-已取消")
    private String status;

    /**
     * 服务费（元），一期不做在线支付，仅线上记账 + 线下结算
     */
    @Schema(description = "服务费（元），一期不做在线支付，仅线上记账 + 线下结算")
    private BigDecimal fee;

    /**
     * 实际结算金额（线下结算回填）
     */
    @Schema(description = "实际结算金额（线下结算回填）")
    private BigDecimal actualFee;

    /**
     * 结算状态：UNPAID-未结算 / SETTLED-已结算
     */
    @Schema(description = "结算状态：UNPAID-未结算 / SETTLED-已结算")
    private String paymentStatus;

    /**
     * 服务小结（**只记录过程，禁止出现诊断与用药建议**）
     */
    @Schema(description = "服务小结（**只记录过程，禁止出现诊断与用药建议**）")
    private String serviceSummary;

    /**
     * 服务现场/取药凭证照片 URL 数组
     */
    @Schema(description = "服务现场/取药凭证照片 URL 数组")
    private String servicePhotos;

    /**
     * 接单时间
     */
    @Schema(description = "接单时间")
    private LocalDateTime acceptTime;

    /**
     * 开始服务时间
     */
    @Schema(description = "开始服务时间")
    private LocalDateTime startTime;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    /**
     * 取消时间
     */
    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

    /**
     * 取消/纠纷原因
     */
    @Schema(description = "取消/纠纷原因")
    private String cancelReason;

    /**
     * 取消操作人用户 ID
     */
    @Schema(description = "取消操作人用户 ID")
    private Long cancelBy;

    /**
     * 是否被管理员强制处理：0-否 1-是
     */
    @Schema(description = "是否被管理员强制处理：0-否 1-是")
    private Integer arbitrateFlag;

    /**
     * 管理员纠纷处理结果说明
     */
    @Schema(description = "管理员纠纷处理结果说明")
    private String arbitrateResult;

    /**
     * 乐观锁版本号（**接单防超卖**，MyBatis-Plus @Version）
     */
    @Version
    @Schema(description = "乐观锁版本号（**接单防超卖**，MyBatis-Plus @Version）")
    private Integer version;

}
