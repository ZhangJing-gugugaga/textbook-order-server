package com.tian.textbook.common.error;

/**
 * 全局错误码令牌（PRD 模块 8 异常码表 + 401 三类语义 + 各模块专属文案）。
 *
 * <p>契约冻结项：401 三类语义 TOKEN_EXPIRED / REFRESH_INVALID / ACCOUNT_DISABLED（SPEC §4）。</p>
 */
public enum ErrorCode {

    SUCCESS("0", "成功", 200),

    // 400
    PARAM_INVALID("PARAM_INVALID", "请求参数有误", 400),
    FIELD_CHECK_FAILED("FIELD_CHECK_FAILED", "存在 %s 项问题，请按提示修复后重新提交", 400),
    FILE_TYPE_INVALID("FILE_TYPE_INVALID", "仅支持 .xlsx 文件", 400),
    FILE_TOO_LARGE("FILE_TOO_LARGE", "文件超过大小上限", 400),
    CONFIG_VALUE_INVALID("CONFIG_VALUE_INVALID", "配置值不合法", 400),
    BIZ_ERROR("BIZ_ERROR", "操作失败", 400),
    BOOK_DELISTED("BOOK_DELISTED", "部分教材已下架，请核对后重新提交", 400),

    // 405 / 415：协议级错误必须与 400 区分（此前被 Exception 兜底成 500）
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不被支持", 405),
    MEDIA_TYPE_NOT_SUPPORTED("MEDIA_TYPE_NOT_SUPPORTED", "请求内容类型不被支持", 415),

    // 401 三类语义（契约冻结项）
    UNAUTHORIZED("UNAUTHORIZED", "登录已过期，请重新登录", 401),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "登录已过期，请重新登录", 401),
    TOKEN_INVALID("TOKEN_INVALID", "登录已过期，请重新登录", 401),
    REFRESH_INVALID("REFRESH_INVALID", "登录已过期，请重新登录", 401),
    ACCOUNT_DISABLED("ACCOUNT_DISABLED", "账号已停用，请联系教材室", 401),
    LOGIN_FAILED("LOGIN_FAILED", "账号或密码不正确", 401),
    ACCOUNT_LOCKED("ACCOUNT_LOCKED", "账号已锁定，请稍后再试", 401),
    FIRST_LOGIN_VERIFY_FAILED("FIRST_LOGIN_VERIFY_FAILED", "校验信息不正确，请联系教材室", 401),
    PASSWORD_POLICY("PASSWORD_POLICY", "新密码需 8 位以上且含字母和数字", 400),

    // 403
    FORBIDDEN("FORBIDDEN", "无权执行该操作", 403),
    FIRST_LOGIN_REQUIRED("FIRST_LOGIN_REQUIRED", "请先完成首登校验并修改初始密码", 403),
    RESOURCE_FORBIDDEN("RESOURCE_FORBIDDEN", "无权访问该资源", 403),

    // 404
    NOT_FOUND("NOT_FOUND", "资源不存在", 404),

    // 409
    WINDOW_CLOSED("WINDOW_CLOSED", "本期征订已截止", 409),
    WINDOW_NOT_OPEN("WINDOW_NOT_OPEN", "征订尚未开始", 409),
    CORRECTION_EXPIRED("CORRECTION_EXPIRED", "补正窗口已过，请联系教材室", 409),
    STATE_CONFLICT("STATE_CONFLICT", "数据状态已变更，请刷新后重试", 409),
    NOTICE_TASK_EXISTS("NOTICE_TASK_EXISTS", "本学期已存在进行中的通知任务", 409),

    // 410
    DOWNLOAD_TOKEN_INVALID("DOWNLOAD_TOKEN_INVALID", "下载链接已失效，请重新导出", 410),

    // 429
    RATE_LIMITED("RATE_LIMITED", "操作过于频繁，请稍后再试", 429),

    // 500
    SERVER_ERROR("SERVER_ERROR", "服务开小差了，请稍后重试", 500);

    public final String code;
    public final String defaultMessage;
    public final int httpStatus;

    ErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    /** 默认文案中的 %s 占位（如字段审查问题数）。 */
    public String format(Object... args) {
        return args == null || args.length == 0 ? defaultMessage : String.format(defaultMessage, args);
    }
}
