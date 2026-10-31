/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio;

import android.content.Context;

import com.macdmr.radio.ham.HeldFit;
import com.macdmr.radio.ham.HeldPath;
import com.macdmr.radio.ham.M17;

import com.macdmr.radio.i18n.Txt;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** The phone's own held-path taps: a pending fit, then the one a second call accepted. */
public final class HeldCal {
    private final File dir;
    private final String profile;
    private double[] pending;
    private String fitSrc = "";
    private int fitDbm = -999, fitFrames;
    private String line = "";

    public HeldCal(Context ctx, String profile) {
        this.profile = profile == null ? "unknown" : profile;
        dir = new File(ctx.getFilesDir(), "m17");
        if (!dir.isDirectory()) dir.mkdirs();
    }

    public File acceptedFile() { return new File(dir, "held_" + profile + ".txt"); }

    /** Taps the receiver should use: an accepted fit, or null to keep the shipped asset. */
    public double[] accepted() {
        double[] t = read(acceptedFile());
        return t;
    }

    public String line() { return line == null ? "" : line; }

    public void reset() {
        acceptedFile().delete();
        new File(dir, "held_" + profile + ".note").delete();
        pending = null;
        line = Txt.s(R.string.service_m17cal_shipped);
    }

    /** A call just ended. {@code frames} were already guarded. {@code audio} is the last few seconds. */
    public boolean onCall(List<HeldFit.Frame> frames, short[] audio, String src, int dbm, double[] liveTaps) {
        if (src == null) src = "";
        if (pending == null) {
            double[] w = HeldFit.fit(frames);
            if (w == null) { line = Txt.s(R.string.service_m17cal_shipped_frames, frames.size()); return false; }
            pending = w; fitSrc = src; fitDbm = dbm; fitFrames = frames.size();
            line = Txt.s(R.string.service_m17cal_fit_waiting, fitFrames, fitDbm);
            return false;
        }
        int liveN = clean(audio, liveTaps);
        int pendN = clean(audio, pending);
        boolean locked = locks(audio, pending);
        if (HeldFit.keep(pendN, liveN, locked, fitDbm, dbm, fitSrc, src)) {
            write(acceptedFile(), pending);
            writeNote(fitFrames, fitDbm, dbm);
            line = Txt.s(R.string.service_m17cal_fitted, fitFrames, fitDbm, dbm);
            pending = null;
            return true;
        }
        line = Txt.s(locked ? R.string.service_m17cal_not_kept_locked : R.string.service_m17cal_not_kept_nolock, pendN, liveN);
        pending = null;
        return false;
    }

    private int clean(short[] audio, double[] taps) {
        if (audio == null || audio.length < 800 || taps == null) return 0;
        int n = 0;
        for (HeldPath.FrameHit h : HeldPath.decode(audio, audio.length, taps, true))
            if (h.stream != null && h.stream.lichErrors == 0) n++;
        return n;
    }

    private boolean locks(short[] audio, double[] taps) {
        if (audio == null || taps == null) return false;
        for (HeldPath.FrameHit h : HeldPath.decode(audio, audio.length, taps, true))
            if (h.lsf() && h.link.crcOk) return true;
        return false;
    }

    private void writeNote(int frames, int a, int b) {
        try {
            String text = String.format(Locale.US, "frames %d\nfitDbm %d\ncheckDbm %d\ndate %s\n", frames, a, b,   // i18n: file format
                    new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
            java.nio.file.Files.write(new File(dir, "held_" + profile + ".note").toPath(), text.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private static double[] read(File f) {
        try {
            if (!f.isFile()) return null;
            String[] lines = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim().split("\\s+");
            if (lines.length < HeldPath.TAPS) return null;
            double[] t = new double[HeldPath.TAPS];
            for (int i = 0; i < t.length; i++) t[i] = Double.parseDouble(lines[i]);
            return t;
        } catch (Exception e) { return null; }
    }

    private static void write(File f, double[] t) throws RuntimeException {
        try {
            StringBuilder s = new StringBuilder();
            for (double v : t) s.append(String.format(Locale.US, "%.8f\n", v));
            java.nio.file.Files.write(f.toPath(), s.toString().getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
