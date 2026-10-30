/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.Locale;

/**
 * Agold phones whose radio sits behind a USB bridge MCU and a vendor HAL (Unihertz Atom XL, measured 2026-10-28). The
 * bridge is a USB device on the phone's only USB controller, so while the radio is powered the controller is a host
 * and a computer on the cable loses the phone (adb, file transfer). A force-stop of the stock app leaves the bridge
 * powered (the HAL keeps /dev/ttyACM0 open). The stock app's own power-off ({@link #STOP_DEVICE} → HAL stopMcu) switches
 * it off but leaves the controller in mode "none"; toggling the MediaTek controller's cable mode (cmode 0, then 1)
 * puts it back in device mode, and adb over the cable answers about 2 s later.
 */
final class AgoldUsb {
    private AgoldUsb() { }

    /** The stock app's power-off broadcast (protected: the shell user is refused, root may send it). */
    static final String STOP_DEVICE = "agui.intercom.intent.action.STOP_DEVICE";
    static final String BRIDGE_PORT = "/dev/ttyACM0";
    /** The bridge driver's power node: "power on = 1" while the radio is powered. */
    static final String MCU_POWER = "/sys/bus/platform/drivers/mcu/mcu_power";

    static final String WARNING = "this phone's radio is a USB device on the phone's only USB port: while the radio is on, "
            + "a computer on the USB cable loses the phone (adb, file transfer). The checks give the port back when they "
            + "close the stock app; for bench work use Wi-Fi ADB";

    /** True for a getprop value of {@code ro.agold.extmodule.hal} that marks the bridge HAL. */
    static boolean bridged(String extmoduleHalProp) { return "yes".equals(extmoduleHalProp == null ? "" : extmoduleHalProp.trim()); }

    /** True when this phone has the bridge HAL (root). */
    static boolean bridged() { return bridged(RootSetup.run("getprop ro.agold.extmodule.hal")); }

    /**
     * The USB controller directory holding {@code cmode}, from the resolved path of a /sys/class/udc entry
     * ({@code /sys/devices/platform/11201000.usb3/udc/musb-hdrc} → {@code /sys/devices/platform/11201000.usb3}), or null.
     */
    static String controllerDir(String udcPath) {
        String p = udcPath == null ? "" : udcPath.trim();
        int i = p.lastIndexOf("/udc/");
        return i > 0 && p.startsWith("/sys/devices/") ? p.substring(0, i) : null;
    }

