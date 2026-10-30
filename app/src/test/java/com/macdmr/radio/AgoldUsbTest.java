/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** The Atom XL's USB bridge: which phones have it, where the USB controller's cable mode lives, the summary line. */
public class AgoldUsbTest {
    @Test public void stockWatchSkippedWhenTheStockAppWouldLoadFirmware() {
        String answered = "bridge version (route 00, cmd 0x01): \"20201016161541\", checksum ok\n";
        assertNull(AgoldUsb.stockWatchBlocked("", answered));                                       // nothing pending, bridge answers
        assertNull(AgoldUsb.stockWatchBlocked("notes.txt\n", "not run: com.agold.intercom is running\n"));   // a probe that did not run blocks nothing
        String f = AgoldUsb.stockWatchBlocked("DMR006.U4A.V076.bin\n", answered);
        assertNotNull(f); assertTrue(f.contains("DMR006.U4A.V076.bin"));                             // a firmware file in /sdcard/intercom/dmr
        String b = AgoldUsb.stockWatchBlocked("", "bridge version (route 00, cmd 0x01): no reply; " + AgoldUsb.BRIDGE_SILENT + "\n");
        assertNotNull(b); assertTrue(b.contains("did not answer"));                                  // the stock app would update the bridge
    }

    @Test public void bridgedOnlyWithTheHalProperty() {
        assertTrue(AgoldUsb.bridged("yes\n"));                     // Atom XL: ro.agold.extmodule.hal=yes
        assertFalse(AgoldUsb.bridged(""));                         // Armor 26 Ultra, W6: no such property
        assertFalse(AgoldUsb.bridged("no"));
        assertFalse(AgoldUsb.bridged(null));
    }

    @Test public void powerStateFromTheBridgeDriver() {
        assertEquals(Boolean.TRUE, AgoldUsb.powered("power on = 1\n"));     // as the Atom XL's mcu driver prints it
        assertEquals(Boolean.FALSE, AgoldUsb.powered("power on = 0"));
        assertNull(AgoldUsb.powered(""));                                   // node missing: unknown, the port is left alone
        assertNull(AgoldUsb.powered("cat: No such file or directory"));
    }

    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x ", x & 0xFF)); return s.toString().trim(); }

    @Test public void requestsAreTheStockAppsBytes() {
        // IComUtils.makePackage({0,1},{1},{1}) and ({1,52},{1},{1}), as sent on the Atom XL 2026-10-28
        assertEquals("7e 96 69 00 01 01 00 01 01 fd fd 81", hex(AgoldFrames.request(0x00, 0x01, 0x01)));
        assertEquals("7e 96 69 01 34 01 00 01 01 fc ca 81", hex(AgoldFrames.request(0x01, 0x34, 0x01)));
    }

    // the replies the Atom XL's bridge and module gave the probe (and the stock app) on 2026-10-28
    private static final String BRIDGE = " 7e 96 69 00 01 00 00 0e 32 30 32 30 31 30 31 36 31 36 31 35 34 31 8e a1 81";
    private static final String MODULE = " 7e 96 69 01 34 00 00 10 44 4d 52 30 30 36 2e 55 34 41 2e 56 30 36 37 00 18 0d 81";

    @Test public void probeRepliesDecodedAndChecked() {
        String d = AgoldUsb.describe("port: after 10 tenths of a second\nbridge: " + BRIDGE + "\nmodule: " + MODULE + "\nafter: power on = 0, HAL running\n");
        assertTrue(d, d.contains("bridge version (route 00, cmd 0x01): \"20201016161541\", checksum ok"));
        assertTrue(d, d.contains("module version (route 01, cmd 0x34): \"DMR006.U4A.V067\", checksum ok"));
        assertTrue(d, d.contains(AgoldUsb.MODULE_ANSWERED + "DMR006.U4A.V067 (bridge 20201016161541)"));
        assertTrue(d, d.contains("clean-up: radio off, HAL running"));
        assertFalse(d.contains(AgoldUsb.BRIDGE_SILENT));
    }

    @Test public void aWrongChecksumIsNotAnAnswer() {
        String bad = MODULE.replace("18 0d", "18 0e");
        assertNull(AgoldUsb.text(bad, 0x01, 0x34));
        assertTrue(AgoldUsb.describe("module: " + bad + "\n").contains("checksum WRONG"));
    }

