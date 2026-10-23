package com.campuspi.member;

import com.campuspi.account.*;
import com.campuspi.admin.BusinessFileService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.campuspi.member.MemberSupport.noBody;

@RestController
@RequestMapping("/api/v1")
public class MemberContentController {
    private final MemberContentService content;private final BusinessFileService files;
    public MemberContentController(MemberContentService content,BusinessFileService files){this.content=content;this.files=files;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    private Object response(Object value,HttpServletRequest r){return ApiSupport.envelope(value,ApiSupport.requestId(r));}
    private Object productResponse(Object value,HttpServletRequest r){return response(files.decorateProducts(value,actor(r)),r);}
    @GetMapping("/products") public Object products(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(defaultValue="")String campus,@RequestParam(name="category_id",required=false)Integer category,@RequestParam(defaultValue="")String keyword,@RequestParam(name="min_price",defaultValue="")String min,@RequestParam(name="max_price",defaultValue="")String max,HttpServletRequest r){return productResponse(content.products(actor(r),page,size,campus,category,keyword,min,max,"","",""),r);}
    @GetMapping("/products/{id}") public Object product(@PathVariable String id,HttpServletRequest r){return productResponse(content.product(AccountService.id(id),actor(r)),r);}
    @GetMapping("/me/products") public Object mine(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="audit_status",defaultValue="")String audit,@RequestParam(name="shelf_status",defaultValue="")String shelf,HttpServletRequest r){return productResponse(content.products(actor(r),page,size,"",null,"","","","published",audit,shelf),r);}
    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return productResponse(content.saveProduct(actor(r),null,body,key),r);}
    @PutMapping("/products/{id}") public Object edit(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){return productResponse(content.saveProduct(actor(r),AccountService.id(id),body,null),r);}
    @PostMapping("/products/{id}/shelf") public Object shelf(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){return productResponse(content.shelf(actor(r),AccountService.id(id),body),r);}
    @PutMapping("/products/{id}/favorite") public Object favorite(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(content.favorite(actor(r),AccountService.id(id),true),r);}
    @DeleteMapping("/products/{id}/favorite") public Object unfavorite(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(content.favorite(actor(r),AccountService.id(id),false),r);}
    @GetMapping("/me/product-favorites") public Object favorites(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return productResponse(content.products(actor(r),page,size,"",null,"","","","favorites","",""),r);}
    @GetMapping("/posts") public Object posts(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="category_id",required=false)Integer category,@RequestParam(defaultValue="")String keyword,HttpServletRequest r){return response(content.posts(actor(r),page,size,category,keyword,"",""),r);}
    @GetMapping("/posts/{id}") public Object post(@PathVariable String id,HttpServletRequest r){return response(content.post(id,actor(r)),r);}
    @PostMapping("/posts") @ResponseStatus(HttpStatus.CREATED) public Object createPost(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return response(content.savePost(actor(r),null,body,key),r);}
    @PutMapping("/posts/{id}") public Object editPost(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){return response(content.savePost(actor(r),id,body,null),r);}
    @DeleteMapping("/posts/{id}") public Object deletePost(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(content.deletePost(actor(r),id),r);}
    @GetMapping("/me/posts") public Object myPosts(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,@RequestParam(name="post_status",defaultValue="")String state,HttpServletRequest r){return response(content.posts(actor(r),page,size,null,"","published",state),r);}
    @PutMapping("/posts/{id}/collection") public Object collect(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(content.collect(actor(r),id,true),r);}
    @DeleteMapping("/posts/{id}/collection") public Object uncollect(@PathVariable String id,@RequestBody(required=false)String body,HttpServletRequest r){noBody(body);return response(content.collect(actor(r),id,false),r);}
    @GetMapping("/me/post-collections") public Object collections(@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size,HttpServletRequest r){return response(content.posts(actor(r),page,size,null,"","collections",""),r);}
}
