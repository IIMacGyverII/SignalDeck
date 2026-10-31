/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One channel's history: when it was last heard and what was heard on it (voice, DMR callers, texts, tones, Morse
 * repeater identifications and what they said, packet traffic, recordings, what you sent) plus its alerts, newest
 * first. The lines are the intercom's own history lines, filtered to this channel; a DMR caller is a tap target
 * for the contact.
 */
public final class ChannelHistoryDialog {
    private ChannelHistoryDialog() { }

    public static void show(Context c, RadioService svc, ChannelStore.Channel ch) {
        LinearLayout body = Neo.sheetBody(c, ch.name);
        final AlertDialog[] self = new AlertDialog[1];
        fill(c, svc, ch, body, 300, () -> { if (self[0] != null) self[0].dismiss(); });
        self[0] = Neo.showSheet(c, body);
    }

    /** Fills {@code body} with the channel's history: summary line, the entries by day, and the Clear button ({@code onCleared} runs after a clear). */
    public static void fill(Context c, RadioService svc, ChannelStore.Channel ch, LinearLayout body, int limit, Runnable onCleared) {
        float d = Theme.dp(c);
        List<Object[]> rows = svc.store().channelHistory(ch.number, limit);
        int total = svc.store().channelHistoryCount(ch.number);
        long lastHeard = 0; String lastWhat = "";
        int voice = 0, texts = 0, tones = 0, ids = 0, pkt = 0, tx = 0;
        for (Object[] r : rows) {
            String kind = (String) r[1], text = (String) r[2];
            boolean heard = kind.equals("rx") || kind.equals("dmr") || kind.equals("sms") || kind.equals("pkt") || kind.equals("tone") || kind.equals("rec") || kind.equals("m17");
            if (heard && lastHeard == 0) { lastHeard = (Long) r[0]; lastWhat = what(kind, text); }
            if (kind.equals("rx") || kind.equals("dmr") || kind.equals("m17")) voice++;
            else if (kind.equals("sms")) texts++;
            else if (kind.equals("tone")) tones++;
            else if (kind.equals("tx")) tx++;
            else if (kind.equals("pkt")) { if (text.contains("Morse") || text.contains("Repeater ID")) ids++; else pkt++; }
        }
        String heard = lastHeard > 0 ? Txt.s(R.string.intercom_chhistory_last_heard, ContactEditor.ago(lastHeard), lastWhat) : Txt.s(R.string.intercom_chhistory_none);
        String entries = total > rows.size() ? Txt.p(R.plurals.intercom_chhistory_entries_newest, total, total, rows.size()) : Txt.p(R.plurals.intercom_chhistory_entries, total, total);
        body.addView(Neo.sheetHint(c, total > 0 ? Txt.s(R.string.intercom_chhistory_summary, heard, entries, voice, texts, tones, ids, pkt, tx) : heard));
        SimpleDateFormat f = new SimpleDateFormat("EEE MM-dd HH:mm:ss", Locale.US);
        String day = "";
        for (Object[] r : rows) {
            long ts = (Long) r[0]; String kind = (String) r[1], text = (String) r[2], shown = (String) r[5]; int rssi = (Integer) r[3], dmrId = (Integer) r[4];   // text: the stored English (what is matched); shown: the line in the app's language
            String dd = new SimpleDateFormat("EEEE, MMM d", Locale.US).format(new Date(ts));
            if (!dd.equals(day)) { day = dd; body.addView(Neo.sheetSection(c, dd)); }
            boolean alert = LastHeardDialog.isAlert(text) || kind.equals("warn") || kind.equals("alert") || kind.equals("busy");
            LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(alert ? Neo.tint(c, Neo.AMBER, 12, 1) : Neo.rowCard(c)); row.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d));
            TextView t = Neo.label(c, shown, alert ? Theme.AMBER : kind.equals("tx") ? Neo.AMBER_LIGHT : kind.equals("dmr") || kind.equals("sms") ? Neo.VIOLET_LIGHT : Theme.CYAN, 14, true);
            row.addView(t);
            String who = dmrId > 0 ? svc.callerName(dmrId) : "";
            String line = f.format(new Date(ts)) + "  ·  " + what(kind, text) + (rssi > -200 && rssi < 0 ? "  ·  " + rssi + " dBm" : "");
            if (dmrId > 0) line = Txt.s(R.string.intercom_chhistory_row_caller, line, who.isEmpty() ? Txt.s(R.string.intercom_dmr_id, dmrId) : who + " (" + dmrId + ")");
            row.addView(Neo.label(c, line, 0xFFBFC8D0, 12, false));
            if (dmrId > 0) { final int id = dmrId; row.setClickable(true); row.setOnClickListener(v -> ContactEditor.open(c, svc, ContactEditor.Identity.dmr(id), null)); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (5 * d);
            body.addView(row, lp);
        }
        if (rows.isEmpty()) body.addView(Neo.label(c, Txt.s(R.string.intercom_chhistory_empty), 0xFFBFC8D0, 14, false));
        if (total > 0) {
            TextView clear = Neo.button(c, Txt.s(R.string.intercom_chhistory_clear), "stop", Neo.RED); clear.setTextSize(14);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.setMargins(0, (int) (12 * d), 0, 0);
            body.addView(clear, bl);
            clear.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_chhistory_clear_title, ch.name)).setMessage(Txt.s(R.string.intercom_chhistory_clear_msg))
                    .setPositiveButton(Txt.s(R.string.common_clear), (dd, w) -> { svc.store().clearChannelHistory(ch.number); if (onCleared != null) onCleared.run(); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        }
    }

    /** What a history line was, from its kind and its stored English wording; the answer is in the app's language. */
    static String what(String kind, String text) {
        if (text == null) text = "";
        switch (kind) {
            case "rx": return Txt.s(R.string.intercom_chhistory_kind_voice);
            case "dmr": return Txt.s(R.string.intercom_chhistory_kind_dmr_voice);
            case "m17": return Txt.s(R.string.intercom_chhistory_kind_m17_voice);
            case "sms": return Txt.s(R.string.intercom_chhistory_kind_dmr_text);
            case "tone": return Txt.s(R.string.intercom_chhistory_kind_tone);
            case "rec": return Txt.s(R.string.intercom_chhistory_kind_recording);
            case "tx": return Txt.s(R.string.intercom_chhistory_kind_transmitted);
            case "pkt": return text.contains("Repeater ID") ? Txt.s(R.string.intercom_chhistory_kind_repeater_id) : text.contains("Morse") ? Txt.s(R.string.intercom_chhistory_kind_morse)
                    : text.contains("APRS") || text.startsWith("📡") ? Txt.s(R.string.intercom_chhistory_kind_packet) : text.contains("SSTV") ? "SSTV" : text.contains("DTMF") ? "DTMF" : Txt.s(R.string.intercom_chhistory_kind_packet);   // i18n: SSTV and DTMF are mode names
            case "warn": case "alert": case "busy": return Txt.s(R.string.intercom_chhistory_kind_alert);
            case "car": return Txt.s(R.string.intercom_chhistory_kind_car);
            case "cov": return Txt.s(R.string.intercom_chhistory_kind_coverage);
            case "chan": return Txt.s(R.string.intercom_chhistory_kind_channel);
            default: return kind;
        }
    }
}