    @Test public void aSilentBridgeIsAWarning() {
        String d = AgoldUsb.describe("port: none after 10 s\nafter: power on = 0, HAL running\n");
        assertTrue(d.contains("not asked"));
        assertTrue(d.contains(AgoldUsb.BRIDGE_SILENT));
        assertFalse(d.contains(AgoldUsb.MODULE_ANSWERED));
    }

    @Test public void stockFirmwareFolderIsReported() {
        assertNull(AgoldUsb.pendingStockFirmware(""));
        assertNull(AgoldUsb.pendingStockFirmware("notes.txt\n"));
        assertEquals("DMR006.U4A.V082.bin", AgoldUsb.pendingStockFirmware("DMR006.U4A.V082.bin\nreadme.txt\n"));
    }

    @Test public void giveBackNeedsTheBridgesAnswer() {
        String ok = AgoldUsb.describe("port: after 10 tenths of a second\nbridge: " + BRIDGE + "\nmodule: " + MODULE + "\nafter: power on = 0, HAL running\n");
        assertTrue(AgoldUsb.bridgeAnswered(ok));
        assertFalse(AgoldUsb.bridgeAnswered(AgoldUsb.describe("port: none after 10 s\nafter: power on = 0, HAL running\n")));
        assertFalse(AgoldUsb.bridgeAnswered(AgoldUsb.describe("port: after 10 tenths of a second\nbridge: \nmodule: \nafter: power on = 0, HAL running\n")));
        assertFalse(AgoldUsb.bridgeAnswered("not run: com.agold.intercom is running\n"));
        assertFalse(AgoldUsb.bridgeAnswered(null));
    }

    @Test public void appBlockDefaultsStopUsUntilSwitchedOff() {
        String pkg = "com.macdmr.radio";
        assertEquals("background cleanup, boot blocker, start blocker", AgoldUsb.appBlockProblem("on", "null", "null", pkg));
        // what Settings -> App block wrote on the bench Atom XL after the three switches were turned off (2026-10-28)
        String off = "{\"com.macdmr.radio\":{\"blockactivity\":false,\"blockboot\":false,\"blockstart\":false,\"clean\":false,\"packageName\":\"com.macdmr.radio\"}}";
        assertNull(AgoldUsb.appBlockProblem("on", "null", off, pkg));
        assertEquals("background cleanup", AgoldUsb.appBlockProblem("on", "1", off.replace("\"clean\":false", "\"clean\":true"), pkg));
        assertNull(AgoldUsb.appBlockProblem("on", "0", "null", pkg));
        assertNull(AgoldUsb.appBlockProblem("", "null", "null", pkg));
    }

    @Test public void stmcuBytesAreReceptionEdges() {
        assertEquals(1, AgoldUsb.receiveStatus(0));
        assertEquals(2, AgoldUsb.receiveStatus(1));
        assertEquals(-1, AgoldUsb.receiveStatus(2));
    }

    @Test public void aStuckReceptionLineIsRestartedOnceThenNotTrusted() {
        assertEquals("one unreported reception: the line misses one now and then", 0, AgoldUsb.stuckLineAction(1, 0, false));
        assertEquals("two in a row: restart the bridge", 1, AgoldUsb.stuckLineAction(2, 0, false));
        assertEquals("one after the restart: wait", 0, AgoldUsb.stuckLineAction(1, 1, false));
        assertEquals("two more after the restart: give up on the line", 2, AgoldUsb.stuckLineAction(2, 1, false));
        assertEquals("already on the signal reading: nothing more to do", 0, AgoldUsb.stuckLineAction(5, 1, true));
    }

    @Test public void aBridgePowerUpWaitsForTheChargerToSettle() {
        assertEquals("no charger change yet", 0, AgoldUsb.plugSettleWait(100_000, 0));
        assertEquals("right after the change: the stock app's 4 s", 4000, AgoldUsb.plugSettleWait(100_000, 100_000));
        assertEquals(1500, AgoldUsb.plugSettleWait(102_500, 100_000));
        assertEquals("long after", 0, AgoldUsb.plugSettleWait(200_000, 100_000));
    }

