package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.campuspi.account.AccountService.Identity;

@Service
public class BusinessFileService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AccountService accounts;
    private final AdminPolicy policy;
    private final BusinessFileValidator validator;
    private final BusinessFileScanner scanner;
    private final Path root;
    private final String publicBase;
    private final SecureRandom random=new SecureRandom();
    private final Semaphore uploadCapacity=new Semaphore(2);
    private record Access(boolean publicFile,boolean sensitiveAdmin,String context){}
    public record Content(InputStream stream,long size,String mime,String fileId){}
    public BusinessFileService(JdbcTemplate db,TransactionTemplate tx,AccountService accounts,AdminPolicy policy,
            BusinessFileValidator validator,BusinessFileScanner scanner,@Value("${campus.files.root:}") String root,
            @Value("${campus.files.public-base-url:}") String base) {
        this.db=db;this.tx=tx;this.accounts=accounts;this.policy=policy;this.validator=validator;this.scanner=scanner;
        this.root=root.isBlank()?null:Path.of(root).toAbsolutePath().normalize();
        if(!base.isBlank()) {
            URI uri=URI.create(base);
            boolean loopback=Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()==null?"":uri.getHost());
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null ||
                    (!uri.getPath().isEmpty()&&!uri.getPath().equals("/")) || !("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&loopback)))throw new IllegalArgumentException("File public base must be a trusted HTTPS origin or local loopback origin");
        }
        this.publicBase=base.replaceAll("/+$","");
    }
    private Instant now(){return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private Instant instant(Object v){return v instanceof Timestamp t?t.toInstant():((LocalDateTime)v).toInstant(ZoneOffset.UTC);}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String hash(String value){return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public String fileId(String value) {
        try {String normalized=UUID.fromString(value).toString();if(!normalized.equalsIgnoreCase(value))throw new IllegalArgumentException();return normalized;}
        catch(Exception ex){throw new ApiException(400,"文件编号无效","file_id");}
    }
    public String refId(String ref) {
        if(ref==null||!ref.startsWith("file://"))throw new ApiException(400,"仅允许本系统上传的文件引用","evidence_refs");
        return fileId(ref.substring(7));
    }
    private Path folder(String name) throws IOException {
        if(root==null||publicBase.isEmpty())throw new ApiException(503,"文件存储与访问地址尚未配置");
        Files.createDirectories(root);
        if(!root.toRealPath().equals(root))throw new IOException("Storage root is a link");
        Path path=root.resolve(name);Files.createDirectories(path);
        if(Files.isSymbolicLink(path)||!path.toRealPath().startsWith(root))throw new IOException("Unsafe storage path");
        return path;
    }
    private Path objectPath(String id) throws IOException {
        Path path=folder("objects").resolve(fileId(id)+".blob");
        if(Files.isSymbolicLink(path))throw new IOException("File is a link");return path;
    }
    private boolean bound(String id){return db.queryForObject("SELECT COUNT(*) FROM business_file_bindings WHERE file_id=?",Long.class,id)>0;}
    private Map<String,Object> metadata(String id,boolean lock) {
        id=fileId(id);
        var row=one("SELECT * FROM business_files WHERE file_id=?"+(lock?" FOR UPDATE":""),fileId(id));
        if(row==null||!"CLEAN".equals(row.get("scan_status")))throw new ApiException(404,"文件不存在");
        if(!instant(row.get("unbound_expires_at")).isAfter(now())&&!bound(id))throw new ApiException(404,"未绑定文件已过期");
        return row;
    }
    private Object view(Map<String,Object> row){return Map.of("file_id",row.get("file_id"),"file_ref","file://"+row.get("file_id"),"purpose",row.get("purpose"),"mime_type",row.get("mime_type"),"size",number(row,"size_bytes"),"expires_at",instant(row.get("unbound_expires_at")).toString());}
    public Object upload(Identity actor,String purpose,MultipartFile file,String key) {
        if(file==null||file.isEmpty())throw new ApiException(400,"请选择文件","file");
        if(file.getSize()>10L*1024*1024)throw new ApiException(413,"文件超过大小限制","file","UPLOAD_TOO_LARGE");
        if(!uploadCapacity.tryAcquire())throw new ApiException(503,"文件处理繁忙，请稍后重试");
        Path temporary=null,stored=null;boolean committed=false;
        try {
            temporary=Files.createTempFile(folder("quarantine"),"upload-",".bin");
            file.transferTo(temporary);
            String mime=validator.inspect(temporary,purpose,file.getSize());
            String engine=scanner.scan(temporary);
            String digest=hash(Files.readAllBytes(temporary));
            String id=UUID.randomUUID().toString();stored=objectPath(id);
            Files.move(temporary,stored);temporary=null;
            Object result=accounts.memberMutation(actor,"file-upload",key,Map.of("purpose",purpose,"sha256",digest,"size",file.getSize()),()->{
                Instant time=now(),expires=time.plus(24,java.time.temporal.ChronoUnit.HOURS);
                db.update("INSERT INTO business_files(file_id,owner_id,purpose,mime_type,size_bytes,sha256,scan_status,scanner_name,scanned_at,created_at,unbound_expires_at) VALUES(?,?,?,?,?,?,'CLEAN',?,?,?,?)",
                        id,actor.id(),purpose,mime,file.getSize(),digest,engine,Timestamp.from(time),Timestamp.from(time),Timestamp.from(expires));
                return view(metadata(id,false));
            });
            committed=result instanceof Map<?,?> map&&id.equals(map.get("file_id"));
            return result;
        }catch(IOException ex){throw new ApiException(503,"文件存储不可用");}
        finally {
            try{if(temporary!=null)Files.deleteIfExists(temporary);if(stored!=null&&!committed)Files.deleteIfExists(stored);}catch(IOException ignored){/* Periodic orphan cleanup handles interrupted filesystem cleanup. */}
            uploadCapacity.release();
        }
    }
    public List<String> parseRefs(JsonNode refs) {
        if(refs==null||!refs.isArray()||refs.isEmpty()||refs.size()>9)throw new ApiException(400,"需要1至9个文件引用","evidence_refs");
        var result=new ArrayList<String>();
        for(JsonNode ref:refs){if(!ref.isTextual())throw new ApiException(400,"文件引用必须是字符串");String normalized="file://"+refId(ref.asText());if(result.contains(normalized))throw new ApiException(400,"文件引用不能重复");result.add(normalized);}
        return result;
    }
    /** Called only within the owning business transaction, after object/owner authorization. */
    public void bindRefs(long owner,String purpose,String type,String objectId,List<String> refs) {
        if(!Objects.equals(purpose,purposeFor(type)))throw new IllegalArgumentException("Binding purpose mismatch");
        List<String> ids=refs.stream().map(this::refId).sorted().toList();
        for(String id:ids) {
            var row=metadata(id,true);
            if(number(row,"owner_id")!=owner||!purpose.equals(row.get("purpose")))throw new ApiException(403,"文件用途或归属不匹配");
            db.update("INSERT IGNORE INTO business_file_bindings(file_id,object_type,object_id,bound_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",id,type,objectId);
        }
    }
    private String purposeFor(String type){return switch(type){case "PRODUCT"->"PRODUCT_IMAGE";case "MESSAGE"->"CHAT_IMAGE";default->type;};}
    private boolean sameRef(Object value,String id){return ("file://"+id).equals(value);}
    private boolean containsRef(String table,String idColumn,Object id,String column,String ref){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+idColumn+"=? AND JSON_CONTAINS("+column+",JSON_QUOTE(?))",Long.class,id,ref)>0;}
    private Access authorize(Map<String,Object> file,Identity actor) {
        String id=file.get("file_id").toString(),ref="file://"+id;long owner=number(file,"owner_id");
        var links=db.queryForList("SELECT object_type,object_id FROM business_file_bindings WHERE file_id=?",id);
        if(links.isEmpty()&&actor!=null&&actor.id()==owner)return new Access(false,false,"未绑定文件");
        for(var link:links) {
            String type=link.get("object_type").toString(),object=link.get("object_id").toString();
            if(!purposeFor(type).equals(file.get("purpose")))continue;
            if(type.equals("AVATAR")) {
                var user=one("SELECT user_id,avatar_url,account_status FROM users WHERE user_id=?",object);
                if(user!=null&&number(user,"user_id")==owner&&sameRef(user.get("avatar_url"),id)&&"ACTIVE".equals(user.get("account_status")))return new Access(true,false,"公开头像");
            }else if(type.equals("PRODUCT")) {
                var product=one("SELECT p.*,u.account_status FROM product_info p JOIN users u ON u.user_id=p.user_id WHERE product_id=?",object);
                if(product==null||number(product,"user_id")!=owner||db.queryForObject("SELECT COUNT(*) FROM product_media WHERE product_id=? AND image_url=?",Long.class,object,ref)==0)continue;
                if("已通过".equals(product.get("audit_status"))&&Set.of("在售","已售出").contains(product.get("shelf_status"))&&"ACTIVE".equals(product.get("account_status")))return new Access(true,false,"公开商品图片");
                if(actor!=null&&(actor.id()==owner||actor.admin()))return new Access(false,false,"商品审核图片");
            }else if(type.equals("VERIFICATION")) {
                var value=one("SELECT user_id FROM user_verification WHERE verification_id=?",object);
                if(value==null||number(value,"user_id")!=owner||!containsRef("user_verification","verification_id",object,"evidence_urls",ref))continue;
                if(actor!=null&&actor.id()==owner)return new Access(false,false,"本人认证材料");
                if(policy.has(actor,"VERIFICATION_REVIEW"))return new Access(false,true,"认证申请 "+object);
            }else if(type.equals("TASK_PROOF")) {
                var value=one("SELECT p.submitter_id,t.publisher_id,t.taker_id FROM task_proofs p JOIN RewardTasks t ON t.reward_task_id=p.reward_task_id WHERE p.proof_id=?",object);
                if(value==null||number(value,"submitter_id")!=owner||!containsRef("task_proofs","proof_id",object,"evidence_urls",ref))continue;
                long taker=value.get("taker_id") instanceof Number n?n.longValue():-1;
                if(actor!=null&&(actor.id()==number(value,"publisher_id")||actor.id()==taker||actor.admin()))return new Access(false,actor.admin()&&actor.id()!=owner,"任务凭证 "+object);
            }else if(type.equals("AFTERSALE")) {
                var value=one("SELECT a.user_id,o.user_id buyer_id,o.seller_id FROM order_aftersales a JOIN user_orders o ON o.order_id=a.order_id WHERE a.aftersale_id=?",object);
                if(value==null||number(value,"user_id")!=owner||!containsRef("order_aftersales","aftersale_id",object,"evidence_urls",ref))continue;
                if(actor!=null&&(actor.id()==number(value,"buyer_id")||actor.id()==number(value,"seller_id")))return new Access(false,false,"本人订单售后");
                if(policy.has(actor,"ORDERS"))return new Access(false,true,"售后工单 "+object);
            }else if(type.equals("MESSAGE")) {
                var message=one("SELECT * FROM t_private_msg WHERE msg_id=? AND content_type='图片' AND content=?",object,ref);
                if(message==null||number(message,"sender_id")!=owner)continue;
                if(actor!=null&&((actor.id()==number(message,"sender_id")&&!truth(message.get("sender_deleted")))||(actor.id()==number(message,"receiver_id")&&!truth(message.get("receiver_deleted")))))return new Access(false,false,"私信图片");
                var report=one("SELECT report_id FROM t_msg_report WHERE msg_id=? AND reporter_id IN (?,?) ORDER BY report_id DESC LIMIT 1",object,message.get("sender_id"),message.get("receiver_id"));
                if(report!=null&&policy.has(actor,"REPORTS"))return new Access(false,true,"举报案件 "+report.get("report_id"));
            }
        }
        throw new ApiException(403,"无权访问此文件");
    }
    private boolean truth(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}
    private void accessAudit(Identity actor,String id,String action,String reason) {
        db.update("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(?,'文件',?,?,?)",actor.id(),id,action,reason);
    }
    public Object access(String id,Identity actor) {
        if(root==null||publicBase.isEmpty())throw new ApiException(503,"文件服务尚未配置");
        final String canonical=fileId(id);
        java.util.function.Supplier<Object> action=()->{
            var row=metadata(canonical,true);Access allowed=authorize(row,actor);
            Instant expires=now().plusSeconds(60);
            if(!bound(canonical)&&instant(row.get("unbound_expires_at")).isBefore(expires))expires=instant(row.get("unbound_expires_at"));
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            db.update("INSERT INTO business_file_grants(token_hash,file_id,session_hash,expires_at) VALUES(?,?,?,?)",hash(token),canonical,allowed.publicFile()?null:actor.tokenHash(),Timestamp.from(expires));
            if(allowed.sensitiveAdmin())accessAudit(actor,canonical,"授权预览",allowed.context());
            return Map.of("url",publicBase+"/file-content/"+canonical+"/"+token,"expires_at",expires.toString());
        };
        return actor==null?tx.execute(status->action.get()):accounts.sessionTransaction(actor,action);
    }
    public Content content(String id,String token) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ApiException(404,"文件地址无效");
        var grant=one("SELECT * FROM business_file_grants WHERE token_hash=? AND file_id=?",hash(token),fileId(id));
        if(grant==null||!instant(grant.get("expires_at")).isAfter(now()))throw new ApiException(404,"文件地址已过期");
        Identity viewer=grant.get("session_hash")==null?null:accounts.authenticateSessionHash(grant.get("session_hash").toString());
        var row=metadata(fileId(id),false);Access allowed=authorize(row,viewer);
        if(allowed.sensitiveAdmin())accounts.sessionTransaction(viewer,()->{accessAudit(viewer,id,"读取文件",allowed.context());return null;});
        try {
            Path path=objectPath(id);
            if(Files.size(path)!=number(row,"size_bytes"))throw new IOException("Stored length mismatch");
            return new Content(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS),number(row,"size_bytes"),row.get("mime_type").toString(),fileId(id));
        }catch(IOException ex){throw new ApiException(503,"文件内容暂不可读取");}
    }
    public List<String> productImages(long productId,Identity viewer) {
        var media=db.queryForList("SELECT image_url FROM product_media WHERE product_id=? ORDER BY sort_order,media_id",productId);
        List<String> result=new ArrayList<>();
        for(var row:media) {
            String ref=row.get("image_url").toString();
            if(!ref.startsWith("file://"))continue; // Legacy arbitrary URLs are never implicitly trusted.
            @SuppressWarnings("unchecked") Map<String,Object> grant=(Map<String,Object>)access(refId(ref),viewer);
            result.add(grant.get("url").toString());
        }
        return result;
    }
    public Object decorateProducts(Object value,Identity viewer) {
        if(!(value instanceof Map<?,?> data))return value;
        if(data.containsKey("product_id")) {
            var result=new LinkedHashMap<String,Object>();data.forEach((k,v)->result.put(k.toString(),v));
            result.put("image_urls",productImages(Long.parseLong(data.get("product_id").toString()),viewer));return result;
        }
        if(data.get("items") instanceof List<?> items) {
            var result=new LinkedHashMap<String,Object>();data.forEach((k,v)->result.put(k.toString(),v));
            result.put("items",items.stream().map(item->decorateProducts(item,viewer)).toList());return result;
        }
        return value;
    }
    @Scheduled(fixedDelayString="${campus.files.cleanup-interval-ms:60000}",initialDelayString="${campus.files.cleanup-interval-ms:60000}")
    public void cleanup() {
        if(root==null||publicBase.isEmpty())return;
        try {
            db.update("DELETE FROM business_file_grants WHERE expires_at<=UTC_TIMESTAMP(3) LIMIT 1000");
            var candidates=db.queryForList("SELECT file_id FROM business_files WHERE unbound_expires_at<=UTC_TIMESTAMP(3) AND NOT EXISTS(SELECT 1 FROM business_file_bindings b WHERE b.file_id=business_files.file_id) LIMIT 100");
            for(var candidate:candidates) {
                String id=candidate.get("file_id").toString();
                boolean removed=Boolean.TRUE.equals(tx.execute(status->{
                    var row=one("SELECT * FROM business_files WHERE file_id=? FOR UPDATE",id);
                    if(row==null||instant(row.get("unbound_expires_at")).isAfter(now())||bound(id))return false;
                    db.update("DELETE FROM business_file_grants WHERE file_id=?",id);db.update("DELETE FROM business_files WHERE file_id=?",id);return true;
                }));
                if(removed)Files.deleteIfExists(objectPath(id));
            }
            Instant cutoff=now().minus(24,java.time.temporal.ChronoUnit.HOURS);
            for(String name:List.of("quarantine","objects"))try(var paths=Files.list(folder(name))) {
                for(Path path:paths.limit(1000).toList()) {
                    if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.getLastModifiedTime(path).toInstant().isBefore(cutoff))continue;
                    String filename=path.getFileName().toString();
                    if(name.equals("quarantine")&&filename.matches("upload-[A-Za-z0-9-]+\\.bin"))Files.deleteIfExists(path);
                    else if(filename.matches("[0-9a-f-]{36}\\.blob")&&one("SELECT file_id FROM business_files WHERE file_id=?",filename.substring(0,36))==null)Files.deleteIfExists(path);
                }
            }
        }catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("File cleanup deferred: storage or database unavailable");}
    }
}
