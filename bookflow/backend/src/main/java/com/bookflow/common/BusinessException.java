package com.bookflow.common;

// 预期的业务拒绝（如名额冲突）与数据库不可达分开，便于浏览器采取不同反馈。
public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }
    public static BusinessException invalid(String message) {
        return new BusinessException(400, "INVALID_ARGUMENT", message);
    }
}
