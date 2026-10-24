# 选项G：商品订单与线下交易

每人负责模块前后端；15个有效接口，P0 12个、P1 3个。API052已删除，不再认领线上支付。

|编号|功能|接口|阶段|
|---|---|---|---|
|API046|创建线下商品订单|POST /orders/product|P0|
|API047|查询本人订单|GET /me/orders|P0|
|API048|获取本人订单详情|GET /orders/{order_id}|P0|
|API049|取消待线下交易订单|POST /orders/{order_id}/cancel|P0|
|API050|卖方确认线下交付|POST /orders/{order_id}/shipping|P0|
|API051|买方确认线下收货|POST /orders/{order_id}/receipt|P0|
|API053|申请任务订单争议|POST /aftersales|P1|
|API054|查询本人发起售后|GET /me/aftersales|P0|
|API055|查询售后详情|GET /aftersales/{aftersale_id}|P0|
|API056|撤回本人待审核售后|POST /aftersales/{aftersale_id}/cancel|P1|
|API095|查询全部订单|GET /admin/orders|P0|
|API096|获取管理订单详情|GET /admin/orders/{order_id}|P0|
|API097|管理取消待线下交易订单|POST /admin/orders/{order_id}/cancel|P0|
|API098|查询售后审核队列|GET /admin/aftersales|P0|
|API099|审核任务订单争议|POST /admin/aftersales/{aftersale_id}/review|P1|

复用现有商品、订单、管理端订单组件，以及小程序business-surface，不创建另一套支付或结算页面。

流程：创建待线下交易订单并预占库存 → 双方私信约定时间地点并当面交易 → 卖方确认交付 → 买方确认收货 → 已完成。买方仅可在交付前取消。平台不代收款、不执行退款；商品退换及金额问题由双方线下协商。任务订单及内部积分不变。

验收包括角色权限、乱序拒绝、幂等重试、重复提交、库存并发与历史记录保留。详细契约见[线下交易接口变更](../../api/线下交易接口变更.md)及[OpenAPI](../../api/campus-pi-openapi.json)。
