/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Host checks of the extmodule socket framing (Armor 3WT) against the tester's log. A local socket, no radio. */
public class ExtModuleLinkTest {
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test public void headerMatchesTheLoggedReply() {
        // the daemon's answer to a channel write, as the intercom app logged it (parseFrame strOldData)
        byte[] logged = hex("4FFE2FF1010000000C0000007E9669013600000100FDC981");
        assertArrayEquals(logged, ExtModuleLink.wrap(ExtModuleLink.TYPE_DATA, hex("7E9669013600000100FDC981")));
    }

    @Test public void controlIsTypeZeroWithOneWord() {
        assertArrayEquals(hex("4FFE2FF1000000000400000007000000"), ExtModuleLink.control(ExtModuleLink.CTL_MCU_START));
    }

    @Test public void readerSplitsMessagesAcrossChunks() {
        byte[] a = ExtModuleLink.wrap(1, hex("7E9669000600000101FEF881"));
        byte[] b = ExtModuleLink.control(3);
        byte[] all = new byte[3 + a.length + b.length];
        all[0] = 1; all[1] = 2; all[2] = 3;                                // stray bytes before a header are skipped
        System.arraycopy(a, 0, all, 3, a.length); System.arraycopy(b, 0, all, 3 + a.length, b.length);
        List<String> got = new ArrayList<>();
        ExtModuleLink.Reader r = new ExtModuleLink.Reader();
        for (int i = 0; i < all.length; i += 5) r.feed(all, i, Math.min(5, all.length - i), (type, p, off, len) -> got.add(type + ":" + len));
        assertEquals(java.util.Arrays.asList("1:12", "0:4"), got);
        assertEquals(3, r.skipped);
    }

    @Test public void audioRateFollowsTheProperty() {
        assertEquals(48000, ExtModuleLink.audioRate(""));
        assertEquals(48000, ExtModuleLink.audioRate(null));
        assertEquals(32000, ExtModuleLink.audioRate("true"));
        assertEquals(32000, ExtModuleLink.audioRate("1\n"));
        assertEquals(1920, ExtModuleLink.frameBytes(48000));                // the stock app's AUDIO_FRAME_SIZE
        assertEquals(1280, ExtModuleLink.frameBytes(32000));
    }

    @Test public void onlyTheSocketPortIsASocket() {
        assertTrue(ExtModuleLink.isSocket(ExtModuleLink.CONTROL_PORT));
        assertFalse(ExtModuleLink.isSocket("/dev/ttyACM0"));
        assertFalse(ExtModuleLink.isSocket(null));
    }

    @Test public void commandStreamsWrapWritesAndUnwrapReads() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(server.getInetAddress(), server.getLocalPort());
            Socket daemon = server.accept();
            ExtModuleLink.CommandStreams cs = new ExtModuleLink.CommandStreams(client);
            byte[] req = hex("7E9669012501000101FCD981");
            cs.out.write(req, 0, req.length);
            cs.control(ExtModuleLink.CTL_START);
            InputStream din = daemon.getInputStream();
            byte[] seen = new byte[req.length + 12 + 16];
            int n = 0; while (n < seen.length) n += din.read(seen, n, seen.length - n);
            assertArrayEquals(ExtModuleLink.wrap(1, req), java.util.Arrays.copyOf(seen, req.length + 12));
            assertArrayEquals(ExtModuleLink.control(1), java.util.Arrays.copyOfRange(seen, req.length + 12, seen.length));
            // the daemon's reply: a control the app ignores, then the version reply split over two writes
            OutputStream dout = daemon.getOutputStream();
            byte[] ver = ExtModuleLink.wrap(1, hex("7E96690125000013766572302E352E345F3437304D5F544F4E4500940F81"));
            dout.write(ExtModuleLink.control(2)); dout.write(ver, 0, 10); dout.flush(); Thread.sleep(50); dout.write(ver, 10, ver.length - 10); dout.flush();
            byte[] got = new byte[ver.length - 12];
            n = 0; while (n < got.length) n += cs.in.read(got, n, got.length - n);
            assertArrayEquals(java.util.Arrays.copyOfRange(ver, 12, ver.length), got);
            cs.close(); daemon.close();
        }
    }
}
