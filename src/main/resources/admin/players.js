/** Players: who is in the game now, where, on which world and channel. */
import { h, fill, table } from './ui.js';
import { api } from './api.js';

export function playersTab(host) {
  let timer = null;
  const list = h('div'), count = h('span', { class: 'muted' });
  host.append(h('section', { class: 'panel' }, h('header', { class: 'panel-head' }, h('h2', null, 'In the game'), count), list));
  const refresh = async () => {
    const players = await api('/players');
    count.textContent = `${players.length} playing`;
    fill(list, table([
      { name: 'Character', cell: (p) => (p.gm > 0 ? [p.name, ' ', h('span', { class: 'chip' }, `GM ${p.gm}`)] : p.name) },
      { name: 'Level', num: true, cell: (p) => p.level },
      { name: 'Job', cell: (p) => p.job },
      { name: 'Map', cell: (p) => (p.mapName ? `${p.mapName} (${p.map})` : String(p.map)) },
      { name: 'World', cell: (p) => p.world },
      { name: 'Channel', num: true, cell: (p) => p.channel },
    ], players, 'Nobody is playing.'));
  };
  return {
    show() { refresh().catch(() => {}); timer = setInterval(() => refresh().catch(() => {}), 5000); },
    hide() { clearInterval(timer); },
  };
}
