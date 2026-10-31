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
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema and plausibility checks for the bundled region table ({@code assets/regions/regions.json}, docs/regions/SOURCES.md):
 * required fields, unique ids, every frequency inside the entry's own amateur bands, sane licence-free bands, every
 * country falling back to an existing IARU default, and a source for every value that is set.
 */
public class RegionsDataTest {
    private static final String PATH = "src/main/assets/regions/regions.json";
    private static final List<String> REQUIRED = Arrays.asList("id", "name", "iaruRegion", "aprsHz", "callingHz", "sstvHz",
            "dapnetHz", "weatherRadio", "amateurBands", "licenseFree", "units", "stationIdMinutes", "dmrCountryTg", "sources");
    /** Fields a source or an "unverified" entry may name. */
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("aprsHz", "aprsAltHz", "callingHz", "sstvHz",
            "dapnetHz", "weatherRadio", "amateurBands", "licenseFree", "units", "stationIdMinutes", "dmrCountryTg"));
    /** What the module can tune (Armor 26 Ultra): 136-174 and 400-480 MHz. */
    private static final long VHF_LO = 136_000_000L, VHF_HI = 174_000_000L, UHF_LO = 400_000_000L, UHF_HI = 480_000_000L;

    private static JSONArray regions;
    private static final Map<String, JSONObject> byId = new HashMap<>();

    @BeforeClass public static void load() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(Paths.get(PATH)), StandardCharsets.UTF_8));
        assertEquals(1, root.getInt("version"));
        regions = root.getJSONArray("regions");
        assertTrue(regions.length() > 3);
        for (int i = 0; i < regions.length(); i++) {
            JSONObject r = regions.getJSONObject(i);
            String id = r.getString("id");
            assertNull("duplicate id " + id, byId.put(id, r));
        }
    }

    private static Long hz(JSONObject o, String k) throws Exception {
        return !o.has(k) || o.isNull(k) ? null : o.getLong(k);
    }

    private static boolean inBands(JSONObject r, long f) throws Exception {
        JSONArray b = r.getJSONArray("amateurBands");
        for (int i = 0; i < b.length(); i++) {
            JSONArray e = b.getJSONArray(i);
            if (f >= e.getLong(0) && f <= e.getLong(1)) return true;
        }
        return false;
    }

    @Test public void requiredFieldsAndIds() throws Exception {
        for (int i = 0; i < regions.length(); i++) {
            JSONObject r = regions.getJSONObject(i);
            String id = r.optString("id");
            for (String k : REQUIRED) assertTrue(id + ": missing " + k, r.has(k));
            assertFalse(id + ": name", r.getString("name").trim().isEmpty());
            int iaru = r.getInt("iaruRegion");
            assertTrue(id + ": iaruRegion", iaru >= 1 && iaru <= 3);
            if (id.startsWith("iaru")) {
                assertEquals(id, "iaru" + iaru);
                assertTrue(id + ": a default has no fallback", !r.has("fallback") || r.isNull("fallback"));
            } else {
                assertTrue(id + ": ISO 3166-1 alpha-2", id.matches("[A-Z]{2}"));
                assertEquals(id + ": fallback", "iaru" + iaru, r.getString("fallback"));
            }
            String u = r.getString("units");
            assertTrue(id + ": units " + u, u.equals("km") || u.equals("mi"));
            for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) {
                String key = it.next();
                assertTrue(id + ": unknown field " + key, REQUIRED.contains(key) || FIELDS.contains(key)
                        || key.equals("fallback") || key.equals("unverified") || key.equals("notes"));
            }
        }
        for (String d : new String[] { "iaru1", "iaru2", "iaru3" }) assertTrue("default " + d, byId.containsKey(d));
    }

    @Test public void everyFallbackExists() throws Exception {
        for (JSONObject r : byId.values()) {
            if (r.has("fallback") && !r.isNull("fallback"))
                assertTrue(r.getString("id") + " -> " + r.getString("fallback"), byId.containsKey(r.getString("fallback")));
        }
    }

    @Test public void amateurBandsAreReachableAndCoverBothBands() throws Exception {
        for (JSONObject r : byId.values()) {
            String id = r.getString("id");
            JSONArray b = r.getJSONArray("amateurBands");
            boolean two = false, seventy = false;
            long prevHi = 0;
            for (int i = 0; i < b.length(); i++) {
                JSONArray e = b.getJSONArray(i);
                assertEquals(id + ": band pair", 2, e.length());
                long lo = e.getLong(0), hi = e.getLong(1);
                assertTrue(id + ": band order " + lo, lo < hi && lo >= prevHi);
                prevHi = hi;
                boolean vhf = lo >= VHF_LO && hi <= VHF_HI, uhf = lo >= UHF_LO && hi <= UHF_HI;
                assertTrue(id + ": band outside the module's range " + lo, vhf || uhf);
                if (lo >= 144_000_000L && hi <= 148_000_000L) two = true;
                if (lo >= 420_000_000L && hi <= 450_000_000L) seventy = true;
            }
            assertTrue(id + ": a 2 m band", two);
            // Thailand: amateurs may only receive on 70 cm, so the 70 cm band is optional for a country (never for a default).
            if (id.startsWith("iaru")) assertTrue(id + ": a 70 cm band", seventy);
        }
    }

    @Test public void frequenciesInsideTheAmateurBands() throws Exception {
        for (JSONObject r : byId.values()) {
            String id = r.getString("id");
            Long aprs = hz(r, "aprsHz");
            // A country with no sourced APRS frequency leaves it null and uses its IARU default; a default always has one.
            if (id.startsWith("iaru")) assertNotNull(id + ": aprsHz", aprs);
            if (aprs == null) assertTrue(id + ": a null aprsHz explains itself in notes", r.has("notes"));
            for (String k : new String[] { "aprsHz", "aprsAltHz", "sstvHz", "dapnetHz" }) {
                Long f = hz(r, k);
                if (f != null) assertTrue(id + ": " + k + " " + f + " outside its amateur bands", inBands(r, f));
            }
            JSONObject c = r.getJSONObject("callingHz");
            assertTrue(id + ": callingHz keys", c.has("2m") && c.has("70cm") && c.length() == 2);
            Long c2 = hz(c, "2m"), c70 = hz(c, "70cm");
            if (c2 != null) assertTrue(id + ": 2 m calling " + c2, c2 >= 144_000_000L && c2 <= 148_000_000L && inBands(r, c2));
            if (c70 != null) assertTrue(id + ": 70 cm calling " + c70, c70 >= 420_000_000L && c70 <= 450_000_000L && inBands(r, c70));
            // FM channels sit on a 12.5 kHz or 5 kHz raster (6.25 kHz covers both PMR446-like and 12.5 kHz plans).
            for (Long f : new Long[] { aprs, hz(r, "aprsAltHz"), c2, c70, hz(r, "sstvHz"), hz(r, "dapnetHz") })
                if (f != null) assertTrue(id + ": raster " + f, f % 5_000 == 0 || f % 6_250 == 0);
        }
    }

    @Test public void licenceFreeServicesAreSane() throws Exception {
        for (JSONObject r : byId.values()) {
            String id = r.getString("id");
            JSONArray l = r.getJSONArray("licenseFree");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < l.length(); i++) {
                JSONObject s = l.getJSONObject(i);
                String n = s.getString("service");
                assertFalse(id + ": service name", n.trim().isEmpty());
                assertTrue(id + ": duplicate service " + n, names.add(n));
                long lo = s.getLong("lowHz"), hi = s.getLong("highHz");
                assertTrue(id + "/" + n + ": band " + lo + "-" + hi, lo > 1_000_000L && lo <= hi && hi < 1_000_000_000L && hi - lo <= 30_000_000L);
                if (!s.isNull("channels")) assertTrue(id + "/" + n + ": channels", s.getInt("channels") > 0 && s.getInt("channels") <= 200);
                if (!s.isNull("maxPowerW")) assertTrue(id + "/" + n + ": power", s.getDouble("maxPowerW") > 0 && s.getDouble("maxPowerW") <= 50);
                assertTrue(id + "/" + n + ": notes", s.has("notes"));
                boolean reachable = (lo >= VHF_LO && hi <= VHF_HI) || (lo >= UHF_LO && hi <= UHF_HI);
                if (!reachable) assertTrue(id + "/" + n + ": an out-of-range service says so in its notes",
                        s.getString("notes").toLowerCase().contains("outside"));
            }
        }
    }

    @Test public void otherValuesInRange() throws Exception {
        for (JSONObject r : byId.values()) {
            String id = r.getString("id");
            if (r.getBoolean("weatherRadio")) assertEquals(id + ": weather radio (NOAA-style) is a Region 2 service", 2, r.getInt("iaruRegion"));
            if (!r.isNull("stationIdMinutes")) {
                int m = r.getInt("stationIdMinutes");
                assertTrue(id + ": stationIdMinutes " + m, m >= 1 && m <= 60);
            }
            if (!r.isNull("dmrCountryTg")) {
                long tg = r.getLong("dmrCountryTg");
                assertTrue(id + ": dmrCountryTg " + tg, tg >= 200 && tg <= 99_999);
            }
        }
    }

    @Test public void everySetValueHasASource() throws Exception {
        for (JSONObject r : byId.values()) {
            String id = r.getString("id");
            JSONArray src = r.getJSONArray("sources");
            Set<String> sourced = new HashSet<>();
            for (int i = 0; i < src.length(); i++) {
                JSONObject s = src.getJSONObject(i);
                String f = s.getString("field");
                assertTrue(id + ": source names unknown field " + f, FIELDS.contains(f));
                assertTrue(id + "/" + f + ": url", s.getString("url").matches("https?://\\S+"));
                assertFalse(id + "/" + f + ": title", s.getString("title").trim().isEmpty());
                assertTrue(id + "/" + f + ": checked", s.getString("checked").matches("\\d{4}-\\d{2}-\\d{2}"));
                sourced.add(f);
            }
            for (String f : FIELDS) {
                if (!r.has(f) || r.isNull(f)) continue;
                Object v = r.get(f);
                if (f.equals("units") || f.equals("weatherRadio") && !r.getBoolean(f)) continue; // defaults need no citation
                if (v instanceof JSONArray && ((JSONArray) v).length() == 0) continue;
                if (f.equals("callingHz")) {
                    JSONObject c = (JSONObject) v;
                    if (c.isNull("2m") && c.isNull("70cm")) continue;
                }
                assertTrue(id + ": " + f + " is set but has no source", sourced.contains(f));
            }
            if (r.has("unverified")) {
                JSONArray u = r.getJSONArray("unverified");
                for (int i = 0; i < u.length(); i++) {
                    String uv = u.getString(i), field = uv.contains(".") ? uv.substring(0, uv.indexOf('.')) : uv;
                    assertTrue(id + ": unverified names unknown field " + uv, FIELDS.contains(field));
                    if (!uv.equals(field)) {                                       // a part: a calling band or a licence-free service the entry has
                        String part = uv.substring(field.length() + 1); boolean known = false;
                        if (field.equals("callingHz")) known = part.equals("2m") || part.equals("70cm");
                        else if (field.equals("licenseFree") && r.has("licenseFree")) { JSONArray l = r.getJSONArray("licenseFree"); for (int j = 0; j < l.length(); j++) known |= l.getJSONObject(j).getString("service").equals(part); }
                        assertTrue(id + ": unverified names an unknown part " + uv, known);
                    }
                }
            }
        }
    }
}
