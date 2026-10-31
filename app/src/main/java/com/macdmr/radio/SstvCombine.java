/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Txt;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.macdmr.radio.pkt.SstvStack;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Combine several receptions of the same SSTV picture (gallery, SignalDeck Web) into a new, cleaner one. */
public final class SstvCombine {
    private SstvCombine() { }

    /** The new picture's file (null on failure) and a line for the user. */
    public static final class Result { public final File file; public final String message; Result(File f, String m) { file = f; message = m; } }

    /** The selected pictures, loaded once, with their line-up (automatic first, then as the user nudges it). */
    public static final class Plan {
        public final List<File> files; public final List<int[]> pixels; public final int w, h;
        public int ref; public int[] dy, dx; public boolean[] use, sure;
        Plan(List<File> f, List<int[]> p, int w, int h) { files = f; pixels = p; this.w = w; this.h = h; }
        /** The combination as it stands (ARGB). */
        public int[] render() { return SstvStack.place(pixels, w, h, dy, dx, use).argb; }
        public int used() { int n = 0; for (boolean b : use) if (b) n++; return n; }
        /** Pictures whose automatic line-up is a guess (1-based, for the user), empty when all are clear. */
        public String unsure() { StringBuilder b = new StringBuilder(); for (int i = 0; i < sure.length; i++) if (use[i] && !sure[i]) b.append(b.length() == 0 ? "" : ", ").append(i + 1); return b.toString(); }
    }

    /** Load the pictures and line them up automatically; null with the reason in {@code err[0]}. */
    public static Plan plan(List<File> files, String[] err) {
        if (files.size() < 2) { err[0] = Txt.s(R.string.modes_sstv_combine_two); return null; }
        List<int[]> px = new ArrayList<>(); int w = -1, h = -1;
        for (File f : files) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) { err[0] = Txt.s(R.string.modes_sstv_combine_unreadable, f.getName()); return null; }
            if (w < 0) { w = b.getWidth(); h = b.getHeight(); }
            else if (b.getWidth() != w || b.getHeight() != h) { b.recycle(); err[0] = Txt.s(R.string.modes_sstv_combine_sizes); return null; }
            int[] a = new int[w * h]; b.getPixels(a, 0, w, 0, 0, w, h); b.recycle(); px.add(a);
        }
        Plan p = new Plan(files, px, w, h);
        SstvStack.Result r = SstvStack.combine(px, w, h, 0.3);
        p.ref = r.ref; p.dy = r.dy.clone(); p.dx = r.dx.clone(); p.use = r.aligned.clone(); p.sure = r.sure.clone();
        return p;
    }

    /** Save the plan's combination beside the pictures. */
    public static Result save(Plan p) {
        if (p.used() < 2) return new Result(null, Txt.s(R.string.modes_sstv_combine_one));
        Bitmap out = Bitmap.createBitmap(p.render(), p.w, p.h, Bitmap.Config.ARGB_8888);
        File f = new File(p.files.get(0).getParentFile(), "SSTV_combined_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + "_" + p.used() + "x.png");
        try (FileOutputStream o = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, o); }
        catch (Exception e) { return new Result(null, Txt.s(R.string.modes_sat_save_failed, String.valueOf(e.getMessage()))); }
        finally { out.recycle(); }
        return new Result(f, Txt.p(R.plurals.modes_sstv_combine_done, p.used(), p.used(), f.getName()));
    }

    /** PNG bytes of the combination as it stands (the web preview). */
    public static byte[] png(Plan p) {
        Bitmap out = Bitmap.createBitmap(p.render(), p.w, p.h, Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.PNG, 100, o); out.recycle(); return o.toByteArray();
    }
}
