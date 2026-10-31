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
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.winlink.B2Message;
import com.macdmr.radio.winlink.Winlink;
import com.macdmr.radio.winlink.WinlinkStore;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT → Winlink e-mail: inbox, outbox and sent mail, compose, and a session over the internet or a radio gateway. */
public final class WinlinkDialog {
    private WinlinkDialog() { }
    static final String ISSUES = "https://github.com/IIMacGyverII/SignalDeck/issues";   // i18n: URL

    public static void show(Context c, RadioService svc) {
        final Winlink w = svc.winlink();
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_wl_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_wl_hint)));
        LinearLayout beta = Neo.tile(c, "alert", Neo.AMBER, Txt.s(R.string.modes_wl_beta_title),
                Txt.s(R.string.modes_wl_beta_text));
        beta.setOnClickListener(v -> { try { c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ISSUES))); } catch (Exception e) { toast(c, ISSUES); } });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.bottomMargin = (int) (8 * d);
        body.addView(beta, bl);
        final TextView state = Neo.label(c, "", Theme.CYAN, 14, true); body.addView(state);
        final TextView prog = Neo.label(c, "", Neo.MUTED, 12.5f, false); body.addView(prog);
        final AlertDialog[] self = { null }; final Runnable[] later = { null };
        LinearLayout r1 = new LinearLayout(c); body.addView(r1);
        final TextView net = btn(c, r1, Txt.s(R.string.modes_wl_connect_internet), "globe", Neo.CYAN), rf = btn(c, r1, Txt.s(R.string.modes_wl_connect_radio), "radio", Neo.GREEN);
        LinearLayout r2 = new LinearLayout(c); body.addView(r2);
        btn(c, r2, Txt.s(R.string.modes_wl_new_message), "plus", Neo.AMBER).setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); compose(c, svc, "", "", "", ""); });
        btn(c, r2, Txt.s(R.string.modes_wl_gateways), "map", Neo.GREEN).setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); gateways(c, svc); });
        btn(c, r2, Txt.s(R.string.modes_ft8_settings), "menu", Neo.VIOLET).setOnClickListener(v -> settings(c, svc, () -> { if (later[0] != null) later[0].run(); }));
        TextView logB = Neo.button(c, Txt.s(R.string.modes_wl_session_log), "report", Neo.MUTED); logB.setTextSize(14);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d)); ll.topMargin = (int) (6 * d);
        body.addView(logB, ll);
        logB.setOnClickListener(v -> sessionLog(c, svc));
        final LinearLayout lists = new LinearLayout(c); lists.setOrientation(LinearLayout.VERTICAL); body.addView(lists);

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] wasBusy = { w.busy() };
        final Runnable refresh = () -> {
            boolean busy = w.busy();
            state.setText(w.state());
            prog.setText(busy ? w.progress() : w.lastWasDiag() && w.lastSession() > 0 ? Txt.s(R.string.modes_wl_log_recorded)
                    : (w.passwordSet() ? "" : Txt.s(R.string.modes_wl_no_password)));
            net.setText(Txt.s(busy ? R.string.common_stop : R.string.modes_wl_connect_internet)); rf.setEnabled(!busy); rf.setAlpha(busy ? 0.4f : 1f);
            if (wasBusy[0] != busy || lists.getChildCount() == 0) { wasBusy[0] = busy; fillLists(c, svc, lists, self); }
        };
        final boolean[] queued = { false };                                    // at most four refreshes a second, the last one never dropped
        w.setListener(() -> h.post(() -> { if (queued[0]) return; queued[0] = true; h.postDelayed(() -> { queued[0] = false; refresh.run(); }, 250); }));
        net.setOnClickListener(v -> {
            if (w.busy()) { w.cancel(); return; }
            askDiag(c, w, () -> { String e = w.connectInternet(); if (e != null) toast(c, e); refresh.run(); });
        });
        rf.setOnClickListener(v -> askDiag(c, w, () -> radioConnect(c, svc, refresh)));
        later[0] = refresh;
        refresh.run();
        self[0] = Neo.showSheet(c, body);
        self[0].setOnDismissListener(x -> w.setListener(null));
    }

    private static void fillLists(Context c, RadioService svc, LinearLayout lists, AlertDialog[] self) {
        lists.removeAllViews();
        WinlinkStore st = svc.winlink().store();
        float d = Theme.dp(c);
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        String[][] folders = { { WinlinkStore.INBOX, Txt.s(R.string.modes_wl_inbox), "inbox" }, { WinlinkStore.OUTBOX, Txt.s(R.string.modes_wl_outbox), "send" }, { WinlinkStore.SENT, Txt.s(R.string.modes_wl_sent), "check" } };
        for (String[] fo : folders) {
            List<WinlinkStore.Item> items = st.list(fo[0]);
            lists.addView(Neo.sheetSection(c, items.isEmpty() ? Txt.s(R.string.modes_wl_folder_empty, fo[1]) : Txt.s(R.string.modes_wl_folder_count, fo[1], items.size())));
            int shown = 0;
            for (WinlinkStore.Item it : items) {
                if (++shown > 40) { lists.addView(Neo.sheetHint(c, Txt.p(R.plurals.modes_wl_older, items.size() - 40, items.size() - 40))); break; }
                boolean in = WinlinkStore.INBOX.equals(fo[0]); boolean unread = in && !st.isRead(it.m.mid);
                List<String> facts = new java.util.ArrayList<>();
                facts.add(in ? Txt.s(R.string.modes_wl_from, B2Message.shown(it.m.from)) : Txt.s(R.string.modes_wl_to, shownList(it.m.to)));
                long t = it.m.time();
                if (t > 0) facts.add(f.format(new Date(t)));
                if (!it.m.files.isEmpty()) facts.add("📎 " + it.m.files.size());
                String sub = String.join(Txt.s(R.string.modes_middot_separator), facts);
                LinearLayout tile = Neo.tile(c, fo[2], unread ? Neo.GREEN : in ? Neo.CYAN : Neo.MUTED, (unread ? "● " : "") + it.m.subject, sub);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
                lists.addView(tile, lp);
                tile.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); view(c, svc, it); });
            }
        }
    }

    static String shownList(List<String> l) { StringBuilder b = new StringBuilder(); for (String a : l) { if (b.length() > 0) b.append(", "); b.append(B2Message.shown(a)); } return b.length() == 0 ? "—" : b.toString(); }

    /** The imported gateway list, nearest first: tune and connect, or hand the frequencies to the packet finder. */
    static void gateways(Context c, RadioService svc) {
        final Winlink w = svc.winlink();
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_wl_gw_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_wl_gw_hint)));
        LinearLayout r = new LinearLayout(c); body.addView(r);
        btn(c, r, Txt.s(R.string.modes_wl_gw_open), "globe", Neo.CYAN).setOnClickListener(v -> { try { c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://winlink.org/RMSChannels"))); } catch (Exception e) { toast(c, "https://winlink.org/RMSChannels"); } });   // i18n: URL
        btn(c, r, Txt.s(R.string.modes_wl_gw_import), "import", Neo.GREEN).setOnClickListener(v -> {
            if (!(c instanceof com.macdmr.radio.MainActivity)) { toast(c, Txt.s(R.string.modes_wl_gw_from_app)); return; }
            ((com.macdmr.radio.MainActivity) c).pickDocument(uri -> new Thread(() -> {
                String msg;
                try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                    byte[] b = in.readAllBytes(); if (b.length > 8 << 20) throw new IllegalArgumentException(Txt.s(R.string.modes_wl_gw_too_large));
                    msg = w.importGateways(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) { msg = Txt.s(R.string.modes_wl_gw_import_failed, String.valueOf(e.getMessage())); }
                final String m = msg;
                new Handler(Looper.getMainLooper()).post(() -> { new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_wl_gw_title)).setMessage(m).setPositiveButton(Txt.s(R.string.common_ok), null).show(); gateways(c, svc); });
            }).start());
        });
        java.util.List<com.macdmr.radio.winlink.RmsList.Gateway> l = w.gateways();
        long at = w.gatewaysImportedAt();
        body.addView(Neo.sheetSection(c, l.isEmpty() ? Txt.s(R.string.modes_wl_gw_none) : Txt.p(R.plurals.modes_wl_gw_count, l.size(), l.size(), new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(at)))));
        final AlertDialog[] self = { null };
        if (!l.isEmpty()) {
            TextView pf = Neo.button(c, Txt.s(R.string.modes_wl_gw_finder), "search", Neo.VIOLET); pf.setTextSize(14);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d)); pl.bottomMargin = (int) (8 * d);
            body.addView(pf, pl);
            pf.setOnClickListener(v -> {
                java.util.LinkedHashSet<String> fr = new java.util.LinkedHashSet<>();
                for (com.macdmr.radio.winlink.RmsList.Gateway g : l) { if (fr.size() >= 10) break; fr.add(String.format(Locale.US, "%.4f", g.hz / 1e6)); }
                String e = svc.finder().setSettings(String.join(", ", fr), 5, 2);
                if (e != null) { toast(c, e); return; }
                if (self[0] != null) self[0].dismiss(); PacketFinderDialog.show(c, svc);
                toast(c, Txt.p(R.plurals.modes_wl_gw_finder_set, fr.size(), fr.size()));
            });
        }
        int shown = 0;
        for (com.macdmr.radio.winlink.RmsList.Gateway g : l) {
            if (++shown > 60) { body.addView(Neo.sheetHint(c, Txt.p(R.plurals.modes_wl_gw_farther, l.size() - 60, l.size() - 60))); break; }
            String dist = g.km < 0 ? "" : (Locale.getDefault().getCountry().equals("US") || Locale.getDefault().getCountry().equals("GB") ? Math.round(g.km * 0.621371) + " mi " : Math.round(g.km) + " km ") + com.macdmr.radio.ham.Maidenhead.compass(g.bearing) + " \u00B7 ";   // i18n: distance units
            String where = dist + String.format(Locale.US, "%.4f MHz", g.hz / 1e6);   // i18n: distance, bearing and frequency, units only
            if (!(g.hours.isEmpty() || g.hours.equals("00-23"))) where = Txt.s(R.string.modes_wl_gw_hours, where, g.hours);
            LinearLayout t = Neo.tile(c, "mail", Neo.GREEN, g.call, where + (g.qth.isEmpty() ? "" : "\n" + g.qth));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
            body.addView(t, lp);
            t.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(g.call).setItems(new String[] { Txt.s(R.string.modes_wl_gw_tune_connect, String.format(Locale.US, "%.4f", g.hz / 1e6)), Txt.s(R.string.modes_wl_gw_tune_listen), Txt.s(R.string.modes_wl_gw_use) }, (dd, which) -> {
                w.setGateway(g.call, "");
                if (which <= 1) { ChannelStore.Channel ch = new ChannelStore.Channel(); ch.type = 1; ch.rxFreq = ch.txFreq = g.hz; ch.band = 1; ch.power = 1; ch.sq = 2; ch.name = g.call; svc.applyVfo(ch, true); }
                if (self[0] != null) self[0].dismiss();
                if (which == 0) { new Handler(Looper.getMainLooper()).postDelayed(() -> { String e = w.connectRadio(g.call, ""); if (e != null) toast(c, e); }, 4500); toast(c, Txt.s(R.string.modes_wl_gw_tuning, String.format(Locale.US, "%.4f", g.hz / 1e6))); }
                show(c, svc);
            }).show());
        }
        if (!l.isEmpty()) {
            TextView clr = Neo.button(c, Txt.s(R.string.modes_wl_gw_remove), "stop", Neo.RED); clr.setTextSize(14);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d)); cl.topMargin = (int) (6 * d);
            body.addView(clr, cl);
            clr.setOnClickListener(v -> { w.clearGateways(); if (self[0] != null) self[0].dismiss(); gateways(c, svc); });
        }
        self[0] = Neo.showSheet(c, body);
    }

    private static void radioConnect(Context c, RadioService svc, Runnable refresh) {
        final Winlink w = svc.winlink();
        float d = Theme.dp(c);
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        TextView hint = Neo.label(c, Txt.s(R.string.modes_wl_radio_hint), Neo.MUTED, 13, false);
        v.addView(hint);
        final EditText gw = new EditText(c); gw.setHint(Txt.s(R.string.modes_wl_radio_gateway_hint)); gw.setText(w.gateway()); gw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); gw.setTextColor(Neo.TEXT); v.addView(gw);
        final EditText via = new EditText(c); via.setHint(Txt.s(R.string.modes_wl_radio_via_hint)); via.setText(w.via()); via.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); via.setTextColor(Neo.TEXT); v.addView(via);
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_wl_radio_title)).setView(v)
                .setPositiveButton(Txt.s(R.string.modes_term_connect), (dd, x) -> { String e = w.connectRadio(gw.getText().toString(), via.getText().toString()); if (e != null) toast(c, e); refresh.run(); })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static void settings(Context c, RadioService svc, Runnable after) {
        final Winlink w = svc.winlink();
        float d = Theme.dp(c);
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        v.addView(Neo.label(c, Txt.s(R.string.modes_wl_set_hint), Neo.MUTED, 13, false));
        final EditText pw = new EditText(c); pw.setHint(Txt.s(w.passwordSet() ? R.string.modes_wl_set_password_set : R.string.modes_wl_set_password)); pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); pw.setTextColor(Neo.TEXT); v.addView(pw);
        final EditText grid = new EditText(c); grid.setHint(Txt.s(R.string.modes_wl_set_grid)); grid.setText(w.grid()); grid.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); grid.setTextColor(Neo.TEXT); v.addView(grid);
        v.addView(Neo.label(c, Txt.s(R.string.modes_wl_set_grid_hint), Neo.MUTED, 12, false));
        final android.widget.CheckBox dg = new android.widget.CheckBox(c); dg.setText(Txt.s(R.string.modes_wl_set_diag)); dg.setTextColor(Neo.TEXT); dg.setChecked(w.diagEnabled()); v.addView(dg);
        final android.widget.CheckBox dc = new android.widget.CheckBox(c); dc.setText(Txt.s(R.string.modes_wl_set_diag_data)); dc.setTextColor(Neo.TEXT); dc.setChecked(w.diagContents()); v.addView(dc);
        v.addView(Neo.label(c, Txt.s(R.string.modes_wl_set_diag_hint), Neo.MUTED, 12, false));
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_wl_set_title)).setView(v)
                .setPositiveButton(Txt.s(R.string.common_save), (dd, x) -> {
                    String g = grid.getText().toString().trim();
                    if (!g.isEmpty() && !g.matches("(?i)[A-R]{2}[0-9]{2}([A-X]{2})?")) { toast(c, Txt.s(R.string.modes_wl_set_grid_invalid)); return; }
                    w.setPassword(pw.getText().toString()); w.setGrid(g); w.setDiag(dg.isChecked()); w.setDiagContents(dc.isChecked()); toast(c, Txt.s(R.string.modes_forms_saved)); after.run();
                })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static void sessionLog(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_wl_log_title), "diag");
        String t = svc.winlink().logText();
        TextView tv = Neo.text(c, t.isEmpty() ? Txt.s(R.string.modes_wl_log_none) : t, 0xFFCFE8D8, 12, Neo.mono(c));
        tv.setBackground(Neo.rowCard(c)); tv.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d)); tv.setTextIsSelectable(true);
        body.addView(tv);
        if (!t.isEmpty()) {
            TextView share = Neo.button(c, Txt.s(R.string.modes_wl_log_share), "send", Neo.CYAN); share.setTextSize(14);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (46 * d)); sl.topMargin = (int) (8 * d);
            body.addView(share, sl);
            share.setOnClickListener(v -> {
                try {
                    android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", svc.winlink().logFile());
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_SUBJECT, "SignalDeck Winlink log")   // i18n: report to the developers
                            .putExtra(Intent.EXTRA_TEXT, "SignalDeck Winlink session log (attached). It worked / it did not work: ").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);   // i18n: report to the developers
                    c.startActivity(Intent.createChooser(i, Txt.s(R.string.modes_wl_log_share_title)));
                } catch (Exception e) { toast(c, Txt.s(R.string.modes_sstv_share_failed, String.valueOf(e.getMessage()))); }
            });
            body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_wl_log_hint, ISSUES)));
        }
        Neo.showSheet(c, body);
    }

    /** The first connection ever: Winlink here is untested, offer the detailed log and ask for it back. */
    static void askDiag(Context c, Winlink w, Runnable go) {
        if (w.diagAsked() || w.diagEnabled()) { go.run(); return; }
        w.setDiagAsked();
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_wl_ask_title))
                .setMessage(Txt.s(R.string.modes_wl_ask_message))
                .setPositiveButton(Txt.s(R.string.modes_wl_ask_on), (dd, x) -> { w.setDiag(true); go.run(); })
                .setNegativeButton(Txt.s(R.string.common_not_now), (dd, x) -> go.run())
                .setOnCancelListener(x -> go.run()).show();
    }

    static void compose(Context c, RadioService svc, String to, String cc, String subject, String text) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_wl_compose_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_wl_compose_hint)));
        final EditText toE = field(c, body, Txt.s(R.string.modes_wl_f_to), to, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false);
        final EditText ccE = field(c, body, Txt.s(R.string.modes_wl_f_cc), cc, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false);
        final EditText subE = field(c, body, Txt.s(R.string.modes_wl_f_subject), subject, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        final EditText txt = field(c, body, Txt.s(R.string.modes_wl_f_message), text, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true);
        final TextView size = Neo.label(c, "", Neo.MUTED, 12, false); body.addView(size);
        txt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int e) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int e) { }
            @Override public void afterTextChanged(android.text.Editable s) { size.setText(Txt.p(R.plurals.modes_pager_chars, s.length(), s.length())); }
        });
        TextView save = Neo.button(c, Txt.s(R.string.modes_forms_winlink_put), "send", Neo.GREEN);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d)); sl.topMargin = (int) (10 * d);
        body.addView(save, sl);
        final AlertDialog dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            String e = svc.winlink().compose(toE.getText().toString(), ccE.getText().toString(), subE.getText().toString(), txt.getText().toString());
            if (e != null) { toast(c, e); return; }
            toast(c, Txt.s(R.string.modes_wl_in_outbox)); dlg.dismiss(); show(c, svc);
        });
    }

    private static void view(Context c, RadioService svc, WinlinkStore.Item it) {
        float d = Theme.dp(c);
        final B2Message m = it.m; final Winlink w = svc.winlink();
        if (WinlinkStore.INBOX.equals(it.folder)) w.store().markRead(m.mid);
        LinearLayout body = Neo.sheetBody(c, m.subject);
        long t = m.time();
        List<String> head = new java.util.ArrayList<>();
        head.add(Txt.s(R.string.modes_wl_from, B2Message.shown(m.from))); head.add(Txt.s(R.string.modes_wl_to, shownList(m.to)));
        if (!m.cc.isEmpty()) head.add(Txt.s(R.string.modes_wl_cc, shownList(m.cc)));
        if (t > 0) head.add(new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(t)));
        head.add(Txt.s(R.string.modes_wl_mid, m.mid));
        body.addView(Neo.sheetHint(c, String.join("\n", head)));
        TextView tv = Neo.text(c, m.bodyText().replace("\r\n", "\n"), Neo.TEXT, 15, Neo.uiMedium(c)); tv.setTextIsSelectable(true);
        tv.setBackground(Neo.rowCard(c)); tv.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        body.addView(tv);
        for (B2Message.Attachment a : m.files) {
            LinearLayout tile = Neo.tile(c, "import", Neo.CYAN, "📎 " + a.name, a.data.length < 1024 ? Txt.p(R.plurals.modes_wl_attach_bytes, a.data.length, a.data.length) : Txt.s(R.string.modes_wl_attach_kb, (a.data.length + 1023) / 1024));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (6 * d);
            body.addView(tile, lp);
            tile.setOnClickListener(v -> shareAttachment(c, a));
        }
        final AlertDialog[] self = { null };
        LinearLayout r = new LinearLayout(c); body.addView(r);
        if (WinlinkStore.INBOX.equals(it.folder)) {
            btn(c, r, Txt.s(R.string.modes_forms_reply), "messages", Neo.GREEN).setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); compose(c, svc, B2Message.shown(m.from), "", m.subject.startsWith("Re:") ? m.subject : "Re: " + m.subject, "\n\n--- " + B2Message.shown(m.from) + " wrote:\n" + m.bodyText().replace("\r\n", "\n")); });   // i18n: Winlink message
        }
        btn(c, r, Txt.s(R.string.modes_wl_forward), "send", Neo.CYAN).setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); compose(c, svc, "", "", "Fw: " + m.subject, "\n\n--- Forwarded message from " + B2Message.shown(m.from) + ":\n" + m.bodyText().replace("\r\n", "\n")); });   // i18n: Winlink message
        btn(c, r, Txt.s(R.string.common_delete), "stop", Neo.RED).setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(Txt.s(WinlinkStore.OUTBOX.equals(it.folder) ? R.string.modes_wl_delete_outbox : R.string.modes_wl_delete))
                .setPositiveButton(Txt.s(R.string.common_delete), (dd, x) -> { w.store().delete(it.folder, m.mid); if (self[0] != null) self[0].dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        self[0] = Neo.showSheet(c, body);
    }

    private static void shareAttachment(Context c, B2Message.Attachment a) {
        try {
            File dir = new File(c.getCacheDir(), "share"); dir.mkdirs();
            String name = a.name.replaceAll("[\\\\/:*?\"<>|]", "_"); if (name.isEmpty()) name = "attachment";
            File f = new File(dir, name);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(a.data); }
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
            String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "");
            if (type == null) type = "application/octet-stream";
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(send, a.name); ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { view });
            c.startActivity(ch);
        } catch (Exception e) { toast(c, Txt.s(R.string.modes_wl_open_failed, String.valueOf(e.getMessage()))); }
    }

    private static EditText field(Context c, LinearLayout target, String label, String value, int type, boolean multi) {
        float d = Theme.dp(c);
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); r.setBackground(Neo.rowCard(c)); r.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (4 * d));
        r.addView(Neo.label(c, label, 0xFF9AA4AE, 12, false));
        EditText e = new EditText(c); e.setText(value); e.setInputType(type); e.setTextSize(16); e.setTextColor(Neo.TEXT); e.setBackground(null);
        if (multi) { e.setMinLines(5); e.setGravity(android.view.Gravity.TOP); } else e.setSingleLine(true);
        r.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (6 * d);
        target.addView(r, lp);
        return e;
    }

    private static TextView btn(Context c, LinearLayout row, String label, String icon, int accent) {
        float d = Theme.dp(c);
        TextView b = Neo.button(c, label, icon, accent); b.setTextSize(14); Neo.fit(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0);
        row.addView(b, lp);
        return b;
    }
    private static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }
    @SuppressWarnings("unused") private static void gone(View v) { v.setVisibility(View.GONE); }
}
