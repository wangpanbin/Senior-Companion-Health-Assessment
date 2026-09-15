package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 注册结果。
 *
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@AllArgsConstructor
@Schema(description = "注册结果")
public class RegisterVO {

    @Schema(description = "新用户 ID", example = "10023")
    private Long userId;
}
