package org.company.nianglin.constant;

/**
 * 入参校验正则（编译期常量，供 Bean Validation 注解引用）。
 *
 * <p><b>为什么单独抽一个类</b>：手机号、身份证号这类正则会在多个 DTO 里出现
 * （新增 / 修改 / 资质申请）。分散写死的后果不是「写错」而是「改漏」——
 * 某天上调身份证校验规则时只改了其中一处，另一处就成了绕过校验的后门。
 * 注解的参数必须是编译期常量，所以只能放在常量类里，不能放工具方法。</p>
 *
 * <p>约定：所有「可选」字段的正则都以 {@code ^$|} 开头 —— 允许空串，
 * 否则前端把可选字段清空后传 {@code ""} 会被校验拦住，而用户什么都没做错。
 * 空值的拦截交给 {@code @NotBlank} / {@code @NotNull}，两个注解各管一件事。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
public final class ValidationPatterns {

    private ValidationPatterns() {
    }

    /** 手机号：11 位，1 开头，第二位 3-9 */
    public static final String PHONE = "^1[3-9]\\d{9}$";

    /** 手机号（可选字段用，允许空串） */
    public static final String PHONE_OPTIONAL = "^$|^1[3-9]\\d{9}$";

    /** 身份证号：18 位，含末位校验位 X */
    public static final String ID_CARD =
            "^[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$";

    /** 身份证号（可选字段用，允许空串） */
    public static final String ID_CARD_OPTIONAL = "^$|" + ID_CARD;

    /** 性别：MALE / FEMALE */
    public static final String GENDER = "^(MALE|FEMALE)$";

    /** 行动能力：SELF / ASSIST / WHEELCHAIR */
    public static final String MOBILITY_LEVEL = "^(SELF|ASSIST|WHEELCHAIR)$";

    /** 与老人关系：SON / DAUGHTER / RELATIVE / OTHER */
    public static final String RELATION = "^(SON|DAUGHTER|RELATIVE|OTHER)$";

    /** 绑定方式：PHONE / INVITE_CODE */
    public static final String BIND_TYPE = "^(PHONE|INVITE_CODE)$";

    /** 可接受的证件图片 URL：站内相对路径或 http(s) 绝对地址 */
    public static final String FILE_URL = "^(/|https?://)\\S{1,250}$";
}
