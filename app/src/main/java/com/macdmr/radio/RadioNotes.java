/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Txt;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Notes about each built-in profile's radio: specs, what works, known quirks and what was measured on our test unit.
 * One file, assets/radios/radios.json, read by the Device page's About this radio sheet; RadioNotesExportTest writes
 * docs/radios/ (Markdown pages and the radios.json signaldeckradio.com reads) from the same data. Plain text only.
 */
public final class RadioNotes {
    public static final String ASSET = "radios/radios.json";
    /** The states a feature may have, and the status of a radio (ok tested, beta in testing, exp untested, prog bring-up, disc discontinued). */
    public static final String[] STATES = { "yes", "no", "partial", "untested" }, STATUSES = { "ok", "beta", "exp", "prog", "disc" };

    public static final class Band { public String name; public double lowMHz, highMHz; }
    public static final class Radio { public String module, link; public List<String> modes = new ArrayList<>(); public List<Band> bands = new ArrayList<>(); public List<Double> txPowerW; }
    public static final class Feature { public String id, name, state, note; }
    public static final class Quirk { public String title, text; }
    public static final class Measured { public String what, value, note; }
    public static final class Firmware { public String stock, modified, note; }
    public static final class Section { public String head, text; }
    public static final class Entry {
        public String id, name, maker, variant, status, summary;
        public List<String> aliases = new ArrayList<>();
        public Radio radio; public Firmware firmware;
        public List<Feature> features = new ArrayList<>(); public List<Quirk> quirks = new ArrayList<>();
        public List<Measured> measured = new ArrayList<>(); public List<Section> sections = new ArrayList<>();
        /** In plain words for the sheet. */
        public String statusText() {
            switch (status) {
                case "ok": return Txt.s(R.string.device_notes_status_tested); case "beta": return Txt.s(R.string.device_notes_status_testing); case "prog": return Txt.s(R.string.device_notes_status_bringup);
                case "disc": return Txt.s(R.string.device_notes_status_discontinued); default: return Txt.s(R.string.device_notes_status_untested);
            }
        }
    }
    public static final class Notes { public String generated; public List<Entry> radios = new ArrayList<>(); }

    private static String str(JSONObject o, String k) { return o.isNull(k) ? null : o.optString(k, null); }
    private static List<String> strings(JSONArray a) throws JSONException { List<String> l = new ArrayList<>(); if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); return l; }

    public static Notes parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        Notes n = new Notes(); n.generated = root.getString("generated");
        JSONArray rs = root.getJSONArray("radios");
        for (int i = 0; i < rs.length(); i++) {
            JSONObject o = rs.getJSONObject(i); Entry e = new Entry();
            e.id = o.getString("id"); e.name = o.getString("name"); e.maker = str(o, "maker"); e.variant = str(o, "variant");
            e.status = o.getString("status"); e.summary = o.getString("summary"); e.aliases = strings(o.optJSONArray("aliases"));
            JSONObject r = o.optJSONObject("radio");
            if (r != null) {
                e.radio = new Radio(); e.radio.module = str(r, "module"); e.radio.link = str(r, "link"); e.radio.modes = strings(r.optJSONArray("modes"));
                JSONArray b = r.optJSONArray("bands");
                if (b != null) for (int j = 0; j < b.length(); j++) { JSONObject bo = b.getJSONObject(j); Band band = new Band(); band.name = bo.getString("name"); band.lowMHz = bo.getDouble("lowMHz"); band.highMHz = bo.getDouble("highMHz"); e.radio.bands.add(band); }
                JSONArray p = r.optJSONArray("txPowerW");
                if (p != null) { e.radio.txPowerW = new ArrayList<>(); for (int j = 0; j < p.length(); j++) e.radio.txPowerW.add(p.getDouble(j)); }
            }
            JSONArray a = o.optJSONArray("features");
            if (a != null) for (int j = 0; j < a.length(); j++) { JSONObject x = a.getJSONObject(j); Feature f = new Feature(); f.id = x.getString("id"); f.name = x.getString("name"); f.state = x.getString("state"); f.note = str(x, "note"); e.features.add(f); }
            a = o.optJSONArray("quirks");
            if (a != null) for (int j = 0; j < a.length(); j++) { JSONObject x = a.getJSONObject(j); Quirk q = new Quirk(); q.title = x.getString("title"); q.text = x.getString("text"); e.quirks.add(q); }
            a = o.optJSONArray("measured");
            if (a != null) for (int j = 0; j < a.length(); j++) { JSONObject x = a.getJSONObject(j); Measured m = new Measured(); m.what = x.getString("what"); m.value = x.getString("value"); m.note = str(x, "note"); e.measured.add(m); }
            JSONObject fw = o.optJSONObject("firmware");
            if (fw != null) { e.firmware = new Firmware(); e.firmware.stock = str(fw, "stock"); e.firmware.modified = str(fw, "modified"); e.firmware.note = str(fw, "note"); }
            a = o.optJSONArray("sections");
            if (a != null) for (int j = 0; j < a.length(); j++) { JSONObject x = a.getJSONObject(j); Section s = new Section(); s.head = x.getString("head"); s.text = x.getString("text"); e.sections.add(s); }
            n.radios.add(e);
        }
        return n;
    }

    /** The entry for a profile id (or one of its aliases), else the generic "unknown" one, else null. */
    public static Entry find(Notes n, String id) {
        Entry fallback = null;
        for (Entry e : n.radios) { if (e.id.equals(id) || e.aliases.contains(id)) return e; if ("unknown".equals(e.id)) fallback = e; }
        return fallback;
    }

    private static Notes cached;
    public static synchronized Notes load(Context c) {
        if (cached != null) return cached;
        try (InputStream in = c.getAssets().open(ASSET)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192];
            for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
            cached = parse(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { cached = new Notes(); }
        return cached;
    }

    /** The notes for the phone in use. */
    public static Entry forActive(Context c) { return find(load(c), DeviceProfile.detect().id); }

    /** Band list as text: "VHF 136–174 MHz, UHF 400–480 MHz". */
    public static String bandsText(Radio r) {
        StringBuilder b = new StringBuilder();
        for (Band x : r.bands) b.append(b.length() > 0 ? ", " : "").append(x.name).append(' ').append(mhz(x.lowMHz)).append('–').append(mhz(x.highMHz)).append(" MHz");   // i18n: unit (band names and ranges are data)
        return b.toString();
    }
    static String mhz(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    private RadioNotes() { }
}
