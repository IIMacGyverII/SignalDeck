/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
// SignalDeck Web: the browser side of SignalDeck's built-in web server. Talks to /api (JSON) and /api/events (SSE).
'use strict';
const $ = id => document.getElementById(id);

// ---- text: i18n/<lang>.json tables (flat key → text), English as the fallback ----
// t(key, { name: 'x' }) fills {name}; t.plural(key, n) picks key.one / key.other (n is also {n}). Elements carry
// data-i18n (their text), data-i18n-html (text with inline markup), data-i18n-title / -placeholder / -aria / -alt;
// applyI18n(root) fills them. The English text stays in the HTML too, so a page reads the same before the tables load.
// Values put into innerHTML are the tables' own text (shipped with the app); data passed in as {params} is escaped
// by the caller as before.
const I18N = { en: {}, tab: {}, lang: 'en', fromBrowser: false };
function t(key, p) {
  let s = I18N.tab[key]; if (s == null) s = I18N.en[key]; if (s == null) return key;
  return p ? s.replace(/\{(\w+)\}/g, (m, k) => p[k] != null ? String(p[k]) : m) : s;
}
t.plural = (key, n, p) => {
  const par = Object.assign({ n }, p || {});
  let cat = 'other'; try { cat = new Intl.PluralRules(I18N.lang).select(n); } catch (e) { }
  const own = I18N.tab[key + '.' + cat] != null ? key + '.' + cat : I18N.tab[key + '.other'] != null ? key + '.other' : null;
  return t(own || key + '.' + (n === 1 ? 'one' : 'other'), par);
};
function applyI18n(root) {
  const r = root || document;
  r.querySelectorAll('[data-i18n]').forEach(el => {
    const s = t(el.dataset.i18n); let node = null;
    for (const c of el.childNodes) if (c.nodeType === 3 && c.nodeValue.trim()) { node = c; break; }
    if (!node) { el.appendChild(document.createTextNode(s)); return; }
    const v = node.nodeValue; node.nodeValue = v.match(/^\s*/)[0] + s + v.match(/\s*$/)[0];   // the text node only: a label keeps its input
  });
  r.querySelectorAll('[data-i18n-html]').forEach(el => { el.innerHTML = t(el.dataset.i18nHtml); });
  r.querySelectorAll('[data-i18n-title]').forEach(el => { el.title = t(el.dataset.i18nTitle); });
  r.querySelectorAll('[data-i18n-placeholder]').forEach(el => { el.placeholder = t(el.dataset.i18nPlaceholder); });
  r.querySelectorAll('[data-i18n-aria]').forEach(el => { el.setAttribute('aria-label', t(el.dataset.i18nAria)); });
  r.querySelectorAll('[data-i18n-alt]').forEach(el => { el.alt = t(el.dataset.i18nAlt); });
}
async function i18nTable(lang) {
  try {
    const r = await fetch('/i18n/' + lang + '.json');            // a language without a table is a plain 404
    if (!r.ok || !/json/.test(r.headers.get('content-type') || '')) return null;
    return await r.json();
  } catch (e) { return null; }
}
/** English first; then the first of the browser's languages with a table (de-AT tries de-AT, then de), or appLang if given. */
async function loadI18n(appLang) {
  if (!Object.keys(I18N.en).length) I18N.en = (await i18nTable('en')) || {};
  const tried = new Set(); let lang = null, tab = null;
  const tryLang = async l => { if (!l || tried.has(l)) return false; tried.add(l); const x = l === 'en' ? (Object.keys(I18N.en).length ? I18N.en : null) : await i18nTable(l); if (x) { lang = l; tab = x; } return !!x; };
  if (!appLang) {
    for (const l of navigator.languages || [navigator.language]) if (await tryLang(l) || await tryLang(String(l).split('-')[0])) break;
    I18N.fromBrowser = !!lang;
  } else if (!(await tryLang(appLang))) await tryLang(String(appLang).split('-')[0]);
  if (!lang) return false;
  I18N.lang = lang; I18N.tab = tab; document.documentElement.lang = lang;
  applyI18n(document); return true;
}
I18N.ready = loadI18n();

const S = { status: null, history: [], channels: [], zones: [], aprsLog: [], stations: [], pictures: [], es: null, page: 'intercom' };

async function api(path, body) {
  const opt = body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const r = await fetch('/api/' + path, opt);
  if (r.status === 401) { showLogin(); throw new Error(t('common_login_required')); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) { const e = new Error(j.error || ('HTTP ' + r.status)); e.status = r.status; throw e; }   // i18n: protocol (the status, not the text, tells errors apart)
  return j;
}
function toast(msg) { const t = $('toast'); t.textContent = msg; t.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.add('hidden'), 3500); }
async function act(path, body) { try { const j = await api(path, body); if (j.message) toast(j.message); return j; } catch (e) { toast(e.message); return null; } }
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const mhz = hz => (hz / 1e6).toFixed(4);
// A number typed in a field, as the phone reads it (Txt.parseDecimal): an optional sign, digits and at most one decimal
// sign, a point or a comma ("439,9875" is 439.9875). Anything else is NaN ("1.234,5", "1,234.5", "12a", "1,2,3", ""):
// parseFloat would stop at the comma and send 439.000 MHz. intNum: the same, and it must be whole.
function num(s) {
  const v = String(s == null ? '' : s).trim();
  if (!/^[-+]?(\d+([.,]\d*)?|[.,]\d+)$/.test(v)) return NaN;
  return Number(v.replace(',', '.'));
}
const intNum = s => { const n = num(s); return Number.isInteger(n) ? n : NaN; };
const hhmm = ts => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const hhmmss = ts => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const ago = ts => { const s = Math.max(0, (Date.now() - ts) / 1000); return s < 60 ? t('common_ago_s', { n: Math.round(s) }) : s < 3600 ? t('common_ago_min', { n: Math.round(s / 60) }) : s < 86400 ? t('common_ago_h', { n: Math.round(s / 3600) }) : new Date(ts).toLocaleDateString(); };