    @Test public void theReceptionWatchdogFollowsTheIdleFloor() {
        assertEquals("floor unknown: the fixed thresholds", -95, AgoldUsb.rssiStartDbm(Double.NaN), 0);
        assertEquals(-100, AgoldUsb.rssiEndDbm(Double.NaN), 0);
        assertEquals("a quiet channel (-102) keeps them", -95, AgoldUsb.rssiStartDbm(-102), 0);
        assertEquals(-100, AgoldUsb.rssiEndDbm(-102), 0);
        double f = Double.NaN;                                               // the bench channel with the charger in idled at -98 (2026-10-29)
        for (int i = 0; i < 30; i++) f = AgoldUsb.nextFloor(f, -98);
        assertEquals(-98, f, 0.01);
        assertTrue("a reception can end again at -98: end above the floor", AgoldUsb.rssiEndDbm(f) > -98);
        assertTrue("and the floor alone does not start one", AgoldUsb.rssiStartDbm(f) > -98);
        assertEquals("a carrier is not idle: the floor stays", f, AgoldUsb.nextFloor(f, -74), 0);
    }

    @Test public void aPowerUpWindowIsSettledByTheDriversFlag() {
        assertEquals("open, the driver says idle: the end was hidden in the window", 2, AgoldUsb.windowAction(true, "1\n", -102, -95));
        assertEquals("idle, the driver says a reception holds the line and the signal agrees", 1, AgoldUsb.windowAction(false, "0", -74, -95));
        assertEquals("a stale flag on a stuck line without a signal opens nothing", 0, AgoldUsb.windowAction(false, "0", -102, -95));
        assertEquals("both agree", 0, AgoldUsb.windowAction(true, "0", -74, -95));
        assertEquals(0, AgoldUsb.windowAction(false, "1", -74, -95));
        assertEquals("an unreadable flag decides nothing", 0, AgoldUsb.windowAction(true, "cat: Permission denied", -102, -95));
        assertEquals(0, AgoldUsb.windowAction(true, null, -102, -95));
        assertTrue(AgoldUsb.callInKnown("1\n")); assertTrue(AgoldUsb.callInKnown("0"));
        assertFalse("an error is not the flag: the signal reading decides then", AgoldUsb.callInKnown("cat: Permission denied"));
        assertFalse(AgoldUsb.callInKnown(null));
    }

    @Test public void powerOffPulsesTheBridgeLikeTheStockHal() {
        String r = AgoldUsb.RESET;
        int off = r.indexOf("echo 0 > " + AgoldUsb.MCU_POWER), pulse = r.indexOf("echo 1 > " + AgoldUsb.MCU_POWER), offAgain = r.indexOf("echo 0 > " + AgoldUsb.MCU_POWER, pulse);
        assertTrue("off first", off >= 0 && off < pulse);
        assertTrue("then the 0.2 s pulse, out of reset", r.indexOf("echo 0 > " + AgoldUsb.RSTMCU, pulse) > pulse && offAgain > pulse);
        assertTrue("then host mode is left", r.indexOf("echo 0 > " + AgoldUsb.USB_PATH) > offAgain);
    }

    @Test public void coverageSkipsReadingsWhileTheBridgeRestarts() {
        assertFalse(AgoldUsb.coverageReadingUsable(true, 1000, 2500));    // inside the restart or its grace
        assertTrue(AgoldUsb.coverageReadingUsable(true, 2500, 2500));     // the grace is over
        assertTrue(AgoldUsb.coverageReadingUsable(true, 5000, 0));        // no restart
        assertTrue(AgoldUsb.coverageReadingUsable(false, 1000, 2500));    // other phones
    }

    @Test public void aSignalOnlyReceptionOpensItsCaptureOnlyOnceItHasLasted() {
        assertEquals(0, AgoldUsb.captureOpenDelay(false, false, 0));                         // the line reported it: open at once, as before
        assertEquals(AgoldUsb.LINE_MISS_MIN_MS, AgoldUsb.captureOpenDelay(true, false, 0));  // found from the signal: wait the full 3 s
        assertEquals(1000, AgoldUsb.captureOpenDelay(true, false, AgoldUsb.LINE_MISS_MIN_MS - 1000));
        assertEquals(0, AgoldUsb.captureOpenDelay(true, false, AgoldUsb.LINE_MISS_MIN_MS + 500));   // a stuck line's station: open after 3 s
        assertEquals(0, AgoldUsb.captureOpenDelay(true, true, 0));                           // MON opens at once
    }

