package org.company.nianglin.util;

import org.company.nianglin.constant.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessageTemplateUtil} 文案模板回归测试。
 *
 * <p>重点覆盖 {@code ORDER_PROGRESS} 的<b>可选备注</b>分支：这条规则与其余模板相反
 * （其余模板缺失占位符渲染成 {@code —}），改动时最容易被打回原形，
 * 而症状只会在真实打卡后出现在家属的消息列表里，靠人眼回归很容易漏。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@DisplayName("M8 站内信文案模板")
class MessageTemplateUtilTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new HashMap<>(8);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("打卡带备注：节点名与备注都要出现在正文里")
    void orderProgressWithRemarkShouldContainBoth() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_PROGRESS,
                params("nodeLabel", "到院", "remark", "已在门诊三楼候诊", "orderNo", "NL20260916000001"));

        assertEquals("陪诊员已完成「到院」打卡：已在门诊三楼候诊（订单 NL20260916000001）。", content);
    }

    @Test
    @DisplayName("打卡不带备注：不能出现破折号占位")
    void orderProgressWithoutRemarkShouldNotRenderDash() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_PROGRESS,
                params("nodeLabel", "出发", "orderNo", "NL20260916000001"));

        assertEquals("陪诊员已完成「出发」打卡（订单 NL20260916000001）。", content);
        assertFalse(content.contains("—"),
                "备注缺失时渲染成「出发：—（订单 …）」等于把破折号当正文： " + content);
    }

    @Test
    @DisplayName("打卡备注是空白串：与缺失同样处理")
    void orderProgressWithBlankRemarkShouldFallBack() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_PROGRESS,
                params("nodeLabel", "取药", "remark", "   ", "orderNo", "NL20260916000002"));

        assertEquals("陪诊员已完成「取药」打卡（订单 NL20260916000002）。", content);
    }

    @Test
    @DisplayName("其余模板保持原规则：缺失占位符渲染成 —")
    void otherTemplatesShouldKeepDashPlaceholder() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_ACCEPTED,
                params("orderNo", "NL20260916000001"));

        assertTrue(content.contains("—"),
                "ORDER_ACCEPTED 的陪诊员姓名缺失时仍应显示占位符，而不是拼出一个空洞： " + content);
    }

    @Test
    @DisplayName("仲裁完成：正文必须携带仲裁说明（M9 纠纷处理承诺「写明仲裁决定与依据」）")
    void orderCompletedWithArbitrateReasonShouldContainIt() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_COMPLETED,
                params("orderNo", "NL20260916000001", "reason", "双方各执一词，平台裁定服务已实际完成"));

        assertTrue(content.contains("仲裁说明：双方各执一词，平台裁定服务已实际完成"),
                "被强制完成的一方看不到仲裁依据： " + content);
        assertTrue(content.contains("NL20260916000001"));
    }

    @Test
    @DisplayName("正常完成：不带 reason 时保持原通用文案（欢迎评价）")
    void orderCompletedWithoutReasonShouldKeepOriginalWording() {
        String content = MessageTemplateUtil.content(MessageType.ORDER_COMPLETED,
                params("orderNo", "NL20260916000001"));

        assertEquals("订单 NL20260916000001 已完成，感谢您的信任，欢迎评价。", content);
        assertFalse(content.contains("仲裁"),
                "正常完成的通知不该出现「仲裁」字样： " + content);
    }

    @Test
    @DisplayName("标题按类型固定，不带占位符")
    void titleShouldBeFixedPerType() {
        assertEquals("陪诊进度更新", MessageTemplateUtil.title(MessageType.ORDER_PROGRESS));
        assertEquals("服务已完成", MessageTemplateUtil.title(MessageType.ORDER_COMPLETED));
    }
}
