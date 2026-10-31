/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.Maidenhead;
import com.macdmr.radio.ham.Satellites;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * On the APRS and SSTV pages while an ISS pass is tracked: when the next pass comes (countdown, highest elevation),
 * or during the pass where the station is, the Doppler-corrected frequency the radio is on, and when it sets.
 * Hidden for every other mode.
 */
final class PassCard extends LinearLayout {
    private final TextView title, detail;

    PassCard(Context c) {
        super(c);
        float d = Theme.dp(c);
        setOrientation(VERTICAL); setVisibility(GONE);
        setBackground(Neo.outline(c, Neo.VIOLET, 0x14, 12, 1)); setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        title = Neo.text(c, "", Neo.VIOLET_LIGHT, 15, Neo.ui(c)); addView(title);
        detail = Neo.text(c, "", Neo.TEXT_SOFT, 13, Neo.uiMedium(c)); detail.setPadding(0, (int) (2 * d), 0, 0); addView(detail);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (8 * d); setLayoutParams(lp);
    }

    void update(RadioService svc) {
        RadioService.SatState st = svc.satState();
        boolean show = st != null && st.mode != Satellites.VOICE;
        if ((getVisibility() == VISIBLE) != show) setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        long now = System.currentTimeMillis();
        String follow = Txt.s(svc.issRepeat() == st.mode ? R.string.modes_pass_follow_all : R.string.modes_pass_follow_one);
        String t, dt;
        if (st.waiting || st.look == null) {
            long in = Math.max(0, st.aos - now) / 1000;
            String when = in >= 3600 ? Txt.s(R.string.modes_pass_in_hours, (int) (in / 3600), (int) (in % 3600 / 60)) : Txt.s(R.string.modes_pass_in_minutes, (int) (in / 60), (int) (in % 60));
            t = Txt.s(R.string.modes_pass_next, new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(st.aos)), when);
            dt = Txt.s(R.string.modes_pass_next_detail, String.format(Locale.US, "%.0f", st.maxEl), String.valueOf((st.los - st.aos + 30_000) / 60_000), follow);
        } else {
            long left = Math.max(0, st.los - now) / 1000;
            boolean up = st.look.el >= 0;
            t = up ? Txt.s(R.string.modes_pass_up, String.format(Locale.US, "%.0f", st.look.el), Maidenhead.compass(st.look.az)) : Txt.s(R.string.modes_pass_rising);
            String rx = String.format(Locale.US, "%.4f", st.rxHz / 1e6), shift = String.format(Locale.US, "%+.1f", (st.rxHz - st.nominalHz) / 1000.0), clock = String.format(Locale.US, "%d:%02d", left / 60, left % 60), km = String.format(Locale.US, "%.0f", st.look.rangeKm);
            dt = Txt.s(up ? R.string.modes_pass_listening_sets : R.string.modes_pass_listening_ends, rx, shift, clock, km)
                    + (st.mode == Satellites.APRS && !st.listenOnly ? "\n" + Txt.s(R.string.modes_pass_talk, String.format(Locale.US, "%.4f", st.txHz / 1e6)) : "") + "\n" + follow;
        }
        ModeScreen.set(title, t); ModeScreen.set(detail, dt);
    }
}
