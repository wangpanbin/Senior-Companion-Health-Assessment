package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台运营驾驶舱的异常中心（V3.5 · T3）。
 *
 * <h3>为什么固定四个条目而不是「有异常才返回」</h3>
 *
 * <p>前端是四张卡片。缺一条时卡片会消失，管理员根本不知道「这一类现在没问题」
 * 还是「这一类没接上数据」—— 而这两种情况的处置动作完全不同。</p>
 *
 * <p>所以四项<b>恒定全部返回</b>，某项为 0 时就显示 0。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@Accessors(chain = true)
@Schema(description = "平台运营驾驶舱异常中心")
public class OperationsExceptionVO {

    @Schema(description = "四类异常的数量合计", example = "16")
    private Integer total;

    @Schema(description = "异常条目（恒定四项，顺序固定）")
    private List<OperationsExceptionItemVO> items = new ArrayList<>();
}
