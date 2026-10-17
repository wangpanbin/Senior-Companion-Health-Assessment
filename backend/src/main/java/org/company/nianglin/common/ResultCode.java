package org.company.nianglin.common;

import lombok.Getter;

/**
 * 全局响应码。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code 2xx / 4xx / 5xx} —— 与 HTTP 语义对齐的通用码</li>
 *   <li>{@code 1xxx} —— 认证与账号</li>
 *   <li>{@code 2xxx} —— 用户与档案</li>
 *   <li>{@code 3xxx} —— 陪诊订单</li>
 *   <li>{@code 4xxx} —— 陪诊执行与打卡</li>
 *   <li>{@code 5xxx} —— 用药管理</li>
 *   <li>{@code 6xxx} —— 评价与投诉</li>
 *   <li>{@code 7xxx} —— 站内信与订单聊天（M13）</li>
 *   <li>{@code 8xxx} —— 管理后台</li>
 *   <li>{@code 9xxx} —— 数据统计与导出</li>
 * </ul>
 *
 * <p>⚠️ 本枚举是接口文档 {@code docs/api/README.md} 中错误码表的唯一来源，新增码值必须同步更新文档。</p>
 *
 * @author 银龄伴诊团队
 */
@Getter
public enum ResultCode {

    /* ==================== 通用 ==================== */
    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    CONFLICT(409, "当前状态不允许该操作"),
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试"),
    SYSTEM_ERROR(500, "服务器开小差了，请稍后再试"),
    NOT_IMPLEMENTED(501, "功能开发中"),

    /* ==================== 1xxx 认证与账号 ==================== */
    LOGIN_FAILED(1001, "账号或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已被封禁，请联系管理员"),
    CAPTCHA_ERROR(1003, "验证码错误或已过期"),
    LOGIN_LOCKED(1004, "登录失败次数过多，请稍后再试"),
    TOKEN_INVALID(1005, "登录状态已失效，请重新登录"),
    OLD_PASSWORD_ERROR(1006, "原密码不正确"),
    PHONE_ALREADY_EXISTS(1007, "该手机号已被注册"),

    /* ==================== 2xxx 用户与档案 ==================== */
    ELDER_NOT_FOUND(2001, "老人档案不存在"),
    /**
     * 该老人已被其他家属绑定。
     *
     * <p>⚠️ <b>预留槽位，当前不会被任何接口抛出。</b>
     * {@code ElderService#bind} 出于防手机号枚举的考虑，把
     * 「账号不存在 / 已被封禁 / 未建档 / 已被他人绑定」四种情况统一回 {@link #ELDER_NOT_FOUND}
     * —— 否则攻击者可以靠 {@code 2001 / 2002} 的差异枚举出哪些手机号对应已注册的老人账号。</p>
     *
     * <p>保留这个常量是为了不改动已对外发布的错误码表（前端可以照常处理，只是收不到）。
     * 详见 {@code docs/api/02-elder-family.md §11}。</p>
     */
    ELDER_ALREADY_BOUND(2002, "该老人已被其他家属绑定"),
    COMPANION_NOT_AUDITED(2003, "陪诊员资质尚未通过审核"),
    USER_DISABLED(2004, "用户已被封禁"),
    RELATION_NOT_FOUND(2005, "绑定关系不存在"),
    NO_PERMISSION_FOR_ELDER(2006, "无权操作该老人档案"),
    COMPANION_NOT_FOUND(2007, "陪诊员不存在"),

    /* ==================== 3xxx 陪诊订单 ==================== */
    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATUS_ILLEGAL(3002, "当前订单状态不允许该操作"),
    ORDER_ALREADY_TAKEN(3003, "手慢了，该订单已被其他陪诊员接单"),
    ORDER_NO_PERMISSION(3004, "无权操作该订单"),
    ORDER_TIME_INVALID(3005, "就诊时间不能早于当前时间"),
    ORDER_CANNOT_CANCEL(3006, "当前状态不允许取消订单"),
    /**
     * 服务小结命中「诊断 / 处方 / 用药建议」类表述。
     *
     * <p>这是本项目的合规红线（不做诊断、不开药方）在代码层的落点：
     * 陪诊员填写的服务小结只允许记录过程（几点到院、是否取药、老人精神状态），
     * 一旦出现「建议服用」「剂量」「确诊」这类词就直接拒绝入库，
     * 不让平台被动地成为医疗建议的载体。</p>
     */
    ORDER_SUMMARY_ILLEGAL(3007, "服务小结不能包含诊断、处方或用药建议"),

    /**
     * 当前订单不允许催单（V3.1）。
     *
     * <p>三种情形共用一个码：订单不在 {@code PENDING}、下单风险未达「履约风险较高」、
     * 或者不是本单下单人之前就已被 {@code 3004} 拦下。
     * 把它们合成一个码，是因为对外区分「你不能催」和「现在还不用催」没有意义 ——
     * 家属需要的反馈都是同一个：现在催不了。</p>
     */
    ORDER_URGE_NOT_ALLOWED(3008, "当前订单状态不允许催单"),

