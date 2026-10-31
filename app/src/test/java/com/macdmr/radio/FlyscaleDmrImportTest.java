/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** The W6Plus DMR's stock channel rows (com.flyscale.dmr): the module's bytes as hex text, synthetic values. */
public class FlyscaleDmrImportTest {
    private static Map<String, String> analog(String rx, String tx, String band, String power, String sq, String rxType, String rxSub, String txType, String txSub) {
        Map<String, String> r = new HashMap<>();
        r.put("RX_FREQ", rx); r.put("TX_FREQ", tx); r.put("BAND", band); r.put("POWER", power); r.put("SQ", sq);
        r.put("RX_TYPE", rxType); r.put("RX_SUBCODE", rxSub); r.put("TX_TYPE", txType); r.put("TX_SUBCODE", txSub);
        r.put("PWRSAVE", "01"); r.put("VOLUME", "01"); r.put("MONITOR", "02"); r.put("RELAY", "02");
        return r;
    }
    private static Map<String, String> digital(String rx, String groups, String contact, String contactType, String cc, String in, String out, String enc) {
        Map<String, String> r = new HashMap<>();
        r.put("RX_FREQ", rx); r.put("TX_FREQ", rx); r.put("LOCAL_ID", "39300000"); r.put("GROUP_LIST", groups); r.put("TX_CONTACT", contact);
        r.put("CONTACT_TYPE", contactType); r.put("POWER", "00"); r.put("CC", cc); r.put("INBOUND_SLOT", in); r.put("OUTBOUND_SLOT", out);
        r.put("CHANNEL_MODE", "00"); r.put("ENCRYPT_SW", enc); r.put("RELAY", "02");
        return r;
    }

    @Test public void analogRowKeepsTheModuleBytes() {
        // the factory row: CTCSS (type 1) code 0 = 62.5 Hz both ways, squelch 3, wide, high power
        ChannelStore.Channel ch = ChannelStore.flyscaleDmrChannel(4, 1, analog("446.10625", "446.10625", "01", "01", "03", "01", "00", "01", "00"), null);
        assertNotNull(ch);
        assertEquals(ChannelStore.Channel.TYPE_FM, ch.type);
        assertEquals("CH 5", ch.name);
        assertEquals(446_106_250, ch.rxFreq); assertEquals(446_106_250, ch.txFreq);
        assertEquals(1, ch.band); assertEquals(1, ch.power); assertEquals(3, ch.sq);
        assertEquals(1, ch.rxType); assertEquals(0, ch.rxSubCode); assertEquals(1, ch.txType); assertEquals(0, ch.txSubCode);
        assertEquals(2, ch.relay);
        // DCS (type 2) with a hex code byte; no tone (type 0) forces the code to 0
        ch = ChannelStore.flyscaleDmrChannel(0, 1, analog("462.5625", "467.5625", "00", "00", "0A", "02", "1F", "00", "05"), null);
        assertEquals(462_562_500, ch.rxFreq); assertEquals(467_562_500, ch.txFreq);
        assertEquals(0, ch.band); assertEquals(0, ch.power); assertEquals(9, ch.sq);   // 0x0A clamps to 9
        assertEquals(2, ch.rxType); assertEquals(0x1F, ch.rxSubCode); assertEquals(0, ch.txType); assertEquals(0, ch.txSubCode);
    }

    @Test public void digitalRowReadsLittleEndianIdsAndTheGroupList() {
        String groups = "09000000" + "5A000000" + "09000000" + "00000000".repeat(29);           // TG 9, TG 90, a repeat
        ChannelStore.Channel ch = ChannelStore.flyscaleDmrChannel(10, 0, analog("440.0", "440.0", "01", "01", "02", "00", "00", "00", "00"),
                digital("441.00000", groups, "09000000", "01", "01", "00", "01", "02"));
        assertEquals(ChannelStore.Channel.TYPE_DMR, ch.type);
        assertEquals("the slot type picks the digital row", 441_000_000, ch.rxFreq);
        assertArrayEquals(new int[] { 9, 90 }, ch.groups);
        assertEquals(9, ch.txContact); assertEquals(1, ch.contactType);
        assertEquals(1, ch.cc); assertEquals(0, ch.inSlot); assertEquals(1, ch.outSlot);
        assertEquals(2, ch.encryptSw); assertEquals(0, ch.power);
    }

    @Test public void badOrMissingRowsAreSkipped() {
        assertNull(ChannelStore.flyscaleDmrChannel(0, 1, null, null));
        assertNull(ChannelStore.flyscaleDmrChannel(0, 0, analog("440.0", "440.0", "01", "01", "02", "00", "00", "00", "00"), null));
        assertNull(ChannelStore.flyscaleDmrChannel(0, 1, analog("not a number", "", "01", "01", "02", "00", "00", "00", "00"), null));
        ChannelStore.Channel ch = ChannelStore.flyscaleDmrChannel(0, 1, analog("440.0", "", "01", "01", "02", "00", "00", "00", "00"), null);
        assertEquals("an empty send frequency is simplex", ch.rxFreq, ch.txFreq);
    }

    @Test public void localIdIsMostSignificantByteFirst() {
        assertEquals("the factory value is ID 1: no ID set", 0, ChannelStore.flyscaleDmrId("00000001"));
        assertEquals(1234568, ChannelStore.flyscaleDmrId("0012D688"));
        assertEquals("the other order only when this one is out of range", 1234568, ChannelStore.flyscaleDmrId("88D61200"));
        assertEquals(0, ChannelStore.flyscaleDmrId("FFFFFFFF"));
        assertEquals(0, ChannelStore.flyscaleDmrId("01000000".substring(2)));
        assertEquals(0, ChannelStore.flyscaleDmrId(null));
        assertEquals(16776415, ChannelStore.MAX_DMR_ID);
    }

    @Test public void littleEndianHex() {
        assertEquals(1, ChannelStore.leInt("01000000"));
        assertEquals(0x123456, ChannelStore.leInt("56341200"));
        assertEquals(0, ChannelStore.leInt("0100")); assertEquals(0, ChannelStore.leInt(null)); assertEquals(0, ChannelStore.leInt("zz000000"));
    }
}