// ---- login ----
function showLogin() { if (S.es) { S.es.close(); S.es = null; } $('app').classList.add('hidden'); $('login').classList.remove('hidden'); $('pin').focus(); }
$('loginForm').addEventListener('submit', async e => {
  e.preventDefault(); $('loginErr').textContent = '';
  const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pin: $('pin').value }) });
  if (r.ok) { $('pin').value = ''; start(); } else { const j = await r.json().catch(() => ({})); $('loginErr').textContent = j.error || t('login_failed'); }
});
$('logout').addEventListener('click', async () => { await fetch('/api/logout'); showLogin(); });

// ---- pages ----
function route() {
  const p = (location.hash || '#intercom').slice(1);
  S.page = ['intercom', 'channels', 'messages', 'contacts', 'aprs', 'sstv', 'pkt', 'pager', 'sat', 'scope', 'map', 'log', 'rec', 'trans', 'exam', 'settings', 'help'].includes(p) ? p : 'intercom';
  document.querySelectorAll('.page').forEach(el => el.classList.toggle('hidden', el.id !== 'page-' + S.page));
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('on', a.dataset.page === S.page));
  const on = document.querySelector('#nav a.on'); if (on && on.scrollIntoView) on.scrollIntoView({ inline: 'center', block: 'nearest' });   // narrow screens scroll the tabs
  if (S.page === 'channels') { loadChannels(); loadZonesEditor(); }
  if (S.page === 'aprs') loadAprs();
  if (S.page === 'sstv') loadSstv();
  if (S.page === 'sat') { renderSat(); loadPasses(); loadConditions(); }
  if (S.page === 'scope') loadScope();
  if (S.page === 'intercom') loadHeard();
  if (S.page === 'messages') { loadConversations(); loadForms(); loadWinlink(); }
  if (S.page === 'contacts') loadContacts();
  if (S.page === 'map') loadMap();
  if (S.page === 'help') loadHelp();
  if (S.page === 'log') { if (!S.qso) editQso(null); loadLog(); loadNet(); loadPota(); }
  if (S.page === 'rec') loadRecordings();
  if (S.page === 'trans') { loadTrans(); loadModels(); }
  if (S.page === 'exam') { loadExam(); loadTrainer(); }
  if (S.page === 'settings') { loadSettings(); loadBackups(); loadDiagLog(); loadSchedules(); loadDevice(); }
  if (S.page === 'pkt') { loadPacket(); loadKeyer(); loadFinder(); }
  if (S.page === 'pager') loadPager();
}
window.addEventListener('hashchange', route);

// ---- start / live events ----
async function start() {
  await I18N.ready; if (!I18N.started) { I18N.started = true; applyI18n(document); }   // the first render needs the text tables (and HTML built while they loaded)
  try { S.status = await api('status'); } catch (e) { return; }
  if (!I18N.fromBrowser && S.status.lang && S.status.lang !== I18N.lang) await loadI18n(S.status.lang);   // no browser language has a table: the app's
  api('phone').then(renderPhone).catch(() => { });
  $('login').classList.add('hidden'); $('app').classList.remove('hidden');
  renderStatus(); loadHistory(); route(); connect();
}
function connect() {
  if (S.es) S.es.close();
  const es = new EventSource('/api/events'); S.es = es;
  es.onopen = () => { setConn(true); refresh(); };
  es.onerror = () => { setConn(false); fetch('/api/status').then(r => { if (r.status === 401) showLogin(); }).catch(() => {}); };
  const on = (t, f) => es.addEventListener(t, ev => { try { f(JSON.parse(ev.data)); } catch (e) { console.warn(t, e); } });
  on('channel', ch => { S.status.channel = ch; renderStatus(); loadHistory(); if (S.page === 'channels') renderChannels(); });
  on('stockImport', () => askStockImport());
  on('channels', () => {   // changed on the phone (or another browser): reload; a selection ends, since numbers may have moved
    loadZones();
    if (S.page !== 'channels') return;
    if (S.chSel) { setChSelecting(false); toast(t('channels_changed_on_phone')); }
    loadChannels(); loadZonesEditor();
  });
  on('rx', d => { S.status.hearing = d.on; renderState(); });
  on('tx', d => { S.status.transmitting = d.on; renderState(); });
  on('rssi', d => { S.status.rssi = d.dbm; renderMeter(); });
  on('phone', d => renderPhone(d));
  on('scope', d => { if (S.page === 'scope') loadScope(); });
  on('scopeState', d => { if (S.page === 'scope') loadScope(); });
  on('spectrum', d => { S.specTarget = d.b; if (!S.specRaf) S.specRaf = requestAnimationFrame(drawSpectrum); });
  on('level', d => { $('vuFill').style.width = Math.min(100, Math.round(Math.sqrt(d.amp / 32767) * 100)) + '%'; clearTimeout(S.vuT); S.vuT = setTimeout(() => { $('vuFill').style.width = '0'; }, 400); });
  on('history', d => addHistory(d.line));
  on('caller', d => { S.status.caller = d.text; renderState(); });
  on('link', d => { S.status.link = d.state; S.status.ready = d.state === 'ready'; renderStatus(); });
  on('modes', d => { Object.assign(S.status, { softSq: d.softSq, softSqLevel: d.softSqLevel, monitor: d.monitor, record: d.record, scanning: d.scanning, zone: d.zone }); renderStatus(); });
  on('caption', d => renderCaption(d));
  loadCaptions();
  on('sms', () => { if (S.page === 'messages') loadConversations(); });
  on('net', () => { if (S.page === 'log') loadNet(); });
  on('smsResult', d => { toast(d.ok ? t('messages_text_delivered') : t('messages_no_ack')); if (S.page === 'messages') loadConversations(); });
  on('sat', d => { S.status.sat = d; renderModebar(); if (S.page === 'sat') renderSat(); });
  on('mode', d => { S.status.mode = d; renderModebar(); renderAprsState(); renderSstvState(); });
  on('aprs', () => { if (S.page === 'aprs') loadAprs(); });
  on('sstv', d => { const old = S.status.sstv || {}; S.status.sstv = d; renderSstvState(); if (S.page === 'sstv' && (d.stamp !== old.stamp || d.on)) refreshLive(); if (S.page === 'sstv' && ((!d.on && old.on) || (old.gallery !== undefined && d.gallery !== old.gallery))) loadSstv(); });
}
async function refresh() { try { S.status = await api('status'); renderStatus(); if (S.status.stockImport) askStockImport(); } catch (e) { } }
// No channels at take-over: the phone asks whether to import the stock app's; so does an open page (the first answer counts)
async function askStockImport() {
  if (S.stockAsking) return; S.stockAsking = true;
  try {
    const yes = confirm(t('channels_stock_import_ask'));
    const j = await act('channels/stockimport', { import: yes });
    if (j) { loadZones(); if (S.page === 'channels') loadChannels(); }
  } finally { S.stockAsking = false; }
}
function setConn(ok) { $('connDot').className = 'dot ' + (ok ? 'ok' : 'bad'); $('connText').textContent = ok ? t('top_live') : t('top_reconnecting'); }

