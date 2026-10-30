/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The known module images. A load is refused unless the file's hash is one of these rows and the phone's
 * vendor image, model and stock app agree with that row's base. FW22 stays a built-in bench row.
 * The signed catalogue adds stock and released images; bench images are not taken from it.
 */
public final class ImageTable {
    /** The version string sits at this file offset in every image built from the stock UV4T image. */
    public static final int TAG_AT = 0x38464;

    public static final class Row {
        public final String tag, sha256, summary, baseTag, model, stockPackage, stockVersion;
        public final int size;
        public final boolean factory, readBack, unlisted;

        Row(String tag, String sha256, int size, String summary, String baseTag, String model, String stockPackage, String stockVersion, boolean factory, boolean readBack) {
            this(tag, sha256, size, summary, baseTag, model, stockPackage, stockVersion, factory, readBack, false);
        }

        Row(String tag, String sha256, int size, String summary, String baseTag, String model, String stockPackage, String stockVersion, boolean factory, boolean readBack, boolean unlisted) {
            this.tag = tag; this.sha256 = sha256; this.size = size; this.summary = summary; this.baseTag = baseTag;
            this.model = model; this.stockPackage = stockPackage; this.stockVersion = stockVersion;
            this.factory = factory; this.readBack = readBack; this.unlisted = unlisted;
        }
    }

    public static final Row FACTORY = new Row(
            "DMR003.UV4T.V022",
            "90e361fda1082cdd010f8f2127eab97786f2a078f2f7590821c26867526c8c10",
            378620,
            "The firmware this phone shipped with. Loading it puts the module back the way it was. The image stays until another one is loaded, including across a reboot.",
            "DMR003.UV4T.V022",
            "Armor 26 Ultra",
            "com.pri.prizeinterphone",
            "V1.0",
            true, false);

    public static final Row FW22 = new Row(
            "DMR003.FW22.V022",
            "38f900a0df169c0101f9e6183fe8ec8d2e9cdca74169f034f5571b04aa988d6a",
            378620,
            "Bench image. A tone still plays when nothing else is queued, and the module can read its own flash. The radio's own transmit limit on this image is 120 seconds. It stays until another image is loaded, including across a reboot. It does not change bands or power.",
            FACTORY.tag,
            FACTORY.model,
            FACTORY.stockPackage,
            FACTORY.stockVersion,
            false, true);

    private static final Row[] ROWS = { FACTORY, FW22 };

    private ImageTable() { }

    public static Row[] rows() { return ROWS.clone(); }

    public static Row byTag(String tag) { return byTag(tag, Arrays.asList(ROWS)); }
    /** The row for a module tag among {@code known} (the built-in rows plus the offered catalogue rows, {@link FirmwareCatalog#rows}). */
    public static Row byTag(String tag, List<Row> known) {
        if (tag == null) return null;
        String t = tag.trim();
        for (Row r : known) if (r.tag.equals(t)) return r;
        return null;
    }

    public static Row bySha(String sha) { return bySha(sha, Arrays.asList(ROWS)); }
    public static Row bySha(String sha, List<Row> known) {
        if (sha == null) return null;
        String s = sha.trim().toLowerCase(Locale.US);
        for (Row r : known) if (r.sha256.equals(s)) return r;
        return null;
    }

    /** The 16-byte version at {@link #TAG_AT}, or "" when the file is too short. */
    public static String tagOf(byte[] image) {
        if (image == null || image.length < TAG_AT + 16) return "";
        int n = 0;
        while (n < 16 && image[TAG_AT + n] != 0) n++;
        return new String(image, TAG_AT, n, StandardCharsets.US_ASCII).trim();
    }

    public static boolean modelMatches(String reported) {
        if (reported == null) return false;
        return reported.equals(FACTORY.model) || reported.endsWith(FACTORY.model);
    }

