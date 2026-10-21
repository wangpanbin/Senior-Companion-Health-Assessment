package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选陪诊员（V3.4 · FR-Service）。
 *
 * <h3>可解释性是硬要求</h3>
 *
 * <p>{@code reasons} 说明「为什么是他」，{@code mismatches} 说明「哪一维没命中」，
 * {@code notes} 说明「哪一维因为缺数据没算」。三者必须分开 ——
 * 把降级写进理由会让人误以为「距离不匹配」，
 * 而真相是「距离没法算」；把没命中写进理由则是把淘汰理由说成了推荐理由。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@Data
@Accessors(chain = true)
@Schema(description = "候选陪诊员（匹配结果 + 可解释性）")
public class CompanionMatchVO {

    @Schema(description = "陪诊员用户 ID", example = "301")
    private Long companionId;

    @Schema(description = "陪诊员姓名（**脱敏**）", example = "李*军")
    private String companionName;

    @Schema(description = "加权总分（0–100）", example = "86")
    private Integer totalScore;

    @Schema(description = "区域维度得分（0–100）", example = "100")
    private Integer regionScore;

    @Schema(description = "时间维度得分（0–100）", example = "100")
    private Integer timeScore;

    @Schema(description = "距离维度得分（0–100）；无坐标时为 0 并在 notes 说明", example = "0")
    private Integer distanceScore;

    @Schema(description = "服务能力维度得分（0–100）", example = "60")
    private Integer skillScore;

    @Schema(description = "评分维度得分（0–100）", example = "96")
    private Integer ratingScore;

    @Schema(description = "命中的推荐理由", example = "[\"同服务区域\",\"服务过该科室 12 单\"]")
    private List<String> reasons = new ArrayList<>();

    @Schema(description = "未命中说明（该维度得了 0 分的原因，如「跨城市」「超出服务半径」）",
            example = "[\"超出服务半径\"]")
    private List<String> mismatches = new ArrayList<>();

    @Schema(description = "降级说明（因数据缺失而没算出来的维度）",
            example = "[\"未填写坐标，距离维度记 0\"]")
    private List<String> notes = new ArrayList<>();
}
