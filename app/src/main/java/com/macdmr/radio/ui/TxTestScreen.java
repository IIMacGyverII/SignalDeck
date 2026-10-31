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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.macdmr.radio.BuildIdentity;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.TxReport;
import com.macdmr.radio.TxTestSession;

import java.util.concurrent.CountDownLatch;

/**
 * The screens of the transmit test session (Device → Device report → Transmit test session): who may run it, the
 * safety sheet with its five confirmations, one sheet per instruction or question, the live sheet while a key is down
 * (with Release now), and the result with Share. The session itself ({@link TxTestSession}) runs on its own thread and
 * waits on these sheets.
 */
public final class TxTestScreen implements TxTestSession.Ui {
    private final Context c; private final RadioService svc; private final Runnable shareReport;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TxTestSession session;
    private android.app.AlertDialog keySheet; private TextView keyText;

    private TxTestScreen(Context c, RadioService svc, Runnable shareReport) { this.c = c; this.svc = svc; this.shareReport = shareReport; }

    /** Entry point from the Device report menu. */
    public static void open(Context c, RadioService svc, Runnable shareReport) {
        if (svc == null) return;
        BuildIdentity.Info b = BuildIdentity.gather(c);
        TxReport.Gate g = TxTestSession.gate(svc, b);
        if (!g.ok) { info(c, "Transmit test session", g.reason + "\n\n" + b.text()); return; }
        if (!svc.isReady()) { info(c, "Transmit test session", "Take over the radio first (Device page)."); return; }
        new TxTestScreen(c, svc, shareReport).safety(b, g);
    }

