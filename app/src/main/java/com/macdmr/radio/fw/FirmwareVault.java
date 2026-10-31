/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.macdmr.radio.RootSetup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The phone's factory image: the stock app's asset, an app-private copy, and a Download mirror. */
public final class FirmwareVault {
    public static final class Copy {
        public final byte[] bytes;
        public final String sha256, tag, source;
        public final boolean verified;
        public Copy(byte[] bytes, String sha256, String tag, String source, boolean verified) {
            this.bytes = bytes; this.sha256 = sha256; this.tag = tag; this.source = source; this.verified = verified;
        }
    }

    private FirmwareVault() { }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "firmware");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(d.length * 2);
            for (byte v : d) s.append(String.format(java.util.Locale.US, "%02x", v & 0xFF));
            return s.toString();
        } catch (Exception e) { return ""; }
    }

    /** Package version name, or "" when that package is not installed. */
    public static String stockVersion(Context c, String pkg) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(pkg, 0);
            return i.versionName == null ? "" : i.versionName;
        } catch (PackageManager.NameNotFoundException e) { return ""; }
    }

    public static boolean stockInstalled(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /**
     * The verified factory image. The stock APK asset wins; a matching private copy is used when the asset
     * cannot be read. A copy that does not match the known factory hash is not returned.
     */
    /** Bytes of a known image: the factory copy, or a file under Download/DMR/Firmware whose hash is the row's. */
    public static byte[] bytesFor(Context c, ImageTable.Row row) {
        if (row == null) return null;
        if (row.factory) { Copy f = factory(c); return f.verified ? f.bytes : null; }
        try {                                                                   // a catalogue image this app downloaded
            File own = downloaded(c, row.tag, row.sha256);
            if (own.isFile()) { byte[] b = java.nio.file.Files.readAllBytes(own.toPath()); if (matches(b, row.tag, row.sha256, row.size)) return b; }
        } catch (Exception ignored) { }
        String listed = RootSetup.run("ls /sdcard/Download/DMR/Firmware/ 2>/dev/null");
        if (listed == null) return null;
        File dst = new File(c.getCacheDir(), "fw-candidate.bin");
        String uid = String.valueOf(android.os.Process.myUid());
        for (String name : listed.split("\\s+")) {
            if (!name.endsWith(".bin")) continue;
            String r = RootSetup.run("cp '/sdcard/Download/DMR/Firmware/" + name + "' '" + dst.getAbsolutePath()
                    + "' && chown " + uid + ":" + uid + " '" + dst.getAbsolutePath() + "' && echo ok");   // i18n: shell command
            if (r == null || !r.endsWith("ok")) continue;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(dst.toPath());
                if (row.sha256.equals(sha256(b)) && row.tag.equals(ImageTable.tagOf(b))) return b;
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static Copy factory(Context c) {
        byte[] asset = readStockAsset(c, ImageTable.FACTORY.stockPackage);
        if (asset != null && ImageTable.FACTORY.sha256.equals(sha256(asset)) && ImageTable.FACTORY.tag.equals(ImageTable.tagOf(asset))) {
            save(c, asset);
            return new Copy(asset, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag, Txt.s(R.string.device_fw_source_stock), true);
        }
        byte[] kept = readPrivate(c);
        if (kept != null && ImageTable.FACTORY.sha256.equals(sha256(kept)) && ImageTable.FACTORY.tag.equals(ImageTable.tagOf(kept)))
            return new Copy(kept, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag, Txt.s(R.string.device_fw_source_saved), true);
        return new Copy(null, asset == null ? "" : sha256(asset), asset == null ? "" : ImageTable.tagOf(asset), asset == null ? "" : Txt.s(R.string.device_fw_source_stock), false);
    }

    private static File privateFile(Context c) {
        return new File(dir(c), "factory-" + ImageTable.FACTORY.tag + "-" + ImageTable.FACTORY.sha256.substring(0, 8) + ".bin");
    }

    private static byte[] readPrivate(Context c) {
        try { return java.nio.file.Files.readAllBytes(privateFile(c).toPath()); }
        catch (Exception e) { return null; }
    }

    /** The bytes are exactly the listed image: size, sha256 and the version string inside the file. */
    static boolean matches(byte[] b, String tag, String sha256, int size) {
        return b != null && tag != null && sha256 != null && b.length == size && sha256.equals(sha256(b)) && tag.equals(ImageTable.tagOf(b));
    }
    private static File downloaded(Context c, String tag, String sha256) { return new File(dir(c), tag + "-" + sha256.substring(0, 8) + ".bin"); }

    /**
     * Keep a downloaded catalogue image (not the factory one) that matches its catalogue row, in the app's files and a copy
     * in Download/DMR/Firmware for the user. @return false when it does not match
     */
    public static boolean keepImage(Context c, byte[] image, String tag, String sha256, int size) {
        if (!matches(image, tag, sha256, size)) return false;
        try {
            File f = downloaded(c, tag, sha256);
            java.nio.file.Files.write(f.toPath(), image);
            String mirror = "/sdcard/Download/DMR/Firmware/" + f.getName();
            RootSetup.run("mkdir -p /sdcard/Download/DMR/Firmware && cp '" + f.getAbsolutePath() + "' '" + mirror + "' && chmod 644 '" + mirror + "'");   // i18n: shell command
            return true;
        } catch (Exception e) { return false; }
    }

    /** Keep a downloaded factory image that already matches the known hash and tag. */
    public static boolean keepFactory(Context c, byte[] image) {
        if (image == null || !ImageTable.FACTORY.sha256.equals(sha256(image)) || !ImageTable.FACTORY.tag.equals(ImageTable.tagOf(image))) return false;
        save(c, image);
        return true;
    }

    private static void save(Context c, byte[] asset) {
        try {
            File f = privateFile(c);
            java.nio.file.Files.write(f.toPath(), asset);
            File side = new File(f.getAbsolutePath() + ".sha256");
            java.nio.file.Files.write(side.toPath(), (ImageTable.FACTORY.sha256 + " " + ImageTable.FACTORY.tag + "\n").getBytes(StandardCharsets.US_ASCII));
            String mirror = "/sdcard/Download/DMR/Firmware/" + f.getName();
            RootSetup.run("mkdir -p /sdcard/Download/DMR/Firmware && cp '" + f.getAbsolutePath() + "' '" + mirror + "' && chmod 644 '" + mirror + "'");   // i18n: shell command
        } catch (Exception ignored) { }
    }

    /** The single DMR*.bin asset in the stock APK, or null. */
    static byte[] readStockAsset(Context c, String pkg) {
        String listed = RootSetup.run("pm path " + pkg + " 2>/dev/null");
        if (listed == null) return null;
        File out = new File(c.getCacheDir(), "factory-asset.bin");
        for (String line : listed.split("\n")) {
            String apk = line.trim();
            int colon = apk.indexOf(':');
            if (colon >= 0) apk = apk.substring(colon + 1).trim();
            if (!apk.endsWith(".apk")) continue;
            String listing = RootSetup.run("unzip -l '" + apk + "' 2>/dev/null");
            if (listing == null) continue;
            int n = 0; String one = "";
            for (String row : listing.split("\n")) {
                String[] parts = row.trim().split("\\s+");
                if (parts.length == 0) continue;
                String name = parts[parts.length - 1];
                if (name.startsWith("assets/DMR") && name.endsWith("bin")) { n++; one = name; }
            }
            if (n != 1) continue;
            String uid = String.valueOf(android.os.Process.myUid());
            String r = RootSetup.run("unzip -p '" + apk + "' '" + one + "' > '" + out.getAbsolutePath()
                    + "' && chown " + uid + ":" + uid + " '" + out.getAbsolutePath() + "' && echo ok");   // i18n: shell command
            if (r != null && r.endsWith("ok")) {
                try { return java.nio.file.Files.readAllBytes(out.toPath()); }
                catch (Exception e) { return null; }
            }
        }
        return null;
    }
}
