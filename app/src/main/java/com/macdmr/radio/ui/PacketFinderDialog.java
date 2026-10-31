/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.PacketFinder;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT → Packet finder: listen across the packet frequencies for BBSs, nodes and Winlink gateways (receive only). */
public final class PacketFinderDialog {
    private PacketFinderDialog() { }

    public static void show(Context c, RadioService svc) {
        final PacketFinder pf = svc.finder();
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.packet_pkt_finder), "pkt");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.packet_pfd_hint)));
        final TextView status = Neo.label(c, "", Theme.CYAN, 14, true); body.addView(status);
        final TextView sub = Neo.label(c, "", Neo.MUTED, 12.5f, false); body.addView(sub);
        LinearLayout r1 = new LinearLayout(c); body.addView(r1);
        final TextView go = btn(c, r1, Txt.s(R.string.common_start), "search", Neo.GREEN), set = btn(c, r1, Txt.s(R.string.packet_pkt_settings), "menu", Neo.VIOLET);
        final LinearLayout list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        final AlertDialog[] self = { null };
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] shown = { -1 };
        final Runnable[] refresh = { null };
        refresh[0] = () -> {
            boolean on = pf.running();
            status.setText(pf.status());
            sub.setText(on ? Txt.s(R.string.packet_pfd_sub_running, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(pf.endsAt())), pf.dwellMin(), pf.freqs().size())
                    : pf.freqs().isEmpty() ? Txt.s(R.string.packet_pfd_sub_no_freqs, svc.profile().bandsText())
                    : Txt.s(R.string.packet_pfd_sub_idle, pf.freqs().size(), pf.dwellMin(), pf.hours()));
            go.setText(Txt.s(on ? R.string.common_stop : R.string.common_start));
            List<PacketFinder.Station> st = pf.stations();
            if (st.size() != shown[0] || on) { shown[0] = st.size(); fill(c, svc, list, st, self); }
            if (self[0] != null && self[0].isShowing()) h.postDelayed(refresh[0], 3000);
        };
        go.setOnClickListener(v -> {
            if (pf.running()) { pf.stop(); h.postDelayed(refresh[0], 800); return; }
            String e = pf.start(0); if (e != null) { Toast.makeText(c, e, Toast.LENGTH_LONG).show(); return; }
            h.postDelayed(refresh[0], 800);
        });
        set.setOnClickListener(v -> settings(c, pf, refresh[0]));
        self[0] = Neo.showSheet(c, body);
        refresh[0].run();
    }

    private static void fill(Context c, RadioService svc, LinearLayout list, List<PacketFinder.Station> st, AlertDialog[] self) {
        float d = Theme.dp(c);
        list.removeAllViews();
        list.addView(Neo.sheetSection(c, st.isEmpty() ? Txt.s(R.string.packet_pfd_nothing) : Txt.s(R.string.packet_pfd_heard, st.size())));
        SimpleDateFormat f = Txt.dates("EEE HH:mm", "EEEHHmm", false);
        for (PacketFinder.Station s : st) {
            String icon = PacketFinder.GATEWAY.equals(s.kind) ? "mail" : PacketFinder.BBS.equals(s.kind) ? "messages" : PacketFinder.NODE.equals(s.kind) ? "zone" : "radio";
            int col = PacketFinder.GATEWAY.equals(s.kind) ? Neo.GREEN : PacketFinder.BBS.equals(s.kind) ? Neo.AMBER : PacketFinder.NODE.equals(s.kind) ? Neo.CYAN : Neo.MUTED;
            String mhz = Txt.freqMhz(s.hz, 3), when = f.format(new Date(s.last));
            String line = (s.bestDbm > -200 ? Txt.s(R.string.packet_pfd_row_dbm, PacketFinder.kindName(s.kind), mhz, s.bestDbm, s.count, when) : Txt.s(R.string.packet_pfd_row, PacketFinder.kindName(s.kind), mhz, s.count, when))
                    + (s.note.isEmpty() ? "" : "\n" + s.note);
            LinearLayout t = Neo.tile(c, icon, col, s.call, line);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
            list.addView(t, lp);
            t.setOnClickListener(v -> act(c, svc, s, self));
        }
        if (!st.isEmpty()) {
            TextView clr = Neo.button(c, Txt.s(R.string.packet_pfd_clear), "stop", Neo.RED); clr.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d)); lp.topMargin = (int) (6 * d);
            list.addView(clr, lp);
            clr.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pfd_clear_q)).setPositiveButton(Txt.s(R.string.common_clear), (dd, w) -> { svc.finder().clear(); if (self[0] != null) self[0].dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        }
    }

    /** Tune to it, then open the terminal or Winlink with its callsign filled in. */
    private static void act(Context c, RadioService svc, PacketFinder.Station s, AlertDialog[] self) {
        if (svc.finder().running()) { Toast.makeText(c, Txt.s(R.string.packet_pfd_err_running), Toast.LENGTH_SHORT).show(); return; }
        final boolean gw = PacketFinder.GATEWAY.equals(s.kind);
        String mhz = Txt.freqMhz(s.hz, 3);
        String[] items = gw
                ? new String[] { Txt.s(R.string.packet_pfd_tune_winlink, mhz), Txt.s(R.string.packet_pfd_tune_terminal_short), Txt.s(R.string.packet_pfd_just_tune) }
                : new String[] { Txt.s(R.string.packet_pfd_tune_terminal, mhz), Txt.s(R.string.packet_pfd_just_tune) };
        new AlertDialog.Builder(c).setTitle(s.call).setItems(items, (dd, w) -> {
            ChannelStore.Channel ch = new ChannelStore.Channel(); ch.type = 1; ch.rxFreq = ch.txFreq = s.hz; ch.band = 1; ch.power = 0; ch.sq = 2; ch.name = s.call;
            svc.applyVfo(ch, true);
            boolean winlink = gw && w == 0, terminal = gw ? w == 1 : w == 0;   // by position: the labels are translated
            if (self[0] != null) self[0].dismiss();
            if (winlink) { svc.winlink().setGateway(s.call, ""); WinlinkDialog.show(c, svc); }
            else if (terminal) { c.getSharedPreferences("packet", Context.MODE_PRIVATE).edit().putString("termCall", s.call).putString("termVia", "").apply(); TerminalDialog.show(c, svc); }
            Toast.makeText(c, Txt.s(R.string.packet_pfd_vfo_on, Txt.freqMhz(s.hz, 3)), Toast.LENGTH_SHORT).show();
        }).show();
    }

    private static void settings(Context c, PacketFinder pf, Runnable after) {
        float d = Theme.dp(c);
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        v.addView(Neo.label(c, Txt.s(R.string.packet_pfd_set_freqs), Neo.MUTED, 13, false));
        final EditText fr = new EditText(c); fr.setText(pf.freqTextShown()); fr.setTextColor(Neo.TEXT); fr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); v.addView(fr);
        TextView mine = Neo.button(c, Txt.s(R.string.packet_pfd_set_mine), "list", Neo.CYAN); mine.setTextSize(13);   // a start on a radio the 2 m defaults miss (the W6): your own channels' frequencies
        mine.setOnClickListener(x -> { String m = pf.myChannelsText(); if (m.isEmpty()) Toast.makeText(c, Txt.s(R.string.packet_pfd_set_mine_none), Toast.LENGTH_SHORT).show(); else fr.setText(m); });
        v.addView(mine, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * d)));
        final EditText dw = new EditText(c); dw.setHint(Txt.s(R.string.packet_pfd_set_dwell)); dw.setText(String.valueOf(pf.dwellMin())); dw.setInputType(InputType.TYPE_CLASS_NUMBER); dw.setTextColor(Neo.TEXT); v.addView(dw);
        final EditText hr = new EditText(c); hr.setHint(Txt.s(R.string.packet_pfd_set_hours)); hr.setText(String.valueOf(pf.hours())); hr.setInputType(InputType.TYPE_CLASS_NUMBER); hr.setTextColor(Neo.TEXT); v.addView(hr);
        v.addView(Neo.label(c, Txt.s(R.string.packet_pfd_set_note), Neo.MUTED, 12, false));
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pfd_set_title)).setView(v)
                .setPositiveButton(Txt.s(R.string.common_save), (dd, w) -> {
                    int dwell, hours; try { dwell = Integer.parseInt(dw.getText().toString().trim()); hours = Integer.parseInt(hr.getText().toString().trim()); } catch (NumberFormatException e) { Toast.makeText(c, Txt.s(R.string.packet_pfd_set_err_numbers), Toast.LENGTH_SHORT).show(); return; }
                    String e = pf.setSettings(fr.getText().toString(), dwell, hours);
                    Toast.makeText(c, e == null ? Txt.s(R.string.packet_pkt_saved) : e, Toast.LENGTH_LONG).show(); after.run();
                })
                .setNeutralButton(Txt.s(R.string.packet_pfd_set_defaults), (dd, w) -> { pf.setSettings("", 3, 2); after.run(); })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static TextView btn(Context c, LinearLayout row, String label, String icon, int accent) {
        float d = Theme.dp(c);
        TextView b = Neo.button(c, label, icon, accent); b.setTextSize(14); Neo.fit(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0);
        row.addView(b, lp);
        return b;
    }
}