// ---- intercom ----
/** What this radio can do (status.radio): an analog-only radio hides the DMR parts, and Messages / Contacts while they have nothing to carry. */
function applyRadio(rd) {
  rd = rd || { dmr: true, messages: true, contacts: true };
  S.dmr = rd.dmr !== false;
  document.body.classList.toggle('no-dmr', !S.dmr);
  document.body.classList.toggle('no-rssi', rd.rssi === false);               // no signal readings: no band scope
  document.body.classList.toggle('no-aprs-terr', rd.aprsTerrestrial === false);   // the region's APRS frequency out of band: no APRS monitoring
  S.aprsMhz = rd.aprsHz ? (rd.aprsHz / 1e6).toFixed(3) : '144.390';                // the region's APRS frequency (Device → Region on the phone)
  const sfa = document.querySelector('#sfAction option[value="aprs"]'); if (sfa) sfa.textContent = t('settings_aprs_monitoring_freq', { mhz: S.aprsMhz });
  if ($('aprsToggle') && !(S.status && S.status.mode && S.status.mode.aprs)) $('aprsToggle').textContent = t('aprs_monitor_freq', { mhz: S.aprsMhz });
  S.sstv144 = rd.sstv144 !== false;                                            // 144.500 out of band: SSTV runs on the channel the radio is on
  document.body.classList.toggle('no-wx', rd.weather === false);              // no 162 MHz weather channels
  const nav = p => document.querySelector('a[data-page="' + p + '"]');
  if (nav('messages')) nav('messages').classList.toggle('hidden', rd.messages === false);
  if (nav('contacts')) nav('contacts').classList.toggle('hidden', rd.contacts === false);
  if (nav('pager')) nav('pager').classList.toggle('hidden', rd.symbolEngine === false);   // pages go out and in through the bench firmware's symbol engine only
  if ((S.page === 'messages' && rd.messages === false) || (S.page === 'contacts' && rd.contacts === false) || (S.page === 'pager' && rd.symbolEngine === false)) location.hash = '#intercom';
  if (!S.dmr && S.chFilter >= 2) { S.chFilter = 0; $('chFilter').querySelectorAll('button').forEach(x => x.classList.toggle('on', x.dataset.f === '0')); }
}
function renderStatus() {
  const s = S.status; if (!s) return; const ch = s.channel || {};
  applyRadio(s.radio);
  $('chNum').textContent = s.vfo ? 'VFO' /* i18n: mode name */ : (ch.number != null ? String(ch.number).padStart(2, '0') : '--');
  $('chName').textContent = ch.name || (ch.digital ? t('common_digital') : t('common_analog'));
  $('freq').textContent = ch.rxFreq ? mhz(ch.rxFreq) : '---.----';
  $('chType').textContent = ch.rxFreq ? (ch.digital ? 'DMR · CC' + ch.cc + ' · TS' + ((ch.slot || 0) + 1) : 'FM') : '';   // i18n: mode name
  $('tone').textContent = ch.rxFreq && ch.txFreq && ch.txFreq !== ch.rxFreq ? 'TX ' + mhz(ch.txFreq) : '';
  const link = $('link'); link.textContent = s.ready ? t('intercom_radio_ready') : (s.link || '').toUpperCase(); link.className = 'chip ' + (s.ready ? 'green' : 'amber');
  $('scanBtn').classList.toggle('on', !!s.scanning); $('monBtn').classList.toggle('on', !!s.monitor); $('recBtn').classList.toggle('on', !!s.record); $('sqBtn').classList.toggle('on', !!s.softSq); $('vfoBtn').classList.toggle('on', !!s.vfo);
  if (document.activeElement !== $('sqLevel')) $('sqLevel').value = s.softSqLevel; $('sqVal').textContent = s.softSqLevel;
  const z = $('zoneSel'); if (z.dataset.loaded !== '1') loadZones(); else z.value = String(s.zoneId);
  const cz = $('chZone'); if (cz && cz.options.length && cz.value !== String(s.zoneId)) { cz.value = String(s.zoneId); if (S.page === 'channels') loadChannels(); }   // changed elsewhere (phone, intercom)
  const ptt = $('ptt'); const canTx = s.webTx && s.ready && !s.txInhibited && !s.firmwareLoad && !(s.sat && s.sat.tracking && s.sat.listenOnly && !s.sat.waiting);
  ptt.disabled = !canTx;
  const secureUrl = 'https://' + location.hostname + ':' + (s.tlsPort || 8443) + '/';
  $('pttNote').innerHTML = s.firmwareLoad ? t('intercom_ptt_firmware') : !s.webTx ? t('intercom_ptt_web_tx_off') : s.txInhibited ? t('intercom_ptt_rx_only')
    : TALK.usable() ? t('intercom_ptt_browser_mic')
    : s.tls ? t('intercom_ptt_phone_mic_https', { url: secureUrl, address: esc(secureUrl) }) : t('intercom_ptt_phone_mic');
  renderState(); renderMeter(); renderModebar(); renderAprsState(); renderSstvState(); renderSendState();
}
function renderState() {
  const s = S.status; const b = $('rxtx');
  if (s.firmwareLoad) { b.textContent = t('intercom_loading_firmware'); b.className = 'badge tx'; } else if (s.transmitting) { b.textContent = 'TX'; b.className = 'badge tx'; } else if (s.hearing) { b.textContent = 'RX'; b.className = 'badge rx'; } else { b.textContent = t('intercom_idle'); b.className = 'badge'; }
  $('caller').textContent = s.hearing || s.transmitting ? (s.caller || '') : '';
}
function renderMeter() {
  const d = S.status.rssi; const ok = d != null && d > -200;
  const pct = ok ? Math.max(0, Math.min(100, (d + 125) / 75 * 100)) : 0;
  $('smeterFill').style.width = pct + '%';
  if (!ok) { $('smeterText').textContent = t('intercom_dbm'); return; }
  const sU = d >= -73 ? 'S9+' + Math.round(d + 73) : 'S' + Math.max(0, Math.min(9, Math.round(9 - (-73 - d) / 6)));
  $('smeterText').textContent = sU + '  ' + d + ' dBm';   // i18n: unit
}
async function loadHistory() { try { const j = await api('history?limit=150'); S.history = j.lines; renderHistory(); } catch (e) { } }
function renderHistory() { const h = $('history'); h.innerHTML = S.history.slice().reverse().map(l => '<div>' + esc(l) + '</div>').join('') || '<div class="empty">' + t('intercom_history_empty') + '</div>'; }
function addHistory(line) { if (S.history[S.history.length - 1] === line) return; S.history.push(line); if (S.history.length > 300) S.history.shift(); renderHistory(); }
function renderCaption(d) {
  $('captionState').textContent = d.enabled ? t('intercom_captions_on') : '';
  setTxt(d.enabled);
  if (d.sentence) addCaptionLine({ ts: Date.now(), channelName: (S.status.channel || {}).name || '', text: d.sentence.replace(/^\u{1F4DD}\s*/u, '') });
  const c = $('caption'); if (d.enabled && d.live) { c.textContent = d.live; c.classList.remove('hidden'); } else c.classList.add('hidden');
}
async function loadZones() {
  try {
    const j = await api('channels'); S.channels = j.channels; S.zones = j.zones;
    const z = $('zoneSel'); z.innerHTML = j.zones.map(x => '<option value="' + x.id + '">' + t('common_zone_option', { name: esc(x.name) }) + '</option>').join(''); z.value = String(j.zoneId); z.dataset.loaded = '1';
  } catch (e) { }
}
$('chUp').onclick = () => act('channel/step', { delta: 1 });
$('chDown').onclick = () => act('channel/step', { delta: -1 });
$('scanBtn').onclick = () => act('scan', {});
$('monBtn').onclick = () => act('monitor', { on: !S.status.monitor });
$('recBtn').onclick = () => act('record', { on: !S.status.record });
$('sqBtn').onclick = () => act('softsq', { on: !S.status.softSq });
$('sqLevel').oninput = () => { $('sqVal').textContent = $('sqLevel').value; };
$('sqLevel').onchange = () => act('softsq', { level: +$('sqLevel').value });
$('zoneSel').onchange = async () => { await act('zone', { id: +$('zoneSel').value }); loadZones(); if (S.page === 'channels') loadChannels(); };   // the Channels page has its own copy (chZone)

