package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.ProductFeedback;

/**
 * ProductFeedback 数据访问接口。
 *
 * <p>产品反馈表（V6）。通用 CRUD 由 {@link BaseMapper} 提供。</p>
 *
 * <p>本接口目前<b>不声明任何自定义 SQL</b> —— 客户端「我的反馈」与管理端列表的
 * 全部筛选条件（状态 / 分类 / 关键词 / 时间区间）都能用
 * {@code LambdaQueryWrapper} 表达，没有必要下沉成 XML。
 * 关键词命中提交人姓名的那一段先查 {@code sys_user} 拿 id 集合再 IN，
 * 与 {@code AdminServiceImpl#complaintList} 同一手法。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Mapper
public interface ProductFeedbackMapper extends BaseMapper<ProductFeedback> {
}
