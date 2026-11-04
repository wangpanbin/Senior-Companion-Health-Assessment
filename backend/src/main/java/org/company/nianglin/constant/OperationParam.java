package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 可在线调整的运营参数（{@code sys_dict.dict_type = OPS_PARAM}）。
 *
 * <h3>为什么只收这几项</h3>
 *
 * <p>字典只能承载「运营确实想调、且调错也不会让系统结构性失效」的阈值。
 * 连接串、密钥、JWT、调度开关、权重配比等属于部署 / 结构配置，
 * 一旦进字典就会出现「谁能改、改错怎么办」两类无法审计的风险，因此不收。</p>
 *
 * <p>每个参数的默认值不写在这里：默认值仍来自 {@code application.yml}，
 * 由 {@code OperationParamServiceImpl} 注入后作为回落值，
 * 避免同一口径在 YAML 与 Java 里各存一份。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Getter
public enum OperationParam {

    MEDICATION_MISSED_THRESHOLD_MINUTES(
            "medication.missed-threshold-minutes", "漏服判定阈值", "分钟", 1, 1440,
            "服药计划时间超过该分钟数仍未确认即判定漏服"),

    URGE_COOLDOWN_MINUTES(
            "fulfillment.urge-cooldown-minutes", "催单最小间隔", "分钟", 1, 1440,
            "家属两次催单之间的最小间隔，防止把陪诊员刷到关接单"),

    EXPORT_MAX_ROWS(
            "export.max-rows", "单次导出条数上限", "行", 100, 50000,
            "单次导出的最大行数，超出时提示缩小筛选范围"),

    SMS_MISSED_DOSE_DAILY_CAP(
            "sms.frequency.missed-dose-daily-cap", "漏服短信单用户日上限", "条", 1, 100,
            "同一用户单日可收到的漏服短信上限，站内信不受此限"),
    ;

    /** 字典键（{@code sys_dict.dict_code}） */
    private final String code;

    /** 中文名 */
    private final String label;

    /** 单位；纯数值参数为空串 */
    private final String unit;

    /** 允许的最小值 */
    private final int min;

    /** 允许的最大值 */
    private final int max;

    /** 说明，写入字典 remark 与管理端列表 */
    private final String description;

    OperationParam(String code, String label, String unit, int min, int max, String description) {
        this.code = code;
        this.label = label;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.description = description;
    }

    /** 按字典键反查，非法值返回 {@code null} */
    public static OperationParam ofCode(String code) {
        if (code == null) {
            return null;
        }
        for (OperationParam param : values()) {
            if (param.code.equals(code)) {
                return param;
            }
        }
        return null;
    }
}
