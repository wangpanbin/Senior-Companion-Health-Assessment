package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.ElderBindType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RelationType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.ValidationPatterns;
import org.company.nianglin.dto.ElderBindDTO;
import org.company.nianglin.dto.ElderCreateDTO;
import org.company.nianglin.dto.ElderQuery;
import org.company.nianglin.dto.ElderUpdateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.util.AesUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.ElderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 老人档案服务实现。
 *
 * <h3>为什么每个方法都要先做归属校验</h3>
 *
 * <p>{@code @PreAuthorize("hasRole('FAMILY')")} 只保证「你是家属」，它管不了
 * 「这个老人是不是你家的」。家属 A 拿着一个合法的 FAMILY 令牌去请求
 * {@code /api/user/elder/402}，角色校验会痛快放行 —— 能拦住这一步的只有
 * {@link #requireAccessible(Long)} / {@link #requireOwnedByFamily(Long)}。
 * 这两个方法是本模块真正的安全边界，其余代码都只是围绕它们的业务处理。</p>
 *
 * <h3>「先查存在、再查归属」的顺序是有意的</h3>
 *
 * <p>分开返回 2001（不存在）与 2006（无权）会泄露「某个档案 ID 是否存在」。
 * 单看这是个很小的信息泄露，但换来的是排障效率：
 * 前端拿到 2006 知道要去绑老人，拿到 2001 知道档案被删了，两者的处置完全不同。
 * 本项目的档案 ID 只是自增数字、不含业务含义，泄露存在性没有实际收益，
 * 因此选择可维护性。<b>涉及订单金额、投诉内容这类接口时不能照抄这个取舍。</b></p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElderServiceImpl implements ElderService {

    /** 「进行中」的订单状态：这三种状态下不允许删除档案或解绑 */
    private static final List<String> ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PENDING.name(),
            OrderStatus.ACCEPTED.name(),
            OrderStatus.IN_SERVICE.name());

    private static final Pattern PHONE_PATTERN = Pattern.compile(ValidationPatterns.PHONE);

    private final ElderProfileMapper elderProfileMapper;
    private final FamilyElderRelationMapper relationMapper;
    private final SysUserMapper sysUserMapper;
    private final CompanionOrderMapper companionOrderMapper;
    private final SecurityProperties securityProperties;

    /* ================================================================== */
    /* 列表                                                                */
    /* ================================================================== */

    @Override
    public PageResult<ElderVO> page(ElderQuery query) {
        Long familyId = SecurityUtils.currentUserId();
        List<Long> elderIds = boundElderIds(familyId);
        if (elderIds.isEmpty()) {
            // 必须提前返回：MyBatis-Plus 的 in() 传空集合会生成 `IN ()`，
            // MySQL 直接语法报错，而不是「查不到数据」
            return PageResult.empty(query.normalizedPage(), query.normalizedSize());
        }

        LambdaQueryWrapper<ElderProfile> wrapper = Wrappers.<ElderProfile>lambdaQuery()
                .in(ElderProfile::getId, elderIds)
                .like(StringUtils.hasText(query.getKeyword()),
                        ElderProfile::getName, query.getKeyword() == null ? null : query.getKeyword().trim())
                .eq(StringUtils.hasText(query.getBindStatus()),
                        ElderProfile::getBindStatus, query.getBindStatus())
                .orderByDesc(ElderProfile::getCreateTime)
                .orderByDesc(ElderProfile::getId);

        Page<ElderProfile> result = elderProfileMapper.selectPage(query.toMpPage(), wrapper);
        return PageResult.of(result, ElderVO::ofList);
    }

    /* ================================================================== */
    /* 新增                                                                */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ElderCreateDTO dto) {
        Long familyId = SecurityUtils.currentUserId();

        ElderProfile elder = new ElderProfile();
        elder.setName(dto.getName().trim());
        elder.setGender(normalizeEnum(dto.getGender()));
        elder.setBirthDate(dto.getBirthDate());
        elder.setIdCard(encryptIdCard(dto.getIdCard()));
        elder.setPhone(clearable(dto.getPhone()));
        elder.setAddress(clearable(dto.getAddress()));
        elder.setEmergencyContact(clearable(dto.getEmergencyContact()));
        elder.setEmergencyPhone(clearable(dto.getEmergencyPhone()));
        elder.setMedicalHistory(clearable(dto.getMedicalHistory()));
        elder.setAllergyHistory(clearable(dto.getAllergyHistory()));
        elder.setMobilityLevel(clearable(dto.getMobilityLevel()));
        elder.setFavoriteHospital(clearable(dto.getFavoriteHospital()));
        elder.setRemark(clearable(dto.getRemark()));
        // 建档即绑定：否则家属刚建完档案在列表里看不到它，会以为没保存成功
        elder.setBindStatus(BindStatus.BOUND.name());
        elder.setCreateBy(familyId);
        elderProfileMapper.insert(elder);

        upsertBoundRelation(familyId, elder.getId(), null, ElderBindType.CREATE);

        log.info("老人档案已创建并自动绑定 | elderId={} | familyId={}", elder.getId(), familyId);
        return elder.getId();
    }

    /* ================================================================== */
    /* 详情 / 修改 / 删除                                                  */
    /* ================================================================== */

    @Override
    public ElderVO detail(Long elderId) {
        ElderProfile elder = requireAccessible(elderId);

        // 解密后立刻脱敏：明文只在这两行里存在，不会进入 VO 对象、不会被日志带走
        ElderVO vo = ElderVO.ofDetail(elder, maskIdCard(elder.getIdCard()));

        LoginUser me = SecurityUtils.currentUser();
        if (RoleConstants.FAMILY.equals(me.role())) {
            FamilyElderRelation relation = findRelation(me.userId(), elderId);
            if (relation != null) {
                vo.withRelation(relation.getRelation());
            }
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long elderId, ElderUpdateDTO dto) {
        ElderProfile elder = requireOwnedByFamily(elderId);

        LambdaUpdateWrapper<ElderProfile> update = Wrappers.<ElderProfile>lambdaUpdate()
                .eq(ElderProfile::getId, elder.getId());

        boolean any = false;

        if (dto.getName() != null) {
            update.set(ElderProfile::getName, requireText(dto.getName(), "姓名"));
            any = true;
        }
        if (dto.getGender() != null) {
            update.set(ElderProfile::getGender, normalizeEnum(dto.getGender()));
            any = true;
        }
        if (dto.getBirthDate() != null) {
            update.set(ElderProfile::getBirthDate, dto.getBirthDate());
            any = true;
        }
        if (dto.getIdCard() != null) {
            update.set(ElderProfile::getIdCard, encryptIdCard(dto.getIdCard()));
            any = true;
        }
        if (dto.getPhone() != null) {
            update.set(ElderProfile::getPhone, clearable(dto.getPhone()));
            any = true;
        }
        if (dto.getAddress() != null) {
            update.set(ElderProfile::getAddress, clearable(dto.getAddress()));
            any = true;
        }
        if (dto.getEmergencyContact() != null) {
            update.set(ElderProfile::getEmergencyContact, clearable(dto.getEmergencyContact()));
            any = true;
        }
        if (dto.getEmergencyPhone() != null) {
            update.set(ElderProfile::getEmergencyPhone, clearable(dto.getEmergencyPhone()));
            any = true;
        }
        if (dto.getMedicalHistory() != null) {
            update.set(ElderProfile::getMedicalHistory, clearable(dto.getMedicalHistory()));
            any = true;
        }
        if (dto.getAllergyHistory() != null) {
            update.set(ElderProfile::getAllergyHistory, clearable(dto.getAllergyHistory()));
            any = true;
        }
        if (dto.getMobilityLevel() != null) {
            update.set(ElderProfile::getMobilityLevel, clearable(dto.getMobilityLevel()));
            any = true;
        }
        if (dto.getFavoriteHospital() != null) {
            update.set(ElderProfile::getFavoriteHospital, clearable(dto.getFavoriteHospital()));
            any = true;
        }
        if (dto.getRemark() != null) {
            update.set(ElderProfile::getRemark, clearable(dto.getRemark()));
            any = true;
        }

        if (!any) {
            return;
        }

        // 这里刻意用 update(null, wrapper) 而不是 updateById(entity)：
        // updateById 会跳过 null 字段，而「清空地址」恰恰就是把列设成 null。
        // update 1 条：日志不打姓名与地址
        int rows = elderProfileMapper.update(null, update);
        log.info("老人档案已修改 | elderId={} | affectedRows={}", elderId, rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long elderId) {
        ElderProfile elder = requireOwnedByFamily(elderId);
        assertNoActiveOrder(elderId);

        // 逻辑删除（deleted = 1）：保留与历史订单、用药记录的关联，
        // 物理删除会让「三个月前那个老人的订单详情」变成一片空白
        elderProfileMapper.deleteById(elder.getId());

        // 绑定关系一并解除，否则列表查询会捞出指向已删档案的关系行
        unbindAllRelations(elderId);

        log.info("老人档案已逻辑删除并解绑 | elderId={}", elderId);
    }

    /* ================================================================== */
    /* 绑定 / 解绑                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long bind(ElderBindDTO dto) {
        Long familyId = SecurityUtils.currentUserId();

        ElderBindType bindType = ElderBindType.of(dto.getBindType());
        if (bindType == ElderBindType.INVITE_CODE) {
            // 一期不开放：elder_profile / sys_user 都没有邀请码列，
            // 实现它要新增 Flyway 迁移，而 Entity 是从 V1 单向生成的，会漂移
            throw new BusinessException(ResultCode.NOT_IMPLEMENTED,
                    "邀请码绑定暂未开放，请改用手机号绑定");
        }
        if (bindType != ElderBindType.PHONE) {
            // @Pattern 已经挡住非法值，这里是兜底，防止将来枚举扩了值却忘了改校验
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的绑定方式");
        }

        String phone = dto.getBindValue().trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号格式不正确");
        }

        SysUser elderUser = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, phone)
                .eq(SysUser::getRole, RoleConstants.ELDER));
        // 统一错误码：账号不存在 / 已封禁 / 未建档 / 已被他人绑定 等多种情况
        // 对调用方只暴露「找不到可绑定的老人账号」一条信息，
        // 否则攻击者可以借此枚举手机号对应的老人账号状态（防账号枚举定时侧信道同样思路）
        if (elderUser == null
                || AccountStatus.DISABLED.equals(elderUser.getStatus())) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND, "未找到可绑定的老人账号");
        }

        ElderProfile elder = findElderByUserId(elderUser.getId());
        if (elder == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND, "未找到可绑定的老人账号");
        }

        // 一个老人只允许被一位主要家属绑定（避免操作权纠纷）
        FamilyElderRelation occupied = findBoundRelationOfOther(elder.getId(), familyId);
        if (occupied != null) {
            // 对外仍用「找不到可绑定的老人账号」屏蔽已被他人绑定这条分支
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND, "未找到可绑定的老人账号");
        }

        FamilyElderRelation mine = findRelation(familyId, elder.getId());
        if (mine != null && BindStatus.BOUND.name().equals(mine.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "该老人已绑定到您的账号");
        }

        upsertBoundRelation(familyId, elder.getId(), dto.getRelation(), ElderBindType.PHONE);

        // 档案侧同步：标记已绑定；若档案此前没关联账号，顺便把账号关联上
        LambdaUpdateWrapper<ElderProfile> update = Wrappers.<ElderProfile>lambdaUpdate()
                .eq(ElderProfile::getId, elder.getId())
                .set(ElderProfile::getBindStatus, BindStatus.BOUND.name());
        if (elder.getUserId() == null) {
            update.set(ElderProfile::getUserId, elderUser.getId());
        }
        elderProfileMapper.update(null, update);

        // 日志只打 ID 与关系，不打手机号
        log.info("家属绑定老人成功 | familyId={} | elderId={} | relation={}",
                familyId, elder.getId(), dto.getRelation());
        return elder.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long elderId) {
        ElderProfile elder = elderProfileMapper.selectById(elderId);
        if (elder == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND);
        }

        Long familyId = SecurityUtils.currentUserId();
        FamilyElderRelation relation = findRelation(familyId, elderId);
        if (relation == null || !BindStatus.BOUND.name().equals(relation.getStatus())) {
            // 关系不存在，或早就解绑过了 —— 两种情况对用户都是「你没绑着这个老人」
            throw new BusinessException(ResultCode.RELATION_NOT_FOUND);
        }

        assertNoActiveOrder(elderId);

        relationMapper.update(null, Wrappers.<FamilyElderRelation>lambdaUpdate()
                .eq(FamilyElderRelation::getId, relation.getId())
                .set(FamilyElderRelation::getStatus, BindStatus.UNBOUND.name())
                .set(FamilyElderRelation::getUnbindTime, LocalDateTime.now()));

        // 没有其他家属仍绑着这个老人时，档案标记为未绑定
        if (countBoundRelations(elderId) == 0) {
            elderProfileMapper.update(null, Wrappers.<ElderProfile>lambdaUpdate()
                    .eq(ElderProfile::getId, elderId)
                    .set(ElderProfile::getBindStatus, BindStatus.UNBOUND.name()));
        }

        log.info("家属解绑老人成功 | familyId={} | elderId={}", familyId, elderId);
    }

    /* ================================================================== */
    /* 归属校验（本模块的安全边界，M4+ 复用）                                */
    /* ================================================================== */

    @Override
    public ElderProfile requireAccessible(Long elderId) {
        ElderProfile elder = elderProfileMapper.selectById(elderId);
        if (elder == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND);
        }

        LoginUser me = SecurityUtils.currentUser();

        // 陪诊员读取老人档案：M3 暂未对陪诊员开放档案查看权限。
        // 必须显式抛出而不是走到末尾的 NO_PERMISSION_FOR_ELDER，
        // 否则未来给 controller 放开 COMPANION 白名单后，陪诊员拿到的是
        // 「绑定关系问题」的错误提示，实际是产品规则不支持，会误导排障
        if (RoleConstants.COMPANION.equals(me.role())) {
            throw new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER,
                    "陪诊员暂不可查看老人档案，请联系管理员申请权限");
        }

        // 管理员读任何档案
        if (RoleConstants.ADMIN.equals(me.role())) {
            return elder;
        }

        // 家属读自己绑定的档案
        if (RoleConstants.FAMILY.equals(me.role())) {
            FamilyElderRelation relation = findRelation(me.userId(), elderId);
            if (relation != null && BindStatus.BOUND.name().equals(relation.getStatus())) {
                return elder;
            }
        }

        // 老人读自己的档案（GET 不被只读拦截器拦，所以这条路径是活的）
        if (RoleConstants.ELDER.equals(me.role())
                && elder.getUserId() != null
                && elder.getUserId().equals(me.userId())) {
            return elder;
        }

        throw new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER);
    }

    /**
     * 写操作的归属校验：必须是<b>当前绑定的那位家属</b>。
     *
     * <p>刻意不给 ADMIN 开后门：管理员改档案属于「代操作」，应该走 M9 的
     * 后台接口并写 {@code admin_oper_log}，而不是借用家属接口悄悄改 ——
     * 后者会让审计日志里查不到到底是谁改的。</p>
     */
    private ElderProfile requireOwnedByFamily(Long elderId) {
        ElderProfile elder = elderProfileMapper.selectById(elderId);
        if (elder == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND);
        }
        FamilyElderRelation relation = findRelation(SecurityUtils.currentUserId(), elderId);
        if (relation == null || !BindStatus.BOUND.name().equals(relation.getStatus())) {
            throw new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER);
        }
        return elder;
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    private List<Long> boundElderIds(Long familyId) {
        return relationMapper.selectList(Wrappers.<FamilyElderRelation>lambdaQuery()
                        .eq(FamilyElderRelation::getFamilyId, familyId)
                        .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name()))
                .stream()
                .map(FamilyElderRelation::getElderId)
                .distinct()
                .toList();
    }

    /**
     * 查某对「家属-老人」的关系行。
     *
     * <p>刻意不用 {@code selectOne}：{@code family_elder_relation} 没有
     * {@code (family_id, elder_id)} 唯一索引，并发下可能出现重复行，
     * 而 {@code selectOne} 遇到多行会直接抛 {@code TooManyResultsException} ——
     * 那会把一个「数据脏了」的问题放大成「接口 500」。取 ID 最小的一行即可。</p>
     */
    private FamilyElderRelation findRelation(Long familyId, Long elderId) {
        List<FamilyElderRelation> list = relationMapper.selectList(Wrappers.<FamilyElderRelation>lambdaQuery()
                .eq(FamilyElderRelation::getFamilyId, familyId)
                .eq(FamilyElderRelation::getElderId, elderId)
                .orderByAsc(FamilyElderRelation::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查是否已被「别人」绑定 */
    private FamilyElderRelation findBoundRelationOfOther(Long elderId, Long familyId) {
        List<FamilyElderRelation> list = relationMapper.selectList(Wrappers.<FamilyElderRelation>lambdaQuery()
                .eq(FamilyElderRelation::getElderId, elderId)
                .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name())
                .ne(FamilyElderRelation::getFamilyId, familyId)
                .orderByAsc(FamilyElderRelation::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    private ElderProfile findElderByUserId(Long userId) {
        List<ElderProfile> list = elderProfileMapper.selectList(Wrappers.<ElderProfile>lambdaQuery()
                .eq(ElderProfile::getUserId, userId)
                .orderByAsc(ElderProfile::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    private long countBoundRelations(Long elderId) {
        Long count = relationMapper.selectCount(Wrappers.<FamilyElderRelation>lambdaQuery()
                .eq(FamilyElderRelation::getElderId, elderId)
                .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name()));
        return count == null ? 0L : count;
    }

    /**
     * 建立或恢复绑定关系。
     *
     * <p>已存在关系行时走 UPDATE 而不是插新行 —— 反复解绑再重绑不会堆出一串历史垃圾，
     * 否则「这个老人被几个人管过」这种查询会被自己造出来的噪音污染。</p>
     *
     * @param relation 与老人关系；传 {@code null} 表示不修改（建档场景用默认值）
     */
    private void upsertBoundRelation(Long familyId, Long elderId, String relation, ElderBindType bindType) {
        LocalDateTime now = LocalDateTime.now();
        FamilyElderRelation existing = findRelation(familyId, elderId);

        if (existing == null) {
            FamilyElderRelation created = new FamilyElderRelation();
            created.setFamilyId(familyId);
            created.setElderId(elderId);
            created.setRelation(relation == null ? RelationType.OTHER.name() : relation);
            created.setBindType(bindType.name());
            created.setIsDefault(1);
            created.setStatus(BindStatus.BOUND.name());
            created.setBindTime(now);
            relationMapper.insert(created);
            return;
        }

        LambdaUpdateWrapper<FamilyElderRelation> update = Wrappers.<FamilyElderRelation>lambdaUpdate()
                .eq(FamilyElderRelation::getId, existing.getId())
                .set(FamilyElderRelation::getBindType, bindType.name())
                .set(FamilyElderRelation::getStatus, BindStatus.BOUND.name())
                .set(FamilyElderRelation::getBindTime, now)
                // 重新绑定要把解绑时间清掉，否则「已解绑时间」留着会误导对账
                .set(FamilyElderRelation::getUnbindTime, null);
        if (relation != null) {
            update.set(FamilyElderRelation::getRelation, relation);
        }
        relationMapper.update(null, update);
    }

    private void unbindAllRelations(Long elderId) {
        relationMapper.update(null, Wrappers.<FamilyElderRelation>lambdaUpdate()
                .eq(FamilyElderRelation::getElderId, elderId)
                .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name())
                .set(FamilyElderRelation::getStatus, BindStatus.UNBOUND.name())
                .set(FamilyElderRelation::getUnbindTime, LocalDateTime.now()));
    }

    /** 存在进行中订单时禁止删除 / 解绑（订单与老人有外键意义上的强关联，靠这里维持） */
    private void assertNoActiveOrder(Long elderId) {
        Long count = companionOrderMapper.selectCount(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getElderId, elderId)
                .in(CompanionOrder::getStatus, ACTIVE_ORDER_STATUSES));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "该老人存在进行中的陪诊订单，请先处理完再操作");
        }
    }

    /** 加密身份证号；为空返回 {@code null}（列本身可空，不存空串） */
    private String encryptIdCard(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return null;
        }
        return AesUtil.encrypt(plainText.trim(), securityProperties.idCardKey());
    }

    /** 解密后立即脱敏；明文不返回给调用方 */
    private String maskIdCard(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            return null;
        }
        String plain = AesUtil.decrypt(cipherText, securityProperties.idCardKey());
        return MaskUtil.idCard(plain);
    }

    /** 空串或纯空白视为「清空」，统一归一成 {@code null} */
    private static String clearable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 必填文本：归一化后仍为空则报参数错误（对应库里的 NOT NULL 列） */
    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, fieldName + "不能为空");
        }
        return value.trim();
    }

    /** 枚举值统一存大写英文，避免 MALE / male 混进库里 */
    private static String normalizeEnum(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
