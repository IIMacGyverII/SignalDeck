/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * VHF/UHF conditions: is tonight worth getting on the air beyond the usual range?
 * <ul>
 * <li><b>Tropospheric ducting</b>, the main source of long VHF/UHF contacts: the radio refractivity of the air from
 * Open-Meteo's forecast (temperature and humidity at the ground and at the 1000–850 hPa levels), and how fast it falls
 * with height. Standard air loses about 40 N-units per km; −79 bends signals back toward the ground (super-refraction,
 * longer range), −157 traps them (a duct: hundreds of km). The model's layers are a few hundred metres apart, so thin
 * surface ducts are underestimated: treat it as a guide. The position is rounded to 0.5° (about 50 km) before it is sent.</li>
 * <li><b>Aurora</b>: NOAA SWPC's planetary Kp now and forecast, and NOAA's OVATION aurora probability at your spot (the
 * whole map is downloaded and read on the phone, so no position is sent).</li>
 * <li><b>Meteor showers</b>: the major annual showers (a calendar, nothing downloaded).</li>
 * </ul>
 */
public final class Conditions {
    private Conditions() { }

    // ---- tropospheric refraction (pure) ----
    /** Radio refractivity N of air: temperature °C, relative humidity %, pressure hPa (ITU-R P.453). */
    public static double refractivity(double tC, double rh, double pHpa) {
        double tk = tC + 273.15, es = 6.112 * Math.exp(17.62 * tC / (243.12 + tC)), e = Math.max(0, Math.min(100, rh)) / 100.0 * es;
        return 77.6 / tk * (pHpa + 4810.0 * e / tk);
    }
    public static final int NORMAL = 0, ENHANCED = 1, SUPER = 2, DUCT = 3;
    private static final int[] CLASS_NAMES = { R.string.modes_cond_class_normal, R.string.modes_cond_class_enhanced, R.string.modes_cond_class_super, R.string.modes_cond_class_duct };
    /** A class's name in the app's language. */
    public static String className(int cls) { return Txt.s(CLASS_NAMES[cls]); }
    /** All the class names, in class order. */
    public static String[] classNames() { String[] o = new String[CLASS_NAMES.length]; for (int i = 0; i < o.length; i++) o[i] = className(i); return o; }
    /** dN/dh in N-units per km → class. */
    public static int classify(double gradientPerKm) { return gradientPerKm <= -157 ? DUCT : gradientPerKm <= -79 ? SUPER : gradientPerKm <= -55 ? ENHANCED : NORMAL; }

