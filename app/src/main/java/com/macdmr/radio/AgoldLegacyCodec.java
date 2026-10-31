/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/**
 * The Ulefone Armor 3WT's module: the Agold bridge envelope around the older ("legacy") command set its intercom app uses
 * when {@code ro.agold.extmodule.dmr09} is not set (module {@code ver0.5.4_470M_TONE}; phonedmrapp
 * docs/armor3wt/stock-app-review.md §3-§4). {@link RadioEngine} keeps speaking the Armor command numbers; this codec
 * translates the ones that have a legacy equivalent and {@link #carries refuses} the rest, so nothing with a colliding
 * number (the legacy {@code 0x35} and {@code 0x36} are channel writes) ever reaches the module.
 *
 * <pre>
 * Armor (engine)            legacy (wire)
 * 0x23 analog, 19 bytes  -> 0x35, 15 bytes: band (0 / 128), power, rx LE, tx LE, sq, rx tone type + 1, code, tx type + 1, code
 * 0x22 digital, 163      -> 0x36, 156: power, rx LE, tx LE, own ID BE, cc, contact type (1 / 2 / 4), call BE, encryption (1 / 255), key, 32 groups BE; no slot
 * 0x34 version           -> 0x25          0x2E volume -> 0x02          0x2B caller info -> 0x10
 * 0x2C text (legacy body) -> 0x07         0x2D text fetch -> 0x11
 * push 0x04 0/2, 1/3     -> status 1 (RECEIVE_START), 2 (RECEIVE_STOP);   push 0x07 0x70 -> status 5 (a text is waiting)
 * </pre>
 */
public final class AgoldLegacyCodec implements FrameCodec {
    static final int L_VOLUME = 0x02, L_RX_STATE = 0x04, L_SEND_TEXT = 0x07, L_CALL_INFO = 0x10, L_FETCH_TEXT = 0x11, L_VERSION = 0x25, L_ANALOG = 0x35, L_DIGITAL = 0x36;
    /** The 0x07 push that announces a received text. */
    static final int TEXT_WAITING = 0x70;
    /** The call number the stock app sends for an all call (contact type 4). */
    static final int ALL_CALL = 0xFFFFFA;

    private final AgoldEnvelope envelope = new AgoldEnvelope(true);
    private Consumer<String> log;

    @Override public void setBridge(BridgeListener listener) { envelope.setBridge(listener); }
    @Override public void setLog(Consumer<String> log) { this.log = log; envelope.setLog(log); }

    @Override public boolean carries(int cmd) { return legacyCmd(cmd) >= 0; }

    /** The legacy command for an Armor one, or -1 when this module has none. */
    static int legacyCmd(int armor) {
        switch (armor) {
            case RadioEngine.CMD_SET_ANALOG: return L_ANALOG;
            case RadioEngine.CMD_SET_DIGITAL: return L_DIGITAL;
            case RadioEngine.CMD_VERSION: return L_VERSION;
            case RadioEngine.CMD_VOLUME: return L_VOLUME;
            case RadioEngine.CMD_CALLER_INFO: return L_CALL_INFO;
            case RadioEngine.CMD_SEND_SMS: return L_SEND_TEXT;
            case RadioEngine.CMD_FETCH_SMS: return L_FETCH_TEXT;
            default: return -1;
        }
    }

    @Override public byte[] encode(Frame frame) {
        int cmd = legacyCmd(frame.cmd);
        if (cmd < 0) return null;
        byte[] body;
        switch (frame.cmd) {
            case RadioEngine.CMD_SET_ANALOG: body = analogBody(frame.body); break;
            case RadioEngine.CMD_SET_DIGITAL: body = digitalBody(frame.body); break;
            default: body = frame.body;                       // version, volume, caller info, fetch: the same one byte; text: built for this module by the engine
        }
        if (body == null) return null;
        int[] data = new int[body.length];
        for (int i = 0; i < data.length; i++) data[i] = body[i] & 0xFF;
        return AgoldFrames.request(0x01, cmd, data);
    }

