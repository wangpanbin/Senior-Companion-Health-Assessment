package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 老人健康时间线（V3.2 · FR-Timeline）。
 *
 * <h3>它是什么</h3>
 *
 * <p>把一位老人身上的四类<b>事实</b>—— 订单状态、陪诊打卡、用药确认、站内信提醒 ——
 * 按时间排成一条线，让家属能还原「某一天到底发生了什么」。</p>
 *
 * <h3>它不是什么</h3>
 *
 * <ul>
 *   <li><b>不是健康评价</b>：不出现任何结论式判断（合规红线-1）；</li>
 *   <li><b>不是聊天记录</b>：M13 订单聊天走独立的 {@code chat_message} 表，
 *       本时间线只读 {@code internal_message}，两者天然隔离；</li>
 *   <li><b>不是无限长的</b>：受区间上限（默认 30 天 / 最多 366 天）与分页限制。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Accessors(chain = true)
@Schema(description = "老人健康时间线")
public class HealthTimelineVO {

    @Schema(description = "老人档案 ID", example = "301")
    private Long elderId;

    @Schema(description = "老人姓名（脱敏）", example = "张*三")
    private String elderName;

    @Schema(description = "区间")
    private Range range;

    @Schema(description = "是否还有更多", example = "false")
    private Boolean hasMore;

    @Schema(description = "下一页游标；没有更多时为 null", example = "MjAyNi0xMC0yMFQxMDowMDowMHwxMjM=")
    private String nextCursor;

    @Schema(description = "是否因触及取数上限而被截断；为 true 时说明这段时间的记录没有全部返回",
            example = "false")
    private Boolean truncated;

    @Schema(description = "事件列表，按发生时间倒序；无数据时为空数组")
    private List<Item> items = new ArrayList<>();

    /** 区间信息 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "统计区间")
    public static class Range {

        @Schema(description = "区间开始日期", example = "2026-09-14")
        private LocalDate startDate;

        @Schema(description = "区间结束日期", example = "2026-10-13")
        private LocalDate endDate;

        @Schema(description = "区间天数", example = "30")
        private Integer days;
    }

    /** 一条时间线事件 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "时间线事件")
    public static class Item {

        @Schema(description = "事件唯一标识", example = "ORDER:1001:ACCEPTED")
        private String eventId;

        @Schema(description = "事件分类：ORDER / CHECKIN / MEDICATION / MESSAGE", example = "ORDER")
        private String category;

        @Schema(description = "分类中文名", example = "订单")
        private String categoryLabel;

        @Schema(description = "事件类型", example = "ACCEPTED")
        private String type;

        @Schema(description = "类型中文名", example = "已接单")
        private String typeLabel;

        @Schema(description = "发生时间", example = "2026-10-16 09:30:00")
        private LocalDateTime occurredAt;

        @Schema(description = "一句话描述", example = "陪诊员已接单")
        private String title;

        @Schema(description = "补充说明（不含医疗判断）", example = "北京协和医院 · 心内科")
        private String detail;

        @Schema(description = "是否异常打卡；非打卡类事件为 null", example = "false")
        private Boolean abnormal;

        @Schema(description = "关联订单 ID；无关联时为 null", example = "1001")
        private Long orderId;

        @Schema(description = "关联服药任务 ID；无关联时为 null", example = "60003")
        private Long medicationTaskId;

        @Schema(description = "跳转链接", example = "/family/order/1001")
        private String linkUrl;
    }
}
