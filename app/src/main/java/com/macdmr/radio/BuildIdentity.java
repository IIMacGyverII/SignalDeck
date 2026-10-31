/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Which build made a report, so a fork tester's device report or transmit test says exactly what was tested: the git
 * commit, branch and repository the APK was built from (app/build.gradle writes them into BuildConfig; "unknown" when
 * git was not there), whether the working tree had uncommitted changes, when it was built, and whether it is an official
 * build or a fork's. Official means signed with the project's release certificate: the APK's signing certificate
 * SHA-256 is compared with {@link #OFFICIAL_CERT_SHA256}. That fingerprint is public (anyone can read it from a released
 * APK with {@code apksigner verify --print-certs}); no key material is in the app. A fork that copies the constant still
 * cannot sign with the key, so its builds report "fork". Also which profile it ran with and whether transmit was
 * switched on for it in a way the official profile list does not show.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class BuildIdentity {
    private BuildIdentity() { }

    /**
     * SHA-256 of the official release signing certificate (lower-case hex, no colons), read from an official release APK
     * with {@code apksigner verify --print-certs} (2026-10-29). Empty would mean "unknown": every build is then
     * classified as unknown rather than official or fork.
     */
    public static final String OFFICIAL_CERT_SHA256 = "9a9a4eb2ec1e0c4a3faafe0039f8a7bae4d72f4039d49fa994bd51bd01a922b9";

    public static final String OFFICIAL = "official", FORK = "fork", UNKNOWN = "unknown";

    /** The facts, gathered once per report (host tests build them by hand). */
    public static final class Info {
        public String version = UNKNOWN, versionCode = UNKNOWN, commit = UNKNOWN, commitFull = UNKNOWN, branch = UNKNOWN, remote = UNKNOWN, dirty = UNKNOWN, builtUtc = UNKNOWN;
        public String certSha256 = UNKNOWN, kind = UNKNOWN;
        public String profileId = UNKNOWN, profileName = UNKNOWN, profileSource = UNKNOWN; public int profileRevision; public boolean profileVerified, txBlocked;
        /** An advisory switching transmit off was overridden by the owner on this phone (its id), else null. */
        public String txForcedBy;
        /** How the verified flag compares with what the official project published (see {@link #verifiedNote}). */
        public String verifiedNote = "";

        public JSONObject toJson() throws JSONException {
            JSONObject b = new JSONObject();
            b.put("version", version).put("versionCode", versionCode).put("commit", commit).put("commitFull", commitFull).put("branch", branch)
                    .put("remote", remote).put("dirty", dirty).put("builtUtc", builtUtc).put("certSha256", certSha256).put("kind", kind);
            JSONObject p = new JSONObject();
            p.put("id", profileId).put("name", profileName).put("revision", profileRevision).put("source", profileSource)
                    .put("verified", profileVerified).put("txBlocked", txBlocked).put("txForcedBy", txForcedBy == null ? JSONObject.NULL : txForcedBy)
                    .put("verifiedNote", verifiedNote);
            b.put("profile", p);
            return b;
        }

        public static Info fromJson(JSONObject b) {
            Info i = new Info(); if (b == null) return i;
            i.version = b.optString("version"); i.versionCode = b.optString("versionCode"); i.commit = b.optString("commit"); i.commitFull = b.optString("commitFull");
            i.branch = b.optString("branch"); i.remote = b.optString("remote"); i.dirty = b.optString("dirty"); i.builtUtc = b.optString("builtUtc");
            i.certSha256 = b.optString("certSha256", UNKNOWN); i.kind = b.optString("kind", UNKNOWN);
            JSONObject p = b.optJSONObject("profile");
            if (p != null) {
                i.profileId = p.optString("id"); i.profileName = p.optString("name"); i.profileRevision = p.optInt("revision"); i.profileSource = p.optString("source");
                i.profileVerified = p.optBoolean("verified"); i.txBlocked = p.optBoolean("txBlocked");
                i.txForcedBy = p.isNull("txForcedBy") ? null : p.optString("txForcedBy", null); i.verifiedNote = p.optString("verifiedNote");
            }
            return i;
        }

        /** The readable lines for 00_SUMMARY.txt and report.txt. */
        public String text() {
            StringBuilder s = new StringBuilder();
            s.append("build: ").append(version).append(" (code ").append(versionCode).append(") · ").append(kindText(kind)).append('\n');
            s.append("  commit ").append(commit).append(" (").append(commitFull).append(")").append("true".equals(dirty) ? " + uncommitted changes" : "unknown".equals(dirty) ? " (working tree state unknown)" : "").append('\n');
            s.append("  branch ").append(branch).append(" · repository ").append(remote).append(" · built ").append(builtUtc).append('\n');
            s.append("  signing certificate SHA-256 ").append(certSha256).append('\n');
            s.append("  profile ").append(profileId).append(" revision ").append(profileRevision).append(" (").append(profileSource).append(") · ")
                    .append(profileVerified ? "verified" : "not verified").append(txBlocked ? " · transmit switched off by an advisory" : "").append('\n');
            if (txForcedBy != null) s.append("  transmit forced on by the owner: advisory ").append(txForcedBy).append(" overridden on this phone\n");
            if (verifiedNote != null && !verifiedNote.isEmpty()) s.append("  ").append(verifiedNote).append('\n');
            return s.toString();
        }
    }

    static String kindText(String kind) {
        return OFFICIAL.equals(kind) ? "official build (signed with the project's release key)"
                : FORK.equals(kind) ? "fork build (not signed with the project's release key)" : "build origin unknown";
    }

    // ---- pure parts (host-tested) ------------------------------------------------------------------------------------

    /**
     * A remote URL without credentials: {@code https://user:token@host/x} loses the user part, a scp-style address keeps
     * only the conventional {@code git@}, query and fragment go, and anything shaped like a GitHub or GitLab token is
     * replaced. app/build.gradle does the same before the URL reaches BuildConfig; keep the two in step.
     */
    public static String sanitizeRemote(String u) {
        if (u == null || u.trim().isEmpty()) return UNKNOWN;
        u = u.trim().replaceAll("(?i)^([a-z][a-z0-9+.-]*://)[^/@]*@", "$1");
        u = u.replaceAll("^(?!git@)[^@/:]+@([^:/]+):", "$1:");
        u = u.replaceAll("[?#].*$", "");
        return u.replaceAll("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,})", "<token>");
    }

    /** "ab:CD:…" or "abcd…" → lower-case hex without separators; null for anything that is not 64 hex digits. */
    public static String normalizeSha256(String s) {
        if (s == null) return null;
        String h = s.replaceAll("[\\s:]", "").toLowerCase(Locale.ROOT);
        return h.matches("[0-9a-f]{64}") ? h : null;
    }

    /** official when the certificate matches the official one, fork when it differs, unknown when either is missing. */
    public static String classify(String certSha256, String officialSha256) {
        String c = normalizeSha256(certSha256), o = normalizeSha256(officialSha256);
        if (c == null || o == null) return UNKNOWN;
        return c.equals(o) ? OFFICIAL : FORK;
    }

    /**
     * How a profile's verified flag relates to what the project published. A fork may set {@code verified = true} in its
     * own build to test transmit; that is expected, and the report says so plainly, so the maintainer knows the flag came
     * from the fork. {@code listVerified} is the signed official profile list's verified value for this id (null when the
     * list on the phone does not carry it).
     */
    public static String verifiedNote(String kind, boolean verified, Boolean listVerified) {
        if (!verified) return "verified=false: transmit is off for this profile";
        if (Boolean.TRUE.equals(listVerified)) return "verified=true, as in the official signed profile list";
        if (OFFICIAL.equals(kind)) return "verified=true in this official build";
        return "verified=true in this build; not in the official profile list";
    }

    /** The official signed list's verified value for a profile id, or null when it does not carry that profile. */
    public static Boolean listVerified(JSONObject officialList, String id) {
        org.json.JSONArray a = officialList == null ? null : officialList.optJSONArray("profiles");
        if (a == null || id == null) return null;
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) return o.optBoolean("verified", false); }
        return null;
    }

    /** The id of an overridden advisory that had switched transmit (or the take-over and with it transmit) off, else null. */
    public static String txForcedBy(ProfileRegistry.Selection s) {
        if (s == null) return null;
        for (ProfileRegistry.Advisory a : s.advisories) if (a.overridden && ("transmit".equals(a.restrict) || "takeover".equals(a.restrict))) return a.id;
        return null;
    }

    /** The build facts from BuildConfig and the profile; certificate and kind are filled by {@link #gather}. */
    public static Info fromBuildConfig(DeviceProfile p, ProfileRegistry.Selection sel, JSONObject officialList, String kind) {
        Info i = new Info();
        i.version = BuildConfig.VERSION_NAME; i.versionCode = String.valueOf(BuildConfig.VERSION_CODE);
        i.commit = BuildConfig.GIT_COMMIT; i.commitFull = BuildConfig.GIT_COMMIT_FULL; i.branch = BuildConfig.GIT_BRANCH;
        i.remote = sanitizeRemote(BuildConfig.GIT_REMOTE); i.dirty = BuildConfig.GIT_DIRTY; i.builtUtc = BuildConfig.BUILD_TIME_UTC;
        i.kind = kind == null ? UNKNOWN : kind;
        if (p != null) {
            i.profileId = p.id; i.profileName = p.name; i.profileRevision = p.revision; i.profileSource = p.source; i.profileVerified = p.verified; i.txBlocked = p.txBlocked;
            i.verifiedNote = verifiedNote(i.kind, p.verified, listVerified(officialList, p.id));
        }
        i.txForcedBy = txForcedBy(sel);
        return i;
    }

    // ---- on the phone ------------------------------------------------------------------------------------------------

    /** SHA-256 of this APK's signing certificate (the current signer after a key rotation), or null. */
    public static String certSha256(android.content.Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] sig = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            if (sig == null || sig.length == 0) return null;
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sig[0].toByteArray());
            StringBuilder h = new StringBuilder(); for (byte b : d) h.append(String.format(Locale.US, "%02x", b & 0xFF));
            return h.toString();
        } catch (Throwable t) { return null; }
    }

    public static Info gather(android.content.Context c) {
        String cert = c == null ? null : certSha256(c);
        JSONObject list = null; try { if (c != null) list = ProfileRegistry.cachedList(c); } catch (Throwable ignored) { }
        Info i = fromBuildConfig(ProfileRegistry.active(), ProfileRegistry.selection(), list, classify(cert, OFFICIAL_CERT_SHA256));
        i.certSha256 = cert == null ? UNKNOWN : cert;
        return i;
    }
}