    @Test public void aSilentSignalOnlyCaptureIsClosedBeforeTheBridgeTimesOut() {
        assertTrue(AgoldUsb.closeSilentSignalOnlyCapture(true, false, 0));     // found from the signal, no audio: close it cleanly
        assertFalse(AgoldUsb.closeSilentSignalOnlyCapture(true, false, 640));  // audio came: a real station on a stuck line
        assertFalse(AgoldUsb.closeSilentSignalOnlyCapture(false, false, 0));   // the line reported it: its own recovery decides
        assertFalse(AgoldUsb.closeSilentSignalOnlyCapture(true, true, 0));     // MON holds the reception open on purpose
        assertTrue(AgoldUsb.SIGNAL_ONLY_AUDIO_WAIT_MS < 2000);                  // before the kernel's 2 s I/O error
    }

    @Test public void theSMeterHoldsItsReadingOnlyDuringAReportedAtomXlReception() {
        assertTrue(AgoldUsb.holdsRxRead(true, true, false));      // the line reported it: no command while the audio streams
        assertFalse(AgoldUsb.holdsRxRead(true, true, true));      // found from the signal: the watchdog needs the readings to end it
        assertFalse(AgoldUsb.holdsRxRead(true, false, false));    // idle
        assertFalse(AgoldUsb.holdsRxRead(false, true, false));    // other phones
    }

    @Test public void onlyALongUnreportedReceptionCountsAgainstTheLine() {
        assertFalse("a 1 s blip from a transmitter on another band", AgoldUsb.countsAsLineMiss(220));
        assertFalse(AgoldUsb.countsAsLineMiss(2999));
        assertTrue("a carrier the line never reported (the stuck-line repro's 12 s carriers)", AgoldUsb.countsAsLineMiss(12000));
        assertTrue(AgoldUsb.countsAsLineMiss(3000));
        assertEquals("two counted misses still restart the bridge", 1, AgoldUsb.stuckLineAction(AgoldUsb.STUCK_LINE_MISSES, 0, false));
    }

    @Test public void powerScriptsFollowTheHalsOrder() {
        String up = AgoldUsb.POWER_UP;
        assertTrue("the stale port node is waited out first", up.indexOf("while [ -e /dev/ttyACM0 ]") < up.indexOf("usb_path"));
        assertTrue(up.indexOf("usb_path") < up.indexOf("mcu_power"));
        assertTrue(up.indexOf("mcu_power") < up.indexOf("rstmcu"));
        String down = AgoldUsb.POWER_DOWN;
        assertTrue(down.startsWith("echo 0 > " + AgoldUsb.MCU_POWER));
        assertTrue("the USB disconnect is waited for before host mode is left", down.indexOf("/sys/class/tty/ttyACM0") < down.indexOf("> " + "/sys/bus/platform/drivers/mcu/usb_path"));
        assertTrue(down.indexOf("cmode") > down.indexOf("usb_path"));
        assertTrue(down.trim().endsWith("start aguiextmodule;"));
    }

    @Test public void controllerDirFromTheUdcLink() {
        assertEquals("/sys/devices/platform/11201000.usb3", AgoldUsb.controllerDir("/sys/devices/platform/11201000.usb3/udc/musb-hdrc\n"));
        assertNull(AgoldUsb.controllerDir("/sys/class/udc/*"));     // the glob matched nothing
        assertNull(AgoldUsb.controllerDir(""));
        assertNull(AgoldUsb.controllerDir("readlink: not found"));
    }

    @Test public void releaseNoteForTheSummary() {
        String watch = "start com.agold.intercom/.IComActivity: Starting: Intent { … }\n"
                + FirstRunCheck.RELEASED + "power-off (agui.intercom.intent.action.STOP_DEVICE): Broadcast completed: result=0\n"
                + "  the radio is off after 0.5 s (/sys/bus/platform/drivers/mcu/mcu_power = 0)\n"
                + "  USB port given back to the socket (/sys/devices/platform/11201000.usb3/cmode 0, then 1)\n"
                + "closed: stopped\n";
        String n = FirstRunCheck.releaseNote(watch);
        assertTrue(n.startsWith("power-off (agui.intercom.intent.action.STOP_DEVICE)"));
        assertTrue(n.endsWith("cmode 0, then 1)"));
        assertFalse(n.contains("closed:"));
        assertNull(FirstRunCheck.releaseNote("start x: ok\nclosed: stopped\n"));   // any phone without the bridge
        assertNull(FirstRunCheck.releaseNote(null));
    }
}
