package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminBatchController {
    private final AdminBatchService batches;
    public AdminBatchController(AdminBatchService batches){this.batches=batches;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    @PostMapping("/batch-operations/{kind:users|products|posts|tasks}")
    public Object submit(@PathVariable String kind,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){
        return ResponseEntity.accepted().body(ApiSupport.envelope(batches.submit(actor(r),kind,body,key),ApiSupport.requestId(r)));
    }
    @GetMapping("/batch-operations/{id}")
    public Object get(@PathVariable String id,HttpServletRequest r){return ApiSupport.envelope(batches.get(actor(r),id),ApiSupport.requestId(r));}
    @GetMapping("/batch-operations")
    public Object recent(@RequestParam String kind,HttpServletRequest r){return ApiSupport.envelope(batches.recent(actor(r),kind),ApiSupport.requestId(r));}
}
