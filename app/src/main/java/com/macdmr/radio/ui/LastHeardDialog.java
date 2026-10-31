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
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Last heard: who and which channel had activity, newest first, with names from contacts and the RadioID list. Tap to switch. */
public final class LastHeardDialog {
    private static final class Row { int channel, dmrId, count; long ts; String kind, text, shown; }   // text: the stored English (what explain() matches); shown: the line in the app's language

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.intercom_lastheard_title), "intercom");
        TextView hint = Neo.label(c, Txt.s(R.string.intercom_lastheard_hint), 0xFF8A98A6, 12, false);
        hint.setPadding(0, (int) (4 * d), 0, (int) (8 * d)); body.addView(hint);
        LinearLayout tools = new LinearLayout(c); tools.setGravity(Gravity.CENTER);
        tools.setBaselineAligned(false);
        for (String[] t : new String[][] { { Txt.s(R.string.intercom_lastheard_recordings), "rec" }, { Txt.s(R.string.intercom_lastheard_captions), "txt" }, { Txt.s(R.string.intercom_lastheard_activity), "signal" } }) {
            TextView b = Neo.button(c, t[0], t[1], t[1].equals("rec") ? Neo.RED : t[1].equals("txt") ? Neo.VIOLET : Neo.CYAN); b.setTextSize(14);
            final String which = t[1];
            b.setOnClickListener(v -> { if (which.equals("rec")) MediaDialogs.recordings(c, svc); else if (which.equals("txt")) MediaDialogs.captions(c, svc); else ActivityDialog.show(c, svc); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lp.setMargins((int) (3 * d), (int) (6 * d), (int) (3 * d), (int) (10 * d)); tools.addView(b, lp);
        }
        body.addView(tools);

        final AlertDialog[] sheet = new AlertDialog[1];
        eventSections(c, svc, body, sheet);

        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_lastheard_section_heard)));
        LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
        for (Object[] h : svc.store().lastHeard(400)) {
            Row r = new Row(); r.ts = (Long) h[0]; r.channel = (Integer) h[1]; r.kind = (String) h[2]; r.text = (String) h[3]; r.dmrId = (Integer) h[4]; r.shown = (String) h[5];
            if (isAlert(r.text)) continue;   // a refusal is not something heard: it is listed under Alerts
            String key = r.channel + ":" + (r.kind.equals("dmr") || r.kind.equals("sms") ? r.dmrId : r.kind);
            Row e = rows.get(key);
            if (e == null) { r.count = 1; rows.put(key, r); } else e.count++;
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        if (rows.isEmpty()) body.addView(Neo.label(c, Txt.s(R.string.intercom_lastheard_empty), 0xFFBFC8D0, 14, false));
        List<Row> list = new ArrayList<>(rows.values());
        for (Row r : list) {
            ChannelStore.Channel ch = svc.store().byNumber(r.channel);
            String chName = ch != null ? ch.name : (r.channel == 0 ? "VFO" : Txt.s(R.string.intercom_channel_n, r.channel));
            String who = r.dmrId > 0 ? svc.callerName(r.dmrId) : "";
            String title = (r.dmrId > 0 ? (who.isEmpty() ? Txt.s(R.string.intercom_dmr_id, r.dmrId) : who + "  ·  " + r.dmrId) : r.shown.length() > 48 ? r.shown.substring(0, 48) + "…" : r.shown);
            LinearLayout rowv = new LinearLayout(c); rowv.setOrientation(LinearLayout.VERTICAL);
            rowv.setBackground(Neo.rowCard(c)); rowv.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
            TextView tl = Neo.label(c, title, r.dmrId > 0 ? Theme.AMBER : Theme.CYAN, 15, true); rowv.addView(tl);
            if (r.dmrId > 0) { final int did = r.dmrId; tl.setClickable(true); tl.setOnClickListener(v -> ContactEditor.open(c, svc, ContactEditor.Identity.dmr(did), null)); }
            rowv.addView(Neo.label(c, f.format(new Date(r.ts)) + "  ·  " + chName + (r.count > 1 ? "  ·  " + r.count + "×" : "") + (r.dmrId > 0 && !r.shown.isEmpty() ? "\n" + r.shown : ""), 0xFFBFC8D0, 12, false));
            final int chNum = r.channel;
            rowv.setClickable(true);
            body.addView(rowv, lp);
            AlertDialog[] holder = new AlertDialog[1];
            rowv.setOnClickListener(v -> { if (chNum > 0) { String e = svc.trySelectChannel(chNum); if (e != null) { android.widget.Toast.makeText(c, e, android.widget.Toast.LENGTH_LONG).show(); return; } if (holder[0] != null) holder[0].dismiss(); } });
            rowv.setTag(holder);
        }
        AlertDialog a = Neo.showSheet(c, body); sheet[0] = a;
        for (int i = 0; i < body.getChildCount(); i++) { Object t = body.getChildAt(i).getTag(); if (t instanceof AlertDialog[]) ((AlertDialog[]) t)[0] = a; }

    }

    // ---- Alerts and Events: warnings, refusals, car and coverage milestones, each with what it means ----
    private static final long WEEK = 7L * 24 * 3600 * 1000;
    static boolean isAlert(String t) { return t != null && (t.startsWith("⛔") || t.startsWith("⚠")); }

    private static void eventSections(Context c, RadioService svc, LinearLayout body, AlertDialog[] sheet) {
        float d = Theme.dp(c);
        List<Object[]> ev = svc.store().events(System.currentTimeMillis() - WEEK, 300);
        List<Row> alerts = new ArrayList<>(), events = new ArrayList<>();
        Row prevA = null, prevE = null;
        for (Object[] h : ev) {
            Row r = new Row(); r.ts = (Long) h[0]; r.channel = (Integer) h[1]; r.kind = (String) h[2]; r.text = (String) h[3]; r.shown = (String) h[5]; r.count = 1;
            boolean alert = isAlert(r.text) || "busy".equals(r.kind) || "alert".equals(r.kind) || "warn".equals(r.kind);
            Row prev = alert ? prevA : prevE;
            if (prev != null && prev.text.equals(r.text) && prev.channel == r.channel) { prev.count++; continue; }   // a run of the same line: one row, n×
            if (alert) { if (alerts.size() < 10) alerts.add(r); prevA = r; } else { if (events.size() < 15) events.add(r); prevE = r; }
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        if (!alerts.isEmpty()) body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_lastheard_section_alerts)));
        for (Row r : alerts) body.addView(eventRow(c, svc, r, f, true, sheet), rowLp(d));
        if (!events.isEmpty()) body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_lastheard_section_events)));
        for (Row r : events) body.addView(eventRow(c, svc, r, f, false, sheet), rowLp(d));
    }

    private static LinearLayout.LayoutParams rowLp(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d); return lp;
    }

    private static String chName(RadioService svc, int n) { ChannelStore.Channel ch = svc.store().byNumber(n); return ch != null ? ch.name : (n == 0 ? "VFO" : Txt.s(R.string.intercom_channel_n, n)); }

    private static LinearLayout eventRow(Context c, RadioService svc, Row r, SimpleDateFormat f, boolean alert, AlertDialog[] sheet) {
        float d = Theme.dp(c);
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(alert ? Neo.tint(c, Neo.AMBER, 12, 1) : Neo.rowCard(c)); v.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        v.addView(Neo.label(c, r.shown, alert ? Theme.AMBER : Theme.CYAN, 14, true));
        String when = f.format(new Date(r.ts));
        v.addView(Neo.label(c, r.count > 1 ? Txt.s(R.string.intercom_lastheard_event_sub_n, when, chName(svc, r.channel), r.count) : Txt.s(R.string.intercom_lastheard_event_sub, when, chName(svc, r.channel)), 0xFFBFC8D0, 12, false));
        v.setClickable(true);
        v.setOnClickListener(x -> details(c, svc, r, sheet));
        return v;
    }

    /** One alert or event: what it means, what to do, and what else happened around it. */
    private static void details(Context c, RadioService svc, Row r, AlertDialog[] sheet) {
        float d = Theme.dp(c);
        String[] ex = explain(r.text);
        LinearLayout box = Neo.sheetBody(c, ex[0], ex[2]);
        box.addView(Neo.label(c, r.shown, isAlert(r.text) ? Theme.AMBER : Theme.CYAN, 14.5f, true));
        ChannelStore.Channel ch = svc.store().byNumber(r.channel);
        String at = new SimpleDateFormat("EEE MMM d, HH:mm:ss", Locale.US).format(new Date(r.ts));
        String when = r.count > 1 ? Txt.s(R.string.intercom_lastheard_when_n, at, r.count) : at;
        String where = ch == null ? chName(svc, r.channel) : Txt.s(ch.power == 1 ? R.string.intercom_lastheard_where_high : R.string.intercom_lastheard_where_low, ch.name,
                String.format(Locale.US, "%.4f", ch.txFreq / 1e6), String.format(Locale.US, "%.4f", ch.rxFreq / 1e6), ch.isDigital() ? "DMR" : "FM");
        box.addView(Neo.sheetHint(c, when + "\n" + where));
        if (!ex[1].isEmpty()) { TextView t = Neo.label(c, ex[1], 0xFFD6DEE6, 13.5f, false); t.setLineSpacing(0, 1.12f); t.setPadding(0, (int) (6 * d), 0, (int) (4 * d)); box.addView(t); }
        List<Object[]> near = svc.store().around(r.ts, 20_000);
        if (near.size() > 1) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_lastheard_around)));
            SimpleDateFormat hf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            for (Object[] h : near) { long ts = (Long) h[0]; sb.append(ts == r.ts ? "▶ " : "   ").append(hf.format(new Date(ts))).append("  ").append((String) h[4]).append('\n'); }
            TextView t = Neo.label(c, sb.substring(0, sb.length() - 1), 0xFFBFC8D0, 12, false); t.setTypeface(Neo.mono(c)); box.addView(t);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.setMargins(0, (int) (10 * d), 0, 0);
        final AlertDialog[] self = new AlertDialog[1];
        if (r.text.contains("hiding SignalDeck")) {
            TextView open = Neo.button(c, Txt.s(R.string.intercom_lastheard_open_auto), "intercom", Neo.AMBER); box.addView(open, bl);
            open.setOnClickListener(x -> { try { c.startActivity(com.macdmr.radio.car.AutoTrust.settingsIntent(c)); } catch (Throwable t) { android.widget.Toast.makeText(c, Txt.s(R.string.intercom_lastheard_open_auto_failed, String.valueOf(t.getMessage())), android.widget.Toast.LENGTH_LONG).show(); } });
        }
        if (ch != null && (svc.current() == null || svc.current().number != ch.number)) {
            TextView tune = Neo.button(c, Txt.s(R.string.intercom_lastheard_tune, ch.name), "channels", Neo.CYAN); box.addView(tune, new LinearLayout.LayoutParams(bl));
            tune.setOnClickListener(x -> { String e = svc.trySelectChannel(ch.number); if (e != null) { android.widget.Toast.makeText(c, e, android.widget.Toast.LENGTH_LONG).show(); return; } if (self[0] != null) self[0].dismiss(); if (sheet[0] != null) sheet[0].dismiss(); });
        }
        self[0] = Neo.showSheet(c, box);
    }

    /** {title, explanation, sheet icon} for a history line, matched on its stored English wording (the lines come from RadioService, CoverageLogger and PacketEngine). The icon is the one Neo.iconFor gave the English title. */
    static String[] explain(String t) {
        if (t.contains("while transmitting") && (t.contains("Android Auto dropped") || t.contains("USB cable dropped")))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_usbdrop_title), Txt.s(R.string.intercom_lastheard_ex_usbdrop_text), "radio" };
        if (t.contains("did not load SignalDeck"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_notloaded_title), Txt.s(R.string.intercom_lastheard_ex_notloaded_text), "radio" };
        if (t.contains("hiding SignalDeck"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_hidden_title), Txt.s(R.string.intercom_lastheard_ex_hidden_text, com.macdmr.radio.car.AutoTrust.steps(com.macdmr.radio.car.AutoTrust.State.OFF)), "radio" };
        if (t.contains("took the sound away"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_sound_title), Txt.s(R.string.intercom_lastheard_ex_sound_text), "radio" };
        if (t.contains("Android Auto connected"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_connected_title), Txt.s(R.string.intercom_lastheard_ex_connected_text), "radio" };
        if (t.contains("Android Auto disconnected"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_disconnected_title), Txt.s(R.string.intercom_lastheard_ex_disconnected_text), "radio" };
        if (t.contains("Android Auto opened"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_opened_title), Txt.s(R.string.intercom_lastheard_ex_opened_text), "radio" };
        if (t.contains("No reply from"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_noreply_title), Txt.s(R.string.intercom_lastheard_ex_noreply_text), "repeaters" };
        if (t.contains("Keyed "))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_keyed_title), Txt.s(R.string.intercom_lastheard_ex_keyed_text), "repeaters" };
        if (t.contains("Coverage logging"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_coverage_title), Txt.s(R.string.intercom_lastheard_ex_coverage_text), "signal" };
        if (t.contains("receive-only"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_rxonly_title), Txt.s(R.string.intercom_lastheard_ex_rxonly_text), "radio" };
        if (t.contains("30 s transmit limit"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_toolong_title), Txt.s(R.string.intercom_lastheard_ex_toolong_text), "radio" };
        if (t.contains("Channel busy"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_busy_title), Txt.s(R.string.intercom_lastheard_ex_busy_text), "radio" };
        if (t.contains("Repeater ID ") && t.contains("Morse"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_repeaterid_title), Txt.s(R.string.intercom_lastheard_ex_repeaterid_text), "repeaters" };
        if (t.contains("Morse:"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_morse_title), Txt.s(R.string.intercom_lastheard_ex_morse_text), "radio" };
        if (t.contains("Keyword"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_keyword_title), Txt.s(R.string.intercom_lastheard_ex_keyword_text), "radio" };
        if (t.contains("Not sent"))
            return new String[] { Txt.s(R.string.intercom_lastheard_ex_notsent_title), Txt.s(R.string.intercom_lastheard_ex_notsent_text), "radio" };
        return new String[] { Txt.s(isAlert(t) ? R.string.intercom_lastheard_ex_alert : R.string.intercom_lastheard_ex_event), "", "radio" };
    }
}
