package com.campuspi.notification;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications){this.notifications=notifications;}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    private void noBody(String body){if(body!=null&&!body.isBlank())throw new ApiException(400,"该操作不接收请求体");}
    @GetMapping("/notifications") public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="unread_only",defaultValue="false")boolean unread,@RequestParam(name="notification_type",defaultValue="")String type,HttpServletRequest r){return response(notifications.list(actor(r),page,size,unread,type),r);}
    @GetMapping("/notifications/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return response(notifications.detail(actor(r),id),r);}
    @PutMapping("/notifications/{id}/read") public Object read(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(notifications.change(actor(r),id,"read"),r);}
    @PutMapping("/notifications/{id}/click") public Object click(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(notifications.change(actor(r),id,"click"),r);}
    @DeleteMapping("/notifications/{id}") public Object delete(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(notifications.change(actor(r),id,"delete"),r);}
    @GetMapping("/notification-preferences") public Object preferences(HttpServletRequest r){return response(notifications.preferences(actor(r)),r);}
    @PutMapping("/notification-preferences") public Object preferences(@RequestBody JsonNode body,HttpServletRequest r){return response(notifications.savePreferences(actor(r),body),r);}
}
