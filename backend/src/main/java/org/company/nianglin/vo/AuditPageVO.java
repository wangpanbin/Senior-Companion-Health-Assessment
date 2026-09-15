package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资质申请分页响应（含各状态数量）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §1：响应里除了常规分页字段，
 * 还要求一个 {@code counts}，让管理端「三种状态及数量」一次拿全（M9 验收项）。</p>
 *
 * <h3>为什么不复用 {@code PageResult}</h3>
 *
 * <p>{@code counts} 是<b>整表口径</b>的统计，而不是当前页的口径。
 * 把它塞进通用的 {@code PageResult}，会让「分页元信息」这个通用结构
 * 多出一个只有审核列表才有的字段，其他十几个接口的分页响应里都会出现
 * 一个永远为 null 的 {@code counts}。为这一个场景单独定义响应结构更清楚。</p>
 *
 * <h3>counts 里三个 key 恒定存在</h3>
 *
 * <p>数量为 0 的状态也会出现（{@code "REJECTED": 0}），
 * 而不是从 map 里消失。前端标签栏是照着「待审核 / 已通过 / 已驳回」
 * 三个固定项渲染的，缺 key 会让角标显示成空白而不是 0。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "资质申请分页响应")
public class AuditPageVO {

    @Schema(description = "总记录数", example = "12")
    private Long total;

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private Long page;

    @Schema(description = "每页条数", example = "10")
    private Long size;

    @Schema(description = "总页数", example = "2")
    private Long pages;

    @Schema(description = "各审核状态的数量（不受分页与状态筛选影响，始终为整表口径）",
            example = "{\"PENDING\":5,\"APPROVED\":6,\"REJECTED\":1}")
    private Map<String, Long> counts = new LinkedHashMap<>();

    @Schema(description = "当前页数据")
    private List<AuditApplicationVO> records;

    public static AuditPageVO of(Long total, Long page, Long size, Long pages,
                                 Map<String, Long> counts, List<AuditApplicationVO> records) {
        return new AuditPageVO()
                .setTotal(total)
                .setPage(page)
                .setSize(size)
                .setPages(pages)
                .setCounts(counts)
                .setRecords(records);
    }
}
