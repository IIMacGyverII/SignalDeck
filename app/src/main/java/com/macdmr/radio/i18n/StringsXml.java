/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.i18n;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads {@code res/values*}/strings*.xml the way aapt2 compiles them (whitespace collapsed outside double quotes,
 * {@code \n \t \' \" \\ \@ \? \\uXXXX} escapes). Used by host tests only: {@link Txt}'s English table without a
 * Context, and the guard tests that compare every language with English. The app itself reads compiled resources.
 * <p>i18n: stays English (exception messages and preference keys only; the user-facing text is in the resources).
 */
public final class StringsXml {
    private StringsXml() { }

    public static final class Entry {
        public final String name, text, comment; public final Map<String, String> plural; public final boolean translatable;
        /** False for {@code formatted="false"}: a string read without arguments whose "%" is plain text ("50 %, faint"). */
        public final boolean formatted;
        Entry(String name, String text, Map<String, String> plural, String comment, boolean translatable) { this(name, text, plural, comment, translatable, true); }
        Entry(String name, String text, Map<String, String> plural, String comment, boolean translatable, boolean formatted) {
            this.name = name; this.text = text; this.plural = plural; this.comment = comment; this.translatable = translatable; this.formatted = formatted;
        }
    }

    /** {@code src/main/res/values} seen from the test's working directory (Gradle runs unit tests in app/), or null. */
    public static File valuesDir() { File r = resDir(); return r == null ? null : new File(r, "values"); }

    public static File resDir() {
        String wd = System.getProperty("user.dir"); if (wd == null) return null;
        for (String p : new String[] { "src/main/res", "app/src/main/res" }) { File f = new File(wd, p); if (f.isDirectory()) return f; }
        return null;
    }

    /** Every string and plural in the strings*.xml files of one values folder, in file and document order. */
    public static List<Entry> read(File valuesDir) {
        List<Entry> out = new ArrayList<>();
        File[] files = valuesDir.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            try {
                DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance(); fac.setNamespaceAware(true);
                Document doc = fac.newDocumentBuilder().parse(f);
                String comment = null;
                for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n.getNodeType() == Node.COMMENT_NODE) { comment = n.getNodeValue().trim(); continue; }
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element e = (Element) n; String name = e.getAttribute("name");
                    boolean tr = !"false".equals(e.getAttribute("translatable")), fmt = !"false".equals(e.getAttribute("formatted"));
                    if ("string".equals(e.getTagName())) out.add(new Entry(name, compile(e.getTextContent()), null, comment, tr, fmt));
                    else if ("plurals".equals(e.getTagName())) {
                        Map<String, String> q = new LinkedHashMap<>(); NodeList items = e.getElementsByTagName("item");
                        for (int i = 0; i < items.getLength(); i++) { Element it = (Element) items.item(i); q.put(it.getAttribute("quantity"), compile(it.getTextContent())); }
                        out.add(new Entry(name, null, q, comment, tr, fmt));
                    }
                    comment = null;
                }
            } catch (Exception ex) { throw new IllegalStateException(f + ": " + ex.getMessage(), ex); }
        }
        return out;
    }

    /** aapt2's string compilation: collapse whitespace outside "…", drop the quote marks, resolve escapes. */
    public static String compile(String raw) {
        StringBuilder b = new StringBuilder(); boolean quoted = false, space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                if (space) { b.append(' '); space = false; }
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'u': b.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16)); i += 4; break;
                    default: b.append(n);
                }
                continue;
            }
            if (c == '"') { quoted = !quoted; continue; }
            if (!quoted && Character.isWhitespace(c)) { if (b.length() > 0) space = true; continue; }
            if (space) { b.append(' '); space = false; }
            b.append(c);
        }
        return b.toString();
    }

    /** The inverse for one value: text as it must be written in strings.xml so {@link #compile} gives it back. */
    public static String escape(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\'': b.append("\\'"); break;
                case '"': b.append("\\\""); break;
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case ' ':
                    // a space survives only singly and between other characters; else spell it out
                    boolean lone = i > 0 && i < text.length() - 1 && text.charAt(i - 1) != ' ' && text.charAt(i + 1) != ' ' && text.charAt(i - 1) != '\n' && text.charAt(i + 1) != '\n';
                    b.append(lone ? " " : "\\u0020"); break;
                default:
                    if (i == 0 && (c == '@' || c == '?')) b.append('\\');
                    b.append(c);
            }
        }
        return b.toString();
    }
}