    /** The Armor 19-byte analog body as the legacy 15 bytes; null when it is not 19 bytes. */
    static byte[] analogBody(byte[] a) {
        if (a == null || a.length != 19) return null;
        ByteBuffer in = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        int rx = in.getInt(), tx = in.getInt();
        int band = a[8] & 0xFF, power = a[9] & 0xFF, sq = a[10] & 0xFF, rxType = a[11] & 0xFF, rxSub = a[12] & 0xFF, txType = a[13] & 0xFF, txSub = a[14] & 0xFF;
        ByteBuffer out = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) (band == 1 ? 128 : 0)).put((byte) power).putInt(rx).putInt(tx).put((byte) sq)
                .put((byte) toneType(rxType)).put((byte) (rxType == 0 ? 0 : rxSub)).put((byte) toneType(txType)).put((byte) (txType == 0 ? 0 : txSub));
        return out.array();
    }
    /** Armor tone type (0 none, 1 CTCSS, 2 DCS, 3 DCS inverted) as the legacy one (1-4); the code index is the same list in both. */
    static int toneType(int armor) { return armor >= 0 && armor <= 3 ? armor + 1 : 1; }

    /** The Armor 163-byte digital body as the legacy 156 bytes (no slot, IDs big-endian); null when it is not 163 bytes. */
    static byte[] digitalBody(byte[] a) {
        if (a == null || a.length != 163) return null;
        ByteBuffer in = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        int rx = in.getInt(), tx = in.getInt(), own = in.getInt();
        int[] groups = new int[32]; for (int i = 0; i < 32; i++) groups[i] = in.getInt();
        int call = in.getInt();
        int type = a[144] & 0xFF, power = a[145] & 0xFF, cc = a[146] & 0xFF, enc = a[150] & 0xFF;
        int legacyType = type == 0 ? 1 : type == 2 ? 4 : 2;      // Armor 0 private, 1 group, 2 all call
        ByteBuffer out = ByteBuffer.allocate(156);               // big-endian: the IDs; frequencies put little-endian below
        out.put((byte) power);
        out.order(ByteOrder.LITTLE_ENDIAN).putInt(rx).putInt(tx).order(ByteOrder.BIG_ENDIAN);
        out.putInt(own & 0xFFFFFF).put((byte) cc).put((byte) legacyType).putInt(legacyType == 4 ? ALL_CALL : call & 0xFFFFFF)
                .put((byte) (enc == 1 ? 1 : 255));
        out.put(a, 151, 8);                                      // the key as the engine wrote it (zeros: SignalDeck never encrypts)
        for (int g : groups) out.putInt(g & 0xFFFFFF);
        return out.array();
    }

    /**
     * The text body this module takes (ExtModuleProtocol.sendMsg): type (2 private, 9 group), the call number as three bytes
     * big-endian, the text in UTF-16LE, four zero bytes.
     */
    static byte[] textBody(boolean group, int callNumber, String text) {
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] b = new byte[4 + t.length + 4];
        b[0] = (byte) (group ? 9 : 2); b[1] = (byte) (callNumber >> 16); b[2] = (byte) (callNumber >> 8); b[3] = (byte) callNumber;
        System.arraycopy(t, 0, b, 4, t.length);
        return b;
    }

    @Override public int tryDecode(byte[] b, int off, int avail, Frame[] out) {
        int used = envelope.tryDecode(b, off, avail, out);
        if (used > 0 && out[0] != null) out[0] = translate(out[0]);
        return used;
    }

    /** A legacy module frame as the engine's Armor frame (unknown commands are handed on as they came). */
    Frame translate(Frame f) {
        byte[] b = f.body;
        int first = b.length > 0 ? b[0] & 0xFF : -1;
        switch (f.cmd) {
            case L_RX_STATE:
                if (first == 0 || first == 2) return status(1);
                if (first == 1 || first == 3) return status(2);
                break;
            case L_SEND_TEXT:
                if (first == TEXT_WAITING) return status(5);
                return new Frame(RadioEngine.CMD_SEND_SMS, f.rw, Math.max(0, first), new byte[0]);
            case L_FETCH_TEXT:
                if (b.length > 3) return new Frame(RadioEngine.CMD_FETCH_SMS, f.rw, 0, fetchedText(b));
                break;
            case L_VERSION: return new Frame(RadioEngine.CMD_VERSION, f.rw, 0, b);
            case L_ANALOG: return new Frame(RadioEngine.CMD_SET_ANALOG, f.rw, Math.max(0, first), new byte[0]);
            case L_DIGITAL: return new Frame(RadioEngine.CMD_SET_DIGITAL, f.rw, Math.max(0, first), new byte[0]);
            case L_VOLUME: return new Frame(RadioEngine.CMD_VOLUME, f.rw, Math.max(0, first), new byte[0]);
            default: break;
        }
        if (log != null) log.accept(String.format(java.util.Locale.US, "legacy module frame cmd=0x%02X rw=%d len=%d", f.cmd, f.rw, b.length));
        return f;
    }

    private static Frame status(int code) { return new Frame(RadioEngine.CMD_STATUS, 0, 0, new byte[] { (byte) code }); }

    /** The legacy 0x11 body (sender 3 bytes BE, UTF-16LE text) as the engine's 0x2D body: type 1, sender LE, the text. */
    static byte[] fetchedText(byte[] b) {
        int from = (b[0] & 0xFF) << 16 | (b[1] & 0xFF) << 8 | (b[2] & 0xFF);
        ByteBuffer out = ByteBuffer.allocate(5 + b.length - 3).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 1).putInt(from).put(b, 3, b.length - 3);
        return out.array();
    }
}
