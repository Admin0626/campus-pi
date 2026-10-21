package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class AdminContentService {
    private final JdbcTemplate db;
    private final AccountService accounts;
    private final ObjectMapper json;
    private final AdminPolicy policy;
    public AdminContentService(JdbcTemplate db,AccountService accounts,ObjectMapper json,AdminPolicy policy) {
        this.db=db;this.accounts=accounts;this.json=json;this.policy=policy;
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> require(String table,String column,Object value) {
        var row=one("SELECT * FROM "+table+" WHERE "+column+"=? FOR UPDATE",value);
        if(row==null)throw new ApiException(404,"记录不存在");return row;
    }
    private String time(Object value) {
        if(value==null)return null;
        return (value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();
    }
    private boolean flag(Object value) {return Boolean.TRUE.equals(value)||(value instanceof Number n && n.intValue()!=0);}
    private String money(Object value) {return ((BigDecimal)value).setScale(2).toPlainString();}
    private void enumValue(String value,String field,String... choices) {
        if(value!=null && !value.isEmpty() && !Set.of(choices).contains(value))throw new ApiException(400,"筛选值无效",field);
    }
    private Object page(String table,String where,List<Object> args,int page,int size,String order,Function<Map<String,Object>,Object> view) {
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        long count=db.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());
        var values=new ArrayList<>(args);values.add(size);values.add((page-1)*size);
        var rows=db.queryForList("SELECT * FROM "+table+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray());
        return Map.of("items",rows.stream().map(view).toList(),"total",Long.toString(count),"page",page,"page_size",size);
    }
    private void audit(Identity actor,String type,Object id,String action,String reason,Object before,Object after) {
        try {db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,before_data,after_data) VALUES(?,?,?,?,?,?,?)",
                actor.id(),type,id.toString(),action,reason,json.writeValueAsString(before),json.writeValueAsString(after));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
    private Map<String,Object> publicUser(Object id) {
        // Only required public fields; never expose real names, contacts or private campus.
        var row=one("SELECT user_id,username FROM users WHERE user_id=?",id);
        return Map.of("user_id",row.get("user_id").toString(),"username",row.get("username"));
    }
    private Object category(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("category_id","category_name","category_level","parent_category_id","category_description"))result.put(key,row.get(key));
        return result;
    }
    public Object categories() {
        // Contract is a flat, bounded two-level list, shared by posts and products.
        var rows=db.queryForList("SELECT * FROM category ORDER BY category_level,category_id LIMIT 501");
        if(rows.size()>500)throw new ApiException(503,"分类超过现有契约上限，需要先调整分类查询契约");
        return Map.of("items",rows.stream().map(this::category).toList());
    }
    public Object saveCategory(Identity actor,Integer target,JsonNode body,String key) {
        fields(body,"category_name","parent_category_id","category_description");
        String name=text(body,"category_name",1,50,true);
        String description=body.has("category_description")?text(body,"category_description",0,500,false):null;
        if(!body.has("parent_category_id"))throw new ApiException(400,"需要指定父分类或null","parent_category_id");
        JsonNode parent=body.get("parent_category_id");
        if(!parent.isNull() && (!parent.isIntegralNumber() || !parent.canConvertToInt() || parent.intValue()<1))throw new ApiException(400,"父分类编号无效");
        Integer parentId=parent.isNull()?null:parent.intValue();
        java.util.function.Supplier<Object> action=()->{
            Object before=null;
            if(target!=null) {
                var row=require("category","category_id",target);before=category(row);
                if(!Objects.equals(parentId,row.get("parent_category_id")))throw new ApiException(409,"已有分类不能更改层级或父分类");
            } else if(db.queryForObject("SELECT COUNT(*) FROM category",Long.class)>=500)throw new ApiException(409,"已达到分类查询契约上限");
            if(parentId!=null && !"1".equals(require("category","category_id",parentId).get("category_level")))throw new ApiException(409,"父分类必须为一级分类");
            var duplicate=one("SELECT category_id FROM category WHERE parent_category_id <=> ? AND category_name=?",parentId,name);
            if(duplicate!=null && !Objects.equals(target,duplicate.get("category_id")))throw new ApiException(409,"同级分类名称已存在",null,"UNIQUE_CONFLICT");
            int id;
            if(target==null) {
                KeyHolder generated=new GeneratedKeyHolder();
                db.update(conn->{var s=conn.prepareStatement("INSERT INTO category(category_name,category_level,parent_category_id,category_description) VALUES(?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
                    s.setString(1,name);s.setString(2,parentId==null?"1":"2");s.setObject(3,parentId);s.setString(4,description);return s;},generated);
                id=generated.getKey().intValue();
            } else {id=target;db.update("UPDATE category SET category_name=?,category_description=? WHERE category_id=?",name,description,id);}
            Object result=category(require("category","category_id",id));
            audit(actor,"分类",id,target==null?"创建":"修改",target==null?"创建分类":"修改分类",before,result);return result;
        };
        return target==null?accounts.adminMutation(actor,"category-create",key,body,action):accounts.adminTransaction(actor,action);
    }
    public Object deleteCategory(Identity actor,int target,String reason) {
        // HTTP headers are byte strings. Decode UTF-8 percent encoding before validating business text.
        try {if(reason!=null)reason=java.net.URLDecoder.decode(reason.replace("+","%2B"),java.nio.charset.StandardCharsets.UTF_8);}
        catch(IllegalArgumentException ex){throw new ApiException(400,"操作原因编码无效");}
        if(reason==null || reason.isBlank() || reason.codePointCount(0,reason.length())>500)throw new ApiException(400,"删除分类需要1–500字符的操作原因");
        final String actionReason=reason.trim();
        return accounts.adminTransaction(actor,()->{
            var before=one("SELECT * FROM category WHERE category_id=? FOR UPDATE",target);
            if(before==null)return null;
            long refs=db.queryForObject("SELECT (SELECT COUNT(*) FROM category WHERE parent_category_id=?)+(SELECT COUNT(*) FROM product_info WHERE category_id=?)+(SELECT COUNT(*) FROM post_info WHERE category_id=?)",Long.class,target,target,target);
            if(refs>0)throw new ApiException(409,"分类仍有子分类、商品或帖子引用");
            try {db.update("DELETE FROM category WHERE category_id=?",target);}catch(DataIntegrityViolationException ex){throw new ApiException(409,"分类仍被引用");}
            audit(actor,"分类",target,"删除",actionReason,category(before),null);return null;
        });
    }
    private Object product(Map<String,Object> row,Identity viewer) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("category_id","product_name","product_desc","campus","location_label","trade_mode","stock_quantity","audit_status","shelf_status"))result.put(key,row.get(key));
        result.put("product_id",row.get("product_id").toString());result.put("seller",publicUser(row.get("user_id")));
        result.put("original_price",money(row.get("original_price")));result.put("discount_price",money(row.get("discount_price")));
        // Private file references must be resolved by the file-access service, never emitted as arbitrary URLs.
        result.put("image_urls",List.of());result.put("is_favorited",db.queryForObject("SELECT COUNT(*) FROM product_favor WHERE product_id=? AND user_id=?",Long.class,row.get("product_id"),viewer.id())>0);
        result.put("create_time",time(row.get("create_time")));result.put("updated_at",time(row.get("updated_at")));return result;
    }
    public Object products(Identity viewer,int page,int size,String audit,String shelf,String campus) {
        enumValue(audit,"audit_status","待审核","已通过","已驳回");enumValue(shelf,"shelf_status","在售","已下架","已售出");
        if(campus.length()>50)throw new ApiException(400,"校区筛选过长");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!audit.isEmpty()){where+=" AND audit_status=?";args.add(audit);}
        if(!shelf.isEmpty()){where+=" AND shelf_status=?";args.add(shelf);}
        if(!campus.isEmpty()){where+=" AND campus=?";args.add(campus);}
        return page("product_info",where,args,page,size,"create_time DESC,product_id DESC",row->product(row,viewer));
    }
    public Object productAction(Identity actor,long target,String action,JsonNode body,String key) {
        boolean review=action.equals("review");fields(body,review?"decision":"shelf_status","reason");
        String next=text(body,review?"decision":"shelf_status",1,10,false),reason=text(body,"reason",1,500,true);
        enumValue(next,"decision",review?new String[]{"通过","驳回"}:new String[]{"在售","已下架"});
        return accounts.adminMutation(actor,"product-"+action+":"+target,key,body,()->{
            var row=require("product_info","product_id",target);Object before=product(row,actor);
            if(review) {
                if(!"待审核".equals(row.get("audit_status")))throw new ApiException(409,"只能审核待审核商品");
                String shelf=next.equals("通过")&&((Number)row.get("stock_quantity")).intValue()>0?"在售":"已下架";
                db.update("UPDATE product_info SET audit_status=?,shelf_status=?,reviewer_id=?,audit_reason=?,audited_at=UTC_TIMESTAMP(3) WHERE product_id=?",next.equals("通过")?"已通过":"已驳回",shelf,actor.id(),reason,target);
            } else {
                if(next.equals("在售") && (!"已通过".equals(row.get("audit_status")) || ((Number)row.get("stock_quantity")).intValue()<1 || !"ACTIVE".equals(require("users","user_id",row.get("user_id")).get("account_status"))))throw new ApiException(409,"上架需要审核通过、库存充足且卖方账号启用");
                db.update("UPDATE product_info SET shelf_status=? WHERE product_id=?",next,target);
            }
            Object result=product(require("product_info","product_id",target),actor);audit(actor,"商品",target,review?"审核":"上下架",reason,before,result);return result;
        });
    }
    private Object post(Map<String,Object> row,Identity viewer) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("post_id","category_id","post_title","post_content","post_status"))result.put(key,row.get(key));
        boolean anonymous=flag(row.get("is_anonymous"));
        var author=anonymous?null:publicUser(row.get("publisher_id"));
        result.put("is_anonymous",anonymous);result.put("author",author);result.put("author_label",anonymous?"校园同学":author.get("username"));
        result.put("view_count",row.get("view_count").toString());
        result.put("collection_count",db.queryForObject("SELECT COUNT(*) FROM post_collection WHERE post_id=? AND collection_status='已收藏'",Long.class,row.get("post_id")).toString());
        result.put("is_collected",db.queryForObject("SELECT COUNT(*) FROM post_collection WHERE post_id=? AND user_id=? AND collection_status='已收藏'",Long.class,row.get("post_id"),viewer.id())>0);result.put("publish_time",time(row.get("publish_time")));return result;
    }
    public Object posts(Identity viewer,int page,int size,String state,String keyword) {
        enumValue(state,"post_status","待审核","已发布","已驳回","已下架","已删除");
        if(keyword.length()>100)throw new ApiException(400,"搜索关键词过长");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!state.isEmpty()){where+=" AND post_status=?";args.add(state);}
        if(!keyword.isBlank()){where+=" AND LOCATE(?,post_title)>0";args.add(keyword.trim());}
        return page("post_info",where,args,page,size,"publish_time DESC,post_id DESC",row->post(row,viewer));
    }
    public Object postAction(Identity actor,String target,boolean review,JsonNode body,String key) {
        if(target.isBlank() || target.length()>50)throw new ApiException(400,"帖子编号无效");
        if(review)fields(body,"decision","reason");else fields(body,"reason");
        String next=review?text(body,"decision",1,10,false):"下架",reason=text(body,"reason",1,500,true);
        if(review)enumValue(next,"decision","通过","驳回");
        return accounts.adminMutation(actor,"post-"+(review?"review":"withdraw")+":"+target,key,body,()->{
            var row=require("post_info","post_id",target);Object before=post(row,actor);
            if(!(review?"待审核":"已发布").equals(row.get("post_status")))throw new ApiException(409,"当前帖子状态不能执行此操作");
            db.update("UPDATE post_info SET post_status=? WHERE post_id=?",review?(next.equals("通过")?"已发布":"已驳回"):"已下架",target);
            if(!review)db.update("UPDATE post_collection SET collection_status='失效' WHERE post_id=?",target);
            Object result=post(require("post_info","post_id",target),actor);audit(actor,"帖子",target,review?"审核":"下架",reason,before,result);return result;
        });
    }
    public Object dashboard() {
        var row=db.queryForMap("SELECT (SELECT COUNT(*) FROM users) user_count,(SELECT COUNT(*) FROM post_info WHERE post_status='已发布') published_post_count,(SELECT COUNT(*) FROM product_info WHERE audit_status='已通过' AND shelf_status='在售') onsale_product_count,(SELECT COUNT(*) FROM RewardTasks WHERE task_status='已完成') completed_task_count,(SELECT COALESCE(SUM(actual_amount),0.00) FROM user_orders WHERE order_type='商品' AND order_status='已完成') completed_product_amount,UTC_TIMESTAMP(3) as_of");
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("user_count","published_post_count","onsale_product_count","completed_task_count"))result.put(key,row.get(key).toString());
        result.put("completed_product_amount",money(row.get("completed_product_amount")));result.put("as_of",time(row.get("as_of")));return result;
    }
    public Object logs(Identity actor,int page,int size,String type,String target) {
        policy.require(actor,"AUDIT");
        if(type.length()>30 || target.length()>50)throw new ApiException(400,"审计筛选条件过长");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!type.isEmpty()){where+=" AND object_type=?";args.add(type);}
        if(!target.isEmpty()){where+=" AND object_id=?";args.add(target);}
        return page("admin_audit_log",where,args,page,size,"created_at DESC,audit_id DESC",row->{
            var result=new LinkedHashMap<String,Object>();
            for(String key:List.of("object_type","object_id","action_name","reason"))result.put(key,row.get(key));
            for(String key:List.of("audit_id","operator_id"))result.put(key,row.get(key).toString());
            result.put("created_at",time(row.get("created_at")));return result;
        });
    }
}
