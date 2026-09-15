package org.company.nianglin.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询入参基类。
 *
 * <p>各模块的查询 DTO 继承本类，即可自动获得 {@code page / size / sortField / sortOrder} 四个参数。</p>
 *
 * <p>约定：页码从 1 开始；{@code size} 上限 100，防止一次拉爆数据库。</p>
 *
 * @param <T> 实体类型，用于生成 MyBatis-Plus 的 {@link Page}
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "分页查询基类")
public class PageQuery<T> {

    @Schema(description = "页码，从 1 开始", example = "1", defaultValue = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Long page = 1L;

    @Schema(description = "每页条数，最大 100", example = "10", defaultValue = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Long size = 10L;

    @Schema(description = "排序字段（下划线命名，需在白名单内）", example = "create_time")
    private String sortField;

    @Schema(description = "排序方向：asc / desc", example = "desc", defaultValue = "desc")
    private String sortOrder = "desc";

    /** 转换为 MyBatis-Plus 分页对象 */
    public <E extends Page<T>> E toPage(E page) {
        page.setCurrent(this.page == null ? 1L : this.page);
        page.setSize(this.size == null ? 10L : this.size);
        return page;
    }

    /** 默认分页对象 */
    public Page<T> toPage() {
        return toPage(new Page<>());
    }

    /** 越界保护：把页码规范化到 [1, ...] */
    public long normalizedPage() {
        return (page == null || page < 1) ? 1L : page;
    }

    /** 越界保护：每页条数规范化到 [1, 100] */
    public long normalizedSize() {
        if (size == null || size < 1) {
            return 10L;
        }
        return Math.min(size, 100L);
    }
}
