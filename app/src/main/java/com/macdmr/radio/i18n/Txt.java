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
 * <p>i18n: stays English (exception messages and preference keys only; the user-facing text is in the resources).
 */
public final class Txt {
    private Txt() { }

    /** Shipped languages besides English (the Device → Language list), as BCP 47 tags; add one when it ships. */
    public static final String[] LANGUAGES = { "en", "de" };
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
        boolean rec = !"en".equals(locale().getLanguage());
        synchronized (SHOWN) { SHOWN.clear(); }
        recording = rec;
        String o = language();
        java.util.List<Locale> phone = new java.util.ArrayList<>();
        android.os.LocaleList sys = Resources.getSystem().getConfiguration().getLocales();
        for (int i = 0; i < sys.size(); i++) phone.add(sys.get(i));
        cjk = cjkLocale(o.isEmpty() ? null : Locale.forLanguageTag(o.split(",")[0]), phone, LANGUAGES);
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

    /** Languages written without spaces in Han/kana/hangul (no tracking, phrase line breaks, own glyph forms); zh, ko when they ship. */
    static final String[] CJK = { "ja" };
    private static volatile Locale cjk;

    /** True when the text on screen is Japanese (the override, or the phone's language when the app ships it): the layout rules of {@code ui.Neo}. */
    public static boolean cjk() { return cjk != null; }
    /** The locale CJK text is drawn with ("ja-JP", so shared Han characters take Japanese forms), null for any other language. */
    public static Locale cjkLocale() { return cjk; }

    /**
     * The CJK locale of the language shown, or null: the override when one is set (even one not shipped, the layout
     * check), else the phone's first language that ships (the one Android picks resources for), English if none does.
     */
    static Locale cjkLocale(Locale override, java.util.List<Locale> phone, String[] shipped) {
        Locale shown = override;
        if (shown == null) for (Locale l : phone) if (java.util.Arrays.asList(shipped).contains(l.getLanguage())) { shown = l; break; }
        if (shown == null || !java.util.Arrays.asList(CJK).contains(shown.getLanguage())) return null;
        return "ja".equals(shown.getLanguage()) ? Locale.JAPAN : shown;
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
        return note(args.length == 0 ? c.getString(id) : c.getString(id, args), id, false, 0, args);
    }

    public static String p(int id, int n, Object... args) {
        Context c = loc;
        if (c == null) return hostPlural(id, n, args);
        return note(c.getResources().getQuantityString(id, n, args), id, true, n, args);
    }

    // ---- the English of a text already shown --------------------------------------------------------------