// PTT: hold the button (mouse/touch) or the space bar; always release on leave/blur so the radio never stays keyed
let pttDown = false;
async function ptt(down) {
  if (TALK.usable()) return talkPtt(down);                                // https: this computer's microphone (talk.js)
  if (down === pttDown) return; pttDown = down; $('ptt').classList.toggle('down', down);
  const j = await act('ptt', { down }); if (down && !j) { pttDown = false; $('ptt').classList.remove('down'); }
}
const pb = $('ptt');
pb.addEventListener('pointerdown', e => { if (!pb.disabled) { pb.setPointerCapture(e.pointerId); ptt(true); } });
['pointerup', 'pointercancel', 'lostpointercapture'].forEach(t => pb.addEventListener(t, () => ptt(false)));
document.addEventListener('keydown', e => { if (e.code === 'Space' && !e.repeat && S.page === 'intercom' && !/INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName) && !pb.disabled) { e.preventDefault(); ptt(true); } });
document.addEventListener('keyup', e => { if (e.code === 'Space') ptt(false); });
window.addEventListener('blur', () => ptt(false));
document.addEventListener('visibilitychange', () => { if (document.hidden) ptt(false); });

// ---- mode bar ----
function renderModebar() {
  const s = S.status; const sat = s.sat || {}; const m = s.mode || {}; let tx = '', href = '#intercom';
  if (sat.tracking) {
    const what = sat.mode === 2 ? 'ISS SSTV' : sat.mode === 1 ? 'ISS APRS' : (sat.name || t('top_satellite'));   // i18n: mode name
    tx = sat.waiting ? t('top_sat_next_pass', { what, time: hhmm(sat.aos) }) : t('top_sat_listening', { what, mhz: mhz(sat.rxHz) }) + (sat.el != null ? '  ·  ' + Math.round(sat.el) + '°' : '');
    href = '#sat';
  }
  if (m.aprs) { tx = (tx ? tx + '   |   ' : '') + t('top_aprs_monitoring'); if (!sat.tracking) href = '#aprs'; }
  if (m.sstv) { tx = (tx ? tx + '   |   ' : '') + t('top_sstv_listening'); if (!sat.tracking) href = '#sstv'; }
  if (m.parrot) { tx = (tx ? tx + '   |   ' : '') + t('top_parrot'); }
  $('modebar').classList.toggle('hidden', !tx); $('modeText').textContent = tx; $('modeOpen').href = href;
}

