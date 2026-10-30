/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
// SignalDeck Web: Messages, Contacts, Map and Help pages (loaded after app.js, which holds the shared helpers).
'use strict';

// ---- messages ----
const TYPE_NAMES = ['Private', 'Group', 'All call'];
S.thread = null; S.contacts = []; S.conversations = [];
async function loadConversations() {
  try {
    const j = await api('conversations'); S.conversations = j.conversations;
    const chip = c => c.transport === 'page' ? 'PAGE' : c.transport === 'm17' ? 'M17' : 'DMR';
    const same = (a, b) => a && b && (a.transport && a.transport !== 'dmr' ? a.threadKey === b.threadKey : a.peer === b.peer && a.type === b.type);
    $('convList').innerHTML = j.conversations.map(c => '<div class="item click' + (same(S.thread, c) ? ' cur' : '') + '" data-key="' + esc(c.transport && c.transport !== 'dmr' ? c.threadKey : (c.peer + ':' + c.type)) + '"><div class="main"><b>' + esc(c.name || ((c.type === 1 ? 'TG ' : 'ID ') + c.peer)) + '</b> <span class="chip dim">' + chip(c) + '</span>' + (c.unread ? ' <span class="chip">' + c.unread + '</span>' : '') + '<div class="sub">' + (c.outgoing ? 'You: ' : '') + esc(c.lastText) + '</div></div><span class="t">' + ago(c.lastTs) + '</span></div>').join('') || '<div class="empty">No texts yet</div>';
    $('quick').innerHTML = j.quickReplies.map(q => '<button type="button">' + esc(q) + '</button>').join('');
    loadThread();
  } catch (e) { }
  renderSendState();
}
async function loadThread() {
  const t = S.thread; if (!t) { $('thread').innerHTML = '<div class="empty">Choose a conversation on the left</div>'; return; }
  $('threadTitle').textContent = String(t.name || t.peer || t.address || '');
  const kind = t.transport === 'page' ? 'Page' : t.transport === 'm17' ? 'M17' : TYPE_NAMES[t.type] || 'DMR';
  $('threadSub').textContent = kind + (t.transport && t.transport !== 'dmr' ? (t.address ? ' · ' + t.address : '') : ' · ' + t.peer);
  const q = t.transport && t.transport !== 'dmr' ? 'messages?thread=' + encodeURIComponent(t.threadKey) : 'messages?peer=' + t.peer + '&type=' + t.type;
  try {
    const j = await api(q);
    const st = m => !m.outgoing ? '' : m.status === 0 ? 'sending…' : m.status === 1 ? 'delivered' : m.status === 2 ? 'failed' : 'sent (no acknowledgement)';
    $('thread').innerHTML = j.messages.map(m => '<div class="bubble' + (m.outgoing ? ' out' : '') + '">' + (!m.outgoing && m.sender ? '<span class="from">' + esc(m.senderName || String(m.sender)) + '</span>' : '') + esc(m.text) + '<span class="meta' + (m.status === 2 ? ' fail' : '') + '">' + new Date(m.ts).toLocaleString() + (m.outgoing ? ' · ' + st(m) : '') + '</span></div>').join('') || '<div class="empty">No texts with ' + esc(t.name || t.peer) + ' yet</div>';
    $('thread').scrollTop = 1e9;
  } catch (e) { }
}
function openThread(peer, type, name) { S.thread = { peer, type, name, transport: 'dmr' }; if (location.hash !== '#messages') location.hash = 'messages'; else loadConversations(); }
function renderSendState() {
  const ok = S.status && S.status.webTx;
  $('sendForm').querySelector('button').disabled = !ok || !S.thread;
  $('sendNote').textContent = !ok ? 'Sending needs “Allow transmit from the web” on the phone (Device → SignalDeck Web).' : !S.thread ? 'Choose a conversation, or New to text a contact.' : '';
}
$('convList').onclick = e => { const it = e.target.closest('.item'); if (!it) return; const c = S.conversations.find(x => (x.transport && x.transport !== 'dmr' ? x.threadKey : (x.peer + ':' + x.type)) === it.dataset.key); if (c) { S.thread = c; if (location.hash !== '#messages') location.hash = 'messages'; else loadConversations(); } };
$('quick').onclick = e => { if (e.target.tagName === 'BUTTON') { $('sendText').value = e.target.textContent; $('sendText').focus(); } };
$('sendForm').addEventListener('submit', async e => {
  e.preventDefault(); if (!S.thread) return;
  const body = { text: $('sendText').value, transport: S.thread.transport || 'dmr' };
  if (body.transport === 'dmr') { body.peer = S.thread.peer; body.type = S.thread.type; }
  else if (body.transport === 'page') { body.ric = parseInt(S.thread.address, 10) || 0; body.hz = S.status && S.status.channel ? S.status.channel.rxFreq : 0; body.sign = true; }
  else { body.dest = S.thread.threadKey === 'everyone' ? '@ALL' : (S.thread.address || ''); }
  const j = await act('messages', body);
  if (j) { $('sendText').value = ''; setTimeout(loadThread, 500); }
});
$('newMsg').onclick = () => { location.hash = 'contacts'; toast('Pick a contact, then Text'); };

// ---- contacts ----
S.editing = null;
async function loadContacts() { try { const j = await api('contacts'); S.contacts = j.contacts; renderContacts(); } catch (e) { } }
function renderContacts() {
  const q = $('ctSearch').value.trim().toLowerCase();
  const rows = S.contacts.filter(c => !q || c.name.toLowerCase().includes(q) || (c.number > 0 && String(c.number).includes(q)) || (c.callsign || '').toLowerCase().includes(q) || (c.rics || '').split(';').includes(q));
  $('ctList').innerHTML = rows.map(c => '<div class="item click' + (S.editing && S.editing.id === c.id ? ' cur' : '') + '" data-id="' + c.id + '"><div class="main"><b>' + esc(c.name || '—') + '</b><div class="sub mono">' + (c.number > 0 ? (c.type === 1 ? 'TG ' : c.type === 2 ? 'ALL ' : 'ID ') + c.number : 'no DMR ID') + (c.callsign ? ' · ' + esc(c.callsign) : '') + (c.rics ? ' · pager ' + esc(c.rics.replace(/;/g, ', ')) : '') + (c.lastTs ? ' · heard ' + ago(c.lastTs) : '') + '</div></div><span class="chip dim">' + TYPE_NAMES[c.type].toUpperCase() + '</span></div>').join('') || '<div class="empty">No contacts</div>';
}
function editContact(c) {
  S.editing = c || null; $('ctFormTitle').textContent = c ? 'EDIT CONTACT' : 'NEW CONTACT';
  $('ctName').value = c ? c.name : ''; $('ctNumber').value = c && c.number > 0 ? c.number : ''; $('ctType').value = c ? c.type : 0;
  $('ctCall').value = c ? (c.callsign || '') : ''; $('ctRics').value = c ? (c.rics || '').replace(/;/g, ', ') : ''; $('ctNotes').value = c ? (c.notes || '') : '';
  $('ctHome').value = c && c.homeLat != null ? c.homeLat.toFixed(5) + ', ' + c.homeLon.toFixed(5) : ''; $('ctHomeLabel').value = c ? (c.homeLabel || '') : '';
  $('ctLast').textContent = c && c.lastTs ? 'Last heard ' + ago(c.lastTs) + (c.lastSource ? ' by ' + c.lastSource : '') + (c.lastLat != null ? ' at ' + c.lastLat.toFixed(4) + ', ' + c.lastLon.toFixed(4) : '') : '';
  $('ctDel').classList.toggle('hidden', !c); $('ctText').classList.toggle('hidden', !c || !(c.number > 0)); renderContacts();
  const hist = $('ctHistory'); hist.innerHTML = '';
  if (c) api('contacts/history?id=' + c.id).then(j => { if (!S.editing || S.editing.id !== c.id) return; hist.innerHTML = j.history.length ? j.history.map(h => '<div class="item"><div class="main"><b>' + (h.sent ? '⬆ Sent ' : '⬇ Heard ') + esc(h.source) + (h.detail ? ' · ' + esc(h.detail) : '') + '</b><div class="sub mono">' + new Date(h.ts).toLocaleString() + (h.lat != null ? ' · ' + h.lat.toFixed(4) + ', ' + h.lon.toFixed(4) : '') + '</div></div></div>').join('') : '<div class="empty">Nothing heard from or sent to this contact yet</div>'; }).catch(() => { });
}
/** Add or open a contact for an identity shown anywhere on the web pages (a callsign or a DMR ID). */
function contactFor(call, dmrId) {
  const c = S.contacts.find(x => (call && x.callsign && (x.callsign === call || x.callsign.split('-')[0] === call.split('-')[0])) || (dmrId && x.number === dmrId && x.type === 0));
  location.hash = 'contacts';
  setTimeout(() => { if (c) editContact(c); else { editContact(null); $('ctCall').value = call || ''; $('ctNumber').value = dmrId || ''; $('ctName').value = call || ''; toast('Not in your contacts: fill in what you know and Save'); } }, 50);
}
$('ctSearch').oninput = renderContacts;
$('ctList').onclick = e => { const it = e.target.closest('.item'); if (it) editContact(S.contacts.find(c => c.id === +it.dataset.id)); };
$('ctNew').onclick = () => editContact(null);
$('ctForm').addEventListener('submit', async e => {
  e.preventDefault();
  const j = await act('contacts', { id: S.editing ? S.editing.id : 0, name: $('ctName').value, number: +$('ctNumber').value || 0, type: +$('ctType').value,
    callsign: $('ctCall').value, rics: $('ctRics').value, notes: $('ctNotes').value, home: $('ctHome').value, homeLabel: $('ctHomeLabel').value });
  if (j) { await loadContacts(); if (!S.editing) editContact(null); }
});
$('ctDel').onclick = async () => {
  if (!S.editing || !confirm('Delete ' + (S.editing.name || S.editing.number) + '?')) return;
  if (await act('contacts/delete', { id: S.editing.id })) { editContact(null); loadContacts(); }
};
$('ctText').onclick = () => { const c = S.editing; if (c) openThread(c.number, c.type, c.name); };

