/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/** The device report's named lines: static String fields read from a dex (a synthetic one here, like the W6Plus DMR's SpeakUtil). */
public class DexFieldsTest {
    /**
     * A minimal dex: class LSpeak; with static fields dmr_ptt, dmr_pwd, volume (in field-index order) whose initial values
     * are a path string, a path string and an int, in that order.
     */
    static byte[] dex() {
        String[] strings = { "/sys/class/gpio/gpio188/value", "/sys/class/gpio/gpio322/value", "I", "LSpeak;", "Ljava/lang/String;", "dmr_ptt", "dmr_pwd", "volume" };
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int base = 0x70 + strings.length * 4 + 3 * 4 + 3 * 8 + 32;            // header, string_ids, type_ids, field_ids, one class_def
        int[] strOff = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            strOff[i] = base + data.size();
            byte[] s = strings[i].getBytes(StandardCharsets.UTF_8); data.write(s.length); data.write(s, 0, s.length); data.write(0);
        }
        int classData = base + data.size();
        data.write(3); data.write(0); data.write(0); data.write(0);              // 3 static fields, nothing else
        data.write(0); data.write(0x09); data.write(1); data.write(0x09); data.write(1); data.write(0x09);   // field idx 0, 1, 2 (diffs), public static
        int values = base + data.size();
        data.write(3);
        data.write(0x17); data.write(0);                                        // VALUE_STRING, 1 byte: string 0
        data.write(0x17); data.write(1);                                        // string 1
        data.write(0x04); data.write(5);                                        // VALUE_INT, 1 byte
        ByteBuffer b = ByteBuffer.allocate(base + data.size()).order(ByteOrder.LITTLE_ENDIAN);
        b.put("dex\n035\0".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0x38, strings.length); b.putInt(0x3C, 0x70);
        b.putInt(0x40, 3); b.putInt(0x44, 0x70 + strings.length * 4);
        b.putInt(0x50, 3); b.putInt(0x54, 0x70 + strings.length * 4 + 12);
        b.putInt(0x60, 1); b.putInt(0x64, 0x70 + strings.length * 4 + 12 + 24);
        b.position(0x70); for (int o : strOff) b.putInt(o);
        b.putInt(2); b.putInt(3); b.putInt(4);                                  // types: I, LSpeak;, Ljava/lang/String;
        for (int name : new int[] { 5, 6, 7 }) { b.putShort((short) 1); b.putShort((short) (name == 7 ? 0 : 2)); b.putInt(name); }   // fields of LSpeak;
        b.putInt(1); b.putInt(1); b.putInt(-1); b.putInt(0); b.putInt(-1); b.putInt(0); b.putInt(classData); b.putInt(values);
        b.put(data.toByteArray());
        return b.array();
    }

    @Test public void namesTheLinesInStaticFields() {
        List<String[]> f = DexFields.staticStrings(dex(), v -> v.startsWith("/sys/"));
        assertEquals(2, f.size());
        assertArrayEquals(new String[] { "LSpeak;", "dmr_ptt", "/sys/class/gpio/gpio188/value" }, f.get(0));
        assertArrayEquals(new String[] { "LSpeak;", "dmr_pwd", "/sys/class/gpio/gpio322/value" }, f.get(1));
        assertTrue(DexFields.staticStrings(dex(), v -> v.startsWith("/dev/")).isEmpty());
    }

    @Test public void badInputGivesNothing() {
        assertTrue(DexFields.staticStrings(null, v -> true).isEmpty());
        assertTrue(DexFields.staticStrings(new byte[200], v -> true).isEmpty());
        byte[] cut = java.util.Arrays.copyOf(dex(), 0x90);                       // truncated: what was read so far, no exception
        assertNotNull(DexFields.staticStrings(cut, v -> true));
    }

    @Test public void draftTakesPowerAndPttFromNamedLines() {
        String r = "  named lines: audio_pa_en=/sys/kernel/ext_pa_en/ext_pa_en  dmr_ptt=/sys/class/gpio/gpio188/value  dmr_pwd=/sys/class/gpio/gpio322/value  m6_pa_en=/sys/kernel/m6_pa_en/m6_pa_en\n";
        assertArrayEquals(new String[] { "dmr_pwd", "/sys/class/gpio/gpio322/value" }, DraftProfile.namedLine(r, "(?i)(^|_)(pwd|pwr|power)(_|$)"));
        assertArrayEquals(new String[] { "dmr_ptt", "/sys/class/gpio/gpio188/value" }, DraftProfile.namedLine(r, "(?i)(^|_)ptt(_|$)"));
        assertNull(DraftProfile.namedLine("  named lines: -\n", "(?i)ptt"));
        assertNull(DraftProfile.namedLine("", "(?i)ptt"));
    }

    @Test public void bothCommandFamiliesLeaveItToTheWatch() {
        List<String> at = List.of("AT+DMOCONNECT", "AT+DMOSETGROUP=", "AT+DMOVERQ");
        List<String> dmr = List.of("SET_DIGITAL", "SET_ANALOG", "QUERY_VERSION_X", "PRINT_STATE", "SET_TRANSMIT");
        assertTrue(DeviceReport.commandFamily(at, dmr).contains("the serial watch decides"));
        assertTrue(DeviceReport.commandFamily(List.of(), dmr).startsWith("the DMR module's 68"));
        assertTrue(DeviceReport.commandFamily(at).startsWith("SA818"));
        assertTrue(DeviceReport.commandFamily(at, List.of("SET_DIGITAL")).startsWith("SA818"));
    }
}
