import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  validateAnalytics,
  periodView,
  trendView,
  monthlyTrades,
  sumTrade,
  sumContent,
  analyticsCsv,
  changePercent,
  loadAnalytics,
  niceAxis,
  percent,
} from "../frontend/src/analytics/data.js";
const fixture = JSON.parse(
  fs.readFileSync(
    new URL("../frontend/public/mock/analytics.json", import.meta.url),
    "utf8",
  ),
);
const sum = (rows, fn) => rows.reduce((n, r) => n + fn(r), 0);
test("fixture declares synthetic data and accepts its continuous dated records", () => {
  assert.equal(validateAnalytics(fixture), fixture);
  assert.equal(fixture.source, "MOCK");
  assert.equal(fixture.daily.at(-1).date, fixture.asOf);
});
test("period totals, pie amounts, content quantities and comparison amounts reconcile with source rows", () => {
  for (const key of ["week", "month", "quarter"]) {
    const v = periodView(fixture, key);
    assert.equal(
      sum(v.trade, (c) => c.value),
      v.current.tradeAmountFen,
    );
    assert.equal(
      sum(v.content, (c) => c.value),
      v.current.contentTotal,
    );
    assert.equal(sum(v.rows, sumTrade), v.current.tradeAmountFen);
    assert.equal(sum(v.rows, sumContent), v.current.contentTotal);
    const previous = fixture.daily.filter(
      (r) => r.date >= v.comparisonStart && r.date <= v.comparisonEnd,
    );
    assert.equal(v.previous.tradeAmountFen, sum(previous, sumTrade));
    assert.equal(
      v.current.totalUsers,
      fixture.openingUsers + sum(fixture.daily, (r) => r.newUsers),
    );
    assert.ok(v.comparisonEnd < v.start);
  }
});
test("active users use explicit interval distinct counts, not daily sums", () => {
  const v = periodView(fixture, "month");
  assert.equal(v.current.activeUsers, fixture.periods.month.activeUsers);
  assert.notEqual(
    v.current.activeUsers,
    sum(v.rows, (r) => r.activeUsers),
  );
  assert.ok(v.current.activeUsers <= v.current.totalUsers);
});
test("week/month/year switch uses 7 daily, 30 daily and 12 monthly observations", () => {
  assert.equal(trendView(fixture, "week").length, 7);
  assert.equal(trendView(fixture, "month").length, 30);
  const year = trendView(fixture, "year");
  assert.equal(year.length, 12);
  for (const point of year) {
    assert.equal(point.activeUsers, fixture.monthlyActiveUsers[point.date]);
    assert.equal(
      point.newUsers,
      sum(
        fixture.daily.filter((r) => r.date.startsWith(point.date)),
        (r) => r.newUsers,
      ),
    );
  }
});
test("monthly bars reconcile to six calendar months including the partial final month", () => {
  const bars = monthlyTrades(fixture);
  assert.equal(bars.length, 6);
  assert.equal(bars.at(-1).date, fixture.asOf.slice(0, 7));
  for (const bar of bars)
    assert.equal(
      bar.value,
      sum(
        fixture.daily.filter((r) => r.date.startsWith(bar.date)),
        sumTrade,
      ),
    );
});
test("comparisons and empty charts handle zeros without NaN or Infinity", () => {
  assert.equal(changePercent(0, 0), 0);
  assert.equal(changePercent(3, 0), null);
  assert.equal(changePercent(0, 4), -100);
  assert.equal(changePercent(125, 100), 25);
  assert.equal(percent(0, 0), "0.0%");
  assert.ok(niceAxis(0).max > 0);
  assert.ok(niceAxis(1700).max >= 1700);
});
test("CSV includes UTF8 BOM, source, exact period, fen units and matching category breakdown", () => {
  const view = periodView(fixture, "month"),
    csv = analyticsCsv(fixture, view);
  assert.ok(csv.startsWith("\uFEFF"));
  assert.ok(csv.includes("非真实业务统计"));
  assert.ok(csv.includes(view.start));
  assert.ok(csv.includes(view.end));
  assert.ok(csv.includes("交易额（分）"));
  assert.ok(csv.includes('"' + view.current.tradeAmountFen + '"'));
  for (const c of view.trade)
    assert.ok(csv.includes('"' + c.name + '","' + c.value + '"'));
});
test("rejects non-mock, negative amounts, duplicate dates and invalid distinct counts", () => {
  for (const mutate of [
    (d) => (d.source = "LIVE"),
    (d) => (d.daily[0].tradeAmountFen.books = -1),
    (d) => (d.daily[1].date = d.daily[0].date),
    (d) => (d.periods.week.activeUsers = Number.MAX_SAFE_INTEGER),
    (d) => (d.monthlyActiveUsers["2026-10"] = Number.MAX_SAFE_INTEGER),
  ]) {
    const invalid = structuredClone(fixture);
    mutate(invalid);
    assert.throws(() => validateAnalytics(invalid), /模拟JSON/);
  }
});
test("local loader does not fall back to invented values on HTTP, syntax or schema failure", async () => {
  await assert.rejects(
    loadAnalytics({ fetcher: async () => ({ ok: false, status: 503 }) }),
    /503/,
  );
  await assert.rejects(
    loadAnalytics({
      fetcher: async () => ({
        ok: true,
        json: async () => {
          throw new SyntaxError("bad JSON");
        },
      }),
    }),
    /模拟JSON/,
  );
  await assert.rejects(
    loadAnalytics({
      fetcher: async () => ({
        ok: true,
        json: async () => ({ source: "MOCK" }),
      }),
    }),
    /模拟JSON/,
  );
  let address;
  await loadAnalytics({
    fetcher: async (url) => {
      address = url;
      return { ok: true, json: async () => fixture };
    },
  });
  assert.equal(address, "/mock/analytics.json");
});
test("aborted loads remain aborts for component cleanup", async () => {
  await assert.rejects(
    loadAnalytics({
      fetcher: async () => {
        throw new DOMException("aborted", "AbortError");
      },
    }),
    { name: "AbortError" },
  );
});

test("rejects aggregate amounts beyond safe integer precision", () => {
  const d = structuredClone(fixture);
  d.daily.forEach((row) => (row.tradeAmountFen.books = 100000000000000));
  assert.throws(() => validateAnalytics(d), /模拟JSON/);
});
test("rejects conflicting distinct counts for the same date interval", () => {
  const d = structuredClone(fixture);
  assert.equal(d.periods.month.start, d.periods.quarter.start);
  d.periods.quarter.activeUsers--;
  assert.throws(() => validateAnalytics(d), /模拟JSON/);
});
test("CSV neutralizes formula-like labels from an edited local fixture", () => {
  const d = structuredClone(fixture);
  d.tradeCategories[0].name = "=1+1";
  assert.ok(analyticsCsv(d, periodView(d, "week")).includes('"\'=1+1"'));
});
