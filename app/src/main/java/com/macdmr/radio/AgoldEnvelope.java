/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (serial and module diagnostics: log lines, shell commands and device-report text)

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Agold USB bridge envelope around the Armor command set: {@code 7E 96 69 | route | cmd | rw | len(2, BE) | body |
 * checksum(2, BE) | 81}. Route 01 is the module. The result of a set command is the body's one byte, not a header
 * field, and only on a reply ({@code rw} 00). A module push keeps that byte in the body. A command that is
 * not in either table is still delivered: one reply byte is the result, anything else is data. Each such
 * command is logged once. Route 00 is the bridge itself and never becomes a module frame. Route 02 is the bridge's
 * USB audio report ({@code 67 01} when the host opens the radio's capture stream, {@code 66 01} when it closes it,
 * measured on the Atom XL 2026-10-28): logged once, never a module frame. Receptions are not reported on the port at
 * all on V067 (no 0x36 push); they come from the bridge driver's {@code /dev/stmcu} ({@link AgoldUsb#STMCU}). The
 * bridge pads short USB packets with zero bytes, which are skipped.
 */
public final class AgoldEnvelope implements FrameCodec {
    /** Module replies whose body is the data (version, RSSI, caller, text, read-back, the symbol engine). */
    // 0x36 is the module's status push and keeps its code in the body: a real push (a text arriving, code 5) carries rw 00,
    // measured on the Atom XL 2026-10-28, so rw cannot tell it from a reply; SignalDeck never sends a 0x36 request here
    private static final int[] DATA = { 0x24, 0x25, 0x2B, 0x2D, 0x32, 0x34, 0x36, 0x40 };
    /** Module replies whose one body byte is the result. */
    private static final int[] SET = { 0x22, 0x23, 0x2E, 0x30, 0x31 };
    /** Route 02: the bridge's audio stream opened / closed by the host. */
    static final int AUDIO_ON = 0x67, AUDIO_OFF = 0x66;

    private BridgeListener bridge;
    private Consumer<String> log;
    private final Set<Integer> logged = new HashSet<>();
    /** True: every module frame is handed on as it came (cmd, rw, body), for a codec that translates another command set ({@link AgoldLegacyCodec}). */
    private final boolean raw;

    public AgoldEnvelope() { this(false); }
    AgoldEnvelope(boolean raw) { this.raw = raw; }

    @Override public void setBridge(BridgeListener listener) { bridge = listener; }
    @Override public void setLog(Consumer<String> log) { this.log = log; }

    @Override public byte[] encode(Frame frame) {
        int[] data = new int[frame.body.length];
        for (int i = 0; i < data.length; i++) data[i] = frame.body[i] & 0xFF;
        return AgoldFrames.request(0x01, frame.cmd, data);
    }

    @Override public int tryDecode(byte[] b, int off, int avail, Frame[] out) {
        out[0] = null;
        if (avail < 1) return 0;
        if (b[off] == 0) { int z = 1; while (z < avail && b[off + z] == 0) z++; return z; }   // USB packet padding
        if ((b[off] & 0xFF) != 0x7E) return -1;
        if (avail < 3) return 0;
        if ((b[off + 1] & 0xFF) != 0x96 || (b[off + 2] & 0xFF) != 0x69) return -1;
        if (avail < 8) return 0;
        int len = ((b[off + 6] & 0xFF) << 8) | (b[off + 7] & 0xFF);
        if (len > 1024) return -1;
        int total = 11 + len;
        if (avail < total) return 0;
        if ((b[off + total - 1] & 0xFF) != 0x81) return -1;
        int[] u = new int[total];
        for (int i = 0; i < total; i++) u[i] = b[off + i] & 0xFF;
        int end = 8 + len;
        if (AgoldFrames.checksum(u, 3, end) != ((u[end] << 8) | u[end + 1])) return -1;
        int route = u[3], cmd = u[4], rw = u[5];
        byte[] body = Arrays.copyOfRange(b, off + 8, off + end);
        if (route == 0) {
            if (bridge != null) bridge.onBridge(cmd, body);
            return total;
        }
        if (route == 2) { audioReport(cmd, body); return total; }
        if (route != 1) return total;
        out[0] = moduleFrame(cmd, rw, body);
        return total;
    }

    private Frame moduleFrame(int cmd, int rw, byte[] body) {
        if (raw || listed(DATA, cmd)) return new Frame(cmd, rw, 0, body);
        boolean reply = rw == 0;
        if (listed(SET, cmd)) {
            if (reply && body.length >= 1) return new Frame(cmd, rw, body[0] & 0xFF, new byte[0]);
            return new Frame(cmd, rw, 0, body);
        }
        if (logged.add(cmd) && log != null) log.accept(String.format(Locale.US, "envelope cmd=0x%02X unlisted", cmd));
        if (reply && body.length == 1) return new Frame(cmd, rw, body[0] & 0xFF, new byte[0]);
        return new Frame(cmd, rw, 0, body);
    }

    /** A route 02 report: logged once per command, never a module frame. */
    private void audioReport(int cmd, byte[] body) {
        if (!logged.add(0x200 | cmd) || log == null) return;
        String what = cmd == AUDIO_ON ? " (audio stream opened)" : cmd == AUDIO_OFF ? " (audio stream closed)" : "";
        log.accept(String.format(Locale.US, "envelope route 02 cmd=0x%02X len=%d%s", cmd, body.length, what));
    }

    private static boolean listed(int[] cmds, int cmd) {
        for (int c : cmds) if (c == cmd) return true;
        return false;
    }
}
