/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

/**
 * How a phone wraps the module's commands on its control port. The Armor framing is the default. A phone whose
 * module speaks the same commands inside another envelope (the Agold bridge) supplies its own codec; {@link RadioEngine}
 * does not change.
 */
public interface FrameCodec {
    /** A route-00 report from the bridge, never a module frame. */
    interface BridgeListener { void onBridge(int cmd, byte[] body); }

    byte[] encode(Frame frame);

    /**
     * Same convention as {@link Frame#tryDecode}: the frame length consumed, 0 when more bytes are needed, or −1 when
     * the bytes at {@code off} are not a frame. A consumed envelope that is not a module frame leaves {@code out[0]}
     * null (a bridge report has already been handed to the listener).
     */
    int tryDecode(byte[] b, int off, int avail, Frame[] out);

    /** Where route-00 reports go. The Armor codec never calls it. */
    default void setBridge(BridgeListener listener) { }

    /**
     * False for a command this codec cannot put on the wire (a module with another command set has no equivalent):
     * {@link RadioEngine} then does not send it at all. Every command for the Armor framing and the Agold envelope.
     */
    default boolean carries(int cmd) { return true; }

    /** One line when an unlisted module command is seen. The Armor codec never calls it. */
    default void setLog(java.util.function.Consumer<String> log) { }

    /** Today's {@code 68 … 10} framing. */
    static FrameCodec armor() { return ARMOR; }

    FrameCodec ARMOR = new FrameCodec() {
        @Override public byte[] encode(Frame frame) { return frame.encode(); }
        @Override public int tryDecode(byte[] b, int off, int avail, Frame[] out) { return Frame.tryDecode(b, off, avail, out); }
    };
}
