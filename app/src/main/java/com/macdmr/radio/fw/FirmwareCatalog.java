/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import android.content.Context;

import com.macdmr.radio.ProfileRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The signed firmware catalogue. The same key and signature scheme as the profile list.
 * A row is kept only when the file verifies, and an older serial is ignored.
 */
public final class FirmwareCatalog {
    public static final String URL =
            "https://raw.githubusercontent.com/IIMacGyverII/AndroidRadio_Firmware_Project/main/catalog/firmware.json";

    public static final class Image {
        public final String tag, sha256, summary, baseTag, model, stockPackage, stockVersion, url, status, device;
        public final int size;
        public final boolean factory, readBack;
        /** Lowest SignalDeck versionCode that may load this image (catalogue status "released-min"); 0 = any. */
        public int minAppVersionCode;

        Image(String tag, String sha256, int size, String summary, String baseTag, String model,
              String stockPackage, String stockVersion, String url, String status, String device,
              boolean factory, boolean readBack) {
            this.tag = tag; this.sha256 = sha256; this.size = size; this.summary = summary; this.baseTag = baseTag;
            this.model = model; this.stockPackage = stockPackage; this.stockVersion = stockVersion;
            this.url = url; this.status = status; this.device = device; this.factory = factory; this.readBack = readBack;
        }
    }

    public static final class Variant {
        public final String device, moduleVersion, stockPackage, stockVersion;
        /** The phone's Build.MODEL for this device block (catalogue serial 4 on), "" when the block has none. */
        public final String buildModel;
        public final boolean supportedBase;

        Variant(String device, String moduleVersion, String stockPackage, String stockVersion, boolean supportedBase) { this(device, "", moduleVersion, stockPackage, stockVersion, supportedBase); }
        Variant(String device, String buildModel, String moduleVersion, String stockPackage, String stockVersion, boolean supportedBase) {
            this.device = device; this.buildModel = buildModel == null ? "" : buildModel; this.moduleVersion = moduleVersion; this.stockPackage = stockPackage;
            this.stockVersion = stockVersion; this.supportedBase = supportedBase;
        }
    }

    public static final class Doc {
        public final int serial;
        public final List<Image> images;
        public final List<Variant> variants;

        Doc(int serial, List<Image> images, List<Variant> variants) {
            this.serial = serial; this.images = images; this.variants = variants;
        }
    }

