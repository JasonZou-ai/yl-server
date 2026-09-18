package cn.yl.common.exception;

import lombok.Getter;

/** 业务错误码。分段：1xxxx 通用 / 2xxxx 账号权限 / 3xxxx 评估 / 4xxxx 档案 / 5xxxx 照护 / 6xxxx 监管报表。 */
@Getter
public enum ErrorCode {

    /** 通用 */
    PARAM_INVALID(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权访问该资源"),
    NOT_FOUND(10004, "资源不存在"),
    CONFLICT(10005, "数据冲突，请刷新后重试"),
    IDEMPOTENT_REPLAY(10006, "重复提交（幂等键已存在）"),
    SYSTEM_ERROR(10999, "系统繁忙，请稍后重试"),

    /** 账号权限 */
    LOGIN_FAILED(20001, "账号或凭证错误"),
    ACCOUNT_DISABLED(20002, "账号已停用"),
    ROLE_NOT_PERMITTED(20003, "角色无权执行该操作"),

    /** 评估 */
    EVAL_RULE_VERSION_MISSING(30001, "未找到生效的国标规则版本"),
    EVAL_STATE_ILLEGAL(30002, "评估单状态不允许该操作"),
    EVAL_SCORE_OUT_OF_RANGE(30003, "评估条目分值超出取值范围"),

    /** 档案 */
    ARCHIVE_SENSITIVE_ENCRYPT_FAILED(40001, "敏感字段加密失败"),

    /** 监管报表 */
    EXPORT_TASK_TOO_LARGE(60001, "导出数据量超出单次上限，请缩小范围");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
