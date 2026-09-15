package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

/**
 * SysDict —— 对应表 {@code sys_dict}。
 *
 * <p>数据字典表</p>
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
@TableName("sys_dict")
public class SysDict extends BaseEntity {

    /**
     * 字典类型：HOSPITAL-医院 / DEPARTMENT-科室 / SERVICE_TYPE-陪诊服务类型 / COMPLAINT_TYPE-投诉类型
     */
    @Schema(description = "字典类型：HOSPITAL-医院 / DEPARTMENT-科室 / SERVICE_TYPE-陪诊服务类型 / COMPLAINT_TYPE-投诉类型")
    private String dictType;

    /**
     * 字典编码（类型内唯一）
     */
    @Schema(description = "字典编码（类型内唯一）")
    private String dictCode;

    /**
     * 显示文本
     */
    @Schema(description = "显示文本")
    private String dictLabel;

    /**
     * 附加值（如排序权重、附加说明）
     */
    @Schema(description = "附加值（如排序权重、附加说明）")
    private String dictValue;

    /**
     * 排序号，越小越靠前
     */
    @Schema(description = "排序号，越小越靠前")
    private Integer sortNo;

    /**
     * 状态：0-停用 1-启用
     */
    @Schema(description = "状态：0-停用 1-启用")
    private Integer status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
