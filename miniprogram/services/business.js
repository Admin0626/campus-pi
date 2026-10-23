const { request, requestKey } = require("../utils/request");
const names = {
  B: "实名与校园认证",
  C: "校园二手",
  D: "校园发现",
  E: "悬赏任务",
  F: "任务记录与奖励",
  G: "我的订单",
  H: "校园私信",
  I: "通知中心",
  A: "资料与隐私",
  S: "修改密码",
};
const scopes = {
  B: [["mine", "申请记录"]],
  C: [
    ["all", "在售商品"],
    ["mine", "我的发布"],
    ["saved", "收藏"],
  ],
  D: [
    ["all", "校园广场"],
    ["mine", "我的发布"],
    ["saved", "收藏"],
  ],
  E: [
    ["all", "任务广场"],
    ["published", "我发布的"],
    ["accepted", "我接取的"],
    ["applications", "申请记录"],
    ["ledger", "奖励流水"],
  ],
  F: [
    ["accepted", "我接取的"],
    ["published", "我发布的"],
    ["applications", "申请记录"],
    ["ledger", "奖励流水"],
  ],
  G: [
    ["all", "全部"],
    ["buyer", "买入 / 发布"],
    ["seller", "卖出 / 接取"],
    ["aftersales", "售后与争议"],
  ],
  H: [
    ["sessions", "会话"],
    ["reports", "举报记录"],
  ],
  I: [
    ["all", "全部通知"],
    ["unread", "未读通知"],
  ],
  A: [],
  S: [],
};
function endpoint(module, scope, keyword = "") {
  const q = "?keyword=" + encodeURIComponent(keyword);
  switch (module) {
    case "B":
      return "/me/verifications";
    case "C":
      return scope === "mine"
        ? "/me/products"
        : scope === "saved"
          ? "/me/product-favorites"
          : "/products" + q;
    case "D":
      return scope === "mine"
        ? "/me/posts"
        : scope === "saved"
          ? "/me/post-collections"
          : "/posts" + q;
    case "E":
    case "F":
      return scope === "all"
        ? "/tasks" + q
        : scope === "applications"
          ? "/me/task-applications"
          : scope === "ledger"
            ? "/me/reward-ledger"
            : "/me/tasks?side=" + scope;
    case "G":
      return scope === "aftersales"
        ? "/me/aftersales"
        : "/me/orders?side=" + scope;
    case "H":
      return scope === "reports" ? "/me/message-reports" : "/chat-sessions";
    case "I":
      return "/me/notifications?unread_only=" + (scope === "unread");
    default:
      return null;
  }
}
const date = (v) =>
  v ? new Date(v).toLocaleString("zh-CN", { hour12: false }) : "—";
