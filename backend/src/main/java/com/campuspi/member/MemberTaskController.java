package com.campuspi.member;
import com.campuspi.account.*;
import com.campuspi.admin.AdminTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class MemberTaskController {
    private final MemberTaskService member;private final AdminTaskService tasks;
    public MemberTaskController(MemberTaskService member,AdminTaskService tasks){this.member=member;this.tasks=tasks;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @GetMapping("/tasks") public Object list(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(defaultValue="")String campus,@RequestParam(name="task_status",defaultValue="")String state,@RequestParam(name="reward_type",defaultValue="")String type,@RequestParam(defaultValue="")String keyword,HttpServletRequest r){return response(member.list(actor(r),page,size,campus,state,type,keyword,""),r);}
    @GetMapping("/me/tasks") public Object mine(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(defaultValue="published")String side,@RequestParam(name="task_status",defaultValue="")String state,HttpServletRequest r){return response(member.list(actor(r),page,size,"",state,"","",side),r);}
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(member.publish(actor(r),body,key),r);}
    @PostMapping("/tasks/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(tasks.cancelByOwner(actor(r),AccountService.id(id),body,key),r);}
    @PostMapping("/tasks/{id}/applications") @ResponseStatus(HttpStatus.CREATED) public Object apply(@PathVariable String id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){MemberSupport.noBody(body);return response(member.apply(actor(r),AccountService.id(id),key),r);}
    @GetMapping("/me/task-applications") public Object applications(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="application_status",defaultValue="")String state,HttpServletRequest r){return response(member.applications(actor(r),page,size,state),r);}
    @PostMapping("/task-applications/{id}/cancel") public Object cancelApplication(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(member.cancelApplication(actor(r),AccountService.id(id),body,key),r);}
    @PostMapping("/tasks/{id}/proofs") @ResponseStatus(HttpStatus.CREATED) public Object proof(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(member.proof(actor(r),AccountService.id(id),body,key),r);}
}
