package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 管理端订单列表项（{@code docs/api/08-admin.md} §8）。
 *
 * <p>在 {@link OrderVO} 的基础上追加 {@code hasComplaint} 与 {@code complaintId}
 * 两个字段 —— 文档 §8 的要求是「{@code records} 为 {@code OrderVO}，
 * 额外包含 {@code hasComplaint} 与 {@code complaintId}」，用继承表达最直接。</p>
 *
 * <h3>为什么这两个字段对管理端是必需的</h3>
 *
 * <p>管理员处理纠纷的入口是「有投诉的订单」。如果列表不标出来，
 * 管理员必须先在投诉列表里看到订单号，再回到订单列表搜索 ——
 * 两个列表之间的关联只存在于管理员的记忆里。{@code hasComplaint}
 * 让「哪些单有纠纷」在列表上直接可见，{@code complaintId} 则省掉了
 * 「再查一次投诉详情」的往返。</p>
 *
 * <h3>姓名沿用户外列表口径脱敏</h3>
 *
 * <p>管理端订单列表一屏几十行，姓名全显没有决策价值；
 * 需要看具体是谁时进详情即可（详情走 {@code ofDetail}，含全名与联系方式）。
 * 这一取舍与用户端列表一致，也是合规红线里「按场景脱敏」的落点。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(description = "管理端订单列表项")
public class AdminOrderVO extends OrderVO {

    @Schema(description = "该订单是否存在投诉", example = "true")
    private Boolean hasComplaint;

    @Schema(description = "该订单最新一条投诉的 ID；无投诉时不返回", example = "3001")
    private Long complaintId;

    /**
     * 由用户端列表项补齐纠纷信息。
     *
     * <p>继承关系使得 {@code setXxx} 返回 {@link OrderVO}，因此不能继续链式调用
     * 设完兄弟字段；这里显式转换后返回，调用方拿到的才是 {@code AdminOrderVO}。</p>
     *
     * @param base         已经装配好的用户端列表项
     * @param complaintId  最新投诉 ID，无投诉传 {@code null}
     */
    public static AdminOrderVO of(OrderVO base, Long complaintId) {
        if (base == null) {
            return null;
        }
        AdminOrderVO vo = new AdminOrderVO();
        vo.setId(base.getId());
        vo.setOrderNo(base.getOrderNo());
        vo.setElderId(base.getElderId());
        vo.setElderName(base.getElderName());
        vo.setElderAge(base.getElderAge());
        vo.setFamilyId(base.getFamilyId());
        vo.setCompanionId(base.getCompanionId());
        vo.setCompanionName(base.getCompanionName());
        vo.setHospital(base.getHospital());
        vo.setDepartment(base.getDepartment());
        vo.setVisitTime(base.getVisitTime());
        vo.setAddress(base.getAddress());
        vo.setRemark(base.getRemark());
        vo.setStatus(base.getStatus());
        vo.setStatusLabel(base.getStatusLabel());
        vo.setFee(base.getFee());
        vo.setActualFee(base.getActualFee());
        vo.setPaymentStatus(base.getPaymentStatus());
        vo.setPaymentStatusLabel(base.getPaymentStatusLabel());
        vo.setServiceSummary(base.getServiceSummary());
        vo.setServicePhotos(base.getServicePhotos());
        vo.setCreateTime(base.getCreateTime());
        vo.setAcceptTime(base.getAcceptTime());
        vo.setStartTime(base.getStartTime());
        vo.setFinishTime(base.getFinishTime());
        vo.setCancelTime(base.getCancelTime());
        vo.setCancelReason(base.getCancelReason());
        vo.setHasComplaint(complaintId != null);
        // 全局 Jackson 的 non_null 策略会让 null 字段自动消失，
        // 因此这里赋 null 就是「不返回」，不需要额外处理
        vo.setComplaintId(complaintId);
        return vo;
    }

    /** 无投诉时的快捷构造 */
    public static AdminOrderVO of(OrderVO base) {
        return of(base, null);
    }
}