function card(m, r) {
  const id = {
    B: r.verification_id,
    C: r.product_id,
    D: r.post_id,
    E: r.application_id || r.ledger_id || r.reward_task_id,
    F: r.application_id || r.ledger_id || r.reward_task_id,
    G: r.aftersale_id || r.order_id,
    H: r.session_id || r.report_id,
    I: r.notification_id,
  }[m];
  return {
    id,
    raw: r,
    title:
      r.product_name ||
      r.post_title ||
      r.task_name ||
      r.item_name ||
      r.peer?.username ||
      r.notification_title ||
      (r.aftersale_id ? "任务争议 " + r.aftersale_id : "") ||
      (r.verification_type
        ? r.verification_type + "认证"
        : r.event_type
          ? r.event_type + " · " + r.reward_type
          : r.report_id
            ? "举报 " + r.report_id
            : "任务 " + r.reward_task_id),
    status:
      r.aftersale_status ||
      r.audit_status ||
      r.post_status ||
      r.task_status ||
      r.application_status ||
      r.order_status ||
      (r.unread_count !== undefined ? "未读 " + r.unread_count : ""),
    summary:
      r.last_message ||
      r.notification_content ||
      r.audit_reason ||
      r.post_content ||
      r.task_description ||
      r.reason ||
      (r.available_delta
        ? "可用 " + r.available_delta + " / 冻结 " + r.frozen_delta
        : ""),
    price: r.discount_price || r.reward_amount || r.total_amount || "",
    date: date(
      r.create_time ||
        r.publish_time ||
        r.order_time ||
        r.submitted_at ||
        r.applied_at ||
        r.send_time ||
        r.last_send_time ||
        r.created_at,
    ),
  };
}
function field(key, label, value = "", extra = {}) {
  return {
    key,
    label,
    value,
    ...extra,
    ...(extra.options
      ? {
          selected: Math.max(
            0,
            extra.options.findIndex((o) => o.value === value),
          ),
        }
      : {}),
  };
}
function form(module, user, categories, row) {
  const category = field(
    "category_id",
    "分类",
    row?.category_id || categories[0]?.category_id,
    {
      type: "select",
      options: categories.map((c) => ({
        label: c.category_name,
        value: c.category_id,
      })),
    },
  );
  switch (module) {
    case "C":
      return [
        category,
        field("product_name", "商品名称", row?.product_name || "", {
          max: 255,
        }),
        field("product_desc", "商品说明", row?.product_desc || "", {
          type: "textarea",
          max: 5000,
          optional: true,
        }),
        field("campus", "认证校区", user.campus || "", { readonly: true }),
        field("trade_mode", "交易方式", "校园自提", { readonly: true }),
        field(
          "original_price",
          "原价（如20.00）",
          row?.original_price || "0.00",
        ),
        field(
          "discount_price",
          "售价（如10.00）",
          row?.discount_price || "0.00",
        ),
        field("stock_quantity", "库存", String(row?.stock_quantity ?? 1), {
          type: "number",
        }),
      ];
    case "D":
      return [
        category,
        field("post_title", "标题", row?.post_title || "", { max: 200 }),
        field("post_content", "内容（纯文本）", row?.post_content || "", {
          type: "textarea",
          max: 10000,
        }),
        field("is_anonymous", "匿名发布", row?.is_anonymous || false, {
          type: "switch",
        }),
      ];
    case "E":
    case "F":
      return [
        field("task_name", "任务名称", "", { max: 100 }),
        field("task_description", "任务说明", "", {
          type: "textarea",
          max: 5000,
        }),
        field("campus", "认证校区", user.campus || "", { readonly: true }),
        field("reward_type", "奖励类型", "积分", { readonly: true }),
        field("reward_amount", "奖励积分（如10.00）", "1.00"),
      ];
    case "B":
      return [
        field("verification_type", "认证类型", "实名", {
          type: "select",
          options: ["实名", "校园"].map((value) => ({ label: value, value })),
        }),
        field("real_name", "真实姓名", "", { max: 50 }),
        field("campus", "校区", "", { optional: true, max: 50 }),
        field("student_no", "学号（校园认证必填）", "", {
          optional: true,
          max: 30,
        }),
      ];
    case "A":
      return [
        field("personal_bio", "个人介绍", user.personal_bio || "", {
          type: "textarea",
          max: 500,
          optional: true,
        }),
        field("address", "常用交接地址", user.address || "", {
          max: 500,
          optional: true,
        }),
        field("show_campus", "公开校区", user.privacy_settings.show_campus, {
          type: "switch",
        }),
        field(
          "show_personal_bio",
          "公开个人介绍",
          user.privacy_settings.show_personal_bio,
          { type: "switch" },
        ),
      ];
    case "S":
      return [
        field("current_password", "当前密码", "", { password: true }),
        field("new_password", "新密码（12–64字符）", "", { password: true }),
        field("confirm_password", "再次输入新密码", "", { password: true }),
      ];
    default:
      return [];
  }
}
function values(fields) {
  const body = {};
  fields.forEach((f) => {
    if (
      !f.optional &&
      f.type !== "switch" &&
      String(f.value ?? "").trim() === ""
    )
      throw new Error("请填写" + f.label);
    body[f.key] =
      f.type === "number" || f.key === "category_id"
        ? Number(f.value)
        : f.value;
  });
  return body;
}
function upload(filePath, purpose) {
  const app = getApp(),
    token = app.globalData.token,
    base = app.globalData.apiBaseUrl;
  if (!base) return Promise.reject(new Error("服务地址未配置"));
  return requestKey().then(
    (key) =>
      new Promise((resolve, reject) =>
        wx.uploadFile({
          url: base.replace(/\/$/, "") + "/api/v1/files",
          filePath,
          name: "file",
          formData: { purpose },
          header: { Authorization: "Bearer " + token, "Idempotency-Key": key },
          timeout: 30000,
          success(r) {
            try {
              const value = JSON.parse(r.data);
              if (
                r.statusCode >= 200 &&
                r.statusCode < 300 &&
                value.code === "OK"
              )
                resolve(value.data);
              else reject(new Error(value.message || "上传失败"));
            } catch (_) {
              reject(new Error("上传响应异常"));
            }
          },
          fail() {
            reject(new Error("上传失败，请检查网络"));
          },
        }),
      ),
  );
}
function mutator() {
  const keys = new Map();
  return async (path, body, method = "POST") => {
    const signature = JSON.stringify([method, path, body]);
    if (!keys.has(signature)) keys.set(signature, await requestKey());
    const result = await request(path, {
      method,
      body,
      key: keys.get(signature),
    });
    keys.delete(signature);
    return result;
  };
}
module.exports = {
  names,
  scopes,
  endpoint,
  card,
  form,
  field,
  values,
  date,
  upload,
  mutator,
};
