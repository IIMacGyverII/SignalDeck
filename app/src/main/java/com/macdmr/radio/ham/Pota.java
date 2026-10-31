/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Parks on the Air and Summits on the Air: the live activator spots of both programmes (the ones this radio can work
 * first: FM, or an unknown mode, on 136–174 / 400–480 MHz), the parks around you (POTA's map, asked for a box rounded
 * to half a degree), checking a park or summit reference, and your own activation (the reference your contacts are
 * logged under, the duplicate check and the ADIF file POTA and SOTA take).
 */
public final class Pota {
    private Pota() { }
    public static final String POTA = "POTA", SOTA = "SOTA";

    public static final class Spot {
        public String program, call, mode, ref, name, where, comment; public long freqHz, time; public double lat, lon;
        public boolean workable() { return com.macdmr.radio.DeviceProfile.inBands(freqHz) && (mode == null || mode.isEmpty() || mode.equalsIgnoreCase("FM")); }
    }
    public static final class Park { public String ref, name; public double lat, lon, km; }

    /** POTA spots (frequency in kHz) and SOTA spots (in MHz), workable first, newest first within each. */
    public static List<Spot> spots(List<String> problems) {
        List<Spot> out = new ArrayList<>();
        try { out.addAll(parsePota(new JSONArray(get("https://api.pota.app/spot/activator")))); } catch (Throwable t) { problems.add(Txt.s(R.string.modes_pota_unreachable)); }
        try { out.addAll(parseSota(new JSONArray(get("https://api2.sota.org.uk/api/spots/40/all")))); } catch (Throwable t) { problems.add(Txt.s(R.string.modes_sota_unreachable)); }
        out.sort((a, b) -> a.workable() != b.workable() ? (a.workable() ? -1 : 1) : Long.compare(b.time, a.time));
        return out;
    }
    static List<Spot> parsePota(JSONArray a) {
        List<Spot> out = new ArrayList<>();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null || o.optBoolean("invalid", false)) continue;
            Spot s = new Spot(); s.program = POTA; s.call = o.optString("activator"); s.mode = o.optString("mode"); s.ref = o.optString("reference");
            s.name = o.optString("name", o.optString("parkName")); s.where = o.optString("locationDesc"); s.comment = o.optString("comments");
            try { s.freqHz = Math.round(Double.parseDouble(o.optString("frequency", "0")) * 1000); } catch (NumberFormatException e) { continue; }
            try { s.time = iso.parse(o.optString("spotTime")).getTime(); } catch (Throwable ignored) { }
            s.lat = o.optDouble("latitude", 0); s.lon = o.optDouble("longitude", 0);
            out.add(s);
        }
        return out;
    }
    static List<Spot> parseSota(JSONArray a) {
        List<Spot> out = new ArrayList<>();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            Spot s = new Spot(); s.program = SOTA; s.call = o.optString("activatorCallsign"); s.mode = o.optString("mode");
            s.ref = o.optString("associationCode") + "/" + o.optString("summitCode"); s.name = o.optString("summitDetails"); s.where = ""; s.comment = o.optString("comments");
            if (o.optString("summitCode").contains("/")) s.ref = o.optString("summitCode");
            try { s.freqHz = Math.round(Double.parseDouble(o.optString("frequency", "0")) * 1_000_000); } catch (NumberFormatException e) { continue; }
            try { String ts = o.optString("timeStamp"); s.time = iso.parse(ts.length() > 19 ? ts.substring(0, 19) : ts).getTime(); } catch (Throwable ignored) { }
            out.add(s);
        }
        return out;
    }

    /** Parks within about 50 km: POTA's map is asked for a box rounded to half a degree; distances are worked out here. */
    public static List<Park> parksNear(double lat, double lon) throws Exception {
        double la0 = Math.floor(lat * 2) / 2 - 0.5, lo0 = Math.floor(lon * 2) / 2 - 0.5;
        JSONObject fc = new JSONObject(get(String.format(Locale.US, "https://api.pota.app/park/grids/%.1f/%.1f/%.1f/%.1f/0", la0, lo0, la0 + 1.5, lo0 + 1.5)));   // i18n: URL
        List<Park> out = new ArrayList<>(); JSONArray f = fc.optJSONArray("features"); if (f == null) return out;
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i); if (o == null) continue;
            JSONObject pr = o.optJSONObject("properties"); JSONArray c = o.optJSONObject("geometry") == null ? null : o.optJSONObject("geometry").optJSONArray("coordinates");
            if (pr == null || c == null) continue;
            Park p = new Park(); p.ref = pr.optString("reference"); p.name = pr.optString("name"); p.lon = c.optDouble(0); p.lat = c.optDouble(1); p.km = km(lat, lon, p.lat, p.lon);
            if (p.km <= 60) out.add(p);
        }
        out.sort((a, b) -> Double.compare(a.km, b.km));
        return out;
    }
    static double km(double a, double b, double c, double d) { double r = Math.PI / 180, x = Math.sin((c - a) * r / 2), y = Math.sin((d - b) * r / 2), h = x * x + Math.cos(a * r) * Math.cos(c * r) * y * y; return 2 * 6371 * Math.asin(Math.sqrt(h)); }

    /** POTA "US-1234" / "GB-0001" or SOTA "W0C/FR-001" / "G/SE-001": which programme, or null. Pure. */
    public static String programOf(String ref) {
        if (ref == null) return null; String r = ref.trim().toUpperCase(Locale.US);
        if (r.matches("[A-Z0-9]{1,4}-\\d{4,5}")) return POTA;
        if (r.matches("[A-Z0-9]{1,4}/[A-Z]{2}-\\d{3}")) return SOTA;
        return null;
    }
    /** The park or summit's name (null when the programme does not know the reference). Off the main thread. */
    public static String lookup(String ref) {
        String p = programOf(ref); if (p == null) return null; String r = ref.trim().toUpperCase(Locale.US);
        try {
            if (POTA.equals(p)) { JSONObject o = new JSONObject(get("https://api.pota.app/park/" + r)); return o.optString("name", null); }
            JSONObject o = new JSONObject(get("https://api2.sota.org.uk/api/summits/" + r)); String n = o.optString("name", ""); return n.isEmpty() ? null : n + (o.has("altM") ? " (" + o.optInt("altM") + " m)" : "");
        } catch (Throwable t) { return null; }
    }

    // ---- your activation (prefs "pota") ----
    public static final class Activation { public String program, ref, name; public long since; }
    public static Activation active(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("pota", Context.MODE_PRIVATE);
        String ref = p.getString("ref", null); if (ref == null) return null;
        Activation a = new Activation(); a.ref = ref; a.program = p.getString("program", programOf(ref)); a.name = p.getString("name", ""); a.since = p.getLong("since", 0); return a;
    }
    public static void start(Context c, String ref, String name) { c.getSharedPreferences("pota", Context.MODE_PRIVATE).edit().putString("ref", ref.trim().toUpperCase(Locale.US)).putString("program", programOf(ref)).putString("name", name == null ? "" : name).putLong("since", System.currentTimeMillis()).apply(); }
    public static void end(Context c) { c.getSharedPreferences("pota", Context.MODE_PRIVATE).edit().clear().apply(); }
    /** POTA counts an activation with 10 contacts in a UTC day, SOTA with 4. */
    public static int needed(String program) { return SOTA.equals(program) ? 4 : 10; }
    /** The UTC day of a time, as POTA groups activations (yyyyMMdd). Pure. */
    public static String utcDay(long ts) { SimpleDateFormat d = new SimpleDateFormat("yyyyMMdd", Locale.US); d.setTimeZone(TimeZone.getTimeZone("UTC")); return d.format(new java.util.Date(ts)); }

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(); h.setConnectTimeout(10000); h.setReadTimeout(20000); h.setRequestProperty("User-Agent", "SignalDeck");
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        try (java.io.InputStream in = h.getInputStream()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
    }
}
