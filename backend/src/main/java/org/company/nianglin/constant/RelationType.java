package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 家属与老人的关系（{@code family_elder_relation.relation}）。
 *
 * <p>取值刻意保持粗粒度：一期不做亲疏分级，也不参与权限判定 ——
 * 关系只影响展示文案，<b>能做什么由绑定关系是否存在决定，与关系类型无关</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum RelationType {

    SON("儿子"),
    DAUGHTER("女儿"),
    RELATIVE("亲属"),
    OTHER("其他"),
    ;

    private final String label;

    RelationType(String label) {
        this.label = label;
    }

    public static RelationType of(String name) {
        if (name == null) {
            return null;
        }
        for (RelationType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    public static String labelOf(String name) {
        RelationType t = of(name);
        return t == null ? name : t.getLabel();
    }
}
