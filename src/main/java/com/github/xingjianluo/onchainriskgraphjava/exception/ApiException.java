package com.github.xingjianluo.onchainriskgraphjava.exception;

/**
 * 对外可暴露的服务异常基类，携带稳定的错误码与 HTTP 状态映射。
 */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 映射的 HTTP 状态码，默认 400，子类可覆盖。 */
    public int httpStatus() {
        return 400;
    }
}
