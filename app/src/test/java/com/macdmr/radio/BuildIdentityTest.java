/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Build identity: remote URLs without credentials, official vs fork from the signing certificate, the verified note. */
public class BuildIdentityTest {
    private static final String A = "9a9a4eb2ec1e0c4a3faafe0039f8a7bae4d72f4039d49fa994bd51bd01a922b9";
    private static final String B = "0000000000000000000000000000000000000000000000000000000000000001";

    @Test public void remoteUrlsLoseCredentials() {
        assertEquals("https://github.com/someone/SignalDeck.git", BuildIdentity.sanitizeRemote("https://someone:ghp_abcdefghijklmnopqrstuvwxyz0123@github.com/someone/SignalDeck.git"));
        assertEquals("https://github.com/someone/SignalDeck.git", BuildIdentity.sanitizeRemote("https://x-access-token:secret@github.com/someone/SignalDeck.git"));
        assertEquals("https://github.com/someone/SignalDeck", BuildIdentity.sanitizeRemote("https://oauth2@github.com/someone/SignalDeck"));
        assertEquals("https://github.com/someone/SignalDeck.git", BuildIdentity.sanitizeRemote("https://github.com/someone/SignalDeck.git?token=abc#frag"));
        assertEquals("git@github.com:someone/SignalDeck.git", BuildIdentity.sanitizeRemote("git@github.com:someone/SignalDeck.git"));
        assertEquals("example.org:repos/SignalDeck.git", BuildIdentity.sanitizeRemote("alice@example.org:repos/SignalDeck.git"));
        assertEquals("ssh://example.org/repos/sd.git", BuildIdentity.sanitizeRemote("ssh://alice:pw@example.org/repos/sd.git"));
        assertEquals("https://example.org/<token>/sd", BuildIdentity.sanitizeRemote("https://example.org/github_pat_11ABCDEFGHIJKLMNOPQRSTUV_xyz/sd"));
        assertEquals("unknown", BuildIdentity.sanitizeRemote(null));
        assertEquals("unknown", BuildIdentity.sanitizeRemote("  "));
        assertEquals("https://github.com/IIMacGyverII/SignalDeck.git", BuildIdentity.sanitizeRemote(" https://github.com/IIMacGyverII/SignalDeck.git\n"));
    }

    @Test public void officialOrForkFromTheCertificate() {
        assertEquals(BuildIdentity.OFFICIAL, BuildIdentity.classify(A, A));
        assertEquals(BuildIdentity.OFFICIAL, BuildIdentity.classify(A.toUpperCase().replaceAll("(..)(?!$)", "$1:"), A));   // keytool's AB:CD:… form
        assertEquals(BuildIdentity.FORK, BuildIdentity.classify(B, A));
        assertEquals(BuildIdentity.UNKNOWN, BuildIdentity.classify(null, A));             // no certificate read
        assertEquals(BuildIdentity.UNKNOWN, BuildIdentity.classify(B, ""));               // no official fingerprint known
        assertEquals(BuildIdentity.UNKNOWN, BuildIdentity.classify("not a hash", A));
        assertNotNull(BuildIdentity.normalizeSha256(BuildIdentity.OFFICIAL_CERT_SHA256));   // the shipped constant is a well-formed fingerprint
    }

    @Test public void verifiedNoteSaysWhereTheFlagCameFrom() throws Exception {
        assertTrue(BuildIdentity.verifiedNote(BuildIdentity.FORK, false, null).startsWith("verified=false"));
        assertEquals("verified=true in this build; not in the official profile list", BuildIdentity.verifiedNote(BuildIdentity.FORK, true, null));
        assertEquals("verified=true in this build; not in the official profile list", BuildIdentity.verifiedNote(BuildIdentity.FORK, true, false));
        assertEquals("verified=true, as in the official signed profile list", BuildIdentity.verifiedNote(BuildIdentity.FORK, true, true));
        assertEquals("verified=true in this official build", BuildIdentity.verifiedNote(BuildIdentity.OFFICIAL, true, null));
        JSONObject list = new JSONObject().put("profiles", new JSONArray().put(new JSONObject().put("id", "x").put("verified", true)).put(new JSONObject().put("id", "y")));
        assertEquals(Boolean.TRUE, BuildIdentity.listVerified(list, "x"));
        assertEquals(Boolean.FALSE, BuildIdentity.listVerified(list, "y"));
        assertNull(BuildIdentity.listVerified(list, "z"));
        assertNull(BuildIdentity.listVerified(null, "x"));
    }

    @Test public void forcedTransmitIsAnOverriddenTransmitAdvisory() {
        ProfileRegistry.Selection s = new ProfileRegistry.Selection();
        ProfileRegistry.Advisory a = new ProfileRegistry.Advisory(); a.id = "adv-1"; a.restrict = "transmit"; a.overridden = false; s.advisories.add(a);
        assertNull(BuildIdentity.txForcedBy(s));
        a.overridden = true;
        assertEquals("adv-1", BuildIdentity.txForcedBy(s));
        a.restrict = "profile";
        assertNull(BuildIdentity.txForcedBy(s));
    }

    @Test public void infoRoundTripsAndReads() throws Exception {
        BuildIdentity.Info i = new BuildIdentity.Info();
        i.version = "1.0.0-beta.20"; i.versionCode = "119"; i.commit = "abc1234567"; i.commitFull = "abc1234567" + "0".repeat(30); i.branch = "my-phone";
        i.remote = "https://github.com/someone/SignalDeck.git"; i.dirty = "true"; i.builtUtc = "2026-10-29T12:00:00Z"; i.certSha256 = B; i.kind = BuildIdentity.FORK;
        i.profileId = "maker-phone"; i.profileName = "Maker Phone"; i.profileRevision = 1; i.profileSource = "built-in"; i.profileVerified = true;
        i.verifiedNote = BuildIdentity.verifiedNote(i.kind, true, null);
        BuildIdentity.Info j = BuildIdentity.Info.fromJson(new JSONObject(i.toJson().toString()));
        assertEquals(i.toJson().toString(), j.toJson().toString());
        String t = j.text();
        assertTrue(t.contains("fork build"));
        assertTrue(t.contains("+ uncommitted changes"));
        assertTrue(t.contains("verified=true in this build; not in the official profile list"));
        assertFalse(t.contains("transmit forced on"));
    }

    @Test public void reportJsonCarriesTheBuild() throws Exception {
        BuildIdentity.Info i = new BuildIdentity.Info(); i.commit = "abc"; i.kind = BuildIdentity.OFFICIAL;
        JSONObject o = ReportJson.build("", null, i.toJson());
        assertEquals("abc", o.getJSONObject("build").getString("commit"));
        assertFalse(ReportJson.build("", null).has("build"));
    }
}
