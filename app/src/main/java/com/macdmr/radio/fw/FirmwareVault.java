/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

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
        String listed = RootSetup.run("ls /sdcard/Download/DMR/Firmware/ 2>/dev/null");
        if (listed == null) return null;
        File dst = new File(c.getCacheDir(), "fw-candidate.bin");
        String uid = String.valueOf(android.os.Process.myUid());
        for (String name : listed.split("\\s+")) {
            if (!name.endsWith(".bin")) continue;
            String r = RootSetup.run("cp '/sdcard/Download/DMR/Firmware/" + name + "' '" + dst.getAbsolutePath()
                    + "' && chown " + uid + ":" + uid + " '" + dst.getAbsolutePath() + "' && echo ok");
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
            return new Copy(asset, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag, "stock app", true);
        }
        byte[] kept = readPrivate(c);
        if (kept != null && ImageTable.FACTORY.sha256.equals(sha256(kept)) && ImageTable.FACTORY.tag.equals(ImageTable.tagOf(kept)))
            return new Copy(kept, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag, "saved copy", true);
        return new Copy(null, asset == null ? "" : sha256(asset), asset == null ? "" : ImageTable.tagOf(asset), asset == null ? "" : "stock app", false);
    }

    private static File privateFile(Context c) {
        return new File(dir(c), "factory-" + ImageTable.FACTORY.tag + "-" + ImageTable.FACTORY.sha256.substring(0, 8) + ".bin");
    }

    private static byte[] readPrivate(Context c) {
        try { return java.nio.file.Files.readAllBytes(privateFile(c).toPath()); }
        catch (Exception e) { return null; }
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
            RootSetup.run("mkdir -p /sdcard/Download/DMR/Firmware && cp '" + f.getAbsolutePath() + "' '" + mirror + "' && chmod 644 '" + mirror + "'");
        } catch (Exception ignored) { }
    }

    /** The single DMR*.bin asset in the stock APK, or null. Reads the APK in-process first (no shell tools needed), then falls back to root unzip. */
    static byte[] readStockAsset(Context c, String pkg) {
        for (String apk : com.macdmr.radio.StockApk.paths(c, pkg)) {
            File f = com.macdmr.radio.StockApk.readable(c, apk, "stock-fw.apk");
            if (f == null) continue;
            try { byte[] b = dmrAsset(f); if (b != null) return b; }
            finally { if (!f.getAbsolutePath().equals(apk)) f.delete(); }
        }
        return readStockAssetUnzip(c, pkg);
    }

    /** The single assets/DMR*.bin entry of an APK, or null when there is none or more than one. */
    static byte[] dmrAsset(File apk) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry one = null; int n = 0;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry en = e.nextElement();
                if (en.getName().startsWith("assets/DMR") && en.getName().endsWith("bin")) { n++; one = en; }
            }
            if (n != 1) return null;
            try (java.io.InputStream in = z.getInputStream(one)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int r; while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                return out.toByteArray();
            }
        } catch (Exception e) { return null; }
    }

    static byte[] readStockAssetUnzip(Context c, String pkg) {
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
                    + "' && chown " + uid + ":" + uid + " '" + out.getAbsolutePath() + "' && echo ok");
            if (r != null && r.endsWith("ok")) {
                try { return java.nio.file.Files.readAllBytes(out.toPath()); }
                catch (Exception e) { return null; }
            }
        }
        return null;
    }
}
