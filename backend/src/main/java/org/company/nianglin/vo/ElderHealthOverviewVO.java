package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 老人健康概览（V3.2 · FR-Overview）。
 *
 * <h3>只展示客观数据</h3>
 *
 * <p>本月就医次数、本月陪诊次数、用药完成率、漏服次数、最近一次陪诊、最近一次用药 ——
 * 全部来自真实业务表，不含任何医疗判断（迭代计划 §24 / §28）。</p>
 *
 * <h3>家属端与老人端共用同一份聚合</h3>
 *
 * <p>老人端看到的是本 VO 的<b>子集</b>（去掉费用、催单、履约风险、家属姓名等），
 * 而不是另算一套 —— 两套口径迟早会漂移（roadmap 决策 2.6 / 6.2）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Accessors(chain = true)
@Schema(description = "老人健康概览")
public class ElderHealthOverviewVO {

    @Schema(description = "老人基础信息")
    private Elder elder;

    @Schema(description = "统计区间")
    private HealthTimelineVO.Range range;

    @Schema(description = "就医统计")
    private Visit visit;

    @Schema(description = "用药统计")
    private Medication medication;

    @Schema(description = "待处理事项；无待办时为空数组")
    private List<HealthPendingItemVO> pendingItems = new ArrayList<>();

    @Schema(description = "时间线预览（最近若干条）")
    private List<HealthTimelineVO.Item> timelinePreview = new ArrayList<>();

    /** 老人基础信息 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "老人基础信息")
    public static class Elder {

        @Schema(description = "老人档案 ID", example = "301")
        private Long id;

        @Schema(description = "老人姓名（脱敏）", example = "张*三")
        private String name;

        @Schema(description = "性别", example = "MALE")
        private String gender;

        @Schema(description = "性别中文名", example = "男")
        private String genderLabel;

        @Schema(description = "年龄", example = "82")
        private Integer age;

        @Schema(description = "与当前家属的关系；老人端不返回", example = "儿子")
        private String relationLabel;
    }

    /** 就医统计 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "就医统计")
    public static class Visit {

        @Schema(description = "区间内已完成的陪诊次数", example = "2")
        private Integer orderCount;

        @Schema(description = "区间内服务过这位老人的陪诊员人数", example = "1")
        private Integer companionCount;

        @Schema(description = "最近一次已完成陪诊的就诊时间", example = "2026-10-16 09:30:00")
        private LocalDateTime lastVisitAt;
    }

    /** 用药统计 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "用药统计")
    public static class Medication {

        @Schema(description = "区间内任务总数", example = "30")
        private Integer total;

        @Schema(description = "已服数量", example = "27")
        private Integer taken;

        @Schema(description = "漏服数量（含漏服后补记）", example = "2")
        private Integer missed;

        @Schema(description = "待服数量", example = "1")
        private Integer pending;

        @Schema(description = "完成率，已服 / 总数；总数为 0 时返回 0.00%", example = "90.00%")
        private String completionRate;
    }
}