// ---- channels ----
async function loadChannels() {
  try {
    const j = await api('channels'); S.channels = j.channels; S.zones = j.zones;
    const z = $('chZone'); z.innerHTML = j.zones.map(x => '<option value="' + x.id + '">' + t('common_zone_option', { name: esc(x.name) }) + '</option>').join(''); z.value = String(j.zoneId);
    renderChannels();
  } catch (e) { }
}
S.chFilter = 0;    // 0 all, 1 favourites, 2 analog, 3 DMR (as on the phone)
function renderChannels() {
  const q = $('chSearch').value.trim().toLowerCase(); const cur = (S.status.channel || {}).number; const f = S.chFilter;
  const rows = S.channels.filter(c => (!q || (c.name || '').toLowerCase().includes(q) || mhz(c.rxFreq).includes(q) || String(c.number) === q)
    && (f === 0 || (f === 1 && c.fav) || (f === 2 && !c.digital) || (f === 3 && c.digital)));
  S.chShown = rows.map(c => c.number);
  $('chList').innerHTML = rows.map(c => '<div class="item click' + (c.number === cur ? ' cur' : '') + (S.chSel && S.chSel.has(c.number) ? ' sel' : '') + (c.tunable === false ? ' untunable' : '') + '" data-n="' + c.number + '"' + (c.tunable === false ? ' title="' + t('channels_not_tunable_title') + '"' : '') + '><span class="n">' + c.number + '</span><div class="main"><div>' + esc(c.name || '—') + '</div><div class="sub mono">' + mhz(c.rxFreq) + ' MHz · ' + (c.digital ? 'DMR CC' + c.cc : 'FM') /* i18n: mode name */ + (c.tunable === false ? ' · <span class="chip dim">' + t('channels_not_tunable_chip') + '</span>' : '') + '</div></div>'
    + '<button type="button" class="star' + (c.fav ? ' on' : '') + '" data-star="' + c.number + '" aria-label="' + (c.fav ? t('channels_unfav_aria') : t('channels_fav_aria')) + '" title="' + (c.fav ? t('channels_unfav_title') : t('channels_fav_title')) + '">' + (c.fav ? '★' : '☆') + '</button>'
    + '<button type="button" class="act" data-edit="' + c.number + '">' + t('common_edit') + '</button></div>').join('') || '<div class="empty">' + (f === 1 ? t('channels_no_favourites') : t('channels_none')) + '</div>';
}
$('chZone').onchange = async () => { await act('zone', { id: +$('chZone').value }); loadZones(); loadChannels(); };
$('chFilter').onclick = e => { const b = e.target.closest('button'); if (!b) return; S.chFilter = +b.dataset.f; $('chFilter').querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b)); renderChannels(); };
$('chSearch').oninput = renderChannels;
$('chList').onclick = e => {
  if (S.chSel) { const it = e.target.closest('.item'); if (it) { const n = +it.dataset.n; if (S.chSel.has(n)) S.chSel.delete(n); else S.chSel.add(n); it.classList.toggle('sel', S.chSel.has(n)); chSelCount(); } return; }
  const ed = e.target.closest('[data-edit]'); if (ed) { openEditor(+ed.dataset.edit); return; }
  const st = e.target.closest('[data-star]'); if (st) { const n = +st.dataset.star, ch = S.channels.find(c => c.number === n); act('channel/star', { number: n, on: !(ch && ch.fav) }).then(() => { loadChannels(); loadZones(); if (S.page === 'channels') loadZonesEditor(); }); return; }
  const it = e.target.closest('.item'); if (it) act('channel/select', { number: +it.dataset.n });
};

// selecting several channels (as on the phone: long-press there, Select here): delete, star, add to a zone, take out of the zone shown
S.chSel = null;
function chSelCount() {
  const n = S.chSel ? S.chSel.size : 0; $('chSelCount').textContent = t.plural('common_selected', n);
  ['chSelStar', 'chSelZone', 'chSelOut', 'chSelDel'].forEach(id => { $(id).disabled = n === 0; });
  $('chSelOut').classList.toggle('hidden', !(+$('chZone').value > 0));
}
function setChSelecting(on) {
  S.chSel = on ? new Set() : null;
  $('chList').classList.toggle('selecting', on); $('chSelBar').classList.toggle('hidden', !on); $('chSelect').classList.toggle('hidden', on);
  $('chSelZoneTo').innerHTML = (S.zones || []).filter(z => z.id > 0 && z.name !== '\u2605 Favourites').map(z => '<option value="' + z.id + '">' + esc(z.name) + '</option>').join('') + '<option value="new">' + t('channels_new_zone_option') + '</option>';
  renderChannels(); chSelCount();
}
async function chBulk(action, extra) {
  const j = await act('channels/bulk', Object.assign({ action, numbers: [...S.chSel] }, extra || {}));
  if (j) { setChSelecting(false); await loadChannels(); loadZones(); if (S.page === 'channels' && typeof loadZonesEditor === 'function') loadZonesEditor(); }
}
$('chSelect').onclick = () => setChSelecting(true);
$('chSelDone').onclick = () => setChSelecting(false);
$('chSelAll').onclick = () => { const shown = S.chShown || [], every = shown.length && shown.every(n => S.chSel.has(n)); shown.forEach(n => every ? S.chSel.delete(n) : S.chSel.add(n)); renderChannels(); chSelCount(); };
$('chSelStar').onclick = () => { const allFav = [...S.chSel].every(n => (S.channels.find(c => c.number === n) || {}).fav); chBulk(allFav ? 'unstar' : 'star'); };
$('chSelZone').onclick = () => {
  const v = $('chSelZoneTo').value;
  if (v === 'new') { const name = prompt(t('channels_new_zone_prompt')); if (name && name.trim()) chBulk('zone', { zoneId: 0, zoneName: name.trim() }); }
  else chBulk('zone', { zoneId: +v });
};
$('chSelOut').onclick = () => { if (confirm(t.plural('channels_unzone_ask', S.chSel.size))) chBulk('unzone', { zoneId: +$('chZone').value }); };
$('chSelDel').onclick = () => { if (confirm(t.plural('channels_delete_ask', S.chSel.size))) chBulk('delete'); };

