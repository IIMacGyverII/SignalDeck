/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Message forms for emergency and traffic nets: the ICS-213 General Message and the ARRL Radiogram. A form travels
 * as text parts that fit an APRS message (67 characters) or a DMR text: {@code "ICS213 K7 1/3 TO=..|FR=..|"} — the
 * kind, a two-letter form id, the part number and count, then the fields as KEY=value separated by "|" (a "|" or "\"
 * in a value is escaped with "\"). The receiving SignalDeck collects the parts of each sender's form id and rebuilds
 * it once all have arrived, in any order. Pure: host-tested.
 */
public final class Forms {
    private Forms() { }
    public static final String ICS213 = "ICS213", RADIOGRAM = "RADIOGRAM";

    /** Field keys and labels, in form order. */
    public static String[][] fields(String kind) {
        if (RADIOGRAM.equals(kind)) return new String[][] {
            { "NR", "Number" }, { "PR", "Precedence (R routine, P priority, O immediate, EMERGENCY)" }, { "HX", "Handling (HX…, optional)" }, { "SO", "Station of origin" },   // i18n: form field names, sent in the form text
            { "CK", "Check (word count of the text)" }, { "PL", "Place of origin" }, { "TF", "Time filed" }, { "DT", "Date" },   // i18n: form field names, sent in the form text
            { "TO", "To: name and address" }, { "PH", "Phone" }, { "MS", "Text" }, { "SG", "Signature" } };   // i18n: form field names, sent in the form text
        return new String[][] {
            { "TO", "To (name and position)" }, { "FR", "From (name and position)" }, { "SU", "Subject" }, { "DT", "Date" }, { "TM", "Time" },   // i18n: form field names, sent in the form text
            { "MS", "Message" }, { "AP", "Approved by (name and position)" } };   // i18n: form field names, sent in the form text
    }
    public static String title(String kind) { return RADIOGRAM.equals(kind) ? "ARRL Radiogram" : "ICS-213 General Message"; }   // i18n: form names (proper names, sent in the form text)

    private static final int[] RADIOGRAM_LABELS = { R.string.modes_forms_rg_nr, R.string.modes_forms_rg_pr, R.string.modes_forms_rg_hx, R.string.modes_forms_rg_so,
        R.string.modes_forms_rg_ck, R.string.modes_forms_rg_pl, R.string.modes_forms_rg_tf, R.string.modes_forms_rg_dt,
        R.string.modes_forms_rg_to, R.string.modes_forms_rg_ph, R.string.modes_forms_rg_ms, R.string.modes_forms_rg_sg };
    private static final int[] ICS213_LABELS = { R.string.modes_forms_ics_to, R.string.modes_forms_ics_fr, R.string.modes_forms_ics_su, R.string.modes_forms_ics_dt, R.string.modes_forms_ics_tm,
        R.string.modes_forms_ics_ms, R.string.modes_forms_ics_ap };
    /**
     * Field {@code i} of {@link #fields}'s label in the app's language, for the form editor and viewer. The text sent
     * and read on the air ({@link #text}) keeps the English labels: the form is the same for whoever receives it.
     */
    public static String label(String kind, int i) { return Txt.s((RADIOGRAM.equals(kind) ? RADIOGRAM_LABELS : ICS213_LABELS)[i]); }

    public static final class Form { public String kind = ICS213, id = ""; public final Map<String, String> f = new LinkedHashMap<>(); }

    static String esc(String v) { return v == null ? "" : v.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " "); }
    /** The fields as one string. */
    public static String encode(Form form) {
        StringBuilder b = new StringBuilder();
        for (String[] k : fields(form.kind)) { String v = form.f.get(k[0]); if (v == null || v.trim().isEmpty()) continue; b.append(b.length() > 0 ? "|" : "").append(k[0]).append('=').append(esc(v.trim())); }
        return b.toString();
    }
    public static Map<String, String> decode(String payload) {
        Map<String, String> out = new LinkedHashMap<>(); StringBuilder cur = new StringBuilder(); boolean e = false;
        List<String> items = new ArrayList<>();
        for (char ch : payload.toCharArray()) { if (e) { cur.append(ch); e = false; } else if (ch == '\\') e = true; else if (ch == '|') { items.add(cur.toString()); cur.setLength(0); } else cur.append(ch); }
        items.add(cur.toString());
        for (String it : items) { int q = it.indexOf('='); if (q > 0) out.put(it.substring(0, q).trim().toUpperCase(Locale.US), it.substring(q + 1)); }
        return out;
    }
    public static String newId(Random r) { String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; return "" + a.charAt(r.nextInt(a.length())) + a.charAt(r.nextInt(a.length())); }
    static String wireKind(String kind) { return RADIOGRAM.equals(kind) ? "RADIOGRAM" : "ICS213"; }

    /** The parts to send, each at most {@code max} characters (APRS 67). Escapes are never split. */
    public static List<String> split(Form form, int max) {
        String payload = encode(form), head = wireKind(form.kind) + " " + form.id + " ";
        int room = max - head.length() - 6;                                   // "nn/nn "
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < payload.length()) { int j = Math.min(payload.length(), i + room); if (j < payload.length() && payload.charAt(j - 1) == '\\') j--; while (j < payload.length() && j > i + 1 && payload.charAt(j - 1) == ' ') j--; chunks.add(payload.substring(i, j)); i = j; }
        if (chunks.isEmpty()) chunks.add("");
        List<String> out = new ArrayList<>();
        for (int k = 0; k < chunks.size(); k++) out.add(head + (k + 1) + "/" + chunks.size() + " " + chunks.get(k));
        return out;
    }

    /** A Winlink message for this form: subject and body (the form to read, then its one-line code for SignalDeck). */
    public static String[] winlink(Form form) {
        String su = form.f.get("SU"), nr = form.f.get("NR");
        String subject = RADIOGRAM.equals(form.kind) ? "Radiogram" + (nr == null || nr.trim().isEmpty() ? "" : " " + nr.trim()) + (form.f.get("MS") == null ? "" : ": " + first(form.f.get("MS"), 60))   // i18n: Winlink message
                : "ICS-213: " + (su == null || su.trim().isEmpty() ? "General Message" : su.trim());   // i18n: Winlink message
        String body = text(form) + "\n\n--\nSignalDeck form (SignalDeck puts it back in Message forms):\n" + split(form, 100_000).get(0) + "\n";   // i18n: Winlink message
        return new String[] { subject, body };
    }
    private static String first(String s, int n) { s = s.trim().replace('\n', ' '); return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }     // ASCII: Winlink subjects are ISO-8859-1
    /** The form code line in a Winlink message body, or null. */
    public static String winlinkPart(String body) {
        if (body == null) return null;
        for (String line : body.split("\r?\n")) { String[] p = part(line); if (p != null && "1".equals(p[2]) && "1".equals(p[3])) return line.trim(); }
        return null;
    }

    /** Does this text look like a form part? Returns {kind, id, part, count, chunk} or null. */
    public static String[] part(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(ICS213|RADIOGRAM) ([A-Z0-9]{2}) (\\d{1,2})/(\\d{1,2}) (.*)$", java.util.regex.Pattern.DOTALL).matcher(text.trim());
        if (!m.matches()) return null;
        return new String[] { m.group(1).equals("RADIOGRAM") ? RADIOGRAM : ICS213, m.group(2), m.group(3), m.group(4), m.group(5) };
    }

    /** Collects parts per sender and form id; {@link #add} returns the rebuilt form once every part is in. */
    public static final class Assembler {
        private final Map<String, String[]> parts = new HashMap<>(); private final Map<String, Long> seen = new HashMap<>();
        public synchronized Form add(String from, String text, long now) {
            String[] p = part(text); if (p == null) return null;
            int n = Integer.parseInt(p[2]), count = Integer.parseInt(p[3]); if (n < 1 || count < 1 || n > count || count > 40) return null;
            for (java.util.Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) { Map.Entry<String, Long> e = it.next(); if (now - e.getValue() > 6 * 3_600_000L) { parts.remove(e.getKey()); it.remove(); } }
            String key = from.toUpperCase(Locale.US) + "|" + p[0] + "|" + p[1];
            String[] got = parts.get(key); if (got == null || got.length != count) { got = new String[count]; parts.put(key, got); }
            got[n - 1] = p[4]; seen.put(key, now);
            StringBuilder all = new StringBuilder(); for (String g : got) { if (g == null) return null; all.append(g); }
            parts.remove(key); seen.remove(key);
            Form f = new Form(); f.kind = p[0]; f.id = p[1]; f.f.putAll(decode(all.toString()));
            return f;
        }
        /** How many parts of how many have arrived for the sender's newest unfinished form (for "1 of 3 parts received"). */
        public synchronized int[] progress(String from, String text) {
            String[] p = part(text); if (p == null) return null; String[] got = parts.get(from.toUpperCase(Locale.US) + "|" + p[0] + "|" + p[1]);
            if (got == null) return null; int k = 0; for (String g : got) if (g != null) k++; return new int[] { k, got.length };
        }
    }

    /** The form laid out for reading over the air or sharing. */
    public static String text(Form form) {
        StringBuilder b = new StringBuilder(title(form.kind).toUpperCase(Locale.US)).append('\n');
        for (String[] k : fields(form.kind)) { String v = form.f.get(k[0]); if (v == null || v.trim().isEmpty()) continue; String lab = k[1].replaceAll(" \\(.*\\)", ""); b.append(lab.toUpperCase(Locale.US)).append(": ").append(v.trim()).append('\n'); }
        return b.toString().trim();
    }
    /** Radiogram check: the words of the text (ARRL counts each group separated by spaces). */
    public static int words(String text) { if (text == null) return 0; String t = text.trim(); return t.isEmpty() ? 0 : t.split("\\s+").length; }
}
