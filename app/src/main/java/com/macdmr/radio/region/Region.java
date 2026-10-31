/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.region;

import android.content.Context;
import android.content.SharedPreferences;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The user's region (Device → Region, issue #95): the region-dependent defaults from {@code assets/regions/regions.json}
 * (terrestrial APRS frequency, FM calling and SSTV frequencies, licence-free services, weather radio, amateur band edges,
 * units, the station-ID interval), with the user's own value for any one of them on top. A region only sets defaults:
 * choosing one never keys, never starts a mode and never changes a channel. Nothing chosen yet = today's (US) behaviour.
 */
public final class Region {
    /** A licence-free personal radio service (FRS, PMR446, UHF CB ...) the region allows. */
    public static final class Service {
        public final String name, notes; public final int lowHz, highHz, channels; public final double maxPowerW;
        Service(String name, int lowHz, int highHz, int channels, double maxPowerW, String notes) { this.name = name; this.lowHz = lowHz; this.highHz = highHz; this.channels = channels; this.maxPowerW = maxPowerW; this.notes = notes; }
        public boolean covers(int hz) { return hz >= lowHz && hz <= highHz; }
    }

    /** ISO 3166-1 alpha-2, "iaru1".."iaru3" for a default, "" while none is chosen. */
    public String id = "", name = "";
    public int iaru = 2;
    /** Frequencies in Hz, 0 = none. */
    public int aprsHz, aprsAltHz, call2mHz, call70Hz, sstvHz, dapnetHz;
    public boolean weatherRadio, km;
    /** Minutes between station identifications (0: the app's 10). */
    public int stationIdMinutes, dmrCountryTg;
    public long[][] amateurBands = new long[0][];
    public List<Service> licenseFree = new ArrayList<>();
    /** regions.json fields not yet confirmed from a national source: a field ("dapnetHz"), or one part of it ("callingHz.70cm", "licenseFree.LPD433"). */
    public Set<String> unverified = new HashSet<>();
    /** One value is still to be confirmed: the whole field or this part of it. */
    public boolean unconfirmed(String field, String part) { return unverified.contains(field) || (part != null && unverified.contains(field + "." + part)); }
    /** True when the region has no entry of its own and uses its IARU region's default values. */
    public boolean generic;
    /** False while the user has not chosen (and the phone did not tell) a region: everything behaves as before regions. */
    public boolean chosen = true;
    /** The value keys the user changed (overrides on top of the region). */
    public Set<String> overridden = new HashSet<>();

    // ---- the values a user can change one by one (prefs keys) ----
    public static final String APRS = "aprsHz", CALL2 = "call2m", CALL70 = "call70", SSTV = "sstvHz", WEATHER = "weatherRadio", UNITS = "units", STATION_ID = "stationIdMinutes";
    public static final String[] VALUES = { APRS, CALL2, CALL70, SSTV, WEATHER, UNITS, STATION_ID };

    public Region copy() {
        Region r = new Region();
        r.id = id; r.name = name; r.iaru = iaru; r.aprsHz = aprsHz; r.aprsAltHz = aprsAltHz; r.call2mHz = call2mHz; r.call70Hz = call70Hz; r.sstvHz = sstvHz; r.dapnetHz = dapnetHz;
        r.weatherRadio = weatherRadio; r.km = km; r.stationIdMinutes = stationIdMinutes; r.dmrCountryTg = dmrCountryTg; r.amateurBands = amateurBands.clone();
        r.licenseFree = new ArrayList<>(licenseFree); r.unverified = new HashSet<>(unverified); r.generic = generic; r.chosen = chosen; r.overridden = new HashSet<>(overridden);
        return r;
    }

    /** Inside one of the region's amateur bands (CW ID, the station-ID timer, the parrot's ID). */
    public boolean amateur(long hz) { for (long[] b : amateurBands) if (hz >= b[0] && hz <= b[1]) return true; return false; }
    /** The station-ID interval in milliseconds (the region's, else 10 minutes). */
    public long idEveryMs() { return (stationIdMinutes > 0 ? stationIdMinutes : 10) * 60_000L; }
    public String aprsMhz() { return mhz(aprsHz); }
    public static String mhz(int hz) { return hz <= 0 ? "none" : String.format(Locale.US, "%.3f", hz / 1e6); }

