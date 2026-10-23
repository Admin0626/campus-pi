package com.campuspi.member;

import com.campuspi.account.*;
import com.campuspi.admin.AdminTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.campuspi.member.MemberSupport.*;

@Service
public class MemberTaskService {
    private final MemberSupport m;private final AdminTaskService tasks;
    @Value("${campus.tasks.synthetic-cash-enabled:false}") private boolean syntheticCash;
    private static final String[] STATES={"待审核","待接取","进行中","待验收","已完成","未完成","已驳回","已取消"};
    public MemberTaskService(MemberSupport m,AdminTaskService tasks){this.m=m;this.tasks=tasks;}
    public Object list(AccountService.Identity actor,int page,int size,String campus,String state,String type,String keyword,String side){
        limited(campus,50);limited(keyword,100);choice(type,"积分","现金");String where=" WHERE ";var args=new ArrayList<Object>();
        if(side.isEmpty()){choice(state,"待接取","进行中","待验收","已完成");where+="t.task_status IN ('待接取','进行中','待验收','已完成') AND u.account_status='ACTIVE'";}
        else{choice(side,"published","accepted");choice(state,STATES);where+=(side.equals("published")?"t.publisher_id=?":"t.taker_id=?");args.add(actor.id());}
        if(!campus.isEmpty()){where+=" AND t.campus=?";args.add(campus);}if(!state.isEmpty()){where+=" AND t.task_status=?";args.add(state);}if(!type.isEmpty()){where+=" AND t.reward_type=?";args.add(type);}if(!keyword.isBlank()){where+=" AND LOCATE(?,t.task_name)>0";args.add(keyword.trim());}
        return m.page("RewardTasks t JOIN users u ON u.user_id=t.publisher_id","t.*",where,args,page,size,"t.publish_time DESC,t.reward_task_id DESC",row->tasks.taskView(row,actor));
    }
    public Object publish(AccountService.Identity actor,JsonNode body,String key){
        AccountService.fields(body,"task_name","task_description","campus","reward_amount","reward_type");String name=text(body,"task_name",1,100),description=text(body,"task_description",1,5000),campus=text(body,"campus",1,50),type=text(body,"reward_type",1,10);choice(type,"积分","现金");BigDecimal amount=amount(body,"reward_amount");if(amount.signum()<=0||type.equals("积分")&&amount.stripTrailingZeros().scale()>0)throw new ApiException(400,"任务奖励须大于零，积分须为整数");if(type.equals("现金")&&!syntheticCash)throw new ApiException(501,"现金奖励尚未接通真实资金渠道");
        return m.accounts.memberMutation(actor,"task-create",key,body,()->{
            m.eligible(actor.id(),campus);var balance=m.one("SELECT * FROM reward_accounts WHERE user_id=? AND reward_type=? FOR UPDATE",actor.id(),type);
            if(balance==null||((BigDecimal)balance.get("available_amount")).compareTo(amount)<0)throw new ApiException(409,"可用奖励余额不足",null,"INSUFFICIENT_REWARD");if(((BigDecimal)balance.get("frozen_amount")).add(amount).compareTo(new BigDecimal("999999999999.99"))>0)throw new ApiException(409,"冻结余额超过上限");
            long id=m.insert("INSERT INTO RewardTasks(publisher_id,task_name,task_description,campus,reward_amount,reward_type) VALUES(?,?,?,?,?,?)",actor.id(),name,description,campus,amount,type);
            m.db.update("UPDATE reward_accounts SET available_amount=available_amount-?,frozen_amount=frozen_amount+? WHERE user_id=? AND reward_type=?",amount,amount,actor.id(),type);
            m.db.update("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(?,?,?,?,'冻结',?,?)","FREEZE:"+id,id,actor.id(),type,amount.negate(),amount);
            log(actor,id,"发布",null,"待审核","发布任务并冻结奖励");return tasks.taskView(m.require("RewardTasks","reward_task_id",id,false),actor);
        });
    }
    private void log(AccountService.Identity actor,long id,String action,String before,String after,String reason){m.db.update("INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(?,?,?,?,?,?)",id,actor.id(),action,before,after,reason);}
    public Object apply(AccountService.Identity actor,long id,String key){return m.accounts.memberMutation(actor,"task-apply:"+id,key,Map.of(),()->{
        if(actor.admin())throw new ApiException(403,"接取申请仅向普通用户开放");var task=m.require("RewardTasks","reward_task_id",id,true);if(number(task,"publisher_id")==actor.id())throw new ApiException(403,"不能接取本人任务");if(!"待接取".equals(task.get("task_status")))throw new ApiException(409,"任务当前不可接取");m.eligible(actor.id(),string(task.get("campus")));m.eligible(number(task,"publisher_id"),string(task.get("campus")));
        if(m.one("SELECT application_id FROM task_applications WHERE reward_task_id=? AND applicant_id=?",id,actor.id())!=null)throw new ApiException(409,"已申请过此任务，撤回后也不能再次申请",null,"UNIQUE_CONFLICT");long application=m.insert("INSERT INTO task_applications(reward_task_id,applicant_id) VALUES(?,?)",id,actor.id());log(actor,id,"接取申请","待接取","待接取","提交申请，等待管理员分配");return tasks.applicationView(m.require("task_applications","application_id",application,false));
    });}
    public Object applications(AccountService.Identity actor,int page,int size,String state){choice(state,"待审核","已通过","已驳回","已取消");var args=new ArrayList<Object>();args.add(actor.id());String where=" WHERE applicant_id=?";if(!state.isEmpty()){where+=" AND application_status=?";args.add(state);}return m.page("task_applications","*",where,args,page,size,"applied_at DESC,application_id DESC",tasks::applicationView);}
    public Object cancelApplication(AccountService.Identity actor,long id,JsonNode body,String key){AccountService.fields(body,"reason");String reason=text(body,"reason",1,500);return m.accounts.memberMutation(actor,"application-cancel:"+id,key,body,()->{
        var initial=m.require("task_applications","application_id",id,false);if(number(initial,"applicant_id")!=actor.id())throw new ApiException(404,"申请不存在");long taskId=number(initial,"reward_task_id");var task=m.require("RewardTasks","reward_task_id",taskId,true);var application=m.require("task_applications","application_id",id,true);if(!"待接取".equals(task.get("task_status"))||!"待审核".equals(application.get("application_status")))throw new ApiException(409,"申请已处理或任务已分配");m.db.update("UPDATE task_applications SET application_status='已取消',audit_reason=? WHERE application_id=?",reason,id);log(actor,taskId,"撤回申请","待接取","待接取",reason);return tasks.applicationView(m.require("task_applications","application_id",id,false));
    });}
    public Object proof(AccountService.Identity actor,long id,JsonNode body,String key){AccountService.fields(body,"proof_text","evidence_refs");String text=text(body,"proof_text",1,1000);var refs=m.files.parseRefs(body.get("evidence_refs"));return m.accounts.memberMutation(actor,"task-proof:"+id,key,body,()->{
        var task=m.require("RewardTasks","reward_task_id",id,true);if(task.get("taker_id")==null||number(task,"taker_id")!=actor.id())throw new ApiException(404,"任务不存在或并非实际接取者");if(!"进行中".equals(task.get("task_status")))throw new ApiException(409,"只能为进行中的任务提交凭证");m.eligible(actor.id(),string(task.get("campus")));var order=m.one("SELECT * FROM user_orders WHERE reward_task_id=? FOR UPDATE",id);if(order==null||!"任务".equals(order.get("order_type"))||!"进行中".equals(order.get("order_status"))||number(order,"seller_id")!=actor.id()||number(order,"user_id")!=number(task,"publisher_id")||number(order,"quantity")!=1||((BigDecimal)order.get("unit_price")).compareTo((BigDecimal)task.get("reward_amount"))!=0||((BigDecimal)order.get("total_amount")).compareTo((BigDecimal)task.get("reward_amount"))!=0)throw new ApiException(409,"关联任务订单不一致");
        int version=m.db.queryForObject("SELECT COALESCE(MAX(submission_no),0)+1 FROM task_proofs WHERE reward_task_id=?",Integer.class,id);long proof=m.insert("INSERT INTO task_proofs(reward_task_id,submitter_id,submission_no,proof_text,evidence_urls) VALUES(?,?,?,?,?)",id,actor.id(),version,text,m.encode(refs));m.files.bindRefs(actor.id(),"TASK_PROOF","TASK_PROOF",Long.toString(proof),refs);m.db.update("UPDATE RewardTasks SET task_status='待验收',submitted_at=UTC_TIMESTAMP(3),version=version+1 WHERE reward_task_id=?",id);m.db.update("UPDATE user_orders SET order_status='待验收' WHERE order_id=?",order.get("order_id"));log(actor,id,"提交凭证","进行中","待验收","提交第"+version+"版凭证");return tasks.proofView(m.require("task_proofs","proof_id",proof,false));
    });}
}
