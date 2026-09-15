package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.util.MaskUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 陪诊员资料（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §5（公开资料）。</p>
 *
 * <h3>⚠️ {@code id} 是「用户 ID」，不是 {@code companion_profile} 的主键</h3>
 *
 * <p>库里 {@code companion_profile.id} 是 601~630，而 {@code user_id} 是 301~330，
 * 两者并不相等（已实查确认）。业务上真正被引用的键是 <b>用户 ID</b> ——
 * {@code companion_order.companion_id}、评价、站内信全部指向 {@code sys_user.id}。
 * 所以本 VO 的 {@code id} 取的是 {@code userId}，与订单模块对得上；
 * 若哪天有人按 {@code companion_profile.id} 来查，会得到 2001，这正是期望的失败方式。</p>
 *
 * <h3>公开资料不返回什么</h3>
 *
 * <p>参照 {@code docs/api/02-elder-family.md} §5：<b>不返回</b>身份证号、联系电话、证件图片 URL。
 * 除此之外本 VO 还<b>不返回 {@code rejectReason}</b> —— 驳回原因是对申请人的私人反馈，
 * 通过 {@code GET /api/user/companion/application} 给本人看就够了，
 * 挂到任意登录用户都能访问的公开资料上属于信息泄露（文档的字段表里列了它，
 * 但字段表描述的是整个 VO 的容量，不是公开接口的实际输出）。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员资料")
public class CompanionProfileVO {

    @Schema(description = "陪诊员用户 ID（注意不是 companion_profile 主键）", example = "301")
    private Long id;

    @Schema(description = "脱敏真实姓名", example = "李*军")
    private String realName;

    @Schema(description = "服务区域", example = "海口市美兰区")
    private String serviceArea;

    @Schema(description = "可服务时段", example = "周一至周五 08:00-18:00")
    private String availableTime;

    @Schema(description = "资质状态枚举名", example = "APPROVED")
    private String auditStatus;

    @Schema(description = "资质状态中文", example = "已通过")
    private String auditStatusLabel;

    @Schema(description = "平均评分，两位小数字符串（避免 JS 浮点误差）", example = "4.80")
    private String score;

    @Schema(description = "累计已完成订单数", example = "37")
    private Integer orderCount;

    @Schema(description = "接单状态：AVAILABLE-可接单 / REST-休息中", example = "AVAILABLE")
    private String workStatus;

    @Schema(description = "接单状态中文", example = "可接单")
    private String workStatusLabel;

    /**
     * 公开资料：姓名脱敏，不含身份证、电话、驳回原因。
     */
    public static CompanionProfileVO ofPublic(CompanionProfile p) {
        if (p == null) {
            return null;
        }
        return base(p)
                .setRealName(MaskUtil.name(p.getRealName()));
    }

    /** 列表与公开详情共用的基础字段 */
    private static CompanionProfileVO base(CompanionProfile p) {
        return new CompanionProfileVO()
                .setId(p.getUserId())
                .setServiceArea(p.getServiceArea())
                .setAvailableTime(p.getAvailableTime())
                .setAuditStatus(p.getAuditStatus())
                .setAuditStatusLabel(AuditStatus.labelOf(p.getAuditStatus()))
                .setScore(scale2(p.getScore()))
                .setOrderCount(p.getOrderCount())
                .setWorkStatus(p.getWorkStatus())
                .setWorkStatusLabel(WorkStatus.labelOf(p.getWorkStatus()));
    }

    /** 评分统一保留两位小数；{@code null} 与「无评分」区分开，不返回 0.00 */
    private static String scale2(BigDecimal score) {
        return score == null ? null : score.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