// ---- map: OpenStreetMap tiles (darkened like the phone's map), Web Mercator, drag / wheel / buttons ----
const M = { z: 9, lat: 0, lon: 0, fitted: false, marks: [] };
const TS = 256;
const wx = (lon, z) => (lon + 180) / 360 * TS * 2 ** z;
const wy = (lat, z) => { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * TS * 2 ** z; };
const lonOf = (x, z) => x / (TS * 2 ** z) * 360 - 180;
const latOf = (y, z) => { const n = Math.PI - 2 * Math.PI * y / (TS * 2 ** z); return 180 / Math.PI * Math.atan(Math.sinh(n)); };
async function loadMap() {
  try {
    const [st, ch] = await Promise.all([api('aprs/stations?limit=500'), api('channels?all=1')]);   // the map shows every channel with a location, as the phone's map does
    M.marks = M.marks.filter(m => m.kind === 'rpt');
    for (const s of st.stations) if (s.lat != null) M.marks.push({ kind: 'aprs', lat: s.lat, lon: s.lon, name: s.call, s });
    for (const c of ch.channels) if (c.lat != null) M.marks.push({ kind: 'ch', lat: c.lat, lon: c.lon, name: c.name || ('Channel ' + c.number), c });
    $('mapCount').textContent = M.marks.filter(m => m.kind === 'aprs').length + ' STATIONS · ' + M.marks.filter(m => m.kind === 'ch').length + ' CHANNELS';
    if (!M.fitted) { M.fitted = true; fitMap(); } else drawMap();
  } catch (e) { }
}
function visibleMarks() { return M.marks.filter(m => (m.kind === 'aprs' ? $('mapAprs').checked : m.kind === 'rpt' ? $('mapRpt').checked : $('mapCh').checked)); }
/** Repeaters near the phone (the phone's catalog: RadioID, BrandMeister, Hearham) as a layer. */
async function loadRepeaterLayer(force, here) {
  M.marks = M.marks.filter(m => m.kind !== 'rpt');
  if (!$('mapRpt').checked) { drawMap(); return; }
  $('mapCount').textContent = 'LOOKING FOR REPEATERS…';
  try {
    const q = [force ? 'force=1' : '', here ? 'lat=' + here.lat.toFixed(5) + '&lon=' + here.lon.toFixed(5) : ''].filter(Boolean).join('&');
    const j = await api('repeaters' + (q ? '?' + q : ''));
    for (const r of j.repeaters) if (r.lat != null) M.marks.push({ kind: 'rpt', lat: r.lat, lon: r.lon, name: r.callsign + ' ' + mhz(r.rxHz), r });
    $('mapCount').textContent = M.marks.filter(m => m.kind === 'aprs').length + ' STATIONS · ' + M.marks.filter(m => m.kind === 'ch').length + ' CHANNELS · ' + j.repeaters.length + ' REPEATERS (' + j.radiusMi + ' MI ' + (j.around === 'map' ? 'AROUND THE MAP CENTRE' : 'AROUND THE PHONE') + ')';
    if (here && !j.repeaters.length) toast('No repeaters known within ' + j.radiusMi + ' miles of the middle of the map');
    if (j.status) toast(j.status);
  } catch (e) { toast(e.message); }
  drawMap();
}
async function showRepeater(m) {
  const r = m.r, c = $('mapCard');
  const off = r.txHz - r.rxHz;
  c.innerHTML = '<b style="color:var(--violet)">' + esc(r.callsign) + '</b> <span class="sub mono">' + mhz(r.rxHz) + ' MHz' + (off ? ' ' + (off > 0 ? '+' : '−') + (Math.abs(off) / 1e6).toFixed(3) : ' simplex') + '</span>'
    + '<div class="sub">' + [r.fm ? 'FM' + (r.toneUp ? ' tone ' + esc(r.toneUp) : '') : '', r.dmr ? 'DMR' + (r.cc >= 0 ? ' CC' + r.cc : '') + (r.network ? ' ' + esc(r.network) : '') : '', esc([r.city, r.region].filter(Boolean).join(', ')), r.distanceKm != null ? (r.distanceKm / 1.609344).toFixed(1) + ' mi' : '', r.approx ? 'approximate position' : ''].filter(Boolean).join(' · ') + '</div>'
    + (r.dmr ? '<div class="tgs" id="rptTgs"><span class="sub">Loading talkgroups…</span></div>' : '')
    + '<button class="btn small-btn" id="rptAdd">Add to my channels</button> <button class="btn small-btn" id="rptCov">Coverage</button>';
  showCard();
  $('rptCov').onclick = () => focusCoverage({ hz: r.rxHz, lat: m.lat, lon: m.lon, name: r.callsign });
  if (r.dmr) {
    try {
      const t = await api('repeaters/talkgroups?key=' + encodeURIComponent(r.key));
      $('rptTgs').innerHTML = t.talkgroups.length ? t.talkgroups.map(g => '<label class="chk"><input type="checkbox" value="' + g.id + '"> TG ' + g.id + (g.name ? ' ' + esc(g.name) : '') + ' · TS' + g.slot + '</label>').join('') : '<span class="sub">No talkgroups listed: the plan uses the repeater\'s defaults</span>';
    } catch (e) { $('rptTgs').textContent = e.message; }
  }
  $('rptAdd').onclick = async () => {
    const talk = [...c.querySelectorAll('#rptTgs input:checked')].map(i => +i.value);
    try {
      const p = await api('repeaters/add', { key: r.key, talk });
      if (!confirm('Add ' + r.callsign + '?\n\n' + p.preview)) return;
      await act('repeaters/add', { key: r.key, talk, confirm: true }); loadChannels();
    } catch (e) { toast(e.message); }
  };
}
function fitMap() {
  const v = visibleMarks(); const el = $('map'); const W = el.clientWidth || 800, H = el.clientHeight || 500;
  if (!v.length) { M.lat = 39.8; M.lon = -98.6; M.z = 4; drawMap(); return; }
  const lat0 = Math.min(...v.map(m => m.lat)), lat1 = Math.max(...v.map(m => m.lat)), lon0 = Math.min(...v.map(m => m.lon)), lon1 = Math.max(...v.map(m => m.lon));
  let z = 14; while (z > 2 && (wx(lon1, z) - wx(lon0, z) > W - 80 || wy(lat0, z) - wy(lat1, z) > H - 80)) z--;
  M.z = z; M.lat = (lat0 + lat1) / 2; M.lon = (lon0 + lon1) / 2; drawMap();
}
function drawMap() {
  const el = $('map'); const W = el.clientWidth, H = el.clientHeight; if (!W) return;
  const cx = wx(M.lon, M.z), cy = wy(M.lat, M.z), x0 = cx - W / 2, y0 = cy - H / 2, n = 2 ** M.z;
  let html = '';
  for (let ty = Math.floor(y0 / TS); ty <= Math.floor((y0 + H) / TS); ty++) {
    if (ty < 0 || ty >= n) continue;
    for (let tx = Math.floor(x0 / TS); tx <= Math.floor((x0 + W) / TS); tx++) {
      const tw = ((tx % n) + n) % n;
      html += '<img alt="" referrerpolicy="origin" src="https://tile.openstreetmap.org/' + M.z + '/' + tw + '/' + ty + '.png" style="left:' + Math.round(tx * TS - x0) + 'px;top:' + Math.round(ty * TS - y0) + 'px">';
    }
  }
  $('mapTiles').innerHTML = html;
  const sq = (c, attr, cls, min) => {
    const l = wx(c.w, M.z) - x0, r = wx(c.e, M.z) - x0, t = wy(c.n, M.z) - y0, b = wy(c.s, M.z) - y0;
    if (r < 0 || l > W || b < 0 || t > H) return '';
    return '<div class="' + cls + '" ' + attr + ' style="left:' + l + 'px;top:' + t + 'px;width:' + Math.max(min, r - l + 0.5) + 'px;height:' + Math.max(min, b - t + 0.5) + 'px;background:' + c.color + ';opacity:' + (c.alpha || .66) + '"></div>';
  };
  $('mapCells').innerHTML = (M.cells || []).map((c, i) => sq(c, 'data-c="' + i + '"', 'cov', 3)).join('');
  const cv = $('mapPredCv'), dpr = window.devicePixelRatio || 1;
  if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cv.style.width = W + 'px'; cv.style.height = H + 'px'; }
  const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H);
  for (const c of M.pred || []) {
    const l = wx(c.w, M.z) - x0, r = wx(c.e, M.z) - x0, t = wy(c.n, M.z) - y0, b = wy(c.s, M.z) - y0;
    if (r < 0 || l > W || b < 0 || t > H) continue;
    g.globalAlpha = c.alpha; g.fillStyle = c.color; g.fillRect(l, t, r - l + 0.5, b - t + 0.5);
  }
  g.globalAlpha = 1;
  $('mapMarks').innerHTML = visibleMarks().map(m => {
    const x = wx(m.lon, M.z) - x0, y = wy(m.lat, M.z) - y0; if (x < -40 || y < -40 || x > W + 40 || y > H + 40) return '';
    return '<div class="mk ' + m.kind + '" data-i="' + M.marks.indexOf(m) + '" style="left:' + x + 'px;top:' + y + 'px" title="' + esc(m.name) + '"></div>'
      + (M.z >= 9 ? '<div class="mk-label" style="left:' + x + 'px;top:' + y + 'px">' + esc(m.name) + '</div>' : '');
  }).join('');
}
/** The coverage layer: the phone's measured grid squares, coloured on the phone's own scale. */
let covSeq = 0;
/** Coverage for all repeaters, or for M.covFocus (one repeater: measured squares and its terrain prediction, polled while it runs). */
async function loadCoverage(fit) {
  const l = $('mapCov').value, seq = ++covSeq, note = $('mapCovNote'), f = M.covFocus;
  M.cells = []; M.pred = []; hideCard();
  if (!l) { note.classList.add('hidden'); drawMap(); return; }
  let q = 'coverage?layer=' + l;
  if (f) q += '&key=' + encodeURIComponent(f.key || '') + (f.hz ? '&hz=' + f.hz + '&lat=' + f.lat + '&lon=' + f.lon + '&name=' + encodeURIComponent(f.name || '') : '') + ($('mapPred').checked ? '&predict=1' : '');
  try {
    const j = await api(q); if (seq !== covSeq) return;
    M.cells = j.cells; M.pred = j.predicted || []; M.covLayer = l;
    if (f) { f.key = j.key; if (!f.name && j.name) f.name = j.name; if (f.lat == null && j.lat != null) { f.lat = j.lat; f.lon = j.lon; } }
    fillCovRepeaters(j.repeaters || [], f);
    const who = f ? '<b>' + esc(f.name || 'Repeater') + '</b>' : '<b>All repeaters</b> (the best one in each square)';
    let t = who + ' · ' + j.cells.length + ' measured ' + (j.cells.length === 1 ? 'square' : 'squares') + (l === 'tx' ? ' with key-ups' : ' heard');
    if (f && $('mapPred').checked) t += j.predictError ? ' · terrain prediction failed: ' + esc(j.predictError) : !j.predictDone ? ' · terrain prediction: ' + esc(j.predictStatus || 'starting…') : j.predictSummary ? ' · ' + esc(j.predictSummary) : '';
    if (!j.logging) t += ' · Coverage logging is off on the phone (Settings), so nothing new is measured';
    note.innerHTML = t; note.classList.remove('hidden');
    if (f && $('mapPred').checked && !j.predictDone && !j.predictError) setTimeout(() => { if (seq === covSeq && S.page === 'map') loadCoverage(fit && !M.pred.length); }, 2000);
    if (fit && (M.cells.length || M.pred.length || f)) fitCoverage();
  } catch (e) { toast(e.message); }
  drawMap();
}
function fillCovRepeaters(list, f) {
  const sel = $('mapCovRpt'); const key = f ? f.key : '';
  let h = '<option value="">All repeaters</option>' + list.map(x => '<option value="' + esc(x.key) + '">' + esc(x.name || mhz(x.rxHz)) + ' · ' + x.rx + ' heard · ' + x.tx + ' key-ups</option>').join('');
  if (f && key && !list.some(x => x.key === key)) h += '<option value="' + esc(key) + '">' + esc(f.name || 'Repeater') + ' (nothing measured yet)</option>';
  sel.innerHTML = h; sel.value = key || '';
}
/** One repeater's coverage (from its card, a channel's card or the list): signal heard if no layer is chosen yet. */
function focusCoverage(f) {
  M.covFocus = f; if (!$('mapCov').value) $('mapCov').value = 'rx';
  $('mapCard').classList.add('hidden'); loadCoverage(true);
}
function fitCoverage() {
  const all = [...(M.cells || []), ...(M.pred || [])]; const f = M.covFocus; const el = $('map'); const W = el.clientWidth || 800, H = el.clientHeight || 500;
  if (!all.length && !(f && f.lat != null)) return;
  let s = Math.min(...all.map(c => c.s), f && f.lat != null ? f.lat : 90), n = Math.max(...all.map(c => c.n), f && f.lat != null ? f.lat : -90);
  let w = Math.min(...all.map(c => c.w), f && f.lon != null ? f.lon : 180), e = Math.max(...all.map(c => c.e), f && f.lon != null ? f.lon : -180);
  let z = 15; while (z > 2 && (wx(e, z) - wx(w, z) > W - 60 || wy(s, z) - wy(n, z) > H - 60)) z--;
  M.z = z; M.lat = (s + n) / 2; M.lon = (w + e) / 2; drawMap();
}
function showCell(c) {
  const who = M.covFocus ? esc(M.covFocus.name || 'this repeater') : 'the best repeater here';
  if (c.predicted) {
    $('mapCard').innerHTML = M.covLayer === 'tx'
      ? '<b style="color:var(--green)">Should key up (' + c.value + ' dB to spare)</b><div class="sub">Predicted · ' + who + '</div><div class="sub">Calculated from the terrain and a typical handheld on high power; the more key-ups the phone logs, the better this gets.</div>'
      : '<b style="color:' + c.color + '">About ' + c.value + ' dBm · ' + esc(c.sunits) + '</b><div class="sub">Predicted · ' + who + '</div><div class="sub">Not measured here: calculated from the terrain between this square and the repeater.</div>';
    showCard(); return;
  }
  $('mapCard').innerHTML = M.covLayer === 'tx'
    ? '<b>Transmit from here</b><div class="sub">' + c.keyed + ' reached the repeater · ' + c.failed + ' got no reply</div>'
    : '<b>Signal heard here</b><div class="sub mono">median ' + c.value + ' dBm' + (c.sunits ? ' · ' + esc(c.sunits) : '') + ' · ' + c.count + ' readings · ' + who + '</div>';
  showCard();
}
/** Show the card with its close button (it also closes with Esc, a click on empty map, or a layer change). */
function showCard() {
  const c = $('mapCard'); const x = document.createElement('button');
  x.type = 'button'; x.className = 'mcx'; x.setAttribute('aria-label', 'Close'); x.textContent = '×'; x.onclick = hideCard;
  c.prepend(x); c.classList.remove('hidden');
}
function hideCard() { $('mapCard').classList.add('hidden'); }
document.addEventListener('keydown', e => { if (e.key === 'Escape' && S.page === 'map') hideCard(); });
function showMark(m) {
  const c = $('mapCard'); let h;
  if (m.kind === 'rpt') { showRepeater(m); return; }
  if (m.kind === 'aprs') {
    const s = m.s;
    h = '<b class="pk-from click" title="Contact" onclick="contactFor(' + JSON.stringify(s.call) + ')">' + esc(s.call) + '</b> <span class="sub">' + ago(s.lastTs) + ' · heard ' + s.count + '×</span><div>' + esc(s.comment) + '</div><div class="sub mono">' + s.lat.toFixed(4) + ', ' + s.lon.toFixed(4)
      + (s.speedKmh != null ? ' · ' + Math.round(s.speedKmh) + ' km/h' : '') + (s.course != null ? ' · ' + s.course + '°' : '') + (s.alt ? ' · ' + s.alt + ' m' : '') + (s.via ? ' · via ' + esc(s.via) : '') + '</div>';
  } else {
    const ch = m.c;
    h = '<b>' + esc(m.name) + '</b> <span class="sub mono">' + mhz(ch.rxFreq) + ' MHz</span><div class="sub">' + esc(ch.location) + '</div><button class="btn small-btn" id="mapTune">Tune channel ' + ch.number + '</button> <button class="btn small-btn" id="mapCovBtn">Coverage</button>';
  }
  c.innerHTML = h; showCard();
  const b = $('mapTune'); if (b) b.onclick = () => act('channel/select', { number: m.c.number });
  const cb = $('mapCovBtn'); if (cb) cb.onclick = () => focusCoverage({ hz: m.c.rxFreq, lat: m.lat, lon: m.lon, name: m.name });
}
(function () {
  const el = $('map'); let drag = null;
  el.addEventListener('pointerdown', e => {
    if (e.target.closest('.zoom,.mapcard,.attrib')) return;
    const mk = e.target.closest('.mk'); if (mk) { showMark(M.marks[+mk.dataset.i]); return; }
    const cv = e.target.closest('.cov'); if (cv) { showCell(M.cells[+cv.dataset.c]); return; }
    drag = { x: e.clientX, y: e.clientY, cx: wx(M.lon, M.z), cy: wy(M.lat, M.z), moved: false }; el.setPointerCapture(e.pointerId); el.classList.add('drag');
  });
  el.addEventListener('pointermove', e => {
    if (!drag) return; const dx = e.clientX - drag.x, dy = e.clientY - drag.y; if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
    M.lon = lonOf(drag.cx - dx, M.z); M.lat = Math.max(-85, Math.min(85, latOf(drag.cy - dy, M.z))); drawMap();
  });
  /** The predicted square under a tap (they are drawn on a canvas, not as elements). */
  const predAt = e => {
    if (!(M.pred || []).length) return null;
    const r = el.getBoundingClientRect(), lon = lonOf(wx(M.lon, M.z) - el.clientWidth / 2 + e.clientX - r.left, M.z), lat = latOf(wy(M.lat, M.z) - el.clientHeight / 2 + e.clientY - r.top, M.z);
    return M.pred.find(c => lat >= c.s && lat <= c.n && lon >= c.w && lon <= c.e) || null;
  };
  const end = e => {
    if (drag && !drag.moved) { const p = e && e.type === 'pointerup' ? predAt(e) : null; if (p) showCell(p); else $('mapCard').classList.add('hidden'); }
    drag = null; el.classList.remove('drag');
  };
  el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
  const zoomAt = (dz, px, py) => {
    const z = Math.max(2, Math.min(18, M.z + dz)); if (z === M.z) return;
    const W = el.clientWidth, H = el.clientHeight; if (px == null) { px = W / 2; py = H / 2; }
    const alon = lonOf(wx(M.lon, M.z) - W / 2 + px, M.z), alat = latOf(wy(M.lat, M.z) - H / 2 + py, M.z);
    M.z = z; M.lon = lonOf(wx(alon, z) - px + W / 2, z); M.lat = latOf(wy(alat, z) - py + H / 2, z); drawMap();
  };
  el.addEventListener('wheel', e => { e.preventDefault(); const r = el.getBoundingClientRect(); zoomAt(e.deltaY < 0 ? 1 : -1, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
  $('zIn').onclick = () => zoomAt(1); $('zOut').onclick = () => zoomAt(-1);
  $('mapCov').onchange = () => loadCoverage(!M.fittedCov && (M.fittedCov = true));
  $('mapCovRpt').onchange = () => { const k = $('mapCovRpt').value; if (!k) { M.covFocus = null; if ($('mapCov').value) loadCoverage(true); return; } focusCoverage({ key: k }); };
  $('mapPred').onchange = () => loadCoverage(false);
  $('mapFit').onclick = () => { if ($('mapCov').value && ((M.cells || []).length || (M.pred || []).length)) fitCoverage(); else fitMap(); }; $('mapAprs').onchange = drawMap; $('mapCh').onchange = drawMap; $('mapRpt').onchange = () => loadRepeaterLayer(false);
  $('mapRptHere').onclick = () => { $('mapRpt').checked = true; hideCard(); loadRepeaterLayer(false, { lat: M.lat, lon: M.lon }); };
  window.addEventListener('resize', () => { if (S.page === 'map') drawMap(); });
})();

// ---- the phone itself (Intercom): battery, cell, Wi-Fi ----
function bars(id, n, of) { const k = of === 5 ? Math.round(n * 4 / 4) : n; [...$(id).children].forEach((b, i) => b.classList.toggle('on', i < Math.min(4, k))); }
function hm(min) { if (min == null || min < 0) return ''; const h = Math.floor(min / 60), m = min % 60; return h ? h + ' h ' + String(m).padStart(2, '0') + ' min' : m + ' min'; }
function renderPhone(d) {
  if (!d) return; S.phone = d; S.phoneAt = Date.now();
  const b = d.battery || {};
  if (b.ok) {
    const chg = b.state === 'charging' || b.state === 'full';
    $('pBatV').textContent = b.level + ' %';
    const bar = $('pBatBar'); bar.style.width = Math.max(0, Math.min(100, b.level)) + '%';
    bar.className = chg ? 'chg' : b.level <= 15 ? 'low' : b.level <= 35 ? 'mid' : '';
    const s = [];
    s.push('<b>' + esc(b.state) + '</b>' + (b.plug ? ' · ' + esc(b.plug) : ''));
    const parts = [];
    if (b.currentMa != null) parts.push((b.currentMa > 0 ? '+' : '') + b.currentMa + ' mA');
    if (b.ratePerHour != null) parts.push((b.ratePerHour > 0 ? '+' : '') + b.ratePerHour.toFixed(1) + ' %/h');
    else parts.push('rate after a few minutes');
    s.push(parts.join(' · '));
    if (b.minutesToFull != null) s.push('full in ' + hm(b.minutesToFull));
    if (b.minutesToEmpty != null) s.push('empty in about ' + hm(b.minutesToEmpty));
    const t = []; if (b.tempC != null) t.push(b.tempC.toFixed(1) + ' °C'); if (b.volts != null) t.push(b.volts.toFixed(2) + ' V'); if (b.chargeMah != null) t.push(b.chargeMah + ' mAh left'); if (b.health && b.health !== 'good') t.push(esc(b.health));
    if (t.length) s.push(t.join(' · '));
    $('pBatS').innerHTML = s.join('<br>');
  }
  const c = d.cell || {};
  if (c.airplane) { $('pCellV').textContent = 'off'; bars('pCellBars', 0); $('pCellS').innerHTML = '<b>airplane mode</b>'; }
  else if (!c.ok || c.dbm == null) { $('pCellV').textContent = '--'; bars('pCellBars', 0); $('pCellS').innerHTML = esc(c.sim === 'ready' ? 'no service' : (c.sim || 'no signal')); }
  else {
    $('pCellV').textContent = c.dbm + ' dBm'; bars('pCellBars', c.bars);
    const s = ['<b>' + esc([c.operator, c.tech].filter(Boolean).join(' · ') || 'cell') + '</b>'];
    const q = []; if (c.rsrq != null) q.push('RSRQ ' + c.rsrq + ' dB'); if (c.snr != null) q.push('SNR ' + c.snr + ' dB'); if (q.length) s.push(q.join(' · '));
    $('pCellS').innerHTML = s.join('<br>');
  }
  const w = d.wifi || {};
  if (!w.enabled) { $('pWifiV').textContent = 'off'; bars('pWifiBars', 0); $('pWifiS').innerHTML = '<b>Wi-Fi is off</b>'; }
  else if (!w.connected) { $('pWifiV').textContent = '--'; bars('pWifiBars', 0); $('pWifiS').innerHTML = 'not connected'; }
  else {
    $('pWifiV').textContent = w.dbm + ' dBm'; bars('pWifiBars', w.bars);
    const s = ['<b>' + esc(w.ssid || 'connected') + '</b>']; const q = [];
    if (w.band) q.push(w.band); if (w.mbps) q.push(w.mbps + ' Mbit/s'); if (q.length) s.push(q.join(' · '));
    $('pWifiS').innerHTML = s.join('<br>');
  }
  $('phoneAge').textContent = 'LIVE';
}
setInterval(() => { if (S.phoneAt && Date.now() - S.phoneAt > 15000) $('phoneAge').textContent = 'NOT UPDATING'; }, 5000);

// ---- message forms (Messages page) ----
async function loadForms() {
  try {
    const j = await api('forms'); S.forms = j;
    $('fmContact').innerHTML = j.contacts.map(c => '<option value="' + c.id + '">' + esc(c.name || String(c.number)) + ' (' + (c.type === 1 ? 'talkgroup ' : 'ID ') + c.number + ')</option>').join('');
    $('fmList').innerHTML = j.forms.map((f, i) => '<div class="item click" data-i="' + i + '"><div class="main"><b>' + esc((f.kind === 'RADIOGRAM' ? 'Radiogram: ' : 'ICS-213: ') + (f.fields.SU || (f.fields.NR ? 'number ' + f.fields.NR : f.title))) + '</b><div class="sub">' + (f.outgoing ? 'To ' : 'From ') + esc(f.peer || '—') + ' · ' + esc(f.via) + ' · ' + new Date(f.ts).toLocaleString() + (f.status ? ' · ' + esc(f.status) : '') + '</div></div></div>').join('') || '<div class="empty">No forms yet</div>';
  } catch (e) { }
}
function fmOpen(kind, pre) {
  S.fmKind = kind; const fl = S.forms.kinds[kind]; pre = pre || {};
  const now = new Date(), d = now.toISOString().slice(0, 10), hm = String(now.getHours()).padStart(2, '0') + String(now.getMinutes()).padStart(2, '0');
  const def = Object.assign({ DT: d }, kind === 'RADIOGRAM' ? { TF: hm, PR: 'R' } : { TM: hm }, pre);
  $('fmFields').innerHTML = fl.filter(k => !(kind === 'RADIOGRAM' && k[0] === 'CK')).map(k => '<label' + (k[0] === 'MS' ? ' class="wide"' : '') + '>' + esc(k[1]) + (k[0] === 'MS' ? '<textarea data-k="MS">' + esc(def.MS || '') + '</textarea>' : '<input data-k="' + k[0] + '" value="' + esc(def[k[0]] || '') + '">') + '</label>').join('');
  $('fmEdit').classList.remove('hidden'); $('fmView').classList.add('hidden'); $('fmVia').value = 'save'; fmVia(); const first = $('fmFields').querySelector('input,textarea'); if (first) first.focus();
}
function fmVia() { const v = $('fmVia').value; $('fmTo').classList.toggle('hidden', v !== 'aprs' && v !== 'winlink'); $('fmTo').placeholder = v === 'winlink' ? 'Callsign or e-mail address' : 'Callsign, e.g. N0CALL-7'; $('fmContact').classList.toggle('hidden', v !== 'dmr'); }
$('fmVia').onchange = fmVia;
$('fmNew213').onclick = async () => { if (!S.forms) await loadForms(); fmOpen('ICS213'); };
$('fmNewRg').onclick = async () => { if (!S.forms) await loadForms(); fmOpen('RADIOGRAM'); };
$('fmCancel').onclick = () => $('fmEdit').classList.add('hidden');
$('fmEdit').onsubmit = async e => {
  e.preventDefault();
  const fields = {}; $('fmFields').querySelectorAll('[data-k]').forEach(x => fields[x.dataset.k] = x.value);
  const body = { kind: S.fmKind, fields, via: $('fmVia').value, to: $('fmTo').value, contact: +$('fmContact').value };
  try { const r = await api('forms', body); toast(r.message); $('fmEdit').classList.add('hidden'); loadForms(); } catch (x) { toast(x.message); }
};
$('fmList').onclick = e => { const it = e.target.closest('[data-i]'); if (!it) return; const f = S.forms.forms[+it.dataset.i]; S.fmSel = f; $('fmText').textContent = f.text; $('fmView').classList.remove('hidden'); $('fmEdit').classList.add('hidden'); };
$('fmClose').onclick = () => $('fmView').classList.add('hidden');
$('fmDel').onclick = async () => { if (!S.fmSel || !await ask('Delete this form?')) return; try { await api('forms', { action: 'delete', id: S.fmSel.id }); $('fmView').classList.add('hidden'); loadForms(); } catch (e) { toast(e.message); } };
$('fmReply').onclick = () => { const f = S.fmSel; if (!f) return; fmOpen(f.kind, f.kind === 'ICS213' ? { TO: f.fields.FR || '', FR: f.fields.TO || '', SU: 'Re: ' + (f.fields.SU || '') } : { TO: f.fields.SG || '' }); };

// ---- packet finder (Packet page) ----
async function loadFinder() {
  let j; try { j = await api('finder'); } catch (e) { return; }
  S.pf = j;
  const until = j.endsAt ? new Date(j.endsAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
  $('pfState').textContent = j.status + (j.running ? ' · until ' + until : ' · ' + j.freqs.split(',').length + ' frequencies, ' + j.dwell + ' min each, for ' + j.hours + ' h');
  $('pfGo').textContent = j.running ? 'Stop' : 'Start';
  const when = t => new Date(t).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
  $('pfList').innerHTML = j.stations.length ? j.stations.map(s => '<div class="item"><div class="main"><b>' + esc(s.call) + '</b> <span class="chip">' + esc(s.kind) + '</span><div class="muted small">'
    + (s.hz / 1e6).toFixed(3) + ' MHz' + (s.dbm > -200 ? ' · ' + s.dbm + ' dBm' : '') + ' · ' + s.count + '× · last ' + when(s.last) + (s.note ? '<br>' + esc(s.note) : '') + '</div></div></div>').join('')
    : '<div class="empty">Nothing heard yet</div>';
  clearTimeout(S.pfTimer); if (j.running && S.page === 'pkt') S.pfTimer = setTimeout(loadFinder, 5000);
}
$('pfGo').onclick = async () => { try { const r = await api('finder', { action: S.pf && S.pf.running ? 'stop' : 'start' }); toast(r.message); } catch (x) { toast(x.message); } setTimeout(loadFinder, 800); };
$('pfSet').onclick = () => { $('pfFreqs').value = (S.pf && S.pf.freqs) || ''; $('pfDwell').value = (S.pf && S.pf.dwell) || 3; $('pfHours').value = (S.pf && S.pf.hours) || 2; $('pfForm').classList.toggle('hidden'); };
$('pfCancel').onclick = () => $('pfForm').classList.add('hidden');
$('pfForm').onsubmit = async e => { e.preventDefault(); try { const r = await api('finder', { action: 'settings', freqs: $('pfFreqs').value, dwell: +$('pfDwell').value, hours: +$('pfHours').value }); toast(r.message); $('pfForm').classList.add('hidden'); loadFinder(); } catch (x) { toast(x.message); } };
$('pfClear').onclick = async () => { if (!await ask('Clear the stations found?')) return; try { await api('finder', { action: 'clear' }); loadFinder(); } catch (x) { toast(x.message); } };

// ---- Winlink e-mail (Messages page) ----
async function loadWinlink() {
  let j; try { j = await api('winlink'); } catch (e) { return; }
  S.wl = j;
  $('wlState').textContent = j.state + (j.busy && j.progress ? ' · ' + j.progress : '') + (j.passwordSet ? '' : ' · no Winlink password set yet (Settings)');
  $('wlNet').textContent = j.busy ? 'Stop' : 'Connect: internet'; $('wlRf').disabled = j.busy;
  if (!$('wlLogBox').classList.contains('hidden')) $('wlLog').textContent = j.log || 'No session yet.';
  if (!j.busy && j.lastWasDiag) $('wlState').textContent += ' · detailed log recorded: Session log → Download log, whether it worked or not. Thank you!';
  const when = t => t ? new Date(t).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
  const sec = (key, title) => {
    const l = j.folders[key] || [];
    return '<div class="label wl-head">' + title + (l.length ? ' (' + l.length + ')' : ': empty') + '</div>' + l.map((m, i) => '<div class="item click" data-f="' + key + '" data-i="' + i + '"><div class="main"><b>' + (m.unread ? '● ' : '') + esc(m.subject) + '</b><div class="muted small">'
      + esc(key === 'inbox' ? 'From ' + m.from : 'To ' + m.to.join(', ')) + ' · ' + when(m.time) + (m.files.length ? ' · 📎 ' + m.files.length : '') + '</div></div></div>').join('');
  };
  $('wlList').innerHTML = sec('inbox', 'INBOX') + sec('outbox', 'OUTBOX · GOES OUT AT THE NEXT CONNECTION') + sec('sent', 'SENT');
  $('wlGwList').innerHTML = j.gateways.length ? j.gateways.map((g, i) => '<div class="item click" data-g="' + i + '"><div class="main"><b>' + esc(g.call) + '</b><div class="muted small">' + (g.km !== null ? g.km + ' km ' + g.dir + ' · ' : '') + (g.hz / 1e6).toFixed(4) + ' MHz' + (g.qth ? ' · ' + esc(g.qth) : '') + '</div></div></div>').join('') + (j.gatewayCount > j.gateways.length ? '<div class="empty">' + (j.gatewayCount - j.gateways.length) + ' farther ones not shown</div>' : '') : '<div class="empty">No list imported yet</div>';
  clearTimeout(S.wlTimer); if (j.busy && S.page === 'messages') S.wlTimer = setTimeout(loadWinlink, 1500);
}
function wlOnly(id) { ['wlCompose', 'wlRfForm', 'wlSetForm', 'wlView', 'wlLogBox', 'wlGw'].forEach(x => $(x).classList.toggle('hidden', x !== id)); }
function wlCompose(to, subj, text) { $('wlTo').value = to || ''; $('wlCc').value = ''; $('wlSubj').value = subj || ''; $('wlText').value = text || ''; wlOnly('wlCompose'); $(to ? 'wlText' : 'wlTo').focus(); }
$('wlNew').onclick = () => wlCompose();
$('wlCancelC').onclick = () => wlOnly('');
$('wlCompose').onsubmit = async e => {
  e.preventDefault();
  try { const r = await api('winlink', { action: 'compose', to: $('wlTo').value, cc: $('wlCc').value, subject: $('wlSubj').value, text: $('wlText').value }); toast(r.message); wlOnly(''); loadWinlink(); } catch (x) { toast(x.message); }
};
$('wlNet').onclick = async () => {
  if (!(S.wl && S.wl.busy)) await wlAskDiag();
  try { const r = await api('winlink', { action: S.wl && S.wl.busy ? 'cancel' : 'connect', how: 'internet' }); toast(r.message); } catch (x) { toast(x.message); }
  setTimeout(loadWinlink, 400);
};
$('wlRf').onclick = async () => { await wlAskDiag(); $('wlGw').value = (S.wl && S.wl.gateway) || ''; $('wlVia').value = (S.wl && S.wl.via) || ''; wlOnly('wlRfForm'); };
$('wlRfCancel').onclick = () => wlOnly('');
$('wlRfForm').onsubmit = async e => {
  e.preventDefault();
  try { const r = await api('winlink', { action: 'connect', how: 'radio', gateway: $('wlGw').value, via: $('wlVia').value }); toast(r.message); wlOnly(''); } catch (x) { toast(x.message); }
  setTimeout(loadWinlink, 400);
};
$('wlSet').onclick = () => { $('wlDiag').checked = !!(S.wl && S.wl.diag); $('wlDiagC').checked = !!(S.wl && S.wl.diagContents); $('wlPw').value = ''; $('wlPw').placeholder = S.wl && S.wl.passwordSet ? 'set (leave empty to keep)' : ''; $('wlGrid').value = (S.wl && S.wl.grid) || ''; wlOnly('wlSetForm'); };
$('wlSetCancel').onclick = () => wlOnly('');
$('wlSetForm').onsubmit = async e => {
  e.preventDefault();
  try { const r = await api('winlink', { action: 'settings', password: $('wlPw').value, grid: $('wlGrid').value, diag: $('wlDiag').checked, diagContents: $('wlDiagC').checked }); $('wlPw').value = ''; toast(r.message); wlOnly(''); loadWinlink(); } catch (x) { toast(x.message); }
};
$('wlLogB').onclick = () => { if (!$('wlLogBox').classList.contains('hidden')) { wlOnly(''); return; } wlOnly('wlLogBox'); $('wlLog').textContent = (S.wl && S.wl.log) || 'No session yet.'; };
async function wlAskDiag() {
  if (!S.wl || S.wl.diagAsked || S.wl.diag) return;
  const on = await ask('Winlink in SignalDeck is new and has not yet been tested with the real Winlink system. Turn on the detailed diagnostic log (timing, protocol, every radio frame; never your password)? Whether it works or not, please send us the log afterwards (Session log → Download log).');
  try { await api('winlink', { action: 'diagasked', diag: !!on }); } catch (x) { }
}
$('wlList').onclick = async e => {
  const it = e.target.closest('[data-f]'); if (!it) return;
  const f = it.dataset.f, m = S.wl.folders[f][+it.dataset.i]; S.wlSel = { f, m };
  $('wlHead').textContent = 'From ' + m.from + ' · To ' + m.to.join(', ') + (m.cc.length ? ' · Cc ' + m.cc.join(', ') : '') + ' · ' + new Date(m.time).toLocaleString() + ' · ' + m.subject;
  $('wlBody').textContent = m.text;
  $('wlFiles').innerHTML = m.files.map((a, i) => { const u = 'api/winlink/file?folder=' + f + '&mid=' + encodeURIComponent(m.mid) + '&i=' + i; return '<a class="btn small-btn" target="_blank" rel="noopener" href="' + u + '">📎 ' + esc(a.name) + ' (' + (a.size < 1024 ? a.size + ' bytes' : Math.ceil(a.size / 1024) + ' KB') + ')</a><a class="btn small-btn" href="' + u + '&download=1">Download</a>'; }).join('');
  $('wlReply').classList.toggle('hidden', f !== 'inbox');
  wlOnly('wlView');
  if (m.unread) { try { await api('winlink', { action: 'read', mid: m.mid }); m.unread = false; loadWinlink(); } catch (x) { } }
};
$('wlClose').onclick = () => wlOnly('');
$('wlGwB').onclick = () => { if (!$('wlGw').classList.contains('hidden')) { wlOnly(''); return; } wlOnly('wlGw'); };
$('wlGwFile').onchange = async e => {
  const f = e.target.files[0]; if (!f) return;
  try { const r = await api('winlink', { action: 'gateways', csv: await f.text() }); toast(r.message); loadWinlink(); } catch (x) { toast(x.message); }
  e.target.value = '';
};
$('wlGwClear').onclick = async () => { if (!await ask('Remove the gateway list?')) return; try { await api('winlink', { action: 'cleargateways' }); loadWinlink(); } catch (x) { toast(x.message); } };
$('wlGwList').onclick = async e => {
  const it = e.target.closest('[data-g]'); if (!it) return; const g = S.wl.gateways[+it.dataset.g];
  if (!await ask('Tune ' + (g.hz / 1e6).toFixed(4) + ' MHz and connect to ' + g.call + '? The radio transmits.')) return;
  try { const r = await api('winlink', { action: 'tuneconnect', call: g.call, hz: g.hz }); toast(r.message); setTimeout(loadWinlink, 5000); } catch (x) { toast(x.message); }
};
$('wlReply').onclick = () => { const m = S.wlSel.m; wlCompose(m.from, /^Re:/i.test(m.subject) ? m.subject : 'Re: ' + m.subject, '\n\n--- ' + m.from + ' wrote:\n' + m.text); };
$('wlFwd').onclick = () => { const m = S.wlSel.m; wlCompose('', 'Fw: ' + m.subject, '\n\n--- Forwarded message from ' + m.from + ':\n' + m.text); $('wlTo').focus(); };
$('wlDel').onclick = async () => {
  const s = S.wlSel; if (!s || !await ask(s.f === 'outbox' ? 'Delete this message? It will not be sent.' : 'Delete this message?')) return;
  try { await api('winlink', { action: 'delete', folder: s.f, mid: s.m.mid }); wlOnly(''); loadWinlink(); } catch (x) { toast(x.message); }
};

// ---- POTA / SOTA (Logbook page) ----
async function loadPota() {
  $('potaSpots').innerHTML = '<div class="empty">Loading the spots…</div>';
  try {
    const j = await api('pota'); S.pota = j; const a = j.activation;
    $('potaAct').innerHTML = a ? '<div class="big">' + esc(a.program + ' ' + a.ref) + '</div><div>' + esc(a.name || '') + '</div><div class="muted">Today (UTC) ' + a.today + ' of ' + a.needed + ' contacts' + (a.today >= a.needed ? ': it counts' : '') + ' · ' + a.total + ' in all</div><div class="row" style="margin-top:6px"><a class="btn small-btn" href="/api/pota/adif">Download ADIF</a><button class="btn small-btn danger" id="potaEnd" type="button">End the activation</button></div>'
      : '<div class="muted">Not activating. Start one when you are in a park or on a summit: your contacts are logged under its reference.</div>';
    $('potaStart').classList.toggle('hidden', !!a); $('potaLog').classList.toggle('hidden', !a);
    if (a) $('potaEnd').onclick = async () => { if (!await ask('End the activation ' + a.ref + '? Its contacts stay in the logbook.', 'End')) return; try { await api('pota', { action: 'end' }); loadPota(); } catch (e) { toast(e.message); } };
    const w = j.spots.filter(s => s.workable).length;
    $('potaSpots').innerHTML = (w ? '' : '<div class="muted">Nobody is on FM on 2 m or 70 cm right now' + (j.spots.length ? ' (' + j.spots.length + ' on HF and other modes):' : '.') + '</div>')
      + j.spots.map((s, i) => '<div class="item' + (s.workable ? ' click' : ' dim') + '" data-i="' + i + '"><div class="main"><b>' + esc(s.call) + '</b> · ' + (s.freqHz / 1e6).toFixed(4) + ' MHz ' + esc(s.mode || '') + '<div class="sub">' + esc(s.program + ' ' + s.ref + ' ' + (s.name || '')) + (s.where ? ' (' + esc(s.where) + ')' : '') + (s.time ? ' · ' + new Date(s.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '') + '</div></div>' + (s.workable ? '<button class="btn small-btn" data-tune="' + i + '">Tune</button>' : '') + '</div>').join('')
      + (j.problems.length ? '<div class="muted small">' + esc(j.problems.join(' · ')) + '</div>' : '');
  } catch (e) { $('potaSpots').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; }
}
$('potaRefresh').onclick = loadPota;
$('potaSpots').onclick = async e => {
  const b = e.target.closest('[data-tune]'); if (b) { const s = S.pota.spots[+b.dataset.tune]; try { await api('scope/listen', { hz: s.freqHz }); toast('Tuned to ' + s.call); } catch (x) { toast(x.message); } return; }
  const it = e.target.closest('.item[data-i]'); if (!it) return; const s = S.pota.spots[+it.dataset.i];
  if (S.pota.activation) { $('plCall').value = s.call; $('plTheir').value = s.ref; $('plCall').focus(); }
};
$('potaStart').onsubmit = async e => { e.preventDefault(); try { const r = await api('pota', { action: 'start', ref: $('potaRef').value }); toast(r.message); loadPota(); } catch (x) { toast(x.message); } };
$('potaParks').onclick = async () => {
  $('potaParkList').innerHTML = '<div class="empty">Looking…</div>';
  try { const j = await api('pota', { action: 'parks' }); $('potaParkList').innerHTML = j.parks.map(p => '<div class="item click" data-ref="' + esc(p.ref) + '"><div class="main"><b>' + esc(p.ref) + '</b> · ' + esc(p.name) + '<div class="sub">' + p.km + ' km</div></div></div>').join('') || '<div class="empty">No park within 60 km</div>'; }
  catch (e) { $('potaParkList').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; }
};
$('potaParkList').onclick = e => { const it = e.target.closest('[data-ref]'); if (it) { $('potaRef').value = it.dataset.ref; $('potaParkList').innerHTML = ''; } };
$('potaLog').onsubmit = async e => {
  e.preventDefault();
  const body = { action: 'log', call: $('plCall').value, sent: $('plSent').value, rcvd: $('plRcvd').value, theirRef: $('plTheir').value };
  try { const r = await api('pota', body); toast(r.message); }
  catch (x) { if (/^DUPE/.test(x.message) && await ask(x.message + '. Log it again?', 'Log again')) { try { await api('pota', Object.assign(body, { force: true })); toast('Logged'); } catch (y) { toast(y.message); } } else if (!/^DUPE/.test(x.message)) toast(x.message); }
  $('plCall').value = ''; $('plTheir').value = ''; loadPota(); if (typeof loadLog === 'function') loadLog();
};

// ---- band scope ----
const scSteps = (f, t, s) => s > 0 && t >= f ? Math.floor((t - f) / s) + 1 : 0;
const scDur = ms => { const s = Math.round(ms / 1000); return s < 90 ? s + ' s' : s < 5400 ? Math.round(s / 60) + ' min' : (s / 3600).toFixed(1) + ' h'; };
$('scPreset').onchange = () => { $('scCustom').classList.toggle('hidden', $('scPreset').value !== 'c'); try { localStorage.setItem('scPreset', $('scPreset').value); } catch (e) { } };
async function loadScope() {
  try {
    const j = await api('scope'); S.scope = j;
    if (!$('scPreset').options.length) {
      $('scPreset').innerHTML = j.presets.map((p, i) => '<option value="' + i + '">' + esc(p.name) + ' (' + (p.from / 1e6).toFixed(3) + '–' + (p.to / 1e6).toFixed(3) + ', ' + p.step / 1000 + ' kHz, about ' + scDur(scSteps(p.from, p.to, p.step) * (j.settleMs + 300)) + ')</option>').join('') + '<option value="c">Custom range…</option>';
      try { $('scPreset').value = localStorage.getItem('scPreset') || 0; const c = JSON.parse(localStorage.getItem('scCustom') || 'null'); if (c) { $('scFrom').value = c.from; $('scTo').value = c.to; $('scStep').value = c.step; } } catch (e) { }
      if (!$('scFrom').value) { $('scFrom').value = '446.0000'; $('scTo').value = '446.2000'; $('scStep').value = '12.5'; }
      $('scCustom').classList.toggle('hidden', $('scPreset').value !== 'c');
    }
    $('scStart').textContent = j.running ? 'Stop' : 'Start';
    const n = j.step > 0 ? Math.floor((j.to - j.from) / j.step) + 1 : 0;
    $('scSub').textContent = j.running ? (j.from / 1e6).toFixed(3) + '–' + (j.to / 1e6).toFixed(3) + ' MHz · ' + j.step / 1000 + ' kHz steps · ' + n + ' steps, a sweep takes ' + (j.sweepMs > 0 ? '' : 'about ') + scDur(j.estimateMs) + (j.currentHz ? ' · now at ' + (j.currentHz / 1e6).toFixed(4) + ' MHz' + (j.partialIndex >= 0 ? ' (step ' + (j.partialIndex + 1) + ')' : '') : '') + ' · receive only' : 'Receive only: the radio sweeps the band step by step (about 0.7 s a step: its signal reading needs that long after each retune) and comes back to your channel when it stops. Click the graph to pick a frequency.';
    $('scPeaks').innerHTML = (j.peaks || []).sort((a, b) => b.dbm - a.dbm).slice(0, 8).map(p => '<div class="item click" data-hz="' + p.hz + '"><div class="main"><b class="mono">' + (p.hz / 1e6).toFixed(4) + ' MHz</b> · ' + p.dbm + ' dBm' + (p.name ? ' · ' + esc(p.name) : '') + '</div></div>').join('') || (j.last ? '<div class="empty">Nothing stands out above the noise (8 dB)</div>' : '');
    drawScope();
    clearTimeout(S.scT); if (j.running && S.page === 'scope') S.scT = setTimeout(loadScope, 1500);   // the sweep in progress, step by step
  } catch (e) { }
}
function drawScope() {
  const j = S.scope, cv = $('scCv'); if (!j || !cv) return;
  const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight; cv.width = W * dpr; cv.height = H * dpr;
  const g = cv.getContext('2d'); g.scale(dpr, dpr); g.clearRect(0, 0, W, H);
  const gh = H * 0.55, wt = gh + 6, wh = H - wt, TOP = -40, BOT = -125, y = v => gh * (1 - (Math.max(BOT, Math.min(TOP, v)) - BOT) / (TOP - BOT));
  g.strokeStyle = 'rgba(255,255,255,.12)'; g.fillStyle = '#6F849A'; g.font = '11px ShareTech, monospace';
  for (let db = -120; db <= -40; db += 20) { g.beginPath(); g.moveTo(0, y(db)); g.lineTo(W, y(db)); g.stroke(); g.fillText(db, 3, y(db) - 3); }
  const n = j.step > 0 ? Math.floor((j.to - j.from) / j.step) + 1 : 0; if (n < 2) return;
  for (let k = 0; k <= 4; k++) { const x = W * k / 4, l = ((j.from + (j.to - j.from) * k / 4) / 1e6).toFixed(3); g.beginPath(); g.moveTo(x, 0); g.lineTo(x, gh); g.stroke(); const tw = g.measureText(l).width; g.fillText(l, Math.max(0, Math.min(W - tw, x - tw / 2)), gh - 3); }
  const line = (v, col, w, a) => { if (!v) return; g.strokeStyle = col; g.globalAlpha = a; g.lineWidth = w; g.beginPath(); let pen = false; v.forEach((d, i) => { if (d == null) { pen = false; return; } const x = i / (v.length - 1) * W; if (!pen) { g.moveTo(x, y(d)); pen = true; } else g.lineTo(x, y(d)); }); g.stroke(); g.globalAlpha = 1; };
  line(j.peak, '#FFB020', 1, .7); line(j.last, '#19D3FF', 2, j.partial ? .35 : 1); if (j.partial) { line(j.partial, '#19D3FF', 2, 1); const px = j.partialIndex / (n - 1) * W; g.strokeStyle = 'rgba(43,234,122,.5)'; g.beginPath(); g.moveTo(px, 0); g.lineTo(px, gh); g.stroke(); }
  const stops = [[10, 20, 32], [14, 78, 106], [25, 211, 255], [43, 234, 122], [255, 176, 32], [255, 84, 104]];
  const heat = d => { if (d == null) return 'rgb(10,20,32)'; const t = Math.max(0, Math.min(1, (d + 118) / 50)) * (stops.length - 1), k = Math.min(stops.length - 2, Math.floor(t)), f = t - k, a = stops[k], b = stops[k + 1]; return 'rgb(' + a.map((c, i) => Math.round(c * (1 - f) + b[i] * f)).join(',') + ')'; };
  const h = j.history || [], rh = Math.max(1, wh / 60), cw = W / n;
  for (let r = 0; r < h.length; r++) { const row = h[h.length - 1 - r], top = wt + r * rh; if (top > H) break; row.forEach((d, i) => { g.fillStyle = heat(d); g.fillRect(i * cw, top, cw + 1, rh + 1); }); }
  if (S.scSel) { const x = (S.scSel - j.from) / (j.to - j.from) * W; g.strokeStyle = '#2BEA7A'; g.lineWidth = 1.5; g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.stroke(); }
}
$('scCv').onclick = e => {
  const j = S.scope; if (!j || !j.step) return; const r = $('scCv').getBoundingClientRect(), n = Math.floor((j.to - j.from) / j.step) + 1;
  const i = Math.round((e.clientX - r.left) / r.width * (n - 1)); S.scSel = j.from + i * j.step; const v = j.last ? j.last[i] : null;
  $('scSel').textContent = (S.scSel / 1e6).toFixed(4) + ' MHz' + (v != null ? '  ' + Math.round(v) + ' dBm' : ''); $('scListen').classList.remove('hidden'); drawScope();
};
$('scPeaks').onclick = e => { const it = e.target.closest('[data-hz]'); if (!it) return; S.scSel = +it.dataset.hz; $('scSel').textContent = (S.scSel / 1e6).toFixed(4) + ' MHz'; $('scListen').classList.remove('hidden'); drawScope(); };
$('scStart').onclick = async () => {
  const j = S.scope || {};
  try {
    if (j.running) { await api('scope/stop', {}); toast('Stopping: the radio goes back to your channel'); }
    else if ($('scPreset').value === 'c') {
      const from = Math.round(parseFloat($('scFrom').value) * 1e6), to = Math.round(parseFloat($('scTo').value) * 1e6), step = Math.round(parseFloat($('scStep').value) * 1e3);
      try { localStorage.setItem('scCustom', JSON.stringify({ from: $('scFrom').value, to: $('scTo').value, step: $('scStep').value })); } catch (e) { }
      await api('scope/start', { from, to, step }); toast('Sweeping ' + (from / 1e6).toFixed(4) + '–' + (to / 1e6).toFixed(4) + ' MHz, ' + scSteps(from, to, step) + ' steps (receive only)');
    }
    else { const p = j.presets[+$('scPreset').value]; try { localStorage.setItem('scPreset', $('scPreset').value); } catch (e) { } await api('scope/start', { from: p.from, to: p.to, step: p.step }); toast('Sweeping ' + p.name + ' (receive only)'); }
  } catch (e) { toast(e.message); }
  setTimeout(loadScope, 600);
};
$('scClear').onclick = () => { if (S.scope) { S.scope.peak = S.scope.last; drawScope(); } };
$('scListen').onclick = async () => { if (!S.scSel) return; try { const r = await api('scope/listen', { hz: S.scSel }); toast(r.message || 'Listening'); setTimeout(loadScope, 1500); } catch (e) { toast(e.message); } };
window.addEventListener('resize', () => { if (S.page === 'scope') drawScope(); });

// ---- scheduled operations (Settings) ----
const SF_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
$('sfDays').innerHTML = SF_DAYS.map((d, i) => '<button type="button" data-b="' + i + '" aria-pressed="false">' + d + '</button>').join('');
$('sfDays').onclick = e => { const b = e.target.closest('button'); if (!b) return; b.classList.toggle('on'); b.setAttribute('aria-pressed', b.classList.contains('on')); sfShow(); };
const sfMask = () => [...$('sfDays').children].reduce((m, b, i) => m | (b.classList.contains('on') ? 1 << i : 0), 0);
function sfShow() {
  const a = $('sfAction').value;
  $('sfChWrap').classList.toggle('hidden', !(a === 'listen' || a === 'sstv' || a === 'winlink')); $('sfRecWrap').classList.toggle('hidden', a !== 'listen');
  $('sfMinWrap').classList.toggle('hidden', a === 'beacon' || a === 'winlink' || a === 'winlinknet');   // the packet finder keeps its minutes $('sfDateWrap').classList.toggle('hidden', sfMask() !== 0);
  const first = $('sfChannel').options[0]; if (first) first.text = a === 'sstv' ? '144.500 MHz (SSTV calling frequency)' : 'The channel the radio is on';
}
$('sfAction').onchange = sfShow;
async function loadSchedules() {
  try {
    const j = await api('schedules'); S.scheds = j.schedules;
    $('schedList').innerHTML = j.schedules.map((s, i) => '<div class="item click" data-i="' + i + '"><div class="main"><b>' + esc(s.label || s.actionName) + '</b>' + (s.id === j.running ? ' <span class="chip green">RUNNING</span>' : !s.enabled ? ' <span class="chip dim">OFF</span>' : '')
      + '<div class="sub">' + esc(s.when) + (s.enabled && s.next > 0 ? ' · next ' + new Date(s.next).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '') + '</div></div></div>').join('') || '<div class="empty">Nothing scheduled yet</div>';
  } catch (e) { }
}
async function sfOpen(s) {
  if (!S.chList) { try { S.chList = (await api('zones')).channels; } catch (e) { S.chList = []; } }
  $('sfChannel').innerHTML = '<option value="-1">The channel the radio is on</option>' + S.chList.map(ch => '<option value="' + ch.number + '">' + ch.number + '  ' + esc(ch.name) + '</option>').join('');
  S.sfId = s ? s.id : 0;
  $('sfAction').value = s ? s.action : 'listen'; $('sfChannel').value = s ? s.channel : -1; $('sfRecord').checked = !!(s && s.record);
  [...$('sfDays').children].forEach((b, i) => { const on = !!(s && (s.days & (1 << i))); b.classList.toggle('on', on); b.setAttribute('aria-pressed', on); });
  $('sfDate').value = s && s.date ? s.date : new Date().toISOString().slice(0, 10);
  $('sfTime').value = s ? String(s.hour).padStart(2, '0') + ':' + String(s.minute).padStart(2, '0') : '19:00';
  $('sfMinutes').value = s ? s.minutes : 60; $('sfLabel').value = s ? s.label : '';
  $('sfDelete').classList.toggle('hidden', !s); $('schedForm').classList.remove('hidden'); sfShow(); $('sfAction').focus();
}
$('schedAdd').onclick = () => sfOpen(null);
$('schedList').onclick = e => { const it = e.target.closest('.item[data-i]'); if (it) sfOpen(S.scheds[+it.dataset.i]); };
$('sfCancel').onclick = () => $('schedForm').classList.add('hidden');
$('sfDelete').onclick = async () => { if (!await ask('Delete this schedule?')) return; try { await api('schedules/delete', { id: S.sfId }); $('schedForm').classList.add('hidden'); loadSchedules(); } catch (e) { toast(e.message); } };
$('schedForm').onsubmit = async e => {
  e.preventDefault();
  const [h, m] = $('sfTime').value.split(':').map(Number);
  const body = { id: S.sfId, action: $('sfAction').value, channel: +$('sfChannel').value, record: $('sfRecord').checked, days: sfMask(), date: $('sfDate').value, hour: h, minute: m, minutes: +$('sfMinutes').value || 60, label: $('sfLabel').value, enabled: true };
  try { await api('schedules', body); toast('Saved'); $('schedForm').classList.add('hidden'); loadSchedules(); } catch (x) { toast(x.message); }
};

// ---- mission statement (Settings) ----
$('missionBtn').onclick = async () => {
  try { const j = await api('mission'); $('missionText').textContent = '\u201C' + j.text + '\u201D'; $('missionBy').textContent = '\u2014 ' + j.by; $('missionLegal').textContent = j.legal || ''; $('missionDlg').classList.remove('hidden'); $('missionX').focus(); } catch (e) { toast(e.message); }
};
const closeMission = () => $('missionDlg').classList.add('hidden');
$('missionX').onclick = closeMission;
$('missionDlg').onclick = e => { if (e.target === $('missionDlg')) closeMission(); };
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('missionDlg').classList.contains('hidden')) closeMission(); });

// ---- the intercom bars: the received audio's spectrum, drawn like the phone's panel (rise fast, fall slowly) ----
S.spec = new Array(18).fill(0); S.specTarget = new Array(18).fill(0);
function drawSpectrum() {
  S.specRaf = 0;
  const cv = $('specCv'); if (!cv || cv.offsetParent === null) return;
  const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
  if (cv.width !== Math.round(W * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
  const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H);
  const n = S.spec.length, bw = W / n, gap = bw * 0.18; let moving = false;
  for (let i = 0; i < n; i++) {
    const t = (S.specTarget[i] || 0) / 100, v = S.spec[i];
    S.spec[i] = v + (t - v) * (t > v ? 0.55 : 0.12); if (Math.abs(S.spec[i] - t) > 0.004) moving = true;
    const h = Math.max(3, S.spec[i] * (H - 2));
    g.fillStyle = 'rgba(0,229,255,' + (0.22 + S.spec[i] * 0.75).toFixed(3) + ')';
    const x = i * bw + gap, w = bw - gap * 2, y = H - h, r = Math.min(3, w / 2);
    g.beginPath(); g.moveTo(x, H); g.lineTo(x, y + r); g.quadraticCurveTo(x, y, x + r, y); g.lineTo(x + w - r, y); g.quadraticCurveTo(x + w, y, x + w, y + r); g.lineTo(x + w, H); g.fill();
  }
  if (moving) S.specRaf = requestAnimationFrame(drawSpectrum);
}
window.addEventListener('resize', () => { if (!S.specRaf) S.specRaf = requestAnimationFrame(drawSpectrum); });

// ---- captions: the last few on the intercom page, all of them on the Transcripts page ----
S.caps = []; S.trRows = [];
const hhmmssDay = ts => { const d = new Date(ts); return (Date.now() - ts > 20 * 3600e3 ? d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' ' : '') + hhmmss(ts); };
async function loadCaptions() {
  try { const j = await api('captions?limit=8'); S.caps = j.captions; setTxt(j.on); renderCapList(); } catch (e) { }
}
function renderCapList() {
  const el = $('capList');
  el.innerHTML = S.caps.slice(0, 8).map(c => '<div><span class="t">' + hhmmssDay(c.ts) + '</span>' + esc(c.text) + '</div>').join('');
  el.classList.toggle('hidden', !S.caps.length);
}
function addCaptionLine(c) {
  S.caps.unshift(c); S.caps = S.caps.slice(0, 8); renderCapList();
  if (S.page === 'trans' && !$('trSearch').value.trim()) { S.trRows.unshift(c); renderTrans(); }
}
// ---- caption engine and models (Transcripts page) ----
let cmTimer = null;
const mb = n => n >= 1e9 ? (n / 1e9).toFixed(1) + ' GB' : Math.round(n / 1e6) + ' MB';
async function loadModels() {
  clearTimeout(cmTimer);
  try {
    const j = await api('captions/models');
    $('cmEngine').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.e === j.engine));
    $('cmGoogle').classList.toggle('hidden', j.engine !== 'google' && j.googleKeySet === false && !S.cmShowKey);
    $('cmKeyState').textContent = j.googleKeySet ? 'A key is set (hidden).' : 'No key set.';
    const busy = j.busy || /^(downloading|unpacking|loading)/.test(j.status);
    $('cmStatus').textContent = j.engine === 'google' ? 'GOOGLE' : (j.status || 'idle').toUpperCase();
    $('cmModels').innerHTML = j.models.map(m => '<div class="item"><div class="main"><div>' + esc(m.label) + (m.selected ? ' <span class="chip green">IN USE</span>' : '') + '</div><div class="sub">'
      + (m.present ? 'on the phone · ' + mb(m.bytes) + (m.loaded ? ' · loaded' : '') : (m.selected && busy ? esc(j.status) : 'not downloaded')) + '</div></div>'
      + (!m.selected ? '<button type="button" class="act" data-use="' + m.name + '"' + (busy ? ' disabled' : '') + '>' + (m.present ? 'Use' : 'Download and use') + '</button>' : '')
      + (m.present && !m.selected && !m.loaded ? ' <button type="button" class="act" data-del="' + m.name + '"' + (busy ? ' disabled' : '') + '>Delete</button>' : '') + '</div>').join('');
    $('cmFree').textContent = 'Free space on the phone: ' + mb(j.freeBytes) + '.';
    if (busy && S.page === 'trans') cmTimer = setTimeout(loadModels, 1500);
  } catch (e) { $('cmModels').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; }
}
$('cmModels').onclick = async e => {
  const u = e.target.closest('[data-use]'), d = e.target.closest('[data-del]');
  if (u) { if (await act('captions/model', { name: u.dataset.use })) loadModels(); }
  if (d && confirm('Delete this model from the phone? It can be downloaded again later.')) { if (await act('captions/model/delete', { name: d.dataset.del })) loadModels(); }
};
$('cmEngine').onclick = async e => {
  const b = e.target.closest('button'); if (!b) return;
  if (b.dataset.e === 'google') { S.cmShowKey = true; $('cmGoogle').classList.remove('hidden'); }
  if (await act('captions/engine', { engine: b.dataset.e })) loadModels();
};
$('cmKeySave').onclick = async () => { const k = $('cmKey').value.trim(); if (!k) return; if (await act('captions/engine', { engine: 'google', googleKey: k })) { $('cmKey').value = ''; loadModels(); } };
function setTxt(on) { S.capOn = !!on; for (const id of ['txtBtn', 'trTxt']) { const b = $(id); if (b) b.classList.toggle('on', !!on); } }
async function toggleTxt() { const want = !S.capOn; const j = await act('captions/set', { on: want }); if (j) setTxt(want); }   // the server's live update may arrive first: set what was asked, never flip twice
$('txtBtn').onclick = toggleTxt; $('trTxt').onclick = toggleTxt;
let trTimer = null;
async function loadTrans() {
  const q = $('trSearch').value.trim();
  try { const j = await api('captions?limit=1000' + (q ? '&q=' + encodeURIComponent(q) : '')); S.trRows = j.captions; setTxt(j.on); renderTrans(); } catch (e) { toast(e.message); }
}
function renderTrans() {
  const q = $('trSearch').value.trim();
  const mark = t => { const e = esc(t); if (!q) return e; const re = new RegExp('(' + esc(q).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi'); return e.replace(re, '<mark>$1</mark>'); };
  $('trCount').textContent = S.trRows.length + (q ? ' MATCHING' : '') + (S.trRows.length >= 1000 ? ' (NEWEST 1000)' : '');
  $('trList').innerHTML = S.trRows.map(c => '<div class="item"><div class="main"><div>' + mark(c.text) + '</div><div class="sub"><span class="t">' + hhmmssDay(c.ts) + '</span>' + esc(c.channelName) + '</div></div></div>').join('')
    || '<div class="empty">' + (q ? 'No caption contains “' + esc(q) + '”.' : 'No captions yet. Turn TXT on: every finished sentence is kept on the phone.') + '</div>';
}
$('trSearch').oninput = () => { clearTimeout(trTimer); trTimer = setTimeout(loadTrans, 300); };
$('trDownload').onclick = async () => {
  try {
    const q = $('trSearch').value.trim(); const j = await api('captions?limit=5000' + (q ? '&q=' + encodeURIComponent(q) : ''));
    const lines = j.captions.slice().reverse().map(c => new Date(c.ts).toISOString().replace('T', ' ').slice(0, 19) + 'Z\t' + c.channelName + '\t' + c.text);
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([lines.join('\n') + '\n'], { type: 'text/plain' }));
    a.download = 'signaldeck-transcripts' + (q ? '-' + q.replace(/[^\w-]+/g, '_') : '') + '.txt'; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  } catch (e) { toast(e.message); }
};

// ---- satellite passes ----
const COMPASS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
const compass = az => COMPASS[Math.round(((az % 360) + 360) % 360 / 22.5) % 16];
async function loadPasses() {
  try {
    const j = await api('sat/passes');
    const first = s => (s.passes[0] || {}).aos || Infinity;                   // the FM satellites from AMSAT's list: soonest pass first
    j.satellites = j.satellites.filter(s => !s.key.startsWith('fm-')).concat(j.satellites.filter(s => s.key.startsWith('fm-')).sort((a, b) => first(a) - first(b)));
    S.sats = j.satellites;
    $('passNote').textContent = !j.hasFix ? 'NO POSITION FIX ON THE PHONE' : j.elementsAgeH > 72 ? 'ORBITS ' + Math.round(j.elementsAgeH / 24) + ' DAYS OLD' : '';
    const now = Date.now(); const day = ts => new Date(ts).toLocaleDateString([], { weekday: 'short' }) + ' ' + hhmm(ts);
    $('passList').innerHTML = j.satellites.map((s, si) => '<div class="satbox"><h4>' + esc(s.name) + '</h4><div class="about">'
      + (s.listenOnly ? 'Listen ' + mhz(s.downHz) + ' MHz (receive only)' : 'Listen ' + mhz(s.downHz) + ', talk ' + mhz(s.upHz) + ' MHz' + (s.toneHz > 0 ? ' with ' + s.toneHz.toFixed(1) + ' Hz' : ''))
      + '. ' + esc(s.about) + '</div>' + (s.amsat ? '<div class="sat-st muted small" data-amsat="' + esc(s.amsat) + '">AMSAT status…</div>' : '')
      + (s.problem ? '<div class="empty">' + esc(s.problem) + '</div>' : s.passes.length ? s.passes.map(p => '<div class="pass' + (p.aos <= now ? ' up' : '') + '" data-s="' + si + '" data-aos="' + p.aos + '"><div><b>' + (p.aos <= now ? 'Up now' : day(p.aos)) + '</b><div class="sub">rises ' + compass(p.aosAz) + ', sets ' + compass(p.losAz) + ' · ' + Math.round((p.los - p.aos) / 60000) + ' min</div></div><span class="chip">' + Math.round(p.maxEl) + '°</span></div>').join('') : '<div class="empty">No pass above 10° in the next 24 hours</div>')
      + '</div>').join('');
    loadSatStatus();
  } catch (e) { }
}
const COND_COL = ['#2A3C50', 'var(--cyan)', 'var(--green)', 'var(--amber)'];
async function loadConditions() {
  try {
    const j = await api('conditions');
    const fmt = t => new Date(t).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
    $('condTropo').textContent = !j.tropo.length ? 'No forecast' : j.best ? j.best.name + ', ' + fmt(j.best.from) + '–' + new Date(j.best.to).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Normal: no enhancement expected';
    $('condTropo').style.color = j.best ? COND_COL[j.best.c] : '';
    $('condStrip').innerHTML = j.tropo.map(s => '<i style="background:' + COND_COL[s.c] + '" title="' + fmt(s.t) + ': ' + esc(j.classNames[s.c]) + ' (' + s.g + ' N/km)"></i>').join('');
    $('condKp').textContent = j.kpNow < 0 ? 'Kp unavailable' : 'Kp ' + j.kpNow.toFixed(1) + ' · ' + j.kpWords;
    $('condAur').textContent = (j.kpMax >= 0 ? 'Forecast up to Kp ' + j.kpMax.toFixed(1) + ' (' + fmt(j.kpMaxAt) + '). ' : '') + (j.aurora >= 0 ? 'Aurora chance overhead now: ' + j.aurora + ' %. ' : '') + 'VHF aurora needs Kp 5+ at mid latitudes: antenna north, a buzzing sound (better for CW/SSB than FM).';
    $('condMet').innerHTML = j.showers.length ? j.showers.map(s => { const d = Math.round((s.peak - Date.now()) / 864e5); return '<b>' + esc(s.name) + '</b> · ' + (d <= 0 ? 'active now' : 'peak ' + new Date(s.peak).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }) + ' (in ' + d + ' d)') + ', up to ' + s.zhr + '/h' + (s.note ? ', ' + esc(s.note) : ''); }).join('<br>') + '<br>Trails reflect 2 m for moments (pings), 800–2000 km; best before dawn.' : 'No major shower in the next six weeks.';
    $('condProb').textContent = j.problems.join(' · ');
    $('condAge').textContent = 'UPDATED ' + new Date(j.fetched).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch (e) { $('condTropo').textContent = 'Could not load'; }
}
async function loadSatStatus() {                                           // AMSAT reports, one satellite after another
  for (const el of [...$('passList').querySelectorAll('.sat-st')]) {
    try { const j = await api('sat/status?name=' + encodeURIComponent(el.dataset.amsat)); el.textContent = j.line; el.style.color = j.heard > 0 ? 'var(--green)' : j.telemetry > 0 ? 'var(--amber)' : ''; } catch (e) { el.textContent = 'AMSAT status: offline'; }
  }
}
$('satUpdate').onclick = async () => { toast('Updating orbits and the FM satellite list…'); try { const j = await api('sat/update', {}); toast(j.message || 'Updated'); loadPasses(); } catch (e) { toast(e.message); } };
$('passList').onclick = async e => {
  const p = e.target.closest('.pass'); if (!p) return; const s = S.sats[+p.dataset.s];
  const sat = S.status.sat || {};
  if (sat.tracking && !confirm('Track this ' + s.name + ' pass instead? It replaces the current tracking' + (sat.repeat >= 0 ? ' (which follows every ISS pass)' : '') + '.')) return;
  await act('sat/track', { key: s.key, aos: +p.dataset.aos }); refresh();
};

// ---- zones (under the channel list) ----
S.zone = null;
async function loadZonesEditor() {
  try {
    const j = await api('zones'); S.zonesEd = j;
    $('zList').innerHTML = j.zones.map(z => '<div class="item click' + (S.zone && S.zone.id === z.id ? ' cur' : '') + '" data-id="' + z.id + '"><div class="main"><b>' + esc(z.name) + '</b>' + (z.id === j.active ? ' <span class="chip green">IN USE</span>' : '') + '<div class="sub">' + z.numbers.length + ' channels</div></div></div>').join('') || '<div class="empty">No zones yet</div>';
    if (S.zone) renderZoneForm();
  } catch (e) { }
}
function editZone(z) { S.zone = z ? { id: z.id, name: z.name, numbers: z.numbers.slice() } : { id: 0, name: '', numbers: [] }; $('zForm').classList.remove('hidden'); $('zName').value = S.zone.name; $('zDel').classList.toggle('hidden', !S.zone.id); renderZoneForm(); loadZonesEditor(); }
function renderZoneForm() {
  const q = $('zFilter').value.trim().toLowerCase(); const sel = new Set(S.zone.numbers);
  $('zChans').innerHTML = S.zonesEd.channels.filter(c => !q || (c.name || '').toLowerCase().includes(q) || mhz(c.rxFreq).includes(q))
    .map(c => '<label><input type="checkbox" data-n="' + c.number + '"' + (sel.has(c.number) ? ' checked' : '') + '><span class="n">' + c.number + '</span><span>' + esc(c.name || '—') + '<div class="sub mono">' + mhz(c.rxFreq) + (c.digital ? ' DMR' : ' FM') + '</div></span></label>').join('');
}
$('zList').onclick = e => { const it = e.target.closest('.item'); if (it) editZone(S.zonesEd.zones.find(z => z.id === +it.dataset.id)); };
$('zNew').onclick = () => editZone(null);
$('zFilter').oninput = renderZoneForm;
$('zChans').onchange = e => { const n = +e.target.dataset.n; if (e.target.checked) S.zone.numbers.push(n); else S.zone.numbers = S.zone.numbers.filter(x => x !== n); };
$('zAll').onclick = () => { const vis = [...$('zChans').querySelectorAll('input')].map(i => +i.dataset.n); S.zone.numbers = [...new Set(S.zone.numbers.concat(vis))]; renderZoneForm(); };
$('zNone').onclick = () => { const vis = new Set([...$('zChans').querySelectorAll('input')].map(i => +i.dataset.n)); S.zone.numbers = S.zone.numbers.filter(n => !vis.has(n)); renderZoneForm(); };
$('zForm').addEventListener('submit', async e => {
  e.preventDefault();
  const j = await act('zones', { id: S.zone.id, name: $('zName').value, numbers: S.zone.numbers });
  if (j) { S.zone.id = j.id; $('zDel').classList.remove('hidden'); loadZonesEditor(); loadZones(); loadChannels(); }
});
$('zDel').onclick = async () => {
  if (!S.zone.id || !confirm('Delete zone ' + S.zone.name + '? Its channels are kept.')) return;
  if (await act('zones/delete', { id: S.zone.id })) { S.zone = null; $('zForm').classList.add('hidden'); loadZonesEditor(); loadZones(); loadChannels(); }
};

// ---- logbook ----
S.qso = null;
async function loadLog() {
  try {
    const j = await api('logbook?limit=500' + ($('logQso').checked ? '&qso=1' : '')); S.log = j.entries;
    $('logCount').textContent = j.qsos + ' CONTACTS · ' + j.total + ' ENTRIES';
    $('logList').innerHTML = j.entries.map(e => '<div class="item click' + (S.qso && S.qso.id === e.id ? ' cur' : '') + '" data-id="' + e.id + '"><span class="t">' + new Date(e.ts).toLocaleDateString() + '<br>' + hhmm(e.ts) + '</span><div class="main"><b class="pk-from">' + esc(e.call) + '</b> ' + (e.name ? esc(e.name) + ' ' : '') + '<span class="sub mono">' + (e.freqHz ? mhz(e.freqHz) + ' MHz ' : '') + esc(e.mode) + (e.band ? ' · ' + esc(e.band) : '') + '</span><div class="sub">' + (e.qso ? 'Contact' : 'Heard') + (e.rstSent || e.rstRcvd ? ' · sent ' + esc(e.rstSent || '—') + ' rcvd ' + esc(e.rstRcvd || '—') : '') + (e.grid ? ' · ' + esc(e.grid) : '') + (e.notes ? ' · ' + esc(e.notes) : '') + '</div></div></div>').join('') || '<div class="empty">Nothing logged yet</div>';
  } catch (e) { }
}
function editQso(e) {
  S.qso = e || null; $('qsoTitle').textContent = e ? 'EDIT ENTRY' : 'LOG A CONTACT';
  const ch = (S.status && S.status.channel) || {};
  $('qCall').value = e ? e.call : ''; $('qFreq').value = e ? (e.freqHz ? (e.freqHz / 1e6).toFixed(4) : '') : (ch.rxFreq ? (ch.rxFreq / 1e6).toFixed(4) : '');
  $('qMode').value = e ? e.mode : (ch.digital ? 'DMR' : 'FM'); $('qSent').value = e ? e.rstSent : ''; $('qRcvd').value = e ? e.rstRcvd : '';
  $('qName').value = e ? e.name : ''; $('qNotes').value = e ? e.notes : ''; $('qDel').classList.toggle('hidden', !e);
}
$('logList').onclick = e => { const it = e.target.closest('.item'); if (it) { editQso(S.log.find(x => x.id === +it.dataset.id)); loadLog(); } };
$('logQso').onchange = loadLog;
$('qClear').onclick = () => { editQso(null); loadLog(); };
$('qDel').onclick = async () => { if (S.qso && confirm('Delete this entry for ' + S.qso.call + '?') && await act('logbook/delete', { id: S.qso.id })) { editQso(null); loadLog(); } };
$('qsoForm').addEventListener('submit', async e => {
  e.preventDefault();
  const body = { id: S.qso ? S.qso.id : 0, call: $('qCall').value, mode: $('qMode').value, rstSent: $('qSent').value, rstRcvd: $('qRcvd').value, name: $('qName').value, notes: $('qNotes').value, qso: true };
  const f = parseFloat($('qFreq').value); if (!isNaN(f)) body.freqMHz = f;
  if (await act('logbook', body)) { editQso(null); loadLog(); }
});

// ---- net control (under the logbook) ----
async function loadNet() {
  try {
    const j = await api('nets'); S.net = j; const n = j.running;
    $('netState').textContent = n ? 'RUNNING · ' + n.checkIns.length + ' CHECK-INS' : 'NO NET RUNNING'; $('netState').className = 'chip ' + (n ? 'green' : 'dim');
    if (!n) {
      $('netActions').innerHTML = '';
      $('netBody').innerHTML = '<form id="netStart" class="row wrap" style="margin-top:12px"><input id="netName" placeholder="Net name" value="' + esc(j.lastName || '') + '" class="grow"><button class="btn primary">Start a net on this channel</button></form>'
        + '<div class="group">PAST NETS</div>' + (j.past.map(p => '<div class="item"><div class="main"><b>' + esc(p.name) + '</b><div class="sub">' + new Date(p.start).toLocaleString() + ' · ' + esc(p.channel) + ' · ' + p.count + ' check-ins</div></div><a class="act" target="_blank" href="/api/nets/report?id=' + p.id + '">Report</a><a class="act" href="/api/nets/report?download=1&id=' + p.id + '">Download</a><button class="act" data-netdel="' + p.id + '">Delete</button></div>').join('') || '<div class="empty">No past nets</div>');
      $('netStart').addEventListener('submit', async e => { e.preventDefault(); if (await act('nets/start', { name: $('netName').value })) loadNet(); });
      return;
    }
    $('netActions').innerHTML = '<a class="btn small-btn" target="_blank" href="/api/nets/report">Report</a><button class="btn small-btn danger" id="netEnd">End the net</button>';
    $('netEnd').onclick = async () => { if (confirm('End ' + n.name + '?')) { await act('nets/end', {}); loadNet(); } };
    const flag = (k, f, label, color) => '<button type="button" class="act" data-flag="' + f + '" data-id="' + k.id + '" style="' + (k[f] ? 'background:' + color + ';color:var(--ground);border-color:' + color : '') + '">' + label + '</button>';
    $('netBody').innerHTML = '<div class="sub" style="margin-top:6px">' + esc(n.name) + ' · ' + esc(n.channel) + (n.freqHz ? ' · ' + mhz(n.freqHz) + ' MHz' : '') + ' · started ' + hhmm(n.start) + '</div>'
      + '<form id="netCheckin" class="row wrap" style="margin-top:12px"><input id="ciCall" placeholder="Callsign" style="text-transform:uppercase" maxlength="12"><input id="ciName" placeholder="Name (looked up if empty)" class="grow"><button class="btn primary">Check in</button></form>'
      + (n.heard.length ? '<div class="quick" style="margin-top:10px">' + n.heard.map(h => '<button type="button" data-heard="' + esc(h.call) + '" data-name="' + esc(h.name) + '">+ ' + esc(h.call) + '</button>').join('') + '</div><div class="sub">Heard in the last 30 minutes: click to check in</div>' : '')
      + '<div class="list">' + (n.checkIns.map((k, i) => '<div class="item"><span class="n">' + (i + 1) + '</span><div class="main"><b class="pk-from">' + esc(k.call) + '</b> ' + esc(k.name) + '<div class="sub">' + hhmm(k.ts) + (k.notes ? ' · ' + esc(k.notes) : '') + '</div></div>'
        + flag(k, 'traffic', 'Traffic', 'var(--amber)') + flag(k, 'late', 'Late', 'var(--violet)') + flag(k, 'out', 'Out', 'var(--red)') + '<button class="act" data-rm="' + k.id + '">Remove</button></div>').join('') || '<div class="empty">No check-ins yet</div>') + '</div>';
    $('netCheckin').addEventListener('submit', async e => { e.preventDefault(); if (await act('nets/checkin', { call: $('ciCall').value, name: $('ciName').value })) { $('ciCall').value = ''; $('ciName').value = ''; loadNet(); $('ciCall').focus(); } });
  } catch (e) { }
}
$('netBody').addEventListener('click', async e => {
  const t = e.target; const n = S.net && S.net.running;
  if (t.dataset.heard) { if (await act('nets/checkin', { call: t.dataset.heard, name: t.dataset.name })) loadNet(); }
  else if (t.dataset.flag && n) { const k = n.checkIns.find(x => x.id === +t.dataset.id); const body = { id: k.id }; body[t.dataset.flag] = !k[t.dataset.flag]; if (await act('nets/update', body)) loadNet(); }
  else if (t.dataset.rm) { if (await act('nets/update', { id: +t.dataset.rm, remove: true })) loadNet(); }
  else if (t.dataset.netdel) { if (confirm('Delete this net and its check-ins?') && await act('nets/delete', { id: +t.dataset.netdel })) loadNet(); }
});

// ---- recordings ----
async function loadRecordings() {
  try {
    const j = await api('recordings'); S.recs = j.recordings;
    $('recList').innerHTML = j.recordings.map((r, i) => '<div class="item" data-i="' + i + '"><span class="t">' + new Date(r.ts).toLocaleDateString() + '<br>' + hhmmss(r.ts) + '</span><div class="main"><b>' + esc(r.channel) + '</b><div class="sub mono">' + esc(r.name) + ' · ' + r.seconds.toFixed(1) + ' s</div></div>'
      + '<button class="act" data-play="' + i + '">▶ Play</button><a class="act" href="/api/recording?download=1&id=' + encodeURIComponent(r.id) + '">Download</a><button class="act" data-del="' + i + '">Delete</button></div>').join('') || '<div class="empty">No recordings yet. Turn REC on at the intercom.</div>';
  } catch (e) { }
}
$('recList').onclick = async e => {
  const p = e.target.closest('[data-play]'), d = e.target.closest('[data-del]');
  if (p) { const r = S.recs[+p.dataset.play]; const pl = $('player'); pl.src = '/api/recording?id=' + encodeURIComponent(r.id); pl.play().catch(() => {}); document.querySelectorAll('#recList .item').forEach(x => x.classList.toggle('playing', x.dataset.i === p.dataset.play)); }
  if (d) { const r = S.recs[+d.dataset.del]; if (confirm('Delete ' + r.name + '?') && await act('recording/delete', { id: r.id })) loadRecordings(); }
};

// ---- packet tools + APRS settings ----
async function loadPacket() {
  try {
    const j = await api('packet'); S.pkt = j; const t = j.tools, st = j.settings;
    const sw = (key, label, sub, on) => '<div class="set"><div class="main"><div>' + esc(label) + '</div><div class="sub">' + esc(sub) + '</div></div><button type="button" class="switch' + (on ? ' on' : '') + '" data-tool="' + key + '" role="switch" aria-checked="' + on + '" aria-label="' + esc(label) + '"></button></div>';
    $('pktTools').innerHTML = sw('is', 'APRS-IS', 'Internet gateway: ' + t.isStatus, t.is) + sw('kiss', 'KISS TNC', t.kiss ? (t.kissClient ? 'A client is connected' : 'Waiting for a client on port ' + st.kissPort) : 'For APRSdroid, YAAC and other packet programs (TCP port ' + st.kissPort + ')', t.kiss)
      + sw('parrot', 'Parrot repeater', 'Plays back what it hears on this channel (needs transmit)', t.parrot)
      + sw('fox', 'Fox transmitter', 'Be the fox: a short tone beacon repeating on this channel, with your Morse ID on amateur bands. Needs transmit; the hunt itself is done on the phone', t.fox);
    $('cwIdBtn').disabled = $('dtmfForm').querySelector('button').disabled = !t.webTx;
    $('dtmfSaved').innerHTML = (j.dtmfSaved || []).map(d => '<button type="button" data-seq="' + esc(d.code) + '">' + esc(d.name + ' · ' + d.code) + '</button>').join('');
    $('dtmfLog').innerHTML = j.dtmf.map(d => '<div class="item"><span class="t">' + hhmmss(d.ts) + '</span><div class="main mono">' + esc(d.digits) + '<div class="sub">' + esc(d.channelName || ('Channel ' + d.channel)) + '</div></div></div>').join('') || '<div class="empty">No DTMF heard yet</div>';
    $('alertState').textContent = j.settings.wx ? 'LISTENING' : 'OFF'; $('alertState').className = 'chip ' + (j.settings.wx ? 'green' : 'dim');
    $('alertList').innerHTML = j.alerts.map(a => '<div class="item"><span class="t">' + new Date(a.ts).toLocaleString() + '</span><div class="main"><b style="color:var(--amber)">' + esc(a.event) + '</b><div class="sub">' + esc(a.summary) + '</div></div></div>').join('') || '<div class="empty">No alerts received</div>';
    renderAprsSettings(j);
  } catch (e) { }
}
function renderAprsSettings(j) {
  const s = j.settings; const opt = (vals, cur) => vals.map(([v, t]) => '<option value="' + v + '"' + (String(v) === String(cur) ? ' selected' : '') + '>' + esc(t) + '</option>').join('');
  const f = (label, input) => '<label class="f"><span>' + esc(label) + '</span>' + input + '</label>';
  const chk = (k, label) => '<label class="chk"><input type="checkbox" data-k="' + k + '"' + (s[k] ? ' checked' : '') + '> ' + esc(label) + '</label>';
  $('aprsSet').innerHTML = '<div class="sect">STATION</div>'
    + f('Callsign', '<input data-k="callsign" placeholder="' + (s.callsignSet ? 'Set (hidden; type to change)' : 'Not set') + '" autocapitalize="characters">')
    + f('SSID (0–15)', '<input data-k="ssid" inputmode="numeric" value="' + s.ssid + '">')
    + f('Symbol (table + code)', '<input data-k="symbol" maxlength="2" value="' + esc(s.symbol) + '">')
    + f('Comment', '<input data-k="comment" maxlength="40" value="' + esc(s.comment) + '">')
    + f('Path', '<input data-k="path" value="' + esc(s.path) + '">')
    + f('Message groups', '<input data-k="groups" value="' + esc(s.groups) + '" placeholder="e.g. CQ, QST">')
    + '<div class="sect">BEACONS AND TRANSMIT</div>'
    + f('Automatic beacons', '<select data-k="autoBeacon">' + opt([[0, 'Off'], [1, 'Every N minutes'], [2, 'SmartBeaconing']], s.autoBeacon) + '</select>')
    + f('Every N minutes', '<input data-k="beaconMin" inputmode="numeric" value="' + s.beaconMin + '">')
    + f('Transmit power', '<select data-k="txPower">' + opt([[1, 'High'], [0, 'Low']], s.txPower) + '</select>')
    + f('Packet lead-in sound', '<select data-k="leadKind">' + opt(j.leadKinds.map((k, i) => [i, k]), s.leadKind) + '</select>')
    + f('Lead-in length', '<select data-k="leadMs">' + opt(j.leadChoices.map(ms => [ms, ms ? (ms / 1000).toFixed(1) + ' s' : 'None']), s.leadMs) + '</select>')
    + '<div class="sect">RECEIVE AND DECODERS</div>'
    + f('Decode packets on analog channels', '<select data-k="passive">' + opt([[0, 'Off'], [1, 'Flagged channels'], [2, 'Every analog channel']], s.passive) + '</select>')
    + '<div class="f" style="grid-column:1/-1;flex-direction:row;flex-wrap:wrap;gap:8px 18px">' + (s.autoAckAllowed === false ? '<span class="muted">No auto-ack: this radio has no 2 m band</span>' : chk('autoAck', 'Acknowledge messages to me')) + chk('digipeat', 'Fill-in digipeater') + chk('dtmf', 'DTMF decoder') + chk('wx', 'Weather alerts (SAME)') + chk('cw', 'Morse decoder') + chk('cwId', 'Morse ID after packets') + chk('telemetry', 'Telemetry beacon') + chk('weather', 'Weather beacon') + chk('txIgate', 'Transmit iGate') + '</div>'
    + '<div class="sect">APRS-IS AND KISS</div>'
    + f('APRS-IS server', '<input data-k="isServer" value="' + esc(s.isServer) + '">')
    + f('APRS-IS port', '<input data-k="isPort" inputmode="numeric" value="' + s.isPort + '">')
    + f('APRS-IS passcode', '<input data-k="isPasscode" type="password" placeholder="' + (s.isPasscodeSet ? 'Set (hidden; type to change)' : 'Not set: receive only') + '">')
    + f('Internet beacon every N minutes (0 off)', '<input data-k="isBeaconMin" inputmode="numeric" value="' + s.isBeaconMin + '">')
    + f('Internet beacon comment', '<input data-k="isComment" value="' + esc(s.isComment) + '">')
    + f('KISS TCP port', '<input data-k="kissPort" inputmode="numeric" value="' + s.kissPort + '">')
    + '<div class="actions"><button class="btn primary">Save APRS settings</button></div>'
    + '<div class="note">Your callsign and APRS-IS passcode are never shown here: leave them empty to keep what is stored on the phone.</div>';
}
$('aprsSet').addEventListener('submit', async e => {
  e.preventDefault(); const body = {};
  $('aprsSet').querySelectorAll('[data-k]').forEach(el => {
    const k = el.dataset.k; let v = el.type === 'checkbox' ? el.checked : el.value;
    if (['ssid', 'beaconMin', 'isPort', 'isBeaconMin', 'kissPort', 'autoBeacon', 'txPower', 'leadKind', 'leadMs', 'passive'].includes(k)) v = parseInt(v, 10);
    if ((k === 'callsign' || k === 'isPasscode') && !v) return;    // write-only: empty keeps the stored value
    body[k] = v;
  });
  if (await act('packet/settings', body)) loadPacket();
});
$('pktTools').addEventListener('click', async e => { const b = e.target.closest('[data-tool]'); if (!b) return; const on = !b.classList.contains('on'); await act('packet/tool', { tool: b.dataset.tool, on }); setTimeout(loadPacket, 800); });
$('dtmfForm').addEventListener('submit', async e => { e.preventDefault(); if (await act('dtmf', { seq: $('dtmfSeq').value })) $('dtmfSeq').value = ''; });
$('dtmfSaved').onclick = e => { const b = e.target.closest('[data-seq]'); if (b) $('dtmfSeq').value = b.dataset.seq; };
$('cwIdBtn').onclick = () => act('cwid', {});

// ---- SSTV: send a picture (the phone scales it to the mode's 320 x 240 and transmits) ----
$('sstvSend').addEventListener('submit', async e => {
  e.preventDefault();
  if (!S.status.webTx) { toast('Transmit from the web is off (phone: Device → SignalDeck Web)'); return; }
  const f = $('sstvFile').files[0]; if (!f) { toast('Choose a picture first'); return; }
  if (f.size > 8 * 1024 * 1024) { toast('That picture is too large (8 MB at most)'); return; }
  try {
    const r = await fetch('/api/sstv/send?mode=' + $('sstvMode').value, { method: 'POST', headers: { 'Content-Type': f.type || 'application/octet-stream' }, body: f });
    const j = await r.json().catch(() => ({})); toast(r.ok ? j.message : (j.error || 'HTTP ' + r.status));
  } catch (err) { toast(err.message); }
});

// ---- last heard + alerts (under the intercom) ----
async function loadHeard() {
  try {
    const j = await api('heard');
    const day = ts => new Date(ts).toLocaleDateString([], { month: '2-digit', day: '2-digit' }) + ' ' + hhmmss(ts);
    $('heardList').innerHTML = j.heard.map(h => '<div class="item' + (h.channel > 0 ? ' click' : '') + '" data-ch="' + h.channel + '"><span class="t">' + day(h.ts) + '</span><div class="main">'
      + (h.dmrId > 0 ? '<b style="color:var(--amber)">' + esc(h.name || ('ID ' + h.dmrId)) + '</b> <span class="sub mono">' + h.dmrId + '</span>' : '<span class="pk-from">' + esc(h.text.length > 70 ? h.text.slice(0, 70) + '…' : h.text) + '</span>')
      + '<div class="sub">' + esc(h.channelName) + (h.count > 1 ? ' · ' + h.count + '×' : '') + (h.dmrId > 0 && h.text ? ' · ' + esc(h.text) : '') + '</div></div></div>').join('') || '<div class="empty">Nothing heard yet</div>';
    $('eventList').innerHTML = j.events.map(e => '<div class="item"><span class="t">' + day(e.ts) + '</span><div class="main">' + esc(e.text) + '</div></div>').join('') || '<div class="empty">No alerts or events this week</div>';
  } catch (e) { }
}
$('heardRefresh').onclick = loadHeard;
$('heardList').onclick = e => { const it = e.target.closest('.item.click'); if (it && confirm('Tune channel ' + it.dataset.ch + '?')) act('channel/select', { number: +it.dataset.ch }); };

// ---- voice keyer ----
async function loadKeyer() {
  try {
    const j = await api('keyer'); S.keyer = j;
    const due = j.idDueMs; $('idTimer').textContent = due == null ? 'ID TIMER IDLE' : due <= 0 ? 'STATION ID DUE NOW' : 'ID DUE IN ' + Math.ceil(due / 60000) + ' MIN';
    $('idTimer').className = 'chip ' + (due != null && due <= 0 ? 'amber' : 'dim');
    $('keyerList').innerHTML = j.slots.map((k, i) => '<div class="set"><div class="main"><input data-kname="' + i + '" value="' + esc(k.name) + '" maxlength="24" style="width:180px"> <span class="sub">' + (k.has ? (k.ms / 1000).toFixed(1) + ' s' : 'empty') + '</span>'
      + '<label class="chk" style="margin-top:6px"><input type="checkbox" data-kid="' + i + '"' + (k.addId ? ' checked' : '') + '> Morse ID after it</label></div>'
      + '<div class="row wrap"><label class="btn small-btn">Upload<input type="file" accept="audio/*" data-kup="' + i + '" hidden></label>'
      + (k.has ? '<button class="btn small-btn" data-kplay="' + i + '">▶ Play</button><button class="btn small-btn primary" data-ksend="' + i + '"' + (j.webTx ? '' : ' disabled') + '>Send</button><button class="btn small-btn danger" data-kdel="' + i + '">Delete</button>' : '') + '</div></div>').join('');
  } catch (e) { }
}
/** Any audio file → 8 kHz mono 16-bit little-endian, at most 25 s. */
async function toKeyerPcm(file) {
  const buf = await file.arrayBuffer();
  const dec = await new (window.AudioContext || window.webkitAudioContext)().decodeAudioData(buf);
  const secs = Math.min(25, dec.duration); const off = new OfflineAudioContext(1, Math.ceil(secs * 8000), 8000);
  const src = off.createBufferSource(); src.buffer = dec; src.connect(off.destination); src.start();
  const out = await off.startRendering(); const f = out.getChannelData(0);
  let peak = 0; for (const v of f) peak = Math.max(peak, Math.abs(v)); const g = peak > 0 ? Math.min(4, 0.9 / peak) : 1;   // normalise quiet recordings
  const dv = new DataView(new ArrayBuffer(f.length * 2)); for (let i = 0; i < f.length; i++) dv.setInt16(i * 2, Math.max(-32768, Math.min(32767, Math.round(f[i] * g * 32767))), true);
  return dv.buffer;
}
let keyerAudio = null;
$('keyerList').addEventListener('change', async e => {
  const t = e.target;
  if (t.dataset.kname) act('keyer', { slot: +t.dataset.kname, name: t.value });
  if (t.dataset.kid) act('keyer', { slot: +t.dataset.kid, addId: t.checked });
  if (t.dataset.kup && t.files[0]) {
    try {
      const pcm = await toKeyerPcm(t.files[0]);
      const r = await fetch('/api/keyer/audio?slot=' + t.dataset.kup, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: pcm });
      const j = await r.json(); toast(r.ok ? j.message : j.error); loadKeyer();
    } catch (err) { toast('Could not read that file: ' + err.message); }
  }
});
$('keyerList').addEventListener('click', async e => {
  const t = e.target.closest('button'); if (!t) return;
  if (t.dataset.kplay) { if (keyerAudio) keyerAudio.pause(); keyerAudio = new Audio('/api/keyer/audio?slot=' + t.dataset.kplay + '&t=' + Date.now()); keyerAudio.play().catch(() => {}); }
  if (t.dataset.ksend) act('keyer', { slot: +t.dataset.ksend, send: true });
  if (t.dataset.kdel && confirm('Delete ' + S.keyer.slots[+t.dataset.kdel].name + '?')) { await act('keyer', { slot: +t.dataset.kdel, delete: true }); loadKeyer(); }
});

