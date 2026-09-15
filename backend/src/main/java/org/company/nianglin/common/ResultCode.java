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
 *   <li>{@code 7xxx} —— 站内信</li>
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

    /* ==================== 7xxx 站内信 ==================== */
    MESSAGE_NOT_FOUND(7001, "消息不存在"),
    MESSAGE_NO_PERMISSION(7002, "无权查看该消息"),

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
