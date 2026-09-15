package org.company.nianglin.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应结构。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": {
 *     "total": 137,
 *     "page": 1,
 *     "size": 10,
 *     "pages": 14,
 *     "records": [ ... ]
 *   }
 * }
 * </pre>
 *
 * @param <T> 列表元素类型
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "分页响应结构")
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "总记录数", example = "137")
    private Long total;

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private Long page;

    @Schema(description = "每页条数", example = "10")
    private Long size;

    @Schema(description = "总页数", example = "14")
    private Long pages;

    @Schema(description = "当前页数据")
    private List<T> records;

    public PageResult() {
    }

    public PageResult(Long total, Long page, Long size, Long pages, List<T> records) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.pages = pages;
        this.records = records;
    }

    /** 空分页结果 */
    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(0L, page, size, 0L, Collections.emptyList());
    }

    /** 由 MyBatis-Plus 的 IPage 直接转换 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), page.getRecords());
    }

    /** 由 IPage 转换并同步做实体 → VO 映射 */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        List<T> list = page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), list);
    }

    /** 包装成统一响应 */
    public Result<PageResult<T>> toResult() {
        return Result.success(this);
    }
}
