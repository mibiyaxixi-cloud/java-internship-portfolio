package com.example.knowledge;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.LoggerFactory;
import java.util.*;

@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) { return reply(e.status,e.code,e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size() { return reply(413,"FILE_SIZE","文件不能超过 256 KiB"); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class})
    ResponseEntity<?> input(Exception e) { return reply(400,"INVALID_INPUT","参数无效；问题须为 1—1000 字符，上传须包含 file 文件"); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> db(DataAccessException e) { return reply(503,"DATABASE_UNAVAILABLE","数据库暂时不可用，本次操作未确认成功，请恢复后刷新状态再重试"); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        String trace=UUID.randomUUID().toString();
        LoggerFactory.getLogger(getClass()).error("request failed trace={} type={}",trace,e.getClass().getName());
        return reply(500,"INTERNAL_ERROR","请求未完成，请用故障编号定位日志："+trace);
    }
    private ResponseEntity<?> reply(int status,String code,String message) { return ResponseEntity.status(status).body(Map.of("code",code,"message",message)); }
}
