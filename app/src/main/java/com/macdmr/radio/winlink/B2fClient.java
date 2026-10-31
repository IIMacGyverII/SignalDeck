/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.winlink;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The client side of a Winlink B2F exchange (FBB compressed forwarding, version 2) over any byte stream: the CMS
 * by telnet, or a gateway (RMS) over AX.25. After the gateway's banner, SID and secure-login challenge, we answer
 * with our forwarders, SID and login response, then the turns alternate: up to five proposals of ours (FC lines
 * and an F> checksum), the gateway's answer (FS), the accepted messages as LZHUF blocks; then its proposals, our
 * answer and its messages; until a side with nothing left says FF and the other ends with FQ. The password is
 * used only to compute the login response and is never shown, logged or stored here.
 */
public final class B2fClient {
    /** A byte stream to the gateway. */
    public interface Wire {
        /** One byte, blocking up to timeoutMs; IOException on timeout or a closed link. */
        int read(long timeoutMs) throws IOException;
        void write(byte[] b) throws IOException;
    }
    /** Where messages come from and go to, and what the user sees. */
    public interface Mailbox {
        /** Messages waiting to be sent, oldest first. */
        List<B2Message> outbox();
        /** True if this message ID is already in the inbox (the proposal is then declined). */
        boolean have(String mid);
        void received(B2Message m) throws IOException;
        void sent(String mid);
        /** A protocol line or a note for the session log ("<" in, ">" out, "*" notes). */
        void log(String line);
        /** A compressed message is being sent or received (true) or the line protocol resumes (false). */
        default void binary(boolean on) { }
        /** Progress: bytes done of total for the message being moved. */
        default void progress(String what, int done, int total) { }
    }

    public static final String SID_FLAGS = "B2FHM$";
    static final byte[] SALT = {
            77, (byte) 197, 101, (byte) 206, (byte) 190, (byte) 249, 93, (byte) 200, 51, (byte) 243, 93, (byte) 237,
            71, 94, (byte) 239, (byte) 138, 68, 108, 70, (byte) 185, (byte) 225, (byte) 137, (byte) 217, 16,
            51, 122, (byte) 193, 48, (byte) 194, (byte) 195, (byte) 198, (byte) 175, (byte) 172, (byte) 169, 70, 84,
            61, 62, 104, (byte) 186, 114, 52, 61, (byte) 168, 66, (byte) 129, (byte) 192, (byte) 208,
            (byte) 187, (byte) 249, (byte) 232, (byte) 193, 41, 113, 41, 45, (byte) 240, 16, 29, (byte) 228,
            (byte) 208, (byte) 228, 61, 20 };
    static final int MAX_BLOCK = 125, MAX_PROPOSALS = 5;

    private final Wire wire; private final Mailbox box;
    private final String myCall, target, locator, version, password;
    private final long timeoutMs;
    private boolean remoteNoMsgs;
    private int sentCount, receivedCount;

    /** myCall without SSID for the forwarders line; password may be empty (then a secure-login challenge fails). */
    public B2fClient(Wire wire, Mailbox box, String myCall, String target, String locator, String version, String password, long timeoutMs) {
        this.wire = wire; this.box = box;
        this.myCall = myCall.toUpperCase(Locale.US); this.target = target.toUpperCase(Locale.US);
        this.locator = locator == null ? "" : locator; this.version = version.replace('-', '.'); this.password = password == null ? "" : password;
        this.timeoutMs = timeoutMs;
    }

    public int sent() { return sentCount; }
    public int received() { return receivedCount; }

