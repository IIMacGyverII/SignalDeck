/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import static org.junit.Assert.*;

import com.macdmr.radio.DeviceProfile;
import com.macdmr.radio.RadioNotes;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.macdmr.radio.ui.HelpExportTest.json;
import static com.macdmr.radio.ui.HelpExportTest.md;

/**
 * The radio notes (assets/radios/radios.json, shown by Device → About this radio) exported to docs/radios/: one Markdown
 * page per radio, an index, and radios.json, which signaldeckradio.com reads at build time. The asset stays the only place
 * the notes are written; the generic "unknown" entry stays in the app only. Regenerate with:
 *   ./gradlew testReleaseUnitTest --tests '*RadioNotesExportTest' -PexportRadios=true
 */
public class RadioNotesExportTest {
    static final String SOURCE = "app/src/main/assets/" + RadioNotes.ASSET;
    static final String REGENERATE = "./gradlew testReleaseUnitTest --tests '*RadioNotesExportTest' -PexportRadios=true";

    static File root() {
        File dir = new File(System.getProperty("user.dir"));          // Gradle runs unit tests in app/
        if (!new File(dir, "settings.gradle").exists()) dir = dir.getParentFile();
        return dir;
    }

    static RadioNotes.Notes notes() throws Exception {
        return RadioNotes.parse(new String(Files.readAllBytes(new File(root(), SOURCE).toPath()), StandardCharsets.UTF_8));
    }

    /** Every built-in profile, found by reflection so a new one cannot be added without its notes. */
    static List<DeviceProfile> builtIns() throws IllegalAccessException {
        List<DeviceProfile> l = new ArrayList<>();
        for (Field f : DeviceProfile.class.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == DeviceProfile.class) { f.setAccessible(true); DeviceProfile p = (DeviceProfile) f.get(null); if (p != null) l.add(p); }
        return l;
    }

    static String jn(String s) { return s == null ? "null" : json(s); }
    static String num(double v) { return v == Math.rint(v) ? String.format(java.util.Locale.ROOT, "%.1f", v) : String.valueOf(v); }
    static String list(List<String> l) { StringBuilder b = new StringBuilder("["); for (int i = 0; i < l.size(); i++) b.append(i > 0 ? ", " : "").append(json(l.get(i))); return b.append(']').toString(); }

