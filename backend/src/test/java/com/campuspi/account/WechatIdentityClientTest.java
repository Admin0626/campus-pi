package com.campuspi.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class WechatIdentityClientTest {
    private final HttpClient http=mock(HttpClient.class);
    private final String appId="wx"+"0".repeat(16);
    private WechatIdentityClient client(boolean enabled,String secret) {return new WechatIdentityClient(new ObjectMapper(),enabled,appId,secret,http);}
    @SuppressWarnings("unchecked")
    private void reply(int status,String body) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
    }
    @Test void unconfiguredNeverCallsWechat() {
        for(var client:new WechatIdentityClient[]{client(false,"test-only-secret"),client(true,"")}) {
            assertThat(client.available()).isFalse();
            assertThatThrownBy(()->client.exchange("code")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(501));
        }
        verifyNoInteractions(http);
    }
    @Test void sendsCodeOnlyToFixedHttpsOriginAndDiscardsSessionKey() throws Exception {
        reply(200,"{\"openid\":\"synthetic_openid\",\"session_key\":\"test-session-key\"}");
        var identity=client(true,"test-only-secret").exchange("single-use-code");
        assertThat(identity.openId()).isEqualTo("synthetic_openid");
        assertThat(new ObjectMapper().writeValueAsString(identity)).doesNotContain("session_key","test-session-key","test-only-secret");
        var argument=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(argument.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(argument.getValue().uri().getScheme()).isEqualTo("https");
        assertThat(argument.getValue().uri().getHost()).isEqualTo("api.weixin.qq.com");
        assertThat(argument.getValue().uri().getPath()).isEqualTo("/sns/jscode2session");
        assertThat(argument.getValue().uri().getQuery()).contains("js_code=single-use-code","grant_type=authorization_code");
        assertThat(argument.getValue().timeout()).isPresent();
    }
    @ParameterizedTest @ValueSource(ints={40029,40163})
    void invalidAndReplayedCodesHaveSafeErrors(int code) throws Exception {
        reply(200,"{\"errcode\":"+code+",\"errmsg\":\"sensitive provider details\"}");
        assertThatThrownBy(()->client(true,"test-only-secret").exchange("code"))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status).isEqualTo(400);assertThat(e.code).isEqualTo("WECHAT_CODE_INVALID");})
            .hasMessageNotContaining("sensitive provider details");
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","not JSON","{}","{\"errcode\":-1,\"errmsg\":\"private\"}","{\"openid\":\"x\"}","{\"openid\":\"x\",\"session_key\":\"\",\"errcode\":\"oops\"}"})
    void malformedOrProviderFailuresAreSanitized(String body) throws Exception {
        reply(200,body);
        assertThatThrownBy(()->client(true,"test-only-secret").exchange("code"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503)).hasMessageNotContaining("private");
    }
    @Test void networkFailureDoesNotExposeRequestUrl() throws Exception {
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException("secret query string"));
        assertThatThrownBy(()->client(true,"test-only-secret").exchange("code")).isInstanceOf(ApiException.class).hasMessageNotContaining("secret query string");
    }
}
