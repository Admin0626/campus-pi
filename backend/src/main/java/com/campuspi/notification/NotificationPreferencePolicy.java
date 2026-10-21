package com.campuspi.notification;

import com.campuspi.account.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Inbox delivery policy, including security notifications, per the user's 2026-10-21 decision. */
@Component
public class NotificationPreferencePolicy {
    private final JdbcTemplate db;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public record Decision(boolean enabled,Instant availableAt,long version) {}
    public NotificationPreferencePolicy(JdbcTemplate db){this.db=db;}
    private LocalTime clock(Object raw){return raw==null?null:raw instanceof java.sql.Time t?t.toLocalTime():LocalTime.parse(raw.toString());}
    public Decision lock(long userId,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Preference evaluation requires a transaction");
        var rows=db.queryForList("SELECT in_app_enabled,dnd_start,dnd_end,config_version FROM notification_preferences WHERE user_id=? FOR UPDATE",userId);
        if(rows.isEmpty())return new Decision(true,now,0);
        var row=rows.get(0);Object flag=row.get("in_app_enabled");boolean enabled=flag instanceof Boolean b?b:((Number)flag).intValue()!=0;
        return new Decision(enabled,nextAllowed(now,clock(row.get("dnd_start")),clock(row.get("dnd_end"))),((Number)row.get("config_version")).longValue());
    }
    /** Pure boundary calculation. Real runtime supplies the actual UTC instant; no test clock override. */
    public static Instant nextAllowed(Instant now,LocalTime start,LocalTime end){
        if(start==null&&end==null)return now;
        if(start==null||end==null||start.equals(end))throw new ApiException(503,"通知免打扰设置需要核对");
        var local=now.atZone(ZONE);var current=local.toLocalTime();var date=local.toLocalDate();
        if(start.isBefore(end))return !current.isBefore(start)&&current.isBefore(end)?date.atTime(end).atZone(ZONE).toInstant():now;
        if(!current.isBefore(start))return date.plusDays(1).atTime(end).atZone(ZONE).toInstant();
        return current.isBefore(end)?date.atTime(end).atZone(ZONE).toInstant():now;
    }
}
