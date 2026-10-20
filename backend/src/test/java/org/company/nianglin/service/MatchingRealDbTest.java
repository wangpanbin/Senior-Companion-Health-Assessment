package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.vo.CompanionMatchVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.4 智能调度的<b>真库</b>一致性测试（T8 · 真库层）。
 *
 * <h3>为什么单测之外还要真库这一层</h3>
 *
 * <p>Mockito 单测锁的是「Service 拿到这些数据之后怎么算」，
 * 而 <b>V7 迁移有没有真的把区域码回填进去</b>只有真跑一遍才知道：
 * backfill 是 {@code UPDATE ... WHERE service_area LIKE '%美兰区%'}，
 * 写错一个字就是「整列 NULL」——而单测根本碰不到这条 SQL。</p>
 *
 * <p>所以这里的断言方式很朴素也很硬：<b>把库里的真实数据读出来，
 * 逐条核对它是否落在「8 个区县 → 8 个区域码」的映射表里</b>。</p>
 *
 * <h3>「区域码不是任意字符串」</h3>
 *
 * <p>最要防的不是「空」，而是「填了个看起来像区域码的乱码」——
 * 比如把中文区县名直接塞进 {@code service_region_code}。
 * 所以本类断言：所有非空的区域码都<b>必须</b>落在允许集合内。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@SpringBootTest
@DisplayName("V3.4 真库一致性：V7 回填的区域码 + 候选推荐的可解释性")
class MatchingRealDbTest {

    /** 允许出现的区域码全集（与 backend/sql/tools/region_code_mapping.json 一一对应） */
    private static final Set<String> ALLOWED_CODES = Set.of(
            "HAIKOU_MEILAN", "HAIKOU_LONGHUA", "HAIKOU_XIUYING", "HAIKOU_QONGSHAN",
            "SANYA_JIYANG", "SANYA_TIANYA", "DANZHOU_NADA", "QIONGHAI_JIJI");

    /** 区县文本 → 区域码（与 V7 迁移里的 backfill CASE 同口径） */
    private static final Map<String, String> DISTRICT_TO_CODE = Map.of(
            "美兰区", "HAIKOU_MEILAN",
            "龙华区", "HAIKOU_LONGHUA",
            "秀英区", "HAIKOU_XIUYING",
            "琼山区", "HAIKOU_QONGSHAN",
            "吉阳区", "SANYA_JIYANG",
            "天涯区", "SANYA_TIANYA",
            "那大镇", "DANZHOU_NADA",
            "嘉积镇", "QIONGHAI_JIJI");

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private CompanionProfileMapper profileMapper;

    @Autowired
    private CompanionOrderMapper orderMapper;

    @BeforeEach
    void loginAsFamily() {
        LoginUser loginUser = new LoginUser(101L, "family001", RoleConstants.FAMILY, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1. V7 迁移生效                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("V7 回填：陪诊员的 service_region_code 非空，且取值都在允许集合内")
    void companionRegionCodesShouldBeBackfilled() {
        List<CompanionProfile> profiles = profileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery());

        assertFalse(profiles.isEmpty(), "前提失效：库里没有陪诊员");

        long filled = 0L;
        for (CompanionProfile profile : profiles) {
            String code = profile.getServiceRegionCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            filled++;
            assertTrue(ALLOWED_CODES.contains(code),
                    "陪诊员 " + profile.getUserId() + " 的区域码不在允许集合内：" + code);
        }

        assertTrue(filled > 0, "V7 的 backfill 没生效：陪诊员区域码一个都没填上");
    }

    @Test
    @DisplayName("V7 回填：订单的 service_region_code 非空，且取值都在允许集合内")
    void orderRegionCodesShouldBeBackfilled() {
        List<CompanionOrder> orders = orderMapper.selectList(
                Wrappers.<CompanionOrder>lambdaQuery());

        assertFalse(orders.isEmpty(), "前提失效：库里没有订单");

        long filled = 0L;
        for (CompanionOrder order : orders) {
            String code = order.getServiceRegionCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            filled++;
            assertTrue(ALLOWED_CODES.contains(code),
                    "订单 " + order.getId() + " 的区域码不在允许集合内：" + code);
        }

        assertTrue(filled > 0, "V7 的 backfill 没生效：订单区域码一个都没填上");
    }

    @Test
    @DisplayName("V7 回填：可映射的陪诊员全部拿到了区域码（不允许「只填了一半」）")
    void everyMappableCompanionShouldHaveRegionCode() {
        List<CompanionProfile> profiles = profileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery());

        int mappable = 0;
        for (CompanionProfile profile : profiles) {
            if (!mentionsDistrict(profile.getServiceArea())) {
                continue;
            }
            mappable++;
            assertNotNull(profile.getServiceRegionCode(),
                    "陪诊员 " + profile.getUserId() + " 的服务区域「" + profile.getServiceArea()
                            + "」明明能映射，却没有区域码");
        }

        assertTrue(mappable > 0, "前提失效：库里没有带区县名的服务区域");
    }

    @Test
    @DisplayName("映射不上的留空，绝不猜：服务区域只有「海口市」这类没有区名的文本 → 区域码必须为空")
    void unmappableTextShouldStayNull() {
        List<CompanionProfile> profiles = profileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery());