    /**
     * Why {@code image} must not be loaded, or null when the pre-flight identity checks pass.
     * {@code moduleTag} empty means the module did not answer: only the factory image, and only from a verified backup.
     */
    public static String refuse(Row image, String moduleTag, String assetSha, String assetTag,
                                String model, String pkg, String pkgVersion, boolean backupVerified, boolean stockPresent) {
        return refuse(image, moduleTag, assetSha, assetTag, model, pkg, pkgVersion, backupVerified, stockPresent, Arrays.asList(ROWS));
    }
    /**
     * As above, with {@code known} the images this build may load: the built-in rows plus the catalogue rows it offers
     * ({@link FirmwareCatalog#rows}: signed, stock or released, "released-min" only when this build is new enough).
     * Both the file and the module's running image are looked up there, so a released catalogue image can be loaded
     * from the factory image, and another image (or the factory one) can be loaded while it runs.
     */
    public static String refuse(Row image, String moduleTag, String assetSha, String assetTag,
                                String model, String pkg, String pkgVersion, boolean backupVerified, boolean stockPresent, List<Row> known) {
        if (image == null) return "That file is not a known module image";
        if (!FACTORY.sha256.equals(image.sha256) && bySha(image.sha256, known) == null) return "That file is not a known module image";
        boolean silent = moduleTag == null || moduleTag.trim().isEmpty();
        if (silent) {
            if (!image.factory) return "The module did not answer. Recovery loads the factory image only";
            if (!backupVerified) return stockPresent
                    ? "The factory backup is missing or does not match. Connect the stock app's copy before recovery"
                    : "The stock app is gone and there is no verified factory copy on this phone";
            return null;
        }
        String tag = moduleTag.trim();
        Row running = byTag(tag, known);
        if (running == null) {
            if (!(image.factory && backupVerified)) return "Firmware variant not yet supported (" + tag + ")";
        } else if (!FACTORY.tag.equals(running.baseTag) && !FACTORY.tag.equals(running.tag)) {
            return "Firmware variant not yet supported (" + tag + ")";
        }
        if (!modelMatches(model)) return "This phone model is not the one that image was built for";
        if (pkg == null || !FACTORY.stockPackage.equals(pkg)) return "The stock radio app on this phone is not the one this image belongs to";
        boolean assetOk = FACTORY.sha256.equals(assetSha) && FACTORY.tag.equals(assetTag);
        if (!assetOk && !backupVerified)
            return stockPresent
                    ? "The stock app's firmware does not match the known factory image, and there is no verified backup"
                    : "The stock app is gone and there is no verified factory copy on this phone";
        if (!image.factory && !backupVerified) return "Load the factory backup onto this phone before any other image";
        if (!image.factory && !FACTORY.tag.equals(image.baseTag)) return "That image was not built from this phone's factory firmware";
        return null;
    }

    /**
     * Why an unlisted file must not be loaded, or null when the identity checks still pass.
     * The file is not in the catalogue, so the known-image check does not apply. The factory backup does.
     */
    public static String unlistedPolicy(String moduleTag, boolean backupVerified, String model, String pkg) {
        return unlistedPolicy(moduleTag, backupVerified, model, pkg, Arrays.asList(ROWS));
    }
    public static String unlistedPolicy(String moduleTag, boolean backupVerified, String model, String pkg, List<Row> known) {
        if (!backupVerified) return "Load the factory backup onto this phone before any other image";
        if (moduleTag == null || moduleTag.trim().isEmpty()) return "The module did not answer. Recovery loads the factory image only";
        String tag = moduleTag.trim();
        Row running = byTag(tag, known);
        if (running == null) return "Firmware variant not yet supported (" + tag + ")";
        if (!FACTORY.tag.equals(running.baseTag) && !FACTORY.tag.equals(running.tag)) return "Firmware variant not yet supported (" + tag + ")";
        if (!modelMatches(model)) return "This phone model is not the one that image was built for";
        if (pkg == null || !FACTORY.stockPackage.equals(pkg)) return "The stock radio app on this phone is not the one this image belongs to";
        return null;
    }

    /**
     * A note when the stock app's version name is not the one the table recorded, but only when the asset
     * itself matches. A different file is a refusal from {@link #refuse}, not a warning.
     */
    public static String versionWarning(String assetSha, String pkgVersion) {
        if (!FACTORY.sha256.equals(assetSha)) return null;
        if (pkgVersion == null || FACTORY.stockVersion.equals(pkgVersion)) return null;
        return "Stock app is " + pkgVersion + ", not " + FACTORY.stockVersion + ". The firmware file matches, so the load can continue";
    }
}
