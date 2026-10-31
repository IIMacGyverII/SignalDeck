/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Root-only radio probe for phones without a verified profile: finds the serial ports, derives the SELinux
 * rules the app needs from the ports' actual labels, puts each candidate port into raw mode at the module's
 * speed, sends the version query ({@code 68 34 01 01 …}) and records whatever comes back; reads and (if asked)
 * pulses the power-looking sysfs node; sniffs a second UART for raw PCM. Everything is appended to the latest
 * device report so the profile can be written from it.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class RadioProbe {
    public interface Progress { void step(String s); }
    private RadioProbe() { }

    /** Open the port as this app (the live rules allow it), send the version query, collect 3 s of reply bytes as hex. */
    private static String javaQuery(String port, byte[] query) {
        StringBuilder hex = new StringBuilder();
        try (java.io.FileInputStream in = new java.io.FileInputStream(port); java.io.FileOutputStream out = new java.io.FileOutputStream(port)) {
            final byte[] buf = new byte[512]; final int[] got = { 0 };
            Thread t = new Thread(() -> { try { int n; while (got[0] < buf.length && (n = in.read(buf, got[0], buf.length - got[0])) > 0) got[0] += n; } catch (Throwable ignored) { } });
            t.setDaemon(true); t.start();
            Thread.sleep(300);
            out.write(query); out.flush();
            Thread.sleep(3000);
            for (int i = 0; i < got[0]; i++) hex.append(String.format(Locale.US, "%02x ", buf[i] & 0xFF));
        } catch (Throwable e) { return "(open failed: " + e.getMessage() + ")"; }
        return hex.toString().trim();
    }

    /** Labels of ports that belong to another subsystem (the Armor's ttyS1 is the modem mux, mt6825muxd_device): never written to, never opened up. */
    private static final java.util.regex.Pattern OTHER_USE = java.util.regex.Pattern.compile("(?i)mux|modem|ccci|radio|gps|gnss|bt_|bluetooth|nfc|console|wifi|wlan|fm_");

    public static String run(android.content.Context ctx, Progress p, boolean pulsePower) { return run(ctx, p, pulsePower, java.util.Collections.emptySet()); }

    /**
     * Ports other evidence names: the stock app's code (the report's clue "paths:" lines), the ports it held during the
     * watch, the profile's control port. When there are any, only those are probed, whatever their label: the W6's module
     * port carries {@code console_device}, and the one unnamed port beside it ({@code ttySE0}) is the Unisoc engineering
     * port, which answered the DMR query with "FAIL: Invalid command" (2026-10-27).
     */
    static java.util.Set<String> namedPorts(String report, String watch, String profilePort) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        java.util.regex.Pattern tty = java.util.regex.Pattern.compile("/dev/tty[A-Za-z]+\\d+");
        for (String l : (report == null ? "" : report).split("\n")) if (l.trim().startsWith("paths:")) { java.util.regex.Matcher m = tty.matcher(l); while (m.find()) out.add(m.group()); }
        for (String l : (watch == null ? "" : watch).split("\n")) if (l.startsWith("devices it holds open:") || l.startsWith("at the end of the watch it holds:")) { java.util.regex.Matcher m = tty.matcher(l); while (m.find()) out.add(m.group()); }
        if (profilePort != null && profilePort.matches("/dev/tty[A-Za-z]+\\d+")) out.add(profilePort);
        return out;
    }

    /** @param named ports other evidence names ({@link #namedPorts}); empty = probe every port that is not another subsystem's */
    public static String run(android.content.Context ctx, Progress p, boolean pulsePower, java.util.Set<String> named) {
        StringBuilder r = new StringBuilder();
        if (!RootSetup.haveRoot()) return "Root is not granted: the probe needs Magisk root (Superuser → allow SignalDeck).";
        p.step("Labels");
        String ls = RootSetup.run("ls -Z /dev/ttyS* /dev/ttyMT* /dev/ttyHS* /dev/ttyACM* /dev/ttyUSB* 2>/dev/null; true");   // USB serial: the Agold phones' module sits behind a USB MCU on ttyACM0
        r.append("Serial ports and labels:\n").append(ls).append('\n');
        String holders = portHolders();
        r.append("Processes holding a serial port:\n").append(holders.isEmpty() ? "  (none)" : "  " + holders).append('\n');
        List<String> types = new ArrayList<>(), ports = new ArrayList<>();
        for (String line : ls.split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("u:object_r:(\\w+):s0\\s+(/dev/\\S+)").matcher(line);
            if (!m.find()) continue;
            String label = m.group(1), port = m.group(2), held = heldBy(holders, port);
            if (!named.isEmpty() && !named.contains(port)) { r.append("  skipped ").append(port).append(": no app or profile names it (named: ").append(String.join(" ", named)).append(")\n"); continue; }
            // a port another process has open would lose its line settings; a modem/GNSS/BT port is not the radio module,
            // unless the stock app itself opens it (the W6's module port is labelled console_device)
            if (named.isEmpty() && OTHER_USE.matcher(label).find()) { r.append("  skipped ").append(port).append(": its label ").append(label).append(" belongs to another subsystem\n"); continue; }
            if (held != null) { r.append("  skipped ").append(port).append(": in use by ").append(held).append(held.contains(ctx.getPackageName()) ? " (this app: Hand back first)" : " (close that app and probe again)").append('\n'); continue; }
            if (!types.contains(label)) types.add(label);
            ports.add(port);
        }
        // live rules derived from the real labels (what a Magisk module for this phone will need)
        StringBuilder rules = new StringBuilder();
        for (String t : types) rules.append("allow untrusted_app ").append(t).append(" chr_file { open read write ioctl getattr lock }\ntypeattribute ").append(t).append(" mlstrustedobject\n");
        String sysls = RootSetup.run("ls -Z /sys/devices/platform/*dmr*/* /sys/devices/platform/*intercom*/* /sys/devices/platform/*walkie*/* /sys/devices/platform/*ptt*/* 2>/dev/null; true");
        boolean plainSysfs = false;
        for (String line : sysls.split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("u:object_r:(\\w+):s0").matcher(line);
            if (!m.find()) continue;
            if (m.group(1).equals("sysfs")) {                                      // opening all of sysfs to apps is not acceptable: the app writes these through root
                if (line.matches(".*/(pwd|ptt|pd|power|enable|en|\\w+_control)\\s*$")) plainSysfs = true;
                continue;
            }
            if (!rules.toString().contains(" " + m.group(1) + " file")) rules.append("allow untrusted_app ").append(m.group(1)).append(" file { open read write getattr }\ntypeattribute ").append(m.group(1)).append(" mlstrustedobject\n");
        }
        r.append("Derived sepolicy.rule lines:\n").append(rules.length() == 0 ? "  (none)\n" : rules);
        if (plainSysfs) r.append("  (module nodes with the plain sysfs label: no rule, the app uses root for them)\n");
        r.append('\n');
        if (rules.length() > 0) try {
            File f = new File(ctx.getCacheDir(), "probe.rule");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(rules.toString().getBytes()); }
            RootSetup.run("chmod 644 " + f.getAbsolutePath());
            r.append("Applied live (until reboot): ").append(RootSetup.run("magiskpolicy --live --apply " + f.getAbsolutePath() + " 2>&1")).append('\n');
        } catch (Throwable t) { r.append("live apply failed: ").append(t).append('\n'); }

        p.step("Power nodes");
        r.append("\nSysfs nodes:\n").append(sysls).append('\n');
        r.append(RootSetup.run("for f in /sys/devices/platform/*dmr*/* /sys/devices/platform/*intercom*/*; do [ -f \"$f\" ] && [ -r \"$f\" ] && echo \"  $f = $(head -c 80 \"$f\" 2>&1 | tr '\\n' ' ')\"; done; true")).append('\n');
        String powerNode = null;
        for (String line : sysls.split("\n")) { String path = line.trim().replaceAll(".*\\s(/sys/\\S+)$", "$1"); if (path.matches(".*/(pwd|power|intercom_power_control|intercom_pwd_control|enable|en)$")) { powerNode = path; break; } }
        if (powerNode != null) {
            r.append("Power-looking node: ").append(powerNode).append(" = ").append(RootSetup.run("cat " + powerNode)).append('\n');
            DeviceProfile prof = DeviceProfile.detect();
            if (pulsePower && prof.lineSetup != null) for (String[] l : prof.lineSetup) r.append("  first ").append(l[0]).append(" = ").append(l[1]).append(": ").append(RootSetup.run("echo " + l[1] + " > " + l[0] + " 2>&1 && echo ok")).append('\n');
            if (pulsePower && prof.supported && prof.powerNode != null) powerNode = prof.powerNode;      // the profile knows which line powers the module
            if (pulsePower && !prof.supported) r.append("  (no power pulse on a phone without a profile: which line keys the transmitter is unknown)\n");
            else if (pulsePower) r.append("  write 1 to ").append(powerNode).append(" → ").append(RootSetup.run("echo 1 > " + powerNode + " 2>&1; sleep 3; cat " + powerNode)).append('\n');
        } else r.append("No power-looking node found under /sys/devices/platform/*{dmr,intercom,walkie,ptt}*\n");

        p.step("Serial probe");
        String stty = stty();
        r.append("\nline settings tool: ").append(stty).append('\n');
        List<String> notBack = new ArrayList<>();
        for (String port : ports) {
            r.append("\n--- ").append(port).append(" ---\n");
            r.append("before: ").append(RootSetup.run(stty + " -F " + port + " -a 2>&1 | head -1")).append('\n');
            Saved saved = save(stty, port);                                    // put the line back exactly as it was
            try { probePort(r, stty, port, saved); }
            finally { String back = restore(stty, port, saved); r.append("  line settings put back: ").append(back).append('\n'); if (!back.startsWith("yes")) notBack.add(port); }
        }

        p.step("Vendor serial library");
        probeVendorLibs(ctx, r, ports);

        p.step("PCM sniff");
        for (String port : ports) {
            Saved saved = save(stty, port);
            if (!saved.canChange) { r.append(port).append(": not sniffed at 230400 (its speed ").append(saved.speed).append(" could not be put back if changed)\n"); continue; }
            String out = RootSetup.run(stty + " -F " + port + " 230400 raw -echo 2>&1; timeout 2 cat " + port + " | head -c 4000 | wc -c").trim();
            String back = restore(stty, port, saved);
            if (!back.startsWith("yes") && !notBack.contains(port)) notBack.add(port);
            r.append(String.format(Locale.US, "%s @230400: %s bytes in 2 s%s\n", port, out, out.matches("[1-9]\\d*") && Integer.parseInt(out) > 1000 ? "  (continuous stream — a PCM audio port?)" : ""));
        }
        r.append(notBack.isEmpty() ? "\nline settings: every probed port is back as it was\n" : "\nline settings NOT back on " + String.join(", ", notBack) + " (a reboot puts them back)\n");
        r.append("\nSELinux denials during the probe:\n").append(RootSetup.run("logcat -d -b all 2>/dev/null | grep -i 'avc: denied' | tail -30")).append('\n');
        return r.toString();
    }

    /** Magisk's busybox stty when present (it sets every speed the kernel knows), else the system's. */
    private static String stty() {
        String bb = RootSetup.run("for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox; do [ -x $b ] && $b stty --help >/dev/null 2>&1 && { echo $b; break; }; done; true").trim();
        return bb.isEmpty() ? "stty" : bb + " stty";
    }

    /** A port's line settings before the probe: the full state, its speed, and whether that speed can be set again. */
    static final class Saved { String g = "", speed = ""; boolean canChange; }

    private static Saved save(String stty, String port) {
        Saved s = new Saved();
        s.g = RootSetup.run(stty + " -F " + port + " -g 2>/dev/null").trim();
        s.speed = speedOf(RootSetup.run(stty + " -F " + port + " 2>&1 | head -1"));
        // only change a port whose own speed the tool accepts: setting the speed it already has must work and read back
        // (the Atom XL's ttyS0 at 921600 was left at 230400 once: the speed was changed and could not be put back)
        s.canChange = !s.speed.isEmpty() && RootSetup.run(stty + " -F " + port + " " + s.speed + " >/dev/null 2>&1 && echo ok").trim().equals("ok")
                && speedOf(RootSetup.run(stty + " -F " + port + " 2>&1 | head -1")).equals(s.speed);
        return s;
    }

    /** "speed 57600 baud; ..." (stty's first line) -> "57600"; "" if not found. */
    static String speedOf(String sttyLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("speed (\\d+) baud").matcher(sttyLine == null ? "" : sttyLine);
        return m.find() ? m.group(1) : "";
    }

    private static String restore(String stty, String port, Saved saved) {
        if (!saved.g.matches("[0-9a-fA-F:]+") && saved.speed.isEmpty()) return "(could not read them beforehand)";
        if (saved.g.matches("[0-9a-fA-F:]+")) RootSetup.run(stty + " -F " + port + " " + saved.g + " 2>/dev/null");
        if (RootSetup.run(stty + " -F " + port + " -g 2>/dev/null").trim().equals(saved.g)) return "yes";
        String now = speedOf(RootSetup.run(stty + " -F " + port + " 2>&1 | head -1"));
        if (!saved.speed.isEmpty() && !now.equals(saved.speed)) {               // the full state did not take: the speed at least
            RootSetup.run(stty + " -F " + port + " " + saved.speed + " 2>/dev/null");
            now = speedOf(RootSetup.run(stty + " -F " + port + " 2>&1 | head -1"));
        }
        return now.equals(saved.speed) ? "yes (speed " + now + "; other flags may differ)" : "NO: speed " + now + " instead of " + saved.speed;
    }

    /** One line per process with a tty open: "  cmdline (pid): /dev/ttyX …". */
    private static String portHolders() {
        return RootSetup.run("for p in /proc/[0-9]*; do t=$(ls -l $p/fd 2>/dev/null | grep -o '/dev/tty[A-Za-z]*[0-9]*' | sort -u | tr '\\n' ' '); [ -n \"$t\" ] && echo \"  $(tr '\\0' ' ' < $p/cmdline 2>/dev/null | cut -c1-60) ($(basename $p)): $t\"; done; true");
    }
    private static String heldBy(String holders, String port) {
        for (String l : holders.split("\n")) if ((l + " ").contains(port + " ")) return l.trim().replaceAll(":\\s+/dev/.*$", "");
        return null;
    }

    /** The module's version queries: the Ulefone/Blackview frame, and the Agold envelope's 0x34 (DMR09 set) and 0x25 (older set). */
    static final byte[] QUERY_ULEFONE = { 0x68, 0x34, 0x01, 0x01, (byte) 0x95, (byte) 0xB9, 0x00, 0x01, 0x01, 0x10 };
    static final byte[] QUERY_AGOLD_34 = { 0x7E, (byte) 0x96, 0x69, 0x01, 0x34, 0x01, 0x00, 0x00, (byte) 0xFD, (byte) 0xCB, (byte) 0x81 };
    static final byte[] QUERY_AGOLD_25 = { 0x7E, (byte) 0x96, 0x69, 0x01, 0x25, 0x01, 0x00, 0x00, (byte) 0xFD, (byte) 0xDA, (byte) 0x81 };
    private static String printf(byte[] q) { StringBuilder s = new StringBuilder("printf '"); for (byte b : q) s.append(String.format(Locale.US, "\\x%02x", b & 0xFF)); return s.append("' > ").toString(); }
    /**
     * SA818-class identity queries (both only read): the handshake every such module answers, and its version, each
     * ending in a bare \r as the W6's stock app sends them (sniffed 2026-10-27). The first command after the probe's speed
     * changes loses its first byte on the W6 ("T+DMOCONNECT\r:1", an error echo), so either answer counts.
     */
    static final byte[] AT_CONNECT = "AT+DMOCONNECT\r".getBytes(), AT_VERSION = "AT+DMOVERQ\r".getBytes();
    static final String AT_MARK = "an AT-command module (SA818 class) answered";
    /**
     * An SA818-class answer: a "+DMO…" reply in the received bytes (hex as the probe prints it). Our own command coming
     * back ("AT+DMO…", an echo or a loopback, also the W6's error echo "AT+DMOCONNEC:1") does not count.
     */
    static boolean looksLikeAt(String hex) { return java.util.regex.Pattern.compile("(^|[^T])\\+DMO[A-Z]+:").matcher(asText(hex)).find(); }
    /** The received hex as text where it is printable ("+DMOCONNECT:0"), for the report. */
    static String asText(String hex) {
        StringBuilder t = new StringBuilder();
        for (String h : hex.trim().split("\\s+")) {
            if (!h.matches("[0-9a-fA-F]{2}")) continue;
            int b = Integer.parseInt(h, 16);
            t.append(b == 0x0d || b == 0x0a ? " " : b >= 0x20 && b < 0x7f ? String.valueOf((char) b) : ".");
        }
        return t.toString().trim().replaceAll(" +", " ");
    }

    /**
     * Bytes that only a radio module's answer starts with, once any echo of our own query is taken out: the W6's AT
     * module sends back what it got wrapped in an error ("\r\n…68 34 01 01 95 b9 00 01 01 10…:1\r\n", 2026-10-27),
     * which is not a DMR module answering.
     */
    static boolean looksLikeModule(String hex) {
        String h = " " + hex.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim() + " ";
        for (byte[] q : new byte[][] { QUERY_ULEFONE, QUERY_AGOLD_34, QUERY_AGOLD_25 }) h = h.replace(" " + hexOf(q) + " ", " | ");
        return h.contains("68 34") || h.contains("bf aa") || h.contains("7e 96 69");
    }
    private static String hexOf(byte[] q) { StringBuilder s = new StringBuilder(); for (byte b : q) s.append(s.length() == 0 ? "" : " ").append(String.format(Locale.US, "%02x", b & 0xFF)); return s.toString(); }

    private static void probePort(StringBuilder r, String stty, String port, Saved saved) {
        boolean usb = port.matches("/dev/tty(ACM|USB)\\d+");
        // first as the app itself, with the line as it is (toybox stty cannot set some MTK UARTs)
        String direct = javaQuery(port, QUERY_ULEFONE);
        if (usb) direct = (direct + " " + javaQuery(port, QUERY_AGOLD_34) + " " + javaQuery(port, QUERY_AGOLD_25)).trim();   // a USB MCU ignores the line speed
        r.append("  as-is (opened by the app, no stty): ").append(direct.isEmpty() ? "(nothing received)" : direct).append('\n');
        if (looksLikeModule(direct)) { r.append("  ^ the DMR module answered on this port at its current line speed\n"); return; }
        if (!usb && !saved.canChange) {                                         // the line cannot be set: the AT handshake as it is
            String at = javaQuery(port, AT_CONNECT);
            r.append("  as-is, after AT+DMOCONNECT: ").append(at.isEmpty() ? "(nothing received)" : at + "  \"" + asText(at) + "\"").append('\n');
            if (looksLikeAt(at)) { r.append("  ^ ").append(AT_MARK).append(" on ").append(port).append(" at ").append(saved.speed.isEmpty() ? "its current" : saved.speed).append(" baud\n"); return; }
        }
        if (usb) { r.append("  (USB serial: the line speed does not matter, no other speeds tried)\n"); return; }
        if (!saved.canChange) { r.append("  other speeds not tried: its speed ").append(saved.speed.isEmpty() ? "(unreadable)" : saved.speed).append(" could not be put back if changed\n"); return; }
        for (int baud : new int[] { 57600, 115200, 9600, 230400 }) {
            String hex = query(stty, port, baud, QUERY_ULEFONE);
            r.append(String.format(Locale.US, "  %6d baud (readback %s), after 0x34 query: %s\n", baud, speedOf(RootSetup.run(stty + " -F " + port + " 2>&1 | head -1")), hex.isEmpty() ? "(nothing received)" : hex));
            if (looksLikeModule(hex)) { r.append("  ^ looks like the DMR module answered at ").append(baud).append(" baud\n"); return; }
        }
        probeAt(r, stty, port, saved);
    }

    /** Put the line at {@code baud}, raw, send {@code q}, return up to 400 reply bytes as hex. */
    private static String query(String stty, String port, int baud, byte[] q) {
        String out = RootSetup.run(stty + " -F " + port + " " + baud + " raw -echo 2>&1; " + "(timeout 3 cat " + port + " | head -c 400 | od -An -tx1 | tr -d '\\n' | cut -c1-600) & sleep 0.3; " + printf(q) + port + "; sleep 3.2; echo");
        return out.replaceAll("\\s+", " ").trim();
    }

    /**
     * SA818-class modules (the W6's UHF module) answer text, not the DMR frame: AT+DMOCONNECT, then AT+DMOVERQ for the
     * version, at the port's own speed first, then 9600 (the family's default). Both only read the module's identity;
     * nothing that tunes, keys or changes a setting is ever sent.
     */
    private static void probeAt(StringBuilder r, String stty, String port, Saved saved) {
        java.util.LinkedHashSet<Integer> bauds = new java.util.LinkedHashSet<>();
        if (saved.speed.matches("\\d+")) bauds.add(Integer.parseInt(saved.speed));
        bauds.add(9600);
        for (int baud : bauds) {
            String hex = query(stty, port, baud, AT_CONNECT);
            r.append(String.format(Locale.US, "  %6d baud, after AT+DMOCONNECT: %s\n", baud, hex.isEmpty() ? "(nothing received)" : hex + "  \"" + asText(hex) + "\""));
            String ver = query(stty, port, baud, AT_VERSION);
            r.append(String.format(Locale.US, "  %6d baud, after AT+DMOVERQ: %s\n", baud, ver.isEmpty() ? "(nothing received)" : ver + "  \"" + asText(ver) + "\""));
            if (!looksLikeAt(hex) && !looksLikeAt(ver)) continue;
            r.append("  ^ ").append(AT_MARK).append(" on ").append(port).append(" at ").append(baud).append(" baud\n");
            return;
        }
    }

    /** Serial libraries of the system and the stock app; one with a wrapper class we already have is tried on each port. */
    private static void probeVendorLibs(android.content.Context ctx, StringBuilder r, List<String> ports) {
        DeviceProfile dp = DeviceProfile.detect();
        for (java.io.File lib : DeviceReport.serialLibs(ctx, dp.stockApp.isEmpty() ? null : dp.stockApp)) {
            String syms = DeviceReport.jniSymbols(lib);
            if (syms.isEmpty()) {
                String reg = DeviceReport.registeredNatives(lib);
                if (!reg.isEmpty()) r.append("\n").append(lib.getAbsolutePath()).append(" (registers its methods at load time):\n").append(reg);
                continue;
            }
            r.append("\n").append(lib.getAbsolutePath()).append(":\n").append(syms);
            if (!VendorSerial.haveWrapper(syms)) { r.append("  no wrapper class for this JNI class yet: add one named exactly as above (see VendorSerial.java) and the app can open the port the vendor way\n"); continue; }
            r.append("  known wrapper class (").append(VendorSerial.wrapperFor(syms)).append(") available: trying it on each port at 57600\n");
            if (!VendorSerial.load(ctx, lib.getAbsolutePath())) { r.append("  could not load it (another serial library is already loaded until SignalDeck restarts, or the load failed)\n"); continue; }
            String stty = stty();
            for (String port : ports) {
                Saved saved = save(stty, port);
                if (!saved.canChange) { r.append("  ").append(port).append(": not opened at 57600 (its speed ").append(saved.speed).append(" could not be put back)\n"); continue; }
                try {
                    VendorSerial.Port sp = VendorSerial.openPort(port, 57600);
                    if (sp == null) { r.append("  ").append(port).append(": native open failed\n"); continue; }
                    java.io.FileInputStream in = new java.io.FileInputStream(sp.fd()); java.io.FileOutputStream out = new java.io.FileOutputStream(sp.fd());
                    final byte[] buf = new byte[512]; final int[] got = { 0 };
                    Thread t = new Thread(() -> { try { int n; while (got[0] < buf.length && (n = in.read(buf, got[0], buf.length - got[0])) > 0) got[0] += n; } catch (Throwable ignored) { } }); t.setDaemon(true); t.start();
                    Thread.sleep(300); out.write(new byte[] { 0x68, 0x34, 0x01, 0x01, (byte) 0x95, (byte) 0xB9, 0x00, 0x01, 0x01, 0x10 }); out.flush(); Thread.sleep(3000);
                    StringBuilder hex = new StringBuilder(); for (int i = 0; i < got[0]; i++) hex.append(String.format(Locale.US, "%02x ", buf[i] & 0xFF));
                    r.append("  ").append(port).append(" via vendor library @57600: ").append(hex.length() == 0 ? "(nothing received)" : hex.toString().trim()).append(looksLikeModule(hex.toString()) ? "  <- module answered" : "").append('\n');
                    try { in.close(); out.close(); } catch (Throwable ignored) { } sp.release();
                } catch (Throwable t) { r.append("  ").append(port).append(": ").append(t).append('\n'); }
                finally { restore(stty, port, saved); }
            }
        }
    }
}
