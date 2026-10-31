/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** The Armor 3WT profile (written from its intercom app and extmodule, unverified) and that no other phone changed. */
public class Armor3wtProfileTest {
    @Test public void detectedByModelBoardOrVendorBuildWithTheDaemon() {
        assertTrue(DeviceProfile.armor3wt("ulefone armor 3wt armor_3wt", "", null, true));
        assertTrue("a GSI: the model says TrebleDroid, the board stays", DeviceProfile.armor3wt("unknown trebledroid with gapps tdgsi_arm64_ab", "s62v71c2k_gq_3wt", null, true));
        assertTrue(DeviceProfile.armor3wt("unknown trebledroid", "", "Ulefone/Armor_3WT/Armor_3WT:9/PPR1.180610.011/x:user/release-keys", true));
        assertFalse("no extmodule: nothing to talk to", DeviceProfile.armor3wt("ulefone armor 3wt", "s62v71c2k_gq_3wt", null, false));
        assertFalse(DeviceProfile.armor3wt("ulefone armor 20wt", "", null, true));
        assertFalse(DeviceProfile.armor3wt("unihertz atom_xl atom_xl", "g63v71c2k_dfl_eea", null, true));
    }

    @Test public void profileIsUnverifiedAndLocked() {
        DeviceProfile p = DeviceProfile.ARMOR_3WT;
        assertEquals("ulefone-armor3wt", p.id);
        assertEquals("agold-legacy", p.protocol);
        assertTrue(p.supported);
        assertFalse("written from the app and one report: no transmit", p.verified);
        assertFalse("transmit test session only with the owner's OK", p.txTestUnverified);
        assertFalse(p.firmwareLoader());
        assertFalse(p.symbolEngine());
        assertFalse("no RSSI command known on this module", p.rssi);
        assertFalse(p.timeSlots);
        assertFalse(p.bootMicInterrupt);
        assertTrue(p.dmr());
        assertEquals(DeviceProfile.Audio.EXTMODULE, p.audio);
        assertTrue(ExtModuleLink.isSocket(p.controlPort));
        assertArrayEquals(new String[] { "mtk-kpd", "00f9" }, p.pttKey);
        assertTrue("the intercom app's floor", p.tunable(100_000_000L));
        assertTrue(p.tunable(130_000_000L));
        assertTrue(p.tunable(470_000_000L));
        assertFalse(p.tunable(470_000_001L));
        assertFalse(p.tunable(300_000_000L));
        DeviceProfile c = p.copy(true, false);
        assertFalse(c.timeSlots); assertArrayEquals(p.pttKey, c.pttKey); assertEquals("agold-legacy", c.protocol);
    }

    @Test public void noOtherPhoneChanged() {
        for (DeviceProfile p : new DeviceProfile[] { DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.ARMOR_20WT, DeviceProfile.XPLORE_1, DeviceProfile.ATOM_XL, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.UNKNOWN }) {
            assertTrue(p.name, p.timeSlots);
            assertNull(p.name, p.pttKey);
            assertNotEquals(p.name, DeviceProfile.Audio.EXTMODULE, p.audio);
            assertFalse(p.name, ExtModuleLink.isSocket(p.controlPort));
            assertNotEquals(p.name, "agold-legacy", p.protocol);
        }
        assertSame(DeviceProfile.ATOM_XL, DeviceProfile.detectBuiltIn("unihertz atom_xl atom_xl", false, false));
        assertSame(DeviceProfile.ARMOR_20WT, DeviceProfile.detectBuiltIn("ulefone armor 20wt armor_20wt", true, false));
        // the Armor numbers keep their own framing on every other phone
        assertTrue(FrameCodec.armor().carries(0x35));
        assertTrue(new AgoldEnvelope().carries(0x32));
    }

    @Test public void profileListCarriesTheNewFields() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject("{\"id\":\"x\",\"revision\":1,\"name\":\"X\",\"controlPort\":\"tcp:127.0.0.1:9980\",\"baud\":0,\"audio\":\"EXTMODULE\",\"protocol\":\"agold-legacy\",\"timeSlots\":false,\"pttKey\":[\"mtk-kpd\",\"00f9\"]}");
        DeviceProfile p = DeviceProfile.fromJson(o);
        assertFalse(p.timeSlots);
        assertArrayEquals(new String[] { "mtk-kpd", "00f9" }, p.pttKey);
        o.remove("timeSlots"); o.remove("pttKey");
        p = DeviceProfile.fromJson(o);
        assertTrue(p.timeSlots); assertNull(p.pttKey);
        o.put("pttKey", new org.json.JSONArray("[\"mtk-kpd\",\"00f9; reboot\"]"));
        try { DeviceProfile.fromJson(o); fail("a key code that is not a key name or four hex digits is refused"); } catch (org.json.JSONException expected) { }
    }

    @Test public void daemonPortsFromProcNetTcp() {
        String tcp = String.join("\n",
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
                "   0: 00000000:26FC 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1",
                "   1: 0100007F:2701 0100007F:9C40 01 00000000:00000000 00:00000000 00000000     0        0 2");
        assertTrue(FirstRunCheck.listening(tcp, 9980));
        assertFalse("connected, not listening", FirstRunCheck.listening(tcp, 9985));
        assertFalse(FirstRunCheck.listening("", 9980));
    }

    @Test public void legacyTextIsBlankedInReports() {
        String line = "createCmdInThread >>7E966901070100" + "0C09000009480069000000000000" + "ABCD81";
        assertTrue(AgoldFrames.redact(line).contains("####"));
    }

    @Test public void sideKeyWithoutAKernelNameIsFound() {
        String pl = String.join("\n",
                "add device 1: /dev/input/event1",
                "  name:     \"mtk-kpd\"",
                "  events:",
                "    KEY (0001): KEY_VOLUMEDOWN        KEY_VOLUMEUP          KEY_POWER             00f9                 ",
                "                00fa                 ",
                "  input props:",
                "    <none>",
                "add device 2: /dev/input/event3",
                "  name:     \"mtk-tpd\"",
                "  events:",
                "    KEY (0001): BTN_TOUCH            ",
                "    ABS (0003): ABS_MT_SLOT           : value 0000, min 0, max 9, fuzz 0, flat 0, resolution 0");
        java.util.Map<String, CustomPttKey.Device> d = CustomPttKey.parseDevices(pl);
        assertTrue(d.get("/dev/input/event1").keys.containsAll(java.util.Arrays.asList("KEY_POWER", "00f9", "00fa")));
        assertFalse("the section number is not a key", d.get("/dev/input/event1").keys.contains("0001"));
        assertFalse("ABS values are not keys", d.get("/dev/input/event3").keys.contains("0000"));
        CustomPttKey.Event e = CustomPttKey.parse("[     427.545179] /dev/input/event1: EV_KEY       00f9                 DOWN                ");
        assertEquals("00f9", e.code); assertEquals(1, e.value);
    }
}
