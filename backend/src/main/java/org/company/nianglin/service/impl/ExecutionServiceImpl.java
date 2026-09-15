package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.dto.CheckinCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.CompanionTrack;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.CompanionTrackMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ExecutionService;
import org.company.nianglin.service.FileStorageService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.util.GeoUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.CheckinResultVO;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.vo.FileUploadVO;
import org.company.nianglin.vo.ProgressVO;
import org.company.nianglin.vo.TrackPointVO;
import org.company.nianglin.websocket.OrderProgressHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 陪诊执行服务实现。
 *
 * <h3>打卡的六道关，顺序不能换</h3>
 *
 * <pre>
 *   ① 订单存在            → 3001
 *   ② 订单状态可打卡      → 3002   ← 先状态
 *   ③ 是这一单的陪诊员    → 4003   ← 再身份
 *   ④ 节点取值合法        → 400
 *   ⑤ 不是重复打卡        → 4002
 *   ⑥ 节点不回退          → 400
 *   ⑦ 距离在阈值内        → 4001
 * </pre>
 *
 * <p>②③ 的顺序延续 M4 的铁律：先把「状态不对」的结论给出来。
 * 对一张 {@code PENDING} 的单子打卡，正确回答是「订单还没被接单」，
 * 而不是「您不是该订单的陪诊员」—— 后者虽然也成立，但把真正的原因藏起来了。</p>
 *
 * <h3>超阈值是「拒绝」而不是「标记异常后放行」</h3>
 *
 * <p>文档 §1 实现要点第 1 条写的是「判为无效（4001）」，
 * 验收标准也允许两种做法（不写入 或 写 {@code isAbnormal = true}）。
 * 这里选前者，原因是：一旦允许超阈值的记录落库，它在时间线上和正常打卡长得一模一样，
 * 家属看到的仍是「已到院」，而审核方要靠 {@code is_abnormal} 这一列才知道有问题 ——
 * 也就是说<b>错误信息默认对最关心它的人是不可见的</b>。
 * 拒绝则让陪诊员当场就知道「定位说我没到」，可以重试或走申诉，
 * 而不是被悄悄记一笔。</p>
 *
 * <h3>打卡与轨迹在同一事务</h3>
 *
 * <p>{@code order_checkin} 与 {@code companion_track} 是同一次定位的两个视图：
 * 前者给家属看「进度到哪了」，后者给争议处理看「人当时在哪」。
 * 只有一条落库的状态是无法解释的，因此同事务写入，要么都有、要么都没有。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    /** 允许上传的扩展名与其文件头特征 */
    private final CompanionOrderMapper orderMapper;
    private final OrderCheckinMapper checkinMapper;
    private final CompanionTrackMapper trackMapper;
    private final SysUserMapper sysUserMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final OrderService orderService;
    private final FileStorageService fileStorageService;
    private final MessageService messageService;
    private final OrderProgressHub progressHub;
    private final ObjectMapper objectMapper;

    /** 打卡坐标允许偏离订单地址的最大距离（米） */
    @Value("${nianglin.order.checkin-max-distance-meters:2000}")
    private int maxDistanceMeters;

    /* ================================================================== */
    /* 1. 打卡                                                             */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckinResultVO checkin(Long orderId, CheckinCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.IN_SERVICE) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不能打卡（须为已接单或服务中）");
        }
        if (!me.userId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.NOT_ORDER_COMPANION);
        }

        CheckinNode node = CheckinNode.of(dto.getNode());
        if (node == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "打卡节点取值不合法：" + dto.getNode());
        }

        BigDecimal longitude = parseCoordinate(dto.getLongitude(), "经度");
        BigDecimal latitude = parseCoordinate(dto.getLatitude(), "纬度");
        if (!GeoUtil.isValidCoordinate(longitude, latitude)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "经纬度超出合法取值范围");
        }
        // 先归一到 6 位小数再写库、再算距离：
        // 库里是 DECIMAL(10,6)，不归一的话写进去的值和参与距离计算的值不是同一个，
        // 事后复核时会算出与接口返回不一致的距离
        longitude = longitude.setScale(6, RoundingMode.HALF_UP);
        latitude = latitude.setScale(6, RoundingMode.HALF_UP);

        // 去重：表上有 uk_order_node(order_id, node)，这里先查一次是为了给出人话提示，
        // 而不是让用户看到数据库约束异常
        Long duplicated = checkinMapper.selectCount(Wrappers.<OrderCheckin>lambdaQuery()
                .eq(OrderCheckin::getOrderId, orderId)
                .eq(OrderCheckin::getNode, node.name()));
        if (duplicated != null && duplicated > 0) {
            throw new BusinessException(ResultCode.CHECKIN_DUPLICATED,
                    "「" + node.getLabel() + "」已经打过卡了");
        }

        List<OrderCheckin> existing = checkinMapper.selectList(Wrappers.<OrderCheckin>lambdaQuery()
                .eq(OrderCheckin::getOrderId, orderId));
        int maxSort = existing.stream()
                .map(OrderCheckin::getNodeSort)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0);
        if (node.getSort() <= maxSort) {
            CheckinNode last = CheckinNode.ofSort(maxSort);
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "打卡顺序不能回退：当前已进行到「" + (last == null ? "未知" : last.getLabel())
                            + "」，不能再打「" + node.getLabel() + "」");
        }

        Integer distance = GeoUtil.distanceMeters(longitude, latitude, order.getLongitude(), order.getLatitude());
        if (distance == null) {
            // 订单没登记医院坐标 → 距离算不出来，本次跳过校验。
            // 刻意留一条 WARN 而不是静默跳过：静默跳过会让「这单为什么没被拦住」
            // 变成一个在代码里根本查不出来的问题（校验看着实现了，其实一次都没跑过）。
            log.warn("订单未登记医院坐标，本次打卡跳过距离校验 | orderId={} | node={}",
                    orderId, node.name());
        } else if (distance > maxDistanceMeters) {
            throw new BusinessException(ResultCode.NOT_IN_CHECKIN_RANGE,
                    "距陪诊地点约 " + distance + " 米，超出 " + maxDistanceMeters + " 米范围，打卡无效");
        }

        String photosJson = writePhotos(dto.getPhotos());
        String address = clearable(dto.getAddress());
        String remark = clearable(dto.getRemark());
        LocalDateTime now = LocalDateTime.now();

        OrderCheckin row = new OrderCheckin();
        row.setOrderId(orderId);
        row.setCompanionId(me.userId());
        row.setNode(node.name());
        row.setNodeSort(node.getSort());
        row.setLongitude(longitude);
        row.setLatitude(latitude);
        row.setAddress(address);
        row.setDistance(distance);
        // 走到这里说明距离一定在阈值内（超了在上一段就抛了），所以恒为 0。
        // 保留这一列是因为历史数据与其他端未来可能有超阈值放行的记录，查询逻辑要能兼容
        row.setIsAbnormal(0);
        row.setPhotos(photosJson);
        row.setRemark(remark);
        row.setCheckinTime(now);
        checkinMapper.insert(row);

        CompanionTrack track = new CompanionTrack();
        track.setOrderId(orderId);
        track.setCompanionId(me.userId());
        track.setNode(node.name());
        track.setLongitude(longitude);
        track.setLatitude(latitude);
        track.setRecordTime(now);
        trackMapper.insert(track);

        String operatorName = resolveOperatorName(me);
        log.info("陪诊打卡成功 | orderId={} | companionId={} | node={} | distance={}",
                orderId, me.userId(), node.name(), distance);

        // 推送放在事务内：事务提交失败时推送也已经发出去了。
        // 这是刻意的取舍 —— 家属端还有「重连后拉一次 /progress」的补齐路径，
        // 而把推送挪到事务提交后需要 TransactionSynchronizationManager，
        // 复杂度换来的只是「少收一条可被补齐的事件」
        pushProgress(order, node, operatorName, remark);

        // 与 WS 是两条互补的通道，不是二选一：
        //   WS 管「家属正开着页面时 3 秒内红点就跳」，
        //   站内信管「家属当时没看，回头在消息列表里还能查到这次打卡」。
        // 只发 WS 的话，关掉页面就等于这条进度从没发生过。
        notifyProgress(order, node, remark);

        return CheckinResultVO.of(row);
    }

    /* ================================================================== */
    /* 2. 打卡记录列表                                                     */
    /* ================================================================== */

    @Override
    public List<CheckinVO> checkins(Long orderId) {
        orderService.requireInvolved(orderId);

        List<OrderCheckin> rows = checkinMapper.selectList(Wrappers.<OrderCheckin>lambdaQuery()
                .eq(OrderCheckin::getOrderId, orderId)
                .orderByAsc(OrderCheckin::getCheckinTime)
                .orderByAsc(OrderCheckin::getId));
        if (rows.isEmpty()) {
            return List.of();
        }

        String operatorName = loadCompanionName(rows.get(0).getCompanionId());
        return rows.stream()
                .map(row -> CheckinVO.of(row, operatorName, readPhotos(row.getPhotos())))
                .toList();
    }

    /* ================================================================== */
    /* 3. 轨迹点列表                                                       */
    /* ================================================================== */

    @Override
    public List<TrackPointVO> track(Long orderId) {
        orderService.requireInvolved(orderId);

        return trackMapper.selectList(Wrappers.<CompanionTrack>lambdaQuery()
                        .eq(CompanionTrack::getOrderId, orderId)
                        .orderByAsc(CompanionTrack::getRecordTime)
                        .orderByAsc(CompanionTrack::getId))
                .stream()
                .map(TrackPointVO::of)
                .toList();
    }

    /* ================================================================== */
    /* 4. 进度快照                                                         */
    /* ================================================================== */

    @Override
    public ProgressVO progress(Long orderId) {
        CompanionOrder order = orderService.requireInvolved(orderId);

        List<OrderCheckin> rows = checkinMapper.selectList(Wrappers.<OrderCheckin>lambdaQuery()
                .eq(OrderCheckin::getOrderId, orderId)
                .orderByAsc(OrderCheckin::getNodeSort));

        List<String> finished = new ArrayList<>();
        LocalDateTime lastTime = order.getCreateTime();
        CheckinNode last = null;
        for (OrderCheckin row : rows) {
            if (!finished.contains(row.getNode())) {
                finished.add(row.getNode());
            }
            CheckinNode node = CheckinNode.ofSort(row.getNodeSort());
            if (node != null && (last == null || node.getSort() > last.getSort())) {
                last = node;
            }
            if (row.getCheckinTime() != null && (lastTime == null || row.getCheckinTime().isAfter(lastTime))) {
                lastTime = row.getCheckinTime();
            }
        }
        return ProgressVO.of(orderId, order.getStatus(), last, finished, lastTime);
    }

    /* ================================================================== */
    /* 5. 上传现场照片                                                     */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileUploadVO uploadPhoto(Long orderId, MultipartFile file) {
        LoginUser me = SecurityUtils.currentUser();

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!me.userId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.NOT_ORDER_COMPANION);
        }
        // 本模块只服务陪诊员与订单，上传成功的文件天然属于打卡场景。
        // 落盘与类型校验统一走 FileStorageService，避免每个模块各写一份 magic bytes 判断
        return fileStorageService.store(file, "CHECKIN", orderId, me.userId());
    }

    /* ================================================================== */
    /* 内部工具：推送                                                       */
    /* ================================================================== */

    /**
     * 打卡成功 → 给下单家属发一条 {@code ORDER_PROGRESS} 站内信。
     *
     * <p>文案里只放节点名与备注，不放坐标：坐标是给争议处理看的证据，
     * 不是给家属看的进度。转发坐标等于把「陪诊员此刻在哪」实时暴露出去。</p>
     *
     * <p>刻意不 try/catch：本方法运行在打卡的事务里，吞掉一个
     * 「已加入当前事务」的异常会让提交阶段抛 {@code UnexpectedRollbackException}，
     * 比直接失败更难定位。真正的推送失败已在 {@code MessageServiceImpl} 内部消化。</p>
     */
    private void notifyProgress(CompanionOrder order, CheckinNode node, String remark) {
        Map<String, Object> params = new HashMap<>(4);
        params.put("nodeLabel", node.getLabel());
        params.put("remark", remark);
        params.put("orderNo", order.getOrderNo());
        messageService.send(order.getFamilyId(), MessageType.ORDER_PROGRESS, order.getId(), params);
    }

    /**
     * 推送一条 {@code ORDER_PROGRESS} 事件。
     *
     * <p>事件负载字段与文档 §6 的报文示例对齐，因此前端可以只写一份解析逻辑。</p>
     */
    private void pushProgress(CompanionOrder order, CheckinNode node, String operatorName, String remark) {
        try {
            Map<String, Object> data = new HashMap<>(8);
            data.put("node", node.name());
            data.put("nodeLabel", node.getLabel());
            data.put("orderStatus", order.getStatus());
            data.put("orderStatusLabel", OrderStatus.labelOf(order.getStatus()));
            data.put("message", progressMessage(node));
            data.put("operatorName", MaskUtil.name(operatorName));
            if (remark != null) {
                data.put("remark", remark);
            }
            progressHub.publish(order.getId(), OrderProgressHub.TYPE_ORDER_PROGRESS, data);
        } catch (Exception e) {
            // 推送失败绝不能影响已经写库的打卡结果
            log.warn("打卡进度推送失败，已忽略 | orderId={} | {}", order.getId(), e.getMessage());
        }
    }

    /** 节点的推送文案；文案写在这里而不是前端，保证两端看到的是同一句话 */
    private static String progressMessage(CheckinNode node) {        return switch (node) {
            case DEPART -> "陪诊员已出发前往医院";
            case ARRIVE -> "陪诊员已到达医院";
            case IN_CONSULT -> "已进入就诊环节，陪诊员全程陪同";
            case TAKE_MEDICINE -> "已协助取药 / 缴费";
            case LEAVE -> "陪诊员已离开医院，正在返程";
            case FINISH -> "服务已完成，等待交接确认";
        };
    }

    /* ================================================================== */
    /* 内部工具：查询装配                                                   */
    /* ================================================================== */

    /** 打卡人显示名：优先取陪诊员资质快照里的真实姓名，退到昵称，再退到用户名 */
    private String resolveOperatorName(LoginUser me) {
        return loadCompanionName(me.userId());
    }

    private String loadCompanionName(Long userId) {
        if (userId == null) {
            return null;
        }
        List<CompanionProfile> profiles = companionProfileMapper.selectList(Wrappers.<CompanionProfile>lambdaQuery()
                .eq(CompanionProfile::getUserId, userId)
                .orderByAsc(CompanionProfile::getId));
        if (!profiles.isEmpty() && StringUtils.hasText(profiles.get(0).getRealName())) {
            return profiles.get(0).getRealName();
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    /* ================================================================== */
    /* 内部工具：杂项                                                       */
    /* ================================================================== */

    /** 经纬度字符串 → {@code BigDecimal}；格式不对给明确提示而不是 500 */
    private static BigDecimal parseCoordinate(String value, String field) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + "格式不正确，应为十进制数字");
        }
    }

    /** 空串或纯空白视为「没填」，统一归一成 {@code null} */
    private static String clearable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 照片列表 → JSON 列；空列表按「没填」处理（存 {@code null} 而不是 {@code []}） */
    private String writePhotos(List<String> photos) {
        if (photos == null || photos.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(photos);
        } catch (JsonProcessingException e) {
            log.warn("打卡照片序列化失败 | size={}", photos.size());
            throw new BusinessException(ResultCode.PARAM_ERROR, "照片格式不正确");
        }
    }

    /** JSON 列 → 照片列表；历史脏数据不抛异常，只降级为「没有照片」 */
    private List<String> readPhotos(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("打卡照片 JSON 解析失败，已按空处理 | len={}", json.length());
            return null;
        }
    }
}
