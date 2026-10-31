/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * report.json beside report.txt (W6 plan item 9): the same facts as data, so two reports can be compared on a host
 * (scripts/report_diff.py). Built from the finished report text only, like the draft profile; it carries nothing the
 * text does not (the text is already redacted), and the phone fields are the ones in the summary's first line.
 * i18n: stays English (developer report/bench text, plan §1)
 */
final class ReportJson {
    private ReportJson() { }

    static final int SCHEMA = 1;

    static JSONObject build(String report, Map<String, String> phone) throws JSONException { return build(report, phone, null); }

    /** With {@code build} (BuildIdentity.Info as JSON): which commit, repository and signing key made the app, and its profile flags. */
    static JSONObject build(String report, Map<String, String> phone, JSONObject build) throws JSONException {
        String r = report == null ? "" : report;
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA);
        if (build != null) o.put("build", build);
        Matcher h = Pattern.compile("==== SignalDeck device report (\\S+)\\s+([^\\n]*?) ====").matcher(r);
        if (h.find()) { o.put("signaldeck", h.group(1)); o.put("generated", h.group(2).trim()); }
        JSONObject p = new JSONObject();
        if (phone != null) for (Map.Entry<String, String> e : phone.entrySet()) p.put(e.getKey(), e.getValue());
        o.put("phone", p);

        JSONArray sections = new JSONArray();                                   // every section, in order, with its size
        Matcher s = Pattern.compile("(?m)^==== (.+?) ====$").matcher(r);
        int prev = -1; String prevTitle = null;
        while (true) {
            boolean more = s.find();
            if (prevTitle != null) sections.put(new JSONObject().put("title", prevTitle).put("lines", lines(r.substring(prev, more ? s.start() : r.length()))));
            if (!more) break;
            prevTitle = s.group(1); prev = s.end();
        }
        o.put("sections", sections);

        JSONObject draft = new JSONObject();
        for (DraftProfile.Field f : DraftProfile.fields(r))
            draft.put(f.name, new JSONObject().put("value", f.value).put("confidence", f.confidence).put("evidence", f.evidence));
        o.put("draft", draft);

        JSONArray summary = new JSONArray();
        String sum = DeviceReport.lastSection(r, "Summary");
        if (sum != null) {
            int cut = sum.indexOf("draft profile (");                         // the draft is already in "draft"
            for (String l : (cut >= 0 ? sum.substring(0, cut) : sum).split("\n")) if (!l.trim().isEmpty()) summary.put(l.trim());
        }
        o.put("summary", summary);
        return o;
    }

    private static int lines(String body) {
        int n = 0; for (String l : body.split("\n")) if (!l.trim().isEmpty()) n++;
        return n;
    }
}
