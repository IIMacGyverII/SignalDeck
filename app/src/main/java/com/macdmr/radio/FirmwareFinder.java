/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Device report, opt-in (root): finds the radio firmware files a phone carries, so a new phone's module and MCU images
 * can be identified (and, if the owner chooses, handed over privately) without anyone digging through the phone by hand.
 * Lists name, size, SHA-256 and any module version strings found inside; copies only the files the owner ticks, into
 * Download/DMR/FoundFirmware/&lt;model&gt;/ (not under Download/DMR/Firmware, which the module loader reads) with a SOURCES.txt. Nothing is sent anywhere and nothing is written to the
 * phone's own partitions. These files belong to the phone maker: they never go into a public repository.
 */
public final class FirmwareFinder {
    private FirmwareFinder() { }

    /** Folders where the phones seen so far keep radio images (Armor 3WT: /system/data/{mcu,dmr}, /sdcard/intercom/dmr; Atom XL: /vendor/data/mcu, and
     *  /vendor/data/dmr, which its stock app reads; a copy of an Atom XL's files came as DMR/dmr, so the upper-case folder is searched too: the vendor
     *  partition is case-sensitive, and an unneeded entry costs nothing). */
    static final String[] FOLDERS = { "/vendor/data/mcu", "/vendor/data/dmr", "/vendor/data/DMR", "/system/data/mcu", "/system/data/dmr", "/odm/data/mcu",
            "/sdcard/intercom/dmr", "/sdcard/intercom/mcu", "/sdcard/DMR", "/vendor/firmware", "/odm/firmware", "/system/etc/firmware" };
    /** In the shared firmware folders only names like these are radio images (the rest is Wi-Fi, camera, touch...). */
    private static final Pattern RADIO_NAME = Pattern.compile("(?i).*(dmr|mcu|intercom|interphone|walkie|ptt|agold|at1846|hrc|c7000|c5000|bk48|sa8[0-9]{2}|rda18|uv4t|u4a).*");
    /** Image-like files inside an APK. */
    private static final Pattern APK_IMAGE = Pattern.compile("(?i)(assets|res/raw)/.*\\.(bin|hex|img|fw|dfu)$");
    /** Module version strings: Ulefone/Agold "DMR003.UV4T.V022", the Armor 3WT's "ver0.5.4_470M_TONE". */
    private static final Pattern VERSION = Pattern.compile("DMR\\d{3}\\.[A-Za-z0-9]{2,8}\\.V\\d{2,4}|ver\\d+\\.\\d+\\.\\d+_[A-Za-z0-9_]{2,24}");
    static final long MAX_BYTES = 32L << 20;

    /** One file found. {@code source} is a path, or "apk-path!entry" for a file inside an APK. */
    public static final class Found {
        public final String source; public final long size; public String sha256 = "", versions = "";
        /** What SignalDeck already knows this file as (its built-in images and the signed catalogue), "" when it is new. */
        public String known = "";
        File cached;                                                       // a copy readable by the app (root-only files), deleted after use
        Found(String source, long size) { this.source = source; this.size = size; }
        public String name() { int s = Math.max(source.lastIndexOf('/'), source.lastIndexOf('!')); return source.substring(s + 1); }
        public String line() { return String.format(Locale.US, "%s  %d bytes  sha256 %s%s%s", source, size, sha256, versions.isEmpty() ? "" : "  contains " + versions, known.isEmpty() ? "" : "  already known: " + known); }
    }

    /**
     * The stock radio apps' APK paths: the profile's stock app first, then the ranked radio apps, each step on its own
     * so one failure (package visibility, a root shell that answers nothing) cannot empty the list silently. Every step
     * is logged under the FirmwareFinder tag.
     */
    public static List<String> stockApks(Context c, String profileStockApp) {
        List<String> pkgs = new ArrayList<>();
        if (profileStockApp != null && !profileStockApp.isEmpty()) pkgs.add(profileStockApp);
        try {
            for (RadioAppFinder.App a : RadioAppFinder.rank(c)) if (pkgs.size() < 3 && a.score >= 5 && !pkgs.contains(a.pkg)) pkgs.add(a.pkg);
        } catch (Throwable t) { android.util.Log.w("FirmwareFinder", "radio app ranking failed: " + t); }
        android.util.Log.i("FirmwareFinder", "stock packages: " + pkgs);
        List<String> apks = new ArrayList<>();
        for (String pk : pkgs) {
            try {
                List<String> paths = StockApk.paths(c, pk);
                android.util.Log.i("FirmwareFinder", "stock app " + pk + ": " + (paths.isEmpty() ? "no APK path" : paths));
                for (String a : paths) if (!apks.contains(a)) apks.add(a);
            } catch (Throwable t) { android.util.Log.w("FirmwareFinder", "stock app " + pk + ": " + t); }
        }
        return apks;
    }

