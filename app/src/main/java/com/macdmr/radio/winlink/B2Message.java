/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.winlink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * A Winlink (B2) message: RFC-822-like headers ("Mid" first, the rest in alphabetical order, CRLF), a blank line,
 * the body, then each attachment followed by CRLF. Addresses are callsigns, or "SMTP:" + an internet address
 * (call@winlink.org is just the callsign). Dates are UTC "yyyy/MM/dd HH:mm". The body is ISO-8859-1 unless the
 * Content-Type names another character set, as Winlink Express and Pat do.
 * i18n: stays English (Winlink message format)
 */
public final class B2Message {
    public static final class Attachment {
        public final String name; public final byte[] data;
        public Attachment(String name, byte[] data) { this.name = name; this.data = data; }
    }

    public String mid = "", date = "", type = "Private", from = "", mbo = "", subject = "";
    public final List<String> to = new ArrayList<>(), cc = new ArrayList<>();
    public byte[] body = new byte[0];
    public String charset = "ISO-8859-1";
    public final List<Attachment> files = new ArrayList<>();
    /** Headers this class does not model, kept as they came (name -> values). */
    public final Map<String, List<String>> other = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** A new private message from this callsign, dated now. */
    public static B2Message create(String myCall, String subject, String text) {
        B2Message m = new B2Message();
        m.mid = newMid(myCall, System.currentTimeMillis());
        m.date = formatDate(System.currentTimeMillis());
        m.from = address(myCall); m.mbo = myCall.toUpperCase(Locale.US);
        m.subject = subject;
        m.body = text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(Charset.forName(m.charset));
        return m;
    }

