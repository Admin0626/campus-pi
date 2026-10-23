package com.campuspi.member;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.campuspi.member.MemberSupport.*;

@Service
public class MemberContentService {
    private final MemberSupport m;
    private static final String PRODUCT_PUBLIC="p.audit_status='已通过' AND p.shelf_status='在售' AND p.stock_quantity>0 AND u.account_status='ACTIVE'";
    private static final String POST_PUBLIC="p.post_status='已发布' AND u.account_status='ACTIVE'";
    public MemberContentService(MemberSupport m){this.m=m;}
    public Object products(AccountService.Identity actor,int page,int size,String campus,Integer category,String keyword,String min,String max,String mine,String audit,String shelf){
        limited(campus,50);limited(keyword,100);choice(audit,"待审核","已通过","已驳回");choice(shelf,"在售","已下架","已售出");if(category!=null&&category<1)throw new ApiException(400,"分类编号无效");
        String where=" WHERE ";var args=new ArrayList<Object>();String from="product_info p JOIN users u ON u.user_id=p.user_id";
        if(mine.equals("published")){where+="p.user_id=?";args.add(actor.id());}
        else {where+=PRODUCT_PUBLIC;if(mine.equals("favorites")){from+=" JOIN product_favor f ON f.product_id=p.product_id";where+=" AND f.user_id=?";args.add(actor.id());}}
        if(!campus.isEmpty()){where+=" AND p.campus=?";args.add(campus);}if(category!=null){where+=" AND p.category_id=?";args.add(category);}if(!keyword.isBlank()){where+=" AND LOCATE(?,p.product_name)>0";args.add(keyword.trim());}
        if(!min.isEmpty()){where+=" AND p.discount_price>=?";args.add(priceFilter(min));}if(!max.isEmpty()){where+=" AND p.discount_price<=?";args.add(priceFilter(max));}if(!min.isEmpty()&&!max.isEmpty()&&priceFilter(min).compareTo(priceFilter(max))>0)throw new ApiException(400,"价格区间无效");
        if(!audit.isEmpty()){where+=" AND p.audit_status=?";args.add(audit);}if(!shelf.isEmpty()){where+=" AND p.shelf_status=?";args.add(shelf);}
        return m.page(from,"p.*",where,args,page,size,mine.equals("favorites")?"f.favor_time DESC,f.product_favor_id DESC":"p.create_time DESC,p.product_id DESC",row->m.product(row,actor));
    }
    Map<String,Object> visibleProduct(long id,AccountService.Identity actor,boolean lock){
        var row=m.require("product_info","product_id",id,lock);var seller=m.require("users","user_id",row.get("user_id"),false);
        boolean owner=actor!=null&&actor.id()==number(row,"user_id");
        if(!owner&&(!"已通过".equals(row.get("audit_status"))||!Set.of("在售","已售出").contains(string(row.get("shelf_status")))||!"ACTIVE".equals(seller.get("account_status"))))throw new ApiException(404,"商品不存在或不可见");return row;
    }
    public Object product(long id,AccountService.Identity actor){return m.product(visibleProduct(id,actor,false),actor);}
    public Object saveProduct(AccountService.Identity actor,Long target,JsonNode body,String key){
        AccountService.fields(body,"category_id","product_name","product_desc","campus","location_label","trade_mode","original_price","discount_price","stock_quantity","image_refs");
        int category=integer(body,"category_id",1,Integer.MAX_VALUE),stock=integer(body,"stock_quantity",0,100000);String name=text(body,"product_name",1,255),campus=text(body,"campus",1,50),mode=text(body,"trade_mode",1,10);choice(mode,"校园自提","快递配送");String desc=optional(body,"product_desc",5000),location=optional(body,"location_label",100);BigDecimal original=amount(body,"original_price"),discount=amount(body,"discount_price");var refs=m.files.parseRefs(body.get("image_refs"));
        java.util.function.Supplier<Object> action=()->{
            m.eligible(actor.id(),campus);m.category(category);long id;
            if(target==null)id=m.insert("INSERT INTO product_info(user_id,category_id,product_name,product_desc,campus,location_label,trade_mode,original_price,discount_price,stock_quantity) VALUES(?,?,?,?,?,?,?,?,?,?)",actor.id(),category,name,desc,campus,location,mode,original,discount,stock);
            else {
                id=target;var prior=m.require("product_info","product_id",id,true);if(number(prior,"user_id")!=actor.id())throw new ApiException(404,"商品不存在");
                if(m.db.queryForObject("SELECT COUNT(*) FROM user_orders WHERE product_id=? AND order_status IN ('待支付','已支付','已发货','待确认收货','售后中')",Integer.class,id)>0)throw new ApiException(409,"商品存在未完结订单，不能编辑");
                m.db.update("UPDATE product_info SET category_id=?,product_name=?,product_desc=?,campus=?,location_label=?,trade_mode=?,original_price=?,discount_price=?,stock_quantity=?,audit_status='待审核',shelf_status='已下架',reviewer_id=NULL,audit_reason=NULL,audited_at=NULL WHERE product_id=?",category,name,desc,campus,location,mode,original,discount,stock,id);
                m.db.update("DELETE FROM product_media WHERE product_id=?",id);
            }
            m.files.bindRefs(actor.id(),"PRODUCT_IMAGE","PRODUCT",Long.toString(id),refs);for(int i=0;i<refs.size();i++)m.db.update("INSERT INTO product_media(product_id,image_url,sort_order) VALUES(?,?,?)",id,refs.get(i),i);
            m.audit(actor,"商品",id,target==null?"发布":"修改","本人提交商品，等待审核");return m.product(m.require("product_info","product_id",id,false),actor);
        };
        return target==null?m.accounts.memberMutation(actor,"product-create",key,body,action):m.accounts.memberTransaction(actor,action);
    }
    public Object shelf(AccountService.Identity actor,long id,JsonNode body){
        AccountService.fields(body,"shelf_status","reason");String state=text(body,"shelf_status",1,10),reason=text(body,"reason",1,500);choice(state,"在售","已下架");
        return m.accounts.memberTransaction(actor,()->{var row=m.require("product_info","product_id",id,true);if(number(row,"user_id")!=actor.id())throw new ApiException(404,"商品不存在");if(state.equals("在售")){m.eligible(actor.id(),string(row.get("campus")));if(!"已通过".equals(row.get("audit_status"))||number(row,"stock_quantity")<1)throw new ApiException(409,"上架需要审核通过且库存充足");}m.db.update("UPDATE product_info SET shelf_status=? WHERE product_id=?",state,id);m.audit(actor,"商品",id,"本人上下架",reason);return m.product(m.require("product_info","product_id",id,false),actor);});
    }
    public Object favorite(AccountService.Identity actor,long id,boolean add){return m.accounts.memberTransaction(actor,()->{
        if(add){var row=visibleProduct(id,null,true);if(!Set.of("在售","已售出").contains(string(row.get("shelf_status"))))throw new ApiException(404,"商品不可见");m.db.update("INSERT IGNORE INTO product_favor(product_id,user_id) VALUES(?,?)",id,actor.id());}
        else m.db.update("DELETE FROM product_favor WHERE product_id=? AND user_id=?",id,actor.id());return null;
    });}
    static String postId(String value){if(value==null||value.isBlank()||value.length()>50||value.codePoints().anyMatch(Character::isISOControl))throw new ApiException(400,"帖子编号无效");return value;}
    public Object posts(AccountService.Identity actor,int page,int size,Integer category,String keyword,String mine,String state){
        limited(keyword,100);choice(state,"待审核","已发布","已驳回","已下架","已删除");if(category!=null&&category<1)throw new ApiException(400,"分类无效");String from="post_info p JOIN users u ON u.user_id=p.publisher_id",where=" WHERE ";var args=new ArrayList<Object>();
        if(mine.equals("published")){where+="p.publisher_id=? AND p.post_status<>'已删除'";args.add(actor.id());}else{where+=POST_PUBLIC;if(mine.equals("collections")){from+=" JOIN post_collection c ON c.post_id=p.post_id";where+=" AND c.user_id=? AND c.collection_status='已收藏'";args.add(actor.id());}}
        if(category!=null){where+=" AND p.category_id=?";args.add(category);}if(!keyword.isBlank()){where+=" AND (LOCATE(?,p.post_title)>0 OR LOCATE(?,p.post_content)>0)";args.add(keyword.trim());args.add(keyword.trim());}if(!state.isEmpty()){where+=" AND p.post_status=?";args.add(state);}
        return m.page(from,"p.*",where,args,page,size,mine.equals("collections")?"c.collection_time DESC,c.collection_id DESC":"p.publish_time DESC,p.post_id DESC",row->m.post(row,actor));
    }
    Map<String,Object> visiblePost(String id,AccountService.Identity actor,boolean lock){var row=m.require("post_info","post_id",postId(id),lock);var user=m.require("users","user_id",row.get("publisher_id"),false);boolean owner=actor!=null&&actor.id()==number(row,"publisher_id");if("已删除".equals(row.get("post_status"))||!owner&&(!"已发布".equals(row.get("post_status"))||!"ACTIVE".equals(user.get("account_status"))))throw new ApiException(404,"帖子不存在或不可见");return row;}
    public Object post(String id,AccountService.Identity actor){var row=visiblePost(id,actor,false);m.db.update("UPDATE post_info SET view_count=view_count+1 WHERE post_id=? AND post_status<>'已删除' AND view_count<9223372036854775807",id);row.put("view_count",m.db.queryForObject("SELECT view_count FROM post_info WHERE post_id=?",Long.class,id));return m.post(row,actor);}
    public Object savePost(AccountService.Identity actor,String target,JsonNode body,String key){
        AccountService.fields(body,"category_id","post_title","post_content","is_anonymous");int category=integer(body,"category_id",1,Integer.MAX_VALUE);String title=text(body,"post_title",1,200),content=text(body,"post_content",1,10000);if(!body.has("is_anonymous")||!body.get("is_anonymous").isBoolean())throw new ApiException(400,"匿名标记须为布尔值");boolean anonymous=body.get("is_anonymous").booleanValue();
        java.util.function.Supplier<Object> action=()->{m.category(category);String id=target==null?"POST_"+UUID.randomUUID().toString().replace("-",""):postId(target);
            if(target==null)m.db.update("INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content,is_anonymous) VALUES(?,?,?,?,?,?)",id,actor.id(),category,title,content,anonymous);
            else{var prior=m.require("post_info","post_id",id,true);if(number(prior,"publisher_id")!=actor.id())throw new ApiException(404,"帖子不存在");if("已删除".equals(prior.get("post_status")))throw new ApiException(409,"已删除帖子不能编辑");m.db.update("UPDATE post_info SET category_id=?,post_title=?,post_content=?,is_anonymous=?,post_status='待审核' WHERE post_id=?",category,title,content,anonymous,id);}
            m.audit(actor,"帖子",id,target==null?"发布":"修改","本人提交帖子，等待人工审核");return m.post(m.require("post_info","post_id",id,false),actor);};
        return target==null?m.accounts.memberMutation(actor,"post-create",key,body,action):m.accounts.memberTransaction(actor,action);
    }
    public Object deletePost(AccountService.Identity actor,String id){return m.accounts.memberTransaction(actor,()->{var row=m.require("post_info","post_id",postId(id),true);if(number(row,"publisher_id")!=actor.id())throw new ApiException(404,"帖子不存在");if(!"已删除".equals(row.get("post_status"))){m.db.update("UPDATE post_info SET post_status='已删除' WHERE post_id=?",id);m.db.update("UPDATE post_collection SET collection_status='失效' WHERE post_id=?",id);m.audit(actor,"帖子",id,"本人删除","本人逻辑删除帖子");}return null;});}
    public Object collect(AccountService.Identity actor,String id,boolean add){return m.accounts.memberTransaction(actor,()->{postId(id);if(add){visiblePost(id,null,true);m.db.update("INSERT INTO post_collection(user_id,post_id) VALUES(?,?) ON DUPLICATE KEY UPDATE collection_time=IF(collection_status='已收藏',collection_time,CURRENT_TIMESTAMP),collection_status='已收藏'",actor.id(),id);}else m.db.update("UPDATE post_collection SET collection_status='已取消' WHERE user_id=? AND post_id=?",actor.id(),id);return null;});}
}
