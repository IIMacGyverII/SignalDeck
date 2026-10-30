/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * The shared protocol layer: module power, boot banner, commands, acks, status events.
 * Command set and bodies per docs/deep-dive/01, 02 (PriInterPhone) — identical in the Blackview SDK.
 */
public final class RadioEngine implements UartPort.Listener, ModuleEngine {
    // commands (A→M unless noted)
    public static final int CMD_SET_DIGITAL = 0x22, CMD_SET_ANALOG = 0x23, CMD_LAUNCH = 0x26, CMD_QUERY_INIT = 0x27,
            CMD_ENHANCE = 0x28, CMD_MIC = 0x2A, CMD_CALLER_INFO = 0x2B, CMD_SEND_SMS = 0x2C, CMD_FETCH_SMS = 0x2D, CMD_VOLUME = 0x2E, CMD_MONITOR = 0x2F,
            CMD_SQUELCH = 0x30, CMD_RSSI = 0x32, CMD_VERSION = 0x34, CMD_INTERRUPT = 0x35, CMD_STATUS = 0x36, CMD_POLITE = 0x37,
            CMD_TOT = 0x3B, CMD_SPK_EN = 0x3C, CMD_INIT = 0xAA;

    public interface Events {
        void log(String s);
        void status(int code, String name);             // 0x36 push: RECEIVE_START…
        void rssi(int raw, double dbm);
        void version(int version, String text);
        /** 0x2B caller info; {@code cc} and {@code slot} are the received colour code and time slot (0 or 1) when the module appended them (FW26 promiscuous receive), else -1. */
        void callerInfo(int callType, int callerId, int target, int cc, int slot);
        void ack(int cmd, boolean ok);
        void booted(String banner);
        void sms(int type, int fromId, String text);          // 0x2D reply
        /** The Agold USB bridge reported a fault that its stock app answers with a bridge reset (state report 03, "pcm error"). */
        default void bridgeFault(String why) { }
    }

    private final DeviceProfile profile;
    private final Events ev;
    private final UartPort port;

