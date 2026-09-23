package com.github.xingjianluo.onchainriskgraphjava.exception;

/**
 * 入参校验失败。
 */
public class InvalidInputException extends ApiException {

    public InvalidInputException(String message) {
        super("INVALID_INPUT", message);
    }
}
