/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The constant String values of static fields in a dex file, with their field names: what a plain strings scan cannot
 * tell. The W6Plus DMR's stock app names its lines this way ({@code dmr_pwd = "/sys/class/gpio/gpio322/value"},
 * {@code dmr_ptt = …gpio188…}), so the device report can say which path is the power line and which the PTT line.
 * Reads only the header tables, class_data and static_values (dex format, any version); never runs anything.
 */
public final class DexFields {
    private DexFields() { }

    /** {class descriptor, field name, value} for every static field whose initial value is a String accepted by {@code keep}. */
    public static List<String[]> staticStrings(byte[] dex, java.util.function.Predicate<String> keep) {
        List<String[]> out = new ArrayList<>();
        if (dex == null || dex.length < 0x70 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') return out;
        ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int strSize = b.getInt(0x38), strOff = b.getInt(0x3C), typeOff = b.getInt(0x44), fieldSize = b.getInt(0x50), fieldOff = b.getInt(0x54);
            int classSize = b.getInt(0x60), classOff = b.getInt(0x64);
            for (int c = 0; c < classSize; c++) {
                int def = classOff + c * 32;
                int dataOff = b.getInt(def + 24), valuesOff = b.getInt(def + 28);
                if (dataOff == 0 || valuesOff == 0) continue;
                int[] p = { dataOff };
                int statics = uleb(dex, p); uleb(dex, p); uleb(dex, p); uleb(dex, p);
                int[] fields = new int[statics]; int idx = 0;
                for (int i = 0; i < statics; i++) { idx += uleb(dex, p); uleb(dex, p); fields[i] = idx; }
                int[] q = { valuesOff };
                int n = Math.min(uleb(dex, q), statics);
                for (int i = 0; i < n; i++) {
                    int head = dex[q[0]++] & 0xFF, type = head & 0x1F, arg = head >> 5;
                    if (type == 0x17) {                                              // VALUE_STRING: arg+1 bytes of string index
                        int si = 0; for (int k = 0; k <= arg; k++) si |= (dex[q[0]++] & 0xFF) << (8 * k);
                        int f = fields[i];
                        if (si >= strSize || f >= fieldSize) continue;
                        String v = string(dex, b.getInt(strOff + si * 4));
                        if (!keep.test(v)) continue;
                        int fid = fieldOff + f * 8;
                        String cls = string(dex, b.getInt(strOff + b.getInt(typeOff + (b.getShort(fid) & 0xFFFF) * 4) * 4));
                        out.add(new String[] { cls, string(dex, b.getInt(strOff + b.getInt(fid + 4) * 4)), v });
                    } else skip(dex, q, type, arg);
                }
            }
        } catch (RuntimeException e) { /* a malformed or truncated dex: what was read so far */ }
        return out;
    }

    private static void skip(byte[] d, int[] p, int type, int arg) {
        switch (type) {
            case 0x1C: { int n = uleb(d, p); for (int i = 0; i < n; i++) { int h = d[p[0]++] & 0xFF; skip(d, p, h & 0x1F, h >> 5); } break; }   // array
            case 0x1D: { uleb(d, p); int n = uleb(d, p); for (int i = 0; i < n; i++) { uleb(d, p); int h = d[p[0]++] & 0xFF; skip(d, p, h & 0x1F, h >> 5); } break; }   // annotation
            case 0x1E: case 0x1F: break;                                             // null, boolean: no bytes
            default: p[0] += arg + 1;
        }
    }

    private static int uleb(byte[] d, int[] p) {
        int v = 0, s = 0, b;
        do { b = d[p[0]++] & 0xFF; v |= (b & 0x7F) << s; s += 7; } while ((b & 0x80) != 0 && s < 35);
        return v;
    }

    /** A string_data_item: uleb length, then MUTF-8 up to the 0 (only ASCII matters for paths and names). */
    private static String string(byte[] d, int off) {
        int[] p = { off }; uleb(d, p);
        int e = p[0]; while (e < d.length && d[e] != 0) e++;
        return new String(d, p[0], e - p[0], StandardCharsets.UTF_8);
    }
}
