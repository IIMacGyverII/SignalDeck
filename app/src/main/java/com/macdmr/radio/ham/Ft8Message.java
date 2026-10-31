/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 *
 * Message packing follows ft8_lib by Karlis Goba (https://github.com/kgoba/ft8_lib), Copyright (c) 2018 Karlis Goba,
 * MIT License (see COPYRIGHT), which implements the WSJT-X 77-bit message formats.
 */
package com.macdmr.radio.ham;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FT8's 77-bit messages: standard (two calls + grid / report / RRR / RR73 / 73, i3 = 1 and 2), non-standard calls
 * (i3 = 4, one call as a 12-bit hash), free text (13 characters, i3.n3 = 0.0) and telemetry (0.5, shown as hex).
 */
public final class Ft8Message {
    private Ft8Message() { }

    static final long NTOKENS = 2063592L, MAX22 = 4194304L;
    static final int MAXGRID4 = 32400;

    /** Callsigns heard so far, by their 22-, 12- and 10-bit hashes, so hashed calls (shown as {@code <...>}) can be filled in. */
    public static final class Hashes {
        final Map<Integer, String> h22 = new HashMap<>(), h12 = new HashMap<>(), h10 = new HashMap<>();
        synchronized void save(String call) {
            int n22 = hash22(call); if (n22 < 0) return;
            h22.put(n22, call); h12.put(n22 >> 10, call); h10.put(n22 >> 12, call);
            if (h22.size() > 2000) { h22.clear(); h12.clear(); h10.clear(); }
        }
        synchronized String lookup(int bits, int hash) { return (bits == 22 ? h22 : bits == 12 ? h12 : h10).get(hash); }
    }

    // ---- character tables (ft8_lib text.c) ------------------------------------------------------------
    static final int FULL = 0, ALNUM_SPACE_SLASH = 1, ALNUM_SPACE = 2, LETTERS_SPACE = 3, ALNUM = 4, NUMERIC = 5;

    static char charn(int c, int table) {
        if (table != ALNUM && table != NUMERIC) { if (c == 0) return ' '; c -= 1; }
        if (table != LETTERS_SPACE) { if (c < 10) return (char) ('0' + c); c -= 10; }
        if (table != NUMERIC) { if (c < 26) return (char) ('A' + c); c -= 26; }
        if (table == FULL) { if (c < 5) return "+-./?".charAt(c); }
        else if (table == ALNUM_SPACE_SLASH) { if (c == 0) return '/'; }
        return '_';
    }

    static int nchar(char c, int table) {
        int n = 0;
        if (table != ALNUM && table != NUMERIC) { if (c == ' ') return n; n += 1; }
        if (table != LETTERS_SPACE) { if (c >= '0' && c <= '9') return n + (c - '0'); n += 10; }
        if (table != NUMERIC) { if (c >= 'A' && c <= 'Z') return n + (c - 'A'); n += 26; }
        if (table == FULL) { int i = "+-./?".indexOf(c); if (i >= 0) return n + i; }
        else if (table == ALNUM_SPACE_SLASH) { if (c == '/') return n; }
        return -1;
    }

    static int hash22(String call) {
        long n58 = 0; int i = 0;
        for (; i < call.length() && i < 11; i++) { int j = nchar(call.charAt(i), ALNUM_SPACE_SLASH); if (j < 0) return -1; n58 = 38 * n58 + j; }
        for (; i < 11; i++) n58 = 38 * n58;
        return (int) (((47055833459L * n58) >>> 42) & 0x3FFFFF);           // the product wraps at 64 bits, as in the reference
    }

    // ---- packing -----------------------------------------------------------------------------------------

    static int packBasecall(String call) {
        int len = call.length();
        if (len <= 2) return -1;
        char[] c6 = "      ".toCharArray();
        if (call.startsWith("3DA0") && len > 4 && len <= 7) { "3D0".getChars(0, 3, c6, 0); call.getChars(4, len, c6, 3); }
        else if (call.startsWith("3X") && Character.isLetter(call.charAt(2)) && len <= 7) { c6[0] = 'Q'; call.getChars(2, len, c6, 1); }
        else if (Character.isDigit(call.charAt(2)) && len <= 6) call.getChars(0, len, c6, 0);
        else if (Character.isDigit(call.charAt(1)) && len <= 5) call.getChars(0, len, c6, 1);
        int i0 = nchar(c6[0], ALNUM_SPACE), i1 = nchar(c6[1], ALNUM), i2 = nchar(c6[2], NUMERIC),
            i3 = nchar(c6[3], LETTERS_SPACE), i4 = nchar(c6[4], LETTERS_SPACE), i5 = nchar(c6[5], LETTERS_SPACE);
        if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0) return -1;
        return ((((i0 * 36 + i1) * 10 + i2) * 27 + i3) * 27 + i4) * 27 + i5;
    }

    static int parseCqModifier(String s) {
        int nnum = 0, nlet = 0, m = 0;
        for (int i = 3; i < 8 && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') break;
            if (Character.isDigit(c)) nnum++;
            else if (c >= 'A' && c <= 'Z') { nlet++; m = 27 * m + (c - 'A' + 1); }
            else return -1;
        }
        if (nnum == 3 && nlet == 0) return Integer.parseInt(s.substring(3, 6));
        if (nnum == 0 && nlet <= 4) return 1000 + m;
        return -1;
    }

    /** 28-bit call field plus its /R or /P flag in ip[0]; -1 = cannot be packed. */
    static long pack28(String call, Hashes h, int[] ip) {
        ip[0] = 0;
        if (call.equals("DE")) return 0;
        if (call.equals("QRZ")) return 1;
        if (call.equals("CQ")) return 2;
        int len = call.length();
        if (call.startsWith("CQ ") && len < 8) { int v = parseCqModifier(call); return v < 0 ? -1 : 3 + v; }
        if (len > 2 && call.startsWith("<") && call.endsWith(">")) {                // <CALL>: send it as its 22-bit hash (WSJT-X's non-standard calls)
            String in = call.substring(1, len - 1); int n22 = hash22(in); if (n22 < 0) return -1; if (h != null) h.save(in); return NTOKENS + n22;
        }
        String base = call;
        if (call.endsWith("/P") || call.endsWith("/R")) { ip[0] = 1; base = call.substring(0, len - 2); }
        int n28 = packBasecall(base);
        if (n28 >= 0) { if (h != null) h.save(call); return NTOKENS + MAX22 + n28; }
        if (len >= 3 && len <= 11) { int n22 = hash22(call); if (n22 < 0) return -1; if (h != null) h.save(call); ip[0] = 0; return NTOKENS + n22; }
        return -1;
    }

    /** True for a call that packs in full in a standard message (not one that goes as a hash or in a type 4 message). */
    public static boolean standardCall(String call) {
        String c = call == null ? "" : call.trim().toUpperCase(Locale.US);
        if (c.endsWith("/P") || c.endsWith("/R")) c = c.substring(0, c.length() - 2);
        return packBasecall(c) >= 0;
    }

    static int packGrid(String g) {
        if (g == null || g.isEmpty()) return MAXGRID4 + 1;
        if (g.equals("RRR")) return MAXGRID4 + 2;
        if (g.equals("RR73")) return MAXGRID4 + 3;
        if (g.equals("73")) return MAXGRID4 + 4;
        if (g.length() == 4 && g.charAt(0) >= 'A' && g.charAt(0) <= 'R' && g.charAt(1) >= 'A' && g.charAt(1) <= 'R' && Character.isDigit(g.charAt(2)) && Character.isDigit(g.charAt(3)))
            return (((g.charAt(0) - 'A') * 18 + (g.charAt(1) - 'A')) * 10 + (g.charAt(2) - '0')) * 10 + (g.charAt(3) - '0');
        boolean r = g.charAt(0) == 'R';
        Integer dd = report(r ? g.substring(1) : g);
        if (dd == null) return -1;
        return (MAXGRID4 + 35 + dd) | (r ? 0x8000 : 0);
    }

    private static Integer report(String s) {
        if (!s.matches("[+-]?\\d{1,2}")) return null;
        int v = Integer.parseInt(s.startsWith("+") ? s.substring(1) : s);
        return v < -30 || v > 49 ? null : v;
    }

    /** Pack a message the way WSJT-X does; {@code null} when it does not fit any supported format. */
    /**
     * True when this message can only go as a type 4 (non-standard call) message, which carries no grid or report:
     * a call with more than three suffix letters or an unusual prefix (N0CALL among them) loses its grid in a CQ,
     * as WSJT-X does. The caller says so instead of sending silently without it.
     */
    public static boolean dropsGrid(String text) {
        String t = text == null ? "" : text.trim().toUpperCase(Locale.US).replaceAll("\\s+", " ");
        if (t.isEmpty()) return false;
        String[] w = t.split(" ");
        if (w.length < 3) return false;
        String last = w[w.length - 1];
        if (packGrid(last) < 0 || packGrid(last) == MAXGRID4 + 1) return false;   // no grid or report on the end
        byte[] p = pack(t, null);
        return p != null && ((p[9] >> 3) & 7) == 4;
    }

    public static byte[] pack(String text, Hashes h) {
        String t = text.trim().toUpperCase(Locale.US).replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        String[] w = t.split(" ");
        String to, de, extra; int next;
        if (w[0].equals("CQ") && w.length >= 3 && parseCqModifier(w[0] + " " + w[1]) >= 0) { to = "CQ " + w[1]; next = 2; }   // i18n: FT8 message text, sent on air
        else { to = w[0]; next = 1; }
        de = next < w.length ? w[next++] : null;
        extra = next < w.length ? w[next++] : "";
        if (extra.equals("R") && next < w.length) extra = "R" + w[next++];   // "R -10" written with a space
        if (de != null && next == w.length) {
            byte[] p = packStd(to, de, extra, h);
            if (p != null) return p;
            p = packNonStd(to, de, extra, h);
            if (p != null) return p;
        }
        return packFree(t);
    }

    static byte[] packStd(String to, String de, String extra, Hashes h) {
        int[] ipa = new int[1], ipb = new int[1];
        long a = pack28(to, h, ipa), b = pack28(de, h, ipb);
        if (a < 0 || b < 0) return null;
        int i3 = 1;
        if (to.endsWith("/P") || de.endsWith("/P")) { i3 = 2; if (to.endsWith("/R") || de.endsWith("/R")) return null; }
        int slash = de.indexOf('/');
        boolean cq = to.equals("CQ") || to.startsWith("CQ ");
        if (slash >= 2 && cq && !(de.substring(slash).equals("/P") || de.substring(slash).equals("/R"))) return null;
        // A non-standard "de" call goes in a type 4 message, which carries it in full (a hashed "to" call is fine here), unless
        // it is written <CALL>: then it goes hashed, as WSJT-X sends it with a report (the other side has had it in full)
        if (b < NTOKENS + MAX22 && b >= NTOKENS && !de.startsWith("<")) return null;
        int g = packGrid(extra);
        if (g < 0) return null;
        long n29a = (a << 1) | ipa[0], n29b = (b << 1) | ipb[0];
        byte[] p = new byte[10];
        p[0] = (byte) (n29a >> 21); p[1] = (byte) (n29a >> 13); p[2] = (byte) (n29a >> 5);
        p[3] = (byte) ((n29a << 3) | (n29b >> 26)); p[4] = (byte) (n29b >> 18); p[5] = (byte) (n29b >> 10); p[6] = (byte) (n29b >> 2);
        p[7] = (byte) ((n29b << 6) | (g >> 10)); p[8] = (byte) (g >> 2); p[9] = (byte) ((g << 6) | (i3 << 3));
        return p;
    }

    static byte[] packNonStd(String to, String de, String extra, Hashes h) {
        boolean cq = to.equals("CQ") || to.startsWith("CQ ");
        if (!cq && to.length() < 3) return null;
        if (de.length() < 3) return null;
        int n12 = 0, iflip = 0; String call58;
        if (!cq) {
            if (de.startsWith("<") && de.endsWith(">")) iflip = 1;
            String call12 = iflip == 0 ? to : de; call58 = iflip == 0 ? de : to;
            String c12 = call12.replace("<", "").replace(">", "");
            int n22 = hash22(c12); if (n22 < 0) return null;
            if (h != null) h.save(c12);
            n12 = n22 >> 10;
        } else call58 = de;
        String c58 = call58.replace("<", "").replace(">", "");
        if (c58.length() > 11) return null;
        long n58 = 0;
        for (int i = 0; i < c58.length(); i++) { int j = nchar(c58.charAt(i), ALNUM_SPACE_SLASH); if (j < 0) return null; n58 = n58 * 38 + j; }
        if (h != null) h.save(c58);
        int nrpt = cq ? 0 : extra.equals("RRR") ? 1 : extra.equals("RR73") ? 2 : extra.equals("73") ? 3 : 0;
        if (!cq && nrpt == 0 && !extra.isEmpty()) return null;            // a grid or report cannot go in a type 4 message
        int icq = cq ? 1 : 0, i3 = 4;
        byte[] p = new byte[10];
        p[0] = (byte) (n12 >> 4); p[1] = (byte) ((n12 << 4) | (int) (n58 >>> 54));
        p[2] = (byte) (n58 >>> 46); p[3] = (byte) (n58 >>> 38); p[4] = (byte) (n58 >>> 30); p[5] = (byte) (n58 >>> 22); p[6] = (byte) (n58 >>> 14); p[7] = (byte) (n58 >>> 6);
        p[8] = (byte) ((n58 << 2) | (iflip << 1) | (nrpt >> 1)); p[9] = (byte) ((nrpt << 7) | (icq << 6) | (i3 << 3));
        return p;
    }

    /** Free text: up to 13 characters of 0-9 A-Z space + - . / ?; {@code null} if it does not fit. */
    static byte[] packFree(String text) {
        if (text.length() > 13) return null;
        int[] b71 = new int[9];
        for (int idx = 0; idx < 13; idx++) {
            char c = idx < text.length() ? text.charAt(idx) : ' ';
            int cid = nchar(c, FULL); if (cid < 0) return null;
            int rem = cid;
            for (int i = 8; i >= 0; i--) { rem += b71[i] * 42; b71[i] = rem & 0xFF; rem >>= 8; }
        }
        byte[] p = new byte[10]; int carry = 0;
        for (int i = 8; i >= 0; i--) { p[i] = (byte) ((b71[i] << 1) | (carry >> 7)); carry = b71[i] & 0x80; }
        p[9] = 0;
        return p;
    }

    // ---- unpacking -------------------------------------------------------------------------------------

    static String unpack28(long n28, int ip, int i3, Hashes h) {
        if (n28 < NTOKENS) {
            if (n28 == 0) return "DE";
            if (n28 == 1) return "QRZ";
            if (n28 == 2) return "CQ";
            if (n28 <= 1002) return String.format(Locale.US, "CQ %03d", n28 - 3);   // i18n: FT8 message text, sent on air
            if (n28 <= 532443) {
                long n = n28 - 1003; char[] a = new char[4];
                for (int i = 3; i >= 0; i--) { a[i] = charn((int) (n % 27), LETTERS_SPACE); n /= 27; }
                return "CQ " + new String(a).trim();   // i18n: FT8 message text, sent on air
            }
            return null;
        }
        n28 -= NTOKENS;
        if (n28 < MAX22) { String c = h != null ? h.lookup(22, (int) n28) : null; return c != null ? "<" + c + ">" : "<...>"; }
        long n = n28 - MAX22;
        char[] c = new char[6];
        c[5] = charn((int) (n % 27), LETTERS_SPACE); n /= 27;
        c[4] = charn((int) (n % 27), LETTERS_SPACE); n /= 27;
        c[3] = charn((int) (n % 27), LETTERS_SPACE); n /= 27;
        c[2] = charn((int) (n % 10), NUMERIC); n /= 10;
        c[1] = charn((int) (n % 36), ALNUM); n /= 36;
        c[0] = charn((int) (n % 37), ALNUM_SPACE);
        String s = new String(c);
        String r;
        if (s.startsWith("3D0") && s.charAt(3) != ' ') r = "3DA0" + s.substring(3).trim();
        else if (s.charAt(0) == 'Q' && Character.isLetter(s.charAt(1))) r = "3X" + s.substring(1).trim();
        else r = s.trim();
        if (r.length() < 3) return null;
        if (ip != 0) { if (i3 == 1) r += "/R"; else if (i3 == 2) r += "/P"; else return null; }
        if (h != null) h.save(r);
        return r;
    }

    static String unpackGrid(int g, int ir) {
        if (g <= MAXGRID4) {
            int n = g; char[] d = new char[4];
            d[3] = (char) ('0' + n % 10); n /= 10; d[2] = (char) ('0' + n % 10); n /= 10; d[1] = (char) ('A' + n % 18); n /= 18; d[0] = (char) ('A' + n % 18);
            return (ir > 0 ? "R " : "") + new String(d);
        }
        int irpt = g - MAXGRID4;
        switch (irpt) {
            case 1: return "";
            case 2: return "RRR";
            case 3: return "RR73";
            case 4: return "73";
            default: return (ir > 0 ? "R" : "") + String.format(Locale.US, "%+03d", irpt - 35);
        }
    }

    /** The text of a 77-bit payload, or {@code null} for a type this app does not show. */
    public static String unpack(byte[] p, Hashes h) {
        int i3 = (p[9] >> 3) & 7;
        int n3 = ((p[8] << 2) & 4) | ((p[9] >> 6) & 3);
        if (i3 == 1 || i3 == 2) {
            long n29a = ((long) (p[0] & 0xFF) << 21) | ((p[1] & 0xFF) << 13) | ((p[2] & 0xFF) << 5) | ((p[3] & 0xFF) >> 3);
            long n29b = ((long) (p[3] & 7) << 26) | ((p[4] & 0xFF) << 18) | ((p[5] & 0xFF) << 10) | ((p[6] & 0xFF) << 2) | ((p[7] & 0xFF) >> 6);
            int ir = (p[7] & 0x20) >> 5;
            int g = ((p[7] & 0x1F) << 10) | ((p[8] & 0xFF) << 2) | ((p[9] & 0xFF) >> 6);
            String a = unpack28(n29a >> 1, (int) (n29a & 1), i3, h), b = unpack28(n29b >> 1, (int) (n29b & 1), i3, h);
            if (a == null || b == null) return null;
            String x = unpackGrid(g, ir);
            return (a + " " + b + (x.isEmpty() ? "" : " " + x)).trim();
        }
        if (i3 == 4) {
            int n12 = ((p[0] & 0xFF) << 4) | ((p[1] & 0xFF) >> 4);
            long n58 = ((long) (p[1] & 0x0F) << 54) | ((long) (p[2] & 0xFF) << 46) | ((long) (p[3] & 0xFF) << 38) | ((long) (p[4] & 0xFF) << 30)
                     | ((long) (p[5] & 0xFF) << 22) | ((long) (p[6] & 0xFF) << 14) | ((long) (p[7] & 0xFF) << 6) | ((p[8] & 0xFF) >> 2);
            int iflip = (p[8] >> 1) & 1, nrpt = ((p[8] & 1) << 1) | ((p[9] & 0xFF) >> 7), icq = (p[9] >> 6) & 1;
            char[] c11 = new char[11];
            for (int i = 10; i >= 0; i--) { c11[i] = charn((int) (n58 % 38), ALNUM_SPACE_SLASH); n58 /= 38; }
            String plain = new String(c11).trim();
            if (h != null && plain.length() >= 3) h.save(plain);
            String hc = h != null ? h.lookup(12, n12) : null; String hashed = hc != null ? "<" + hc + ">" : "<...>";
            String c1 = iflip != 0 ? plain : hashed, c2 = iflip != 0 ? hashed : plain;
            if (icq != 0) return "CQ " + c2;   // i18n: FT8 message text, sent on air
            String x = nrpt == 1 ? " RRR" : nrpt == 2 ? " RR73" : nrpt == 3 ? " 73" : "";
            return c1 + " " + c2 + x;
        }
        if (i3 == 0 && n3 == 0) {
            int[] b71 = new int[9]; int carry = 0;
            for (int i = 0; i < 9; i++) { b71[i] = ((carry << 7) | ((p[i] & 0xFF) >> 1)) & 0xFF; carry = p[i] & 1; }
            char[] c = new char[13];
            for (int idx = 12; idx >= 0; idx--) {
                int rem = 0;
                for (int i = 0; i < 9; i++) { rem = (rem << 8) | b71[i]; b71[i] = rem / 42; rem %= 42; }
                c[idx] = charn(rem, FULL);
            }
            return new String(c).trim();
        }
        if (i3 == 0 && n3 == 5) {
            StringBuilder sb = new StringBuilder(); int carry = 0;
            for (int i = 0; i < 9; i++) { int b = ((carry << 7) | ((p[i] & 0xFF) >> 1)) & 0xFF; carry = p[i] & 1; sb.append(String.format(Locale.US, "%02X", b)); }
            return sb.toString();
        }
        return null;
    }
}
