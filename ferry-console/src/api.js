// All HTTP goes through here: one base URL, one error shape ({ error, status } from the server).
const BASE = import.meta.env.VITE_FERRY_API || "";

async function handle(res) {
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!res.ok) {
    const msg = (body && body.error) || (typeof body === "string" && body) || `${res.status} ${res.statusText}`;
    throw new Error(msg);
  }
  return body;
}

export const api = {
  get: (path) => fetch(BASE + path).then(handle),
  post: (path, data) => fetch(BASE + path, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data ?? {}),
  }).then(handle),
  put: (path, data) => fetch(BASE + path, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data ?? {}),
  }).then(handle),
  upload: (path, formData) => fetch(BASE + path, { method: "POST", body: formData }).then(handle),
  /** Direct link for downloads (templates, exports). The server sets the file name. */
  href: (path) => BASE + path,
};
