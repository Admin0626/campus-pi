package com.campuspi.account;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiSupport {
    public static Map<String,Object> envelope(Object data, String requestId) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("code", "OK"); value.put("message", "成功"); value.put("data", data);
        value.put("request_id", requestId); return value;
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String,Object>> error(ApiException ex, HttpServletRequest request) {
        String code = ex.code != null ? ex.code : switch(ex.status) { case 400 -> "VALIDATION_ERROR"; case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; case 409 -> "STATE_CONFLICT";
            case 429 -> "RATE_LIMITED"; case 501 -> "FEATURE_NOT_ENABLED"; case 503 -> "DEPENDENCY_UNAVAILABLE"; default -> "INTERNAL_ERROR"; };
        Map<String,Object> body = envelope(null, requestId(request));
        body.put("code", code); body.put("message", ex.getMessage());
        body.put("errors", ex.field == null ? List.of() : List.of(Map.of("field",ex.field,"message",ex.getMessage())));
        var response = ResponseEntity.status(ex.status).cacheControl(CacheControl.noStore());
        if (ex.status == 429) response.header("Retry-After", String.valueOf(ex.retryAfter));
        return response.body(body);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> malformed(Exception ex, HttpServletRequest request) {
        return error(new ApiException(400,"请求参数格式错误"),request);
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,Object>> unavailable(DataAccessException ex, HttpServletRequest request) {
        return error(new ApiException(503,"数据服务暂时不可用，请稍后重试"),request);
    }
    public static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute("request_id");
        if (id == null) { id = UUID.randomUUID().toString(); request.setAttribute("request_id",id); }
        return id.toString();
    }
}
