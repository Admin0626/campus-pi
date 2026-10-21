package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class VerificationController {
    private final VerificationService service;
    public VerificationController(VerificationService service){this.service=service;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object result(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @GetMapping("/me/verifications") public Object mine(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return result(service.list(actor(r),false,page,size,"",""),r);}
    @PostMapping("/me/verifications") public ResponseEntity<Object> submit(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result(service.submit(actor(r),body,key),r));}
    @GetMapping("/admin/verifications") public Object queue(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="audit_status",defaultValue="")String state,@RequestParam(name="verification_type",defaultValue="")String type,HttpServletRequest r){return result(service.list(actor(r),true,page,size,state,type),r);}
    @GetMapping("/admin/verifications/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return result(service.detail(actor(r),AccountService.id(id)),r);}
    @PostMapping("/admin/verifications/{id}/review") public Object review(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return result(service.review(actor(r),AccountService.id(id),body,key),r);}
}
