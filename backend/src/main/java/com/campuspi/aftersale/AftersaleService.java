package com.campuspi.aftersale;

import com.campuspi.account.*;
import com.campuspi.admin.AdminOrderService;
import com.campuspi.admin.AdminPolicy;
import com.campuspi.admin.BusinessFileService;
import com.campuspi.core.BusinessNumbers;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class AftersaleService {
    private final JdbcTemplate db;
    private final com.campuspi.notification.ReviewNotifications notifications;
    private final AccountService accounts;
    private final AdminPolicy policy;
    private final BusinessNumbers numbers;
    private final BusinessFileService files;
    private final ObjectMapper json;

    public AftersaleService(JdbcTemplate db, AccountService accounts, AdminPolicy policy,
            BusinessNumbers numbers, BusinessFileService files, ObjectMapper json, com.campuspi.notification.ReviewNotifications notifications) {
        this.notifications=notifications; this.db=db; this.accounts=accounts; this.policy=policy; this.numbers=numbers; this.files=files; this.json=json;
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args); return rows.isEmpty()?null:rows.get(0);
    }
    private long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private boolean party(Map<String,Object> order,Identity actor) {return actor.id()==number(order,"user_id")||actor.id()==number(order,"seller_id");}
    private String time(Object value) {return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private String money(Object value) {return value==null?null:((BigDecimal)value).setScale(2).toPlainString();}
    private Map<String,Object> view(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("aftersale_id","order_id","aftersale_status","apply_reason","audit_reason"))result.put(key,row.get(key));
        result.put("user_id",row.get("user_id").toString());
        for(String key:List.of("apply_amount","approve_amount"))result.put(key,money(row.get(key)));
        for(String key:List.of("apply_time","audit_time","complete_time"))result.put(key,time(row.get(key)));
        Object refs=row.get("evidence_urls");
        try {result.put("evidence_refs",refs==null?List.of():json.readValue(refs instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):refs.toString(),Object.class));}
        catch(Exception ex) {throw new IllegalStateException("Invalid stored evidence",ex);}
        return result;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException(ex);}}
    private BigDecimal amount(JsonNode body,String field) {
        String value=text(body,field,4,13,false);
        if(!value.matches("(0|[1-9][0-9]{0,9})\\.[0-9]{2}"))throw new ApiException(400,"金额必须为两位小数字符串",field);
        return new BigDecimal(value);
    }
    private Map<String,Object> order(String id,boolean lock) {
        // Do not read shipping addresses or payment identifiers for a zero-money dispute.
        var row=one("SELECT order_id,order_type,user_id,seller_id,reward_task_id,order_status FROM user_orders WHERE order_id=?"+(lock?" FOR UPDATE":""),id);
        if(row==null)throw new ApiException(404,"订单不存在或无权访问"); return row;
    }
    private Map<String,Object> find(String id) {
        var row=one("SELECT * FROM order_aftersales WHERE aftersale_id=?",id);
        if(row==null)throw new ApiException(404,"售后不存在或无权访问"); return row;
    }
    /** Lock order first, then the case. Legacy application-time state is never inferred. */
    private Map<String,Object> lockCase(String id,Map<String,Object> order) {
        var row=one("SELECT * FROM order_aftersales WHERE aftersale_id=? FOR UPDATE",id);
        if(row==null||!Objects.equals(row.get("order_id"),order.get("order_id"))||number(row,"user_id")!=number(order,"user_id"))
            throw new ApiException(409,"工单与订单关系异常，需要核对");
        return row;
    }
    private void taskContext(Map<String,Object> row,Map<String,Object> order) {
        if(!"任务".equals(order.get("order_type")))throw new ApiException(501,"商品采用线下交易，退换或款项问题请双方线下协商");
        if(((BigDecimal)row.get("apply_amount")).signum()!=0)throw new ApiException(409,"任务争议申请金额异常，需要核对");
        var context=one("SELECT order_id,order_type FROM aftersale_order_context WHERE aftersale_id=? FOR UPDATE",row.get("aftersale_id"));
        if(context==null||!Objects.equals(context.get("order_id"),order.get("order_id"))||!"任务".equals(context.get("order_type")))
            throw new ApiException(409,"缺少申请时的持久上下文，不能推断历史状态");
    }
    public Object create(Identity actor,JsonNode body,String key) {
        fields(body,"order_id","apply_amount","apply_reason","evidence_refs");
        String id=AdminOrderService.orderId(text(body,"order_id",19,19,false)),reason=text(body,"apply_reason",1,500,true);
        BigDecimal amount=amount(body,"apply_amount");
        JsonNode evidence=body.get("evidence_refs");
        if(evidence==null||!evidence.isArray())throw new ApiException(400,"请提供文件引用数组","evidence_refs");
        List<String> refs=evidence.isEmpty()?List.of():files.parseRefs(evidence);
        return accounts.memberMutation(actor,"aftersale-create:"+id,key,body,()->{
            var order=order(id,true);
            if(actor.id()!=number(order,"user_id"))throw new ApiException(404,"订单不存在或无权申请");
            if(!"任务".equals(order.get("order_type")))throw new ApiException(501,"商品采用线下交易，退换或款项问题请双方线下协商");
            if(amount.signum()!=0)throw new ApiException(400,"任务争议金额只能为0.00","apply_amount");
            if(!Set.of("进行中","待验收","已完成","未完成","已取消").contains(order.get("order_status")))throw new ApiException(409,"任务订单状态异常，需要核对");
            if(db.queryForObject("SELECT COUNT(*) FROM order_aftersales WHERE order_id=? AND aftersale_status IN ('待审核','退款中')",Long.class,id)>0)
                throw new ApiException(409,"该订单已有处理中工单");
            String after=numbers.nextAftersale();
            db.update("INSERT INTO order_aftersales(aftersale_id,order_id,user_id,apply_amount,apply_reason,evidence_urls) VALUES(?,?,?,?,?,?)",after,id,actor.id(),amount,reason,encode(refs));
            db.update("INSERT INTO aftersale_order_context(aftersale_id,order_id,order_type,original_order_status) VALUES(?,?,?,?)",after,id,order.get("order_type"),order.get("order_status"));
            files.bindRefs(actor.id(),"AFTERSALE","AFTERSALE",after,refs);
            return view(find(after));
        });
    }
    public Object list(Identity actor,int page,int size) {
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        return accounts.sessionTransaction(actor,()->{
            long count=db.queryForObject("SELECT COUNT(*) FROM order_aftersales a JOIN user_orders o ON o.order_id=a.order_id WHERE a.user_id=? AND o.user_id=a.user_id",Long.class,actor.id());
            var rows=db.queryForList("SELECT a.* FROM order_aftersales a JOIN user_orders o ON o.order_id=a.order_id WHERE a.user_id=? AND o.user_id=a.user_id ORDER BY a.apply_time DESC,a.aftersale_id DESC LIMIT ? OFFSET ?",actor.id(),size,(page-1)*size);
            return Map.of("items",rows.stream().map(this::view).toList(),"total",Long.toString(count),"page",page,"page_size",size);
        });
    }
    public Object detail(Identity actor,String id) {
        AdminOrderService.aftersaleId(id);
        return accounts.sessionTransaction(actor,()->{
            var row=find(id);var order=order(row.get("order_id").toString(),false);
            if(!party(order,actor)||number(row,"user_id")!=number(order,"user_id"))throw new ApiException(404,"售后不存在或无权访问");
            return view(row);
        });
    }
    public Object cancel(Identity actor,String id,String key) {
        AdminOrderService.aftersaleId(id);
        return accounts.memberMutation(actor,"aftersale-cancel:"+id,key,Map.of(),()->{
            var original=find(id);var order=order(original.get("order_id").toString(),true);
            if(number(original,"user_id")!=actor.id()||number(order,"user_id")!=actor.id())throw new ApiException(404,"售后不存在或无权撤回");
            var row=lockCase(id,order); taskContext(row,order);
            if(!"待审核".equals(row.get("aftersale_status")))throw new ApiException(409,"仅待审核工单可撤回");
            db.update("UPDATE order_aftersales SET aftersale_status='已取消' WHERE aftersale_id=?",id);
            return view(find(id));
        });
    }
    public Object review(Identity actor,String id,JsonNode body,String key) {
        policy.require(actor,"ORDERS"); AdminOrderService.aftersaleId(id);
        fields(body,"decision","reason","approve_amount");
        String decision=text(body,"decision",1,10,false),reason=text(body,"reason",1,500,true);
        if(!Set.of("通过","驳回").contains(decision))throw new ApiException(400,"审核决定无效","decision");
        BigDecimal approved;
        if("通过".equals(decision))approved=amount(body,"approve_amount");
        else {if(body.has("approve_amount"))throw new ApiException(400,"驳回不能包含批准金额","approve_amount");approved=null;}
        return accounts.adminMutation(actor,"aftersale-review:"+id,key,body,()->{
            var original=find(id);var order=order(original.get("order_id").toString(),true);
            var row=lockCase(id,order); taskContext(row,order);
            if(!"待审核".equals(row.get("aftersale_status")))throw new ApiException(409,"工单已处理或撤回");
            if(approved!=null&&approved.signum()!=0)throw new ApiException(400,"任务争议批准金额只能为0.00","approve_amount");
            String next="通过".equals(decision)?"已完成":"已驳回";
            db.update("UPDATE order_aftersales SET aftersale_status=?,approve_amount=?,reviewer_id=?,audit_reason=?,audit_time=UTC_TIMESTAMP(3),complete_time=IF(?='已完成',UTC_TIMESTAMP(3),NULL) WHERE aftersale_id=?",next,approved,actor.id(),reason,next,id);
            db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,'售后',?,'审核任务争议',?)",actor.id(),id,reason);
            notifications.send(actor.id(),number(row,"user_id"),"任务争议",id,next,reason);
            // No user_orders, RewardTasks, reward account, stock or payment mutation is permitted here.
            return view(find(id));
        });
    }
}