    /** The entry that stands in for the stock app's APK when none was found, so the report says so instead of saying nothing. */
    public static Found noStockApk(String profileStockApp) {
        return new Found((profileStockApp == null || profileStockApp.isEmpty() ? "stock app" : profileStockApp) + "! (APK not found: not installed, or the package manager hides it and root gave no path)", 0);
    }

    /** The radio firmware files on this phone. Off the main thread; needs root for the system folders. */
    public static List<Found> scan(Context c, List<String> stockApks, Progress p) {
        List<Found> out = new ArrayList<>();
        File tmp = new File(c.getCacheDir(), "fwfind"); tmp.mkdirs();
        if (RootSetup.haveRoot()) scanFolders(FOLDERS, ROOT_FS, tmp, p, out);
        for (String apk : stockApks) {
            p.step("Looking in " + new File(apk).getName());
            File readable = StockApk.readable(c, apk, "fwfind.apk");
            android.util.Log.i("FirmwareFinder", "apk " + apk + ": " + (readable == null ? "not readable" : "readable as " + readable.getPath()));
            if (readable == null) { out.add(new Found(apk + "! (could not read)", 0)); continue; }
            try (ZipFile z = new ZipFile(readable)) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry ze = e.nextElement();
                    if (ze.isDirectory() || !APK_IMAGE.matcher(ze.getName()).matches() || ze.getSize() > MAX_BYTES) continue;
                    Found f = new Found(apk + "!" + ze.getName(), ze.getSize());
                    File copy = new File(tmp, "f" + out.size());
                    try (InputStream in = z.getInputStream(ze); OutputStream o = new FileOutputStream(copy)) { pipe(in, o); }
                    f.cached = copy; digest(f);
                    out.add(f);
                }
            } catch (Throwable t) { out.add(new Found(apk + "! (could not read: " + t.getMessage() + ")", 0)); }
            finally { if (!readable.getPath().equals(apk)) readable.delete(); }
        }
        com.macdmr.radio.fw.FirmwareCatalog.Doc cat = com.macdmr.radio.fw.FirmwareCatalog.cached(c);
        markKnown(out, cat == null ? null : cat.images);
        return out;
    }

    /** The stock apps of the Atom XL and the Armor 3WT flash a .bin from this folder into the module by themselves, when its name sorts after
     *  the module's version + ".bin" (read from their code). */
    static final String AUTO_FLASH_DIR = "/sdcard/intercom/dmr/";

    static boolean autoFlash(String source) { return source.startsWith(AUTO_FLASH_DIR) || source.startsWith("/storage/self/primary/intercom/dmr/"); }

    /** Name every file whose SHA-256 SignalDeck already has: the built-in images first, then the signed catalogue's rows. */
    static void markKnown(List<Found> fs, List<com.macdmr.radio.fw.FirmwareCatalog.Image> catalogue) {
        for (Found f : fs) f.known = known(f.sha256, catalogue);
    }

    static String known(String sha, List<com.macdmr.radio.fw.FirmwareCatalog.Image> catalogue) {
        return known(sha, catalogue, BuildConfig.VERSION_CODE);
    }

    static String known(String sha, List<com.macdmr.radio.fw.FirmwareCatalog.Image> catalogue, int appVersionCode) {
        if (sha == null || sha.length() != 64) return "";
        com.macdmr.radio.fw.ImageTable.Row r = com.macdmr.radio.fw.ImageTable.bySha(sha);
        if (r != null) return r.tag + (r.factory ? ", the factory image for the " + r.model : ", a SignalDeck bench image for the " + r.model);
        if (catalogue != null) for (com.macdmr.radio.fw.FirmwareCatalog.Image im : catalogue) {
            if (!sha.equalsIgnoreCase(im.sha256)) continue;
            String model = im.model == null || im.model.isEmpty() ? "another phone" : "the " + im.model;
            if (im.factory) return im.tag + ", the factory image for " + model;
            if ("released".equals(im.status)) return im.tag + ", a released image for " + model;
            if ("released-min".equals(im.status))                              // released, but only for SignalDeck builds new enough (FirmwareCatalog.offered)
                return com.macdmr.radio.fw.FirmwareCatalog.offered(im.status, im.minAppVersionCode, appVersionCode)
                        ? im.tag + ", a released image for " + model
                        : im.tag + ", a released image for " + model + " that needs a newer SignalDeck (version code " + im.minAppVersionCode + " or newer)";
            return im.tag + ", a stock copy for " + model + " kept for research (not offered for loading)";
        }
        return "";
    }
    public interface Progress { void step(String s); }

    /** Where the folders are read from: the phone through root, or a test tree on the host. */
    interface Fs {
        List<String> files(String dir);                      // files up to two levels down
        long size(String path);
        boolean copy(String path, File to);                  // to a file the app can read
    }
    static final Fs ROOT_FS = new Fs() {
        @Override public List<String> files(String dir) {
            List<String> l = new ArrayList<>();
            for (String path : RootSetup.run("find " + dir + " -maxdepth 2 -type f 2>/dev/null | head -200").split("\n")) if (!path.trim().isEmpty()) l.add(path.trim());
            return l;
        }
        @Override public long size(String path) { return parseLong(RootSetup.run("stat -c %s '" + path + "' 2>/dev/null").trim()); }
        @Override public boolean copy(String path, File to) { RootSetup.run("cat '" + path + "' > '" + to.getAbsolutePath() + "' && chmod 644 '" + to.getAbsolutePath() + "'"); return to.isFile(); }
    };

    /** SignalDeck's and the old module's folder, where the user may also have put a radio image by hand. */
    static final String APP_DIR = "/sdcard/DMR";
    /** Folders under it that hold what the apps write themselves (serial captures, recordings, reports, logs...), never vendor images. */
    static final java.util.Set<String> OWN_OUTPUT = new java.util.HashSet<>(java.util.Arrays.asList("uart_logs", "Audio", "DeviceReport", "Firmware",
            "FoundFirmware", "APRS", "DMR_Backups", "SSTV", "NOAA", "Coverage", "Transcription", "RadioID"));
    /** Under {@link #APP_DIR} only a module image counts: a .bin of at least this size with a module version string inside. */
    static final long APP_DIR_MIN_BYTES = 100 * 1024;

    static void scanFolders(String[] folders, Fs fs, File tmp, Progress p, List<Found> out) {
        for (String dir : folders) {
            p.step("Looking in " + dir);
            boolean shared = dir.endsWith("/firmware"), appDir = dir.equals(APP_DIR);
            for (String path : fs.files(dir)) {
                if (shared && !RADIO_NAME.matcher(path).matches()) continue;
                if (appDir && (ownOutput(path) || !path.toLowerCase(Locale.US).endsWith(".bin"))) continue;
                long size = fs.size(path);
                if (appDir && size < APP_DIR_MIN_BYTES) continue;
                Found f = new Found(path, size);
                if (size > 0 && size <= MAX_BYTES) {
                    File copy = new File(tmp, "f" + out.size());
                    if (fs.copy(path, copy) && copy.length() == size) { f.cached = copy; digest(f); }
                }
                if (appDir && f.versions.isEmpty()) { if (f.cached != null) f.cached.delete(); continue; }   // a capture or the app's own data, not an image
                out.add(f);
            }
        }
    }

    /** A file in one of the apps' own output folders under {@link #APP_DIR}. */
    static boolean ownOutput(String path) {
        if (!path.startsWith(APP_DIR + "/")) return false;
        String rest = path.substring(APP_DIR.length() + 1); int slash = rest.indexOf('/');
        return slash > 0 && OWN_OUTPUT.contains(rest.substring(0, slash));
    }

    /** The report section: one line per file, then what to do with them. */
    public static String describe(List<Found> fs) {
        if (fs.isEmpty()) return "no radio firmware files found in " + String.join(", ", FOLDERS) + " or in the stock app\n";
        StringBuilder s = new StringBuilder();
        boolean anyKnown = false, anyAuto = false;
        for (Found f : fs) { s.append("  ").append(f.line()).append('\n'); anyKnown |= !f.known.isEmpty(); anyAuto |= autoFlash(f.source); }
        if (anyKnown) s.append("Files marked already known are in SignalDeck or its firmware catalogue: nobody needs a copy of those.\n");
        if (anyAuto) s.append("Note: on some phones (Unihertz Atom XL, Ulefone Armor 3WT) the stock walkie-talkie app loads a .bin from " + AUTO_FLASH_DIR
                + " into the radio module by itself when its name sorts after the module's version. Leave nothing there you do not mean to load.\n");
        return s.append("Nothing was copied or sent. Copy files only if the SignalDeck maintainer asks for them, and hand them over privately: they are the phone maker's.\n").toString();
    }

    /** Copy the ticked files into Download/DMR/FoundFirmware/<model>/ with SOURCES.txt; returns the folder. */
    public static File copy(List<Found> ticked) throws Exception {
        File dir = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DMR/FoundFirmware"), safe(Build.MANUFACTURER + "_" + Build.MODEL));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("cannot create " + dir);
        StringBuilder src = new StringBuilder("Radio firmware files copied by SignalDeck " + BuildConfig.VERSION_NAME + " from " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DISPLAY + ")\n"
                + "These belong to the phone maker. Share them only privately, never in a public place.\n\n");
        Set<String> used = new LinkedHashSet<>();
        for (Found f : ticked) {
            if (f.cached == null || !f.cached.isFile()) { src.append("not copied (unreadable): ").append(f.source).append('\n'); continue; }
            String name = uniqueName(used, safe(f.name()));
            try (InputStream in = new FileInputStream(f.cached); OutputStream o = new FileOutputStream(new File(dir, name))) { pipe(in, o); }
            src.append(name).append("  <-  ").append(f.line()).append('\n');
        }
        try (OutputStream o = new FileOutputStream(new File(dir, "SOURCES.txt"))) { o.write(src.toString().getBytes("UTF-8")); }
        return dir;
    }

    /** Delete the app-private copies made by {@link #scan}. */
    public static void cleanUp(Context c) { File[] fs = new File(c.getCacheDir(), "fwfind").listFiles(); if (fs != null) for (File f : fs) f.delete(); }

    // ---- pure helpers (host-tested) ----

    /** Module version strings in an image, each once, in order. */
    static String versionsIn(byte[] b) {
        StringBuilder ascii = new StringBuilder(b.length);
        for (byte x : b) ascii.append(x >= 0x20 && x < 0x7F ? (char) x : '\n');
        Set<String> v = new LinkedHashSet<>(); Matcher m = VERSION.matcher(ascii);
        while (m.find() && v.size() < 6) v.add(m.group());
        return String.join(", ", v);
    }
    static boolean radioName(String path) { return RADIO_NAME.matcher(path).matches(); }
    static boolean apkImage(String entry) { return APK_IMAGE.matcher(entry).matches(); }
    static String safe(String s) { String t = s.replaceAll("[^A-Za-z0-9._-]+", "_"); return t.isEmpty() ? "file" : t; }
    static String uniqueName(Set<String> used, String name) {
        String n = name; int k = 2;
        while (!used.add(n)) { int dot = name.lastIndexOf('.'); n = dot > 0 ? name.substring(0, dot) + "_" + k + name.substring(dot) : name + "_" + k; k++; }
        return n;
    }
    static String hex(byte[] d) { StringBuilder s = new StringBuilder(); for (byte x : d) s.append(String.format(Locale.US, "%02x", x & 0xFF)); return s.toString(); }

    private static void digest(Found f) {
        try (InputStream in = new FileInputStream(f.cached)) {
            byte[] all = new byte[(int) Math.min(f.cached.length(), MAX_BYTES)]; int n = 0, r;
            while (n < all.length && (r = in.read(all, n, all.length - n)) > 0) n += r;
            f.sha256 = hex(MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(all, n)));
            f.versions = versionsIn(all);
        } catch (Throwable t) { f.sha256 = "(" + t.getMessage() + ")"; }
    }
    private static void pipe(InputStream in, OutputStream o) throws java.io.IOException { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (Throwable t) { return -1; } }
}