// ---- text modes (RTTY / PSK31) and the packet terminal, refreshed while the Packet page is open ----
function setTerm(el, text) { const atEnd = el.scrollTop + el.clientHeight >= el.scrollHeight - 20; if (el.textContent !== text) { el.textContent = text; if (atEnd) el.scrollTop = el.scrollHeight; } }
async function pollPacketTools() {
  if (S.page !== 'pkt') return;
  try {
    const [t, r] = await Promise.all([api('textmodes'), api('terminal')]);
    if (!$('tmMode').options.length) $('tmMode').innerHTML = t.modes.map((m, i) => '<option value="' + i + '">' + (i ? 'Listen: ' + esc(m) : 'Not listening') + '</option>').join('');
    if (document.activeElement !== $('tmMode')) $('tmMode').value = t.mode;
    if (document.activeElement !== $('tmTx')) $('tmTx').value = t.txMode;
    $('tmStatus').textContent = t.status.toUpperCase(); setTerm($('tmText'), t.text || '');
    $('tmForm').querySelector('button').disabled = !t.webTx;
    $('trState').textContent = r.state.toUpperCase(); $('trState').className = 'chip ' + (/^connected/.test(r.state) ? 'green' : 'dim');
    $('trAccept').checked = r.accept; $('trAccept').disabled = !r.webTx && !r.accept;
    $('trConnect').querySelector('button').disabled = $('trForm').querySelector('button').disabled = !r.webTx || !r.callsignSet;
    setTerm($('trText'), r.transcript || (r.callsignSet ? '' : 'Set your callsign in the APRS settings first.'));
  } catch (e) { }
}
setInterval(pollPacketTools, 1000);
$('tmMode').onchange = () => act('textmodes', { mode: +$('tmMode').value });
$('tmTx').onchange = () => act('textmodes', { txMode: +$('tmTx').value });
$('tmClear').onclick = () => act('textmodes', { clear: true });
$('tmForm').addEventListener('submit', async e => { e.preventDefault(); if (await act('textmodes', { send: $('tmLine').value })) $('tmLine').value = ''; });
$('trConnect').addEventListener('submit', e => { e.preventDefault(); act('terminal', { connect: $('trCall').value.trim(), via: $('trVia').value.trim() }); });
$('trDisc').onclick = () => act('terminal', { disconnect: true });
$('trAccept').onchange = () => act('terminal', { accept: $('trAccept').checked });
$('trForm').addEventListener('submit', async e => { e.preventDefault(); if (await act('terminal', { send: $('trLine').value })) $('trLine').value = ''; });