    /** Per command: how many were sent, answered, refused, and how long the answers took (the device report's summary). */
    public static final class CommandStats {
        public int sent, answered, refused, slow; public long sumMs, maxMs;
        final java.util.ArrayDeque<Long> pending = new java.util.ArrayDeque<>();
    }
    private final java.util.Map<Integer, CommandStats> stats = new java.util.TreeMap<>();
    private volatile int rssiNoReading, rssiReadings;
    /**
     * Commands the module always answers (channel, MON, squelch, version). Xplore 1 tester 2026-10-23: after an update the
     * module answered signal readings at once but none of these for four minutes, so these are watched separately.
     */
    static boolean watched(int cmd) { return cmd == CMD_SET_DIGITAL || cmd == CMD_SET_ANALOG || cmd == CMD_MONITOR || cmd == CMD_SQUELCH || cmd == CMD_VERSION; }
    private volatile long firstUnansweredAt;                // the first watched command sent since the module last answered one (0 = none waiting)
    /** How long a watched command has been waiting for the module's answer (0 = none): a stuck module grows this. */
    public long unansweredForMs() { long t = firstUnansweredAt; return t == 0 ? 0 : System.currentTimeMillis() - t; }
    /** Bench: one command, body byte 1. Does not key. */
    public void diagCommand(int cmd) throws IOException { diagCommand(cmd, 1); }
    /** Bench: one command with one body byte. Does not key. */
    public void diagCommand(int cmd, int data) throws IOException { send(Frame.request(cmd & 0xFF, (byte) data)); }
    /**
     * Bench: command 0x40 with a 1 kHz-class sine as little-endian samples. Does not key.
     * The amplitude stays at or below 2048. The firmware clamps anything larger.
     */
    public void diagSine(int n, int amp, int hz) throws IOException {
        int count = Math.max(8, Math.min(256, n));
        int peak = Math.max(1, Math.min(2048, amp));
        int tone = Math.max(50, Math.min(3000, hz));
        byte[] body = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int s = (int) Math.round(peak * Math.sin(2 * Math.PI * tone * i / 8000.0));
            body[2 * i] = (byte) s;
            body[2 * i + 1] = (byte) (s >> 8);
        }
        send(Frame.request(0x40, body));
    }
    /** Bench: command 0x40, a sample offset then little-endian samples. Does not key. */
    public void diagPcm(int offset, byte[] samples) throws IOException {
        if (samples == null || samples.length == 0 || samples.length > 796 || (samples.length & 1) != 0) {
            throw new IOException("pcm length " + (samples == null ? -1 : samples.length));
        }
        byte[] body = new byte[2 + samples.length];
        body[0] = (byte) offset;
        body[1] = (byte) (offset >> 8);
        System.arraycopy(samples, 0, body, 2, samples.length);
        send(Frame.request(0x40, body));
    }
    private final Object gate40 = new Object();
    private Frame reply40;
    private final java.util.ArrayList<Frame> extra40 = new java.util.ArrayList<>();
    private int want40;
    /**
     * Bench: one command 0x40, then the module's answer. Does not key.
     * The UART reader delivers the answer. The symbol stream calls this from
     * its own thread: a key holds the radio worker for the whole playback.
     */
    public Frame diag40(byte[] body) throws IOException { return diag40(body, 500); }
    /** Bench: one command 0x40, then the module's answer, waiting at most waitMs. Does not key. */
    public Frame diag40(byte[] body, int waitMs) throws IOException {
        if (body == null || body.length == 0 || body.length > 799) throw new IOException("body " + (body == null ? -1 : body.length));
        synchronized (gate40) { reply40 = null; }
        send(Frame.request(0x40, body));
        long end = System.currentTimeMillis() + Math.max(1, waitMs);
        synchronized (gate40) {
            while (reply40 == null && System.currentTimeMillis() < end) {
                try { gate40.wait(Math.max(1, end - System.currentTimeMillis())); } catch (InterruptedException e) { break; }
            }
            return reply40;
        }
    }
    /** Bench: one command 0x40, then up to n replies. Does not key. */
    public java.util.List<Frame> diag40n(byte[] body, int n, int waitMs) throws IOException {
        if (body == null || body.length == 0 || body.length > 799 || n < 1 || n > 6) throw new IOException("body");
        synchronized (gate40) { reply40 = null; extra40.clear(); want40 = n; }
        send(Frame.request(0x40, body));
        long end = System.currentTimeMillis() + Math.max(1, waitMs);
        synchronized (gate40) {
            while (extra40.size() < n && System.currentTimeMillis() < end) {
                try { gate40.wait(Math.max(1, end - System.currentTimeMillis())); } catch (InterruptedException e) { break; }
            }
            want40 = 0;
            return new java.util.ArrayList<>(extra40);
        }
    }
    private volatile boolean rawHold;

    /** True while a firmware load owns the port. Ordinary commands are dropped so they cannot land in the bootloader. */
    public boolean rawSession() { return rawHold; }

    private void send(Frame fr) throws IOException {
        if (rawHold) return;
        if (fr.rw == 1 && watched(fr.cmd) && firstUnansweredAt == 0) firstUnansweredAt = System.currentTimeMillis();
        if (fr.rw == 1 && fr.cmd != CMD_STATUS) synchronized (stats) { CommandStats c = stats.computeIfAbsent(fr.cmd, k -> new CommandStats()); c.sent++; c.pending.addLast(System.currentTimeMillis()); while (c.pending.size() > 20) c.pending.removeFirst(); }
        port.send(fr);
    }
    private void answered(Frame f) {
        if (f.rw != 0) return;
        if (watched(f.cmd)) firstUnansweredAt = 0;                       // any answer (or refusal) shows the module is listening
        synchronized (stats) {
            CommandStats c = stats.get(f.cmd); if (c == null) return;
            Long t = c.pending.pollFirst(); c.answered++; if (f.sr != 0) c.refused++;
            if (t != null) { long ms = System.currentTimeMillis() - t; c.sumMs += ms; if (ms > c.maxMs) c.maxMs = ms; if (ms > 1000) c.slow++; }
        }
    }
    /** A copy of the statistics, command byte to stats. */
    public java.util.Map<Integer, CommandStats> stats() {
        java.util.Map<Integer, CommandStats> out = new java.util.TreeMap<>();
        synchronized (stats) { for (java.util.Map.Entry<Integer, CommandStats> e : stats.entrySet()) { CommandStats c = new CommandStats(); CommandStats o = e.getValue(); c.sent = o.sent; c.answered = o.answered; c.refused = o.refused; c.slow = o.slow; c.sumMs = o.sumMs; c.maxMs = o.maxMs; out.put(e.getKey(), c); } }
        return out;
    }
    /** RSSI replies, and how many of them were the module's "no reading" value (above -20 dBm). */
    public int[] rssiCounts() { return new int[] { rssiReadings, rssiNoReading }; }
    private final SysfsGpio power;
    private final StringBuilder banner = new StringBuilder();
    private volatile java.util.concurrent.CountDownLatch bootLatch = new java.util.concurrent.CountDownLatch(1);

    /** A raw signal reading in dBm on a module with this step and offset ({@link DeviceProfile#rssiStepDb}). */
    static double rssiDbm(int raw, double stepDb, double offsetDb) { return -120 + raw * stepDb + offsetDb; }
    public RadioEngine(DeviceProfile p, Events ev) {
        this.profile = p; this.ev = ev;
        FrameCodec codec = "agold".equals(p.protocol) ? new AgoldEnvelope() : FrameCodec.armor();
        this.port = new UartPort(p.controlPort, this, codec);
        if ("agold".equals(p.protocol)) port.setWriteDeadline(AgoldUsb.WRITE_DEADLINE_MS);   // a write the bridge never takes must not freeze the engine
        this.power = p.power();
    }

    /** True while a write to the port has missed its deadline (the Atom XL's bridge stopped taking data). */
    @Override public boolean writeStalled() { return port.writeStalled(); }

    public DeviceProfile profile() { return profile; }
    public boolean isOpen() { return port.isOpen(); }

    /** True on a phone whose module sits behind the Agold USB bridge (Atom XL): power, PTT and the port go through {@link AgoldUsb}. */
    private boolean agold() { return "agold".equals(profile.protocol); }

    public void open() throws IOException {
        if (agold()) ev.log(AgoldUsb.bridgeUp());          // the port exists only while the bridge is powered
        port.open(profile.serialLib != null && VendorSerial.isLoaded() ? profile.baud : 0);
        if (agold()) startReceiveWatch();
    }

    /** Route 00 reports from the Agold bridge: logged; state report 03 ("pcm error, reset mcu" in its stock app) is a fault. */
    @Override public void onBridge(int cmd, byte[] body) {
        ev.log(String.format(Locale.US, "bridge cmd=0x%02X body=%s", cmd, body == null ? "-" : java.util.Arrays.toString(body)));
        if (cmd == 0x08 && body != null && body.length >= 1 && body[0] == 3) ev.bridgeFault("bridge state report 03 (pcm error)");
    }

    // ---- Agold: receptions come from the bridge driver's /dev/stmcu, not from the port ----
    private volatile Thread rxWatch;
    private volatile long rxLineBytes;
    @Override public long receptionLineBytes() { return agold() ? rxLineBytes : -1; }
    private volatile java.io.Closeable rxSource;

    /** One reader on {@link AgoldUsb#STMCU}: the app's own file when policy allows it, else a root cat; each byte is an edge. */
    private void startReceiveWatch() {
        stopReceiveWatch();
        rxLineBytes = 0;
        Thread t = new Thread(() -> {
            java.io.InputStream in = null; Process p = null;
            try {
                try { in = new java.io.FileInputStream(AgoldUsb.STMCU); }
                catch (IOException e) {
                    p = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", "exec cat " + AgoldUsb.STMCU).start();
                    in = p.getInputStream();
                    ev.log("reception line: " + AgoldUsb.STMCU + " through root (" + e.getMessage() + ")");
                }
                final java.io.InputStream src = in; final Process proc = p;
                synchronized (rxLock) {
                    if (rxWatch != Thread.currentThread()) return;     // stopped before the line was open (finally closes it)
                    rxSource = () -> { try { src.close(); } finally { if (proc != null) proc.destroy(); } };
                }
                int b; final long started = System.currentTimeMillis(); int empty = 0;
                while (rxWatch == Thread.currentThread()) {
                    b = in.read();
                    if (b < 0) {                               // the driver sometimes returns an empty read (seen at a power-up): open it again
                        if (p != null || rxWatch != Thread.currentThread()) break;
                        if (empty++ == 0) ev.log("reception line: empty read, reopening " + AgoldUsb.STMCU);
                        try { in.close(); } catch (IOException ignored) { }
                        try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                        in = new java.io.FileInputStream(AgoldUsb.STMCU);
                        final java.io.InputStream again = in;
                        synchronized (rxLock) {                    // a stop between the reopen and here closed the old stream only
                            if (rxWatch != Thread.currentThread()) break;
                            rxSource = again::close;
                        }
                        continue;
                    }
                    rxLineBytes++;
                    // the bridge toggles the line at power-up (00 00 01 within ~1.7 s; read late, only a 00 is left): not a reception
                    if (System.currentTimeMillis() - started < AgoldUsb.POWER_UP_EDGES_MS) { ev.log(String.format(Locale.US, "reception line: 0x%02X at start, ignored", b)); continue; }
                    int code = AgoldUsb.receiveStatus(b);
                    if (code > 0) { ev.log("reception line: " + statusName(code)); ev.status(code, statusName(code)); } else ev.log(String.format(Locale.US, "reception line: byte 0x%02X", b));
                }
            } catch (IOException e) {
                if (rxWatch == Thread.currentThread()) ev.log("reception line: " + e.getMessage());
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) { }
                if (p != null) p.destroy();
            }
        }, "agold-rx-watch");
        t.setDaemon(true);
        rxWatch = t;
        t.start();
    }

    private final Object rxLock = new Object();
    private void stopReceiveWatch() {
        java.io.Closeable c;
        synchronized (rxLock) { rxWatch = null; c = rxSource; rxSource = null; }
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
    public void close() { stopReceiveWatch(); port.close(); lineShell.close(); }

    /**
     * A phone whose speaker route and PTT are its own lines around the module (the W6Plus DMR): the receive lines follow the
     * module's RECEIVE_START / RECEIVE_STOP, and PTT is the line, not 0x26 (its stock app never sends 0x26). One root shell
     * for them, not an su (and a Magisk toast) at every reception.
     */
    private boolean phoneLines() { return profile.rxLinesOn != null && !agold(); }
    private final RootShell lineShell = new RootShell();
    private void lines(String[][] l) {
        if (l == null || l.length == 0) return;
        StringBuilder c = new StringBuilder();
        for (String[] p : l) c.append("echo ").append(p[1]).append(" > ").append(p[0]).append("; ");
        String r = lineShell.run(c.toString());
        if (!r.isEmpty()) ev.log("lines: " + r);
    }

    /** Pause framing so a firmware load can use the same port. Ordinary commands are held until {@link #stopDivert}. */
    public void startDivert() { rawHold = true; firstUnansweredAt = 0; port.startDivert(); }
    public void stopDivert() { port.stopDivert(); rawHold = false; }
    public void writeRaw(byte[] b) throws IOException { port.writeRaw(b); }
    public int readRaw(int timeoutMs) throws IOException { return port.readRaw(timeoutMs); }
    public String powerNode() { return profile.powerNode; }
    public String pttNode() { return profile.pttNode; }

    public String powerState() { return power.read(); }

    /** pwd 0→1; the module then prints its banner ("…BF AA <GBK text>\r\n") within ~1 s and accepts commands. */
    /** @return true if the module was off and has just been switched on (caller must {@link #waitBooted}). */
    public boolean powerOn() throws IOException {
        if (agold()) { ev.log("module powered with the USB bridge (" + power.read() + ")"); return false; }   // open() powered it; the module sends no banner
        if (profile.lineSetup != null) for (String[] l : profile.lineSetup) { SysfsGpio.write(l[0], l[1]); ev.log("line " + l[0].substring(l[0].lastIndexOf('/') + 1) + " -> " + l[1]); }
        if (!power.isOn()) { bootLatch = new java.util.concurrent.CountDownLatch(1); power.set(true); ev.log("module power -> on (" + power.read() + ")"); return true; }
        ev.log("module already powered (" + power.read() + ")");
        return false;
    }

    public void powerOff() throws IOException {
        if (agold()) { stopReceiveWatch(); port.close(); ev.log(AgoldUsb.bridgeDown()); return; }      // off, the USB port back to the socket, the HAL running
        power.set(false); ev.log("module power -> off (" + power.read() + ")");
    }

    /**
     * A real restart of the module: the power line off, and the profile's other supply lines too (the Xplore 1's stock app
     * switches power, pwd and the PTT line off), a second's pause, then the normal power-up order. Needed where the power
     * line cannot be read back, so a module that is already on is never restarted by {@link #powerOn}.
     */
    public void powerCycle() throws IOException {
        if (agold()) {                                     // the port goes with the bridge's power: close, power-cycle, reopen
            stopReceiveWatch(); port.close(); ev.log(AgoldUsb.bridgeUp()); port.open(0); startReceiveWatch();
            firstUnansweredAt = 0; bootLatch.countDown(); ev.log("module power-cycled with the USB bridge"); return;
        }
        power.set(false);
        if (profile.lineSetup != null) for (int i = profile.lineSetup.length - 1; i >= 0; i--) { String[] l = profile.lineSetup[i]; SysfsGpio.write(l[0], "0"); }
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
        if (profile.lineSetup != null) for (String[] l : profile.lineSetup) SysfsGpio.write(l[0], l[1]);
        bootLatch = new java.util.concurrent.CountDownLatch(1); firstUnansweredAt = 0;
        power.set(true); ev.log("module power-cycled (" + power.read() + ")");
    }

    /** After pwd=1 the module needs a few seconds before it prints its banner and accepts commands. */
    public boolean waitBooted(long ms) {
        try { return bootLatch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return false; }
    }

    // ---- commands ----------------------------------------------------------------------------------

    public void queryVersion() throws IOException { send(Frame.request(CMD_VERSION, (byte) 1)); }
    public void queryRssi() throws IOException { send(Frame.request(CMD_RSSI, (byte) 1)); }
    public void queryCallerInfo() throws IOException { send(Frame.request(CMD_CALLER_INFO, (byte) 1)); }

    /**
     * The 0x2B body: {@code [0]} call type (0 private, 1 group), {@code [1..4]} caller ID LE (24 bits used),
     * {@code [5..8]} target LE (own ID or talkgroup). A module with the promiscuous-receive firmware (FW26) appends
     * {@code [9]} the received colour code and {@code [10]} the received time slot while the mode is on; the stock
     * 9-byte body gives -1 for both. Returns {type, caller, target, cc, slot}, or null when the body is too short.
     */
    public static int[] parseCallerInfo(byte[] b) {
        if (b == null || b.length < 9) return null;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.get() & 0xFF; int caller = bb.getInt() & 0xFFFFFF; int target = bb.getInt();
        int cc = -1, slot = -1;
        if (b.length >= 11) { cc = b[9] & 0x0F; slot = b[10] & 0x01; }
        return new int[] { type, caller, target, cc, slot };
    }
    public void setTot(int seconds) throws IOException { send(Frame.request(CMD_TOT, (byte) seconds)); }
    /** What the boot handshake sends on 0x3B: SignalDeck's transmit limit plus a margin, 0 (off) for none or over 255 s (TxLimit.moduleTot). */
    private volatile int bootTot = TxLimit.moduleTot(TxLimit.DEFAULT_S);
    @Override public void setBootTot(int seconds) { bootTot = Math.max(0, Math.min(255, seconds)); }
    public void setVolume(int v) throws IOException { send(Frame.request(CMD_VOLUME, (byte) v)); }
    public void setMic(int gain) throws IOException { send(Frame.request(CMD_MIC, (byte) gain)); }
    /** Monitor: 1 = hear every decodable digital call, 2 = normal filtering (enum 1/2 like the rest). */
    /** Global squelch level (0 open … 9), the same scale as a channel's sq byte. */
    public void setSquelch(int level) throws IOException { send(Frame.request(CMD_SQUELCH, (byte) Math.max(0, Math.min(9, level)))); }
    public void setMonitor(boolean on) throws IOException { send(Frame.request(CMD_MONITOR, (byte) (on ? 1 : 2))); }
    /** The monitor byte as given (the radio audio check tries 0 on a module whose squelch stays open after 2). */
    public void setMonitorRaw(int v) throws IOException { send(Frame.request(CMD_MONITOR, (byte) v)); }
    public void setSpkEn(boolean on) throws IOException { send(Frame.request(CMD_SPK_EN, (byte) (on ? 1 : 0))); }

    public void setInterrupt(boolean on) throws IOException { send(Frame.request(CMD_INTERRUPT, (byte) (on ? 1 : 2))); }
    /** 0x37 "polite" (busy-channel rule): QuickChat sends 0 = off, 1 = on after every channel (the Xplore 1's module). */
    public void setPolite(int v) throws IOException { send(Frame.request(CMD_POLITE, (byte) v)); }
    public void queryInit() throws IOException { send(Frame.request(CMD_QUERY_INIT, (byte) 1)); }

    /**
     * The OEM's cold-start handshake after the module boots (docs/deep-dive/03 §7a): version → channel →
     * interrupt (digital) / mic (analog) → TOT. Each step is acked; we just fire them in order with short gaps.
     * Profiles whose stock app never sends interrupt or mic ({@link DeviceProfile#bootMicInterrupt} false) skip that step.
     */
    public void bootHandshake(Runnable setChannel, boolean digital) throws IOException {
        if (!waitBooted(10000)) ev.log("no boot banner within 10 s — sending the handshake anyway");
        pause(200);
        queryVersion(); pause(150);
        setChannel.run(); pause(250);
        if (profile.bootMicInterrupt) { if (digital) setInterrupt(false); else setMic(4); pause(150); }
        setTot(bootTot); pause(150);   // 0 turns the module timer off (its own start-up value is 120); FW19 to FW31 bench images raise 0 and anything over 120 to 120
        ev.log("boot handshake sent");
    }

    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    /**
     * DMR text (0x2C): msgType 1 = single, 2 = all, 3 = group (then the group number follows), target id,
     * UTF-16LE text. Success/failure comes back as status 8/9.
     */
    public void sendSms(int msgType, int callNumber, int groupNumber, String text) throws IOException {
        if (agold()) { send(Frame.request(CMD_SEND_SMS, agoldSmsBody(msgType, callNumber, text))); return; }
        if (profile.smsUtf8) { send(Frame.request(CMD_SEND_SMS, utf8SmsBody(msgType, callNumber, text))); return; }
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        // Body: type, callNumber, [second id], UTF-16LE text. The stock app writes the second int only for
        // group texts, but the firmware reads a 9-byte header for private texts too: a private text sent with the
        // 5-byte header reached a BTECH 6X2 with its first two characters missing (2026-10-17). So the id is
        // written twice for private texts; the group id follows for group texts.
        boolean second = msgType == 3 || msgType == 1;
        ByteBuffer b = ByteBuffer.allocate(5 + (second ? 4 : 0) + t.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) msgType).putInt(callNumber);
        if (msgType == 3) b.putInt(groupNumber); else if (msgType == 1) b.putInt(callNumber);
        b.put(t);
        send(Frame.request(CMD_SEND_SMS, b.array()));
    }

    /**
     * The Agold stock app's text body (ExtModuleProtocol.sendDmr09Msg, for the Atom XL's module): type, the call number
     * (talkgroup or radio ID) little-endian, the text in UTF-16LE, then four zero bytes; no second ID.
     */
    static byte[] agoldSmsBody(int msgType, int callNumber, String text) {
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        ByteBuffer b = ByteBuffer.allocate(5 + t.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) msgType).putInt(callNumber).put(t).putInt(0);
        return b.array();
    }

    /** QuickChat's text body (DmrManager.sendMsg, the Xplore 1): type, the target little-endian, the text in UTF-8; no second ID. */
    static byte[] utf8SmsBody(int msgType, int callNumber, String text) {
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(5 + t.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) msgType).putInt(callNumber).put(t);
        return b.array();
    }

    /**
     * A received text's bytes: UTF-16LE (the Armor 26 Ultra's module, most DMR radios) or, when {@code utf8} is set and
     * the bytes do not look like UTF-16LE, UTF-8 (QuickChat reads every text as UTF-8). UTF-16LE text from a Latin script
     * has a zero in most odd bytes; UTF-8 text never contains a zero.
     */
    static String smsText(byte[] b, int off, boolean utf8) {
        int n = b.length - off, zeros = 0;
        for (int i = off + 1; i < b.length; i += 2) if (b[i] == 0) zeros++;
        boolean wide = !utf8 || (n >= 2 && zeros * 4 >= n);   // at least half the odd bytes are zero
        return new String(b, off, n, wide ? java.nio.charset.StandardCharsets.UTF_16LE : java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    public void fetchSms() throws IOException { send(Frame.request(CMD_FETCH_SMS, (byte) 1)); }

    /** PTT: launch=1 keys the transmitter, 2 releases (enum 1/2 again — 0 is NAKed and the TX keeps running; firmware caps a keyed TX at 30 s). */
    public void ptt(boolean down) throws IOException {
        if (agold()) {                                     // the HAL's startPtt / stopPtt: the bridge's PTT line, active low; no 0x26
            SysfsGpio.write(AgoldUsb.HANDLE_PTT, down ? "0" : "1");
            RootSetup.run("setprop vendor.agui.extmodule.start " + (down ? 1 : 0));
            return;
        }
        if (phoneLines() || keyLines()) { lines(down ? profile.txLinesOn : profile.txLinesOff); return; }
        send(Frame.request(CMD_LAUNCH, (byte) (down ? 1 : 2)));
    }

    /** A phone that keys its module with its own lines and never with 0x26 (the Xplore 1: intercom_ptt_control 0 = transmit). */
    private boolean keyLines() { return profile.txLinesOn != null && !agold(); }
    /** How this engine keys the module, for the device report. */
    public String keyPath() {
        if (agold()) return "the USB bridge's PTT line";
        if (phoneLines() || keyLines()) return "phone lines";
        return "module command 0x26";
    }

    /**
     * Analog channel, 19-byte body (little-endian ints): rxFreq, txFreq, band, power, sq, rxType, rxSubCode,
     * txType, txSubCode, pwrSave, volume, monitor, relay. band must be 1 on VHF (band=0 deafens the RX),
     * relay 2 = normal, sq 0 = squelch open (soft squelch is done in software).
     * Enum bytes are 1 = on / 2 = off — the MCU answers sr=1 (fail) to a 0 in pwrSave/monitor (measured 2026-10-17).
     */
    public void setAnalog(int rxHz, int txHz, int band, int power, int sq, int rxType, int rxSub, int txType, int txSub,
                          int volume, int monitor) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(rxHz).putInt(txHz).put((byte) band).put((byte) power).put((byte) sq).put((byte) rxType).put((byte) rxSub)
                .put((byte) txType).put((byte) txSub).put((byte) 2 /* pwrSave: 1 on, 2 off (OEM default 2; 0 is NAKed) */)
                .put((byte) volume).put((byte) (monitor == 1 ? 1 : 2) /* monitor: 1 on, 2 off */).put((byte) 2 /* relay normal */);
        send(Frame.request(CMD_SET_ANALOG, b.array()));
    }

    /**
     * Digital channel, 163-byte body: rxFreq, txFreq, localId, groups[32], txContact, contactType, power, cc,
     * inboundSlot, outboundSlot, channelMode, encryptSw(2=off), encryptKey[8], pwrSave, volume, mic, relay.
     * The MCU passes a group call only if its TG is in groups[]; the call number is added automatically.
     */
    public void setDigital(int rxHz, int txHz, int localId, int[] groups, int txContact, int contactType, int power, int cc,
                           int inSlot, int outSlot, int volume, int mic) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(163).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(rxHz).putInt(txHz).putInt(localId);
        int[] g = new int[32]; int n = 0;
        if (groups != null) for (int x : groups) if (n < 32 && x > 0) g[n++] = x;
        boolean has = false; for (int x : g) if (x == txContact) has = true;
        if (!has && contactType == 1 && n < 32) g[n++] = txContact;
        for (int x : g) b.putInt(x);
        b.putInt(txContact).put((byte) contactType).put((byte) power).put((byte) cc).put((byte) inSlot).put((byte) outSlot)
                .put((byte) 0 /* channelMode direct */).put((byte) 2 /* encrypt off */).put(new byte[8])
                .put((byte) 1).put((byte) volume).put((byte) mic).put((byte) 2);
        send(Frame.request(CMD_SET_DIGITAL, b.array()));
    }

    // ---- inbound ----------------------------------------------------------------------------------

    @Override public void onLog(String s) { ev.log(s); }

    @Override public void onRaw(byte[] b, int n) {
        // boot banner: 00 00 BF AA + GBK text + CR LF
        String txt;
        try { txt = new String(b, 0, n, Charset.forName("GBK")).replace("\u0000", "").trim(); } catch (Throwable t) { txt = Frame.hex(b, n); }
        boolean marker = false; for (int i = 0; i + 1 < n; i++) if ((b[i] & 0xFF) == 0xBF && (b[i + 1] & 0xFF) == 0xAA) marker = true;
        ev.log("RAW " + Frame.hex(b, n) + (txt.isEmpty() ? "" : "  \"" + txt + "\""));
        if (marker) { bootLatch.countDown(); ev.booted(txt); }
    }

    @Override public void onFrame(Frame f) {
        ev.log("RX " + f);
        answered(f);
        byte[] b = f.body;
        if (f.cmd == 0x40 && f.rw == 0) {
            synchronized (gate40) {
                reply40 = f;
                if (want40 > 0 && extra40.size() < want40) extra40.add(f);
                gate40.notifyAll();
            }
        }
        switch (f.cmd) {
            case CMD_STATUS: {
                int code = b.length > 0 ? b[0] & 0xFF : -1;
                if (!agold()) try { send(new Frame(CMD_STATUS, 1, 1, new byte[] { 1 })); } catch (IOException ignored) { }   // the OEM acks every push; the Agold stock app does not
                if (phoneLines() && code == 1) { lines(profile.rxLinesOn); if (profile.appPlayback) lines(profile.muteLines); }   // app playback: the module's speaker route stays off
                else if (phoneLines() && code == 2) lines(profile.rxLinesOff);
                ev.status(code, statusName(code));
                break;
            }
            case CMD_RSSI:
                if (f.rw == 0 && b.length >= 1) { int raw = b[0] & 0xFF; double dbm = raw > 0 ? rssiDbm(raw, profile.rssiStepDb, profile.rssiOffsetDb) : -999; rssiReadings++; if (dbm > -20) { dbm = -999; rssiNoReading++; } ev.rssi(raw, dbm); }   // above -20 dBm is not a reading (the Xplore 1 reports raw 226, "-7")
                break;
            case CMD_VERSION:
                if (b.length >= 4) ev.version(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(), new String(b).trim());
                break;
            case CMD_CALLER_INFO: {
                int[] ci = parseCallerInfo(b);
                if (ci != null) ev.callerInfo(ci[0], ci[1], ci[2], ci[3], ci[4]);
                break;
            }
            case CMD_FETCH_SMS:
                if (b.length >= 5) {
                    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                    int type = bb.get() & 0xFF; int from = bb.getInt() & 0xFFFFFF;   // a DMR ID is 24 bits (the Agold stock app reads three bytes)
                    String text = smsText(b, 5, profile.smsUtf8);
                    ev.sms(type, from, text);
                }
                break;
            case CMD_INIT:
                ev.log("module init frame (0xAA)");
                break;
            default:
                ev.ack(f.cmd, f.sr == 0);     // sr 0 = success, 1 = fail, 2 = checksum error
        }
    }

    public static String statusName(int s) {
        switch (s) {
            case 1: return "RECEIVE_START"; case 2: return "RECEIVE_STOP"; case 3: return "SEND_START"; case 4: return "SEND_STOP";
            case 5: return "SMS_RECEIVED"; case 6: return "RELAY_ACTIVITY_TIME_OUT"; case 7: return "CHANNEL_BUSY";
            case 8: return "SMS_SENT_SUCCESS"; case 9: return "SMS_SENT_FAIL";
            case 10: return "MIX_DIGITAL_RX_START"; case 11: return "MIX_DIGITAL_RX_STOP"; case 12: return "MIX_ANALOG_RX_START"; case 13: return "MIX_ANALOG_RX_STOP";
            default: return String.format(Locale.US, "STATUS_%d", s);
        }
    }
}
