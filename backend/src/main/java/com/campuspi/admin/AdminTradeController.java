package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminTradeController {
    private final AdminOrderService orders;
    private final AdminReportService reports;
    private final com.campuspi.aftersale.AftersaleService aftersales;
    public AdminTradeController(AdminOrderService orders,AdminReportService reports,com.campuspi.aftersale.AftersaleService aftersales){this.orders=orders;this.reports=reports;this.aftersales=aftersales;}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @GetMapping("/orders") public Object orders(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="order_type",defaultValue="")String type,@RequestParam(name="order_status",defaultValue="")String state,@RequestParam(name="order_id",defaultValue="")String id,HttpServletRequest r){return response(orders.orders(page,size,type,state,id),r);}
    @GetMapping("/orders/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return response(orders.detail(actor(r),id),r);}
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(orders.cancel(actor(r),id,body,key),r);}
    @GetMapping("/aftersales") public Object aftersales(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="aftersale_status",defaultValue="")String state,HttpServletRequest r){return response(orders.aftersales(page,size,state),r);}
    @PostMapping("/aftersales/{id}/review") public Object aftersaleReview(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(aftersales.review(actor(r),id,body,key),r);}
    @GetMapping("/message-reports") public Object reports(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="audit_status",defaultValue="")String state,HttpServletRequest r){return response(reports.list(actor(r),page,size,state),r);}
    @GetMapping("/message-reports/{id}") public Object evidence(@PathVariable String id,HttpServletRequest r){return response(reports.detail(actor(r),AccountService.id(id)),r);}
    @PostMapping("/message-reports/{id}/review") public Object review(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(reports.review(actor(r),AccountService.id(id),body,key),r);}
}
