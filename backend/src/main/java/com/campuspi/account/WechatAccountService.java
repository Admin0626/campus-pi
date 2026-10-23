package com.campuspi.account;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WechatAccountService {
    private final AccountService accounts;
    private final WechatIdentityClient wechat;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public WechatAccountService(AccountService accounts,WechatIdentityClient wechat,JdbcTemplate db,TransactionTemplate tx) {
        this.accounts=accounts;this.wechat=wechat;this.db=db;this.tx=tx;
    }
    public Object capabilities() {return Map.of("available",wechat.available());}
    public Object status(AccountService.Identity actor) {
        return accounts.sessionTransaction(actor,()->{
            if(!wechat.available()) return Map.of("available",false);
            var rows=db.queryForList("SELECT bound_at FROM account_wechat_bindings WHERE app_id=? AND user_id=?",wechat.appId(),actor.id());
            if(rows.isEmpty()) return Map.of("bound",false,"available",true);
            Object value=rows.get(0).get("bound_at");
            Instant time=value instanceof Timestamp stamp?stamp.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC);
            return Map.of("bound",true,"available",true,"bound_at",time.toString());
        });
    }
    private WechatIdentityClient.Identity exchange(JsonNode body,String source) {
        AccountService.fields(body,"code");
        String code=AccountService.text(body,"code",1,256,false);
        if(!code.matches("[A-Za-z0-9_-]+")) throw new ApiException(400,"微信登录凭证格式错误","code");
        wechat.requireAvailable();accounts.consumeWechatAttempt(source);
        return wechat.exchange(code);
    }
    public Object bind(AccountService.Identity actor,JsonNode body,String source) {
        var identity=exchange(body,source);
        for(int attempt=0;;attempt++) try {
            // Recheck session after the round trip: logout/disable may race with it.
            return accounts.sessionTransaction(actor,()->{
                // actor() already locks this user's row, serializing all of their binds.
                // Locking a missing secondary-index row here creates overlapping gap locks
                // for different users. Let the unique keys arbitrate cross-user claims.
                var current=db.queryForList("SELECT open_id FROM account_wechat_bindings WHERE app_id=? AND user_id=?",identity.appId(),actor.id());
                if(!current.isEmpty()) {
                    if(!identity.openId().equals(current.get(0).get("open_id"))) throw conflict();
                    return Map.of("bound",true);
                }
                db.update("INSERT INTO account_wechat_bindings(app_id,open_id,user_id,bound_at) VALUES(?,?,?,?)",identity.appId(),identity.openId(),actor.id(),Timestamp.from(Instant.now()));
                return Map.of("bound",true);
            });
        } catch(DuplicateKeyException ex) {throw conflict();}
          catch(PessimisticLockingFailureException ex) {
            // Retry only the rolled-back DB transaction, never the consumed WeChat code.
            if(attempt>=2) throw new ApiException(503,"绑定服务繁忙，请查询绑定状态后重试");
          }
    }
    public Object login(JsonNode body,String source) {
        var identity=exchange(body,source);
        var snapshot=db.queryForList("SELECT user_id FROM account_wechat_bindings WHERE app_id=? AND open_id=?",identity.appId(),identity.openId());
        if(snapshot.isEmpty()) throw notBound();
        long userId=((Number)snapshot.get(0).get("user_id")).longValue();
        return tx.execute(status->{
            // Same lock order as authenticated writes: user before binding/session.
            db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",userId);
            var latest=db.queryForList("SELECT user_id FROM account_wechat_bindings WHERE app_id=? AND open_id=? FOR UPDATE",identity.appId(),identity.openId());
            if(latest.isEmpty() || ((Number)latest.get(0).get("user_id")).longValue()!=userId) throw notBound();
            return accounts.issueWechatSession(userId);
        });
    }
    private static ApiException notBound() {return new ApiException(403,"此微信尚未绑定校园账号，请先用账号密码登录后主动绑定",null,"WECHAT_NOT_BOUND");}
    private static ApiException conflict() {return new ApiException(409,"绑定关系已存在，不能替换或绑定其他账号",null,"WECHAT_BINDING_CONFLICT");}
}