// ---- APRS ----
async function loadAprs() {
  try {
    const [l, st] = await Promise.all([api('aprs/log?limit=200'), api('aprs/stations?limit=200')]);
    S.aprsLog = l.log; S.stations = st.stations; renderAprs();
  } catch (e) { }
}
function renderAprs() {
  const heard = e => !/-tx$/.test(e.kind) && e.kind !== 'beacon' && !/^is-/.test(e.kind);
  const forYou = S.aprsLog.filter(e => e.mine && e.kind === 'msg').length;
  $('aprsCount').textContent = S.aprsLog.filter(heard).length; $('stationCount').textContent = S.stations.length;
  $('forYou').textContent = forYou; $('forYou').style.color = forYou ? 'var(--amber)' : '';
  $('aprsLog').innerHTML = S.aprsLog.map(e => '<div class="item' + (e.mine ? ' cur' : '') + '"><span class="t">' + hhmmss(e.ts) + '</span><div class="main"><span class="' + (heard(e) ? 'pk-from' : 'pk-mine') + '">' + esc(e.from) + '</span> › ' + esc(e.to) + '<div class="sub">' + esc(e.text) + '</div></div></div>').join('') || '<div class="empty">' + t('aprs_no_packets') + '</div>';
  $('stations').innerHTML = S.stations.map(s => '<div class="item"><div class="main"><b class="pk-from">' + esc(s.call) + '</b>' + (s.lat != null ? ' <span class="sub mono">' + s.lat.toFixed(3) + ', ' + s.lon.toFixed(3) + '</span>' : '') + '<div class="sub">' + esc(s.comment) + (s.via ? t('aprs_via', { via: esc(s.via) }) : '') + '</div></div><span class="t">' + ago(s.lastTs) + ' · ' + s.count + '×</span></div>').join('') || '<div class="empty">' + t('aprs_no_stations') + '</div>';
}
function renderAprsState() {
  const m = (S.status && S.status.mode) || {}; const on = !!m.aprs;
  const c = $('aprsState'); c.textContent = on ? t('aprs_monitoring_chip') : t('common_off_chip'); c.className = 'chip ' + (on ? 'green' : 'dim');
  $('aprsToggle').textContent = on ? t('aprs_stop_monitoring') : t('aprs_monitor_freq', { mhz: S.aprsMhz || '144.390' });
  $('beaconBtn').disabled = $('msgForm').querySelector('button').disabled = !(S.status && S.status.webTx);
  $('msgNote').textContent = S.status && S.status.webTx ? '' : t('common_needs_web_tx');
}
$('aprsToggle').onclick = () => act('aprs/monitor', { on: !(S.status.mode || {}).aprs });
$('beaconBtn').onclick = () => act('aprs/beacon', {});
$('msgForm').addEventListener('submit', async e => { e.preventDefault(); const j = await act('aprs/message', { to: $('msgTo').value, text: $('msgText').value }); if (j) $('msgText').value = ''; });

// ---- SSTV ----
async function loadSstv() {
  refreshLive(); if (!S.decLoaded) { S.decLoaded = true; loadDecodeList(); }
  try {
    const j = await api('sstv/pictures'); S.pictures = j.pictures;
    $('gallery').innerHTML = j.pictures.map((p, i) => { const u = picUrl(p); return '<a href="' + u + '" data-i="' + i + '"><img loading="lazy" src="' + u + '" alt=""><span>' + new Date(p.ts).toLocaleString() + '</span></a>'; }).join('') || '<div class="empty">' + t('sstv_no_pictures') + '</div>';
    $('gallery').querySelectorAll('a[data-i]').forEach(a => {
      a.classList.toggle('sel', !!(S.sel && S.sel.has(j.pictures[+a.dataset.i].name)));
      a.onclick = e => { e.preventDefault(); if (S.sel) toggleSel(a); else openPic(+a.dataset.i); };
    });
    if (S.sel) { for (const n of [...S.sel]) if (!j.pictures.some(p => p.name === n)) S.sel.delete(n); selCount(); }
  } catch (e) { }
}
// decode a recording (SSTV page): the list, start, progress
async function loadDecodeList() {
  try {
    const j = await api('decode/recordings');
    $('decList').innerHTML = j.recordings.map((r, i) => '<div class="item"><div class="main"><b>' + (r.pass ? t('sstv_pass_recording') : esc(r.name)) + '</b><div class="sub">' + esc(r.folder) + ' · ' + new Date(r.ts).toLocaleString() + ' · ' + (r.seconds >= 60 ? t('sstv_duration_min', { min: Math.floor(r.seconds / 60), s: r.seconds % 60 }) : t('sstv_duration_s', { s: r.seconds })) + '</div></div><button class="btn small-btn" data-r="' + i + '">' + t('sstv_decode') + '</button></div>').join('') || '<div class="empty">' + t('sstv_no_recordings') + '</div>';
    $('decList').querySelectorAll('button[data-r]').forEach(bt => bt.onclick = async () => {
      const r = j.recordings[+bt.dataset.r];
      try { await api('decode', { folder: r.folder, name: r.name, mode: $('decMode').value }); pollDecode(); } catch (e) { toast(e.message); }
    });
  } catch (e) { }
  pollDecode();
}
async function pollDecode() {
  clearTimeout(S.decT);
  let j; try { j = (await api('decode')).job; } catch (e) { return; }
  if (!j) { $('decJob').classList.add('hidden'); return; }
  $('decJob').classList.remove('hidden');
  $('decName').textContent = j.file; $('decPct').textContent = j.percent + ' %'; $('decBar').style.width = j.percent + '%'; $('decBar').className = j.mode === 'aprs' ? '' : 'chg';   // i18n: unit
  $('decState').textContent = j.error ? t('sstv_decode_error', { error: j.error }) : j.done ? j.summary : (j.stage ? j.stage.charAt(0).toUpperCase() + j.stage.slice(1) + '…' : t('sstv_decode_starting'));
  $('decOut').textContent = j.mode === 'aprs' ? (j.packetCount > j.packets.length ? t('sstv_decode_last', { n: j.packets.length, total: j.packetCount }) + '\n' : '') + j.packets.join('\n') : j.pictures.join('\n');
  $('decStop').classList.toggle('hidden', j.done);
  if (!j.done) S.decT = setTimeout(pollDecode, 1000);
  else if (j.mode === 'sstv' && j.pictures.length && S.decShown !== j.started) { S.decShown = j.started; loadSstv(); }
}
$('decRefresh').onclick = loadDecodeList;
$('decUpload').onclick = () => $('decFile').click();
$('decFile').onchange = async () => {
  const f = $('decFile').files[0]; $('decFile').value = ''; if (!f) return;
  if (f.size > 200 * 1024 * 1024) { toast(t('sstv_upload_too_big')); return; }
  toast(t('sstv_uploading', { name: f.name }));
  try {
    const r = await fetch('/api/decode/upload?name=' + encodeURIComponent(f.name) + '&mode=' + $('decMode').value, { method: 'POST', headers: { 'Content-Type': f.type || 'application/octet-stream' }, body: f });
    const j = await r.json().catch(() => ({})); if (!r.ok) throw new Error(j.error || j.message || ('HTTP ' + r.status));   // i18n: protocol
    toast(j.message || t('sstv_uploaded')); loadDecodeList();
  } catch (e) { toast(e.message); }
};
$('decStop').onclick = async () => { try { await api('decode/stop', {}); } catch (e) { } };

