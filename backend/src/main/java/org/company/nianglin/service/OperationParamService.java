package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.constant.OperationParam;
import org.company.nianglin.dto.OperationParamUpdateDTO;
import org.company.nianglin.vo.OperLogVO;
import org.company.nianglin.vo.OperationParamVO;

import java.util.List;

/**
 * 运营参数读写（{@code sys_dict.dict_type = OPS_PARAM}）。
 *
 * <p>读取端必须能在数据库不可用时回落 {@code application.yml} 默认值，
 * 因此本接口的读方法<b>不声明受检异常</b>，实现也不抛出运行时异常。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
public interface OperationParamService {

    /**
     * 读取整数参数；缓存命中不查库，任何失败都回落默认值。
     *
     * @param param 参数枚举
     * @return 当前生效值
     */
    int getInt(OperationParam param);

    /** 管理端列表：字典值、默认值、范围与更新时间合并展示 */
    List<OperationParamVO> list();

    /**
     * 修改参数并写操作日志。
     *
     * @param code 字典键
     * @param dto  新值与备注
     */
    void update(String code, OperationParamUpdateDTO dto);

    /** 参数变更留痕（按操作时间倒序） */
    PageResult<OperLogVO> logs(long page, long size);
}
