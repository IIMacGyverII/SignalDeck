/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.winlink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Winlink gateway list as winlink.org (RMS Channels) exports it as CSV: Timestamp, Callsign, BaseCallsign,
 * GridSquare, Frequency ("144.9300 MHz" or "3,585.000 KHz"), Mode ("Packet 1200", "Pactor 3,4", ...), Hours, Sysop,
 * QTH. SignalDeck can use the 1200-baud packet gateways on frequencies this radio tunes (136–174, 400–480 MHz);
 * distances come from each gateway's grid square and the phone's position, on the phone only.
 */
public final class RmsList {
    private RmsList() { }

    public static final class Gateway {
        public String call = "", grid = "", mode = "", hours = "", qth = ""; public int hz; public double km = -1, bearing;
        public JSONObject json() throws Exception { return new JSONObject().put("call", call).put("grid", grid).put("mode", mode).put("hours", hours).put("qth", qth).put("hz", hz); }
        public static Gateway of(JSONObject o) { Gateway g = new Gateway(); g.call = o.optString("call"); g.grid = o.optString("grid"); g.mode = o.optString("mode"); g.hours = o.optString("hours"); g.qth = o.optString("qth"); g.hz = o.optInt("hz"); return g; }
    }
    public static final class Result { public final List<Gateway> usable = new ArrayList<>(); public int rows, hf, otherBaud, outOfRange; }

    /** Parse the CSV; throws IllegalArgumentException when it is not a Winlink gateway list. */
    public static Result parse(String text) {
        List<List<String>> rows = csv(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("The file is empty");
        List<String> head = rows.get(0); int cCall = idx(head, "Callsign"), cGrid = idx(head, "GridSquare"), cFreq = idx(head, "Frequency"), cMode = idx(head, "Mode"), cHours = idx(head, "Hours"), cQth = idx(head, "QTH");   // i18n: CSV header names
        if (cCall < 0 || cFreq < 0 || cMode < 0) throw new IllegalArgumentException("Not a Winlink gateway list (expected the CSV from winlink.org, RMS Channels)");
        Result r = new Result();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i); if (row.size() <= Math.max(cCall, Math.max(cFreq, cMode))) continue;
            r.rows++;
            long hz = freqHz(row.get(cFreq)); String mode = row.get(cMode).trim();
            if (hz <= 0) continue;
            if (hz < 30_000_000L) { r.hf++; continue; }
            if (!mode.toLowerCase(Locale.US).startsWith("packet") || baud(mode) != 1200) { r.otherBaud++; continue; }
            if (!com.macdmr.radio.DeviceProfile.inBands(hz)) { r.outOfRange++; continue; }
            Gateway g = new Gateway(); g.call = row.get(cCall).trim().toUpperCase(Locale.US); g.hz = (int) hz; g.mode = mode;
            g.grid = cGrid >= 0 && cGrid < row.size() ? row.get(cGrid).trim() : ""; g.hours = cHours >= 0 && cHours < row.size() ? row.get(cHours).trim() : ""; g.qth = cQth >= 0 && cQth < row.size() ? row.get(cQth).trim() : "";
            if (g.call.matches("[A-Z0-9]{3,6}(-[0-9]{1,2})?")) r.usable.add(g);
        }
        return r;
    }

    /** "144.9300 MHz" → 144930000, "3,585.000 KHz" → 3585000; 0 if unreadable. */
    static long freqHz(String s) {
        String t = s.trim().toUpperCase(Locale.US).replace(",", "");
        double mult = t.endsWith("MHZ") ? 1e6 : t.endsWith("KHZ") ? 1e3 : t.endsWith("HZ") ? 1 : 1e3;
        try { return Math.round(Double.parseDouble(t.replaceAll("[^0-9.]", "")) * mult); } catch (NumberFormatException e) { return 0; }
    }
    static int baud(String mode) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,5})").matcher(mode); return m.find() ? Integer.parseInt(m.group(1)) : 0; }

    /** Distance and bearing from (lat, lon), nearest first; gateways without a grid square go last. */
    public static void rank(List<Gateway> l, double lat, double lon) {
        for (Gateway g : l) {
            double[] c = g.grid.length() >= 4 ? com.macdmr.radio.ham.Maidenhead.centre(g.grid) : null;
            if (c == null) { g.km = -1; continue; }
            g.km = com.macdmr.radio.ham.Maidenhead.km(lat, lon, c[0], c[1]); g.bearing = com.macdmr.radio.ham.Maidenhead.bearing(lat, lon, c[0], c[1]);
        }
        l.sort((a, b) -> a.km < 0 && b.km < 0 ? a.call.compareTo(b.call) : a.km < 0 ? 1 : b.km < 0 ? -1 : Double.compare(a.km, b.km));
    }

    public static String toJson(List<Gateway> l) { try { JSONArray a = new JSONArray(); for (Gateway g : l) a.put(g.json()); return a.toString(); } catch (Exception e) { return "[]"; } }
    public static List<Gateway> fromJson(String s) { List<Gateway> l = new ArrayList<>(); try { JSONArray a = new JSONArray(s); for (int i = 0; i < a.length(); i++) l.add(Gateway.of(a.getJSONObject(i))); } catch (Exception ignored) { } return l; }

    static int idx(List<String> head, String name) { for (int i = 0; i < head.size(); i++) if (head.get(i).trim().replace("﻿", "").equalsIgnoreCase(name)) return i; return -1; }

    /** RFC 4180-ish: quoted fields with commas and doubled quotes, CRLF or LF. */
    static List<List<String>> csv(String text) {
        List<List<String>> out = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder f = new StringBuilder(); boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (q) { if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false; } else f.append(ch); }   // i18n: CSV parsing
            else if (ch == '"') q = true;
            else if (ch == ',') { row.add(f.toString()); f.setLength(0); }
            else if (ch == '\n' || ch == '\r') { if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; row.add(f.toString()); f.setLength(0); if (!(row.size() == 1 && row.get(0).isEmpty())) out.add(row); row = new ArrayList<>(); }
            else f.append(ch);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); out.add(row); }
        return out;
    }
}
