/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import static org.junit.Assert.*;

import com.macdmr.radio.i18n.Guide;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The in-app guide (assets/guide/en.json, the English source) exported to docs/help/: one Markdown page per topic
 * (named by the topic's id, so a renamed title keeps its URL), an index, and help.json, which signaldeckradio.com reads
 * at build time. en.json stays the only place the guide is written; these files are generated from it and the test
 * fails when they are out of date. Regenerate with:
 *   ./gradlew testReleaseUnitTest --tests '*HelpExportTest' -PexportHelp=true
 */
public class HelpExportTest {
    static final String SOURCE = Guide.SOURCE;
    static final String REGENERATE = "./gradlew testReleaseUnitTest --tests '*HelpExportTest' -PexportHelp=true";

    /** URL-safe name for a topic title: "POTA and SOTA" -> "pota-and-sota". */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** Plain help text as Markdown that renders as the same text (no accidental emphasis, tags or headings). */
    static String md(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\`*_[]<>|".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString().replaceAll("(?m)^(\\s*)([#>+-])", "$1\\\\$2").replaceAll("(?m)^(\\s*\\d+)\\.", "$1\\\\.");
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Every generated file, by name, in a stable order. */
    static Map<String, String> export() {
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder();
        StringBuilder js = new StringBuilder();
        index.append("# SignalDeck guide\n\n")
             .append("<!-- Generated from ").append(SOURCE).append(" by HelpExportTest. Do not edit by hand. -->\n\n")
             .append("The same guide the app shows under Device → Help & how-to, one page per topic.\n");
        js.append("{\n  \"format\": 1,\n  \"source\": ").append(json(SOURCE)).append(",\n  \"topics\": [");
        String group = null;
        int n = 0;
        Guide.Topic[] topics = Guide.topics();
        for (Guide.Topic t : topics) {
            n++;
            String slug = t.id;
            if (!t.group.equals(group)) { group = t.group; index.append("\n## ").append(md(group)).append("\n\n"); }
            index.append("- [").append(md(t.title)).append("](").append(slug).append(".md): ").append(md(t.summary)).append('\n');

            StringBuilder page = new StringBuilder();
            page.append("<!-- Generated from ").append(SOURCE).append(" by HelpExportTest. Do not edit by hand. -->\n\n")
                .append("# ").append(md(t.title)).append("\n\n")
                .append("*").append(md(t.group)).append(" · ").append(n).append(" of ").append(topics.length).append("*\n\n")
                .append(md(t.summary)).append('\n');
            for (Guide.Section s : t.sections) {
                page.append("\n## ").append(md(s.head)).append("\n\n");
                for (String para : s.text.split("\n")) if (!para.trim().isEmpty()) page.append(md(para.trim())).append("\n\n");
            }
            page.append("[All topics](README.md)\n");
            files.put(slug + ".md", page.toString().replaceAll("\n{3,}", "\n\n"));

            js.append(n > 1 ? ",\n" : "\n").append("    {\"slug\": ").append(json(slug))
              .append(", \"group\": ").append(json(t.group))
              .append(", \"title\": ").append(json(t.title))
              .append(", \"summary\": ").append(json(t.summary))
              .append(", \"sections\": [");
            for (int i = 0; i < t.sections.length; i++) {
                js.append(i > 0 ? ",\n" : "\n").append("      {\"head\": ").append(json(t.sections[i].head))
                  .append(", \"text\": ").append(json(t.sections[i].text)).append('}');
            }
            js.append("\n    ]}");
        }
        js.append("\n  ]\n}\n");
        files.put("README.md", index.toString());
        files.put("help.json", js.toString());
        return files;
    }

    static File helpDir() {
        File dir = new File(System.getProperty("user.dir"));          // Gradle runs unit tests in app/
        if (!new File(dir, "settings.gradle").exists()) dir = dir.getParentFile();
        return new File(dir, "docs/help");
    }

    @Test public void topicsAreComplete() {
        Set<String> slugs = new HashSet<>();
        assertTrue(Guide.topics().length > 0);
        for (Guide.Topic t : Guide.topics()) {
            String slug = t.id;
            assertTrue(t.title + ": the id is the page name, lower case letters, digits and hyphens", slug.matches("[a-z0-9]+(-[a-z0-9]+)*"));
            assertTrue("two topics would share the page " + slug + ".md: rename one", slugs.add(slug));
            Set<String> secIds = new HashSet<>();
            assertFalse(t.title + ": no group", t.group.trim().isEmpty());
            assertFalse(t.title + ": no summary", t.summary.trim().isEmpty());
            assertTrue(t.title + ": no sections", t.sections.length > 0);
            for (Guide.Section s : t.sections) {
                assertTrue(t.title + ": two sections share the id " + s.id, secIds.add(s.id));
                assertFalse(t.title + ": a section has no heading", s.head.trim().isEmpty());
                assertFalse(t.title + " / " + s.head + ": no text", s.text.trim().isEmpty());
            }
        }
    }

    @Test public void slugsAndEscaping() {
        assertEquals("pota-and-sota", slug("POTA and SOTA"));
        assertEquals("widget-and-notification", slug("Widget & notification"));
        assertEquals("signaldeck-web", slug("SignalDeck Web"));
        assertEquals("Download/DMR/\\<your call\\>\\_x", md("Download/DMR/<your call>_x"));
        assertEquals("\\# not a heading", md("# not a heading"));
        assertEquals("\"a\\\"b\\nc\"", json("a\"b\nc"));
    }

    @Test public void exportCoversEveryTopicAndSection() {
        Map<String, String> files = export();
        assertEquals(Guide.topics().length + 2, files.size());
        String js = files.get("help.json");
        int sections = 0;
        for (Guide.Topic t : Guide.topics()) {
            assertTrue(t.title, files.containsKey(t.id + ".md"));
            assertTrue(t.title, files.get("README.md").contains("(" + t.id + ".md)"));
            sections += t.sections.length;
        }
        int heads = 0;
        for (int i = js.indexOf("\"head\": "); i >= 0; i = js.indexOf("\"head\": ", i + 1)) heads++;
        assertEquals(sections, heads);
    }

    /** docs/help must match the guide. With -PexportHelp=true the files are written instead of checked. */
    @Test public void committedGuideIsCurrent() throws IOException {
        Map<String, String> files = export();
        File dir = helpDir();
        if (Boolean.parseBoolean(System.getProperty("signaldeck.exportHelp", "false"))) {
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
        if (present != null) for (File f : present) if (f.isFile() && !files.containsKey(f.getName())) stale.add(f.getName() + " (no longer a topic)");
        assertTrue("docs/help is out of date with the guide (" + stale + "). Regenerate it with: " + REGENERATE, stale.isEmpty());
    }
}
