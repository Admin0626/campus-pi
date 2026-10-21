package com.campuspi.admin;

import com.campuspi.account.*;
import com.campuspi.core.BusinessNumbers;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class AdminTaskService {
    private static final Set<String> TASK_STATES=Set.of("待审核","待接取","进行中","待验收","已完成","未完成","已驳回","已取消");
    private final JdbcTemplate db;
    private final AccountService accounts;
    private final ObjectMapper json;
    private final BusinessNumbers numbers;
    private final boolean syntheticCash;
    public AdminTaskService(JdbcTemplate db,AccountService accounts,ObjectMapper json,BusinessNumbers numbers,
            @Value("${campus.tasks.synthetic-cash-enabled:false}")boolean syntheticCash){
        this.db=db;this.accounts=accounts;this.json=json;this.numbers=numbers;this.syntheticCash=syntheticCash;
    }
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> require(String table,String field,Object id,boolean lock){var row=one("SELECT * FROM "+table+" WHERE "+field+"=?"+(lock?" FOR UPDATE":""),id);if(row==null)throw new ApiException(404,"记录不存在");return row;}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private String text(Object value){return value==null?null:value.toString();}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private String money(Object value){return ((BigDecimal)value).setScale(2).toPlainString();}
    private Object decode(Object value){try{return json.readValue(value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):value.toString(),Object.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void enumFilter(String value,Set<String> options){if(!value.isEmpty()&&!options.contains(value))throw new ApiException(400,"筛选状态无效");}
    private Object page(String table,String where,List<Object> args,int page,int size,String order,Function<Map<String,Object>,Object> view){
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        long total=db.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());
        var values=new ArrayList<>(args);values.add(size);values.add((page-1)*size);
        return Map.of("items",db.queryForList("SELECT * FROM "+table+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()).stream().map(view).toList(),"page",page,"page_size",size,"total",Long.toString(total));
    }
    private Object publicUser(Object id){var user=require("users","user_id",id,false);return Map.of("user_id",text(user.get("user_id")),"username",user.get("username"));}
    private boolean related(Map<String,Object> task,Identity actor){return actor!=null&&(actor.admin()||actor.id()==number(task,"publisher_id")||(task.get("taker_id") instanceof Number n&&actor.id()==n.longValue()));}
    private void party(Map<String,Object> task,Identity actor){if(!related(task,actor))throw new ApiException(404,"任务不存在或无权读取");}
    private Object taskView(Map<String,Object> task,Identity actor){
        var result=new LinkedHashMap<String,Object>();result.put("reward_task_id",text(task.get("reward_task_id")));result.put("publisher",publicUser(task.get("publisher_id")));
        boolean privateView=related(task,actor);
        result.put("taker",privateView&&task.get("taker_id")!=null?publicUser(task.get("taker_id")):null);
        for(String key:List.of("task_name","task_description","campus","reward_type","task_status","version"))result.put(key,task.get(key));
        result.put("reward_amount",money(task.get("reward_amount")));result.put("publish_time",time(task.get("publish_time")));
        var order=privateView?one("SELECT order_id FROM user_orders WHERE reward_task_id=?",task.get("reward_task_id")):null;
        result.put("order_id",order==null?null:order.get("order_id"));return result;
    }
    private Object applicationView(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("application_id","reward_task_id","applicant_id"))result.put(key,text(row.get(key)));
        for(String key:List.of("application_status","audit_reason"))result.put(key,row.get(key));
        for(String key:List.of("applied_at","reviewed_at"))result.put(key,time(row.get(key)));return result;
    }
    private Object proofView(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("proof_id","reward_task_id","submitter_id"))result.put(key,text(row.get(key)));
        for(String key:List.of("submission_no","proof_text","audit_status","audit_reason"))result.put(key,row.get(key));
        result.put("evidence_refs",decode(row.get("evidence_urls")));for(String key:List.of("submitted_at","reviewed_at"))result.put(key,time(row.get(key)));return result;
    }
    private Object logView(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("log_id","reward_task_id","operator_id"))result.put(key,text(row.get(key)));
        for(String key:List.of("action_name","from_status","to_status","reason"))result.put(key,row.get(key));result.put("operated_at",time(row.get("operated_at")));return result;
    }
    public Object tasks(Identity actor,int page,int size,String state,String campus){
        enumFilter(state,TASK_STATES);if(campus.length()>50)throw new ApiException(400,"校区过长");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();if(!state.isEmpty()){where+=" AND task_status=?";args.add(state);}if(!campus.isEmpty()){where+=" AND campus=?";args.add(campus);}
        return page("RewardTasks",where,args,page,size,"publish_time DESC,reward_task_id DESC",row->taskView(row,actor));
    }
    public Object detail(long id,Identity actor){
        var task=require("RewardTasks","reward_task_id",id,false);
        var owner=require("users","user_id",task.get("publisher_id"),false);
        if(!related(task,actor)&&(!Set.of("待接取","进行中","待验收","已完成").contains(text(task.get("task_status")))||!"ACTIVE".equals(owner.get("account_status"))))throw new ApiException(404,"任务不存在或无权读取");
        return taskView(task,actor);
    }
    public Object applications(int page,int size,String taskId,String state){
        enumFilter(state,Set.of("待审核","已通过","已驳回","已取消"));String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!taskId.isEmpty()){where+=" AND reward_task_id=?";args.add(id(taskId));}if(!state.isEmpty()){where+=" AND application_status=?";args.add(state);}
        return page("task_applications",where,args,page,size,"applied_at ASC,application_id ASC",this::applicationView);
    }
    public Object proofs(Identity actor,Long taskId,int page,int size,String state){
        enumFilter(state,Set.of("待审核","已通过","补充凭证","未通过"));String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(taskId!=null){party(require("RewardTasks","reward_task_id",taskId,false),actor);where+=" AND reward_task_id=?";args.add(taskId);}
        else if(actor==null||!actor.admin())throw new ApiException(403,"需要管理员权限");
        if(!state.isEmpty()){where+=" AND audit_status=?";args.add(state);}
        return page("task_proofs",where,args,page,size,taskId==null?"submitted_at ASC,proof_id ASC":"submission_no DESC",this::proofView);
    }
    public Object logs(Identity actor,long taskId,int page,int size){
        party(require("RewardTasks","reward_task_id",taskId,false),actor);
        return page("task_audit_log"," WHERE reward_task_id=?",List.of(taskId),page,size,"operated_at DESC,log_id DESC",this::logView);
    }
    public Object balances(Identity actor){
        var items=new ArrayList<Object>();for(String type:List.of("积分","现金")){
            var row=one("SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=? AND reward_type=?",actor.id(),type);
            items.add(Map.of("reward_type",type,"available_amount",row==null?"0.00":money(row.get("available_amount")),"frozen_amount",row==null?"0.00":money(row.get("frozen_amount"))));
        }return Map.of("items",items);
    }
    public Object ledger(Identity actor,int page,int size,String type){
        enumFilter(type,Set.of("积分","现金"));String where=" WHERE user_id=?";var args=new ArrayList<Object>();args.add(actor.id());if(!type.isEmpty()){where+=" AND reward_type=?";args.add(type);}
        return page("reward_ledger",where,args,page,size,"created_at DESC,ledger_id DESC",row->{var result=new LinkedHashMap<String,Object>();for(String key:List.of("ledger_id","reward_task_id"))result.put(key,text(row.get(key)));for(String key:List.of("reward_type","event_type"))result.put(key,row.get(key));for(String key:List.of("available_delta","frozen_delta"))result.put(key,money(row.get(key)));result.put("created_at",time(row.get("created_at")));return result;});
    }
    private String decision(JsonNode body,Set<String> allowed){fields(body,"decision","reason");String decision=AccountService.text(body,"decision",1,10,false);if(!allowed.contains(decision))throw new ApiException(400,"审核决定无效");return decision;}
    private void cash(Map<String,Object> task){if("现金".equals(task.get("reward_type"))&&!syntheticCash)throw new ApiException(501,"现金奖励仅开放显式合成账本测试，当前未启用");}
    private void eligible(Object id,String campus){var user=require("users","user_id",id,true);if(!"ACTIVE".equals(user.get("account_status"))||!"已通过".equals(user.get("identity_verification"))||!"已通过".equals(user.get("campus_verification"))||!campus.equals(user.get("campus")))throw new ApiException(409,"任务参与人的当前校区或认证资格已失效");}
    private void unchangedSettlement(Map<String,Object> task){
        long id=number(task,"reward_task_id");
        if(db.queryForObject("SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=?",Long.class,id)>0||db.queryForObject("SELECT COUNT(*) FROM reward_ledger WHERE reward_task_id=? AND event_type IN ('解冻','支付','收入')",Long.class,id)>0)throw new ApiException(409,"任务奖励已经释放或结算，不能重复处理");
    }
    private Map<String,Object> frozen(Map<String,Object> task){
        unchangedSettlement(task);long id=number(task,"reward_task_id"),owner=number(task,"publisher_id");String type=text(task.get("reward_type"));BigDecimal amount=(BigDecimal)task.get("reward_amount");
        var freeze=one("SELECT * FROM reward_ledger WHERE event_key=? FOR UPDATE","FREEZE:"+id);
        if(freeze==null||number(freeze,"reward_task_id")!=id||number(freeze,"user_id")!=owner||!type.equals(freeze.get("reward_type"))||!"冻结".equals(freeze.get("event_type"))||((BigDecimal)freeze.get("frozen_delta")).compareTo(amount)!=0||((BigDecimal)freeze.get("available_delta")).compareTo(amount.negate())!=0)throw new ApiException(409,"任务缺少一致的奖励冻结记录");
        var account=one("SELECT * FROM reward_accounts WHERE user_id=? AND reward_type=? FOR UPDATE",owner,type);
        if(account==null||((BigDecimal)account.get("frozen_amount")).compareTo(amount)<0)throw new ApiException(409,"任务冻结余额不足，需要核对账本");return account;
    }
    private void release(Map<String,Object> task){
        frozen(task);BigDecimal amount=(BigDecimal)task.get("reward_amount");
        db.update("UPDATE reward_accounts SET available_amount=available_amount+?,frozen_amount=frozen_amount-? WHERE user_id=? AND reward_type=?",amount,amount,task.get("publisher_id"),task.get("reward_type"));
        db.update("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(?,?,?,?,'解冻',?,?)","RELEASE:"+task.get("reward_task_id"),task.get("reward_task_id"),task.get("publisher_id"),task.get("reward_type"),amount,amount.negate());
    }
    private void log(Identity actor,Map<String,Object> task,String action,String next,String reason){
        db.update("INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(?,?,?,?,?,?)",task.get("reward_task_id"),actor.id(),action,task.get("task_status"),next,reason);
        db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,'任务',?,?,?)",actor.id(),text(task.get("reward_task_id")),action,reason);
    }
    public Object publication(Identity actor,long id,JsonNode body,String key){
        String decision=decision(body,Set.of("通过","驳回")),reason=AccountService.text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"task-publication:"+id,key,body,()->{
            var task=require("RewardTasks","reward_task_id",id,true);cash(task);
            if(!"待审核".equals(task.get("task_status")))throw new ApiException(409,"只能审核待发布任务");
            if("通过".equals(decision)){eligible(task.get("publisher_id"),text(task.get("campus")));frozen(task);}else release(task);
            String next=decision.equals("通过")?"待接取":"已驳回";
            db.update("UPDATE RewardTasks SET task_status=?,version=version+1 WHERE reward_task_id=?",next,id);log(actor,task,"发布审核",next,reason);return taskView(require("RewardTasks","reward_task_id",id,false),actor);
        });
    }
    public Object assign(Identity actor,long id,JsonNode body,String key){
        String decision=decision(body,Set.of("通过","驳回")),reason=AccountService.text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"task-application:"+id,key,body,()->{
            var initial=require("task_applications","application_id",id,false);long taskId=number(initial,"reward_task_id");
            var task=require("RewardTasks","reward_task_id",taskId,true);var application=require("task_applications","application_id",id,true);cash(task);
            if(!"待接取".equals(task.get("task_status"))||!"待审核".equals(application.get("application_status")))throw new ApiException(409,"任务已分配或当前申请不可审批");
            String orderId=null;
            if(decision.equals("驳回"))db.update("UPDATE task_applications SET application_status='已驳回',reviewer_id=?,audit_reason=?,reviewed_at=UTC_TIMESTAMP(3) WHERE application_id=?",actor.id(),reason,id);
            else {
                if(number(application,"applicant_id")==number(task,"publisher_id"))throw new ApiException(409,"不能将任务分配给发布者本人");
                eligible(application.get("applicant_id"),text(task.get("campus")));eligible(task.get("publisher_id"),text(task.get("campus")));frozen(task);
                if(one("SELECT order_id FROM user_orders WHERE reward_task_id=? FOR UPDATE",taskId)!=null)throw new ApiException(409,"任务已有订单，不能重新分配");
                orderId=numbers.nextOrder();
                db.update("UPDATE task_applications SET application_status=IF(application_id=?,'已通过','已驳回'),reviewer_id=?,audit_reason=IF(application_id=?,?,'任务已分配给其他申请者'),reviewed_at=UTC_TIMESTAMP(3) WHERE reward_task_id=? AND application_status='待审核'",id,actor.id(),id,reason,taskId);
                db.update("UPDATE RewardTasks SET task_status='进行中',taker_id=?,accepted_at=UTC_TIMESTAMP(3),version=version+1 WHERE reward_task_id=?",application.get("applicant_id"),taskId);
                db.update("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,reward_task_id,item_name,quantity,unit_price,total_amount,order_status) VALUES(?,'任务',?,?,?,?,1,?,?,'进行中')",orderId,task.get("publisher_id"),application.get("applicant_id"),taskId,task.get("task_name"),task.get("reward_amount"),task.get("reward_amount"));
            }
            log(actor,task,decision.equals("通过")?"接取审核":"接取驳回",decision.equals("通过")?"进行中":"待接取",reason);
            var result=new LinkedHashMap<String,Object>();result.put("application",applicationView(require("task_applications","application_id",id,false)));result.put("task",taskView(require("RewardTasks","reward_task_id",taskId,false),actor));result.put("order_id",orderId);return result;
        });
    }
    private Map<String,Object> taskOrder(Map<String,Object> task,String state){
        var order=one("SELECT * FROM user_orders WHERE reward_task_id=? FOR UPDATE",task.get("reward_task_id"));
        if(order==null||!"任务".equals(order.get("order_type"))||number(order,"user_id")!=number(task,"publisher_id")||number(order,"seller_id")!=number(task,"taker_id")||!state.equals(order.get("order_status"))||((BigDecimal)order.get("total_amount")).compareTo((BigDecimal)task.get("reward_amount"))!=0)throw new ApiException(409,"任务订单关联或状态不一致，需要核对");return order;
    }
    private void settle(Map<String,Object> task,Map<String,Object> proof){
        frozen(task);BigDecimal amount=(BigDecimal)task.get("reward_amount");long owner=number(task,"publisher_id"),taker=number(task,"taker_id");String type=text(task.get("reward_type"));
        // The global mutation guard and task lock serialize same-task settlement; the DB keys are the final backstop.
        db.update("INSERT INTO reward_accounts(user_id,reward_type) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",taker,type);
        var payee=one("SELECT * FROM reward_accounts WHERE user_id=? AND reward_type=? FOR UPDATE",taker,type);
        if(((BigDecimal)payee.get("available_amount")).add(amount).compareTo(new BigDecimal("999999999999.99"))>0)throw new ApiException(409,"收款账户余额超过当前账本上限");
        db.update("UPDATE reward_accounts SET frozen_amount=frozen_amount-? WHERE user_id=? AND reward_type=?",amount,owner,type);
        db.update("UPDATE reward_accounts SET available_amount=available_amount+? WHERE user_id=? AND reward_type=?",amount,taker,type);
        db.update("INSERT INTO task_settlement(reward_task_id,proof_id,payer_id,payee_id,reward_type,amount) VALUES(?,?,?,?,?,?)",task.get("reward_task_id"),proof.get("proof_id"),owner,taker,type,amount);
        db.update("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(?,?,?,?,'支付',0,?),(?,?,?,?,'收入',?,0)","PAY:"+task.get("reward_task_id"),task.get("reward_task_id"),owner,type,amount.negate(),"INCOME:"+task.get("reward_task_id"),task.get("reward_task_id"),taker,type,amount);
    }
    public Object reviewProof(Identity actor,long id,JsonNode body,String key){
        String decision=decision(body,Set.of("通过","补充凭证","未通过")),reason=AccountService.text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"task-proof:"+id,key,body,()->{
            var initial=require("task_proofs","proof_id",id,false);long taskId=number(initial,"reward_task_id");var task=require("RewardTasks","reward_task_id",taskId,true);var proof=require("task_proofs","proof_id",id,true);cash(task);
            if(!"待验收".equals(task.get("task_status"))||!"待审核".equals(proof.get("audit_status")))throw new ApiException(409,"任务或凭证不处于待验收状态");
            long latest=db.queryForObject("SELECT MAX(submission_no) FROM task_proofs WHERE reward_task_id=?",Long.class,taskId);
            if(number(proof,"submission_no")!=latest||number(proof,"submitter_id")!=number(task,"taker_id"))throw new ApiException(409,"只能验收当前接取者的最新凭证");
            var order=taskOrder(task,"待验收");unchangedSettlement(task);
            if(decision.equals("通过"))settle(task,proof);
            String next=switch(decision){case "通过"->"已完成";case "补充凭证"->"进行中";default->"未完成";};
            db.update("UPDATE task_proofs SET audit_status=?,reviewer_id=?,audit_reason=?,reviewed_at=UTC_TIMESTAMP(3) WHERE proof_id=?",decision.equals("通过")?"已通过":decision,actor.id(),reason,id);
            db.update("UPDATE RewardTasks SET task_status=?,completed_at=IF(?='已完成',UTC_TIMESTAMP(3),NULL),version=version+1 WHERE reward_task_id=?",next,next,taskId);
            if(decision.equals("通过"))db.update("UPDATE user_orders SET order_status='已完成',actual_amount=?,payment_method='奖励账户',completion_time=UTC_TIMESTAMP(3),payment_time=UTC_TIMESTAMP(3) WHERE order_id=?",task.get("reward_amount"),order.get("order_id"));
            else db.update("UPDATE user_orders SET order_status=? WHERE order_id=?",next,order.get("order_id"));
            log(actor,task,"完成验收",next,reason);return taskView(require("RewardTasks","reward_task_id",taskId,false),actor);
        });
    }
    public Object close(Identity actor,long id,JsonNode body,String key){
        fields(body,"reason");String reason=AccountService.text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"task-close:"+id,key,body,()->{
            var task=require("RewardTasks","reward_task_id",id,true);cash(task);String state=text(task.get("task_status"));
            if(!Set.of("待审核","待接取","未完成").contains(state))throw new ApiException(409,"当前任务状态不能直接关闭");
            var order=one("SELECT * FROM user_orders WHERE reward_task_id=? FOR UPDATE",id);
            if(state.equals("未完成"))taskOrder(task,"未完成");else if(order!=null)throw new ApiException(409,"未分配任务已有订单，需要核对");
            release(task);
            db.update("UPDATE RewardTasks SET task_status='已取消',cancel_reason=?,version=version+1 WHERE reward_task_id=?",reason,id);
            db.update("UPDATE task_applications SET application_status='已取消' WHERE reward_task_id=? AND application_status='待审核'",id);
            db.update("UPDATE user_orders SET order_status='已取消' WHERE reward_task_id=?",id);
            log(actor,task,"管理关闭","已取消",reason);return taskView(require("RewardTasks","reward_task_id",id,false),actor);
        });
    }
}
