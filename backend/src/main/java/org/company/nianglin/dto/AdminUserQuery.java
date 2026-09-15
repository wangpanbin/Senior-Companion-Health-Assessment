package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.SysUser;

/**
 * 用户列表查询入参（{@code docs/api/08-admin.md} §4）。
 *
 * <p>{@code status} 与数据库存的是同一套枚举名（{@code NORMAL} / {@code DISABLED}），
 * 不做中文映射 —— 管理端筛选框的选项本身就来自接口，
 * 中文转换放在前端一次做完，避免「代码里两套中文」又对不上号。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户列表查询入参")
public class AdminUserQuery extends PageQuery<SysUser> {

    @Schema(description = "角色筛选：ELDER / FAMILY / COMPANION / ADMIN", example = "COMPANION")
    private String role;

    @Schema(description = "账号状态：NORMAL / DISABLED", example = "NORMAL")
    private String status;

    @Schema(description = "用户名 / 昵称 / 手机号搜索", example = "13800138000")
    private String keyword;

    @Schema(description = "注册时间起（yyyy-MM-dd）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "注册时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<SysUser> toMpPage() {
        return toPage(new Page<>());
    }
}
