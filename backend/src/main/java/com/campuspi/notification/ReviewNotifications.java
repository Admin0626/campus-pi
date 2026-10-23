package com.campuspi.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal review events share the review transaction and the ordinary preference/outbox pipeline. */
@Service
public class ReviewNotifications {
    private final JdbcTemplate db;
    private final NotificationPreferencePolicy preferences;
    public ReviewNotifications(JdbcTemplate db, NotificationPreferencePolicy preferences) {
        this.db=db; this.preferences=preferences;
    }
    public void send(long reviewer, long recipient, String object, Object objectId, String result, String reason) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Review event requires transaction");
        var decision=preferences.lock(recipient,Instant.now());
        // The confirmed rule excludes opt-outs, including audit/security events.
        if(!decision.enabled())return;
        String id="REVIEW_"+UUID.randomUUID().toString().replace("-","");
        String content=object+"（编号 "+objectId+"）处理结果："+result+"。原因："+reason;
        db.update("INSERT INTO system_notifications(notification_id,notification_title,notification_content,notification_type,publisher_id,target_scope,target_filter,publish_status) VALUES(?,?,?,'审核',?,'指定用户',?,'待发送')",id,object+"处理结果",content,reviewer,"{\"user_ids\":[\""+recipient+"\"]}");
        db.update("INSERT INTO notification_receipts(notification_id,user_id) VALUES(?,?)",id,recipient);
        db.update("INSERT INTO notification_publish_jobs(job_id,notification_id,recipient_count) VALUES(?,?,1)","job_"+UUID.randomUUID().toString().replace("-",""),id);
        db.update("INSERT INTO notification_delivery(notification_id,user_id,channel,attempt_no) VALUES(?,?,'站内',1)",id,recipient);
        long delivery=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO notification_outbox(delivery_id,notification_id,available_at,preference_version) VALUES(?,?,?,?)",delivery,id,Timestamp.from(decision.availableAt()),decision.version());
    }
}
