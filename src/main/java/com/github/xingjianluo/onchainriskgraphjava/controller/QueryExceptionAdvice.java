package com.github.xingjianluo.onchainriskgraphjava.controller;

import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 查询类异常 -> 可解释拒绝响应的统一转换。
 *
 * <p>响应体统一为 {@link QueryRejection}（reasonCode/reason/message），
 * HTTP 状态码由 {@link RejectReason#httpStatus()} 决定：参数类 400、资源类 404。
 * 正常查询路径不经过本处理器，行为不受影响。
 */
@RestControllerAdvice
public class QueryExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(QueryExceptionAdvice.class);

    /** 服务/校验层显式抛出的可解释拒绝（参数非法） */
    @ExceptionHandler(QueryInvalidException.class)
    public ResponseEntity<QueryRejection> handleInvalid(QueryInvalidException ex) {
        return rejected(ex);
    }

    /** 服务/校验层显式抛出的可解释拒绝（资源不存在：无生效规则/版本不存在） */
    @ExceptionHandler(QueryNotFoundException.class)
    public ResponseEntity<QueryRejection> handleNotFound(QueryNotFoundException ex) {
        return rejected(ex);
    }

    private static ResponseEntity<QueryRejection> rejected(
            com.github.xingjianluo.onchainriskgraphjava.query.RejectedQuery ex) {
        log.info("查询被拒绝 reason={}, message={}", ex.reason().code(), ex.rejection().message());
        return ResponseEntity.status(ex.reason().httpStatus()).body(ex.rejection());
    }

    /** 缺少必填查询参数（如 asOf） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<QueryRejection> handleMissingParam(MissingServletRequestParameterException ex) {
        RejectReason reason = "asOf".equals(ex.getParameterName())
                ? RejectReason.MISSING_AS_OF
                : RejectReason.INVALID_PARAMETER;
        String message = "缺少必填参数 " + ex.getParameterName();
        return reject(reason, message);
    }

    /** 查询参数类型不匹配（如 asOf=abc） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<QueryRejection> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        RejectReason reason = "asOf".equals(ex.getName())
                ? RejectReason.INVALID_AS_OF
                : RejectReason.INVALID_PARAMETER;
        String message = "参数 " + ex.getName() + " 取值非法: "
                + (ex.getValue() == null ? "null" : ex.getValue());
        return reject(reason, message);
    }

    /** 请求体无法解析（批量点位 JSON 格式错误等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<QueryRejection> handleUnreadable(HttpMessageNotReadableException ex) {
        return reject(RejectReason.INVALID_PARAMETER, "请求体无法解析或字段类型不正确");
    }

    private static ResponseEntity<QueryRejection> reject(RejectReason reason, String message) {
        log.info("查询被拒绝 reason={}, message={}", reason.code(), message);
        return ResponseEntity.status(reason.httpStatus())
                .body(new QueryRejection(reason.code(), reason.name(), message));
    }
}