    /** The website's shape, in a fixed key order. */
    static String websiteJson(RadioNotes.Notes n) {
        StringBuilder b = new StringBuilder("{\n  \"generated\": ").append(json(n.generated)).append(",\n  \"radios\": [");
        int k = 0;
        for (RadioNotes.Entry e : n.radios) {
            if ("unknown".equals(e.id)) continue;
            b.append(k++ > 0 ? ",\n" : "\n").append("    {\n");
            b.append("      \"id\": ").append(json(e.id)).append(",\n");
            b.append("      \"aliases\": ").append(list(e.aliases)).append(",\n");
            b.append("      \"name\": ").append(json(e.name)).append(",\n");
            b.append("      \"maker\": ").append(jn(e.maker)).append(",\n");
            b.append("      \"variant\": ").append(jn(e.variant)).append(",\n");
            b.append("      \"status\": ").append(json(e.status)).append(",\n");
            b.append("      \"summary\": ").append(json(e.summary)).append(",\n");
            b.append("      \"radio\": ");
            if (e.radio == null) b.append("null"); else {
                b.append("{\"module\": ").append(jn(e.radio.module)).append(", \"modes\": ").append(list(e.radio.modes)).append(", \"bands\": [");
                for (int i = 0; i < e.radio.bands.size(); i++) { RadioNotes.Band x = e.radio.bands.get(i); b.append(i > 0 ? ", " : "").append("{\"name\": ").append(json(x.name)).append(", \"lowMHz\": ").append(num(x.lowMHz)).append(", \"highMHz\": ").append(num(x.highMHz)).append('}'); }
                b.append("], \"txPowerW\": ");
                if (e.radio.txPowerW == null) b.append("null"); else { b.append('['); for (int i = 0; i < e.radio.txPowerW.size(); i++) b.append(i > 0 ? ", " : "").append(num(e.radio.txPowerW.get(i))); b.append(']'); }
                b.append(", \"link\": ").append(jn(e.radio.link)).append('}');
            }
            b.append(",\n      \"features\": [");
            for (int i = 0; i < e.features.size(); i++) { RadioNotes.Feature f = e.features.get(i); b.append(i > 0 ? "," : "").append("\n        {\"id\": ").append(json(f.id)).append(", \"name\": ").append(json(f.name)).append(", \"state\": ").append(json(f.state)).append(", \"note\": ").append(jn(f.note)).append('}'); }
            b.append(e.features.isEmpty() ? "]" : "\n      ]").append(",\n      \"quirks\": [");
            for (int i = 0; i < e.quirks.size(); i++) { RadioNotes.Quirk q = e.quirks.get(i); b.append(i > 0 ? "," : "").append("\n        {\"title\": ").append(json(q.title)).append(", \"text\": ").append(json(q.text)).append('}'); }
            b.append(e.quirks.isEmpty() ? "]" : "\n      ]").append(",\n      \"measured\": [");
            for (int i = 0; i < e.measured.size(); i++) { RadioNotes.Measured m = e.measured.get(i); b.append(i > 0 ? "," : "").append("\n        {\"what\": ").append(json(m.what)).append(", \"value\": ").append(json(m.value)).append(", \"note\": ").append(jn(m.note)).append('}'); }
            b.append(e.measured.isEmpty() ? "]" : "\n      ]").append(",\n      \"firmware\": ");
            if (e.firmware == null) b.append("null"); else b.append("{\"stock\": ").append(jn(e.firmware.stock)).append(", \"modified\": ").append(jn(e.firmware.modified)).append(", \"note\": ").append(jn(e.firmware.note)).append('}');
            b.append(",\n      \"sections\": [");
            for (int i = 0; i < e.sections.size(); i++) { RadioNotes.Section s = e.sections.get(i); b.append(i > 0 ? "," : "").append("\n        {\"head\": ").append(json(s.head)).append(", \"text\": ").append(json(s.text)).append('}'); }
            b.append(e.sections.isEmpty() ? "]" : "\n      ]").append(",\n      \"photos\": []\n    }");
        }
        return b.append("\n  ]\n}\n").toString();
    }

    static final Map<String, String> STATE_WORDS = new LinkedHashMap<>();
    static { STATE_WORDS.put("yes", "Works"); STATE_WORDS.put("partial", "Partly"); STATE_WORDS.put("no", "No"); STATE_WORDS.put("untested", "Not tested"); }

