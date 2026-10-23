package com.campuspi.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real HTTP + verified isolated MySQL. Only the external WeChat provider is mocked. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campus.notifications.worker-enabled=false","campus.admin-batches.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="CAMPUS_WECHAT_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatAccountIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        com.campuspi.testing.IntegrationDatabase.configure(registry);
    }
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    @MockitoBean WechatIdentityClient wechat;
    final String appId="wx"+"0".repeat(16);
    final Map<String,String> codes=new ConcurrentHashMap<>();
    record Member(long id,String token) {}
    @BeforeAll void verifyDatabaseBeforeWrites() {
        com.campuspi.testing.IntegrationDatabase.verify(db);
    }
    @BeforeEach void provider() {
        codes.clear();when(wechat.available()).thenReturn(true);when(wechat.appId()).thenReturn(appId);
        when(wechat.exchange(anyString())).thenAnswer(call->{
            String openId=codes.remove(call.getArgument(0));
            if(openId==null) throw new ApiException(400,"测试凭证已使用或无效","code","WECHAT_CODE_INVALID");
            return new WechatIdentityClient.Identity(appId,openId);
        });
    }
    ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        if(path.equals("/auth/register")) headers.set("Idempotency-Key",UUID.randomUUID().toString());
        return rest.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");
    }
    String code(String openId) {String key=UUID.randomUUID().toString();codes.put(key,openId);return key;}
    Member member() {
        String name="wxcase_"+UUID.randomUUID().toString().replace("-","").substring(0,16),password=UUID.randomUUID()+"!Aa1";
        long id=ok(call(HttpMethod.POST,"/auth/register",Map.of("username",name,"password",password,"email",name+"@example.invalid"),null)).path("user_id").asLong();
        String token=ok(call(HttpMethod.POST,"/auth/login",Map.of("account",name,"password",password),null)).path("access_token").asText();
        assertThat(id).isPositive();assertThat(token.length()).isEqualTo(43);return new Member(id,token);
    }
    ResponseEntity<JsonNode> bind(Member member,String openId) {return call(HttpMethod.POST,"/me/wechat",Map.of("code",code(openId)),member.token());}
    String oid() {return "test_"+UUID.randomUUID().toString().replace("-","");}
    @Test void configurationAndAnonymousBindingAreSeparated() {
        var config=call(HttpMethod.GET,"/auth/wechat/config",null,null);
        assertThat(ok(config).path("available").asBoolean()).isTrue();assertThat(config.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(call(HttpMethod.GET,"/me/wechat",null,null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST,"/me/wechat",Map.of("code","anything"),null).getStatusCode().value()).isEqualTo(401);
        verify(wechat,never()).exchange(anyString());
    }
    @Test void unboundWechatDoesNotCreateAnAccount() {
        long before=db.queryForObject("SELECT COUNT(*) FROM users",Long.class);
        var response=call(HttpMethod.POST,"/auth/wechat/login",Map.of("code",code(oid())),null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);assertThat(response.getBody().path("code").asText()).isEqualTo("WECHAT_NOT_BOUND");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(before);
    }
    @Test void bindingLoginAndLogoutUseRealOpaqueSessions() {
        var member=member();String openId=oid();
        assertThat(ok(call(HttpMethod.GET,"/me/wechat",null,member.token())).path("bound").asBoolean()).isFalse();
        assertThat(ok(bind(member,openId)).path("bound").asBoolean()).isTrue();
        JsonNode binding=ok(call(HttpMethod.GET,"/me/wechat",null,member.token()));
        assertThat(binding.path("bound").asBoolean()).isTrue();assertThat(binding.path("bound_at").asText()).isNotBlank();assertThat(binding.toString()).doesNotContain(openId,appId,"session_key");
        var login=ok(call(HttpMethod.POST,"/auth/wechat/login",Map.of("code",code(openId)),null));
        String token=login.path("access_token").asText();assertThat(token.length()).isEqualTo(43);assertThat(token.equals(member.token())).isFalse();
        assertThat(login.path("expires_in").asInt()).isEqualTo(7200);assertThat(login.path("user").path("user_id").asLong()).isEqualTo(member.id());
        assertThat(ok(call(HttpMethod.GET,"/me",null,token)).path("user_id").asLong()).isEqualTo(member.id());
        ok(call(HttpMethod.POST,"/auth/logout",null,token));assertThat(call(HttpMethod.GET,"/me",null,token).getStatusCode().value()).isEqualTo(401);
    }
    @Test void ownershipCannotBeReplacedOrStolen() {
        var first=member();var second=member();String openId=oid();ok(bind(first,openId));ok(bind(first,openId));
        assertThat(bind(second,openId).getStatusCode().value()).isEqualTo(409);assertThat(bind(first,oid()).getStatusCode().value()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_wechat_bindings WHERE app_id=? AND open_id=? AND user_id=?",Integer.class,appId,openId,first.id())).isEqualTo(1);
    }
    @Test void rejectsClientIdentitiesAndReplayedCodes() {
        var member=member();String value=code(oid());
        assertThat(call(HttpMethod.POST,"/me/wechat",Map.of("code",value,"openid","forged"),member.token()).getStatusCode().value()).isEqualTo(400);
        ok(call(HttpMethod.POST,"/me/wechat",Map.of("code",value),member.token()));
        assertThat(call(HttpMethod.POST,"/auth/wechat/login",Map.of("code",value),null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST,"/auth/wechat/login",Map.of("code","bad code"),null).getStatusCode().value()).isEqualTo(400);
    }
    @Test void disableBlocksWechatLoginAndRevocationDuringExchangeBlocksBinding() {
        var member=member();String openId=oid();ok(bind(member,openId));
        db.update("UPDATE users SET account_status='DISABLED',auth_version=auth_version+1 WHERE user_id=?",member.id());
        assertThat(call(HttpMethod.POST,"/auth/wechat/login",Map.of("code",code(openId)),null).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET,"/me",null,member.token()).getStatusCode().value()).isEqualTo(401);
        var another=member();String raceCode="revoke_"+UUID.randomUUID();
        doAnswer(call->{db.update("UPDATE auth_sessions SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=?",another.id());return new WechatIdentityClient.Identity(appId,oid());}).when(wechat).exchange(raceCode);
        assertThat(call(HttpMethod.POST,"/me/wechat",Map.of("code",raceCode),another.token()).getStatusCode().value()).isEqualTo(401);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_wechat_bindings WHERE user_id=?",Integer.class,another.id())).isZero();
    }
    @RepeatedTest(3) void concurrentClaimHasOneWinner() throws Exception {
        var first=member();var second=member();String openId=oid();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            var one=pool.submit(()->{start.await();return bind(first,openId).getStatusCode().value();});
            var two=pool.submit(()->{start.await();return bind(second,openId).getStatusCode().value();});
            start.countDown();assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM account_wechat_bindings WHERE app_id=? AND open_id=?",Integer.class,appId,openId)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentDifferentIdentitiesCannotReplaceOneUsersBinding() throws Exception {
        var member=member();String first=oid(),second=oid();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            var one=pool.submit(()->{start.await();return bind(member,first).getStatusCode().value();});
            var two=pool.submit(()->{start.await();return bind(member,second).getStatusCode().value();});
            start.countDown();assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM account_wechat_bindings WHERE app_id=? AND user_id=?",Integer.class,appId,member.id())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void exchangeAttemptsAreRateLimitedBeforeCallingProvider() {
        String source="integration-"+UUID.randomUUID();
        for(int i=0;i<30;i++) accounts.consumeWechatAttempt(source);
        assertThatThrownBy(()->accounts.consumeWechatAttempt(source)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(429));
    }
    @Test void concurrentAttemptsKeepTheExactLimitWithoutDatabaseFailures() throws Exception {
        String source="parallel-limit-"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(4);
        try {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<40;i++) futures.add(pool.submit(()->{
                start.await();try {accounts.consumeWechatAttempt(source);return 200;}
                catch(ApiException ex){return ex.status;}
            }));
            start.countDown();var responses=new ArrayList<Integer>();
            for(var future:futures)responses.add(future.get(15,TimeUnit.SECONDS));
            assertThat(Collections.frequency(responses,200)).isEqualTo(30);
            assertThat(Collections.frequency(responses,429)).isEqualTo(10);
        } finally {pool.shutdownNow();}
    }
}
