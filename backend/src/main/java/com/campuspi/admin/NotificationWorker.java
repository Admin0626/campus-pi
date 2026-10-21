package com.campuspi.admin;

import java.util.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Database work rows are claimed under their notification lock, without a volatile queue or lease.
 * A process exit rolls back the claim and delivery; another instance can resume the same work row.
 */
@Component
@ConditionalOnProperty(name="campus.notifications.worker-enabled",havingValue="true")
public class NotificationWorker {
    private static final Logger log=LoggerFactory.getLogger(NotificationWorker.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public NotificationWorker(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;this.tx=new TransactionTemplate(manager);}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    @Scheduled(fixedDelayString="${campus.notifications.poll-interval-ms:2000}")
    public void poll(){
        try {
            // Invalid snapshots stay inspectable without starving healthy jobs behind a bounded batch.
            for(var row:db.queryForList("SELECT j.notification_id FROM notification_publish_jobs j WHERE j.job_status='待执行' AND j.recipient_count=(SELECT COUNT(*) FROM notification_receipts r WHERE r.notification_id=j.notification_id) ORDER BY j.created_at,j.job_id LIMIT 20")){
                try {activate(row.get("notification_id").toString());}
                catch(RuntimeException ex){log.warn("A notification snapshot could not be activated; it remains pending for inspection.");}
            }
            for(var row:db.queryForList("SELECT o.delivery_id,o.notification_id FROM notification_outbox o JOIN system_notifications n ON n.notification_id=o.notification_id WHERE o.work_status='待执行' AND n.publish_status IN ('已发送','已撤回') ORDER BY o.delivery_id LIMIT 100"))deliver(row.get("notification_id").toString(),((Number)row.get("delivery_id")).longValue());
        } catch(RuntimeException ex){log.warn("Notification worker paused this poll because a durable dependency was unavailable; work remains recoverable.");}
    }
    private void activate(String id){
        tx.executeWithoutResult(status->{
            var notification=one("SELECT publish_status FROM system_notifications WHERE notification_id=? FOR UPDATE",id);
            var job=one("SELECT recipient_count,job_status FROM notification_publish_jobs WHERE notification_id=? FOR UPDATE",id);
            if(notification==null||job==null||!"待执行".equals(job.get("job_status")))return;
            if("已撤回".equals(notification.get("publish_status"))){db.update("UPDATE notification_publish_jobs SET job_status='已取消',completed_at=UTC_TIMESTAMP(3) WHERE notification_id=?",id);return;}
            if(!"待发送".equals(notification.get("publish_status")))return;
            long actual=db.queryForObject("SELECT COUNT(*) FROM notification_receipts WHERE notification_id=?",Long.class,id);
            if(actual!=((Number)job.get("recipient_count")).longValue())throw new IllegalStateException("Durable notification snapshot mismatch");
            db.update("UPDATE system_notifications SET publish_status='已发送',publish_time=UTC_TIMESTAMP(3) WHERE notification_id=?",id);
            db.update("UPDATE notification_publish_jobs SET job_status='已完成',completed_at=UTC_TIMESTAMP(3) WHERE notification_id=?",id);
        });
    }
    private void deliver(String id,long deliveryId){
        try {
            tx.executeWithoutResult(status->{
                var notification=one("SELECT publish_status FROM system_notifications WHERE notification_id=? FOR UPDATE",id);
                var work=one("SELECT work_status FROM notification_outbox WHERE delivery_id=? AND notification_id=? FOR UPDATE",deliveryId,id);
                if(notification==null||work==null||!"待执行".equals(work.get("work_status")))return;
                var attempt=one("SELECT d.channel,d.delivery_status FROM notification_delivery d JOIN notification_receipts r ON r.notification_id=d.notification_id AND r.user_id=d.user_id WHERE d.delivery_id=? AND d.notification_id=? FOR UPDATE",deliveryId,id);
                if(attempt==null)throw new IllegalStateException("Durable notification relationship mismatch");
                if(!"待发送".equals(attempt.get("delivery_status"))){db.update("UPDATE notification_outbox SET work_status='已完成',completed_at=UTC_TIMESTAMP(3) WHERE delivery_id=?",deliveryId);return;}
                if("已撤回".equals(notification.get("publish_status"))){db.update("UPDATE notification_delivery SET delivery_status='失败',failure_reason='通知已撤回，未执行的投递已取消' WHERE delivery_id=?",deliveryId);db.update("UPDATE notification_outbox SET work_status='已取消',completed_at=UTC_TIMESTAMP(3) WHERE delivery_id=?",deliveryId);return;}
                if(!"已发送".equals(notification.get("publish_status")))return;
                // Only durable in-app storage is implemented; never claim an external provider succeeded.
                if(!"站内".equals(attempt.get("channel")))throw new IllegalStateException("External delivery is not enabled");
                db.update("UPDATE notification_delivery SET delivery_status='成功',sent_at=UTC_TIMESTAMP(3),failure_reason=NULL WHERE delivery_id=?",deliveryId);
                db.update("UPDATE notification_outbox SET work_status='已完成',completed_at=UTC_TIMESTAMP(3) WHERE delivery_id=?",deliveryId);
            });
        } catch(RuntimeException ex){
            // Keep the old failed attempt for inspection; operator retry creates a new numbered attempt.
            tx.executeWithoutResult(status->{
                var notification=one("SELECT publish_status FROM system_notifications WHERE notification_id=? FOR UPDATE",id);
                var work=one("SELECT work_status FROM notification_outbox WHERE delivery_id=? AND notification_id=? FOR UPDATE",deliveryId,id);
                if(notification==null||work==null||!"待执行".equals(work.get("work_status")))return;
                db.update("UPDATE notification_delivery SET delivery_status='失败',failure_reason='站内投递未完成，请核对依赖后重试' WHERE delivery_id=? AND notification_id=? AND delivery_status='待发送'",deliveryId,id);
                db.update("UPDATE notification_outbox SET work_status='已完成',completed_at=UTC_TIMESTAMP(3) WHERE delivery_id=?",deliveryId);
            });
        }
    }
}
