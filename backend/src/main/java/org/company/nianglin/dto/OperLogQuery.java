package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.AdminOperLog;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作日志查询入参（{@code docs/api/08-admin.md} §12）。
 *
 * <p>时间区间用 {@code LocalDateTime} 而不是 {@code LocalDate}：
 * 日志排查经常精确到秒（「10:00 前后那次封禁是谁做的」），
 * 按天筛会把当天几十条操作一起倒出来。全局 Jackson 已统一
 * {@code yyyy-MM-dd HH:mm:ss} 格式，前端直接传字符串即可。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "操作日志查询入参")
public class OperLogQuery extends PageQuery<AdminOperLog> {

    @Schema(description = "按操作人筛选", example = "10001")
    private Long operatorId;

    @Schema(description = "按操作类型筛选：AUDIT_COMPANION / DISABLE_USER / ENABLE_USER / "
            + "RESET_PASSWORD / ARBITRATE_ORDER / HANDLE_COMPLAINT / PUBLISH_NOTICE",
            example = "ARBITRATE_ORDER")
    private String operType;

    @Schema(description = "按目标类型筛选：USER / ORDER / COMPANION / COMPLAINT", example = "ORDER")
    private String targetType;

    @Schema(description = "按目标 ID 筛选", example = "1001")
    private Long targetId;

    @Schema(description = "操作时间起（yyyy-MM-dd HH:mm:ss）", example = "2026-09-21 00:00:00")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"})
    private LocalDateTime startTime;

    @Schema(description = "操作时间止（yyyy-MM-dd HH:mm:ss）", example = "2026-09-21 23:59:59")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"})
    private LocalDateTime endTime;

    /** MyBatis-Plus 分页对象 */
    public Page<AdminOperLog> toMpPage() {
        return toPage(new Page<>());
    }
}
