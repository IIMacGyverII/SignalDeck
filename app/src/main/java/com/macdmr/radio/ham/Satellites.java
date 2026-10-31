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
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The FM satellites this radio can work, their frequencies, and their orbital elements (AMSAT, CelesTrak fallback). */
public final class Satellites {
    /** What the radio does during a tracked pass besides following the frequency. */
    public static final int VOICE = 0, APRS = 1, SSTV = 2;
    public static final int ISS_CATALOG = 25544;
    public static final class Sat {
        public final String key, name, tleName, about; public final int catalog; public final long downHz, upHz; public final double toneHz; public final int mode;
        Sat(String key, String name, String tleName, int catalog, long downHz, long upHz, double toneHz, String about) { this(key, name, tleName, catalog, downHz, upHz, toneHz, about, VOICE); }
        Sat(String key, String name, String tleName, int catalog, long downHz, long upHz, double toneHz, String about, int mode) { this.key = key; this.name = name; this.tleName = tleName; this.catalog = catalog; this.downHz = downHz; this.upHz = upHz; this.toneHz = toneHz; this.about = about; this.mode = mode; }
        /** Receive only (no uplink). */
        public boolean listenOnly() { return upHz <= 0; }
    }

    // ISS frequencies as ARISS lists them (status of 2026-10-07: APRS on the Service Module's 437.825, the Columbus
    // radio a cross-band repeater 145.990 up with 67 Hz / 437.800 down, SSTV events on 437.550). ARISS moves these
    // often, so each one is a setting (Satellite passes → ISS frequencies); these are the defaults.
    public static final long ISS_APRS_HZ = 437_825_000L, ISS_SSTV_HZ = 437_550_000L, ISS_RPTR_DOWN_HZ = 437_800_000L, ISS_RPTR_UP_HZ = 145_990_000L, ISS_VOICE_DOWN_HZ = 145_800_000L, ISS_VOICE_UP_HZ = 144_490_000L;
    public static final double ISS_RPTR_TONE = 67.0;
    public static final String ARISS_STATUS_URL = "https://www.ariss.org/current-status-of-iss-stations.html";

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("sats", Context.MODE_PRIVATE); }
    public static long issHz(Context c, String key, long def) { return prefs(c).getLong(key, def); }
    public static void setIssHz(Context c, String key, long hz) { prefs(c).edit().putLong(key, hz).apply(); }
    public static double issRptrTone(Context c) { return Double.longBitsToDouble(prefs(c).getLong("issRptrTone", Double.doubleToLongBits(ISS_RPTR_TONE))); }
    public static void setIssRptrTone(Context c, double t) { prefs(c).edit().putLong("issRptrTone", Double.doubleToLongBits(t)).apply(); }
    public static void resetIss(Context c) { prefs(c).edit().clear().apply(); }

    /** The satellites this radio can work: the built-in ones (ISS frequencies from the settings), then the FM satellites from AMSAT's list ({@link FmSats}). */
    public static Sat[] all(Context c) {
        List<Sat> l = new ArrayList<>(java.util.Arrays.asList(builtIn(c)));
        for (FmSats.Entry e : FmSats.load(c)) l.add(new Sat("fm-" + e.amsat.toLowerCase(java.util.Locale.US), e.name, e.name, e.norad, e.downHz, e.upHz, e.toneHz, e.about));
        return l.toArray(new Sat[0]);
    }
    private static Sat[] builtIn(Context c) {
        return new Sat[] {
            new Sat("iss-aprs", "ISS APRS", "ISS", ISS_CATALOG, issHz(c, "issAprs", ISS_APRS_HZ), issHz(c, "issAprs", ISS_APRS_HZ), 0, Txt.s(R.string.modes_sat_about_iss_aprs), APRS),   // i18n: satellite name
            new Sat("iss-sstv", "ISS SSTV", "ISS", ISS_CATALOG, issHz(c, "issSstv", ISS_SSTV_HZ), 0, 0, Txt.s(R.string.modes_sat_about_iss_sstv), SSTV),   // i18n: satellite name
            new Sat("iss-rptr", "ISS repeater", "ISS", ISS_CATALOG, issHz(c, "issRptrDown", ISS_RPTR_DOWN_HZ), issHz(c, "issRptrUp", ISS_RPTR_UP_HZ), issRptrTone(c), Txt.s(R.string.modes_sat_about_iss_rptr)),   // i18n: satellite name
            new Sat("iss-voice", "ISS voice", "ISS", ISS_CATALOG, issHz(c, "issVoiceDown", ISS_VOICE_DOWN_HZ), issHz(c, "issVoiceUp", ISS_VOICE_UP_HZ), 0, Txt.s(R.string.modes_sat_about_iss_voice)),   // i18n: satellite name
            new Sat("so50", "SO-50", "SO-50", 27607, 436_795_000, 145_850_000, 67.0, Txt.s(R.string.modes_sat_about_so50)),   // i18n: satellite name
            new Sat("po101", "PO-101", "PO-101", 43678, 145_900_000, 437_500_000, 141.3, Txt.s(R.string.modes_sat_about_po101)),   // i18n: satellite name
        };
    }

    private final Context ctx;
    public Satellites(Context c) { ctx = c; }
    private File file() { return new File(ctx.getFilesDir(), "sats.tle"); }

    public long elementsAgeMs() { File f = file(); return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE; }

    /** Download fresh elements (call off the main thread). Error or null. */
    public String update() {
        String body = get("https://www.amsat.org/tle/current/nasabare.txt");
        if (body == null || !body.contains("25544")) {
            StringBuilder sb = new StringBuilder();
            for (int cat : new int[] { 25544, 27607, 43678 }) { String b = get("https://celestrak.org/NORAD/elements/gp.php?CATNR=" + cat + "&FORMAT=TLE"); if (b != null) sb.append(b.trim()).append('\n'); }   // i18n: URL
            body = sb.length() > 0 ? sb.toString() : null;
        }
        if (body == null) return Txt.s(R.string.modes_sat_download_failed);
        try { Files.write(file().toPath(), body.getBytes(StandardCharsets.US_ASCII)); } catch (Throwable t) { return Txt.s(R.string.modes_sat_save_failed, String.valueOf(t.getMessage())); }
        FmSats.updateIfOld(ctx, 3 * 86_400_000L);                               // the FM satellite list: every few days is plenty
        return null;
    }
    /** Orbits and the FM satellite list now (the Update orbits button). */
    public String updateAll() { String e = update(); String f = FmSats.update(ctx); return e != null ? e : f; }

    /** Elements by catalog number from the saved file. */
    public Map<Integer, Sgp4> elements() {
        Map<Integer, Sgp4> out = new LinkedHashMap<>();
        for (File src : new File[] { file(), FmSats.tleFile(ctx) }) try {
            String[] l = new String(Files.readAllBytes(src.toPath()), StandardCharsets.US_ASCII).split("\\r?\\n");
            for (int i = 0; i + 1 < l.length; i++) {
                if (l[i].startsWith("1 ") && l[i + 1].startsWith("2 ")) {
                    String name = i > 0 && !l[i - 1].startsWith("2 ") ? l[i - 1].trim() : "";
                    try { Sgp4 s = new Sgp4(name, l[i], l[i + 1]); out.put(s.catalog, s); } catch (Throwable ignored) { }
                    i++;
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String get(String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(); h.setConnectTimeout(10000); h.setReadTimeout(15000); h.setRequestProperty("User-Agent", "SignalDeck");
            if (h.getResponseCode() != 200) return null;
            try (InputStream in = h.getInputStream()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("US-ASCII"); }
        } catch (Throwable t) { Log.w("Satellites", url + ": " + t); return null; }
    }

    public static Sat byKey(Context c, String k) { for (Sat s : all(c)) if (s.key.equals(k)) return s; return null; }
    public static List<Sat> list(Context c) { List<Sat> l = new ArrayList<>(); for (Sat s : all(c)) l.add(s); return l; }
}
