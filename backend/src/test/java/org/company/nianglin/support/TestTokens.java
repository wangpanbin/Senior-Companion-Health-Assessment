package org.company.nianglin.support;

import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;

/**
 * 矩阵测试共用的「真实可登录令牌」工厂。
 *
 * <h3>为什么不能把密码版本写死成 0</h3>
 *
 * <p>JWT 载荷里的 {@code ver} 必须与 Redis 的 {@code pwd:version:{userId}} 相等，
 * 认证过滤器才认这张票。种子账号刚建好时这个键不存在，
 * {@link TokenStore#currentPasswordVersion(Long)} 返回默认值 0，于是
 * 「写死 ver=0」在<b>干净的 Redis</b> 上恰好能跑通 —— 直到下面这条路径被触发：</p>
 *
 * <ol>
 *   <li>{@code AuthServiceImpl#logout} 会调用 {@link TokenStore#bumpPasswordVersion(Long)}
 *       —— 登出即吊销该用户全部令牌，所以<b>登出一次，版本号就从 0 变成 1</b>；</li>
 *   <li>该键<b>没有 TTL</b>（{@code ensurePasswordVersion} 用 {@code setIfAbsent} 写入，
 *       不设过期），所以它<b>永久</b>留在 Redis 里；</li>
 *   <li>端到端脚本 {@code e2e_auth.py} 的 E1 用例正是以 {@code elder001} 身份调用
 *       {@code POST /auth/logout}。</li>
 * </ol>
 *
 * <p>结果：跑完一次端到端，{@code pwd:version:201} 变成 1 且再无过期之日；
 * 任何「写死 ver=0 签 elder001 令牌」的用例此后一律拿到 <b>401</b>（验票失败）
 * 而不是预期的 403/200。这类失败极具迷惑性 —— 它看起来是鉴权坏了，
 * 实际是测试自己把一个<b>会随运行历史漂移的运行时状态</b>当成了常量。</p>
 *
 * <h3>本类的约定</h3>
 *
 * <p>签令牌前一律先向 {@link TokenStore} 问一次<b>当前</b>版本号，
 * 与真实登录流程（{@code AuthServiceImpl#buildLoginVO}）完全一致。
 * 这样无论 Redis 被前面的测试改成了什么状态，签出来的都是一张能通过验票的合法令牌，
 * 用例断言的「角色 / 归属 / 状态机」这些<b>真正要测的东西</b>才不会被无关噪声掩盖。</p>
 *
 * <p>反过来说：<b>若某个用例想验证「旧版本的令牌已失效」</b>，请显式传一个不等于
 * 当前版本的 {@code ver}，不要依赖「0 恰好是错的」这种巧合。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
public final class TestTokens {

    private TestTokens() {
    }

    /**
     * 签一张裸 JWT（不含 {@code Bearer } 前缀），供需要自行篡改载荷的用例使用。
     *
     * @param tokenProvider 真实签发器
     * @param tokenStore    密码版本来源（必须注入真实 Bean，不可 mock）
     * @param userId        令牌主体
     * @param role          角色枚举名
     * @param scene         用例场景前缀，仅用于让令牌里的 username 可读、便于排查
     */
    public static String raw(JwtTokenProvider tokenProvider, TokenStore tokenStore,
                            long userId, String role, String scene) {
        int version = tokenStore.currentPasswordVersion(userId);
        return tokenProvider.createAccessToken(userId, scene + "-" + role + "-" + userId, role, version);
    }

    /** 签一张可直接塞进 {@code Authorization} 头的令牌（已含 {@code Bearer } 前缀）。 */
    public static String bearer(JwtTokenProvider tokenProvider, TokenStore tokenStore,
                               long userId, String role, String scene) {
        return "Bearer " + raw(tokenProvider, tokenStore, userId, role, scene);
    }
}