    static String page(RadioNotes.Entry e) {
        StringBuilder p = new StringBuilder();
        p.append("<!-- Generated from ").append(SOURCE).append(" by RadioNotesExportTest. Do not edit by hand. -->\n\n")
         .append("# ").append(md(e.name)).append("\n\n")
         .append("*").append(e.variant != null ? md(e.variant) + " · " : "").append(md(e.statusText())).append(" · profile ").append(md(e.id)).append("*\n\n")
         .append(md(e.summary)).append('\n');
        if (e.radio != null) {
            p.append("\n## The radio\n\n| | |\n|---|---|\n");
            if (e.radio.module != null) p.append("| Module | ").append(md(e.radio.module)).append(" |\n");
            if (!e.radio.modes.isEmpty()) p.append("| Modes | ").append(md(String.join(", ", e.radio.modes))).append(" |\n");
            if (!e.radio.bands.isEmpty()) p.append("| Bands | ").append(md(RadioNotes.bandsText(e.radio))).append(" |\n");
            p.append("| Power | ").append(e.radio.txPowerW == null ? "Not measured yet" : md(e.radio.txPowerW.toString()) + " W").append(" |\n");
            if (e.radio.link != null) p.append("| Connection | ").append(md(e.radio.link)).append(" |\n");
            if (e.firmware != null) {
                if (e.firmware.stock != null) p.append("| Stock firmware | ").append(md(e.firmware.stock)).append(" |\n");
                if (e.firmware.modified != null) p.append("| Modified firmware | ").append(md(e.firmware.modified)).append(" |\n");
            }
            if (e.firmware != null && e.firmware.note != null) p.append('\n').append(md(e.firmware.note)).append('\n');
        }
        if (!e.features.isEmpty()) {
            p.append("\n## What works\n\n| Feature | State | Note |\n|---|---|---|\n");
            for (RadioNotes.Feature f : e.features) p.append("| ").append(md(f.name)).append(" | ").append(STATE_WORDS.get(f.state)).append(" | ").append(f.note == null ? "" : md(f.note)).append(" |\n");
        }
        if (!e.quirks.isEmpty()) {
            p.append("\n## Known quirks\n");
            for (RadioNotes.Quirk q : e.quirks) p.append("\n### ").append(md(q.title)).append("\n\n").append(md(q.text)).append('\n');
        }
        if (!e.measured.isEmpty()) {
            p.append("\n## Measured on our test unit\n\nMeasured on one test unit; your radio may differ.\n\n| What | Value | Note |\n|---|---|---|\n");
            for (RadioNotes.Measured m : e.measured) p.append("| ").append(md(m.what)).append(" | ").append(md(m.value)).append(" | ").append(m.note == null ? "" : md(m.note)).append(" |\n");
        }
        for (RadioNotes.Section s : e.sections) {
            p.append("\n## ").append(md(s.head)).append("\n\n");
            for (String para : s.text.split("\n")) if (!para.trim().isEmpty()) p.append(md(para.trim())).append("\n\n");
        }
        return (p.append("\n[All radios](README.md)\n")).toString().replaceAll("\n{3,}", "\n\n");
    }

    static Map<String, String> export(RadioNotes.Notes n) {
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder("# Radio notes\n\n<!-- Generated from ").append(SOURCE).append(" by RadioNotesExportTest. Do not edit by hand. -->\n\n")
                .append("Specs, what works, known quirks and what we measured on our test unit, for each phone SignalDeck has a profile for. ")
                .append("The app shows the same notes for the phone in use under Device → About this radio. Measurements come from one test unit; yours may differ.\n\n")
                .append("| Radio | Status | Summary |\n|---|---|---|\n");
        for (RadioNotes.Entry e : n.radios) {
            if ("unknown".equals(e.id)) continue;
            index.append("| [").append(md(e.name)).append("](").append(e.id).append(".md) | ").append(md(e.statusText())).append(" | ").append(md(e.summary)).append(" |\n");
            files.put(e.id + ".md", page(e));
        }
        index.append("\nNotes as of ").append(n.generated).append(". radios.json holds the same data for the website.\n");
        files.put("README.md", index.toString());
        files.put("radios.json", websiteJson(n));
        return files;
    }

    @Test public void everyBuiltInProfileHasNotes() throws Exception {
        RadioNotes.Notes n = notes();
        List<DeviceProfile> ps = builtIns();
        assertTrue(ps.size() >= 7);
        for (DeviceProfile p : ps) {
            RadioNotes.Entry e = RadioNotes.find(n, p.id);
            assertNotNull(p.id, e);
            assertTrue("no notes for built-in profile " + p.id + ": add an entry to " + SOURCE, e.id.equals(p.id) || e.aliases.contains(p.id));
            if (!p.verified && !"unknown".equals(p.id)) assertNotEquals(p.id + " is unverified: its status cannot be ok", "ok", e.status);
        }
        assertEquals("unknown", RadioNotes.find(n, "no-such-phone").id);
    }

