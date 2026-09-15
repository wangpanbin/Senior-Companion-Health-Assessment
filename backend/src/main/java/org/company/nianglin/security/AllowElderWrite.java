package org.company.nianglin.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 允许老年患者账号执行写操作。
 *
 * <p><b>背景</b>：{@code AGENT.md} §4.3 规定老人账号默认只读，所有写操作必须由家属代做，
 * 由 {@link ElderReadOnlyInterceptor} 在服务端统一拦截。</p>
 *
 * <p>但「只读」不能一刀切：<b>登出</b>和<b>修改自己的密码</b>这两件事必须由本人完成，
 * 若也拦掉，老人账号登录后将无法退出。这类接口用本注解显式放行。</p>
 *
 * <p>⚠️ 本注解的每一项使用都应在 Code Review 时被质疑一次 —— 放行必须是刻意的，
 * 不能因为「前端已经隐藏了按钮」而顺手加上。</p>
 *
 * @author 银龄伴诊团队
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllowElderWrite {

    /** 放行原因，必填，便于审查时理解为什么这个写接口对老人开放 */
    String value();
}
