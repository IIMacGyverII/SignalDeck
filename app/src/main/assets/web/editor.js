/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
// SignalDeck Web: the channel editor (every field of the phone's editor, its checks run on the phone, its help texts).
'use strict';

const ED = { ch: null, isNew: false, help: {}, tones: { ctcss: [], dcs: [] } };

/** The VFO in the same editor: any frequency without saving a channel; Tune enters VFO mode. */
async function openVfo() {
  try {
    if (location.hash !== '#channels') { location.hash = 'channels'; await new Promise(r => setTimeout(r, 50)); }
    const j = await api('vfo');
    ED.vfo = true; ED.vfoActive = j.active; ED.step = j.step; ED.steps = j.steps;
    ED.ch = j.channel; ED.isNew = false; ED.help = j.help; ED.tones = j.tones;
    renderEditor();
  } catch (e) { toast(e.message); }
}
async function openEditor(number) {
  try {
    const j = await api('channel?number=' + (number || 0));
    ED.vfo = false;
    ED.ch = j.channel; ED.isNew = j.isNew; ED.help = j.help; ED.tones = j.tones; ED.nameMax = j.nameMax || 32;
    renderEditor();
    if (window.innerWidth < 900) $('edPanel').scrollIntoView({ behavior: 'smooth' });
  } catch (e) { toast(e.message); }
}

