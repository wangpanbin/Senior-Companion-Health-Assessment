package org.company.nianglin.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一响应结构契约测试。
 *
 * <p>对应 plan.md · M0 验收标准：任意接口返回体必须符合
 * {@code { code, message, data }} 结构。</p>
 *
 * @author 银龄伴诊团队
 */
@DisplayName("统一响应结构")
class ResultTest {

    @Test
    @DisplayName("成功响应：code=200，message=操作成功")
    void successShouldCarry200() {
        Result<String> result = Result.success("hello");
        assertEquals(200, result.getCode().intValue());
        assertEquals("操作成功", result.getMessage());
        assertEquals("hello", result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("成功但无数据：data 为 null")
    void successWithoutData() {
        Result<Void> result = Result.success();
        assertEquals(200, result.getCode().intValue());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("业务失败：code 与 message 来自 ResultCode")
    void failShouldCarryBusinessCode() {
        Result<Void> result = Result.fail(ResultCode.ORDER_STATUS_ILLEGAL);
        assertEquals(3002, result.getCode().intValue());
        assertEquals("当前订单状态不允许该操作", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("业务失败：允许覆盖默认提示")
    void failShouldAllowCustomMessage() {
        Result<Void> result = Result.fail(ResultCode.ORDER_ALREADY_TAKEN, "该订单已被张三接走");
        assertEquals(3003, result.getCode().intValue());
        assertEquals("该订单已被张三接走", result.getMessage());
    }

    @Test
    @DisplayName("分页结构：total / page / size / pages / records 齐全")
    void pageResultContract() {
        PageResult<String> page = new PageResult<>(137L, 2L, 10L, 14L, List.of("a", "b"));
        Result<PageResult<String>> result = page.toResult();

        assertEquals(200, result.getCode().intValue());
        PageResult<String> data = result.getData();
        assertNotNull(data);
        assertEquals(137L, data.getTotal().longValue());
        assertEquals(2L, data.getPage().longValue());
        assertEquals(10L, data.getSize().longValue());
        assertEquals(14L, data.getPages().longValue());
        assertEquals(2, data.getRecords().size());
    }

    @Test
    @DisplayName("空分页：total=0，records 为空集合而非 null")
    void emptyPageShouldNotReturnNullRecords() {
        PageResult<String> page = PageResult.empty(1L, 10L);
        assertEquals(0L, page.getTotal().longValue());
        assertEquals(0L, page.getPages().longValue());
        assertNotNull(page.getRecords());
        assertEquals(0, page.getRecords().size());
    }

    @Test
    @DisplayName("分页参数越界保护：page<1 归 1，size>100 截断到 100")
    void pageQueryShouldClampValues() {
        PageQuery<Object> query = new PageQuery<>();
        query.setPage(-5L);
        query.setSize(9999L);
        assertEquals(1L, query.normalizedPage());
        assertEquals(100L, query.normalizedSize());

        query.setPage(null);
        query.setSize(null);
        assertEquals(1L, query.normalizedPage());
        assertEquals(10L, query.normalizedSize());
    }
}
