package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AdminContentController {
    private final AdminContentService content;
    private final BusinessFileService files;
    public AdminContentController(AdminContentService content,BusinessFileService files){this.content=content;this.files=files;}
    private AccountService.Identity actor(HttpServletRequest req){return (AccountService.Identity)req.getAttribute("identity");}
    private Object response(Object data,HttpServletRequest req){return ApiSupport.envelope(files.decorateProducts(data,actor(req)),ApiSupport.requestId(req));}
    private int categoryId(String value){long id=AccountService.id(value);if(id>Integer.MAX_VALUE)throw new ApiException(400,"分类编号超出范围");return (int)id;}
    @GetMapping("/categories") public Object categories(HttpServletRequest r){return response(content.categories(),r);}
    @PostMapping("/admin/categories") public ResponseEntity<Object> create(@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){return ResponseEntity.status(201).body(response(content.saveCategory(actor(r),null,b,k),r));}
    @PutMapping("/admin/categories/{id}") public Object update(@PathVariable String id,@RequestBody JsonNode b,HttpServletRequest r){return response(content.saveCategory(actor(r),categoryId(id),b,null),r);}
    @DeleteMapping("/admin/categories/{id}") public Object delete(@PathVariable String id,@RequestHeader(value="X-Action-Reason",required=false)String reason,HttpServletRequest r){return response(content.deleteCategory(actor(r),categoryId(id),reason),r);}
    @GetMapping("/admin/products") public Object products(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="audit_status",defaultValue="")String audit,@RequestParam(name="shelf_status",defaultValue="")String shelf,@RequestParam(defaultValue="")String campus,HttpServletRequest r){return response(content.products(actor(r),page,size,audit,shelf,campus),r);}
    @PostMapping("/admin/products/{id}/{action:review|shelf}") public Object productAction(@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(content.productAction(actor(r),AccountService.id(id),action,body,key),r);}
    @GetMapping("/admin/posts") public Object posts(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="post_status",defaultValue="")String state,@RequestParam(defaultValue="")String keyword,HttpServletRequest r){return response(content.posts(actor(r),page,size,state,keyword),r);}
    @PostMapping("/admin/posts/{id}/{action:review|withdraw}") public Object postAction(@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(content.postAction(actor(r),id,action.equals("review"),body,key),r);}
    @GetMapping("/admin/dashboard") public Object dashboard(HttpServletRequest r){return response(content.dashboard(),r);}
    @GetMapping("/admin/audit-logs") public Object logs(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="object_type",defaultValue="")String type,@RequestParam(name="object_id",defaultValue="")String target,HttpServletRequest r){return response(content.logs(actor(r),page,size,type,target),r);}
}