    private static void info(Context c, String title, String text) {
        LinearLayout b = Neo.sheetBody(c, title); b.addView(Neo.sheetHint(c, text));
        final android.app.AlertDialog[] d = new android.app.AlertDialog[1];
        TextView ok = Neo.button(c, "OK", null, Neo.CYAN); ok.setOnClickListener(v -> d[0].dismiss());
        b.addView(ok, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)));
        d[0] = Neo.showSheet(c, b);
    }

    /** The rules first: each one switched on by the tester, then Start. */
    private void safety(BuildIdentity.Info b, TxReport.Gate g) {
        LinearLayout body = Neo.sheetBody(c, "Transmit test session");
        body.addView(Neo.sheetHint(c, "Guides the transmit tests of the certification guide (section 3.3): an FM key, the release check, the limit check (the session uses its own 28 s limit), frequency and deviation, a DMR group call on DMR phones, and heat. SignalDeck keys the radio itself and records every key; you say what the receiving radio heard. The result, tx-report.json, goes into the device report. Your callsign and DMR ID are removed from it."));
        TextView kind = Neo.label(c, (g.fork ? "⚠ " : "") + b.text().trim(), g.fork ? Neo.AMBER : Neo.MUTED, 12, false); kind.setTypeface(Neo.mono(c)); kind.setTextSize(11);
        body.addView(kind);
        if (g.fork) body.addView(Neo.sheetHint(c, "This is not an official build: the report says so, with the commit and repository above, so the maintainer can rebuild exactly what you tested."));
        body.addView(Neo.sheetSection(c, "Before the first key"));
        final Neo.NeonSwitch[] sw = new Neo.NeonSwitch[TxReport.SAFETY.length];
        for (int i = 0; i < sw.length; i++) {
            sw[i] = new Neo.NeonSwitch(c); sw[i].setAccent(Neo.AMBER);
            LinearLayout row = Neo.switchRow(c, "alert", Neo.AMBER, TxReport.SAFETY[i], null, sw[i]);
            row.setBackground(Neo.rowCard(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = Theme.dp(c, 6);
            body.addView(row, lp);
        }
        final android.app.AlertDialog[] d = new android.app.AlertDialog[1];
        TextView start = Neo.button(c, "Start the session", "ptt", Neo.RED);
        start.setOnClickListener(v -> {
            for (Neo.NeonSwitch s : sw) if (!s.isChecked()) { android.widget.Toast.makeText(c, "Confirm every rule first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            d[0].dismiss(); run(b, System.currentTimeMillis());
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 52)); sl.topMargin = Theme.dp(c, 10);
        body.addView(start, sl);
        TextView later = Neo.button(c, "Not now", null, Neo.CYAN); later.setOnClickListener(v -> d[0].dismiss());
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); ll.topMargin = Theme.dp(c, 8);
        body.addView(later, ll);
        d[0] = Neo.showSheet(c, body);
    }

    private void run(BuildIdentity.Info b, long safetyMs) {
        session = new TxTestSession(svc, c, this);
        new Thread(() -> {
            String err = null; java.io.File f = null;
            try { session.run(b, safetyMs); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            try { if (svc.pttHeld()) svc.ptt(false); } catch (Throwable ignored) { }
            try { f = TxTestSession.save(c, svc, session.report); } catch (Throwable t) { err = (err == null ? "" : err + "; ") + "saving: " + t.getMessage(); }
            final String e = err; final java.io.File file = f;
            ui.post(() -> result(file, e));
        }, "tx-test-session").start();
    }

    private void result(java.io.File f, String err) {
        LinearLayout body = Neo.sheetBody(c, "Transmit test results");
        StringBuilder s = new StringBuilder();
        for (TxReport.Step st : session.report.steps) s.append(st.title).append(": ").append(st.result).append('\n');
        if (err != null) s.append("\nProblem: ").append(err).append('\n');
        TextView t = Neo.label(c, s.toString().trim(), Neo.TEXT, 14, false); body.addView(t);
        body.addView(Neo.sheetHint(c, f != null ? "Saved as " + TxReport.FILE + " in the device report (Download/DMR/DeviceReport/" + f.getParentFile().getName() + "), with a readable section in report.txt and 00_SUMMARY.txt. Attach the report zip to your transmit test results issue or pull request." : "The results could not be saved."));
        final android.app.AlertDialog[] d = new android.app.AlertDialog[1];
        if (f != null) {
            TextView zip = Neo.button(c, "Share the report zip", "send", Neo.GREEN); zip.setOnClickListener(v -> { d[0].dismiss(); if (shareReport != null) shareReport.run(); });
            body.addView(zip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 50)));
            TextView json = Neo.button(c, "Share " + TxReport.FILE + " alone", "send", Neo.CYAN);
            json.setOnClickListener(v -> { d[0].dismiss(); shareJson(f); });
            LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 50)); jl.topMargin = Theme.dp(c, 8);
            body.addView(json, jl);
        }
        TextView close = Neo.button(c, "Close", null, Neo.CYAN); close.setOnClickListener(v -> d[0].dismiss());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); cl.topMargin = Theme.dp(c, 8);
        body.addView(close, cl);
        d[0] = Neo.showSheet(c, body);
    }

    private void shareJson(java.io.File f) {
        try {
            java.io.File copy = TxTestSession.shareCopy(c, f);
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", copy);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json").putExtra(android.content.Intent.EXTRA_STREAM, u)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "SignalDeck transmit test: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(android.content.Intent.createChooser(i, "Share " + TxReport.FILE));
        } catch (Throwable t) { android.widget.Toast.makeText(c, "Share failed: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
    }

    // ---- TxTestSession.Ui ------------------------------------------------------------------------------------------

    @Override public int choose(String title, String text, String... buttons) throws InterruptedException {
        final CountDownLatch l = new CountDownLatch(1); final int[] pick = { -1 };
        ui.post(() -> {
            LinearLayout body = Neo.sheetBody(c, title); body.addView(Neo.sheetHint(c, text));
            final android.app.AlertDialog[] d = new android.app.AlertDialog[1];
            for (int i = 0; i < buttons.length; i++) {
                final int w = i; String lab = buttons[i];
                int accent = lab.startsWith("Stop") ? Neo.RED : lab.startsWith("Key") || lab.startsWith("Hold") || lab.startsWith("Start") ? Neo.AMBER : i == 0 ? Neo.GREEN : Neo.CYAN;
                TextView b = Neo.button(c, lab, null, accent);
                b.setOnClickListener(v -> { pick[0] = w; d[0].dismiss(); l.countDown(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); lp.topMargin = Theme.dp(c, i == 0 ? 4 : 8);
                body.addView(b, lp);
            }
            d[0] = Neo.showSheet(c, body); d[0].setCancelable(false);
        });
        l.await(); return pick[0];
    }

    @Override public String[] form(String title, String text, String[] labels, String[] values, boolean[] numeric) throws InterruptedException {
        final CountDownLatch l = new CountDownLatch(1); final String[][] out = { null };
        ui.post(() -> {
            LinearLayout body = Neo.sheetBody(c, title); body.addView(Neo.sheetHint(c, text));
            final EditText[] e = new EditText[labels.length];
            for (int i = 0; i < labels.length; i++) {
                body.addView(Neo.sheetSection(c, labels[i]));
                e[i] = new EditText(c); e[i].setTextColor(Neo.TEXT); e[i].setHintTextColor(Neo.DIM); e[i].setTypeface(Neo.mono(c)); e[i].setText(values[i]);
                e[i].setBackground(Neo.rowCard(c)); int p = Theme.dp(c, 10); e[i].setPadding(p, p, p, p);
                e[i].setInputType(numeric[i] ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                body.addView(e[i], new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final android.app.AlertDialog[] d = new android.app.AlertDialog[1];
            TextView ok = Neo.button(c, "Continue", null, Neo.GREEN);
            ok.setOnClickListener(v -> { String[] r = new String[e.length]; for (int i = 0; i < e.length; i++) r[i] = e[i].getText().toString(); out[0] = r; d[0].dismiss(); l.countDown(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); lp.topMargin = Theme.dp(c, 12);
            body.addView(ok, lp);
            TextView skip = Neo.button(c, "Leave empty", null, Neo.CYAN);
            skip.setOnClickListener(v -> { out[0] = com.macdmr.radio.TxTestSession.leftEmpty(e.length); d[0].dismiss(); l.countDown(); });   // empty answers, not a stop
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); sp.topMargin = Theme.dp(c, 8);
            body.addView(skip, sp);
            d[0] = Neo.showSheet(c, body); d[0].setCancelable(false);
        });
        l.await(); return out[0];
    }

    @Override public String[] pickRecording() throws InterruptedException {
        if (!(c instanceof com.macdmr.radio.MainActivity)) return null;
        final CountDownLatch l = new CountDownLatch(1); final String[][] out = { null };
        final android.app.AlertDialog[] wait = new android.app.AlertDialog[1];
        ui.post(() -> {
            LinearLayout body = Neo.sheetBody(c, "Recording");                    // under the picker; its Back gives no callback, so this sheet ends the wait
            final TextView line = Neo.label(c, "Choose the file in the picker. SignalDeck reads it once to compute its SHA-256; the file itself is not copied.", Neo.MUTED, 13, false);
            body.addView(line);
            TextView none = Neo.button(c, "No file after all", null, Neo.CYAN); none.setOnClickListener(v -> { wait[0].dismiss(); l.countDown(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)); lp.topMargin = Theme.dp(c, 12);
            body.addView(none, lp);
            wait[0] = Neo.showSheet(c, body); wait[0].setCancelable(false);
            ((com.macdmr.radio.MainActivity) c).pickDocument(uri -> {
                line.setText("Computing the SHA-256…");
                new Thread(() -> {
                    try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                        String name = uri.getLastPathSegment();
                        try (android.database.Cursor q = c.getContentResolver().query(uri, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) { if (q != null && q.moveToFirst()) name = q.getString(0); } catch (Throwable ignored) { }
                        String[] h = TxReport.sha256(in);
                        out[0] = new String[] { name, h[0], h[1] };
                    } catch (Throwable t) { ui.post(() -> android.widget.Toast.makeText(c, "Could not read the file: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show()); }
                    ui.post(() -> wait[0].dismiss());
                    l.countDown();
                }, "tx-test-hash").start();
            });
        });
        l.await();
        return out[0];
    }

    @Override public void keying(String text) {
        ui.post(() -> {
            if (keySheet == null) {
                LinearLayout body = Neo.sheetBody(c, "Transmitting");
                keyText = Neo.label(c, text, Neo.TEXT, 15, false); body.addView(keyText);
                TextView stop = Neo.button(c, "Release now", "stop", Neo.RED);
                stop.setOnClickListener(v -> { if (session != null) session.releaseNow(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 56)); lp.topMargin = Theme.dp(c, 14);
                body.addView(stop, lp);
                keySheet = Neo.showSheet(c, body); keySheet.setCancelable(false);
            } else keyText.setText(text);
        });
    }

    @Override public void keyingDone() { ui.post(() -> { if (keySheet != null) { keySheet.dismiss(); keySheet = null; keyText = null; } }); }
}
