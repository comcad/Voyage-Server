/**
 * Players: who is in the game now, where, on which world and channel, with Kick and Ban for each. Banned accounts'
 * characters are listed too, marked banned, so their ban can be lifted: their button is Unban.
 */
import { h, fill, table, toast, dialog } from './ui.js';
import { api } from './api.js';

export function playersTab(host) {
  let timer = null, asking = false;
  const list = h('div'), count = h('span', { class: 'muted' });
  host.append(h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'Players'), count), list));

  /** A window asking to go on; with `reason`, a line for one. Resolves { reason } to go on, or nothing. */
  const ask = (title, text, go, reason) => dialog(title, (close) => {
    const box = reason ? h('input', { type: 'text', maxlength: 100, placeholder: 'Reason (the player is told it)', 'aria-label': 'Reason' }) : null;
    setTimeout(() => box?.focus(), 0);
    return [h('p', null, text), box ? h('p', null, box) : null,
      h('div', { class: 'dialog-foot' }, h('button', { type: 'button', onclick: () => close(null) }, 'Cancel'),
        h('button', { type: 'button', class: 'primary', onclick: () => close({ reason: box ? box.value : '' }) }, go))];
  });
  const act = async (action, p) => {
    const [title, text, go, reason] = {
      kick: [`Kick ${p.name}?`, `${p.name} is disconnected from the game and can log in again.`, 'Kick', false],
      ban: [`Ban ${p.name}?`, `${p.name}'s account is banned, with every character on it, ${p.online ? 'and they are disconnected in five seconds' : 'and can no longer log in'}. The machine their game ran on is banned too.`, 'Ban', true],
      unban: [`Unban ${p.name}?`, `${p.name}'s account can log in again, with every character on it.`, 'Unban', false],
    }[action];
    asking = true;
    const yes = await ask(title, text, go, reason);
    asking = false;
    if (!yes) return;
    try {
      await api(`/players/${action}`, { method: 'POST', body: { name: p.name, reason: yes.reason } });
      toast({ kick: `${p.name} was kicked.`, ban: `${p.name} is banned.`, unban: `${p.name} is no longer banned.` }[action]);
    } catch (e) { toast(e.message, true); }
    refresh().catch(() => {});
  };

  const refresh = async () => {
    if (asking) return;   // (not while a window is open over the list)
    const players = await api('/players');
    const on = players.filter((p) => p.online).length, banned = players.filter((p) => p.banned).length;
    count.textContent = `${on} playing${banned ? `, ${banned} banned` : ''}`;
    fill(list, table([
      { name: 'Character', cell: (p) => (p.gm > 0 ? [p.name, ' ', h('span', { class: 'chip' }, `GM ${p.gm}`)] : p.name) },
      { name: 'Level', num: true, cell: (p) => p.level },
      { name: 'Job', cell: (p) => p.job },
      { name: 'Map', cell: (p) => (p.mapName ? `${p.mapName} (${p.map})` : String(p.map)) },
      { name: 'World', cell: (p) => p.world },
      { name: 'Channel', num: true, cell: (p) => (p.online ? p.channel : '-') },
      { name: 'Status', cell: (p) => [
        p.online ? h('span', { class: 'chip ok' }, 'online') : h('span', { class: 'chip off' }, 'offline'),
        p.banned ? [' ', h('span', { class: 'chip off', title: p.reason || '' }, 'banned')] : null] },
      { name: '', cell: (p) => h('span', { style: { display: 'inline-flex', gap: '6px' } },
        h('button', { type: 'button', class: 'quiet', disabled: !p.online, title: p.online ? null : 'Not in the game', onclick: () => act('kick', p) }, 'Kick'),
        p.banned
          ? h('button', { type: 'button', class: 'quiet', onclick: () => act('unban', p) }, 'Unban')
          : h('button', { type: 'button', class: 'quiet danger', onclick: () => act('ban', p) }, 'Ban')) },
    ], players, 'Nobody is playing, and nobody is banned.'));
  };
  return {
    show() { refresh().catch(() => {}); timer = setInterval(() => refresh().catch(() => {}), 5000); },
    hide() { clearInterval(timer); },
  };
}
