/**
 * Server: how it is doing (who is on, in each world and channel), a notice to everyone, Restart and Shut down, the
 * worlds (channels, rates, messages) and every other setting of config.yaml, and the console.
 *
 * Settings are changed here and saved together; the server reads its settings when it starts, so saving offers the
 * restart.
 */
import { h, fill, fmt, toast, dialog } from './ui.js';
import { api } from './api.js';

const SHOWN = 2000;   // console lines on the page at once
const RATES = [['exp_rate', 'EXP'], ['meso_rate', 'Meso'], ['drop_rate', 'Drop'], ['boss_drop_rate', 'Boss drop'], ['quest_rate', 'Quest'], ['fishing_rate', 'Fishing'], ['travel_rate', 'Travel']];
const FLAGS = ['Nothing', 'Event', 'New', 'Hot'];
const words = (key) => key.toLowerCase().replace(/_/g, ' ').replace(/^use /, '').replace(/^./, (c) => c.toUpperCase());

export function serverTab(host) {
  let status = null, settings = null, file = '', timer = null, logTimer = null, shown = false, lastLine = 0, busy = false;
  const edits = { server: new Map(), worlds: new Map() };   // setting -> value; world index -> Map(field -> value)
  const count = () => edits.server.size + [...edits.worlds.values()].reduce((n, m) => n + m.size, 0);
  const confirmBox = (title, text, go) => dialog(title, (close) => [h('p', null, text), h('div', { class: 'dialog-foot' }, h('button', { type: 'button', onclick: () => close(false) }, 'Cancel'), h('button', { type: 'button', class: 'primary', onclick: () => close(true) }, go))]);

  // ---------------------------------------------------------------- running
  const statusEl = h('div', { class: 'facts-wrap' }), buttons = h('div', { class: 'toolbar' });
  const act = async (action, ask) => {
    if (busy || !(await confirmBox(ask.title, ask.text, ask.go))) return;
    busy = true; paintStatus();
    try {
      await api('/control', { method: 'POST', body: { action } });
      toast(action === 'restart' ? 'Restarting. It takes about a minute; then sign in again.' : 'Shutting down.');
    } catch (e) { toast(e.message, true); busy = false; }
    paintStatus();
  };
  function paintStatus() {
    if (!status) { fill(statusEl, h('p', { class: 'muted' }, busy ? 'Waiting for the server...' : 'No answer.')); fill(buttons); return; }
    fill(statusEl, h('dl', { class: 'facts' },
      h('dt', null, 'Server'), h('dd', null, h('span', { class: `chip ${status.online ? 'ok' : 'off'}` }, status.online ? 'online' : 'starting'), ` up ${fmt.span(Date.now() - status.since)}`),
      h('dt', null, 'Playing'), h('dd', null, `${status.players}`),
      status.worlds.map((w) => [h('dt', null, w.name), h('dd', null, `${w.players} playing; channels ${w.channels.map((c) => `${c.id}: ${c.players}`).join(', ')}`)]),
      h('dt', null, 'Memory'), h('dd', null, `${fmt.bytes(status.memory.used)} of ${fmt.bytes(status.memory.max)}`),
      h('dt', null, 'Game data'), h('dd', null, h('code', null, status.assets), ' with the customs in ', h('code', null, status.customs))));
    fill(buttons,
      h('button', { type: 'button', disabled: busy, onclick: () => act('restart', { title: 'Restart the server?', text: 'Everyone playing is disconnected and can come back in about a minute.', go: 'Restart' }) }, 'Restart'),
      h('button', { type: 'button', class: 'danger', disabled: busy, onclick: () => act('shutdown', { title: 'Shut the server down?', text: 'Everyone playing is disconnected, and this page goes with it: the server has to be started again from where it runs.', go: 'Shut down' }) }, 'Shut down'),
      busy ? h('span', { class: 'muted' }, 'Working...') : null);
  }
  const noticeBox = h('input', { type: 'text', maxlength: 200, placeholder: 'A notice everyone in the game sees', 'aria-label': 'Notice' });
  const notice = h('form', { class: 'toolbar', onsubmit: async (e) => {
    e.preventDefault();
    if (!noticeBox.value.trim()) return;
    try { await api('/notice', { method: 'POST', body: { text: noticeBox.value } }); toast('Sent.'); noticeBox.value = ''; } catch (err) { toast(err.message, true); }
  } }, noticeBox, h('button', { type: 'submit' }, 'Send'));

  // ---------------------------------------------------------------- settings
  const saveBar = h('div', { class: 'savebar', hidden: true });
  const paintSave = () => {
    const n = count();
    saveBar.hidden = !n;
    if (n) fill(saveBar, h('span', null, `${n} change${n === 1 ? '' : 's'} not saved`),
      h('button', { type: 'button', onclick: () => { edits.server.clear(); edits.worlds.clear(); paintSettings(); paintSave(); } }, 'Discard'),
      h('button', { type: 'button', class: 'primary', onclick: save }, 'Save'));
  };
  async function save() {
    const body = { server: Object.fromEntries(edits.server), worlds: [...edits.worlds].map(([index, m]) => ({ index, fields: Object.fromEntries(m) })) };
    try {
      const r = await api('/settings', { method: 'PUT', body });
      settings = r.settings; edits.server.clear(); edits.worlds.clear(); paintSettings(); paintSave();
      if (!r.changed) toast('Nothing to change.');
      else if (await confirmBox('Saved', `${r.changed} line${r.changed === 1 ? '' : 's'} changed in the settings file. The server reads it when it starts: restart it now? Everyone playing is disconnected for about a minute.`, 'Restart now')) act('restart', { title: 'Restart the server?', text: 'Everyone playing is disconnected and can come back in about a minute.', go: 'Restart' });
      else toast('Saved. It takes effect the next time the server starts.');
    } catch (e) { toast(e.message, true); }
  }
  /** An input for one value: a checkbox, a number or a line of text, by the kind the file has. */
  const input = (kind, value, changed, onset, label, secret = false) => {
    const mark = (el, on) => el.closest?.('.setting, td, .field')?.classList.toggle('changed', on);
    if (kind === 'bool') {
      const el = h('input', { type: 'checkbox', checked: (changed ?? value) === true, 'aria-label': label, onchange: () => { onset(el.checked === value ? undefined : el.checked); mark(el, el.checked !== value); } });
      return el;
    }
    const num = kind === 'int' || kind === 'number';
    const el = h('input', {
      type: secret ? 'password' : num ? 'number' : 'text', step: kind === 'number' ? 'any' : num ? 1 : null, value: changed ?? value, 'aria-label': label, autocomplete: 'off',
      placeholder: secret ? 'unchanged' : null, class: num ? 'num' : null,
      oninput: () => {
        const v = num ? (el.value === '' ? NaN : Number(el.value)) : el.value;
        const same = secret ? el.value === '' : v === value;
        if (num && !Number.isFinite(v)) { el.setCustomValidity('A number'); return; }
        el.setCustomValidity('');
        onset(same ? undefined : v); mark(el, !same);
      },
    });
    return el;
  };
  const typed = (o) => (o.kind === 'bool' ? o.value === 'true' : o.kind === 'int' || o.kind === 'number' ? Number(o.value) : o.value);
  const setServer = (key) => (v) => { if (v === undefined) edits.server.delete(key); else edits.server.set(key, v); paintSave(); if (key === 'WORLDS') paintWorlds(); };
  const setWorld = (index, key) => (v) => {
    const m = edits.worlds.get(index) || new Map();
    if (v === undefined) m.delete(key); else m.set(key, v);
    if (m.size) edits.worlds.set(index, m); else edits.worlds.delete(index);
    paintSave();
  };

  const worldsEl = h('div'), groupsEl = h('div', { class: 'settings' });
  const find = h('input', { type: 'search', placeholder: 'Find a setting', 'aria-label': 'Find a setting', oninput: () => paintGroups() });
  const option = (key) => settings.groups.flatMap((g) => g.options).find((o) => o.key === key);
  function paintWorlds() {
    const worldsOpt = option('WORLDS'), max = settings.worlds.length;
    const n = Math.max(1, Math.min(max, Number(edits.server.get('WORLDS') ?? worldsOpt?.value ?? 1)));
    const cell = (w, key, kind, label) => {
      const f = w.fields[key], value = f ? (kind === 'text' ? f.value : Number(f.value)) : (kind === 'text' ? '' : NaN);
      const el = input(kind, Number.isNaN(value) ? '' : value, edits.worlds.get(w.index)?.get(key), setWorld(w.index, key), `${w.name}: ${label}`);
      if (!f && kind !== 'text') el.placeholder = 'default';
      return h('td', { class: edits.worlds.get(w.index)?.has(key) ? 'changed' : null }, el);
    };
    fill(worldsEl,
      h('div', { class: 'toolbar' }, h('label', { class: 'mini' }, 'Worlds ', worldsOpt ? input('int', Number(worldsOpt.value), edits.server.get('WORLDS'), setServer('WORLDS'), 'Number of worlds') : null),
        h('span', { class: 'muted' }, `of the ${max} the settings file describes. Rates are multipliers of the game's own.`)),
      h('div', { class: 'table-wrap' }, h('table', { class: 'table worlds' },
        h('thead', null, h('tr', null, ['World', 'Channels', ...RATES.map((r) => r[1]), 'Flag', 'Scrolling message', 'Event message'].map((t) => h('th', null, t)))),
        h('tbody', null, settings.worlds.slice(0, n).map((w) => h('tr', null,
          h('th', { scope: 'row' }, w.name), cell(w, 'channels', 'int', 'channels'), RATES.map(([k, label]) => cell(w, k, 'int', label)),
          h('td', { class: edits.worlds.get(w.index)?.has('flag') ? 'changed' : null }, (() => {
            const was = Number(w.fields.flag?.value ?? 0);
            const sel = h('select', { 'aria-label': `${w.name}: flag`, onchange: () => { const v = Number(sel.value); setWorld(w.index, 'flag')(v === was ? undefined : v); sel.closest('td').classList.toggle('changed', v !== was); } },
              FLAGS.map((name, i) => h('option', { value: i, selected: i === (edits.worlds.get(w.index)?.get('flag') ?? was) }, name)));
            return sel;
          })()),
          cell(w, 'server_message', 'text', 'scrolling message'), cell(w, 'event_message', 'text', 'event message')))))));
  }
  function paintGroups() {
    const q = find.value.trim().toLowerCase();
    const hit = (o) => !q || o.key.toLowerCase().includes(q) || words(o.key).toLowerCase().includes(q) || (o.note || '').toLowerCase().includes(q);
    const groups = settings.groups.map((g) => ({ ...g, options: g.options.filter((o) => o.key !== 'WORLDS' && hit(o)) })).filter((g) => g.options.length);
    fill(groupsEl, groups.length ? groups.map((g) => h('details', { class: 'group', open: !!q || g.options.some((o) => edits.server.has(o.key)) },
      h('summary', null, h('span', { class: 'group-name' }, g.title), h('span', { class: 'muted' }, ` ${g.options.length}`)),
      g.notes?.length ? h('p', { class: 'muted' }, g.notes.join(' ')) : null,
      g.options.map((o) => h('div', { class: `setting${edits.server.has(o.key) ? ' changed' : ''}` },
        h('div', { class: 'setting-name' }, h('strong', null, words(o.key)), h('code', null, o.key)),
        h('div', { class: 'setting-value' }, o.kind === 'block' ? h('pre', { class: 'muted' }, o.value || '(empty)') : input(o.kind, o.secret ? '' : typed(o), edits.server.get(o.key), setServer(o.key), words(o.key), o.secret)),
        h('div', { class: 'setting-note muted' }, o.kind === 'block' ? 'A list: change it in the settings file itself.' : o.secret ? `${o.set ? 'Set.' : 'Not set.'} Type a new one to change it. ${o.note || ''}` : o.note || ''))))) : h('p', { class: 'muted' }, 'No setting matches.'));
  }
  const paintSettings = () => { paintWorlds(); paintGroups(); };

  // ---------------------------------------------------------------- the console
  const list = h('div', { class: 'console', tabindex: 0, role: 'log', 'aria-label': 'Server console' });
  const followBox = h('input', { type: 'checkbox', checked: true }), only = h('input', { type: 'checkbox' });
  const filter = h('input', { type: 'search', placeholder: 'Show lines with...', 'aria-label': 'Show lines with', oninput: () => repaintLog() });
  let lines = [];
  const pass = (l) => (!only.checked || l.level !== 'log') && (!filter.value || l.text.toLowerCase().includes(filter.value.toLowerCase()));
  const row = (l) => h('div', { class: `line ${l.level}` }, h('time', null, fmt.clock(l.t)), l.level === 'log' ? null : h('span', { class: 'level' }, l.level === 'warn' ? 'warning' : 'error'), h('span', { class: 'text' }, l.text));
  const repaintLog = () => { fill(list, lines.filter(pass).slice(-SHOWN).map(row)); if (followBox.checked) list.scrollTop = list.scrollHeight; };
  only.onchange = repaintLog;
  const take = (l) => {
    if (l.n <= lastLine) return;
    lastLine = l.n; lines.push(l);
    if (lines.length > SHOWN * 2) lines = lines.slice(-SHOWN);
    if (!pass(l)) return;
    list.append(row(l));
    while (list.childElementCount > SHOWN) list.firstElementChild.remove();
    if (followBox.checked) list.scrollTop = list.scrollHeight;
  };
  const readLog = async () => { for (const l of await api(`/log?after=${lastLine}`)) take(l); };

  // ---------------------------------------------------------------- the page
  const page = h('div', { class: 'game' });
  host.append(page);
  async function load() {
    const [s, st] = await Promise.all([api('/settings'), api('/status')]);
    settings = s.settings; file = s.file; status = st;
    fill(page,
      h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'Running'), buttons), statusEl, notice),
      h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'Worlds')), worldsEl),
      h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'Settings'), find),
        h('p', { class: 'muted' }, `Every setting in ${file}, under the headings the file has. A change is written to the file when you save and takes effect when the server next starts.`), groupsEl),
      saveBar,
      h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'Console')),
        h('div', { class: 'toolbar' }, filter, h('label', { class: 'check' }, only, 'Warnings and errors only'), h('label', { class: 'check' }, followBox, 'Follow')), list));
    paintStatus(); paintSettings(); paintSave();
    await readLog();
  }
  const poll = async () => { try { status = await api('/status'); } catch { status = null; } paintStatus(); };

  return {
    show() {
      shown = true;
      (settings ? Promise.resolve() : load()).then(() => {
        if (!shown) return;
        timer = setInterval(poll, 8000);
        logTimer = setInterval(() => readLog().catch(() => {}), 1000);
      }).catch((e) => toast(e.message, true));
    },
    hide() { shown = false; clearInterval(timer); clearInterval(logTimer); },
    unsaved: () => count() > 0,
  };
}
