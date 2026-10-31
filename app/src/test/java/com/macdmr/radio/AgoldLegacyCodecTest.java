/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Host checks of the Armor 3WT's legacy command set against the frames in its intercom app's log (beta.19 report) and the
 * body layouts of its ExtModuleProtocol (phonedmrapp docs/armor3wt/stock-app-review.md §3-§4). No radio.
 */
public class AgoldLegacyCodecTest {
    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
    private static Frame decode(AgoldLegacyCodec c, byte[] raw) {
        Frame[] out = new Frame[1];
        assertEquals(raw.length, c.tryDecode(raw, 0, raw.length, out));
        return out[0];
    }
    /** A module push or reply (rw 00) with a correct checksum. */
    private static byte[] reply(int route, int cmd, int... body) {
        byte[] f = AgoldFrames.request(route, cmd, body);
        f[5] = 0;
        int[] u = new int[f.length]; for (int i = 0; i < f.length; i++) u[i] = f[i] & 0xFF;
        int c = AgoldFrames.checksum(u, 3, 8 + body.length);
        f[8 + body.length] = (byte) (c >> 8); f[9 + body.length] = (byte) c;
        return f;
    }

    @Test public void versionRequestIsTheStockAppsFrame() {
        assertArrayEquals(hex("7E9669012501000101FCD981"), new AgoldLegacyCodec().encode(Frame.request(RadioEngine.CMD_VERSION, (byte) 1)));
    }

    @Test public void versionReplyFromTheLogBecomesTheEngineVersion() {
        Frame f = decode(new AgoldLegacyCodec(), hex("7E96690125000013766572302E352E345F3437304D5F544F4E4500940F81"));
        assertEquals(RadioEngine.CMD_VERSION, f.cmd);
        assertEquals("ver0.5.4_470M_TONE", new String(f.body).trim().replace("\0", ""));
    }

    @Test public void digitalChannelAcceptedFromTheLog() {
        Frame f = decode(new AgoldLegacyCodec(), hex("7E9669013600000100FDC981"));
        assertEquals(RadioEngine.CMD_SET_DIGITAL, f.cmd);
        assertEquals(0, f.sr);
        Frame refused = decode(new AgoldLegacyCodec(), reply(1, 0x35, 1));
        assertEquals(RadioEngine.CMD_SET_ANALOG, refused.cmd);
        assertEquals(1, refused.sr);
    }

    @Test public void bridgeReportsGoToTheListener() {
        AgoldLegacyCodec c = new AgoldLegacyCodec();
        List<Integer> heard = new ArrayList<>();
        c.setBridge((cmd, body) -> heard.add(cmd));
        assertNull(decode(c, hex("7E9669000600000101FEF881")));
        assertNull(decode(c, hex("7E9669000100000E323031383132323131353137353584A081")));
        assertEquals(java.util.Arrays.asList(0x06, 0x01), heard);
    }

    @Test public void receptionPushesBecomeStatus() {
        AgoldLegacyCodec c = new AgoldLegacyCodec();
        for (int v : new int[] { 0, 2 }) { Frame f = decode(c, reply(1, 0x04, v)); assertEquals(RadioEngine.CMD_STATUS, f.cmd); assertEquals(1, f.body[0]); }
        for (int v : new int[] { 1, 3 }) { Frame f = decode(c, reply(1, 0x04, v)); assertEquals(RadioEngine.CMD_STATUS, f.cmd); assertEquals(2, f.body[0]); }
        Frame text = decode(c, reply(1, 0x07, 0x70));
        assertEquals(RadioEngine.CMD_STATUS, text.cmd);
        assertEquals(5, text.body[0]);
    }