// ---- M17 receive: state once a second while the Packet page is open ----
let m7 = null;
async function pollM17() {
  if (S.page !== 'pkt') return;
  try {
    const j = await api('m17'); m7 = j;
    $('m7State').textContent = j.running ? (j.receiving ? 'RECEIVING' : 'LISTENING') : 'OFF'; $('m7State').className = 'chip ' + (j.running ? (j.receiving ? 'green' : 'amber') : 'dim');
    $('m7On').textContent = j.running ? 'Stop M17' : 'Start M17'; $('m7On').disabled = !j.running && !j.analog;
    const c = j.current;
    $('m7Now').innerHTML = c ? esc((c.src || '…') + '  →  ' + (c.dst || '…')) + (c.encrypted ? '<span class="meta">Encrypted: not played</span>' : c.meta ? '<span class="meta">“' + esc(c.meta) + '”</span>' : '') + '<span class="stats">' + c.seconds.toFixed(1) + ' s · ' + c.frames + ' frames · signal ' + j.quality + ' %</span>'
      : (j.running ? '<span class="stats">Listening for M17…</span>' : '');
    const t = ms => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    $('m7List').innerHTML = j.heard.slice().reverse().map(h => '<div class="item"><div class="main"><b class="mono">' + esc(h.src || '?') + '</b> → ' + esc(h.dst || '?') + (h.meta ? ' · “' + esc(h.meta) + '”' : '') + '</div><div class="sub">' + t(h.start) + ' · ' + ((h.end - h.start) / 1000).toFixed(1) + ' s · ' + h.frames + ' frames' + (h.frames ? ' · ' + Math.round(100 * h.good / h.frames) + ' % clean' : '') + '</div></div>').join('') || (j.running ? '<div class="empty">Nothing heard yet</div>' : '');
    $('m7Form').querySelector('button').disabled = !j.webTx || !j.canSend;
    $('m7Texts').innerHTML = (j.texts || []).slice().reverse().map(x => '<div class="item"><div class="main"><b>' + (x.sent ? 'Sent' : 'Heard') + '</b> ' + esc(x.src || '') + ' → ' + esc(x.dst || '') + '<div class="sub">' + esc(x.body) + '</div></div><span class="t">' + t(x.ts) + '</span></div>').join('');
    $('m7Cal').textContent = j.calibration ? 'Equalizer ' + j.calibration + '.' : '';
  } catch (e) { }
}
setInterval(pollM17, 1000);
$('m7On').onclick = () => act('m17', { on: !(m7 && m7.running) }).then(pollM17);
$('m7Form').addEventListener('submit', async e => {
  e.preventDefault();
  if (await act('m17/sms', { dest: $('m7Dest').value.trim(), text: $('m7Text').value })) { $('m7Text').value = ''; pollM17(); }
});

