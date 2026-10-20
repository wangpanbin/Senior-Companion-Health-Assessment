package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 健康时间线的事件分类（V3.2 · FR-Timeline）。
 *
 * <p>时间线把四类<b>事实</b>按时间排成一条线，分类只回答「这条记录属于哪一类」，
 * 不做任何健康判断（合规红线-1：不做诊断、不开药方）。</p>
 *
 * <table border="1">
 *   <caption>四类事件的来源</caption>
 *   <tr><th>分类</th><th>来源</th><th>说明</th></tr>
 *   <tr><td>{@code ORDER}</td><td>{@code companion_order} 的五个时间戳 + {@code order_review}</td>
 *       <td>下单 / 已接单 / 开始服务 / 服务完成 / 已取消 / 已评价</td></tr>
 *   <tr><td>{@code CHECKIN}</td><td>{@code order_checkin}</td>
 *       <td>陪诊执行的六个打卡节点；异常打卡带 {@code abnormal} 标记</td></tr>
 *   <tr><td>{@code MEDICATION}</td><td>{@code medication_task}</td>
 *       <td>已服（按确认时间）与漏服（按计划时间）</td></tr>
 *   <tr><td>{@code MESSAGE}</td><td>{@code internal_message}</td>
 *       <td>只取发给当前用户本人的进度与用药提醒；<b>订单聊天内容不进时间线</b></td></tr>
 * </table>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public enum HealthTimelineCategory {

    /** 陪诊订单的状态事件 */
    ORDER("订单"),

    /** 陪诊执行的打卡节点 */
    CHECKIN("陪诊打卡"),

    /** 用药任务的确认与漏服 */
    MEDICATION("用药"),

    /** 站内信提醒（进度 / 用药提醒） */
    MESSAGE("消息提醒");

    /** 中文展示名 */
    private final String label;

    HealthTimelineCategory(String label) {
        this.label = label;
    }

    /** 中文展示名 */
    public String getLabel() {
        return label;
    }

    /**
     * 按枚举名反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错。
     *
     * @param name 枚举名字面量，可为 {@code null}
     * @return 分类；非法值返回 {@code null}
     */
    public static HealthTimelineCategory of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }
}
