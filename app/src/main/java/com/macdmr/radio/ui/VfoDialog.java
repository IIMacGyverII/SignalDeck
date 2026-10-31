/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.dsp.ToneTable;
import com.macdmr.radio.i18n.Txt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VFO: tune any frequency without touching the channel list. Everything the radio can be told about a
 * channel is here (mode, frequencies with repeater-offset shortcuts, power, bandwidth, tones, DMR colour
 * code / slot / call target / RX groups / ID override) plus the step the intercom arrows use while VFO is
 * on, and "Save as channel" for when a tuned frequency turns out to be worth keeping.
 */
public final class VfoDialog {
    private static String[] toneTypes() { return new String[] { Txt.s(R.string.intercom_vfo_tone_none), "CTCSS", "DCS", Txt.s(R.string.intercom_vfo_tone_dcs_inverted) }; }
    private static String[] callTypes() { return new String[] { Txt.s(R.string.intercom_vfo_call_private), Txt.s(R.string.intercom_vfo_call_group), Txt.s(R.string.intercom_vfo_call_all) }; }
    private static final int[] STEPS = { 5000, 6250, 10000, 12500, 25000 };
    private static final String[] STEP_LABELS = { "5", "6.25", "10", "12.5", "25 kHz" };

    private final Context c; private final RadioService svc; private final ChannelStore.Channel v;
    private final float d;
    private EditText rx, tx, call, groups, idOverride;
    private TextView rxCode, txCode;
    private LinearLayout analog, digital;
    private final List<TextView> modePills = new ArrayList<>(), powerPills = new ArrayList<>(), bandPills = new ArrayList<>(), stepPills = new ArrayList<>(), slotPills = new ArrayList<>();
    private int step;
    private AlertDialog dlg;

    public static void show(Context c, RadioService svc) { new VfoDialog(c, svc).open(); }

    private VfoDialog(Context c, RadioService svc) { this.c = c; this.svc = svc; this.v = svc.vfoChannel(); this.step = svc.vfoStepHz(); d = Theme.dp(c); }

