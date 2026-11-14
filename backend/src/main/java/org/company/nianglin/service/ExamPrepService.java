package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.company.nianglin.entity.SysDict;
import org.company.nianglin.mapper.SysDictMapper;
import org.company.nianglin.vo.ExamPrepItemVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检查准备须知查询（V5 · B，docs/api/14-exam-prep.md §1）。
 *
 * <p>纯静态字典读取：内容由 {@code V12__exam_prep_dict.sql} 种子落库，
 * 逐条带来源与免责声明，<b>不由 LLM 生成</b>（红线-1：检查准备要求属医疗范畴，
 * 模型生成即医疗建议风险）。空结果返回空列表，不抛错、不回退到任何动态内容。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Service
@RequiredArgsConstructor
public class ExamPrepService {

    /** 字典类型：检查准备（与 V12 种子同口径） */
    public static final String DICT_TYPE = "EXAM_PREP";

    private final SysDictMapper sysDictMapper;

    /** 全部启用的检查准备条目，按 sort_no 升序 */
    public List<ExamPrepItemVO> list() {
        return sysDictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                        .eq(SysDict::getDictType, DICT_TYPE)
                        .eq(SysDict::getStatus, 1)
                        .orderByAsc(SysDict::getSortNo))
                .stream()
                .map(ExamPrepItemVO::of)
                .toList();
    }
}
