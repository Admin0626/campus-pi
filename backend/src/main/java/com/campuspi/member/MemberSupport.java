package com.campuspi.member;

import com.campuspi.account.*;
import com.campuspi.admin.BusinessFileService;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Component;

@Component
public class MemberSupport {
    final JdbcTemplate db;
    final AccountService accounts;
    final BusinessFileService files;
    final ObjectMapper json;
    public MemberSupport(JdbcTemplate db,AccountService accounts,BusinessFileService files,ObjectMapper json){this.db=db;this.accounts=accounts;this.files=files;this.json=json;}
    Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    Map<String,Object> require(String table,String column,Object id,boolean lock){var row=one("SELECT * FROM "+table+" WHERE "+column+"=?"+(lock?" FOR UPDATE":""),id);if(row==null)throw new ApiException(404,"记录不存在或无权读取");return row;}
    long insert(String sql,Object...args){KeyHolder keys=new GeneratedKeyHolder();db.update(conn->{var stmt=conn.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)stmt.setObject(i+1,args[i]);return stmt;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static String string(Object value){return value==null?null:value.toString();}
    static boolean flag(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}
    static String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    static String money(Object value){return value==null?null:((BigDecimal)value).setScale(2).toPlainString();}
    static String limited(String value,int max){if(value==null)return "";if(value.codePointCount(0,value.length())>max)throw new ApiException(400,"筛选条件过长");return value;}
    static void choice(String value,String...allowed){if(value!=null&&!value.isEmpty()&&!Set.of(allowed).contains(value))throw new ApiException(400,"状态或类型无效");}
    static void noBody(String body){if(body!=null&&!body.isBlank())throw new ApiException(400,"此操作不接收请求体");}
    static int integer(JsonNode body,String key,int minimum,int maximum){var value=body.get(key);if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<minimum||value.intValue()>maximum)throw new ApiException(400,key+"必须为范围内的整数",key);return value.intValue();}
    static BigDecimal amount(JsonNode body,String key){String value=AccountService.text(body,key,4,13,false);if(!value.matches("(0|[1-9][0-9]{0,9})\\.[0-9]{2}"))throw new ApiException(400,"金额须为固定两位小数字符串",key);return new BigDecimal(value);}
    static BigDecimal priceFilter(String value){limited(value,13);if(!value.matches("(0|[1-9][0-9]{0,9})\\.[0-9]{2}"))throw new ApiException(400,"价格筛选格式错误");return new BigDecimal(value);}
    static String text(JsonNode body,String key,int min,int max){String value=AccountService.text(body,key,min,max,true);if(value.codePoints().anyMatch(c->c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'))throw new ApiException(400,"文本包含不支持的控制字符",key);return value;}
    static String optional(JsonNode body,String key,int max){return body.has(key)?text(body,key,0,max):null;}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("Invalid JSON");}}
    Object decode(Object value){try{return value==null?null:json.readValue(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString(),Object.class);}catch(Exception ex){throw new IllegalStateException("Stored JSON invalid");}}
    Object page(String from,String select,String where,List<Object>args,int page,int size,String order,Function<Map<String,Object>,Object> view){
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        long total=db.queryForObject("SELECT COUNT(*) FROM "+from+where,Long.class,args.toArray());var values=new ArrayList<>(args);values.add(size);values.add((page-1)*size);
        return Map.of("items",db.queryForList("SELECT "+select+" FROM "+from+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()).stream().map(view).toList(),"total",Long.toString(total),"page",page,"page_size",size);
    }
    void eligible(long user,String campus){var row=require("users","user_id",user,true);if(!"ACTIVE".equals(row.get("account_status")))throw new ApiException(403,"账号已停用",null,"ACCOUNT_DISABLED");if(!"已通过".equals(row.get("identity_verification"))||!"已通过".equals(row.get("campus_verification")))throw new ApiException(403,"请先完成实名和校园认证",null,"VERIFICATION_REQUIRED");if(!campus.equals(row.get("campus")))throw new ApiException(403,"只能操作本人认证校区",null,"CAMPUS_MISMATCH");}
    void category(int id){require("category","category_id",id,true);}
    void audit(AccountService.Identity actor,String type,Object id,String action,String reason){db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,?,?,?,?)",actor.id(),type,id.toString(),action,reason);}
    Object publicUser(Object id){var row=one("SELECT user_id,username FROM users WHERE user_id=?",id);return row==null?null:Map.of("user_id",row.get("user_id").toString(),"username",row.get("username"));}
    Object product(Map<String,Object> row,AccountService.Identity actor){
        var out=new LinkedHashMap<String,Object>();for(String field:List.of("category_id","product_name","product_desc","campus","location_label","trade_mode","stock_quantity","audit_status","shelf_status"))out.put(field,row.get(field));
        out.put("product_id",row.get("product_id").toString());out.put("seller",publicUser(row.get("user_id")));out.put("original_price",money(row.get("original_price")));out.put("discount_price",money(row.get("discount_price")));out.put("image_urls",List.of());out.put("is_favorited",actor!=null&&db.queryForObject("SELECT COUNT(*) FROM product_favor WHERE product_id=? AND user_id=?",Integer.class,row.get("product_id"),actor.id())>0);out.put("create_time",time(row.get("create_time")));out.put("updated_at",time(row.get("updated_at")));return out;
    }
    Object post(Map<String,Object> row,AccountService.Identity actor){
        var out=new LinkedHashMap<String,Object>();for(String field:List.of("post_id","category_id","post_title","post_content","post_status"))out.put(field,row.get(field));boolean anonymous=flag(row.get("is_anonymous"));Object author=anonymous?null:publicUser(row.get("publisher_id"));out.put("is_anonymous",anonymous);out.put("author",author);out.put("author_label",anonymous?"校园同学":((Map<?,?>)author).get("username"));out.put("view_count",row.get("view_count").toString());out.put("collection_count",db.queryForObject("SELECT COUNT(*) FROM post_collection WHERE post_id=? AND collection_status='已收藏'",Long.class,row.get("post_id")).toString());out.put("is_collected",actor!=null&&db.queryForObject("SELECT COUNT(*) FROM post_collection WHERE post_id=? AND user_id=? AND collection_status='已收藏'",Integer.class,row.get("post_id"),actor.id())>0);out.put("publish_time",time(row.get("publish_time")));return out;
    }
}