    @Test public void analogBodyIsTheLegacyFifteen() {
        ByteBuffer a = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        a.putInt(446_006_250).putInt(446_006_250).put((byte) 1).put((byte) 1).put((byte) 3).put((byte) 1).put((byte) 12)
                .put((byte) 0).put((byte) 7).put((byte) 2).put((byte) 8).put((byte) 2).put((byte) 2);
        byte[] l = AgoldLegacyCodec.analogBody(a.array());
        assertEquals(15, l.length);
        assertEquals(128, l[0] & 0xFF);                                    // wide
        assertEquals(1, l[1]);                                             // high power
        assertEquals(446_006_250, ByteBuffer.wrap(l, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(446_006_250, ByteBuffer.wrap(l, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(3, l[10]);
        assertEquals(2, l[11]); assertEquals(12, l[12]);                    // CTCSS (type 1 -> 2), index kept
        assertEquals(1, l[13]); assertEquals(0, l[14]);                     // none (0 -> 1), no code
        byte[] frame = new AgoldLegacyCodec().encode(Frame.request(RadioEngine.CMD_SET_ANALOG, a.array()));
        assertEquals(0x35, frame[4] & 0xFF);
        assertEquals(15, frame[7]);
        assertNull("a body of another length is never sent", AgoldLegacyCodec.analogBody(new byte[15]));
    }

    @Test public void digitalBodyHasNoSlotAndBigEndianIds() {
        ByteBuffer a = ByteBuffer.allocate(163).order(ByteOrder.LITTLE_ENDIAN);
        a.putInt(446_006_250).putInt(446_006_250).putInt(1234567);
        a.putInt(9); for (int i = 1; i < 32; i++) a.putInt(0);
        a.putInt(9).put((byte) 1 /* group */).put((byte) 0).put((byte) 1).put((byte) 1).put((byte) 1).put((byte) 0).put((byte) 2).put(new byte[8])
                .put((byte) 1).put((byte) 8).put((byte) 0).put((byte) 2);
        byte[] l = AgoldLegacyCodec.digitalBody(a.array());
        assertEquals(156, l.length);
        assertEquals(0, l[0]);                                             // low power
        assertEquals(446_006_250, ByteBuffer.wrap(l, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(1234567, ByteBuffer.wrap(l, 9, 4).getInt());          // own ID big-endian
        assertEquals(1, l[13]);                                            // colour code
        assertEquals(2, l[14]);                                            // group
        assertEquals(9, ByteBuffer.wrap(l, 15, 4).getInt());               // TG 9 big-endian
        assertEquals(255, l[19] & 0xFF);                                   // encryption off
        assertEquals(9, ByteBuffer.wrap(l, 28, 4).getInt());               // first RX group
        for (int i = 32; i < 156; i++) assertEquals("rest of the RX list is zero at " + i, 0, l[i]);
        // private and all call
        a.put(144, (byte) 0); assertEquals(1, AgoldLegacyCodec.digitalBody(a.array())[14]);
        a.put(144, (byte) 2); byte[] all = AgoldLegacyCodec.digitalBody(a.array());
        assertEquals(4, all[14]); assertEquals(AgoldLegacyCodec.ALL_CALL, ByteBuffer.wrap(all, 15, 4).getInt());
    }

    @Test public void onlyCommandsWithALegacyEquivalentAreCarried() {
        AgoldLegacyCodec c = new AgoldLegacyCodec();
        for (int cmd : new int[] { 0x22, 0x23, 0x2B, 0x2C, 0x2D, 0x2E, 0x34 }) assertTrue(String.format("0x%02X", cmd), c.carries(cmd));
        // the Armor numbers that collide here (0x35 interrupt = legacy analog channel, 0x36 status ack = legacy digital channel), and the rest
        for (int cmd : new int[] { 0x26, 0x27, 0x2A, 0x2F, 0x30, 0x31, 0x32, 0x35, 0x36, 0x37, 0x3B, 0x3C, 0x40 }) {
            assertFalse(String.format("0x%02X", cmd), c.carries(cmd));
            assertNull(c.encode(Frame.request(cmd, (byte) 1)));
        }
    }

    @Test public void textBodiesBothWays() {
        byte[] b = AgoldLegacyCodec.textBody(true, 9, "Hi");
        assertArrayEquals(new byte[] { 9, 0, 0, 9, 'H', 0, 'i', 0, 0, 0, 0, 0 }, b);
        assertEquals(2, AgoldLegacyCodec.textBody(false, 1234567, "x")[0]);
        byte[] send = new AgoldLegacyCodec().encode(Frame.request(RadioEngine.CMD_SEND_SMS, b));
        assertEquals(0x07, send[4] & 0xFF);
        // a fetched text: sender 3 bytes big-endian, UTF-16LE
        Frame f = decode(new AgoldLegacyCodec(), reply(1, 0x11, 0x12, 0xD6, 0x87, 'O', 0, 'K', 0));
        assertEquals(RadioEngine.CMD_FETCH_SMS, f.cmd);
        ByteBuffer bb = ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, bb.get());
        assertEquals(1234567, bb.getInt());
        assertEquals("OK", RadioEngine.smsText(f.body, 5, false));
    }
}
