package com.campuspi.aftersale;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AftersaleController {
    private final AftersaleService service;
    public AftersaleController(AftersaleService service) {this.service=service;}
    @ModelAttribute public void noStore(HttpServletResponse r) {r.setHeader("Cache-Control","no-store");}
    private AccountService.Identity actor(HttpServletRequest r) {return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r) {return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @PostMapping("/aftersales") @ResponseStatus(HttpStatus.CREATED)
    public Object create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r) {return response(service.create(actor(r),body,key),r);}
    @GetMapping("/me/aftersales")
    public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r) {return response(service.list(actor(r),page,size),r);}
    @GetMapping("/aftersales/{id}")
    public Object detail(@PathVariable String id,HttpServletRequest r) {return response(service.detail(actor(r),id),r);}
    @PostMapping("/aftersales/{id}/cancel")
    public Object cancel(@PathVariable String id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r) {
        if(body!=null&&!body.isBlank())throw new ApiException(400,"撤回操作不接收请求体");
        return response(service.cancel(actor(r),id,key),r);
    }
}
