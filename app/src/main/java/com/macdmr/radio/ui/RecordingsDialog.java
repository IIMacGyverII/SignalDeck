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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.RecordingDecodeJob;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PKT → Decode a recording (SSTV or APRS): the recordings SignalDeck made (pass recordings first in time order, REC
 * too) or any WAV file; then progress and what was found. Decoding runs in the background and never touches the radio.
 */
public final class RecordingsDialog {
    private RecordingsDialog() { }

    public static void show(Context c, RadioService svc, String mode) {
        RecordingDecodeJob running = RecordingDecodeJob.current();
        if (running != null && !running.done) { progress(c, running); return; }
        float d = Theme.dp(c);
        boolean sstv = RecordingDecodeJob.SSTV.equals(mode);
        LinearLayout box = Neo.sheetBody(c, Txt.s(sstv ? R.string.modes_recdec_title_sstv : R.string.modes_recdec_title_aprs), "rec");
        box.addView(Neo.sheetHint(c, Txt.s(sstv ? R.string.modes_recdec_hint_sstv : R.string.modes_recdec_hint_aprs)));
        final AlertDialog[] self = { null };
        List<File> recs = RecordingDecodeJob.recordings(40);
        box.addView(Neo.sheetSection(c, Txt.s(recs.isEmpty() ? R.string.modes_recdec_none : R.string.modes_recdec_list)));
        if (recs.isEmpty()) box.addView(Neo.sheetHint(c, Txt.s(R.string.modes_recdec_none_hint)));
        TextView up = Neo.button(c, Txt.s(R.string.modes_recdec_upload), "import", Neo.AMBER); box.addView(up);
        up.setOnClickListener(v -> {
            if (!(c instanceof com.macdmr.radio.MainActivity)) return;
            if (self[0] != null) self[0].dismiss();
            ((com.macdmr.radio.MainActivity) c).pickDocument(uri -> copyAndBegin(c, svc, mode, uri));
        });
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        for (File f : recs) {
            long sec = Math.max(0, (f.length() - 44) / 16000);
            boolean pass = f.getName().contains("_pass"), uploaded = RecordingDecodeJob.UPLOADED.equals(f.getParentFile().getName());
            String size = f.getName().toLowerCase(Locale.US).endsWith(".wav") ? (sec >= 60 ? Txt.s(R.string.modes_recdec_minutes, (int) (sec / 60), (int) (sec % 60)) : Txt.s(R.string.modes_recdec_seconds, (int) sec)) : Txt.s(R.string.modes_recdec_kb, String.valueOf(Math.max(1, f.length() / 1024)));
            String sub = Txt.s(R.string.modes_recdec_row, f.getParentFile().getName(), when.format(new Date(f.lastModified())), size);
            LinearLayout t = Neo.tile(c, pass ? "sstv" : uploaded ? "import" : "rec", pass ? Neo.CYAN : uploaded ? Neo.AMBER : Neo.VIOLET, pass ? Txt.s(R.string.modes_recdec_pass) : f.getName(), sub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (8 * d);
            box.addView(t, lp);
            t.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); begin(c, svc, mode, f); });
        }
        self[0] = Neo.showSheet(c, box);
    }

    private static void copyAndBegin(Context c, RadioService svc, String mode, Uri uri) {
        new Thread(() -> {
            File out = null; String err = null;
            try {
                String name = CodeplugDialog.displayName(c, uri); if (name == null || name.isEmpty()) name = "recording.wav";
                try (InputStream in = c.getContentResolver().openInputStream(uri)) { out = RecordingDecodeJob.saveUpload(name, in); }   // kept in Download/DMR/Audio/Uploaded
            } catch (Throwable t) { err = t.getMessage() == null ? Txt.s(R.string.modes_recdec_unreadable) : t.getMessage(); }
            final File f = out; final String e = err;
            new Handler(Looper.getMainLooper()).post(() -> { if (e != null) Toast.makeText(c, e, Toast.LENGTH_LONG).show(); else begin(c, svc, mode, f); });
        }, "decode-copy").start();
    }

    private static void begin(Context c, RadioService svc, String mode, File f) {
        String e = RecordingDecodeJob.start(mode, f, svc);
        if (e != null) { Toast.makeText(c, e, Toast.LENGTH_LONG).show(); return; }
        progress(c, RecordingDecodeJob.current());
    }

    /** Progress and results of the running (or last) job, updated twice a second. */
    private static void progress(Context c, RecordingDecodeJob job) {
        float d = Theme.dp(c);
        boolean sstv = RecordingDecodeJob.SSTV.equals(job.mode);
        LinearLayout box = Neo.sheetBody(c, Txt.s(sstv ? R.string.modes_recdec_decoding_sstv : R.string.modes_recdec_decoding_aprs), sstv ? "report" : "radio");
        box.addView(Neo.sheetHint(c, job.file.getName()));
        TextView state = Neo.text(c, "", Neo.TEXT, 18, Neo.uiMedium(c)); state.setPadding(0, (int) (8 * d), 0, (int) (6 * d)); box.addView(state);
        View bar = new View(c); LinearLayout track = new LinearLayout(c); track.setBackground(Neo.card(c, 0xFF1E3044, 6));
        track.addView(bar, new LinearLayout.LayoutParams(0, (int) (8 * d))); box.addView(track, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (8 * d)));
        bar.setBackground(Neo.tint(c, sstv ? Neo.CYAN : Neo.GREEN, 6, 0));
        TextView found = Neo.text(c, "", Neo.MUTED, 14, Neo.uiMedium(c)); found.setLineSpacing(0, 1.15f); found.setTextIsSelectable(true); found.setPadding(0, (int) (12 * d), 0, (int) (8 * d)); box.addView(found);
        TextView stop = Neo.button(c, Txt.s(R.string.common_stop), "stop", Neo.RED); box.addView(stop);
        stop.setOnClickListener(v -> job.cancel());
        final AlertDialog dlg = Neo.showSheet(c, box);
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() { @Override public void run() {
            if (!dlg.isShowing()) return;
            int w = track.getWidth(); LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams(); lp.width = (int) (w * job.percent / 100.0); bar.setLayoutParams(lp);
            if (job.error != null) state.setText(Txt.s(R.string.modes_recdec_failed, job.error));
            else if (job.done) state.setText(job.summary == null ? Txt.s(R.string.common_done) : job.summary);
            else state.setText(job.stage.isEmpty() ? Txt.s(R.string.modes_recdec_percent, job.percent) : Txt.s(R.string.modes_recdec_percent_stage, job.percent, job.stage));
            StringBuilder sb = new StringBuilder();
            if (sstv) { synchronized (job.pictures) { for (String p : job.pictures) sb.append("📺 ").append(p).append('\n'); } }
            else { synchronized (job.packets) { int n = job.packets.size(); for (int i = Math.max(0, n - 60); i < n; i++) sb.append(job.packets.get(i)).append('\n'); if (n > 60) sb.insert(0, Txt.s(R.string.modes_recdec_last_60, n) + "\n"); } }
            found.setText(sb.length() == 0 ? (job.done ? "" : Txt.s(sstv ? R.string.modes_recdec_pictures_here : R.string.modes_recdec_packets_here)) : sb.toString().trim());
            if (job.done) { stop.setText(Txt.s(R.string.common_close)); stop.setOnClickListener(v -> dlg.dismiss()); return; }
            h.postDelayed(this, 500);
        } });
    }
}
