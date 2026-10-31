/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.VoiceKeyer;

import java.util.Locale;

/** Voice keyer: three recorded messages to send with one tap, and the ten-minute identification timer. */
public final class KeyerDialog {
    private KeyerDialog() { }

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        final VoiceKeyer k = svc.keyer();
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_keyer_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_keyer_hint)));
        final Handler h = new Handler(Looper.getMainLooper());
        final TextView[] info = new TextView[VoiceKeyer.SLOTS], recB = new TextView[VoiceKeyer.SLOTS], idB = new TextView[VoiceKeyer.SLOTS];
        final Runnable[] paint = new Runnable[1];
        for (int i = 0; i < VoiceKeyer.SLOTS; i++) {
            final int s = i;
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(Neo.rowCard(c)); card.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Neo.text(c, k.name(s), Neo.TEXT, 16, Neo.ui(c));
            top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            info[s] = Neo.text(c, "", Neo.MUTED, 13, Neo.uiMedium(c)); top.addView(info[s]);
            card.addView(top);
            name.setOnClickListener(v -> {
                final EditText e = new EditText(c); e.setText(k.name(s)); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_keyer_name_title)).setView(e).setPositiveButton(Txt.s(R.string.common_ok), (dd, w) -> { String n = e.getText().toString().trim(); if (!n.isEmpty()) { k.setName(s, n); name.setText(n); } }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            });
            LinearLayout r = new LinearLayout(c); r.setBaselineAligned(false);
            recB[s] = Neo.button(c, Txt.s(R.string.modes_keyer_record), "rec", Neo.RED);
            TextView listen = Neo.button(c, Txt.s(R.string.modes_keyer_listen), "volume", Neo.CYAN), send = Neo.button(c, Txt.s(R.string.common_send), "send", Neo.GREEN);
            idB[s] = Neo.button(c, "", null, Neo.VIOLET);
            for (TextView b : new TextView[] { recB[s], listen, send, idB[s] }) { b.setTextSize(13); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (2 * d), (int) (6 * d), (int) (2 * d), 0); r.addView(b, lp); }
            card.addView(r);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); cl.bottomMargin = (int) (8 * d);
            body.addView(card, cl);
            recB[s].setOnClickListener(v -> {
                if (k.recordingSlot() == s) { k.stop(); return; }
                if (k.recordingSlot() >= 0) return;
                String e = k.start(s, () -> h.post(paint[0]));
                if (e != null) Toast.makeText(c, e, Toast.LENGTH_LONG).show();
                paint[0].run();
            });
            listen.setOnClickListener(v -> { if (k.has(s)) k.preview(s); else Toast.makeText(c, Txt.s(R.string.modes_keyer_nothing), Toast.LENGTH_SHORT).show(); });
            send.setOnClickListener(v -> { String e = svc.sendKeyer(s); Toast.makeText(c, e == null ? Txt.s(R.string.modes_keyer_sending, k.name(s)) : e, Toast.LENGTH_SHORT).show(); });
            idB[s].setOnClickListener(v -> { k.setAddId(s, !k.addId(s)); paint[0].run(); });
        }
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_keyer_id_section)));
        final TextView idClock = Neo.text(c, "", Neo.CYAN_LIGHT, 22, Neo.display(c)); idClock.setGravity(Gravity.CENTER); body.addView(idClock);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_keyer_id_hint)));
        TextView done = Neo.button(c, Txt.s(R.string.modes_keyer_identified), "check", Neo.GREEN); body.addView(done);
        done.setOnClickListener(v -> { svc.markIdentified(); paint[0].run(); });
        paint[0] = () -> {
            for (int s = 0; s < VoiceKeyer.SLOTS; s++) {
                boolean recording = k.recordingSlot() == s;
                recB[s].setText(Txt.s(recording ? R.string.common_stop : R.string.modes_keyer_record));
                info[s].setText(recording ? Txt.s(R.string.modes_keyer_recording) : k.has(s) ? Txt.s(R.string.modes_keyer_length, Txt.decimal(k.lengthMs(s) / 1000.0, 1)) : Txt.s(R.string.modes_keyer_empty));
                idB[s].setText(Txt.s(k.addId(s) ? R.string.modes_keyer_id_on : R.string.modes_keyer_id_off));
            }
            long due = svc.idDueInMs();
            idClock.setText(due == Long.MAX_VALUE ? Txt.s(R.string.modes_keyer_timer_idle) : due > 0 ? Txt.s(R.string.modes_keyer_timer_due, String.format(Locale.US, "%d:%02d", due / 60000, due / 1000 % 60)) : Txt.s(R.string.modes_keyer_timer_now));
            idClock.setTextColor(due == Long.MAX_VALUE ? Neo.MUTED : due > 60_000 ? Neo.CYAN_LIGHT : Neo.AMBER_LIGHT);
        };
        final Runnable tick = new Runnable() { @Override public void run() { paint[0].run(); h.postDelayed(this, 1000); } };
        h.post(tick);
        AlertDialog a = Neo.showSheet(c, body);
        a.setOnDismissListener(x -> { h.removeCallbacks(tick); if (k.recordingSlot() >= 0) k.stop(); });
    }
}