// ---- pager page ----
async function loadPager() {
  try {
    const ric = $('pgRic').value.trim();
    const j = await api('pager/pages' + (ric ? '?ric=' + encodeURIComponent(ric) : ''));
    $('pgState').textContent = j.monitor ? 'MONITOR' : j.listening ? 'LISTENING' : 'OFF';
    $('pgState').className = 'chip ' + (j.listening ? 'green' : 'dim');
    $('pgMon').textContent = j.monitor ? 'Stop monitor' : 'Monitor';
    $('pgScan').textContent = j.scan ? ('Monitor steps ' + j.scan + ', about two and a half seconds on each.') : 'Set at least two pager frequencies on the phone (Messages, long-press the scan icon).';
    if (document.activeElement !== $('pgMhz') && !$('pgMhz').value) $('pgMhz').value = (j.hz / 1e6).toFixed(4);
    $('pgForm').querySelector('button').disabled = !j.webTx;
    $('pgNote').textContent = j.webTx ? '' : 'Sending needs “Allow transmit from the web” on the phone.';
    $('pgList').innerHTML = j.pages.map(p => '<div class="item"><div class="main"><b>RIC ' + p.ric + '</b> <span class="chip dim">' + p.baud + '</span>' + (p.outgoing ? ' <span class="chip dim">SENT</span>' : '') + '<div class="sub">' + esc(p.text) + '</div></div><span class="t">' + new Date(p.ts).toLocaleString() + '<br>' + (p.hz / 1e6).toFixed(4) + '</span></div>').join('') || '<div class="empty">No pages yet</div>';
  } catch (e) { }
}
setInterval(() => { if (S.page === 'pager') loadPager(); }, 2000);
$('pgRic').addEventListener('change', loadPager);
$('pgMon').onclick = async () => { await act('pager/monitor', { on: $('pgMon').textContent.indexOf('Stop') !== 0 }); loadPager(); };
$('pgForm').addEventListener('submit', async e => {
  e.preventDefault();
  const mhz = parseFloat($('pgMhz').value);
  if (!mhz) { toast('Enter the frequency in MHz'); return; }
  if (await act('pager/send', { hz: Math.round(mhz * 1e6), baud: +$('pgBaud').value, to: $('pgTo').value, text: $('pgText').value, sign: $('pgSign').checked, func: 3 })) {
    $('pgText').value = ''; setTimeout(loadPager, 800);
  }
});

