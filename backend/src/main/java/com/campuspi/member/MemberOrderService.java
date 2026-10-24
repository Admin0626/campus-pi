package com.campuspi.member;
import com.campuspi.account.*;
import com.campuspi.admin.AdminOrderService;
import com.campuspi.core.BusinessNumbers;
import com.campuspi.core.OfflineTrade;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.campuspi.member.MemberSupport.*;

@Service
public class MemberOrderService {
    private final MemberSupport m;private final BusinessNumbers numbers;
    public MemberOrderService(MemberSupport m,BusinessNumbers numbers){this.m=m;this.numbers=numbers;}
    Object view(Map<String,Object> row,boolean privateView){var out=new LinkedHashMap<String,Object>();for(String field:List.of("order_id","order_type","item_name","quantity","order_status","trade_channel"))out.put(field,row.get(field));out.put("buyer_id",string(row.get("user_id")));for(String field:List.of("seller_id","product_id","reward_task_id"))out.put(field,string(row.get(field)));for(String field:List.of("unit_price","total_amount","shipping_fee","actual_amount"))out.put(field,money(row.get(field)));for(String field:List.of("order_time","payment_time","shipping_time","completion_time"))out.put(field,time(row.get(field)));out.put("shipping_address",privateView&&"商品".equals(row.get("order_type"))?row.get("shipping_address"):null);return out;}
    Map<String,Object> owned(AccountService.Identity actor,String id,boolean lock){var row=m.require("user_orders","order_id",AdminOrderService.orderId(id),lock);if(number(row,"user_id")!=actor.id()&&number(row,"seller_id")!=actor.id())throw new ApiException(404,"订单不存在或无权读取");return row;}
    public Object detail(AccountService.Identity actor,String id){return view(owned(actor,id,false),true);}
    public Object list(AccountService.Identity actor,int page,int size,String side,String type,String state){choice(side,"all","buyer","seller");choice(type,"商品","任务");choice(state,"待线下交易","待支付","已支付","已发货","待确认收货","已完成","已取消","售后中","已退款","进行中","待验收","未完成");String where=" WHERE ";var args=new ArrayList<Object>();if(side.equals("buyer")){where+="user_id=?";args.add(actor.id());}else if(side.equals("seller")){where+="seller_id=?";args.add(actor.id());}else{where+="(user_id=? OR seller_id=?)";args.add(actor.id());args.add(actor.id());}if(!type.isEmpty()){where+=" AND order_type=?";args.add(type);}if(!state.isEmpty()){where+=" AND order_status=?";args.add(state);}return m.page("user_orders","*",where,args,page,size,"order_time DESC,order_id DESC",row->view(row,false));}
    public Object create(AccountService.Identity actor,JsonNode body,String key){AccountService.fields(body,"product_id","quantity","shipping_address");long productId=AccountService.id(AccountService.text(body,"product_id",1,19,false));int quantity=integer(body,"quantity",1,100000);String address=text(body,"shipping_address",1,500);return m.accounts.memberMutation(actor,"product-order",key,body,()->{
        var product=m.require("product_info","product_id",productId,true);long seller=number(product,"user_id");if(seller==actor.id())throw new ApiException(403,"不能购买本人商品");if(!"已通过".equals(product.get("audit_status"))||!"在售".equals(product.get("shelf_status")))throw new ApiException(409,"商品当前不可购买");if(!"校园自提".equals(product.get("trade_mode")))throw new ApiException(409,"仅支持校园自提的线下交易商品");m.eligible(actor.id(),string(product.get("campus")));m.eligible(seller,string(product.get("campus")));int stock=((Number)product.get("stock_quantity")).intValue();if(stock<quantity)throw new ApiException(409,"商品库存不足",null,"INSUFFICIENT_STOCK");BigDecimal price=(BigDecimal)product.get("discount_price"),total=price.multiply(BigDecimal.valueOf(quantity));if(total.compareTo(new BigDecimal("9999999999.99"))>0)throw new ApiException(409,"订单金额超过上限");
        String id=numbers.nextOrder();m.db.update("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,shipping_fee,actual_amount,order_status,trade_channel,shipping_address) VALUES(?,'商品',?,?,?,?,?,?,?,0.00,0.00,'待线下交易','OFFLINE',?)",id,actor.id(),seller,productId,product.get("product_name"),quantity,price,total,address);int remaining=stock-quantity;m.db.update("UPDATE product_info SET stock_quantity=?,shelf_status=? WHERE product_id=?",remaining,remaining==0?"已售出":"在售",productId);return view(m.require("user_orders","order_id",id,false),true);
    });}
    public Object cancel(AccountService.Identity actor,String id,String key){AdminOrderService.orderId(id);return m.accounts.memberMutation(actor,"product-order-cancel:"+id,key,Map.of(),()->{
        var order=owned(actor,id,true);if(number(order,"user_id")!=actor.id())throw new ApiException(403,"只有买方可以取消订单");OfflineTrade.requirePending(order);var product=m.require("product_info","product_id",order.get("product_id"),true);if(number(product,"user_id")!=number(order,"seller_id"))throw new ApiException(409,"订单商品归属不一致");long stock=number(product,"stock_quantity")+number(order,"quantity");if(stock>Integer.MAX_VALUE)throw new ApiException(409,"恢复库存超过上限");String shelf=string(product.get("shelf_status"));if(shelf.equals("已售出")&&"已通过".equals(product.get("audit_status")))shelf="在售";m.db.update("UPDATE user_orders SET order_status='已取消' WHERE order_id=?",id);m.db.update("UPDATE product_info SET stock_quantity=?,shelf_status=? WHERE product_id=?",stock,shelf,order.get("product_id"));m.audit(actor,"订单",id,"本人取消","买方取消待线下交易订单并释放一次库存");return view(m.require("user_orders","order_id",id,false),true);
    });}
    public Object confirmOffline(AccountService.Identity actor,String id,String key,boolean receipt) {
        AdminOrderService.orderId(id);
        return m.accounts.memberMutation(actor,(receipt?"offline-receipt:":"offline-handover:")+id,key,Map.of(),()->{
            var order=owned(actor,id,true);
            if(actor.id()!=number(order,receipt?"user_id":"seller_id"))throw new ApiException(403,receipt?"只有买方可以确认收货":"只有卖方可以确认交付");
            OfflineTrade.requireOrder(order);
            if(receipt) {
                if(!"待确认收货".equals(order.get("order_status"))||order.get("shipping_time")==null||order.get("completion_time")!=null)
                    throw new ApiException(409,"请等待卖方确认线下交付，且订单不能重复完成");
                m.db.update("UPDATE user_orders SET order_status='已完成',completion_time=UTC_TIMESTAMP(3) WHERE order_id=?",id);
            } else {
                OfflineTrade.requirePending(order);
                m.db.update("UPDATE user_orders SET order_status='待确认收货',shipping_time=UTC_TIMESTAMP(3) WHERE order_id=?",id);
            }
            m.audit(actor,"订单",id,receipt?"买方确认线下收货":"卖方确认线下交付","仅记录本人确认的交付进度，不代表平台收付款");
            return view(m.require("user_orders","order_id",id,false),true);
        });
    }
}
