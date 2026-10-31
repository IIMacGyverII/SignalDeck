/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guard for SignalDeck Web's text (assets/web/i18n/&lt;lang&gt;.json, read by t() in app.js):
 * <ul>
 *   <li>every key the pages use exists in en.json: {@code t('…')}, {@code t.plural('…')} (key.one / key.other), the
 *       {@code data-i18n*} attributes, and any JS literal shaped like a key (a known page prefix and an underscore,
 *       for keys chosen at run time: {@code t(on ? 'a_x' : 'a_y')});</li>
 *   <li>English has a translator note for every key ({@code _comments}), and every other table has only English's
 *       keys with the same {placeholders};</li>
 *   <li>a ratchet on English left in the pages: string literals in the JS that read like text, and HTML text or
 *       title / placeholder / aria-label / alt attributes without a data-i18n attribute. The counts per file are in
 *       {@code src/test/resources/i18n/web-literal-baseline.txt} and only go down; rewrite it with
 *       {@code ./gradlew testReleaseUnitTest --tests '*WebI18nTest' -PexportI18n=true --rerun-tasks}.</li>
 * </ul>
 * Deliberate English (mode names, units, protocol, text sent on the air) is marked on its line with
 * {@code // i18n: <reason>} or {@code /* i18n: <reason> *}{@code /}, or {@code translate="no"} on an HTML element.
 */
public class WebI18nTest {
    static final String BASELINE = "src/test/resources/i18n/web-literal-baseline.txt";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*_[a-z0-9_]+(?:\\.(?:zero|one|two|few|many|other))?");
    private static final Pattern T_CALL = Pattern.compile("\\bt(\\.plural)?\\(\\s*'([^']+)'");
    private static final Pattern ATTR_KEY = Pattern.compile("data-i18n(?:-[a-z]+)?=\"([^\"]+)\"");
    private static final Pattern PH = Pattern.compile("\\{(\\w+)\\}");

    static File web() {
        File f = new File("src/main/assets/web"); if (f.isDirectory()) return f;
        return new File("app/src/main/assets/web");
    }
    static File baselineFile() { File f = new File("src/test"); return f.isDirectory() ? new File(BASELINE) : new File("app/" + BASELINE); }
    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static JSONObject table(String lang) throws Exception { return new JSONObject(read(new File(web(), "i18n/" + lang + ".json"))); }
    static List<File> scripts() { List<File> out = new ArrayList<>(); File[] fs = web().listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".js")) out.add(f); out.sort(null); return out; }
    static Set<String> keys(JSONObject o) { Set<String> s = new TreeSet<>(); for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); if (!k.startsWith("_")) s.add(k); } return s; }
    static String base(String k) { return k.replaceFirst("\\.(zero|one|two|few|many|other)$", ""); }
    static Set<String> placeholders(String s) { Set<String> p = new TreeSet<>(); Matcher m = PH.matcher(s); while (m.find()) p.add(m.group(1)); return p; }

    // ---- keys ---------------------------------------------------------------------------------------

    @Test public void everyKeyThePagesUseExists() throws Exception {
        JSONObject en = table("en"); Set<String> have = keys(en);
        Set<String> prefixes = new HashSet<>(); for (String k : have) prefixes.add(k.substring(0, k.indexOf('_')));
        Set<String> missing = new TreeSet<>();
        List<File> files = scripts(); files.add(new File(web(), "index.html"));
        for (File f : files) {
            String src = read(f);
            Matcher m = T_CALL.matcher(src);
            while (m.find()) {
                String k = m.group(2);
                if (m.group(1) != null) { if (!have.contains(k + ".one") || !have.contains(k + ".other")) missing.add(f.getName() + ": " + k + ".one/.other"); }
                else if (!have.contains(k)) missing.add(f.getName() + ": " + k);
            }
            Matcher a = ATTR_KEY.matcher(src);
            while (a.find()) if (KEY.matcher(a.group(1)).matches() && !have.contains(a.group(1))) missing.add(f.getName() + ": " + a.group(1));
            if (f.getName().endsWith(".js")) {
                Matcher l = Pattern.compile("'([a-z][a-z0-9_.]*)'").matcher(src);
                while (l.find()) {
                    String k = l.group(1);
                    if (!KEY.matcher(k).matches() || !prefixes.contains(k.substring(0, k.indexOf('_')))) continue;
                    if (!have.contains(k) && !have.contains(k + ".other")) missing.add(f.getName() + ": " + k);
                }
            }
        }
        assertTrue("Keys the pages use that en.json lacks: " + missing, missing.isEmpty());
    }

    @Test public void englishIsComplete() throws Exception {
        JSONObject en = table("en"); JSONObject notes = en.getJSONObject("_comments");
        for (String k : keys(en)) {
            assertTrue(k + ": a key is <page>_<purpose>", KEY.matcher(k).matches());
            assertFalse(k + ": empty text", en.getString(k).isEmpty());
            assertTrue(k + ": needs a translator note in _comments", notes.has(base(k)) && !notes.getString(base(k)).isEmpty());
            if (!k.equals(base(k))) {
                assertTrue(k + ": a plural needs .one and .other", en.has(base(k) + ".one") && en.has(base(k) + ".other"));
                assertFalse(base(k) + ": a plural's name is not also a plain key", en.has(base(k)));
            }
        }
        for (Iterator<String> it = notes.keys(); it.hasNext(); ) { String k = it.next(); assertTrue("_comments." + k + ": no such key", en.has(k) || en.has(k + ".other")); }
    }

    @Test public void everyLanguageMatchesEnglish() throws Exception {
        JSONObject en = table("en");
        File[] fs = new File(web(), "i18n").listFiles(); assertNotNull(fs);
        for (File f : fs) {
            if (!f.getName().endsWith(".json") || f.getName().equals("en.json")) continue;
            JSONObject tr = new JSONObject(read(f));
            for (String k : keys(tr)) {
                String where = f.getName() + ": " + k;
                String src = en.has(k) ? en.getString(k) : !k.equals(base(k)) && en.has(base(k) + ".other") ? en.getString(base(k) + ".other") : null;
                assertNotNull(where + ": not an English key (renamed or removed?)", src);
                Set<String> want = placeholders(src), got = placeholders(tr.getString(k));
                if (!k.equals(base(k))) { want.remove("n"); got.remove("n"); }    // a plural form may leave the count out ("one channel")
                assertEquals(where + ": the {placeholders} must be English's", want, got);
                String text = tr.getString(k);
                assertFalse(where + ": a written backslash-n (\\\\n in the JSON); English has a line break, write \\n", text.contains("\\n") && !src.contains("\\n"));
                assertEquals(where + ": line breaks must be English's", src.split("\n", -1).length, text.split("\n", -1).length);
            }
        }
    }

    // ---- the ratchet: English left in the pages ---------------------------------------------------------

    private static final Pattern LIT = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** Reads like text: two words, a capitalised word, or two upper-case words ("No pictures yet", "Analog", "NOT TRACKING"). */
    private static final Pattern SENT = Pattern.compile("[A-Za-z][a-z]+ [a-z]{2,}|(?:^|[^\\w-])[A-Z][a-z]{2,}|[A-Z]{2,} [A-Z]{2,}");
    /** The literal is handed to code, not shown: a key, a selector, an API path, a comparison, a class name. */
    private static final Pattern NON_UI = Pattern.compile("(?:\\bt|\\bt\\.plural|\\bapi|\\bact|\\bfetch|querySelector(?:All)?|getElementById|\\$|addEventListener|removeEventListener|on|\\.toggle|\\.add|\\.remove|\\.contains|getItem|setItem|console\\.\\w+|RegExp|\\.test|getAttribute|setAttribute|closest|createElement|getContext|includes|startsWith|indexOf)\\s*\\(\\s*$"
            + "|(?:===|!==|==|!=|case)\\s*$|(?:className|\\.font)\\s*=\\s*$");
    private static final Pattern MARK = Pattern.compile("/[/*] i18n:");

    /** HTML pieces inside a JS literal are not text: tags, a tag's start or end, attributes. */
    static String litText(String lit) {
        return lit.replaceAll("<[^>]*>", " ").replaceAll("<[^>]*$", " ").replaceAll("^[^<>]*>", " ").replaceAll("[\\w-]+=\"[^\"]*(\"|$)", " ").replace("\\n", " ");
    }

    static int countJs(String src) {
        int n = 0; boolean block = false;
        for (String line : src.split("\r?\n")) {
            String t = line.trim();
            if (block) { if (t.contains("*/")) block = false; continue; }
            if (t.startsWith("/*")) { if (!t.contains("*/")) block = true; continue; }
            if (t.startsWith("//") || t.startsWith("*")) continue;
            if (MARK.matcher(line).find()) continue;
            Matcher m = LIT.matcher(line);
            while (m.find()) {
                String lit = m.group(1) != null ? m.group(1) : m.group(2);
                if (lit.equals("use strict") || lit.matches("[A-Z][a-z]+(-[A-Z][a-z]+)+") || !SENT.matcher(litText(lit)).find()) continue;
                if (NON_UI.matcher(line.substring(0, m.start())).find()) continue;
                n++;
            }
        }
        return n;
    }

    private static final Pattern TAG = Pattern.compile("<!--[\\s\\S]*?-->|<(/?)([a-zA-Z0-9]+)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Set<String> VOID = new HashSet<>(java.util.Arrays.asList("input", "img", "br", "meta", "link", "source", "hr", "wbr"));
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final String[][] ATTRS = { { "title", "data-i18n-title" }, { "placeholder", "data-i18n-placeholder" }, { "aria-label", "data-i18n-aria" }, { "alt", "data-i18n-alt" } };
    static String attr(String a, String n) { Matcher m = Pattern.compile("(?:^|\\s)" + n + "=\"([^\"]*)\"").matcher(a); return m.find() ? m.group(1) : null; }
    static boolean wordy(String s) { return WORD.matcher(s.replaceAll("&[a-z]+;|&#\\d+;", " ")).find(); }

    private static final class El { final String tag; final boolean covered, keyed; boolean used; El(String t, boolean c, boolean k) { tag = t; covered = c; keyed = k; } }

    /** Text in the body without data-i18n: a text node, or a shown attribute without its data-i18n-* twin. */
    static int countHtml(String src) {
        int n = 0, last = 0; boolean body = false;
        Deque<El> st = new ArrayDeque<>();
        Matcher m = TAG.matcher(src);
        while (m.find()) {
            if (body && m.start() > last) {
                String text = src.substring(last, m.start());
                El top = st.peek();
                if (wordy(text) && (top == null || !top.covered)) { if (top != null && top.keyed && !top.used) top.used = true; else n++; }
            }
            last = m.end();
            if (m.group().startsWith("<!--")) continue;
            String tag = m.group(2).toLowerCase(java.util.Locale.ROOT), a = m.group(3);
            if (!m.group(1).isEmpty()) {
                if (tag.equals("body")) break;
                while (!st.isEmpty()) if (st.pop().tag.equals(tag)) break;
                continue;
            }
            if (tag.equals("body")) { body = true; continue; }
            if (!body) continue;
            boolean parentCovered = !st.isEmpty() && st.peek().covered;
            boolean covered = parentCovered || a.contains("data-i18n-html=") || a.contains("translate=\"no\"");
            if (!covered) for (String[] p : ATTRS) { String v = attr(a, p[0]); if (v != null && wordy(v) && !a.contains(p[1] + "=")) n++; }
            if (tag.equals("script") || tag.equals("style")) { int e = src.indexOf("</" + tag, m.end()); if (e > 0) { m.region(e, src.length()); last = e; } continue; }
            if (VOID.contains(tag) || a.endsWith("/")) continue;
            st.push(new El(tag, covered, attr(a, "data-i18n") != null));
        }
        return n;
    }

    static Map<String, Integer> scan() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (File f : scripts()) { int c = countJs(read(f)); if (c > 0) out.put(f.getName(), c); }
        int h = countHtml(read(new File(web(), "index.html"))); if (h > 0) out.put("index.html", h);
        return out;
    }

    @Test public void noNewEnglishInThePages() throws IOException {
        Map<String, Integer> now = scan();
        if (Boolean.parseBoolean(System.getProperty("signaldeck.exportI18n", "false"))) {
            StringBuilder b = new StringBuilder("# English left in SignalDeck Web's pages, per file (WebI18nTest). Only ever goes down.\n");
            int total = 0; for (Map.Entry<String, Integer> e : now.entrySet()) { b.append(e.getValue()).append(' ').append(e.getKey()).append('\n'); total += e.getValue(); }
            b.append("# total ").append(total).append('\n');
            File f = baselineFile(); f.getParentFile().mkdirs(); Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Integer> base = new TreeMap<>();
        File bf = baselineFile();
        if (bf.isFile()) for (String l : read(bf).split("\n")) { l = l.trim(); if (l.isEmpty() || l.startsWith("#")) continue; String[] p = l.split("\\s+", 2); base.put(p[1], Integer.parseInt(p[0])); }
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, Integer> e : now.entrySet()) { int was = base.getOrDefault(e.getKey(), 0); if (e.getValue() > was) worse.add(e.getKey() + " (" + was + " → " + e.getValue() + ")"); }
        assertTrue("New English in the web pages: " + worse + ". Put the text in assets/web/i18n/en.json and use t('key') or data-i18n=\"key\"; "
                + "mark text that must stay English with // i18n: <reason> (JS) or translate=\"no\" (HTML).", worse.isEmpty());
    }

    @Test public void scannerSeesText() {
        assertEquals(1, countJs("x.textContent = 'No pictures yet';\n"));
        assertEquals(4, countJs("toast('Uploading ' + f.name + ' now, please wait');\nh = '<div class=\"empty\">Nothing heard yet</div>';\nc.textContent = 'NOT TRACKING';\n"));
        assertEquals(0, countJs("x.textContent = t('sstv_none'); api('decode/stop', {}); if (e.key === 'Escape') b.className = 'badge tx';\n"));
        assertEquals(0, countJs("h = '<div class=\"item click\" data-n=\"' + n + '\">' + mhz(f) + ' MHz';\n"));
        assertEquals(0, countJs("x.textContent = 'DMR · CC' + cc;   // i18n: mode name\n"));
        assertEquals(1, countHtml("<body><p>Some words</p><p data-i18n=\"a_b\">Kept words</p><b translate=\"no\">APRS here</b></body>"));
        assertEquals(1, countHtml("<body><label data-i18n=\"a_b\">Name<input placeholder=\"Your name\"></label><p data-i18n-html=\"a_c\">See <a href=\"x\">this page</a></p></body>"));
        assertEquals(0, countHtml("<body><input placeholder=\"Your name\" data-i18n-placeholder=\"a_d\"><span>--</span></body>"));
    }
}
