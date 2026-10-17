package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.FulfillmentRisk;

import java.util.List;
import java.util.Map;

/**
 * 履约风险中心顶部的四个计数（V3.1 · T6，spec §2.5）。
 *
 * <h3>为什么是四个 int 而不是一张 Map</h3>
 *
 * <p>四个字段是<b>接口契约</b>：前端四张卡片一一对应。Map 会让「某一档为 0 时
 * 这个键根本不存在」成为可能 —— 于是卡片上要么显示空、要么显示占位符，
 * 而「0 单正常等待」和「这一档没统计」是两件不同的事。</p>
 *
 * <p>原语 {@code int} 默认就是 0，配合 Jackson 的默认序列化<b>永不缺字段</b>，
 * 从类型上杜绝了「省略 0 值」这种返回形态。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "履约风险计数（只统计待接单订单）")
public class FulfillmentSummaryVO {

    @Schema(description = "需要人工介入的订单数", example = "1")
    private int manualRequired;

    @Schema(description = "履约风险较高的订单数", example = "3")
    private int urgent;

    @Schema(description = "等待时间较长的订单数", example = "5")
    private int warning;

    @Schema(description = "正常等待的订单数", example = "12")
    private int normal;

    /**
     * 由分组计数结果装配。
     *
     * <p>未匹配到枚举的取值（人工改库产生的脏数据）一律并入 {@code normal}：
     * 四档之和必须等于待接单总数，否则管理员看到的卡片会「对不上账」，
     * 而对不上账的看板没人敢用。</p>
     *
     * @param rows 每行形如 {@code {risk: URGENT, cnt: 3}}；允许传 {@code null}
     * @return 四档计数，缺档为 0
     */
    public static FulfillmentSummaryVO of(List<Map<String, Object>> rows) {
        FulfillmentSummaryVO vo = new FulfillmentSummaryVO();
        if (rows == null || rows.isEmpty()) {
            return vo;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            int cnt = intValue(row.get("cnt"));
            FulfillmentRisk risk = FulfillmentRisk.of(stringValue(row.get("risk")));
            if (risk == null) {
                // 脏值 / NULL 一律并入 normal（见方法注释：保持四档之和 = 待接单总数）
                vo.normal += cnt;
            } else if (risk == FulfillmentRisk.WARNING) {
                vo.warning += cnt;
            } else if (risk == FulfillmentRisk.URGENT) {
                vo.urgent += cnt;
            } else if (risk == FulfillmentRisk.MANUAL_REQUIRED) {
                vo.manualRequired += cnt;
            } else {
                vo.normal += cnt;
            }
        }
        return vo;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
