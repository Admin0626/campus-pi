package com.campuspi.admin;

import com.campuspi.account.*;
import com.campuspi.core.OfflineTrade;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class AdminOrderService {
    private static final Set<String> ORDER_STATES=Set.of("待线下交易","待支付","已支付","已发货","待确认收货","已完成","已取消","售后中","已退款","进行中","待验收","未完成");
    private static final String ORDER_FIELDS="order_id,order_type,user_id,seller_id,product_id,reward_task_id,item_name,trade_channel,payment_method,quantity,unit_price,total_amount,shipping_fee,actual_amount,order_status,order_time,payment_time,shipping_time,completion_time";
    private final JdbcTemplate db;
    private final AccountService accounts;
    private final AdminPolicy policy;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public AdminOrderService(JdbcTemplate db,AccountService accounts,AdminPolicy policy,com.fasterxml.jackson.databind.ObjectMapper json){this.db=db;this.accounts=accounts;this.policy=policy;this.json=json;}
    public static String orderId(String id){if(id==null||!id.matches("ORDER[0-9]{14}"))throw new ApiException(400,"订单编号无效","order_id");return id;}
    public static String aftersaleId(String id){if(id==null||!id.matches("AFTER[0-9]{14}"))throw new ApiException(400,"售后编号无效","aftersale_id");return id;}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String string(Object value){return value==null?null:value.toString();}
    private String money(Object value){return value==null?null:((BigDecimal)value).setScale(2).toPlainString();}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private void enumFilter(String value,Set<String> choices){if(!value.isEmpty()&&!choices.contains(value))throw new ApiException(400,"筛选条件无效");}
    private void paging(int page,int size){if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");}
    private Object page(String table,String columns,String where,List<Object> args,int page,int size,String order,Function<Map<String,Object>,Object> view){
        paging(page,size);long count=db.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());var values=new ArrayList<>(args);values.add(size);values.add((page-1)*size);
        return Map.of("items",db.queryForList("SELECT "+columns+" FROM "+table+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()).stream().map(view).toList(),"total",Long.toString(count),"page",page,"page_size",size);
    }
    private Object orderView(Map<String,Object> row,boolean address){
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("order_id","order_type","item_name","quantity","order_status","trade_channel"))result.put(key,row.get(key));
        result.put("buyer_id",string(row.get("user_id")));result.put("seller_id",string(row.get("seller_id")));
        result.put("product_id",string(row.get("product_id")));result.put("reward_task_id",string(row.get("reward_task_id")));
        for(String key:List.of("unit_price","total_amount","shipping_fee","actual_amount"))result.put(key,money(row.get(key)));
        for(String key:List.of("order_time","payment_time","shipping_time","completion_time"))result.put(key,time(row.get(key)));
        result.put("shipping_address",address&&"商品".equals(row.get("order_type"))?row.get("shipping_address"):null);return result;
    }
    private void audit(Identity actor,String type,String id,String action,String reason){db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,?,?,?,?)",actor.id(),type,id,action,reason);}
    public Object orders(int page,int size,String type,String state,String id){
        enumFilter(type,Set.of("商品","任务"));enumFilter(state,ORDER_STATES);String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!type.isEmpty()){where+=" AND order_type=?";args.add(type);}if(!state.isEmpty()){where+=" AND order_status=?";args.add(state);}if(!id.isEmpty()){where+=" AND order_id=?";args.add(orderId(id));}
        // Sensitive addresses are not selected into an ordinary administrator's list request.
        return page("user_orders",ORDER_FIELDS,where,args,page,size,"order_time DESC,order_id DESC",row->orderView(row,false));
    }
    public Object detail(Identity actor,String id){
        policy.require(actor,"ORDERS");orderId(id);
        return accounts.adminTransaction(actor,()->{
            var row=one("SELECT "+ORDER_FIELDS+",shipping_address FROM user_orders WHERE order_id=?",id);if(row==null)throw new ApiException(404,"订单不存在");
            audit(actor,"订单",id,"读取敏感详情","订单处理详情访问");return orderView(row,true);
        });
    }
    public Object cancel(Identity actor,String id,JsonNode body,String key){
        orderId(id);fields(body,"reason");String reason=text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"order-cancel:"+id,key,body,()->{
            var order=one("SELECT "+ORDER_FIELDS+",payment_transaction_no FROM user_orders WHERE order_id=? FOR UPDATE",id);if(order==null)throw new ApiException(404,"订单不存在");
            OfflineTrade.requirePending(order);
            var product=one("SELECT product_id,user_id,stock_quantity,shelf_status,audit_status FROM product_info WHERE product_id=? FOR UPDATE",order.get("product_id"));
            if(product==null||!Objects.equals(product.get("user_id"),order.get("seller_id")))throw new ApiException(409,"订单商品归属不一致，需要核对");
            long stock=((Number)product.get("stock_quantity")).longValue()+((Number)order.get("quantity")).longValue();if(stock>Integer.MAX_VALUE)throw new ApiException(409,"恢复库存超过上限");
            String shelf=string(product.get("shelf_status"));if("已售出".equals(shelf)&&"已通过".equals(product.get("audit_status")))shelf="在售";
            db.update("UPDATE user_orders SET order_status='已取消' WHERE order_id=?",id);
            db.update("UPDATE product_info SET stock_quantity=?,shelf_status=? WHERE product_id=?",stock,shelf,product.get("product_id"));
            audit(actor,"订单",id,"取消",reason);
            // A cancellation response must not bypass the separate sensitive-detail permission.
            order.put("order_status","已取消");return orderView(order,false);
        });
    }
    private Object aftersaleView(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("aftersale_id","order_id","aftersale_status","apply_reason","audit_reason"))result.put(key,row.get(key));result.put("user_id",string(row.get("user_id")));
        for(String key:List.of("apply_amount","approve_amount"))result.put(key,money(row.get(key)));for(String key:List.of("apply_time","audit_time","complete_time"))result.put(key,time(row.get(key)));
        Object refs=row.get("evidence_urls");try {result.put("evidence_refs",refs==null?List.of():json.readValue(refs instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):refs.toString(),Object.class));}catch(Exception ex){throw new IllegalStateException(ex);}return result;
    }
    public Object aftersales(int page,int size,String state){
        enumFilter(state,Set.of("待审核","已驳回","退款中","已完成","已取消"));String where=state.isEmpty()?"":" WHERE aftersale_status=?";List<Object> args=state.isEmpty()?List.of():List.of(state);
        return page("order_aftersales","aftersale_id,order_id,user_id,aftersale_status,apply_amount,approve_amount,apply_reason,evidence_urls,audit_reason,apply_time,audit_time,complete_time",where,args,page,size,"apply_time ASC,aftersale_id ASC",this::aftersaleView);
    }
}
