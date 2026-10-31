package com.macdmr.radio.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.macdmr.radio.R;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every language agrees with English: no key English lacks, the same placeholders in each string and plural form,
 * nothing marked untranslatable translated. English itself: a context note on every string, numbered placeholders
 * when there is more than one. The guide: every {@name} label exists, translated topics and sections exist in English.
 */
public class I18nResourcesTest {
    private static final Pattern PH = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXcbeEgGoh%n]");

    static Map<String, StringsXml.Entry> byName(List<StringsXml.Entry> l) { Map<String, StringsXml.Entry> m = new HashMap<>(); for (StringsXml.Entry e : l) m.put(e.name, e); return m; }

    /** The placeholders of a string as a sorted set ("%1$s", "%2$d"); %% and %n are not placeholders. */
    static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>(); Matcher m = PH.matcher(s); int auto = 0;
        while (m.find()) {
            String p = m.group(); if (p.equals("%%") || p.equals("%n")) continue;
            char conv = p.charAt(p.length() - 1);
            out.add((m.group(1) != null ? m.group(1) : (++auto) + "$") + conv);
        }
        return out;
    }

    static List<File> languageDirs() {
        List<File> out = new ArrayList<>(); File res = StringsXml.resDir(); File[] d = res.listFiles();
        if (d != null) for (File f : d) if (f.isDirectory() && f.getName().matches("values-[a-z]{2,3}(-r[A-Z]{2}|-b\\+[A-Za-z0-9+]+)?")) out.add(f);
        return out;
    }

    @Test public void englishStringsHaveContextNotes() {
        for (StringsXml.Entry e : StringsXml.read(StringsXml.valuesDir())) {
            if (e.name.equals("widget_desc")) continue;   // the widget's own description, older than the extraction
            assertNotNull(e.name + ": every string needs a context note (an XML comment above it) for translators", e.comment);
            String text = e.plural != null ? e.plural.get("other") : e.text;
            assertNotNull(e.name + ": a plural needs an 'other' form", text);
            if (placeholders(text).size() > 1) {
                Matcher m = PH.matcher(text);
                while (m.find()) if (!m.group().equals("%%") && !m.group().equals("%n"))
                    assertNotNull(e.name + ": with more than one value, number them (%1$s, %2$d) so a translation can reorder them", m.group(1));
            }
        }
    }

    @Test public void everyLanguageMatchesEnglish() {
        Map<String, StringsXml.Entry> en = byName(StringsXml.read(StringsXml.valuesDir()));
        for (File dir : languageDirs()) {
            for (StringsXml.Entry e : StringsXml.read(dir)) {
                String where = dir.getName() + "/" + e.name;
                StringsXml.Entry src = en.get(e.name);
                assertNotNull(where + ": not an English string (renamed or removed?)", src);
                assertTrue(where + ": marked translatable=\"false\" in English", src.translatable);
                if (src.plural != null) {
                    assertNotNull(where + ": English has it as a plural", e.plural);
                    Set<String> want = placeholders(src.plural.get("other"));
                    for (Map.Entry<String, String> q : e.plural.entrySet())
                        assertEquals(where + " [" + q.getKey() + "]: placeholders", want, withoutCount(placeholders(q.getValue()), want));
                } else {
                    assertNotNull(where + ": English has it as a plain string", e.text);
                    assertEquals(where + ": placeholders must be the same as English", placeholders(src.text), placeholders(e.text));
                }
            }
        }
    }

    /** A plural's "one" form may leave out the count ("one channel" instead of "%d channel"). */
    static Set<String> withoutCount(Set<String> have, Set<String> want) {
        Set<String> out = new TreeSet<>(have); if (!out.equals(want)) { Set<String> w = new TreeSet<>(want); w.removeAll(have); if (w.size() == 1) out.addAll(w); }
        return out;
    }

    @Test public void guideLabelsExistAndTranslationsMatchEnglish() throws Exception {
        String en = Guide.read("en"); assertNotNull("assets/guide/en.json", en);
        Map<String, StringsXml.Entry> strings = byName(StringsXml.read(StringsXml.valuesDir()));
        for (String ref : Guide.labelRefs(en)) assertTrue("guide: {@" + ref + "} is not a string resource", strings.containsKey(ref));
        Guide.Topic[] base = Guide.parse(en, null);
        Set<String> ids = new HashSet<>(); for (Guide.Topic t : base) { ids.add(t.id); for (Guide.Section s : t.sections) ids.add(t.id + "/" + s.id); }
        File dir = Guide.assetFile("guide/en.json").getParentFile();
        for (File f : dir.listFiles()) {
            if (f.getName().equals("en.json")) continue;
            String tr = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String ref : Guide.labelRefs(tr)) assertTrue(f.getName() + ": {@" + ref + "} is not a string resource", strings.containsKey(ref));
            org.json.JSONArray topics = new org.json.JSONObject(tr).optJSONArray("topics");
            if (topics != null) for (int i = 0; i < topics.length(); i++) {
                org.json.JSONObject t = topics.getJSONObject(i); String id = t.getString("id");
                assertTrue(f.getName() + ": topic " + id + " is not in en.json", ids.contains(id));
                org.json.JSONArray s = t.optJSONArray("sections");
                if (s != null) for (int k = 0; k < s.length(); k++) assertTrue(f.getName() + ": section " + id + "/" + s.getJSONObject(k).getString("id") + " is not in en.json", ids.contains(id + "/" + s.getJSONObject(k).getString("id")));
            }
            Guide.parse(en, tr);   // and it lays over English without error
        }
    }

    @Test public void shippedLanguagesHaveFolders() {
        Set<String> dirs = new HashSet<>(); for (File d : languageDirs()) dirs.add(d.getName());
        for (String tag : Txt.LANGUAGES) {
            if (tag.equals("en")) continue;
            String[] p = tag.split("-"); String folder = "values-" + p[0] + (p.length > 1 ? "-r" + p[1] : "");
            assertTrue(tag + " is listed in Txt.LANGUAGES but has no " + folder, dirs.contains(folder));
        }
    }

    @Test public void hostTableReadsEnglish() {
        assertEquals("Cancel", Txt.s(R.string.common_cancel));
        assertEquals("Phone's language (Deutsch)", Txt.s(R.string.device_language_phone, "Deutsch"));
        assertEquals("device_language", Txt.name(R.string.device_language));
        assertEquals(R.string.device_language, Txt.id("device_language", false));
        assertEquals(0, Txt.id("no_such_string_anywhere", false));
    }

    @Test public void compileFollowsAapt() {
        assertEquals("Phone's language", StringsXml.compile("Phone\\'s language"));
        assertEquals("a b", StringsXml.compile("  a \n   b  "));
        assertEquals("  two  spaces", StringsXml.compile("\"  two  spaces\""));
        assertEquals("line\nnext", StringsXml.compile("line\\nnext"));
        assertEquals("@home", StringsXml.compile("\\@home"));
        for (String t : Arrays.asList("Phone's \"x\" & <y>", "  lead", "trail  ", "a  b", "50 %", "@at", "?q", "tab\there", "nl\n second", "→ path · 30 s"))
            assertEquals(t, StringsXml.compile(StringsXml.escape(t).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")));   // as the XML parser hands it over
    }
}