    private FirmwareCatalog() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "firmware/catalog");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Parse a catalogue body. Does not check the signature. */
    public static Doc parse(byte[] json) throws Exception {
        JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
        List<Image> images = new ArrayList<>();
        JSONArray arr = o.optJSONArray("images");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            images.add(new Image(
                    row.optString("tag"), row.optString("sha256").toLowerCase(java.util.Locale.US), row.optInt("size"),
                    row.optString("summary"), row.optString("base"), row.optString("model"),
                    row.optString("stockPackage"), row.optString("stockVersion"), row.optString("url"),
                    row.optString("status"), row.optString("device"),
                    row.optBoolean("factory"), row.optBoolean("readBack")));
            images.get(images.size() - 1).minAppVersionCode = row.optInt("minAppVersionCode", 0);
        }
        List<Variant> variants = new ArrayList<>();
        JSONArray devices = o.optJSONArray("variants");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev == null) continue;
            String id = dev.optString("device"), buildModel = dev.isNull("buildModel") ? "" : dev.optString("buildModel", "");
            JSONArray vs = dev.optJSONArray("variants");
            if (vs == null) continue;
            for (int k = 0; k < vs.length(); k++) {
                JSONObject v = vs.optJSONObject(k);
                if (v == null) continue;
                JSONObject app = v.optJSONObject("stockApp");
                variants.add(new Variant(id, buildModel, v.optString("moduleVersion", ""),
                        app == null ? "" : app.optString("package"),
                        app == null ? "" : app.optString("version"),
                        v.optBoolean("supportedBase")));
            }
        }
        return new Doc(o.optInt("serial"), images, variants);
    }

    /**
     * The firmware catalogue's own signing key (AndroidRadio_Firmware_Project catalog/public_key_firmware.b64, KEYS.md),
     * used from serial {@link #FIRMWARE_KEY_FROM_SERIAL} on. Earlier serials were signed with the profile-list key. Builds
     * before beta.22 only know the profile key, so they cannot verify serial 6 or later and keep serial 5.
     */
    public static final String FIRMWARE_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEm8EYMfAtrrQnIqRzAPjH2ZdZjKm9z8SnrVQep042zlGbaxPomUz8qw7ref7vBOBY/hB1UrBNyElaF6SqOydVcw==";
    public static final int FIRMWARE_KEY_FROM_SERIAL = 6;

    /** @return null when the signature, the serial, or the body is not acceptable */
    public static Doc accept(byte[] json, String sig, int previousSerial) {
        return accept(json, sig, previousSerial, ProfileRegistry.PUBLIC_KEY, FIRMWARE_PUBLIC_KEY);
    }

    /** The key follows the serial inside the signed body: the firmware key from serial 6 on, the profile key before. */
    static Doc accept(byte[] json, String sig, int previousSerial, String profileKey, String firmwareKey) {
        if (json == null || sig == null) return null;
        try {
            Doc doc = parse(json);
            String key = doc.serial >= FIRMWARE_KEY_FROM_SERIAL ? firmwareKey : profileKey;
            if (!ProfileRegistry.verify(json, sig, key)) return null;
            if (doc.serial < previousSerial) return null;
            return doc;
        } catch (Exception e) { return null; }
    }

    public static Doc cached(Context c) {
        try {
            File f = new File(dir(c), "firmware.json"), g = new File(dir(c), "firmware.json.sig");
            if (!f.isFile() || !g.isFile()) return null;
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String sig = new String(java.nio.file.Files.readAllBytes(g.toPath()), StandardCharsets.US_ASCII);
            return accept(data, sig, 0);
        } catch (Exception e) { return null; }
    }

    /**
     * Whether a catalogue row may be offered to this app. "released-min" is a released image that needs at least
     * {@code minAppVersionCode}: builds older than its introduction only know "stock" and "released", so they never see it
     * (FW57 needs SignalDeck beta.22's data-hold fix; with beta.21 and M17 receive on, its DMR keys are silent).
     */
    public static boolean offered(String status, int minAppVersionCode, int appVersionCode) {
        if ("stock".equals(status) || "released".equals(status)) return true;
        return "released-min".equals(status) && minAppVersionCode > 0 && appVersionCode >= minAppVersionCode;
    }

    /** Built-in rows, plus catalogue rows that are stock or released (and, for "released-min", new enough) and not already listed. */
    public static List<ImageTable.Row> rows(Context c) {
        Doc doc = c == null ? null : cached(c);
        return rows(doc == null ? null : doc.images, com.macdmr.radio.BuildConfig.VERSION_CODE);
    }
    /** {@link #rows(Context)} for a given catalogue image list and app versionCode. */
    static List<ImageTable.Row> rows(List<Image> images, int appVersionCode) {
        List<ImageTable.Row> out = new ArrayList<>();
        java.util.Collections.addAll(out, ImageTable.rows());
        if (images == null) return out;
        for (Image im : images) {
            if (im.tag == null || im.sha256 == null || im.sha256.length() != 64) continue;
            if (!offered(im.status, im.minAppVersionCode, appVersionCode)) continue;
            boolean have = false;
            for (ImageTable.Row r : out) if (r.sha256.equals(im.sha256)) have = true;
            if (have) continue;
            if (!im.factory && !ImageTable.FACTORY.tag.equals(im.baseTag)) continue;
            out.add(new ImageTable.Row(im.tag, im.sha256, im.size, im.summary, im.baseTag, im.model,
                    im.stockPackage, im.stockVersion, im.factory, im.readBack));
        }
        return out;
    }

    /**
     * When the catalogue knows this phone and none of its variants are a supported base, say so.
     * No catalogue on the phone leaves the built-in check in charge.
     */
    public static String variantRefusal(Context c, String model, String pkg) {
        Doc doc = cached(c);
        if (doc == null || model == null) return null;
        String device = null;
        for (Variant v : doc.variants) if (!v.buildModel.isEmpty() && (model.equals(v.buildModel) || model.endsWith(v.buildModel))) device = v.device;   // serial 4 on: the block names the phone
        if (device == null) for (Image im : doc.images) if (model.equals(im.model) || model.endsWith(im.model)) device = im.device;              // older catalogues: through an image row
        if (device == null) return null;
        boolean seen = false, supported = false;
        for (Variant v : doc.variants) {
            if (!device.equals(v.device)) continue;
            seen = true;
            if (v.supportedBase && pkg != null && pkg.equals(v.stockPackage)) supported = true;
        }
        return seen && !supported ? "This phone's module variant is not supported yet" : null;
    }

    /** Fetch and store the catalogue. @return one line for the page */
    public static String fetch(Context c) {
        try {
            com.macdmr.radio.repeaters.HttpClient.Response body = com.macdmr.radio.repeaters.HttpClient.get(URL + "?t=" + System.currentTimeMillis() / 600_000);
            com.macdmr.radio.repeaters.HttpClient.Response sig = com.macdmr.radio.repeaters.HttpClient.get(URL + ".sig?t=" + System.currentTimeMillis() / 600_000);
            if (!body.isOk() || !sig.isOk()) return "Firmware catalogue not reachable";
            Doc old = cached(c);
            int previous = old == null ? 0 : old.serial;
            Doc doc = accept(body.body, sig.bodyUtf8(), previous);
            if (doc == null) return "Firmware catalogue ignored";
            if (old != null && doc.serial == old.serial) return "Firmware catalogue is up to date (" + doc.serial + ")";
            java.nio.file.Files.write(new File(dir(c), "firmware.json").toPath(), body.body);
            java.nio.file.Files.write(new File(dir(c), "firmware.json.sig").toPath(), sig.bodyUtf8().trim().getBytes(StandardCharsets.US_ASCII));
            return "Firmware catalogue " + doc.serial + " loaded: " + doc.images.size() + " image" + (doc.images.size() == 1 ? "" : "s");
        } catch (Exception e) { return "Firmware catalogue check failed"; }
    }

    /** Download one catalogue image and, for the factory row, keep it as the verified backup. @return the bytes, or null */
    public static byte[] download(Context c, String tag) {
        Doc doc = cached(c);
        if (doc == null || tag == null) return null;
        Image want = null;
        for (Image im : doc.images) if (tag.equals(im.tag)) want = im;
        if (want == null || want.url == null || want.url.isEmpty()) return null;
        try {
            com.macdmr.radio.repeaters.HttpClient.Response r = com.macdmr.radio.repeaters.HttpClient.get(want.url);
            if (!r.isOk() || r.body.length != want.size) return null;
            if (!want.sha256.equals(FirmwareVault.sha256(r.body))) return null;
            if (!want.tag.equals(ImageTable.tagOf(r.body))) return null;
            if (want.factory) FirmwareVault.keepFactory(c, r.body);
            return r.body;
        } catch (Exception e) { return null; }
    }
}
