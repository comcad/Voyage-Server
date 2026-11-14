/**
 * The server's admin page: signing in, and the tabs. A tab is made the first time it is opened and kept, so going to
 * another and back loses nothing (settings not yet saved stay where they were).
 */
import { h, fill, toast } from './ui.js';
import { api, whenSignedOut } from './api.js';
import { serverTab } from './server.js';
import { playersTab } from './players.js';

const TABS = [
  { id: 'server', name: 'Server', make: serverTab },
  { id: 'players', name: 'Players', make: playersTab },
];
const root = document.getElementById('app');

async function start() {
  let session;
  try { session = await api('/session'); } catch (e) { fill(root, h('main', { class: 'signin' }, h('h1', null, 'Voyage server'), h('p', { class: 'error' }, e.message))); return; }
  if (session.in) shell(); else signIn();
}

function signIn(message = '') {
  const pass = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', required: true, 'aria-label': 'Password' });
  const error = h('p', { class: 'error', role: 'alert' }, message);
  const button = h('button', { class: 'primary', type: 'submit' }, 'Sign in');
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      button.disabled = true; error.textContent = '';
      try { await api('/login', { method: 'POST', body: { password: pass.value } }); shell(); }
      catch (err) { error.textContent = err.message; button.disabled = false; pass.select(); }
    },
  }, h('label', null, 'Password', pass), button, error);
  fill(root, h('main', { class: 'signin' }, h('h1', null, 'Voyage server'), h('p', { class: 'muted' }, 'Sign in with the password set as ADMIN_PASSWORD (in config.yaml, or in the environment).'), form));
  pass.focus();
}

function shell() {
  const made = new Map();
  let current = null;
  const body = h('div', { class: 'tab-body' });
  const links = TABS.map((t) => h('a', { href: `#${t.id}`, role: 'tab', id: `tab-${t.id}` }, t.name));
  const open = () => {
    const tab = TABS.find((t) => t.id === location.hash.slice(1)) || TABS[0];
    if (current === tab) return;
    if (current) { const was = made.get(current.id); was.el.hidden = true; was.tab.hide?.(); }
    current = tab;
    let m = made.get(tab.id);
    if (!m) {
      const el = h('div', { class: `tab tab-${tab.id}`, role: 'tabpanel', 'aria-labelledby': `tab-${tab.id}` });
      body.append(el);
      m = { el, tab: tab.make(el) };
      made.set(tab.id, m);
    }
    m.el.hidden = false;
    m.tab.show?.();
    links.forEach((a, i) => { const on = TABS[i] === tab; a.classList.toggle('on', on); a.setAttribute('aria-selected', on); });
    document.title = `${tab.name} - Voyage server admin`;
  };
  // leaving the page with settings not yet saved asks first
  addEventListener('beforeunload', (e) => { if ([...made.values()].some((m) => m.tab.unsaved?.())) { e.preventDefault(); e.returnValue = ''; } });
  whenSignedOut(() => { removeEventListener('hashchange', open); for (const m of made.values()) m.tab.hide?.(); signIn('You were signed out (or the server started again). Sign in again.'); });
  const out = h('button', { class: 'quiet', onclick: async () => { try { await api('/logout', { method: 'POST' }); } catch { /* signed out either way */ } location.reload(); } }, 'Sign out');
  fill(root,
    h('header', { class: 'top' }, h('div', { class: 'brand' }, 'Voyage server'), h('nav', { role: 'tablist', 'aria-label': 'Sections' }, links), h('div', { class: 'top-right' }, out)),
    body);
  addEventListener('hashchange', open);
  open();
}

addEventListener('unhandledrejection', (e) => { if (e.reason?.message && e.reason.message !== 'Signed out.') toast(e.reason.message, true); });
start();