function renderEditor() {
  const c = ED.ch;
  if (S.dmr === false && ED.isNew && c.type === 0) c.type = 1;                  // an analog-only radio: a new channel is analog (before the form is built)
  const digital = c.type === 0, m17 = c.type === 2;
  $('edTitle').textContent = ED.vfo ? 'VFO' /* i18n: mode name */ : ED.isNew ? t('editor_new_channel') : t('editor_edit_channel');
  $('edSub').textContent = ED.vfo ? (ED.vfoActive ? t('common_on_chip') : t('common_off_chip')) : ED.isNew ? '' : t('editor_channel_n', { n: c.number });
  $('edEmpty').classList.add('hidden'); $('edForm').classList.remove('hidden');
  const mhzIn = hz => hz > 0 ? (hz / 1e6).toFixed(5) : '';
  const opt = (vals, cur) => vals.map(([v, t]) => '<option value="' + v + '"' + (String(v) === String(cur) ? ' selected' : '') + '>' + esc(t) + '</option>').join('');
  const range = (a, b) => Array.from({ length: b - a + 1 }, (_, i) => [a + i, String(a + i)]);
  const field = (key, label, input) => '<label class="f"><span>' + esc(label) + (ED.help[key] ? ' <button type="button" class="info" data-help="' + key + '" aria-label="' + t('editor_about_aria', { label: esc(label) }) + '">i</button>' : '') + '</span>' + input + '<div class="tip hidden" data-tip="' + key + '"></div></label>';
  const sel = (id, vals, cur) => '<select id="' + id + '">' + opt(vals, cur) + '</select>';
  const txt = (id, val, extra) => '<input id="' + id + '" value="' + esc(val) + '" ' + (extra || '') + '>';
  const toneVals = type => type === 1 ? ED.tones.ctcss.map((t, i) => [i, t]) : type === 2 || type === 3 ? ED.tones.dcs.map((t, i) => [i, t + (type === 3 ? 'I' : 'N')]) : [[0, '—']];
  let h = '';
  h += '<div class="sect">' + (ED.vfo ? t('editor_sect_frequency') : t('channels_channel')) + '</div>';
  if (!ED.vfo) h += field('name', t('editor_name'), txt('fName', (c.name || '').slice(0, ED.nameMax), 'maxlength="' + ED.nameMax + '"') + '<div class="count" id="fNameCount"></div>');
  if (S.dmr !== false || c.type !== 1) h += field('type', t('editor_type'), sel('fType', [[0, t('common_digital')], [1, t('common_analog')], [2, 'M17']], c.type));   // no choice there (an old DMR channel can still be changed)
  h += field('rx', t('editor_rx'), txt('fRx', mhzIn(c.rxFreq), 'inputmode="decimal"'));
  h += field('tx', t('editor_tx'), txt('fTx', mhzIn(c.txFreq), 'inputmode="decimal"'));
  h += field('power', t('editor_power'), sel('fPower', [[0, t('editor_power_low')], [1, t('editor_power_high')]], c.power));
  h += field('rxOnly', t('editor_transmit'), sel('fRxOnly', [[0, t('editor_tx_allowed')], [1, t('editor_rx_only')]], c.rxOnly ? 1 : 0));
  if (S.dmr !== false) h += field('relay', t('editor_relay'), sel('fRelay', [[1, t('editor_relay_disconnect')], [2, t('editor_relay_normal')]], c.relay === 1 ? 1 : 2));   // the DMR module's setting
  if (digital) {
    h += '<div class="sect">' + t('editor_sect_digital') + '</div>';
    h += field('cc', t('editor_cc'), sel('fCc', range(0, 15), c.cc));
    h += field('mode', t('editor_mode'), sel('fMode', [[0, t('editor_mode_direct')], [4, t('editor_mode_double')]], c.mode === 4 ? 4 : 0));
    h += field('slot', t('editor_slot'), sel('fSlot', [[0, 'TS1'], [1, 'TS2']], c.slot));
    h += field('contactType', t('editor_ctype'), sel('fCtype', [[0, t('editor_ctype_private')], [1, t('editor_ctype_group')], [2, t('editor_ctype_all')]], c.contactType));
    h += field('call', t('editor_call'), txt('fCall', c.txContact > 0 ? c.txContact : '', 'inputmode="numeric"'));
    h += field('groups', t('editor_groups'), txt('fGroups', (c.groups || []).join(', ')));
    h += field('encrypt', t('editor_encrypt'), sel('fEnc', [[2, t('editor_disabled')], [1, t('editor_enabled')]], c.encryptSw === 1 ? 1 : 2));
    h += field('key', t('editor_key'), txt('fKey', '', 'maxlength="8" placeholder="' + (c.hasKey ? t('editor_key_set') : t('editor_key_not_set')) + '"'));
    h += field('interrupt', t('editor_interrupt'), sel('fInt', [[2, t('common_off_chip')], [1, t('common_on_chip')]], c.interrupt === 1 ? 1 : 2));
  } else if (m17) {
    h += '<div class="sect">M17</div>';
    h += field('m17dest', t('editor_m17dest'), txt('fM17', c.m17dest || '@ALL', 'maxlength="9" autocapitalize="characters"'));
    h += field('can', t('editor_can'), sel('fCan', range(0, 15), c.cc || 0));
  } else {
    h += '<div class="sect">' + t('editor_sect_analog') + '</div>';
    h += field('band', t('editor_band'), sel('fBand', [[0, t('editor_band_narrow')], [1, t('editor_band_wide')]], c.band === 0 ? 0 : 1));
    h += field('softSq', t('editor_soft_sq'), sel('fSoft', [[0, t('common_off')], [1, t('common_on')]], c.softSq ? 1 : 0));
    h += field('softSqLevel', t('editor_soft_sq_level'), sel('fSoftLvl', range(1, 9), Math.max(1, Math.min(9, c.softSqLevel))));
    h += field('sq', t('editor_sq'), sel('fSq', range(0, 9), c.sq));
    h += field('txType', t('editor_tx_tone_type'), sel('fTxType', [[0, t('common_none')], [1, 'CTCSS'], [2, 'DCS'], [3, t('editor_dcs_inverted')]], c.txType));
    h += field('txTone', t('editor_tx_tone'), sel('fTxCode', toneVals(c.txType), c.txSubCode));
    h += field('rxType', t('editor_rx_tone_type'), sel('fRxType', [[0, t('common_none')], [1, 'CTCSS'], [2, 'DCS'], [3, t('editor_dcs_inverted')]], c.rxType));
    h += field('rxTone', t('editor_rx_tone'), sel('fRxCode', toneVals(c.rxType), c.rxSubCode));
  }
  if (ED.vfo) {
    h += field('step', t('editor_step'), sel('fStep', ED.steps.map(s => [s, (s / 1000) + ' kHz' /* i18n: unit */]), ED.step));
    h += '<div class="actions"><button class="btn primary">' + t('editor_tune') + '</button>' + (ED.vfoActive ? '<button type="button" class="btn" id="edExitVfo">' + t('editor_back_to_channel') + '</button>' : '') + '<button type="button" class="btn" id="edCancel">' + t('common_close') + '</button></div>';
    h += '<div class="note">' + t('editor_vfo_note') + '</div>';
  } else {
    h += '<div class="sect">' + t('editor_sect_location') + '</div>';
    h += field('pos', t('editor_lat'), txt('fLat', c.lat != null ? c.lat.toFixed(5) : '', 'inputmode="decimal"'));
    h += field('pos', t('editor_lon'), txt('fLon', c.lon != null ? c.lon.toFixed(5) : '', 'inputmode="decimal"'));
    h += '<div class="sect">' + t('editor_sect_notes') + '</div>';
    h += field('notes', t('editor_notes'), '<textarea id="fNotes" rows="3">' + esc(c.notes || '') + '</textarea>');
    h += '<div class="actions"><button class="btn primary">' + (ED.isNew ? t('editor_add_channel') : t('common_save')) + '</button><button type="button" class="btn" id="edCancel">' + t('common_close') + '</button>'
      + (ED.isNew ? '' : '<button type="button" class="btn" id="edTune">' + t('editor_tune') + '</button><button type="button" class="btn danger" id="edDelete">' + t('common_delete') + '</button>') + '</div>';
    h += '<div class="note">' + t('editor_save_note') + '</div>';
  }
  $('edForm').innerHTML = h;
  const nm = $('fName'); if (nm) { const cnt = () => { $('fNameCount').textContent = nm.value.length + ' / ' + ED.nameMax; $('fNameCount').classList.toggle('full', nm.value.length >= ED.nameMax); }; nm.oninput = cnt; cnt(); }

  // a change of type redraws the form (keeping what was typed); a tone type change refills its tone list
  if ($('fType')) $('fType').onchange = () => { Object.assign(ED.ch, collect(true)); ED.ch.type = +$('fType').value; renderEditor(); };
  const toneSwap = (typeId, codeId) => { const t = $(typeId); if (t) t.onchange = () => { $(codeId).innerHTML = opt(toneVals(+t.value), 0); }; };
  toneSwap('fTxType', 'fTxCode'); toneSwap('fRxType', 'fRxCode');
  $('edCancel').onclick = closeEditor;
  if (ED.vfo) { const x = $('edExitVfo'); if (x) x.onclick = async () => { if (await act('vfo/exit', {})) setTimeout(openVfo, 1500); }; return; }
  if (!ED.isNew) {
    $('edTune').onclick = () => act('channel/select', { number: ED.ch.number });
    $('edDelete').onclick = async () => {
      if (!confirm(ED.ch.name ? t('editor_delete_ask_named', { n: ED.ch.number, name: ED.ch.name }) : t('editor_delete_ask', { n: ED.ch.number }))) return;
      if (await act('channel/delete', { number: ED.ch.number })) { closeEditor(); loadChannels(); }
    };
  }
}

