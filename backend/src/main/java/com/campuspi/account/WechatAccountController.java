package com.campuspi.account;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WechatAccountController {
    private final WechatAccountService wechat;
    private final AccountProfileService profiles;
    public WechatAccountController(WechatAccountService wechat,AccountProfileService profiles) {this.wechat=wechat;this.profiles=profiles;}
    private Object response(Object data,HttpServletRequest req) {return ApiSupport.envelope(profiles.decorate(data),ApiSupport.requestId(req));}
    private AccountService.Identity identity(HttpServletRequest req) {return (AccountService.Identity)req.getAttribute("identity");}
    @GetMapping("/auth/wechat/config") public Object config(HttpServletRequest req) {return response(wechat.capabilities(),req);}
    @PostMapping("/auth/wechat/login") public Object login(@RequestBody JsonNode body,HttpServletRequest req) {return response(wechat.login(body,req.getRemoteAddr()),req);}
    @GetMapping("/me/wechat") public Object status(HttpServletRequest req) {return response(wechat.status(identity(req)),req);}
    @PostMapping("/me/wechat") public Object bind(@RequestBody JsonNode body,HttpServletRequest req) {return response(wechat.bind(identity(req),body,req.getRemoteAddr()),req);}
}
