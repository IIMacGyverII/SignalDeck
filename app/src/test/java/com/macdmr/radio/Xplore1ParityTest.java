/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** The Xplore 1 drives its radio as QuickChat does (phonedmrapp docs/xplore1/stock-app-review.md); every other phone is unchanged. */
public class Xplore1ParityTest {

    private static final String PTT = "/sys/devices/platform/intercom/intercom_ptt_control";

    @Test public void xplore1KeysAndTalksAsQuickChatDoes() {
        DeviceProfile p = DeviceProfile.XPLORE_1;
        assertEquals(3, p.revision);
        assertFalse("still untested on hardware", p.verified);
        assertTrue("the guided session only", p.txTestUnverified);
        assertArrayEquals(new String[] { PTT, "0" }, p.txLinesOn[0]);             // 0 = transmit, no 0x26
        assertArrayEquals(new String[] { PTT, "1" }, p.txLinesOff[0]);
        assertNull("receive needs no lines of its own", p.rxLinesOn);
        assertTrue(p.txAudioOnSendStart);
        assertEquals(200, p.txAudioTailMs);
        assertEquals(1500, p.minKeyGapMs);
        assertEquals(0, p.txAudioLeadMs);
        assertFalse(p.bootMicInterrupt);
        assertEquals(0, p.politeAfterChannel);
        assertTrue(p.smsUtf8);
        assertFalse("its updater ships no image: no loader on an undiffed variant", p.firmwareLoader());
        assertArrayEquals(new int[] { 0x22, 0x23, 0x27, 0x28, 0x2C, 0x2D, 0x2F, 0x30, 0x34, 0x35, 0x37, 0x3B }, p.stockCommands);
        assertEquals(8, p.analogVolume);                                          // QuickChat sends 0; 8 is what receive was verified with
        DeviceProfile c = p.copy(true, false);                                    // an advisory's copy keeps them
        assertTrue(c.txAudioOnSendStart); assertEquals(200, c.txAudioTailMs); assertEquals(0, c.politeAfterChannel); assertTrue(c.smsUtf8);
        assertSame(p.txLinesOn, c.txLinesOn);
        String path = TxTestSession.pttPath(p);
        assertTrue(path, path.contains("intercom_ptt_control=0") && path.contains("no 0x26") && path.contains("SEND_START") && path.contains("200 ms"));
    }

    @Test public void otherPhonesKeepTheirKeyAndTextBehaviour() {
        for (DeviceProfile p : new DeviceProfile[] { DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.ARMOR_20WT, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.ATOM_XL, DeviceProfile.UNKNOWN }) {
            String n = p.name;
            assertFalse(n, p.txAudioOnSendStart); assertEquals(n, 0, p.txAudioTailMs); assertEquals(n, -1, p.politeAfterChannel); assertFalse(n, p.smsUtf8);
            assertTrue(n, p.txLinesOn == null || p.rxLinesOn != null);           // lines keying stays the W6 family's (with its receive lines)
        }
        assertNull(DeviceProfile.ARMOR_26_ULTRA.txLinesOn);
        assertFalse(TxTestSession.pttPath(DeviceProfile.ARMOR_26_ULTRA).contains("SEND_START"));
    }

    @Test public void parityFieldsComeFromTheProfileList() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject("{\"id\":\"x\",\"revision\":1,\"name\":\"X\",\"controlPort\":\"/dev/ttyS0\",\"baud\":57600,\"audio\":\"UART_PCM\","
                + "\"txAudioOnSendStart\":true,\"txAudioTailMs\":200,\"politeAfterChannel\":0,\"smsUtf8\":true}");
        DeviceProfile p = DeviceProfile.fromJson(o);
        assertTrue(p.txAudioOnSendStart); assertEquals(200, p.txAudioTailMs); assertEquals(0, p.politeAfterChannel); assertTrue(p.smsUtf8);
        o.put("txAudioTailMs", 99999).put("politeAfterChannel", 7);              // clamped
        p = DeviceProfile.fromJson(o); assertEquals(1000, p.txAudioTailMs); assertEquals(1, p.politeAfterChannel);
        o.remove("txAudioOnSendStart"); o.remove("txAudioTailMs"); o.remove("politeAfterChannel"); o.remove("smsUtf8");
        p = DeviceProfile.fromJson(o);                                           // absent: the Armor 26 Ultra's behaviour
        assertFalse(p.txAudioOnSendStart); assertEquals(0, p.txAudioTailMs); assertEquals(-1, p.politeAfterChannel); assertFalse(p.smsUtf8);
    }

    @Test public void quickChatTextBody() {
        // DmrManager.sendMsg: type, target LE32, then the text's bytes (UTF-8)
        assertArrayEquals(new byte[] { 3, 9, 0, 0, 0, 'H', 'i' }, RadioEngine.utf8SmsBody(3, 9, "Hi"));
        assertArrayEquals(new byte[] { 2, (byte) 0x87, (byte) 0xD6, 0x12, 0, (byte) 0xC3, (byte) 0xA9 }, RadioEngine.utf8SmsBody(2, 1234567, "\u00E9"));
    }

    @Test public void receivedTextsAreReadInEitherEncoding() {
        byte[] head = { 0, 1, 0, 0, 0 };
        byte[] u8 = concat(head, "Hallo \u00E9".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] u16 = concat(head, "Hallo \u00E9".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        assertEquals("Hallo \u00E9", RadioEngine.smsText(u8, 5, true));      // QuickChat's own texts
        assertEquals("Hallo \u00E9", RadioEngine.smsText(u16, 5, true));     // another DMR radio's
        assertEquals("Hallo \u00E9", RadioEngine.smsText(u16, 5, false));    // other phones: UTF-16LE as before
        assertEquals("", RadioEngine.smsText(head, 5, true));
    }

    @Test public void textStepOnlyWhereTheBodyDiffers() {
        assertNotNull(stepOf(TxReport.plan(true, true), "text"));
        assertNull(stepOf(TxReport.plan(true, false), "text"));
        assertNull("no text step without DMR", stepOf(TxReport.plan(false, true), "text"));
        assertEquals(TxReport.plan(true).size(), TxReport.plan(true, false).size());
        assertEquals(TxReport.PASS, TxReport.textVerdict(8, "yes"));
        assertEquals(TxReport.FAIL, TxReport.textVerdict(9, "yes"));
        assertEquals(TxReport.FAIL, TxReport.textVerdict(8, "no"));
        assertEquals(TxReport.RECORDED, TxReport.textVerdict(0, "not checked"));
        assertEquals(TxReport.RECORDED, TxReport.textVerdict(8, "not checked"));
    }

    private static TxReport.Step stepOf(java.util.List<TxReport.Step> l, String id) { for (TxReport.Step s : l) if (s.id.equals(id)) return s; return null; }
    private static byte[] concat(byte[] a, byte[] b) { byte[] r = java.util.Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
}
