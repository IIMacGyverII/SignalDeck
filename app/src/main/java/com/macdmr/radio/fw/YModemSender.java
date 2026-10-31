/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.fw;

import java.io.IOException;

/**
 * Sender-driven YModem-1K for the module bootloader. The packet reader is paused by the caller.
 * The knock and the byte {@code 0x31} are what this module needs before it sends {@code C}.
 */
public final class YModemSender {
    public static final int SOH = 0x01, STX = 0x02, EOT = 0x04, ACK = 0x06, NAK = 0x15, CAN = 0x18, CRC_REQ = 0x43;
    private static final int PROMPT_MS = 10_000, BLOCK_MS = 15_000, HEADER_MS = 60_000, TRIES = 3, RETRIES = 6;

    /** The open control port. {@code read} returns 0–255, or −1 when the wait expires. */
    public interface Wire {
        void write(byte[] b) throws IOException;
        int read(int timeoutMs) throws IOException;
        void close() throws IOException;
    }

    public interface Pins { void set(String node, boolean high) throws IOException; }
    public interface Pause { void ms(long n) throws InterruptedException; }
    public interface Log { void line(String s); }
    /** Called after the power line is raised again, once the packet reader is running. */
    public interface Tag { String read() throws IOException; }

    public static final class Result {
        public final String code, detail;
        public Result(String code, String detail) { this.code = code; this.detail = detail == null ? "" : detail; }
        public boolean ok() { return "ok".equals(code); }
        @Override public String toString() { return detail.isEmpty() ? code : code + "(" + detail + ")"; }
    }

    /** Named stages of a load, for the firmware page. */
    public interface Watch {
        void phase(String name);
        void block(int done, int total, long ackMs);
        void retry();
    }

    /** Polled before each data block. When it returns true the port is closed and the load stops. */
    public interface Halt { boolean halt(); }

    private final Pause pause;
    private final Log log;
    private Watch watch;
    private Halt halt;
    private int blockPauseMs = 0;

    public YModemSender(Pause pause, Log log) {
        this.pause = pause == null ? ms -> Thread.sleep(ms) : pause;
        this.log = log == null ? s -> { } : log;
    }

    public YModemSender watch(Watch watch) { this.watch = watch; return this; }
    public YModemSender halt(Halt halt) { this.halt = halt; return this; }
    /** Milliseconds to wait after an ACK before the next data block. 0 passed three loads and was faster than 50. */
    public YModemSender blockPause(int ms) { this.blockPauseMs = Math.max(0, ms); return this; }