async function loadDevice() {
  try {
    const j = await api('device');
    const row = (k, v) => '<div class="item"><span class="t">' + k + '</span><div class="main">' + v + '</div></div>';
    $('devCard').innerHTML = row('Firmware', esc(j.tag || '—') + (j.factory ? ' · factory image' : (j.stock ? ' · stock ' + esc(j.stock) : '')))
      + row('Profile', esc(j.profile || '—') + (j.profileId ? ' · ' + esc(j.profileId) + ' rev ' + j.profileRevision : ''))
      + row('App', esc(j.version || ''))
      + row('Battery', j.batteryC == null ? '—' : j.batteryC.toFixed(1) + ' °C')
      + row('Processor', j.apC == null ? '—' : j.apC.toFixed(1) + ' °C')
      + row('Own the radio at boot', j.ownAtBoot ? 'On' : 'Off')
      + row('Wi-Fi debugging', j.wifiAdb ? 'Listening on port 5555' : (j.wifiAdbPort ? 'Port ' + esc(j.wifiAdbPort) : 'Off'));
  } catch (e) { }
}
$('devRestart').onclick = async () => {
  if (!confirm('Restart the radio module? Reception stops for a few seconds. Nothing is transmitted.')) return;
  await act('device/restart', { confirm: true });
};
$('devUpdate').onclick = async () => {
  if (!confirm('Check for a SignalDeck update?')) return;
  const j = await act('device/update', { confirm: true });
  if (j && j.newer && confirm('Install ' + j.latest + '? The app restarts and keeps your channels and settings.')) await act('device/update', { confirm: true, install: true });
};

