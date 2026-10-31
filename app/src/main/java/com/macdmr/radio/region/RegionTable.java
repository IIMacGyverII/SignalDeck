/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.region;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bundled region table ({@code assets/regions/regions.json}, sources in docs/regions/SOURCES.md): the three IARU
 * defaults and the countries with an entry of their own. A country's missing (null) value is its IARU default's; a
 * country without an entry is its IARU region's default under its own name.
 */
public final class RegionTable {
    private final Map<String, Region> byId = new LinkedHashMap<>();

    /** Before any region is chosen: the values the app used before regions (US band plan, miles, weather radio). */
    static final Region LEGACY = legacy();
    private static Region legacy() {
        Region r = new Region(); r.id = ""; r.name = "Not chosen"; r.iaru = 2; r.chosen = false;
        r.aprsHz = 144_390_000; r.call2mHz = 146_520_000; r.call70Hz = 446_000_000; r.weatherRadio = true; r.km = false; r.stationIdMinutes = 10;
        r.amateurBands = new long[][] { { 144_000_000L, 148_000_000L }, { 222_000_000L, 225_000_000L }, { 420_000_000L, 450_000_000L } };
        return r;
    }
    public Region unchosen() { return LEGACY.copy(); }

    // ITU/IARU region of a country without an entry: Region 2 (the Americas), Region 3 (Asia-Pacific), the rest Region 1.
    private static final Set<String> IARU2 = new HashSet<>(Arrays.asList(("AG AI AR AW BB BL BM BO BQ BR BS BZ CA CL CO CR CU CW DM DO EC FK GD GF GP GT GY HN HT JM KN KY LC MF MQ MS MX NI PA PE PM PR PY SR SV SX TC TT UM US UY VC VE VG VI").split(" ")));
    private static final Set<String> IARU3 = new HashSet<>(Arrays.asList(("AF AS AU BD BN BT CC CK CN CX FJ FM GU HK ID IN IO IR JP KH KI KP KR LA LK MH MM MN MO MP MV MY NC NF NP NR NU NZ PF PG PH PK PN PW SB SG TH TK TL TO TV TW VN VU WF WS").split(" ")));
    public static int iaruOf(String code) { return IARU2.contains(code) ? 2 : IARU3.contains(code) ? 3 : 1; }

    private static Set<String> iso;
    public static synchronized Set<String> isoCountries() { if (iso == null) iso = new HashSet<>(Arrays.asList(Locale.getISOCountries())); return iso; }
    /** A country's English name ("BE" → "Belgium"). */
    public static String countryName(String code) { String n = new Locale("", code).getDisplayCountry(Locale.ENGLISH); return n == null || n.isEmpty() || n.equals(code) ? code : n; }

    public static RegionTable parse(String json) throws Exception {
        RegionTable t = new RegionTable();
        JSONArray a = new JSONObject(json).getJSONArray("regions");
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); if (o.getString("id").startsWith("iaru")) t.byId.put(o.getString("id"), entry(o, null)); }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i); String id = o.getString("id");
            if (id.startsWith("iaru")) continue;
            Region fb = t.byId.get(o.optString("fallback", "iaru" + o.getInt("iaruRegion")));
            t.byId.put(id, entry(o, fb));
        }
        return t;
    }
    private static int hz(JSONObject o, String k, int fb) throws Exception { return !o.has(k) || o.isNull(k) ? fb : (int) o.getLong(k); }
    private static Region entry(JSONObject o, Region fb) throws Exception {
        Region r = fb == null ? new Region() : fb.copy();
        r.id = o.getString("id"); r.name = o.getString("name"); r.iaru = o.getInt("iaruRegion"); r.generic = false; r.chosen = true;
        r.aprsHz = hz(o, "aprsHz", r.aprsHz); r.aprsAltHz = hz(o, "aprsAltHz", fb == null ? 0 : r.aprsAltHz);
        if (fb != null && o.has("aprsHz") && !o.isNull("aprsHz") && !o.has("aprsAltHz")) r.aprsAltHz = 0;    // the default's second frequency belongs to its own first
        JSONObject c = o.optJSONObject("callingHz");
        if (c != null) { r.call2mHz = hz(c, "2m", r.call2mHz); r.call70Hz = hz(c, "70cm", r.call70Hz); }
        r.sstvHz = hz(o, "sstvHz", r.sstvHz); r.dapnetHz = hz(o, "dapnetHz", r.dapnetHz);
        if (o.has("weatherRadio") && !o.isNull("weatherRadio")) r.weatherRadio = o.getBoolean("weatherRadio");
        if (o.has("units") && !o.isNull("units")) r.km = !"mi".equals(o.getString("units"));
        r.stationIdMinutes = hz(o, "stationIdMinutes", r.stationIdMinutes); r.dmrCountryTg = hz(o, "dmrCountryTg", r.dmrCountryTg);
        JSONArray b = o.optJSONArray("amateurBands");
        if (b != null && b.length() > 0) { r.amateurBands = new long[b.length()][]; for (int i = 0; i < b.length(); i++) r.amateurBands[i] = new long[] { b.getJSONArray(i).getLong(0), b.getJSONArray(i).getLong(1) }; }
        JSONArray l = o.optJSONArray("licenseFree");
        r.licenseFree = new ArrayList<>();
        if (l != null) for (int i = 0; i < l.length(); i++) {
            JSONObject s = l.getJSONObject(i);
            r.licenseFree.add(new Region.Service(s.getString("service"), (int) s.getLong("lowHz"), (int) s.getLong("highHz"), s.isNull("channels") ? 0 : s.getInt("channels"), s.isNull("maxPowerW") ? 0 : s.getDouble("maxPowerW"), s.optString("notes", "")));
        }
        // a value inherited from the default keeps the default's "unverified" mark; a value set here is judged by this entry's own list
        Set<String> inherited = new HashSet<>();
        if (fb != null) for (String k : fb.unverified) { String f = k.contains(".") ? k.substring(0, k.indexOf('.')) : k; if (!o.has(f) || o.isNull(f)) inherited.add(k); }
        r.unverified = inherited; JSONArray u = o.optJSONArray("unverified"); if (u != null) for (int i = 0; i < u.length(); i++) r.unverified.add(u.getString(i));
        r.overridden = new HashSet<>();
        return r;
    }

    /** The entry for an id: a country of the table, an IARU default, or any other ISO country (its IARU default under its own name). Null: unknown. */
    public Region resolve(String id) {
        if (id == null) return null;
        Region r = byId.get(id);
        if (r != null) return r.copy();
        if (!isoCountries().contains(id)) return null;
        Region d = byId.get("iaru" + iaruOf(id));
        if (d == null) return null;
        r = d.copy(); r.id = id; r.name = countryName(id); r.generic = true;
        return r;
    }
    public boolean hasEntry(String id) { return byId.containsKey(id); }

    /** The picker's list: every ISO country by name ({id, name}), then the three IARU defaults as "Custom" starting points. */
    public List<String[]> pickList() {
        List<String[]> out = new ArrayList<>();
        for (String code : isoCountries()) { Region e = byId.get(code); out.add(new String[] { code, e != null ? e.name : countryName(code) }); }
        Collections.sort(out, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        for (int i = 1; i <= 3; i++) { Region d = byId.get("iaru" + i); if (d != null) out.add(new String[] { d.id, "Custom: " + d.name }); }
        return out;
    }
}