    /** CRC-16-CCITT, init 0, polynomial 0x1021, not reflected. The value covers the data bytes only. */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return crc;
    }

    /** One block: start, sequence, complement, data, CRC high, CRC low. */
    public static byte[] block(int start, int seq, byte[] data) {
        byte[] out = new byte[3 + data.length + 2];
        out[0] = (byte) start;
        out[1] = (byte) seq;
        out[2] = (byte) (seq ^ 0xFF);
        System.arraycopy(data, 0, out, 3, data.length);
        int crc = crc16(data, 0, data.length);
        out[out.length - 2] = (byte) (crc >> 8);
        out[out.length - 1] = (byte) crc;
        return out;
    }

    public static byte[] nameBlock(String name, int size) {
        byte[] data = new byte[128];
        byte[] text = (name + "\0" + size).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, data, 0, Math.min(text.length, 128));
        return block(SOH, 0, data);
    }

    /**
     * @param abort50 close the port after half the data blocks have been accepted and return {@code aborted}
     */
    public Result send(byte[] image, String name, String pwd, String ptt, Wire wire, Pins pins, boolean abort50, Tag tag) {
        long t0 = System.currentTimeMillis();
        try {
            if (watch != null) watch.phase("update");
            if (!prompt(pwd, ptt, wire, pins)) return fail("no-prompt", "no bootloader prompt", t0);   // i18n: bootloader protocol log/result code (developer)
            if (watch != null) watch.phase("handshake");
            byte[] header = nameBlock(name, image.length);
            if (!ack(wire, header, true)) return lastFail(t0);
            if (watch != null) watch.phase("transfer");
            int blocks = (image.length + 1023) / 1024;
            int stopAt = abort50 ? Math.max(1, blocks / 2) : Integer.MAX_VALUE;
            int seq = 1;
            for (int i = 0; i < blocks; i++) {
                if (halt != null && halt.halt()) {
                    log.line("stopped after block " + i + " of " + blocks);   // i18n: bootloader protocol log/result code (developer)
                    try { wire.close(); } catch (IOException e) { log.line("close: " + e.getMessage()); }   // i18n: bootloader protocol log/result code (developer)
                    return new Result("aborted", "stopped after block " + i);   // i18n: bootloader protocol log/result code (developer)
                }
                pause.ms(blockPauseMs);
                drain(wire);
                byte[] data = new byte[1024];
                int n = Math.min(1024, image.length - i * 1024);
                System.arraycopy(image, i * 1024, data, 0, n);
                long sent = System.currentTimeMillis();
                if (!ack(wire, block(STX, seq & 0xFF, data), false)) return lastFail(t0);
                long ackMs = System.currentTimeMillis() - sent;
                log.line("block " + (i + 1) + "/" + blocks + " ack " + ackMs + " ms");   // i18n: bootloader protocol log/result code (developer)
                if (watch != null) watch.block(i + 1, blocks, ackMs);
                seq++;
                if (i + 1 == stopAt) {
                    log.line("abort50 after block " + stopAt + " of " + blocks);   // i18n: bootloader protocol log/result code (developer)
                    try { wire.close(); } catch (IOException e) { log.line("close: " + e.getMessage()); }   // i18n: bootloader protocol log/result code (developer)
                    return new Result("aborted", "closed after block " + stopAt);   // i18n: bootloader protocol log/result code (developer)
                }
            }
            if (watch != null) watch.phase("finish");
            if (!eot(wire)) return lastFail(t0);
            byte[] end = block(SOH, 0, new byte[128]);
            if (!ack(wire, end, false)) return lastFail(t0);
            if (watch != null) watch.phase("restart");
            pins.set(pwd, false);
            pause.ms(500);
            pins.set(pwd, true);
            log.line("power raised");   // i18n: bootloader protocol log/result code (developer)
            if (tag == null) return new Result("ok", "");
            String got = tag.read();
            log.line("total " + (System.currentTimeMillis() - t0) + " ms tag " + got);   // i18n: bootloader protocol log/result code (developer)
            return new Result("ok", got == null ? "" : got.trim());
        } catch (IOException e) {
            log.line("io-error " + e.getMessage());   // i18n: bootloader protocol log/result code (developer)
            return new Result("io-error", e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("io-error", "interrupted");   // i18n: bootloader protocol log/result code (developer)
        }
    }

    private String failCode = "timeout";

    private Result lastFail(long t0) {
        log.line(failCode + " at " + (System.currentTimeMillis() - t0) + " ms");   // i18n: bootloader protocol log/result code (developer)
        return new Result(failCode, "");
    }

    private Result fail(String code, String why, long t0) {
        log.line(why + " at " + (System.currentTimeMillis() - t0) + " ms");   // i18n: bootloader protocol log/result code (developer)
        return new Result(code, why);
    }

    /** Drop bytes that arrived while the sender was waiting, so a late CAN is not the next block's answer. */
    private void drain(Wire wire) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = wire.read(20)) >= 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b));
            if (sb.length() > 180) {
                log.line("drain " + sb);   // i18n: bootloader protocol log/result code (developer)
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) log.line("drain " + sb);   // i18n: bootloader protocol log/result code (developer)
    }

    private boolean prompt(String pwd, String ptt, Wire wire, Pins pins) throws IOException, InterruptedException {
        for (int t = 1; t <= TRIES; t++) {
            knock(pwd, ptt, pins);
            long t0 = System.currentTimeMillis();
            wire.write(new byte[] { 0x31 });
            log.line("knock " + t + " wrote 0x31");   // i18n: bootloader protocol log/result code (developer)
            long end = t0 + PROMPT_MS;
            while (System.currentTimeMillis() < end) {
                int b = wire.read((int) Math.max(1, end - System.currentTimeMillis()));
                if (b < 0) break;
                log.line(String.format("rx %02X", b));   // i18n: bootloader protocol log/result code (developer)
                if (b == CRC_REQ) {
                    log.line("prompt C in " + (System.currentTimeMillis() - t0) + " ms");   // i18n: bootloader protocol log/result code (developer)
                    return true;
                }
            }
            log.line("knock " + t + " silent");   // i18n: bootloader protocol log/result code (developer)
        }
        return false;
    }

    private void knock(String pwd, String ptt, Pins pins) throws IOException, InterruptedException {
        pins.set(pwd, false); pause.ms(50);
        pins.set(ptt, false); pause.ms(50);
        pins.set(pwd, true); pause.ms(1000);
        pins.set(ptt, true);
        log.line("knock done");   // i18n: bootloader protocol log/result code (developer)
    }

    /** @param thenC block 0 is accepted only after ACK and then C */
    private boolean ack(Wire wire, byte[] packet, boolean thenC) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            log.line(String.format("tx %02X seq %d len %d", packet[0] & 0xFF, packet[1] & 0xFF, packet.length));   // i18n: bootloader protocol log/result code (developer)
            wire.write(packet);
            if (thenC && watch != null) watch.phase("erasing");
            long end = System.currentTimeMillis() + (thenC ? HEADER_MS : BLOCK_MS);
            boolean sawAck = false;
            while (System.currentTimeMillis() < end) {
                int b = wire.read((int) Math.max(1, end - System.currentTimeMillis()));
                if (b < 0) { failCode = "timeout"; break; }
                log.line(String.format("rx %02X", b));   // i18n: bootloader protocol log/result code (developer)
                if (b == CAN) { failCode = "cancelled"; return false; }
                if (b == NAK) { failCode = "nak-limit"; break; }
                if (b == ACK) {
                    if (!thenC) return true;
                    sawAck = true;
                    continue;
                }
                if (b == CRC_REQ && sawAck) return true;
            }
            if (attempt == RETRIES) return false;
            log.line("retry " + (attempt + 1));   // i18n: bootloader protocol log/result code (developer)
            if (watch != null) watch.retry();
        }
        return false;
    }

    private boolean eot(Wire wire) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            wire.write(new byte[] { (byte) EOT });
            long end = System.currentTimeMillis() + BLOCK_MS;
            while (System.currentTimeMillis() < end) {
                int b = wire.read((int) Math.max(1, end - System.currentTimeMillis()));
                if (b < 0) { failCode = "timeout"; break; }
                log.line(String.format("rx %02X", b));   // i18n: bootloader protocol log/result code (developer)
                if (b == CAN) { failCode = "cancelled"; return false; }
                if (b == NAK) { failCode = "nak-limit"; break; }
                if (b == ACK) return true;
            }
            if (attempt == RETRIES) return false;
            log.line("eot retry " + (attempt + 1));   // i18n: bootloader protocol log/result code (developer)
        }
        return false;
    }
}
