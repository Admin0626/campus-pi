package com.campuspi.account;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountService accounts;
    private final com.campuspi.admin.AdminPolicy policy;
    private final AccountProfileService profiles;
    public AccountController(AccountService accounts,com.campuspi.admin.AdminPolicy policy,AccountProfileService profiles){this.accounts=accounts;this.policy=policy;this.profiles=profiles;}
    private AccountService.Identity identity(HttpServletRequest req){return (AccountService.Identity)req.getAttribute("identity");}
    private Object response(Object data,HttpServletRequest req){return ApiSupport.envelope(profiles.decorate(data),ApiSupport.requestId(req));}
    @PostMapping("/auth/register") public ResponseEntity<Object> register(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest req){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response(accounts.register(body,key),req));}
    @PostMapping("/auth/login") public Object login(@RequestBody JsonNode body,HttpServletRequest req){return response(accounts.login(body,req.getRemoteAddr()),req);}
    @PostMapping("/auth/logout") public Object logout(@RequestHeader(value="Authorization",required=false)String token,HttpServletRequest req){return response(accounts.logout(token),req);}
    @GetMapping("/me") public Object me(HttpServletRequest req){return response(accounts.me(identity(req)),req);}
    @RequestMapping(value="/me",method={RequestMethod.PATCH,RequestMethod.PUT}) public Object patch(@RequestBody JsonNode body,HttpServletRequest req){return response(profiles.patch(identity(req),body),req);}
    @PostMapping("/me/password") public Object password(@RequestBody JsonNode body,HttpServletRequest req){return response(accounts.changePassword(identity(req),body),req);}
    @GetMapping("/users/{userId}/profile") public Object profile(@PathVariable String userId,HttpServletRequest req){return response(accounts.publicProfile(AccountService.id(userId)),req);}
    @GetMapping("/admin/users") public Object users(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(defaultValue="")String keyword,@RequestParam(name="account_status",defaultValue="")String state,@RequestParam(defaultValue="")String role,@RequestParam(defaultValue="")String campus,@RequestParam(name="date_from",defaultValue="")String from,@RequestParam(name="date_to",defaultValue="")String to,HttpServletRequest req){return response(accounts.list(page,size,keyword,state,role,campus,from,to),req);}
    @GetMapping("/admin/users/{userId}") public Object detail(@PathVariable String userId,HttpServletRequest req){return response(accounts.adminDetail(AccountService.id(userId)),req);}
    @PostMapping("/admin/users/{userId}/status") public Object status(@PathVariable String userId,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest req){return response(accounts.status(identity(req),AccountService.id(userId),body,key),req);}
    @PostMapping("/admin/users/{userId}/role") public Object role(@PathVariable String userId,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest req){policy.require(identity(req),"ROLES");return response(accounts.role(identity(req),AccountService.id(userId),body,key),req);}
    @PostMapping({"/auth/recovery-challenges","/auth/password-reset"}) public Object recovery(){throw new ApiException(501,"密码找回通道尚未接入");}
}
