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
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.PacketEngine;
import com.macdmr.radio.RadioService;

/** RTTY and PSK31 keyboard chat on the current channel: listen, read, type and send. */
public final class TextModesDialog {
    private TextModesDialog() { }

    public static void show(Context c, RadioService svc) {
        final PacketEngine pe = svc.packet(); if (pe == null) return;
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_text_title), "radio");
        final TextView status = Neo.label(c, pe.textStatus(), Theme.CYAN, 14, true); body.addView(status);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_text_hint)));
        LinearLayout r1 = new LinearLayout(c); r1.setBaselineAligned(false);
        final TextView[] modes = new TextView[2];
        for (int k = 0; k < 2; k++) {
            final int m = k + 1;
            modes[k] = Neo.button(c, PacketEngine.TEXT_MODES[m], null, Neo.CYAN); modes[k].setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (6 * d), (int) (3 * d), 0);
            r1.addView(modes[k], lp);
        }
        final TextView listen = Neo.button(c, "", null, Neo.GREEN); listen.setTextSize(14);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); ll.setMargins((int) (3 * d), (int) (6 * d), (int) (3 * d), 0);
        r1.addView(listen, ll);
        body.addView(r1);
        final TextView screen = Neo.text(c, "", 0xFFCFE8D8, 14, Neo.mono(c));
        screen.setBackground(Neo.rowCard(c)); screen.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d)); screen.setMinHeight((int) (220 * d)); screen.setTextIsSelectable(true);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); sl.topMargin = (int) (8 * d);
        body.addView(screen, sl);
        LinearLayout r2 = new LinearLayout(c); r2.setBaselineAligned(false);
        final EditText input = new EditText(c); input.setHint(Txt.s(R.string.modes_text_input_hint)); input.setTextColor(Neo.TEXT); input.setHintTextColor(Neo.DIM); input.setTypeface(Neo.mono(c));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); input.setSingleLine(true);
        TextView send = Neo.button(c, Txt.s(R.string.common_send), null, Neo.AMBER), clear = Neo.button(c, Txt.s(R.string.common_clear), null, Neo.RED);
        r2.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(send, new LinearLayout.LayoutParams((int) (90 * d), LinearLayout.LayoutParams.MATCH_PARENT));
        r2.addView(clear, new LinearLayout.LayoutParams((int) (90 * d), LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(r2);

        final Runnable paint = () -> {
            int tx = pe.textTxMode();
            for (int k = 0; k < 2; k++) Neo.pillStyle(modes[k], tx == k + 1, Neo.CYAN);
            listen.setText(pe.textMode() > 0 ? Txt.s(R.string.modes_text_stop_listening) : Txt.s(R.string.modes_text_listen));
            status.setText(pe.textStatus());
        };
        for (int k = 0; k < 2; k++) { final int m = k + 1; modes[k].setOnClickListener(v -> { if (pe.textMode() > 0) pe.setTextMode(m); else pe.setTextTxMode(m); paint.run(); }); }
        listen.setOnClickListener(v -> { pe.setTextMode(pe.textMode() > 0 ? 0 : pe.textTxMode()); paint.run(); });
        pe.setTextListener((text, st) -> { String s = text.length() > 6000 ? text.substring(text.length() - 6000) : text; screen.setText(s.isEmpty() ? Txt.s(R.string.modes_text_nothing) : s); status.setText(st); });
        Runnable doSend = () -> { String e = pe.textSend(input.getText().toString()); if (e != null) { Toast.makeText(c, e, Toast.LENGTH_SHORT).show(); return; } input.setText(""); };
        send.setOnClickListener(v -> doSend.run());
        input.setOnEditorActionListener((v, id, ev) -> { doSend.run(); return true; });
        clear.setOnClickListener(v -> pe.textClear());
        paint.run();
        AlertDialog a = Neo.showSheet(c, body);
        a.setOnDismissListener(x -> pe.setTextListener(null));
    }
}
