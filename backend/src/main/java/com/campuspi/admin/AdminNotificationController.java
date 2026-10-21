package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminNotificationController {
    private final AdminNotificationService notifications;
    public AdminNotificationController(AdminNotificationService notifications){this.notifications=notifications;}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @GetMapping("/notifications") public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="publish_status",defaultValue="")String state,HttpServletRequest r){return response(notifications.list(page,size,state),r);}
    @GetMapping("/notifications/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return response(notifications.detail(id),r);}
    @PostMapping("/notifications") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(notifications.save(actor(r),null,body,key),r);}
    @PutMapping("/notifications/{id}") public Object replace(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){return response(notifications.save(actor(r),id,body,null),r);}
    @GetMapping("/notifications/{id}/deliveries") public Object deliveries(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="delivery_status",defaultValue="")String state,@RequestParam(defaultValue="")String channel,HttpServletRequest r){return response(notifications.deliveries(actor(r),id,page,size,state,channel),r);}
    @PostMapping("/notifications/{id}/withdraw") public Object withdraw(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(notifications.withdraw(actor(r),id,body,key),r);}
    @PostMapping("/notifications/{id}/publish") @ResponseStatus(HttpStatus.ACCEPTED) public Object publish(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(notifications.publish(actor(r),id,body,key),r);}
    @PostMapping("/notification-deliveries/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED) public Object retry(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(notifications.retry(actor(r),AccountService.id(id),body,key),r);}
}
