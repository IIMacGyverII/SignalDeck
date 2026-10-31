/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.winlink;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Winlink e-mail: the mailbox and one session at a time, either over the internet (telnet to the Winlink CMS,
 * server.winlink.org:8772, the same B2F exchange as on the air) or over the radio to a packet gateway (RMS) in
 * AX.25 connected mode. The Winlink password lives only in this app's private settings ("winlink"/"password"); it
 * is never logged, shown, put in a device report or backup, and the session log masks the login response.
 */
public final class Winlink {
    /** The radio side: an AX.25 link to a gateway, and the station's callsign. */
    public interface Radio {
        /** Connect to the gateway and return a byte stream over the link (blocking until connected). */
        B2fClient.Wire open(String gateway, String via, StatusSink status) throws IOException;
        /** End the link (if any). */
        void close();
        /** Base callsign (no SSID), or "" when none is set. */
        String callsign();
        /** Tell the user (new mail). */
        void notifyUser(String title, String text);
        /** The phone's last known position {lat, lon}, or null (for gateway distances; never leaves the phone). */
        default double[] position() { return null; }
        /** Diagnostic log: a line for every AX.25 frame of the session (null stops it). */
        default void frameLog(java.util.function.Consumer<String> l) { }
        /** Diagnostic log: what to say about the phone and radio at the top of the log. */
        default String describe() { return ""; }
        /** A received message carried a SignalDeck form code line (the host rebuilds the form). */
        default void formText(String from, String line) { }
    }
    public interface StatusSink { void status(String s); }
    public interface Listener { void changed(); }

    public static final String CMS_HOST = "server.winlink.org"; public static final int CMS_PORT = 8772;
    public static final String CMS_TARGET = "WL2K", CMS_TELNET_PASSWORD = "CMSTelnet";
    /** Bench only (DiagReceiver, adb): a test CMS on this phone's loopback (adb reverse); 0 = the real CMS. Never saved. */
    public static volatile int benchPort;

    private final Context ctx; private final Radio radio; private final WinlinkStore store; private final SharedPreferences prefs;
    private final StringBuilder log = new StringBuilder();
    private volatile String state = Txt.s(R.string.modes_winlink_state_idle); private volatile boolean busy; private volatile String progress = "";
    private volatile Listener listener; private volatile Socket socket; private volatile boolean viaRadio, cancelled;
    private volatile long lastSession; private volatile String lastResult = "";   // English: it also goes in the history text

    public Winlink(Context ctx, Radio radio) {
        this.ctx = ctx.getApplicationContext(); this.radio = radio;
        store = new WinlinkStore(new File(this.ctx.getFilesDir(), "winlink"));
        prefs = this.ctx.getSharedPreferences("winlink", Context.MODE_PRIVATE);
    }

    public WinlinkStore store() { return store; }
    public String state() { return state; }
    public boolean busy() { return busy; }
    public String progress() { return progress; }
    public synchronized String logText() { return log.toString(); }
    public void setListener(Listener l) { listener = l; }
    public long lastSession() { return lastSession; }
    public String lastResult() { return lastResult; }

    // ---- settings --------------------------------------------------------------------------------------

    public boolean passwordSet() { return !prefs.getString("password", "").isEmpty(); }
    /** Write-only: an empty value keeps the old password; "-" clears it. */
    public void setPassword(String p) { if (p == null || p.isEmpty()) return; prefs.edit().putString("password", "-".equals(p) ? "" : p).apply(); }
    public String grid() { return prefs.getString("grid", ""); }
    public void setGrid(String g) { prefs.edit().putString("grid", g == null ? "" : g.trim()).apply(); }
    public String gateway() { return prefs.getString("gateway", ""); }
    public String via() { return prefs.getString("via", ""); }
    public void setGateway(String g, String v) { prefs.edit().putString("gateway", g.trim().toUpperCase(Locale.US)).putString("via", v.trim().toUpperCase(Locale.US)).apply(); }

