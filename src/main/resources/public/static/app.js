// Phase 1: bare timeline. Replaced by the full page in phase 7.
(async function () {
  const state = await fetch('/api/state').then(r => r.json());
  document.getElementById('banner').textContent = state.labels.banner;
  const list = document.getElementById('timeline');
  const es = new EventSource('/api/events?epoch=' + encodeURIComponent(state.session_epoch) + '&after=0');
  es.onmessage = null;
  es.addEventListener('open', () => { const li = document.createElement('li'); li.textContent = 'stream open'; list.appendChild(li); });
  ['session.reset', 'session.stale', 'session.expired', 'system.notice'].forEach(k => es.addEventListener(k, ev => {
    const e = JSON.parse(ev.data);
    const li = document.createElement('li');
    li.textContent = e.seq + ' ' + e.actor_type + ' ' + e.kind + ' ' + e.summary;
    list.appendChild(li);
  }));
})();
