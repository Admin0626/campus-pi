package com.campuspi.member;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.campuspi.admin.*;
import com.campuspi.testing.IntegrationDatabase;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

/** Real HTTP/files/MySQL. Only the external antivirus engine is substituted. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campus.notifications.worker-enabled=false","campus.admin-batches.worker-enabled=false","campus.verification.allowed-campuses-json=[\"测试校区\"]","campus.files.public-base-url=http://127.0.0.1"})
@EnabledIfEnvironmentVariable(named="CAMPUS_MEMBER_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberBusinessIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){IntegrationDatabase.configure(registry);registry.add("campus.files.root",()->IntegrationDatabase.ROOT.resolve(".local/remaining/test-business-files").toString());}
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired AdminPolicy policy;
    @Autowired BusinessFileService files;@LocalServerPort int serverPort;
    @MockitoBean BusinessFileScanner scanner;
    record Member(long id,String name,String password,String token){}
    Member admin;int category;
    @BeforeAll void init()throws Exception{
        IntegrationDatabase.verify(db);rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());when(scanner.scan(any(Path.class))).thenReturn("TEST_ENGINE_ONLY");
        ReflectionTestUtils.setField(files,"publicBase","http://127.0.0.1:"+serverPort);
        admin=register(false);db.update("UPDATE users SET role='ADMIN',auth_version=auth_version+1 WHERE user_id=?",admin.id());admin=login(admin);
        ((ObjectNode)ReflectionTestUtils.getField(policy,"grants")).set(Long.toString(admin.id()),json.createArrayNode().add("VERIFICATION_REVIEW").add("ORDERS").add("REPORTS").add("AUDIT"));
        var body=new LinkedHashMap<String,Object>();body.put("category_name","联调_"+UUID.randomUUID());body.put("parent_category_id",null);category=ok(call("POST","/admin/categories",body,admin,UUID.randomUUID().toString())).path("category_id").asInt();assertThat(category).isPositive();
    }
    @AfterAll void releaseSyntheticAdministrator(){
        if(admin!=null)db.update("UPDATE users SET role='USER',auth_version=auth_version+1 WHERE user_id=? AND username=?",admin.id(),admin.name());
    }
    @BeforeEach void scanner(){when(scanner.scan(any(Path.class))).thenReturn("TEST_ENGINE_ONLY");}
    ResponseEntity<JsonNode> call(String method,String path,Object body,Member member,String key){var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(member!=null)headers.setBearerAuth(member.token());if(key!=null)headers.set("Idempotency-Key",key);return rest.exchange("/api/v1"+path,HttpMethod.valueOf(method),new HttpEntity<>(body,headers),JsonNode.class);}
    ResponseEntity<JsonNode> call(String method,String path,Object body,Member member){return call(method,path,body,member,method.equals("POST")?UUID.randomUUID().toString():null);}
    JsonNode ok(ResponseEntity<JsonNode> response){assertThat(response.getStatusCode().is2xxSuccessful()).as("HTTP %s / code %s / %s",response.getStatusCode().value(),response.getBody().path("code").asText(),response.getBody().path("message").asText()).isTrue();assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");}
    void error(ResponseEntity<JsonNode> response,int status){assertThat(response.getStatusCode().value()).isEqualTo(status);assertThat(response.getBody().path("data").isNull()).isTrue();}
    Member register(boolean verified)throws Exception{
        String name="member_"+UUID.randomUUID().toString().replace("-","").substring(0,16),password=UUID.randomUUID()+"!Aa1";long id=ok(call("POST","/auth/register",Map.of("username",name,"password",password,"email",name+"@example.invalid"),null)).path("user_id").asLong();var member=login(new Member(id,name,password,""));
        if(verified){String ref=upload(member,"VERIFICATION");for(String type:List.of("实名","校园")){var body=new LinkedHashMap<String,Object>();body.put("verification_type",type);body.put("real_name","合成验收人员");body.put("evidence_refs",List.of(ref));if(type.equals("校园")){body.put("campus","测试校区");body.put("student_no","TEST_"+UUID.randomUUID().toString().substring(0,18));}String verification=ok(call("POST","/me/verifications",body,member)).path("verification_id").asText();ok(call("POST","/admin/verifications/"+verification+"/review",Map.of("decision","通过","reason","仅用于独立验收库的合成材料"),admin));}}
        return member;
    }
    Member login(Member member){String token=ok(call("POST","/auth/login",Map.of("account",member.name(),"password",member.password()),null)).path("access_token").asText();return new Member(member.id(),member.name(),member.password(),token);}
    String upload(Member member,String purpose)throws Exception{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(24,24,BufferedImage.TYPE_INT_RGB),"png",bytes);var body=new LinkedMultiValueMap<String,Object>();body.add("purpose",purpose);body.add("file",new ByteArrayResource(bytes.toByteArray()){@Override public String getFilename(){return "synthetic-evidence.png";}});var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);headers.setBearerAuth(member.token());headers.set("Idempotency-Key",UUID.randomUUID().toString());return ok(rest.exchange("/api/v1/files",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class)).path("file_ref").asText();}
    Map<String,Object> productBody(String ref,int stock){return Map.of("category_id",category,"product_name","真实接口合成商品","product_desc","仅供验收","campus","测试校区","trade_mode","校园自提","original_price","20.00","discount_price","10.00","stock_quantity",stock,"image_refs",List.of(ref));}
    String product(Member seller,int stock)throws Exception{String id=ok(call("POST","/products",productBody(upload(seller,"PRODUCT_IMAGE"),stock),seller)).path("product_id").asText();ok(call("POST","/admin/products/"+id+"/review",Map.of("decision","通过","reason","合成验收商品"),admin));return id;}
    String task(Member owner)throws Exception{
        // Explicit reward fixture, never an application self-credit or real money operation.
        db.update("UPDATE reward_accounts SET available_amount=100.00 WHERE user_id=? AND reward_type='积分'",owner.id());
        return ok(call("POST","/tasks",Map.of("task_name","合成验收任务","task_description","测试用户端到管理审核闭环","campus","测试校区","reward_amount","20.00","reward_type","积分"),owner)).path("reward_task_id").asText();
    }
    @Test void productsEnforceVerificationOwnershipVisibilityAndIdempotency()throws Exception{
        var seller=register(true);var outsider=register(false);String ref=upload(seller,"PRODUCT_IMAGE"),key=UUID.randomUUID().toString();var body=productBody(ref,2);
        error(call("POST","/products",body,outsider),403);var created=ok(call("POST","/products",body,seller,key));String id=created.path("product_id").asText();assertThat(created.path("audit_status").asText()).isEqualTo("待审核");assertThat(created.path("image_urls").size()).isEqualTo(1);assertThat(ok(call("POST","/products",body,seller,key)).path("product_id").asText()).isEqualTo(id);error(call("GET","/products/"+id,null,null),404);error(call("PUT","/products/"+id,body,outsider),403);
        ok(call("POST","/admin/products/"+id+"/review",Map.of("decision","通过","reason","验收通过"),admin));assertThat(ok(call("GET","/products/"+id,null,null)).path("seller").path("user_id").asText()).isEqualTo(Long.toString(seller.id()));ok(call("PUT","/products/"+id+"/favorite",null,outsider));ok(call("PUT","/products/"+id+"/favorite",null,outsider));assertThat(ok(call("GET","/me/product-favorites",null,outsider)).path("items").size()).isEqualTo(1);ok(call("POST","/products/"+id+"/shelf",Map.of("shelf_status","已下架","reason","本人暂时下架"),seller));assertThat(ok(call("GET","/me/product-favorites",null,outsider)).path("items").size()).isZero();error(call("GET","/products/"+id,null,null),404);
    }
    @Test void ordersReserveAndReleaseExactlyOnceAndProtectPrivateAddresses()throws Exception{
        var seller=register(true);var buyer=register(true);var outsider=register(false);String product=product(seller,1),key=UUID.randomUUID().toString();var body=Map.of("product_id",product,"quantity",1,"shipping_address","合成自提地点");var order=ok(call("POST","/orders/product",body,buyer,key));String id=order.path("order_id").asText();assertThat(order.path("total_amount").asText()).isEqualTo("10.00");assertThat(order.path("shipping_fee").asText()).isEqualTo("0.00");assertThat(ok(call("POST","/orders/product",body,buyer,key)).path("order_id").asText()).isEqualTo(id);assertThat(db.queryForObject("SELECT stock_quantity FROM product_info WHERE product_id=?",Integer.class,product)).isZero();error(call("GET","/orders/"+id,null,outsider),404);error(call("PUT","/products/"+product,productBody(upload(seller,"PRODUCT_IMAGE"),9),seller),409);String cancel=UUID.randomUUID().toString();ok(call("POST","/orders/"+id+"/cancel",null,buyer,cancel));ok(call("POST","/orders/"+id+"/cancel",null,buyer,cancel));error(call("POST","/orders/"+id+"/cancel",null,buyer),409);assertThat(db.queryForObject("SELECT stock_quantity FROM product_info WHERE product_id=?",Integer.class,product)).isEqualTo(1);error(call("POST","/orders/"+id+"/payment-intents",Map.of("channel","微信"),buyer),501);
    }
    @Test void competingOrdersCannotOversell()throws Exception{
        var seller=register(true);var one=register(true);var two=register(true);String id=product(seller,1);var body=Map.of("product_id",id,"quantity",1,"shipping_address","合成自提地点");var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return call("POST","/orders/product",body,one).getStatusCode().value();});var b=pool.submit(()->{start.await();return call("POST","/orders/product",body,two).getStatusCode().value();});start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);assertThat(db.queryForObject("SELECT stock_quantity FROM product_info WHERE product_id=?",Integer.class,id)).isZero();}finally{pool.shutdownNow();}
    }
    @Test void anonymousPostsReenterReviewAndCollectionsBecomeInvalid()throws Exception{
        var owner=register(false);var viewer=register(false);var body=Map.of("category_id",category,"post_title","匿名验收帖子","post_content","纯文本 <script>literal</script>","is_anonymous",true);String id=ok(call("POST","/posts",body,owner)).path("post_id").asText();error(call("GET","/posts/"+id,null,viewer),404);ok(call("POST","/admin/posts/"+id+"/review",Map.of("decision","通过","reason","验收"),admin));var post=ok(call("GET","/posts/"+id,null,viewer));assertThat(post.path("author").isNull()).isTrue();assertThat(post.toString()).doesNotContain(owner.name());ok(call("PUT","/posts/"+id+"/collection",null,viewer));ok(call("PUT","/posts/"+id,body,owner));error(call("GET","/posts/"+id,null,viewer),404);assertThat(ok(call("GET","/me/post-collections",null,viewer)).path("items").size()).isZero();error(call("PUT","/posts/"+id,body,viewer),404);ok(call("DELETE","/posts/"+id,null,owner));ok(call("DELETE","/posts/"+id,null,owner));error(call("PUT","/posts/"+id,body,owner),409);
    }
    @Test void taskPublishCancelAndInsufficientBalanceAreAtomic()throws Exception{
        var owner=register(true);var body=Map.of("task_name","不足积分","task_description","验收冻结","campus","测试校区","reward_amount","1.00","reward_type","积分");error(call("POST","/tasks",body,owner),409);String id=task(owner);assertThat(db.queryForObject("SELECT frozen_amount FROM reward_accounts WHERE user_id=? AND reward_type='积分'",java.math.BigDecimal.class,owner.id()).toPlainString()).startsWith("20");String key=UUID.randomUUID().toString();ok(call("POST","/tasks/"+id+"/cancel",Map.of("reason","本人撤回"),owner,key));ok(call("POST","/tasks/"+id+"/cancel",Map.of("reason","本人撤回"),owner,key));assertThat(db.queryForObject("SELECT COUNT(*) FROM reward_ledger WHERE event_key=?",Integer.class,"RELEASE:"+id)).isEqualTo(1);assertThat(db.queryForObject("SELECT frozen_amount FROM reward_accounts WHERE user_id=? AND reward_type='积分'",java.math.BigDecimal.class,owner.id()).signum()).isZero();
    }
    @Test void taskApplicationProofAndSettlementFormARealHttpChain()throws Exception{
        var owner=register(true);var taker=register(true);var outsider=register(true);String id=task(owner);ok(call("POST","/admin/tasks/"+id+"/publication-review",Map.of("decision","通过","reason","验收"),admin));error(call("POST","/tasks/"+id+"/applications",null,owner),403);String application=ok(call("POST","/tasks/"+id+"/applications",null,taker)).path("application_id").asText();error(call("POST","/tasks/"+id+"/applications",null,taker),409);ok(call("POST","/admin/task-applications/"+application+"/review",Map.of("decision","通过","reason","分配验收"),admin));error(call("GET","/tasks/"+id+"/proofs",null,outsider),404);String ref=upload(taker,"TASK_PROOF"),key=UUID.randomUUID().toString();var body=Map.of("proof_text","已完成合成任务","evidence_refs",List.of(ref));String proof=ok(call("POST","/tasks/"+id+"/proofs",body,taker,key)).path("proof_id").asText();assertThat(ok(call("POST","/tasks/"+id+"/proofs",body,taker,key)).path("proof_id").asText()).isEqualTo(proof);ok(call("POST","/admin/task-proofs/"+proof+"/review",Map.of("decision","补充凭证","reason","补充一版验收材料"),admin));String next=ok(call("POST","/tasks/"+id+"/proofs",body,taker)).path("proof_id").asText();ok(call("POST","/admin/task-proofs/"+next+"/review",Map.of("decision","通过","reason","验收通过"),admin));assertThat(ok(call("GET","/tasks/"+id,null,owner)).path("task_status").asText()).isEqualTo("已完成");assertThat(db.queryForObject("SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=?",Integer.class,id)).isEqualTo(1);assertThat(ok(call("GET","/tasks/"+id+"/proofs",null,taker)).path("items").size()).isEqualTo(2);
    }
    @Test void chatPairCursorReadDeleteAndReportRespectBothParticipants()throws Exception{
        var first=register(false);var second=register(false);var third=register(false);String id=ok(call("POST","/chat-sessions",Map.of("peer_user_id",Long.toString(second.id())),first)).path("session_id").asText();assertThat(ok(call("POST","/chat-sessions",Map.of("peer_user_id",Long.toString(first.id())),second)).path("session_id").asText()).isEqualTo(id);var ids=new ArrayList<String>();for(int i=0;i<4;i++)ids.add(ok(call("POST","/chat-sessions/"+id+"/messages",Map.of("content_type","文本","content","第"+i+"条合成消息"),first)).path("msg_id").asText());var latest=ok(call("GET","/chat-sessions/"+id+"/messages?limit=2",null,second));assertThat(latest.path("has_more").asBoolean()).isTrue();assertThat(latest.path("items").get(0).path("msg_id").asText()).isEqualTo(ids.get(2));var old=ok(call("GET","/chat-sessions/"+id+"/messages?limit=2&before_msg_id="+ids.get(2),null,second));assertThat(old.path("items").get(0).path("msg_id").asText()).isEqualTo(ids.get(0));assertThat(ok(call("GET","/chat-sessions",null,second)).path("items").get(0).path("unread_count").asInt()).isEqualTo(4);error(call("GET","/chat-sessions/"+id+"/messages",null,third),404);error(call("PUT","/messages/"+ids.get(0)+"/read",null,first),403);String read=ok(call("PUT","/messages/"+ids.get(0)+"/read",null,second)).path("read_at").asText();assertThat(ok(call("PUT","/messages/"+ids.get(0)+"/read",null,second)).path("read_at").asText()).isEqualTo(read);ok(call("DELETE","/messages/"+ids.get(0),null,first));assertThat(ok(call("GET","/chat-sessions/"+id+"/messages",null,first)).path("items").size()).isEqualTo(3);assertThat(ok(call("GET","/chat-sessions/"+id+"/messages",null,second)).path("items").size()).isEqualTo(4);String key=UUID.randomUUID().toString();var report=ok(call("POST","/messages/"+ids.get(0)+"/reports",Map.of("reason","仅合成举报验收"),second,key));assertThat(ok(call("POST","/messages/"+ids.get(0)+"/reports",Map.of("reason","仅合成举报验收"),second,key)).path("report_id").asText()).isEqualTo(report.path("report_id").asText());error(call("POST","/messages/"+ids.get(0)+"/reports",Map.of("reason","重复举报"),second),409);
    }
    @Test void nativePutProfileReusesPatchValidationAndKeepsOmittedFields()throws Exception{
        var member=register(false);var other=register(false);
        error(call("PUT","/me",Map.of("personal_bio","x"),null),401);
        error(call("PUT","/me",Map.of(),member),400);
        error(call("PUT","/me",Map.of("role","ADMIN"),member),400);
        error(call("PUT","/me",Map.of("personal_bio","x".repeat(501)),member),400);
        String avatar=upload(member,"AVATAR");
        var body=Map.of("personal_bio","原生资料保存","address","合成地点","privacy_settings",Map.of("show_campus",false,"show_personal_bio",true),"avatar_ref",avatar);
        var saved=ok(call("PUT","/me",body,member));
        assertThat(saved.path("personal_bio").asText()).isEqualTo("原生资料保存");
        assertThat(saved.path("avatar_url").asText()).contains("/file-content/");
        error(call("PUT","/me",Map.of("avatar_ref",avatar),other),403);
        ok(call("PUT","/me",body,member));
        var patched=ok(call("PATCH","/me",Map.of("address","更新合成地点"),member));
        assertThat(patched.path("personal_bio").asText()).isEqualTo("原生资料保存");
        assertThat(patched.path("address").asText()).isEqualTo("更新合成地点");
        assertThat(patched.path("privacy_settings").path("show_personal_bio").asBoolean()).isTrue();
        assertThat(ok(call("GET","/me",null,other)).path("personal_bio").asText()).isNotEqualTo("原生资料保存");
    }
    @Test void imageFilesAndAvatarsCannotBeBorrowedAcrossAccounts()throws Exception{
        var first=register(false);var second=register(false);String avatar=upload(first,"AVATAR");var me=ok(call("PATCH","/me",Map.of("avatar_ref",avatar),first));assertThat(me.path("avatar_url").asText()).contains("/file-content/");error(call("PATCH","/me",Map.of("avatar_ref",avatar),second),403);String id=ok(call("POST","/chat-sessions",Map.of("peer_user_id",Long.toString(second.id())),first)).path("session_id").asText();String ref=upload(first,"CHAT_IMAGE");error(call("POST","/chat-sessions/"+id+"/messages",Map.of("content_type","图片","content",ref),second),403);String msg=ok(call("POST","/chat-sessions/"+id+"/messages",Map.of("content_type","图片","content",ref),first)).path("msg_id").asText();String file=ref.substring(7);ok(call("GET","/files/"+file+"/access",null,second));ok(call("DELETE","/messages/"+msg,null,second));error(call("GET","/files/"+file+"/access",null,second),403);
    }
    @Test void reviewNotificationsAreAtomicPrivateIdempotentAndRespectPreferences()throws Exception {
        var owner=register(false);var outsider=register(false);
        String id=ok(call("POST","/posts",Map.of("category_id",category,"post_title","通知验收","post_content","仅合成审核事件","is_anonymous",false),owner)).path("post_id").asText();
        long before=db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE user_id=?",Long.class,owner.id());
        String key=UUID.randomUUID().toString();var body=Map.of("decision","通过","reason","审核事件验收");
        ok(call("POST","/admin/posts/"+id+"/review",body,admin,key));
        ok(call("POST","/admin/posts/"+id+"/review",body,admin,key));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE user_id=?",Long.class,owner.id())).isEqualTo(before+1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE user_id=?",Long.class,outsider.id())).isZero();
        String notice=db.queryForObject("SELECT notification_id FROM notification_receipts WHERE user_id=?",String.class,owner.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE notification_id=? AND work_status='待执行'",Long.class,notice)).isEqualTo(1);
        error(call("POST","/admin/posts/"+id+"/review",body,admin),409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE user_id=?",Long.class,owner.id())).isEqualTo(before+1);
        db.update("INSERT INTO notification_preferences(user_id,in_app_enabled) VALUES(?,FALSE) ON DUPLICATE KEY UPDATE in_app_enabled=FALSE",owner.id());
        ok(call("PUT","/posts/"+id,Map.of("category_id",category,"post_title","偏好验收","post_content","关闭后不接收","is_anonymous",false),owner));
        ok(call("POST","/admin/posts/"+id+"/review",body,admin));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE user_id=?",Long.class,owner.id())).isEqualTo(before+1);
        var now=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        var start=now.minusHours(1).toLocalTime().withSecond(0).withNano(0);var end=now.plusHours(1).toLocalTime().withSecond(0).withNano(0);
        db.update("UPDATE notification_preferences SET in_app_enabled=TRUE,dnd_start=?,dnd_end=?,config_version=config_version+1 WHERE user_id=?",start,end,owner.id());
        ok(call("PUT","/posts/"+id,Map.of("category_id",category,"post_title","免打扰验收","post_content","结束后投递","is_anonymous",false),owner));
        ok(call("POST","/admin/posts/"+id+"/review",body,admin));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_outbox o JOIN notification_delivery d ON d.delivery_id=o.delivery_id WHERE d.user_id=? AND o.available_at>UTC_TIMESTAMP(3)",Long.class,owner.id())).isEqualTo(1);
    }

    @Test void inconsistentTaskOrderSnapshotMustNotAdvanceProofOrSettlement()throws Exception {
        var owner=register(true);var taker=register(true);String task=task(owner);
        ok(call("POST","/admin/tasks/"+task+"/publication-review",Map.of("decision","通过","reason","白盒快照一致性"),admin));
        String app=ok(call("POST","/tasks/"+task+"/applications",null,taker)).path("application_id").asText();
        String order=ok(call("POST","/admin/task-applications/"+app+"/review",Map.of("decision","通过","reason","分配"),admin)).path("order_id").asText();
        String ref=upload(taker,"TASK_PROOF");var body=Map.of("proof_text","仅合成凭证","evidence_refs",List.of(ref));
        // Corrupt ONLY this synthetic order: internal arithmetic stays valid, frozen reward no longer matches.
        db.update("UPDATE user_orders SET unit_price=21.00,total_amount=21.00 WHERE order_id=?",order);
        try {
            error(call("POST","/tasks/"+task+"/proofs",body,taker),409);
            assertThat(db.queryForObject("SELECT task_status FROM RewardTasks WHERE reward_task_id=?",String.class,task)).isEqualTo("进行中");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM task_proofs WHERE reward_task_id=?",Integer.class,task)).isZero();
        } finally {db.update("UPDATE user_orders SET unit_price=20.00,total_amount=20.00 WHERE order_id=?",order);}
        String proof=ok(call("POST","/tasks/"+task+"/proofs",body,taker)).path("proof_id").asText();
        db.update("UPDATE user_orders SET unit_price=21.00,total_amount=21.00 WHERE order_id=?",order);
        try {
            error(call("POST","/admin/task-proofs/"+proof+"/review",Map.of("decision","通过","reason","故障状态不得结算"),admin),409);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=?",Integer.class,task)).isZero();
        } finally {db.update("UPDATE user_orders SET unit_price=20.00,total_amount=20.00 WHERE order_id=?",order);}
    }
    @Test void notificationDependencyFailureRollsBackReviewAndReplayRecord()throws Exception {
        var owner=register(false);String post=ok(call("POST","/posts",Map.of("category_id",category,"post_title","事务回滚","post_content","合成审核失败","is_anonymous",true),owner)).path("post_id").asText();
        db.update("INSERT INTO notification_preferences(user_id,dnd_start,dnd_end) VALUES(?,'12:00:00','12:00:00')",owner.id());
        String key=UUID.randomUUID().toString();var body=Map.of("decision","通过","reason","验证依赖故障回滚");
        try {
            error(call("POST","/admin/posts/"+post+"/review",body,admin,key),503);
            assertThat(db.queryForObject("SELECT post_status FROM post_info WHERE post_id=?",String.class,post)).isEqualTo("待审核");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_idempotency WHERE request_key=?",Integer.class,key)).isZero();
        }finally{db.update("DELETE FROM notification_preferences WHERE user_id=?",owner.id());}
        ok(call("POST","/admin/posts/"+post+"/review",body,admin,key));
    }

}
