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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.Logbook;
import com.macdmr.radio.ham.Pota;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT → POTA / SOTA: your activation (log, duplicate check, ADIF) and the activators on the air now. */
public final class PotaDialog {
    private PotaDialog() { }

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, "POTA / SOTA");   // i18n: program names
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_pota_hint)));
        final AlertDialog[] self = { null };
        // ---- your activation ----
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_pota_your_activation)));
        Pota.Activation a = Pota.active(c);
        if (a != null) {
            String day = Pota.utcDay(System.currentTimeMillis());
            int today = svc.logbook().activation(a.ref, day).size(), need = Pota.needed(a.program);
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(Neo.tint(c, Neo.GREEN, 12, 1)); card.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (12 * d));
            card.addView(Neo.text(c, a.program + " " + a.ref, Neo.GREEN_LIGHT, 20, Neo.display(c)));
            if (!a.name.isEmpty()) card.addView(Neo.text(c, a.name, Neo.TEXT, 15, Neo.uiMedium(c)));
            card.addView(Neo.text(c, Txt.s(today >= need ? R.string.modes_pota_since_counts : R.string.modes_pota_since, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(a.since)), today, need), today >= need ? Neo.GREEN : Neo.MUTED, 14, Neo.uiMedium(c)));
            body.addView(card);
            LinearLayout r = row(c, body);
            btn(c, r, Txt.s(R.string.modes_pota_log_contact), "plus", Neo.GREEN, v -> { if (self[0] != null) self[0].dismiss(); log(c, svc, null); });
            btn(c, r, Txt.s(R.string.modes_log_export), "send", Neo.CYAN, v -> export(c, svc, a));
            LinearLayout r2 = row(c, body);
            btn(c, r2, Txt.s(R.string.modes_pota_end_activation), "stop", Neo.RED, v -> new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_pota_end_confirm, a.ref)).setMessage(Txt.s(R.string.modes_pota_end_message)).setPositiveButton(Txt.s(R.string.modes_pota_end), (dd, w) -> { Pota.end(c); if (self[0] != null) self[0].dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        } else {
            body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_pota_start_hint)));
            LinearLayout r = row(c, body);
            btn(c, r, Txt.s(R.string.modes_pota_activate_park_button), "map", Neo.GREEN, v -> { if (self[0] != null) self[0].dismiss(); chooseRef(c, svc, Pota.POTA); });
            btn(c, r, Txt.s(R.string.modes_pota_activate_summit_button), "signal", Neo.AMBER, v -> { if (self[0] != null) self[0].dismiss(); chooseRef(c, svc, Pota.SOTA); });
        }
        // ---- activators on the air ----
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_pota_on_air)));
        final TextView status = Neo.label(c, Txt.s(R.string.modes_pota_loading), Neo.MUTED, 14, false); body.addView(status);
        final LinearLayout list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        self[0] = Neo.showSheet(c, body);
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<String> problems = new ArrayList<>(); List<Pota.Spot> spots = Pota.spots(problems);
            h.post(() -> {
                int workable = 0; for (Pota.Spot s : spots) if (s.workable()) workable++;
                String line = workable == 0 ? (spots.isEmpty() ? Txt.s(R.string.modes_pota_nobody) : Txt.p(R.plurals.modes_pota_nobody_others, spots.size(), spots.size())) : Txt.s(R.string.modes_pota_workable, workable);
                status.setText(problems.isEmpty() ? line : line + "  " + android.text.TextUtils.join(Txt.s(R.string.modes_list_separator), problems));
                SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
                int shown = 0;
                for (Pota.Spot s : spots) {
                    if (++shown > 40) break;
                    String sub = String.format(Locale.US, "%s %.4f MHz %s · %s %s", s.program, s.freqHz / 1e6, s.mode == null ? "" : s.mode, s.ref, s.name) + (s.where == null || s.where.isEmpty() ? "" : " (" + s.where + ")") + (s.time > 0 ? " · " + hm.format(new Date(s.time)) : "");   // i18n: spot data and units
                    LinearLayout t = Neo.tile(c, s.program.equals(Pota.SOTA) ? "signal" : "map", s.workable() ? Neo.GREEN : Neo.DIM, s.call, sub);
                    if (!s.workable()) t.setAlpha(0.6f);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
                    list.addView(t, lp);
                    t.setOnClickListener(v -> spotActions(c, svc, s, self[0]));
                }
            });
        }, "pota-spots").start();
    }

    private static void spotActions(Context c, RadioService svc, Pota.Spot s, AlertDialog sheet) {
        List<String> items = new ArrayList<>();
        if (s.workable()) items.add(Txt.s(R.string.modes_pota_tune_to, String.format(Locale.US, "%.4f", s.freqHz / 1e6)));
        items.add(Txt.s(R.string.modes_pota_log_hunting, s.call, s.ref));
        new AlertDialog.Builder(c).setTitle(s.call + " · " + s.ref).setItems(items.toArray(new String[0]), (dd, w) -> {
            if (s.workable() && w == 0) {
                ChannelStore.Channel v = new ChannelStore.Channel(); v.type = 1; v.rxFreq = v.txFreq = (int) s.freqHz; v.band = 1; v.power = 1; v.sq = 2; v.name = s.call + " " + s.ref;
                svc.applyVfo(v); Toast.makeText(c, Txt.s(R.string.modes_pota_tuned, s.call), Toast.LENGTH_SHORT).show();
            } else { if (sheet != null) sheet.dismiss(); log(c, svc, s); }
        }).show();
    }

    private static void chooseRef(Context c, RadioService svc, String program) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(Pota.POTA.equals(program) ? R.string.modes_pota_activate_park : R.string.modes_pota_activate_summit), "radio");
        final EditText ref = field(c, body, Txt.s(Pota.POTA.equals(program) ? R.string.modes_pota_park_ref : R.string.modes_pota_summit_ref), "");
        ref.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        TextView go = Neo.button(c, Txt.s(R.string.modes_pota_start), "check", Neo.GREEN); body.addView(go);
        final LinearLayout near = new LinearLayout(c); near.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog dlg = Neo.showSheet(c, body);
        go.setOnClickListener(v -> {
            final String r = ref.getText().toString().trim().toUpperCase(Locale.US);
            if (!program.equals(Pota.programOf(r))) { Toast.makeText(c, Txt.s(R.string.modes_pota_bad_ref, program), Toast.LENGTH_SHORT).show(); return; }
            go.setEnabled(false);
            new Thread(() -> { String name = Pota.lookup(r); new Handler(Looper.getMainLooper()).post(() -> {
                go.setEnabled(true);
                if (name == null) { new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_pota_not_found, r)).setMessage(Txt.s(R.string.modes_pota_not_found_message, program)).setPositiveButton(Txt.s(R.string.common_start), (dd, w) -> { Pota.start(c, r, ""); dlg.dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show(); return; }
                Pota.start(c, r, name); dlg.dismiss(); Toast.makeText(c, Txt.s(R.string.modes_pota_activating, r, name), Toast.LENGTH_SHORT).show(); show(c, svc);
            }); }, "pota-lookup").start();
        });
        if (!Pota.POTA.equals(program)) return;
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_pota_parks_near)));
        final TextView st = Neo.label(c, Txt.s(R.string.modes_pota_looking), Neo.MUTED, 14, false); body.addView(st); body.addView(near);
        final android.location.Location me = svc.lastKnownLocation();
        if (me == null) { st.setText(Txt.s(R.string.modes_pota_needs_fix)); return; }
        new Thread(() -> {
            List<Pota.Park> ps; try { ps = Pota.parksNear(me.getLatitude(), me.getLongitude()); } catch (Throwable t) { ps = null; }
            final List<Pota.Park> f = ps;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (f == null) { st.setText(Txt.s(R.string.modes_pota_unreachable_parks)); return; }
                st.setText(Txt.s(f.isEmpty() ? R.string.modes_pota_no_park : R.string.modes_pota_tap_park));
                for (int i = 0; i < Math.min(25, f.size()); i++) {
                    Pota.Park p = f.get(i);
                    LinearLayout t = Neo.tile(c, "map", Neo.GREEN, p.ref, p.name + String.format(Locale.US, " · %.1f km", p.km));   // i18n: park name and distance
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
                    near.addView(t, lp); t.setOnClickListener(v -> ref.setText(p.ref));
                }
            });
        }, "pota-parks").start();
    }

    /** Quick log: call, reports, notes; the frequency the radio is on (or the spot's); the duplicate check. */
    private static void log(Context c, RadioService svc, Pota.Spot hunted) {
        float d = Theme.dp(c);
        final Pota.Activation a = Pota.active(c);
        LinearLayout body = Neo.sheetBody(c, hunted != null ? Txt.s(R.string.modes_pota_log_call, hunted.call) : Txt.s(R.string.modes_pota_log_contact), "diag");
        ChannelStore.Channel cur = svc.current();
        final long freq = hunted != null ? hunted.freqHz : cur != null ? cur.rxFreq : 0;
        List<String> facts = new ArrayList<>(); facts.add(String.format(Locale.US, "%.4f MHz FM", freq / 1e6));   // i18n: frequency and mode
        if (a != null) facts.add(Txt.s(R.string.modes_pota_under_activation, a.ref));
        if (hunted != null) facts.add(Txt.s(R.string.modes_pota_their_ref, hunted.program, hunted.ref));
        body.addView(Neo.sheetHint(c, String.join(Txt.s(R.string.modes_middot_separator), facts)));
        final EditText call = field(c, body, Txt.s(R.string.modes_log_f_call), hunted != null ? hunted.call : "");
        call.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText sent = field(c, body, Txt.s(R.string.modes_pota_f_sent), "59"), rcvd = field(c, body, Txt.s(R.string.modes_log_f_rcvd), "59");
        final EditText theirRef = field(c, body, Txt.s(R.string.modes_pota_f_their_ref), hunted != null ? hunted.ref : "");
        theirRef.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText notes = field(c, body, Txt.s(R.string.modes_pota_f_notes), "");
        TextView save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (50 * d)); sl.topMargin = (int) (12 * d);
        body.addView(save, sl);
        final AlertDialog dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            String cs = call.getText().toString().trim().toUpperCase(Locale.US);
            if (Logbook.baseCall(cs) == null) { Toast.makeText(c, Txt.s(R.string.modes_pota_enter_call), Toast.LENGTH_SHORT).show(); return; }
            Runnable doSave = () -> {
                Logbook.Entry e = new Logbook.Entry(); e.ts = System.currentTimeMillis(); e.call = cs; e.freqHz = freq; e.mode = "FM"; e.qso = true;
                e.rstSent = sent.getText().toString().trim(); e.rstRcvd = rcvd.getText().toString().trim(); e.notes = notes.getText().toString().trim(); e.source = a != null ? a.program + " activation" : "POTA/SOTA hunting";   // i18n: stored in the logbook source column
                String tr = theirRef.getText().toString().trim().toUpperCase(Locale.US); if (Pota.programOf(tr) != null) { e.sig = Pota.programOf(tr); e.sigInfo = tr; }
                if (a != null) { e.mySig = a.program; e.mySigInfo = a.ref; }
                svc.logbook().save(e); dlg.dismiss();
                int n = a == null ? 0 : svc.logbook().activation(a.ref, Pota.utcDay(e.ts)).size();
                Toast.makeText(c, a != null ? Txt.s(R.string.modes_pota_logged_count, cs, n, Pota.needed(a.program)) : Txt.s(R.string.modes_pota_logged, cs), Toast.LENGTH_SHORT).show();
                show(c, svc);
            };
            if (a != null && svc.logbook().isDupe(cs, freq, System.currentTimeMillis(), a.ref))
                new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_pota_dupe, cs)).setMessage(Txt.s(R.string.modes_pota_dupe_message, a.ref)).setPositiveButton(Txt.s(R.string.modes_pota_log_again), (dd, w) -> doSave.run()).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            else doSave.run();
        });
    }

    /** POTA's file name: CALL@REF-YYYYMMDD.adi (one per UTC day), saved to Download/DMR/Logbook and shared. */
    private static void export(Context c, RadioService svc, Pota.Activation a) {
        try {
            String me = svc.packet() == null ? "" : svc.packet().callsign();
            String day = Pota.utcDay(System.currentTimeMillis());
            List<Logbook.Entry> es = svc.logbook().activation(a.ref, null);
            if (es.isEmpty()) { Toast.makeText(c, Txt.s(R.string.modes_pota_no_contacts, a.ref), Toast.LENGTH_SHORT).show(); return; }
            String adif = Logbook.adif(es, com.macdmr.radio.BuildConfig.VERSION_NAME, me);
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/Logbook"); dir.mkdirs();
            java.io.File out = new java.io.File(dir, (me.isEmpty() ? "MYCALL" : me) + "@" + a.ref.replace('/', '_') + "-" + day + ".adi");   // i18n: file name
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8)) { w.write(adif); }
            Toast.makeText(c, me.isEmpty() ? Txt.s(R.string.modes_pota_exported_no_call, out.getName()) : Txt.s(R.string.modes_log_exported, out.getName()), Toast.LENGTH_LONG).show();
            c.startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, out.getName()).putExtra(android.content.Intent.EXTRA_TEXT, adif), Txt.s(R.string.modes_pota_send_log)));
        } catch (Throwable t) { Toast.makeText(c, Txt.s(R.string.modes_log_export_failed, String.valueOf(t.getMessage())), Toast.LENGTH_LONG).show(); }
    }

    private static LinearLayout row(Context c, LinearLayout body) { LinearLayout r = new LinearLayout(c); body.addView(r); return r; }
    private static void btn(Context c, LinearLayout r, String label, String icon, int accent, android.view.View.OnClickListener l) {
        float d = Theme.dp(c); TextView b = Neo.button(c, label, icon, accent); b.setTextSize(14); Neo.fit(b); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (3 * d), (int) (6 * d), (int) (3 * d), 0); r.addView(b, lp);
    }
    private static EditText field(Context c, LinearLayout body, String label, String value) {
        float d = Theme.dp(c);
        body.addView(Neo.sheetSection(c, label));
        EditText e = new EditText(c); e.setText(value); e.setSingleLine(true);
        e.setTextColor(Neo.TEXT); e.setHintTextColor(Neo.DIM); e.setTextSize(16); e.setBackground(Neo.rowCard(c)); e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        body.addView(e);
        return e;
    }
}
