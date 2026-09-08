package com.highlighthub.common;

public class BusinessException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public BusinessException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCodes.NOT_FOUND, 404, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCodes.FORBIDDEN, 403, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCodes.VALIDATION_FAILED, 400, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(code, 409, message);
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
}
