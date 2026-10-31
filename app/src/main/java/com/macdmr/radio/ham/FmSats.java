/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import android.content.Context;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The FM amateur satellites besides the built-in ISS / SO-50 / PO-101, kept current from the web instead of hard-coded:
 * AMSAT's live status list says which FM satellites people work (its "[FM]" entries), SatNOGS' transmitter database
 * gives their frequencies and CTCSS tones, SatNOGS gives their orbits. AMSAT's reports of the last days ("Heard",
 * "Not Heard", "Telemetry Only") tell whether a satellite is actually on. Downloaded with the orbits (every few days),
 * cached in the app's files; nothing about the user is sent.
 */
public final class FmSats {
    private FmSats() { }

    static final String AMSAT_STATUS = "https://www.amsat.org/status/", AMSAT_API = "https://www.amsat.org/status/api/v1/sat_info.php?name=";   // i18n: URL
    static final String SATNOGS = "https://db.satnogs.org/api/";
    /** If AMSAT's page cannot be read: its FM list as of 2026-10-22 (the built-in three are left out). */
    static final String[] FALLBACK = { "AO-123", "AO-91", "CAS-3H", "IO-86", "RS83S", "RS95S", "TEVEL2-1", "TEVEL2-2", "TEVEL2-3", "TEVEL2-4", "TEVEL2-5", "TEVEL2-6", "TEVEL2-7", "TEVEL2-8", "TEVEL2-9" };
    /** Built into {@link Satellites}: their AMSAT names (status reports only). */
    static final String[] BUILT_IN = { "ISS", "SO-50", "PO-101" };

    public static final class Entry {
        public final String amsat, name, about; public final int norad; public final long downHz, upHz; public final double toneHz;
        Entry(String amsat, String name, int norad, long downHz, long upHz, double toneHz, String about) { this.amsat = amsat; this.name = name; this.norad = norad; this.downHz = downHz; this.upHz = upHz; this.toneHz = toneHz; this.about = about; }
    }

    static File file(Context c) { return new File(c.getFilesDir(), "fmsats.json"); }
    static File tleFile(Context c) { return new File(c.getFilesDir(), "fmsats.tle"); }
    public static long ageMs(Context c) { File f = file(c); return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE; }

