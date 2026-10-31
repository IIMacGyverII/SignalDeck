/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (serial and module diagnostics: log lines, shell commands and device-report text)

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Radio frames of the Agold phones (Unihertz Atom XL, Ulefone Armor 3WT) as their stock intercom app logs them, for the
 * device report: {@code 7E 96 69 | route | cmd | rw | len (2, BE) | body | checksum (2, BE) | 81}, route 01 = the DMR
 * module, rw 00 = a reply. The app logs them as spaced hex ("7E 96 69 01 22 ...") or as one hex run ("7E966901..."), and
 * a long frame is often cut short by the log. Two jobs: take the owner's details out (radio ID, call number, encryption
 * key, caller and text bodies) before the log goes into a report that may be posted publicly, and tell from the log
 * whether the module answered the stock app, so the summary does not say "no answer" when SignalDeck's own probe could
 * not reach the port.
 */
public final class AgoldFrames {
    private AgoldFrames() { }

    private static final Pattern START = Pattern.compile("(?i)(?:(?<![0-9A-F])|(?<=81))7E( ?)96\\1(?:69)");   // a frame may follow another one's end byte in a hex run
    private static final int TO_END = Integer.MAX_VALUE;

    /** One frame as found in a log line: where its hex text is, and the bytes the log kept. */
    static final class Frame {
        final int start, end; final boolean spaced; final int[] bytes; final int declaredLen;
        Frame(int start, int end, boolean spaced, int[] bytes) {
            this.start = start; this.end = end; this.spaced = spaced; this.bytes = bytes;
            declaredLen = bytes.length >= 8 ? (bytes[6] << 8) | bytes[7] : -1;
        }
        int route() { return bytes.length > 3 ? bytes[3] : -1; }
        int cmd() { return bytes.length > 4 ? bytes[4] : -1; }
        boolean reply() { return bytes.length > 5 && bytes[5] == 0; }
        boolean complete() { return declaredLen >= 0 && bytes.length >= 8 + declaredLen + 3; }
        /** The body bytes the log kept (may be fewer than declared). */
        int[] body() {
            if (bytes.length <= 8 || declaredLen <= 0) return new int[0];
            int[] b = new int[Math.min(bytes.length - 8, declaredLen)]; System.arraycopy(bytes, 8, b, 0, b.length); return b;
        }
    }

    /**
     * The 16-bit one's-complement sum the stock app puts after the body (IComUtils.checkSum): big-endian words over
     * route … body, an odd last byte in the high half, folded and inverted.
     */
    static int checksum(int[] b, int from, int to) {
        int s = 0, i = from;
        for (; i + 1 < to; i += 2) s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        if (i < to) s += (b[i] & 0xFF) << 8;
        while ((s >> 16) > 0) s = (s & 0xFFFF) + (s >> 16);
        return s ^ 0xFFFF;
    }

    /** A request frame as the stock app builds it (IComUtils.makePackage: rw 01, length big-endian). */
    static byte[] request(int route, int cmd, int... data) {
        int[] f = new int[8 + data.length + 3];
        f[0] = 0x7E; f[1] = 0x96; f[2] = 0x69; f[3] = route; f[4] = cmd; f[5] = 1; f[6] = data.length >> 8; f[7] = data.length & 0xFF;
        System.arraycopy(data, 0, f, 8, data.length);
        int c = checksum(f, 3, 8 + data.length);
        f[8 + data.length] = c >> 8; f[9 + data.length] = c & 0xFF; f[10 + data.length] = 0x81;
        byte[] out = new byte[f.length]; for (int i = 0; i < f.length; i++) out[i] = (byte) f[i];
        return out;
    }

    /** True when a complete frame's own checksum matches its route … body. */
    static boolean checksumOk(Frame f) {
        if (!f.complete()) return false;
        int end = 8 + f.declaredLen;
        for (int i = 0; i < end + 2; i++) if (f.bytes[i] < 0) return false;          // a blanked byte: cannot tell
        return checksum(f.bytes, 3, end) == ((f.bytes[end] << 8) | f.bytes[end + 1]);
    }

    /** The body of a complete reply as text (the version replies are ASCII, NUL-padded). */
    static String bodyText(Frame f) {
        StringBuilder s = new StringBuilder();
        for (int b : f.body()) if (b >= 0x20 && b < 0x7F) s.append((char) b);
        return s.toString();
    }

