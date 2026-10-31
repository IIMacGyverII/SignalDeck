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
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.Parrot;

/** The simplex repeater (parrot): what it is doing now, how many transmissions it has repeated, and stop. */
public final class ParrotScreen extends ModeScreen {
    private final Parrot parrot;
    private final TextView now, count, last;

    public ParrotScreen(Context c, RadioService svc, Runnable onClose) {
        super(c, svc, Txt.s(R.string.modes_parrot_title), "repeaters", Neo.AMBER, onClose);
        parrot = svc.parrot();
        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding(dp(14), dp(10), dp(14), dp(10));
        sv.addView(body);
        now = Neo.text(c, "", Neo.AMBER_LIGHT, 24, Neo.display(c)); now.setGravity(Gravity.CENTER); now.setPadding(0, dp(18), 0, dp(6));
        body.addView(now);
        LinearLayout stats = new LinearLayout(c); stats.setPadding(0, dp(10), 0, dp(10));
        count = stat(stats, Txt.s(R.string.modes_parrot_stat_repeated)); last = stat(stats, Txt.s(R.string.modes_parrot_stat_last));
        body.addView(stats);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_parrot_hint, String.valueOf(Parrot.MAX_MS / 1000), String.valueOf(Parrot.idEveryMs() / 60000))));
        content.addView(sv);
        LinearLayout r = buttonRow();
        button(r, Txt.s(R.string.modes_parrot_stop), "stop", Neo.RED, v -> { toast(svc.setParrot(false)); close(); });
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout box = new LinearLayout(c); box.setOrientation(VERTICAL); box.setGravity(Gravity.CENTER);
        box.setBackground(Neo.card(c, Neo.LINE_SOFT, 10)); box.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView v = Neo.text(c, "0", Neo.TEXT, 24, Neo.display(c)); v.setGravity(Gravity.CENTER);
        TextView l = Neo.text(c, label, Neo.DIM, 11, Neo.ui(c)); l.setGravity(Gravity.CENTER); Neo.tracking(l, 0.08f);
        box.addView(v); box.addView(l);
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(box, lp); return v;
    }

    @Override protected boolean stillRunning() { return parrot.enabled(); }
    @Override protected void refresh() {
        com.macdmr.radio.ChannelStore.Channel ch = svc.current();
        setSubtitle(ch != null ? Txt.s(R.string.modes_parrot_on_channel, String.valueOf(ch.number), ch.name) : Txt.s(R.string.modes_parrot_on_this));
        set(now, Txt.s(parrot.playing() ? R.string.modes_parrot_replaying : parrot.recording() ? R.string.modes_parrot_recording : R.string.modes_parrot_waiting));
        color(now, parrot.playing() ? Neo.RED : parrot.recording() ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT);
        set(count, String.valueOf(parrot.played()));
        set(last, parrot.lastHeardMs() <= 0 ? "—" : since(parrot.lastHeardMs()));
    }
}