    // ---- diagnostic log (for the first tests with the real Winlink system) --------------------------------

    /** Detailed log: timing, raw protocol bytes, every radio frame. Off by default. */
    public boolean diagEnabled() { return prefs.getBoolean("diag", false); }
    public void setDiag(boolean on) { prefs.edit().putBoolean("diag", on).apply(); changed(); }
    /** Include the bytes of the messages themselves (compressed) in the detailed log; off by default. */
    public boolean diagContents() { return prefs.getBoolean("diagContents", false); }
    public void setDiagContents(boolean on) { prefs.edit().putBoolean("diagContents", on).apply(); }
    /** Whether the user was already asked (at the first connection) to turn the detailed log on. */
    public boolean diagAsked() { return prefs.getBoolean("diagAsked", false); }
    public void setDiagAsked() { prefs.edit().putBoolean("diagAsked", true).apply(); }
    /** Bench only: forget that the first-connection question was asked, and switch the detailed log off. */
    public void resetDiag() { prefs.edit().remove("diagAsked").putBoolean("diag", false).putBoolean("diagContents", false).apply(); lastWasDiag = false; changed(); }
    /** Whether the last session ran with the detailed log (the screen then suggests sharing it). */
    public boolean lastWasDiag() { return lastWasDiag; }
    private volatile boolean lastWasDiag, diagNow, inBinary; private volatile long t0;

