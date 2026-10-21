package com.campuspi.account;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String secret;
    private final SecureRandom random = new SecureRandom();
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16,32,1,65536,3);
    private final String dummyHash;
    public record Identity(long id, String tokenHash, long version, boolean admin) {}
    private record LoginAttempt(int status, Object data) {}
    public AccountService(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json,
            @Value("${campus.auth.idempotency-secret:}") String secret) {
        this.db=db; this.tx=tx; this.json=json; this.secret=secret;
        this.dummyHash=encoder.encode(UUID.randomUUID().toString());
    }
    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private Timestamp stamp(Instant value) { return Timestamp.from(value); }
    private Instant instant(Object value) {
        return value instanceof Timestamp t ? t.toInstant() : ((LocalDateTime)value).toInstant(ZoneOffset.UTC);
    }
    private Map<String,Object> one(String sql, Object... args) {
        var list=db.queryForList(sql,args); return list.isEmpty()? null : list.get(0);
    }
    private Map<String,Object> user(long id, boolean lock) {
        Map<String,Object> value=one("SELECT * FROM users WHERE user_id=?"+(lock?" FOR UPDATE":""),id);
        if(value==null) throw new ApiException(404,"用户不存在"); return value;
    }
    private long number(Map<String,Object> value,String key) { return ((Number)value.get(key)).longValue(); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private Object decode(Object value) {
        if(value==null) return null;
        try { return json.readValue(value instanceof byte[] b? new String(b,StandardCharsets.UTF_8):value.toString(),Object.class); }
        catch(Exception ex) { throw new IllegalStateException("Stored JSON is invalid",ex); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private String digest(String value) {
        if(secret.length()<32) throw new ApiException(503,"账号服务未完成本地配置");
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static long id(String value) {
        try { if(!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();return Long.parseLong(value); }
        catch(Exception ex) { throw new ApiException(400,"用户编号格式错误","user_id"); }
    }
    public static void fields(JsonNode body,String... allowed) {
        if(body==null || !body.isObject()) throw new ApiException(400,"请求体必须是 JSON 对象");
        Set<String> keys=Set.of(allowed);body.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new ApiException(400,"不允许修改该字段",k);});
    }
    public static String text(JsonNode body,String key,int min,int max,boolean trim) {
        var node=body.get(key);
        if(node==null || !node.isTextual()) throw new ApiException(400,"请填写"+key,key);
        String value=trim?node.textValue().trim():node.textValue();
        int size=value.codePointCount(0,value.length());
        if(size<min || size>max) throw new ApiException(400,key+"长度应为 "+min+"–"+max+" 个字符",key);
        return value;
    }
    private Object idempotent(String scope,String key,String signature,Supplier<Object> action) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new ApiException(400,"缺少有效的 Idempotency-Key");
        // Concurrent INSERT IGNORE readers can deadlock while upgrading to FOR UPDATE.
        // Retry only after TransactionTemplate has rolled back the whole database action.
        for(int attempt=0;;attempt++) {
            try { return idempotentTransaction(scope,key,signature,action); }
            catch(PessimisticLockingFailureException ex) { if(attempt>=2) throw ex; }
        }
    }
    private Object idempotentTransaction(String scope,String key,String signature,Supplier<Object> action) {
        return tx.execute(status->{
            int inserted=db.update("INSERT IGNORE INTO auth_idempotency(scope_key,request_key,request_digest,expires_at) VALUES(?,?,?,?)",scope,key,signature,stamp(now().plus(24,ChronoUnit.HOURS)));
            Map<String,Object> saved=one("SELECT * FROM auth_idempotency WHERE scope_key=? AND request_key=? FOR UPDATE",scope,key);
            if(inserted==0 && !instant(saved.get("expires_at")).isAfter(now())) {
                db.update("UPDATE auth_idempotency SET request_digest=?,response_json=NULL,expires_at=? WHERE scope_key=? AND request_key=?",signature,stamp(now().plus(24,ChronoUnit.HOURS)),scope,key);
                inserted=1;
            }
            if(inserted==0) {
                if(!signature.equals(saved.get("request_digest"))) throw new ApiException(409,"同一请求标识不能用于不同内容",null,"IDEMPOTENCY_CONFLICT");
                if(saved.get("response_json")==null) throw new ApiException(409,"请求处理中，请稍后重试",null,"REQUEST_IN_PROGRESS");
                return decode(saved.get("response_json"));
            }
            Object result=action.get();
            db.update("UPDATE auth_idempotency SET response_json=? WHERE scope_key=? AND request_key=?",encode(result),scope,key);
            return result;
        });
    }
    public Object register(JsonNode body,String key) {
        fields(body,"username","password","phone","email");
        String name=text(body,"username",3,25,false), password=text(body,"password",12,64,false);
        if(!name.matches("[A-Za-z][A-Za-z0-9_]{2,24}")) throw new ApiException(400,"用户名须以字母开头，使用字母、数字或下划线","username");
        String phone=body.has("phone")?text(body,"phone",8,20,true):null;
        String email=body.has("email")?text(body,"email",3,100,true).toLowerCase(Locale.ROOT):null;
        if(phone==null && email==null) throw new ApiException(400,"手机号和邮箱至少填写一项","email");
        if(phone!=null && !phone.matches("\\+[1-9][0-9]{7,14}")) throw new ApiException(400,"手机号需使用 E.164 国际格式","phone");
        if(email!=null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new ApiException(400,"邮箱格式不正确","email");
        var canonical=Arrays.asList(name.toLowerCase(Locale.ROOT),password,phone,email);
        String signature=digest(encode(canonical));
        String passwordHash=encoder.encode(password);
        try { return idempotent("register",key,signature,()->{
            KeyHolder generated=new GeneratedKeyHolder();
            db.update(connection->{
                var statement=connection.prepareStatement("INSERT INTO users(username,password_hash,phone,email,privacy_settings,registration_time) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                statement.setString(1,name);statement.setString(2,passwordHash);statement.setString(3,phone);statement.setString(4,email);
                statement.setString(5,"{\"show_campus\":false,\"show_personal_bio\":false}");statement.setTimestamp(6,stamp(now()));return statement;
            },generated);
            long userId=Objects.requireNonNull(generated.getKey()).longValue();
            db.update("INSERT INTO reward_accounts(user_id,reward_type) VALUES(?,'积分'),(?,'现金')",userId,userId);
            return view(user(userId,false),false);
        }); } catch(DuplicateKeyException ex) { throw new ApiException(409,"用户名或联系方式已被使用",null,"UNIQUE_CONFLICT"); }
    }
    public Object login(JsonNode body,String source) {
        fields(body,"account","password");
        String account=text(body,"account",1,100,true), password=text(body,"password",12,64,false);
        String normalized=account.toLowerCase(Locale.ROOT);
        String throttle=digest(normalized+"\n"+source);
        var earlyLimit=one("SELECT locked_until FROM auth_login_throttle WHERE throttle_key=?",throttle);
        if(earlyLimit!=null && earlyLimit.get("locked_until")!=null && instant(earlyLimit.get("locked_until")).isAfter(now())) {
            ApiException failure=new ApiException(429,"尝试次数过多，请稍后重试");
            failure.retryAfter=(int)Math.max(1,Duration.between(now(),instant(earlyLimit.get("locked_until"))).toSeconds());
            throw failure;
        }
        var snapshot=one("SELECT * FROM users WHERE username=? OR email=? OR phone=?",account,normalized,account);
        String snapshotHash=snapshot==null?dummyHash:snapshot.get("password_hash").toString();
        boolean correct=encoder.matches(password,snapshotHash);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LoginAttempt result=tx.execute(status->{
            Instant time=now();
            db.update("INSERT IGNORE INTO auth_login_throttle(throttle_key,window_start) VALUES(?,?)",throttle,stamp(time));
            var limit=one("SELECT * FROM auth_login_throttle WHERE throttle_key=? FOR UPDATE",throttle);
            if(limit.get("locked_until")!=null && instant(limit.get("locked_until")).isAfter(time)) return new LoginAttempt(429,Math.max(1,Duration.between(time,instant(limit.get("locked_until"))).toSeconds()));
            Map<String,Object> latest=snapshot==null?null:user(number(snapshot,"user_id"),true);
            boolean accepted=correct && latest!=null && snapshotHash.equals(latest.get("password_hash"));
            if(!accepted) {
                boolean fresh=!instant(limit.get("window_start")).plus(5,ChronoUnit.MINUTES).isAfter(time);
                int failures=fresh?1:((Number)limit.get("failure_count")).intValue()+1;
                Instant start=fresh?time:instant(limit.get("window_start"));
                db.update("UPDATE auth_login_throttle SET failure_count=?,window_start=?,locked_until=? WHERE throttle_key=?",failures,stamp(start),failures>=5?stamp(time.plus(15,ChronoUnit.MINUTES)):null,throttle);
                return new LoginAttempt(failures>=5?429:401,failures>=5?900L:null);
            }
            if(!"ACTIVE".equals(latest.get("account_status"))) return new LoginAttempt(403,null);
            db.update("UPDATE auth_login_throttle SET failure_count=0,window_start=?,locked_until=NULL WHERE throttle_key=?",stamp(time),throttle);
            db.update("INSERT INTO auth_sessions(token_hash,user_id,auth_version,issued_at,expires_at,last_seen_at) VALUES(?,?,?,?,?,?)",hash(token),number(latest,"user_id"),number(latest,"auth_version"),stamp(time),stamp(time.plus(2,ChronoUnit.HOURS)),stamp(time));
            return new LoginAttempt(200,Map.of("access_token",token,"token_type","Bearer","expires_in",7200,"user",view(latest,false)));
        });
        if(result.status()!=200) {
            ApiException failure = new ApiException(result.status(),result.status()==429?"尝试次数过多，请稍后重试":result.status()==403?"账号已停用，请联系管理员":"账号或密码错误",null,result.status()==403?"ACCOUNT_DISABLED":null);
            if(result.status()==429)failure.retryAfter=((Number)result.data()).intValue();
            throw failure;
        }
        return result.data();
    }
    private String tokenHash(String authorization) {
        if(authorization==null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw new ApiException(401,"请先登录");
        return hash(authorization.substring(7));
    }
    public Identity authenticate(String authorization) {
        String tokenHash=tokenHash(authorization);
        return authenticateSessionHash(tokenHash);
    }
    // Internal use by short-lived file grants. This digest is never accepted as a login credential.
    public Identity authenticateSessionHash(String tokenHash) {
        if(tokenHash==null || !tokenHash.matches("[0-9a-f]{64}"))throw new ApiException(401,"登录状态已失效");
        return tx.execute(status->{
            var original=one("SELECT user_id FROM auth_sessions WHERE token_hash=?",tokenHash);
            if(original==null) throw new ApiException(401,"登录状态已失效，请重新登录");
            var value=user(number(original,"user_id"),true);
            var session=one("SELECT * FROM auth_sessions WHERE token_hash=? FOR UPDATE",tokenHash);
            Instant time=now();
            if(session.get("revoked_at")!=null || !"ACTIVE".equals(value.get("account_status")) || number(session,"auth_version")!=number(value,"auth_version") || !instant(session.get("expires_at")).isAfter(time) || !instant(session.get("last_seen_at")).plus(30,ChronoUnit.MINUTES).isAfter(time)) throw new ApiException(401,"登录状态已失效，请重新登录");
            db.update("UPDATE auth_sessions SET last_seen_at=? WHERE token_hash=?",stamp(time),tokenHash);
            return new Identity(number(value,"user_id"),tokenHash,number(value,"auth_version"),"ADMIN".equals(value.get("role")));
        });
    }
    private Map<String,Object> actor(Identity identity,boolean admin) {
        var value=user(identity.id(),true);
        var session=one("SELECT * FROM auth_sessions WHERE token_hash=? FOR UPDATE",identity.tokenHash());
        if(session==null || session.get("revoked_at")!=null || !"ACTIVE".equals(value.get("account_status")) || identity.version()!=number(value,"auth_version") || identity.version()!=number(session,"auth_version") || !instant(session.get("expires_at")).isAfter(now()) || !instant(session.get("last_seen_at")).plus(30,ChronoUnit.MINUTES).isAfter(now())) throw new ApiException(401,"登录状态已失效，请重新登录");
        if(admin && !"ADMIN".equals(value.get("role"))) throw new ApiException(403,"需要管理员权限");
        return value;
    }
    public Object me(Identity identity) { return view(user(identity.id(),false),false); }
    public Object logout(String authorization) {
        String tokenHash=tokenHash(authorization);
        if(one("SELECT token_hash FROM auth_sessions WHERE token_hash=?",tokenHash)==null) throw new ApiException(401,"请先登录");
        db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE token_hash=?",stamp(now()),tokenHash);return null;
    }
    public Object changePassword(Identity identity,JsonNode body) {
        fields(body,"current_password","new_password");
        String old=text(body,"current_password",12,64,false), next=text(body,"new_password",12,64,false);
        var snapshot=user(identity.id(),false);
        if(!encoder.matches(old,snapshot.get("password_hash").toString())) throw new ApiException(400,"当前密码不正确","current_password");
        String encoded=encoder.encode(next);
        return tx.execute(status->{
            var value=actor(identity,false);
            if(!value.get("password_hash").equals(snapshot.get("password_hash"))) throw new ApiException(409,"密码已发生变化，请重新登录");
            db.update("UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE user_id=?",encoded,identity.id());
            db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",stamp(now()),identity.id());return null;
        });
    }
    public Object patch(Identity identity,JsonNode body) {
        fields(body,"avatar_ref","personal_bio","address","privacy_settings");
        if(body.isEmpty()) throw new ApiException(400,"至少修改一项资料");
        if(body.has("avatar_ref")) throw new ApiException(501,"头像文件服务尚在开发");
        String bio=body.has("personal_bio")?text(body,"personal_bio",0,500,false):null;
        String address=body.has("address")?text(body,"address",0,500,false):null;
        JsonNode privacy=body.get("privacy_settings");
        if(privacy!=null) {
            fields(privacy,"show_campus","show_personal_bio");
            if(!privacy.path("show_campus").isBoolean() || !privacy.path("show_personal_bio").isBoolean()) throw new ApiException(400,"请同时设置两项隐私开关","privacy_settings");
        }
        return tx.execute(status->{
            actor(identity,false);
            if(bio!=null)db.update("UPDATE users SET personal_bio=? WHERE user_id=?",bio,identity.id());
            if(address!=null)db.update("UPDATE users SET address=? WHERE user_id=?",address,identity.id());
            if(privacy!=null)db.update("UPDATE users SET privacy_settings=? WHERE user_id=?",privacy.toString(),identity.id());
            return me(identity);
        });
    }
    public Object publicProfile(long id) {
        var row=user(id,false);
        if(!"ACTIVE".equals(row.get("account_status"))) throw new ApiException(404,"用户不存在");
        var privacy=json.valueToTree(decode(row.get("privacy_settings")));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("user_id",String.valueOf(id));result.put("username",row.get("username"));result.put("avatar_url",row.get("avatar_url"));
        result.put("campus",privacy.path("show_campus").asBoolean()?row.get("campus"):null);
        result.put("personal_bio",privacy.path("show_personal_bio").asBoolean()?row.get("personal_bio"):null);return result;
    }
    public Object list(int page,int size,String keyword,String state,String role,String campus) {
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw new ApiException(400,"分页参数超出范围");
        if(keyword.length()>100 || campus.length()>50 || (!state.isEmpty()&&!Set.of("ACTIVE","DISABLED").contains(state)) || (!role.isEmpty()&&!Set.of("USER","ADMIN").contains(role))) throw new ApiException(400,"筛选条件格式错误");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!keyword.isBlank()){where+=" AND LOCATE(?,username)>0";args.add(keyword.trim());}
        if(!state.isEmpty()){where+=" AND account_status=?";args.add(state);}
        if(!role.isEmpty()){where+=" AND role=?";args.add(role);}
        if(!campus.isEmpty()){where+=" AND campus=?";args.add(campus);}
        long count=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM users"+where,Long.class,args.toArray()));
        args.add(size);args.add((page-1)*size);
        var rows=db.queryForList("SELECT * FROM users"+where+" ORDER BY registration_time DESC,user_id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows.stream().map(u->view(u,true)).toList(),"total",String.valueOf(count),"page",page,"page_size",size);
    }
    public Object adminDetail(long id) { return view(user(id,false),true); }
    public Object role(Identity identity,long target,JsonNode body,String key) {
        fields(body,"role","reason");String next=text(body,"role",1,10,false),reason=text(body,"reason",1,500,true);
        if(!Set.of("USER","ADMIN").contains(next))throw new ApiException(400,"角色无效","role");
        return adminMutation(identity,"role:"+target,key,body,()->{
            var row=user(target,true);
            if(next.equals("USER")&&"ADMIN".equals(row.get("role"))&&"ACTIVE".equals(row.get("account_status"))&&db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND account_status='ACTIVE'",Long.class)<=1)throw new ApiException(409,"必须保留一名启用的管理员");
            db.update("UPDATE users SET role=?,auth_version=auth_version+1 WHERE user_id=?",next,target);
            db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",stamp(now()),target);
            db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,before_data,after_data,created_at) VALUES(?,'USER',?,'ROLE_CHANGE',?,?,?,?)",
                    identity.id(),Long.toString(target),reason,encode(Map.of("role",row.get("role"))),encode(Map.of("role",next)),stamp(now()));
            return view(user(target,false),true);
        });
    }
    public Object status(Identity identity,long target,JsonNode body,String key) {
        fields(body,"account_status","reason");String next=text(body,"account_status",1,10,false),reason=text(body,"reason",1,500,true);
        if(!Set.of("ACTIVE","DISABLED").contains(next)) throw new ApiException(400,"账号状态无效","account_status");
        String signature=digest(encode(List.of(next,reason)));
        return idempotent("status:"+identity.id()+":"+target,key,signature,()->{
            db.queryForObject("SELECT guard_id FROM auth_admin_guard WHERE guard_id=1 FOR UPDATE",Integer.class);
            actor(identity,true);var row=user(target,true);
            if(next.equals("DISABLED") && target==identity.id()) throw new ApiException(409,"不能停用自己的账号");
            if(next.equals("DISABLED") && "ADMIN".equals(row.get("role")) && "ACTIVE".equals(row.get("account_status")) && Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND account_status='ACTIVE'",Long.class))<=1) throw new ApiException(409,"必须保留一名启用的管理员");
            String before=encode(Map.of("account_status",row.get("account_status")));
            db.update("UPDATE users SET account_status=?,auth_version=auth_version+1 WHERE user_id=?",next,target);
            db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",stamp(now()),target);
            db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,before_data,after_data,created_at) VALUES(?,'USER',?,'ACCOUNT_STATUS',?,?,?,?)",identity.id(),String.valueOf(target),reason,before,encode(Map.of("account_status",next)),stamp(now()));
            return view(user(target,false),true);
        });
    }
    private Map<String,Object> view(Map<String,Object> row,boolean admin) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("username","phone","email","role","account_status","avatar_url","campus","identity_verification","campus_verification","personal_bio","address"))result.put(key,row.get(key));
        result.put("user_id",String.valueOf(number(row,"user_id")));
        result.put("registration_time",instant(row.get("registration_time")).toString());
        Object privacy=decode(row.get("privacy_settings"));
        result.put("privacy_settings",privacy==null?Map.of("show_campus",false,"show_personal_bio",false):privacy);
        if(admin){
            String phone=(String)row.get("phone"),email=(String)row.get("email");
            result.put("phone",phone==null?null:phone.substring(0,Math.min(4,phone.length()))+"****"+phone.substring(Math.max(4,phone.length()-4)));
            result.put("email",email==null?null:email.substring(0,Math.min(2,email.indexOf('@')))+"***"+email.substring(email.indexOf('@')));
            result.put("address",null);
        }
        return result;
    }
    // Shared admin writes use one transaction for authorization, state, audit and replay.
    // Stored procedures with their own COMMIT must not be called inside this boundary.
    public Object adminMutation(Identity identity,String scope,String key,Object signature,Supplier<Object> action) {
        final String canonical;
        try { canonical=json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(json.convertValue(signature,Object.class)); }
        catch(Exception ex) { throw new IllegalArgumentException("Invalid action signature",ex); }
        return idempotent("admin:"+identity.id()+":"+scope,key,digest(canonical),()->{
            db.queryForObject("SELECT guard_id FROM auth_admin_guard WHERE guard_id=1 FOR UPDATE",Integer.class);
            actor(identity,true);
            return action.get();
        });
    }
    public Object adminTransaction(Identity identity,Supplier<Object> action) {
        return tx.execute(status->{
            db.queryForObject("SELECT guard_id FROM auth_admin_guard WHERE guard_id=1 FOR UPDATE",Integer.class);
            actor(identity,true);return action.get();
        });
    }
    public Object memberMutation(Identity identity,String scope,String key,Object signature,Supplier<Object> action) {
        final String canonical;
        try {canonical=json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(signature,Object.class));}
        catch(Exception ex){throw new IllegalArgumentException("Invalid action signature",ex);}
        return idempotent("member:"+identity.id()+":"+scope,key,digest(canonical),()->{
            db.queryForObject("SELECT guard_id FROM auth_admin_guard WHERE guard_id=1 FOR UPDATE",Integer.class);
            actor(identity,false);return action.get();
        });
    }
    public Object sessionTransaction(Identity identity,Supplier<Object> action) {
        return tx.execute(status->{actor(identity,false);return action.get();});
    }
}
