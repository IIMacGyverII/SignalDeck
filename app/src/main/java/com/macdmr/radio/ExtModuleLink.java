/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The Ulefone Armor 3WT's radio daemon, {@code /system/bin/extmodule} (run at boot by the owner's Magisk module): it owns
 * the USB bridge ({@code /dev/ttyACM0}, the bridge driver's sysfs files, the radio's USB sound card) and its stock app
 * talks to it over two TCP sockets on 127.0.0.1, 9980 for commands and 9985 for audio (phonedmrapp
 * docs/armor3wt/stock-app-review.md §2). Every message carries a 12-byte header of little-endian words: the magic
 * {@code 0xF12FFE4F}, the type (0 = a control word, 1 = data), the payload length. On 9980 data is one {@code 7E 96 69}
 * bridge frame each way; on 9985 it is PCM. The daemon sends what it reads from the bridge only to the client that wrote
 * to it last, so the stock app has to be stopped while SignalDeck uses it.
 */
final class ExtModuleLink {
    private ExtModuleLink() { }

    static final String HOST = "127.0.0.1";
    static final int CMD_PORT = 9980, AUDIO_PORT = 9985;
    /** {@link DeviceProfile#controlPort} of a phone whose commands go through the daemon. */
    static final String CONTROL_PORT = "tcp:" + HOST + ":" + CMD_PORT;
    static final int MAGIC = 0xF12FFE4F, TYPE_CONTROL = 0, TYPE_DATA = 1, HEADER = 12;
    /** A payload longer than this is not a message (a frame is at most a few hundred bytes, PCM 1920). */
    static final int MAX_PAYLOAD = 16384;

    /** Daemon controls on 9980 (its switch at 0x71d8): start, stop (MCU off too), PTT on / off, MCU start. */
    static final int CTL_START = 1, CTL_STOP = 2, CTL_PTT_ON = 3, CTL_PTT_OFF = 4, CTL_MCU_START = 7;
    /** Audio controls on 9985: transmit audio on / off, receive stream on / off. */
    static final int AUDIO_TX_ON = 1, AUDIO_TX_OFF = 2, AUDIO_RX_ON = 3, AUDIO_RX_OFF = 4;
    /** The stock app waits this long between the stop and the MCU start (resetMcu; 4.0 s in the tester's log). */
    static final long RESET_WAIT_MS = 4000;
    /** Longest wait for the bridge's "MCU started" report (route 00, 0x06) after the MCU start; the tester's took 1.4 s. */
    static final long MCU_START_WAIT_MS = 10000;
    /** The property that switches the daemon and its stock app to 32 kHz audio (1280-byte frames); 48 kHz otherwise. */
    static final String PROP_32K = "ro.agold.extmodule.32k";
    static final String DAEMON = "/system/bin/extmodule";

    /** True when {@code controlPort} names the daemon's socket rather than a device file. */
    static boolean isSocket(String controlPort) { return controlPort != null && controlPort.startsWith("tcp:"); }

    /** The audio rate the daemon uses, from {@code getprop ro.agold.extmodule.32k}. */
    static int audioRate(String prop32k) {
        String v = prop32k == null ? "" : prop32k.trim().toLowerCase(java.util.Locale.US);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on") ? 32000 : 48000;
    }
    /** One 10 ms stereo 16-bit frame at {@code rate}: 1920 bytes at 48 kHz, 1280 at 32 kHz. */
    static int frameBytes(int rate) { return rate / 100 * 4; }

    /** One message: header and payload. */
    static byte[] wrap(int type, byte[] payload, int off, int len) {
        byte[] out = new byte[HEADER + len];
        putLe(out, 0, MAGIC); putLe(out, 4, type); putLe(out, 8, len);
        System.arraycopy(payload, off, out, HEADER, len);
        return out;
    }
    static byte[] wrap(int type, byte[] payload) { return wrap(type, payload, 0, payload.length); }
    /** A control message: type 0, the control number as one little-endian word. */
    static byte[] control(int cmd) { byte[] w = new byte[4]; putLe(w, 0, cmd); return wrap(TYPE_CONTROL, w); }

    static void putLe(byte[] b, int at, int v) { b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); b[at + 2] = (byte) (v >> 16); b[at + 3] = (byte) (v >> 24); }
    static int getLe(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24; }

    /**
     * Splits the byte stream from a socket into messages. Bytes before a header (none in practice) are skipped up to the
     * next magic; a header announcing more than {@link #MAX_PAYLOAD} is skipped as garbage. Not thread-safe: one reader.
     */
    static final class Reader {
        interface Sink { void message(int type, byte[] payload, int off, int len); }
        private byte[] buf = new byte[8192];
        private int n;
        long skipped;

        void feed(byte[] b, int off, int len, Sink sink) {
            if (n + len > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, n + len));
            System.arraycopy(b, off, buf, n, len); n += len;
            int p = 0;
            while (n - p >= HEADER) {
                if (getLe(buf, p) != MAGIC) { p++; skipped++; continue; }
                int type = getLe(buf, p + 4), plen = getLe(buf, p + 8);
                if (plen < 0 || plen > MAX_PAYLOAD) { p++; skipped++; continue; }
                if (n - p < HEADER + plen) break;
                sink.message(type, buf, p + HEADER, plen);
                p += HEADER + plen;
            }
            if (p > 0) { System.arraycopy(buf, p, buf, 0, n - p); n -= p; }
        }
    }

    static Socket connect(int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(HOST, port), timeoutMs);
        return s;
    }

    /**
     * The command socket as the two streams {@link UartPort} uses: what is written goes out as one data message per
     * write (UartPort writes whole frames), and what is read is the data payloads joined, controls dropped.
     */
    static final class CommandStreams {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        private final Object writeLock = new Object();
        private final OutputStream raw;

        CommandStreams(Socket s) throws IOException {
            socket = s;
            raw = s.getOutputStream();
            final InputStream sin = s.getInputStream();
            in = new InputStream() {
                private final Reader reader = new Reader();
                private final ByteArrayOutputStream ready = new ByteArrayOutputStream();
                private byte[] pending = new byte[0]; private int pos;
                private final byte[] chunk = new byte[2048];
                @Override public int read() throws IOException { byte[] one = new byte[1]; int r = read(one, 0, 1); return r <= 0 ? -1 : one[0] & 0xFF; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    while (pos >= pending.length) {
                        int r = sin.read(chunk);
                        if (r < 0) return -1;
                        ready.reset();
                        reader.feed(chunk, 0, r, (type, p, o, l) -> { if (type == TYPE_DATA) ready.write(p, o, l); });
                        pending = ready.toByteArray(); pos = 0;
                    }
                    int k = Math.min(len, pending.length - pos);
                    System.arraycopy(pending, pos, b, off, k); pos += k;
                    return k;
                }
                @Override public void close() throws IOException { socket.close(); }
            };
            out = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException { send(wrap(TYPE_DATA, b, off, len)); }
                @Override public void close() throws IOException { socket.close(); }
            };
        }

        /** A daemon control (start, stop, PTT, MCU start). */
        void control(int cmd) throws IOException { send(ExtModuleLink.control(cmd)); }

        private void send(byte[] m) throws IOException { synchronized (writeLock) { raw.write(m); raw.flush(); } }

        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
