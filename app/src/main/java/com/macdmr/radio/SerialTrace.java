/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what sdtrace (the report's serial sniffer, src/main/cpp/sdtrace) printed while the stock walkie-talkie app
 * started: which port it opened, the line settings it set, and the bytes both ways. Gives the report a short decoded
 * view (protocol family, line, the command sequence with answers) and a copy with radio IDs blanked for sharing.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class SerialTrace {
    private SerialTrace() { }

    /** "1173.6 29166 W fd 68 14: 41 54 … |AT+DMOCONNECT.|" */
    static final Pattern DATA = Pattern.compile("^(\\d+(?:\\.\\d+)?) (\\d+) ([WR]) fd (\\d+) (\\d+):((?: [0-9a-f]{2})+) \\|(.*)\\|(.*)$");
    static final Pattern OPEN = Pattern.compile("^(\\d+(?:\\.\\d+)?) \\d+ (?:open|dup) (\\S+) -> fd (\\d+)");
    static final Pattern SET = Pattern.compile("^(\\d+(?:\\.\\d+)?) \\d+ set fd (\\d+) (\\S+) (\\S+) (raw|icanon)");

    static final class Io { double t; boolean write; byte[] b; }

    static List<Io> data(String trace) {
        List<Io> out = new ArrayList<>();
        for (String l : (trace == null ? "" : trace).split("\n")) {
            Matcher m = DATA.matcher(l.trim()); if (!m.matches()) continue;
            Io io = new Io(); io.t = Double.parseDouble(m.group(1)); io.write = m.group(3).equals("W");
            String[] h = m.group(6).trim().split(" "); io.b = new byte[h.length];
            for (int i = 0; i < h.length; i++) io.b[i] = (byte) Integer.parseInt(h[i], 16);
            out.add(io);
        }
        return out;
    }

    static String text(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(x >= 0x20 && x < 0x7f ? (char) x : x == '\r' ? "\\r" : x == '\n' ? "\\n" : "."); return s.toString(); }

    /** The protocol family the app's writes show: SA818-class AT text, the DMR module's 68 … 10 frames, the Agold envelope, or unknown. */
    static String family(List<Io> io) {
        int at = 0, dmr = 0, agold = 0, writes = 0;
        for (Io x : io) {
            if (!x.write) continue; writes++;
            String t = new String(x.b, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (t.startsWith("AT+DMO")) at++;
            else if ((x.b[0] & 0xFF) == 0x68 && (x.b[x.b.length - 1] & 0xFF) == 0x10) dmr++;
            else if (x.b.length > 3 && (x.b[0] & 0xFF) == 0x7E && (x.b[1] & 0xFF) == 0x96 && (x.b[2] & 0xFF) == 0x69) agold++;
        }
        if (writes == 0) return "none: the app wrote nothing to a serial port while it was watched";
        if (at * 2 >= writes) return "SA818-class AT commands (" + at + " of " + writes + " writes start AT+DMO)";
        if (dmr * 2 >= writes) return "the DMR module's 68 … 10 frames (" + dmr + " of " + writes + " writes)";
        if (agold * 2 >= writes) return "the Agold 7E 96 69 envelope (" + agold + " of " + writes + " writes)";
        return "unknown (" + writes + " writes; see the bytes below)";
    }

    /** The decoded view for report.txt. */
    public static String summary(String trace) {
        StringBuilder r = new StringBuilder();
        String port = null; double t0 = -1;
        for (String l : (trace == null ? "" : trace).split("\n")) {
            Matcher m = OPEN.matcher(l.trim());
            if (m.find() && port == null) { port = m.group(2); t0 = Double.parseDouble(m.group(1)); }
            Matcher s = SET.matcher(l.trim());
            if (s.find()) r.append("  line set by the app: ").append(s.group(3)).append(' ').append(s.group(4)).append(' ').append(s.group(5)).append('\n');
        }
        List<Io> io = data(trace);
        if (port == null && io.isEmpty()) return "  no serial port opened or used while it was watched\n";
        if (port != null) r.insert(0, "  port: " + port + "\n");
        if (t0 < 0 && !io.isEmpty()) t0 = io.get(0).t;
        int w = 0, rd = 0, wb = 0, rb = 0;
        for (Io x : io) { if (x.write) { w++; wb += x.b.length; } else { rd++; rb += x.b.length; } }
        r.append("  protocol: ").append(family(io)).append('\n');
        r.append(String.format(Locale.US, "  traffic: %d writes (%d bytes), %d reads (%d bytes)\n", w, wb, rd, rb));
        if (family(io).startsWith("SA818")) r.append(atSequence(io, t0));
        return r.toString();
    }

    private static final Pattern AT_CMD = Pattern.compile("^AT\\+(DMO[A-Z]+)"), AT_REPLY = Pattern.compile("\\+(DMO[A-Z]+):([^\\r\\n]*)");

    /** Each AT command the app sent, its line end, its time from the open, and the module's answer to it. */
    static String atSequence(List<Io> io, double t0) {
        Map<String, String> replies = new LinkedHashMap<>();
        StringBuilder seq = new StringBuilder(); String ends = null;
        List<String[]> cmds = new ArrayList<>();
        for (Io x : io) {
            String t = new String(x.b, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (x.write) {
                Matcher m = AT_CMD.matcher(t);
                if (!m.find()) continue;
                String end = t.endsWith("\r\n") ? "\\r\\n" : t.endsWith("\r") ? "\\r" : t.endsWith("\n") ? "\\n" : "none";
                ends = ends == null ? end : ends.equals(end) ? ends : "mixed";
                cmds.add(new String[] { String.format(Locale.US, "%+6.0f ms", x.t - t0), t.trim(), m.group(1) });
            } else {
                Matcher m = AT_REPLY.matcher(t);
                while (m.find()) if (!replies.containsKey(m.group(1))) replies.put(m.group(1), "+" + m.group(1) + ":" + m.group(2));
            }
        }
        seq.append("  commands end with: ").append(ends == null ? "-" : ends).append(" (one write per command)\n");
        seq.append("  sequence:\n");
        for (String[] c : cmds) seq.append("    ").append(c[0]).append("  ").append(c[1]).append("  -> ").append(replies.getOrDefault(c[2], "(no answer seen)")).append('\n');
        return seq.toString();
    }

    /**
     * The trace with radio IDs blanked: the bodies of DMR (68 … 10) and Agold (7E 96 69) frames carry the phone's own
     * radio ID, contacts and texts, so only their first 8 bytes (header, command, length) and last byte stay. AT text has
     * no identifiers and stays whole, as do unknown bytes (they are what a new protocol is learned from).
     */
    public static String redact(String trace) {
        StringBuilder out = new StringBuilder();
        for (String l : (trace == null ? "" : trace).split("\n", -1)) {
            Matcher m = DATA.matcher(l.trim());
            if (!m.matches()) { out.append(l).append('\n'); continue; }
            String[] h = m.group(6).trim().split(" ");
            boolean frame = h[0].equals("68") || (h.length > 3 && h[0].equals("7e") && h[1].equals("96") && h[2].equals("69"));
            if (!frame || h.length <= 9) { out.append(l).append('\n'); continue; }
            StringBuilder hex = new StringBuilder(), txt = new StringBuilder();
            for (int i = 0; i < h.length; i++) {
                boolean keep = i < 8 || i == h.length - 1;
                hex.append(' ').append(keep ? h[i] : "xx");
                int b = Integer.parseInt(h[i], 16);
                txt.append(keep && b >= 0x20 && b < 0x7f ? (char) b : '.');
            }
            out.append(m.group(1)).append(' ').append(m.group(2)).append(' ').append(m.group(3)).append(" fd ").append(m.group(4)).append(' ').append(m.group(5)).append(':')
                    .append(hex).append(" |").append(txt).append('|').append(m.group(8)).append(" (frame body blanked)\n");
        }
        return out.toString().replaceAll("\n+$", "\n");
    }
}