    private void open() {
        ScrollView sv = new ScrollView(c);
        LinearLayout body = Neo.sheetBody(c, "VFO", "vfo");   // i18n: mode name (VFO)
        sv.addView(body);
        body.addView(hint(Txt.s(R.string.intercom_vfo_hint)));

        // mode
        LinearLayout modeRow = pillRow(); modePills.add(pill(modeRow, Txt.s(R.string.intercom_vfo_mode_fm), v.type == 1, x -> { v.type = 1; select(modePills, 0); sections(); }));
        modePills.add(pill(modeRow, Txt.s(R.string.intercom_vfo_mode_dmr), v.type == 0, x -> { v.type = 0; select(modePills, 1); sections(); }));
        if (svc.profile().dmr()) body.addView(modeRow);                     // an analog-only radio: FM is the only mode

        // frequencies
        rx = freqField(body, Txt.s(R.string.intercom_vfo_rx_mhz), v.rxFreq); tx = freqField(body, Txt.s(R.string.intercom_vfo_tx_mhz), v.txFreq);
        body.addView(bandPlanLine(c, rx, tx));
        body.addView(hint(Txt.s(R.string.intercom_vfo_offset_hint)));
        LinearLayout off = pillRow();
        pill(off, Txt.s(R.string.intercom_vfo_simplex), false, x -> offset(0));
        if (svc.profile().tunable(146_000_000L)) { pill(off, "−0.600", false, x -> offset(-600_000)); pill(off, "+0.600", false, x -> offset(600_000)); }   // the 2 m repeater shift: only on a radio with the 136–174 MHz band
        pill(off, "−5.000", false, x -> offset(-5_000_000)); pill(off, "+5.000", false, x -> offset(5_000_000)); body.addView(off);
        for (int i = 0; i < off.getChildCount(); i++) ((TextView) off.getChildAt(i)).setTextSize(12);

        // step
        body.addView(section(Txt.s(R.string.intercom_vfo_arrow_step)));
        LinearLayout stepRow = pillRow();
        for (int i = 0; i < STEPS.length; i++) { final int k = i; stepPills.add(pill(stepRow, STEP_LABELS[i], step == STEPS[i], x -> { step = STEPS[k]; select(stepPills, k); })); }
        body.addView(stepRow);

        // power
        body.addView(section(Txt.s(R.string.intercom_vfo_power)));
        LinearLayout pw = pillRow(); powerPills.add(pill(pw, Txt.s(R.string.intercom_vfo_power_low), v.power == 0, x -> { v.power = 0; select(powerPills, 0); })); powerPills.add(pill(pw, Txt.s(R.string.intercom_vfo_power_high), v.power == 1, x -> { v.power = 1; select(powerPills, 1); })); body.addView(pw);

        // analog
        analog = new LinearLayout(c); analog.setOrientation(LinearLayout.VERTICAL);
        analog.addView(section(Txt.s(R.string.intercom_vfo_bandwidth)));
        LinearLayout bw = pillRow(); bandPills.add(pill(bw, Txt.s(R.string.intercom_vfo_band_narrow), v.band == 0, x -> { v.band = 0; select(bandPills, 0); })); bandPills.add(pill(bw, Txt.s(R.string.intercom_vfo_band_wide), v.band == 1, x -> { v.band = 1; select(bandPills, 1); })); analog.addView(bw);
        rxCode = toneRow(analog, Txt.s(R.string.intercom_vfo_rx_tone), false); txCode = toneRow(analog, Txt.s(R.string.intercom_vfo_tx_tone), true);
        body.addView(analog);

        // digital
        digital = new LinearLayout(c); digital.setOrientation(LinearLayout.VERTICAL);
        cycleRow(digital, Txt.s(R.string.intercom_vfo_colour_code), names(0, 15), v.cc, x -> v.cc = x);
        digital.addView(section(Txt.s(R.string.intercom_vfo_time_slot)));
        LinearLayout sl = pillRow(); slotPills.add(pill(sl, Txt.s(R.string.intercom_vfo_slot, 1), v.inSlot == 0, x -> { v.inSlot = v.outSlot = 0; select(slotPills, 0); })); slotPills.add(pill(sl, Txt.s(R.string.intercom_vfo_slot, 2), v.inSlot == 1, x -> { v.inSlot = v.outSlot = 1; select(slotPills, 1); })); digital.addView(sl);
        cycleRow(digital, Txt.s(R.string.intercom_vfo_call_type), callTypes(), v.contactType, x -> v.contactType = x);
        call = textField(digital, Txt.s(R.string.intercom_vfo_call_number), v.txContact > 0 ? String.valueOf(v.txContact) : "", InputType.TYPE_CLASS_NUMBER);
        groups = textField(digital, Txt.s(R.string.intercom_vfo_rx_groups), join(v.groups), InputType.TYPE_CLASS_TEXT);
        idOverride = textField(digital, Txt.s(R.string.intercom_vfo_id_override, String.valueOf(svc.localId())), v.mode > 0 ? String.valueOf(v.mode) : "", InputType.TYPE_CLASS_NUMBER);
        body.addView(digital);
        sections();

        // buttons
        LinearLayout btns = pillRow(); btns.setGravity(Gravity.CENTER);
        TextView tune = button(btns, Txt.s(R.string.intercom_vfo_tune), Theme.VFO), save = button(btns, Txt.s(R.string.intercom_vfo_save_as_channel), Theme.PKT), off2 = button(btns, Txt.s(svc.vfoActive() ? R.string.intercom_vfo_off : R.string.common_cancel), Theme.MON);
        body.addView(btns);
        tune.setOnClickListener(x -> { if (apply()) { svc.setVfoStepHz(step); svc.applyVfo(v); dlg.dismiss(); } });
        save.setOnClickListener(x -> { if (apply()) saveAsChannel(); });
        off2.setOnClickListener(x -> { if (svc.vfoActive()) svc.exitVfo(); dlg.dismiss(); });

        sv.removeView(body); dlg = Neo.showSheet(c, body);
    }

    private void sections() { analog.setVisibility(v.type == 1 ? View.VISIBLE : View.GONE); digital.setVisibility(v.type == 0 ? View.VISIBLE : View.GONE); }

    private void offset(int hz) {
        try { double r = Double.parseDouble(rx.getText().toString().trim()); tx.setText(String.format(Locale.US, "%.4f", r + hz / 1e6)); }
        catch (Exception e) { Toast.makeText(c, Txt.s(R.string.intercom_vfo_need_rx_toast), Toast.LENGTH_SHORT).show(); }
    }

