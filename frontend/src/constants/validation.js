/**
 * 前端校验口径 —— 与后端 {@code ValidationPatterns} 保持**同一份**规则。
 *
 * <h3>为什么要有这个文件</h3>
 *
 * <p>此前同一个概念在不同页面各写各的正则，导致两个方向的问题：</p>
 * <ul>
 *   <li><b>前端比后端松</b>：{@code companion/entry.vue} 的身份证只校验
 *       {@code ^\d{17}[\dXx]$}，比后端的含地址码 + 日期段的严格式松 ——
 *       前端放行、后端 400，用户只看到一个泛化错误提示；</li>
 *   <li><b>同一个字段两套规则</b>：姓名在 {@code elder-bind.vue} 查 2-20 字，
 *       在 {@code entry.vue} 只查非空，而后端两处都要求 2-20。</li>
 * </ul>
 *
 * <p>把规则收口到这里，前端与后端各写一份但**同源**：改动时两边一起改，
 * 注释里标明后端对应位置。</p>
 *
 * @author 银龄伴诊团队
 */

/* ------------------------------------------------------------------ */
/* 与后端 ValidationPatterns 一一对应                                    */
/* ------------------------------------------------------------------ */

/**
 * 手机号：11 位，1 开头，第二位 3-9。
 *
 * <p>后端：{@code backend/.../constant/ValidationPatterns.java#PHONE}</p>
 */
export const PHONE_RE = /^1[3-9]\d{9}$/

/**
 * 身份证号：18 位，含地址码（6 位）、出生日期（8 位）与校验位。
 *
 * <p>后端：{@code backend/.../constant/ValidationPatterns.java#ID_CARD}</p>
 *
 * <p>⚠️ 不要用 {@code ^\d{17}[\dXx]$} 这类「只数长度」的松式 ——
 * 那会让非法身份证一路走到后端才被拒，用户看到的是「请求参数有误」这种
 * 毫无指向性的提示。</p>
 */
export const ID_CARD_RE = /^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/

/**
 * 密码：6-32 位，且需同时包含字母与数字。
 *
 * <p>后端：{@code RegisterDTO#password} / {@code ResetPasswordDTO#newPassword}</p>
 */
export const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d)\S{6,32}$/

/* ------------------------------------------------------------------ */
/* 长度口径                                                            */
/* ------------------------------------------------------------------ */

/** 姓名长度下限（后端 {@code RegisterDTO#nickname} / {@code CompanionApplyDTO#realName}） */
export const NAME_MIN = 2
/** 姓名长度上限 */
export const NAME_MAX = 20

/* ------------------------------------------------------------------ */
/* 常用错误文案（统一措辞，避免各页各写）                                 */
/* ------------------------------------------------------------------ */

export const PHONE_MSG = '手机号格式不正确（11 位，1 开头）'
export const ID_CARD_MSG = '身份证号格式不正确'
export const PASSWORD_MSG = '密码为 6-32 位，且需同时包含字母与数字'
export const NAME_MSG = `姓名长度需在 ${NAME_MIN}-${NAME_MAX} 个字符之间`
