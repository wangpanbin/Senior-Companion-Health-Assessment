package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.ElderProfile;

/**
 * 老人档案列表查询入参。
 *
 * <p>对应 {@code GET /api/user/elder}（{@code docs/api/02-elder-family.md} §6）。</p>
 *
 * <p><b>没有 {@code familyId} 参数</b>，这不是漏写：列表永远只返回当前登录家属名下的老人，
 * 「查谁的孩子」由令牌决定，不由前端决定。一旦加上这个参数，
 * 就必然有人忘记在 Service 里再校验一次归属，那就是一个越权读接口。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "老人档案列表查询入参")
public class ElderQuery extends PageQuery<ElderProfile> {

    @Schema(description = "按姓名模糊搜索", example = "张")
    @Size(max = 20, message = "搜索关键字不能超过 20 个字符")
    private String keyword;

    @Schema(description = "绑定状态：BOUND-已绑定 / UNBOUND-未绑定（见文档「已知限制」）",
            example = "BOUND")
    private String bindStatus;

    /** MyBatis-Plus 分页对象 */
    public Page<ElderProfile> toMpPage() {
        return toPage(new Page<>());
    }
}
