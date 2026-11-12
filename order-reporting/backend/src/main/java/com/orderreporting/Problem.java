package com.orderreporting;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;
    public final Object detail;
    public Problem(int status, String code, String message) { this(status, code, message, null); }
    public Problem(int status, String code, String message, Object detail) {
        super(message); this.status = status; this.code = code; this.detail = detail;
    }
}