    /** The session log as a text file to share (in the app's share folder). */
    public java.io.File logFile() throws IOException {
        java.io.File dir = new java.io.File(ctx.getCacheDir(), "share"); dir.mkdirs();
        java.io.File f = new java.io.File(dir, "signaldeck_winlink_log.txt");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(logText().getBytes(StandardCharsets.UTF_8)); }
        return f;
    }

    /** Wraps the byte stream: raw bytes both ways (hex + text), message blocks as their size unless contents are wanted. */
    private B2fClient.Wire diagWire(final B2fClient.Wire w) {
        return new B2fClient.Wire() {
            private final java.io.ByteArrayOutputStream rx = new java.io.ByteArrayOutputStream(); private int rxBin;
            @Override public int read(long timeoutMs) throws IOException {
                int c;
                try { c = w.read(timeoutMs); } catch (IOException e) { flushRx(); note("~ read: " + e.getMessage()); throw e; }   // i18n: session log (diagnostic)
                if (inBinary && !diagContents()) { rxBin++; return c; }
                if (rxBin > 0) { note("~ RX " + rxBin + " bytes of message data (contents not logged)"); rxBin = 0; }   // i18n: session log (diagnostic)
                rx.write(c); if (c == '\r' || rx.size() >= 64) flushRx();
                return c;
            }
            @Override public void write(byte[] b) throws IOException {
                flushRx();
                if (inBinary && !diagContents()) note("~ TX " + b.length + " bytes of message data (contents not logged)");   // i18n: session log (diagnostic)
                else note("~ TX " + b.length + " B: " + dump(b));   // i18n: session log (diagnostic)
                w.write(b);
            }
            private void flushRx() {
                if (rxBin > 0) { note("~ RX " + rxBin + " bytes of message data (contents not logged)"); rxBin = 0; }   // i18n: session log (diagnostic)
                if (rx.size() > 0) { byte[] b = rx.toByteArray(); rx.reset(); note("~ RX " + b.length + " B: " + dump(b)); }   // i18n: session log (diagnostic)
            }
        };
    }
    /** "hex | text" with the secure-login answer hidden. */
    static String dump(byte[] b) {
        String text = new String(b, StandardCharsets.ISO_8859_1);
        if (text.contains(";PR:")) return "(the login answer, hidden)";   // i18n: session log (diagnostic)
        StringBuilder h = new StringBuilder(), t = new StringBuilder();
        for (int i = 0; i < b.length && i < 256; i++) { h.append(String.format(Locale.US, "%02X", b[i] & 0xFF)).append(i % 4 == 3 ? " " : ""); int c = b[i] & 0xFF; t.append(c >= 32 && c < 127 ? (char) c : c == 13 ? '\u21B5' : '.'); }
        return h.toString().trim() + " | " + t + (b.length > 256 ? " \u2026" : "");
    }

    // ---- gateway list (the CSV from winlink.org, RMS Channels) --------------------------------------------

    /** Import the CSV text; returns a summary for the user, or throws IllegalArgumentException. */
    public String importGateways(String csv) {
        RmsList.Result r = RmsList.parse(csv);
        prefs.edit().putString("gateways", RmsList.toJson(r.usable)).putLong("gatewaysAt", System.currentTimeMillis()).apply();
        changed();
        List<String> parts = new ArrayList<>();
        parts.add(Txt.p(R.plurals.modes_winlink_import_usable, r.usable.size(), r.usable.size(), r.rows));
        if (r.hf > 0) parts.add(Txt.s(R.string.modes_winlink_import_hf, r.hf));
        if (r.otherBaud > 0) parts.add(Txt.p(R.plurals.modes_winlink_import_other_baud, r.otherBaud, r.otherBaud));
        if (r.outOfRange > 0) parts.add(Txt.p(R.plurals.modes_winlink_import_other_bands, r.outOfRange, r.outOfRange));
        return String.join(Txt.s(R.string.modes_clause_separator), parts);
    }
    /** The imported gateways, nearest first when the position is known. */
    public java.util.List<RmsList.Gateway> gateways() {
        java.util.List<RmsList.Gateway> l = RmsList.fromJson(prefs.getString("gateways", "[]"));
        double[] p = radio.position(); if (p != null) RmsList.rank(l, p[0], p[1]);
        return l;
    }
    public long gatewaysImportedAt() { return prefs.getLong("gatewaysAt", 0); }
    public void clearGateways() { prefs.edit().remove("gateways").remove("gatewaysAt").apply(); changed(); }

    // ---- mail ------------------------------------------------------------------------------------------

    /** Put a new message in the outbox; returns an error text or null. */
    public String compose(String to, String cc, String subject, String text) {
        String call = radio.callsign();
        if (call.isEmpty()) return Txt.s(R.string.modes_winlink_err_callsign);
        B2Message m = B2Message.create(call, subject.trim().isEmpty() ? "No subject" : subject.trim(), text);   // i18n: Winlink message header
        for (String a : split(to)) m.to.add(B2Message.address(a));
        for (String a : split(cc)) m.cc.add(B2Message.address(a));
        if (m.to.isEmpty()) return Txt.s(R.string.modes_winlink_err_no_to);
        if (m.subject.length() > 128) return Txt.s(R.string.modes_winlink_err_subject);
        if (m.body.length == 0) return Txt.s(R.string.modes_winlink_err_empty);
        try { store.save(WinlinkStore.OUTBOX, m); } catch (IOException e) { return Txt.s(R.string.modes_sat_save_failed, String.valueOf(e.getMessage())); }
        changed();
        return null;
    }
    static List<String> split(String s) {
        List<String> l = new ArrayList<>(); if (s == null) return l;
        for (String p : s.split("[,;\\s]+")) { p = p.trim(); if (!p.isEmpty()) l.add(p); }
        return l;
    }

    // ---- sessions --------------------------------------------------------------------------------------

    /** Exchange mail with the CMS over the internet. Returns an error text or null (started). */
    public String connectInternet() {
        String call = radio.callsign();
        if (call.isEmpty()) return Txt.s(R.string.modes_winlink_err_callsign);
        if (busy) return Txt.s(R.string.modes_winlink_err_busy);
        start("internet", () -> {
            Socket s = new Socket(); socket = s;
            setState(Txt.s(R.string.modes_winlink_state_connecting_cms));
            int bp = benchPort;
            if (bp > 0) note("* bench: test CMS at 127.0.0.1:" + bp);   // i18n: session log (diagnostic)
            s.connect(bp > 0 ? new InetSocketAddress("127.0.0.1", bp) : new InetSocketAddress(CMS_HOST, CMS_PORT), 20_000); s.setSoTimeout(1_000);
            final InputStream in = s.getInputStream(); final OutputStream out = s.getOutputStream();
            B2fClient.Wire w = socketWire(in, out);
            for (;;) {                                                         // telnet login: callsign, then the fixed telnet password
                StringBuilder sb = new StringBuilder(); int c;
                while ((c = w.read(30_000)) != '\r') { if (c != '\n') sb.append((char) c); if (sb.length() > 512) throw new IOException("unexpected answer from the CMS"); }
                String l = sb.toString().trim().toLowerCase(Locale.US);
                if (l.startsWith("callsign")) w.write((call + "\r").getBytes(StandardCharsets.US_ASCII));
                else if (l.startsWith("password")) { w.write((CMS_TELNET_PASSWORD + "\r").getBytes(StandardCharsets.US_ASCII)); break; }
            }
            setState(Txt.s(R.string.modes_winlink_state_cms));
            exchange(w, CMS_TARGET, 60_000);
        });
        return null;
    }

    /** Exchange mail with a packet gateway (RMS) on the current channel. Returns an error text or null (started). */
    public String connectRadio(String gateway, String via) {
        String call = radio.callsign();
        if (call.isEmpty()) return Txt.s(R.string.modes_winlink_err_callsign);
        if (busy) return Txt.s(R.string.modes_winlink_err_busy);
        String g = gateway == null ? "" : gateway.trim().toUpperCase(Locale.US);
        if (!g.matches("[A-Z0-9]{3,6}(-[0-9]{1,2})?")) return Txt.s(R.string.modes_winlink_err_gateway);
        setGateway(g, via == null ? "" : via);
        start("radio", () -> {
            viaRadio = true;
            setState(Txt.s(R.string.modes_winlink_state_calling, g));
            B2fClient.Wire w = radio.open(g, via, this::setState);
            setState(Txt.s(R.string.modes_winlink_state_gateway, g));
            exchange(w, g.replaceAll("-.*", ""), 240_000);
        });
        return null;
    }

    /** Stop the session now (the link is dropped; nothing half-received is kept). */
    public void cancel() {
        cancelled = true;
        Socket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) { }
        if (viaRadio) radio.close();
    }

    private interface Job { void run() throws Exception; }

    private void start(String how, Job job) {
        busy = true; cancelled = false; viaRadio = false; progress = "";
        synchronized (this) { log.setLength(0); }
        t0 = System.currentTimeMillis(); diagNow = diagEnabled(); lastWasDiag = diagNow; inBinary = false;
        note("* " + how + " session started " + B2Message.formatDate(System.currentTimeMillis()) + " UTC");   // i18n: session log (diagnostic)
        if (diagNow) {
            String ver = ""; try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            note("* detailed diagnostic log on: SignalDeck " + ver + ", " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE   // i18n: session log (diagnostic)
                    + (radio.describe().isEmpty() ? "" : ", " + radio.describe()) + "; password never logged" + (diagContents() ? "; message data included" : "; message data left out"));   // i18n: session log (diagnostic)
            if ("radio".equals(how)) radio.frameLog(line -> note("# " + line));
        }
        new Thread(() -> {
            String result, shown;                                              // English (log, history) and in the app's language (the screen)
            try { job.run(); result = lastResult; shown = lastShown; }
            catch (Throwable t) {
                boolean timeout = t instanceof SocketTimeoutException; String why = t.getMessage() == null ? t.toString() : t.getMessage();
                result = cancelled ? Txt.en(R.string.modes_winlink_stopped) : Txt.en(R.string.modes_winlink_failed, timeout ? Txt.en(R.string.modes_winlink_timeout) : why);
                shown = cancelled ? Txt.s(R.string.modes_winlink_stopped) : Txt.s(R.string.modes_winlink_failed, timeout ? Txt.s(R.string.modes_winlink_timeout) : why);
                note("* " + result);
            } finally {
                Socket s = socket; socket = null; if (s != null) try { s.close(); } catch (IOException ignored) { }
                if (viaRadio) radio.close();
                radio.frameLog(null);
                busy = false; progress = "";
            }
            lastResult = result; lastSession = System.currentTimeMillis();
            if (diagNow) note("* " + result + " (" + (lastSession - t0) / 1000 + " s). Please share this log (Session log \u2192 Share log), whether it worked or not.");   // i18n: session log (diagnostic)
            setState(shown);
        }, "winlink").start();
    }

    private void exchange(B2fClient.Wire w, String target, long timeoutMs) throws IOException {
        final int[] got = { 0 };
        B2fClient.Mailbox box = new B2fClient.Mailbox() {
            @Override public List<B2Message> outbox() { List<B2Message> l = new ArrayList<>(); List<WinlinkStore.Item> items = store.list(WinlinkStore.OUTBOX); for (int i = items.size() - 1; i >= 0; i--) l.add(items.get(i).m); return l; }
            @Override public boolean have(String mid) { return store.has(WinlinkStore.INBOX, mid); }
            @Override public void received(B2Message m) throws IOException {
                store.save(WinlinkStore.INBOX, m); got[0]++; changed();
                String form = com.macdmr.radio.ham.Forms.winlinkPart(m.bodyText());
                if (form != null) try { radio.formText(B2Message.shown(m.from), form); } catch (Throwable ignored) { }
            }
            @Override public void sent(String mid) { store.markSent(mid); changed(); }
            @Override public void log(String line) { note(line); }
            @Override public void progress(String what, int done, int total) { progress = Txt.s(R.string.modes_winlink_progress, what, done, total); changed(); }
            @Override public void binary(boolean on) { inBinary = on; }
        };
        String version = "1.0";
        try { version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        if (diagNow) w = diagWire(w);
        B2fClient c = new B2fClient(w, box, radio.callsign(), target, grid(), version, prefs.getString("password", ""), timeoutMs);
        c.run();
        lastResult = Txt.en(R.string.modes_winlink_done, c.sent(), c.received()); lastShown = Txt.s(R.string.modes_winlink_done, c.sent(), c.received());
        if (got[0] > 0) radio.notifyUser("Winlink", Txt.p(R.plurals.modes_winlink_new_mail, got[0], got[0]));   // i18n: service name
    }

    private volatile String lastShown = "";

    private B2fClient.Wire socketWire(InputStream in, OutputStream out) {
        return new B2fClient.Wire() {
            @Override public int read(long timeoutMs) throws IOException {
                long end = System.currentTimeMillis() + timeoutMs;
                for (;;) {
                    if (cancelled) throw new IOException("stopped");
                    try { int c = in.read(); if (c < 0) throw new IOException("the CMS closed the connection"); return c; }
                    catch (SocketTimeoutException e) { if (System.currentTimeMillis() > end) throw e; }
                }
            }
            @Override public void write(byte[] b) throws IOException { if (cancelled) throw new IOException("stopped"); out.write(b); out.flush(); }
        };
    }

    // ---- state -------------------------------------------------------------------------------------------

    private void setState(String s) { state = s; changed(); }
    private synchronized void note(String line) {
        if (diagNow) log.append(String.format(Locale.US, "+%7.3f ", (System.currentTimeMillis() - t0) / 1000.0));
        log.append(line).append('\n');
        int cap = diagNow ? 600_000 : 40_000;
        if (log.length() > cap) log.delete(0, log.length() - cap * 3 / 4);
        changed();
    }
    private void changed() { Listener l = listener; if (l != null) l.changed(); }
}