    /** Every frame in one line of log text. */
    static List<Frame> find(String line) {
        List<Frame> out = new ArrayList<>();
        if (line == null) return out;
        Matcher m = START.matcher(line);
        int from = 0;
        while (from < line.length() && m.find(from)) {
            boolean spaced = !m.group(1).isEmpty();
            int p = m.start(), max = -1; List<Integer> b = new ArrayList<>();   // max: the whole frame's length once the header is in
            while (p + 2 <= line.length() && isByte(line, p)) {
                b.add(line.charAt(p) == '#' ? -1 : Integer.parseInt(line.substring(p, p + 2), 16)); p += 2;   // ## = a byte already blanked
                if (b.size() == 8) max = 8 + ((b.get(6) << 8) | b.get(7)) + 3;
                if (max > 0 && b.size() >= max) break;
                if (spaced) {
                    if (p + 2 < line.length() && line.charAt(p) == ' ' && isByte(line, p + 1)) p++;
                    else break;
                }
            }
            int[] bytes = new int[b.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(i);
            out.add(new Frame(m.start(), p, spaced, bytes));
            from = Math.max(p, m.start() + 1);
        }
        return out;
    }

    /** Body byte ranges to blank, {from, to} inclusive (to = TO_END: the rest); null = nothing private. */
    static int[][] privateRanges(int cmd, int declaredLen) {
        switch (cmd) {
            case 0x22: return declaredLen == 163                                 // DMR09 digital channel: own ID, TX call number, encryption key
                    ? new int[][] { { 8, 11 }, { 140, 143 }, { 151, 158 } } : new int[][] { { 8, TO_END } };
            case 0x36: return declaredLen == 156                                 // older set's digital channel: own ID, call number, encryption key
                    ? new int[][] { { 9, 12 }, { 15, 18 }, { 20, 27 } } : new int[][] { { 9, TO_END } };
            case 0x29: case 0x2B: case 0x2C: case 0x2D: case 0x10: case 0x11: case 0x07:   // encryption, caller info, texts; older set's call info, message content and text sent
                return new int[][] { { 0, TO_END } };
            default: return null;
        }
    }

    /**
     * The line with the private bytes of every module frame shown as {@code ##}, and that frame's checksum too (with the
     * rest of the frame known, it would narrow the ID down). Other frames and the text around them are unchanged.
     */
    public static String redact(String line) {
        List<Frame> fs = find(line);
        if (fs.isEmpty()) return line;
        StringBuilder s = new StringBuilder(line);
        for (Frame f : fs) {
            if (f.route() != 1 || f.bytes.length <= 8) continue;
            int[][] rs = privateRanges(f.cmd(), f.declaredLen);
            if (rs == null) continue;
            boolean[] blank = new boolean[f.bytes.length];
            for (int[] r : rs) for (int i = 8 + r[0]; i < f.bytes.length && (r[1] == TO_END || i <= 8 + r[1]); i++) blank[i] = true;
            for (int i = 8 + Math.max(0, f.declaredLen); i < 8 + Math.max(0, f.declaredLen) + 2 && i < f.bytes.length; i++) blank[i] = true;
            for (int i = 0; i < f.bytes.length; i++) if (blank[i]) { int at = f.start + i * (f.spaced ? 3 : 2); s.setCharAt(at, '#'); s.setCharAt(at + 1, '#'); }
        }
        return s.toString();
    }

    /** Where the frames are in a line, {start, end}, so other masks (long numbers, callsigns) can leave their hex alone. */
    static List<int[]> spans(String line) {
        List<int[]> out = new ArrayList<>();
        for (Frame f : find(line)) out.add(new int[] { f.start, f.end });
        return out;
    }

    /**
     * What the module said to the stock app, from that app's log: null when the log holds no module reply, else one line
     * such as "the module answered the stock app 6 times (Agold frames in its log; version DMR006.U4A.V082; 0x22 0x2E accepted)".
     */
    public static String answers(String log) {
        if (log == null) return null;
        int replies = 0; String version = null; Map<Integer, Integer> results = new LinkedHashMap<>();
        for (String line : log.split("\n"))
            for (Frame f : find(line)) {
                if (f.route() != 1 || !f.reply() || f.bytes.length <= 8) continue;
                replies++;
                int[] b = f.body();
                if (f.cmd() == 0x34 || f.cmd() == 0x25) {
                    StringBuilder v = new StringBuilder();
                    for (int x : b) { if (x >= 0x20 && x < 0x7F) v.append((char) x); else if (v.length() > 0) break; }
                    if (v.length() >= 3) version = v.toString().trim();
                } else if (b.length >= 1 && b[0] >= 0 && !results.containsKey(f.cmd())) results.put(f.cmd(), b[0]);
            }
        if (replies == 0) return null;
        StringBuilder s = new StringBuilder("the module answered the stock app ").append(replies).append(replies == 1 ? " time" : " times").append(" (Agold frames in its log");
        if (version != null) s.append("; version ").append(version);
        StringBuilder ok = new StringBuilder(), no = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : results.entrySet()) (e.getValue() == 0 ? ok : no).append(String.format(Locale.US, " 0x%02X", e.getKey()));
        if (ok.length() > 0) s.append(";").append(ok).append(" accepted");
        if (no.length() > 0) s.append(";").append(no).append(" refused");
        return s.append(')').toString();
    }

    private static boolean isByte(String s, int p) { char a = s.charAt(p), b = s.charAt(p + 1); return (isHex(a) && isHex(b)) || (a == '#' && b == '#'); }
    private static boolean isHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
}
