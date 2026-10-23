package com.campuspi.member;
import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class MemberChatController {
    private final MemberChatService chats;
    public MemberChatController(MemberChatService chats){this.chats=chats;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @PostMapping("/chat-sessions") public Object create(@RequestBody JsonNode body,HttpServletRequest r){return response(chats.create(actor(r),body),r);}
    @GetMapping("/chat-sessions") public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return response(chats.list(actor(r),page,size),r);}
    @GetMapping("/chat-sessions/{id}/messages") public Object messages(@PathVariable String id,@RequestParam(defaultValue="50")int limit,@RequestParam(name="before_msg_id",defaultValue="")String before,@RequestParam(name="after_msg_id",defaultValue="")String after,HttpServletRequest r){return response(chats.messages(actor(r),AccountService.id(id),limit,before,after),r);}
    @PostMapping("/chat-sessions/{id}/messages") @ResponseStatus(HttpStatus.CREATED) public Object send(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(chats.send(actor(r),AccountService.id(id),body,key),r);}
    @PutMapping("/messages/{id}/read") public Object read(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){MemberSupport.noBody(body);return response(chats.read(actor(r),AccountService.id(id)),r);}
    @DeleteMapping("/messages/{id}") public Object delete(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){MemberSupport.noBody(body);return response(chats.delete(actor(r),AccountService.id(id)),r);}
    @PostMapping("/messages/{id}/reports") @ResponseStatus(HttpStatus.CREATED) public Object report(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(chats.report(actor(r),AccountService.id(id),body,key),r);}
    @GetMapping("/me/message-reports") public Object reports(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return response(chats.reports(actor(r),page,size),r);}
}