    /** Validate and copy the text fields into the VFO channel. */
    private boolean apply() {
        try {
            v.rxFreq = (int) Math.round(Double.parseDouble(rx.getText().toString().trim()) * 1e6);
            v.txFreq = (int) Math.round(Double.parseDouble(tx.getText().toString().trim()) * 1e6);
            if (!com.macdmr.radio.ChannelStore.inBand(v.rxFreq) || !com.macdmr.radio.ChannelStore.inBand(v.txFreq)) throw new IllegalArgumentException(Txt.s(R.string.intercom_vfo_out_of_band_toast, com.macdmr.radio.DeviceProfile.detect().bandsText()));
            if (v.rxFreq < 300_000_000) v.band = 1;
            if (v.isDigital()) {
                String cn = call.getText().toString().trim();
                v.txContact = cn.isEmpty() ? 0 : Integer.parseInt(cn);
                if (v.contactType != 2 && v.txContact <= 0) throw new IllegalArgumentException(Txt.s(R.string.intercom_vfo_need_call_toast));
                List<Integer> g = new ArrayList<>();
                for (String s : groups.getText().toString().split("[,; ]+")) { s = s.trim(); if (!s.isEmpty()) g.add(Integer.parseInt(s)); }
                if (g.size() > 32) throw new IllegalArgumentException(Txt.s(R.string.intercom_vfo_too_many_groups_toast));
                v.groups = new int[g.size()]; for (int i = 0; i < g.size(); i++) v.groups[i] = g.get(i);
                String id = idOverride.getText().toString().trim();
                v.mode = id.isEmpty() ? 0 : Integer.parseInt(id);
            }
            v.name = "VFO " + String.format(Locale.US, "%.4f", v.rxFreq / 1e6) + (v.isDigital() ? " DMR" : " FM");   // i18n: mode name (the VFO channel's name)
            return true;
        } catch (NumberFormatException e) { Toast.makeText(c, Txt.s(R.string.intercom_vfo_check_numbers_toast), Toast.LENGTH_LONG).show(); }
        catch (IllegalArgumentException e) { Toast.makeText(c, e.getMessage(), Toast.LENGTH_LONG).show(); }
        return false;
    }

