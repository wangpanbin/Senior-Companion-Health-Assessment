package org.company.nianglin.service;

import jakarta.servlet.http.HttpServletResponse;
import org.company.nianglin.dto.DeactivateAccountDTO;
import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.vo.UserInfoVO;

/**
 * 当前用户资料服务。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §1 / §2，以及
 * {@code docs/api/01-auth-user.md} §8 / §9（V4 账号注销与数据导出）。</p>
 *
 * <p>注意本接口<b>没有任何方法接收 userId 参数</b> —— 全部从登录态取。
 * 这是刻意的：一旦接口签名里出现 userId，早晚会有人从请求参数里把它填进来。
 * 注销与数据导出同样遵守：只能操作「我自己」。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
public interface UserService {

    /** 获取当前登录用户资料 */
    UserInfoVO getProfile();

    /**
     * 更新当前登录用户资料（只更新传入的非空字段）。
     *
     * <p>⚠️ 老人账号（ELDER）调用本方法会被 {@code ElderReadOnlyInterceptor}
     * 在进入 Controller 之前拦掉（403），连这里都到不了 —— 符合「老人账号只读」的产品规则。</p>
     */
    void updateProfile(ProfileUpdateDTO dto);

    /**
     * 注销当前登录账号（V4 · P0-1，{@code docs/api/01-auth-user.md} §8）。
     *
     * <p>成功副作用（同一事务）：逻辑删除 + 禁用 + 姓名 / 手机号 / 身份证去标识化 +
     * 家属绑定关系逻辑删除 + 密码版本递增（全部令牌立即 401）。
     * 存在进行中订单时抛 {@code 11001} 且 {@code data} 带 {@code pendingOrderCount}。</p>
     */
    void deactivate(DeactivateAccountDTO dto);

    /**
     * 导出当前登录用户的个人数据（V4 · P0-1，{@code docs/api/01-auth-user.md} §9）。
     *
     * <p>直写 {@code application/octet-stream}（xlsx 多 sheet），不走统一响应结构。
     * 全部姓名 / 手机号在装配时过 {@code MaskUtil}；身份证号、密码哈希不进入导出范围。</p>
     */
    void dataExport(HttpServletResponse response);
}
