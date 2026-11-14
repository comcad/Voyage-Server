/**
 * Players: every character, online or not, with a search, filters, and sorting by any column; where each is, on which
 * world and channel, with Kick and Ban (for a time or for good; Unban for a banned account) for each. The server does the searching and
 * sorting and sends one page at a time, so this stays quick with many characters.
 */
import { h, fill, toast, dialog, settled, fmt } from './ui.js';
import { api } from './api.js';

const SIZE = 50;
const STATUS = [['', 'Everyone'], ['online', 'Online'], ['offline', 'Offline'], ['banned', 'Banned']];
const JOBS = [['', 'All jobs'], ['beginner', 'Beginner'], ['warrior', 'Warrior'], ['magician', 'Magician'], ['bowman', 'Bowman'],
  ['thief', 'Thief'], ['pirate', 'Pirate'], ['gm', 'GM']];
const GMS = [['', 'GMs and players'], ['yes', 'GMs only'], ['no', 'No GMs']];
const FILTERS = ['q', 'status', 'world', 'job', 'gm', 'min', 'max'];
// the first click on a column: names and places A to Z, numbers and times highest and latest first
const FIRST_DESC = new Set(['level', 'channel', 'seen']);
const DEFAULTS = { q: '', status: '', world: '', job: '', gm: '', min: '', max: '', sort: 'status', dir: 'asc' };
const KEY = 'voyage-admin-players';
// a ban's length: so many minutes, hours or days, or for good (0)
const UNITS = [['1', 'minutes'], ['60', 'hours'], ['1440', 'days'], ['0', 'permanently']];
const MOST_MINUTES = 10 * 366 * 24 * 60;
/** "1 minute", "90 minutes", "2 hours", "3 days", the way the server says it to the player. */
const span = (m) => {
  const [n, unit] = m % 1440 === 0 ? [m / 1440, 'day'] : m % 60 === 0 ? [m / 60, 'hour'] : [m, 'minute'];
  return `${n} ${unit}${n === 1 ? '' : 's'}`;
};

/** The filters and order this browser used last (not the search). */
function remembered() {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}'), q: '' }; } catch { return { ...DEFAULTS }; }
}
function remember(v) {
  try { localStorage.setItem(KEY, JSON.stringify({ ...v, q: undefined })); } catch { /* not kept: fine */ }
}

