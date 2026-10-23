package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AdminTaskController {
    private final AdminTaskService tasks;
    private final AccountService accounts;
    public AdminTaskController(AdminTaskService tasks,AccountService accounts){this.tasks=tasks;this.accounts=accounts;}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    @GetMapping("/admin/tasks") public Object tasks(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="task_status",defaultValue="")String state,@RequestParam(defaultValue="")String campus,HttpServletRequest r){return response(tasks.tasks(actor(r),page,size,state,campus),r);}
    @PostMapping("/admin/tasks/{id}/publication-review") public Object publication(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(tasks.publication(actor(r),AccountService.id(id),body,key),r);}
    @GetMapping("/admin/task-applications") public Object applications(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="reward_task_id",defaultValue="")String id,@RequestParam(name="application_status",defaultValue="")String state,HttpServletRequest r){return response(tasks.applications(page,size,id,state),r);}
    @PostMapping("/admin/task-applications/{id}/review") public Object assign(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(tasks.assign(actor(r),AccountService.id(id),body,key),r);}
    @GetMapping("/admin/task-proofs") public Object proofs(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="reward_task_id",defaultValue="")String id,@RequestParam(name="audit_status",defaultValue="")String state,HttpServletRequest r){return response(tasks.proofs(actor(r),id.isEmpty()?null:AccountService.id(id),page,size,state),r);}
    @PostMapping("/admin/task-proofs/{id}/review") public Object review(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(tasks.reviewProof(actor(r),AccountService.id(id),body,key),r);}
    @PostMapping("/admin/tasks/{id}/close") public Object close(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(tasks.close(actor(r),AccountService.id(id),body,key),r);}
    @GetMapping("/tasks/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){String header=r.getHeader("Authorization");return response(tasks.detail(AccountService.id(id),header==null?null:accounts.authenticate(header)),r);}
    @GetMapping("/tasks/{id}/proofs") public Object versions(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return response(tasks.proofs(actor(r),AccountService.id(id),page,size,""),r);}
    @GetMapping("/tasks/{id}/logs") public Object logs(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return response(tasks.logs(actor(r),AccountService.id(id),page,size),r);}
    @GetMapping("/admin/task-audit-logs") public Object reviewLogs(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="reward_task_id",defaultValue="")String id,@RequestParam(defaultValue="")String keyword,@RequestParam(name="to_status",defaultValue="")String state,@RequestParam(name="date_from",defaultValue="")String from,@RequestParam(name="date_to",defaultValue="")String to,HttpServletRequest r){return response(tasks.reviewLogs(actor(r),page,size,id,keyword,state,from,to),r);}
    @GetMapping("/me/reward-accounts") public Object balances(HttpServletRequest r){return response(tasks.balances(actor(r)),r);}
    @GetMapping("/me/reward-ledger") public Object ledger(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="reward_type",defaultValue="")String type,HttpServletRequest r){return response(tasks.ledger(actor(r),page,size,type),r);}
}