/** The form's values; keepOnly = just remember them in ED.ch (for a redraw after a type change). */
function collect(keepOnly) {
  const v = id => { const e = $(id); return e ? e.value : undefined; };
  const o = { number: ED.isNew ? 0 : ED.ch.number, name: v('fName'), type: $('fType') ? +v('fType') : ED.ch.type, txMHz: parseFloat(v('fTx')), rxMHz: parseFloat(v('fRx')),
    power: +v('fPower'), relay: $('fRelay') ? +v('fRelay') : (ED.ch.relay || 2), rxOnly: v('fRxOnly') === '1', lat: v('fLat'), lon: v('fLon') };
  if ($('fNotes')) o.notes = v('fNotes');
  if ($('fCc')) Object.assign(o, { cc: +v('fCc'), mode: +v('fMode'), slot: +v('fSlot'), contactType: +v('fCtype'), txContact: v('fCall'), groups: v('fGroups'), encryptSw: +v('fEnc'), interrupt: +v('fInt') });
  if ($('fKey') && v('fKey')) o.encryptKey = v('fKey');
  if ($('fBand')) Object.assign(o, { band: +v('fBand'), softSq: v('fSoft') === '1', softSqLevel: +v('fSoftLvl'), sq: +v('fSq'), txType: +v('fTxType'), txSubCode: +v('fTxCode'), rxType: +v('fRxType'), rxSubCode: +v('fRxCode') });
  if ($('fM17')) Object.assign(o, { m17dest: v('fM17'), cc: +v('fCan') });
  if (keepOnly) {   // map back onto the channel object's own field names
    return { name: o.name, txFreq: Math.round((o.txMHz || 0) * 1e6), rxFreq: Math.round((o.rxMHz || 0) * 1e6), power: o.power, relay: o.relay, rxOnly: o.rxOnly,
      lat: o.lat ? parseFloat(o.lat) : undefined, lon: o.lon ? parseFloat(o.lon) : undefined };
  }
  return o;
}

function closeEditor() { ED.ch = null; $('edForm').classList.add('hidden'); $('edForm').innerHTML = ''; $('edEmpty').classList.remove('hidden'); $('edTitle').textContent = t('channels_channel'); $('edSub').textContent = ''; }

$('edForm').addEventListener('submit', async e => {
  e.preventDefault();
  if (isNaN(parseFloat($('fTx').value)) || isNaN(parseFloat($('fRx').value))) { toast(t('editor_enter_frequencies')); return; }
  if (ED.vfo) { const o = collect(false); o.step = +$('fStep').value; if (await act('vfo', o)) setTimeout(openVfo, 1500); return; }
  const j = await act('channel/save', collect(false));
  if (j) { await loadChannels(); openEditor(j.number); }
});
$('edForm').addEventListener('click', e => {
  const b = e.target.closest('.info'); if (!b) return; e.preventDefault();
  const tip = b.closest('.f').querySelector('.tip'); const on = tip.classList.toggle('hidden') === false;
  tip.textContent = ED.help[b.dataset.help] || ''; b.classList.toggle('on', on);
});
$('chNew').onclick = () => openEditor(0);
$('chVfo').onclick = openVfo;
$('vfoBtn').onclick = openVfo;
