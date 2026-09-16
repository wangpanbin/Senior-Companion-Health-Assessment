package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.OrderRejectLog;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.OrderAcceptResultVO;
import org.company.nianglin.vo.OrderCreateResultVO;
import org.company.nianglin.vo.OrderFlowResultVO;
import org.company.nianglin.vo.OrderTimelineVO;
import org.company.nianglin.vo.OrderVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 陪诊订单服务实现。
 *
 * <h3>这个类里最需要读懂的三件事</h3>
 *
 * <ol>
 *   <li><b>状态判断的顺序</b>：每个流转方法都是
 *       「先判状态（{@code 3002}）→ 再判身份（{@code 4003} / {@code 3004}）」。
 *       反过来写的话，对一张 {@code PENDING} 的单子调用「完成服务」会得到
 *       「您不是该订单的陪诊员」—— 而待接单的订单根本没有陪诊员，
 *       这句话既没有信息量，也把验收标准里的「跳级调用返回 3002」变成了 4003。</li>
 *   <li><b>两种并发写入各自解决的问题</b>：接单用 {@code @Version} 乐观锁，
 *       因为它的失败要区分「被抢先」（{@code 3003}）；其余流转用
 *       {@code WHERE status = 期望状态} 的条件更新，因为它们的失败统一就是
 *       「状态不对」（{@code 3002}）。两者都是原子的，都不能退化成先查后改。</li>
 *   <li><b>列表的名字是批量补上的</b>：订单表不存老人姓名与陪诊员姓名（M1 的表结构如此），
 *       一页 10 条订单若逐条查档案就是 20 次查询。这里统一按 ID 集合批量取，
 *       把一页的查询数固定成 3 次（订单 1 + 老人 1 + 陪诊员 1）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 订单号前缀，格式 NL + yyyyMMdd + 6 位当日序列 */
    private static final String ORDER_NO_PREFIX = "NL";
    private static final DateTimeFormatter ORDER_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ORDER_NO_SEQ_WIDTH = 6;
    /** 序列段在订单号里的起始位置（1-based）：前缀 2 位 + 日期 8 位之后 */
    private static final int ORDER_NO_SEQ_START = ORDER_NO_PREFIX.length() + 8 + 1;
    /** 序列 key 保留 2 天：跨零点后旧 key 自然过期，不必手动清理 */
    private static final Duration ORDER_SEQ_TTL = Duration.ofDays(2);
    /** 发号的最大尝试次数：撞号只可能是计数器被重建，正常第二次必成 */
    private static final int ORDER_NO_MAX_ATTEMPTS = 3;

    /**
     * 「抬升到至少 floor，再自增并返回新值」的原子脚本。
     *
     * <p>低于当前值时不回退 —— 序列计数器只能往前走，否则会把已发出的号重发一遍。</p>
     */
    private static final DefaultRedisScript<Long> RAISE_SEQ_SCRIPT = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "local floor = tonumber(ARGV[1]) "
                    + "if cur < floor then redis.call('SET', KEYS[1], floor) end "
                    + "return redis.call('INCR', KEYS[1])",
            Long.class);

    /** 基础服务费（工作日 08:00–18:00） */
    private static final BigDecimal BASE_FEE = new BigDecimal("128.00");
    /** 夜间或周末加价 */
    private static final BigDecimal OFF_HOURS_EXTRA = new BigDecimal("30.00");
    /** 夜间时段起点 / 终点（18 点后、8 点前） */
    private static final int NIGHT_START_HOUR = 18;
    private static final int NIGHT_END_HOUR = 8;

    private static final int MAX_SERVICE_PHOTOS = 6;

    private final CompanionOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final OrderRejectLogMapper rejectLogMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final SysUserMapper sysUserMapper;
    private final OrderReadMapper orderReadMapper;
    private final ElderService elderService;
    private final MessageService messageService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /* ================================================================== */
    /* 1. 创建订单                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateResultVO create(OrderCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        // 归属校验复用 M3：家属只能给自己绑定的老人下单（不是自己家的 → 2006 / 档案不存在 → 2001）
        ElderProfile elder = elderService.requireAccessible(dto.getElderId());

        if (!dto.getVisitTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ORDER_TIME_INVALID);
        }

        CompanionOrder order = new CompanionOrder();
        order.setOrderNo(nextOrderNo());
        order.setFamilyId(me.userId());
        order.setElderId(elder.getId());
        order.setHospital(dto.getHospital().trim());
        order.setDepartment(dto.getDepartment().trim());
        order.setVisitTime(dto.getVisitTime());
        order.setAddress(dto.getAddress().trim());
        // 坐标成对归一：只传一个视为没传（半对坐标无法参与距离计算，
        // 留着只会让下游写「lat != null 但 lng == null」这种判断）
        BigDecimal[] point = normalizePoint(dto.getLongitude(), dto.getLatitude());
        order.setLongitude(point[0]);
        order.setLatitude(point[1]);
        order.setRemark(clearable(dto.getRemark()));
        order.setStatus(OrderStatus.PENDING.name());
        order.setFee(resolveFee(dto.getFee(), dto.getVisitTime()));
        order.setPaymentStatus(PaymentStatus.UNPAID.name());
        order.setArbitrateFlag(0);
        // 乐观锁字段必须显式给初值：@Version 参与 UPDATE 的 WHERE 条件，
        // 让它为 null 会让「第一次接单」永远匹配不上
        order.setVersion(0);
        orderMapper.insert(order);

        writeStatusLog(order.getId(), null, OrderStatus.PENDING, me, "下单成功");
        notifyOrderCreated(order);

        log.info("陪诊订单已创建 | orderId={} | orderNo={} | familyId={} | elderId={}",
                order.getId(), order.getOrderNo(), me.userId(), elder.getId());
        return OrderCreateResultVO.of(order);
    }

    /* ================================================================== */
    /* 2. 我的订单列表                                                     */
    /* ================================================================== */

    @Override
    public PageResult<OrderVO> myOrders(OrderQuery query) {
        LoginUser me = SecurityUtils.currentUser();
        LambdaQueryWrapper<CompanionOrder> wrapper = Wrappers.lambdaQuery();

        String role = me.role();
        if (RoleConstants.FAMILY.equals(role)) {
            wrapper.eq(CompanionOrder::getFamilyId, me.userId());
        } else if (RoleConstants.COMPANION.equals(role)) {
            wrapper.eq(CompanionOrder::getCompanionId, me.userId());
        } else if (RoleConstants.ELDER.equals(role)) {
            List<Long> elderIds = myElderProfileIds(me.userId());
            if (elderIds.isEmpty()) {
                // 没有关联档案的老人账号看不到任何订单；也要提前返回，
                // 否则会拼出 `IN ()` 这种语法错误的 SQL
                return PageResult.empty(query.normalizedPage(), query.normalizedSize());
            }
            wrapper.in(CompanionOrder::getElderId, elderIds);
        } else if (!RoleConstants.ADMIN.equals(role)) {
            // 兜底默认拒绝：将来若新增角色却忘了在这里写数据范围，
            // 这个分支保证它看到的是空列表，而不是全库订单
            log.warn("未知角色的订单列表请求，已按空结果处理 | role={}", role);
            return PageResult.empty(query.normalizedPage(), query.normalizedSize());
        }
        // ADMIN 不加范围 = 全部订单（前端建议走 M9 的 /api/admin/order）

        List<String> statuses = parseStatuses(query.getStatus());
        if (!statuses.isEmpty()) {
            wrapper.in(CompanionOrder::getStatus, statuses);
        }
        // 列表按「下单日期」筛，与文档 §2 的参数说明一致
        applyDateRange(wrapper, CompanionOrder::getCreateTime, query.getStartDate(), query.getEndDate());
        if (query.getElderId() != null) {
            wrapper.eq(CompanionOrder::getElderId, query.getElderId());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(CompanionOrder::getOrderNo, keyword)
                    .or().like(CompanionOrder::getHospital, keyword));
        }
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        Page<CompanionOrder> page = orderMapper.selectPage(query.toMpPage(), wrapper);
        return toVoPage(page, false);
    }

    /* ================================================================== */
    /* 3. 待接单订单大厅                                                   */
    /* ================================================================== */

    @Override
    public PageResult<OrderVO> hall(OrderHallQuery query) {
        LoginUser me = SecurityUtils.currentUser();

        // 双重拦截的第二重：角色注解只能保证"是 COMPANION"，
        // 保证不了"资质已经审过了"
        requireApprovedCompanion(me.userId());

        LambdaQueryWrapper<CompanionOrder> wrapper = Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                // 就诊时间已过的单子不再挂在大厅上：家属没取消，但陪诊员也接不了了
                .gt(CompanionOrder::getVisitTime, LocalDateTime.now());

        List<Long> rejected = rejectedOrderIds(me.userId());
        if (!rejected.isEmpty()) {
            wrapper.notIn(CompanionOrder::getId, rejected);
        }

        if (StringUtils.hasText(query.getArea())) {
            wrapper.like(CompanionOrder::getAddress, query.getArea().trim());
        }
        if (StringUtils.hasText(query.getHospital())) {
            wrapper.like(CompanionOrder::getHospital, query.getHospital().trim());
        }
        // 大厅按「就诊时间」筛，因为陪诊员是按住哪天有空来接单的
        applyDateRange(wrapper, CompanionOrder::getVisitTime, query.getStartDate(), query.getEndDate());
        wrapper.orderByAsc(CompanionOrder::getVisitTime).orderByAsc(CompanionOrder::getId);

        Page<CompanionOrder> page = orderMapper.selectPage(query.toMpPage(), wrapper);
        return toVoPage(page, true);
    }

    /* ================================================================== */
    /* 4. 订单详情                                                         */
    /* ================================================================== */

    @Override
    public OrderVO detail(Long orderId) {
        CompanionOrder order = requireInvolved(orderId);
        ElderProfile elder = loadElders(List.of(order.getElderId())).get(order.getElderId());
        String companionName = order.getCompanionId() == null
                ? null : loadCompanionNames(List.of(order.getCompanionId())).get(order.getCompanionId());
        return OrderVO.ofDetail(order,
                elder == null ? null : elder.getName(),
                elder == null ? null : ageOf(elder),
                companionName,
                readPhotoList(order.getServicePhotos()));
    }

    /* ================================================================== */
    /* 5. 取消订单                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, OrderCancelDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!me.userId().equals(order.getFamilyId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        OrderStatus from = OrderStatus.of(order.getStatus());
        if (from != OrderStatus.PENDING) {
            // 已接单之后不能再由家属单方面取消 —— 陪诊员可能已经在路上，
            // 这种情况要走 M9 的纠纷处理
            throw new BusinessException(ResultCode.ORDER_CANNOT_CANCEL);
        }

        LocalDateTime now = LocalDateTime.now();
        // 条件更新：`WHERE id=? AND status='PENDING'` 由数据库保证原子性，
        // 并发重复取消时只有一条能成功，另一条 affectedRows=0 → 3006
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                .set(CompanionOrder::getStatus, OrderStatus.CANCELLED.name())
                .set(CompanionOrder::getCancelTime, now)
                .set(CompanionOrder::getCancelReason, dto.getReason().trim())
                .set(CompanionOrder::getCancelBy, me.userId()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_CANNOT_CANCEL);
        }

        writeStatusLog(orderId, from, OrderStatus.CANCELLED, me, dto.getReason().trim());
        // 这里刻意不发 ORDER_CANCELLED：能走到这一行说明订单还是 PENDING，
        // 也就是还没有陪诊员，收件人为 null。给「还不存在的接单人」发取消通知
        // 是纯粹的死代码。已接单后的取消只能走 M9 纠纷处理，
        // 那条路径由 AdminServiceImpl 向家属与陪诊员双发。
        log.info("订单已取消 | orderId={} | familyId={}", orderId, me.userId());
    }

    /* ================================================================== */
    /* 6. 接单（乐观锁防超卖 —— M4 最重要的验收点）                          */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderAcceptResultVO accept(Long orderId) {
        LoginUser me = SecurityUtils.currentUser();
        CompanionProfile profile = requireApprovedCompanion(me.userId());

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        OrderStatus from = OrderStatus.of(order.getStatus());
        if (from != OrderStatus.PENDING) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(OrderStatus.ACCEPTED.name());
        order.setCompanionId(me.userId());
        order.setAcceptTime(now);

        // updateById 带着 @Version：MyBatis-Plus 生成
        //   UPDATE companion_order SET ..., version = version + 1
        //   WHERE id = ? AND version = ?
        // 50 个并发请求里只有第 1 个 affectedRows = 1，其余全是 0 → 3003。
        // 这就是验收项「50 并发同时接同一订单，只有 1 条成功、version 仅 +1」的实现。
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            log.info("接单乐观锁未命中（已被抢先） | orderId={} | companionId={}", orderId, me.userId());
            throw new BusinessException(ResultCode.ORDER_ALREADY_TAKEN);
        }

        writeStatusLog(orderId, from, OrderStatus.ACCEPTED, me, "已接单");
        notifyOrderAccepted(order, profile);
        log.info("接单成功 | orderId={} | companionId={} | version={}", orderId, me.userId(), order.getVersion());
        return OrderAcceptResultVO.of(order, now);
    }

    /* ================================================================== */
    /* 7. 拒单（不改变订单状态）                                            */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long orderId, OrderRejectDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        requireApprovedCompanion(me.userId());

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING) {
            // 拒单只对还没被人接走的单子有意义
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        Long exists = rejectLogMapper.selectCount(Wrappers.<OrderRejectLog>lambdaQuery()
                .eq(OrderRejectLog::getOrderId, orderId)
                .eq(OrderRejectLog::getCompanionId, me.userId()));
        if (exists != null && exists > 0) {
            // 表上有唯一索引 uk_order_companion，这里先查一次是为了给出人话提示，
            // 而不是让用户看到一个数据库约束异常
            throw new BusinessException(ResultCode.CONFLICT, "您已经拒过该订单了");
        }

        OrderRejectLog rejectRow = new OrderRejectLog();
        rejectRow.setOrderId(orderId);
        rejectRow.setCompanionId(me.userId());
        rejectRow.setReason(dto.getReason().trim());
        rejectRow.setRejectTime(LocalDateTime.now());
        rejectLogMapper.insert(rejectRow);

        // 刻意不写 order_status_log：拒单没有改变订单状态，
        // 往状态日志里塞一条"状态没变"的记录会把时间线搞乱
        log.info("陪诊员拒单 | orderId={} | companionId={}", orderId, me.userId());
    }

    /* ================================================================== */
    /* 8. 开始服务                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderFlowResultVO start(Long orderId) {
        LoginUser me = SecurityUtils.currentUser();
        requireApprovedCompanion(me.userId());

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        OrderStatus from = OrderStatus.of(order.getStatus());
        // 先状态后身份，理由见类注释第 1 条
        if (from != OrderStatus.ACCEPTED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (!me.userId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.NOT_ORDER_COMPANION);
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getStatus, OrderStatus.ACCEPTED.name())
                .eq(CompanionOrder::getCompanionId, me.userId())
                .set(CompanionOrder::getStatus, OrderStatus.IN_SERVICE.name())
                .set(CompanionOrder::getStartTime, now));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        writeStatusLog(orderId, from, OrderStatus.IN_SERVICE, me, "已开始服务");
        return OrderFlowResultVO.ofStarted(now);
    }

    /* ================================================================== */
    /* 9. 完成服务                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderFlowResultVO complete(Long orderId, OrderCompleteDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        requireApprovedCompanion(me.userId());

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        OrderStatus from = OrderStatus.of(order.getStatus());
        if (from != OrderStatus.IN_SERVICE) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (!me.userId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.NOT_ORDER_COMPANION);
        }

        String summary = clearable(dto.getSummary());
        if (summary != null) {
            String hit = ComplianceCheckUtil.firstHit(summary);
            if (hit != null) {
                // 合规红线：命中"诊断 / 处方 / 用药建议"类表述直接拒绝入库，
                // 并把命中的词回给用户，让他知道改哪里
                throw new BusinessException(ResultCode.ORDER_SUMMARY_ILLEGAL,
                        "服务小结不能包含「" + hit + "」这类诊断或用药建议，请改为记录过程");
            }
        }
        String photosJson = writePhotoList(dto.getPhotos());
        BigDecimal actualFee = dto.getFee() == null ? null : dto.getFee().setScale(2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<CompanionOrder> update = Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getStatus, OrderStatus.IN_SERVICE.name())
                .eq(CompanionOrder::getCompanionId, me.userId())
                .set(CompanionOrder::getStatus, OrderStatus.COMPLETED.name())
                .set(CompanionOrder::getFinishTime, now);
        if (summary != null) {
            update.set(CompanionOrder::getServiceSummary, summary);
        }
        if (photosJson != null) {
            update.set(CompanionOrder::getServicePhotos, photosJson);
        }
        if (actualFee != null) {
            update.set(CompanionOrder::getActualFee, actualFee);
        }

        int rows = orderMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        writeStatusLog(orderId, from, OrderStatus.COMPLETED, me, "服务已完成");
        notifyOrderCompleted(order);

        // 结算状态读库里的真实值返回，不写死 UNPAID ——
        // 一期虽然恒为 UNPAID，但 M9 支持线下回填之后写死就是一句谎话
        log.info("订单已完成 | orderId={} | companionId={} | actualFee={}", orderId, me.userId(), actualFee);
        return OrderFlowResultVO.ofCompleted(now, order.getPaymentStatus());
    }

    /* ================================================================== */
    /* 10. 时间线                                                          */
    /* ================================================================== */

    @Override
    public List<OrderTimelineVO> timeline(Long orderId) {
        requireInvolved(orderId);
        List<OrderStatusLog> rows = statusLogMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, orderId)
                .orderByAsc(OrderStatusLog::getOperateTime)
                .orderByAsc(OrderStatusLog::getId));
        return rows.stream().map(OrderTimelineVO::of).toList();
    }

    /* ================================================================== */
    /* 11. 评价推进（供 M7 调用）                                           */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReviewed(Long orderId) {
        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (OrderStatus.REVIEWED.name().equals(order.getStatus())) {
            // 幂等：评价接口在并发重试下可能调用两次，第二次不该报错
            return;
        }
        if (!OrderStatus.COMPLETED.name().equals(order.getStatus())) {
            // 走到这里说明调用方漏了状态校验。抛 3002 而不是静默返回，
            // 是为了让「评价接口传了未完成的订单」这类 bug 在第一次出现时就暴露
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getStatus, OrderStatus.COMPLETED.name())
                .set(CompanionOrder::getStatus, OrderStatus.REVIEWED.name()));
        if (rows == 0) {
            // 并发下另一个请求已经推进过了，日志也已由它写入，这里直接返回
            log.info("订单状态已被其他请求推进到已评价，跳过 | orderId={}", orderId);
            return;
        }
        writeStatusLog(orderId, OrderStatus.COMPLETED, OrderStatus.REVIEWED,
                SecurityUtils.currentUser(), "家属提交评价");
        log.info("订单状态已推进 | orderId={} | {} → {}", orderId,
                OrderStatus.COMPLETED.name(), OrderStatus.REVIEWED.name());
    }

    /* ================================================================== */
    /* 12. 管理员强制终态（供 M9 纠纷处理调用）                             */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionOrder forceTerminal(Long orderId, OrderStatus target, String remark) {
        if (target == null || !target.isAdminForceable()) {
            // 只允许 COMPLETED / CANCELLED。允许改成 IN_SERVICE 之类的话，
            // 状态机会被拉回中间态，而双方对「谁该继续做」没有任何共识
            throw new BusinessException(ResultCode.PARAM_ERROR, "只能强制进入「已完成」或「已取消」状态");
        }

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (current.isTerminal()) {
            // 已是终态（含已被强制处理过的）不再处理，避免同一笔纠纷被反复改结论
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL, "订单已处于终态，不可再处理");
        }
        if (current == target) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                // 条件更新把「读到的状态」钉在 SQL 里：并发下另一个管理员已改过状态时，
                // 这一次更新影响 0 行，而不是覆盖掉对方的结果
                .eq(CompanionOrder::getStatus, order.getStatus())
                .set(CompanionOrder::getStatus, target.name())
                .set(target == OrderStatus.CANCELLED, CompanionOrder::getCancelTime, now)
                .set(target == OrderStatus.CANCELLED, CompanionOrder::getCancelReason, remark)
                .set(target == OrderStatus.COMPLETED, CompanionOrder::getFinishTime, now));
        if (rows == 0) {
            log.info("强制终态失败：订单状态已被其他请求改变 | orderId={} | expect={}", orderId, order.getStatus());
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        writeStatusLog(orderId, current, target, SecurityUtils.currentUser(), "管理员强制变更：" + remark);

        log.info("管理员强制变更订单终态 | orderId={} | {} → {} | operator={}",
                orderId, current.name(), target.name(), SecurityUtils.currentUser().userId());
        return orderMapper.selectById(orderId);
    }

    /* ================================================================== */
    /* 归属校验（跨模块复用）                                               */
    /* ================================================================== */

    @Override
    public CompanionOrder requireInvolved(Long orderId) {
        return requireInvolved(orderId, SecurityUtils.currentUser());
    }

    @Override
    public CompanionOrder requireInvolved(Long orderId, LoginUser me) {
        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (me == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (me.isAdmin()) {
            return order;
        }
        if (me.userId().equals(order.getFamilyId())) {
            return order;
        }
        if (order.getCompanionId() != null && me.userId().equals(order.getCompanionId())) {
            return order;
        }
        if (RoleConstants.ELDER.equals(me.role()) && isOrderElder(order, me.userId())) {
            return order;
        }
        throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
    }

    /* ================================================================== */
    /* 内部工具：订单事件站内信（M8）                                        */
    /* ================================================================== */

    /*
     * 为什么订单模块要直接调 MessageService，而不是等 M8 来「订阅」：
     * 订单状态机是唯一的真源，只有它知道「这一秒谁变成了接单人」。
     * 把这件事推给一个旁路监听器，就得在库里比状态快照 —— 那是更贵也更不准的做法。
     *
     * 三条约定（与 M6 漏服提醒保持一致）：
     *   1. 文案不自拼，全部交给 MessageTemplateUtil；
     *   2. 姓名类占位符由调用方先脱敏，MessageService 不知道哪个字段是隐私；
     *   3. 不 try/catch 吞异常 —— 这些方法本身处在 @Transactional 里，
     *      吞掉一个来自「已加入当前事务」的异常会让外层提交时抛
     *      UnexpectedRollbackException，比直接失败更难查。
     *      推送环节自身的失败已经在 MessageServiceImpl.pushNewMessage 内部处理掉了。
     */

    /** 就诊时间在站内信里的展示格式：不带年份，通知里只看「几号几点」 */
    private static final DateTimeFormatter NOTIFY_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 下单成功 → 广播给「全部已审核陪诊员」。
     *
     * <p>大厅是「谁先看到谁抢」的模型，所以这条通知必须群发：
     * 只发给某一个人等于把订单私下指派给他，大厅也就形同虚设了。</p>
     */
    private void notifyOrderCreated(CompanionOrder order) {
        List<Long> receivers = approvedCompanionUserIds();
        if (receivers.isEmpty()) {
            // 一个已审核陪诊员都没有是正常的（新环境，或全被驳回），
            // 但不能因此让家属下不了单 —— 订单照样进大厅，等有人过审后自然能看到
            log.info("下单通知无接收人（当前没有已审核陪诊员） | orderId={}", order.getId());
            return;
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("orderNo", order.getOrderNo());
        params.put("visitTime", order.getVisitTime() == null ? null
                : order.getVisitTime().format(NOTIFY_TIME_FORMAT));
        params.put("hospital", order.getHospital());
        messageService.sendBatch(receivers, MessageType.ORDER_CREATED, order.getId(), params);
    }

    /** 接单成功 → 通知下单家属「谁来了」 */
    private void notifyOrderAccepted(CompanionOrder order, CompanionProfile companion) {
        Map<String, Object> params = new HashMap<>(2);
        params.put("companionName", MaskUtil.name(companion == null ? null : companion.getRealName()));
        params.put("orderNo", order.getOrderNo());
        messageService.send(order.getFamilyId(), MessageType.ORDER_ACCEPTED, order.getId(), params);
    }

    /** 服务完成 → 通知下单家属去评价 */
    private void notifyOrderCompleted(CompanionOrder order) {
        Map<String, Object> params = new HashMap<>(2);
        params.put("orderNo", order.getOrderNo());
        messageService.send(order.getFamilyId(), MessageType.ORDER_COMPLETED, order.getId(), params);
    }

    /**
     * 全部「已审核」陪诊员的用户 ID。
     *
     * <p>用 {@code userId} 而不是 {@code companion_profile.id}：前者是登录身份，
     * 后者只是快照行号，两者在库里并不相等（M3 实测已确认）。
     * 一人多行快照时 {@code distinct()} 保证只发一条。</p>
     */
    private List<Long> approvedCompanionUserIds() {
        return companionProfileMapper.selectList(Wrappers.<CompanionProfile>lambdaQuery()
                        .eq(CompanionProfile::getAuditStatus, AuditStatus.APPROVED.name())
                        // 只取需要的一列，避免把整行（含证件号快照）读进内存
                        .select(CompanionProfile::getUserId))
                .stream()
                .map(CompanionProfile::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /* ================================================================== */
    /* 内部工具：校验                                                       */
    /* ================================================================== */

    /**
     * 陪诊员资质校验（大厅 / 接单 / 拒单 / 开始 / 完成都要过）。
     *
     * <p>角色是 {@code COMPANION} 只说明「这个账号被定义成陪诊员」，
     * 不说明「他的资质已经审过了」。未审核或无快照一律 {@code 2003}。</p>
     */
    private CompanionProfile requireApprovedCompanion(Long userId) {
        CompanionProfile profile = findCompanionProfile(userId);
        AuditStatus status = profile == null ? null : AuditStatus.of(profile.getAuditStatus());
        if (status == null || !status.isApproved()) {
            throw new BusinessException(ResultCode.COMPANION_NOT_AUDITED);
        }
        return profile;
    }

    /** 当前用户是否为该订单的就诊老人本人 */
    private boolean isOrderElder(CompanionOrder order, Long userId) {
        ElderProfile elder = loadElders(List.of(order.getElderId())).get(order.getElderId());
        return elder != null && elder.getUserId() != null && elder.getUserId().equals(userId);
    }

    /* ================================================================== */
    /* 内部工具：查询装配                                                   */
    /* ================================================================== */

    /**
     * 实体分页 → VO 分页。
     *
     * @param withAddress 是否返回地址（大厅需要，我的订单不需要）
     */
    private PageResult<OrderVO> toVoPage(Page<CompanionOrder> page, boolean withAddress) {
        List<CompanionOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), List.of());
        }

        Map<Long, ElderProfile> elders = loadElders(records.stream()
                .map(CompanionOrder::getElderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        Map<Long, String> companionNames = loadCompanionNames(records.stream()
                .map(CompanionOrder::getCompanionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());

        List<OrderVO> list = records.stream().map(order -> {
            ElderProfile elder = elders.get(order.getElderId());
            String elderName = elder == null ? null : elder.getName();
            Integer age = elder == null ? null : ageOf(elder);
            String companionName = order.getCompanionId() == null
                    ? null : companionNames.get(order.getCompanionId());
            return withAddress
                    ? OrderVO.ofHall(order, elderName, age, companionName)
                    : OrderVO.ofList(order, elderName, age, companionName);
        }).toList();

        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), list);
    }

    /**
     * 批量取老人档案（{@code id → 档案}）。
     *
     * <p>走 {@code OrderReadMapper} 而不是 {@code ElderProfileMapper}：
     * 后者带逻辑删除过滤，会让已删除档案的历史订单丢失老人姓名，
     * 而 M3 的删除流程刻意保留了物理行正是为了这些历史订单。</p>
     */
    private Map<Long, ElderProfile> loadElders(Collection<Long> elderIds) {
        if (elderIds == null || elderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ElderProfile> map = new HashMap<>();
        for (ElderProfile elder : orderReadMapper.selectEldersIgnoringLogicDelete(elderIds)) {
            map.put(elder.getId(), elder);
        }
        return map;
    }

    /** 批量取陪诊员姓名（{@code userId → 真实姓名}） */
    private Map<Long, String> loadCompanionNames(Collection<Long> companionIds) {
        if (companionIds == null || companionIds.isEmpty()) {
            return Map.of();
        }
        return companionProfileMapper.selectList(Wrappers.<CompanionProfile>lambdaQuery()
                        .in(CompanionProfile::getUserId, companionIds))
                .stream()
                .filter(p -> p.getUserId() != null)
                .collect(Collectors.toMap(CompanionProfile::getUserId,
                        p -> p.getRealName() == null ? "" : p.getRealName(),
                        (a, b) -> a));
    }

    /**
     * 查陪诊员快照行。
     *
     * <p>用 {@code selectList} 取首行而不是 {@code selectOne}：
     * {@code selectOne} 遇到重复行会抛 {@code TooManyResultsException}，
     * 把「数据脏了」升级成「接口 500」。这里只取 id 最小的一行，接口照常可用。</p>
     */
    private CompanionProfile findCompanionProfile(Long userId) {
        List<CompanionProfile> list = companionProfileMapper.selectList(Wrappers.<CompanionProfile>lambdaQuery()
                .eq(CompanionProfile::getUserId, userId)
                .orderByAsc(CompanionProfile::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 当前老人账号关联的档案 ID（用于 ELDER 的数据范围） */
    private List<Long> myElderProfileIds(Long userId) {
        return elderProfileMapper.selectList(Wrappers.<ElderProfile>lambdaQuery()
                        .eq(ElderProfile::getUserId, userId))
                .stream()
                .map(ElderProfile::getId)
                .toList();
    }

    /** 该陪诊员已经拒过的订单 ID（大厅要排除掉） */
    private List<Long> rejectedOrderIds(Long companionId) {
        return rejectLogMapper.selectList(Wrappers.<OrderRejectLog>lambdaQuery()
                        .eq(OrderRejectLog::getCompanionId, companionId))
                .stream()
                .map(OrderRejectLog::getOrderId)
                .distinct()
                .toList();
    }

    /* ================================================================== */
    /* 内部工具：写入                                                       */
    /* ================================================================== */

    /**
     * 写一条状态流转日志。
     *
     * <p>{@code operatorName} 在这里定格成快照，之后操作人改名不会篡改历史。</p>
     *
     * @param from 变更前状态，首次下单传 {@code null}
     */
    private void writeStatusLog(Long orderId, OrderStatus from, OrderStatus to, LoginUser me, String remark) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(orderId);
        row.setFromStatus(from == null ? null : from.name());
        row.setToStatus(to.name());
        row.setOperatorId(me == null ? null : me.userId());
        row.setOperatorName(me == null ? null : resolveOperatorName(me));
        row.setOperatorRole(me == null ? RoleConstants.SYSTEM : me.role());
        row.setRemark(remark);
        row.setIsForce(0);
        row.setOperateTime(LocalDateTime.now());
        statusLogMapper.insert(row);
    }

    /** 操作人显示名：陪诊员取资质快照里的真实姓名，其余取昵称，都没有再退到用户名 */
    private String resolveOperatorName(LoginUser me) {
        if (RoleConstants.COMPANION.equals(me.role())) {
            CompanionProfile profile = findCompanionProfile(me.userId());
            if (profile != null && StringUtils.hasText(profile.getRealName())) {
                return profile.getRealName();
            }
        }
        SysUser user = sysUserMapper.selectById(me.userId());
        if (user == null) {
            return me.username();
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    /* ================================================================== */
    /* 内部工具：订单号与费用                                               */
    /* ================================================================== */

    /**
     * 生成订单号 {@code NL + yyyyMMdd + 6 位当日序列}。
     *
     * <p><b>正常路径</b>：用 Redis 的 {@code INCR} 而不是「查当天最大订单号 + 1」——
     * 后者在并发下两个请求会读到同一个最大值，然后一起撞上 {@code uk_order_no}
     * 唯一索引，下单接口在高峰期随机失败，且失败原因对用户完全不可理解。
     * {@code INCR} 是单线程原子的，天然不重复。</p>
     *
     * <p>序列 key 按天分桶并设 2 天 TTL，因此跨零点自动从 1 重新开始，
     * 不需要清理任务。</p>
     *
     * <h3>为什么还要补一次唯一性兜底</h3>
     *
     * <p>{@code INCR} 的「不重复」有一个隐含前提：<b>计数器活得比当天已发出的订单号更久</b>。
     * 这个前提是会被打破的 —— Redis 被清空、未开持久化重启、或 key 的 2 天 TTL 先到期，
     * 而当天已发出的订单号还留在库里。此时计数器从 1 重新开始，直接撞上
     * {@code uk_order_no}，用户看到的是下单接口报「该记录已存在」（409）：
     * 既看不懂，也无法自救 —— 重试多少次都一样，直到计数器自己爬过冲突值。</p>
     *
     * <p>所以每次发号后补一次唯一性判断：命中已存在的号就说明计数器失效了，
     * 把计数器<b>原子地</b>抬到「库里当天已用的最大序号」之后重新发号。
     * 代价是每单多一次走唯一索引的等值查询 —— 换的是「Redis 数据丢了也不会让用户下不了单」，
     * 在下单这种低频写路径上这个交换是划算的。</p>
     */
    private String nextOrderNo() {
        String day = LocalDate.now().format(ORDER_DAY_FORMAT);
        String key = RedisKeyConstants.orderSeq(day);

        for (int attempt = 1; attempt <= ORDER_NO_MAX_ATTEMPTS; attempt++) {
            Long seq;
            if (attempt == 1) {
                seq = redisTemplate.opsForValue().increment(key);
                if (seq != null && seq == 1L) {
                    redisTemplate.expire(key, ORDER_SEQ_TTL);
                }
            } else {
                // 冲突后的重新发号：先抬到库内当天最大值再原子取号
                seq = raiseSeqAbove(key, maxUsedSeq(day));
            }

            if (seq == null) {
                log.error("订单号序列获取失败，Redis 未返回自增值 | key={}", key);
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "订单号生成失败，请稍后重试");
            }

            String orderNo = ORDER_NO_PREFIX + day
                    + String.format("%0" + ORDER_NO_SEQ_WIDTH + "d", seq);
            if (!orderNoExists(orderNo)) {
                return orderNo;
            }

            log.warn("订单号已存在，判定为当日序列计数器失效，将抬升至库内最大序号后重发 | "
                    + "conflict={} | dbMaxSeq={} | attempt={}", orderNo, maxUsedSeq(day), attempt);
        }

        log.error("订单号连续 {} 次生成失败，当日序列异常 | day={}", ORDER_NO_MAX_ATTEMPTS, day);
        throw new BusinessException(ResultCode.SYSTEM_ERROR, "订单号生成失败，请稍后重试");
    }

    /** 订单号是否已存在（走 {@code uk_order_no} 唯一索引的等值查询） */
    private boolean orderNoExists(String orderNo) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<CompanionOrder>()
                .eq(CompanionOrder::getOrderNo, orderNo));
        return count != null && count > 0L;
    }

    /**
     * 库里当天已发出的最大序号；当天没有订单时返回 0。
     *
     * <p>订单号长度固定（前缀 + 8 位日期 + 6 位序列），所以直接在 SQL 里
     * 截取序列段取最大值，而不是把当天所有订单号拉回内存自己解析。</p>
     */
    private long maxUsedSeq(String day) {
        QueryWrapper<CompanionOrder> wrapper = new QueryWrapper<>();
        wrapper.select("IFNULL(MAX(CAST(SUBSTRING(`order_no`, " + ORDER_NO_SEQ_START
                        + ") AS UNSIGNED)), 0)")
                .likeRight("order_no", ORDER_NO_PREFIX + day);
        List<Object> values = orderMapper.selectObjs(wrapper);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(values.get(0)));
    }

    /**
     * 把当日序列计数器抬到 {@code floor} 之上并原子取下一个号。
     *
     * <p>用 Lua 而不是「先 GET 再 SET」：并发下「自己读到 18、另一个线程已经涨到 25、
     * 自己再写回 18」会把已经发出去的号又发一遍 —— 兜底逻辑自己制造重复。
     * 判断与自增放进一次 Redis 调用才安全；计数器只增不减。</p>
     *
     * @param floor 已用掉的最大序号，返回值为 {@code max(当前值, floor) + 1}
     */
    private Long raiseSeqAbove(String key, long floor) {
        return redisTemplate.execute(RAISE_SEQ_SCRIPT, List.of(key), String.valueOf(floor));
    }

    /**
     * 服务费：传了就用传的（只做两位小数归一），没传就按规则算。
     *
     * <p>规则：工作日 08:00–18:00 收 {@code 128.00}；夜间（18 点后或 8 点前）
     * 或周末加 {@code 30.00}。规则写在后端而不是前端，是因为价格若由前端决定，
     * 改价就得等前端发版，而且用户改一个请求体就能改价。</p>
     */
    private BigDecimal resolveFee(BigDecimal given, LocalDateTime visitTime) {
        if (given != null) {
            if (given.signum() < 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "服务费不能为负数");
            }
            return given.setScale(2, RoundingMode.HALF_UP);
        }
        DayOfWeek dayOfWeek = visitTime.getDayOfWeek();
        boolean weekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        int hour = visitTime.getHour();
        boolean night = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
        return (weekend || night) ? BASE_FEE.add(OFF_HOURS_EXTRA) : BASE_FEE;
    }

    /* ================================================================== */
    /* 内部工具：查询条件                                                   */
    /* ================================================================== */

    /**
     * 解析多值状态参数。
     *
     * <p>非法取值直接 {@code 400} 而不是忽略：前端传了 {@code status=PENDNG}（拼错）
     * 却拿到全量列表，会以为筛选生效了，然后拿这份数据做出错误的产品判断。</p>
     */
    private static List<String> parseStatuses(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            OrderStatus status = OrderStatus.of(value);
            if (status == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "订单状态取值不合法：" + value);
            }
            if (!result.contains(status.name())) {
                result.add(status.name());
            }
        }
        return result;
    }

    /**
     * 日期区间筛选（闭区间）。
     *
     * <p>结束日期用 {@code < 次日 0 点} 而不是 {@code <= 当日 23:59:59}：
     * {@code DATETIME} 没有小数秒时后者看起来没问题，一旦将来改成
     * {@code DATETIME(3)}，{@code 23:59:59.500} 就会被漏掉。</p>
     */
    private void applyDateRange(LambdaQueryWrapper<CompanionOrder> wrapper,
                                SFunction<CompanionOrder, ?> column,
                                String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        if (start != null) {
            wrapper.ge(column, start.atStartOfDay());
        }
        if (end != null) {
            wrapper.lt(column, end.plusDays(1).atStartOfDay());
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 日期格式应为 yyyy-MM-dd");
        }
    }

    /**
     * 排序：用白名单 switch 而不是把 {@code sortField} 直接拼进 SQL。
     *
     * <p>非白名单取值不报错、直接回落到默认排序。原因是这里控制的是
     * 「列表怎么排」，不是「能不能拿到数据」—— 为此让整个列表 400
     * 对用户来说是莫名其妙的失败。</p>
     */
    private void applySort(LambdaQueryWrapper<CompanionOrder> wrapper, String sortField, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String field = sortField == null ? "" : sortField.trim().toLowerCase();
        switch (field) {
            case "visit_time" -> wrapper.orderBy(true, asc, CompanionOrder::getVisitTime);
            case "fee" -> wrapper.orderBy(true, asc, CompanionOrder::getFee);
            case "status" -> wrapper.orderBy(true, asc, CompanionOrder::getStatus);
            case "id" -> wrapper.orderBy(true, asc, CompanionOrder::getId);
            default -> wrapper.orderByDesc(CompanionOrder::getCreateTime)
                    .orderByDesc(CompanionOrder::getId);
        }
    }

    /* ================================================================== */
    /* 内部工具：杂项                                                       */
    /* ================================================================== */

    /** 由出生日期算年龄；日期为空返回 {@code null} 而不是 0 */
    private static Integer ageOf(ElderProfile elder) {
        if (elder.getBirthDate() == null) {
            return null;
        }
        int years = java.time.Period.between(elder.getBirthDate(), LocalDate.now()).getYears();
        return years < 0 ? null : years;
    }

    /** 空串或纯空白视为「没填」，统一归一成 {@code null} */
    private static String clearable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 医院坐标归一。
     *
     * <p>规则：两个都传才生效；只传一个就当没传（返回两个 {@code null}）。
     * 半对坐标在库里是一条「看起来有定位、其实算不出距离」的记录 ——
     * M5 的距离校验会拿到 {@code null} 并且静默放行，比明确没有坐标更危险。</p>
     *
     * <p>顺带把精度归一到 6 位小数：库列是 {@code DECIMAL(10,6)}，
     * 不归一的话写进去的值与调用方传的值不是同一个数，
     * 事后复核打卡距离时会和接口返回的对不上。</p>
     *
     * @return 长度恒为 2 的数组：{@code [经度, 纬度]}，未提供时全为 {@code null}
     */
    private static BigDecimal[] normalizePoint(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null) {
            return new BigDecimal[]{null, null};
        }
        return new BigDecimal[]{
                longitude.setScale(6, RoundingMode.HALF_UP),
                latitude.setScale(6, RoundingMode.HALF_UP)};
    }

    /** 照片列表 → JSON 列；空列表按「没填」处理（存 {@code null} 而不是 {@code []}） */
    private String writePhotoList(List<String> photos) {
        if (photos == null || photos.isEmpty()) {
            return null;
        }
        if (photos.size() > MAX_SERVICE_PHOTOS) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "服务照片最多 " + MAX_SERVICE_PHOTOS + " 张");
        }
        try {
            return objectMapper.writeValueAsString(photos);
        } catch (JsonProcessingException ex) {
            // 不打内容，只打长度：异常信息可能被日志采集带走
            log.warn("服务照片序列化失败 | size={}", photos.size());
            throw new BusinessException(ResultCode.PARAM_ERROR, "服务照片格式不正确");
        }
    }

    /** JSON 列 → 照片列表；历史脏数据不抛异常，只降级为「没有照片」 */
    private List<String> readPhotoList(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            // 一条脏数据不该让整个订单详情打不开
            log.warn("订单服务照片 JSON 解析失败，已按空处理 | len={}", json.length());
            return null;
        }
    }
}
