/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * On a phone SignalDeck has a profile for: is this phone's stock module firmware in the signed firmware catalogue?
 * Compares the version the module first reported (its stock firmware) and the stock app's firmware file hash with the
 * catalogue's images and variants. A variant the catalogue lacks is offered once for submission to the firmware
 * project's "Firmware variant seen" issue form, filled in, in the browser; the user checks it and presses Submit.
 * Nothing is sent by the app. When the stock app's firmware file is new, it is copied to Download/DMR/FoundFirmware so the
 * user can attach it to the issue by hand if they choose (an attachment there is public); the app never uploads it.
 */
public final class FirmwareVariantCheck {
    private FirmwareVariantCheck() { }

    public static final String ISSUES = "https://github.com/IIMacGyverII/AndroidRadio_Firmware_Project/issues/new";   // i18n: URL
    private static final String PREFS = "radio";
    /** SignalDeck's own bench and release tags (DMR003.FW27.V022, DMR003.R4a0.V022): never a phone's stock firmware. */
    private static final Pattern OWN_TAG = Pattern.compile("^DMR\\d{3}\\.(FW\\d+|R\\d[0-9A-Za-z]*)\\.V\\d+$");

    /** What the phone has. assetSha is "" when the stock app's firmware file could not be read. */
    public static final class Facts {
        public final String model, stockTag, stockPkg, stockPkgVersion, assetSha;
        public Facts(String model, String stockTag, String stockPkg, String stockPkgVersion, String assetSha) {
            this.model = model == null ? "" : model.trim(); this.stockTag = stockTag == null ? "" : stockTag.trim();
            this.stockPkg = stockPkg == null ? "" : stockPkg; this.stockPkgVersion = stockPkgVersion == null ? "" : stockPkgVersion;
            this.assetSha = assetSha == null ? "" : assetSha.trim().toLowerCase(Locale.US);
        }
        /** One key per variant: the prompt is shown once per key. */
        public String key() { return stockTag + "|" + assetSha; }
    }

    /** What the catalogue (and the built-in table) knows: version tags and file hashes. */
    public static final class Known {
        public final List<String> tags = new ArrayList<>(), shas = new ArrayList<>();
        public int serial;
    }

    /** null when the catalogue lists this variant (or there is nothing to judge); otherwise what is missing, for the issue's message. */
    public static String missing(Facts f, Known k) {
        if (f.stockTag.isEmpty() || OWN_TAG.matcher(f.stockTag).matches()) return null;
        boolean tag = k.tags.contains(f.stockTag), sha = f.assetSha.isEmpty() || k.shas.contains(f.assetSha);
        if (tag && sha) return null;
        if (!tag && !sha) return "the module version " + f.stockTag + " and the stock app's firmware file are not in the firmware catalogue";   // i18n: issue text for the firmware project (English, sent to GitHub)
        if (!tag) return "the module version " + f.stockTag + " is not in the firmware catalogue";   // i18n: issue text for the firmware project (English, sent to GitHub)
        return "the stock app's firmware file (sha256 " + f.assetSha + ") is not in the firmware catalogue";   // i18n: issue text for the firmware project (English, sent to GitHub)
    }

