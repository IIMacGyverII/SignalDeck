/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.i18n;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A sentence to show later, kept as its string resource and arguments rather than as finished text: history lines,
 * Last heard events, anything stored and shown again after the language may have changed.
 * <ul>
 *   <li>{@link #english()} is what goes in the history table's {@code text} column. It stays English, so everything
 *       that reads or matches that text (Last heard's explanations, the packet purge, the activity statistics,
 *       net finding, logs) keeps working unchanged.</li>
 *   <li>{@link #key()} and {@link #args()} go in {@code msgKey}/{@code msgArgs}; {@link #display} renders them in the
 *       app's language when the line is shown, falling back to the stored English for old rows (no key), a string
 *       since removed, or arguments that no longer fit.</li>
 *   <li>An argument may be a String, a number, or another Msg (a reason inside a sentence), which is rendered in the
 *       same language as the sentence.</li>
 * </ul>
 * <p>i18n: stays English (exception messages and preference keys only; the user-facing text is in the resources).
 */
public final class Msg {
    private final String name; private final boolean plural; private final int count; private final Object[] args;

    private Msg(String name, boolean plural, int count, Object[] args) { this.name = name; this.plural = plural; this.count = count; this.args = args; }

    public static Msg of(int stringId, Object... args) { return new Msg(Txt.name(stringId), false, 0, clean(args)); }
    public static Msg plural(int pluralsId, int n, Object... args) { return new Msg(Txt.name(pluralsId), true, n, clean(args)); }

    /** No control characters on screen (received callsigns and packet text can carry them; they also break screen readers). */
    private static Object[] clean(Object[] args) {
        Object[] out = args.clone();
        for (int i = 0; i < out.length; i++) if (out[i] instanceof String) out[i] = ((String) out[i]).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "·");
        return out;
    }

    public String english() { return render(true); }
    public String text() { return render(false); }
    @Override public String toString() { return text(); }

    private String render(boolean en) {
        Object[] a = new Object[args.length];
        for (int i = 0; i < args.length; i++) a[i] = args[i] instanceof Msg ? ((Msg) args[i]).render(en) : args[i];
        int id = Txt.id(name, plural);
        if (id == 0) throw new IllegalStateException("no " + (plural ? "plurals" : "string") + " named " + name);
        if (plural) return en ? Txt.enP(id, count, a) : Txt.p(id, count, a);
        return en ? Txt.en(id, a) : Txt.s(id, a);
    }

    /** The resource name, for the {@code msgKey} column. */
    public String key() { return name; }

    /** The arguments and plural count as JSON, for the {@code msgArgs} column. */
    public String args() { try { return toJson().toString(); } catch (JSONException e) { throw new IllegalStateException(e); } }

    private JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject(); o.put("k", name);
        if (plural) o.put("n", count);
        JSONArray a = new JSONArray();
        for (Object x : args) a.put(x instanceof Msg ? ((Msg) x).toJson() : x instanceof Number || x instanceof Boolean ? x : String.valueOf(x));
        o.put("a", a);
        return o;
    }

    private static Msg fromJson(JSONObject o) throws JSONException {
        JSONArray a = o.getJSONArray("a"); Object[] args = new Object[a.length()];
        for (int i = 0; i < a.length(); i++) { Object x = a.get(i); args[i] = x instanceof JSONObject ? fromJson((JSONObject) x) : x; }
        return new Msg(o.getString("k"), o.has("n"), o.optInt("n", 0), args);
    }

    /** Rebuild from the two columns; null when the row has none (stored before this existed) or they don't parse. */
    public static Msg from(String key, String args) {
        if (key == null || key.isEmpty() || args == null) return null;
        try { Msg m = fromJson(new JSONObject(args)); return key.equals(m.name) ? m : null; } catch (JSONException e) { return null; }
    }

    /** What a stored line shows: the message in the app's language, or the stored English when that can't be done. */
    public static String display(String storedText, String key, String args) {
        Msg m = from(key, args);
        if (m == null) return storedText;
        try { return m.text(); } catch (RuntimeException e) { return storedText; }   // a string since removed, or arguments that no longer fit
    }
}
