/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (serial and module diagnostics: log lines, shell commands and device-report text)

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine for SA818-class analog modules driven with {@code AT+DMO…} text commands (the ANYSECU W6+: module
 * PB202EU05 on /dev/ttyS2 at 9600 8N1). Everything here follows the stock app as sniffed and read (docs/w6/ENGINE_DESIGN.md):
 * one command per write ending in a bare CR, at least 100 ms apart, answered "+DMO&lt;NAME&gt;:0" (done) or ":1" (refused);
 * the channel is one {@code AT+DMOSETGROUP=gbw,tx,rx,txTone,rxTone,sq,power}. Reception comes from the module's receive
 * line (a GPIO input), and on it the stock app's audio lines are set so the speaker and the recordable audio route open.
 * Answers go out through the DMR engine's {@link RadioEngine.Events} with its command numbers (SETGROUP as the analog
 * channel write, VERQ as the version), so RadioService needs no second set of callbacks.
 */
public final class AtModuleEngine implements ModuleEngine, UartPort.Listener {
    private final DeviceProfile profile;
    private final RadioEngine.Events ev;
    private final UartPort port;
    private final SysfsGpio power;
    /** One command at a time, in order; callers return at once and hear the answer through {@link RadioEngine.Events#ack}. */
    private final java.util.concurrent.ExecutorService cmds = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "at-commands"));
    private volatile boolean running;
    private Thread reader, rxWatch;

    // the channel as last written, for MON and squelch changes (which resend it)
    private volatile int[] group;                  // gbw, rxHz, txHz, txCode, rxCode, sq, power
    private volatile boolean monitor;
    private volatile int squelchOverride = -1;
    private volatile int moduleVolume = -1;
    /** The level that follows Android's media volume (-1 until RadioService sets it): 0 mutes the speaker, 1-8 the module volume. */
    private volatile int speakerLevel = -1;
    private volatile boolean rxOn;

    // answers: the line waited for, by command name
    private final Object answerLock = new Object();
    private String waitingFor, answer;
    private long lastSentAt;

    private final java.util.Map<Integer, RadioEngine.CommandStats> stats = new java.util.TreeMap<>();
    private volatile long firstUnansweredAt;
    private volatile int rssiReadings, rssiNoReading, rssiMisses;
    private volatile boolean rssiDead;

    public AtModuleEngine(DeviceProfile p, RadioEngine.Events ev) {
        this.profile = p; this.ev = ev;
        this.port = new UartPort(p.controlPort, this);
        this.power = p.power();
    }

    @Override public String protocol() { return "sa818"; }
    @Override public boolean dmr() { return false; }
    @Override public DeviceProfile profile() { return profile; }
    @Override public boolean isOpen() { return port.isOpen(); }

    @Override public void open() throws IOException {
        if (port.isOpen()) return;
        port.open(0);                                   // the line: RootSetup.ensureTty sets the profile's 9600 8N1 raw
        port.startDivert();                             // every byte to our line reader: there are no 68 … 10 frames here
        running = true;
        reader = new Thread(this::readLines, "at-reader"); reader.start();
        if (profile.rxDetectNode != null) { rxWatch = new Thread(this::watchReceive, "at-rx-watch"); rxWatch.start(); }
    }

    @Override public void close() {
        running = false;
        port.stopDivert();
        port.close();
        root.close();
    }
    /** The speaker, receive and transmit lines change at every reception: one root shell for them, not an su (and a Magisk toast) each time. */
    private final RootShell root = new RootShell();

    // ---- lines in ----------------------------------------------------------------------------------

    private void readLines() {
        StringBuilder line = new StringBuilder();
        while (running) {
            int b;
            try { b = port.readRaw(250); } catch (IOException e) { if (running) ev.log("AT read: " + e.getMessage()); break; }
            if (b < 0) continue;
            if (b == '\r' || b == '\n') { if (line.length() > 0) { onLine(line.toString()); line.setLength(0); } continue; }
            if (line.length() < 200) line.append((char) b);
        }
    }

    /** "+DMOSETGROUP:0" → {"SETGROUP", "0"}; "RSSI=123" (RSSI query) → {"RSSI", "123"}; anything else null. */
    static String[] reply(String line) {
        Matcher m = REPLY.matcher(line);
        if (m.find()) return new String[] { m.group(1), m.group(2).trim() };
        m = RSSI.matcher(line);
        if (m.find()) return new String[] { "RSSI", m.group(1) };
        return null;
    }
    private static final Pattern REPLY = Pattern.compile("^\\+DMO([A-Z]+):(.*)$"), RSSI = Pattern.compile("^\\+?(?:DMO)?RSSI[=:]\\s*(\\d+)");

    private void onLine(String l) {
        ev.log("AT RX " + l);
        String[] r = reply(l.trim());
        if (r == null) return;
        firstUnansweredAt = 0;
        if (r[0].equals("VERQ")) ev.version(0, r[1]);
        synchronized (answerLock) { if (r[0].equals(waitingFor)) { answer = r[1]; answerLock.notifyAll(); } }
    }

    // ---- commands out ------------------------------------------------------------------------------

    /** The command number the device report and RadioService know each AT command by. */
    static int statsKey(String name) {
        switch (name) {
            case "SETGROUP": return RadioEngine.CMD_SET_ANALOG;
            case "VERQ": return RadioEngine.CMD_VERSION;
            case "SETVOLUME": return RadioEngine.CMD_VOLUME;
            case "SETMIC": return RadioEngine.CMD_MIC;
            case "RSSI": return RadioEngine.CMD_RSSI;
            default: return RadioEngine.CMD_QUERY_INIT;    // CONNECT and the rest
        }
    }

    /**
     * Send one command and wait for its answer (one retry after a silent second). Runs on the command thread. Returns the
     * answer's value ("0" done, "1" refused, or the version text), or null when the module stayed silent.
     */
    private String command(String name, String text, long timeoutMs) throws IOException, InterruptedException {
        byte[] bytes = (text + "\r").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int attempt = 0; attempt < 2; attempt++) {
            long gap = 100 - (System.currentTimeMillis() - lastSentAt);
            if (gap > 0) Thread.sleep(gap);
            long t0 = System.currentTimeMillis();
            RadioEngine.CommandStats s;
            synchronized (stats) { s = stats.computeIfAbsent(statsKey(name), k -> new RadioEngine.CommandStats()); s.sent++; }
            if ((name.equals("SETGROUP") || name.equals("VERQ")) && firstUnansweredAt == 0) firstUnansweredAt = t0;
            synchronized (answerLock) { waitingFor = name; answer = null; }
            port.writeRaw(bytes); lastSentAt = System.currentTimeMillis();
            ev.log("AT TX " + text);
            String a;
            synchronized (answerLock) {
                long end = t0 + timeoutMs;
                while (answer == null && System.currentTimeMillis() < end) answerLock.wait(Math.max(1, end - System.currentTimeMillis()));
                a = answer; waitingFor = null;
            }
            if (a != null) {
                long ms = System.currentTimeMillis() - t0;
                synchronized (stats) { s.answered++; if (!a.equals("0") && !name.equals("VERQ") && !name.equals("RSSI")) s.refused++; s.sumMs += ms; if (ms > s.maxMs) s.maxMs = ms; if (ms > 1000) s.slow++; }
                return a;
            }
        }
        return null;
    }

    /** Queue a command; {@code done} gets the answer (null = silent) on the command thread. */
    private void async(String name, String text, java.util.function.Consumer<String> done) {
        cmds.execute(() -> {
            try { String a = command(name, text, 1000); if (done != null) done.accept(a); }
            catch (Throwable t) { ev.log("AT " + name + ": " + t.getMessage()); if (done != null) done.accept(null); }
        });
    }

    /** MHz as the stock app writes it: three decimals, a fourth only when the frequency needs it ("435.025", "435.0125"). */
    static String mhz(int hz) {
        String s = String.format(Locale.US, "%.4f", hz / 1e6);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    /** The module's frequency range (the W6 listing; the module's own limits are not measured). */
    static boolean inBand(int hz) { return hz >= 400_000_000 && hz <= 470_000_000; }

    /** The SETGROUP line: gbw, transmit and receive MHz, transmit then receive tone code, squelch 0–8, power 0/1. */
    static String groupCommand(int[] g, int sq) {
        return "AT+DMOSETGROUP=" + g[0] + "," + mhz(g[2]) + "," + mhz(g[1]) + "," + g[3] + "," + g[4] + "," + sq + "," + g[6];
    }

    private void sendGroup() {
        final int[] g = group; if (g == null) return;
        int sq = monitor ? 0 : squelchOverride >= 0 ? squelchOverride : g[5];
        async("SETGROUP", groupCommand(g, sq), a -> ev.ack(RadioEngine.CMD_SET_ANALOG, "0".equals(a)));
    }

    // ---- ModuleEngine: channel and settings --------------------------------------------------------

    @Override public void setAnalog(int rxHz, int txHz, int band, int power, int sq, int rxType, int rxSub, int txType, int txSub, int volume, int monitor) {
        if (!inBand(rxHz) || !inBand(txHz)) {
            ev.log("AT: " + mhz(rxHz) + " / " + mhz(txHz) + " MHz is outside this module's 400–470 MHz");
            ev.ack(RadioEngine.CMD_SET_ANALOG, false); return;
        }
        int txCode = Sa818Tones.toModule(txType, txSub), rxCode = Sa818Tones.toModule(rxType, rxSub);
        if (txCode < 0 || rxCode < 0) ev.log("AT: this module has no code for the channel's tone (inverted DCS?): sent without it");
        group = new int[] { band == 0 ? 0 : 1, rxHz, txHz, Math.max(0, txCode), Math.max(0, rxCode), Math.max(0, Math.min(8, sq)), power == 1 ? 1 : 0 };
        this.monitor = monitor == 1;
        sendGroup();
        setVolume(volume);
    }

    /**
     * RadioService passes the DMR module's volume byte (8). This module takes 0–8 and its audio drives the speaker
     * amplifier directly; 4 is the middle, loud enough for the recordable route without a harsh speaker. Not measured yet.
     */
    static int moduleVolumeFor(int dmrVolume) { return Math.max(1, Math.min(8, Math.round(dmrVolume / 2f))); }

    @Override public void setVolume(int v) {
        int lv = speakerLevel;
        int m = profile.appPlayback ? profile.fixedModuleVolume                                    // SignalDeck plays the audio: a fixed module level
                : lv > 0 ? lv : lv == 0 ? Math.max(1, moduleVolume) : moduleVolumeFor(v);   // the media volume rules once it is known; at 0 the speaker route is off instead
        if (m == moduleVolume) return;
        moduleVolume = m;
        async("SETVOLUME", "AT+DMOSETVOLUME=" + m, null);
    }
    /** Media volume 0-8 (0 = speaker off), as the stock app does: its module volume follows the media volume, and at 0 it leaves the speaker off. */
    @Override public void setSpeakerLevel(int level) {
        int was = speakerLevel; speakerLevel = Math.max(0, Math.min(8, level));
        if (speakerLevel > 0 && speakerLevel != moduleVolume && running) { moduleVolume = speakerLevel; async("SETVOLUME", "AT+DMOSETVOLUME=" + speakerLevel, null); }
        if (rxOn && !profile.appPlayback && (was == 0) != (speakerLevel == 0)) lines(speakerLevel == 0 ? profile.muteLines : profile.rxLinesOn);   // changed while receiving
    }
    /** Media volume (0..max) to the module's 0-8 the way the stock app maps it (on a 0-25 scale: 1-3 = 1 ... 22-25 = 8). */
    static int speakerLevelFor(int media, int max) { return media <= 0 || max <= 0 ? 0 : Math.max(1, Math.min(8, (int) Math.ceil(media * 8.0 / max))); }
    /** Not sent: the stock app's AT+DMOSETMIC arguments were never seen on the wire, and a guessed format is not worth the risk. */
    @Override public void setMic(int gain) { ev.log("AT: mic gain not set (AT+DMOSETMIC's format is not known yet)"); }
    @Override public void setSquelch(int level) { squelchOverride = Math.max(0, Math.min(8, level)); sendGroup(); }
    @Override public void setMonitor(boolean on) {
        monitor = on;
        final int[] g = group;
        if (g == null) { ev.ack(RadioEngine.CMD_MONITOR, true); return; }
        int sq = on ? 0 : squelchOverride >= 0 ? squelchOverride : g[5];
        async("SETGROUP", groupCommand(g, sq), a -> ev.ack(RadioEngine.CMD_MONITOR, "0".equals(a)));
    }
    @Override public void setMonitorRaw(int v) { setMonitor(v == 1); }
    @Override public void setSpkEn(boolean on) { }
    @Override public void setInterrupt(boolean on) { }
    @Override public void setTot(int seconds) { }       // the module has no time-out command; SignalDeck's own watchdog applies
    @Override public void setDigital(int rxHz, int txHz, int localId, int[] groups, int txContact, int contactType, int power, int cc, int inSlot, int outSlot, int volume, int mic) {
        ev.log("AT: this module has no DMR; the digital channel is not written");
        ev.ack(RadioEngine.CMD_SET_DIGITAL, false);
    }

    // ---- queries -----------------------------------------------------------------------------------

    @Override public void queryVersion() { async("VERQ", "AT+DMOVERQ", null); }
    @Override public void queryInit() { }
    @Override public void queryCallerInfo() { }

    /**
     * Signal strength: "RSSI?" is the SA818 family's query; the stock app never sends it. A module that does not answer
     * three in a row is not asked again (the S-meter stays dark).
     */
    @Override public void queryRssi() {
        if (rssiDead) return;
        async("RSSI", "RSSI?", a -> {
            if (a == null) { if (++rssiMisses >= 3 && !rssiDead) { rssiDead = true; ev.log("AT: the module does not answer RSSI?: no signal readings on this phone"); } return; }
            rssiMisses = 0; rssiReadings++;
            int raw = Integer.parseInt(a);
            ev.rssi(raw, rssiDbm(raw));
        });
    }
    /** Not calibrated yet: the raw reading is reported, the dBm value is unknown (-999) until measured against a known signal. */
    static double rssiDbm(int raw) { return -999; }

    // ---- power and start-up -----------------------------------------------------------------------

    @Override public String powerNode() { return profile.powerNode; }
    @Override public String pttNode() { return profile.pttNode; }
    @Override public String powerState() { return power.read(); }
    @Override public boolean powerOn() throws IOException {
        if (power.isOn()) { ev.log("module already powered (" + power.read() + ")"); return false; }
        power.set(true); ev.log("module power -> on (" + power.read() + ")");
        return true;
    }
    @Override public void powerOff() throws IOException { power.set(false); ev.log("module power -> off (" + power.read() + ")"); }
    @Override public void powerCycle() throws IOException {
        power.set(false);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
        power.set(true); firstUnansweredAt = 0; ev.log("module power-cycled (" + power.read() + ")");
    }

    /** The module prints no banner: it is up when it answers AT+DMOCONNECT (the stock app asks once a second). */
    @Override public boolean waitBooted(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            final String[] a = { null };
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            async("CONNECT", "AT+DMOCONNECT", x -> { a[0] = x; done.countDown(); });
            try { done.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { return false; }
            if ("0".equals(a[0])) { ev.booted("+DMOCONNECT:0"); return true; }
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }
    @Override public void bootHandshake(Runnable setChannel, boolean digital) {
        if (!waitBooted(10000)) ev.log("the module did not answer AT+DMOCONNECT within 10 s: sending the channel anyway");
        queryVersion(); setChannel.run();
    }

    // ---- reception: the module's receive line ------------------------------------------------------

    private void watchReceive() {
        boolean was = false; boolean rootRead = false;
        while (running) {
            String v = null;
            if (!rootRead) try (FileInputStream f = new FileInputStream(profile.rxDetectNode)) { byte[] b = new byte[8]; int n = f.read(b); v = n > 0 ? new String(b, 0, n).trim() : ""; }
                catch (IOException e) { rootRead = true; ev.log("receive line not readable directly (" + e.getMessage() + "): reading it through root every 250 ms"); }
            if (rootRead) v = root.run("cat " + profile.rxDetectNode).trim();
            boolean on = "1".equals(v);
            if (on != was) {
                was = on; rxOn = on;
                if (on) { lines(profile.rxLinesOn); if (speakerLevel == 0 || profile.appPlayback) lines(profile.muteLines); ev.status(1, RadioEngine.statusName(1)); }   // app playback: the module's speaker route stays off
                else { ev.status(2, RadioEngine.statusName(2)); lines(profile.rxLinesOff); }
            }
            try { Thread.sleep(rootRead ? 250 : 100); } catch (InterruptedException e) { return; }
        }
    }

    /** Write {node, value} pairs in order, through root in one call (the stock app writes them as the system user). */
    private void lines(String[][] l) {
        if (l == null || l.length == 0) return;
        StringBuilder c = new StringBuilder();
        for (String[] p : l) c.append("echo ").append(p[1]).append(" > ").append(p[0]).append("; ");
        String r = root.run(c.toString());
        if (!r.isEmpty()) ev.log("lines: " + r);
    }

    // ---- transmit: only on a profile the owner has confirmed on air --------------------------------

    @Override public void ptt(boolean down) throws IOException {
        if (!profile.verified || profile.txBlocked) throw new IOException("transmit is off on this phone until its profile has been tested on air");
        if (down) { lines(profile.txLinesOn); ev.status(3, RadioEngine.statusName(3)); }
        else { lines(profile.txLinesOff); ev.status(4, RadioEngine.statusName(4)); }
    }

    // ---- not on this module ------------------------------------------------------------------------

    @Override public void sendSms(int msgType, int callNumber, int groupNumber, String text) throws IOException { throw new IOException("this radio module has no DMR texts"); }
    @Override public void fetchSms() { }
    @Override public void startDivert() { }
    @Override public void stopDivert() { }
    @Override public boolean rawSession() { return false; }
    @Override public void writeRaw(byte[] b) throws IOException { throw new IOException("no raw port use on this module"); }
    @Override public int readRaw(int timeoutMs) throws IOException { throw new IOException("no raw port use on this module"); }
    @Override public void diagCommand(int cmd) throws IOException { throw new IOException("not on this module"); }
    @Override public void diagCommand(int cmd, int data) throws IOException { throw new IOException("not on this module"); }
    @Override public void diagSine(int n, int amp, int hz) throws IOException { throw new IOException("not on this module"); }
    @Override public void diagPcm(int offset, byte[] samples) throws IOException { throw new IOException("not on this module"); }
    @Override public Frame diag40(byte[] body) throws IOException { throw new IOException("not on this module"); }
    @Override public Frame diag40(byte[] body, int waitMs) throws IOException { throw new IOException("not on this module"); }
    @Override public java.util.List<Frame> diag40n(byte[] body, int n, int waitMs) throws IOException { throw new IOException("not on this module"); }

    // ---- health ------------------------------------------------------------------------------------

    @Override public long unansweredForMs() { long t = firstUnansweredAt; return t == 0 ? 0 : System.currentTimeMillis() - t; }
    @Override public java.util.Map<Integer, RadioEngine.CommandStats> stats() {
        java.util.Map<Integer, RadioEngine.CommandStats> out = new java.util.TreeMap<>();
        synchronized (stats) { for (java.util.Map.Entry<Integer, RadioEngine.CommandStats> e : stats.entrySet()) { RadioEngine.CommandStats c = new RadioEngine.CommandStats(), o = e.getValue(); c.sent = o.sent; c.answered = o.answered; c.refused = o.refused; c.slow = o.slow; c.sumMs = o.sumMs; c.maxMs = o.maxMs; out.put(e.getKey(), c); } }
        return out;
    }
    @Override public int[] rssiCounts() { return new int[] { rssiReadings, rssiNoReading }; }

    // ---- UartPort.Listener (the port runs diverted: only its log reaches here) --------------------
    @Override public void onFrame(Frame f) { }
    @Override public void onRaw(byte[] b, int n) { }
    @Override public void onLog(String s) { ev.log(s); }
}