    /**
     * 催促过于频繁（V3.1）。
     *
     * <p>冷却期内、或累计次数已达上限都走这个码。<b>它同时也是并发穿透的出口</b>：
     * 条件更新影响 0 行时无法区分「被别人抢先」「在冷却期」「已超限」三种原因，
     * 对外一律报「过于频繁」，避免泄漏他人操作时序。</p>
     */
    ORDER_URGE_TOO_FREQUENT(3009, "催促过于频繁，请稍后再试"),

    /* ==================== 4xxx 陪诊执行与打卡 ==================== */
    NOT_IN_CHECKIN_RANGE(4001, "未到达陪诊地点，打卡无效"),
    CHECKIN_DUPLICATED(4002, "该节点已打卡，请勿重复提交"),
    NOT_ORDER_COMPANION(4003, "您不是该订单的陪诊员"),

    /* ==================== 5xxx 用药管理 ==================== */
    MEDICINE_NOT_FOUND(5001, "药品不存在"),
    MEDICATION_PLAN_INVALID(5002, "用药计划时间区间不合法"),
    MEDICATION_TASK_CONFIRMED(5003, "该服药任务已确认"),

    /* ==================== 6xxx 评价与投诉 ==================== */
    ORDER_NOT_COMPLETED(6001, "订单尚未完成，不能评价"),
    ORDER_ALREADY_REVIEWED(6002, "该订单已评价"),
    CONTENT_SENSITIVE(6003, "内容包含敏感词，请修改后重试"),
    COMPLAINT_NOT_FOUND(6004, "投诉记录不存在"),
    /**
     * 评价不存在（含 {@code deleted=1}）。
     *
     * <p>与 {@link #COMPLAINT_NOT_FOUND} 同样的语义：把「真的没了」与「不给我看」
     * 合成同一个码会让排障无从下手，因此为评价侧单独留一个码。</p>
     */
    REVIEW_NOT_FOUND(6005, "评价不存在"),
    /**
     * 该评价已由陪诊员回复，不能重复回复。
     *
     * <p>「一评一回复」是产品决策（详见 {@code docs/spec/E4-review-credibility.md} §七）：
     * 落库即定稿，不接受编辑也不接受删除。重复提交会被 {@code isNull(companion_reply)}
     * 乐观条件翻译成此码，与并发穿透走同一出口。</p>
     */
    REVIEW_ALREADY_REPLIED(6006, "该评价已回复，不能重复回复"),

    /* ==================== 7xxx 站内信与订单聊天 ==================== */
    MESSAGE_NOT_FOUND(7001, "消息不存在"),
    MESSAGE_NO_PERMISSION(7002, "无权查看该消息"),

    /* ---------- 7xxx 订单聊天（M13，ADR-0011） ---------- */
    /**
     * 会话尚未建立：订单 {@code companion_id} 为空（PENDING 未接单），
     * 没有会话对方，聊天无从谈起。接单后即开放。
     */
    CHAT_NOT_AVAILABLE(7003, "会话尚未建立，陪诊员接单后开放聊天"),
    /**
     * 聊天内容为空 / 超过 1000 字 / 命中合规词表（与 3007 同源口径）：
     * 聊天不是绕过「不做诊断、不开药方」红线的旁路。
     */
    CHAT_CONTENT_ILLEGAL(7004, "消息内容为空、超过 1000 字或包含违规表述"),
    CHAT_FILE_REQUIRED(7005, "图片 / 视频消息必须携带已上传的 fileId"),
    /**
     * 附件不合法：不存在、{@code biz_type} 不是 CHAT、或不属于当前发送人
     * （防止引用他人附件充当自己的消息媒体）。
     */
    CHAT_FILE_NOT_OWNED(7006, "附件不存在或不属于当前发送人"),
    CHAT_FILE_TYPE_MISMATCH(7007, "附件类型与消息类型不符（图片仅 jpg/png/webp，视频仅 mp4）"),
    /**
     * 无发言权限：会话双方以外一律只读。
     * ELDER 走 {@code ElderReadOnlyInterceptor} 通常已被 403 拦截，此处兜底；
     * ADMIN 仲裁只读是刻意设计（ADR-0011），不是遗漏。
     */
    CHAT_READ_ONLY(7008, "您在当前会话中只有查看权限"),
    /**
     * 聊天附件超过 50MB 上限。
     * 仅限 {@code bizType=CHAT}；非聊天业务超限走通用 {@code PARAM_ERROR(400)}，
     * 不得借用本码（7xxx 段位纪律，AGENTS.md §2.7）。
     */
    CHAT_FILE_TOO_LARGE(7009, "聊天附件超过 50MB 上限"),

    /* ==================== 8xxx 管理后台 ==================== */
    AUDIT_STATUS_ILLEGAL(8001, "资质审核状态不合法"),
    CANNOT_DISABLE_ADMIN(8002, "不能封禁管理员账号"),
    AUDIT_REASON_REQUIRED(8003, "驳回时必须填写原因"),

    /* ==================== 9xxx 数据统计与导出 ==================== */
    EXPORT_LIMIT_EXCEEDED(9001, "导出数据量超过上限，请缩小筛选范围"),
    STAT_RANGE_INVALID(9002, "统计时间区间不合法"),
    ;

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
