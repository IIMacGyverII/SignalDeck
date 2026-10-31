/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * Everything a first run on a new or untested phone should find out, in one go, so the owner presses one button and
 * sends one zip: the device report, the PTT key capture, the radio probe (repeated with the module's power switched on
 * when it got no answer and the profile knows the power line), optionally a short trial take-over (receive only:
 * module answer, RSSI, a few seconds of the raw audio stream, then the radio is handed back to the stock app) and
 * SignalDeck's own log with personal details removed.
 */
public final class FirstRunCheck {
    public interface Progress { void step(String s); }
    private FirstRunCheck() { }

    /** Runs on a worker thread; returns the zip to share. */
    /** @param watchPkg the walkie-talkie app the owner confirmed for the 15 s watch, or null to skip that step */
    public static File run(Context c, RadioService svc, boolean trial, String watchPkg, Progress p) throws Exception {
        DeviceProfile dp = svc.profile();
        StringBuilder sum = new StringBuilder();
        if (dp.supported && !dp.stockApp.isEmpty() && !svc.isReady() && RootSetup.haveRoot()
                && !RootSetup.run("pidof " + dp.stockApp + " || true").replaceAll("[^0-9]", "").isEmpty()) {
            p.step("Closing " + dp.stockApp + " so the ports are free");
            RootSetup.run("am force-stop " + dp.stockApp);
            Thread.sleep(1500);
            sum.append("closed the stock app (").append(dp.stockApp).append(") for the checks\n");
        }

        DeviceReport.generate(c, s -> p.step("Report: " + s));
        if (watchPkg != null && !DeviceReport.lastCandidates.contains(watchPkg)) {      // the owner picked an app the ranking did not examine
            try {
                File src = new File(c.getPackageManager().getApplicationInfo(watchPkg, 0).sourceDir), dir = DeviceReport.newestDir();
                p.step("Reading " + watchPkg + "'s code");
                String note = "chosen by the owner as the walkie-talkie app\n" + DeviceReport.apkClues(src);
                if (dir != null && DeviceReport.copyFile(src, new File(dir, watchPkg + "_" + src.getName()))) note += "  copied " + watchPkg + "_" + src.getName() + "\n";
                DeviceReport.append("Clues in " + watchPkg + "'s code", note);
            } catch (Throwable t) { DeviceReport.append("Clues in " + watchPkg + "'s code", String.valueOf(t)); }
        }
        { File d = DeviceReport.newestDir(); String hint = codeHint(d == null ? "" : DeviceReport.readReport(d));
          if (hint != null) sum.append("the stock app's code names ").append(hint).append('\n'); }   // works without root

        p.step("Press and release the side PTT key a few times now (10 s)");
        DeviceReport.append("PTT key capture", DeviceReport.captureKeys(c, 10));

        String stockAnswers = null;                                              // module replies found in the stock app's own log
        String watchText = "";
        if (watchPkg != null && !svc.isReady() && RootSetup.haveRoot()) {
            lastTrace = null;
            String w = watchStockApp(c, watchPkg, p); watchText = w;
            DeviceReport.append("The stock app at work (" + watchPkg + ", 15 s, receive only)", w);
            sum.append("watched ").append(watchPkg).append(" start up for 15 s\n");
            // a stock app that had not run since its data was cleared has no databases before the watch (Armor 20WT beta.18
            // report): it creates them as it starts, so the structure is read again here
            if (watchPkg.equals(dp.stockApp)) DeviceReport.append("Databases of " + watchPkg + " after the watch (structure only)", DeviceReport.dbStructure(c, watchPkg));
            File dir = DeviceReport.newestDir();
            if (lastTrace != null && dir != null) {                              // the whole sniff beside report.txt (radio IDs blanked)
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "stock_serial.txt"))) { o.write(SerialTrace.redact(lastTrace).getBytes("UTF-8")); } catch (Throwable ignored) { }
                String proto = SerialTrace.family(SerialTrace.data(lastTrace));
                sum.append("its serial traffic (stock_serial.txt): ").append(proto).append('\n');
            }
            stockAnswers = stockAnswers(w);
            String rel = releaseNote(w);
            if (rel != null) sum.append("USB bridge: ").append(AgoldUsb.WARNING).append("\n  ").append(rel).append('\n');
        } else sum.append("stock app not watched: ").append(watchPkg == null ? "skipped (no app chosen)" : svc.isReady() ? "SignalDeck owns the radio" : "no root").append('\n');

        if (!svc.isReady() && !dp.stockApp.isEmpty() && RootSetup.haveRoot() && !RootSetup.run("pidof " + dp.stockApp + " || true").replaceAll("[^0-9]", "").isEmpty())
            sum.append("stock app was running again before the probe: ").append(stopFully(dp.stockApp)).append('\n');
        if (svc.isReady()) {
            sum.append("radio probe skipped: SignalDeck owns the radio (Hand back first to probe the ports)\n");
        } else {
            File dir = DeviceReport.newestDir();
            java.util.Set<String> named = RadioProbe.namedPorts(dir == null ? "" : DeviceReport.readReport(dir), watchText, dp.supported ? dp.controlPort : null);
            String r = "";
            boolean answered = false;
            if (AgoldUsb.bridged()) {                                          // Atom XL: the module is behind a USB bridge, not on a UART: ask there first, the HAL stopped meanwhile
                p.step("Probe: asking the radio through its USB bridge (USB to a computer is off for about 10 s)");
                String b = AgoldUsb.identityProbe(c.getCacheDir(), watchPkg != null ? watchPkg : dp.stockApp.isEmpty() ? "com.agold.intercom" : dp.stockApp);
                DeviceReport.append("Radio probe through the USB bridge", b);
                int k = b.indexOf(AgoldUsb.MODULE_ANSWERED);
                if (k >= 0) { answered = true; sum.append("radio module answered through the USB bridge: ").append(b.substring(k + AgoldUsb.MODULE_ANSWERED.length(), b.indexOf('\n', k)).trim()).append('\n'); }
                if (b.contains(AgoldUsb.BRIDGE_SILENT)) sum.append("WARNING: ").append(b.substring(b.indexOf(AgoldUsb.BRIDGE_SILENT), b.indexOf('\n', b.indexOf(AgoldUsb.BRIDGE_SILENT)))).append('\n');
            }
            if (answered) DeviceReport.append("Radio probe", SWEEP_SKIPPED);   // no reason to send version queries to the phone's other UARTs
            else {
                r = RadioProbe.run(c, s -> p.step("Probe: " + s), false, named);
                DeviceReport.append("Radio probe", r);
                answered = r.contains("answered");
                if (!answered && dp.supported && dp.powerNode != null) {         // the stock app switches the module off when it closes
                    String r2 = RadioProbe.run(c, s -> p.step("Probe with power on: " + s), true, named);
                    DeviceReport.append("Radio probe (module power switched on first)", r2);
                    answered = r2.contains("answered"); r = r2;
                }
            }
            if (r.contains(RadioProbe.AT_MARK))
                sum.append("radio module answered: yes, with AT commands (SA818 class)").append(atWhere(r)).append(": SignalDeck has no engine for this module family yet\n");
            else sum.append("radio module answered the version query: ").append(answered ? "yes" : "no").append('\n');
            if (!named.isEmpty()) sum.append("ports probed: only those the stock app or the profile name (").append(String.join(" ", named)).append(")\n");
            String[] bridge = AgoldUsb.port(watchText);
            if (!answered && stockAnswers != null && bridge != null)
                sum.append("  the module sits behind a USB bridge on ").append(bridge[0]).append(", held by ").append(bridge[2]).append(" while the radio is on; ")
                   .append(stockAnswers).append(": the module works; SignalDeck's probe does not ask through the bridge yet\n");
            else if (!answered && stockAnswers != null)
                sum.append("  but ").append(stockAnswers).append(": the module works; SignalDeck's probe did not reach the port it is on\n");
            if (!answered && dp.supported && dp.serialLib != null && speedNotSet(r, dp.controlPort, dp.baud))
                sum.append("  the probe could not set ").append(dp.controlPort).append(" to ").append(dp.baud)
                   .append(" baud with stty, so its silence there proves nothing; the trial opens the port through the stock serial library\n");
        }

        if (trial && dp.supported && !svc.isReady()) {
            String t = trialTakeOver(c, svc, p);
            DeviceReport.append("Trial take-over", t);
            sum.append("trial: ").append(t.replace("\n", "\n  ").trim()).append('\n');
        } else if (trial) sum.append("trial take-over skipped: ").append(!dp.supported ? "no profile for this phone yet" : "SignalDeck already owns the radio").append('\n');

        DeviceReport.append("SignalDeck log (personal details removed)", DeviceReport.redactAppLog(svc.recentLog()));
        { File d = DeviceReport.newestDir();                                  // the draft reads the finished report and this summary
          String draft = DraftProfile.text((d == null ? "" : DeviceReport.readReport(d)) + "\n==== Summary ====\n" + sum);
          DeviceReport.append("Draft profile", draft); sum.append(draft); }
        DeviceReport.append("Summary", sum.toString());
        DeviceReport.writeJson(DeviceReport.newestDir(), BuildIdentity.gather(c));   // report.json: the same facts as data, for scripts/report_diff.py
        p.step("Zipping");
        return DeviceReport.zipNewest(c, true);
    }

    /**
     * Listen check (support stage 2, receive only): with SignalDeck owning the radio on a channel the owner chose because
     * stations are heard there, record for {@code seconds} the signal strength, how much of the time something was heard
     * and how much audio arrived. The owner's answer ("did you hear it?") is added by the caller.
     */
    public static String listenCheck(RadioService svc, int seconds, Progress p) throws InterruptedException { return listenCheck(svc, seconds, p, null, 0, false); }

    /** As above, recording up to {@code clipMs} of the received audio into {@code clipFile} (only while heard, if asked). */
    public static String listenCheck(RadioService svc, int seconds, Progress p, File clipFile, int clipMs, boolean onlyHeard) throws InterruptedException {
        StringBuilder r = new StringBuilder();
        ChannelStore.Channel ch = svc.current();
        if (!svc.isReady() || ch == null) return "not run: SignalDeck does not own the radio or no channel is selected\n";
        r.append(String.format(Locale.US, "channel: %s, %s MHz, %s\n", ch.name, ch.freqText(ch.rxFreq), ch.isDigital() ? "DMR" : "analog FM"));
        ClipRecorder clip = null;
        if (clipFile != null) try { clipFile.getParentFile().mkdirs(); clip = new ClipRecorder(clipFile, clipMs); svc.recordClip(clip, onlyHeard); } catch (Throwable t) { r.append("recording failed: ").append(t).append('\n'); }
        long bytes0 = svc.rxAudioBytes(), t0 = System.currentTimeMillis();
        int samples = 0, heard = 0, rmin = 0, rmax = -999; StringBuilder rs = new StringBuilder();
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            svc.pollRssiMeasure(); Thread.sleep(1000);
            int v = svc.lastRssi(); samples++; if (svc.rxActive()) heard++;
            if (v > -999) { rmax = Math.max(rmax, v); rmin = rmin == 0 ? v : Math.min(rmin, v); }
            if (samples <= 60) rs.append(v).append(svc.rxActive() ? "*" : "").append(' ');
            p.step(String.format(Locale.US, "Listening: %d of %d s, something heard %d s", samples, seconds, heard));
        }
        long bytes = svc.rxAudioBytes() - bytes0;
        if (clip != null) { svc.recordClip(null, false); clip.finish(); r.append("recording ").append(clip.stats()).append(onlyHeard ? " (only while something was heard)" : "").append('\n'); }
        r.append(String.format(Locale.US, "something heard in %d of %d seconds; signal %d … %d dBm\n", heard, samples, rmin, rmax));
        r.append("signal each second (* = heard): ").append(rs.toString().trim()).append('\n');
        r.append(String.format(Locale.US, "receive audio: %d bytes in %d s%s\n", bytes, seconds, svc.audioStats().isEmpty() ? "" : " (" + svc.audioStats() + ")"));
        if (heard * 10 < samples && rmax > -999 && rmax <= -88)                // the radio's squelch sits near -90 dBm (measured on the Ulefone)
            r.append(String.format(Locale.US, "note: the strongest signal was %d dBm, at or below the radio's squelch level (about -90 dBm), so the squelch stayed closed most of the time — that is normal for a signal this weak. Pick a stronger channel, or turn MON on (Intercom) to hear it anyway, and run the check again.\n", rmax));
        return r.toString();
    }

    /**
     * Bench (adb {@code checks --es watch …}): which app the 15 s watch opens, as the Run all checks dialog would offer it.
     * "none" skips the watch, a package name is taken as given, "auto" (or nothing) takes the profile's stock app, else
     * the best-ranked candidate with a launcher icon, else none.
     */
    static String benchWatch(String arg, String profileApp, java.util.List<RadioAppFinder.App> ranked) {
        if ("none".equals(arg)) return null;
        if (arg != null && !arg.isEmpty() && !"auto".equals(arg)) return arg;
        if (profileApp != null && !profileApp.isEmpty()) return profileApp;
        for (RadioAppFinder.App a : ranked) if (a.launcher) return a.pkg;
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean benchBusy = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Bench (adb): Run all checks with no screen, as {@code DiagReceiver checks}. Progress, the folder and the zip go to the
     * log under the tag "BenchChecks" ("done <folder> <zip>" or "failed …" ends a run), which scripts/w6/run_checks.sh waits for.
     */
    static void benchRun(Context c, RadioService svc, String watchArg, boolean trial, long hostTime) {
        if (!benchBusy.compareAndSet(false, true)) { android.util.Log.w("BenchChecks", "busy: a run is in progress"); return; }
        if (hostTime > 0) DeviceReport.clockOffsetMs = hostTime - System.currentTimeMillis();
        new Thread(() -> {
            try {
                String watch = benchWatch(watchArg, svc.profile().supported ? svc.profile().stockApp : "",
                        "none".equals(watchArg) || svc.isReady() || !RootSetup.haveRoot() ? new java.util.ArrayList<>() : RadioAppFinder.rank(c));
                android.util.Log.i("BenchChecks", "start watch=" + watch + " trial=" + trial + " clockOffsetMs=" + DeviceReport.clockOffsetMs);
                File zip = run(c, svc, trial, watch, t -> android.util.Log.i("BenchChecks", "step " + t));
                c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putLong("supportChecks", System.currentTimeMillis()).apply();
                File dir = DeviceReport.newestDir();
                android.util.Log.i("BenchChecks", "done " + (dir == null ? "-" : dir.getAbsolutePath()) + " " + zip.getAbsolutePath());
            } catch (Throwable t) { android.util.Log.w("BenchChecks", "failed " + t); }
            finally { benchBusy.set(false); }
        }, "bench-checks").start();
    }

    /** The first "protocol hint from its code:" in a report, or null. */
    static String codeHint(String report) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^  protocol hint from its code: (.+)$").matcher(report == null ? "" : report);
        return m.find() ? m.group(1).trim() : null;
    }

    /** " on /dev/ttyS2 at 9600 baud" from the probe's AT line, or "". */
    static String atWhere(String probe) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(RadioProbe.AT_MARK) + "( on \\S+ at \\d+ baud)").matcher(probe);
        return m.find() ? m.group(1) : "";
    }

    static final String STOCK_ANSWERS = "module replies in its log: ", NONE_RECOGNISED = "none recognised";

    /** The module-replies line of a stock-app watch, or null when it found none. */
    static String stockAnswers(String watch) {
        int a = watch.indexOf(STOCK_ANSWERS); if (a < 0) return null;
        int from = a + STOCK_ANSWERS.length(), end = watch.indexOf('\n', from);
        String t = watch.substring(from, end < 0 ? watch.length() : end).trim();
        return t.isEmpty() || t.equals(NONE_RECOGNISED) ? null : t;
    }

    private static final String KMSG = "dmesg 2>/dev/null | grep -i -E 'intercom|dmr|interphone|walkie|ptt|pwd|pdt|ttyS|ttyMT' | tail -40";

    /**
     * Start the stock walkie-talkie app the way its icon does, let it power up and listen for 15 s, and record what it does:
     * its own log lines (QuickChat logs every step: "setPttReceive", "initPcmSerialPort", its PCM packets), which serial
     * ports it opens and the line settings it gives them, and the radio driver's kernel messages. Then close it. Nobody
     * presses PTT meanwhile (the progress text says so): this only listens.
     * <p>With the sdtrace helper, the app is stopped first and started under it, so the report sees its start-up from the
     * first system call: the serial port it opens, the line settings, and every byte both ways (the W6's app sent
     * AT+DMOCONNECT, AT+DMOVERQ, AT+DMOSETGROUP, AT+DMOSETVOLUME, each ending in a bare \r). The whole trace is kept in
     * {@link #lastTrace}; the caller writes it beside the report.
     */
    static String watchStockApp(Context c, String pkg, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder();
        String comp = RootSetup.run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + pkg + " | tail -1").trim();
        if (!comp.contains("/")) return "no launcher activity found for " + pkg + " (" + comp + ")\n";
        String tracer = null;
        try { tracer = AlsaDirectAudio.install(c, "sdtrace"); } catch (Throwable t) { r.append("serial sniff not available: ").append(t.getMessage()).append('\n'); }
        if (tracer != null) r.append("stopped first, so its start-up is seen: ").append(stopFully(pkg)).append('\n');   // a running app is only brought to the front
        String kBefore = RootSetup.run(KMSG);
        RootSetup.run("logcat -c");
        boolean bridged = AgoldUsb.bridged();                                  // Atom XL: the radio takes the USB port while it is on
        if (bridged) {
            r.append("note: ").append(AgoldUsb.WARNING).append('\n');
            String why = AgoldUsb.stockWatchBlocked(c.getCacheDir(), pkg);
            if (why != null) return r.append("watch skipped: ").append(why).append('\n').toString();
        }
        p.step("The stock walkie-talkie app is open for 15 s — please do not press PTT" + (bridged ? " (USB to a computer is off meanwhile)" : ""));
        final String[] started = { "" };
        Thread sniff = null;
        if (tracer != null) {                                                   // the sniffer waits for the process, then the app is started
            final String cmd = AlsaDirectAudio.quote(tracer) + " -n " + pkg + " -t 15000 -w 10000 > " + TRACE_FILE + " 2>&1 & sleep 0.3; am start -n " + comp + " 2>&1 | tail -1; wait";
            sniff = new Thread(() -> started[0] = RootSetup.run(cmd), "sdtrace");
            sniff.start();
        } else started[0] = RootSetup.run("am start -n " + comp + " 2>&1 | tail -1");
        Thread.sleep(8000);
        String pid = RootSetup.run("pidof " + pkg + " || true").trim().split("\\s+")[0];
        if (pid.matches("\\d+")) {
            String fds = RootSetup.run("ls -l /proc/" + pid + "/fd 2>/dev/null | grep -o '/dev/[A-Za-z0-9_]*' | sort -u");
            r.append("devices it holds open: ").append(fds.replace('\n', ' ')).append('\n');
            for (String dev : fds.split("\\s+")) if (dev.matches("/dev/tty\\w+"))
                r.append("  ").append(dev).append(": ").append(RootSetup.run("stty -F " + dev + " -a 2>&1 | head -2").replace('\n', ' ')).append('\n');
        } else r.append("not running after 8 s (pid: ").append(pid).append(")\n");
        if (bridged) r.append("USB devices and USB sound cards while the radio is on (root):\n").append(AgoldUsb.whileOn());
        Thread.sleep(7000);
        if (pid.matches("\\d+")) {                                            // again at the end: a daemon-backed app (Armor 3WT) connects only after start-up
            r.append("at the end of the watch it holds: ").append(RootSetup.run("ls -l /proc/" + pid + "/fd 2>/dev/null | grep -o '/dev/[A-Za-z0-9_/]*' | sort -u").replace('\n', ' ')).append('\n');
            r.append("its TCP connections: ").append(DeviceReport.tcpOf(RootSetup.run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"), RootSetup.run("ls -l /proc/" + pid + "/fd 2>/dev/null"))).append('\n');
        }
        String log = pid.matches("\\d+") ? RootSetup.run("logcat -d --pid=" + pid + " 2>/dev/null | tail -600") : RootSetup.run("logcat -d 2>/dev/null | tail -400");
        int hexLines = 0; StringBuilder kept = new StringBuilder();
        for (String l : log.split("\n")) {
            if (l.contains("ClassLoader referenced unknown path")) continue;   // framework noise
            if (l.matches(".*[0-9A-Fa-f ,]{120,}.*")) { hexLines++; if (hexLines <= 3) kept.append(l, 0, Math.min(l.length(), 260)).append(" …\n"); continue; }   // data dumps: a sample is enough
            kept.append(l.length() > 260 ? l.substring(0, 260) + " …" : l).append('\n');
        }
        String ans = AgoldFrames.answers(log);
        r.append(STOCK_ANSWERS).append(ans == null ? NONE_RECOGNISED : ans).append('\n');
        r.append("\nits log (").append(hexLines).append(" data-dump lines shortened to 3 samples; personal details removed):\n").append(DeviceReport.redact(kept.toString()));
        String kAfter = RootSetup.run(KMSG);
        r.append("\nradio driver kernel messages that appeared meanwhile:\n").append(newLines(kBefore, kAfter));
        if (sniff != null) {
            sniff.join(15000);
            String trace = RootSetup.run("cat " + TRACE_FILE + " 2>&1; rm -f " + TRACE_FILE);
            lastTrace = trace;
            r.append("\nits serial traffic from its first call (sdtrace; every byte in stock_serial.txt, radio IDs blanked):\n").append(SerialTrace.summary(trace));
            for (String l : trace.split("\n")) if (l.contains("sdtrace:") && (l.contains("attach") || l.contains("not found") || l.contains("not listed"))) r.append("  ").append(l.trim()).append('\n');
        }
        r.append("\nstart ").append(comp).append(": ").append(started[0].trim()).append('\n');
        if (bridged) r.append(RELEASED).append(AgoldUsb.release(pkg).replace("\n", "\n  ").trim()).append('\n');   // a force-stop alone leaves the radio on and USB off
        r.append("closed: ").append(stopFully(pkg)).append('\n');
        return r.toString();
    }

    private static final String TRACE_FILE = "/data/local/tmp/signaldeck_sdtrace.txt";
    static final String SWEEP_SKIPPED = "serial ports not swept: the module answered through its USB bridge (see the section before)\n";
    static final String RELEASED = "radio released before closing: ";

    /** The release lines of a watch, for the summary, or null when the watch did not release a USB-bridged radio. */
    static String releaseNote(String watch) {
        int a = watch == null ? -1 : watch.indexOf(RELEASED); if (a < 0) return null;
        int end = watch.indexOf("\nclosed: ", a);
        return watch.substring(a + RELEASED.length(), end < 0 ? watch.length() : end).trim();
    }
    /** The last watch's raw serial trace (null without the sniffer); written redacted beside the report. */
    static volatile String lastTrace;

    /**
     * Force-stop an app and make sure it stays stopped: QuickChat restarts itself (its service), and the probe found it
     * holding both ports again right after a single force-stop (2026-10-21). Up to 4 tries over ~6 s.
     */
    static String stopFully(String pkg) throws InterruptedException {
        for (int i = 1; i <= 4; i++) {
            RootSetup.run("am force-stop " + pkg);
            Thread.sleep(1500);
            if (RootSetup.run("pidof " + pkg + " || true").replaceAll("[^0-9]", "").isEmpty()) return "stopped" + (i > 1 ? " (after " + i + " tries: it restarted itself)" : "");
        }
        return "still running after 4 force-stops: " + RootSetup.run("pidof " + pkg + " || true").trim();
    }

    /** Lines of {@code after} that were not in {@code before}. */
    private static String newLines(String before, String after) {
        java.util.HashSet<String> seen = new java.util.HashSet<>(java.util.Arrays.asList(before.split("\n")));
        StringBuilder out = new StringBuilder();
        for (String l : after.split("\n")) if (!l.trim().isEmpty() && !seen.contains(l)) out.append("  ").append(l).append('\n');
        return out.length() == 0 ? "  (none)\n" : out.toString();
    }

    /** Take the radio for a few seconds (receive only), record what happened, give it back. */
    /**
     * The commands SignalDeck sent that the stock app is never seen to send ({@link DeviceProfile#stockCommands}), one line
     * each with the module's answers: whether this module implements them is known only from these. "" when the profile
     * lists no stock commands or SignalDeck sent nothing else.
     */
    static String unusedCommands(int[] stockCommands, java.util.Map<Integer, RadioEngine.CommandStats> stats) {
        if (stockCommands == null || stats == null) return "";
        java.util.Set<Integer> stock = new java.util.HashSet<>(); for (int x : stockCommands) stock.add(x);
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<Integer, RadioEngine.CommandStats> e : new java.util.TreeMap<>(stats).entrySet()) {
            if (stock.contains(e.getKey())) continue;
            RadioEngine.CommandStats s = e.getValue();
            b.append(String.format(Locale.US, "  0x%02X  %d / %d / %d%s\n", e.getKey(), s.sent, s.answered, s.refused,
                    s.answered == 0 ? "   <- no answer" : s.refused == s.answered ? "   <- always refused" : ""));
        }
        return b.toString();
    }

    /**
     * Whether the radio probe tried {@code port} at {@code baud} and read another speed back ("  57600 baud (readback
     * 9600), …"): toybox stty cannot change some UARTs (the Armor 20WT's ttyS1 read 9600 in its beta.18 report), so a
     * silent module there was never asked at its speed.
     */
    static boolean speedNotSet(String probe, String port, int baud) {
        if (probe == null || port == null) return false;
        int k = probe.indexOf("--- " + port + " ---"); if (k < 0) return false;
        int end = probe.indexOf("\n--- ", k + 1); String sec = probe.substring(k, end < 0 ? probe.length() : end);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + baud + " baud \\(readback (\\d+)\\)").matcher(sec);
        return m.find() && !m.group(1).equals(String.valueOf(baud));
    }

    // ---- trial checks for an untested phone (receive only) --------------------------------------------------------------

    /**
     * Whether the module opens its squelch on the listen channel with squelch 0 alone, and with the monitor command too.
     * A stock app that never sends monitor (the Armor 20WT's) leaves it open whether this module honours it; with neither
     * opening the squelch, an empty audio capture says nothing about the audio path.
     */
    static String squelchCheck(RadioService svc, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder("squelch check (446.00625 MHz, nobody needed on the channel):\n");
        boolean[][] seen = new boolean[2][]; long[] bytes = new long[2];
        for (int mon = 0; mon < 2; mon++) {
            p.step("Trial: squelch check, " + (mon == 0 ? "squelch 0 alone" : "squelch 0 and monitor"));
            svc.setMonitor(mon == 1);
            Thread.sleep(2000);
            long b0 = svc.rxAudioBytes(); boolean rx = false;
            for (int i = 0; i < 15; i++) { Thread.sleep(200); rx |= svc.moduleReceiving(); }
            seen[mon] = new boolean[] { rx }; bytes[mon] = svc.rxAudioBytes() - b0;
            r.append(String.format(Locale.US, "  %s: the module %s a reception, %d audio bytes in 3 s\n",
                    mon == 0 ? "squelch 0 alone" : "squelch 0 and monitor (0x2F)", rx ? "reported" : "did not report", bytes[mon]));
        }
        svc.setMonitor(false);
        r.append("  ").append(squelchVerdict(seen[0][0], bytes[0], seen[1][0], bytes[1])).append('\n');
        return r.toString();
    }

    /** One line reading the squelch check: which setting opens the module, and whether the audio path carried anything. */
    static String squelchVerdict(boolean rx0, long bytes0, boolean rxMon, long bytesMon) {
        if (bytes0 > 0 && bytesMon > 0) return "verdict: audio flows with squelch 0 alone; the audio path works";
        if (bytesMon > 0) return "verdict: audio flows only with monitor on; the module honours 0x2F";
        if (bytes0 > 0) return "verdict: audio flows with squelch 0 but not with monitor on; monitor does something else on this module";
        if (rx0 || rxMon) return "verdict: the module reported a reception but no audio arrived; the audio path is the problem";
        return "verdict: the module never opened its squelch, so the audio path is still untested (try the Listen check on a busy channel)";
    }

    /**
     * The received noise level at the module volume byte this profile uses and at the Armor 26 Ultra's 8, monitor on, two
     * seconds each, saved as rx_level_v&lt;n&gt;.wav beside the report: the decoders were tuned on the Armor's level.
     */
    static String levelCheck(RadioService svc, File dir, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder("receive level by module volume byte (monitor on, noise):\n");
        int own = svc.profile().analogVolume;
        svc.setMonitor(true); Thread.sleep(1500);
        for (int vol : own == 8 ? new int[] { 8 } : new int[] { own, 8 }) {
            p.step("Trial: receive level at volume " + vol);
            svc.diagAnalogVolume(vol); Thread.sleep(1500);
            ClipRecorder clip = null;
            try { if (dir != null) { clip = new ClipRecorder(new File(dir, "rx_level_v" + vol + ".wav"), 2000); svc.recordClip(clip, false); } } catch (java.io.IOException e) { r.append("  recording failed: ").append(e).append('\n'); }
            Thread.sleep(2300);
            if (clip != null) { svc.recordClip(null, false); clip.finish(); }
            double rms = clip != null ? clip.rms() : -1;
            r.append(String.format(Locale.US, "  volume %d%s: %s\n", vol, vol == own ? " (this profile)" : "", rms > 0 ? String.format(Locale.US, "%.1f dBFS RMS", 20 * Math.log10(rms / 32768.0)) : "no audio"));
        }
        svc.diagAnalogVolume(own); svc.setMonitor(false); Thread.sleep(500);
        return r.toString();
    }

    /**
     * How soon after a retune the module's signal reading is steady (the Armor 26 Ultra: 0.7 s; the Atom XL: 50 ms): five
     * retunes to the listen channel, the readings every 150 ms for 2 s after each. Needs no signal: the noise floor
     * settles too.
     */
    static String settleCheck(RadioService svc, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder("signal reading after a retune (ms after the channel write: dBm):\n");
        java.util.List<Long> settled = new java.util.ArrayList<>(), first = new java.util.ArrayList<>();
        for (int run = 0; run < 5; run++) {
            p.step("Trial: signal reading after a retune, " + (run + 1) + " of 5");
            svc.probeTune(run % 2 == 0 ? 446_100_000 : 446_006_250);       // a real retune each time
            Thread.sleep(100);
            long t0 = svc.lastTuneAt(), seenAt = svc.lastRssiAt();
            java.util.List<long[]> pts = new java.util.ArrayList<>();
            while (System.currentTimeMillis() - t0 < 2000) {
                svc.pollRssiMeasure(); Thread.sleep(150);
                long at = svc.lastRssiAt(); if (at != seenAt && at > t0) { seenAt = at; pts.add(new long[] { at - t0, svc.lastRssi() }); }
            }
            StringBuilder line = new StringBuilder("  run ").append(run + 1).append(':');
            for (long[] q : pts) line.append(' ').append(q[0]).append(':').append(q[1]);
            r.append(line).append(pts.isEmpty() ? " no reading" : "").append('\n');
            if (!pts.isEmpty()) { first.add(pts.get(0)[0]); long s = settledAtMs(pts, 2); if (s >= 0) settled.add(s); }
        }
        r.append(String.format(Locale.US, "  first reading after %s ms; steady (within 2 dB of the end) from %s ms\n", median(first), median(settled)));
        return r.toString();
    }

    /** The time of the first reading from which every later one stays within {@code db} of the last; -1 without readings. */
    static long settledAtMs(java.util.List<long[]> pts, int db) {
        if (pts.isEmpty()) return -1;
        long end = pts.get(pts.size() - 1)[1]; long at = pts.get(pts.size() - 1)[0];
        for (int i = pts.size() - 1; i >= 0 && Math.abs(pts.get(i)[1] - end) <= db; i--) at = pts.get(i)[0];
        return at;
    }
    static String median(java.util.List<Long> l) {
        if (l.isEmpty()) return "?";
        java.util.List<Long> s = new java.util.ArrayList<>(l); java.util.Collections.sort(s);
        return String.valueOf(s.get(s.size() / 2));
    }

    /** Band edges the Armor family's module enforces (measured on the Armor 26 Ultra): each tuned once, receive only. */
    static final int[] EDGES_HZ = { 136_000_000, 135_995_000, 174_000_000, 174_005_000, 400_000_000, 399_995_000, 480_000_000, 480_005_000 };

    /** Which frequencies around the band edges the module takes (its 0x23 answer); the profile's bands come from this. */
    static String bandEdgeCheck(RadioService svc, Progress p) throws InterruptedException { return bandEdgeCheck(svc, p, EDGES_HZ); }
    static String bandEdgeCheck(RadioService svc, Progress p, int[] edges) throws InterruptedException {
        StringBuilder r = new StringBuilder("band edges (the module's answer to a receive channel write; nothing keyed):\n");
        for (int hz : edges) {
            p.step(String.format(Locale.US, "Trial: band edges, %.3f MHz", hz / 1e6));
            RadioEngine.CommandStats b = svc.engine().stats().get(RadioEngine.CMD_SET_ANALOG);
            int a0 = b == null ? 0 : b.answered, f0 = b == null ? 0 : b.refused;
            svc.probeTune(hz);
            String res = "no answer";
            for (int i = 0; i < 20; i++) {
                Thread.sleep(100);
                RadioEngine.CommandStats s = svc.engine().stats().get(RadioEngine.CMD_SET_ANALOG);
                if (s != null && s.answered > a0) { res = s.refused > f0 ? "refused" : "accepted"; break; }
            }
            r.append(String.format(Locale.US, "  %.3f MHz: %s\n", hz / 1e6, res));
            Thread.sleep(300);
        }
        return r.toString();
    }

    /** The Armor 3WT's edges: the intercom app's 100 MHz floor, generic points below 136 MHz, the usual VHF/UHF edges, the module's "470M". */
    static final int[] EDGES_3WT_HZ = { 100_000_000, 120_000_000, 130_000_000, 135_995_000, 136_000_000, 174_000_000, 174_005_000, 399_995_000, 400_000_000, 470_000_000, 470_005_000, 480_000_000 };
    /** Armor commands probed raw on the 3WT's module (receive-only queries on the Armor set; their meaning on the legacy set is what the report finds out). */
    static final int[] PROBES_3WT = { 0x32, 0x34 };
    /** Whether a socket listens on {@code port}, from /proc/net/tcp(6) text (local address :PORT in hex, state 0A). */
    static boolean listening(String procNetTcp, int port) {
        String want = String.format(Locale.US, ":%04X", port);
        for (String l : procNetTcp.split("\n")) {
            String[] f = l.trim().split("\\s+");
            if (f.length > 3 && f[1].toUpperCase(Locale.US).endsWith(want) && "0A".equalsIgnoreCase(f[3])) return true;
        }
        return false;
    }

    /** The Armor 3WT's trial lines (phonedmrapp docs/armor3wt/stock-app-review.md, "What the next 3WT device report must bring back"). */
    static String armor3wtChecks(RadioService svc, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder("Armor 3WT (extmodule):\n");
        String pid = RootSetup.run("pidof extmodule || true").trim();
        String tcp = RootSetup.run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null");
        r.append("  extmodule: ").append(pid.isEmpty() ? "not running" : "running (pid " + pid + ")")
                .append(", listening on ").append(ExtModuleLink.CMD_PORT).append(": ").append(listening(tcp, ExtModuleLink.CMD_PORT) ? "yes" : "no")
                .append(", on ").append(ExtModuleLink.AUDIO_PORT).append(": ").append(listening(tcp, ExtModuleLink.AUDIO_PORT) ? "yes" : "no").append('\n');
        ModuleEngine e = svc.engine();
        String build = e instanceof RadioEngine ? ((RadioEngine) e).bridgeBuild() : null;
        r.append("  bridge MCU build: ").append(build == null ? "(no answer to route 00 0x01)" : build).append('\n');
        boolean started = false; for (String l : svc.recentLog()) if (l.contains("the bridge reports the MCU started")) started = true;
        r.append("  bridge MCU-started report after the daemon's MCU start: ").append(started ? "seen" : "not seen").append('\n');
        r.append("  ro.agold.extmodule.32k = '").append(RootSetup.run("getprop " + ExtModuleLink.PROP_32K).trim()).append("'\n");
        ExtModuleAudio a = svc.extModuleAudio();
        if (a != null) {
            p.step("Trial: extmodule receive stream, 5 s");
            long f0 = a.rxFrames(), b0 = a.rxBytes();
            svc.benchCapture(true); Thread.sleep(5000); svc.benchCapture(false);
            long f = a.rxFrames() - f0, b = a.rxBytes() - b0;
            r.append(String.format(Locale.US, "  receive stream forced open 5 s: %d frames, %d bytes (%s per frame; this profile expects %d at %d kHz)\n",
                    f, b, f > 0 ? String.valueOf(b / f) : "-", a.frameBytes, a.rate / 1000));
        }
        r.append(bandEdgeCheck(svc, p, EDGES_3WT_HZ));
        for (int cmd : PROBES_3WT) {
            p.step(String.format(Locale.US, "Trial: module command 0x%02X (query)", cmd));
            java.util.List<String> was = svc.recentLog();
            String mark = was.isEmpty() ? null : was.get(was.size() - 1);           // the ring drops old lines: find where it stood by content
            try { e.writeRaw(AgoldFrames.request(0x01, cmd, 0x01)); }
            catch (java.io.IOException x) { r.append(String.format(Locale.US, "  0x%02X: not sent (%s)\n", cmd, x.getMessage())); continue; }
            Thread.sleep(1500);
            java.util.List<String> log = svc.recentLog();
            StringBuilder got = new StringBuilder();
            for (int i = mark == null ? 0 : log.lastIndexOf(mark) + 1; i < log.size(); i++) {
                String l = log.get(i);
                if (l.contains(" RX ") || l.contains("legacy module frame")) got.append("\n      ").append(DeviceReport.mask(l));
            }
            r.append(String.format(Locale.US, "  0x%02X 01 (the Armor set's %s):", cmd, cmd == 0x32 ? "signal reading" : "version")).append(got.length() == 0 ? " no answer" : got.toString()).append('\n');
        }
        return r.toString();
    }

    private static String trialTakeOver(Context c, RadioService svc, Progress p) throws InterruptedException {
        StringBuilder r = new StringBuilder();
        p.step("Trial: taking over the radio (receive only)");
        String kBefore = RootSetup.run(KMSG);
        long t0 = System.currentTimeMillis();
        svc.bringUp();
        String link = svc.link();
        while (System.currentTimeMillis() - t0 < 45000) {
            Thread.sleep(500);
            link = svc.link();
            if ("ready".equals(link) || link.startsWith("error") || "no root".equals(link) || "unsupported".equals(link)) break;
            p.step("Trial: " + link);
        }
        long took = System.currentTimeMillis() - t0;
        r.append("result: ").append("ready".equals(link) ? "radio came up in " + took / 1000 + " s" : "did not come up (" + link + " after " + took / 1000 + " s)").append('\n');
        if ("ready".equals(link)) {
            if (!svc.profile().verified) {                              // untested phone: give the audio path something to carry
                final long lhz = svc.profile().listenTestHz(); final String lmhz = String.format(java.util.Locale.US, "%.5f", lhz / 1e6).replaceAll("0{1,2}$", "");   // 446.00625, or the middle of a band without it
                p.step("Trial: listening on " + lmhz + " MHz with the squelch open");
                svc.listenTest((int) lhz, "Listen test " + lmhz);
                Thread.sleep(3000);                                     // the module settles after a retune
                // the module only streams audio while its squelch is open, and QuickChat sets a separate global squelch (0x30):
                // the monitor command (0x2F, as the MON key) forces it open, so noise flows with nobody on the channel
                svc.setMonitor(true);
                Thread.sleep(1500);
                r.append("tuned " + lmhz + " MHz analog, squelch open and monitor on (receive only)\n");
            }
            p.step("Trial: reading signal strength and audio");
            File dir = DeviceReport.newestDir();
            File raw = dir != null ? new File(dir, "rx_audio_raw.bin") : null;
            svc.benchCapture(true);                                     // the Atom XL opens its USB capture per reception: open it for the listen
            String cap = raw != null ? svc.captureRxAudio(raw, 5000) : "no report folder";
            svc.benchCapture(false);
            StringBuilder rs = new StringBuilder();
            for (int i = 0; i < 5; i++) { svc.pollRssiMeasure(); Thread.sleep(1200); rs.append(svc.lastRssi()).append(' '); }
            Thread.sleep(1000);
            r.append("RSSI readings (dBm, -999 = no answer): ").append(rs.toString().trim()).append('\n');
            r.append("receive audio: ").append(cap).append('\n');
            r.append("received audio bytes so far: ").append(svc.rxAudioBytes()).append('\n');
            byte[] head = svc.rxAudioHead();                           // the first bytes the audio port ever delivered
            if (head != null && head.length > 0 && dir != null) {
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "rx_audio_first.bin"))) { o.write(head); } catch (java.io.IOException e) { r.append("could not save the first bytes: ").append(e).append('\n'); }
                StringBuilder hx = new StringBuilder(); for (int i = 0; i < Math.min(64, head.length); i++) hx.append(String.format(Locale.US, "%02x ", head[i] & 0xFF));
                r.append("first ").append(head.length).append(" bytes saved to rx_audio_first.bin; they start: ").append(hx.toString().trim()).append('\n');
            }
            if (!svc.profile().verified) svc.setMonitor(false);
            if (!svc.profile().verified && "agold-legacy".equals(svc.profile().protocol)) {   // the Armor 3WT: the daemon, the bridge, the edges, two queries; receive only
                r.append(armor3wtChecks(svc, p));
                svc.listenTest(446_006_250, "Listen test 446.00625");
                Thread.sleep(1500);
            }
            if (!svc.profile().verified && "mcu68".equals(svc.profile().protocol)) {   // receive only; the module tunes, nothing is keyed
                r.append(squelchCheck(svc, p));
                r.append(levelCheck(svc, dir, p));
                r.append(settleCheck(svc, p));
                r.append(bandEdgeCheck(svc, p));
                svc.listenTest(446_006_250, "Listen test 446.00625");       // back on the listen channel, as the app knows it
                Thread.sleep(1500);
            }
            r.append("module version: ").append(svc.moduleVersion().isEmpty() ? "(no answer to 0x34)" : svc.moduleVersion()).append('\n');
            for (String l : svc.recentLog())                                 // how the port was opened and whether the module printed its banner
                if (l.contains("UART ") && l.contains(" open") || l.contains("module booted") || l.contains("no boot banner")) r.append("  ").append(DeviceReport.mask(l)).append('\n');
            String unused = unusedCommands(svc.profile().stockCommands, svc.engine().stats());
            if (!unused.isEmpty()) r.append("commands the stock app does not use (sent / answered / refused):\n").append(unused);
        }
        p.step("Trial: handing the radio back");
        svc.shutDown(true);
        Thread.sleep(3000);
        for (int w = 0; w < 30 && "agold".equals(svc.profile().protocol) && !RootSetup.stockAppEnabled(svc.profile()); w++) Thread.sleep(1000);   // its hand-back checks the bridge first (~20 s)
        r.append("handed back; stock app enabled: ").append(RootSetup.stockAppEnabled(svc.profile())).append('\n');
        r.append("radio driver kernel messages during the trial:\n").append(newLines(kBefore, RootSetup.run(KMSG)));
        r.append(String.format(Locale.US, "(trial ran %d s)\n", (System.currentTimeMillis() - t0) / 1000));
        return r.toString();
    }
}