// ---- FT8 over FM: state once a second, the waterfall about five times a second while running ----
const f8 = { j: null, skew: 0, fall: null };
function f8Row(h) { return h.time + ' ' + (h.snr >= 0 ? '+' : '') + String(h.snr).padStart(3) + ' ' + (h.dt >= 0 ? '+' : '') + h.dt.toFixed(1) + ' ' + String(h.hz).padStart(4) + '  ' + h.shown; }
async function pollFt8() {
  if (S.page !== 'pkt') return;
  try {
    const j = await api('ft8'); f8.j = j; f8.skew = j.now - Date.now();
    $('f8State').textContent = j.running ? (j.tx ? 'TRANSMITTING IN ITS PERIOD' : 'LISTENING') : 'OFF'; $('f8State').className = 'chip ' + (j.running ? (j.tx ? 'amber' : 'green') : 'dim');
    $('f8On').textContent = j.running ? 'Stop FT8' : 'Start FT8';
    $('f8Cq').disabled = $('f8Form').querySelector('button').disabled = !j.webTx || !j.callsignSet || !j.analog;
    $('f8Halt').disabled = !j.tx;
    if (document.activeElement !== $('f8Par')) $('f8Par').value = j.parity;
    if (document.activeElement !== $('f8Hz')) $('f8Hz').value = j.txHz;
    if (document.activeElement !== $('f8Grid')) $('f8Grid').value = j.gridSetting;
    $('f8Next').textContent = j.tx && j.next ? 'Next: ' + j.next : (j.partner ? 'QSO with ' + j.partner : (j.running ? j.status : (j.callsignSet ? 'Not running.' : 'Set your callsign in the APRS settings first.')));
    const key = j.heard.length + ':' + j.sent.length + ':' + (j.heard.length ? j.heard[j.heard.length - 1].slot : 0);
    if (key !== f8.key) {
      f8.key = key;
      const rows = j.heard.slice().reverse().map(h => '<div class="' + (h.toMe ? 'me' : h.cq ? 'cq' : '') + '" data-slot="' + h.slot + '" data-text="' + esc(h.text) + '">' + esc(f8Row(h)) + '</div>');
      const sent = j.sent.slice().reverse().slice(0, 20).map(t => '<div class="sent">' + esc(t) + '</div>');
      $('f8List').innerHTML = rows.length || sent.length ? rows.join('') + (sent.length ? '<div class="sent">— sent —</div>' + sent.join('') : '') : '<div class="sent">Nothing decoded yet. Each 15 s slot is decoded when it ends.</div>';
    }
  } catch (e) { }
}
function drawFt8Clock() {
  const j = f8.j; if (!j || S.page !== 'pkt') return;
  const t = Date.now() + f8.skew, slot = Math.floor(t / 15000), sec = (t % 15000) / 1000, par = slot & 1, ours = j.tx && par === j.parity;
  $('f8Clock').className = 'f8clock' + (par ? ' p1' : '') + (ours ? ' tx' : '');
  $('f8Clock').firstElementChild.style.width = (sec / 15 * 100).toFixed(1) + '%';
  $('f8Clock').lastElementChild.textContent = (par ? '2nd' : '1st') + ' period  ' + sec.toFixed(1) + ' s' + (ours ? '  · TX' : '');
}
async function pollFt8Fall() {
  if (S.page !== 'pkt' || !f8.j || !f8.j.running) return;
  try {
    const j = await api('ft8/spectrum'); if (!j.db.length) return;
    const c = $('f8Fall'), g = c.getContext('2d'), w = c.width, h = c.height;
    g.drawImage(c, 0, 0, w, h - 1, 0, 1, w, h - 1);
    const sorted = j.db.slice().sort((a, b) => a - b), med = sorted[sorted.length >> 1];
    f8.floor = f8.floor === undefined ? med : f8.floor * 0.9 + med * 0.1;
    const img = g.createImageData(w, 1);
    for (let x = 0; x < w; x++) {
      const v = Math.max(0, Math.min(1, (j.db[Math.floor(x * j.db.length / w)] - f8.floor - 3) / 30));
      const r = v < .4 ? 6 + 25 * v : v < .75 ? 16 + 680 * (v - .4) : 255, gr = v < .4 ? 18 + 375 * v : v < .75 ? 168 + 63 * (v - .4) : 190 + 260 * (v - .75), b = v < .4 ? 26 + 425 * v : v < .75 ? 196 - 430 * (v - .4) : 46 + 836 * (v - .75);
      img.data.set([r, gr, b, 255], x * 4);
    }
    g.putImageData(img, 0, 0);
    const mx = (f8.j.txHz - 200) / 2600 * w; g.fillStyle = 'rgba(255,84,104,.8)'; g.fillRect(mx, 0, 2, h);
  } catch (e) { }
}
setInterval(pollFt8, 1000); setInterval(drawFt8Clock, 100); setInterval(pollFt8Fall, 200);
$('f8On').onclick = () => act('ft8', { on: !(f8.j && f8.j.running) }).then(pollFt8);
$('f8Cq').onclick = () => act('ft8', { cq: true }).then(pollFt8);
$('f8Halt').onclick = () => act('ft8', { halt: true }).then(pollFt8);
$('f8Par').onchange = () => act('ft8', { parity: +$('f8Par').value });
$('f8Hz').onchange = () => act('ft8', { txHz: +$('f8Hz').value });
$('f8Grid').onchange = () => act('ft8', { grid: $('f8Grid').value.trim() });
$('f8Fall').onclick = e => { const r = e.target.getBoundingClientRect(); act('ft8', { txHz: Math.round(200 + 2600 * (e.clientX - r.left) / r.width) }).then(pollFt8); };
$('f8List').addEventListener('click', e => { const d = e.target.closest('div[data-slot]'); if (d) act('ft8', { reply: { slot: +d.dataset.slot, text: d.dataset.text } }).then(pollFt8); });
$('f8Form').addEventListener('submit', async e => { e.preventDefault(); if (await act('ft8', { send: $('f8Line').value.trim() })) $('f8Line').value = ''; pollFt8(); });