    /** The Winlink secure-login response: MD5 of challenge + password + salt, folded into 8 decimal digits. */
    public static String secureLoginResponse(String challenge, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(challenge.getBytes(StandardCharsets.UTF_8)); md.update(password.getBytes(StandardCharsets.UTF_8)); md.update(SALT);                // the salt as raw bytes
            byte[] s = md.digest();
            int pr = s[3] & 0x3F;
            for (int i = 2; i >= 0; i--) pr = (pr << 8) | (s[i] & 0xFF);
            String str = String.format(Locale.US, "%08d", pr);
            return str.substring(str.length() - 8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Run the whole exchange. Returns normally when both sides are done; throws on any protocol or link error. */
    public void run() throws IOException {
        String challenge = null, sid = null;
        for (;;) {                                                             // the gateway's banner up to its prompt
            String line = readLine();
            box.log("< " + line);
            remoteError(line);
            if (line.startsWith("[") && line.endsWith("]")) sid = line;
            else if (line.startsWith(";PQ:")) challenge = line.substring(4).trim();
            else if (line.endsWith(">")) break;
        }
        if (sid == null) throw new IOException("no SID from the gateway");
        String flags = sid.substring(sid.lastIndexOf('-') + 1, sid.length() - 1).toUpperCase(Locale.US);
        if (!flags.contains("B2")) throw new IOException("the gateway does not offer B2F (" + sid + ")");
        if (challenge != null && password.isEmpty()) throw new IOException("the gateway asks for your Winlink password (secure login): set it in Winlink settings");
        StringBuilder hs = new StringBuilder();
        hs.append(";FW: ").append(myCall).append('\r');   // i18n: B2F protocol
        hs.append('[').append("SignalDeck-").append(version).append('-').append(SID_FLAGS).append("]\r");   // i18n: B2F protocol
        if (challenge != null) hs.append(";PR: ").append(secureLoginResponse(challenge, password)).append('\r');   // i18n: B2F protocol
        hs.append("; ").append(target).append(" DE ").append(myCall).append(locator.isEmpty() ? "" : " (" + locator + ")").append('\r');   // i18n: B2F protocol
        for (String l : hs.toString().split("\r")) box.log("> " + (l.startsWith(";PR:") ? ";PR: ********" : l));
        write(hs.toString());
        boolean done = false;
        for (boolean myTurn = true; !done; myTurn = !myTurn) done = myTurn ? outbound() : inbound();
        box.log("* done: " + sentCount + " sent, " + receivedCount + " received");
    }

    // ---- our turn ------------------------------------------------------------------------------------

    private static final class Out { B2Message m; byte[] data; int size; char answer; }

    /** Returns true when we sent FQ (the session ends). */
    private boolean outbound() throws IOException {
        List<B2Message> all = box.outbox();
        if (all.isEmpty()) {
            String r = remoteNoMsgs ? "FQ" : "FF";
            box.log("> " + r); write(r + "\r");
            return remoteNoMsgs;
        }
        List<Out> props = new ArrayList<>();
        int sum = 0; StringBuilder sb = new StringBuilder();
        for (B2Message m : all) {
            if (props.size() >= MAX_PROPOSALS) break;
            Out o = new Out(); o.m = m; byte[] plain = m.toBytes(); o.size = plain.length; o.data = Lzhuf.compressB2(plain);
            String line = "FC EM " + m.mid + " " + o.size + " " + o.data.length + " 0";   // i18n: B2F protocol
            for (char c : line.toCharArray()) sum += c; sum += '\r';
            sb.append(line).append('\r'); box.log("> " + line);
            props.add(o);
        }
        String cs = String.format(Locale.US, "F> %02X", (-sum) & 0xFF);
        sb.append(cs).append('\r'); box.log("> " + cs);
        write(sb.toString());
        String reply = null;
        while (reply == null) {
            String line = readLine(); box.log("< " + line); remoteError(line);
            if (line.startsWith("FS ")) reply = line.substring(3).trim();
            else if (line.startsWith(";")) continue;
            else throw new IOException("expected an answer to our proposals, got: " + line);
        }
        int i = 0;
        for (int k = 0; k < reply.length(); k++) {
            if (i >= props.size()) throw new IOException("more answers than proposals: " + reply);
            char c = reply.charAt(k); Out o = props.get(i++);
            if ("Yy+".indexOf(c) >= 0) o.answer = '+';   // i18n: B2F protocol
            else if ("NnRr-".indexOf(c) >= 0) o.answer = '-';   // i18n: B2F protocol
            else if ("LlHh=".indexOf(c) >= 0) o.answer = '=';   // i18n: B2F protocol
            else if ("Aa!".indexOf(c) >= 0) {                                 // resume at an offset: we start over instead   // i18n: B2F protocol
                while (k + 1 < reply.length() && Character.isDigit(reply.charAt(k + 1))) k++;
                o.answer = '+';
            } else throw new IOException("bad answer character '" + c + "' in " + reply);
        }
        for (Out o : props) {
            if (o.answer == '+') { writeCompressed(o); box.sent(o.m.mid); sentCount++; }
            else if (o.answer == '-') { box.log("* the gateway already has " + o.m.mid); box.sent(o.m.mid); }
            else box.log("* the gateway deferred " + o.m.mid + " (stays in the outbox)");
        }
        return false;
    }

    private void writeCompressed(Out o) throws IOException {
        String title = ascii(o.m.subject.isEmpty() ? "No title" : o.m.subject); if (title.length() > 80) title = title.substring(0, 80);   // i18n: B2F protocol (proposal title)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = title.getBytes(StandardCharsets.US_ASCII);
        b.write(1); b.write(t.length + 1 + 2); b.write(t, 0, t.length); b.write(0); b.write('0'); b.write(0);
        int sum = 0;
        for (int p = 0; p < o.data.length; p += MAX_BLOCK) {
            int n = Math.min(MAX_BLOCK, o.data.length - p);
            b.write(2); b.write(n); b.write(o.data, p, n);
            for (int k = 0; k < n; k++) sum += o.data[p + k] & 0xFF;
        }
        b.write(4); b.write((-sum) & 0xFF);
        box.log("* sending " + o.m.mid + " (" + o.data.length + " bytes compressed)");
        byte[] all = b.toByteArray();
        box.binary(true);
        try {
        for (int p = 0; p < all.length; p += 512) {                           // in pieces, so progress can be shown
            int n = Math.min(512, all.length - p); byte[] part = new byte[n]; System.arraycopy(all, p, part, 0, n);
            wire.write(part); box.progress(Txt.s(R.string.modes_winlink_sending, o.m.mid), Math.min(p + n, all.length), all.length);
        }
        } finally { box.binary(false); }
    }

    // ---- their turn ----------------------------------------------------------------------------------

    private static final class In { String mid; int size, csize; char answer; }

    /** Returns true when the gateway sent FQ. */
    private boolean inbound() throws IOException {
        List<In> props = new ArrayList<>(); int sum = 0;
        for (;;) {
            String line = readLine(); box.log("< " + line); remoteError(line);
            if (line.isEmpty() || line.startsWith(";")) continue;
            if (line.length() < 2 || line.charAt(0) != 'F') throw new IOException("unexpected line from the gateway: " + line);
            switch (line.substring(0, 2)) {
                case "FA": case "FB": case "FC": case "FD": {
                    for (char c : line.toCharArray()) sum += c; sum += '\r';
                    String[] p = line.substring(3).trim().split(" ");
                    if (p.length < 5) throw new IOException("bad proposal: " + line);
                    In in = new In(); in.mid = p[1];
                    try { in.size = Integer.parseInt(p[2]); in.csize = Integer.parseInt(p[3]); } catch (NumberFormatException e) { throw new IOException("bad proposal: " + line); }
                    in.answer = line.charAt(1) != 'C' ? '=' : box.have(in.mid) ? '-' : '+';
                    for (In q : props) if (q.mid.equals(in.mid)) in.answer = '=';
                    props.add(in);
                    break;
                }
                case "FF": remoteNoMsgs = true; return false;
                case "FQ": return true;
                case "F>": {
                    int theirs; try { theirs = Integer.parseInt(line.substring(2).trim(), 16); } catch (NumberFormatException e) { throw new IOException("bad checksum line: " + line); }
                    if (theirs != ((-sum) & 0xFF)) throw new IOException("proposal checksum error");
                    if (props.isEmpty()) { remoteNoMsgs = true; return false; }
                    remoteNoMsgs = false;
                    StringBuilder a = new StringBuilder("FS ");   // i18n: B2F protocol
                    for (In in : props) a.append(in.answer);
                    box.log("> " + a); write(a + "\r");
                    for (In in : props) if (in.answer == '+') readCompressed(in);
                    return false;
                }
                default: throw new IOException("unknown command from the gateway: " + line);
            }
        }
    }

    private void readCompressed(In in) throws IOException {
        box.binary(true);
        try { readCompressedBody(in); } finally { box.binary(false); }
    }
    private void readCompressedBody(In in) throws IOException {
        int c = wire.read(timeoutMs);
        if (c == '*') { throw new IOException("the gateway reported an error: *" + readLine()); }
        if (c != 1) throw new IOException("expected the start of a message, got byte " + c);
        int hlen = wire.read(timeoutMs);
        String title = readZ(), offset = readZ();
        if (hlen != title.length() + offset.length() + 2) throw new IOException("message header length mismatch");
        if (!"0".equals(offset.trim())) throw new IOException("the gateway resumed at offset " + offset + " (not supported)");
        box.log("* receiving " + in.mid + " · " + title);
        ByteArrayOutputStream data = new ByteArrayOutputStream(in.csize); int sum = 0;
        for (;;) {
            int k = wire.read(timeoutMs);
            if (k == 2) {
                int n = wire.read(timeoutMs); if (n == 0) n = 256;
                for (int i = 0; i < n; i++) { int b = wire.read(timeoutMs); data.write(b); sum += b; }
                box.progress(Txt.s(R.string.modes_winlink_receiving, in.mid), data.size(), in.csize);
            } else if (k == 4) {
                sum += wire.read(timeoutMs);
                if ((sum & 0xFF) != 0) throw new IOException("message checksum error");
                if (data.size() != in.csize) throw new IOException("message length mismatch");
                break;
            } else throw new IOException("unexpected byte " + k + " in a message");
        }
        byte[] plain = Lzhuf.decompressB2(data.toByteArray());
        B2Message m = B2Message.parse(plain);
        box.received(m); receivedCount++;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void remoteError(String line) throws IOException {
        if (line.startsWith("***")) {
            String why = line.replaceFirst("^\\*+\\s*", "");
            throw new IOException(why.isEmpty() ? "the gateway ended the session" : why);   // i18n: exception text
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (;;) {
            int c = wire.read(timeoutMs);
            if (c == '\r') return sb.toString();
            if (c == '\n') { if (sb.length() == 0) continue; return sb.toString(); }
            sb.append((char) c);
            if (sb.length() > 2048) throw new IOException("line too long");
        }
    }
    private String readZ() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (;;) { int c = wire.read(timeoutMs); if (c == 0) return sb.toString(); sb.append((char) c); if (sb.length() > 255) throw new IOException("header field too long"); }
    }
    private void write(String s) throws IOException { wire.write(s.getBytes(StandardCharsets.ISO_8859_1)); }
    private static String ascii(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) b.append(c >= 32 && c < 127 ? c : '?'); return b.toString(); }
}
