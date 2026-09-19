// The whole page: a state object, a reducer, a dozen render functions and the guided script.
// Any text that came from an agent, a tool result, a seed file or the corpus is set with textContent, never innerHTML.
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const REVEAL_MS = 700;
  const STAGES = [
    ['analyse', 'Analyse', 'Analysis agent'],
    ['approve', 'Approve', 'Human, DBA persona'],
    ['remediate', 'Remediate', 'Remediation agent'],
    ['verify', 'Verify', 'Verification agent'],
    ['supervise', 'Supervise', 'Supervision agent'],
  ];

  const state = {
    snap: null,
    events: [],
    lastSeq: 0,
    epoch: null,
    step: 0,            // index into GUIDE; GUIDE.length means explore mode
    fromSeq: 0,         // the current step's range starts after this seq
    toSeq: null,        // the seq of the awaited event, once seen
    revealed: 0,        // how many focus items of the current step are visible
    pending: false,     // a command is in flight or the step is waiting
    filter: 'all',
    gauges: null,
    es: null,
    revealTimer: null,
    atBottom: true,
    renderedSeq: 0,
  };

  // ---- DOM helpers ----

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = String(text);
    return n;
  }
  function add(parent, ...kids) { for (const k of kids) if (k) parent.appendChild(k); return parent; }
  function tag(text, cls) { return el('span', 'tag' + (cls ? ' ' + cls : ''), text); }
  function short(hash) { return hash ? String(hash).slice(0, 8) : ''; }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function pretty(obj) { try { return JSON.stringify(obj, null, 2); } catch (e) { return String(obj); } }

  // ---- API ----

  async function fetchState() {
    const r = await fetch('/api/state', { cache: 'no-store' });
    if (!r.ok) throw new Error('state ' + r.status);
    return r.json();
  }

  async function send(type, persona, args) {
    const r = await fetch('/api/commands', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ type, persona, args: args || {} }),
    });
    let body = {};
    try { body = await r.json(); } catch (e) { /* no body */ }
    if (type === 'reset' && r.status === 200) { location.reload(); return body; }
    if (r.status === 409 && body.error && body.error.code === 'SESSION_FULL') toast('Session full. Reset to continue.');
    return { status: r.status, body };
  }

  function toast(text) {
    const n = el('div', 'pill', text);
    n.style.position = 'fixed'; n.style.bottom = '90px'; n.style.left = '50%'; n.style.transform = 'translateX(-50%)'; n.style.zIndex = '30';
    document.body.appendChild(n);
    setTimeout(() => n.remove(), 3500);
  }

  // ---- Active finding ----

  function active() {
    if (!state.snap) return null;
    const id = state.snap.active_finding_id;
    return state.snap.findings.find((f) => f.id === id) || state.snap.findings[0];
  }
  function latestPlan(f) { return f && f.plans && f.plans.length ? f.plans[f.plans.length - 1] : null; }
  function currentPersona() { return $('persona').value; }

  // ---- Guided script ----

  const GUIDE = [
    {
      persona: 'requester', title: 'Start the analysis',
      look: 'The analysis agent reads the role graph, grants and telemetry through allowlisted tools and proposes seven privileges. Every read is a tool strip; the proposal is a recorded card.',
      commands: () => [['start_analysis', { finding_id: active().id }]],
      await: (e) => e.kind === 'agent.decision' && e.actor === 'analysis' && e.payload.phase === 'A',
    },
    {
      persona: 'requester', title: 'History changes the plan',
      look: 'Deterministic retrieval found a rolled-back past fix, OUT-0212. The agent checked the objects exist here and revised the plan: two grants and a month-end scenario were added. The guards accepted it.',
      commands: null,
      await: (e) => e.kind === 'finding.state_changed' && e.payload.to === 'PLAN_READY',
    },
    {
      persona: 'requester', title: 'Request approval',
      look: 'The plan is bound to a hash. Nothing can execute until someone else approves that exact hash.',
      commands: () => [['request_approval', { finding_id: active().id, plan_hash: latestPlan(active()).hash }]],
      await: (e) => e.kind === 'approval.requested',
    },
    {
      persona: 'requester', title: 'Sam tries to approve his own request',
      look: 'Refused by code, not by an agent. Separation of duties is a deterministic gate; the refusals counter rises.',
      commands: () => [['approve', { finding_id: active().id, plan_hash: latestPlan(active()).hash }]],
      await: (e) => e.kind === 'gate.checked' && e.payload.rule === 'approval.separation_of_duties' && e.payload.result === 'REFUSED',
    },
    {
      persona: 'dba', title: 'Dana approves, with two faults injected',
      look: 'The write is delivered twice and the database’s reply is lost. Watch the lease: one delivery wins, one is refused, and the outcome becomes unknown rather than retried blindly.',
      commands: () => [['arm_chaos', { switch: 'duplicate_delivery' }], ['arm_chaos', { switch: 'drop_response' }],
        ['approve', { finding_id: active().id, plan_hash: latestPlan(active()).hash, comment: 'Plan v2 keeps the month-end privileges. Approved.' }]],
      await: (e) => e.kind === 'operation.state_changed' && e.payload.to === 'OUTCOME_UNKNOWN',
    },
    {
      persona: null, title: 'Recovery without a second write',
      look: 'The agent may only ask for status. Status reads under the same lock, finds the ledger row, and the operation is applied once. Mutations stays at 1.',
      commands: null,
      await: (e) => e.kind === 'operation.state_changed' && e.payload.to === 'APPLIED',
    },
    {
      persona: null, title: 'Verification and close',
      look: 'Deterministic code computed eleven checks before the verifier saw them. The verdict guard, not the agent, has the last word. The fingerprint is sealed.',
      commands: null,
      await: (e) => e.kind === 'finding.state_changed' && e.payload.to === 'CLOSED',
    },
    {
      persona: 'dba', title: 'Drift outside the system',
      look: 'An on-call DBA re-grants the owner role through a separate client. The poller sees the fingerprint change within seconds, the supervisor classifies it as drift, and a linked child finding opens.',
      commands: () => [['trigger_drift', { finding_id: active().id }]],
      await: (e) => e.kind === 'finding.created',
    },
    {
      persona: null, title: 'Bounded concurrency',
      look: '5,000 findings through a 256-slot queue and 8 workers. Queue, in-flight and heap stay under their named bounds.',
      commands: () => [['start_burst', { count: 5000 }]],
      await: (e) => e.kind === 'burst.completed',
    },
  ];

  function exploring() { return state.step >= GUIDE.length; }

  // ---- Events ----

  const FOCUS_KINDS = new Set(['agent.turn', 'agent.decision', 'agent.stopped', 'gate.checked', 'tool.invoked', 'analysis.requested',
    'approval.requested', 'approval.decided', 'approval.voided', 'kill_switch.changed', 'chaos.armed', 'chaos.fired',
    'operation.created', 'operation.state_changed', 'lease.won', 'delivery.duplicate_refused', 'plan.versioned',
    'retrieval.completed', 'verification.completed', 'evidence.sealed', 'drift.detected', 'finding.state_changed',
    'finding.created', 'session.active_finding_changed', 'system.notice', 'burst.started', 'burst.completed']);

  function apply(e) {
    if (e.kind === 'gauges') { state.gauges = e.payload; renderGauges(); return; }
    if (e.seq === null || e.seq === undefined) return;
    if (e.seq <= state.lastSeq) return; // already applied
    state.lastSeq = e.seq;
    state.events.push(e);
    if (!exploring() && state.toSeq === null && e.seq > state.fromSeq && GUIDE[state.step].await(e)) state.toSeq = e.seq;
    if (STRUCTURAL.has(e.kind)) scheduleRefresh();
    renderTimelineAppend(e);
    scheduleReveal();
    renderGuide();
  }

  const STRUCTURAL = new Set(['finding.state_changed', 'finding.created', 'plan.versioned', 'approval.requested', 'approval.decided',
    'approval.voided', 'operation.created', 'operation.state_changed', 'verification.completed', 'evidence.sealed', 'drift.detected',
    'chaos.armed', 'chaos.fired', 'kill_switch.changed', 'burst.started', 'burst.completed', 'session.active_finding_changed', 'gate.checked']);

  let refreshTimer = null;
  function scheduleRefresh() {
    if (refreshTimer) return;
    refreshTimer = setTimeout(async () => {
      refreshTimer = null;
      try {
        const snap = await fetchState();
        if (snap.session_epoch !== state.epoch) { gone('The demo restarted.'); return; }
        state.snap = snap;
        renderHeader(); renderRail(); renderContext(); renderGuide();
      } catch (err) { /* transient */ }
    }, 120);
  }

  function connect() {
    if (state.es) state.es.close();
    const es = new EventSource('/api/events?epoch=' + encodeURIComponent(state.epoch) + '&after=' + state.lastSeq);
    state.es = es;
    const kinds = ['session.reset', 'session.stale', 'session.expired', 'gauges'];
    es.addEventListener('session.reset', () => location.reload());
    es.addEventListener('session.stale', () => location.reload());
    es.addEventListener('session.expired', () => gone('This session expired.'));
    es.addEventListener('gauges', (ev) => apply(JSON.parse(ev.data)));
    for (const k of FOCUS_KINDS) if (!kinds.includes(k)) es.addEventListener(k, (ev) => apply(JSON.parse(ev.data)));
    es.onerror = () => { /* EventSource reconnects with Last-Event-ID */ };
  }

  function gone(text) {
    $('gone-text').textContent = text + ' Everything here lives in memory.';
    $('gone').classList.remove('hidden');
  }

  // ---- Rendering: header ----

  function renderHeader() {
    const s = state.snap;
    $('banner-text').textContent = s.labels.banner;
    const c = $('counters');
    clear(c);
    const cs = s.counters;
    for (const [k, label] of [['mutations', 'Mutations'], ['duplicates_refused', 'Duplicates refused'], ['refusals', 'Refusals']]) {
      add(c, add(el('div', 'counter'), el('b', null, cs[k]), el('span', null, label)));
    }
    const p = $('persona');
    if (!p.options.length) {
      for (const a of s.personas) {
        const o = el('option', null, a.display_name + ', ' + a.role);
        o.value = a.persona;
        p.appendChild(o);
      }
    }
    const reset = $('reset');
    reset.disabled = !s.can_reset;
    reset.title = s.can_reset ? 'Start over with a fresh session' : 'Available when the current stage finishes';
  }

  // ---- Rendering: rail ----

  function attentionStage(f) {
    for (let i = state.events.length - 1; i >= 0; i--) {
      const e = state.events[i];
      if (e.kind === 'finding.state_changed' && e.finding_id === f.id && e.payload.to === 'NEEDS_ATTENTION') return e.stage;
    }
    return 'analyse';
  }

  function renderRail() {
    const f = active();
    const rail = $('rail');
    clear(rail);
    if (!f) return;
    const attention = f.state === 'NEEDS_ATTENTION';
    const cur = attention ? attentionStage(f) : f.stage;
    const idx = STAGES.findIndex((s) => s[0] === cur);
    STAGES.forEach(([key, name, who], i) => {
      const n = el('div', 'stage');
      if (i < idx) n.classList.add('done');
      if (i === idx) n.classList.add(attention ? 'attention' : 'active');
      if (f.state === 'REOPENED' && key === 'supervise') n.classList.add('done');
      const head = add(el('div', 'name'), el('span', null, name));
      let sub = who;
      if (i === idx) {
        if (attention) sub = f.attention_reason ? f.attention_reason.split(':')[0].replace(/_/g, ' ').toLowerCase() : 'needs attention';
        else sub = f.state.replace(/_/g, ' ').toLowerCase();
      }
      if (key === 'remediate' && f.operation) head.appendChild(tag(f.operation.state, 'state'));
      if (key === 'supervise' && f.children && f.children.length) head.appendChild(tag('reopened as ' + short(f.children[0]), 'state'));
      if (key === 'supervise' && f.parent_finding_id) head.appendChild(tag('child of ' + short(f.parent_finding_id), 'state'));
      add(n, head, el('div', 'sub', sub));
      rail.appendChild(n);
    });
  }

  // ---- Rendering: context column ----

  function renderContext() {
    const f = active();
    const s = state.snap;
    const c = $('context');
    clear(c);
    if (!f) return;
    // Finding
    const fp = el('div', 'panel');
    add(fp, add(el('h3'), el('span', null, 'Finding '), tag(f.state.replace(/_/g, ' '), 'state')));
    add(fp, el('p', null, f.title));
    add(fp, el('p', 'muted', f.description));
    const rows = [['Role', f.subject_role], ['Owner role', f.owner_role], ['Asset', f.asset_id], ['Criticality', f.criticality]];
    if (f.parent_finding_id) rows.push(['Reopened from', short(f.parent_finding_id)]);
    if (f.requester) rows.push(['Requested by', f.requester.display_name + ' (demo persona)']);
    if (f.attention_reason) rows.push(['Stopped', f.attention_reason]);
    for (const [k, v] of rows) add(fp, add(el('div', 'row'), el('span', 'muted', k), el('span', null, v)));
    add(fp, el('div', 'source', 'Platform record'));
    c.appendChild(fp);
    // Plan
    const plan = latestPlan(f);
    if (plan) {
      const pp = el('div', 'panel');
      add(pp, add(el('h3'), el('span', null, 'Plan v' + plan.version + ' '), tag(short(plan.hash), 'state')));
      const prev = f.plans.length > 1 ? f.plans[f.plans.length - 2] : null;
      const list = el('div');
      const addedKeys = new Set((plan.added_grants || []).map((g) => g.privilege + ' ' + g.object));
      let compare = false;
      const draw = () => {
        clear(list);
        for (const g of plan.grants) {
          const key = g.privilege + ' ' + g.object;
          const row = el('div', 'grant' + (compare && addedKeys.has(key) ? ' added' : ''));
          add(row, el('span', null, g.privilege + ' on ' + g.object + ' '), el('span', 'kind', g.kind));
          list.appendChild(row);
        }
        const sc = el('div', 'grant');
        sc.textContent = 'verify: ' + plan.scenarios.join(', ');
        if (compare && plan.added_scenarios.length) sc.classList.add('added');
        list.appendChild(sc);
      };
      draw();
      add(pp, el('p', 'muted', plan.grants.length + ' grants replace the owner membership; expected_before ' + short(plan.expected_before)));
      pp.appendChild(list);
      if (prev) {
        const b = el('button', 'link', 'Compare with v' + prev.version);
        b.type = 'button';
        b.onclick = () => { compare = !compare; b.textContent = compare ? 'Hide comparison' : 'Compare with v' + prev.version; draw(); };
        add(pp, el('div', 'muted', '+' + plan.added_grants.length + ' grants, +' + plan.added_scenarios.length + ' scenario since v' + prev.version + ' '), b);
      }
      c.appendChild(pp);
    }
    // Folded context
    const ctx = s.context;
    c.appendChild(folded('Asset', 'Platform record', (body) => {
      const a = ctx.asset;
      for (const [k, v] of [['Name', a.name], ['Application', a.application], ['Team', a.team], ['Environment', a.environment], ['Engine', a.engine], ['PII', a.pii_label ? 'yes' : 'no']])
        add(body, add(el('div', 'row'), el('span', 'muted', k), el('span', null, v)));
    }));
    c.appendChild(folded('Query telemetry', ctx.telemetry.label + ', ' + ctx.telemetry.window_days + ' days', (body) => {
      const t = el('table', 'small');
      for (const r of ctx.telemetry.rows) add(t, add(el('tr'), el('td', null, r.role), el('td', null, r.command + ' ' + r.object), el('td', 'num', r.calls), el('td', 'num', r.last_seen_days_ago + 'd')));
      body.appendChild(t);
    }));
    c.appendChild(folded('Scheduled jobs', ctx.jobs.label, (body) => {
      const t = el('table', 'small');
      for (const j of ctx.jobs.rows) add(t, add(el('tr'), el('td', null, j.name), el('td', null, j.role), el('td', null, j.calls.join(', ')), el('td', 'num', j.last_run_days_ago + 'd ago')));
      body.appendChild(t);
    }));
    c.appendChild(folded('Role change log', ctx.audit.label, (body) => {
      const t = el('table', 'small');
      for (const r of ctx.audit.rows) add(t, add(el('tr'), el('td', 'num', r.days_ago + 'd'), el('td', null, r.actor), el('td', null, r.action + ' (' + r.ticket + ')')));
      body.appendChild(t);
    }));
    c.appendChild(folded('Knowledge corpus', ctx.corpus.label + ', ' + ctx.corpus.documents.length + ' documents', (body) => {
      const t = el('table', 'small');
      for (const d of ctx.corpus.documents) add(t, add(el('tr'), el('td', 'mono', d.id), el('td', null, d.title), el('td', null, d.disposition || d.kind)));
      body.appendChild(t);
    }));
  }

  function folded(title, source, fill) {
    const d = el('details', 'panel');
    add(d, add(el('summary'), el('span', null, title), el('span', 'muted', source)));
    const body = el('div');
    fill(body);
    d.appendChild(body);
    return d;
  }

  // ---- Rendering: focus items in their visual form ----

  function agentName(actor) {
    return { analysis: 'Analysis agent', remediation: 'Remediation agent', verification: 'Verification agent', supervision: 'Supervision agent' }[actor] || actor;
  }

  function renderItem(e) {
    const p = e.payload || {};
    switch (e.kind) {
      case 'agent.turn': {
        const card = el('div', 'card agent item');
        add(card, add(el('div', 'head'), el('span', 'who', agentName(e.actor)), tag('recorded', 'recorded'), el('span', 'muted', 'step ' + p.step)));
        add(card, el('p', 'say', p.say));
        if (p.calls && p.calls.length) {
          const chips = el('div', 'chips');
          add(chips, el('span', 'muted', 'calls: '));
          for (const c of p.calls) chips.appendChild(el('span', 'chip', c.tool));
          card.appendChild(chips);
        }
        return card;
      }
      case 'agent.decision': return decisionCard(e);
      case 'agent.stopped': return sysline('■ ' + e.summary, true);
      case 'gate.checked': return strip('⛨', p.rule, p.result, gateDetail(e), p.result === 'REFUSED');
      case 'tool.invoked': return strip('⎇', p.tool, p.error ? p.error.code : (p.outcome || 'ok'), p.error ? p.error.message : (p.evidence_id + ' · ' + (p.source_label || '')), !!p.error);
      case 'analysis.requested': case 'approval.requested': case 'approval.decided': case 'kill_switch.changed': return signature(e);
      case 'approval.voided': return strip('⛨', 'approval.plan_binding', 'VOIDED', e.summary.replace(/^[^.]*\.\s*/, ''), true);
      case 'chaos.armed': case 'chaos.fired': {
        const f = el('div', 'fault item');
        add(f, el('span', 'glyph', '🔧'), el('span', null, e.summary));
        return f;
      }
      case 'operation.created': return strip('⛨', 'operation.unique', p.result, 'operation ' + short(p.operation_id) + ' created in APPROVED for plan ' + short(p.plan_hash), false);
      case 'operation.state_changed': return strip('⛨', 'operation ' + p.from + ' → ' + p.to, p.to === 'OUTCOME_UNKNOWN' ? 'UNKNOWN' : (p.to === 'FAILED_NOT_APPLIED' ? 'FAILED' : 'PASS'), e.summary, p.to === 'FAILED_NOT_APPLIED' || p.to === 'NEEDS_HUMAN', p.to === 'OUTCOME_UNKNOWN');
      case 'lease.won': return strip('⛨', 'lease.compare_and_set', 'WON', 'by ' + p.winner, false);
      case 'delivery.duplicate_refused': return strip('⛨', 'delivery.duplicate', 'REFUSED', 'LEASE_HELD: ' + p.loser + ' refused, ' + p.holder + ' holds the lease', true);
      case 'plan.versioned': return strip('⛨', 'plan.version', 'v' + p.plan.version, short(p.plan.hash) + ', ' + p.plan.grants.length + ' grants, verify ' + p.plan.scenarios.join(', ') + (p.previous_hash ? '; +' + p.plan.added_grants.length + ' grants, +' + p.plan.added_scenarios.length + ' scenario' : ''), false);
      case 'retrieval.completed': {
        const s = strip('⛨', 'retrieval.mandatory_outcomes', 'PASS', '', false);
        const d = el('details');
        add(d, el('summary', null, p.mandatory.length + ' outcomes of this finding type, ' + p.mandatory.filter((m) => m.disposition === 'rolled_back').length + ' rolled back'));
        const ul = el('ul', 'checks');
        for (const m of p.mandatory) { const li = el('li', m.disposition === 'rolled_back' ? 'fail' : '', m.doc_id + ' ' + m.title + (m.failed_scenario ? ' (failed: ' + m.failed_scenario + ')' : '')); ul.appendChild(li); }
        d.appendChild(ul);
        s.appendChild(d);
        return s;
      }
      case 'verification.completed': {
        const rec = p.record;
        const s = strip('⛨', 'verification.checks', p.result, '', p.result === 'REFUSED');
        const d = el('details');
        add(d, el('summary', null, rec.assertions.length + ' assertions, ' + rec.probes.length + ' probes, ' + rec.scenarios.length + ' scenarios, computed before the verifier’s turn'));
        const ul = el('ul', 'checks');
        for (const c of [...rec.assertions, ...rec.probes, ...rec.scenarios]) ul.appendChild(el('li', c.passed ? '' : 'fail', c.name + ': ' + c.detail));
        d.appendChild(ul);
        s.appendChild(d);
        return s;
      }
      case 'evidence.sealed': return strip('⛨', 'evidence.seal', 'PASS', e.summary.replace(/^[^.]*\.\s*/, ''), false);
      case 'drift.detected': return strip('⛨', 'drift.fingerprint_poll', 'CHANGED', e.summary.replace(/^[^.]*\.\s*/, ''), false, true);
      case 'finding.state_changed': return stateLine(e);
      case 'finding.created': return sysline('⊕ ' + e.summary);
      case 'session.active_finding_changed': return sysline('↳ ' + e.summary);
      case 'system.notice': return sysline(e.summary, /stopped|unavailable/i.test(e.summary));
      case 'burst.started': case 'burst.completed': return strip('⛨', e.kind, 'PASS', e.summary, false);
      default: return sysline(e.summary);
    }
  }

  function gateDetail(e) {
    const p = e.payload || {};
    if (p.failures && p.failures.length) return p.failures.join('; ');
    const i = e.summary.indexOf('. ');
    return i > 0 ? e.summary.slice(i + 2) : '';
  }

  function strip(glyph, rule, verdict, detail, refused, changed) {
    const s = el('div', 'strip item' + (refused ? ' refused' : ''));
    const v = el('span', 'verdict ' + (refused ? 'refuse' : (changed ? 'changed' : 'pass')), verdict);
    add(s, el('span', 'glyph', glyph), el('span', 'rule', rule), v);
    if (detail) s.appendChild(el('span', 'detail', detail));
    return s;
  }

  function sysline(text, attention) {
    const n = el('div', 'sysline item' + (attention ? ' attention' : ''));
    n.appendChild(el('span', null, text));
    return n;
  }

  function stateLine(e) {
    const p = e.payload;
    const n = el('div', 'sysline item' + (p.to === 'NEEDS_ATTENTION' ? ' attention' : ''));
    add(n, el('span', null, 'Finding'), el('span', 'state', p.from.replace(/_/g, ' ') + ' → ' + p.to.replace(/_/g, ' ')), el('span', null, p.reason));
    return n;
  }

  function signature(e) {
    const p = e.payload || {};
    const a = p.actor || {};
    const box = el('div', 'signature item');
    let line = e.summary;
    if (e.kind === 'approval.decided') line = (p.decision === 'approved' ? 'Approved' : 'Rejected') + ' the plan';
    else if (e.kind === 'approval.requested') line = 'Requested approval of the plan';
    else if (e.kind === 'analysis.requested') line = 'Requested analysis of the finding';
    else if (e.kind === 'kill_switch.changed') line = p.on ? 'Turned the kill switch on' : 'Turned the kill switch off';
    add(box, add(el('div', 'name'), el('span', null, a.display_name + ', ' + a.role + ' '), tag(a.tag || 'demo persona, not authenticated')));
    box.appendChild(el('div', 'line', line));
    const hash = p.plan_hash || (p.approval && p.approval.plan_hash);
    if (hash) box.appendChild(el('div', 'plan', 'plan ' + short(hash) + (p.expires_in_minutes ? ' · expires in ' + p.expires_in_minutes + ' min' : '')));
    if (p.comment) box.appendChild(el('div', 'line muted', '“' + p.comment + '”'));
    box.appendChild(el('div', 'muted', new Date(e.ts).toLocaleTimeString()));
    return box;
  }

  function decisionCard(e) {
    const p = e.payload;
    const d = p.decision || {};
    const card = el('div', 'card agent item');
    const head = el('div', 'head');
    add(head, el('span', 'who', agentName(e.actor)), tag('recorded', 'recorded'));
    if (p.tools) head.appendChild(tag('may call ' + p.tools.length + ' tools'));
    if (p.accepted === false) head.appendChild(tag('refused by a guard', 'refuse'));
    card.appendChild(head);
    card.appendChild(el('div', 'decision', decisionSentence(e.actor, d)));
    const why = el('div', 'part');
    add(why, el('b', null, 'Why'), el('span', null, d.reason));
    card.appendChild(why);
    if (d.retain_grants && d.retain_grants.length) {
      const part = el('div', 'part');
      part.appendChild(el('b', null, (e.payload.phase === 'B' ? 'Adds' : 'Keeps') + ' ' + d.retain_grants.length + ' privileges'));
      const ul = el('ul');
      for (const g of d.retain_grants) ul.appendChild(el('li', 'mono', g.privilege + ' on ' + g.object));
      part.appendChild(ul);
      card.appendChild(part);
    }
    if (d.addressed_outcomes && d.addressed_outcomes.length) {
      const part = el('div', 'part');
      part.appendChild(el('b', null, 'Past outcomes addressed'));
      const ul = el('ul');
      for (const o of d.addressed_outcomes) ul.appendChild(el('li', null, o.doc_id + ': ' + o.disposition + '. ' + o.reason));
      part.appendChild(ul);
      card.appendChild(part);
    }
    if (d.confidence) {
      const part = el('div', 'part');
      add(part, el('b', null, 'Confidence'), el('span', null, d.confidence));
      card.appendChild(part);
    }
    if (d.unknowns && d.unknowns.length) {
      const part = el('div', 'part');
      part.appendChild(el('b', null, 'What I could not confirm'));
      const ul = el('ul');
      for (const u of d.unknowns) ul.appendChild(el('li', null, u));
      part.appendChild(ul);
      card.appendChild(part);
    }
    const ev = el('details');
    const ids = new Set(d.evidence || []);
    for (const g of d.retain_grants || []) for (const id of g.evidence || []) ids.add(id);
    ev.appendChild(el('summary', null, 'Evidence: ' + ids.size + ' items, every number from a live tool call'));
    const chips = el('div', 'chips');
    for (const id of ids) chips.appendChild(el('span', 'chip', id));
    ev.appendChild(chips);
    card.appendChild(ev);
    if (p.tools) {
      const t = el('details');
      t.appendChild(el('summary', null, 'Permission: ' + p.tools.join(', ')));
      card.appendChild(t);
    }
    return card;
  }

  function decisionSentence(actor, d) {
    switch (actor) {
      case 'analysis': return d.decision === 'fix' ? 'Fix it: replace the owner membership with explicit grants' : 'Decision: ' + d.decision;
      case 'remediation': return d.decision === 'applied' ? 'The approved plan is applied, once' : 'Escalate to a human';
      case 'verification': return d.decision === 'pass' ? 'Verification passes' : 'Verification: ' + d.decision;
      case 'supervision': return d.decision === 'reopen' ? 'Recommend reopening the finding' : (d.decision === 'annotate' ? 'Authorised change, reseal' : 'Inconclusive');
      default: return d.decision;
    }
  }

  // ---- Focus: the current step's range, revealed one item at a time ----

  function stepItems() {
    const from = exploring() ? state.fromSeq : state.fromSeq;
    const to = exploring() ? Infinity : (state.toSeq === null ? Infinity : state.toSeq);
    return state.events.filter((e) => e.seq > from && e.seq <= to && FOCUS_KINDS.has(e.kind));
  }

  function grouped(items) {
    // Consecutive passing gates and successful tool calls fold into one expandable group; refusals never fold.
    const out = [];
    let run = [];
    const foldable = (e) => (e.kind === 'gate.checked' && e.payload.result === 'PASS') || (e.kind === 'tool.invoked' && !e.payload.error);
    const flush = () => {
      if (run.length >= 3) out.push({ group: run });
      else for (const r of run) out.push({ one: r });
      run = [];
    };
    for (const e of items) {
      if (foldable(e)) run.push(e); else { flush(); out.push({ one: e }); }
    }
    flush();
    return out;
  }

  function renderFocus() {
    const box = $('focus');
    clear(box);
    const items = grouped(stepItems());
    const visible = exploring() ? items : items.slice(0, state.revealed);
    if (!visible.length) {
      box.appendChild(el('div', 'empty', exploring() ? 'Explore mode. Use the controls below; everything shows here and in the timeline.' : 'Press Next to begin.'));
      return;
    }
    for (const it of visible) {
      if (it.one) { box.appendChild(renderItem(it.one)); continue; }
      const g = el('details', 'strip-group item');
      const gates = it.group.filter((e) => e.kind === 'gate.checked').length;
      const tools = it.group.length - gates;
      const parts = [];
      if (gates) parts.push(gates + ' check' + (gates === 1 ? '' : 's') + ' passed');
      if (tools) parts.push(tools + ' tool call' + (tools === 1 ? '' : 's'));
      g.appendChild(el('summary', null, parts.join(', ')));
      for (const e of it.group) g.appendChild(renderItem(e));
      box.appendChild(g);
    }
    if (!exploring() && state.revealed < items.length) box.onclick = () => { state.revealed = items.length; renderFocus(); renderGuide(); };
    else box.onclick = null;
  }

  function scheduleReveal() {
    if (exploring()) { renderFocus(); return; }
    if (state.revealTimer) return;
    const tick = () => {
      state.revealTimer = null;
      const total = grouped(stepItems()).length;
      if (state.revealed < total) {
        state.revealed = document.hidden ? total : state.revealed + 1;
        renderFocus();
        renderGuide();
        const last = $('focus').lastElementChild;
        if (last && last.scrollIntoView) last.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        if (state.revealed < total) state.revealTimer = setTimeout(tick, REVEAL_MS);
      }
    };
    tick();
  }

  function renderEarlier() {
    const list = $('earlier-list');
    clear(list);
    const items = state.events.filter((e) => e.seq <= state.fromSeq && FOCUS_KINDS.has(e.kind) && (e.kind === 'agent.decision' || e.kind.startsWith('approval.') || e.kind === 'finding.state_changed' || e.kind === 'chaos.fired' || e.kind === 'drift.detected' || e.kind === 'evidence.sealed'));
    for (const e of items.slice(-12)) list.appendChild(renderItem(e));
    $('earlier').classList.toggle('hidden', items.length === 0);
  }

  // ---- Timeline ----

  const FILTERS = [['all', 'All'], ['agent', 'Agent'], ['gate', 'Gates'], ['human', 'Human'], ['chaos', 'Faults']];
  const GLYPH = { agent: '●', gate: '⛨', tool: '⎇', human: '✎', system: '·', chaos: '🔧' };

  function renderFilters() {
    const f = $('filters');
    clear(f);
    for (const [k, label] of FILTERS) {
      const b = el('button', state.filter === k ? 'on' : '', label);
      b.type = 'button';
      b.onclick = () => { state.filter = k; renderFilters(); renderTimelineAll(); };
      f.appendChild(b);
    }
  }

  function rowFor(e) {
    const li = el('li');
    li.dataset.seq = e.seq;
    const refused = (e.kind === 'gate.checked' && e.payload.result === 'REFUSED') || (e.kind === 'tool.invoked' && e.payload.error);
    if (refused) li.classList.add('refused');
    const a = e.actor_type === 'gate' || e.actor_type === 'tool' ? e.actor_type : e.actor_type;
    add(li, el('span', 'seq', e.seq), el('span', 'g ' + a, GLYPH[e.actor_type] || '·'), el('span', 'sum', e.summary));
    li.title = e.kind + ' · ' + e.source_label;
    li.onclick = () => {
      const open = li.classList.toggle('open');
      const old = li.querySelector('pre');
      if (old) old.remove();
      if (open) li.appendChild(el('pre', null, pretty({ kind: e.kind, actor: e.actor, stage: e.stage, source_label: e.source_label, agent_mode: e.agent_mode, target_mode: e.target_mode, payload: e.payload })));
    };
    return li;
  }

  function passes(e) {
    if (state.filter === 'all') return true;
    if (state.filter === 'gate') return e.actor_type === 'gate' || e.actor_type === 'tool';
    return e.actor_type === state.filter;
  }

  function renderTimelineAll() {
    const ol = $('timeline');
    clear(ol);
    for (const e of state.events) if (passes(e)) ol.appendChild(rowFor(e));
    state.renderedSeq = state.lastSeq;
    scrollBottom();
  }

  function renderTimelineAppend(e) {
    if (!passes(e)) { state.renderedSeq = e.seq; return; }
    $('timeline').appendChild(rowFor(e));
    state.renderedSeq = e.seq;
    if (state.atBottom) scrollBottom(); else $('new-events').classList.remove('hidden');
  }

  function scrollBottom() {
    const ol = $('timeline');
    ol.scrollTop = ol.scrollHeight;
    $('new-events').classList.add('hidden');
  }

  // ---- Gauges ----

  function renderGauges() {
    const g = state.gauges;
    const box = $('gauges');
    if (!g) { box.classList.add('hidden'); return; }
    box.classList.remove('hidden');
    clear(box);
    const items = [
      ['queue', g.queue, g.queue_capacity],
      ['in flight', g.in_flight, g.workers],
      ['agent slots', g.agent_slots_used, g.agent_slots],
      ['processed', g.processed, g.total],
      ['unique', g.dedup, null],
      ['heap MB', g.heap_mb, null],
    ];
    for (const [label, v, max] of items) {
      const n = el('div', 'g');
      add(n, el('b', null, v), el('span', null, ' ' + label + (max ? ' / ' + max : '')));
      if (max) { const bar = el('span', 'bar'); const i = el('i'); i.style.width = Math.min(100, Math.round(100 * v / max)) + '%'; bar.appendChild(i); n.appendChild(bar); }
      box.appendChild(n);
    }
    if (g.rss_mb) add(box, add(el('div', 'g'), el('b', null, g.rss_mb), el('span', null, ' resident MB')));
    if (g.done) setTimeout(() => { if (state.gauges && state.gauges.done) { state.gauges = null; renderGauges(); } }, 8000);
  }

  // ---- Guide bar ----

  function renderGuide() {
    const g = $('guide');
    clear(g);
    g.classList.toggle('explore-mode', exploring());
    if (exploring()) { renderExplore(g); return; }
    const step = GUIDE[state.step];
    const n = state.step + 1;
    const isReady = ready();
    const box = el('div', 'step');
    add(box, el('div', 'n', 'Step ' + n + ' of ' + GUIDE.length + (step.persona ? ' · acting as ' + (step.persona === 'dba' ? 'Dana, DBA' : 'Sam, security engineer') : '')),
      el('div', 'cap', step.title), el('div', 'look', step.look));
    g.appendChild(box);
    const actions = el('div', 'actions');
    if (state.pending && state.toSeq === null) actions.appendChild(el('span', 'persona-hint', step.commands ? 'Waiting for the system…' : 'Waiting…'));
    else if (state.pending && !isReady) actions.appendChild(el('span', 'persona-hint', 'Revealing… click the panel to skip'));
    else if (!state.pending) actions.appendChild(el('span', 'persona-hint', step.commands ? 'Next sends the command' : 'Next shows what happened'));
    const next = el('button', 'btn primary', state.pending && n === GUIDE.length ? 'Finish' : 'Next');
    next.type = 'button';
    next.id = 'next';
    next.disabled = state.pending && !isReady;
    next.onclick = onNext;
    actions.appendChild(next);
    g.appendChild(actions);
  }

  function ready() {
    return state.pending && state.toSeq !== null && state.revealed >= grouped(stepItems()).length;
  }

  async function onNext() {
    if (state.pending) {
      if (!ready()) return;
      finishStep();
      if (exploring()) return;
    }
    await startStep();
  }

  function finishStep() {
    state.fromSeq = state.toSeq;
    state.toSeq = null;
    state.revealed = 0;
    state.pending = false;
    state.step += 1;
    renderEarlier();
    if (exploring()) state.fromSeq = state.lastSeq;
    renderFocus();
    renderGuide();
  }

  async function startStep() {
    const step = GUIDE[state.step];
    state.pending = true;
    if (step.persona) $('persona').value = step.persona;
    state.toSeq = null;
    state.revealed = 0;
    // Observe steps may already have their awaited event.
    for (const e of state.events) if (e.seq > state.fromSeq && step.await(e)) { state.toSeq = e.seq; break; }
    renderFocus();
    renderGuide();
    if (step.commands) {
      for (const [type, args] of step.commands()) {
        const r = await send(type, step.persona, args);
        if (r && r.status >= 400 && !(r.body.error && r.body.error.code === 'SEPARATION_OF_DUTIES')) {
          toast(type + ' refused: ' + (r.body.error ? r.body.error.code : r.status));
        }
      }
    }
    scheduleReveal();
    renderGuide();
    const next = $('next');
    if (next) next.focus();
  }

  function renderExplore(g) {
    const box = el('div', 'step');
    add(box, el('div', 'n', 'Guided flow complete'), el('div', 'cap', 'Everything the agents said was recorded. Everything that stopped them was live.'),
      el('div', 'look', 'Explore: inject a fault, block writes, run the burst again, or run the lifecycle on the reopened finding with the persona above.'));
    g.appendChild(box);
    const x = el('div', 'explore');
    const f = active();
    const btn = (label, fn, cls) => { const b = el('button', 'btn ' + (cls || 'secondary'), label); b.type = 'button'; b.onclick = fn; return b; };
    const lifecycle = el('div', 'grp');
    lifecycle.appendChild(el('span', 'muted', 'Lifecycle'));
    lifecycle.appendChild(btn('Start analysis', () => send('start_analysis', currentPersona(), { finding_id: active().id })));
    lifecycle.appendChild(btn('Request approval', () => send('request_approval', currentPersona(), { finding_id: active().id, plan_hash: latestPlan(active()).hash })));
    lifecycle.appendChild(btn('Approve', () => send('approve', currentPersona(), { finding_id: active().id, plan_hash: latestPlan(active()).hash })));
    lifecycle.appendChild(btn('Reject', () => send('reject', currentPersona(), { finding_id: active().id, plan_hash: latestPlan(active()).hash })));
    lifecycle.appendChild(btn('Trigger drift', () => send('trigger_drift', currentPersona(), { finding_id: active().id })));
    x.appendChild(lifecycle);
    const faults = el('div', 'grp');
    faults.appendChild(el('span', 'muted', 'Inject'));
    for (const sw of ['duplicate_delivery', 'drop_response', 'abort_before_commit']) faults.appendChild(btn(sw.replace(/_/g, ' '), () => send('arm_chaos', currentPersona(), { switch: sw })));
    x.appendChild(faults);
    const kill = el('div', 'grp');
    const on = state.snap && state.snap.kill_switch;
    kill.appendChild(btn(on ? 'Kill switch: on' : 'Kill switch: off', () => send('kill_switch', currentPersona(), { on: !on })));
    x.appendChild(kill);
    const burst = el('div', 'grp');
    const count = el('input');
    count.type = 'number'; count.min = '1'; count.max = '5000'; count.value = '5000'; count.setAttribute('aria-label', 'Burst count');
    burst.appendChild(count);
    burst.appendChild(btn('Burst', () => send('start_burst', currentPersona(), { count: Number(count.value) || 5000 })));
    x.appendChild(burst);
    const exp = el('div', 'grp');
    const a = el('a', 'btn secondary', 'Evidence export');
    a.href = '/api/evidence.json'; a.target = '_blank'; a.rel = 'noopener';
    exp.appendChild(a);
    x.appendChild(exp);
    g.appendChild(x);
    if (!f) return;
  }

  // ---- About sheet ----

  function renderAbout() {
    const b = $('about-body');
    clear(b);
    const s = state.snap;
    const sec = (h, items) => { b.appendChild(el('h3', null, h)); const ul = el('ul'); for (const i of items) ul.appendChild(el('li', null, i)); b.appendChild(ul); };
    b.appendChild(el('p', null, 'This prototype demonstrates the control logic of an agentic remediation system. It runs entirely in memory, needs no account or key, and is honest about what is simulated.'));
    sec('Recorded', ['Agent responses are hand-authored recordings (captured: ' + s.labels.captured + '), selected turn by turn from live tool results. They never contain tool results; every number on a card came from a live tool call.',
      'If a situation has no recorded turn, the run stops safely and says so. Nothing is guessed.']);
    sec('Simulated', ['The PostgreSQL target is an in-memory model of roles, memberships, grants, a transaction, a lock and a ledger. No SQL is parsed. Grantor rules on revoke are simplified.',
      'Sam and Dana are demo personas chosen in the page. Separation of duties compares persona ids; nothing is authenticated.']);
    sec('Seeded', ['Telemetry, job history, the audit feed, the knowledge corpus and the fleet grid are JSON resources loaded per session.']);
    sec('Live', ['Tool allowlists, argument validation, policy, plan hashing, approval binding, guards, the compare-and-set lease, the atomic commit with its ledger marker, outcome classification, reconciliation, verification checks, the drift poll and the bounded burst all execute for real on every run.',
      'State is lost on reset, expiry or restart by design. The timeline lives in memory.']);
    sec('Proved by tests', ['duplicateDeliveryAppliesOnce (looped 100 times), lostResponseReconcilesWithoutSecondMutation, abortBeforeCommitLeavesNoTrace, statusWaitsForInFlightWrite, foreignToolAndExtraArgsRefused, approvalRules, completenessAndJustificationGuards, verdictGuardOverridesPass, driftDetectedAndReopened, reopenIsAtomic, recordingsCoverEveryRequiredTrigger, goldenFlowHasNoRecordingMiss, everythingSimulatedIsLabelled, burstIsBounded.']);
  }

  // ---- Boot ----

  async function boot() {
    renderFilters();
    $('about-open').onclick = () => { renderAbout(); $('about').showModal(); };
    $('reset').onclick = () => send('reset', currentPersona() || 'requester', {});
    $('again').onclick = () => { document.cookie = 'adr_session=; Max-Age=0; path=/'; location.reload(); };
    $('new-events').onclick = () => { state.atBottom = true; scrollBottom(); };
    $('timeline').addEventListener('scroll', () => {
      const ol = $('timeline');
      state.atBottom = ol.scrollHeight - ol.scrollTop - ol.clientHeight < 24;
      if (state.atBottom) $('new-events').classList.add('hidden');
    });
    let snap = null;
    for (let i = 0; i < 40 && !snap; i++) {
      try { snap = await fetchState(); } catch (e) { await new Promise((r) => setTimeout(r, 750)); }
    }
    if (!snap) { $('waking').querySelector('p').textContent = 'The demo did not answer. Reload to try again.'; return; }
    state.snap = snap;
    state.epoch = snap.session_epoch;
    state.fromSeq = snap.last_seq || 0;
    $('waking').classList.add('hidden');
    renderHeader(); renderRail(); renderContext(); renderGuide(); renderFocus();
    // Resume: if the session already progressed (a reload mid-run), drop into explore mode once the replay arrives.
    connect();
    setTimeout(() => {
      if (state.step === 0 && !state.pending && state.events.some((e) => e.kind === 'finding.state_changed')) {
        state.step = GUIDE.length;
        state.fromSeq = 0;
        renderEarlier();
        renderFocus(); renderGuide();
      }
    }, 1200);
  }

  boot();
})();
