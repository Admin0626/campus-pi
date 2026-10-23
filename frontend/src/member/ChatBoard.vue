<script setup>
import { ref, reactive, onMounted, onUnmounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import MemberForm from "./MemberForm.vue";
import MemberFiles from "./MemberFiles.vue";
import FilePreview from "../admin/FilePreview.vue";
const props = defineProps({ user: Object });
const mode = ref("sessions"),
  selected = ref(null),
  messages = ref([]),
  older = ref(false),
  files = ref([]),
  reporting = ref(null);
const model = reactive({
  peer_user_id: "",
  content: "",
  reason: "",
  content_type: "文本",
});
const { state, run, mutate } = useOperation();
const list = usePaged(() =>
  mode.value === "reports" ? "/me/message-reports" : "/chat-sessions",
);
let generation = 0,
  timer;
async function fetchMessages(direction = "latest") {
  const id = selected.value?.session_id;
  if (!id) return;
  const version = generation;
  const boundary =
    direction === "before"
      ? messages.value[0]?.msg_id
      : direction === "after"
        ? messages.value.at(-1)?.msg_id
        : null;
  const q = new URLSearchParams({ limit: 50 });
  if (boundary)
    q.set(direction === "before" ? "before_msg_id" : "after_msg_id", boundary);
  const result = await api(`/chat-sessions/${id}/messages?${q}`);
  if (version !== generation || selected.value?.session_id !== id) return;
  if (direction === "latest") {
    messages.value = result.items;
    older.value = result.has_more;
  } else {
    const map = new Map(
      [...messages.value, ...result.items].map((row) => [row.msg_id, row]),
    );
    messages.value = [...map.values()].sort((a, b) =>
      BigInt(a.msg_id) < BigInt(b.msg_id) ? -1 : 1,
    );
    if (direction === "before") older.value = result.has_more;
  }
  return result;
}
async function open(row) {
  await run(async () => {
    generation++;
    selected.value = row;
    messages.value = [];
    older.value = false;
    reporting.value = null;
    model.content = "";
    files.value = [];
    await fetchMessages();
  });
}
async function create() {
  await run(async () => {
    const row = await api("/chat-sessions", {
      method: "POST",
      body: { peer_user_id: model.peer_user_id },
    });
    await list.load();
    generation++;
    selected.value = row;
    messages.value = [];
    older.value = false;
    reporting.value = null;
    model.content = "";
    files.value = [];
    await fetchMessages();
  });
}
async function send() {
  await run(async () => {
    const content =
      model.content_type === "图片" ? files.value[0] : model.content;
    if (!content) throw new Error("请输入内容或上传图片");
    await mutate(`/chat-sessions/${selected.value.session_id}/messages`, {
      content_type: model.content_type,
      content,
    });
    model.content = "";
    files.value = [];
    await fetchMessages("after");
    await list.load();
  }, "消息已发送");
}
async function read(row) {
  await run(async () => {
    await api("/messages/" + row.msg_id + "/read", { method: "PUT" });
    await fetchMessages();
    await list.load();
  });
}
async function remove(row) {
  if (
    !window.confirm(
      "只删除自己这侧的消息显示，另一方和举报证据仍保留。确认删除？",
    )
  )
    return;
  await run(async () => {
    await api("/messages/" + row.msg_id, { method: "DELETE" });
    await fetchMessages();
    await list.load();
  });
}
async function report() {
  await run(async () => {
    await mutate("/messages/" + reporting.value.msg_id + "/reports", {
      reason: model.reason,
    });
    reporting.value = null;
    model.reason = "";
  }, "举报已提交，可在举报记录查看处理结果");
}
onMounted(() => {
  const id = sessionStorage.getItem("campus-chat-session");
  sessionStorage.removeItem("campus-chat-session");
  if (id) open({ session_id: id, peer: { username: "校园同学" } });
  timer = setInterval(() => {
    if (!selected.value || document.hidden || state.busy) return;
    run(() => fetchMessages("after"));
  }, 10000);
});
onUnmounted(() => {
  generation++;
  clearInterval(timer);
});
</script>
<template>
  <MemberState
    :state="state"
    :loading="list.loading.value"
    :error="list.error.value"
  />
  <div class="member-toolbar">
    <button
      @click="
        mode = 'sessions';
        selected = null;
        generation++;
      "
    >
      会话</button
    ><button
      @click="
        mode = 'reports';
        selected = null;
        generation++;
      "
    >
      举报记录</button
    ><button
      :disabled="state.busy"
      @click="run(() => (selected ? fetchMessages('after') : list.load()))"
    >
      刷新
    </button>
  </div>
  <div v-if="selected" class="member-chat-view"
    ><h2>与 {{ selected.peer.username }} 的私信</h2>
    <p class="member-meta">
      每 10 秒检查新消息；只有点击“标记已读”才更新已读状态。
    </p>
    <div class="member-messages" role="log" aria-label="会话消息">
    <button
      v-if="older"
      :disabled="state.busy"
      @click="run(() => fetchMessages('before'))"
    >
      加载更早消息
    </button>
    <article
      v-for="row in messages"
      :key="row.msg_id"
      class="member-bubble"
      :class="{ mine: row.sender_id === user.user_id }"
    >
      <p v-if="row.content_type === '文本'">{{ row.content }}</p>
      <FilePreview v-else :files="[row.content]" mode="message" /><small class="member-meta"
        >{{ date(row.send_time) }} · {{ row.read_at ? "已读" : "未读" }}</small
      >
      <div class="member-actions">
        <button
          v-if="row.receiver_id === user.user_id && !row.read_at"
          :disabled="state.busy"
          @click="read(row)"
        >
          标记已读</button
        ><button :disabled="state.busy" @click="remove(row)">删除</button
        ><button
          @click="
            reporting = row;
            model.reason = '';
          "
        >
          举报
        </button>
      </div>
    </article>
    </div>
    <MemberForm
      v-if="reporting"
      :model="model"
      :fields="[
        { key: 'reason', label: '举报原因', type: 'textarea', max: 500 },
      ]"
      :busy="state.busy"
      @submit="report"
      @cancel="reporting = null"
    />
    <form v-else class="member-form member-chat-compose" @submit.prevent="send">
      <select v-model="model.content_type" aria-label="消息类型">
        <option>文本</option>
        <option>图片</option></select
      ><textarea
        v-if="model.content_type === '文本'"
        v-model="model.content"
        placeholder="输入消息"
        maxlength="2000"
        required
      /><MemberFiles
        v-else
        v-model="files"
        purpose="CHAT_IMAGE"
        :max="1"
        :disabled="state.busy"
      /><button class="member-primary" :disabled="state.busy">发送</button>
    </form></div
  ><template v-else
    ><form
      v-if="mode === 'sessions'"
      class="member-toolbar"
      @submit.prevent="create"
    >
      <input
        v-model="model.peer_user_id"
        placeholder="对方用户编号"
        pattern="[1-9][0-9]*"
        required
      /><button :disabled="state.busy">创建 / 打开会话</button>
    </form>
    <p
      v-if="
        !list.loading.value && !list.items.value.length && !list.error.value
      "
    >
      暂无记录
    </p>
    <article
      v-for="row in list.items.value"
      :key="row.session_id || row.report_id"
      class="member-card"
    >
      <template v-if="mode === 'sessions'"
        ><h3>{{ row.peer.username }} · 未读 {{ row.unread_count }}</h3>
        <p>{{ row.last_message || "还没有消息" }}</p>
        <button :disabled="state.busy" @click="open(row)">
          进入会话
        </button></template
      ><template v-else
        ><h3>举报 {{ row.report_id }} · {{ row.audit_status }}</h3>
        <p>{{ row.reason }}</p>
        <p>{{ row.audit_reason || "等待处理" }}</p></template
      >
    </article>
    <MemberPager
      :page="list.page.value"
      :total="list.total.value"
      :loading="list.loading.value"
      @turn="list.turn"
  /></template>
</template>
