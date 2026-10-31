/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The MCU control UART as plain files. The line is configured by the stock app's own serial JNI
 * ({@link VendorSerial}) when the profile names it, else the tty is
 * opened as a plain file and {@link RootSetup#ensureTty} has to have set raw mode and the baud. SELinux access: see {@link RootSetup}. A reader thread splits the byte stream into {@link Frame}s and hands anything
 * that is not a frame (the module's boot banner, "00 00 BF AA …\r\n") to the listener as raw bytes.
 */
public final class UartPort {
    public interface Listener {
        void onFrame(Frame f);
        void onRaw(byte[] b, int n);      // non-frame bytes (boot banner)
        void onLog(String s);
        /** A report from the phone's USB bridge (route 00). The Armor port never calls this. */
        default void onBridge(int cmd, byte[] body) {
            onLog(String.format(java.util.Locale.US, "bridge cmd=0x%02X len=%d", cmd, body == null ? 0 : body.length));
        }
    }

    private final String path;
    private final Listener listener;
    private final FrameCodec codec;
    private java.io.InputStream in;
    private java.io.OutputStream out;
    private ExtModuleLink.CommandStreams daemon;            // the Armor 3WT: the port is extmodule's command socket
    private VendorSerial.Port native_;
    private volatile long writeDeadlineMs;                  // 0: plain blocking writes; else through a TimedWriter (the Atom XL's USB bridge)
    private TimedWriter timed;
    private Thread reader;
    private volatile boolean running;
    /** While set, received bytes are queued for the firmware loader instead of being framed. */
    private volatile boolean divert;
    private final LinkedBlockingQueue<byte[]> diverted = new LinkedBlockingQueue<>();
    private byte[] divertCur;
    private int divertPos;

    public UartPort(String path, Listener l) { this(path, l, FrameCodec.armor()); }

    /** {@code codec} null keeps the Armor framing. The codec's bridge reports are delivered to {@link Listener#onBridge}. */
    public UartPort(String path, Listener l, FrameCodec codec) {
        this.path = path; this.listener = l;
        this.codec = codec == null ? FrameCodec.armor() : codec;
        this.codec.setBridge(l::onBridge);
        this.codec.setLog(l::onLog);
    }

    public synchronized void open() throws IOException { open(0); }

    /** @param baud 0 = plain file open (line already configured); else configure it through the stock serial JNI. */
    public synchronized void open(int baud) throws IOException {
        if (in != null) return;
        if (ExtModuleLink.isSocket(path)) {
            daemon = new ExtModuleLink.CommandStreams(ExtModuleLink.connect(ExtModuleLink.CMD_PORT, 3000));
            in = daemon.in; out = daemon.out;
            listener.onLog("extmodule command socket " + ExtModuleLink.HOST + ":" + ExtModuleLink.CMD_PORT + " open");
        } else if (baud > 0 && VendorSerial.isLoaded()) {
            native_ = VendorSerial.openPort(path, baud);
            if (native_ == null) throw new IOException("serial open " + path + " @ " + baud + " failed");
            in = new FileInputStream(native_.fd());
            out = new FileOutputStream(native_.fd());
            listener.onLog("UART " + path + " open @ " + baud + " (stock serial library)");
        } else {
            in = new FileInputStream(path);
            out = new FileOutputStream(path);
            listener.onLog("UART " + path + " open (plain file)");
        }
        if (writeDeadlineMs > 0) timed = new TimedWriter(out, writeDeadlineMs, "uart-writer");
        running = true;
        reader = new Thread(this::readLoop, "uart-reader");
        reader.start();
    }

    public synchronized void close() {
        running = false;
        if (timed != null) { timed.shutdown(); timed = null; }
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        if (native_ != null) { native_.release(); native_ = null; }
        if (daemon != null) { daemon.close(); daemon = null; }
        in = null; out = null;
    }

    /** A daemon control on the extmodule command socket (start, stop, PTT, MCU start); only on a socket port. */
    public synchronized void control(int cmd) throws IOException {
        if (daemon == null) throw new IOException("not the extmodule socket");
        daemon.control(cmd);
        listener.onLog("extmodule control " + cmd);
    }

    public boolean isOpen() { return in != null; }

    /** Writes that miss this deadline fail instead of blocking (0 = plain writes). Takes effect at the next open. */
    public void setWriteDeadline(long ms) { writeDeadlineMs = ms; }
    /** True while a write has missed its deadline and none has finished since. */
    public boolean writeStalled() { TimedWriter t = timed; return t != null && t.stalled(); }
    private void put(byte[] b) throws IOException {
        if (timed != null) timed.write(b);
        else { out.write(b); out.flush(); }
    }

    /** Hand the byte stream to {@link #readRaw} until {@link #stopDivert}. */
    public synchronized void startDivert() {
        divert = true;
        diverted.clear();
        divertCur = null;
        divertPos = 0;
    }

    public synchronized void stopDivert() { divert = false; diverted.clear(); divertCur = null; }

    public synchronized void writeRaw(byte[] b) throws IOException {
        if (out == null) throw new IOException("port closed");
        put(b);
    }

    /** One diverted byte, or −1 when {@code timeoutMs} expires. */
    public int readRaw(int timeoutMs) throws IOException {
        try {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (divertCur != null && divertPos < divertCur.length) return divertCur[divertPos++] & 0xFF;
                long left = end - System.currentTimeMillis();
                if (left <= 0) return -1;
                divertCur = diverted.poll(left, TimeUnit.MILLISECONDS);
                divertPos = 0;
                if (divertCur == null) return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    public synchronized void send(Frame f) throws IOException {
        if (out == null) throw new IOException("port closed");
        byte[] b = codec.encode(f);
        if (b == null) { listener.onLog("not sent (no equivalent on this module): " + f); return; }
        put(b);
        listener.onLog("TX " + f + "  <" + Frame.hex(b, b.length) + ">");
    }

    private void readLoop() {
        byte[] buf = new byte[4096]; int n = 0;
        byte[] chunk = new byte[1024];
        Frame[] one = new Frame[1];
        while (running) {
            int r;
            try { r = in.read(chunk); } catch (IOException e) { if (running) listener.onLog("UART read: " + e.getMessage()); break; }
            if (r <= 0) continue;
            if (divert) {
                byte[] c = new byte[r];
                System.arraycopy(chunk, 0, c, 0, r);
                diverted.offer(c);
                n = 0;
                continue;
            }
            if (n + r > buf.length) n = 0;                    // garbage flood: resync
            System.arraycopy(chunk, 0, buf, n, r); n += r;
            int off = 0, rawStart = -1;
            while (off < n) {
                int used = codec.tryDecode(buf, off, n - off, one);
                if (used > 0) {
                    if (rawStart >= 0) { flushRaw(buf, rawStart, off - rawStart); rawStart = -1; }
                    if (one[0] != null) listener.onFrame(one[0]);
                    off += used;
                } else if (used == 0) {
                    break;                                     // need more bytes for this frame
                } else {
                    if (rawStart < 0) rawStart = off;
                    off++;
                }
            }
            if (rawStart >= 0 && off == n) {                  // trailing non-frame bytes: deliver line-wise
                int end = n;
                // keep a partial frame head that might still complete
                flushRaw(buf, rawStart, end - rawStart); off = end;
            }
            if (off > 0) { System.arraycopy(buf, off, buf, 0, n - off); n -= off; }
        }
    }

    private void flushRaw(byte[] b, int off, int len) {
        if (len <= 0) return;
        byte[] c = new byte[len]; System.arraycopy(b, off, c, 0, len);
        listener.onRaw(c, len);
    }
}
