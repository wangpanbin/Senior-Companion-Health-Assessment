package org.company.nianglin.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.ElderBindType;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RelationType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DeactivateAccountDTO;
import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.UserService;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.DataExportElderVO;
import org.company.nianglin.vo.DataExportMessageVO;
import org.company.nianglin.vo.DataExportOrderVO;
import org.company.nianglin.vo.DataExportProfileVO;
import org.company.nianglin.vo.DataExportReviewVO;
import org.company.nianglin.vo.UserInfoVO;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 当前用户资料服务实现。
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 注销二次确认固定串（{@code docs/api/01-auth-user.md} §8，前后端保持一致） */
    static final String DEACTIVATE_CONFIRM_TEXT = "我已知晓注销后果";

    /** 导出文件名时间戳：{@code 个人数据_20261102_104500.xlsx} */
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 导出单元格内的时间格式，与接口全局约定一致 */
    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysUserMapper sysUserMapper;
    private final ElderService elderService;
    private final CompanionOrderMapper companionOrderMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final FamilyElderRelationMapper familyElderRelationMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final OrderReviewMapper orderReviewMapper;
    private final InternalMessageMapper internalMessageMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    @Override
    public UserInfoVO getProfile() {
        SysUser user = requireActiveCurrentUser();
        return UserInfoVO.of(user, elderService.elderIdOf(user));
    }

    @Override
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        // 必须校验「账号未封禁」：封禁后 JWT 仍可能有效（ver 不变），被禁用账号不应继续改资料
        requireActiveCurrentUser();

        SysUser patch = new SysUser();
        patch.setId(userId);

        boolean nicknameChanged = dto.getNickname() != null && StringUtils.hasText(dto.getNickname());
        boolean avatarChanged = dto.getAvatar() != null && StringUtils.hasText(dto.getAvatar());

        if (nicknameChanged) {
            patch.setNickname(dto.getNickname().trim());
        }
        if (avatarChanged) {
            patch.setAvatar(dto.getAvatar().trim());
        }

        if (!nicknameChanged && !avatarChanged) {
            // 两个字段都没传：直接返回，不做一次无意义的 UPDATE
            return;
        }

        // 只更新 patch 上的非空字段（MyBatis-Plus updateById 默认忽略 null）
        sysUserMapper.updateById(patch);

        // 日志只打「改了哪些字段」与 userId；不打昵称内容，也不打完整用户对象
        log.info("用户资料已更新 | userId={} | nicknameChanged={} | avatarChanged={}",
                userId, nicknameChanged, avatarChanged);
    }

    /* ================================================================== */
    /* 注销账号（V4 · P0-1）                                                */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(DeactivateAccountDTO dto) {
        SysUser user = requireActiveCurrentUser();
        Long userId = user.getId();

        // ① 二次确认固定串：防「手滑点错」。放在密码之前 —— 串不对就说明用户
        //    根本没读过后果说明，没必要浪费一次 BCrypt（~80ms）
        if (!DEACTIVATE_CONFIRM_TEXT.equals(dto.getConfirmText())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "确认文本不匹配，请原样输入「" + DEACTIVATE_CONFIRM_TEXT + "」");
        }

        // ② 密码复验：防止他人拿着未锁屏的设备把账号注销掉，与修改密码同一口径
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_ERROR);
        }

        // ③ 进行中订单闸门：作为下单家属或接单陪诊员均计入。
        //    进行中的订单关联着老人行程安排与陪诊劳动报酬，账号一注销（手机号、
        //    姓名去标识化）双方就失联了，必须先走完或取消这些订单。
        //    ⚠️ OR 条件必须用 and(...) 显式分组，否则 MP 追加的 deleted = 0
        //    会被解析成「family OR (companion AND deleted=0)」（tools/README 坑 9）。
        Long activeOrderCount = companionOrderMapper.selectCount(Wrappers.<CompanionOrder>lambdaQuery()
                .and(w -> w.eq(CompanionOrder::getFamilyId, userId)
                        .or().eq(CompanionOrder::getCompanionId, userId))
                .in(CompanionOrder::getStatus,
                        OrderStatus.PENDING.name(), OrderStatus.ACCEPTED.name(), OrderStatus.IN_SERVICE.name()));
        if (activeOrderCount != null && activeOrderCount > 0) {
            throw new BusinessException(ResultCode.DEACTIVATE_HAS_ACTIVE_ORDER,
                    ResultCode.DEACTIVATE_HAS_ACTIVE_ORDER.getMessage(),
                    Map.of("pendingOrderCount", activeOrderCount));
        }

        // ④ 去标识化 + 逻辑删除 + 禁用（条件更新兜底并发重复提交）。
        //    phone 用 "d"+userId 墓碑占位而不是 MaskUtil.phone：uk_phone 唯一约束仍在，
        //    脱敏输出只有 11 位且不同原号可碰撞同一占位串，第二次注销会被唯一索引拒掉；
        //    "d"+userId ≤ 20 字符、必然唯一、永不与真实手机号（11 位数字）冲突。
        String deactivatedAt = LocalDateTime.now().format(CELL_TIME);
        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, AccountStatus.NORMAL)
                .set(SysUser::getStatus, AccountStatus.DISABLED)
                .set(SysUser::getRealName, MaskUtil.nameAll(user.getRealName()))
                .set(SysUser::getNickname, MaskUtil.nameAll(user.getNickname()))
                .set(SysUser::getPhone, "d" + userId)
                .set(SysUser::getRemark, "账号已于 " + deactivatedAt + " 自主注销")
                .set(SysUser::getDeleted, 1));

        // ⑤ 陪诊员资质档案去标识化：真实姓名只留姓氏、电话脱敏、身份证密文置 NULL
        //    （AES 密文脱敏无意义，真去标识化即清除）。资质无唯一约束困扰，直接脱敏即可。
        CompanionProfile profile = companionProfileMapper.selectOne(Wrappers.<CompanionProfile>lambdaQuery()
                .eq(CompanionProfile::getUserId, userId));
        if (profile != null) {
            companionProfileMapper.update(null, Wrappers.<CompanionProfile>lambdaUpdate()
                    .eq(CompanionProfile::getId, profile.getId())
                    .set(CompanionProfile::getRealName, MaskUtil.nameAll(profile.getRealName()))
                    .set(CompanionProfile::getPhone, MaskUtil.phone(profile.getPhone()))
                    .set(CompanionProfile::getIdCard, null));
        }

        // ⑥ 家属的绑定关系逻辑删除；老人档案本身保留（注销不影响被照护人的档案存续）。
        //    实体带 @TableLogic，delete 即逻辑删除（UPDATE deleted = 1）
        if (RoleConstants.FAMILY.equals(user.getRole())) {
            familyElderRelationMapper.delete(Wrappers.<FamilyElderRelation>lambdaQuery()
                    .eq(FamilyElderRelation::getFamilyId, userId));
        }

        // ⑦ 密码版本 +1：该账号全部已签发令牌立即失效（401 语义）。
        //    刻意【不】调 markBanned —— 那是封禁语义（过滤器就地 403 + 封禁文案），
        //    注销要的是「账号已不存在」的 401 语义。放在 DB 写完之后：
        //    DB 失败整体回滚时版本号不会被白白抬高。
        tokenStore.bumpPasswordVersion(userId);

        // ⑧ 不写 admin_oper_log：注销是用户自助行为，不是管理员操作
        log.info("账号已注销 | userId={} | role={}", userId, user.getRole());
    }

    /* ================================================================== */
    /* 个人数据导出（V4 · P0-1）                                            */
    /* ================================================================== */

    @Override
    public void dataExport(HttpServletResponse response) {
        SysUser user = requireActiveCurrentUser();
        Long userId = user.getId();

        List<DataExportProfileVO> profileRows = List.of(profileRow(user));
        List<DataExportElderVO> elderRows = elderRows(user);
        List<DataExportOrderVO> orderRows = orderRows(userId);
        List<DataExportReviewVO> reviewRows = reviewRows(userId);
        List<DataExportMessageVO> messageRows = messageRows(userId);

        // 响应已经提交时立刻收手，与 ExportServiceImpl 同一取舍：
        // 此时 response.reset() 只会抛 IllegalStateException 制造无意义堆栈
        if (response.isCommitted()) {
            log.warn("响应已提交，本次导出放弃 | 个人数据 | userId={}", userId);
            return;
        }

        String fileName = "个人数据_" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        response.reset();
        // 契约（01-auth-user.md §9）固定为 application/octet-stream
        response.setContentType("application/octet-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"export.xlsx\"; filename*=UTF-8''" + encoded);

        try (OutputStream out = response.getOutputStream()) {
            ExcelWriter excelWriter = EasyExcel.write(out).build();
            try {
                excelWriter.write(profileRows, sheet(0, "个人资料", DataExportProfileVO.class));
                excelWriter.write(elderRows, sheet(1, "绑定老人", DataExportElderVO.class));
                excelWriter.write(orderRows, sheet(2, "订单记录", DataExportOrderVO.class));
                excelWriter.write(reviewRows, sheet(3, "评价记录", DataExportReviewVO.class));
                excelWriter.write(messageRows, sheet(4, "消息记录", DataExportMessageVO.class));
            } finally {
                excelWriter.finish();
            }
            log.info("个人数据导出完成 | userId={} | 订单 {} 行 | 评价 {} 行 | 消息 {} 行",
                    userId, orderRows.size(), reviewRows.size(), messageRows.size());
        } catch (IOException e) {
            // 通常是客户端中途断开下载，响应头已发出，回不了 JSON 错误体，只能记日志
            log.warn("个人数据导出写出失败（可能是客户端中断下载） | userId={} | {}", userId, e.getMessage());
        } catch (RuntimeException e) {
            // 与 ExportServiceImpl 同一判据：响应已提交 = 客户端中断（WARN 放弃）；
            // 未提交 = 真正的生成故障，抛出让前端能提示
            if (response.isCommitted()) {
                log.warn("个人数据导出中断（响应已提交，客户端多半已取消下载） | userId={} | {}", userId, e.getMessage());
                return;
            }
            log.error("个人数据导出失败 | userId={}", userId, e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "导出失败，请稍后重试");
        }
    }

    /* ================================================================== */
    /* 导出行装配（全部先过 MaskUtil 再写入，不依赖事后扫描）                    */
    /* ================================================================== */

    private DataExportProfileVO profileRow(SysUser user) {
        DataExportProfileVO row = new DataExportProfileVO();
        row.setId(user.getId());
        row.setUsername(user.getUsername());
        row.setNickname(user.getNickname());
        row.setRealName(MaskUtil.name(user.getRealName()));
        row.setPhone(MaskUtil.phone(user.getPhone()));
        row.setRoleLabel(RoleConstants.Role.labelOf(user.getRole()));
        row.setStatusLabel(AccountStatus.DISABLED.equals(user.getStatus()) ? "已封禁" : "正常");
        row.setCreateTime(fmt(user.getCreateTime()));
        return row;
    }

    /** 仅 FAMILY 有绑定老人；其余角色返回空表（sheet 只有表头） */
    private List<DataExportElderVO> elderRows(SysUser user) {
        if (!RoleConstants.FAMILY.equals(user.getRole())) {
            return Collections.emptyList();
        }
        List<FamilyElderRelation> relations = familyElderRelationMapper.selectList(
                Wrappers.<FamilyElderRelation>lambdaQuery()
                        .eq(FamilyElderRelation::getFamilyId, user.getId())
                        .orderByAsc(FamilyElderRelation::getId));
        if (relations.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> elderIds = relations.stream().map(FamilyElderRelation::getElderId).toList();
        Map<Long, ElderProfile> elders = elderProfileMapper.selectList(
                        Wrappers.<ElderProfile>lambdaQuery().in(ElderProfile::getId, elderIds))
                .stream().collect(Collectors.toMap(ElderProfile::getId, Function.identity()));

        List<DataExportElderVO> rows = new ArrayList<>(relations.size());
        for (FamilyElderRelation relation : relations) {
            ElderProfile elder = elders.get(relation.getElderId());
            DataExportElderVO row = new DataExportElderVO();
            row.setElderId(relation.getElderId());
            row.setElderName(elder == null ? null : MaskUtil.name(elder.getName()));
            row.setRelationLabel(RelationType.labelOf(relation.getRelation()));
            // ElderBindType / BindStatus 没有 labelOf（与 ElderVO 同一取舍：手工 of + getLabel）
            ElderBindType bindType = ElderBindType.of(relation.getBindType());
            row.setBindTypeLabel(bindType == null ? relation.getBindType() : bindType.getLabel());
            BindStatus bindStatus = BindStatus.of(relation.getStatus());
            row.setStatusLabel(bindStatus == null ? relation.getStatus() : bindStatus.getLabel());
            row.setBindTime(fmt(relation.getBindTime()));
            rows.add(row);
        }
        return rows;
    }

    /** 本人作为下单家属或接单陪诊员的全部订单（不含对方姓名 / 详细地址 / 坐标） */
    private List<DataExportOrderVO> orderRows(Long userId) {
        List<CompanionOrder> orders = companionOrderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .and(w -> w.eq(CompanionOrder::getFamilyId, userId)
                        .or().eq(CompanionOrder::getCompanionId, userId))
                .orderByDesc(CompanionOrder::getId));
        List<DataExportOrderVO> rows = new ArrayList<>(orders.size());
        for (CompanionOrder order : orders) {
            DataExportOrderVO row = new DataExportOrderVO();
            row.setOrderNo(order.getOrderNo());
            row.setMyRoleLabel(userId.equals(order.getFamilyId()) ? "下单家属" : "接单陪诊员");
            row.setHospital(order.getHospital());
            row.setDepartment(order.getDepartment());
            row.setVisitTime(fmt(order.getVisitTime()));
            row.setStatusLabel(OrderStatus.labelOf(order.getStatus()));
            row.setFee(order.getFee() == null ? null : order.getFee().toPlainString());
            row.setPaymentStatusLabel(PaymentStatus.labelOf(order.getPaymentStatus()));
            row.setCreateTime(fmt(order.getCreateTime()));
            rows.add(row);
        }
        return rows;
    }

    /** 家属导出「我发出的评价」，陪诊员导出「评价我的评价」 */
    private List<DataExportReviewVO> reviewRows(Long userId) {
        List<OrderReview> reviews = orderReviewMapper.selectList(Wrappers.<OrderReview>lambdaQuery()
                .and(w -> w.eq(OrderReview::getFamilyId, userId)
                        .or().eq(OrderReview::getCompanionId, userId))
                .orderByDesc(OrderReview::getId));
        List<DataExportReviewVO> rows = new ArrayList<>(reviews.size());
        for (OrderReview review : reviews) {
            DataExportReviewVO row = new DataExportReviewVO();
            row.setOrderNo(review.getOrderNo());
            row.setMyRoleLabel(userId.equals(review.getFamilyId()) ? "评价人" : "被评价陪诊员");
            row.setScore(review.getScore());
            row.setContent(review.getContent());
            row.setAnonymousLabel(Integer.valueOf(1).equals(review.getIsAnonymous()) ? "匿名" : "实名");
            row.setCreateTime(fmt(review.getCreateTime()));
            rows.add(row);
        }
        return rows;
    }

    /** 本人站内信收件箱（正文发信时已由后端模板脱敏） */
    private List<DataExportMessageVO> messageRows(Long userId) {
        List<InternalMessage> messages = internalMessageMapper.selectList(Wrappers.<InternalMessage>lambdaQuery()
                .eq(InternalMessage::getReceiverId, userId)
                .orderByDesc(InternalMessage::getId));
        List<DataExportMessageVO> rows = new ArrayList<>(messages.size());
        for (InternalMessage message : messages) {
            DataExportMessageVO row = new DataExportMessageVO();
            row.setTitle(message.getTitle());
            row.setTypeLabel(MessageType.labelOf(message.getType()));
            row.setContent(message.getContent());
            row.setReadLabel(Integer.valueOf(1).equals(message.getIsRead()) ? "已读" : "未读");
            row.setCreateTime(fmt(message.getCreateTime()));
            rows.add(row);
        }
        return rows;
    }

    private static WriteSheet sheet(int index, String name, Class<?> head) {
        return EasyExcel.writerSheet(index, name).head(head)
                // 自适应列宽：数字与中文混排时固定列宽要么截断要么留大片空白
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .build();
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? null : time.format(CELL_TIME);
    }

    /**
     * 取当前登录用户实体，并要求账号处于「正常」状态。
     *
     * <p>令牌有效但账号已被物理删除时返回 401 而不是 404 —— 前端拿到 401 会清空登录态
     * 跳登录页，这是正确的处置；拿到 404 只会弹个「资源不存在」然后卡在原地。</p>
     *
     * <p>封禁账号即使 JWT 仍有效（ver 未变），任何写操作都要拒绝 —— 与
     * {@code AuthServiceImpl.currentUser} 的禁用拦截保持一致，避免出现
     * 「改密后被封、但旧令牌还能改昵称」的越权裂缝。</p>
     */
    private SysUser requireActiveCurrentUser() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        return user;
    }
}