    /** Tags and hashes from a verified catalogue body, plus SignalDeck's built-in images. */
    public static Known known(byte[] catalogue) {
        Known k = new Known();
        for (com.macdmr.radio.fw.ImageTable.Row r : com.macdmr.radio.fw.ImageTable.rows()) { k.tags.add(r.tag); k.shas.add(r.sha256); }
        if (catalogue == null) return k;
        try {
            JSONObject o = new JSONObject(new String(catalogue, StandardCharsets.UTF_8));
            k.serial = o.optInt("serial");
            JSONArray im = o.optJSONArray("images");
            if (im != null) for (int i = 0; i < im.length(); i++) {
                JSONObject r = im.optJSONObject(i); if (r == null) continue;
                add(k.tags, r.optString("tag")); add(k.shas, r.optString("sha256").toLowerCase(Locale.US));
            }
            JSONArray devs = o.optJSONArray("variants");
            if (devs != null) for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.optJSONObject(i); JSONArray vs = d == null ? null : d.optJSONArray("variants");
                if (vs != null) for (int j = 0; j < vs.length(); j++) {
                    JSONObject v = vs.optJSONObject(j); if (v == null) continue;
                    add(k.tags, v.optString("moduleVersion"));
                    if (!v.isNull("assetSha256")) add(k.shas, v.optString("assetSha256").toLowerCase(Locale.US));
                }
            }
        } catch (Exception ignored) { }
        return k;
    }
    private static void add(List<String> l, String s) { if (s != null && !s.isEmpty() && !l.contains(s)) l.add(s); }

    /** The issue form, filled in (field ids of .github/ISSUE_TEMPLATE/firmware-variant.yml). Only the phone model, versions and hash. */
    public static String issueUrl(Facts f, String why, String appVersion) {
        String stockApp = f.stockPkg.isEmpty() ? "" : f.stockPkg + (f.stockPkgVersion.isEmpty() ? "" : " " + f.stockPkgVersion);
        String msg = "SignalDeck " + appVersion + ": " + why + ".";   // i18n: issue text for the firmware project (English, sent to GitHub)
        return ISSUES + "?template=firmware-variant.yml"   // i18n: issue text for the firmware project (English, sent to GitHub)
                + "&title=" + enc("[variant] " + (f.stockTag.isEmpty() ? "unknown version" : f.stockTag) + " on " + f.model)   // i18n: issue text for the firmware project (English, sent to GitHub)
                + "&model=" + enc(f.model) + "&version=" + enc(f.stockTag) + "&stockapp=" + enc(stockApp)
                + "&hash=" + enc(f.assetSha) + "&message=" + enc(msg);
    }
    /** The issue's attachment box, filled in with where the copied file is (the user attaches it, or not). */
    public static String withFileNote(String url, String folder, String name) {
        return url + "&report=" + enc("The stock firmware file " + name + " was copied on the phone to Download/DMR/FoundFirmware/" + folder   // i18n: issue text for the firmware project (English, sent to GitHub)
                + "/. Attach it here only if you choose to: an attachment on this issue is public, and the file belongs to the phone maker.");   // i18n: issue text for the firmware project (English, sent to GitHub)
    }

    /** The single assets/DMR*.bin in an APK (the stock app's firmware file), or null when there is none or more than one. */
    static byte[] assetFrom(File apk) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry one = null; int n = 0;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry ze = e.nextElement();
                if (!ze.isDirectory() && ze.getName().startsWith("assets/DMR") && ze.getName().endsWith("bin")) { one = ze; n++; }
            }
            if (n != 1 || one.getSize() > (32L << 20)) return null;
            try (java.io.InputStream in = z.getInputStream(one)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) o.write(b, 0, r);
                return o.toByteArray();
            }
        } catch (Exception e) { return null; }
    }
    static String assetName(File apk) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.startsWith("assets/DMR") && n.endsWith("bin")) return n.substring(n.lastIndexOf('/') + 1);
            }
        } catch (Exception ignored) { }
        return "firmware.bin";
    }

    /** Put the stock firmware file into dir, with a SOURCES.txt, only when its hash is the one reported. @return the file name, or null */
    static String writeFile(byte[] image, String name, String expectSha, String source, File dir) throws Exception {
        if (image == null || !expectSha.equalsIgnoreCase(com.macdmr.radio.fw.FirmwareVault.sha256(image))) return null;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("cannot create " + dir);
        String safe = FirmwareFinder.safe(name);
        java.nio.file.Files.write(new File(dir, safe).toPath(), image);
        String src = "Stock firmware file copied by SignalDeck for the firmware project's \"Firmware variant seen\" issue.\n"   // i18n: SOURCES.txt file text (English)
                + safe + "  <-  " + source + "  " + image.length + " bytes  sha256 " + expectSha.toLowerCase(Locale.US) + "\n"   // i18n: SOURCES.txt file text (English)
                + "It belongs to the phone maker. Attaching it to the issue is your choice; an attachment there is public.\n";   // i18n: SOURCES.txt file text (English)
        java.nio.file.Files.write(new File(dir, "SOURCES.txt").toPath(), src.getBytes(StandardCharsets.UTF_8));
        return safe;
    }

    /**
     * Worker thread: copy the stock app's firmware file to Download/DMR/FoundFirmware/&lt;model&gt;/ when its hash matches
     * the finding. Reads the APK directly, or through root when the app cannot. @return the issue URL to open (with the note when copied)
     */
    public static String prepareAttachment(Context c, Finding f) {
        if (!f.fileToAttach() || f.pkg.isEmpty()) return f.url;
        File tmp = new File(c.getCacheDir(), "fwvariant.apk");
        try {
            for (String apk : StockApk.paths(c, f.pkg)) {   // root pm path when the package manager cannot see the stock app
                File a = new File(apk);
                byte[] img = assetFrom(a); String name = img == null ? null : assetName(a);
                if (img == null && RootSetup.haveRoot()) {
                    RootSetup.run("cat '" + apk + "' > '" + tmp.getAbsolutePath() + "' && chmod 644 '" + tmp.getAbsolutePath() + "'");   // i18n: shell command
                    img = assetFrom(tmp); name = img == null ? null : assetName(tmp);
                }
                if (img == null) continue;
                String folder = FirmwareFinder.safe(android.os.Build.MANUFACTURER + "_" + android.os.Build.MODEL);
                File dir = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/FoundFirmware"), folder);
                String written = writeFile(img, name, f.sha, f.pkg + "!" + "assets/" + name, dir);
                if (written != null) return withFileNote(f.url, folder, written);
            }
        } catch (Throwable ignored) {
        } finally { tmp.delete(); }
        return f.url;
    }

    /** The stock app's version name; through root (dumpsys) when the package manager cannot see it (Android 11+ package visibility). */
    static String stockAppVersion(Context c, String pkg) {
        String v = com.macdmr.radio.fw.FirmwareVault.stockVersion(c, pkg);
        if (!v.isEmpty() || pkg.isEmpty() || !pkg.matches("[A-Za-z0-9_.]+") || !RootSetup.haveRoot()) return v;
        return versionFromDumpsys(RootSetup.run("dumpsys package " + pkg + " 2>/dev/null | grep -m 1 versionName="));
    }
    static String versionFromDumpsys(String s) {
        if (s == null) return "";
        int i = s.indexOf("versionName=");
        if (i < 0) return "";
        String v = s.substring(i + 12).trim();
        int sp = v.indexOf(' '); if (sp >= 0) v = v.substring(0, sp);
        return v.length() > 40 ? v.substring(0, 40) : v;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
    }

    // ---- the stored result the Device page shows ----

    /** The open finding: key, url and message, or null when the catalogue lists this phone's firmware. */
    public static final class Finding {
        public final String key, url, why, pkg, sha; public final boolean opened, fileNew;
        Finding(String key, String url, String why, String pkg, String sha, boolean fileNew, boolean opened) {
            this.key = key; this.url = url; this.why = why; this.pkg = pkg; this.sha = sha; this.fileNew = fileNew; this.opened = opened;
        }
        /** The stock firmware file can be offered for attaching: it was read, and the catalogue does not have it. */
        public boolean fileToAttach() { return fileNew && sha.length() == 64; }
    }

    public static Finding finding(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = p.getString("fwVariantKey", "");
        if (key.isEmpty()) return null;
        return new Finding(key, p.getString("fwVariantUrl", ""), p.getString("fwVariantWhy", ""), p.getString("fwVariantPkg", ""),
                p.getString("fwVariantSha", ""), p.getBoolean("fwVariantFileNew", false), p.getBoolean("fwVariantOpened:" + key, false));
    }

    private static void clear(SharedPreferences p) { p.edit().remove("fwVariantKey").remove("fwVariantUrl").remove("fwVariantWhy").remove("fwVariantPkg").remove("fwVariantSha").remove("fwVariantFileNew").apply(); }

    /** The user opened the issue form for this variant (the notice then says it is waiting for the catalogue). */
    public static void markOpened(Context c, String key) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("fwVariantOpened:" + key, true).apply(); }

    /**
     * Worker thread. Fetches the catalogue at most once a day, then stores the finding (or clears it).
     * @return the finding when it is new and was never offered before (the caller asks once), else null
     */
    public static Finding run(Context c, RadioService svc) {
        DeviceProfile dp = svc.profile();
        if (!dp.supported || !svc.isReady() || svc.stockFirmware().isEmpty()) return null;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!dp.firmwareCatalogued()) { clear(p); return null; }                         // nothing could be submitted (and a stored finding goes)
        long now = System.currentTimeMillis();
        if (now - p.getLong("fwCatalogCheckedAt", 0) > 24L * 3600_000) {
            com.macdmr.radio.fw.FirmwareCatalog.fetch(c);
            p.edit().putLong("fwCatalogCheckedAt", now).apply();
        }
        if (com.macdmr.radio.fw.FirmwareCatalog.cached(c) == null) return null;          // no verified catalogue: nothing to judge against
        byte[] body;
        try { body = java.nio.file.Files.readAllBytes(new File(c.getFilesDir(), "firmware/catalog/firmware.json").toPath()); }   // the file cached() just verified
        catch (Exception e) { return null; }
        String sha = "";
        // the stock app's firmware file; not for the built-in factory tag, whose file the firmware page verifies (and reading it there keeps the backup copy)
        boolean builtIn = com.macdmr.radio.fw.ImageTable.FACTORY.tag.equals(svc.stockFirmware().trim());
        try { if (!builtIn && !dp.stockApp.isEmpty() && RootSetup.haveRoot()) sha = com.macdmr.radio.fw.FirmwareVault.factory(c).sha256; } catch (Throwable ignored) { }
        Facts f = new Facts(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, svc.stockFirmware(), dp.stockApp,
                stockAppVersion(c, dp.stockApp), sha);
        Known k = known(body);
        String why = missing(f, k);
        if (why == null) { clear(p); return null; }
        String ver; try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { ver = ""; }
        String url = issueUrl(f, why, ver);
        p.edit().putString("fwVariantKey", f.key()).putString("fwVariantUrl", url).putString("fwVariantWhy", why).putString("fwVariantPkg", f.stockPkg)
                .putString("fwVariantSha", f.assetSha).putBoolean("fwVariantFileNew", !f.assetSha.isEmpty() && !k.shas.contains(f.assetSha)).apply();
        if (p.getBoolean("fwVariantAsked:" + f.key(), false)) return null;
        p.edit().putBoolean("fwVariantAsked:" + f.key(), true).apply();
        return finding(c);
    }
}
