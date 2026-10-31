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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.PacketEngine;
import com.macdmr.radio.RadioService;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * SSTV receive: the picture building up line by line, what the decoder is doing, a strip of the pictures received
 * (tap to view or share), and send / switch frequency / stop.
 */
public final class SstvScreen extends ModeScreen {
    private final PacketEngine pe;
    private final ImageView live;
    private final TextView status, progressText, galleryTitle;
    private final ProgressBar progress;
    private final LinearLayout gallery;
    private long galleryStamp = -1;
    private java.util.Set<String> selected;                                    // non-null while selecting (long-press a picture)
    private LinearLayout selBar; private TextView selCount;
    private final java.util.Map<String, ImageView> thumbs = new java.util.HashMap<>();
    private final TextView switchBtn;
    private final PassCard passCard;
    private TextView sendBtn, decoderBtn;

    public SstvScreen(Context c, RadioService svc, Runnable onClose) {
        super(c, svc, "SSTV", "pkt", Neo.VIOLET, onClose);
        pe = svc.packet();
        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding(dp(14), dp(4), dp(14), dp(10));
        sv.addView(body);
        passCard = new PassCard(c); body.addView(passCard);

        live = new ImageView(c); live.setAdjustViewBounds(true); live.setScaleType(ImageView.ScaleType.FIT_CENTER);
        live.setBackground(Neo.card(c, Neo.alpha(Neo.VIOLET, 0x66), 12)); live.setPadding(dp(3), dp(3), dp(3), dp(3));
        body.addView(live, new LayoutParams(LayoutParams.MATCH_PARENT, dp(260)));
        status = Neo.text(c, "", Neo.TEXT, 16, Neo.uiSemi(c)); status.setPadding(0, dp(10), 0, dp(4));
        body.addView(status);
        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Neo.VIOLET));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Neo.TRACK));
        body.addView(progress, new LayoutParams(LayoutParams.MATCH_PARENT, dp(8)));
        progressText = Neo.text(c, "", Neo.DIM, 12, Neo.mono(c)); progressText.setPadding(0, dp(4), 0, 0);
        body.addView(progressText);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_sstv_hint)));

        galleryTitle = Neo.caps(c, Txt.s(R.string.modes_sstv_received)); galleryTitle.setPadding(0, dp(14), 0, dp(6));
        body.addView(galleryTitle);
        selBar = new LinearLayout(c); selBar.setOrientation(VERTICAL); selBar.setVisibility(GONE); selBar.setPadding(0, 0, 0, dp(8));   // the count, then the actions sharing the width
        selCount = Neo.text(c, "", Neo.CYAN_LIGHT, 15, Neo.uiMedium(c)); selCount.setPadding(0, 0, 0, dp(6)); selBar.addView(selCount);
        TextView sShare = Neo.button(c, Txt.s(R.string.common_share), null, Neo.CYAN), sDel = Neo.button(c, Txt.s(R.string.common_delete), null, Neo.RED), sDone = Neo.button(c, Txt.s(R.string.common_done), null, Neo.MUTED);
        TextView sComb = Neo.button(c, Txt.s(R.string.modes_sstv_combine), null, Neo.GREEN);
        sComb.setOnClickListener(v -> combine(selectedFiles()));
        LinearLayout selRow = new LinearLayout(c); selBar.addView(selRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView[] acts = { sComb, sShare, sDel, sDone };
        for (int k = 0; k < acts.length; k++) { TextView t = acts[k]; t.setTextSize(14); t.setPadding(dp(4), 0, dp(4), 0); LayoutParams l = new LayoutParams(0, dp(42), 1f); if (k > 0) l.leftMargin = dp(6); selRow.addView(t, l); }
        sShare.setOnClickListener(v -> { java.util.List<File> fs = selectedFiles(); if (fs.isEmpty()) toast(Txt.s(R.string.modes_sstv_tap_to_select)); else share(fs); });
        sDel.setOnClickListener(v -> { java.util.List<File> fs = selectedFiles(); if (fs.isEmpty()) toast(Txt.s(R.string.modes_sstv_tap_to_select)); else delete(fs, null); });
        sDone.setOnClickListener(v -> endSelect());
        HorizontalScrollView hs = new HorizontalScrollView(c); hs.setHorizontalScrollBarEnabled(false);
        gallery = new LinearLayout(c); hs.addView(gallery);
        body.addView(hs);
        selBar.setPadding(0, dp(8), 0, 0); body.addView(selBar);                  // below the pictures: they stay where the finger is
        content.addView(sv);

        LinearLayout r1 = buttonRow();
        sendBtn = button(r1, Txt.s(R.string.modes_sstv_send), "send", Neo.AMBER, v -> PktDialog.sheet(c, svc, "sstvSend"));
        switchBtn = button(r1, "", "radio", Neo.CYAN, v -> {
            boolean parked = parked();
            svc.sstvMonitor(false, false); toast(svc.sstvMonitor(true, !parked));
        });
        LinearLayout r2 = buttonRow();
        decoderBtn = button(r2, "", "scan", Neo.VIOLET, v -> {
            if (iss()) { toast(Txt.s(R.string.modes_sstv_iss_robot)); return; }
            pe.setSstvContinuousMode(!pe.sstvContinuousMode());
            toast(Txt.s(pe.sstvContinuousMode() ? R.string.modes_sstv_mode_continuous_toast : R.string.modes_sstv_mode_any_toast));
            refresh();
        });
        button(r2, Txt.s(R.string.modes_sstv_stop), "stop", Neo.RED, v -> {
            if (iss()) { svc.endSatTracking(); toast(Txt.s(R.string.modes_sstv_iss_stopped)); close(); return; }
            toast(svc.sstvMonitor(false, false)); close();
        });
        LinearLayout r3 = buttonRow();
        button(r3, Txt.s(R.string.modes_sstv_decode_recording), "rec", Neo.CYAN, v -> RecordingsDialog.show(c, svc, com.macdmr.radio.RecordingDecodeJob.SSTV));

        if (pe.sstvLast() != null && !iss()) live.setImageBitmap(pe.sstvLast());      // an ISS session starts empty: the last picture came from elsewhere
        status.setText(nice(pe.sstvStatus()));
    }

    private boolean parked() { com.macdmr.radio.ChannelStore.Channel ch = svc.current(); return ch != null && ch.rxFreq == PacketEngine.SSTV_FREQ_HZ; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pe.setSstvListener((st, bmp, lines, total, done) -> {
            if (bmp != null) live.setImageBitmap(bmp);
            status.setText(nice(st));
            if (total > 0) { progress.setProgress(Math.round(1000f * lines / total)); progressText.setText(Txt.s(R.string.modes_sstv_lines, lines, total)); }
            else if (!st.startsWith("receiving")) { progress.setProgress(done ? 1000 : 0); progressText.setText(""); }   // i18n: PacketEngine status word, not shown
            if (done) galleryStamp = -1;
        });
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); pe.setSstvListener(null); }

    private static String nice(String s) {
        if (s == null || s.isEmpty() || s.equals("idle")) return Txt.s(R.string.modes_sstv_waiting);
        return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }

    private boolean iss() { return svc.satMode() == com.macdmr.radio.ham.Satellites.SSTV; }
    @Override protected long waitingHz() { com.macdmr.radio.RadioService.SatState st = svc.satState(); return st != null && st.mode == com.macdmr.radio.ham.Satellites.SSTV && st.waiting ? st.nominalHz : 0; }
    @Override protected long waitingUntil() { com.macdmr.radio.RadioService.SatState st = svc.satState(); return st == null ? 0 : st.aos - 60_000; }
    @Override protected boolean stillRunning() { return pe.sstvRx() || iss(); }

    @Override protected void refresh() {
        passCard.update(svc);
        String dec = Txt.s(pe.sstvUsingContinuous() || iss() ? R.string.modes_sstv_dec_continuous : R.string.modes_sstv_dec_any);
        if (!dec.contentEquals(decoderBtn.getText())) { decoderBtn.setText(dec); Neo.fit(decoderBtn); }
        if (waitingHz() > 0) { set(status, Txt.s(R.string.modes_sstv_starts_with_pass)); set(progressText, ""); }
        if (iss()) {
            setSubtitle(Txt.s(R.string.modes_sstv_iss_subtitle, String.format(Locale.US, "%.3f", svc.satState().nominalHz / 1e6)));
            if (switchBtn.getVisibility() != GONE) { switchBtn.setVisibility(GONE); sendBtn.setVisibility(GONE); }    // an ISS downlink: receive only
        } else {
            setSubtitle(Txt.s(parked() ? R.string.modes_sstv_on_144 : R.string.modes_sstv_on_channel));
            boolean can144 = svc.profile().tunable(PacketEngine.SSTV_FREQ_HZ) || parked();   // the W6 tunes UHF only: no 144.500
            int sv = can144 ? VISIBLE : GONE;
            if (switchBtn.getVisibility() != sv) switchBtn.setVisibility(sv);
            if (sendBtn.getVisibility() != VISIBLE) sendBtn.setVisibility(VISIBLE);
            String sw = Txt.s(parked() ? R.string.modes_sstv_use_channel : R.string.modes_sstv_go_144);
            if (!sw.contentEquals(switchBtn.getText())) { switchBtn.setText(sw); Neo.fit(switchBtn); }
        }
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/SSTV");
        File[] fs = dir.listFiles((x, n) -> n.toLowerCase(Locale.US).endsWith(".png") || n.toLowerCase(Locale.US).endsWith(".jpg"));
        long stamp = fs == null ? 0 : fs.length + dir.lastModified();
        if (stamp == galleryStamp) return;
        galleryStamp = stamp;
        gallery.removeAllViews(); thumbs.clear();
        if (fs == null || fs.length == 0) { galleryTitle.setText(Txt.s(R.string.modes_sstv_received_none)); endSelect(); return; }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        galleryTitle.setText(Txt.s(R.string.modes_sstv_received_count, fs.length));
        for (int i = 0; i < Math.min(24, fs.length); i++) {
            final File f = fs[i];
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 4;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            LinearLayout cell = new LinearLayout(c); cell.setOrientation(VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(c); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageBitmap(b);
            iv.setBackground(Neo.card(c, Neo.LINE, 8)); iv.setPadding(dp(2), dp(2), dp(2), dp(2)); iv.setClipToOutline(true);
            cell.addView(iv, new LayoutParams(dp(120), dp(96)));
            TextView t = Neo.text(c, ago(f.lastModified()), Neo.DIM, 11, Neo.uiMedium(c)); t.setPadding(0, dp(3), 0, 0); cell.addView(t);
            thumbs.put(f.getName(), iv); mark(iv, selected != null && selected.contains(f.getName()));
            cell.setOnClickListener(v -> { if (selected != null) toggle(f); else view(f); });
            cell.setOnLongClickListener(v -> { if (selected == null) { selected = new java.util.LinkedHashSet<>(); selBar.setVisibility(VISIBLE); } toggle(f); return true; });
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); lp.rightMargin = dp(8);
            gallery.addView(cell, lp);
        }
    }

    private void view(File f) {
        LinearLayout body = Neo.sheetBody(c, "📺  " + f.getName());
        ImageView iv = new ImageView(c); iv.setAdjustViewBounds(true); android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath()); iv.setImageBitmap(bm);
        // its real height up front: the sheet's window is sized when it opens, and a wrap-content picture measured short there (cut the sheet off)
        int pw = getResources().getDisplayMetrics().widthPixels - dp(56), ph = bm != null && bm.getWidth() > 0 ? pw * bm.getHeight() / bm.getWidth() : LayoutParams.WRAP_CONTENT;
        body.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, ph));
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_sstv_received_at, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(f.lastModified())))));
        TextView share = Neo.button(c, Txt.s(R.string.common_share), "send", Neo.CYAN);
        share.setOnClickListener(v -> {
            try {
                File dir = new File(c.getCacheDir(), "share"); dir.mkdirs();       // the provider shares the cache's share/ folder only
                File copy = new File(dir, f.getName());
                try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = new java.io.FileOutputStream(copy)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
                android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", copy);
                c.startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/*").putExtra(android.content.Intent.EXTRA_STREAM, u).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), Txt.s(R.string.modes_sstv_share_picture)));
            } catch (Throwable t) { toast(Txt.s(R.string.modes_sstv_share_failed, String.valueOf(t.getMessage()))); }
        });
        body.addView(share, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        TextView del = Neo.button(c, Txt.s(R.string.common_delete), "stop", Neo.RED);
        LayoutParams dl = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)); dl.topMargin = dp(8);
        body.addView(del, dl);
        final android.app.AlertDialog[] sheet = { null };
        del.setOnClickListener(v -> delete(java.util.Collections.singletonList(f), () -> { if (sheet[0] != null) sheet[0].dismiss(); }));
        sheet[0] = Neo.showSheet(c, body);
    }

    // ---- selecting several pictures: long-press one, tap the others, then Share or Delete ----
    private void mark(ImageView iv, boolean on) { iv.setBackground(on ? Neo.outline(c, Neo.CYAN, 0x40, 8, 3) : Neo.card(c, Neo.LINE, 8)); iv.setAlpha(selected != null && !on ? 0.6f : 1f); }
    private void toggle(File f) {
        if (selected == null) return;
        if (!selected.remove(f.getName())) selected.add(f.getName());
        for (java.util.Map.Entry<String, ImageView> e : thumbs.entrySet()) mark(e.getValue(), selected.contains(e.getKey()));
        selCount.setText(Txt.p(R.plurals.modes_sstv_selected, selected.size(), selected.size()));
    }
    /**
     * Several receptions of the same picture (the ISS repeats its slides) into one cleaner picture, saved beside them.
     * Lined up automatically first; partial pictures are drawn at the bottom, so the user can move each one and leave
     * one out, with a preview after every change, then Save.
     */
    private void combine(final java.util.List<File> fs) {
        if (fs.size() < 2) { toast(Txt.s(R.string.modes_sstv_select_two)); return; }
        toast(Txt.p(R.plurals.modes_sstv_lining_up, fs.size(), fs.size()));
        new Thread(() -> {
            final String[] err = { null };
            final com.macdmr.radio.SstvCombine.Plan pl = com.macdmr.radio.SstvCombine.plan(fs, err);
            post(() -> { if (pl == null) toast(err[0]); else combineSheet(pl); });
        }, "sstv-combine").start();
    }
    private void combineSheet(final com.macdmr.radio.SstvCombine.Plan pl) {
        LinearLayout body = Neo.sheetBody(c, Txt.p(R.plurals.modes_sstv_combine_title, pl.files.size(), pl.files.size()), "report");
        final ImageView iv = new ImageView(c); iv.setAdjustViewBounds(true);
        int pw = getResources().getDisplayMetrics().widthPixels - dp(56);                 // real height up front (see view())
        body.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, pw * pl.h / Math.max(1, pl.w)));
        final TextView note = Neo.sheetHint(c, ""); body.addView(note);
        final int[] cur = { pl.ref == 0 ? 1 : 0 }; final boolean[] manual = { false };
        final TextView pick = Neo.button(c, "", null, Neo.CYAN), use = Neo.button(c, "", null, Neo.MUTED);
        pick.setTextSize(13);
        LinearLayout r1 = new LinearLayout(c); r1.setOrientation(HORIZONTAL);
        r1.addView(pick, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); LayoutParams ul = new LayoutParams(dp(104), LayoutParams.MATCH_PARENT); ul.leftMargin = dp(6); r1.addView(use, ul);
        LayoutParams r1l = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); r1l.topMargin = dp(6); body.addView(r1, r1l);
        LinearLayout r2 = new LinearLayout(c); r2.setOrientation(HORIZONTAL);
        final TextView[] arrows = { Neo.button(c, "\u25B2", null, Neo.VIOLET), Neo.button(c, "\u25BC", null, Neo.VIOLET), Neo.button(c, "\u25C0", null, Neo.VIOLET), Neo.button(c, "\u25B6", null, Neo.VIOLET) };
        final int[][] step = { { -1, 0 }, { 1, 0 }, { 0, 1 }, { 0, -1 } };      // rows, and the sideways shift (positive moves the picture left)
        for (int k = 0; k < 4; k++) { LayoutParams l = new LayoutParams(0, dp(46), 1f); if (k > 0) l.leftMargin = dp(6); r2.addView(arrows[k], l); }
        LayoutParams r2l = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); r2l.topMargin = dp(6); body.addView(r2, r2l);
        final TextView auto = Neo.button(c, Txt.s(R.string.modes_sstv_automatic), null, Neo.MUTED), save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN);
        LinearLayout r3 = new LinearLayout(c); r3.setOrientation(HORIZONTAL);
        r3.addView(auto, new LayoutParams(0, dp(48), 1f)); LayoutParams sl = new LayoutParams(0, dp(48), 1f); sl.leftMargin = dp(6); r3.addView(save, sl);
        LayoutParams r3l = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); r3l.topMargin = dp(8); body.addView(r3, r3l);
        final int[] auto0y = pl.dy.clone(), auto0x = pl.dx.clone(); final boolean[] auto0u = pl.use.clone();
        final Runnable[] show = { null };
        show[0] = () -> {
            int i = cur[0]; boolean isRef = i == pl.ref;
            String n = pl.files.get(i).getName().replace("SSTV_", "").replace(".png", "");
            String where = isRef ? Txt.s(R.string.modes_sstv_where_ref) : !pl.use[i] ? Txt.s(R.string.modes_sstv_where_out)
                : Txt.s(pl.dy[i] < 0 ? (pl.dx[i] > 0 ? R.string.modes_sstv_where_up_left : R.string.modes_sstv_where_up_right) : (pl.dx[i] > 0 ? R.string.modes_sstv_where_down_left : R.string.modes_sstv_where_down_right), Math.abs(pl.dy[i]), Math.abs(pl.dx[i]));
            pick.setText(Txt.s(R.string.modes_sstv_pick, i + 1, pl.files.size(), n, where));
            use.setText(Txt.s(isRef ? R.string.modes_sstv_use_ref : pl.use[i] ? R.string.modes_sstv_use_in : R.string.modes_sstv_use_out)); use.setEnabled(!isRef); use.setAlpha(isRef ? 0.5f : 1f);
            for (TextView a : arrows) { a.setEnabled(!isRef && pl.use[i]); a.setAlpha(!isRef && pl.use[i] ? 1f : 0.4f); }
            String uns = pl.unsure();
            java.util.List<String> sentences = new java.util.ArrayList<>();
            if (pl.used() < 2) sentences.add(Txt.s(R.string.modes_sstv_note_one));
            sentences.add(manual[0] ? Txt.s(R.string.modes_sstv_note_manual) : uns.isEmpty() ? Txt.s(R.string.modes_sstv_note_auto) : Txt.s(R.string.modes_sstv_note_unsure, uns));
            sentences.add(Txt.s(R.string.modes_sstv_note_partial));
            note.setText(String.join(Txt.s(R.string.modes_sentence_separator), sentences));
            save.setEnabled(pl.used() >= 2); save.setAlpha(pl.used() >= 2 ? 1f : 0.4f);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(pl.render(), pl.w, pl.h, android.graphics.Bitmap.Config.ARGB_8888);
            iv.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bm, pl.w * 2, pl.h * 2, false)); bm.recycle();
        };
        pick.setOnClickListener(v -> { cur[0] = (cur[0] + 1) % pl.files.size(); show[0].run(); });
        use.setOnClickListener(v -> { int i = cur[0]; if (i == pl.ref) return; pl.use[i] = !pl.use[i]; manual[0] = true; show[0].run(); });
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            arrows[k].setOnClickListener(v -> { int i = cur[0]; pl.dy[i] += step[kk][0]; pl.dx[i] += step[kk][1]; manual[0] = true; show[0].run(); });
            arrows[k].setOnLongClickListener(v -> { int i = cur[0]; pl.dy[i] += 8 * step[kk][0]; pl.dx[i] += 8 * step[kk][1]; manual[0] = true; show[0].run(); return true; });
        }
        auto.setOnClickListener(v -> { System.arraycopy(auto0y, 0, pl.dy, 0, auto0y.length); System.arraycopy(auto0x, 0, pl.dx, 0, auto0x.length); System.arraycopy(auto0u, 0, pl.use, 0, auto0u.length); manual[0] = false; show[0].run(); });
        final android.app.AlertDialog[] sheet = { null };
        save.setOnClickListener(v -> {
            com.macdmr.radio.SstvCombine.Result r = com.macdmr.radio.SstvCombine.save(pl);
            toast(r.message); if (r.file != null) { if (sheet[0] != null) sheet[0].dismiss(); endSelect(); refresh(); view(r.file); }
        });
        show[0].run();
        sheet[0] = Neo.showSheet(c, body);
    }
    private void endSelect() {
        selected = null; if (selBar != null) selBar.setVisibility(GONE);
        for (ImageView iv : thumbs.values()) mark(iv, false);
    }
    private File sstvDir() { return new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/SSTV"); }
    private java.util.List<File> selectedFiles() {
        java.util.List<File> out = new java.util.ArrayList<>(); if (selected == null) return out;
        for (String n : selected) { File f = new File(sstvDir(), n); if (f.isFile()) out.add(f); }
        return out;
    }
    /** One picture through ACTION_SEND, several through ACTION_SEND_MULTIPLE (copies in the shared cache folder). */
    private void share(java.util.List<File> fs) {
        try {
            File dir = new File(c.getCacheDir(), "share"); dir.mkdirs();       // the provider shares the cache's share/ folder only
            java.util.ArrayList<android.net.Uri> us = new java.util.ArrayList<>();
            for (File f : fs) {
                File copy = new File(dir, f.getName());
                try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = new java.io.FileOutputStream(copy)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
                us.add(androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", copy));
            }
            android.content.Intent i = us.size() == 1
                    ? new android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/*").putExtra(android.content.Intent.EXTRA_STREAM, us.get(0))
                    : new android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, us);
            i.putExtra(android.content.Intent.EXTRA_TEXT, us.size() == 1 ? Txt.s(R.string.modes_sstv_share_text_one) : Txt.p(R.plurals.modes_sstv_share_text, us.size(), us.size())).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(android.content.Intent.createChooser(i, us.size() == 1 ? Txt.s(R.string.modes_sstv_share_title_one) : Txt.p(R.plurals.modes_sstv_share_title, us.size(), us.size())));
        } catch (Throwable t) { toast(Txt.s(R.string.modes_sstv_share_failed, String.valueOf(t.getMessage()))); }
    }
    /** Delete after a confirmation; {@code after} runs once they are gone (closes the viewer). */
    private void delete(java.util.List<File> fs, Runnable after) {
        String title = fs.size() == 1 ? Txt.s(R.string.modes_sstv_delete_one) : Txt.p(R.plurals.modes_sstv_delete_many, fs.size(), fs.size());
        new android.app.AlertDialog.Builder(c).setTitle(title).setMessage(Txt.s(R.string.modes_sstv_delete_message))
                .setPositiveButton(Txt.s(R.string.common_delete), (d, w) -> {
                    int gone = 0; for (File f : fs) if (f.delete()) gone++;
                    toast(Txt.p(R.plurals.modes_sstv_deleted, gone, gone));
                    endSelect(); galleryStamp = -1; refresh();
                    if (after != null) after.run();
                })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }
}
