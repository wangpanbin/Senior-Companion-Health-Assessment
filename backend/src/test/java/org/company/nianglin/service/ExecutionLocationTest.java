package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.TrackingProperties;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.LocationReportDTO;
import org.company.nianglin.dto.LocationSharingDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionTrack;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.CompanionTrackMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ExecutionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.LocationVO;
import org.company.nianglin.websocket.OrderProgressHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 实时位置服务单测（ADR-0022）。
 *
 * <p>这组测试盯的是「实时位置」这件事上<b>四条容易写反的性质</b>：</p>
 *
 * <ol>
 *   <li><b>限流是拒绝而不是静默</b>：10 秒内二次上报必须回 {@code 4004}，
 *       而不是悄悄丢掉 —— 否则「位置为什么不动」在排查时完全无从下手；</li>
 *   <li><b>抽稀不影响「最新点」</b>：位移不到 50 米不落库，但 Redis 必须照刷，
 *       否则家属看到的位置会跳着走；</li>
 *   <li><b>暂停是 4005 而不是静默丢弃</b>：陪诊员关了共享，前端要能看到明确原因；</li>
 *   <li><b>可见性收窄</b>：老人与管理员的 {@code GET /location} 必须回 {@code 3004}，
 *       即使他们能看订单详情与打卡进度。</li>
 * </ol>
 *
 * <p>与既有 {@code ExecutionServiceTest} 一样用纯 Mockito（不连 MySQL / Redis），
 * 所以这里讨论的「Redis 有值」全部由 mock 的 {@code ValueOperations} 提供。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("实时位置：限流 / 抽稀 / 暂停 / 可见性")
class ExecutionLocationTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long COMPANION_ID = 301L;
    private static final Long OTHER_COMPANION_ID = 302L;
    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;

    /** 医院坐标（海南省人民医院附近），种子数据口径 */
    private static final BigDecimal HOSPITAL_LON = new BigDecimal("110.311200");
    private static final BigDecimal HOSPITAL_LAT = new BigDecimal("20.021500");

    /** 距医院约 1.2 公里的一个点（海口市内，纬度略高） */
    private static final String FAR_LON = "110.321200";
    private static final String FAR_LAT = "20.031500";

    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private OrderCheckinMapper checkinMapper;
    @Mock
    private CompanionTrackMapper trackMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private CompanionProfileMapper companionProfileMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private MessageService messageService;
    @Mock
    private OrderProgressHub progressHub;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        service = new ExecutionServiceImpl(orderMapper, checkinMapper, trackMapper, sysUserMapper,
                companionProfileMapper, orderService, fileStorageService, messageService, progressHub,
                new ObjectMapper(), redisTemplate, new TrackingProperties());
        ReflectionTestUtils.setField(service, "maxDistanceMeters", 2000);
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 上报：状态 / 身份 / 暂停 / 限流                                      */
    /* ================================================================== */

    @Test
    @DisplayName("订单不存在 → 3001")
    void reportOrderNotFound() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT)));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("待接单的订单上报 → 3002（先判状态，不能报成 4003）")
    void reportPendingOrderRejectedAsStatusIllegal() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.PENDING, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT)));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("非本单陪诊员上报 → 4003")
    void reportByOtherCompanionRejected() {
        loginAs(OTHER_COMPANION_ID, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT)));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("位置共享已暂停 → 4005，且不写 Redis 最新点、不落库")
    void reportWhenSharingDisabledRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn("off");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT)));

        assertEquals(ResultCode.LOCATION_SHARING_DISABLED.getCode(), ex.getCode());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(trackMapper, never()).insert(any(CompanionTrack.class));
    }

    @Test
    @DisplayName("10 秒内二次上报 → 4004（不静默丢弃）")
    void reportTooFrequentRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        // 限流 key 已存在 = 距上次上报不足最小间隔
        given(valueOperations.setIfAbsent(eq(RedisKeyConstants.trackRate(ORDER_ID)), eq("1"), any(Duration.class)))
                .willReturn(Boolean.FALSE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT)));

        assertEquals(ResultCode.LOCATION_REPORT_TOO_FREQUENT.getCode(), ex.getCode());
        verify(trackMapper, never()).insert(any(CompanionTrack.class));
    }

    @Test
    @DisplayName("坐标非法 → 400（且不占用限流额度、不落库）")
    void reportInvalidCoordinateRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportLocation(ORDER_ID, dto("999", "20.0315")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(trackMapper, never()).insert(any(CompanionTrack.class));
    }

    /* ================================================================== */
    /* 上报：距离 / 抽稀 / 推送                                             */
    /* ================================================================== */

    @Test
    @DisplayName("首次上报：落一条轨迹（node 为 null）+ 刷最新点 + 距离与打卡同口径 + 只推 FAMILY/COMPANION")
    void firstReportPersistsTrackAndPushes() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        given(trackMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());

        LocationVO vo = service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT));

        // 距离用与打卡同一个 GeoUtil：这两个点相距约 1.5 公里
        assertNotNull(vo.getDistanceMeters());
        assertTrue(vo.getDistanceMeters() > 1000 && vo.getDistanceMeters() < 2500,
                "距离应约为 1.1~1.6 公里，实际 " + vo.getDistanceMeters());

        org.mockito.ArgumentCaptor<CompanionTrack> captor =
                org.mockito.ArgumentCaptor.forClass(CompanionTrack.class);
        verify(trackMapper).insert(captor.capture());
        CompanionTrack saved = captor.getValue();
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals(COMPANION_ID, saved.getCompanionId());
        // 位置点不是打卡锚点
        assertNull(saved.getNode(), "连续位置点的 node 必须为 null，与打卡锚点区分");
        assertEquals(6, saved.getLongitude().scale());

        // Redis 最新点必须被写
        verify(valueOperations).set(eq(RedisKeyConstants.trackLatest(ORDER_ID)), anyString(), any(Duration.class));

        // 位置事件只发给家属与陪诊员
        verify(progressHub).publishTo(eq(ORDER_ID), eq(OrderProgressHub.TYPE_LOCATION), any(),
                eq(OrderProgressHub.LOCATION_AUDIENCE));
    }

    @Test
    @DisplayName("位移不足 50 米且间隔不足 120 秒 → 不落库，但最新点照刷")
    void smallMoveIsThinnedButCacheStillRefreshed() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        // 上一个点几乎在同一个位置，且就在 10 秒前
        CompanionTrack previous = new CompanionTrack();
        previous.setOrderId(ORDER_ID);
        previous.setLongitude(new BigDecimal(FAR_LON));
        previous.setLatitude(new BigDecimal(FAR_LAT));
        previous.setRecordTime(LocalDateTime.now().minusSeconds(10));
        given(trackMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(previous));

        service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT));

        verify(trackMapper, never()).insert(any(CompanionTrack.class));
        verify(valueOperations).set(eq(RedisKeyConstants.trackLatest(ORDER_ID)), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("位移超过 50 米 → 落库（即使间隔只有 10 秒）")
    void largeMoveIsPersisted() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        CompanionTrack previous = new CompanionTrack();
        previous.setOrderId(ORDER_ID);
        // 上一个点在医院坐标上，距这次的 FAR 点约 1.5 公里
        previous.setLongitude(HOSPITAL_LON);
        previous.setLatitude(HOSPITAL_LAT);
        previous.setRecordTime(LocalDateTime.now().minusSeconds(10));
        given(trackMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(previous));

        service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT));

        verify(trackMapper).insert(any(CompanionTrack.class));
    }

    @Test
    @DisplayName("位置没动但间隔超过 120 秒 → 仍落库（静止也要有时间锚点，供纠纷取证）")
    void staticButLongIntervalIsPersisted() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        CompanionTrack previous = new CompanionTrack();
        previous.setOrderId(ORDER_ID);
        previous.setLongitude(new BigDecimal(FAR_LON));
        previous.setLatitude(new BigDecimal(FAR_LAT));
        previous.setRecordTime(LocalDateTime.now().minusSeconds(121));
        given(trackMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(previous));

        service.reportLocation(ORDER_ID, dto(FAR_LON, FAR_LAT));

        verify(trackMapper).insert(any(CompanionTrack.class));
    }

    /* ================================================================== */
    /* 最新位置快照：可见性                                                 */
    /* ================================================================== */

    @Test
    @DisplayName("家属可查最新位置：坐标与距离都对，staleSeconds 由服务端盖章")
    void familyCanReadLatestLocation() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackLatest(ORDER_ID)))
                .willReturn(latestJson(30));
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);

        LocationVO vo = service.latestLocation(ORDER_ID);

        assertEquals(FAR_LON, vo.getLongitude());
        assertEquals("110.311200", vo.getOrderLongitude());
        assertNotNull(vo.getDistanceMeters());
        assertTrue(vo.getStaleSeconds() >= 29 && vo.getStaleSeconds() <= 40,
                "staleSeconds 应按 recordTime 现算，实际 " + vo.getStaleSeconds());
    }

    @Test
    @DisplayName("老人查询最新位置 → 3004（能看订单详情与打卡进度，但看不了实时位置）")
    void elderCannotReadLatestLocation() {
        loginAs(ELDER_ID, RoleConstants.ELDER);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.latestLocation(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("管理员查询最新位置 → 3004（纠纷取证走 /track，不给实时地图）")
    void adminCannotReadLatestLocation() {
        loginAs(999L, RoleConstants.ADMIN);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.latestLocation(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("从未上报过 → HTTP 200 语义：坐标全 null，但医院坐标与共享开关照常返回")
    void neverReportedReturnsNullPointNotError() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackLatest(ORDER_ID))).willReturn(null);
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);

        LocationVO vo = service.latestLocation(ORDER_ID);

        assertNull(vo.getLongitude());
        assertNull(vo.getLatitude());
        assertNull(vo.getRecordTime());
        assertNull(vo.getDistanceMeters(), "没有位置点时距离必须是 null，不能是 0");
        assertEquals("110.311200", vo.getOrderLongitude());
        assertTrue(vo.getSharingEnabled());
    }

    /* ================================================================== */
    /* 共享开关                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("暂停共享：写 Redis off + 推 LOCATION_PAUSED（只发家属与陪诊员）")
    void pauseSharingWritesFlagAndPushesEvent() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackLatest(ORDER_ID))).willReturn(null);
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);

        LocationVO vo = service.setLocationSharing(ORDER_ID, sharing(false));

        verify(valueOperations).set(RedisKeyConstants.trackShare(ORDER_ID), "off");
        verify(progressHub).publishTo(eq(ORDER_ID), eq(OrderProgressHub.TYPE_LOCATION_PAUSED), any(),
                eq(OrderProgressHub.LOCATION_AUDIENCE));
        assertFalse(vo.getSharingEnabled());
    }

    @Test
    @DisplayName("恢复共享：删 key（而不是写 on），有最新点则补推一条 LOCATION")
    void resumeSharingDeletesFlagAndRepublishes() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(valueOperations.get(RedisKeyConstants.trackLatest(ORDER_ID))).willReturn(latestJson(20));
        // 恢复后 isSharingEnabled 应为 true：Redis 无 track:share
        given(valueOperations.get(RedisKeyConstants.trackShare(ORDER_ID))).willReturn(null);

        LocationVO vo = service.setLocationSharing(ORDER_ID, sharing(true));

        verify(redisTemplate).delete(RedisKeyConstants.trackShare(ORDER_ID));
        verify(progressHub).publishTo(eq(ORDER_ID), eq(OrderProgressHub.TYPE_LOCATION), any(),
                eq(OrderProgressHub.LOCATION_AUDIENCE));
        assertTrue(vo.getSharingEnabled());
    }

    @Test
    @DisplayName("非本单陪诊员开关共享 → 4003")
    void setSharingByOtherCompanionRejected() {
        loginAs(OTHER_COMPANION_ID, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setLocationSharing(ORDER_ID, sharing(false)));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
        verify(redisTemplate, never()).delete(anyString());
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    private static void loginAs(Long userId, String role) {
        LoginUser loginUser = new LoginUser(userId, "test-" + userId, role, 0, "jti-" + userId, 0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private static CompanionOrder order(OrderStatus status, Long companionId) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260915000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setCompanionId(companionId);
        order.setStatus(status.name());
        order.setLongitude(HOSPITAL_LON);
        order.setLatitude(HOSPITAL_LAT);
        order.setCreateTime(LocalDateTime.of(2026, 9, 15, 16, 40));
        return order;
    }

    private static LocationReportDTO dto(String longitude, String latitude) {
        LocationReportDTO dto = new LocationReportDTO();
        dto.setLongitude(longitude);
        dto.setLatitude(latitude);
        dto.setAccuracy(18.5);
        dto.setSpeed(1.2);
        return dto;
    }

    private static LocationSharingDTO sharing(boolean enabled) {
        LocationSharingDTO dto = new LocationSharingDTO();
        dto.setEnabled(enabled);
        return dto;
    }

    /** 与 {@code cacheLatestPoint} 写入结构一致的缓存 JSON */
    private static String latestJson(int secondsAgo) {
        return "{\"longitude\":\"" + FAR_LON + "\",\"latitude\":\"" + FAR_LAT + "\","
                + "\"accuracy\":18.5,\"speed\":1.2,"
                + "\"recordTime\":\"" + LocalDateTime.now().minusSeconds(secondsAgo) + "\"}";
    }
}