    @Test public void entriesAreCompleteAndPlain() throws Exception {
        RadioNotes.Notes n = notes();
        assertTrue(n.generated.matches("\\d{4}-\\d{2}-\\d{2}"));
        Set<String> ids = new HashSet<>();
        for (RadioNotes.Entry e : n.radios) {
            assertTrue("id must be lowercase-hyphenated (a URL slug): " + e.id, e.id.matches("[a-z0-9]+(-[a-z0-9]+)*"));
            assertTrue("two entries for " + e.id, ids.add(e.id));
            for (String a : e.aliases) assertTrue("alias used twice: " + a, ids.add(a));
            assertFalse(e.id + ": no name", e.name.trim().isEmpty());
            assertTrue(e.id + ": status " + e.status, Arrays.asList(RadioNotes.STATUSES).contains(e.status));
            assertFalse(e.id + ": no summary", e.summary.trim().isEmpty());
            assertTrue(e.id + ": summary over 150 characters", e.summary.length() <= 150);
            assertFalse(e.id + ": no quirks or measurements", e.quirks.isEmpty() && e.measured.isEmpty());
            if (!"unknown".equals(e.id)) {
                assertNotNull(e.id + ": no radio block", e.radio);
                assertFalse(e.id + ": no bands", e.radio.bands.isEmpty());
                for (RadioNotes.Band b : e.radio.bands) assertTrue(e.id + " " + b.name, b.lowMHz > 0 && b.highMHz > b.lowMHz);
                assertFalse(e.id + ": no features", e.features.isEmpty());
            }
            Set<String> fids = new HashSet<>();
            for (RadioNotes.Feature f : e.features) {
                assertTrue(e.id + ": feature id " + f.id, f.id.matches("[a-z0-9_]+") && fids.add(f.id));
                assertTrue(e.id + "/" + f.id + ": state " + f.state, Arrays.asList(RadioNotes.STATES).contains(f.state));
                assertFalse(f.name.trim().isEmpty());
            }
            for (RadioNotes.Quirk q : e.quirks) { assertFalse(e.id + ": quirk without title", q.title.trim().isEmpty()); assertFalse(e.id + "/" + q.title + ": no text", q.text.trim().isEmpty()); }
            for (RadioNotes.Measured m : e.measured) { assertFalse(e.id + ": measurement without what", m.what.trim().isEmpty()); assertFalse(e.id + "/" + m.what + ": no value", m.value.trim().isEmpty()); }
            for (RadioNotes.Section s : e.sections) { assertFalse(s.head.trim().isEmpty()); assertFalse(s.text.trim().isEmpty()); }
        }
        // plain text only (the website shows it as text)
        String raw = new String(Files.readAllBytes(new File(root(), SOURCE).toPath()), StandardCharsets.UTF_8);
        assertFalse("markup in the notes", raw.matches("(?s).*(<[a-zA-Z/]|\\*\\*|\\]\\().*"));
    }

    /** docs/radios must match the asset. With -PexportRadios=true the files are written instead of checked. */
    @Test public void committedNotesAreCurrent() throws Exception {
        Map<String, String> files = export(notes());
        File dir = new File(root(), "docs/radios");
        if (Boolean.parseBoolean(System.getProperty("signaldeck.exportRadios", "false"))) {
            if (!dir.isDirectory()) assertTrue("cannot create " + dir, dir.mkdirs());
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.isFile() && !files.containsKey(f.getName())) assertTrue(f.delete());
            for (Map.Entry<String, String> e : files.entrySet())
                Files.write(new File(dir, e.getKey()).toPath(), e.getValue().getBytes(StandardCharsets.UTF_8));
            return;
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            File f = new File(dir, e.getKey());
            String have = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            if (!e.getValue().equals(have)) stale.add(e.getKey());
        }
        File[] present = dir.listFiles();
        if (present != null) for (File f : present) if (f.isFile() && !files.containsKey(f.getName())) stale.add(f.getName() + " (no longer a radio)");
        assertTrue("docs/radios is out of date with " + SOURCE + " (" + stale + "). Regenerate it with: " + REGENERATE, stale.isEmpty());
    }

    @Test public void websiteJsonParsesBack() throws Exception {
        RadioNotes.Notes n = notes();
        RadioNotes.Notes back = RadioNotes.parse(websiteJson(n));
        assertEquals(n.radios.size() - 1, back.radios.size());
        assertEquals(n.generated, back.generated);
        assertEquals(n.radios.get(0).features.size(), back.radios.get(0).features.size());
    }
}
