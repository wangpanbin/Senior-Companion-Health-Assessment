package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CheckinCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.CompanionTrack;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.CompanionTrackMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.impl.ExecutionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.CheckinResultVO;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.vo.FileUploadVO;
import org.company.nianglin.vo.ProgressVO;
import org.company.nianglin.vo.TrackPointVO;
import org.company.nianglin.websocket.OrderProgressHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 陪诊执行服务单测（M5）。
 *
 * <p>重点不在「打卡能成功」，而在<b>没人会主动去点的那些分支</b>：</p>
 * <ol>
 *   <li><b>状态与身份的判断顺序</b>：对「待接单」的单子打卡必须是 3002 而不是 4003；</li>
 *   <li><b>被拒之后有没有副作用</b>：距离超限被拒时，两张表都不许有写入；</li>
 *   <li><b>节点顺序</b>：回退要拦，跳过要放；</li>
 *   <li><b>双写完整性</b>：打卡成功必须是 {@code order_checkin} 与
 *       {@code companion_track} 各一行，且 {@code orderId} 一致。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊执行服务：打卡校验 / 节点顺序 / 距离阈值 / 双写")
class ExecutionServiceTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long COMPANION_ID = 301L;
    private static final Long OTHER_COMPANION_ID = 302L;

    /** 医院坐标（海南省人民医院附近） */
    private static final BigDecimal HOSPITAL_LON = new BigDecimal("110.331200");
    private static final BigDecimal HOSPITAL_LAT = new BigDecimal("20.031500");

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

    private ExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ExecutionServiceImpl(orderMapper, checkinMapper, trackMapper, sysUserMapper,
                companionProfileMapper, orderService, fileStorageService, messageService, progressHub,
                new ObjectMapper());
        // @Value 字段在纯单测里不会被注入，不手动设就是 0 ——
        // 那会让「任何非零距离都超限」，把距离相关的用例全部变成假红
        ReflectionTestUtils.setField(service, "maxDistanceMeters", 2000);
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 打卡：状态与身份                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("订单不存在 → 3001，且不写任何表")
    void checkinOrderNotFound() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("DEPART", "110.331200", "20.031500")));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(checkinMapper, never()).insert(any(OrderCheckin.class));
        verify(trackMapper, never()).insert(any(CompanionTrack.class));
    }

    @Test
    @DisplayName("待接单的订单打卡 → 3002（先判状态，不能报成 4003）")
    void checkinPendingOrderRejectedAsStatusIllegal() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.PENDING, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("DEPART", "110.331200", "20.031500")));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("已完成的订单打卡 → 3002")
    void checkinCompletedOrderRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("DEPART", "110.331200", "20.031500")));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("非本单陪诊员打卡 → 4003")
    void checkinByOtherCompanionRejected() {
        loginAs(OTHER_COMPANION_ID, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "110.331200", "20.031500")));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
        verify(checkinMapper, never()).insert(any(OrderCheckin.class));
    }

    /* ================================================================== */
    /* 打卡：节点与去重                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("节点取值非法 → 400")
    void checkinInvalidNodeRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("WAITING", "110.331200", "20.031500")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("同一节点重复打卡 → 4002，不产生第二行")
    void checkinDuplicateNodeRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "110.331200", "20.031500")));

        assertEquals(ResultCode.CHECKIN_DUPLICATED.getCode(), ex.getCode());
        verify(checkinMapper, never()).insert(any(OrderCheckin.class));
    }

    @Test
    @DisplayName("节点顺序回退（已取药后再打「到院」）→ 400")
    void checkinNodeRegressionRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of(checkin(CheckinNode.TAKE_MEDICINE)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "110.331200", "20.031500")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("回退"));
        verify(checkinMapper, never()).insert(any(OrderCheckin.class));
    }

    @Test
    @DisplayName("节点顺序允许跳过（打完「到院」直接打「离院」）")
    void checkinAllowsSkippingNodes() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of(checkin(CheckinNode.ARRIVE)));
        given(companionProfileMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(sysUserMapper.selectById(COMPANION_ID)).willReturn(null);

        CheckinResultVO result = service.checkin(ORDER_ID, dto("LEAVE", "110.331200", "20.031500"));

        assertNotNull(result);
        assertEquals(CheckinNode.LEAVE.name(), result.getNode());
        verify(checkinMapper).insert(any(OrderCheckin.class));
    }

    /* ================================================================== */
    /* 打卡：距离校验                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("坐标超出订单地址阈值 → 4001，两张表都不写入")
    void checkinTooFarRejectedWithoutWrites() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());

        // 与医院相距约 60 km（海口 → 三亚方向）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "109.508000", "18.247000")));

        assertEquals(ResultCode.NOT_IN_CHECKIN_RANGE.getCode(), ex.getCode());
        verify(checkinMapper, never()).insert(any(OrderCheckin.class));
        verify(trackMapper, never()).insert(any(CompanionTrack.class));
    }

    @Test
    @DisplayName("经纬度非法（超出 ±180 / ±90）→ 400")
    void checkinInvalidCoordinateRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "999.000000", "20.031500")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("经纬度非数字 → 400")
    void checkinNonNumericCoordinateRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkin(ORDER_ID, dto("ARRIVE", "not-a-number", "20.031500")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("订单无坐标时不做距离校验，打卡正常通过")
    void checkinSkipsDistanceCheckWhenOrderHasNoCoordinate() {
        CompanionOrder noCoord = order(OrderStatus.ACCEPTED, COMPANION_ID);
        noCoord.setLongitude(null);
        noCoord.setLatitude(null);
        given(orderMapper.selectById(ORDER_ID)).willReturn(noCoord);
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(companionProfileMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(sysUserMapper.selectById(COMPANION_ID)).willReturn(null);

        CheckinResultVO result = service.checkin(ORDER_ID, dto("DEPART", "110.331200", "20.031500"));

        assertNull(result.getDistance());
        assertFalse(result.getIsAbnormal());
    }

    /* ================================================================== */
    /* 打卡：双写与推送                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("打卡成功：order_checkin 与 companion_track 各一行，orderId 一致，并推送进度")
    void checkinWritesBothTablesAndPushes() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(checkinMapper.insert(any(OrderCheckin.class))).willAnswer(inv -> {
            inv.getArgument(0, OrderCheckin.class).setId(8001L);
            return 1;
        });
        CompanionProfile profile = new CompanionProfile();
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        given(companionProfileMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(profile));

        CheckinResultVO result = service.checkin(ORDER_ID, dto("ARRIVE", "110.331200", "20.031500"));

        assertEquals(8001L, result.getCheckinId());

        ArgumentCaptor<OrderCheckin> checkinCaptor = ArgumentCaptor.forClass(OrderCheckin.class);
        verify(checkinMapper).insert(checkinCaptor.capture());
        OrderCheckin saved = checkinCaptor.getValue();
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals(COMPANION_ID, saved.getCompanionId());
        assertEquals(CheckinNode.ARRIVE.name(), saved.getNode());
        assertEquals(2, saved.getNodeSort());
        // 打卡点就在医院坐标上，距离应为 0 而不是 null
        assertEquals(0, saved.getDistance());
        assertEquals(0, saved.getIsAbnormal());

        ArgumentCaptor<CompanionTrack> trackCaptor = ArgumentCaptor.forClass(CompanionTrack.class);
        verify(trackMapper).insert(trackCaptor.capture());
        assertEquals(ORDER_ID, trackCaptor.getValue().getOrderId());
        assertEquals(CheckinNode.ARRIVE.name(), trackCaptor.getValue().getNode());

        verify(progressHub).publish(eq(ORDER_ID), eq(OrderProgressHub.TYPE_ORDER_PROGRESS), any());
    }

    @Test
    @DisplayName("经纬度写库前归一到 6 位小数，与 DECIMAL(10,6) 对齐")
    void checkinNormalizesCoordinateScale() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.ACCEPTED, COMPANION_ID));
        given(checkinMapper.selectCount(any(LambdaQueryWrapper.class))).willReturn(0L);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(companionProfileMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());
        given(sysUserMapper.selectById(COMPANION_ID)).willReturn(null);

        service.checkin(ORDER_ID, dto("ARRIVE", "110.3312", "20.0315"));

        ArgumentCaptor<OrderCheckin> captor = ArgumentCaptor.forClass(OrderCheckin.class);
        verify(checkinMapper).insert(captor.capture());
        assertEquals(6, captor.getValue().getLongitude().scale());
        assertEquals("110.331200", captor.getValue().getLongitude().toPlainString());
    }

    /* ================================================================== */
    /* 查询类：归属 + 装配                                                 */
    /* ================================================================== */

    @Test
    @DisplayName("打卡列表：归属校验走 OrderService，按时间升序，姓名脱敏")
    void checkinsRequiresInvolvementAndMasksName() {
        OrderCheckin row = checkin(CheckinNode.ARRIVE);
        row.setId(8001L);
        row.setCompanionId(COMPANION_ID);
        row.setPhotos("[\"/uploads/202609/a.jpg\"]");
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(row));
        CompanionProfile profile = new CompanionProfile();
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        given(companionProfileMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(profile));

        List<CheckinVO> list = service.checkins(ORDER_ID);

        verify(orderService).requireInvolved(ORDER_ID);
        assertEquals(1, list.size());
        assertEquals("李*军", list.get(0).getOperatorName());
        assertEquals("到院", list.get(0).getNodeLabel());
        assertEquals(1, list.get(0).getPhotos().size());
    }

    @Test
    @DisplayName("打卡列表：无记录返回空列表而不是 null")
    void checkinsEmptyReturnsEmptyList() {
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());

        List<CheckinVO> list = service.checkins(ORDER_ID);

        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("轨迹列表：归属校验走 OrderService，坐标转 6 位小数字符串")
    void trackReturnsPoints() {
        CompanionTrack point = new CompanionTrack();
        point.setOrderId(ORDER_ID);
        point.setNode(CheckinNode.DEPART.name());
        point.setLongitude(new BigDecimal("110.3112"));
        point.setLatitude(new BigDecimal("20.0215"));
        point.setRecordTime(LocalDateTime.now());
        given(trackMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(point));

        List<TrackPointVO> list = service.track(ORDER_ID);

        verify(orderService).requireInvolved(ORDER_ID);
        assertEquals(1, list.size());
        assertEquals("110.311200", list.get(0).getLongitude());
    }

    @Test
    @DisplayName("进度快照：无打卡时 currentNode 为 null、nextNode 为「出发」、进度 0")
    void progressWithoutCheckin() {
        CompanionOrder order = order(OrderStatus.ACCEPTED, COMPANION_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order);
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of());

        ProgressVO progress = service.progress(ORDER_ID);

        assertNull(progress.getCurrentNode());
        assertEquals(CheckinNode.DEPART.name(), progress.getNextNode());
        assertEquals(0, progress.getProgressPercent());
        assertTrue(progress.getFinishedNodes().isEmpty());
        assertEquals(order.getCreateTime(), progress.getLastUpdateTime());
    }

    @Test
    @DisplayName("进度快照：已打「就诊中」时 nextNode 为「取药」、进度 50、已完成节点按顺序排列")
    void progressWithCheckins() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        given(checkinMapper.selectList(any(LambdaQueryWrapper.class))).willReturn(List.of(
                checkin(CheckinNode.DEPART),
                checkin(CheckinNode.ARRIVE),
                checkin(CheckinNode.IN_CONSULT)));

        ProgressVO progress = service.progress(ORDER_ID);

        assertEquals(CheckinNode.IN_CONSULT.name(), progress.getCurrentNode());
        assertEquals(CheckinNode.TAKE_MEDICINE.name(), progress.getNextNode());
        assertEquals(50, progress.getProgressPercent());
        assertEquals(List.of("DEPART", "ARRIVE", "IN_CONSULT"), progress.getFinishedNodes());
    }

    /* ================================================================== */
    /* 上传                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("上传照片：非本单陪诊员 → 4003，不落盘")
    void uploadPhotoRejectedForOtherCompanion() {
        loginAs(OTHER_COMPANION_ID, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.uploadPhoto(ORDER_ID, org.mockito.Mockito.mock(MultipartFile.class)));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
        verify(fileStorageService, never()).store(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("上传照片：本单陪诊员 → 委托给 FileStorageService 并以 CHECKIN 归档")
    void uploadPhotoDelegatesToStorage() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE, COMPANION_ID));
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        FileUploadVO expected = new FileUploadVO().setFileId("f_1").setUrl("/uploads/202609/a.jpg").setSize(10L);
        given(fileStorageService.store(eq(file), eq("CHECKIN"), eq(ORDER_ID), eq(COMPANION_ID)))
                .willReturn(expected);

        FileUploadVO result = service.uploadPhoto(ORDER_ID, file);

        assertSame(expected, result);
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
        order.setFamilyId(101L);
        order.setElderId(401L);
        order.setCompanionId(companionId);
        order.setStatus(status.name());
        order.setLongitude(HOSPITAL_LON);
        order.setLatitude(HOSPITAL_LAT);
        order.setCreateTime(LocalDateTime.of(2026, 9, 15, 16, 40));
        return order;
    }

    private static OrderCheckin checkin(CheckinNode node) {
        OrderCheckin row = new OrderCheckin();
        row.setOrderId(ORDER_ID);
        row.setNode(node.name());
        row.setNodeSort(node.getSort());
        row.setCheckinTime(LocalDateTime.now());
        return row;
    }

    private static CheckinCreateDTO dto(String node, String longitude, String latitude) {
        CheckinCreateDTO dto = new CheckinCreateDTO();
        dto.setNode(node);
        dto.setLongitude(longitude);
        dto.setLatitude(latitude);
        return dto;
    }
}
