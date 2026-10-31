/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.Conditions;
import com.macdmr.radio.ham.SatTrack;
import com.macdmr.radio.ham.Satellites;
import com.macdmr.radio.ham.Sgp4;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PKT → VHF/UHF conditions: tropo ducting forecast, aurora, meteor showers and the next satellite passes. */
public final class ConditionsDialog {
    private ConditionsDialog() { }
    static final int[] CLASS_COLOR = { 0xFF2A3C50, Neo.CYAN, Neo.GREEN, Neo.AMBER };

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_cond_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_cond_hint)));
        final TextView loading = Neo.label(c, Txt.s(R.string.modes_cond_loading), Neo.MUTED, 14, false); body.addView(loading);
        final LinearLayout cards = new LinearLayout(c); cards.setOrientation(LinearLayout.VERTICAL); body.addView(cards);
        Neo.showSheet(c, body);
        final android.location.Location me = svc.lastKnownLocation();
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Conditions.Report r = Conditions.get(me == null ? 0 : me.getLatitude(), me == null ? 0 : me.getLongitude(), me != null);
            h.post(() -> { loading.setVisibility(View.GONE); fill(c, svc, cards, r, me); });
        }, "conditions").start();
    }

    private static LinearLayout card(Context c, LinearLayout parent, String title, String icon, int accent) {
        float d = Theme.dp(c);
        LinearLayout k = new LinearLayout(c); k.setOrientation(LinearLayout.VERTICAL); k.setBackground(Neo.rowCard(c)); k.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (12 * d));
        LinearLayout head = new LinearLayout(c); head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (28 * d), (int) (28 * d)); il.rightMargin = (int) (10 * d);
        head.addView(Neo.iconTile(c, icon, accent, 28), il); head.addView(Neo.caps(c, title));
        k.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (10 * d);
        parent.addView(k, lp);
        return k;
    }
    private static TextView big(Context c, String s, int col) { TextView t = Neo.text(c, s, col, 19, Neo.uiMedium(c)); t.setPadding(0, (int) (8 * Theme.dp(c)), 0, (int) (2 * Theme.dp(c))); return t; }
    private static TextView small(Context c, String s) { TextView t = Neo.text(c, s, Neo.MUTED, 13.5f, Neo.uiMedium(c)); t.setLineSpacing(0, 1.12f); return t; }

    private static void fill(Context c, RadioService svc, LinearLayout cards, Conditions.Report r, android.location.Location me) {
        float d = Theme.dp(c);
        SimpleDateFormat day = new SimpleDateFormat("EEE HH:mm", Locale.getDefault()), hh = new SimpleDateFormat("HH", Locale.getDefault()), date = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
        // tropo
        LinearLayout t = card(c, cards, Txt.s(R.string.modes_cond_tropo_title), "signal", Neo.GREEN);
        if (r.tropo.isEmpty()) t.addView(small(c, me == null ? Txt.s(R.string.modes_cond_needs_fix) : Txt.s(R.string.modes_cond_tropo_failed)));
        else {
            long[] w = r.bestWindow();
            t.addView(big(c, w == null ? Txt.s(R.string.modes_cond_tropo_normal) : Txt.s(R.string.modes_cond_tropo_window, Conditions.className((int) w[2]), day.format(new Date(w[0])), new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(w[1]))), w == null ? Neo.TEXT : CLASS_COLOR[(int) w[2]]));
            LinearLayout strip = new LinearLayout(c); strip.setPadding(0, (int) (8 * d), 0, (int) (2 * d));
            LinearLayout labels = new LinearLayout(c);
            for (int i = 0; i < r.tropo.size(); i++) {
                Conditions.Slot s = r.tropo.get(i);
                View v = new View(c); v.setBackgroundColor(CLASS_COLOR[s.cls]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (18 * d), 1f); lp.rightMargin = (int) (1 * d); strip.addView(v, lp);
                TextView lb = Neo.text(c, i % 6 == 0 ? hh.format(new Date(s.time)) : "", Neo.DIM, 10, Neo.uiMedium(c));
                labels.addView(lb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            t.addView(strip); t.addView(labels);
            t.addView(small(c, Txt.s(R.string.modes_cond_tropo_legend)));
        }
        // aurora
        LinearLayout a = card(c, cards, Txt.s(R.string.modes_cond_aurora_title), "weather", Neo.VIOLET);
        a.addView(big(c, r.kpNow < 0 ? Txt.s(R.string.modes_cond_kp_unavailable) : Txt.s(R.string.modes_cond_kp_now, String.format(Locale.US, "%.1f", r.kpNow), Conditions.kpWords(r.kpNow)), r.kpNow >= 5 ? Neo.AMBER : Neo.TEXT));
        List<String> al = new ArrayList<>();
        if (r.kpMax >= 0) al.add(Txt.s(R.string.modes_cond_kp_forecast, String.format(Locale.US, "%.1f", r.kpMax), Conditions.kpWords(r.kpMax), day.format(new Date(r.kpMaxAt))));
        if (r.aurora >= 0) al.add(Txt.s(R.string.modes_cond_aurora_chance, String.valueOf(r.aurora)));
        al.add(Txt.s(R.string.modes_cond_aurora_advice));
        a.addView(small(c, android.text.TextUtils.join("\n", al)));
        // meteors
        LinearLayout m = card(c, cards, Txt.s(R.string.modes_cond_meteor_title), "star", Neo.AMBER);
        if (r.showers.isEmpty()) m.addView(small(c, Txt.s(R.string.modes_cond_meteor_none)));
        for (Conditions.Shower s : r.showers) {
            long days = Math.round((s.peak - System.currentTimeMillis()) / 86_400_000.0);
            m.addView(big(c, days <= 0 ? Txt.s(R.string.modes_cond_shower_active, s.name) : Txt.p(R.plurals.modes_cond_shower_peak, (int) days, s.name, date.format(new Date(s.peak)), String.valueOf(days)), days <= 1 ? Neo.AMBER : Neo.TEXT));
            String note = s.note();
            m.addView(small(c, note.isEmpty() ? Txt.s(R.string.modes_cond_shower_rate, String.valueOf(s.zhr)) : Txt.s(R.string.modes_cond_shower_rate_note, String.valueOf(s.zhr), note)));
        }
        m.addView(small(c, Txt.s(R.string.modes_cond_meteor_advice)));
        // satellites
        LinearLayout sp = card(c, cards, Txt.s(R.string.modes_cond_sat_title), "sstv", Neo.CYAN);
        if (me == null) sp.addView(small(c, Txt.s(R.string.modes_cond_needs_fix)));
        else {
            Map<Integer, Sgp4> el = svc.satellites().elements(); long now = System.currentTimeMillis();
            List<Object[]> passes = new ArrayList<>(); java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Satellites.Sat s : Satellites.all(c)) {
                if (!seen.add(s.catalog)) continue;                           // the ISS once, not per mode
                Sgp4 e = el.get(s.catalog); if (e == null) continue;
                for (SatTrack.Pass p : SatTrack.passes(e, now, 12, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 20)) if (p.los > now) { passes.add(new Object[] { s.catalog == Satellites.ISS_CATALOG ? "ISS" : s.name, p }); break; }   // i18n: proper name
            }
            passes.sort((x, y) -> Long.compare(((SatTrack.Pass) x[1]).aos, ((SatTrack.Pass) y[1]).aos));
            if (passes.isEmpty()) sp.addView(small(c, Txt.s(R.string.modes_cond_sat_none)));
            for (int i = 0; i < Math.min(5, passes.size()); i++) {
                SatTrack.Pass p = (SatTrack.Pass) passes.get(i)[1];
                String el0 = String.format(Locale.US, "%.0f", p.maxEl);
                sp.addView(big(c, p.aos <= now ? Txt.s(R.string.modes_cond_sat_up_now, String.valueOf(passes.get(i)[0]), el0) : Txt.s(R.string.modes_cond_sat_pass, String.valueOf(passes.get(i)[0]), day.format(new Date(p.aos)), el0), Neo.TEXT));
            }
            sp.addView(small(c, Txt.s(R.string.modes_cond_sat_footer)));
        }
        if (!r.problems.isEmpty()) cards.addView(Neo.sheetHint(c, android.text.TextUtils.join(" · ", r.problems)));
        cards.addView(Neo.sheetHint(c, Txt.s(R.string.modes_cond_sources, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(r.fetched)))));
    }
}