    public static final class Slot { public long time; public double gradient; public int cls; }
    /**
     * Each forecast hour: the steepest refractivity drop between the ground and the pressure levels above it (only
     * levels above the ground count). {@code hourly} is Open-Meteo's "hourly" object, {@code elevation} metres.
     */
    public static List<Slot> tropo(JSONObject hourly, double elevation) throws Exception {
        List<Slot> out = new ArrayList<>();
        JSONArray time = hourly.getJSONArray("time");
        int[] lv = { 1000, 975, 950, 925, 900, 850 };
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < time.length(); i++) {
            double ts = hourly.getJSONArray("temperature_2m").optDouble(i, Double.NaN), rs = hourly.getJSONArray("relative_humidity_2m").optDouble(i, Double.NaN), ps = hourly.getJSONArray("surface_pressure").optDouble(i, Double.NaN);
            if (Double.isNaN(ts) || Double.isNaN(rs) || Double.isNaN(ps)) continue;
            double prevN = refractivity(ts, rs, ps), prevH = elevation + 2, best = 0;
            for (int p : lv) {
                if (p >= ps) continue;                                        // that level is below the ground here
                JSONArray tA = hourly.optJSONArray("temperature_" + p + "hPa"), rA = hourly.optJSONArray("relative_humidity_" + p + "hPa"), hA = hourly.optJSONArray("geopotential_height_" + p + "hPa");
                if (tA == null || rA == null || hA == null) continue;
                double t = tA.optDouble(i, Double.NaN), r = rA.optDouble(i, Double.NaN), h = hA.optDouble(i, Double.NaN);
                if (Double.isNaN(t) || Double.isNaN(r) || Double.isNaN(h) || h <= prevH + 30) continue;
                double n = refractivity(t, r, p), g = (n - prevN) / ((h - prevH) / 1000.0);
                if (g < best) best = g;
                prevN = n; prevH = h;
                if (h - elevation > 1500) break;                              // ducts that matter for us are in the lowest 1.5 km
            }
            Slot s = new Slot(); s.time = iso.parse(time.getString(i)).getTime(); s.gradient = best; s.cls = classify(best); out.add(s);
        }
        return out;
    }

    // ---- aurora (pure) ----
    /** NOAA's Kp in words. */
    public static String kpWords(double kp) { return Txt.s(kp >= 7 ? R.string.modes_cond_kp_severe : kp >= 6 ? R.string.modes_cond_kp_strong : kp >= 5 ? R.string.modes_cond_kp_storm : kp >= 4 ? R.string.modes_cond_kp_active : kp >= 3 ? R.string.modes_cond_kp_unsettled : R.string.modes_cond_kp_quiet); }
    /** OVATION's grid ("coordinates": [lon 0..359, lat, percent]) at a place, nearest point. */
    public static int ovationAt(JSONArray coords, double lat, double lon) {
        int la = (int) Math.round(lat), lo = (int) Math.round(((lon % 360) + 360) % 360) % 360; int best = -1;
        for (int i = 0; i < coords.length(); i++) { JSONArray c = coords.optJSONArray(i); if (c != null && c.optInt(0) == lo && c.optInt(1) == la) { best = c.optInt(2); break; } }
        return best;
    }

    // ---- meteor showers (pure) ----
    public static final class Shower { public final String name; public final int month, day, zhr, halfWidthDays; final int noteId; public long peak;
        Shower(String n, int m, int d, int z, int w, int noteId) { name = n; month = m; day = d; zhr = z; halfWidthDays = w; this.noteId = noteId; }
        /** The shower's note in the app's language, "" when it has none. */
        public String note() { return noteId == 0 ? "" : Txt.s(noteId); } }
    static final Shower[] SHOWERS = {
        new Shower("Quadrantids", 1, 3, 110, 2, R.string.modes_cond_shower_quadrantids),   // i18n: proper name
        new Shower("Lyrids", 4, 22, 18, 2, 0),   // i18n: proper name
        new Shower("Eta Aquariids", 5, 6, 50, 4, R.string.modes_cond_shower_eta_aquariids),   // i18n: proper name
        new Shower("Arietids", 6, 7, 60, 5, R.string.modes_cond_shower_arietids),   // i18n: proper name
        new Shower("Southern Delta Aquariids", 7, 30, 25, 5, 0),   // i18n: proper name
        new Shower("Perseids", 8, 12, 100, 4, R.string.modes_cond_shower_perseids),   // i18n: proper name
        new Shower("Orionids", 10, 21, 20, 4, 0),   // i18n: proper name
        new Shower("Leonids", 11, 17, 15, 2, 0),   // i18n: proper name
        new Shower("Geminids", 12, 14, 150, 3, R.string.modes_cond_shower_geminids),   // i18n: proper name
    };
    /** The showers whose peak is within {@code days} from now (or now), soonest first. */
    public static List<Shower> showers(long now, int days, TimeZone tz) {
        List<Shower> out = new ArrayList<>();
        Calendar c = Calendar.getInstance(tz, Locale.US); c.setTimeInMillis(now); int year = c.get(Calendar.YEAR);
        for (int y = year; y <= year + 1; y++) for (Shower s : SHOWERS) {
            Calendar p = Calendar.getInstance(tz, Locale.US); p.clear(); p.set(y, s.month - 1, s.day, 12, 0);
            long peak = p.getTimeInMillis();
            if (peak + s.halfWidthDays * 86_400_000L >= now && peak - now <= days * 86_400_000L) { Shower x = new Shower(s.name, s.month, s.day, s.zhr, s.halfWidthDays, s.noteId); x.peak = peak; out.add(x); }
        }
        out.sort((a, b) -> Long.compare(a.peak, b.peak));
        return out;
    }

    // ---- the report (network; off the main thread) ----
    public static final class Report {
        public double kpNow = -1, kpMax = -1; public long kpMaxAt; public int aurora = -1; public List<Slot> tropo = new ArrayList<>(); public List<Shower> showers = new ArrayList<>();
        public long fetched; public final List<String> problems = new ArrayList<>();
        /** The best tropo window in the next 48 h: {start, end, class}, or null when nothing beats Normal. */
        public long[] bestWindow() {
            int best = NORMAL; for (Slot s : tropo) best = Math.max(best, s.cls);
            if (best == NORMAL) return null;
            long st = -1, en = -1;
            for (Slot s : tropo) { if (s.cls == best) { if (st < 0) st = s.time; en = s.time + 3_600_000L; } else if (st >= 0) break; }
            return new long[] { st, en, best };
        }
    }
    private static volatile Report cache; private static volatile double cacheLat = 999, cacheLon = 999;

    public static Report get(double lat, double lon, boolean hasFix) {
        Report c = cache;
        if (c != null && System.currentTimeMillis() - c.fetched < 20 * 60_000L && Math.abs(cacheLat - lat) < 0.5 && Math.abs(cacheLon - lon) < 0.5) return c;
        Report r = new Report(); r.fetched = System.currentTimeMillis();
        r.showers = showers(r.fetched, 45, TimeZone.getDefault());
        try {
            JSONArray kp = new JSONArray(get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"));
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 0; i < kp.length(); i++) {
                JSONObject o = kp.optJSONObject(i); if (o == null) continue;
                double v = o.optDouble("kp", -1); long t = iso.parse(o.getString("time_tag")).getTime();
                if (!"predicted".equals(o.optString("observed"))) r.kpNow = v;
                else if (v > r.kpMax) { r.kpMax = v; r.kpMaxAt = t; }
            }
        } catch (Throwable t) { r.problems.add(Txt.s(R.string.modes_cond_problem_kp)); }
        if (hasFix) {
            try { JSONObject ov = new JSONObject(get("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json")); r.aurora = ovationAt(ov.getJSONArray("coordinates"), lat, lon); }
            catch (Throwable t) { r.problems.add(Txt.s(R.string.modes_cond_problem_aurora)); }
            try {
                double la = Math.round(lat * 2) / 2.0, lo = Math.round(lon * 2) / 2.0;          // about 50 km: the weather service does not need more
                StringBuilder v = new StringBuilder("temperature_2m,relative_humidity_2m,surface_pressure");
                for (int p : new int[] { 1000, 975, 950, 925, 900, 850 }) v.append(",temperature_").append(p).append("hPa,relative_humidity_").append(p).append("hPa,geopotential_height_").append(p).append("hPa");   // i18n: API parameter names
                JSONObject om = new JSONObject(get(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f&hourly=%s&forecast_days=3&timezone=GMT", la, lo, v)));   // i18n: URL
                List<Slot> all = tropo(om.getJSONObject("hourly"), om.optDouble("elevation", 0));
                long now = System.currentTimeMillis(); for (Slot s : all) if (s.time >= now - 3_600_000L && s.time <= now + 48 * 3_600_000L) r.tropo.add(s);
            } catch (Throwable t) { r.problems.add(Txt.s(R.string.modes_cond_problem_tropo)); }
        } else r.problems.add(Txt.s(R.string.modes_cond_problem_nofix));
        cache = r; cacheLat = lat; cacheLon = lon;
        return r;
    }

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(); h.setConnectTimeout(10000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "SignalDeck");
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        try (java.io.InputStream in = h.getInputStream()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
    }

    /** The whole report as JSON (phone sheet and SignalDeck Web share it). */
    public static JSONObject json(Report r) throws Exception {
        JSONObject o = new JSONObject().put("kpNow", r.kpNow).put("kpWords", r.kpNow < 0 ? "" : kpWords(r.kpNow)).put("kpMax", r.kpMax).put("kpMaxAt", r.kpMaxAt).put("aurora", r.aurora).put("fetched", r.fetched);
        JSONArray t = new JSONArray(); for (Slot s : r.tropo) t.put(new JSONObject().put("t", s.time).put("g", Math.round(s.gradient)).put("c", s.cls));
        JSONArray m = new JSONArray(); for (Shower s : r.showers) m.put(new JSONObject().put("name", s.name).put("peak", s.peak).put("zhr", s.zhr).put("note", s.note()));
        long[] w = r.bestWindow(); JSONArray p = new JSONArray(); for (String x : r.problems) p.put(x);
        return o.put("tropo", t).put("showers", m).put("best", w == null ? JSONObject.NULL : new JSONObject().put("from", w[0]).put("to", w[1]).put("c", (int) w[2]).put("name", className((int) w[2]))).put("classNames", new JSONArray(java.util.Arrays.asList(classNames()))).put("problems", p);
    }
}