    /** From the {@link #MCU_POWER} text: true while powered, false when off, null when it says neither (node missing). */
    static Boolean powered(String mcuPower) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("power on\\s*=\\s*([01])").matcher(mcuPower == null ? "" : mcuPower);
        return m.find() ? "1".equals(m.group(1)) : null;
    }

    /**
     * Power the radio off the way the stock app does and give the USB port back to the socket. Run before the app is
     * force-stopped (the broadcast needs it running). The cable mode is toggled only once the bridge driver says the
     * radio is off, so a radio that is still powered is never cut off underneath its HAL. (Not {@link #BRIDGE_PORT}: its
     * node outlives the power-off while the HAL still has it open, measured 2026-10-28.)
     */
    static String release(String pkg) throws InterruptedException {
        StringBuilder r = new StringBuilder();
        boolean running = !RootSetup.run("pidof " + pkg + " || true").replaceAll("[^0-9]", "").isEmpty();
        if (running) r.append("power-off (").append(STOP_DEVICE).append("): ").append(RootSetup.run("am broadcast -a " + STOP_DEVICE + " 2>&1 | tail -1")).append('\n');
        else r.append("stock app not running: no power-off sent\n");
        int waited = 0;
        Boolean on = powered(RootSetup.run("cat " + MCU_POWER + " 2>/dev/null"));
        while (Boolean.TRUE.equals(on) && waited < 6000) { Thread.sleep(500); waited += 500; on = powered(RootSetup.run("cat " + MCU_POWER + " 2>/dev/null")); }
        if (on == null) return r.append("no ").append(MCU_POWER).append(": the radio's power state is unknown, the USB port was left as it is (a restart gives it back)\n").toString();
        if (on) return r.append("the radio is still powered after 6 s (").append(MCU_POWER).append("): the USB port was left as it is (a restart gives it back)\n").toString();
        r.append(String.format(Locale.US, "the radio is off after %.1f s (%s = 0)\n", waited / 1000.0, MCU_POWER));
        String dir = null;
        for (String u : RootSetup.run("for u in /sys/class/udc/*; do readlink -f $u; done").split("\n")) if ((dir = controllerDir(u)) != null) break;
        if (dir == null || RootSetup.run("[ -f " + dir + "/cmode ] && echo yes").trim().isEmpty())
            return r.append("no USB controller cable-mode node found: a computer on the USB cable may need a restart of the phone to see it again\n").toString();
        RootSetup.run("echo 0 > " + dir + "/cmode; sleep 1; echo 1 > " + dir + "/cmode");
        return r.append("USB port given back to the socket (").append(dir).append("/cmode 0, then 1)\n").toString();
    }

    static final String USB_PATH = "/sys/bus/platform/drivers/mcu/usb_path", RSTMCU = "/sys/bus/platform/drivers/mcu/rstmcu", HAL_SERVICE = "aguiextmodule";
    /**
     * The bridge driver's event node (agui_device, mode 0666). The HAL's detectAudioinState keeps it open and reads one
     * byte at a time; each read blocks until the radio's audio line changes: 0 = a reception started, 1 = it ended
     * (measured on the Atom XL 2026-10-28 with DMR calls from a HackRF). The sysfs call_in node is not reliable.
     */
    static final String STMCU = "/dev/stmcu";
    /** The driver's reception flag: "0" while a reception holds the line, "1" otherwise. */
    static final String CALL_IN = "/sys/bus/platform/drivers/mcu/call_in";
    /** call_in read back: exactly "0" is a reception in progress (anything else, an error included, is not). */
    static boolean receptionInProgress(String callIn) { return callIn != null && callIn.trim().equals("0"); }
    /** True when call_in read back as the driver's flag ("0" or "1"), not an error. */
    static boolean callInKnown(String callIn) { return callIn != null && (callIn.trim().equals("0") || callIn.trim().equals("1")); }
    /**
     * At the end of a power-up window: 2 = end the reception SignalDeck has open (the driver says idle), 1 = open one (the
     * driver says a reception holds the line and the signal reading is above the start threshold), 0 = nothing.
     */
    static int windowAction(boolean receiving, String callIn, double dbm, double startDbm) {
        if (callIn == null || !(callIn.trim().equals("0") || callIn.trim().equals("1"))) return 0;
        boolean line = receptionInProgress(callIn);
        if (receiving && !line) return 2;
        if (!receiving && line && dbm > startDbm) return 1;
        return 0;
    }

    /**
     * Android treats the bridge as a USB headset: at every enumeration its audio policy connects the card and the USB audio
     * HAL opens it, beside SignalDeck's capture (measured 2026-10-28). The developer setting "Disable USB audio routing"
     * stops that; it is switched on while SignalDeck owns the radio and put back when it lets go.
     */
    static final String USB_ROUTING_OFF = "usb_audio_automatic_routing_disabled";

    /** Switch USB audio routing off; returns the previous value ("null" when unset) to restore later. */
    static String usbRoutingOff() {
        String prev = RootSetup.run("settings get secure " + USB_ROUTING_OFF).trim();
        if (!"1".equals(prev)) RootSetup.run("settings put secure " + USB_ROUTING_OFF + " 1");
        return prev.isEmpty() ? "null" : prev;
    }

    /** Put the setting back as {@link #usbRoutingOff} found it. */
    static void usbRoutingRestore(String prev) {
        if (prev == null || "1".equals(prev)) return;
        RootSetup.run("null".equals(prev) ? "settings delete secure " + USB_ROUTING_OFF : "settings put secure " + USB_ROUTING_OFF + " " + prev);
    }

    /** Edges read in this long after the reader starts are the bridge's power-up toggles, not receptions. */
    static final long POWER_UP_EDGES_MS = 2000;

    /** A write to the bridge's serial port that takes longer than this has failed: the bridge stopped taking data. */
    static final long WRITE_DEADLINE_MS = 2000;
    /** A capture that died during a reception is restarted (a bridge power-cycle) when the reception ends, or after this long. */
    static final long CAPTURE_DEAD_WAIT_MS = 5000;
    /** After a charger or USB change, the bridge is left alone this long before it is powered up (the stock app waits 4 s). */
    static final long PLUG_SETTLE_MS = 4000;
    /** How long to wait before a bridge power-up, given the last charger or USB change (0 = none yet). */
    static long plugSettleWait(long now, long lastPlugChangeAt) {
        return lastPlugChangeAt == 0 ? 0 : Math.max(0, lastPlugChangeAt + PLUG_SETTLE_MS - now);
    }

    /**
     * Signal-reading thresholds of the reception watchdog, given the channel's idle noise floor (NaN until it is known):
     * a reception starts above the higher of -95 dBm and floor + 6 dB and ends below the higher of -100 dBm and
     * floor + 2 dB. With a charger on the USB socket the bench channel idled at -98 dBm (2026-10-29, -101 to -103 without
     * it), so a fixed -100 dBm end never came and a reception the watchdog had opened stayed open for good.
     */
    static double rssiStartDbm(double floor) { return Double.isNaN(floor) ? -95 : Math.max(-95, floor + 6); }
    static double rssiEndDbm(double floor) { return Double.isNaN(floor) ? -100 : Math.max(-100, floor + 2); }
    /** The idle floor: a slow running mean of readings taken while nothing is received (a reading above the start threshold is not idle). */
    static double nextFloor(double floor, double dbm) {
        if (dbm > rssiStartDbm(floor)) return floor;
        return Double.isNaN(floor) ? dbm : floor + (dbm - floor) * 0.1;
    }

    /** Receptions in a row that the line never reported (found from the signal, or opened by MON) before the line counts as stuck. */
    static final int STUCK_LINE_MISSES = 2;
    /**
     * A reception found from the signal reading counts against the reception line only when it lasted this long: on
     * 2026-10-29 a short off-channel blip (its source in the cage was not identified; a HackRF on 446 MHz, even at full
     * power, did not reproduce it) read -87 dBm on the Atom XL's 401.025 MHz for about a second, the line rightly stayed
     * silent, and that blip was counted as a missed reception and restarted the bridge.
     */
    static final long LINE_MISS_MIN_MS = 3000;
    /**
     * No signal reading for the S-meter while the line reports a reception: a command crossing the bridge's USB link while
     * it streams audio breaks the stream (2026-10-29, RTTY/speech from a HackRF on 401.025 MHz: 12 of 12 receptions clean
     * with no 0x32 during them, against 1 drop in 6 with a reading every 0.5 to 2 s). The watchdog's own receptions (found
     * from the signal) keep reading, they end on it; measurement tools read anyway.
     */
    /**
     * A signal reading for the coverage log: not while SignalDeck restarts the bridge, nor in the grace after it
     * ({@code ownCycleUntil}, 1.5 s, past the ~0.7 s the module's reading needs after a power-up). 2026-10-29 21:51 a
     * reading taken 24 ms after the power-cycle was stored as -109 dBm beside two of -74 from the same carrier.
     */
    static boolean coverageReadingUsable(boolean agold, long now, long ownCycleUntil) { return !agold || now >= ownCycleUntil; }
    /**
     * A capture opened for a reception found only from the signal is closed by SignalDeck when no audio came within this
     * long and the line still has not reported it: energy from another transmitter close by makes the module play
     * nothing, the bridge streams only while the module plays, and a capture left open hits the kernel's 2 s I/O error,
     * after which the bridge streams nothing until it is power-cycled (2026-10-29 21:41 and 22:12: about 12 s without
     * the radio each time; skipping that restart instead left the next real reception silent). A clean close, as at the
     * end of every reception, keeps the bridge healthy; the capture reopens if the line reports the reception after all.
     */
    static final long SIGNAL_ONLY_AUDIO_WAIT_MS = 1500;
    /**
     * How long a reception waits before its capture opens: none when the line reported it (or MON opened it), and for a
     * reception found only from the signal until it has lasted {@link #LINE_MISS_MIN_MS}, so a short burst from another
     * transmitter never opens one. A station the line really missed loses only its first seconds, and the line's own
     * catching up opens the capture at once.
     */
    static long captureOpenDelay(boolean signalOnly, boolean mon, long heldMs) { return !signalOnly || mon ? 0 : Math.max(0, LINE_MISS_MIN_MS - heldMs); }
    static boolean closeSilentSignalOnlyCapture(boolean signalOnly, boolean mon, long audioBytes) { return signalOnly && !mon && audioBytes == 0; }
    static boolean holdsRxRead(boolean agold, boolean lineReception, boolean synthetic) { return agold && lineReception && !synthetic; }
    static boolean countsAsLineMiss(long heldMs) { return heldMs >= LINE_MISS_MIN_MS; }
    /** At least this long between two automatic bridge power-cycles of any kind. */
    static final long AUTO_CYCLE_GAP_MS = 20_000;
    /**
     * What to do after {@code missed} receptions in a row that the reception line never reported: 0 nothing (yet), 1 restart
     * the bridge (once per bring-up), 2 stop trusting the line (receptions come from the signal; the phone needs a restart).
     * The line can stay in its power-up state for a whole session (2026-10-29: one power-up byte, then no edge through MON
     * and a −71 dBm carrier), and the stock app then hears nothing either.
     */
    static int stuckLineAction(int missed, int restarts, boolean fallback) {
        if (fallback || missed < STUCK_LINE_MISSES) return 0;
        return restarts == 0 ? 1 : 2;
    }

    /** The engine's 0x36 status code for a {@link #STMCU} byte: 1 RECEIVE_START, 2 RECEIVE_STOP, -1 for anything else. */
    static int receiveStatus(int b) { return b == 0 ? 1 : b == 1 ? 2 : -1; }

    /** The bridge's PTT line: active low (0 keys, 1 idle), as the HAL's startPtt / stopPtt write it. */
    static final String HANDLE_PTT = "/sys/bus/platform/drivers/mcu/handle_ptt";

    private static final String STTY = "S=stty; [ -x /data/adb/magisk/busybox ] && S='/data/adb/magisk/busybox stty'; ";
    /**
     * The port node outlives a power-off while a holder (the HAL, just restarted) keeps it open, so a power-up waits up
     * to 5 s for the old node to go first; otherwise the next step would take the stale node for the new port (a hand-back
     * check that way asked nothing and found the bridge "silent", 2026-10-28).
     */
    private static final String STALE_PORT_GONE = "k=0; while [ -e " + BRIDGE_PORT + " ] && [ $k -lt 25 ]; do sleep 0.2; k=$((k+1)); done; "
            + "[ -e " + BRIDGE_PORT + " ] && echo 'stale port still there'; ";
    /**
     * Bridge power off, then wait up to 6 s for the USB disconnect to be processed (the tty leaves /sys/class/tty) before
     * anything takes the controller out of host mode: switched over too early, the dead bridge stays registered as a
     * ghost ttyACM0 that no power-up replaces and only a reboot clears (2026-10-28, twice).
     */
    private static final String POWER_OFF = "echo 0 > " + MCU_POWER + "; "
            + "k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 30 ]; do sleep 0.2; k=$((k+1)); done; "
            + "[ -e /sys/class/tty/ttyACM0 ] && echo 'bridge still registered after power-off'; sleep 0.3; ";
    /**
     * The stock HAL's stop_mcu powers the bridge off, waits 0.2 s, powers it on for 0.2 s (usb_path 1, mcu_power 1,
     * rstmcu 0) and off again (disassembled 2026-10-29, docs/atomxl/HAL_SEQUENCES.md): too short for the bridge to
     * enumerate, a reset pulse for its next start. SignalDeck's power-off had no pulse, and a session that SignalDeck
     * left in the middle of a reception (killed) came back with a reception line that never reported again.
     */
    static final String HAL_PULSE = "sleep 0.2; echo 1 > " + USB_PATH + "; echo 1 > " + MCU_POWER + "; echo 0 > " + RSTMCU + "; sleep 0.2; echo 0 > " + MCU_POWER + "; "
            + "k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 15 ]; do sleep 0.2; k=$((k+1)); done; ";
    /** Radio off (with the HAL's pulse) and the USB controller back in device mode (cable mode 0, then 1); a clean start for the next power-up. */
    static final String RESET = POWER_OFF + HAL_PULSE + "echo 0 > " + USB_PATH + "; sleep 0.5; "
            + "for u in /sys/class/udc/*; do d=$(readlink -f $u); d=${d%/udc/*}; [ -f $d/cmode ] && { echo 0 > $d/cmode; sleep 1; echo 1 > $d/cmode; }; done; sleep 0.5; ";
    /**
     * The HAL's own start-up (traced 2026-10-28): usb_path 1, mcu_power 1, 0.75 s, rstmcu 0 (after a fresh boot the bridge
     * sits in reset and never enumerates without it), then the port within 3 s; when the controller refuses device ->
     * host it goes through "none" (power off, usb_path 0) and tries once more for 10 s. Leaves $i set.
     */
    private static final String ONE_POWER_UP = "setprop vendor.agui.extmodule.open 1 2>/dev/null; echo 1 > " + USB_PATH + "; echo 1 > " + MCU_POWER + "; sleep 0.75; echo 0 > " + RSTMCU + "; ";
    static final String POWER_UP = STALE_PORT_GONE + ONE_POWER_UP
            + "i=0; while [ ! -e " + BRIDGE_PORT + " ] && [ $i -lt 6 ]; do sleep 0.5; i=$((i+1)); done; "
            + "if [ ! -e " + BRIDGE_PORT + " ]; then echo 'retry: through none'; " + RESET + ONE_POWER_UP
            + "i=0; while [ ! -e " + BRIDGE_PORT + " ] && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); done; fi; ";
    /** Radio off, USB port back to the socket, the HAL running again. */
    static final String POWER_DOWN = RESET + "setprop vendor.agui.extmodule.open 0 2>/dev/null; start " + HAL_SERVICE + "; ";

    /**
     * Take the radio for SignalDeck (design §4, Atom XL): stop the HAL (init does not restart a stopped hal-class
     * service), switch off a bridge that is still powered (the stock app was paused under it, or SignalDeck itself was
     * killed while it owned the radio), power it up the HAL's way, then let the app open the port: mode 0666 (the node is
     * made again at every power-up) and 115200 raw, PTT line idle. Throws when no port appears; the radio is then off and
     * the HAL back.
     */
    static String bridgeUp() throws java.io.IOException {
        String out = RootSetup.run(STTY + "stop " + HAL_SERVICE + "; sleep 0.5; "
                + "case \"$(cat " + MCU_POWER + ")\" in *'= 1'*) echo 'was powered: off first'; " + RESET + ";; esac; "
                + POWER_UP
                + "if [ -e " + BRIDGE_PORT + " ]; then sleep 2; chmod 0666 " + BRIDGE_PORT + "; $S -F " + BRIDGE_PORT + " 115200 raw -echo 2>&1; echo 1 > " + HANDLE_PTT + "; "
                + "echo \"port: after $((i*5)) tenths of a second\"; else echo 'port: none'; " + POWER_DOWN + "fi");
        if (!out.contains("port: after")) throw new java.io.IOException("the radio's USB bridge did not appear (" + out.replace('\n', ' ').trim() + "); the radio is off again");
        return "bridge up: " + out.replace('\n', ' ').trim();
    }

    /** Radio off, the USB port back to the socket and the HAL running again (SignalDeck's Exit, and the start of a hand-back). */
    static String bridgeDown() {
        String out = RootSetup.run("echo 1 > " + HANDLE_PTT + " 2>/dev/null; " + POWER_DOWN + "sleep 0.5; "
                + "echo \"radio $(cat " + MCU_POWER + "), HAL $(pidof vendor.mediatek.hardware." + HAL_SERVICE + "@1.0-service >/dev/null && echo running || echo NOT running)\"");
        return "bridge down: " + out.replace("power on = 0", "off").replace("power on = 1", "STILL ON").replace('\n', ' ').trim();
    }

    /**
     * Hand the radio back to the stock app: {@link #bridgeDown}, then ask the bridge for its version once more
     * ({@link #identityProbe}) and enable the stock app only when it answered. The stock app reflashes the bridge when its
     * version query goes unanswered at start-up, so a silent bridge keeps it paused and the user is told to restart the
     * phone first (a restart leaves a working stock app). Returns what happened.
     */
    static String giveBack(DeviceProfile p, java.io.File dir) {
        StringBuilder r = new StringBuilder(bridgeDown()).append('\n');
        String probe = identityProbe(dir, p.stockApp);
        r.append(probe);
        String pending = pendingStockFirmware(RootSetup.run("ls " + STOCK_FIRMWARE_DIR + " 2>/dev/null"));
        if (pending != null) r.append(STOCK_FIRMWARE_WARNING).append(pending).append('\n');
        if (bridgeAnswered(probe)) r.append("stock app: ").append(RootSetup.stockApp(p, true)).append('\n');
        else r.append(GIVE_BACK_HELD).append('\n');
        return r.toString();
    }

    /**
     * The stock app loads any .bin in this folder into the radio module by itself when the file's name differs from the
     * module's version reply, or when the version query goes unanswered (the V076 analysis, phonedmrapp
     * docs/atomxl-v076-firmware-analysis.md §9). SignalDeck never writes there; Hand back warns when something is in it.
     */
    static final String STOCK_FIRMWARE_DIR = "/sdcard/intercom/dmr";
    static final String STOCK_FIRMWARE_WARNING = "WARNING: the stock app would load these files into the radio module at its start: ";

    /** The .bin names in a listing of {@link #STOCK_FIRMWARE_DIR}, comma-separated, or null when there are none. */
    static String pendingStockFirmware(String listing) {
        StringBuilder b = new StringBuilder();
        for (String l : (listing == null ? "" : listing).split("\\s+")) if (l.toLowerCase(java.util.Locale.US).endsWith(".bin")) b.append(b.length() > 0 ? ", " : "").append(l);
        return b.length() == 0 ? null : b.toString();
    }

    /**
     * Agold's App block (system_server's AppblockController, on when ro.agui.appblock=on): an app without its own entry is
     * force-stopped a few minutes after the screen goes off (appclear_time, default 300 s), and its start at boot and by
     * other apps is blocked, which cuts SignalDeck off while it owns the radio (the 15:51 force-stop, 2026-10-28). Returns
     * what is still on for {@code pkg} ("background cleanup, boot blocker, start blocker"), or null when nothing is.
     */
    static String appBlockProblem(String prop, String enabled, String listJson, String pkg) {
        if (!"on".equals(prop == null ? "" : prop.trim())) return null;
        String en = enabled == null ? "" : enabled.trim();
        if (en.equals("0")) return null;                                     // switched off for the whole phone
        boolean clean = true, boot = true, start = true;                     // its defaults for an app without an entry
        try {
            org.json.JSONObject o = new org.json.JSONObject(listJson == null || listJson.trim().isEmpty() || listJson.trim().equals("null") ? "{}" : listJson.trim()).optJSONObject(pkg);
            if (o != null) { clean = o.optBoolean("clean", true); boot = o.optBoolean("blockboot", true); start = o.optBoolean("blockstart", true); }
        } catch (org.json.JSONException ignored) { }
        StringBuilder b = new StringBuilder();
        if (clean) b.append("background cleanup");
        if (boot) b.append(b.length() > 0 ? ", " : "").append("boot blocker");
        if (start) b.append(b.length() > 0 ? ", " : "").append("start blocker");
        return b.length() == 0 ? null : b.toString();
    }

    /** {@link #appBlockProblem} for this phone (root). */
    static String appBlockProblem(String pkg) {
        String[] v = RootSetup.run("echo \"$(getprop ro.agui.appblock)|$(settings get system appblock_enabled)|$(settings get system appblock_list)\"").split("\\|", 3);
        return v.length < 3 ? null : appBlockProblem(v[0], v[1], v[2], pkg);
    }

    /** Agold's App block page for {@code pkg} (not exported: started as root). */
    static String openAppBlock(String pkg) {
        return RootSetup.run("am start -n com.agui.appblock/.AppBlockActivity --es package_name " + pkg + " 2>&1 | tail -1");
    }

    static final String GIVE_BACK_HELD = "the stock app stays paused: restart the phone, then open it (it would otherwise update the bridge's firmware)";

    /** True when an {@link #identityProbe} result shows the bridge's version reply. */
    /**
     * Run all checks opens the stock app for its 15 s watch; on this phone that start can load firmware into the radio: any
     * .bin in {@link #STOCK_FIRMWARE_DIR}, or the bridge's own image when the bridge does not answer. Returns why the watch
     * must be skipped, or null. {@code probe} is {@link #identityProbe}'s output (a probe that did not run blocks nothing).
     */
    static String stockWatchBlocked(String listing, String probe) {
        String pending = pendingStockFirmware(listing);
        if (pending != null) return "the stock app would load firmware files from " + STOCK_FIRMWARE_DIR + " into the radio at its start (" + pending + ")";
        if (probe != null && !probe.startsWith("not run") && !bridgeAnswered(probe)) return "the radio's USB bridge did not answer, so the stock app would try to update its firmware: restart the phone first";
        return null;
    }
    static String stockWatchBlocked(java.io.File dir, String pkg) {
        String listing = RootSetup.run("ls " + STOCK_FIRMWARE_DIR + " 2>/dev/null");
        if (pendingStockFirmware(listing) != null) return stockWatchBlocked(listing, null);
        return stockWatchBlocked(listing, identityProbe(dir, pkg));
    }

    static boolean bridgeAnswered(String probe) {
        return probe != null && !probe.startsWith("not run") && probe.contains("bridge version (route 00, cmd 0x01): \"") && !probe.contains(BRIDGE_SILENT);
    }

    /**
     * Ask the bridge and the module who they are, with nobody else on the line (Atom XL, measured by hand 2026-10-28):
     * stop the HAL, switch the USB port to host ({@link #USB_PATH} 1: {@link #MCU_POWER} alone powers the bridge
     * without it), power the bridge and release its reset ({@link #RSTMCU} 0, as the HAL does), wait for {@link #BRIDGE_PORT}, send the stock app's own two version queries (route
     * 00 cmd 01, route 01 cmd 0x34), then power off, give the port back to the socket and start the HAL again, in one
     * root script so the clean-up runs whatever happened. Nothing tunes, keys or changes a setting. Only while the radio
     * is off and the stock app is not running. The stock app updates the bridge's firmware when the bridge does not
     * answer its version query at start-up, so the result says whether the bridge answered.
     */
    static String identityProbe(java.io.File dir, String stockApp) {
        if (!RootSetup.run("pidof " + stockApp + " || true").replaceAll("[^0-9]", "").isEmpty()) return "not run: " + stockApp + " is running\n";
        if (!Boolean.FALSE.equals(powered(RootSetup.run("cat " + MCU_POWER + " 2>/dev/null")))) return "not run: the radio is not off (" + MCU_POWER + ")\n";
        java.io.File q1 = new java.io.File(dir, "agold_q_bridge.bin"), q2 = new java.io.File(dir, "agold_q_module.bin");
        try {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(q1)) { o.write(AgoldFrames.request(0x00, 0x01, 0x01)); }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(q2)) { o.write(AgoldFrames.request(0x01, 0x34, 0x01)); }
        } catch (java.io.IOException e) { return "not run: " + e + "\n"; }
        String ask = "rm -f $R; ( timeout 3 cat " + BRIDGE_PORT + " > $R ) & sleep 0.3; cat $Q > " + BRIDGE_PORT + "; wait; echo \"$N: $(od -An -tx1 $R | tr -d '\\n')\"; ";
        String out = RootSetup.run(STTY + "R=/data/local/tmp/signaldeck_agold_reply; "
                + "stop " + HAL_SERVICE + "; sleep 1; " + POWER_UP
                + "if [ -e " + BRIDGE_PORT + " ]; then echo \"port: after $((i*5)) tenths of a second\"; sleep 2; $S -F " + BRIDGE_PORT + " 115200 raw -echo 2>&1; "
                + "Q=" + q1.getAbsolutePath() + "; N=bridge; " + ask + "Q=" + q2.getAbsolutePath() + "; N=module; " + ask
                // what the draft profile needs, while the bridge is up: its port and label, the radio's USB sound card
                + "echo \"" + PORT_LINE + BRIDGE_PORT + " $(ls -Z " + BRIDGE_PORT + " | cut -d' ' -f1) held by /vendor/bin/hw/vendor.mediatek.hardware." + HAL_SERVICE + "@1.0-service (stopped for the probe)\"; "
                + "for s in /proc/asound/card[1-9]/stream*; do [ -f $s ] && { echo \"-- $s\"; cat $s; }; done; "
                + "else echo 'port: none after 10 s'; fi; "
                + POWER_DOWN + "sleep 1; rm -f $R; echo \"after: $(cat " + MCU_POWER + "), HAL $(pidof vendor.mediatek.hardware." + HAL_SERVICE + "@1.0-service >/dev/null && echo running || echo NOT running)\"");
        q1.delete(); q2.delete();
        return describe(out);
    }

    static final String MODULE_ANSWERED = "the module answered through the bridge: ", BRIDGE_SILENT = "the bridge did not answer";

    /** The probe's raw lines in words: each reply decoded and its checksum checked. */
    static String describe(String out) {
        StringBuilder r = new StringBuilder();
        String port = null, bridge = null, module = null, after = null; StringBuilder extra = new StringBuilder();
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("port: ")) port = t.substring(6);
            else if (t.startsWith("bridge: ")) bridge = t.substring(8);
            else if (t.startsWith("module: ")) module = t.substring(8);
            else if (t.startsWith("after: ")) after = t.substring(7).replace("power on = 0", "off").replace("power on = 1", "STILL ON");
            else if (t.startsWith(PORT_LINE) || t.startsWith("-- /proc/asound/") || t.matches("(Playback|Capture):|(Format|Channels|Rates): .*")) extra.append(l).append('\n');
        }
        r.append(BRIDGE_PORT).append(": ").append(port == null ? "?" : port).append('\n');
        r.append(reply("bridge version (route 00, cmd 0x01)", bridge, 0x00, 0x01));
        r.append(reply("module version (route 01, cmd 0x34)", module, 0x01, 0x34));
        String b = text(bridge, 0x00, 0x01), m = text(module, 0x01, 0x34);
        if (m != null) r.append(MODULE_ANSWERED).append(m).append(b != null ? " (bridge " + b + ")" : "").append('\n');
        if (b == null) r.append(BRIDGE_SILENT).append(": the stock app updates the bridge's firmware when that happens at its start-up; restart the phone before opening it\n");
        r.append(extra);
        r.append("clean-up: radio ").append(after == null ? "?" : after).append('\n');
        return r.toString();
    }

    private static String reply(String what, String hex, int route, int cmd) {
        if (hex == null) return "  " + what + ": not asked\n";
        if (hex.trim().isEmpty()) return "  " + what + ": no reply in 3 s\n";
        for (AgoldFrames.Frame f : AgoldFrames.find(hex.trim().toUpperCase(java.util.Locale.US).replaceAll("\\s+", " ")))
            if (f.route() == route && f.cmd() == cmd && f.reply())
                return "  " + what + ": \"" + AgoldFrames.bodyText(f) + "\", checksum " + (AgoldFrames.checksumOk(f) ? "ok" : "WRONG") + " (" + hex.trim() + ")\n";
        return "  " + what + ": no reply frame in (" + hex.trim() + ")\n";
    }

    /** The decoded body of a checksum-valid reply, or null. */
    static String text(String hex, int route, int cmd) {
        if (hex == null) return null;
        for (AgoldFrames.Frame f : AgoldFrames.find(hex.trim().toUpperCase(java.util.Locale.US).replaceAll("\\s+", " ")))
            if (f.route() == route && f.cmd() == cmd && f.reply() && AgoldFrames.checksumOk(f)) return AgoldFrames.bodyText(f);
        return null;
    }

    /** USB devices and USB sound card streams as root, taken while the radio is on (the bridge exists only then). */
    static String whileOn() {
        return RootSetup.run("for d in /sys/bus/usb/devices/*; do n=${d##*/}; "
                + "if [ -f $d/idVendor ]; then case $n in usb*) ;; *) echo \"  $n $(cat $d/idVendor):$(cat $d/idProduct)  $(cat $d/manufacturer 2>/dev/null) $(cat $d/product 2>/dev/null)\";; esac; "
                + "elif [ -f $d/bInterfaceClass ]; then echo \"           $n: class $(cat $d/bInterfaceClass) $(basename $(readlink $d/driver) 2>/dev/null)\"; fi; done; "
                + "cat /proc/asound/cards; for s in /proc/asound/card[1-9]/stream*; do [ -f $s ] && { echo \"-- $s\"; cat $s; }; done; "
                + "for t in /dev/ttyACM*; do [ -e $t ] || continue; h=''; for p in /proc/[0-9]*; do ls -l $p/fd 2>/dev/null | grep -q \" $t\\$\" && h=\"$h $(tr '\\0' ' ' < $p/cmdline | cut -c1-80)\"; done; "
                + "echo \"" + PORT_LINE + "$t $(ls -Z $t | cut -d' ' -f1) held by${h:- nobody}\"; done") + "\n";
    }

    static final String PORT_LINE = "bridge port: ";

    /** {port, SELinux label, holder} from the {@link #PORT_LINE} of a watch, or null. */
    static String[] port(String watch) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(PORT_LINE) + "(/dev/\\S+) (u:object_r:[A-Za-z0-9_]+:s0)\\S* held by ([^\\n]+)").matcher(watch == null ? "" : watch);
        return m.find() ? new String[] { m.group(1), m.group(2), m.group(3).trim() } : null;
    }
}
