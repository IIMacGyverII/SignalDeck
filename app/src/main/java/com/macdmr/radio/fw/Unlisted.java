/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import android.content.Context;
import android.content.SharedPreferences;

import com.macdmr.radio.RootSetup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The Allow unlisted images switch, off unless the owner has confirmed it, and the files it can see. */
public final class Unlisted {
    private Unlisted() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("firmware", Context.MODE_PRIVATE); }

    public static boolean allowed(Context c) { return prefs(c).getBoolean("unlisted", false); }

    public static void allow(Context c, boolean on) { prefs(c).edit().putBoolean("unlisted", on).apply(); }

    /** Files in Download/DMR/Firmware that are the right size, carry a version string, and are not a known image. */
    public static List<ImageTable.Row> files(Context c) {
        List<ImageTable.Row> out = new ArrayList<>();
        String listed = RootSetup.run("ls /sdcard/Download/DMR/Firmware/ 2>/dev/null");
        if (listed == null) return out;
        File dst = new File(c.getCacheDir(), "fw-unlisted.bin");
        String uid = String.valueOf(android.os.Process.myUid());
        for (String name : listed.split("\\s+")) {
            if (!name.endsWith(".bin")) continue;
            String r = RootSetup.run("cp '/sdcard/Download/DMR/Firmware/" + name + "' '" + dst.getAbsolutePath()
                    + "' && chown " + uid + ":" + uid + " '" + dst.getAbsolutePath() + "' && echo ok");   // i18n: shell command
            if (r == null || !r.endsWith("ok")) continue;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(dst.toPath());
                if (b.length != ImageTable.FACTORY.size) continue;
                String tag = ImageTable.tagOf(b);
                if (tag.length() < 8) continue;
                String sha = FirmwareVault.sha256(b);
                if (ImageTable.bySha(sha) != null) continue;
                out.add(new ImageTable.Row(tag, sha, b.length,
                        Txt.s(R.string.device_fw_unlisted_summary, tag),
                        ImageTable.FACTORY.tag, ImageTable.FACTORY.model, ImageTable.FACTORY.stockPackage, ImageTable.FACTORY.stockVersion,
                        false, false, true));
            } catch (Exception ignored) { }
        }
        return out;
    }
}
