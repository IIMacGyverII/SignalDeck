/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio;

import com.macdmr.radio.ham.HeldFit;
import com.macdmr.radio.ham.M17Receiver;

import com.macdmr.radio.i18n.Txt;

import java.io.File;
import java.util.Locale;

/**
 * The phone's own table for the decision-feedback M17 receiver (the one used where the module's voice filter cannot be
 * held open: stock firmware, or a module without that hold such as the W6's). The receiver learns from the frames the
 * error correction confirmed in a call; with at least {@link #MIN_FRAMES} of them the fitted table waits for a later call,
 * which is decoded with both tables. The new table is kept only when it finds the link setup there, confirms at least as
 * many frames, and the call is from another station or 6 dB weaker (the held path's rule, {@link HeldFit#keep}).
 * It refines a table; it cannot make one from nothing (a phone whose table decodes nothing confirms no frames).
 */
public final class DfeCal {
    public static final int MIN_FRAMES = 20;
    private final File dir;
    private final String profile;
    private float[] pending;
    private String fitSrc = "";
    private int fitDbm = -999, fitFrames;
    private String line = "";

    public DfeCal(File dir, String profile) {
        this.profile = profile == null ? "unknown" : profile;
        this.dir = dir;
        if (!dir.isDirectory()) dir.mkdirs();
    }

    public File acceptedFile() { return new File(dir, "eq_" + profile + ".txt"); }

    /** The table this phone accepted, or null to keep the shipped one. */
    public float[] accepted() { return read(acceptedFile()); }

    public String line() { return line == null ? "" : line; }

    public void reset() {
        acceptedFile().delete();
        new File(dir, "eq_" + profile + ".note").delete();
        pending = null;
        line = "";
    }

    /**
     * A call ended. {@code learned} is the receiver's table from its confirmed frames (null when it had too few),
     * {@code audio} the call's recording, {@code live} the table that received it. Returns true when a table was kept.
     */
    public boolean onCall(float[] learned, int frames, short[] audio, String src, int dbm, float[] live) {
        if (src == null) src = "";
        if (pending == null) {
            if (learned == null) { if (frames > 0) line = Txt.s(R.string.service_m17eq_unchanged, frames, MIN_FRAMES); return false; }
            pending = learned.clone(); fitSrc = src; fitDbm = dbm; fitFrames = frames;
            line = Txt.s(R.string.service_m17eq_waiting, fitFrames);
            return false;
        }
        if (audio == null || audio.length < 8000 || live == null) return false;
        int[] withPending = M17Receiver.score(audio, pending), withLive = M17Receiver.score(audio, live);
        boolean locked = withPending[2] > 0;
        if (HeldFit.keep(withPending[0], withLive[0], locked, fitDbm, dbm, fitSrc, src)) {
            write(acceptedFile(), pending);
            writeNote(fitFrames, withPending[0], withLive[0]);
            line = Txt.s(R.string.service_m17eq_fitted, fitFrames, withPending[0], withLive[0]);
            pending = null;
            return true;
        }
        line = Txt.s(!locked ? R.string.service_m17eq_not_kept_nolink : withPending[0] < withLive[0] ? R.string.service_m17eq_not_kept_fewer : R.string.service_m17eq_not_kept_same, withPending[0], withLive[0]);
        pending = null;
        return false;
    }

    private void writeNote(int frames, int pend, int live) {
        try {
            String text = String.format(Locale.US, "frames %d\ncheckPending %d\ncheckLive %d\ndate %s\n", frames, pend, live,   // i18n: file format
                    new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
            java.nio.file.Files.write(new File(dir, "eq_" + profile + ".note").toPath(), text.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    static float[] read(File f) {
        try {
            if (!f.isFile()) return null;
            String[] lines = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim().split("\\s+");
            if (lines.length != M17Receiver.NCOEF) return null;
            float[] t = new float[lines.length];
            for (int i = 0; i < t.length; i++) t[i] = Float.parseFloat(lines[i]);
            return t;
        } catch (Exception e) { return null; }
    }

    private static void write(File f, float[] t) {
        try {
            StringBuilder s = new StringBuilder();
            for (float v : t) s.append(v).append('\n');
            java.nio.file.Files.write(f.toPath(), s.toString().getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