// confirmation card (deleting); resolves true on the red button
function ask(text, yes) {
  return new Promise(res => {
    $('askText').textContent = text; $('askYes').textContent = yes || t('common_delete'); $('askDlg').classList.remove('hidden'); $('askNo').focus();
    const done = v => { $('askDlg').classList.add('hidden'); document.removeEventListener('keydown', key, true); res(v); };
    const key = e => { if (e.key === 'Escape') { e.stopPropagation(); done(false); } };
    document.addEventListener('keydown', key, true);
    $('askYes').onclick = () => done(true); $('askNo').onclick = () => done(false);
    $('askDlg').onclick = e => { if (e.target === $('askDlg')) done(false); };
  });
}
async function deletePics(names) {
  const j = await api('sstv/delete', { names });
  toast(t.plural('sstv_pictures_deleted', j.deleted)); return j.deleted;
}
async function sharePics(list) {
  try {
    const files = await Promise.all(list.map(async p => { const b = await (await fetch(picUrl(p))).blob(); return new File([b], p.name, { type: b.type || 'image/png' }); }));
    if (navigator.canShare && navigator.canShare({ files })) { await navigator.share({ files, title: t.plural('sstv_share_title', files.length), text: t.plural('sstv_share_text', files.length) }); return; }
  } catch (e) { if (e && e.name === 'AbortError') return; }
  toast(window.isSecureContext ? t('sstv_share_unsupported') : t('sstv_share_needs_https'));
}
// selecting several pictures (Select on the gallery)
function selCount() { const n = S.sel ? S.sel.size : 0; $('selCount').textContent = t.plural('common_selected', n); $('selShare').disabled = $('selDel').disabled = n === 0; $('selComb').disabled = n < 2; }
function toggleSel(a) { const n = S.pictures[+a.dataset.i].name; if (S.sel.has(n)) S.sel.delete(n); else S.sel.add(n); a.classList.toggle('sel', S.sel.has(n)); selCount(); }
function setSelecting(on) {
  S.sel = on ? new Set() : null; $('gallery').classList.toggle('selecting', on); $('selBar').classList.toggle('hidden', !on); $('picSelect').classList.toggle('hidden', on);
  $('gallery').querySelectorAll('a.sel').forEach(a => a.classList.remove('sel')); selCount();
}
$('picSelect').onclick = () => setSelecting(true);
$('selDone').onclick = () => setSelecting(false);
$('selAll').onclick = () => { const all = (S.pictures || []).length && S.sel.size === S.pictures.length; S.sel = new Set(all ? [] : (S.pictures || []).map(p => p.name)); $('gallery').querySelectorAll('a[data-i]').forEach(a => a.classList.toggle('sel', S.sel.has(S.pictures[+a.dataset.i].name))); selCount(); };
$('selShare').onclick = () => sharePics((S.pictures || []).filter(p => S.sel.has(p.name)));
// combining: an automatic line-up first, then the user nudges each picture (partial pictures sit at the bottom, so the
// row shift is unknown and a noisy strip can fool the automatic match), with a preview from the phone after every change
const C = {};
function combShow() {
  const i = +$('combSel').value; $('combUse').checked = C.use[i]; $('combUse').disabled = i === C.ref;
  ['combUp', 'combDown', 'combLeft', 'combRight'].forEach(id => $(id).disabled = i === C.ref || !C.use[i]);
  $('combShift').textContent = i === C.ref ? t('sstv_comb_reference') : (C.use[i] ? t('sstv_comb_shift', { vertical: C.dy[i] < 0 ? t('sstv_comb_up', { n: -C.dy[i] }) : t('sstv_comb_down', { n: C.dy[i] }), horizontal: C.dx[i] > 0 ? t('sstv_comb_left', { n: C.dx[i] }) : t('sstv_comb_right', { n: -C.dx[i] }) }) : t('sstv_comb_left_out'));
  const unsure = C.use.map((u, k) => u && !C.sure[k] ? k + 1 : 0).filter(Boolean);
  $('combNote').textContent = t('sstv_comb_note', { only: C.use.filter(Boolean).length < 2 ? t('sstv_comb_only_one') : '',
    how: C.manual ? t('sstv_comb_by_hand') : unsure.length ? t('sstv_comb_unsure', { list: unsure.join(', ') }) : t('sstv_comb_auto') });
  $('combSave').disabled = C.use.filter(Boolean).length < 2;
}
async function combRun(manual) {
  const body = { names: C.names }; if (manual) { body.dy = C.dy; body.dx = C.dx; body.use = C.use; }
  try {
    const j = await api('sstv/combine', body);
    Object.assign(C, { ref: j.ref, dy: j.dy, dx: j.dx, use: j.use, manual }); if (!manual) C.sure = j.sure;
    $('combImg').src = 'data:image/png;base64,' + j.png; combShow();
  } catch (e) { toast(e.message); }
}
let combTimer = 0;
function combNudge(ddy, ddx, e) { const i = +$('combSel').value, k = e && e.shiftKey ? 8 : 1; C.dy[i] += ddy * k; C.dx[i] += ddx * k; combShow(); clearTimeout(combTimer); combTimer = setTimeout(() => combRun(true), 150); }
$('combUp').onclick = e => combNudge(-1, 0, e); $('combDown').onclick = e => combNudge(1, 0, e);
$('combLeft').onclick = e => combNudge(0, 1, e); $('combRight').onclick = e => combNudge(0, -1, e);
$('combUse').onchange = () => { C.use[+$('combSel').value] = $('combUse').checked; combShow(); combRun(true); };
$('combSel').onchange = combShow;
$('combAuto').onclick = () => combRun(false);
const combClose = () => $('combDlg').classList.add('hidden');
$('combNo').onclick = $('combX').onclick = combClose;
$('combSave').onclick = async () => {
  try { const j = await api('sstv/combine', { names: C.names, dy: C.dy, dx: C.dx, use: C.use, save: true }); toast(j.message); combClose(); setSelecting(false); loadSstv(); } catch (e) { toast(e.message); }
};
$('selComb').onclick = async () => {
  const names = [...S.sel]; if (names.length < 2) return;
  C.names = names; C.sure = [];
  $('combSel').innerHTML = names.map((n, i) => '<option value="' + i + '">' + (i + 1) + ': ' + esc(n.replace(/^SSTV_/, '').replace(/\.png$/, '')) + '</option>').join('');
  $('combImg').removeAttribute('src'); $('combNote').textContent = t('sstv_comb_lining_up'); $('combDlg').classList.remove('hidden');
  await combRun(false); if (C.ref != null) { $('combSel').value = String(names.length > 1 ? (C.ref === 0 ? 1 : 0) : 0); combShow(); }
};
$('selDel').onclick = async () => {
  const names = [...S.sel]; if (!names.length) return;
  if (!await ask(t.plural('sstv_delete_ask', names.length))) return;
  try { await deletePics(names); setSelecting(false); loadSstv(); } catch (e) { toast(e.message); }
};

