package com.campuspi.admin;

import com.campuspi.account.*;
import com.campuspi.notification.NotificationPreferencePolicy;
import com.fasterxml.jackson.databind.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

/** Notification content is plain text. Draft edits never overwrite a published notification. */
@Service
public class AdminNotificationService {
    private final JdbcTemplate db;
    private final AccountService accounts;
    private final AdminPolicy policy;
    private final ObjectMapper json;
    private final NotificationPreferencePolicy preferences;
    public AdminNotificationService(JdbcTemplate db,AccountService accounts,AdminPolicy policy,ObjectMapper json,NotificationPreferencePolicy preferences){this.db=db;this.accounts=accounts;this.policy=policy;this.json=json;this.preferences=preferences;}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    public static String identifier(String id){if(id==null||id.isEmpty()||id.codePointCount(0,id.length())>50||id.codePoints().anyMatch(Character::isISOControl))throw new ApiException(400,"通知编号无效");return id;}
    private Map<String,Object> notification(String id,boolean lock){identifier(id);var row=one("SELECT * FROM system_notifications WHERE notification_id=?"+(lock?" FOR UPDATE":""),id);if(row==null)throw new ApiException(404,"通知不存在");return row;}
    private Object decode(Object value){try{return value==null?Map.of():json.readValue(value.toString(),Object.class);}catch(Exception ex){throw new ApiException(503,"通知目标配置无法读取");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("Invalid notification target",ex);}}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private Object view(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("notification_id","notification_title","notification_content","notification_type","target_scope","publish_status"))result.put(key,row.get(key));result.put("target_filter",decode(row.get("target_filter")));result.put("publish_time",time(row.get("publish_time")));return result;}
    private void page(int page,int size){if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");}
    private void audit(Identity actor,String id,String action,String reason,Object before,Object after){db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,before_data,after_data) VALUES(?,'通知',?,?,?,?,?)",actor.id(),id,action,reason,before==null?null:encode(before),after==null?null:encode(after));}
    public Object list(int page,int size,String state){
        page(page,size);if(!state.isEmpty()&&!Set.of("草稿","待发送","已发送","已撤回").contains(state))throw new ApiException(400,"通知状态无效");
        String where=state.isEmpty()?"":" WHERE publish_status=?";var args=new ArrayList<Object>();if(!state.isEmpty())args.add(state);
        long total=db.queryForObject("SELECT COUNT(*) FROM system_notifications"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        return Map.of("items",db.queryForList("SELECT * FROM system_notifications"+where+" ORDER BY notification_id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList(),"total",Long.toString(total),"page",page,"page_size",size);
    }
    public Object detail(String id){return view(notification(id,false));}
    public Object publish(Identity actor,String id,JsonNode body,String key){
        policy.require(actor,"NOTIFICATION_PUBLISH");identifier(id);fields(body,"channels");var input=body.get("channels");
        if(input==null||!input.isArray()||input.isEmpty()||input.size()>3)throw new ApiException(400,"需要1–3个通知渠道","channels");
        var channels=new TreeSet<String>();for(JsonNode channel:input){if(!channel.isTextual()||!Set.of("站内","短信","邮件").contains(channel.textValue()))throw new ApiException(400,"通知渠道无效","channels");channels.add(channel.textValue());}
        if(!channels.contains("站内"))throw new ApiException(400,"发布必须包含站内渠道","channels");
        return accounts.adminMutation(actor,"notification-publish:"+id,key,Map.of("channels",new ArrayList<>(channels)),()->{
            var row=notification(id,true);if(!"草稿".equals(row.get("publish_status")))throw new ApiException(409,"仅草稿可以发布");
            if(channels.size()>1){if(!"安全".equals(row.get("notification_type")))throw new ApiException(400,"短信邮件仅允许用于安全通知");throw new ApiException(501,"短信邮件渠道尚未接入");}
            // Revalidate legacy drafts before taking an immutable snapshot. No raw SQL/filter is accepted.
            var stored=new LinkedHashMap<String,Object>();for(String field:List.of("notification_title","notification_content","notification_type","target_scope"))stored.put(field,row.get(field));stored.put("target_filter",decode(row.get("target_filter")));
            var value=validated(json.valueToTree(stored));
            if(db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE notification_id=?",Long.class,id)!=0||one("SELECT job_id FROM notification_publish_jobs WHERE notification_id=?",id)!=null)throw new ApiException(409,"草稿存在异常历史接收记录，请核对后处理");
            String where="";var args=new ArrayList<Object>();args.add(id);
            @SuppressWarnings("unchecked") var filter=(Map<String,Object>)value.get("target_filter");
            if("校区".equals(value.get("target_scope"))){where=" WHERE u.campus=?";args.add(filter.get("campus"));}
            else if("指定用户".equals(value.get("target_scope"))){
                @SuppressWarnings("unchecked") var ids=(List<String>)filter.get("user_ids");where=" WHERE u.user_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";var targets=ids.stream().map(AccountService::id).toList();
                if(db.queryForObject("SELECT COUNT(*) FROM users u"+where,Long.class,targets.toArray())!=ids.size())throw new ApiException(404,"部分指定接收用户不存在");args.addAll(targets);
            }
            Object before=view(row);
            // One INSERT SELECT fixes the target set. Opt-out is excluded, including security messages.
            // No extra account-status eligibility filter is invented for the contract's 全体/校区 scopes.
            db.update("INSERT INTO notification_receipts(notification_id,user_id) SELECT ?,u.user_id FROM users u LEFT JOIN notification_preferences p ON p.user_id=u.user_id"+where+(where.isEmpty()?" WHERE ":" AND ")+"COALESCE(p.in_app_enabled,TRUE)=TRUE",args.toArray());
            var decisions=new LinkedHashMap<Long,NotificationPreferencePolicy.Decision>();
            for(var target:db.queryForList("SELECT user_id FROM notification_receipts WHERE notification_id=? ORDER BY user_id",id)){
                long user=((Number)target.get("user_id")).longValue();var decision=preferences.lock(user,Instant.now());
                if(!decision.enabled())db.update("DELETE FROM notification_receipts WHERE notification_id=? AND user_id=?",id,user);
                else decisions.put(user,decision);
            }
            long recipients=db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE notification_id=?",Long.class,id);
            if(recipients>Integer.MAX_VALUE)throw new ApiException(503,"接收者数量超出当前发布契约上限");
            String job="job_"+UUID.randomUUID().toString().replace("-","");
            db.update("INSERT INTO notification_publish_jobs(job_id,notification_id,recipient_count) VALUES(?,?,?)",job,id,recipients);
            db.update("INSERT INTO notification_delivery(notification_id,user_id,channel,attempt_no) SELECT notification_id,user_id,'站内',1 FROM notification_receipts WHERE notification_id=?",id);
            for(var target:db.queryForList("SELECT delivery_id,user_id FROM notification_delivery WHERE notification_id=? ORDER BY user_id",id)){
                var decision=decisions.get(((Number)target.get("user_id")).longValue());
                db.update("INSERT INTO notification_outbox(delivery_id,notification_id,available_at,preference_version) VALUES(?,?,?,?)",target.get("delivery_id"),id,Timestamp.from(decision.availableAt()),decision.version());
            }
            db.update("UPDATE system_notifications SET publish_status='待发送',publisher_id=?,publish_time=NULL WHERE notification_id=?",actor.id(),id);
            Object result=Map.of("notification_id",id,"publish_status","待发送","job_id",job,"recipient_count",(int)recipients);audit(actor,id,"审核发布","核对内容及目标后发布站内通知",before,result);return result;
        });
    }
    private Map<String,Object> validated(JsonNode body){
        fields(body,"notification_title","notification_content","notification_type","target_scope","target_filter");
        var result=new LinkedHashMap<String,Object>();result.put("notification_title",text(body,"notification_title",1,200,true));result.put("notification_content",text(body,"notification_content",1,10000,false));
        String type=text(body,"notification_type",1,10,false),scope=text(body,"target_scope",1,10,false);
        if(!Set.of("系统","互动","交易","审核","安全").contains(type))throw new ApiException(400,"通知类型无效","notification_type");
        JsonNode filter=body.get("target_filter");var target=new LinkedHashMap<String,Object>();
        switch(scope){
            case "全体" -> fields(filter);
            case "校区" -> {fields(filter,"campus");target.put("campus",text(filter,"campus",1,50,true));}
            case "指定用户" -> {
                fields(filter,"user_ids");var ids=filter.get("user_ids");if(ids==null||!ids.isArray()||ids.isEmpty()||ids.size()>1000)throw new ApiException(400,"指定用户须为1–1000个编号","target_filter.user_ids");
                var unique=new LinkedHashSet<String>();for(JsonNode node:ids){if(!node.isTextual())throw new ApiException(400,"用户编号须为字符串");String value=node.textValue();id(value);if(!unique.add(value))throw new ApiException(400,"指定用户编号不能重复");}target.put("user_ids",new ArrayList<>(unique));
            }
            default -> throw new ApiException(400,"通知目标范围无效","target_scope");
        }
        result.put("notification_type",type);result.put("target_scope",scope);result.put("target_filter",target);return result;
    }
    public Object save(Identity actor,String target,JsonNode body,String key){
        if(target!=null)identifier(target);var value=validated(body);
        Supplier<Object> action=()->{
            Object before=null;String id=target;
            if(id!=null){var row=notification(id,true);if(!"草稿".equals(row.get("publish_status")))throw new ApiException(409,"仅草稿可以编辑；已发布内容需撤回后新建");before=view(row);}
            else id="NOTICE_"+LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS",Locale.ROOT))+"_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
            if(target==null)db.update("INSERT INTO system_notifications(notification_id,notification_title,notification_content,notification_type,publisher_id,target_scope,target_filter,publish_status,scheduled_at) VALUES(?,?,?,?,?,?,?,'草稿',NULL)",id,value.get("notification_title"),value.get("notification_content"),value.get("notification_type"),actor.id(),value.get("target_scope"),encode(value.get("target_filter")));
            else db.update("UPDATE system_notifications SET notification_title=?,notification_content=?,notification_type=?,target_scope=?,target_filter=? WHERE notification_id=?",value.get("notification_title"),value.get("notification_content"),value.get("notification_type"),value.get("target_scope"),encode(value.get("target_filter")),id);
            Object after=view(notification(id,false));audit(actor,id,target==null?"创建草稿":"编辑草稿",target==null?"创建通知草稿":"替换通知草稿",before,after);return after;
        };
        return target==null?accounts.adminMutation(actor,"notification-create",key,value,action):accounts.adminTransaction(actor,action);
    }
    public Object deliveries(Identity actor,String id,int page,int size,String state,String channel){
        policy.require(actor,"NOTIFICATION_PUBLISH");identifier(id);page(page,size);
        if(!state.isEmpty()&&!Set.of("待发送","成功","失败").contains(state))throw new ApiException(400,"投递状态无效");if(!channel.isEmpty()&&!Set.of("站内","短信","邮件").contains(channel))throw new ApiException(400,"渠道无效");
        return accounts.adminTransaction(actor,()->{
            notification(id,false);String where=" WHERE notification_id=?";var args=new ArrayList<Object>();args.add(id);
            if(!state.isEmpty()){where+=" AND delivery_status=?";args.add(state);}if(!channel.isEmpty()){where+=" AND channel=?";args.add(channel);}
            long total=db.queryForObject("SELECT COUNT(*) FROM notification_delivery"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
            var rows=db.queryForList("SELECT delivery_id,notification_id,user_id,channel,delivery_status,attempt_no,failure_reason,sent_at FROM notification_delivery"+where+" ORDER BY delivery_id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::deliveryView).toList();
            audit(actor,id,"读取投递记录","按通知查看投递尝试",null,null);return Map.of("items",rows,"total",Long.toString(total),"page",page,"page_size",size);
        });
    }
    private Object deliveryView(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("delivery_id","notification_id","user_id"))result.put(key,row.get(key).toString());for(String key:List.of("channel","delivery_status","attempt_no","failure_reason"))result.put(key,row.get(key));result.put("sent_at",time(row.get("sent_at")));return result;}
    public Object withdraw(Identity actor,String id,JsonNode body,String key){
        policy.require(actor,"NOTIFICATION_PUBLISH");identifier(id);fields(body,"reason");String reason=text(body,"reason",1,500,true);
        return accounts.adminMutation(actor,"notification-withdraw:"+id,key,body,()->{
            var row=notification(id,true);if(!Set.of("待发送","已发送").contains(row.get("publish_status")))throw new ApiException(409,"仅待发送或已发送通知可撤回");Object before=view(row);
            db.update("UPDATE system_notifications SET publish_status='已撤回' WHERE notification_id=?",id);
            db.update("UPDATE notification_publish_jobs SET job_status='已取消',completed_at=UTC_TIMESTAMP(3) WHERE notification_id=? AND job_status='待执行'",id);
            db.update("UPDATE notification_delivery SET delivery_status='失败',failure_reason='通知已撤回，未执行的投递已取消' WHERE notification_id=? AND delivery_status='待发送'",id);
            db.update("UPDATE notification_outbox SET work_status='已取消',completed_at=UTC_TIMESTAMP(3) WHERE notification_id=? AND work_status='待执行'",id);
            Object after=view(notification(id,false));audit(actor,id,"撤回",reason,before,after);return after;
        });
    }
    public Object retry(Identity actor,long deliveryId,JsonNode body,String key){
        policy.require(actor,"NOTIFICATION_PUBLISH");fields(body,"reason");String reason=text(body,"reason",1,500,true);
        var selected=one("SELECT notification_id FROM notification_delivery WHERE delivery_id=?",deliveryId);if(selected==null)throw new ApiException(404,"投递尝试不存在");String id=selected.get("notification_id").toString();
        return accounts.adminMutation(actor,"notification-retry:"+deliveryId,key,body,()->{
            var row=notification(id,true);if(!Set.of("待发送","已发送").contains(row.get("publish_status")))throw new ApiException(409,"该通知不能重新投递");
            var attempt=one("SELECT * FROM notification_delivery WHERE delivery_id=? FOR UPDATE",deliveryId);if(attempt==null||!"失败".equals(attempt.get("delivery_status")))throw new ApiException(409,"只能重试失败的投递");
            if(!"站内".equals(attempt.get("channel")))throw new ApiException(501,"短信邮件渠道尚未接入");
            var decision=preferences.lock(((Number)attempt.get("user_id")).longValue(),Instant.now());if(!decision.enabled())throw new ApiException(409,"接收者已关闭站内通知，不能重试");
            var receipt=one("SELECT is_deleted FROM notification_receipts WHERE notification_id=? AND user_id=? FOR UPDATE",id,attempt.get("user_id"));Object deleted=receipt.get("is_deleted");if(Boolean.TRUE.equals(deleted)||(deleted instanceof Number n&&n.intValue()!=0))throw new ApiException(409,"接收记录已删除，不能重新投递");
            var latest=one("SELECT delivery_id,delivery_status,attempt_no FROM notification_delivery WHERE notification_id=? AND user_id=? AND channel=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",id,attempt.get("user_id"),attempt.get("channel"));
            if(((Number)latest.get("delivery_id")).longValue()!=deliveryId)throw new ApiException(409,"已有更新的投递尝试，请查看最新记录");
            int number=((Number)latest.get("attempt_no")).intValue();if(number>=5)throw new ApiException(409,"该用户渠道已达到5次尝试上限");
            if(one("SELECT job_id FROM notification_publish_jobs WHERE notification_id=?",id)==null)throw new ApiException(503,"该历史通知缺少持久任务，需核对后迁移");
            db.update("INSERT INTO notification_delivery(notification_id,user_id,channel,attempt_no) VALUES(?,?,?,?)",id,attempt.get("user_id"),attempt.get("channel"),number+1);
            long next=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            db.update("INSERT INTO notification_outbox(delivery_id,notification_id,available_at,preference_version) VALUES(?,?,?,?)",next,id,Timestamp.from(decision.availableAt()),decision.version());
            Object result=deliveryView(one("SELECT * FROM notification_delivery WHERE delivery_id=?",next));audit(actor,id,"重试投递",reason,deliveryView(attempt),result);return result;
        });
    }
}
