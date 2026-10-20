// Local JSON adapter. No analytics API or database is called by this module.
const DAY = 86400000;
export const MOCK_URL = "/mock/analytics.json";
export const PERIOD_KEYS = ["week", "month", "quarter"];
export const SERIES = [
  { id: "newUsers", name: "新增用户", color: "#6253ff" },
  { id: "activeUsers", name: "活跃用户", color: "#00a57b" },
];
export const countFormat = (value) =>
  new Intl.NumberFormat("zh-CN").format(value);
export const moneyFormat = (fen, digits = 2) =>
  "¥" +
  new Intl.NumberFormat("zh-CN", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(fen / 100);
const validDate = (value) =>
  typeof value === "string" &&
  /^\d{4}-\d{2}-\d{2}$/.test(value) &&
  Number.isFinite(Date.parse(value)) &&
  new Date(value + "T00:00:00Z").toISOString().slice(0, 10) === value;
const integer = (value) => Number.isSafeInteger(value) && value >= 0;
function ensure(condition) {
  if (!condition)
    throw new Error("模拟JSON结构或数值不正确，请检查 analytics.json");
}
const dayShift = (date, days) =>
  new Date(Date.parse(date + "T00:00:00Z") + days * DAY)
    .toISOString()
    .slice(0, 10);
const sum = (rows, get) => rows.reduce((n, row) => n + get(row), 0);
export const sumTrade = (row) =>
  sum(Object.values(row.tradeAmountFen), (n) => n);
export const sumContent = (row) =>
  sum(Object.values(row.contentCounts), (n) => n);
export const between = (data, start, end) =>
  data.daily.filter((row) => row.date >= start && row.date <= end);
export function validateAnalytics(data) {
  ensure(
    data &&
      data.schemaVersion === 1 &&
      data.source === "MOCK" &&
      data.currency === "CNY" &&
      data.amountUnit === "fen" &&
      data.timeZone === "Asia/Shanghai",
  );
  ensure(validDate(data.asOf) && integer(data.openingUsers));
  for (const name of ["tradeCategories", "contentCategories"]) {
    const categories = data[name];
    ensure(
      Array.isArray(categories) &&
        categories.length > 0 &&
        categories.length <= 12,
    );
    ensure(new Set(categories.map((c) => c.id)).size === categories.length);
    ensure(
      categories.every(
        (c) =>
          typeof c.id === "string" &&
          /^[a-z]+$/.test(c.id) &&
          typeof c.name === "string" &&
          c.name.length > 0 &&
          c.name.length < 30 &&
          /^#[0-9a-f]{6}$/i.test(c.color),
      ),
    );
  }
  ensure(
    Array.isArray(data.daily) &&
      data.daily.length >= 30 &&
      data.daily.length < 1000 &&
      data.daily.at(-1).date === data.asOf,
  );
  let population = data.openingUsers;
  const monthly = {};
  data.daily.forEach((row, i) => {
    ensure(
      validDate(row.date) &&
        (!i || row.date === dayShift(data.daily[i - 1].date, 1)),
    );
    ensure(integer(row.newUsers) && integer(row.activeUsers));
    population += row.newUsers;
    ensure(Number.isSafeInteger(population) && row.activeUsers <= population);
    for (const [field, categories] of [
      ["tradeAmountFen", data.tradeCategories],
      ["contentCounts", data.contentCategories],
    ]) {
      ensure(
        row[field] && Object.keys(row[field]).length === categories.length,
      );
      ensure(categories.every((c) => integer(row[field][c.id])));
      ensure(Number.isSafeInteger(sum(Object.values(row[field]), (n) => n)));
    }
    (monthly[row.date.slice(0, 7)] ||= []).push(row);
  });
  ensure(
    Number.isSafeInteger(sum(data.daily, sumTrade)) &&
      Number.isSafeInteger(sum(data.daily, sumContent)),
  );
  ensure(
    data.monthlyActiveUsers &&
      Object.keys(monthly).every((month) => {
        const rows = monthly[month],
          unique = data.monthlyActiveUsers[month];
        const total =
          data.openingUsers +
          sum(
            data.daily.filter((r) => r.date <= rows.at(-1).date),
            (r) => r.newUsers,
          );
        return (
          integer(unique) &&
          unique >= Math.max(...rows.map((r) => r.activeUsers)) &&
          unique <=
            Math.min(
              total,
              sum(rows, (r) => r.activeUsers),
            )
        );
      }),
  );
  const distinctWindows = new Map();
  for (const key of PERIOD_KEYS) {
    const period = data.periods?.[key];
    ensure(period && typeof period.label === "string");
    for (const field of ["start", "end", "comparisonStart", "comparisonEnd"])
      ensure(validDate(period[field]));
    ensure(
      period.end === data.asOf &&
        period.start <= period.end &&
        period.comparisonStart <= period.comparisonEnd &&
        period.comparisonEnd < period.start &&
        period.comparisonStart >= data.daily[0].date,
    );
    for (const [a, b, field] of [
      [period.start, period.end, "activeUsers"],
      [period.comparisonStart, period.comparisonEnd, "comparisonActiveUsers"],
    ]) {
      const rows = between(data, a, b),
        active = period[field];
      const windowKey = a + "/" + b;
      ensure(
        !distinctWindows.has(windowKey) ||
          distinctWindows.get(windowKey) === active,
      );
      distinctWindows.set(windowKey, active);
      const populationAtEnd =
        data.openingUsers +
        sum(
          data.daily.filter((r) => r.date <= b),
          (r) => r.newUsers,
        );
      ensure(
        rows.length > 0 &&
          integer(active) &&
          active >= Math.max(...rows.map((r) => r.activeUsers)) &&
          active <=
            Math.min(
              populationAtEnd,
              sum(rows, (r) => r.activeUsers),
            ),
      );
    }
  }
  return data;
}
export async function loadAnalytics({
  signal,
  fetcher = globalThis.fetch,
} = {}) {
  const response = await fetcher(MOCK_URL, { signal, cache: "no-store" });
  if (!response.ok)
    throw new Error("模拟数据加载失败（HTTP " + response.status + "），请重试");
  try {
    return validateAnalytics(await response.json());
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error("模拟JSON无法读取或格式不正确，请检查 analytics.json");
  }
}
function snapshot(data, start, end, activeUsers) {
  const rows = between(data, start, end);
  return {
    totalUsers:
      data.openingUsers +
      sum(
        data.daily.filter((r) => r.date <= end),
        (r) => r.newUsers,
      ),
    activeUsers,
    tradeAmountFen: sum(rows, sumTrade),
    contentTotal: sum(rows, sumContent),
    newUsers: sum(rows, (r) => r.newUsers),
  };
}
export function periodView(data, key) {
  const period = data.periods[key];
  const current = snapshot(data, period.start, period.end, period.activeUsers);
  const previous = snapshot(
    data,
    period.comparisonStart,
    period.comparisonEnd,
    period.comparisonActiveUsers,
  );
  const rows = between(data, period.start, period.end);
  const categories = (list, field) =>
    list.map((c) => ({ ...c, value: sum(rows, (r) => r[field][c.id]) }));
  return {
    ...period,
    key,
    current,
    previous,
    rows,
    trade: categories(data.tradeCategories, "tradeAmountFen"),
    content: categories(data.contentCategories, "contentCounts"),
  };
}
export function changePercent(current, previous) {
  return previous === 0
    ? current === 0
      ? 0
      : null
    : ((current - previous) / previous) * 100;
}
export function trendView(data, range) {
  if (range === "year") {
    return Object.keys(data.monthlyActiveUsers)
      .sort()
      .slice(-12)
      .map((month) => ({
        date: month,
        label: Number(month.slice(5)) + "月",
        newUsers: sum(
          data.daily.filter((r) => r.date.startsWith(month)),
          (r) => r.newUsers,
        ),
        activeUsers: data.monthlyActiveUsers[month],
      }));
  }
  const days = range === "week" ? 7 : 30;
  return between(data, dayShift(data.asOf, -(days - 1)), data.asOf).map(
    (row) => ({
      ...row,
      label: Number(row.date.slice(5, 7)) + "/" + Number(row.date.slice(8)),
    }),
  );
}
export function monthlyTrades(data) {
  return Object.keys(data.monthlyActiveUsers)
    .sort()
    .slice(-6)
    .map((month) => ({
      date: month,
      label: Number(month.slice(5)) + "月",
      value: sum(
        data.daily.filter((r) => r.date.startsWith(month)),
        sumTrade,
      ),
    }));
}
export function percent(value, total) {
  return total > 0 ? ((value / total) * 100).toFixed(1) + "%" : "0.0%";
}
export function niceAxis(max) {
  if (!(max > 0)) return { max: 4, ticks: [0, 1, 2, 3, 4] };
  const raw = max / 4,
    magnitude = 10 ** Math.floor(Math.log10(raw));
  const step = Math.max(1, Math.ceil(raw / magnitude) * magnitude);
  const upper = step * 4;
  return { max: upper, ticks: [0, step, step * 2, step * 3, upper] };
}
const csvCell = (value) => {
  const text = String(value);
  return (
    '"' +
    (typeof value === "string" && /^[=+@-]/.test(text)
      ? "'" + text
      : text
    ).replaceAll('"', '""') +
    '"'
  );
};
export function analyticsCsv(data, view) {
  const lines = [
    ["数据来源", "JSON模拟数据，非真实业务统计"],
    ["模拟统计日期", data.asOf],
    ["统计区间", view.start, view.end],
    ["对比区间", view.comparisonStart, view.comparisonEnd],
    ["指标", "当前值", "对比值", "单位"],
    [
      "总用户数（截至区间末）",
      view.current.totalUsers,
      view.previous.totalUsers,
      "人",
    ],
    [
      "活跃用户（区间去重）",
      view.current.activeUsers,
      view.previous.activeUsers,
      "人",
    ],
    [
      "交易总额",
      view.current.tradeAmountFen,
      view.previous.tradeAmountFen,
      "分",
    ],
    [
      "内容总量（区间发布）",
      view.current.contentTotal,
      view.previous.contentTotal,
      "条",
    ],
    [],
    [
      "日期",
      "新增用户（人）",
      "日活跃用户（人）",
      "交易额（分）",
      "发布内容（条）",
    ],
    ...view.rows.map((r) => [
      r.date,
      r.newUsers,
      r.activeUsers,
      sumTrade(r),
      sumContent(r),
    ]),
    [],
    ["交易分类", "交易额（分）"],
    ...view.trade.map((c) => [c.name, c.value]),
    [],
    ["内容分类", "内容量（条）"],
    ...view.content.map((c) => [c.name, c.value]),
  ];
  return "\uFEFF" + lines.map((row) => row.map(csvCell).join(",")).join("\r\n");
}
