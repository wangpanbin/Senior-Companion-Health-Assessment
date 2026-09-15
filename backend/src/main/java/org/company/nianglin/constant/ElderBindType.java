package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 家属绑定老人的方式（{@code family_elder_relation.bind_type}）。
 *
 * <p><b>一期只开放 {@link #PHONE}</b>：老人账号注册时手机号是唯一的，
 * 家属输入老人手机号即可精确命中账号，不需要额外发放凭证。</p>
 *
 * <p>{@link #INVITE_CODE} 保留在枚举里（种子数据里就有历史取值为
 * {@code INVITE_CODE} 的绑定记录，删掉枚举值会读不出来），但接口层会明确拒绝并返回
 * {@code 501}。原因是 {@code elder_profile} / {@code sys_user} 都没有邀请码列 ——
 * 实现它需要新增 Flyway 迁移，而 Entity 由 {@code gen_entity.py} 从
 * {@code V1__init_schema.sql} 单向生成，新增列会造成生成器与库结构漂移。
 * 这件事留到二期，届时连「邀请码签发 / 过期 / 吊销」一起做。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum ElderBindType {

    PHONE("按手机号"),
    INVITE_CODE("按邀请码"),
    /**
     * 家属代建档时自动建立的绑定关系。
     *
     * <p>⚠️ <b>这不是一个可传入的取值</b>：它只会出现在
     * {@code family_elder_relation.bind_type} 列里，用于区分「家属代为建档顺手绑上」
     * 和「家属拿手机号认领了一个已有账号」。接口入参的合法值由
     * {@code ValidationPatterns.BIND_TYPE} 限定为 {@code PHONE|INVITE_CODE}。</p>
     *
     * <p>如果两个场景都记成 {@code PHONE}，运营查数据时就没法回答
     * 「这个老人到底有没有自己的登录账号」——而这正是二期要清理的历史数据问题。</p>
     */
    CREATE("家属代建档"),
    ;

    private final String label;

    ElderBindType(String label) {
        this.label = label;
    }

    public static ElderBindType of(String name) {
        if (name == null) {
            return null;
        }
        for (ElderBindType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }
}
