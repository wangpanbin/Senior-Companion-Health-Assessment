package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * FamilyElderRelation —— 对应表 {@code family_elder_relation}。
 *
 * <p>家属-老人绑定关系表</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("family_elder_relation")
public class FamilyElderRelation extends BaseEntity {

    /**
     * 家属用户 ID
     */
    @Schema(description = "家属用户 ID")
    private Long familyId;

    /**
     * 老人档案 ID
     */
    @Schema(description = "老人档案 ID")
    private Long elderId;

    /**
     * 与老人关系：SON-儿子 / DAUGHTER-女儿 / RELATIVE-亲属 / OTHER-其他
     */
    @Schema(description = "与老人关系：SON-儿子 / DAUGHTER-女儿 / RELATIVE-亲属 / OTHER-其他")
    private String relation;

    /**
     * 绑定方式：PHONE-按手机号 / INVITE_CODE-按邀请码
     */
    @Schema(description = "绑定方式：PHONE-按手机号 / INVITE_CODE-按邀请码")
    private String bindType;

    /**
     * 是否主要联系人：0-否 1-是
     */
    @TableField("is_default")
    @Schema(description = "是否主要联系人：0-否 1-是")
    private Integer isDefault;

    /**
     * 关系状态：BOUND-已绑定 / UNBOUND-已解绑
     */
    @Schema(description = "关系状态：BOUND-已绑定 / UNBOUND-已解绑")
    private String status;

    /**
     * 绑定时间
     */
    @Schema(description = "绑定时间")
    private LocalDateTime bindTime;

    /**
     * 解绑时间
     */
    @Schema(description = "解绑时间")
    private LocalDateTime unbindTime;

}
