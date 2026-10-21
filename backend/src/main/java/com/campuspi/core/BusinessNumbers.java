package com.campuspi.core;

import com.campuspi.account.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BusinessNumbers {
    private final JdbcTemplate db;
    public BusinessNumbers(JdbcTemplate db){this.db=db;}
    /** Caller must create the resource in this same transaction; never reserve outside it. */
    public String nextOrder() {
        return next("ORDER", "user_orders", "order_id");
    }
    public String nextAftersale() {
        return next("AFTER", "order_aftersales", "aftersale_id");
    }
    private String next(String kind, String table, String column) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Number allocation requires an active transaction");
        // Contract: ORDER/AFTER + UTC yyMMdd + eight-digit sequence. Legacy IDs remain untouched.
        String day=java.time.LocalDate.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd",java.util.Locale.ROOT));
        String key=kind+":"+day,prefix=kind+day;
        db.update("INSERT IGNORE INTO business_number_sequences(number_type,counter_value) VALUES(?,0)",key);
        var rows=db.queryForList("SELECT counter_value FROM business_number_sequences WHERE number_type=? FOR UPDATE",key);
        long previous=((Number)rows.get(0).get("counter_value")).longValue();
        // Identifiers come only from the two fixed Java call sites, never from requests.
        long existing=db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING("+column+",12) AS UNSIGNED)),0) FROM "+table+" WHERE "+column+" REGEXP ?",Long.class,"^"+prefix+"[0-9]{8}$");
        long value=Math.max(previous,existing);
        if(value>=99_999_999L)throw new ApiException(503,"当日业务编号空间已用尽");
        db.update("UPDATE business_number_sequences SET counter_value=? WHERE number_type=?",value+1,key);
        return prefix+String.format(java.util.Locale.ROOT,"%08d",value+1);
    }
}