// the picture viewer: a card on the page (×, ‹ ›, keys, swipe), Share through the device's share menu, Download
const picUrl = p => '/api/sstv/picture?name=' + encodeURIComponent(p.name);
function openPic(i) {
  const L = S.pictures || []; if (!L.length) return;
  S.picIdx = (i + L.length) % L.length; const p = L[S.picIdx];
  $('picImg').src = picUrl(p); $('picImg').alt = t('sstv_picture_alt', { when: new Date(p.ts).toLocaleString() });
  $('picWhen').textContent = new Date(p.ts).toLocaleString();
  $('picCount').textContent = L.length > 1 ? (S.picIdx + 1) + ' / ' + L.length : '';
  $('picPrev').classList.toggle('hidden', L.length < 2); $('picNext').classList.toggle('hidden', L.length < 2);
  const dl = $('picDl'); dl.href = picUrl(p); dl.download = p.name;
  $('picDlg').classList.remove('hidden'); $('picX').focus();
}
const closePic = () => $('picDlg').classList.add('hidden');
const picOpen = () => !$('picDlg').classList.contains('hidden');
$('picX').onclick = closePic;
$('picPrev').onclick = () => openPic(S.picIdx - 1);
$('picNext').onclick = () => openPic(S.picIdx + 1);
$('picDlg').onclick = e => { if (e.target === $('picDlg')) closePic(); };
document.addEventListener('keydown', e => {
  if (!picOpen() || !$('askDlg').classList.contains('hidden')) return;
  if (e.key === 'Escape') closePic(); else if (e.key === 'ArrowLeft') openPic(S.picIdx - 1); else if (e.key === 'ArrowRight') openPic(S.picIdx + 1);
});
(() => { let x0 = null; const el = $('picImg');
  el.addEventListener('touchstart', e => { x0 = e.touches[0].clientX; }, { passive: true });
  el.addEventListener('touchend', e => { if (x0 == null) return; const dx = e.changedTouches[0].clientX - x0; x0 = null; if (Math.abs(dx) > 50) openPic(S.picIdx + (dx < 0 ? 1 : -1)); }, { passive: true });
})();
$('picDel').onclick = async () => {
  const p = (S.pictures || [])[S.picIdx]; if (!p) return;
  if (!await ask(t('sstv_delete_one_ask', { when: new Date(p.ts).toLocaleString() }))) return;
  try {
    await deletePics([p.name]);
    S.pictures.splice(S.picIdx, 1);
    if (S.pictures.length) openPic(Math.min(S.picIdx, S.pictures.length - 1)); else closePic();
    loadSstv();
  } catch (e) { toast(e.message); }
};
$('picShare').onclick = async () => {
  const p = (S.pictures || [])[S.picIdx]; if (!p) return;
  const when = new Date(p.ts).toLocaleString();
  try {
    const blob = await (await fetch(picUrl(p))).blob();
    const file = new File([blob], p.name, { type: blob.type || 'image/png' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) { await navigator.share({ files: [file], title: t.plural('sstv_share_title', 1), text: t('sstv_share_text_when', { when }) }); return; }
  } catch (e) { if (e && e.name === 'AbortError') return; }
  toast(window.isSecureContext ? t('sstv_share_unsupported') : t('sstv_share_needs_https'));
};

function refreshLive() {
  const s = S.status.sstv || {}; const img = $('sstvLive');
  if (!s.picture) { img.classList.add('hidden'); $('sstvEmpty').classList.remove('hidden'); return; }
  const n = new Image(); n.onload = () => { img.src = n.src; img.classList.remove('hidden'); $('sstvEmpty').classList.add('hidden'); }; n.src = '/api/sstv/live.png?t=' + Date.now();
}
function renderSstvState() {
  const s = (S.status && S.status.sstv) || {}; const c = $('sstvState');
  c.textContent = s.on ? (s.status || t('sstv_listening_chip')).toUpperCase() : t('common_off_chip'); c.className = 'chip ' + (s.on ? 'green' : 'dim');
  $('sstvToggle').textContent = s.on ? t('sstv_stop_listening') : t('sstv_listen_on_this_channel');
}
$('sstvToggle').onclick = () => act('sstv/monitor', { on: !(S.status.sstv || {}).on });

// ---- satellites ----
function renderSat() {
  const sat = (S.status && S.status.sat) || {}; const c = $('satCard');
  if (!sat.tracking) { c.innerHTML = '<div class="big">' + t('sat_not_tracking') + '</div><p class="muted">' + t('sat_choose_iss') + '</p>'; return; }
  const what = sat.mode === 2 ? 'ISS SSTV' : sat.mode === 1 ? 'ISS APRS' : esc(sat.name);   // i18n: mode name
  const f = (k, v) => '<div><span>' + k + '</span><b>' + v + '</b></div>';
  c.innerHTML = '<div class="big">' + (sat.waiting ? t('sat_waiting', { what }) : t('sat_listening', { what })) + '</div><div class="grid">'
    + (sat.waiting ? f(t('sat_next_pass'), hhmm(sat.aos)) + f(t('sat_ends'), hhmm(sat.los)) : f(t('sat_tuned'), mhz(sat.rxHz) + ' MHz') + f(t('sat_elevation'), sat.el != null ? Math.round(sat.el) + '°' : '—') + f(t('sat_azimuth'), sat.az != null ? Math.round(sat.az) + '°' : '—') + f(t('sat_range'), sat.rangeKm != null ? Math.round(sat.rangeKm) + ' km' : '—') + f(t('sat_los'), hhmm(sat.los)))
    + f(t('sat_highest'), Math.round(sat.maxEl) + '°') + f(t('sat_nominal'), mhz(sat.nominalHz) + ' MHz') + '</div>';
}
document.querySelectorAll('[data-iss]').forEach(b => b.onclick = () => act('iss', { mode: b.dataset.iss }).then(refresh));

// start() runs at the end of pages.js, once every page's code is loaded