// ---- diagnostics log (Settings) ----
async function loadDiagLog() { try { const j = await api('log'); const el = $('diagLog'); el.textContent = j.lines.join(String.fromCharCode(10)); el.scrollTop = el.scrollHeight; } catch (e) { } }
$('logRefresh').onclick = loadDiagLog;

// ---- settings ----
async function loadSettings() { try { renderSettings(await api('settings')); } catch (e) { } }
function renderSettings(j) {
  S.settings = j.settings;
  const f = (k, v) => '<div><span>' + k + '</span><b>' + esc(v) + '</b></div>';
  $('radioCard').innerHTML = '<div class="big">' + (j.ready ? 'SIGNALDECK OWNS THE RADIO' : esc(String(j.link || '').toUpperCase())) + '</div><div class="grid">' + f('PHONE', j.profile || '—') + (j.region ? f('REGION', (j.region.chosen ? j.region.name : 'Not chosen') + ' · APRS ' + (j.region.aprsHz / 1e6).toFixed(3)) : '') + f('VERSION', j.version) + (j.firmwareStock ? f('RADIO FIRMWARE', j.firmware && j.firmware !== j.firmwareStock ? j.firmware + ' (stock ' + j.firmwareStock + ')' : j.firmwareStock + ' (stock)') : '') + '</div>';
  $('webCard').innerHTML = '<div class="grid">' + f('BROWSERS', j.browsers) + f('LISTENING', j.listeners) + f('TRANSMIT FROM WEB', j.webTx ? 'Allowed' : 'Off') + '</div>';
  let html = '', group = null;
  j.settings.forEach((s, i) => {
    if (s.group !== group) { group = s.group; html += '<div class="group">' + esc(group).toUpperCase() + '</div>'; }
    let ctl;
    if (s.kind === 'bool') ctl = '<button type="button" class="switch' + (s.value ? ' on' : '') + '" data-i="' + i + '" role="switch" aria-checked="' + !!s.value + '" aria-label="' + esc(s.label) + '"></button>';
    else if (s.kind === 'choice') ctl = '<select data-i="' + i + '">' + s.choices.map((c, ci) => '<option value="' + ci + '"' + (ci === s.value ? ' selected' : '') + '>' + esc(c) + '</option>').join('') + '</select>';
    else ctl = '<input data-i="' + i + '" value="' + esc(s.value) + '"' + (s.kind === 'int' ? ' inputmode="numeric"' : '') + ' title="Enter to save">';
    html += '<div class="set"><div class="main"><div>' + esc(s.label) + '</div><div class="sub">' + esc(s.sub) + '</div></div>' + ctl + '</div>';
  });
  $('setList').innerHTML = html;
}
async function saveSetting(i, value) { const s = S.settings[i]; const j = await act('settings', { key: s.key, value }); if (j) renderSettings(j); else loadSettings(); }
$('setList').addEventListener('click', e => { const b = e.target.closest('.switch'); if (b) saveSetting(+b.dataset.i, !S.settings[+b.dataset.i].value); });
$('setList').addEventListener('change', e => { const el = e.target; if (el.tagName === 'SELECT') { const w = (S.settings[+el.dataset.i].warn || [])[+el.value]; if (w && !confirm(w)) { loadSettings(); return; } saveSetting(+el.dataset.i, +el.value); } else if (el.tagName === 'INPUT') { const s = S.settings[+el.dataset.i]; saveSetting(+el.dataset.i, s.kind === 'int' ? parseInt(el.value, 10) : el.value); } });
// backup and restore
async function loadBackups() {
  try {
    const j = await api('backups'); S.backups = j.backups;
    $('backupList').innerHTML = j.backups.slice(0, 30).map((b, i) => '<div class="item"><div class="main"><b>' + esc(b.name) + '</b><div class="sub">' + new Date(b.ts).toLocaleString() + ' · ' + b.files.length + ' files</div></div>'
      + '<a class="act" href="/api/backup/zip?name=' + encodeURIComponent(b.name) + '">Download</a><button class="act" data-restore="' + i + '">Restore</button></div>').join('') || '<div class="empty">No backups yet</div>';
  } catch (e) { }
}
$('backupNow').onclick = async () => { if (await act('backup', {})) loadBackups(); };
$('backupList').onclick = async e => {
  const b = e.target.closest('[data-restore]'); if (!b) return; const bk = S.backups[+b.dataset.restore];
  if (!confirm('Restore ' + bk.name + '? It replaces every channel, contact and zone in SignalDeck with this backup.')) return;
  if (await act('restore', { name: bk.name })) { loadChannels(); refresh(); }
};
$('restoreFiles').onchange = async () => {
  const fl = [...$('restoreFiles').files]; if (!fl.length) return;
  const files = {}; for (const f of fl) files[f.name] = await f.text();
  if (!files['Channels.csv']) { toast('Channels.csv is needed'); $('restoreFiles').value = ''; return; }
  if (!confirm('Restore from ' + Object.keys(files).join(', ') + '? It replaces every channel, contact and zone in SignalDeck.')) { $('restoreFiles').value = ''; return; }
  if (await act('restore', { files })) { loadChannels(); refresh(); }
  $('restoreFiles').value = '';
};
$('takeOver').onclick = async () => { await act('radio', { action: 'takeover' }); setTimeout(loadSettings, 4000); };
$('handBack').onclick = async () => { if (confirm('Hand the radio back to the stock app? SignalDeck (and this page) stops controlling the radio until you take it over again.')) { await act('radio', { action: 'handback' }); setTimeout(loadSettings, 3000); } };

// ---- help: the phone's guide ----
S.help = null;
async function loadHelp() {
  if (!S.help) { try { S.help = (await api('help')).topics; } catch (e) { return; } }
  renderHelpList(); if (!$('helpBody').innerHTML) showTopic(0);
}
function renderHelpList() {
  const q = $('helpSearch').value.trim().toLowerCase(); let html = '', group = null;
  S.help.forEach((t, i) => {
    const hit = !q || (t.title + ' ' + t.summary + ' ' + t.sections.map(x => x.head + ' ' + x.text).join(' ')).toLowerCase().includes(q);
    if (!hit) return;
    if (t.group !== group) { group = t.group; html += '<div class="group">' + esc(group).toUpperCase() + '</div>'; }
    html += '<div class="item click" data-i="' + i + '"><div class="main"><b>' + esc(t.title) + '</b><div class="sub">' + esc(t.summary) + '</div></div></div>';
  });
  $('helpList').innerHTML = html || '<div class="empty">Nothing matches</div>';
}
function showTopic(i) {
  const t = S.help[i]; const q = $('helpSearch').value.trim().toLowerCase();
  $('helpBody').innerHTML = '<h2>' + esc(t.title) + '</h2><p class="lead">' + esc(t.summary) + '</p>'
    + t.sections.map(x => '<h3' + (q && (x.head + ' ' + x.text).toLowerCase().includes(q) ? ' class="hit"' : '') + '>' + esc(x.head) + '</h3><p>' + esc(x.text) + '</p>').join('');
  const h = $('helpBody').querySelector('.hit'); (h || $('helpBody')).scrollIntoView({ block: 'start', behavior: 'smooth' });
}
$('helpSearch').oninput = renderHelpList;
$('helpList').onclick = e => { const it = e.target.closest('.item'); if (it) showTopic(+it.dataset.i); };

start().catch(() => showLogin());