    /** The cached list (empty before the first download). */
    public static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8)).getJSONArray("sats");
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.add(new Entry(o.getString("amsat"), o.getString("name"), o.getInt("norad"), o.getLong("down"), o.getLong("up"), o.optDouble("tone", 0), o.optString("about", ""))); }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Download again when the cache is older than {@code maxAgeMs}. Error or null. Off the main thread. */
    public static String updateIfOld(Context c, long maxAgeMs) { return ageMs(c) < maxAgeMs ? null : update(c); }

    public static String update(Context c) {
        try {
            String page = get(AMSAT_STATUS);
            List<String> names = page == null ? new ArrayList<>() : amsatFmNames(page);
            if (names.isEmpty()) for (String n : FALLBACK) names.add(n);
            names.removeIf(n -> { for (String b : BUILT_IN) if (b.equalsIgnoreCase(n)) return true; return false; });
            String sj = get(SATNOGS + "satellites/?format=json"), tj = get(SATNOGS + "transmitters/?format=json&alive=true&status=active");
            if (sj == null || tj == null) return Txt.s(R.string.modes_fmsat_unreachable);
            JSONArray sats = new JSONArray(sj), txs = new JSONArray(tj);
            List<Entry> out = pick(names, sats, txs);
            StringBuilder tle = new StringBuilder();
            JSONArray js = new JSONArray();
            for (Entry e : out) {
                String t = get(SATNOGS + "tle/?format=json&norad_cat_id=" + e.norad);
                if (t != null) { JSONArray ta = new JSONArray(t); if (ta.length() > 0) { JSONObject o = ta.getJSONObject(0); tle.append(e.name).append('\n').append(o.getString("tle1")).append('\n').append(o.getString("tle2")).append('\n'); } }
                js.put(new JSONObject().put("amsat", e.amsat).put("name", e.name).put("norad", e.norad).put("down", e.downHz).put("up", e.upHz).put("tone", e.toneHz).put("about", e.about));
            }
            Files.write(tleFile(c).toPath(), tle.toString().getBytes(StandardCharsets.US_ASCII));
            Files.write(file(c).toPath(), new JSONObject().put("updated", System.currentTimeMillis()).put("sats", js).toString().getBytes(StandardCharsets.UTF_8));
            return null;
        } catch (Throwable t) { return Txt.s(R.string.modes_fmsat_failed, String.valueOf(t.getMessage())); }
    }

    /** AMSAT's status page lists every satellite people report on as option values like "AO-123_[FM]". */
    static List<String> amsatFmNames(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("value=\"([A-Za-z0-9 -]+)_\\[FM\\]\"").matcher(html);
        while (m.find()) { String n = m.group(1).trim(); if (!out.contains(n)) out.add(n); }
        return out;
    }

    private static String norm(String s) { return s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", ""); }
    private static boolean inBand(long hz) { return com.macdmr.radio.DeviceProfile.inBands(hz); }

    /** For each AMSAT name: its SatNOGS satellite (by name or alias) and the FM voice transmitter this radio can work. */
    static List<Entry> pick(List<String> names, JSONArray sats, JSONArray txs) throws Exception {
        Map<String, JSONObject> byName = new HashMap<>();
        for (int i = 0; i < sats.length(); i++) {
            JSONObject s = sats.getJSONObject(i);
            if (!"in orbit".equals(s.optString("status"))) continue;   // i18n: SatNOGS API value
            byName.put(norm(s.optString("name")), s);
            for (String a : s.optString("names", "").split(",")) if (!a.trim().isEmpty()) byName.putIfAbsent(norm(a), s);
        }
        Map<Integer, List<JSONObject>> txBy = new LinkedHashMap<>();
        for (int i = 0; i < txs.length(); i++) { JSONObject t = txs.getJSONObject(i); txBy.computeIfAbsent(t.optInt("norad_cat_id"), k -> new ArrayList<>()).add(t); }
        List<Entry> out = new ArrayList<>();
        for (String n : names) {
            JSONObject s = byName.get(norm(n)); if (s == null) continue;
            int norad = s.optInt("norad_cat_id");
            JSONObject best = null; int bestScore = -1;
            for (JSONObject t : txBy.getOrDefault(norad, new ArrayList<>())) {
                if (!"FM".equalsIgnoreCase(t.optString("mode")) || !t.optBoolean("alive", true)) continue;
                String type = t.optString("type"), d = t.optString("description").toLowerCase(Locale.US);
                if (!type.equals("Transceiver") && !type.equals("Transponder")) continue;
                if (d.contains("linear") || d.contains("dsb") || d.contains("digipeater") || d.contains("cw in fm")) continue;
                long down = t.optLong("downlink_low"), up = t.optLong("uplink_low");
                if (!inBand(down) || !inBand(up)) continue;
                int score = (d.contains("voice") ? 4 : 0) + (d.contains("repeater") || d.contains("transponder") || d.contains("transceiver") ? 2 : 0) + (d.contains("beacon") ? -3 : 0);
                if (score > bestScore) { bestScore = score; best = t; }
            }
            if (best == null) continue;
            String alias = s.optString("names", "").split(",")[0].trim();
            String about = best.optString("description").trim() + (alias.isEmpty() || norm(alias).equals(norm(n)) ? "" : " (" + s.optString("name") + ")");
            out.add(new Entry(n, n, norad, best.optLong("downlink_low"), best.optLong("uplink_low"), tone(best.optString("description")), about));
        }
        return out;
    }

    /** "V/U FM Voice (PL 88.5Hz)", "CTCSS 67.0 Hz", "67.0 PL" → the tone; "no CTCSS any longer" → 0. */
    static double tone(String d) {
        if (d == null) return 0;
        Matcher m = Pattern.compile("(?i)(?:ctcss|pl|tone)\\s*:?\\s*(\\d{2,3}(?:\\.\\d)?)|(\\d{2,3}\\.\\d)\\s*(?:hz|pl)").matcher(d);
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
        return v >= 60 && v <= 260 ? v : 0;
    }

    // ---- AMSAT live status ----
    public static final class Status { public int heard, notHeard, telemetry, crew; public long lastHeard = -1, lastReport = -1; public long fetched; }
    private static final Map<String, Status> statusCache = new HashMap<>();

    /** The AMSAT name for a satellite SignalDeck lists (its key or name), or null. */
    public static String amsatName(Satellites.Sat s) {
        if (s.catalog == Satellites.ISS_CATALOG) return s.mode == Satellites.SSTV ? "ISS_[SSTV]" : "ISS_[FM]";
        if (s.key.startsWith("fm-")) return s.name + "_[FM]";
        if (s.key.equals("so50")) return "SO-50_[FM]";
        if (s.key.equals("po101")) return "PO-101_[FM]";
        return null;
    }

    /** Reports of the last {@code hours} (max 96), cached 30 minutes; null offline. Off the main thread. */
    public static Status status(String amsatName, int hours) {
        synchronized (statusCache) { Status s = statusCache.get(amsatName); if (s != null && System.currentTimeMillis() - s.fetched < 30 * 60_000L) return s; }
        try {
            String j = get(AMSAT_API + java.net.URLEncoder.encode(amsatName, "UTF-8") + "&hours=" + Math.min(96, hours));
            if (j == null) return null;
            Status s = parseStatus(new JSONArray(j)); s.fetched = System.currentTimeMillis();
            synchronized (statusCache) { statusCache.put(amsatName, s); }
            return s;
        } catch (Throwable t) { return null; }
    }
    static Status parseStatus(JSONArray a) {
        Status s = new Status();
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            String r = o.optString("report"); long t = -1; try { t = iso.parse(o.optString("reported_time")).getTime(); } catch (Throwable ignored) { }
            if (r.equalsIgnoreCase("Heard")) { s.heard++; if (t > s.lastHeard) s.lastHeard = t; }
            else if (r.equalsIgnoreCase("Not Heard")) s.notHeard++;
            else if (r.toLowerCase(Locale.US).startsWith("telemetry")) s.telemetry++;
            else if (r.toLowerCase(Locale.US).startsWith("crew")) { s.crew++; if (t > s.lastHeard) s.lastHeard = t; }
            if (t > s.lastReport) s.lastReport = t;
        }
        return s;
    }
    /** One line for the satellite list. */
    public static String describe(Status s, long now) {
        if (s == null) return Txt.s(R.string.modes_fmsat_status_offline);
        if (s.heard + s.notHeard + s.telemetry + s.crew == 0) return Txt.s(R.string.modes_fmsat_status_none);
        List<String> parts = new ArrayList<>();
        if (s.heard + s.crew > 0) parts.add(Txt.s(R.string.modes_fmsat_status_heard, s.heard + s.crew));
        if (s.telemetry > 0) parts.add(Txt.s(R.string.modes_fmsat_status_telemetry, s.telemetry));
        if (s.notHeard > 0) parts.add(Txt.s(R.string.modes_fmsat_status_not_heard, s.notHeard));
        String list = String.join(Txt.s(R.string.modes_list_separator), parts);
        if (s.lastHeard <= 0) return Txt.s(R.string.modes_fmsat_status, list);
        long h = (now - s.lastHeard) / 3_600_000L;
        String when = h < 1 ? Txt.s(R.string.modes_fmsat_last_hour) : h < 48 ? Txt.s(R.string.modes_fmsat_last_hours, (int) h) : Txt.p(R.plurals.modes_fmsat_last_days, (int) (h / 24), (int) (h / 24));
        return Txt.s(R.string.modes_fmsat_status_last, list, when);
    }

    private static String get(String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(); h.setConnectTimeout(10000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "SignalDeck");
            if (h.getResponseCode() != 200) return null;
            try (java.io.InputStream in = h.getInputStream()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
        } catch (Throwable t) { return null; }
    }
}
