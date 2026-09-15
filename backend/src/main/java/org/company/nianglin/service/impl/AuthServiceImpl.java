package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ClientInfo;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.AuthLogConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ChangePasswordDTO;
import org.company.nianglin.dto.LoginDTO;
import org.company.nianglin.dto.RegisterDTO;
import org.company.nianglin.entity.SysLoginLog;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysLoginLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.JwtProperties;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.security.TokenPayload;
import org.company.nianglin.service.AuthService;
import org.company.nianglin.service.CaptchaService;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.LoginVO;
import org.company.nianglin.vo.TokenVO;
import org.company.nianglin.vo.UserInfoVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 认证与账号服务实现。
 *
 * <p><b>关于事务边界（重要）</b>：{@link #login} / {@link #refresh} / {@link #logout}
 * <b>刻意不加</b> {@code @Transactional}。原因是登录失败路径需要「先落一条失败日志，
 * 再抛业务异常」，若整个过程在一个事务里，异常抛出会把刚写的失败日志一起回滚 ——
 * 而这条日志恰恰是安全审计最需要的那条。注册与改密是纯写操作，保留事务。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 手机号形态，用于日志脱敏判断 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** {@code sys_login_log.username} 为 VARCHAR(50) */
    private static final int USERNAME_MAX_LENGTH = 50;

    private final SysUserMapper sysUserMapper;
    private final SysLoginLogMapper sysLoginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenStore tokenStore;
    private final SecurityProperties securityProperties;

    /* ================================================================== */
    /* 注册                                                                */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterDTO dto) {
        captchaService.validate(dto.getCaptchaKey(), dto.getCaptchaCode());

        // DTO 的正则已经排除了 ADMIN，这里再拦一次：正则是可以被后人放宽的，
        // 而「注册接口永远不能产出管理员」是权限体系的根，值得冗余一道
        if (RoleConstants.Role.of(dto.getRole()) == null || RoleConstants.ADMIN.equals(dto.getRole())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "注册角色只能为 ELDER / FAMILY / COMPANION");
        }

        if (existsByUsername(dto.getUsername())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该用户名已被占用");
        }
        if (existsByPhone(dto.getPhone())) {
            throw new BusinessException(ResultCode.PHONE_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        // 合规红线：密码只经 BCrypt 单向哈希入库，任何情况下不存明文 / 可逆密文
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setRole(dto.getRole());
        user.setStatus(AccountStatus.NORMAL);
        user.setNeedChangePassword(0);

        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 上面的存在性检查与 insert 之间存在竞态窗口，最终由数据库唯一索引兜底
            throw translateDuplicateKey(e);
        }

        log.info("用户注册成功 | userId={} | username={} | role={}", user.getId(), user.getUsername(), user.getRole());
        return user.getId();
    }

    /* ================================================================== */
    /* 登录                                                                */
    /* ================================================================== */

    @Override
    public LoginVO login(LoginDTO dto, ClientInfo client) {
        String account = dto.getUsername().trim();

        // ① 验证码放在最前：否则暴力破解脚本可以绕过验证码直接刷密码
        try {
            captchaService.validate(dto.getCaptchaKey(), dto.getCaptchaCode());
        } catch (BusinessException e) {
            writeLoginLog(null, account, AuthLogConstants.TYPE_LOGIN, AuthLogConstants.RESULT_FAIL,
                    AuthLogConstants.REASON_BAD_CAPTCHA, client);
            throw e;
        }

        // ② 锁定检查：连续失败达阈值后，正确密码也不再受理
        long failCount = tokenStore.loginFailCount(account);
        if (failCount >= securityProperties.getLoginFailThreshold()) {
            writeLoginLog(null, account, AuthLogConstants.TYPE_LOGIN, AuthLogConstants.RESULT_FAIL,
                    AuthLogConstants.REASON_LOGIN_LOCKED, client);
            log.warn("登录被锁定 | account={}", maskAccount(account));
            throw new BusinessException(ResultCode.LOGIN_LOCKED);
        }

        // ③ 账号密码：账号不存在与密码错误共用同一错误码与提示，防账号枚举
        SysUser user = findUserByAccount(account);
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            long current = tokenStore.increaseLoginFail(account, securityProperties.loginLockSeconds());
            writeLoginLog(user == null ? null : user.getId(), account, AuthLogConstants.TYPE_LOGIN,
                    AuthLogConstants.RESULT_FAIL,
                    user == null ? AuthLogConstants.REASON_ACCOUNT_NOT_FOUND : AuthLogConstants.REASON_BAD_PASSWORD,
                    client);
            log.warn("登录失败 | account={} | 本次窗口累计失败 {} 次", maskAccount(account), current);
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        // ④ 封禁校验放在密码校验之后：避免未认证者通过 1002/1001 的差异探测出账号是否存在
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            writeLoginLog(user.getId(), account, AuthLogConstants.TYPE_LOGIN, AuthLogConstants.RESULT_FAIL,
                    AuthLogConstants.REASON_ACCOUNT_DISABLED, client);
            log.warn("被封禁账号尝试登录 | userId={}", user.getId());
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        // ⑤ 成功：清失败计数、更新最后登录信息、写成功日志
        tokenStore.clearLoginFail(account);
        updateLastLogin(user.getId(), client);

        writeLoginLog(user.getId(), account, AuthLogConstants.TYPE_LOGIN, AuthLogConstants.RESULT_SUCCESS, null, client);
        log.info("登录成功 | userId={} | username={} | role={}", user.getId(), user.getUsername(), user.getRole());

        return buildLoginVO(user);
    }

    /* ================================================================== */
    /* 刷新令牌                                                            */
    /* ================================================================== */

    @Override
    public TokenVO refresh(String refreshToken, ClientInfo client) {
        TokenPayload payload;
        try {
            payload = tokenProvider.parse(refreshToken);
        } catch (BusinessException e) {
            writeLoginLog(null, null, AuthLogConstants.TYPE_REFRESH, AuthLogConstants.RESULT_FAIL,
                    AuthLogConstants.REASON_TOKEN_INVALID, client);
            throw e;
        }

        // 访问令牌不能拿来刷新：否则 accessToken 泄露就等于 refreshToken 泄露
        if (!payload.isRefresh() || tokenStore.isBlacklisted(payload.jti())) {
            writeLoginLog(payload.userId(), payload.username(), AuthLogConstants.TYPE_REFRESH,
                    AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_TOKEN_INVALID, client);
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        SysUser user = sysUserMapper.selectById(payload.userId());
        if (user == null) {
            writeLoginLog(payload.userId(), payload.username(), AuthLogConstants.TYPE_REFRESH,
                    AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_TOKEN_INVALID, client);
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        // 封禁用户在刷新环节立即失效，不能等访问令牌自然过期
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            writeLoginLog(user.getId(), user.getUsername(), AuthLogConstants.TYPE_REFRESH,
                    AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_ACCOUNT_DISABLED, client);
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        // 改密后版本递增，旧刷新令牌同步失效
        int currentVersion = tokenStore.currentPasswordVersion(user.getId());
        if (payload.passwordVersion() != currentVersion) {
            writeLoginLog(user.getId(), user.getUsername(), AuthLogConstants.TYPE_REFRESH,
                    AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_TOKEN_INVALID, client);
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        // refreshToken 不滚动：它本身有 7 天有效期，每次刷新都续签等于令牌永不失效
        String accessToken = tokenProvider.createAccessToken(
                user.getId(), user.getUsername(), user.getRole(), currentVersion);

        writeLoginLog(user.getId(), user.getUsername(), AuthLogConstants.TYPE_REFRESH,
                AuthLogConstants.RESULT_SUCCESS, null, client);
        log.info("刷新访问令牌成功 | userId={}", user.getId());

        return new TokenVO()
                .setAccessToken(accessToken)
                .setExpiresIn(jwtProperties.accessTtlSeconds());
    }

    /* ================================================================== */
    /* 登出                                                                */
    /* ================================================================== */

    @Override
    public void logout(String refreshToken) {
        LoginUser loginUser = SecurityUtils.currentUser();

        // 只把令牌拉黑到「它本来就会过期的那一刻」为止，Redis 不会因为登出而无限膨胀
        long remain = tokenProvider.remainingSeconds(loginUser.expiresAtMillis());
        tokenStore.blacklist(loginUser.jti(), remain);

        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                TokenPayload payload = tokenProvider.parse(refreshToken);
                tokenStore.blacklist(payload.jti(), tokenProvider.remainingSeconds(payload.expiresAtMillis()));
            } catch (BusinessException e) {
                // 刷新令牌本来就无效，没有拉黑的必要。登出必须成功 ——
                // 不能因为一个附属令牌有问题就让用户「退不出去」
                log.debug("登出时 refreshToken 无效，已忽略 | userId={}", loginUser.userId());
            }
        }

        writeLoginLog(loginUser.userId(), loginUser.username(), AuthLogConstants.TYPE_LOGOUT,
                AuthLogConstants.RESULT_SUCCESS, null, null);
        log.info("登出成功 | userId={}", loginUser.userId());
    }

    /* ================================================================== */
    /* 当前用户 / 修改密码                                                  */
    /* ================================================================== */

    @Override
    public UserInfoVO currentUser() {
        Long userId = SecurityUtils.currentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不存在或已被删除");
        }
        // 令牌有效期内账号被封禁 → 立即拒绝，不必等令牌自然过期
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        return UserInfoVO.of(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordDTO dto) {
        LoginUser loginUser = SecurityUtils.currentUser();

        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的新密码不一致");
        }
        if (dto.getNewPassword().equals(dto.getOldPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与原密码相同");
        }

        SysUser user = sysUserMapper.selectById(loginUser.userId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_ERROR);
        }

        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        // 用户已自行改密，管理员重置时打下的「必须改密」标记随之清除
        update.setNeedChangePassword(0);
        sysUserMapper.updateById(update);

        // 版本 +1 → 该用户所有已签发令牌（含当前这一个）立即失效，实现「改密后强制重新登录」
        tokenStore.bumpPasswordVersion(user.getId());

        log.info("修改密码成功，该用户全部令牌已失效 | userId={}", user.getId());
    }

    /* ================================================================== */
    /* 私有方法                                                            */
    /* ================================================================== */

    private LoginVO buildLoginVO(SysUser user) {
        int passwordVersion = tokenStore.currentPasswordVersion(user.getId());
        String accessToken = tokenProvider.createAccessToken(
                user.getId(), user.getUsername(), user.getRole(), passwordVersion);
        String refreshToken = tokenProvider.createRefreshToken(
                user.getId(), user.getUsername(), user.getRole(), passwordVersion);

        return new LoginVO()
                .setAccessToken(accessToken)
                .setRefreshToken(refreshToken)
                .setExpiresIn(jwtProperties.accessTtlSeconds())
                .setUserInfo(UserInfoVO.of(user));
    }

    /** 用户名或手机号登录，两者都可作为账号 */
    private SysUser findUserByAccount(String account) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                // 必须用 and(...) 把 OR 条件整体括起来：MyBatis-Plus 会把逻辑删除条件
                // 以 "AND deleted = 0" 追加在末尾，若不分组就会变成
                // "username = ? OR (phone = ? AND deleted = 0)" ——
                // 结果是「已注销账号用用户名仍能登录」，这是个只在软删数据上暴露的隐蔽越权
                .and(w -> w.eq(SysUser::getUsername, account).or().eq(SysUser::getPhone, account))
                // 固定字面量，无注入风险；兜住「用户名与手机号恰好相等」的极端数据
                .last("LIMIT 1"));
    }

    private boolean existsByUsername(String username) {
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        return count != null && count > 0L;
    }

    private boolean existsByPhone(String phone) {
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, phone));
        return count != null && count > 0L;
    }

    private void updateLastLogin(Long userId, ClientInfo client) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(client == null ? null : client.ip());
        sysUserMapper.updateById(update);
    }

    /**
     * 写登录日志。
     *
     * <p>日志属于审计辅助，<b>写失败绝不能影响主流程</b>：如果这里把异常抛出去，
     * 数据库抖动就会导致「所有人都登不进来」这种比漏一条日志严重得多的故障。</p>
     *
     * <p>⚠️ 只记录粗粒度失败原因，绝不记录密码。</p>
     */
    private void writeLoginLog(Long userId, String username, String type, String status,
                               String failReason, ClientInfo client) {
        try {
            SysLoginLog entity = new SysLoginLog();
            entity.setUserId(userId);
            entity.setUsername(truncate(username, USERNAME_MAX_LENGTH));
            entity.setLoginType(type);
            entity.setStatus(status);
            entity.setFailReason(failReason);
            entity.setIp(client == null ? null : client.ip());
            entity.setUserAgent(client == null ? null : client.userAgent());
            entity.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("写登录日志失败 | username={} | type={} | status={}", maskAccount(username), type, status, e);
        }
    }

    /**
     * 日志里的账号必须脱敏。
     *
     * <p>用户可以用手机号登录，直接打印账号等于把完整手机号写进日志文件，
     * 违反「日志禁止出现完整手机号」这条合规红线。</p>
     */
    private String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return account;
        }
        return PHONE_PATTERN.matcher(account).matches() ? MaskUtil.phone(account) : account;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 把数据库唯一索引冲突翻译成业务错误码 */
    private static BusinessException translateDuplicateKey(DuplicateKeyException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("uk_phone")) {
            return new BusinessException(ResultCode.PHONE_ALREADY_EXISTS);
        }
        if (message.contains("uk_username")) {
            return new BusinessException(ResultCode.PARAM_ERROR, "该用户名已被占用");
        }
        return new BusinessException(ResultCode.CONFLICT, "账号信息已存在，请修改后重试");
    }
}
