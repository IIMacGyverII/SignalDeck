/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (serial and module diagnostics: log lines, shell commands and device-report text)

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The stock app's serial JNI library, loaded into SignalDeck. Its symbols are bound to one class name, which
 * differs between phones: {@code com.pri.prizeinterphone.serial.port.SerialPort} (Armor 26 Ultra,
 * /system/lib64/libinterphone_serial_port.so) or {@code com.serial.port.SerialPort} (Armor 20WT,
 * libserial_port_app.so in the stock app's folder). Both wrappers have the same natives; the library's own
 * symbol names say which one it fills.
 */
public final class VendorSerial {
    private static final String TAG = "VendorSerial";
    static final String PRI = "com.pri.prizeinterphone.serial.port.SerialPort", PLAIN = "com.serial.port.SerialPort";

    /** An open line: its descriptor, and closing it through the library. */
    public interface Port { FileDescriptor fd(); void release(); }

    private static volatile String bound;    // the wrapper class the loaded library fills, null = not loaded

    /** Which wrapper a library's JNI symbols are bound to (PRI when the library registers its methods at load time). */
    static String wrapperFor(String jniSymbols) {
        if (jniSymbols.contains(PLAIN + ".open") && !jniSymbols.contains(PRI + ".open")) return PLAIN;
        return PRI;
    }

    /** True when a library's symbols name a wrapper SignalDeck has (or it names none: it may register them itself). */
    static boolean haveWrapper(String jniSymbols) {
        return jniSymbols.isEmpty() || jniSymbols.contains(PRI + ".open") || jniSymbols.contains(PLAIN + ".open");
    }

    /** Copy the system library into our own files dir (the app linker namespace cannot load it from /system) and load it. */
    public static synchronized boolean load(Context ctx, String systemLib) {
        if (bound != null) return true;
        if (systemLib == null) return false;
        try {
            File src = new File(systemLib);
            File dir = new File(ctx.getFilesDir(), "lib"); dir.mkdirs();
            // the library links the system C++ runtime, which the app linker namespace does not expose either:
            // bring it along the same way (an already-loaded soname satisfies the dependency). A library in the
            // stock app's own folder has none next to it: take the system one.
            File dep = new File(src.getParentFile(), "libc++.so");
            if (!dep.exists()) dep = new File("/system/lib64/libc++.so");
            if (dep.exists()) { try { System.load(copy(dep, dir).getAbsolutePath()); } catch (Throwable t) { Log.w(TAG, "libc++: " + t); } }
            String cls = wrapperFor(DeviceReport.jniSymbols(src));
            System.load(copy(src, dir).getAbsolutePath());
            bound = cls;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "load " + systemLib + ": " + t);
            return false;
        }
    }

    private static File copy(File src, File dir) throws java.io.IOException {
        File dst = new File(dir, src.getName());
        if (!dst.exists() || dst.length() != src.length()) {
            try (InputStream i = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[8192]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n);
            }
        }
        return dst;
    }

    public static boolean isLoaded() { return bound != null; }

    /** Open and configure the line through the loaded library; null if none is loaded or the native open failed. */
    public static Port openPort(String path, int baud) {
        String cls = bound;
        if (cls == null) return null;
        try { return open(cls, path, baud); }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, cls + ": " + e); return null; }    // the library filled another class
    }

    private static Port open(String cls, String path, int baud) {
        if (PLAIN.equals(cls)) {
            com.serial.port.SerialPort p = com.serial.port.SerialPort.openPort(path, baud);
            return p == null ? null : new Port() { public FileDescriptor fd() { return p.fd(); } public void release() { p.release(); } };
        }
        com.pri.prizeinterphone.serial.port.SerialPort p = com.pri.prizeinterphone.serial.port.SerialPort.openPort(path, baud);
        return p == null ? null : new Port() { public FileDescriptor fd() { return p.fd(); } public void release() { p.release(); } };
    }
}
