package com.campuspi.notification;

import com.campuspi.account.*;
import com.campuspi.admin.AdminNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class NotificationService {
    private final JdbcTemplate db;
    private final AccountService accounts;
    private static final String PROJECTION="n.notification_id,n.notification_title,n.notification_content,n.notification_type,n.publish_time,r.read_at,r.clicked_at";
    private static final String DELIVERED=" AND EXISTS(SELECT 1 FROM notification_delivery d WHERE d.notification_id=r.notification_id AND d.user_id=r.user_id AND d.channel='站内' AND d.delivery_status='成功')";
    public NotificationService(JdbcTemplate db,AccountService accounts){this.db=db;this.accounts=accounts;}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private Object view(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String field:List.of("notification_id","notification_title","notification_content","notification_type"))result.put(field,row.get(field));for(String field:List.of("publish_time","read_at","clicked_at"))result.put(field,time(row.get(field)));return result;}
    private Map<String,Object> owned(Identity actor,String id){
        var row=one("SELECT "+PROJECTION+" FROM notification_receipts r JOIN system_notifications n ON n.notification_id=r.notification_id WHERE r.notification_id=? AND r.user_id=? AND r.is_deleted=FALSE AND n.publish_status='已发送'"+DELIVERED,id,actor.id());
        if(row==null)throw new ApiException(404,"通知不存在");return row;
    }
    public Object list(Identity actor,int page,int size,boolean unread,String type){
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        if(!type.isEmpty()&&!Set.of("系统","互动","交易","审核","安全").contains(type))throw new ApiException(400,"通知类型无效");
        return accounts.sessionTransaction(actor,()->{
            String where=" FROM notification_receipts r JOIN system_notifications n ON n.notification_id=r.notification_id WHERE r.user_id=? AND r.is_deleted=FALSE AND n.publish_status='已发送'"+DELIVERED;
            var args=new ArrayList<Object>();args.add(actor.id());if(unread)where+=" AND r.read_at IS NULL";if(!type.isEmpty()){where+=" AND n.notification_type=?";args.add(type);}
            long total=db.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
            return Map.of("items",db.queryForList("SELECT "+PROJECTION+where+" ORDER BY r.received_at DESC,n.notification_id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList(),"total",Long.toString(total),"page",page,"page_size",size);
        });
    }
    public Object detail(Identity actor,String id){AdminNotificationService.identifier(id);return accounts.sessionTransaction(actor,()->view(owned(actor,id)));}
    public Object change(Identity actor,String id,String action){
        AdminNotificationService.identifier(id);return accounts.sessionTransaction(actor,()->{
            // Serialize against publication/withdrawal and worker activation before locking this receipt.
            var notification=one("SELECT publish_status FROM system_notifications WHERE notification_id=? FOR UPDATE",id);
            if(notification==null)throw new ApiException(404,"通知不存在");
            var receipt=one("SELECT is_deleted FROM notification_receipts WHERE notification_id=? AND user_id=? FOR UPDATE",id,actor.id());
            if(receipt==null)throw new ApiException(404,"通知不存在");
            if("delete".equals(action)){db.update("UPDATE notification_receipts SET is_deleted=TRUE WHERE notification_id=? AND user_id=?",id,actor.id());return Map.of();}
            owned(actor,id); // Published, non-deleted and owned; never reveal other recipients or draft content.
            if("read".equals(action))db.update("UPDATE notification_receipts SET read_at=COALESCE(read_at,GREATEST(UTC_TIMESTAMP(3),received_at)) WHERE notification_id=? AND user_id=?",id,actor.id());
            else if("click".equals(action))db.update("UPDATE notification_receipts SET read_at=COALESCE(read_at,GREATEST(UTC_TIMESTAMP(3),received_at)),clicked_at=COALESCE(clicked_at,GREATEST(UTC_TIMESTAMP(3),read_at)) WHERE notification_id=? AND user_id=?",id,actor.id());
            else throw new IllegalArgumentException("Unknown receipt operation");
            return view(owned(actor,id));
        });
    }
    private boolean bool(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private Object preferencesView(Map<String,Object> row){
        var value=new LinkedHashMap<String,Object>();for(String field:List.of("in_app_enabled","email_enabled","sms_enabled"))value.put(field,row==null?field.equals("in_app_enabled"):bool(row.get(field)));
        for(String field:List.of("dnd_start","dnd_end")){Object raw=row==null?null:row.get(field);value.put(field,raw==null?null:(raw instanceof java.sql.Time t?t.toLocalTime():LocalTime.parse(raw.toString())).format(DateTimeFormatter.ofPattern("HH:mm:ss",Locale.ROOT)));}return value;
    }
    public Object preferences(Identity actor){return accounts.sessionTransaction(actor,()->preferencesView(one("SELECT in_app_enabled,email_enabled,sms_enabled,dnd_start,dnd_end FROM notification_preferences WHERE user_id=?",actor.id())));}
    private LocalTime dnd(JsonNode body,String field){var value=body.get(field);if(value==null)throw new ApiException(400,"偏好需要完整替换",field);if(value.isNull())return null;if(!value.isTextual()||!value.textValue().matches("([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"))throw new ApiException(400,"免打扰时间须为HH:mm:ss",field);return LocalTime.parse(value.textValue());}
    public Object savePreferences(Identity actor,JsonNode body){
        fields(body,"in_app_enabled","email_enabled","sms_enabled","dnd_start","dnd_end");
        for(String field:List.of("in_app_enabled","email_enabled","sms_enabled"))if(!body.has(field)||!body.get(field).isBoolean())throw new ApiException(400,"偏好开关须为布尔值",field);
        LocalTime start=dnd(body,"dnd_start"),end=dnd(body,"dnd_end");if((start==null)!=(end==null)||(start!=null&&start.equals(end)))throw new ApiException(400,"免打扰起止需同空或同时填写不同时间");
        if(body.get("email_enabled").booleanValue()||body.get("sms_enabled").booleanValue())throw new ApiException(501,"短信邮件提醒渠道尚未接入");
        return accounts.sessionTransaction(actor,()->{db.update("INSERT INTO notification_preferences(user_id,in_app_enabled,email_enabled,sms_enabled,dnd_start,dnd_end,config_version) VALUES(?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE in_app_enabled=VALUES(in_app_enabled),email_enabled=VALUES(email_enabled),sms_enabled=VALUES(sms_enabled),dnd_start=VALUES(dnd_start),dnd_end=VALUES(dnd_end),config_version=config_version+1",actor.id(),body.get("in_app_enabled").booleanValue(),false,false,start,end);return preferencesView(one("SELECT * FROM notification_preferences WHERE user_id=?",actor.id()));});
    }
}
