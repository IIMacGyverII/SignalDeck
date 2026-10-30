/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceProfileTest {

    @Test public void otherPhonesKeepTheirTimingAndScaleExactly() {
        DeviceProfile[] others = { DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.ARMOR_20WT, DeviceProfile.XPLORE_1, DeviceProfile.ANYSECU_W6, DeviceProfile.UNKNOWN };
        for (DeviceProfile p : others) {
            String n = p.name;
            assertEquals(n, 0.5, p.rssiStepDb, 0); assertEquals(n, 0, p.rssiOffsetDb, 0);
            assertEquals(n, 700, p.scanSettleMs); assertEquals(n, 700, p.scopeSettleMs); assertEquals(n, 300, p.scopeReadMs);
            assertEquals(n, p == DeviceProfile.XPLORE_1 ? 2500 : 1500, p.scanDwellMs);
            assertEquals(n, Scanner.SETTLE_MS, p.scanSettleMs); assertEquals(n, BandScope.DEFAULT_SETTLE_MS, p.scopeSettleMs);
        }
    }

    @Test public void theAtomXlUsesItsMeasuredTiming() {
        DeviceProfile a = DeviceProfile.ATOM_XL;               // 2026-10-30: settles in ~50 ms, answers in 6-7 ms, the line reports 0.22 s after a retune
        assertEquals(100, a.scopeSettleMs); assertEquals(20, a.scopeReadMs);
        assertEquals(100, a.scanSettleMs); assertEquals(500, a.scanDwellMs);
        assertEquals(1.0, a.rssiStepDb, 0); assertEquals(-17, a.rssiOffsetDb, 0);
    }

    @Test public void armor20wtIsNotTakenForTheArmor26() {
        // Armor 20WT report (beta.18, 2026-10-24): same dmr009 node as the Armor 26 Ultra, detected as one before
        DeviceProfile p = DeviceProfile.detectBuiltIn("ulefone armor 20wt armor_20wt", true, false);
        assertSame(DeviceProfile.ARMOR_20WT, p);
        assertEquals("/dev/ttyS1", p.controlPort);
        assertEquals(57600, p.baud);
        assertFalse("untested on hardware: no automatic take-over, no transmit", p.verified);
        assertTrue(p.serialLib.endsWith("libserial_port_app.so"));
        assertEquals("ulefone-armor20wt", p.id);
    }

    @Test public void w6PlusDmrIsTheDmrModuleOnTheW6Body() {
        // the W6Plus DMR (2026-10-29): model V69 on the W6+'s board; the Armor's command set on ttyS2 at 57600
        DeviceProfile p = DeviceProfile.detectBuiltIn("unisoc v69 sl8541e_3h10_32b", false, false);
        assertSame(DeviceProfile.ANYSECU_W6_DMR, p);
        assertSame(DeviceProfile.ANYSECU_W6, DeviceProfile.detectBuiltIn("unisoc w6+ sl8541e_3h10_32b", false, false));
        assertEquals("mcu68", p.protocol);
        assertEquals("/dev/ttyS2", p.controlPort);
        assertEquals(57600, p.baud);
        assertTrue(p.dmr());
        assertTrue("revision 2 (owner, 2026-10-29): transmit tested in the cage", p.verified);
        assertEquals(2, p.revision);
        assertFalse("the vendor's module image stays: no loader", p.firmwareLoader());
        assertFalse(p.symbolEngine());
        assertFalse(p.copy(true, false).firmwareLoader());
        assertTrue(p.tunable(400_000_000L)); assertTrue(p.tunable(480_000_000L));
        assertFalse(p.tunable(146_520_000L)); assertFalse(p.tunable(480_005_000L));
        assertEquals(DeviceProfile.Audio.CAMCORDER, p.audio);
        assertTrue(p.appPlayback);
        assertEquals(4, p.camcorderGain); assertEquals(12, DeviceProfile.ANYSECU_W6.camcorderGain); assertEquals(4, p.copy(true, false).camcorderGain);
        assertNotNull(p.rxLinesOn); assertNotNull(p.txLinesOn);
        assertEquals("/sys/class/gpio/gpio188/value", p.txLinesOn[p.txLinesOn.length - 1][0]);   // the PTT line goes last
        // the stock app's PoC gateway route for audio SignalDeck makes itself: relay on, speaker amplifier off; relay off again after
        assertNotNull(p.gatewayLinesOn); assertNotNull(p.gatewayLinesOff);
        boolean relay = false, ampOff = false;
        for (String[] l : p.gatewayLinesOn) { relay |= l[0].contains("relay_state") && l[1].equals("1"); ampOff |= l[0].contains("ext_pa_en") && l[1].equals("0"); }
        assertTrue(relay); assertTrue(ampOff);
        assertTrue(p.gatewayLinesOff[0][0].contains("relay_state") && p.gatewayLinesOff[0][1].equals("0"));
        boolean ampBack = false;                             // without a snapshot the off list alone must leave the speaker working
        for (String[] l : p.gatewayLinesOff) ampBack |= l[0].contains("ext_pa_en") && l[1].equals("1");
        assertTrue(ampBack);
        for (String[][] ls : new String[][][] { p.gatewayLinesOn, p.gatewayLinesOff }) for (String[] l : ls) assertFalse("never a flyscale_misc node", l[0].contains("flyscale_misc"));
        assertEquals(0.15f, p.txAudioScale, 1e-6); assertEquals(0.15f, p.copy(true, true).txAudioScale, 1e-6);
        assertEquals("one raw unit per dB, as its module family on the Atom XL", 1.0, p.rssiStepDb, 0); assertEquals(-17, p.rssiOffsetDb, 0);
        assertEquals("timing defaults confirmed by §3.7 items 2 and 5", 700, p.scopeSettleMs); assertEquals(1500, p.scanDwellMs);
        assertEquals(0.4f, p.gatewayVolume, 1e-6); assertEquals(0.4f, p.copy(true, true).gatewayVolume, 1e-6);
        // the W6+ (same board) has the same relay; its stock app never used it, a tone and APRS through it decoded on a W6Plus DMR
        assertNotNull(DeviceProfile.ANYSECU_W6.gatewayLinesOn); assertEquals(0.2f, DeviceProfile.ANYSECU_W6.txAudioScale, 1e-6);
        for (String[] l : DeviceProfile.ANYSECU_W6.gatewayLinesOn) assertFalse(l[0].contains("flyscale_misc"));
        for (String[][] ls : new String[][][] { p.rxLinesOn, p.rxLinesOff, p.txLinesOn, p.txLinesOff, p.muteLines })
            for (String[] l : ls) assertFalse("never the flyscale_misc attributes", l[0].contains("flyscale_misc"));
    }

    @Test public void armor20wtDrivesTheRadioAsItsStockAppDoes() {
        // stock-app review 2026-10-30 (phonedmrapp docs/armor20wt/stock-app-review.md, D3-D10)
        DeviceProfile p = DeviceProfile.ARMOR_20WT;
        assertEquals(2, p.revision);
        assertFalse("still untested on hardware", p.verified);
        assertFalse("its app never sends mic or interrupt at boot", p.bootMicInterrupt);
        assertEquals(600, p.txAudioLeadMs);
        assertEquals(2000, p.minKeyGapMs);
        assertEquals(5, p.analogVolume);
        assertEquals("channel_database_", p.stockAreaPrefix); assertEquals("Default", p.stockDefaultArea);
        assertFalse("no updater and no image in its app: no loader on an undiffed variant", p.firmwareLoader());
        assertFalse(p.symbolEngine());
        assertArrayEquals(new int[] { 0x22, 0x23, 0x26, 0x27, 0x2C, 0x2D, 0x34, 0x36, 0x3B }, p.stockCommands);
        DeviceProfile c = p.copy(true, false);                                 // an advisory's copy keeps them
        assertFalse(c.bootMicInterrupt); assertEquals(600, c.txAudioLeadMs); assertEquals(2000, c.minKeyGapMs); assertEquals(5, c.analogVolume);
        assertEquals("channel_database_", c.stockAreaPrefix); assertEquals("Default", c.stockDefaultArea); assertSame(p.stockCommands, c.stockCommands);
    }

    @Test public void otherPhonesKeepTheArmor26StockAppBehaviour() {
        DeviceProfile[] others = { DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.ATOM_XL, DeviceProfile.UNKNOWN };
        for (DeviceProfile p : others) {
            String n = p.name;
            assertTrue(n, p.bootMicInterrupt);
            assertEquals(n, 0, p.txAudioLeadMs); assertEquals(n, 0, p.minKeyGapMs);
            assertEquals(n, 8, p.analogVolume);
            assertEquals(n, "database_channel_area_", p.stockAreaPrefix); assertEquals(n, "default_uhf", p.stockDefaultArea);
            assertNull(n, p.stockCommands);
        }
        assertEquals(1, DeviceProfile.ARMOR_26_ULTRA.revision);
        assertTrue(DeviceProfile.ARMOR_26_ULTRA.firmwareLoader());
    }

    @Test public void stockAppFieldsComeFromTheProfileList() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject("{\"id\":\"x\",\"revision\":1,\"name\":\"X\",\"controlPort\":\"/dev/ttyS1\",\"baud\":57600,\"audio\":\"TINY_SERVICE\","
                + "\"bootMicInterrupt\":false,\"txAudioLeadMs\":600,\"minKeyGapMs\":2000,\"analogVolume\":5,\"stockAreaPrefix\":\"channel_database_\",\"stockDefaultArea\":\"Default\",\"stockCommands\":[34,35,38]}");
        DeviceProfile p = DeviceProfile.fromJson(o);
        assertFalse(p.bootMicInterrupt); assertEquals(600, p.txAudioLeadMs); assertEquals(2000, p.minKeyGapMs); assertEquals(5, p.analogVolume);
        assertEquals("channel_database_", p.stockAreaPrefix); assertEquals("Default", p.stockDefaultArea);
        assertArrayEquals(new int[] { 0x22, 0x23, 0x26 }, p.stockCommands);
        o.put("txAudioLeadMs", 99999).put("analogVolume", 0);                 // clamped
        p = DeviceProfile.fromJson(o); assertEquals(2000, p.txAudioLeadMs); assertEquals(1, p.analogVolume);
        o.put("stockAreaPrefix", "x; rm -rf /");                                 // ends up on a root command line
        try { DeviceProfile.fromJson(o); fail("a prefix with shell characters must be refused"); } catch (org.json.JSONException expected) { }
        o.remove("stockAreaPrefix"); o.remove("stockDefaultArea"); o.remove("bootMicInterrupt"); o.remove("txAudioLeadMs"); o.remove("minKeyGapMs"); o.remove("analogVolume"); o.remove("stockCommands");
        p = DeviceProfile.fromJson(o);                                          // absent: the Armor 26 Ultra's behaviour
        assertTrue(p.bootMicInterrupt); assertEquals(0, p.txAudioLeadMs); assertEquals(0, p.minKeyGapMs); assertEquals(8, p.analogVolume);
        assertEquals("database_channel_area_", p.stockAreaPrefix); assertEquals("default_uhf", p.stockDefaultArea); assertNull(p.stockCommands);
    }

    @Test public void moduleLoaderComesFromTheProfileList() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject("{\"id\":\"x\",\"revision\":1,\"name\":\"X\",\"controlPort\":\"/dev/ttyS2\",\"baud\":57600,\"audio\":\"CAMCORDER\",\"moduleLoader\":true}");
        assertFalse("the signed list cannot certify a phone", DeviceProfile.fromJson(o).firmwareLoader());
        o.put("id", "ulefone-armor26ultra");
        assertTrue("a certified phone keeps it", DeviceProfile.fromJson(o).firmwareLoader());
        o.put("moduleLoader", false);
        assertFalse("and the list can switch it off", DeviceProfile.fromJson(o).firmwareLoader());
    }

    /** Owner, 2026-10-30: the loader exists only on phones whose flash process was bench-tested (the Armor 26 Ultra so far). */
    @Test public void onlyCertifiedPhonesHaveTheLoader() {
        for (DeviceProfile p : new DeviceProfile[] { DeviceProfile.ARMOR_20WT, DeviceProfile.XPLORE_1, DeviceProfile.ATOM_XL, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.UNKNOWN })
            assertFalse(p.name, p.firmwareLoader());
        assertTrue(DeviceProfile.ARMOR_26_ULTRA.firmwareLoader());
        assertEquals(java.util.Collections.singleton("ulefone-armor26ultra"), DeviceProfile.LOADER_CERTIFIED);
        DeviceProfile other = DeviceProfile.detectBuiltIn("ulefone armor 24 something", true, false);    // the dmr009 fallback
        assertFalse("a phone that only falls back to the 26 Ultra's profile gets no loader", other.firmwareLoader());
        assertEquals(DeviceProfile.ARMOR_26_ULTRA.controlPort, other.controlPort);
        assertTrue(DeviceProfile.detectBuiltIn("ulefone armor 26 ultra", true, false).firmwareLoader());
    }

    @Test public void atomXlIsTheAgoldBridgeProfile() {
        DeviceProfile p = DeviceProfile.detectBuiltIn("unihertz atom_xl atom_xl", false, false);
        assertSame(DeviceProfile.ATOM_XL, p);
        assertEquals("agold", p.protocol);
        assertEquals("/dev/ttyACM0", p.controlPort);
        assertTrue("verified 2026-10-28: transmit tested in the cage", p.verified);
        assertTrue(p.dmr());
        assertFalse("the symbol engine is the Armor firmware's", p.symbolEngine());
        assertFalse("the module is loaded through the bridge's own path, not our YModem knock", p.firmwareLoader());
        assertTrue(DeviceProfile.ARMOR_26_ULTRA.firmwareLoader());
        assertEquals(32000, p.directAudio.rate);
        assertTrue(p.directAudio.valid());
        assertTrue(p.power().node().endsWith("/mcu_power"));
        DeviceProfile c = p.copy(true, false);
        assertEquals("agold", c.protocol);
        assertEquals(32000, c.directAudio.rate);
    }

    @Test public void otherPhonesUnchanged() {
        assertSame(DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.detectBuiltIn("ulefone armor 26 ultra armor_26_ultra", true, false));
        DeviceProfile fallback = DeviceProfile.detectBuiltIn("some phone x", true, false);      // the dmr009 fallback: the 26 Ultra's radio path without its loader
        assertEquals(DeviceProfile.ARMOR_26_ULTRA.id, fallback.id); assertEquals(DeviceProfile.ARMOR_26_ULTRA.controlPort, fallback.controlPort); assertFalse(fallback.firmwareLoader());
        assertSame(DeviceProfile.XPLORE_1, DeviceProfile.detectBuiltIn("blackview xplore 1 walkie talkie xplore_1_wt", false, true));
        assertSame(DeviceProfile.UNKNOWN, DeviceProfile.detectBuiltIn("some phone x", false, false));
    }

    @Test public void serialWrapperFollowsTheLibrarysSymbols() {
        String armor20 = "      JNI com.serial.port.SerialPort.close()\n      JNI com.serial.port.SerialPort.open()\n";
        String armor26 = "      JNI com.pri.prizeinterphone.serial.port.SerialPort.open()\n      JNI com.pri.prizeinterphone.serial.port.SerialPort.close()\n";
        assertEquals(VendorSerial.PLAIN, VendorSerial.wrapperFor(armor20));
        assertEquals(VendorSerial.PRI, VendorSerial.wrapperFor(armor26));
        assertEquals(VendorSerial.PRI, VendorSerial.wrapperFor(""));          // registers its methods itself: the known one
        assertTrue(VendorSerial.haveWrapper(armor20));
        assertFalse(VendorSerial.haveWrapper("      JNI com.wonder.serial.SerialPort.open()\n"));
    }
}
