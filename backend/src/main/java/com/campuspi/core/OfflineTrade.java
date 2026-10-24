package com.campuspi.core;

import com.campuspi.account.ApiException;
import java.math.BigDecimal;
import java.util.Map;

/** Offline progress records never assert that the platform collected any money. */
public final class OfflineTrade {
    public static final String CHANNEL = "OFFLINE";
    public static final String PENDING = "待线下交易";
    private OfflineTrade() {}
    public static void requireOrder(Map<String, Object> order) {
        if (!"商品".equals(order.get("order_type")) || !CHANNEL.equals(order.get("trade_channel")))
            throw new ApiException(409, "仅线下商品订单可执行此操作，历史订单请核对原始记录");
        if (((BigDecimal) order.get("actual_amount")).signum() != 0
                || ((BigDecimal) order.get("shipping_fee")).signum() != 0
                || order.get("payment_method") != null || order.get("payment_time") != null
                || order.get("payment_transaction_no") != null)
            throw new ApiException(409, "线下订单账务记录异常，需要核对");
    }
    public static void requirePending(Map<String, Object> order) {
        requireOrder(order);
        if (!PENDING.equals(order.get("order_status")) || order.get("shipping_time") != null
                || order.get("completion_time") != null)
            throw new ApiException(409, "仅尚未确认交付的线下商品订单可取消或交付");
    }
}
