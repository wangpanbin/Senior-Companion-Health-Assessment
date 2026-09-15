package org.company.nianglin.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类。
 *
 * <p>统一约定：</p>
 * <ul>
 *   <li>主键 {@code id}：数据库自增 BIGINT</li>
 *   <li>审计字段 {@code createTime / updateTime}：由 MetaObjectHandler 自动填充</li>
 *   <li>逻辑删除 {@code deleted}：0 未删除 / 1 已删除，MyBatis-Plus 全局配置已开启</li>
 * </ul>
 *
 * <p>⚠️ 实体类不得直接作为接口返回值，必须转换为 VO，避免敏感字段（密码、身份证号）外泄。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
public class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
