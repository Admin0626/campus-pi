<script setup>
import AdminPageHeader from "./admin/AdminPageHeader.vue";
import AdminPagination from "./admin/AdminPagination.vue";
import Icon from "./Icon.vue";
defineProps({
  users: Array,
  total: String,
  page: Number,
  pageSize: Number,
  filters: Object,
  currentId: String,
  loading: Boolean,
});
const emit = defineEmits([
  "search",
  "reset",
  "detail",
  "status",
  "logs",
  "page",
  "size",
  "unavailable",
]);
const date = (value) =>
  value
    ? new Intl.DateTimeFormat("sv-SE", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(new Date(value))
    : "—";
const tip = (name) =>
  emit("unavailable", `${name}还在开发，尚未新增相应业务规则。`);
</script>
<template>
  <section class="word-user-management">
    <AdminPageHeader title="用户列表" :subtitle="`共${total}名用户`"
      ><template #actions>
        <button @click="tip('意见反馈管理')">
          <Icon name="file" />意见反馈管理</button
        ><button class="word-batch" @click="tip('批量操作')">
          <Icon name="grid" />批量操作⌄</button
        ><button @click="tip('新增用户')">
          <Icon name="plus" />新增用户
        </button>
      </template></AdminPageHeader
    >
    <form class="word-user-filters" @submit.prevent="emit('search')">
      <div class="word-filter-name">
        <label for="search">用户名/id搜索</label
        ><input
          id="search"
          v-model="filters.keyword"
          aria-label="用户名"
          placeholder="输入用户名或用户id"
          maxlength="100"
          title="当前接口支持用户名关键词，ID搜索待契约确认"
        />
      </div>
      <div class="word-filter-state">
        <label for="status">账号状态</label
        ><select id="status" v-model="filters.account_status">
          <option value="">全部状态</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
      </div>
      <div class="word-filter-range">
        <label>注册时间范围</label>
        <div>
          <input
            aria-label="注册起始时间（开发中）"
            type="date"
            readonly
            @click="tip('注册时间范围筛选')"
          /><span>至</span
          ><input
            aria-label="注册结束时间（开发中）"
            type="date"
            readonly
            @click="tip('注册时间范围筛选')"
          />
        </div>
      </div>
      <div class="word-filter-actions">
        <button type="button" @click="emit('reset')">
          <Icon name="rotate" />重置</button
        ><button :disabled="loading"><Icon name="search" />搜索</button>
      </div>
    </form>
    <div class="word-user-table-wrap">
      <div v-if="loading" class="word-table-loading" role="status">
        正在加载用户…
      </div>
      <table v-else class="word-user-table">
        <colgroup>
          <col style="width: 4.3%" />
          <col style="width: 13.2%" />
          <col style="width: 14.5%" />
          <col style="width: 12.5%" />
          <col style="width: 14%" />
          <col style="width: 8.5%" />
          <col style="width: 16%" />
          <col style="width: 17%" />
        </colgroup>
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                aria-label="全选（批量操作开发中）"
                @click.prevent="tip('批量操作')"
              />
            </th>
            <th>用户ID<span class="word-sort">◆</span></th>
            <th>手机号</th>
            <th>姓名<span class="word-sort">◆</span></th>
            <th>注册时间<span class="word-sort">◆</span></th>
            <th>账号状态</th>
            <th>最后登录时间<span class="word-sort">◆</span></th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in users" :key="item.user_id">
            <td>
              <input
                type="checkbox"
                :aria-label="'选择用户' + item.username + '（开发中）'"
                @click.prevent="tip('批量操作')"
              />
            </td>
            <td>{{ item.user_id }}</td>
            <td>{{ item.phone || "—" }}</td>
            <td>
              <span class="word-table-avatar"></span
              ><span :title="'账号展示名：' + item.username">{{
                item.username
              }}</span>
            </td>
            <td>{{ date(item.registration_time) }}</td>
            <td>
              <span
                class="word-state"
                :class="
                  item.account_status === 'ACTIVE' ? 'enabled' : 'disabled'
                "
                >{{ item.account_status === "ACTIVE" ? "启用" : "禁用" }}</span
              >
            </td>
            <td title="接口尚未提供最后登录时间">—</td>
            <td>
              <div class="word-row-actions">
                <button class="view" @click="emit('detail', item)">
                  <Icon name="eye" />查看</button
                ><button class="edit" @click="tip('管理员编辑用户')">
                  <Icon name="edit" />编辑</button
                ><button
                  :class="
                    item.account_status === 'ACTIVE' ? 'disable' : 'enable'
                  "
                  :disabled="item.user_id === currentId"
                  @click="emit('status', item)"
                >
                  <Icon
                    :name="item.account_status === 'ACTIVE' ? 'close' : 'check'"
                  />{{
                    item.account_status === "ACTIVE" ? "禁用" : "启用"
                  }}</button
                ><button class="logs" @click="emit('logs', item)">
                  <Icon name="file" />日志
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!users.length">
            <td colspan="8">
              <div class="empty-state">没有找到符合条件的用户</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <AdminPagination
      :page="page"
      :total="total"
      :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :disabled="loading"
      @turn="emit('page', page + $event)"
      @size="emit('size', $event)"
    />
  </section>
</template>
