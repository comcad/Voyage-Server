/** The admin page talking to the server: JSON both ways. */

let onSignedOut = () => {};
/** What to do when the server says the page isn't signed in any more. */
export function whenSignedOut(fn) { onSignedOut = fn; }

/** GET (or `method` with a `body`) /api<path>; the answer as JSON, or an Error with the server's own words. */
export async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(`/api${path}`, {
    method, credentials: 'same-origin',
    headers: { ...(method !== 'GET' ? { 'X-Voyage-Admin': '1' } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 && path !== '/login') { onSignedOut(); throw new Error('Signed out.'); }
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.error || `The server answered ${res.status}.`);
  return data;
}
