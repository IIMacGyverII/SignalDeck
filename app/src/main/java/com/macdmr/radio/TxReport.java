/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * tx-report.json, the record of a transmit test session (Device report → Transmit test session; the steps follow
 * docs/DEVICE_CERTIFICATION.md section 3.3). What the app saw is recorded by itself for every key (time, channel,
 * power, mode, how it was keyed and released, the module's status replies, battery and processor temperature before and
 * after); what only the tester can know is entered (did the receiving radio hear it, measured offset and deviation, the
 * equipment, a recording and its SHA-256). Pure data, host-tested; {@link TxTestSession} fills it on the phone.
 *
 * <p>Schema version 1 (documented in docs/DEVICE_CERTIFICATION.md, "Transmit test session"):
 * <pre>
 * { "schema": 1, "type": "signaldeck-tx-report", "created": UTC, "finished": UTC,
 *   "build": { BuildIdentity.Info },  "phone": { manufacturer, model, device, android }, "module": version,
 *   "safety": { "confirmedUtc": UTC, "items": [ ... ] }, "equipment": text, "receiver": text,
 *   "recordings": [ { "name", "url", "sha256", "bytes" } ],
 *   "steps": [ { "id", "title", "result": pass|fail|recorded|skipped|not run, "auto": text,
 *                "tester": { "heard": yes|no|not checked, "callerId": yes|no|not checked|null, "notes" },
 *                "measured": { "offsetHz", "deviationHz" } (numbers or null),
 *                "keys": [ { "startUtc", "plannedMs", "durationMs", "releasedBy": app|tester|watchdog|module|not keyed,
 *                            "refused", "channel": { number, name, mode, rxHz, txHz, power, bandwidth, colorCode, slot (1|2), talkgroup },
 *                            "pttPath", "statuses": [ { "tMs", "code", "name" } ], "sendStartMs", "sendStopMs",
 *                            "batteryC": { before, after }, "processorC": { before, after } } ] } ] }
 * </pre>
 * Times in a key are milliseconds from the moment SignalDeck pressed the key; temperatures are null when unreadable.
 * The callsign and DMR ID configured in the app never appear in the exported file: {@link #redact} replaces them.
 */
public final class TxReport {
    public static final int SCHEMA = 1;
    public static final String TYPE = "signaldeck-tx-report", FILE = "tx-report.json";
    public static final String PASS = "pass", FAIL = "fail", RECORDED = "recorded", SKIPPED = "skipped", NOT_RUN = "not run";

    // ---- the model ---------------------------------------------------------------------------------------------------

    public static final class Status { public long tMs; public int code; public String name; }
    public static final class Channel { public int number, rxHz, txHz, colorCode, slot, talkgroup; public String name, mode, power, bandwidth; }
    public static final class Key {
        public String startUtc, releasedBy = "app", refused, pttPath; public long plannedMs, durationMs, sendStartMs = -1, sendStopMs = -1;
        public Channel channel; public final List<Status> statuses = new ArrayList<>();
        public Double batteryBefore, batteryAfter, processorBefore, processorAfter;
        public boolean keyed() { return refused == null && sendStartMs >= 0; }
        public boolean endedCleanly() { return keyed() && sendStopMs >= 0 && sendStopMs >= durationMs - 50; }
    }
    public static final class Step {
        public String id, title, result = NOT_RUN, auto = "", heard, callerId, notes = ""; public Double offsetHz, deviationHz;
        public final List<Key> keys = new ArrayList<>();
        public Step(String id, String title) { this.id = id; this.title = title; }
    }
    public static final class Recording { public String name, url, sha256; public long bytes = -1; }

    public String created, finished, module = "", equipment = "", receiver = "", safetyUtc;
    public BuildIdentity.Info build = new BuildIdentity.Info();
    public final JSONObject phone = new JSONObject();
    public final List<String> safety = new ArrayList<>();
    public final List<Recording> recordings = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();

    /** The safety rules the tester confirms before the first key (docs/DEVICE_CERTIFICATION.md section 1). */
    public static final String[] SAFETY = {
            "I hold a licence for this frequency, or it is a licence-free service this radio may use",
            "Into a dummy load, or at low power on a quiet simplex frequency I am allowed to use",
            "Keys stay short (10 s or less); only the limit check holds one key longer (the session's own 28 s limit)",
            "I stay with the phone for the whole session and stop if anything looks wrong",
            "I watch the heat and stop if the phone gets hot" };

    /** The steps of section 3.3, in order; the DMR step only on phones with DMR. */
    public static List<Step> plan(boolean dmr) {
        List<Step> s = new ArrayList<>();
        s.add(new Step("fm", "FM voice key (5 s)"));
        s.add(new Step("release", "Release check (three 2 s keys)"));
        s.add(new Step("watchdog", "Transmit limit check (one held key, the session's 28 s limit)"));
        s.add(new Step("measure", "Frequency and deviation"));
        if (dmr) s.add(new Step("dmr", "DMR group call (5 s)"));
        s.add(new Step("heat", "Heat (five 3 s keys)"));
        return s;
    }

    public Step step(String id) { for (Step s : steps) if (s.id.equals(id)) return s; return null; }

    // ---- automatic verdicts (pure) -----------------------------------------------------------------------------------

    /** One key's automatic finding, for the step's "auto" line. */
    public static String keyFinding(Key k) {
        if (k.refused != null) return "not keyed: " + k.refused;
        if (k.sendStartMs < 0) return "keyed, but the module never reported SEND_START";
        StringBuilder s = new StringBuilder(String.format(Locale.US, "keyed %.1f s (SEND_START at %d ms), released by %s", k.durationMs / 1000.0, k.sendStartMs, k.releasedBy));
        s.append(k.sendStopMs >= 0 ? String.format(Locale.US, ", SEND_STOP at %d ms", k.sendStopMs) : ", no SEND_STOP seen");
        return s.toString();
    }

    /** Release check: every key keyed and ended with SEND_STOP after the release. */
    public static String releaseVerdict(List<Key> keys) {
        if (keys.isEmpty()) return NOT_RUN;
        for (Key k : keys) if (!k.endedCleanly()) return FAIL;
        return PASS;
    }

    /** The limit check: SignalDeck's watchdog (not the tester) released the key at the session's 28 s limit (27 to 30 s), and the module stopped. */
    public static String watchdogVerdict(Key k) {
        if (k == null || !k.keyed()) return NOT_RUN;
        if (!"watchdog".equals(k.releasedBy)) return "tester".equals(k.releasedBy) ? SKIPPED : FAIL;
        return k.durationMs >= 27_000 && k.durationMs <= 30_000 && k.sendStopMs >= 0 ? PASS : FAIL;
    }

    /** A key step with the tester's answer: pass when keyed cleanly and heard. */
    public static String heardVerdict(Key k, String heard) {
        if (k == null || !k.keyed()) return k != null && k.refused != null ? FAIL : NOT_RUN;
        if (!k.endedCleanly()) return FAIL;
        return "yes".equals(heard) ? PASS : "no".equals(heard) ? FAIL : RECORDED;
    }

    // ---- JSON --------------------------------------------------------------------------------------------------------

    private static Object num(Double d) { return d == null || d.isNaN() ? JSONObject.NULL : d; }
    private static Double dbl(JSONObject o, String k) { return o == null || !o.has(k) || o.isNull(k) ? null : o.optDouble(k); }
    private static String str(JSONObject o, String k) { return o == null || !o.has(k) || o.isNull(k) ? null : o.optString(k); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA).put("type", TYPE).put("created", created).put("finished", finished == null ? JSONObject.NULL : finished);
        o.put("build", build.toJson()).put("phone", phone).put("module", module);
        JSONObject sf = new JSONObject().put("confirmedUtc", safetyUtc == null ? JSONObject.NULL : safetyUtc).put("items", new JSONArray(safety));
        o.put("safety", sf).put("equipment", equipment).put("receiver", receiver);
        JSONArray rec = new JSONArray();
        for (Recording r : recordings) rec.put(new JSONObject().put("name", r.name == null ? JSONObject.NULL : r.name).put("url", r.url == null ? JSONObject.NULL : r.url)
                .put("sha256", r.sha256 == null ? JSONObject.NULL : r.sha256).put("bytes", r.bytes));
        o.put("recordings", rec);
        JSONArray st = new JSONArray();
        for (Step s : steps) {
            JSONObject j = new JSONObject().put("id", s.id).put("title", s.title).put("result", s.result).put("auto", s.auto);
            j.put("tester", new JSONObject().put("heard", s.heard == null ? JSONObject.NULL : s.heard).put("callerId", s.callerId == null ? JSONObject.NULL : s.callerId).put("notes", s.notes));
            j.put("measured", new JSONObject().put("offsetHz", num(s.offsetHz)).put("deviationHz", num(s.deviationHz)));
            JSONArray ks = new JSONArray();
            for (Key k : s.keys) {
                JSONObject kj = new JSONObject().put("startUtc", k.startUtc).put("plannedMs", k.plannedMs).put("durationMs", k.durationMs).put("releasedBy", k.releasedBy)
                        .put("refused", k.refused == null ? JSONObject.NULL : k.refused).put("pttPath", k.pttPath).put("sendStartMs", k.sendStartMs).put("sendStopMs", k.sendStopMs);
                if (k.channel != null) {
                    Channel c = k.channel;
                    kj.put("channel", new JSONObject().put("number", c.number).put("name", c.name).put("mode", c.mode).put("rxHz", c.rxHz).put("txHz", c.txHz)
                            .put("power", c.power).put("bandwidth", c.bandwidth).put("colorCode", c.colorCode).put("slot", c.slot).put("talkgroup", c.talkgroup));
                }
                JSONArray ss = new JSONArray(); for (Status x : k.statuses) ss.put(new JSONObject().put("tMs", x.tMs).put("code", x.code).put("name", x.name));
                kj.put("statuses", ss);
                kj.put("batteryC", new JSONObject().put("before", num(k.batteryBefore)).put("after", num(k.batteryAfter)));
                kj.put("processorC", new JSONObject().put("before", num(k.processorBefore)).put("after", num(k.processorAfter)));
                ks.put(kj);
            }
            j.put("keys", ks);
            st.put(j);
        }
        o.put("steps", st);
        return o;
    }

    public static TxReport fromJson(JSONObject o) throws JSONException {
        if (o.optInt("schema") != SCHEMA || !TYPE.equals(o.optString("type"))) throw new JSONException("not a " + TYPE + " v" + SCHEMA);
        TxReport r = new TxReport();
        r.created = str(o, "created"); r.finished = str(o, "finished"); r.module = o.optString("module"); r.equipment = o.optString("equipment"); r.receiver = o.optString("receiver");
        r.build = BuildIdentity.Info.fromJson(o.optJSONObject("build"));
        JSONObject ph = o.optJSONObject("phone"); if (ph != null) for (java.util.Iterator<String> it = ph.keys(); it.hasNext(); ) { String k = it.next(); r.phone.put(k, ph.get(k)); }
        JSONObject sf = o.optJSONObject("safety");
        if (sf != null) { r.safetyUtc = str(sf, "confirmedUtc"); JSONArray a = sf.optJSONArray("items"); if (a != null) for (int i = 0; i < a.length(); i++) r.safety.add(a.optString(i)); }
        JSONArray rec = o.optJSONArray("recordings");
        if (rec != null) for (int i = 0; i < rec.length(); i++) { JSONObject j = rec.getJSONObject(i); Recording x = new Recording(); x.name = str(j, "name"); x.url = str(j, "url"); x.sha256 = str(j, "sha256"); x.bytes = j.optLong("bytes", -1); r.recordings.add(x); }
        JSONArray st = o.optJSONArray("steps");
        if (st != null) for (int i = 0; i < st.length(); i++) {
            JSONObject j = st.getJSONObject(i); Step s = new Step(j.optString("id"), j.optString("title"));
            s.result = j.optString("result", NOT_RUN); s.auto = j.optString("auto");
            JSONObject t = j.optJSONObject("tester"); s.heard = str(t, "heard"); s.callerId = str(t, "callerId"); s.notes = t == null ? "" : t.optString("notes");
            JSONObject m = j.optJSONObject("measured"); s.offsetHz = dbl(m, "offsetHz"); s.deviationHz = dbl(m, "deviationHz");
            JSONArray ks = j.optJSONArray("keys");
            if (ks != null) for (int q = 0; q < ks.length(); q++) {
                JSONObject kj = ks.getJSONObject(q); Key k = new Key();
                k.startUtc = str(kj, "startUtc"); k.plannedMs = kj.optLong("plannedMs"); k.durationMs = kj.optLong("durationMs"); k.releasedBy = kj.optString("releasedBy");
                k.refused = str(kj, "refused"); k.pttPath = str(kj, "pttPath"); k.sendStartMs = kj.optLong("sendStartMs", -1); k.sendStopMs = kj.optLong("sendStopMs", -1);
                JSONObject c = kj.optJSONObject("channel");
                if (c != null) { Channel ch = new Channel(); ch.number = c.optInt("number"); ch.name = str(c, "name"); ch.mode = str(c, "mode"); ch.rxHz = c.optInt("rxHz"); ch.txHz = c.optInt("txHz");
                    ch.power = str(c, "power"); ch.bandwidth = str(c, "bandwidth"); ch.colorCode = c.optInt("colorCode"); ch.slot = c.optInt("slot"); ch.talkgroup = c.optInt("talkgroup"); k.channel = ch; }
                JSONArray ss = kj.optJSONArray("statuses");
                if (ss != null) for (int z = 0; z < ss.length(); z++) { JSONObject x = ss.getJSONObject(z); Status v = new Status(); v.tMs = x.optLong("tMs"); v.code = x.optInt("code"); v.name = x.optString("name"); k.statuses.add(v); }
                JSONObject b = kj.optJSONObject("batteryC"), p = kj.optJSONObject("processorC");
                k.batteryBefore = dbl(b, "before"); k.batteryAfter = dbl(b, "after"); k.processorBefore = dbl(p, "before"); k.processorAfter = dbl(p, "after");
                s.keys.add(k);
            }
            r.steps.add(s);
        }
        return r;
    }

    // ---- readable section (00_SUMMARY.txt, report.txt) -----------------------------------------------------------------

    private static String t(Double d) { return d == null || d.isNaN() ? "?" : String.format(Locale.US, "%.1f", d); }

    public String summaryText() {
        StringBuilder b = new StringBuilder();
        b.append("transmit test session (tx-report.json, schema ").append(SCHEMA).append("): started ").append(created).append(finished != null ? ", finished " + finished : ", not finished").append('\n');
        b.append(build.text());
        b.append("  safety rules confirmed: ").append(safetyUtc != null ? safetyUtc + " (" + safety.size() + " items)" : "no").append('\n');
        if (!equipment.isEmpty()) b.append("  equipment: ").append(equipment).append('\n');
        if (!receiver.isEmpty()) b.append("  receiving radio: ").append(receiver).append('\n');
        for (Recording r : recordings) b.append("  recording: ").append(r.name != null ? r.name : r.url).append(r.sha256 != null ? " sha256 " + r.sha256 : " (no hash)").append(r.bytes >= 0 ? " (" + r.bytes + " bytes)" : "").append('\n');
        for (Step s : steps) {
            b.append(String.format(Locale.US, "  %-34s %s", s.title, s.result.toUpperCase(Locale.ROOT)));
            if (s.heard != null) b.append(" · heard: ").append(s.heard);
            if (s.callerId != null) b.append(" · caller ID shown: ").append(s.callerId);
            if (s.offsetHz != null) b.append(" · offset ").append(String.format(Locale.US, "%+.0f Hz", s.offsetHz));
            if (s.deviationHz != null) b.append(" · deviation ").append(String.format(Locale.US, "%.0f Hz", s.deviationHz));
            b.append('\n');
            if (!s.auto.isEmpty()) b.append("      ").append(s.auto.replace("\n", "\n      ")).append('\n');
            if (!s.notes.isEmpty()) b.append("      notes: ").append(s.notes).append('\n');
            for (Key k : s.keys) {
                Channel c = k.channel;
                b.append("      key ").append(k.startUtc).append(": ").append(keyFinding(k));
                if (c != null) b.append(String.format(Locale.US, " · %s %.5f MHz %s power", c.mode, c.txHz / 1e6, c.power));
                if (k.batteryBefore != null || k.batteryAfter != null) b.append(" · battery ").append(t(k.batteryBefore)).append("→").append(t(k.batteryAfter)).append(" °C");
                if (k.processorBefore != null || k.processorAfter != null) b.append(" · processor ").append(t(k.processorBefore)).append("→").append(t(k.processorAfter)).append(" °C");
                b.append('\n');
            }
        }
        return b.toString();
    }

    // ---- privacy ---------------------------------------------------------------------------------------------------

    /**
     * Replace the callsign and DMR ID configured in the app with {@code <callsign>} and {@code <dmr id>}, and mask e-mail
     * addresses. The callsign matches with or without an SSID (-7) and in any case; only whole words, so a hash or a
     * frequency is never touched. A DMR ID shorter than four digits (1, the stock default) is too ambiguous to replace
     * and is not personal. Applied to the whole exported text (JSON and summary), after everything else.
     */
    public static String redact(String text, String callsign, int dmrId) {
        if (text == null) return null;
        String out = text;
        String base = callsign == null ? "" : callsign.trim().replaceAll("-\\d{1,2}$", "");
        if (base.length() >= 3) out = Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(base) + "(-\\d{1,2})?(?![A-Za-z0-9])").matcher(out).replaceAll("<callsign>");
        if (dmrId >= 1000) out = Pattern.compile("(?<![A-Za-z0-9])" + dmrId + "(?![A-Za-z0-9])").matcher(out).replaceAll("<dmr id>");
        return out.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***@***");
    }

    // ---- who may run it -----------------------------------------------------------------------------------------------

    /** Whether the session may start, why not, and whether the build is not an official one (flagged in the report). */
    public static final class Gate {
        public final boolean ok, fork; public final String reason;
        Gate(boolean ok, boolean fork, String reason) { this.ok = ok; this.fork = fork; this.reason = reason; }
    }

    /**
     * The transmit test session follows the same rules as every other transmit path: never while Restricted mode keeps
     * this mode or transmit off, never on a profile that is not verified (in an official build that means "wait for the
     * maintainer's test build"; a fork sets {@code verified = true} in its own build to test, and the report flags it as
     * a fork), never while an advisory has switched transmit off.
     */
    public static Gate gate(boolean verified, boolean txBlocked, boolean restrictedAllows, String buildKind) { return gate(verified, false, txBlocked, restrictedAllows, buildKind); }
    /** {@code testUnverified}: the profile lets this session key while it is not verified yet (DeviceProfile.txTestUnverified). */
    public static Gate gate(boolean verified, boolean testUnverified, boolean txBlocked, boolean restrictedAllows, String buildKind) {
        boolean fork = !BuildIdentity.OFFICIAL.equals(buildKind);
        if (!restrictedAllows) return new Gate(false, fork, "Restricted mode keeps transmitting off on this phone.");
        if (!verified && !testUnverified) return new Gate(false, fork, fork
                ? "This phone's profile has verified = false, so SignalDeck never transmits with it. To test transmit in your fork, set verified = true for this profile in your own build (keep it false in your pull request); the report then records that the flag came from your build."
                : "Transmit is off on this phone until its profile is verified. The maintainer sends a test build for the transmit tests (docs/DEVICE_CERTIFICATION.md, section 3.3).");
        if (txBlocked) return new Gate(false, fork, "An advisory from the profile list switched transmit off for this phone (Device → Profile tells why; the owner can override it there).");
        return new Gate(true, fork, null);
    }

    // ---- small helpers ------------------------------------------------------------------------------------------------

    public static String utc(long ms) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date(ms)); }

    /** "1234", "+1 234,5", "-600 Hz" → a number, or null when empty or unreadable. */
    public static Double parseHz(String s) {
        if (s == null) return null;
        String v = s.trim().replaceAll("(?i)\\s*hz$", "").replace(" ", "").replace(",", ".");
        if (v.isEmpty()) return null;
        try { return Double.parseDouble(v.startsWith("+") ? v.substring(1) : v); } catch (NumberFormatException e) { return null; }
    }

    /** A lower-case hex SHA-256, or null when the text is not one. */
    public static String sha256OrNull(String s) { return BuildIdentity.normalizeSha256(s); }

    /** Hex SHA-256 of a stream (a picked recording), with its length. */
    public static String[] sha256(java.io.InputStream in) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536]; long n = 0; int r;
        while ((r = in.read(buf)) > 0) { md.update(buf, 0, r); n += r; }
        StringBuilder h = new StringBuilder(); for (byte x : md.digest()) h.append(String.format(Locale.US, "%02x", x & 0xFF));
        return new String[] { h.toString(), String.valueOf(n) };
    }
}
