package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import static com.campuspi.account.AccountService.*;

@Service
public class VerificationService {
    private final JdbcTemplate db;
    private final com.campuspi.notification.ReviewNotifications notifications;
    private final AccountService accounts;
    private final AdminPolicy policy;
    private final BusinessFileService files;
    private final ObjectMapper json;
    private final Set<String> campuses=new HashSet<>();
    public VerificationService(JdbcTemplate db,AccountService accounts,AdminPolicy policy,BusinessFileService files,ObjectMapper json,com.campuspi.notification.ReviewNotifications notifications,
            @Value("${campus.verification.allowed-campuses-json:}") String campusConfig) {
        this.notifications=notifications;this.db=db;this.accounts=accounts;this.policy=policy;this.files=files;this.json=json;
        if(!campusConfig.isBlank())try {
            var values=json.readTree(campusConfig);if(!values.isArray())throw new IllegalArgumentException();
            for(var value:values){if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>50)throw new IllegalArgumentException();campuses.add(value.asText());}
        }catch(Exception ex){throw new IllegalArgumentException("Invalid controlled campus allowlist");}
    }
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> user(long id){var row=one("SELECT * FROM users WHERE user_id=? FOR UPDATE",id);if(row==null)throw new ApiException(404,"用户不存在");return row;}
    private Map<String,Object> record(long id,boolean lock){var row=one("SELECT * FROM user_verification WHERE verification_id=?"+(lock?" FOR UPDATE":""),id);if(row==null)throw new ApiException(404,"认证申请不存在");return row;}
    private String time(Object value){return value==null?null:(value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC)).toString();}
    private Object decode(Object value){try{return json.readValue(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString(),Object.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Object view(Map<String,Object> row,boolean summary) {
        var result=new LinkedHashMap<String,Object>();
        result.put("verification_id",row.get("verification_id").toString());result.put("user_id",row.get("user_id").toString());
        for(String key:List.of("verification_type","campus","audit_status"))result.put(key,row.get(key));
        result.put("submitted_at",time(row.get("submitted_at")));
        if(!summary){
            for(String key:List.of("student_no","real_name","audit_reason"))result.put(key,row.get(key));
            result.put("evidence_refs",decode(row.get("evidence_urls")));result.put("reviewed_at",time(row.get("reviewed_at")));
        }
        return result;
    }
    private void allowedCampus(String campus) {
        if(campuses.isEmpty())throw new ApiException(503,"正式校区名单尚未配置");
        if(!campuses.contains(campus))throw new ApiException(400,"校区不在服务端允许名单中","campus");
    }
    public Object list(Identity viewer,boolean admin,int page,int size,String state,String type) {
        if(admin)policy.require(viewer,"VERIFICATION_REVIEW");
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw new ApiException(400,"分页参数无效");
        if(!state.isEmpty()&&!Set.of("待审核","已通过","已驳回").contains(state))throw new ApiException(400,"审核状态无效");
        if(!type.isEmpty()&&!Set.of("实名","校园").contains(type))throw new ApiException(400,"认证类型无效");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!admin){where+=" AND user_id=?";args.add(viewer.id());}
        if(!state.isEmpty()){where+=" AND audit_status=?";args.add(state);}
        if(!type.isEmpty()){where+=" AND verification_type=?";args.add(type);}
        long count=db.queryForObject("SELECT COUNT(*) FROM user_verification"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        String columns=admin?"verification_id,user_id,verification_type,campus,audit_status,submitted_at":"*";
        var rows=db.queryForList("SELECT "+columns+" FROM user_verification"+where+" ORDER BY submitted_at "+(admin?"ASC":"DESC")+",verification_id "+(admin?"ASC":"DESC")+" LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows.stream().map(row->view(row,admin)).toList(),"total",Long.toString(count),"page",page,"page_size",size);
    }
    public Object submit(Identity owner,JsonNode body,String key) {
        fields(body,"verification_type","real_name","campus","student_no","evidence_refs");
        String type=text(body,"verification_type",1,10,false),name=text(body,"real_name",1,50,true);
        if(!Set.of("实名","校园").contains(type))throw new ApiException(400,"认证类型无效","verification_type");
        String campus=type.equals("校园")?text(body,"campus",1,50,true):null;
        String student=type.equals("校园")?text(body,"student_no",1,30,true):null;
        if(campus!=null)allowedCampus(campus);
        var refs=files.parseRefs(body.get("evidence_refs"));
        return accounts.memberMutation(owner,"verification-submit",key,body,()->{
            var user=user(owner.id());
            if(db.queryForObject("SELECT COUNT(*) FROM user_verification WHERE user_id=? AND verification_type=? AND audit_status='待审核'",Long.class,owner.id(),type)>0)throw new ApiException(409,"同类型认证申请仍在审核中");
            if(type.equals("实名")&&"已通过".equals(user.get("identity_verification")))throw new ApiException(409,"实名已通过，无需重复申请");
            if(type.equals("校园")) {
                if(!"已通过".equals(user.get("identity_verification")))throw new ApiException(409,"校园认证需要实名先通过");
                if("已通过".equals(user.get("campus_verification"))&&campus.equals(user.get("campus"))&&student.equals(user.get("student_no")))throw new ApiException(409,"校区和学号未变化，无需重复申请");
                db.update("DELETE FROM approved_campus_registry WHERE user_id=?",owner.id());
            }
            KeyHolder generated=new GeneratedKeyHolder();
            db.update(conn->{var s=conn.prepareStatement("INSERT INTO user_verification(user_id,verification_type,campus,student_no,real_name,evidence_urls) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                s.setLong(1,owner.id());s.setString(2,type);s.setString(3,campus);s.setString(4,student);s.setString(5,name);s.setString(6,encode(refs));return s;},generated);
            long id=generated.getKey().longValue();
            files.bindRefs(owner.id(),"VERIFICATION","VERIFICATION",Long.toString(id),refs);
            db.update("UPDATE users SET "+(type.equals("实名")?"identity_verification":"campus_verification")+"='待审核' WHERE user_id=?",owner.id());
            return view(record(id,false),false);
        });
    }
    private void audit(Identity actor,long id,String action,String reason){db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,'认证',?,?,?)",actor.id(),Long.toString(id),action,reason);}
    public Object detail(Identity actor,long id) {
        policy.require(actor,"VERIFICATION_REVIEW");
        return accounts.adminTransaction(actor,()->{var row=record(id,false);audit(actor,id,"读取敏感资料","认证审核详情访问");return view(row,false);});
    }
    public Object review(Identity actor,long id,JsonNode body,String key) {
        policy.require(actor,"VERIFICATION_REVIEW");fields(body,"decision","reason");
        String decision=text(body,"decision",1,10,false),reason=text(body,"reason",1,500,true);
        if(!Set.of("通过","驳回").contains(decision))throw new ApiException(400,"审核决定无效");
        return accounts.adminMutation(actor,"verification-review:"+id,key,body,()->{
            var initial=record(id,false);long owner=((Number)initial.get("user_id")).longValue();
            var user=user(owner);var row=record(id,true);
            if(owner==actor.id())throw new ApiException(403,"不能审核本人认证申请");
            if(!"待审核".equals(row.get("audit_status")))throw new ApiException(409,"只能处理待审核申请");
            boolean campus="校园".equals(row.get("verification_type")),pass="通过".equals(decision);
            String next=pass?"已通过":"已驳回";
            if(campus&&pass) {
                allowedCampus((String)row.get("campus"));
                if(!"已通过".equals(user.get("identity_verification")))throw new ApiException(409,"实名资格已失效");
                try {db.update("INSERT INTO approved_campus_registry(campus,student_no,user_id) VALUES(?,?,?)",row.get("campus"),row.get("student_no"),owner);}
                catch(DuplicateKeyException ex){throw new ApiException(409,"该校区学号已由其他通过认证的账号登记");}
                db.update("UPDATE users SET campus=?,student_no=?,campus_verification='已通过' WHERE user_id=?",row.get("campus"),row.get("student_no"),owner);
            }else if(campus)db.update("UPDATE users SET campus_verification=? WHERE user_id=?",next,owner);
            else {
                db.update("UPDATE users SET identity_verification=?,real_name=? WHERE user_id=?",next,pass?row.get("real_name"):user.get("real_name"),owner);
            }
            db.update("UPDATE user_verification SET audit_status=?,reviewer_id=?,audit_reason=?,reviewed_at=UTC_TIMESTAMP(3) WHERE verification_id=?",next,actor.id(),reason,id);
            notifications.send(actor.id(),owner,campus?"校园认证":"实名认证",Long.toString(id),next,reason);
            audit(actor,id,"审核",reason);return view(record(id,false),false);
        });
    }
}
