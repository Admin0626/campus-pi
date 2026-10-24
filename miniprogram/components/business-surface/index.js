const { request } = require("../../utils/request");
const b = require("../../services/business");
const { guard } = require("../../utils/account");
const { openPage, goLogin } = require("../../utils/navigation");
const session = require("../../utils/session");
const confirm = (content) =>
  new Promise((resolve) =>
    wx.showModal({
      title: "请确认",
      content,
      success: (r) => resolve(r.confirm),
      fail: () => resolve(false),
    }),
  );
Component({
  properties: { module: { type: String, value: "D" } },
  data: {
    title: "",
    scope: "",
    scopes: [],
    user: null,
    rows: [],
    page: 1,
    total: "0",
    canNext: false,
    loading: false,
    busy: false,
    error: "",
    notice: "",
    keyword: "",
    stage: "list",
    detail: null,
    detailLines: [],
    actions: [],
    fields: [],
    files: [],
    purpose: "",
    mode: "",
    messages: [],
    older: false,
    history: [],
    historyPage: 1,
    historyKind: "proofs",
    historyMore: false,
  },
  lifetimes: {
    attached() {
      this._active = true;
      this._mutation = b.mutator();
      this.start();
    },
    detached() {
      this._active = false;
      this._serial++;
      clearInterval(this._timer);
    },
  },
  pageLifetimes: {
    show() {
      this._visible = true;
    },
    hide() {
      this._visible = false;
    },
  },
  methods: {
    safe(data) {
      if (this._active) this.setData(data);
    },
    async work(fn, notice = "") {
      if (this.data.busy || !guard()) return;
      const token = getApp().globalData.token;
      this.setData({ busy: true, error: "", notice: "" });
      try {
        await fn();
        if (this._active && token === getApp().globalData.token)
          this.setData({ notice });
      } catch (e) {
        if (this._active && token === getApp().globalData.token)
          this.setData({ error: e.message });
      } finally {
        this.safe({ busy: false });
      }
    },
    async start() {
      if (!guard()) return;
      clearInterval(this._timer);
      const identity = getApp().globalData.token;
      const m = this.properties.module,
        scopes = b.scopes[m] || [];
      this.setData({
        title: b.names[m],
        scope: scopes[0]?.[0] || "",
        scopes: scopes.map(([value, label]) => ({ value, label })),
        user: getApp().globalData.user,
      });
      await this.work(async () => {
        const user = await request("/me");
        if (!this._active || getApp().globalData.token !== identity) return;
        getApp().globalData.user = user;
        this.safe({ user });
        if (["C", "D"].includes(m))
          this._categories = (await request("/categories")).items;
        if (m === "A") {
          this.showProfile(user);
        } else if (m === "S") this.edit();
        else await this.load();
        if (["E", "F"].includes(m)) {
          const a = await request("/me/reward-accounts");
          this.safe({ balances: a.items });
        }
        if (
          m === "H" &&
          this._active &&
          getApp().globalData.token === identity
        ) {
          const id = getApp().globalData.pendingChat;
          delete getApp().globalData.pendingChat;
          if (id)
            await this.openRaw({
              session_id: id,
              peer: { username: "校园同学" },
            });
          this._timer = setInterval(() => {
            if (
              this._active &&
              this._visible !== false &&
              this.data.detail &&
              !this.data.busy
            )
              this.work(() => this.chatMessages("after"));
          }, 10000);
        }
      });
    },
    showProfile(user) {
      this.safe({
        stage: "detail",
        detail: {},
        fields: [],
        files: [],
        actions: [],
        detailLines: [
          { label: "用户名", value: user.username },
          { label: "个人介绍", value: user.personal_bio || "—" },
          { label: "常用交接地址", value: user.address || "—" },
          { label: "认证校区", value: user.campus || "—" },
        ],
        detailFiles: user.avatar_url ? [user.avatar_url] : [],
      });
    },
    async load() {
      const endpoint = b.endpoint(
        this.properties.module,
        this.data.scope,
        this.data.keyword,
      );
      if (!endpoint) return;
      const serial = (this._serial = (this._serial || 0) + 1);
      this.safe({ loading: true });
      try {
        const value = await request(
          endpoint +
            (endpoint.includes("?") ? "&" : "?") +
            "page=" +
            this.data.page +
            "&page_size=10",
        );
        if (serial === this._serial)
          this.safe({
            rows: value.items.map((r) => b.card(this.properties.module, r)),
            total: value.total,
            canNext: BigInt(this.data.page) * 10n < BigInt(value.total),
          });
      } finally {
        if (serial === this._serial) this.safe({ loading: false });
      }
    },
    scope(event) {
      if (this.data.busy) return;
      this.safe({
        scope: event.currentTarget.dataset.value,
        stage: "list",
        detail: null,
        page: 1,
        rows: [],
      });
      return this.work(() => this.load());
    },
    turn(event) {
      if (this.data.busy) return;
      this.safe({
        page: this.data.page + Number(event.currentTarget.dataset.delta),
      });
      return this.work(() => this.load());
    },
    input(event) {
      this.safe({ [event.currentTarget.dataset.key]: event.detail.value });
    },
    search() {
      this.safe({ page: 1 });
      return this.work(() => this.load());
    },
    refresh() {
      if (this.properties.module === "A") return this.start();
      return this.work(() =>
        this.data.detail && this.properties.module === "H"
          ? this.chatMessages("after")
          : this.load(),
      );
    },
    back() {
      if (this.properties.module === "A")
        return this.showProfile(this.data.user);
      this.safe({
        stage: "list",
        detail: null,
        fields: [],
        files: [],
        messages: [],
      });
    },
    open(event) {
      const row = this.data.rows[event.currentTarget.dataset.index]?.raw;
      if (row) return this.work(() => this.openRaw(row));
    },
    async openRaw(row) {
      const m = this.properties.module;
      let detail = row;
      if (m === "C") detail = await request("/products/" + row.product_id);
      if (m === "D") detail = await request("/posts/" + row.post_id);
      if (["E", "F"].includes(m))
        detail = await request("/tasks/" + row.reward_task_id);
      if (m === "G")
        detail = row.aftersale_id
          ? await request("/aftersales/" + row.aftersale_id)
          : await request("/orders/" + row.order_id);
      if (m === "I") {
        detail = await request("/me/notifications/" + row.notification_id);
        await request("/me/notifications/" + row.notification_id + "/click", {
          method: "PUT",
        });
      }
      this.safe({
        messages: [],
        older: false,
        messageText: "",
        detail,
        stage: "detail",
        fields: [],
        files: [],
        history: [],
        historyPage: 1,
      });
      this.describe();
      if (m === "H" && detail.session_id) await this.chatMessages();
      if (
        ["E", "F"].includes(m) &&
        (detail.publisher.user_id === this.data.user.user_id ||
          detail.taker?.user_id === this.data.user.user_id)
      )
        await this.loadHistory();
    },
    describe() {
      const d = this.data.detail,
        m = this.properties.module,
        u = this.data.user.user_id;
      let actions = [],
        pairs = [];
      if (m === "C") {
        pairs = [
          ["商品", d.product_name],
          ["说明", d.product_desc],
          ["售价", "¥" + d.discount_price],
          ["校区", d.campus],
          ["库存", d.stock_quantity],
          ["状态", d.audit_status + " / " + d.shelf_status],
        ];
        actions =
          d.seller.user_id === u
            ? [
                ["edit", "编辑并重新审核"],
                ["shelf", d.shelf_status === "在售" ? "下架" : "上架"],
              ]
            : [
                ["favorite", d.is_favorited ? "取消收藏" : "收藏"],
                ["contact", "联系卖家"],
                ...(d.shelf_status === "在售" ? [["buy", "预约线下交易"]] : []),
              ];
      }
      if (m === "D") {
        pairs = [
          ["标题", d.post_title],
          ["作者", d.author_label],
          ["内容", d.post_content],
          ["状态", d.post_status],
          ["阅读 / 收藏", d.view_count + " / " + d.collection_count],
        ];
        actions =
          this.data.scope === "mine"
            ? [
                ["edit", "编辑并重新审核"],
                ["delete-post", "删除"],
              ]
            : [["collect", d.is_collected ? "取消收藏" : "收藏"]];
      }
      if (["E", "F"].includes(m)) {
        pairs = [
          ["任务", d.task_name],
          ["说明", d.task_description],
          ["状态", d.task_status],
          ["发布者", d.publisher.username],
          ["接取者", d.taker?.username || "未分配"],
          ["奖励", d.reward_amount + " " + d.reward_type],
        ];
        if (
          d.publisher.user_id === u &&
          ["待审核", "待接取"].includes(d.task_status)
        )
          actions.push(["cancel-task", "取消任务并解冻"]);
        if (d.publisher.user_id !== u && d.task_status === "待接取")
          actions.push(["apply", "申请接取"]);
        if (d.taker?.user_id === u && d.task_status === "进行中")
          actions.push(["proof", "提交凭证"]);
        if (d.publisher.user_id === u || d.taker?.user_id === u)
          actions.push(
            ["proof-history", "凭证版本"],
            ["task-logs", "流转记录"],
          );
      }
      if (m === "G") {
        pairs = [
          ["订单", d.order_id],
          ["名称", d.item_name],
          ["类型", d.order_type],
          ["状态", d.order_status],
          ["数量", d.quantity],
          ["总额", d.total_amount],
          ["交易渠道", d.trade_channel === "OFFLINE" ? "线下交易（校园自提）" : d.order_type === "任务" ? "内部奖励账户" : "历史订单"],
          ["交接地点", d.shipping_address],
          ["卖家确认交付", d.shipping_time ? b.date(d.shipping_time) : "—"],
          ["完成时间", d.completion_time ? b.date(d.completion_time) : "—"],
        ];
        if (
          d.buyer_id === u &&
          d.order_type === "商品" &&
          d.trade_channel === "OFFLINE" &&
          d.order_status === "待线下交易"
        )
          actions.push(["cancel-order", "取消订单"]);
        if (d.trade_channel === "OFFLINE" && d.order_status === "待线下交易" && d.seller_id === u)
          actions.push(["offline-handover", "确认已线下交付"]);
        if (d.trade_channel === "OFFLINE" && d.order_status === "待确认收货" && d.buyer_id === u)
          actions.push(["offline-receipt", "确认已线下收货"]);
        if (d.trade_channel === "OFFLINE" && (d.buyer_id === u || d.seller_id === u))
          actions.push(["contact-order", d.buyer_id === u ? "联系卖家" : "联系买家"]);
        if (d.order_type === "任务" && d.buyer_id === u)
          actions.push(["apply-aftersale", "申请任务争议"]);
        if (d.aftersale_id) {
          pairs = [
            ["工单", d.aftersale_id],
            ["订单", d.order_id],
            ["状态", d.aftersale_status],
            ["申请金额", d.apply_amount],
            ["原因", d.apply_reason],
            ["审核说明", d.audit_reason],
          ];
          if (d.user_id === u && d.aftersale_status === "待审核")
            actions.push(["cancel-aftersale", "撤回争议"]);
        }
      }
      if (m === "B") {
        pairs = [
          ["类型", d.verification_type],
          ["状态", d.audit_status],
          ["审核说明", d.audit_reason],
          ["校区", d.campus],
        ];
      }
      if (m === "H" && !d.session_id) {
        pairs = [
          ["举报", d.report_id],
          ["原因", d.reason],
          ["状态", d.audit_status],
          ["审核说明", d.audit_reason],
        ];
      }
      if (m === "I") {
        pairs = [
          ["标题", d.notification_title],
          ["类型", d.notification_type],
          ["内容", d.notification_content],
          ["时间", b.date(d.publish_time)],
        ];
        actions = [
          ["read-notice", "标记已读"],
          ["delete-notice", "删除通知"],
        ];
      }
      this.safe({
        detailLines: pairs.map(([label, value]) => ({
          label,
          value: value ?? "—",
        })),
        actions: actions.map(([value, label]) => ({ value, label })),
        detailFiles: d.image_urls || d.evidence_refs || [],
      });
    },
    edit(event) {
      const m = this.properties.module,
        row = event?.currentTarget?.dataset?.edit ? this.data.detail : null;
      const purpose =
        { C: "PRODUCT_IMAGE", B: "VERIFICATION", A: "AVATAR" }[m] || "";
      this.safe({
        stage: "form",
        mode: row ? "edit" : "create",
        editingId: row?.product_id || row?.post_id || null,
        fields: b.form(m, this.data.user, this._categories || [], row),
        files: [],
        purpose,
      });
    },
    field(event) {
      const i = event.currentTarget.dataset.index,
        f = this.data.fields[i];
      let value = event.detail.value;
      if (f.type === "select") value = f.options[Number(value)].value;
      this.safe({
        ["fields[" + i + "].value"]: value,
        ...(f.type === "select"
          ? { ["fields[" + i + "].selected"]: Number(event.detail.value) }
          : {}),
      });
    },
    async choose() {
      return this.work(async () => {
        if (this.data.purpose === "AVATAR" && this.data.files.length)
          throw new Error("头像只支持一个文件，请先移除当前选择");
        if (this.data.files.length >= 9) throw new Error("最多上传9个文件");
        const choice = await new Promise((resolve, reject) =>
          wx.chooseMessageFile({
            count: 1,
            type: "file",
            success: resolve,
            fail: () => reject(new Error("未选择文件")),
          }),
        );
        const file = choice.tempFiles[0];
        if (!file) return;
        const value = await b.upload(file.path, this.data.purpose);
        this.safe({ files: [...this.data.files, value.file_ref] });
      });
    },
    removeFile(event) {
      this.safe({
        files: this.data.files.filter(
          (_, i) => i !== Number(event.currentTarget.dataset.index),
        ),
      });
    },
    async action(event) {
      const action = event.currentTarget.dataset.action,
        d = this.data.detail,
        m = this.properties.module;
      if (action === "edit")
        return this.edit({ currentTarget: { dataset: { edit: true } } });
      const forms = {
        "apply-aftersale": [
          b.field("apply_reason", "争议原因（金额固定0.00）", "", {
            type: "textarea",
            max: 500,
          }),
        ],
        buy: [
          b.field("quantity", "数量", "1", { type: "number" }),
          b.field(
            "shipping_address",
            "自提交接地点",
            this.data.user.address || "",
            { max: 500 },
          ),
        ],
        proof: [
          b.field("proof_text", "完成说明", "", {
            type: "textarea",
            max: 1000,
          }),
        ],
        "cancel-task": [
          b.field("reason", "取消原因", "", { type: "textarea", max: 500 }),
        ],
      };
      if (forms[action]) {
        this.safe({
          stage: "form",
          mode: action,
          fields: forms[action],
          files: [],
          purpose:
            action === "proof"
              ? "TASK_PROOF"
              : action === "apply-aftersale"
                ? "AFTERSALE"
                : "",
        });
        return;
      }
      return this.work(async () => {
        if (action === "proof-history" || action === "task-logs") {
          this.safe({
            historyKind: action === "proof-history" ? "proofs" : "logs",
            historyPage: 1,
          });
          await this.loadHistory();
          return;
        }
        if (
          [
            "delete-post",
            "cancel-order",
            "offline-handover",
            "offline-receipt",
            "delete-notice",
            "apply",
            "cancel-aftersale",
          ].includes(action) &&
          !(await confirm(
            action === "offline-handover"
              ? "确认已将商品线下交付给买家？确认后不能直接取消订单，款项由双方自行结算。"
              : action === "offline-receipt"
                ? "确认已当面验货、收货并完成线下结算？确认后订单完成，平台不会收款或退款。"
              : action === "apply"
              ? "同一任务只能申请一次，取消后也不能重新申请。确认申请？"
              : "确认此操作？",
          ))
        )
          return;
        if (action === "favorite") {
          await request("/products/" + d.product_id + "/favorite", {
            method: d.is_favorited ? "DELETE" : "PUT",
          });
          d.is_favorited = !d.is_favorited;
        }
        if (action === "collect") {
          await request("/posts/" + d.post_id + "/collection", {
            method: d.is_collected ? "DELETE" : "PUT",
          });
          d.is_collected = !d.is_collected;
        }
        if (action === "shelf") {
          this.safe({
            detail: await request("/products/" + d.product_id + "/shelf", {
              method: "POST",
              body: {
                shelf_status: d.shelf_status === "在售" ? "已下架" : "在售",
                reason: "用户主动调整商品上下架",
              },
            }),
          });
        }
        if (action === "delete-post") {
          await request("/posts/" + d.post_id, { method: "DELETE" });
          this.back();
        }
        if (action === "cancel-order")
          this.safe({
            detail: await this._mutation("/orders/" + d.order_id + "/cancel"),
          });
        if (action === "offline-handover" || action === "offline-receipt")
          this.safe({ detail: await this._mutation("/orders/" + d.order_id + (action === "offline-receipt" ? "/receipt" : "/shipping")) });
        if (action === "cancel-aftersale")
          this.safe({
            detail: await this._mutation(
              "/aftersales/" + d.aftersale_id + "/cancel",
            ),
          });
        if (action === "apply")
          await this._mutation("/tasks/" + d.reward_task_id + "/applications");
        if (action === "contact" || action === "contact-order") {
          const peer = action === "contact-order" ? (d.buyer_id === this.data.user.user_id ? d.seller_id : d.buyer_id) : d.seller.user_id;
          const chat = await request("/chat-sessions", {
            method: "POST",
            body: { peer_user_id: peer },
          });
          getApp().globalData.pendingChat = chat.session_id;
          await openPage("/pages/business/index?module=H");
        }
        if (action === "read-notice")
          await request("/me/notifications/" + d.notification_id + "/read", {
            method: "PUT",
          });
        if (action === "delete-notice") {
          await request("/me/notifications/" + d.notification_id, {
            method: "DELETE",
          });
          this.back();
        }
        if (this.data.detail) this.describe();
        await this.load();
      }, "操作成功");
    },
    async submit() {
      return this.work(async () => {
        const m = this.properties.module,
          mode = this.data.mode,
          d = this.data.detail;
        let body = b.values(this.data.fields),
          result;
        if (
          ["C", "B"].includes(m) &&
          ["create", "edit"].includes(mode) &&
          !this.data.files.length
        )
          throw new Error("请上传实际文件");
        if (mode === "buy") {
          result = await this._mutation("/orders/product", {
            ...body,
            product_id: d.product_id,
          });
          this.back();
          await openPage("/pages/business/index?module=G");
          return;
        }
        if (mode === "apply-aftersale") {
          const after = await this._mutation("/aftersales", {
            order_id: d.order_id,
            apply_amount: "0.00",
            apply_reason: body.apply_reason,
            evidence_refs: this.data.files,
          });
          await this.openRaw(after);
          await this.load();
          return;
        }
        if (mode === "proof") {
          if (!this.data.files.length) throw new Error("请上传完成凭证");
          await this._mutation("/tasks/" + d.reward_task_id + "/proofs", {
            ...body,
            evidence_refs: this.data.files,
          });
          await this.openRaw(d);
          return;
        }
        if (mode === "cancel-task") {
          await this._mutation("/tasks/" + d.reward_task_id + "/cancel", body);
          await this.openRaw(d);
          return;
        }
        if (mode === "cancel-application") {
          await this._mutation(
            "/task-applications/" + this.data.targetId + "/cancel",
            body,
          );
          this.back();
          await this.load();
          return;
        }
        if (mode === "report-message") {
          await this._mutation(
            "/messages/" + this.data.targetId + "/reports",
            body,
          );
          this.safe({ stage: "detail" });
          return;
        }
        if (mode === "create-chat") {
          const chat = await request("/chat-sessions", {
            method: "POST",
            body,
          });
          await this.openRaw(chat);
          return;
        }
        if (mode === "preferences") {
          body = {
            in_app_enabled: body.in_app_enabled,
            email_enabled: false,
            sms_enabled: false,
            dnd_start: body.dnd_start || null,
            dnd_end: body.dnd_end || null,
          };
          await request("/me/notification-preferences", {
            method: "PUT",
            body,
          });
          this.back();
          return;
        }
        if (m === "C") {
          body.image_refs = this.data.files;
          result = await this._mutation(
            "/products" +
              (this.data.editingId ? "/" + this.data.editingId : ""),
            body,
            this.data.editingId ? "PUT" : "POST",
          );
        }
        if (m === "D")
          result = await this._mutation(
            "/posts" + (this.data.editingId ? "/" + this.data.editingId : ""),
            body,
            this.data.editingId ? "PUT" : "POST",
          );
        if (["E", "F"].includes(m))
          result = await this._mutation("/tasks", body);
        if (m === "B") {
          body.evidence_refs = this.data.files;
          if (body.verification_type === "实名") {
            delete body.campus;
            delete body.student_no;
          }
          result = await this._mutation("/me/verifications", body);
        }
        if (m === "A") {
          const identity = getApp().globalData.token;
          const profile = {
            personal_bio: body.personal_bio,
            address: body.address,
            privacy_settings: {
              show_campus: body.show_campus,
              show_personal_bio: body.show_personal_bio,
            },
          };
          if (this.data.files.length) profile.avatar_ref = this.data.files[0];
          const saved = await request("/me", { method: "PUT", body: profile });
          if (!this._active || getApp().globalData.token !== identity) return;
          getApp().globalData.user = saved;
          this.safe({ user: saved });
          this.showProfile(saved);
          return;
        }
        if (m === "S") {
          if (body.new_password !== body.confirm_password)
            throw new Error("两次新密码不一致");
          delete body.confirm_password;
          await request("/me/password", { method: "POST", body });
          session.clear();
          await goLogin();
          return;
        }
        this.safe({ files: [] });
        if (result) await this.openRaw(result);
        await this.load();
      }, "已保存");
    },
    cancelForm() {
      if (this.data.detail)
        this.safe({ stage: "detail", fields: [], files: [] });
      else this.back();
    },
    cancelApplication(event) {
      this.safe({
        stage: "form",
        mode: "cancel-application",
        targetId: event.currentTarget.dataset.id,
        fields: [
          b.field("reason", "取消原因", "", { type: "textarea", max: 500 }),
        ],
        purpose: "",
      });
    },
    createChat() {
      this.safe({
        stage: "form",
        mode: "create-chat",
        fields: [b.field("peer_user_id", "对方用户编号")],
        purpose: "",
      });
    },
    async chatMessages(direction = "latest") {
      const id = this.data.detail?.session_id;
      if (!id) return;
      const before =
          direction === "before" ? this.data.messages[0]?.msg_id : null,
        after =
          direction === "after" ? this.data.messages.at(-1)?.msg_id : null;
      const result = await request(
        "/chat-sessions/" +
          id +
          "/messages?limit=50" +
          (before
            ? "&before_msg_id=" + before
            : after
              ? "&after_msg_id=" + after
              : ""),
      );
      if (this.data.detail?.session_id !== id) return;
      let rows = result.items;
      if (direction !== "latest") {
        const map = new Map(
          [...this.data.messages, ...rows].map((r) => [r.msg_id, r]),
        );
        rows = [...map.values()].sort((a, c) =>
          BigInt(a.msg_id) < BigInt(c.msg_id) ? -1 : 1,
        );
      }
      this.safe({
        messages: rows.map((r) => ({
          ...r,
          time: b.date(r.send_time),
          mine: r.sender_id === this.data.user.user_id,
          files: r.content_type === "图片" ? [r.content] : [],
        })),
        ...(direction !== "after" ? { older: result.has_more } : {}),
      });
    },
    olderMessages() {
      return this.work(() => this.chatMessages("before"));
    },
    async send() {
      return this.work(async () => {
        const content = this.data.messageText;
        if (!content?.trim()) throw new Error("请输入消息");
        await this._mutation(
          "/chat-sessions/" + this.data.detail.session_id + "/messages",
          { content_type: "文本", content },
        );
        this.safe({ messageText: "" });
        await this.chatMessages("after");
        await this.load();
      });
    },
    async sendImage() {
      return this.work(async () => {
        const identity = getApp().globalData.token,
          chat = this.data.detail.session_id;
        if (
          !this._pendingImage ||
          this._pendingImage.identity !== identity ||
          this._pendingImage.chat !== chat
        ) {
          const result = await new Promise((resolve, reject) =>
            wx.chooseMedia({
              count: 1,
              mediaType: ["image"],
              success: resolve,
              fail: () => reject(new Error("未选择图片")),
            }),
          );
          const file = await b.upload(
            result.tempFiles[0].tempFilePath,
            "CHAT_IMAGE",
          );
          this._pendingImage = { identity, chat, file };
        }
        await this._mutation(
          "/chat-sessions/" + this.data.detail.session_id + "/messages",
          { content_type: "图片", content: this._pendingImage.file.file_ref },
        );
        this._pendingImage = null;
        await this.chatMessages("after");
      });
    },
    messageAction(event) {
      const action = event.currentTarget.dataset.action,
        id = event.currentTarget.dataset.id;
      if (action === "report") {
        this.safe({
          stage: "form",
          mode: "report-message",
          targetId: id,
          purpose: "",
          fields: [
            b.field("reason", "举报原因", "", { type: "textarea", max: 500 }),
          ],
        });
        return;
      }
      return this.work(async () => {
        if (
          action === "delete" &&
          !(await confirm("仅删除自己一侧的消息显示。确认删除？"))
        )
          return;
        await request("/messages/" + id + (action === "read" ? "/read" : ""), {
          method: action === "read" ? "PUT" : "DELETE",
        });
        await this.chatMessages();
        await this.load();
      });
    },
    async loadHistory() {
      const id = this.data.detail.reward_task_id;
      const result = await request(
        "/tasks/" +
          id +
          "/" +
          this.data.historyKind +
          "?page=" +
          this.data.historyPage +
          "&page_size=10",
      );
      if (this.data.detail?.reward_task_id === id)
        this.safe({
          history: result.items.map((r) => ({
            ...r,
            time: b.date(r.submitted_at || r.operated_at),
          })),
          historyMore:
            BigInt(this.data.historyPage) * 10n < BigInt(result.total),
        });
    },
    historyTurn(event) {
      this.safe({
        historyPage:
          this.data.historyPage + Number(event.currentTarget.dataset.delta),
      });
      return this.work(() => this.loadHistory());
    },
    async preferences() {
      return this.work(async () => {
        const p = await request("/me/notification-preferences");
        this.safe({
          stage: "form",
          mode: "preferences",
          purpose: "",
          fields: [
            b.field("in_app_enabled", "接收站内通知", p.in_app_enabled, {
              type: "switch",
            }),
            b.field("dnd_start", "免打扰开始（HH:mm:ss）", p.dnd_start || "", {
              optional: true,
            }),
            b.field("dnd_end", "免打扰结束（HH:mm:ss）", p.dnd_end || "", {
              optional: true,
            }),
          ],
        });
      });
    },
  },
});