    private void saveAsChannel() {
        final EditText name = new EditText(c); name.setText(String.format(Locale.US, "%.4f", v.rxFreq / 1e6) + (v.isDigital() ? " DMR" : " FM")); name.setSelectAllOnFocus(true);   // i18n: mode name
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_vfo_save_as_channel)).setMessage(Txt.s(R.string.intercom_vfo_name_prompt)).setView(name)
                .setPositiveButton(Txt.s(R.string.common_save), (dd, w) -> {
                    ChannelStore.Channel ch = svc.vfoCopy(v); ch.id = 0; ch.number = 0; ch.name = name.getText().toString().trim();
                    if (ch.name.isEmpty()) ch.name = Txt.s(R.string.intercom_vfo_default_channel_name);
                    svc.saveChannel(ch, true);
                    Toast.makeText(c, Txt.s(R.string.intercom_vfo_saved_toast, ch.number), Toast.LENGTH_SHORT).show();
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static String[] names(int from, int to) { String[] s = new String[to - from + 1]; for (int i = from; i <= to; i++) s[i - from] = String.valueOf(i); return s; }
    private static String join(int[] a) { StringBuilder b = new StringBuilder(); for (int x : a) { if (b.length() > 0) b.append(", "); b.append(x); } return b.toString(); }
    private static String toneName(int type, int code) {
        if (type == 1) return ToneTable.ctcssLabel(code);
        if (type == 2 || type == 3) return "DCS " + ToneTable.toneLabel(type, code);   // i18n: mode name (DCS)
        return "—";
    }

    // ---- widgets --------------------------------------------------------------------------------------

    private interface IntSetter { void set(int x); }
    private int dp(float x) { return Math.round(x * d); }

    private TextView hint(String s) { return Neo.sheetHint(c, s); }
    /** A live band-plan line under the frequency fields: what RX and TX are used for, and any caution. */
    static TextView bandPlanLine(android.content.Context c, final EditText rx, final EditText tx) {
        final TextView t = Neo.sheetHint(c, "");
        final Runnable upd = () -> {
            long r = mhz(rx), x = mhz(tx);
            boolean hasR = r > 0, hasX = x > 0 && x != r;
            String line = hasR && hasX ? Txt.s(R.string.intercom_vfo_bandplan_both, com.macdmr.radio.ham.BandPlan.line(r), com.macdmr.radio.ham.BandPlan.line(x))
                    : hasR ? Txt.s(R.string.intercom_vfo_bandplan_rx, com.macdmr.radio.ham.BandPlan.line(r))
                    : hasX ? Txt.s(R.string.intercom_vfo_bandplan_tx, com.macdmr.radio.ham.BandPlan.line(x)) : "";
            com.macdmr.radio.ham.BandPlan.Info i = x > 0 ? com.macdmr.radio.ham.BandPlan.of(x) : null;
            t.setText(line);
            t.setTextColor(i != null && i.caution != null ? Neo.AMBER_LIGHT : Neo.MUTED);
        };
        android.text.TextWatcher w = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { } @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) { upd.run(); }
        };
        rx.addTextChangedListener(w); tx.addTextChangedListener(w); upd.run();
        return t;
    }
    private static long mhz(EditText e) { try { return Math.round(Double.parseDouble(e.getText().toString().trim()) * 1e6); } catch (Throwable x) { return 0; } }

    private TextView section(String s) { return Neo.sheetSection(c, s); }

    private LinearLayout pillRow() {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(2); lp.bottomMargin = dp(4); r.setLayoutParams(lp);
        return r;
    }

    private TextView pill(LinearLayout row, String text, boolean on, View.OnClickListener l) {
        TextView t = Neo.label(c, text, on ? Theme.VFO[3] : 0xFFBFC8D0, 13, true); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(8), dp(6), dp(8)); t.setClickable(true); t.setOnClickListener(l);
        style(t, on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(t, lp);
        return t;
    }
    private void style(TextView t, boolean on) { Neo.pillStyle(t, on, Neo.AMBER); }
    private void select(List<TextView> pills, int idx) { for (int i = 0; i < pills.size(); i++) style(pills.get(i), i == idx); }

    private TextView button(LinearLayout row, String text, int[] accent) { TextView b = Neo.button(c, text, null, Neo.accentOf(accent)); b.setTextSize(14); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins(dp(3), dp(12), dp(3), dp(4)); row.setBaselineAligned(false); row.addView(b, lp); return b; }

    private EditText freqField(LinearLayout target, String label, int hz) {
        return textField(target, label, hz > 0 ? String.format(Locale.US, "%.4f", hz / 1e6) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    private EditText textField(LinearLayout target, String label, String value, int inputType) {
        LinearLayout row = shell();
        row.addView(Neo.label(c, label, 0xFF9AA4AE, 12, false));
        EditText e = new EditText(c); e.setText(value); e.setInputType(inputType); e.setTextSize(18); e.setTextColor(Theme.WHITE); e.setSingleLine(true);
        e.setBackground(null); e.setPadding(0, dp(4), 0, dp(4));
        row.addView(e); target.addView(row);
        return e;
    }

    private TextView cycleRow(LinearLayout target, String label, final String[] options, int index, final IntSetter setter) {
        LinearLayout row = shell(); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Neo.label(c, label, 0xFFBFC8D0, 15, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = Neo.label(c, options[Math.max(0, Math.min(index, options.length - 1))], Theme.CYAN, 15, true);
        val.setBackground(Neo.outline(c, Neo.CYAN, 0x16, 12, 1)); val.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.addView(val);
        final int[] cur = { index };
        View.OnClickListener cl = x -> { cur[0] = (cur[0] + 1) % options.length; val.setText(options[cur[0]]); setter.set(cur[0]); };
        row.setClickable(true); row.setOnClickListener(cl); val.setOnClickListener(cl);
        target.addView(row);
        return val;
    }

    /** Tone type cycles; the code opens a list (both sides share the layout of the channel editor). */
    private TextView toneRow(LinearLayout target, String label, final boolean txSide) {
        LinearLayout row = shell(); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Neo.label(c, label, 0xFFBFC8D0, 15, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int type = txSide ? v.txType : v.rxType, code = txSide ? v.txSubCode : v.rxSubCode;
        final String[] toneTypes = toneTypes();
        final TextView typeV = Neo.label(c, toneTypes[Math.max(0, Math.min(type, 3))], Theme.CYAN, 14, true);
        typeV.setBackground(Neo.outline(c, Neo.CYAN, 0x16, 12, 1)); typeV.setPadding(dp(10), dp(6), dp(10), dp(6));
        final TextView codeV = Neo.label(c, toneName(type, code), Theme.VFO[3], 14, true);
        codeV.setBackground(Theme.rounded(c, Theme.VFO[0], Theme.VFO[2], 1, 14)); codeV.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.leftMargin = dp(6);
        row.addView(typeV); row.addView(codeV, lp);
        typeV.setOnClickListener(x -> {
            int t = ((txSide ? v.txType : v.rxType) + 1) % 3;
            if (txSide) { v.txType = t; v.txSubCode = 0; } else { v.rxType = t; v.rxSubCode = 0; }
            typeV.setText(toneTypes[t]); codeV.setText(toneName(t, 0));
        });
        codeV.setOnClickListener(x -> {
            int t = txSide ? v.txType : v.rxType; if (t == 0) return;
            final int n = t == 1 ? ToneTable.CTCSS.length : ToneTable.DCS_COUNT; final String[] items = new String[n]; for (int i = 0; i < n; i++) items[i] = toneName(t, i);
            new AlertDialog.Builder(c).setTitle(label).setItems(items, (dd, w) -> { if (txSide) v.txSubCode = w; else v.rxSubCode = w; codeV.setText(items[w]); }).show();
        });
        target.addView(row);
        return codeV;
    }

    private LinearLayout shell() {
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Neo.rowCard(c)); row.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }
}
