package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.util.MaskUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 陪诊订单（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/03-order.md} §1（模型）/ §2（列表）/ §3（大厅）/ §4（详情）。</p>
 *
 * <h3>为什么是三套口径而不是一个「字段齐全」的对象</h3>
 *
 * <table border="1">
 *   <caption>三套口径对照</caption>
 *   <tr><th>字段</th><th>{@link #ofList} 我的订单</th><th>{@link #ofHall} 大厅</th><th>{@link #ofDetail} 详情</th></tr>
 *   <tr><td>老人 / 陪诊员姓名</td><td>{@code 张*海}</td><td>{@code 张*海}</td><td>全名</td></tr>
 *   <tr><td>医院地址</td><td>不返回</td><td><b>返回</b></td><td>返回</td></tr>
 *   <tr><td>备注 / 结算状态</td><td>不返回</td><td>不返回</td><td>返回</td></tr>
 *   <tr><td>服务小结 / 照片</td><td>不返回</td><td>不返回</td><td>返回</td></tr>
 * </table>
 *
 * <p><b>大厅为什么反而要返回地址</b>：陪诊员在接单前必须知道「去哪儿」，
 * 否则这个列表对他毫无决策价值。地址是医院地址而不是老人住址，
 * 本来就是公开信息，在这里放出来不构成隐私泄露。
 * 反观列表页：家属在手机上划自己的订单，姓名全显没有必要，
 * 一屏就能被旁边的人看走。</p>
 *
 * <h3>version 不对外返回</h3>
 *
 * <p>{@code version} 是乐观锁的内部字段（防超卖接单），对外暴露只会诱导客户端
 * 尝试自己传一个版本来「保证」接单成功 —— 而正确做法是让服务端读当前版本。
 * 数据库里的 {@code version} 变化由端到端脚本直接查库验证，不经过接口。</p>
 *
 * <p>{@code fee} 用字符串返回两位小数，与全局约定一致：JS 的 {@code Number}
 * 表示 128.00 会变成 128，前端再格式化就有出现浮点误差的机会，
 * 而对账场景下「差一分钱」是要被追问的。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊订单")
public class OrderVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long id;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "就诊老人档案 ID；列表不返回", example = "401")
    private Long elderId;

    @Schema(description = "老人姓名（列表/大厅脱敏为「张*海」，详情为全名）", example = "张德海")
    private String elderName;

    @Schema(description = "老人年龄", example = "88")
    private Integer elderAge;

    @Schema(description = "下单家属用户 ID；列表不返回", example = "101")
    private Long familyId;

    @Schema(description = "接单陪诊员用户 ID，未接单时字段消失；列表不返回", example = "301")
    private Long companionId;

    @Schema(description = "陪诊员姓名（列表/大厅脱敏，详情为全名）", example = "李建军")
    private String companionName;

    @Schema(description = "医院名称", example = "海南省人民医院")
    private String hospital;

    @Schema(description = "就诊科室", example = "心血管内科")
    private String department;

    @Schema(description = "就诊时间", example = "2026-09-20 09:30:00")
    private LocalDateTime visitTime;

    @Schema(description = "医院地址（文字地址）；仅大厅与详情返回",
            example = "海南省海口市秀英区秀华路19号 门诊大楼3楼")
    private String address;

    @Schema(description = "家属备注；仅详情返回", example = "老人听力不好，请大声沟通")
    private String remark;

    @Schema(description = "订单状态枚举名", example = "ACCEPTED")
    private String status;

    @Schema(description = "订单状态中文", example = "已接单")
    private String statusLabel;

    @Schema(description = "服务费（两位小数字符串）", example = "128.00")
    private String fee;

    @Schema(description = "实际结算金额；仅详情返回", example = "128.00")
    private BigDecimal actualFee;

    @Schema(description = "结算状态；仅详情返回", example = "UNPAID")
    private String paymentStatus;

    @Schema(description = "结算状态中文；仅详情返回", example = "未结算")
    private String paymentStatusLabel;

    @Schema(description = "服务小结；仅详情返回", example = "09:10 到达医院，全程陪同完成就诊")
    private String serviceSummary;

    @Schema(description = "服务现场 / 取药凭证照片；仅详情返回")
    private List<String> servicePhotos;

    @Schema(description = "下单时间", example = "2026-09-15 16:40:00")
    private LocalDateTime createTime;

    @Schema(description = "接单时间；仅详情返回", example = "2026-09-15 17:02:11")
    private LocalDateTime acceptTime;

    @Schema(description = "开始服务时间；仅详情返回", example = "2026-09-20 09:10:00")
    private LocalDateTime startTime;

    @Schema(description = "完成时间；仅详情返回", example = "2026-09-20 12:05:00")
    private LocalDateTime finishTime;

    @Schema(description = "取消时间；仅详情返回", example = "2026-09-16 08:00:00")
    private LocalDateTime cancelTime;

    @Schema(description = "取消 / 纠纷原因；仅详情返回", example = "老人临时身体不适，改天再去")
    private String cancelReason;

    /* ------------------------------------------------------------------ */
    /* 工厂方法                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 我的订单列表项。
     *
     * @param elderName     老人姓名原文，本方法内部脱敏（传 {@code null} 表示档案已不存在）
     * @param companionName 陪诊员姓名原文，未接单传 {@code null}
     */
    public static OrderVO ofList(CompanionOrder o, String elderName, Integer elderAge, String companionName) {
        if (o == null) {
            return null;
        }
        return base(o)
                .setElderName(MaskUtil.name(elderName))
                .setElderAge(elderAge)
                .setCompanionName(MaskUtil.name(companionName));
    }

    /**
     * 订单大厅列表项：与 {@link #ofList} 唯一的差别是<b>多返回地址</b>。
     *
     * <p>陪诊员必须先知道去哪儿才能决定接不接这一单。</p>
     */
    public static OrderVO ofHall(CompanionOrder o, String elderName, Integer elderAge, String companionName) {
        return ofList(o, elderName, elderAge, companionName).setAddress(o.getAddress());
    }

    /**
     * 订单详情：姓名全显，补齐备注、结算状态、服务记录与各节点时间。
     *
     * <p>访问者已经过 {@code OrderService#requireInvolved} 判定为该订单的相关方，
     * 此时再对姓名做脱敏会让页面变成「张*海 的订单」——该看的人看不到该看的信息。</p>
     *
     * @param servicePhotos 已解析的图片 URL 列表（JSON 列的解析由调用方完成，失败传 {@code null}）
     */
    public static OrderVO ofDetail(CompanionOrder o, String elderName, Integer elderAge,
                                   String companionName, List<String> servicePhotos) {
        if (o == null) {
            return null;
        }
        return base(o)
                .setElderId(o.getElderId())
                .setElderName(elderName)
                .setElderAge(elderAge)
                .setFamilyId(o.getFamilyId())
                .setCompanionId(o.getCompanionId())
                .setCompanionName(companionName)
                .setAddress(o.getAddress())
                .setRemark(o.getRemark())
                .setActualFee(o.getActualFee())
                .setPaymentStatus(o.getPaymentStatus())
                .setPaymentStatusLabel(PaymentStatus.labelOf(o.getPaymentStatus()))
                .setServiceSummary(o.getServiceSummary())
                .setServicePhotos(servicePhotos)
                .setAcceptTime(o.getAcceptTime())
                .setStartTime(o.getStartTime())
                .setFinishTime(o.getFinishTime())
                .setCancelTime(o.getCancelTime())
                .setCancelReason(o.getCancelReason());
    }

    /** 三套口径共用的基础字段 */
    private static OrderVO base(CompanionOrder o) {
        return new OrderVO()
                .setId(o.getId())
                .setOrderNo(o.getOrderNo())
                .setHospital(o.getHospital())
                .setDepartment(o.getDepartment())
                .setVisitTime(o.getVisitTime())
                .setStatus(o.getStatus())
                .setStatusLabel(OrderStatus.labelOf(o.getStatus()))
                .setFee(money(o.getFee()))
                .setCreateTime(o.getCreateTime());
    }

    /** 金额一律两位小数字符串；空值保持 {@code null}（配合 non_null 直接消失） */
    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
