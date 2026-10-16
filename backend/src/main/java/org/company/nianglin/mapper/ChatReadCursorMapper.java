package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.ChatReadCursor;

/**
 * ChatReadCursor 数据访问接口。
 *
 * <p>订单聊天已读游标表（ADR-0011）</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/ChatReadCursorMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface ChatReadCursorMapper extends BaseMapper<ChatReadCursor> {
}
