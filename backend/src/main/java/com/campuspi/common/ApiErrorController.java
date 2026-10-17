package com.campuspi.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 最小错误出口；业务异常映射与鉴权由A模块后续统一接入。 */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value && value >= 400 && value <= 599
                ? value : 500;
        String code = status == 404 ? "NOT_FOUND" : "INTERNAL_ERROR";
        String message = status == 404 ? "资源不存在" : "请求处理失败";
        ErrorResponse body = new ErrorResponse(
                code, message, null, UUID.randomUUID().toString(), List.of());
        return ResponseEntity.status(status).body(body);
    }

    public record ErrorResponse(
            String code,
            String message,
            Object data,
            @JsonProperty("request_id") String requestId,
            List<Object> errors) {}
}
