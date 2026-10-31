/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The draft profile at the end of Run all checks (W6 plan item 8): every {@link DeviceProfile} field the report can
 * speak to, with a value or "unknown", how sure it is and what says so. Built from the finished report text only, so
 * the same report gives the same draft on a host. A maintainer reads it to write the profile; nothing here is applied.
 */
final class DraftProfile {
    private DraftProfile() { }

    static final String HIGH = "high", MEDIUM = "medium", LOW = "low", UNKNOWN = "unknown";

    static final class Field {
        final String name, value, confidence, evidence;
        Field(String name, String value, String confidence, String evidence) { this.name = name; this.value = value; this.confidence = confidence; this.evidence = evidence; }
    }

    /** The report text of one section ("==== title …" up to the next "==== "), or "" when it is absent. */
    static String section(String report, String titleStart) {
        int a = report.indexOf("==== " + titleStart); if (a < 0) return "";
        int b = report.indexOf("\n==== ", a + 5);
        return report.substring(a, b < 0 ? report.length() : b);
    }

    private static String first(String text, String regex, int group) {
        Matcher m = Pattern.compile(regex).matcher(text); return m.find() ? m.group(group) : null;
    }

    static List<Field> fields(String report) {
        String r = report == null ? "" : report;
        String watch = section(r, "The stock app at work"), probe = section(r, "Radio probe"), summary = section(r, "Summary");
        String ptt = section(r, "PTT key capture"), libs = section(r, "Serial-looking system libraries");
        String clues = "";
        for (Matcher m = Pattern.compile("==== Clues in [^\\n]*").matcher(r); m.find(); ) clues += section(r, m.group().substring(5));
        boolean bridged = r.contains(AgoldUsb.WARNING);
        List<Field> f = new ArrayList<>();

        // stock app
        String watched = first(watch, "^==== The stock app at work \\(([A-Za-z0-9_.]+),", 1), ranked = first(r, "(?m)^  examined below: ([A-Za-z0-9_.]+)", 1);
        if (watched != null) f.add(new Field("stockApp", watched, HIGH, "watched for 15 s"));
        else if (ranked != null) f.add(new Field("stockApp", ranked, MEDIUM, "best-ranked candidate, not watched"));
        else f.add(new Field("stockApp", "unknown", UNKNOWN, "no candidate"));

        // protocol: what the module answered, else what the stock app sent
        String agold = first(watch, "module replies in its log: (the module answered the stock app \\d+ times \\(Agold frames[^\\n]*)", 1);
        String at = first(probe, "an AT-command module \\(SA818 class\\) answered on (\\S+) at (\\d+) baud", 0);
        String family = first(summary, "(?m)^its serial traffic \\(stock_serial.txt\\): ([^\\n]+)", 1);
        String viaBridge = first(section(r, "Radio probe through the USB bridge"), Pattern.quote(AgoldUsb.MODULE_ANSWERED) + "([^\\n]+)", 1);
        if (at != null) f.add(new Field("protocol", "sa818", HIGH, "the probe: " + at.trim()));
        else if (viaBridge != null) f.add(new Field("protocol", "agold: mcu68 inside the Agold 7E 96 69 envelope (SignalDeck's agold engine)", HIGH, "SignalDeck's own probe through the bridge, checksums verified: " + viaBridge.trim()));
        else if (agold != null) f.add(new Field("protocol", "agold: mcu68 inside the Agold 7E 96 69 envelope (SignalDeck's agold engine)", HIGH, agold.replaceAll("\\)\\s*$", ")")));
        else if (summary.contains("radio module answered the version query: yes")) f.add(new Field("protocol", "mcu68", HIGH, "the probe's 0x34 version query was answered"));
        else if (family != null && !family.startsWith("none")) f.add(new Field("protocol", family, MEDIUM, "the stock app's serial traffic"));
        else f.add(new Field("protocol", "unknown", UNKNOWN, "no answer, no serial traffic recognised"));

        // control port and line speed
        Matcher held = Pattern.compile("skipped (/dev/tty(?:ACM|USB)\\d+): in use by (\\S+)").matcher(probe);
        String stockTty = first(watch, "devices it holds open:[^\\n]*?(/dev/tty(?:S|MT|HS|ACM|USB)\\d+)", 1);
        String stockBaud = stockTty == null ? null : first(watch, Pattern.quote("  " + stockTty + ": speed ") + "(\\d+) baud", 1);
        String bridgeProbe = section(r, "Radio probe through the USB bridge");
        String[] bridge = AgoldUsb.port(watch);
        if (bridge == null) bridge = AgoldUsb.port(bridgeProbe);                      // a run without the watch: the probe saw it
        if (bridge != null) {
            String holder = bridge[2].split("\\s+")[0];
            f.add(new Field("controlPort", bridge[0] + " (USB serial to a bridge MCU)", HIGH, "listed while the radio was on, held by " + bridge[2]));
            f.add(new Field("baud", "none (USB CDC-ACM)", HIGH, "a USB serial port has no line speed"));
            f.add(new Field("transport", holder.contains("/vendor/bin/hw/") ? "vendor HAL " + holder.replaceAll(".*/", "").replaceAll("-service$", "") + " (the stock app calls it over HwBinder)" : "held by " + holder, HIGH, "the process holding the port"));
        } else if (held.find()) {
            f.add(new Field("controlPort", held.group(1) + " (USB serial to a bridge MCU)", HIGH, "held by " + held.group(2) + " while the radio is on"));
            f.add(new Field("baud", "none (USB CDC-ACM)", HIGH, "a USB serial port has no line speed"));
            f.add(new Field("transport", held.group(2).contains("/vendor/bin/hw/") ? "vendor HAL " + held.group(2).replaceAll(".*/", "").replaceAll("-se(rvice)?$", "") + " (the stock app calls it over HwBinder)" : "held by " + held.group(2), HIGH, "the probe found the port in use by it"));
        } else if (at != null) {
            Matcher m = Pattern.compile("answered on (\\S+) at (\\d+) baud").matcher(at); m.find();
            f.add(new Field("controlPort", m.group(1), HIGH, "the module answered there"));
            f.add(new Field("baud", m.group(2), HIGH, "the module answered at this speed"));
        } else if (stockTty != null) {
            f.add(new Field("controlPort", stockTty, HIGH, "the stock app holds it open"));
            f.add(new Field("baud", stockBaud == null ? "unknown" : stockBaud, stockBaud == null ? UNKNOWN : HIGH, stockBaud == null ? "no line settings read" : "its line settings while the stock app ran"));
        } else {
            f.add(new Field("controlPort", "unknown", UNKNOWN, "the stock app held no serial port the report saw"));
            f.add(new Field("baud", "unknown", UNKNOWN, "-"));
        }

        // audio
        Matcher usbAudio = Pattern.compile("-- /proc/asound/card(\\d+)/stream\\d+[\\s\\S]*?Channels: (\\d+)[\\s\\S]*?Rates: (\\d+)").matcher(watch + "\n" + bridgeProbe);
        if (usbAudio.find()) f.add(new Field("audio", "USB Audio card " + usbAudio.group(1) + ", " + usbAudio.group(3) + " Hz, " + usbAudio.group(2) + " channels (SignalDeck's direct USB capture, per reception)", HIGH, "its stream while the radio was on"));
        else if (r.contains("PrizeTinyService")) f.add(new Field("audio", "TINY_SERVICE", MEDIUM, "PrizeTinyService is a binder service"));
        else f.add(new Field("audio", "unknown", UNKNOWN, "see Sound card streams and mixer, Audio clues"));

        // power
        if (bridged || bridge != null) f.add(new Field("powerNode", "/sys/bus/platform/drivers/mcu/mcu_power, through the HAL's startMcu/stopMcu (release: STOP_DEVICE, then the USB controller's cmode 0 and 1)", MEDIUM,
                watch.contains("mcu_power = 0") ? "the release at the end of the watch read it as off" : "the probe through the bridge powered it this way"));
        else if (namedLine(r, "(?i)(^|_)(pwd|pwr|power)(_|$)") != null) { String[] l = namedLine(r, "(?i)(^|_)(pwd|pwr|power)(_|$)"); f.add(new Field("powerNode", l[1], MEDIUM, "named " + l[0] + " in the stock app's code")); }
        else f.add(new Field("powerNode", "unknown", UNKNOWN, "not found by the report"));
        { String[] l = namedLine(r, "(?i)(^|_)ptt(_|$)"); if (l != null && !bridged && bridge == null) f.add(new Field("pttNode", l[1], MEDIUM, "named " + l[0] + " in the stock app's code")); }

        // PTT key and broadcasts
        String layout = first(ptt, "(?m)^  scancode 0x[0-9a-f]+ \\(\\d+\\) on [^\\n]+ maps it to [^\\n]+", 0);
        String event = first(ptt, "(/dev/input/event\\d+): EV_KEY +([0-9a-fA-F]{4}|KEY_\\w+) +DOWN[^\\n]*", 0);
        if (layout != null) f.add(new Field("pttKey", layout.trim().replaceAll("^scancode ", ""), MEDIUM, "pressed during the capture (check it was the side key)"));
        else if (event != null) f.add(new Field("pttKey", event.trim(), MEDIUM, "pressed during the capture"));
        else f.add(new Field("pttKey", "unknown", UNKNOWN, "no key was pressed during the capture"));
        String acts = first(clues, "(?m)^  PTT broadcasts: ([^\\n]+)", 1);
        if (acts != null) acts = broadcasts(acts);
        if (acts != null && !acts.isEmpty()) f.add(new Field("pttActions", acts, MEDIUM, "names in the stock app's code"));
        else f.add(new Field("pttActions", "unknown", UNKNOWN, "none in the stock app's code"));

        // SELinux label of the control port
        String port = f.stream().filter(x -> x.name.equals("controlPort")).findFirst().get().value.replaceAll(" .*", "");
        String label = bridge != null ? bridge[1].replaceAll("^u:object_r:|:s0$", "")
                : port.startsWith("/dev/") ? first(probe, "u:object_r:([A-Za-z0-9_]+):s0 +" + Pattern.quote(port) + "\\b", 1) : null;
        if (label != null) f.add(new Field("sepolicy", "allow untrusted_app " + label + " chr_file { open read write ioctl getattr lock }; typeattribute " + label + " mlstrustedobject",
                bridged || bridge != null ? LOW : MEDIUM, bridged || bridge != null ? "the port's label; reaching it also needs the HAL stopped, or hwservice rules for the HAL route" : "the port's label"));
        else f.add(new Field("sepolicy", "unknown", UNKNOWN, "the control port's label was not listed"));

        String lib = first(libs, "(?m)^\\s*(/\\S+\\.so)", 1);
        f.add(lib != null ? new Field("serialLib", lib, MEDIUM, "serial-looking system library") : new Field("serialLib", "none", LOW, "no serial-looking system library"));
        if (bridged || bridge != null) f.add(new Field("usb", "the radio takes the phone's only USB port while it is on", HIGH, "the radio is a USB device behind the bridge: release needed on exit"));
        return f;
    }

