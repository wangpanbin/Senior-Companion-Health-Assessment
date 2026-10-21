package org.company.nianglin.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助手<b>兜底路径</b>的输出护栏（V3.7 收口评审发现）。
 *
 * <p>接口契约 {@code docs/api/13-assistant.md} 写明：<b>本地兜底路径同样要过这道护栏</b> ——
 * 否则「Python 挂了就没人管」恰好成了绕过护栏的路径。</p>
 *
 * <p>此前 Java 侧只做了医疗词表的整段替换，<b>漏了派单措辞的词语级纠正</b>。
 * Python 服务不可用时，兜底答案直接来自 FAQ 文件 ——
 * 一旦 FAQ 答案里混进「已指派 / 已分配 / 自动派单」，
 * Java 侧会原样吐给家属，而这正是 ADR-0016 决策 4.4 明令禁止的表述。</p>
 */
class AssistantServiceImplGuardTest {

    @Test
    @DisplayName("兜底答案里的派单措辞 → 词语级纠正并追加免责句")
    void correctAssignmentWordingShouldReplaceAndAppendDisclaimer() {
        String corrected = AssistantServiceImpl.correctAssignmentWording(
                "根据匹配结果，已指派李建军为您服务，评分 4.8。");

        assertFalse(corrected.contains("已指派"), "派单措辞不该残留：" + corrected);
        assertTrue(corrected.contains("李建军"), "其余内容要保留：" + corrected);
        assertTrue(corrected.contains("4.8"), "其余内容要保留：" + corrected);
        assertTrue(corrected.contains("最终由陪诊员本人确认接单"), "免责句要补上：" + corrected);
    }

    @Test
    @DisplayName("「已分配」也要纠正")
    void correctAssignmentWordingShouldFixAllocated() {
        String corrected = AssistantServiceImpl.correctAssignmentWording("系统已分配陪诊员。");

        assertFalse(corrected.contains("已分配"), "派单措辞不该残留：" + corrected);
        assertTrue(corrected.contains("推荐"), "纠正后应说「推荐」：" + corrected);
    }

    @Test
    @DisplayName("「自动派单」纠正成「自动推荐」")
    void correctAssignmentWordingShouldFixAutoDispatch() {
        String corrected = AssistantServiceImpl.correctAssignmentWording("系统自动派单了。");

        assertFalse(corrected.contains("自动派单"), "派单措辞不该残留：" + corrected);
        assertTrue(corrected.contains("自动推荐"), "纠正后应为「自动推荐」：" + corrected);
    }

    @Test
    @DisplayName("没有派单措辞 → 原样返回，不追加免责句")
    void correctAssignmentWordingShouldKeepCleanText() {
        String text = "在「家属端 → 陪诊订单」里下单即可。";

        assertEquals(text, AssistantServiceImpl.correctAssignmentWording(text));
    }

    @Test
    @DisplayName("空文本 → 原样返回")
    void correctAssignmentWordingShouldHandleEmptyText() {
        assertEquals("", AssistantServiceImpl.correctAssignmentWording(""));
    }
}
