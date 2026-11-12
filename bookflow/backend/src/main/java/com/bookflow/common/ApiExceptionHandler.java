package com.bookflow.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ARGUMENT", "请检查必填字段、正整数 ID 和日期时间格式"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return ResponseEntity.status(403).body(new ApiError("FORBIDDEN", "没有权限执行此操作"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(409).body(new ApiError("DATA_CONFLICT", "数据已被占用或有关联记录，请刷新后检查"));
    }

    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ApiError> busy(TransientDataAccessException exception) {
        // 不自动重发写请求：M2 尚无持久化幂等键。先让用户刷新记录，避免误判提交结果。
        log.warn("Database transaction temporarily failed", exception);
        return ResponseEntity.status(503).body(new ApiError("DATABASE_BUSY", "数据库暂忙，请先刷新记录确认结果后再操作"));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception exception) {
        log.error("Database operation failed", exception);
        return ResponseEntity.status(503).body(new ApiError("DATABASE_UNAVAILABLE", "数据库暂时不可用，请稍后刷新记录确认结果"));
    }
}
