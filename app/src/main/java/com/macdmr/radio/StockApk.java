/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where another app's APK files are. The package manager answers only for packages this app may see (Android 11+
 * package visibility: the stock walkie-talkie apps are not declared), so with root it falls back to {@code pm path},
 * which sees every package, a disabled stock app included.
 */
public final class StockApk {
    private StockApk() { }

    /** The base and split APK paths of pkg; empty when it is not installed or cannot be found. */
    public static List<String> paths(Context c, String pkg) {
        List<String> out = new ArrayList<>();
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) java.util.Collections.addAll(out, ai.splitSourceDirs);
            return out;
        } catch (Throwable ignored) { }
        if (pkg.matches("[A-Za-z0-9_.]+") && RootSetup.haveRoot()) out.addAll(parsePmPath(RootSetup.run("pm path " + pkg + " 2>/dev/null")));
        return out;
    }

    /** The APK paths in {@code pm path} output ("package:/path/base.apk" per line). */
    static List<String> parsePmPath(String listed) {
        List<String> out = new ArrayList<>();
        if (listed == null) return out;
        for (String line : listed.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("package:")) continue;
            s = s.substring(8).trim();
            if (s.startsWith("/") && s.endsWith(".apk") && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** The APK itself when this app can read it, else a root copy in the cache named tmpName (the caller deletes it); null when neither works. */
    public static File readable(Context c, String apk, String tmpName) {
        File a = new File(apk);
        if (a.canRead()) return a;
        if (!RootSetup.haveRoot() || apk.contains("'")) return null;
        File tmp = new File(c.getCacheDir(), tmpName);
        RootSetup.run("cat '" + apk + "' > '" + tmp.getAbsolutePath() + "' && chmod 644 '" + tmp.getAbsolutePath() + "'");   // i18n: shell
        return tmp.length() > 0 ? tmp : null;
    }
}
