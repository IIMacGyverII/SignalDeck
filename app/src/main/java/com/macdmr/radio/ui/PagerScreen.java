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
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.Station;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compose a POCSAG page: frequency, speed, one or many RICs, and the text. Send uses the symbol engine. */
public final class PagerScreen extends ModeScreen {
    private final EditText freq, to, msg;
    private final TextView count, status;
    private int baud = 1200;
    private int func = 3;
    private boolean sign = true;

    public PagerScreen(Context c, RadioService svc, Runnable onClose) {
        super(c, svc, Txt.s(R.string.modes_pager_title), "messages", Neo.VIOLET, onClose);
        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding(dp(14), dp(8), dp(14), dp(16));
        sv.addView(body);
        ChannelStore.Channel ch = svc.current();
        double mhz = ch != null && ch.rxFreq > 0 ? ch.rxFreq / 1e6 : 467.625;
        freq = field(body, Txt.s(R.string.modes_pager_freq), String.format(Locale.US, "%.5f", mhz));
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_pager_freq_hint)));
        TextView speed = Neo.text(c, "", Neo.TEXT, 15, Neo.ui(c));
        body.addView(speed);
        LinearLayout speeds = new LinearLayout(c); speeds.setOrientation(HORIZONTAL);
        for (int b : new int[] { 512, 1200, 2400 }) {
            TextView t = Neo.button(c, String.valueOf(b), "wave", Neo.CYAN); t.setTextSize(14);
            int rate = b;
            t.setOnClickListener(v -> { baud = rate; speed.setText(speedLine()); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            speeds.addView(t, lp);
        }
        body.addView(speeds);
        speed.setText(speedLine());
        String mine = Station.ricText(c);
        body.addView(Neo.sheetHint(c, mine.isEmpty() ? Txt.s(R.string.modes_pager_rics_none) : Txt.s(R.string.modes_pager_rics, mine)));
        to = field(body, Txt.s(R.string.modes_pager_to), "");
        msg = field(body, Txt.s(R.string.modes_pager_message), "");
        msg.setSingleLine(false); msg.setMinLines(3);
        count = Neo.text(c, "", Neo.MUTED, 13, Neo.uiMedium(c));
        body.addView(count);
        msg.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            public void onTextChanged(CharSequence s, int a, int b, int n) { count.setText(Txt.p(R.plurals.modes_pager_chars, s.length(), s.length())); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        count.setText(Txt.p(R.plurals.modes_pager_chars, 0, 0));
        TextView signBtn = Neo.text(c, "", Neo.AMBER_LIGHT, 15, Neo.ui(c));
        signBtn.setPadding(0, dp(8), 0, dp(4));
        signBtn.setOnClickListener(v -> { sign = !sign; signBtn.setText(signLine()); });
        signBtn.setText(signLine());
        body.addView(signBtn);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_pager_sign_hint)));
        status = Neo.text(c, "", Neo.MUTED, 14, Neo.uiMedium(c));
        status.setPadding(0, dp(8), 0, 0);
        body.addView(status);
        content.addView(sv);
        LinearLayout row = buttonRow();
        button(row, Txt.s(R.string.common_send), "send", Neo.GREEN, v -> send());
        button(row, Txt.s(R.string.common_close), "left", Neo.CYAN, v -> close());
    }

    @Override protected boolean stillRunning() { return true; }
    @Override protected void refresh() { }

    private String speedLine() { return Txt.s(R.string.modes_pager_speed, baud, func); }
    private String signLine() { return Txt.s(sign ? R.string.modes_pager_sign_on : R.string.modes_pager_sign_off); }

    private EditText field(LinearLayout body, String label, String value) {
        body.addView(Neo.sheetSection(c, label));
        EditText e = new EditText(c);
        e.setText(value);
        e.setTextColor(Neo.TEXT);
        e.setHintTextColor(Neo.DIM);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setBackgroundColor(0xFF0E1C2A);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        body.addView(e, lp);
        return e;
    }

    private void send() {
        double mhz;
        try { mhz = Double.parseDouble(freq.getText().toString().trim()); }
        catch (NumberFormatException e) { status.setText(Txt.s(R.string.modes_pager_err_freq)); return; }
        int hz = (int) Math.round(mhz * 1e6);
        int[] rics;
        try { rics = resolve(to.getText().toString()); }
        catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
        String err = svc.sendPage(baud, hz, rics, func, msg.getText().toString(), sign);
        status.setText(err == null ? Txt.p(R.plurals.modes_pager_sending, rics.length, rics.length) : err);
    }

    /** Digits are RICs. Any other word is a contact name or callsign, and every RIC on that contact is included. */
    private int[] resolve(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException(Txt.s(R.string.modes_pager_err_empty));
        List<ChannelStore.Contact> all = svc.store().contacts();
        for (String tok : raw.trim().split("[,;\\s]+")) {
            if (tok.isEmpty()) continue;
            boolean digits = true;
            for (int i = 0; i < tok.length(); i++) if (!Character.isDigit(tok.charAt(i))) digits = false;
            if (digits) {
                int r = Integer.parseInt(tok);
                if (r < 0 || r > 0x1FFFFF) throw new IllegalArgumentException(Txt.s(R.string.modes_pager_err_ric, tok));
                if (!out.contains(r)) out.add(r);
                continue;
            }
            String key = tok.toUpperCase(Locale.US);
            ChannelStore.Contact hit = null;
            for (ChannelStore.Contact ct : all) {
                if (key.equals(ct.name.toUpperCase(Locale.US)) || key.equals(ct.baseCall())) { hit = ct; break; }
            }
            if (hit == null) throw new IllegalArgumentException(Txt.s(R.string.modes_pager_err_contact, tok));
            if (hit.ricList().length == 0) throw new IllegalArgumentException(Txt.s(R.string.modes_pager_err_no_ric, hit.name));
            for (int r : hit.ricList()) if (!out.contains(r)) out.add(r);
        }
        if (out.isEmpty()) throw new IllegalArgumentException(Txt.s(R.string.modes_pager_err_empty));
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }
}
