package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.company.nianglin.entity.SysDict;
import org.company.nianglin.mapper.SysDictMapper;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ExamPrepItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 检查准备字典查询单测（V5 · B，docs/api/14-exam-prep.md §1）。
 *
 * <p>盯两件事：<b>映射正确</b>（dict_code/label/value/remark → VO 四字段）与
 * <b>查询口径</b>（只取 EXAM_PREP 类型、只取启用行、按 sort_no 排序）——
 * 口径错了前端会看到别的字典类型或已停用的过期条目。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("检查准备须知查询：映射与口径")
class ExamPrepServiceTest {

    @Mock
    private SysDictMapper sysDictMapper;

    private ExamPrepService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ExamPrepService(sysDictMapper);
    }

    @Test
    @DisplayName("映射：dict_code/label/value/remark → code/name/requirement/remark")
    void listShouldMapDictRowsToVo() {
        SysDict row = new SysDict();
        row.setDictCode("FASTING_BLOOD");
        row.setDictLabel("空腹血糖 / 静脉采血");
        row.setDictValue("空腹 8–12 小时");
        row.setRemark("……来源：……；就医须知转述，不构成医疗建议");
        given(sysDictMapper.selectList(any())).willReturn(List.of(row));

        List<ExamPrepItemVO> items = service.list();

        assertEquals(1, items.size());
        assertEquals("FASTING_BLOOD", items.get(0).getCode());
        assertEquals("空腹血糖 / 静脉采血", items.get(0).getName());
        assertEquals("空腹 8–12 小时", items.get(0).getRequirement());
        assertTrue(items.get(0).getRemark().contains("来源"), "remark 必须原样带出来源与免责");
    }

    @Test
    @DisplayName("口径：只取 EXAM_PREP 类型、只取启用行、按 sort_no 升序")
    void listShouldQueryExamPrepTypeEnabledSorted() {
        given(sysDictMapper.selectList(any())).willReturn(List.of());

        service.list();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(sysDictMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().replaceAll("\\s+", " ");
        assertTrue(sql.contains("dict_type"), "按类型过滤：" + sql);
        assertTrue(sql.contains("status"), "只取启用行：" + sql);
        assertTrue(sql.contains("ORDER BY"), "按 sort_no 排序：" + sql);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(ExamPrepService.DICT_TYPE),
                "类型必须是 EXAM_PREP");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(1), "只取 status=1");
    }

    @Test
    @DisplayName("空字典 → 空列表（不是 null，前端不用判空两套）")
    void listShouldReturnEmptyWhenNoRows() {
        given(sysDictMapper.selectList(any())).willReturn(List.of());

        assertTrue(service.list().isEmpty());
    }
}
