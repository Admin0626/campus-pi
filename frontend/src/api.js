// 凭证只在当前标签页内存中保留；刷新需重新登录。持久化方案待团队确认。
let token = "";
let onExpired = () => {};
export const setToken = (value) => {
  token = value || "";
};
export const setExpiredHandler = (handler) => {
  onExpired = handler;
};
export const requestKey = () => crypto.randomUUID();
export async function api(
  path,
  { method = "GET", body, key, signal, reason } = {},
) {
  const headers = { Accept: "application/json" };
  const multipart = body instanceof FormData;
  if (body !== undefined && !multipart) headers["Content-Type"] = "application/json";
  const sentToken = token;
  if (sentToken) headers.Authorization = `Bearer ${sentToken}`;
  if (key) headers["Idempotency-Key"] = key;
  if (reason) headers["X-Action-Reason"] = encodeURIComponent(reason);
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener("abort", cancel, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(() => controller.abort(), 15000);
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
      signal: controller.signal,
      cache: "no-store",
    });
  } catch (error) {
    if (error.name === "AbortError")
      throw new Error("请求超时或已取消，请稍后重试");
    throw new Error("无法连接服务，请检查网络或稍后重试");
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", cancel);
  }
  let value;
  try {
    value = await response.json();
  } catch {
    throw new Error("服务响应异常，请稍后重试");
  }
  if (!response.ok) {
    const error = new Error(value.message || "请求失败");
    error.status = response.status;
    error.fields = value.errors || [];
    error.requestId = value.request_id;
    error.retryAfter = response.headers.get("Retry-After");
    if (response.status === 401 && !path.startsWith("/auth/") && sentToken && token === sentToken) {
      token = "";
      onExpired();
    }
    throw error;
  }
  return value.data;
}
