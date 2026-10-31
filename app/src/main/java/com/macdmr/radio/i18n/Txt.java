/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one way user-facing text is fetched, from any class (service, engine, car screens, web server, views).
 * <ul>
 *   <li>{@link #s} a string in the app's language, {@link #p} a plural, {@link #en} the English text (for the stored
 *       history text, logs and anything that must stay English).</li>
 *   <li>The language is the phone's, or the override in Device → Language ({@link #setLanguage}). On Android 13+ the
 *       system applies it to the whole app; on 10–12 appcompat applies it to activities only, so this class keeps its
 *       own localised context and services, notifications, the widget and Android Auto read through it too.</li>
 *   <li>Host tests have no Context: the English table is read from {@code src/main/res/values/strings*.xml}, so code
 *       that calls Txt runs (and says the same English) under JUnit.</li>
 * </ul>
 * Logs, diagnostics, report.json, file formats and anything sent on the air never go through {@link #s}.
 */
public final class Txt {
    private Txt() { }

    /** Shipped languages besides English (the Device → Language list), as BCP 47 tags; add one when it ships. */
    public static final String[] LANGUAGES = { "en" };
    /** Pseudo-locales for the layout and extraction checks; offered in debug builds only (pseudoLocalesEnabled). */
    public static final String[] PSEUDO = { "en-XA", "ar-XB" };

    private static volatile Context app, loc, english;

    /** From Application.onCreate, and again when the system configuration changes (the phone's language). */
    public static void init(Context c) {
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Configuration en = new Configuration(app.getResources().getConfiguration()); en.setLocale(Locale.ENGLISH);
        english = app.createConfigurationContext(en);
        refresh();
    }

    /** Rebuild the localised context for the current choice (after {@link #setLanguage} or a system change). */
    public static void refresh() {
        Context a = app; if (a == null) return;
        Configuration cf = new Configuration(a.getResources().getConfiguration()); cf.setLocale(locale());
        loc = a.createConfigurationContext(cf);
    }

    /** The language in use: the override if one is set, else the phone's first language. */
    public static Locale locale() {
        if (app == null) return Locale.ENGLISH;
        String tag = language();
        if (!tag.isEmpty()) return Locale.forLanguageTag(tag);
        return Resources.getSystem().getConfiguration().getLocales().get(0);
    }

    /**
     * The override as a language tag, or "" when the app follows the phone. Our own copy is read too: on Android 10–12
     * appcompat loads its stored choice only when an activity starts, and the service can start at boot without one.
     */
    public static String language() {
        LocaleListCompat o = AppCompatDelegate.getApplicationLocales();
        if (!o.isEmpty()) return o.toLanguageTags();
        Context a = app;
        return a == null ? "" : a.getSharedPreferences("radio", Context.MODE_PRIVATE).getString(PREF, "");
    }
    private static final String PREF = "appLanguage";

    /** Set the override ("" = follow the phone). Activities are recreated by appcompat; everything else reads the new context at once. */
    public static void setLanguage(String tag) {
        String t = tag == null ? "" : tag;
        Context a = app; if (a != null) a.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putString(PREF, t).apply();
        AppCompatDelegate.setApplicationLocales(t.isEmpty() ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(t));
        refresh();
    }

    /** A language's own name, in that language ("Deutsch", "Español"), for the Device → Language list. */
    public static String nativeName(String tag) {
        Locale l = Locale.forLanguageTag(tag); String n = l.getDisplayName(l);
        return n.isEmpty() ? tag : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A localised Context for code that needs one (views built by a service, notifications). */
    public static Context context() { Context c = loc; return c != null ? c : app; }

    // ---- strings ------------------------------------------------------------------------------------

    public static String s(int id, Object... args) {
        Context c = loc;
        if (c == null) return host(id, args);
        return args.length == 0 ? c.getString(id) : c.getString(id, args);
    }

    public static String p(int id, int n, Object... args) {
        Context c = loc;
        if (c == null) return hostPlural(id, n, args);
        return c.getResources().getQuantityString(id, n, args);
    }

    /** English, whatever the app language: the canonical history text, and text that is parsed or logged. */
    public static String en(int id, Object... args) {
        Context c = english;
        if (c == null) return host(id, args);
        return args.length == 0 ? c.getString(id) : c.getString(id, args);
    }

    /** English plural. */
    public static String enP(int id, int n, Object... args) {
        Context c = english;
        if (c == null) return hostPlural(id, n, args);
        return c.getResources().getQuantityString(id, n, args);
    }

    /** The resource's name ("intercom_scan_on"): stable across builds, unlike its id. */
    public static String name(int id) {
        Context c = app;
        if (c == null) { String n = hostNames().get(id); if (n == null) throw new IllegalArgumentException("no string " + id); return n; }
        return c.getResources().getResourceEntryName(id);
    }

    /** A string or plural's id from its name, 0 if no such resource (an old row naming a string since removed). */
    public static int id(String name, boolean plural) {
        Context c = app;
        if (c == null) { Integer i = hostIds(plural).get(name); return i == null ? 0 : i; }
        return c.getResources().getIdentifier(name, plural ? "plurals" : "string", c.getPackageName());
    }

    // ---- host tests: the English table from res/values ------------------------------------------------

    private static Map<String, String> hostStrings;
    private static Map<String, Map<String, String>> hostPlurals;
    private static Map<Integer, String> hostNames;
    private static Map<String, Integer> hostStringIds, hostPluralIds;

    private static synchronized void loadHost() {
        if (hostStrings != null) return;
        File dir = StringsXml.valuesDir();
        hostStrings = new HashMap<>(); hostPlurals = new HashMap<>();
        if (dir != null) for (StringsXml.Entry e : StringsXml.read(dir)) {
            if (e.plural != null) hostPlurals.put(e.name, e.plural); else hostStrings.put(e.name, e.text);
        }
        hostNames = new HashMap<>(); hostStringIds = new HashMap<>(); hostPluralIds = new HashMap<>();
        reflect("com.macdmr.radio.R$string", hostStringIds);
        reflect("com.macdmr.radio.R$plurals", hostPluralIds);
    }

    private static void reflect(String cls, Map<String, Integer> ids) {
        try {
            for (Field f : Class.forName(cls).getFields()) { int v = f.getInt(null); ids.put(f.getName(), v); hostNames.put(v, f.getName()); }
        } catch (ClassNotFoundException none) { /* no plurals yet */ } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    private static Map<Integer, String> hostNames() { loadHost(); return hostNames; }
    private static Map<String, Integer> hostIds(boolean plural) { loadHost(); return plural ? hostPluralIds : hostStringIds; }

    private static String host(int id, Object[] args) {
        loadHost();
        String t = hostStrings.get(hostNames().get(id));
        if (t == null) throw new IllegalArgumentException("no English string for id " + id + " (" + hostNames.get(id) + ")");
        return args.length == 0 ? t : String.format(Locale.US, t, args);
    }

    private static String hostPlural(int id, int n, Object[] args) {
        loadHost();
        Map<String, String> q = hostPlurals.get(hostNames().get(id));
        if (q == null) throw new IllegalArgumentException("no English plural for id " + id);
        String t = n == 1 && q.containsKey("one") ? q.get("one") : q.get("other");
        return String.format(Locale.US, t, args);
    }
}