    /** The clue line's names that can be broadcasts: system property names (ro.…, persist.…) are not. */
    static String broadcasts(String names) {
        StringBuilder b = new StringBuilder();
        for (String n : names.trim().split("\\s+")) if (!n.equals("-") && !n.matches("(ro|persist|sys|vendor|debug)\\..*")) b.append(b.length() == 0 ? "" : " ").append(n);
        return b.toString();
    }

    /** The block for the report and the summary. */
    static String text(String report) {
        StringBuilder out = new StringBuilder("draft profile (confidence: evidence); nothing here is applied:\n");
        for (Field x : fields(report)) out.append(String.format(java.util.Locale.US, "  %-12s %s\n  %-12s   (%s: %s)\n", x.name, x.value, "", x.confidence, x.evidence));
        return out.toString();
    }

    /** {field, path} of the first line in the report's "named lines:" whose field name matches {@code nameRegex}, or null. */
    static String[] namedLine(String report, String nameRegex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^  named lines: (.+)$").matcher(report == null ? "" : report);
        if (!m.find()) return null;
        java.util.regex.Pattern n = java.util.regex.Pattern.compile(nameRegex);
        for (String kv : m.group(1).trim().split("\\s+")) {
            int eq = kv.indexOf('='); if (eq <= 0) continue;
            String name = kv.substring(0, eq), path = kv.substring(eq + 1);
            if (path.startsWith("/sys/") && n.matcher(name).find()) return new String[] { name, path };
        }
        return null;
    }
}