    /** The same region with the user's own values on top ({@link #VALUES} keys; a frequency of 0 = none). Bad values are skipped. */
    public Region withOverrides(Map<String, ?> ov) {
        Region r = copy();
        for (String k : VALUES) {
            Object o = ov.get(k); if (o == null) continue;
            String v = String.valueOf(o).trim();
            try {
                switch (k) {
                    case APRS: { int hz = Integer.parseInt(v); if (hz <= 0) continue; r.aprsHz = hz; break; }
                    case CALL2: r.call2mHz = Math.max(0, Integer.parseInt(v)); break;
                    case CALL70: r.call70Hz = Math.max(0, Integer.parseInt(v)); break;
                    case SSTV: r.sstvHz = Math.max(0, Integer.parseInt(v)); break;
                    case WEATHER: r.weatherRadio = Boolean.parseBoolean(v); break;
                    case UNITS: if (!v.equals("km") && !v.equals("mi")) continue; r.km = v.equals("km"); break;
                    case STATION_ID: { int m = Integer.parseInt(v); if (m < 0 || m > 60) continue; r.stationIdMinutes = m; break; }
                    default: continue;
                }
                r.overridden.add(k);
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /** One value as shown on the Region sheet. */
    public String show(String key) {
        switch (key) {
            case APRS: return mhz(aprsHz) + " MHz";
            case CALL2: return call2mHz > 0 ? mhz(call2mHz) + " MHz" : Txt.s(R.string.channels_region_none_listed);
            case CALL70: return call70Hz > 0 ? mhz(call70Hz) + " MHz" : Txt.s(R.string.channels_region_none_listed);
            case SSTV: return sstvHz > 0 ? mhz(sstvHz) + " MHz" : Txt.s(R.string.channels_region_none_listed);
            case WEATHER: return Txt.s(weatherRadio ? R.string.channels_region_yes : R.string.channels_region_no);
            case UNITS: return Txt.s(km ? R.string.channels_region_km : R.string.channels_region_miles);
            case STATION_ID: return stationIdMinutes > 0 ? Txt.s(R.string.channels_region_id_every, stationIdMinutes) : Txt.s(R.string.channels_region_id_default);
            default: return "";
        }
    }
    public static String label(String key) {
        switch (key) {
            case APRS: return Txt.s(R.string.channels_region_label_aprs); case CALL2: return Txt.s(R.string.channels_region_label_call2); case CALL70: return Txt.s(R.string.channels_region_label_call70); case SSTV: return Txt.s(R.string.channels_region_label_sstv);
            case WEATHER: return Txt.s(R.string.channels_region_label_weather); case UNITS: return Txt.s(R.string.channels_region_label_units); case STATION_ID: return Txt.s(R.string.channels_region_label_station_id);
            default: return key;
        }
    }

    // ---- the phone's choice: SIM, then network, then locale ----

    /** The first usable country code of SIM, network and locale: {code, how, the network's country when it differs from the SIM's}. Null when none is usable. */
    public static String[] derive(String sim, String network, String locale, Set<String> known) {
        String s = code(sim, known), n = code(network, known), l = code(locale, known);
        if (s != null) return new String[] { s, "sim", n != null && !n.equals(s) ? n : null };
        if (n != null) return new String[] { n, "network", null };
        if (l != null) return new String[] { l, "locale", null };
        return null;
    }
    static String code(String c, Set<String> known) {
        if (c == null) return null;
        c = c.trim().toUpperCase(Locale.US);
        return c.matches("[A-Z]{2}") && known.contains(c) && !c.equals("ZZ") ? c : null;
    }
    public static String howText(String how) { return Txt.s("sim".equals(how) ? R.string.channels_region_how_sim : "network".equals(how) ? R.string.channels_region_how_network : "locale".equals(how) ? R.string.channels_region_how_locale : R.string.channels_region_how_user); }


    // ---- stored choice (prefs "region") ----
    private static final String PREFS = "region";
    private static volatile RegionTable table;
    private static volatile Region cached;

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static RegionTable table(Context c) {
        RegionTable t = table;
        if (t != null) return t;
        synchronized (Region.class) {
            if (table == null) {
                try (java.io.InputStream in = c.getAssets().open("regions/regions.json")) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    table = RegionTable.parse(new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) { throw new IllegalStateException("regions.json: " + e, e); }
            }
            return table;
        }
    }

    /** The region in use, with the user's own values. Never null: nothing chosen gives today's (US) values. */
    public static Region current(Context c) {
        Region r = cached;
        if (r != null) return r;
        SharedPreferences p = prefs(c);
        RegionTable t = table(c);
        String id = p.getString("id", null);
        Region base = id == null ? t.unchosen() : t.resolve(id);
        if (base == null) base = t.unchosen();
        Map<String, ?> all = p.getAll(); Map<String, Object> ov = new HashMap<>();
        for (String k : VALUES) if (all.containsKey("ov." + k)) ov.put(k, all.get("ov." + k));
        r = base.withOverrides(ov);
        cached = r;
        return r;
    }
    /** The last region read (for code without a Context: distances, the band checks); today's values before the first read. */
    public static Region cached() { Region r = cached; return r != null ? r : RegionTable.LEGACY; }
    /** Kilometres (else miles) for distances. */
    public static boolean km() { return cached().km; }

    /** First start (or the first start of a version with regions): take the phone's country once; never again by itself. */
    public static void ensureDefault(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("derived", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("derived", true).putBoolean("card", true);
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            String sim = null, net = null;
            try { sim = tm == null ? null : tm.getSimCountryIso(); } catch (Throwable ignored) { }
            try { net = tm == null ? null : tm.getNetworkCountryIso(); } catch (Throwable ignored) { }
            String[] d = derive(sim, net, Locale.getDefault().getCountry(), RegionTable.isoCountries());
            if (d != null) { e.putString("id", d[0]).putString("how", d[1]); if (d[2] != null) e.putString("network", d[2]); }
        } catch (Throwable ignored) { }
        e.apply();
        cached = null;
    }
    /** The intercom card ("Region: Belgium (from your SIM). Change") is still to be seen. */
    public static boolean cardPending(Context c) { return prefs(c).getBoolean("card", false); }
    public static void dismissCard(Context c) { prefs(c).edit().putBoolean("card", false).apply(); }
    public static String how(Context c) { return prefs(c).getString("how", ""); }
    /** The network's country when it differs from the SIM's (roaming), or null. */
    public static String roamingIn(Context c) { return prefs(c).getString("network", null); }
    public static boolean isChosen(Context c) { return prefs(c).getString("id", null) != null; }
    public static int overrideCount(Context c) { int n = 0; SharedPreferences p = prefs(c); for (String k : VALUES) if (p.contains("ov." + k)) n++; return n; }

    /** Choose a region (the user's act; the caller checks restricted mode). {@code clearOverrides}: drop the user's own values too. */
    public static void choose(Context c, String id, boolean clearOverrides) {
        SharedPreferences.Editor e = prefs(c).edit().putString("id", id).putString("how", "user").remove("network").putBoolean("card", false);
        if (clearOverrides) for (String k : VALUES) e.remove("ov." + k);
        e.apply(); cached = null; current(c);
    }
    /** One value of the user's own on top of the region; null resets it to the region's. */
    public static void setOverride(Context c, String key, String value) {
        if (!Arrays.asList(VALUES).contains(key)) return;
        SharedPreferences.Editor e = prefs(c).edit();
        if (value == null) e.remove("ov." + key); else e.putString("ov." + key, value);
        e.apply(); cached = null; current(c);
    }
    /** Tests and a reload after a change made elsewhere. */
    public static void invalidate() { cached = null; }
    /** Host tests: use this table and region without Android. */
    public static void setForTest(RegionTable t, Region r) { table = t; cached = r; }
}