        for (CompanionProfile profile : profiles) {
            String area = profile.getServiceArea();
            if (area == null || area.isBlank() || mentionsDistrict(area)) {
                continue;
            }
            assertNull(profile.getServiceRegionCode(),
                    "陪诊员 " + profile.getUserId() + " 的服务区域「" + area
                            + "」映射不上区县，区域码必须留空，不能猜");
        }
    }

    /* ================================================================== */
    /* 2. 区域码与自由文本一致                                              */
    /* ================================================================== */

    @Test
    @DisplayName("区域码与自由文本一致：服务区域写「美兰区」→ 区域码必须是 HAIKOU_MEILAN")
    void regionCodeShouldMatchFreeText() {
        List<CompanionProfile> profiles = profileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery());

        int checked = 0;
        for (CompanionProfile profile : profiles) {
            String area = profile.getServiceArea();
            if (area == null || area.isBlank()) {
                continue;
            }
            for (Map.Entry<String, String> entry : DISTRICT_TO_CODE.entrySet()) {
                if (!area.contains(entry.getKey())) {
                    continue;
                }
                assertEquals(entry.getValue(), profile.getServiceRegionCode(),
                        "陪诊员 " + profile.getUserId() + " 的服务区域「" + area
                                + "」与区域码对不上");
                checked++;
            }
        }

        assertTrue(checked > 0, "前提失效：库里没有带区县名的服务区域，映射无从校验");
    }

    @Test
    @DisplayName("区域码与自由文本一致：订单地址里的区县名与区域码对得上")
    void orderRegionCodeShouldMatchAddressText() {
        List<CompanionOrder> orders = orderMapper.selectList(
                Wrappers.<CompanionOrder>lambdaQuery());

        int checked = 0;
        for (CompanionOrder order : orders) {
            String address = order.getAddress();
            if (address == null || address.isBlank()) {
                continue;
            }
            for (Map.Entry<String, String> entry : DISTRICT_TO_CODE.entrySet()) {
                if (!address.contains(entry.getKey())) {
                    continue;
                }
                assertEquals(entry.getValue(), order.getServiceRegionCode(),
                        "订单 " + order.getId() + " 的地址「" + address + "」与区域码对不上");
                checked++;
            }
        }

        assertTrue(checked > 0, "前提失效：库里没有带区县名的订单地址，映射无从校验");
    }

    /* ================================================================== */
    /* 3. 匹配候选（真库数据上跑一遍）                                     */
    /* ================================================================== */

    @Test
    @DisplayName("候选推荐：每条候选都有分数和至少一条理由（不允许出现「有分数没理由」）")
    void everyCandidateShouldCarryScoreAndReason() {
        List<CompanionOrder> pending = pendingOrders();
        assertFalse(pending.isEmpty(), "前提失效：库里没有待接单订单");

        for (CompanionOrder order : pending) {
            OrderRecommendVO result = matchingService.recommendCompanions(order, 10);

            assertNotNull(result, "推荐结果不能为 null");
            assertEquals(order.getId(), result.getOrderId(), "响应里的订单 ID 要对上");
            assertNotNull(result.getTotal(), "候选总数不能为 null");
            assertTrue(result.getTotal() >= 0, "候选总数不能为负");
            assertTrue(result.getTotal() >= result.getCandidates().size(),
                    "候选总数不得小于返回的条数");
            assertTrue(result.getCandidates().size() <= 10, "返回条数不得超过 limit");

            for (CompanionMatchVO candidate : result.getCandidates()) {
                assertNotNull(candidate.getTotalScore(), "总分不能为 null");
                assertTrue(candidate.getTotalScore() >= 0 && candidate.getTotalScore() <= 100,
                        "总分越界：" + candidate.getTotalScore());
                assertNotNull(candidate.getReasons(), "理由列表不能为 null");
                assertFalse(candidate.getReasons().isEmpty(),
                        "陪诊员 " + candidate.getCompanionId() + " 的分数没有任何理由");
                assertNotNull(candidate.getNotes(), "降级说明列表不能为 null");
                assertNotNull(candidate.getCompanionName(), "候选姓名不能为 null");
                assertTrue(candidate.getCompanionName().contains("*")
                                || candidate.getCompanionName().length() <= 1,
                        "候选姓名必须脱敏，实际：" + candidate.getCompanionName());
            }
        }
    }

    @Test
    @DisplayName("候选推荐：当前库里确实算得出候选（防止上一条断言空跑）")
    void shouldActuallyProduceCandidates() {
        int withCandidates = 0;
        for (CompanionOrder order : pendingOrders()) {
            if (matchingService.recommendCompanions(order, 10).getTotal() > 0) {
                withCandidates++;
            }
        }

        assertTrue(withCandidates > 0,
                "库里一条候选都算不出来 —— 说明过滤链把所有人都筛掉了，上一条断言等于空跑");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 库里全部待接单订单（按 ID 升序） */
    private List<CompanionOrder> pendingOrders() {
        return orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                .orderByAsc(CompanionOrder::getId));
    }

    /** 文本里是否出现了 8 个区县名之一 */
    private static boolean mentionsDistrict(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return DISTRICT_TO_CODE.keySet().stream().anyMatch(text::contains);
    }
}
