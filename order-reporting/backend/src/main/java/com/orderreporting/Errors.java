package com.orderreporting;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class Errors {
    record ErrorBody(String code, String message, Object detail) {}
    @ExceptionHandler(Problem.class)
    ResponseEntity<ErrorBody> problem(Problem p) { return ResponseEntity.status(p.status).body(new ErrorBody(p.code,p.getMessage(),p.detail)); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> large() { return problem(new Problem(413,"FILE_TOO_LARGE","CSV 文件上限 1 MiB")); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ErrorBody> bad(Exception e) { return problem(new Problem(400,"BAD_REQUEST","参数格式错误或缺少必要参数")); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException e) {
        LoggerFactory.getLogger(Errors.class).error("Database operation failed", e);
        return problem(new Problem(503,"DATABASE_UNAVAILABLE","数据库暂时不可用，请稍后重试"));
    }
}
