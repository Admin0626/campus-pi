package com.campuspi.member;
import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class MemberOrderController {
    private final MemberOrderService orders;
    public MemberOrderController(MemberOrderService orders){this.orders=orders;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @PostMapping("/orders/product") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(orders.create(actor(r),body,key),r);}
    @GetMapping("/me/orders") public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(defaultValue="all")String side,@RequestParam(name="order_type",defaultValue="")String type,@RequestParam(name="order_status",defaultValue="")String state,HttpServletRequest r){return response(orders.list(actor(r),page,size,side,type,state),r);}
    @GetMapping("/orders/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return response(orders.detail(actor(r),id),r);}
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){MemberSupport.noBody(body);return response(orders.cancel(actor(r),id,key),r);}
    @PostMapping("/orders/{id}/shipping") public Object handover(@PathVariable String id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){MemberSupport.noBody(body);return response(orders.confirmOffline(actor(r),id,key,false),r);}
    @PostMapping("/orders/{id}/receipt") public Object receipt(@PathVariable String id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){MemberSupport.noBody(body);return response(orders.confirmOffline(actor(r),id,key,true),r);}
}
