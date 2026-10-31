/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

/**
 * Transmit audio for a module fed over a serial port (the Xplore 1): the microphone delivers its samples in bursts (a
 * tester's recording, 2026-10-31, showed the voice chopped about every 64 ms), but the module wants one 160-byte block
 * (10 ms) at a time, as QuickChat sends it. The pacer cuts whatever arrives into 160-byte blocks, waits until
 * {@code prebuffer} blocks are queued, then hands out one block per 10 ms. After an underrun it fills up again; a backlog
 * over {@code maxBlocks} loses its oldest blocks so the delay stays short. Time comes in from the caller (testable).
 */
final class PcmPacer {
    static final int BLOCK = 160;
    static final long PERIOD_NS = 10_000_000L;
    private final int prebuffer, maxBlocks;
    private final java.util.ArrayDeque<byte[]> q = new java.util.ArrayDeque<>();
    private final byte[] part = new byte[BLOCK]; private int partLen;
    private long nextNs = -1;
    int sent, dropped, underruns;

    PcmPacer(int prebuffer, int maxBlocks) { this.prebuffer = prebuffer; this.maxBlocks = maxBlocks; }

    /** Queue {@code n} bytes of 8 kHz mono 16-bit audio. */
    synchronized void offer(byte[] b, int off, int n) {
        while (n > 0) {
            int k = Math.min(n, BLOCK - partLen);
            System.arraycopy(b, off, part, partLen, k); partLen += k; off += k; n -= k;
            if (partLen == BLOCK) { q.addLast(part.clone()); partLen = 0; }
        }
        while (q.size() > maxBlocks) { q.pollFirst(); dropped++; }
    }

    /** The block due at {@code nowNs}, or null when none is due yet (or the queue is filling up again). */
    synchronized byte[] poll(long nowNs) {
        if (nextNs < 0) { if (q.size() < prebuffer) return null; nextNs = nowNs; }
        if (nowNs < nextNs) return null;
        byte[] b = q.pollFirst();
        if (b == null) { underruns++; nextNs = -1; return null; }
        if (nowNs - nextNs > 2 * PERIOD_NS) nextNs = nowNs;   // after a stall, go on from now rather than send a burst to catch up
        nextNs += PERIOD_NS;
        sent++;
        return b;
    }

    /** How long the writer may sleep before the next block can be due. */
    synchronized long waitNs(long nowNs) { return nextNs < 0 ? 2_000_000L : Math.max(0, Math.min(nextNs - nowNs, PERIOD_NS)); }

    synchronized String stats() { return sent + " blocks, " + underruns + " underruns, " + dropped + " dropped"; }   // i18n: log line
}
