package com.campuspi.account;

public class ApiException extends RuntimeException {
    public final int status;
    public final String field;
    public final String code;
    public int retryAfter = 900;
    public ApiException(int status, String message) { this(status, message, null, null); }
    public ApiException(int status, String message, String field) {
        this(status,message,field,null);
    }
    public ApiException(int status, String message, String field, String code) {
        super(message); this.status = status; this.field = field; this.code = code;
    }
}
