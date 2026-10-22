package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A batch is a durable list of independent transactions, using the original single-item services. */
@Service
public class AdminBatchService {
    private static final Logger log=LoggerFactory.getLogger(AdminBatchService.class);
    private final JdbcTemplate db;
    private final AccountService accounts;
    private final AdminContentService content;
    private final AdminTaskService tasks;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    @Value("${campus.admin-batches.worker-enabled:false}") private boolean enabled;
    public AdminBatchService(JdbcTemplate db,AccountService accounts,AdminContentService content,AdminTaskService tasks,ObjectMapper json,PlatformTransactionManager manager){
        this.db=db;this.accounts=accounts;this.content=content;this.tasks=tasks;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Batch serialization failed");}}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception ex){throw new IllegalStateException("Invalid durable batch payload");}}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}

    public Object submit(AccountService.Identity actor,String kind,JsonNode body,String key){
        if(!Set.of("users","products","posts","tasks").contains(kind))throw new ApiException(400,"不支持此批量操作");
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw new ApiException(400,"请提供8至64位 Idempotency-Key");
        String field=kind.equals("users")?"account_status":"decision";
        AccountService.fields(body,"ids",field,"reason");
        String choice=AccountService.text(body,field,1,20,true),reason=AccountService.text(body,"reason",1,500,true);
        if(!(kind.equals("users")?Set.of("ACTIVE","DISABLED"):Set.of("通过","驳回")).contains(choice))throw new ApiException(400,"操作选项无效",field);
        var values=body.get("ids");
        if(values==null||!values.isArray()||values.isEmpty()||values.size()>50)throw new ApiException(400,"每批请选择1至50条记录","ids");
        var ids=new ArrayList<String>();var seen=new HashSet<String>();
        for(var value:values){
            if(!value.isTextual())throw new ApiException(400,"记录编号必须使用字符串","ids");
            String id=value.asText();
            if(kind.equals("posts")){
                if(id.isBlank()||id.codePointCount(0,id.length())>50||id.chars().anyMatch(Character::isISOControl))throw new ApiException(400,"帖子编号无效","ids");
            } else id=String.valueOf(AccountService.id(id));
            if(!seen.add(id))throw new ApiException(400,"同一批次不能包含重复编号","ids");
            ids.add(id);
        }
        ObjectNode action=json.createObjectNode().put(field,choice).put("reason",reason);
        String signature=digest(List.of(kind,ids,choice,reason));
        return accounts.adminTransaction(actor,()->{
            var existing=one("SELECT job_id,request_digest FROM admin_batch_jobs WHERE operator_id=? AND request_key=?",actor.id(),key);
            if(existing!=null){
                if(!signature.equals(existing.get("request_digest")))throw new ApiException(409,"该幂等键已用于其他批量请求",null,"IDEMPOTENCY_CONFLICT");
                return snapshot(existing.get("job_id").toString());
            }
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO admin_batch_jobs(job_id,operator_id,session_hash,auth_version,request_key,request_digest,operation_kind,action_body) VALUES(?,?,?,?,?,?,?,?)",id,actor.id(),actor.tokenHash(),actor.version(),key,signature,kind,encode(action));
            for(int index=0;index<ids.size();index++)db.update("INSERT INTO admin_batch_items(job_id,item_no,target_id) VALUES(?,?,?)",id,index+1,ids.get(index));
            return snapshot(id);
        });
    }
    public Object get(AccountService.Identity actor,String id){
        return accounts.adminTransaction(actor,()->{
            if(one("SELECT job_id FROM admin_batch_jobs WHERE job_id=? AND operator_id=?",id,actor.id())==null)throw new ApiException(404,"批次不存在");
            return snapshot(id);
        });
    }
    public Object recent(AccountService.Identity actor,String kind){
        if(!Set.of("users","products","posts","tasks").contains(kind))throw new ApiException(400,"批次类型无效");
        return accounts.adminTransaction(actor,()->Map.of("items",db.queryForList("SELECT job_id FROM admin_batch_jobs WHERE operator_id=? AND operation_kind=? ORDER BY created_at DESC,job_id DESC LIMIT 10",actor.id(),kind).stream().map(row->snapshot(row.get("job_id").toString())).toList()));
    }
    private Map<String,Object> snapshot(String id){
        var job=one("SELECT operation_kind,action_body,created_at FROM admin_batch_jobs WHERE job_id=?",id);
        var items=db.queryForList("SELECT target_id,item_status,http_status,error_code,result_message FROM admin_batch_items WHERE job_id=? ORDER BY item_no",id);
        long success=items.stream().filter(i->"SUCCEEDED".equals(i.get("item_status"))).count(),failed=items.stream().filter(i->"FAILED".equals(i.get("item_status"))).count();
        var result=new LinkedHashMap<String,Object>();
        result.put("batch_id",id);result.put("kind",job.get("operation_kind"));result.put("action",decode(job.get("action_body")));
        result.put("status",success+failed==items.size()?"COMPLETED":"PENDING");
        result.put("total",items.size());result.put("succeeded",success);result.put("failed",failed);result.put("pending",items.size()-success-failed);result.put("items",items);
        Object created=job.get("created_at");
        result.put("created_at",(created instanceof java.sql.Timestamp stamp?stamp.toInstant():((java.time.LocalDateTime)created).toInstant(java.time.ZoneOffset.UTC)).toString());return result;
    }
    @Scheduled(fixedDelayString="${campus.admin-batches.poll-interval-ms:1000}")
    public void poll(){
        if(!enabled)return;
        try{
            // Discovery takes no locks. Execution always locks the shared admin guard before item locks.
            for(var candidate:db.queryForList("SELECT i.job_id,i.item_no FROM admin_batch_items i JOIN admin_batch_jobs j ON j.job_id=i.job_id WHERE i.item_status='PENDING' ORDER BY j.created_at,j.job_id,i.item_no LIMIT 20")){
                process(candidate.get("job_id").toString(),((Number)candidate.get("item_no")).intValue());
            }
        }catch(RuntimeException ex){log.warn("Batch worker dependency unavailable; durable pending items will be resumed.");}
    }
    private void process(String id,int ordinal){
        var job=one("SELECT * FROM admin_batch_jobs WHERE job_id=?",id);
        if(job==null)return;
        var actor=new AccountService.Identity(((Number)job.get("operator_id")).longValue(),job.get("session_hash").toString(),((Number)job.get("auth_version")).longValue(),true);
        try{
            accounts.adminTransaction(actor,()->{
                var item=one("SELECT * FROM admin_batch_items WHERE job_id=? AND item_no=? FOR UPDATE",id,ordinal);
                if(item==null||!"PENDING".equals(item.get("item_status")))return null;
                String target=item.get("target_id").toString(),key=id+"_"+ordinal,kind=job.get("operation_kind").toString();
                var body=decode(job.get("action_body"));
                switch(kind){
                    case "users" -> accounts.status(actor,AccountService.id(target),body,key);
                    case "products" -> content.productAction(actor,AccountService.id(target),"review",body,key);
                    case "posts" -> content.postAction(actor,target,true,body,key);
                    case "tasks" -> tasks.publication(actor,AccountService.id(target),body,key);
                    default -> throw new IllegalStateException("Unsupported durable operation");
                }
                finish(id,ordinal,"SUCCEEDED",200,null,"操作成功");return null;
            });
        }catch(RuntimeException ex){
            // Catch outside the failed business transaction: it has already rolled back entirely.
            int status;String code,message;
            if(ex instanceof ApiException api){status=api.status;code=api.code!=null?api.code:switch(status){case 400->"VALIDATION_ERROR";case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 409->"STATE_CONFLICT";case 501->"FEATURE_NOT_ENABLED";default->"INTERNAL_ERROR";};message=api.getMessage();}
            else {status=ex instanceof DataAccessException?503:500;code=status==503?"DEPENDENCY_UNAVAILABLE":"INTERNAL_ERROR";message="本条操作未完成，请核对依赖后重试";}
            final int http=status;final String failureCode=code,failureMessage=message;
            tx.executeWithoutResult(transaction->{
                db.queryForObject("SELECT guard_id FROM auth_admin_guard WHERE guard_id=1 FOR UPDATE",Integer.class);
                // Another process may have finished this item after the rollback; never replace its result.
                finish(id,ordinal,"FAILED",http,failureCode,failureMessage);
            });
        }
    }
    private void finish(String id,int ordinal,String state,int http,String code,String message){
        db.update("UPDATE admin_batch_items SET item_status=?,http_status=?,error_code=?,result_message=?,completed_at=UTC_TIMESTAMP(3) WHERE job_id=? AND item_no=? AND item_status='PENDING'",state,http,code,message,id,ordinal);
    }
}