    /*
     * Many methods return an error or a status as finished text in the app language (Txt.s), and some of those texts
     * end up in the history's English text column or a DIAG line. In a language other than English every s/p remembers
     * which string and arguments made its text (the last SHOWN_MAX distinct texts), so such a text can be turned back
     * into its Msg: Msg.shown(e) as an argument, english(e) for a log. In English nothing is kept (the text is English).
     */
    private static final int SHOWN_MAX = 1024;
    private static final Map<String, Msg> SHOWN = new java.util.LinkedHashMap<String, Msg>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Msg> e) { return size() > SHOWN_MAX; }
    };
    private static volatile boolean recording;

    private static String note(String out, int id, boolean plural, int n, Object[] args) {
        if (!recording || out == null || out.isEmpty()) return out;
        try { Msg m = plural ? Msg.plural(id, n, args) : Msg.of(id, args); synchronized (SHOWN) { SHOWN.put(out, m); } } catch (RuntimeException ignored) { }
        return out;
    }
    /** Host tests: remember texts as a non-English language does. */
    static void recordForTest(boolean on) { synchronized (SHOWN) { SHOWN.clear(); } recording = on; }
    static String noteForTest(String out, int id, Object... args) { return note(out, id, false, 0, args); }

    /** The Msg that made a text Txt.s / Txt.p returned recently in a language other than English, else null. */
    public static Msg madeBy(String text) { if (text == null || !recording) return null; synchronized (SHOWN) { return SHOWN.get(text); } }

    /** A text returned in the app language (an error, a status) in English, for logs and DIAG; the text itself when unknown. */
    public static String english(String text) { Msg m = madeBy(text); return m == null ? text : m.english(); }

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

    // ---- numbers and dates on screen -----------------------------------------------------------------

    /**
     * A decimal number for the screen, in the app language's style: "467.6250" in English, "467,6250" in German. Never
     * grouped, digits always 0-9. Not for edit fields that are parsed back, files, logs, report.json, the web API or
     * anything sent on the air: those keep {@code String.format(Locale.US, ...)}.
     */
    public static String decimal(double v, int places) { return decimal(locale(), v, places); }
    /** {@link #decimal} with a sign always shown ("+1.5", "-0.4"), as {@code %+.Nf}. */
    public static String signed(double v, int places) { return number(locale(), "%+." + places + "f", v); }
    /** A frequency in MHz for the screen, {@code places} decimals: "467.6250" / "467,6250". */
    public static String freqMhz(long hz, int places) { return decimal(hz / 1e6, places); }
    /** One number in a {@code java.util.Formatter} spec of its own ("%4.1f"), with the app language's separator. */
    public static String decimalf(String spec, double v) { return number(locale(), spec, v); }

    /** A number already written with a point ("12.5", from code that trims zeros), with the app language's separator. */
    public static String decimalText(String plain) { return decimalText(locale(), plain); }
    static String decimalText(Locale l, String plain) { char sep = separator(l); return plain == null || sep == '.' ? plain : plain.replace('.', sep); }

    static String decimal(Locale l, double v, int places) { return number(l, "%." + places + "f", v); }
    static String number(Locale l, String spec, double v) {
        String s = String.format(Locale.US, spec, v);
        char sep = separator(l);
        return sep == '.' ? s : s.replace('.', sep);
    }
    /** The locale's decimal separator; English and anything unknown keep the point. */
    static char separator(Locale l) {
        if (l == null || "en".equals(l.getLanguage())) return '.';
        char sep = java.text.DecimalFormatSymbols.getInstance(l).getDecimalSeparator();
        return sep == ',' ? ',' : '.';                                     // only the comma: never a script's own sign
    }

    /**
     * A decimal typed by the user, with a point or a comma ("446.00625", "446,00625"). A comma counts as the decimal
     * sign only when it is the one separator in the text, so lists split on commas before this never reach it.
     * Only a sign, digits and that one separator: "1.234,5", "1,234.5", "12a", "12f", "1e3", "NaN" and "" throw
     * NumberFormatException (Double.parseDouble alone would take some of those), so nothing is half read.
     */
    public static double parseDecimal(String text) {
        String t = text == null ? "" : text.trim();
        if (t.indexOf('.') < 0 && t.indexOf(',') >= 0 && t.indexOf(',') == t.lastIndexOf(',')) t = t.replace(',', '.');
        if (!DECIMAL.matcher(t).matches()) throw new NumberFormatException("not a number: \"" + t + "\"");
        return Double.parseDouble(t);
    }
    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    /**
     * A date/time format for the screen. English keeps {@code english} exactly as before (in {@code Locale.US}, or in
     * the app's own English locale when {@code usEnglish} is false); every other language gets its own order and names
     * for {@code skeleton} (an ICU skeleton such as "MMMdHHmm"), e.g. "28. Okt., 22:44" in German.
     */
    public static java.text.SimpleDateFormat dates(String english, String skeleton) { return dates(english, skeleton, true); }
    public static java.text.SimpleDateFormat dates(String english, String skeleton, boolean usEnglish) {
        Locale l = locale();
        if ("en".equals(l.getLanguage()) || app == null) return new java.text.SimpleDateFormat(english, usEnglish ? Locale.US : l);
        String p = null;
        try { p = android.text.format.DateFormat.getBestDateTimePattern(l, skeleton); } catch (Throwable ignored) { }
        return new java.text.SimpleDateFormat(p == null || p.isEmpty() ? english : p, l);
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