export function playersTab(host) {
  let timer = null, asking = false, seq = 0, page = 0, worldsKey = '';
  const v = remembered();
  const count = h('span', { class: 'muted' }), list = h('div'), pager = h('div', { class: 'pager' });

  const changed = () => { page = 0; remember(v); clearBtn.hidden = !FILTERS.some((k) => v[k]); refresh().catch(() => {}); };
  const later = settled(changed, 250);
  const select = (key, options, label) => {
    const el = h('select', { 'aria-label': label, onchange: () => { v[key] = el.value; changed(); } },
      options.map(([value, text]) => h('option', { value }, text)));
    el.value = v[key];
    return el;
  };
  const search = h('input', { type: 'search', class: 'grow', maxlength: 30, value: v.q, placeholder: 'Search characters or accounts',
    'aria-label': 'Search characters or accounts', oninput: () => { v.q = search.value.trim(); later(); } });
  const level = (key, hint) => h('input', { type: 'number', min: 1, max: 250, value: v[key], placeholder: hint, 'aria-label': `Level ${hint}`,
    oninput: (e) => { v[key] = e.target.value; later(); } });
  const status = select('status', STATUS, 'Status'), job = select('job', JOBS, 'Job'), gm = select('gm', GMS, 'GM');
  const world = select('world', [['', 'All worlds']], 'World');
  const min = level('min', 'from'), max = level('max', 'to');
  const clearBtn = h('button', { type: 'button', class: 'quiet', hidden: !FILTERS.some((k) => v[k]), onclick: () => {
    for (const k of FILTERS) v[k] = '';
    search.value = ''; min.value = ''; max.value = '';
    for (const el of [status, world, job, gm]) el.value = '';
    changed();
  } }, 'Clear filters');

  host.append(h('section', { class: 'panel' },
    h('header', { class: 'panel-head' }, h('h2', null, 'Players'), count),
    h('div', { class: 'filters' }, search, status, world, job, h('label', null, 'Level', min, '–', max), gm, clearBtn),
    list, pager));

  /**
   * A window asking to go on; for a ban, also how long and a line for the reason. Resolves { reason, minutes } to go
   * on (minutes 0: for good), or nothing.
   */
  const ask = (title, text, go, banning) => dialog(title, (close) => {
    const box = banning ? h('input', { type: 'text', class: 'ban-reason', maxlength: 100, placeholder: 'Reason (the player is told it)', 'aria-label': 'Reason' }) : null;
    const amount = banning ? h('input', { type: 'number', min: 1, step: 1, value: 1, 'aria-label': 'How many', class: 'ban-amount' }) : null;
    const unit = banning ? h('select', { 'aria-label': 'Minutes, hours or days, or permanently', onchange: () => { amount.hidden = unit.value === '0'; } },
      UNITS.map(([value, label]) => h('option', { value }, label))) : null;
    if (unit) unit.value = '1440';
    const error = h('p', { class: 'error' });
    const minutes = () => (unit.value === '0' ? 0 : Number(amount.value) * Number(unit.value));
    const yes = () => {
      if (banning && unit.value !== '0' && !(Number.isInteger(Number(amount.value)) && Number(amount.value) >= 1 && minutes() <= MOST_MINUTES)) {
        error.textContent = 'A ban lasts from a minute to ten years, in whole numbers.';
        amount.focus();
        return;
      }
      close({ reason: box ? box.value : '', minutes: banning ? minutes() : 0 });
    };
    setTimeout(() => amount?.focus(), 0);
    return [h('p', null, text),
      banning ? h('p', { class: 'ban-length' }, h('label', null, 'Ban for', amount, unit)) : null,
      box ? h('p', null, box) : null, banning ? error : null,
      h('div', { class: 'dialog-foot' }, h('button', { type: 'button', onclick: () => close(null) }, 'Cancel'),
        h('button', { type: 'button', class: 'primary', onclick: yes }, go))];
  });
  const act = async (action, p) => {
    const [title, text, go, reason] = {
      kick: [`Kick ${p.name}?`, `${p.name} is disconnected from the game and can log in again.`, 'Kick', false],
      ban: [`Ban ${p.name}?`, `${p.name}'s account is banned, with every character on it${p.online ? '; they are told for how long and why, and disconnected' : ''}. A permanent ban also bans the machine their game ran on.`, 'Ban', true],
      unban: [`Unban ${p.name}?`, `${p.name}'s account can log in again, with every character on it.`, 'Unban', false],
    }[action];
    asking = true;
    const yes = await ask(title, text, go, reason);
    asking = false;
    if (!yes) return;
    try {
      await api(`/players/${action}`, { method: 'POST', body: { name: p.name, reason: yes.reason, ...(action === 'ban' ? { minutes: yes.minutes } : {}) } });
      toast({ kick: `${p.name} was kicked.`, ban: `${p.name} is banned ${yes.minutes ? `for ${span(yes.minutes)}` : 'permanently'}.`, unban: `${p.name} is no longer banned.` }[action]);
    } catch (e) { toast(e.message, true); }
    refresh().catch(() => {});
  };

  const sortBy = (key) => {
    if (v.sort === key) v.dir = v.dir === 'asc' ? 'desc' : 'asc';
    else { v.sort = key; v.dir = FIRST_DESC.has(key) ? 'desc' : 'asc'; }
    changed();
  };
  const COLUMNS = [
    { key: 'name', name: 'Character', cell: (p) => (p.gm > 0 ? [p.name, ' ', h('span', { class: 'chip' }, `GM ${p.gm}`)] : p.name) },
    { key: 'account', name: 'Account', cell: (p) => h('span', { class: 'muted' }, p.account) },
    { key: 'level', name: 'Level', num: true, cell: (p) => p.level },
    { key: 'job', name: 'Job', cell: (p) => p.job },
    { key: 'map', name: 'Map', cell: (p) => (p.mapName ? `${p.mapName} (${p.map})` : String(p.map)) },
    { key: 'world', name: 'World', cell: (p) => p.world },
    { key: 'channel', name: 'Channel', num: true, cell: (p) => (p.online ? p.channel : '-') },
    { key: 'status', name: 'Status', cell: (p) => [
      p.online ? h('span', { class: 'chip ok' }, 'online') : h('span', { class: 'chip off' }, 'offline'),
      p.banned ? [' ', h('span', { class: 'chip off', title: [p.until ? `Until ${fmt.day(p.until)}.` : 'For good.', p.reason].filter(Boolean).join(' ') },
        p.until ? `banned, ${fmt.span(p.until - Date.now())} left` : 'banned')] : null] },
    { key: 'seen', name: 'Last online', cell: (p) => (p.online ? 'now' : p.seen ? h('span', { title: fmt.day(p.seen) }, fmt.ago(p.seen)) : h('span', { class: 'muted' }, 'never')) },
    { name: '', cls: 'actions', cell: (p) => h('span', { style: { display: 'inline-flex', gap: '6px' } },
      h('button', { type: 'button', class: 'quiet', disabled: !p.online, title: p.online ? null : 'Not in the game', onclick: () => act('kick', p) }, 'Kick'),
      p.banned
        ? h('button', { type: 'button', class: 'quiet', onclick: () => act('unban', p) }, 'Unban')
        : h('button', { type: 'button', class: 'quiet danger', onclick: () => act('ban', p) }, 'Ban')) },
  ];
  const header = (c) => {
    if (!c.key) return h('th', { class: c.cls }, '');
    const on = v.sort === c.key, asc = v.dir === 'asc';
    return h('th', { class: c.num ? 'num' : null, 'aria-sort': on ? (asc ? 'ascending' : 'descending') : null },
      h('button', { type: 'button', class: `sort${on ? ' on' : ''}`, 'data-key': c.key, title: `Sort by ${c.name.toLowerCase()}`, onclick: () => sortBy(c.key) },
        c.name, h('span', { class: 'arrow', 'aria-hidden': 'true' }, on ? (asc ? '▲' : '▼') : '')));
  };

  const refresh = async () => {
    if (asking) return;   // (not while a window is open over the list)
    const mine = ++seq;
    const params = new URLSearchParams({ sort: v.sort, dir: v.dir, page: String(page), size: String(SIZE) });
    for (const k of FILTERS) if (v[k]) params.set(k, v[k]);
    const d = await api(`/players?${params}`);
    if (mine !== seq) return;   // an older answer, after a newer question
    page = d.page;

    // the worlds there are, as they come; a world remembered from before that is gone shows all of them
    const key = d.worlds.map((w) => w.id).join(',');
    if (key !== worldsKey) {
      worldsKey = key;
      fill(world, h('option', { value: '' }, 'All worlds'), d.worlds.map((w) => h('option', { value: String(w.id) }, w.name)));
      world.value = d.worlds.some((w) => String(w.id) === v.world) ? v.world : '';
      if (world.value !== v.world) { v.world = ''; changed(); return; }
    }

    count.textContent = `${fmt.count(d.characters)} character${d.characters === 1 ? '' : 's'}, ${d.online} playing${d.banned ? `, ${d.banned} banned` : ''}`;
    const focused = document.activeElement?.closest?.('th button.sort')?.dataset.key;
    if (!d.players.length) {
      fill(list, h('p', { class: 'empty' }, d.characters ? 'No character matches the search and filters.' : 'There are no characters yet.'));
    } else {
      fill(list, h('div', { class: 'table-wrap' }, h('table', null,
        h('thead', null, h('tr', null, COLUMNS.map(header))),
        h('tbody', null, d.players.map((p) => h('tr', null, COLUMNS.map((c) => h('td', { class: c.cls || (c.num ? 'num' : null) }, c.cell(p)))))))));
      if (focused) list.querySelector(`th button[data-key="${focused}"]`)?.focus();
    }
    const pages = Math.max(1, Math.ceil(d.total / d.size)), from = d.page * d.size;
    fill(pager, d.total > d.size ? [
      h('span', { class: 'muted' }, `${(from + 1).toLocaleString('en-US')}–${Math.min(d.total, from + d.size).toLocaleString('en-US')} of ${d.total.toLocaleString('en-US')}`),
      h('button', { type: 'button', disabled: d.page === 0, onclick: () => { page--; refresh().catch(() => {}); } }, 'Previous'),
      h('button', { type: 'button', disabled: d.page >= pages - 1, onclick: () => { page++; refresh().catch(() => {}); } }, 'Next'),
    ] : d.total < d.characters ? h('span', { class: 'muted' }, `${d.total} of ${fmt.count(d.characters)} match`) : null);
  };
  return {
    show() { refresh().catch(() => {}); timer = setInterval(() => refresh().catch(() => {}), 5000); },
    hide() { clearInterval(timer); },
  };
}
