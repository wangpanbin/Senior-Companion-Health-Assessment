package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.Gender;
import org.company.nianglin.constant.MobilityLevel;
import org.company.nianglin.constant.RelationType;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

/**
 * 老人档案（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §6（列表）与 §8（详情）。</p>
 *
 * <h3>为什么列表和详情是两个工厂方法</h3>
 *
 * <p>两者的脱敏口径<b>不一样</b>，而且必须不一样：</p>
 *
 * <table border="1">
 *   <caption>脱敏口径对照</caption>
 *   <tr><th>字段</th><th>{@link #ofList} 列表</th><th>{@link #ofDetail} 详情</th></tr>
 *   <tr><td>姓名</td><td>{@code 张*海}</td><td>全名 {@code 张德海}</td></tr>
 *   <tr><td>身份证号</td><td>不返回</td><td>{@code 460101********0011}</td></tr>
 *   <tr><td>地址</td><td>不返回</td><td>门牌号打码</td></tr>
 *   <tr><td>病史 / 过敏史</td><td>不返回</td><td>返回</td></tr>
 * </table>
 *
 * <p>列表页一次可能展示十几个老人，姓名和手机号被旁边的人看到是常态；
 * 详情页是家属主动点开自己家人的档案，此时姓名全隐会让页面完全没法用
 * （「张*海」和「张*河」分不清）。所以不是「越严越好」，而是按场景给到刚刚够用的信息。</p>
 *
 * <p><b>身份证号必须由调用方在解密后立刻脱敏再传进来</b> —— 数据库里存的是 AES 密文，
 * 对密文做掩码毫无意义。把明文参数化（而不是把密钥塞进 VO 里）是为了让「明文」
 * 的生命周期止步于调用方那两行代码，不进入 VO 对象内部，也就不会被 toString / 日志带走。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "老人档案")
public class ElderVO {

    @Schema(description = "档案 ID", example = "401")
    private Long id;

    @Schema(description = "姓名（列表脱敏为「张*海」，详情为全名）", example = "张德海")
    private String name;

    @Schema(description = "性别枚举名", example = "MALE")
    private String gender;

    @Schema(description = "性别中文", example = "男")
    private String genderLabel;

    @Schema(description = "年龄（由出生日期实时计算，不落库）", example = "78")
    private Integer age;

    @Schema(description = "出生日期", example = "1948-03-12")
    private LocalDate birthDate;

    @Schema(description = "脱敏身份证号；列表不返回", example = "460101********0011")
    private String idCard;

    @Schema(description = "脱敏手机号", example = "139****2222")
    private String phone;

    @Schema(description = "常用地址（门牌号打码）；列表不返回",
            example = "海南省海口市美兰区人民大道***")
    private String address;

    @Schema(description = "紧急联系人姓名；列表不返回", example = "张四")
    private String emergencyContact;

    @Schema(description = "脱敏紧急联系人电话；列表不返回", example = "138****8888")
    private String emergencyPhone;

    @Schema(description = "病史备注（仅记录，不做诊断）；列表不返回",
            example = "高血压、2型糖尿病，长期服药")
    private String medicalHistory;

    @Schema(description = "过敏史备注（仅记录）；列表不返回", example = "青霉素过敏")
    private String allergyHistory;

    @Schema(description = "行动能力枚举名；列表不返回", example = "ASSIST")
    private String mobilityLevel;

    @Schema(description = "行动能力中文；列表不返回", example = "需搀扶")
    private String mobilityLevelLabel;

    @Schema(description = "常去医院", example = "海南省人民医院")
    private String favoriteHospital;

    @Schema(description = "绑定状态枚举名", example = "BOUND")
    private String bindStatus;

    @Schema(description = "绑定状态中文", example = "已绑定")
    private String bindStatusLabel;

    @Schema(description = "当前家属与该老人的关系中文；仅详情返回", example = "儿子")
    private String relation;

    @Schema(description = "建档时间", example = "2026-09-02 10:00:00")
    private LocalDateTime createTime;

    /* ------------------------------------------------------------------ */
    /* 工厂方法                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 列表项：姓名与手机号脱敏，身份证、地址、病史等敏感字段一律不返回。
     *
     * <p>未赋值的字段保持 {@code null}，配合全局 Jackson {@code non_null} 策略
     * 直接从 JSON 里消失（而不是以 {@code null} 出现）—— 前端不需要区分「没有权限看」
     * 和「本来就没填」。</p>
     */
    public static ElderVO ofList(ElderProfile e) {
        if (e == null) {
            return null;
        }
        return base(e)
                .setName(MaskUtil.name(e.getName()))
                .setPhone(MaskUtil.phone(e.getPhone()));
    }

    /**
     * 详情：姓名返回全名，身份证返回脱敏串，地址门牌打码，病史等完整返回。
     *
     * @param e             档案实体
     * @param maskedIdCard  <b>已脱敏</b>的身份证号（调用方负责解密后立即脱敏）；
     *                      传 {@code null} 表示该档案没有登记身份证
     */
    public static ElderVO ofDetail(ElderProfile e, String maskedIdCard) {
        if (e == null) {
            return null;
        }
        return base(e)
                .setName(e.getName())
                .setIdCard(maskedIdCard)
                .setAddress(MaskUtil.address(e.getAddress()))
                .setEmergencyPhone(MaskUtil.phone(e.getEmergencyPhone()))
                .setMedicalHistory(e.getMedicalHistory())
                .setAllergyHistory(e.getAllergyHistory())
                .setMobilityLevel(e.getMobilityLevel())
                .setMobilityLevelLabel(MobilityLevel.labelOf(e.getMobilityLevel()));
    }

    /** 列表与详情共用的基础字段 */
    private static ElderVO base(ElderProfile e) {
        return new ElderVO()
                .setId(e.getId())
                .setGender(e.getGender())
                .setGenderLabel(Gender.labelOf(e.getGender()))
                .setAge(ageOf(e.getBirthDate()))
                .setBirthDate(e.getBirthDate())
                .setEmergencyContact(e.getEmergencyContact())
                .setFavoriteHospital(e.getFavoriteHospital())
                .setBindStatus(e.getBindStatus())
                .setBindStatusLabel(labelOfBindStatus(e.getBindStatus()))
                .setCreateTime(e.getCreateTime());
    }

    /** 由出生日期算年龄；日期为空返回 {@code null} 而不是 0（0 岁是个错误答案，不是「不知道」） */
    private static Integer ageOf(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        int years = Period.between(birthDate, LocalDate.now()).getYears();
        return years < 0 ? null : years;
    }

    private static String labelOfBindStatus(String bindStatus) {
        BindStatus s = BindStatus.of(bindStatus);
        return s == null ? bindStatus : s.getLabel();
    }

    /** 供 Service 设置关系中文（列表场景不设） */
    public ElderVO withRelation(String relation) {
        return setRelation(RelationType.labelOf(relation));
    }
}
