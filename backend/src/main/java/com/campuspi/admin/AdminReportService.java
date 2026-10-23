package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class AdminReportService {
    private final JdbcTemplate db;
    private final com.campuspi.notification.ReviewNotifications notifications;
    private final AccountService accounts;
    private final AdminPolicy policy;
    public AdminReportService(JdbcTemplate db,AccountService accounts,AdminPolicy policy,com.campuspi.notification.ReviewNotifications notifications){this.notifications=notifications;this.db=db;this.accounts=accounts;this.policy=policy;}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private Map<String,Object> report(long id,boolean lock){var row=one("SELECT * FROM t_msg_report WHERE report_id=?"+(lock?" FOR UPDATE":""),id);if(row==null)throw new ApiException(404,"举报工单不存在");return row;}
    private Object reportView(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("report_id","msg_id","reporter_id"))result.put(key,row.get(key).toString());for(String key:List.of("reason","audit_status","audit_reason"))result.put(key,row.get(key));for(String key:List.of("report_time","reviewed_at"))result.put(key,time(row.get(key)));return result;
    }
    private Map<String,Object> evidenceMessage(Map<String,Object> report){
        var row=one("SELECT m.msg_id,m.session_id,m.sender_id,m.receiver_id,m.content_type,m.content,m.send_time,m.read_at FROM t_private_msg m JOIN t_chat_session s ON s.session_id=m.session_id AND s.sender_id=LEAST(m.sender_id,m.receiver_id) AND s.receiver_id=GREATEST(m.sender_id,m.receiver_id) WHERE m.msg_id=? AND ? IN (m.sender_id,m.receiver_id)",report.get("msg_id"),report.get("reporter_id"));
        if(row==null)throw new ApiException(404,"举报关联关系无效，不能读取消息");return row;
    }
    private Object messageView(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("msg_id","session_id","sender_id","receiver_id"))result.put(key,row.get(key).toString());for(String key:List.of("content_type","content"))result.put(key,row.get(key));for(String key:List.of("send_time","read_at"))result.put(key,time(row.get(key)));return result;}
    private void audit(Identity actor,long id,String action,String reason){db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,'举报',?,?,?)",actor.id(),Long.toString(id),action,reason);}
    public Object list(Identity actor,int page,int size,String state){
        policy.require(actor,"REPORTS");if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");if(!state.isEmpty()&&!Set.of("待审核","成立","不成立").contains(state))throw new ApiException(400,"举报状态无效");
        String where=state.isEmpty()?"":" WHERE audit_status=?";var args=new ArrayList<Object>();if(!state.isEmpty())args.add(state);
        long count=db.queryForObject("SELECT COUNT(*) FROM t_msg_report"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        return Map.of("items",db.queryForList("SELECT * FROM t_msg_report"+where+" ORDER BY report_time ASC,report_id ASC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::reportView).toList(),"total",Long.toString(count),"page",page,"page_size",size);
    }
    public Object detail(Identity actor,long id){
        policy.require(actor,"REPORTS");return accounts.adminTransaction(actor,()->{var report=report(id,false);var message=evidenceMessage(report);audit(actor,id,"读取案件证据","举报处理详情访问");return Map.of("report",reportView(report),"message",messageView(message));});
    }
    public Object review(Identity actor,long id,JsonNode body,String key){
        policy.require(actor,"REPORTS");fields(body,"decision","reason");String decision=text(body,"decision",1,10,false),reason=text(body,"reason",1,500,true);if(!Set.of("成立","不成立").contains(decision))throw new ApiException(400,"举报处理决定无效");
        return accounts.adminMutation(actor,"message-report:"+id,key,body,()->{
            var row=report(id,true);evidenceMessage(row);if(!"待审核".equals(row.get("audit_status")))throw new ApiException(409,"只能处理待审核举报");
            db.update("UPDATE t_msg_report SET audit_status=?,reviewer_id=?,audit_reason=?,reviewed_at=UTC_TIMESTAMP(3) WHERE report_id=?",decision,actor.id(),reason,id);notifications.send(actor.id(),((Number)row.get("reporter_id")).longValue(),"私信举报",Long.toString(id),decision,reason);audit(actor,id,"处理",reason);
            return reportView(report(id,false));
        });
    }
}