    /** A 12-character message ID: base32 of an MD5 of the time and the callsign (as Pat makes them). */
    public static String newMid(String call, long now) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest((now + "-" + System.nanoTime() + "-" + call.toUpperCase(Locale.US)).getBytes(StandardCharsets.US_ASCII));
            String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; StringBuilder sb = new StringBuilder(); int buf = 0, bits = 0;
            for (byte b : h) { buf = (buf << 8) | (b & 0xFF); bits += 8; while (bits >= 5 && sb.length() < 12) { sb.append(alpha.charAt((buf >> (bits - 5)) & 31)); bits -= 5; } }
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String formatDate(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }
    /** Milliseconds since 1970 of a Winlink date (the layouts seen in the wild), or 0. */
    public static long parseDate(String d) {
        if (d == null) return 0;
        for (String layout : new String[] { "yyyy/MM/dd HH:mm", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm", "yyyyMMddHHmmss" }) {
            try { SimpleDateFormat f = new SimpleDateFormat(layout, Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); f.setLenient(false); Date x = f.parse(d.trim()); if (x != null) return x.getTime(); } catch (Exception ignored) { }
        }
        return 0;
    }

    /** "n0abc" -> "N0ABC", "n0abc@winlink.org" -> "N0ABC", "a@b.com" -> "SMTP:a@b.com". */
    public static String address(String a) {
        a = a.trim();
        int colon = a.indexOf(':');
        if (colon > 0) return a.substring(0, colon).toUpperCase(Locale.US) + ":" + a.substring(colon + 1);
        int at = a.indexOf('@');
        if (at < 0) return a.toUpperCase(Locale.US);
        if (a.substring(at + 1).equalsIgnoreCase("winlink.org")) return a.substring(0, at).toUpperCase(Locale.US);
        return "SMTP:" + a;
    }
    /** For display: "SMTP:a@b.com" -> "a@b.com". */
    public static String shown(String a) { return a.regionMatches(true, 0, "SMTP:", 0, 5) ? a.substring(5) : a; }

    public String bodyText() { try { return new String(body, Charset.forName(charset)); } catch (Exception e) { return new String(body, StandardCharsets.ISO_8859_1); } }
    public long time() { return parseDate(date); }

    // ---- wire form ----------------------------------------------------------------------------------

    public byte[] toBytes() {
        TreeMap<String, List<String>> h = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : other.entrySet()) h.put(e.getKey(), new ArrayList<>(e.getValue()));
        put(h, "Body", String.valueOf(body.length));
        put(h, "Content-Transfer-Encoding", "8bit");
        put(h, "Content-Type", "text/plain; charset=" + charset);
        put(h, "Date", date);
        put(h, "From", from);
        put(h, "Mbo", mbo.isEmpty() ? from : mbo);
        put(h, "Subject", subject.replace("\r", " ").replace("\n", " "));
        put(h, "Type", type);
        if (!to.isEmpty()) h.put("To", new ArrayList<>(to));
        if (!cc.isEmpty()) h.put("Cc", new ArrayList<>(cc));
        if (!files.isEmpty()) { List<String> l = new ArrayList<>(); for (Attachment a : files) l.add(a.data.length + " " + a.name); h.put("File", l); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Charset hc = StandardCharsets.ISO_8859_1;
        write(o, ("Mid: " + mid + "\r\n").getBytes(hc));
        for (Map.Entry<String, List<String>> e : h.entrySet()) for (String v : e.getValue()) write(o, (e.getKey() + ": " + v.trim() + "\r\n").getBytes(hc));
        write(o, "\r\n".getBytes(hc));
        write(o, body);
        if (!files.isEmpty()) write(o, "\r\n".getBytes(hc));
        for (Attachment a : files) { write(o, a.data); write(o, "\r\n".getBytes(hc)); }
        return o.toByteArray();
    }

    public static B2Message parse(byte[] b) throws IOException {
        B2Message m = new B2Message();
        int p = 0;
        while (p < b.length && (b[p] == '\r' || b[p] == '\n' || b[p] == ' ' || b[p] == '\t')) p++;
        List<String[]> headers = new ArrayList<>();
        for (;;) {
            int e = p; while (e < b.length && b[e] != '\n') e++;
            if (e >= b.length) throw new IOException("message: no end of headers");
            String line = new String(b, p, e - p, StandardCharsets.ISO_8859_1); if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            p = e + 1;
            if (line.isEmpty()) break;
            if ((line.startsWith(" ") || line.startsWith("\t")) && !headers.isEmpty()) { String[] last = headers.get(headers.size() - 1); last[1] += " " + line.trim(); continue; }
            int c = line.indexOf(':'); if (c <= 0) continue;
            headers.add(new String[] { line.substring(0, c).trim(), line.substring(c + 1).trim() });
        }
        int bodyLen = -1;
        List<String> fileHeaders = new ArrayList<>();
        for (String[] h : headers) {
            String k = h[0], v = h[1];
            switch (k.toLowerCase(Locale.US)) {
                case "mid": m.mid = v; break;
                case "date": m.date = v; break;
                case "type": m.type = v; break;
                case "from": m.from = v; break;
                case "mbo": m.mbo = v; break;
                case "subject": m.subject = v; break;
                case "to": m.to.add(v); break;
                case "cc": m.cc.add(v); break;
                case "body": try { bodyLen = Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { throw new IOException("message: bad Body length " + v); } break;
                case "file": fileHeaders.add(v); break;
                case "content-type": { int i = v.toLowerCase(Locale.US).indexOf("charset="); if (i >= 0) { String cs = v.substring(i + 8).replace("\"", "").split("[;\\s]")[0]; try { Charset.forName(cs); m.charset = cs; } catch (Exception ignored) { } } break; }
                case "content-transfer-encoding": break;
                default: m.other.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        if (bodyLen < 0) throw new IOException("message: no Body header");
        if (p + bodyLen > b.length) throw new IOException("message: body shorter than its header says");
        m.body = new byte[bodyLen]; System.arraycopy(b, p, m.body, 0, bodyLen); p += bodyLen;
        if (!fileHeaders.isEmpty() && p + 1 < b.length && b[p] == '\r' && b[p + 1] == '\n') p += 2;
        for (String fh : fileHeaders) {
            int sp = fh.indexOf(' '); if (sp <= 0) throw new IOException("message: bad File header " + fh);
            int n; try { n = Integer.parseInt(fh.substring(0, sp)); } catch (NumberFormatException ex) { throw new IOException("message: bad File size " + fh); }
            if (p + n > b.length) throw new IOException("message: attachment " + fh.substring(sp + 1) + " is cut short");
            byte[] d = new byte[n]; System.arraycopy(b, p, d, 0, n); p += n;
            if (p + 1 < b.length && b[p] == '\r' && b[p + 1] == '\n') p += 2;
            m.files.add(new Attachment(fh.substring(sp + 1), d));
        }
        return m;
    }

    private static void put(Map<String, List<String>> h, String k, String v) { List<String> l = new ArrayList<>(); l.add(v); h.put(k, l); }
    private static void write(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
}
