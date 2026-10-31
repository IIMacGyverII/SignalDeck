/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.i18n;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-app guide, from {@code assets/guide/en.json} (the source; edit it there, not in code) and, for another
 * language, {@code assets/guide/<lang>.json} laid over it by topic and section id: a topic or section the language
 * lacks is shown in English. {@code {@name}} in any text is replaced by that string resource, so the guide names a
 * button or menu exactly as the screen shows it, in every language.
 */
public final class Guide {
    public static final String SOURCE = "app/src/main/assets/guide/en.json";

    public static final class Section {
        public final String id, head, text;
        Section(String id, String head, String text) { this.id = id; this.head = head; this.text = text; }
    }
    public static final class Topic {
        public final String id, groupId, group, icon, accent, title, summary; public final Section[] sections;
        Topic(String id, String groupId, String group, String icon, String accent, String title, String summary, Section[] s) {
            this.id = id; this.groupId = groupId; this.group = group; this.icon = icon; this.accent = accent; this.title = title; this.summary = summary; sections = s;
        }
        public int section(String sectionId) { for (int i = 0; i < sections.length; i++) if (sections[i].id.equals(sectionId)) return i; return -1; }
    }

    private static volatile String cachedFor; private static volatile Topic[] cached;

    /** The guide in the app's language (English for what that language lacks). */
    public static Topic[] topics() {
        String tag = Txt.locale().toLanguageTag();
        Topic[] t = cached;
        if (t != null && tag.equals(cachedFor)) return t;
        synchronized (Guide.class) {
            if (cached != null && tag.equals(cachedFor)) return cached;
            String en = read("en"); String lang = null;
            for (String cand : candidates(Txt.locale())) { if (cand.equals("en")) break; lang = read(cand); if (lang != null) break; }
            cached = parse(en, lang); cachedFor = tag;
            return cached;
        }
    }

    /** "pt-BR" → pt-BR, pt; "de-AT" → de-AT, de. The first file that exists wins. */
    static List<String> candidates(Locale l) {
        List<String> out = new ArrayList<>();
        if (!l.getCountry().isEmpty()) out.add(l.getLanguage() + "-" + l.getCountry());
        out.add(l.getLanguage());
        return out;
    }

    /** Parse English and lay an optional translation over it. */
    public static Topic[] parse(String enJson, String langJson) {
        try {
            JSONObject en = new JSONObject(enJson);
            JSONObject tr = langJson == null ? null : new JSONObject(langJson);
            JSONObject groups = en.getJSONObject("groups"), tgroups = tr != null ? tr.optJSONObject("groups") : null;
            Map<String, JSONObject> ttopics = new HashMap<>();
            if (tr != null) { JSONArray a = tr.optJSONArray("topics"); if (a != null) for (int i = 0; i < a.length(); i++) ttopics.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i)); }
            JSONArray a = en.getJSONArray("topics");
            Topic[] out = new Topic[a.length()];
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i); String id = t.getString("id"); JSONObject tt = ttopics.get(id);
                Map<String, JSONObject> tsec = new HashMap<>();
                if (tt != null) { JSONArray s = tt.optJSONArray("sections"); if (s != null) for (int k = 0; k < s.length(); k++) tsec.put(s.getJSONObject(k).getString("id"), s.getJSONObject(k)); }
                JSONArray s = t.getJSONArray("sections"); Section[] secs = new Section[s.length()];
                for (int k = 0; k < s.length(); k++) {
                    JSONObject e = s.getJSONObject(k), te = tsec.get(e.getString("id"));
                    secs[k] = new Section(e.getString("id"), labels(pick(te, e, "head")), labels(pick(te, e, "text")));
                }
                String g = t.getString("group");
                String gname = tgroups != null && tgroups.has(g) ? tgroups.getString(g) : groups.getString(g);
                out[i] = new Topic(id, g, labels(gname), t.getString("icon"), t.getString("accent"), labels(pick(tt, t, "title")), labels(pick(tt, t, "summary")), secs);
            }
            return out;
        } catch (JSONException e) { throw new IllegalStateException("guide: " + e.getMessage(), e); }
    }

    private static String pick(JSONObject tr, JSONObject en, String key) throws JSONException {
        return tr != null && tr.has(key) && !tr.getString(key).trim().isEmpty() ? tr.getString(key) : en.getString(key);
    }

    private static final Pattern LABEL = Pattern.compile("\\{@([a-z0-9_]+)\\}");

    /** Replace {@name} with the string resource of that name (in the app's language). */
    public static String labels(String text) {
        if (text.indexOf("{@") < 0) return text;
        Matcher m = LABEL.matcher(text); StringBuffer b = new StringBuffer();
        while (m.find()) {
            int id = Txt.id(m.group(1), false);
            m.appendReplacement(b, Matcher.quoteReplacement(id != 0 ? Txt.s(id) : m.group(1)));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Every {@name} used in a guide file (the guard test checks each one exists). */
    public static List<String> labelRefs(String json) {
        List<String> out = new ArrayList<>(); Matcher m = LABEL.matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** A guide file's text, or null if this language has none. In host tests, read from src/main/assets. */
    public static String read(String lang) {
        Context c = Txt.context();
        if (c != null) {
            try (InputStream in = c.getAssets().open("guide/" + lang + ".json")) { return readAll(in); } catch (IOException e) { return null; }
        }
        File f = assetFile("guide/" + lang + ".json");
        try { return f != null && f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; } catch (IOException e) { return null; }
    }

    static File assetFile(String path) {
        String wd = System.getProperty("user.dir"); if (wd == null) return null;
        for (String p : new String[] { "src/main/assets/", "app/src/main/assets/" }) { File f = new File(wd, p + path); if (f.getParentFile().isDirectory()) return f; }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
