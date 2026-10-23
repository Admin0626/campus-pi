package com.campuspi.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Backend-only code exchange. session_key and provider errors are never exposed. */
@Component
public class WechatIdentityClient {
    public record Identity(String appId,String openId) {}
    private final boolean enabled;
    private final String appId, appSecret;
    private final HttpClient http;
    private final ObjectMapper json;
    @Autowired
    public WechatIdentityClient(ObjectMapper json,
            @Value("${campus.wechat.enabled:false}") boolean enabled,
            @Value("${campus.wechat.app-id:}") String appId,
            @Value("${campus.wechat.app-secret:}") String appSecret) {
        this(json,enabled,appId,appSecret,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    WechatIdentityClient(ObjectMapper json,boolean enabled,String appId,String appSecret,HttpClient http) {
        this.json=json;this.enabled=enabled;this.appId=appId;this.appSecret=appSecret;this.http=http;
    }
    public boolean available() {return enabled && appId.matches("wx[0-9a-fA-F]{16}") && !appSecret.isBlank();}
    String appId() {return appId;}
    public void requireAvailable() {
        if(!available()) throw new ApiException(501,"微信登录尚未配置，请先使用账号密码登录");
    }
    public Identity exchange(String code) {
        requireAvailable();
        String query="appid="+escape(appId)+"&secret="+escape(appSecret)+"&js_code="+escape(code)+"&grant_type=authorization_code";
        // Fixed HTTPS origin. No configurable endpoint that could receive credentials.
        var request=HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/sns/jscode2session?"+query))
                .timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build();
        final HttpResponse<String> response;
        try {response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception ex) {throw unavailable();}
        if(response.statusCode()!=200 || response.body()==null || response.body().length()>16384) throw unavailable();
        final JsonNode body;
        try {body=json.readTree(response.body());} catch(Exception ex) {throw unavailable();}
        if(body==null || !body.isObject()) throw unavailable();
        JsonNode errorNode=body.get("errcode");
        if(errorNode!=null && (!errorNode.isIntegralNumber() || !errorNode.canConvertToInt())) throw unavailable();
        int error=body.path("errcode").asInt(0);
        if(error==40029 || error==40163) throw new ApiException(400,"微信登录凭证无效或已过期，请重新点击","code","WECHAT_CODE_INVALID");
        if(error!=0) throw unavailable();
        JsonNode openId=body.get("openid"),sessionKey=body.get("session_key");
        if(openId==null || !openId.isTextual() || !openId.asText().matches("[A-Za-z0-9_-]{1,128}") || sessionKey==null || !sessionKey.isTextual() || sessionKey.asText().isBlank()) throw unavailable();
        return new Identity(appId,openId.asText());
    }
    private static String escape(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static ApiException unavailable() {return new ApiException(503,"微信身份服务暂时不可用，请稍后重试");}
}
