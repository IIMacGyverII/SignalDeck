/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Everything we need to write a device profile for a phone we have never seen, gathered on the phone itself:
 * build facts, serial ports, the sysfs nodes a radio module would use, the stock walkie-talkie app (its APK is
 * copied into the report), vendor libraries, framework jars, init scripts, running services — and, with root,
 * the SELinux labels, process contexts and the stock app's receivers (which reveal the PTT key broadcasts).
 * Output: {@code Download/DMR/DeviceReport/<model>_<time>/report.txt} plus the copied APKs, and one zip of it
 * all to share straight into a GitHub issue.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class DeviceReport {
    public interface Progress { void step(String s); }
    private static final Pattern RADIO = Pattern.compile("(?i)intercom|interphone|walkie|dmr|ptt|quickchat|chamsion|prize|talkie|radio|sy88|uart|serial|tiny");
    private static final Pattern NODES = Pattern.compile("(?i)dmr|intercom|walkie|ptt|uart|radio|sy88|interphone|gpio");

    private DeviceReport() { }

    public static File reportRoot() { return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DMR/DeviceReport"); }

    /** Volatile hook the activity feeds hardware key events into while a PTT capture runs. */
    public static volatile Consumer<KeyEvent> keyCapture;
    /** Packages the last report took for the stock walkie-talkie app (the first-run check watches the first one). */
    public static volatile List<String> lastCandidates = new ArrayList<>();

    /**
     * Bench (adb {@code checks --el hosttime}): the host's clock minus the phone's. A phone whose clock is wrong (the W6 boots
     * at its build date) would otherwise name its folders months off and sort them wrongly. 0 = trust the phone.
     */
    public static volatile long clockOffsetMs;
    static long now() { return System.currentTimeMillis() + clockOffsetMs; }

    /** One line about the phone's clock when the host's differs by two minutes or more, else null. */
    static String clockNote(long phoneMs, long offsetMs) {
        if (Math.abs(offsetMs) < 120_000L) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long d = Math.abs(offsetMs) / 86_400_000L, h = Math.abs(offsetMs) / 3600_000L % 24, m = Math.abs(offsetMs) / 60_000L % 60;
        return "phone " + f.format(new Date(phoneMs)) + " UTC, host " + f.format(new Date(phoneMs + offsetMs)) + " UTC: the phone is "
                + (d > 0 ? d + " d " : "") + (d > 0 || h > 0 ? h + " h " : "") + m + " min " + (offsetMs > 0 ? "behind" : "ahead") + " (folder and header use the host's time)";
    }

    public static File generate(Context c, Progress p) throws Exception {
        String tag = (Build.MANUFACTURER + "_" + Build.MODEL).replaceAll("[^A-Za-z0-9._-]+", "_");
        File dir = new File(reportRoot(), tag + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now())));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("cannot create " + dir);
        StringBuilder r = new StringBuilder();
        boolean root = suPresent() && RootSetup.haveRoot();

        p.step("Phone");
        h(r, "SignalDeck device report " + BuildConfig.VERSION_NAME + "  " + new Date(now()));
        String clock = clockNote(System.currentTimeMillis(), clockOffsetMs); if (clock != null) kv(r, "clock", clock);
        kv(r, "manufacturer", Build.MANUFACTURER); kv(r, "brand", Build.BRAND); kv(r, "model", Build.MODEL); kv(r, "device", Build.DEVICE); kv(r, "product", Build.PRODUCT);
        kv(r, "board", Build.BOARD); kv(r, "hardware", Build.HARDWARE); kv(r, "fingerprint", Build.FINGERPRINT); kv(r, "display", Build.DISPLAY);
        kv(r, "android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ", security patch " + Build.VERSION.SECURITY_PATCH + ")");
        kv(r, "abis", String.join(",", Build.SUPPORTED_ABIS)); kv(r, "kernel", System.getProperty("os.version"));
        String se = cat("/sys/fs/selinux/enforce").trim();
        if (!se.equals("0") && !se.equals("1") && root) se = RootSetup.run("cat /sys/fs/selinux/enforce 2>/dev/null; true").trim();   // apps cannot read it on newer Android
        kv(r, "selinux", se.equals("1") ? "enforcing" : se.equals("0") ? "permissive" : "unknown");
        kv(r, "su present", String.valueOf(suPresent())); kv(r, "root granted to this app", String.valueOf(root));
        kv(r, "magisk", root ? RootSetup.run("magisk -v 2>/dev/null; magisk -V 2>/dev/null").replace('\n', ' ') : "n/a");
        for (String prop : new String[] { "ro.product.model", "ro.board.platform", "ro.hardware", "ro.build.version.sdk", "ro.vendor.build.fingerprint", "persist.sys.usb.config", "ro.boot.verifiedbootstate" }) kv(r, prop, getprop(prop));
        for (String prop : VENDOR_PROPS) kv(r, prop, getprop(prop));
        String vn = vendorName();
        if (vn != null) kv(r, "phone (vendor partition)", vn);
        DeviceProfile dp = DeviceProfile.detect();
        kv(r, "profile detected", dp.supported ? dp.name + (dp.verified ? "" : " (untested on hardware)") : "none (this is an unsupported phone)");
        BuildIdentity.Info build = BuildIdentity.gather(c);
        h(r, "SignalDeck build"); r.append(build.text());              // which commit, repository and signing key made this build (official or fork)

        p.step("Serial ports");
        h(r, "Serial ports (File API)");
        File[] dev = new File("/dev").listFiles();
        if (dev != null) for (File f : dev) if (f.getName().matches("tty(S|MT|HS|USB|GS|ACM)\\d*")) r.append(String.format(Locale.US, "  %-14s read=%s write=%s\n", f.getName(), f.canRead(), f.canWrite()));
        r.append(cat("/proc/tty/drivers"));
        if (root) { h(r, "Serial ports (root: labels, drivers)"); r.append(RootSetup.run("ls -lZ /dev/ttyS* /dev/ttyMT* /dev/ttyHS* /dev/ttyUSB* /dev/ttyGS* 2>/dev/null; echo; cat /proc/tty/drivers; echo; for t in /dev/ttyS*; do echo \"$t: $(stty -F $t -a 2>&1 | head -1)\"; done")).append('\n'); }

        p.step("Radio module nodes");
        h(r, "Platform devices that look like a radio module (/sys/devices/platform)");
        File[] plat = new File("/sys/devices/platform").listFiles();
        if (plat != null) for (File d : plat) if (NODES.matcher(d.getName()).find()) {
            r.append("  ").append(d.getName()).append('\n');
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) r.append(String.format(Locale.US, "      %-24s r=%s w=%s  %s\n", f.getName(), f.canRead(), f.canWrite(), f.canRead() ? cat(f.getAbsolutePath()).trim().replace('\n', ' ').substring(0, Math.min(80, cat(f.getAbsolutePath()).trim().length())) : ""));
        }
        if (root) r.append(RootSetup.run("for d in /sys/devices/platform/*dmr* /sys/devices/platform/*intercom* /sys/devices/platform/*walkie* /sys/devices/platform/*ptt* /sys/devices/platform/*sy88*; do [ -d \"$d\" ] && echo \"--- $d\" && ls -lZ \"$d\" && for f in \"$d\"/*; do [ -f \"$f\" ] && printf '%s = ' \"$f\" && (head -c 200 \"$f\" 2>&1; echo); done; done 2>/dev/null")).append('\n');
        if (root) {                                       // how the kernel describes the module's wiring: pin names, compatible string
            h(r, "Device tree of the radio module (root)");
            String dt = RootSetup.run("for d in /sys/devices/platform/*dmr*/of_node /sys/devices/platform/*intercom*/of_node /sys/devices/platform/*walkie*/of_node; do [ -d \"$d\" ] || continue; echo \"--- $d\"; for f in \"$d\"/*; do [ -f \"$f\" ] && echo \"$(basename \"$f\") $(od -An -tx1 -N" + DT_MAX + " \"$f\" | tr -s ' \\n' ' ')\"; done; done; true");
            for (String l : dt.split("\n")) {
                if (l.startsWith("---") || l.trim().isEmpty()) { r.append(l).append('\n'); continue; }
                String[] kv = l.trim().split(" ", 2);
                r.append("  ").append(kv[0]).append(" = ").append(kv.length > 1 ? dtValue(kv[1]) : "").append('\n');
            }
        }
        h(r, "GPIO / misc nodes"); r.append(root ? RootSetup.run("ls -lZ /dev/ | grep -iE 'dmr|intercom|walkie|ptt|gpio|misc' ; ls /sys/class/gpio 2>/dev/null | head -40") : ls("/sys/class/gpio", null)).append('\n');

        p.step("Walkie-talkie apps");
        h(r, "Installed apps that look like the stock walkie-talkie app");
        PackageManager pm = c.getPackageManager();
        List<String> candidates = new ArrayList<>(); lastCandidates = candidates;
        for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES)) {
            if (pi.packageName.equals(c.getPackageName()) || !RADIO.matcher(pi.packageName).find()) continue;
            if (pi.packageName.matches("(?i).*(android\\.|google\\.|qualcomm|mediatek|mtk).*") && !pi.packageName.matches("(?i).*(intercom|interphone|walkie|dmr|ptt|quickchat).*")) continue;
            ApplicationInfo ai = pi.applicationInfo; boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            r.append(String.format(Locale.US, "  %s  v%s  uid=%d  system=%s  shared=%s\n      apk=%s\n      nativeLibs=%s\n", pi.packageName, pi.versionName, ai.uid, sys, pi.sharedUserId, ai.sourceDir, ai.nativeLibraryDir));
            r.append("      libs: ").append(ls(ai.nativeLibraryDir, null)).append('\n');
            File[] libs = ai.nativeLibraryDir == null ? null : new File(ai.nativeLibraryDir).listFiles();
            if (libs == null && root && ai.nativeLibraryDir != null) libs = rootCopyLibs(c, ai.nativeLibraryDir, r);   // a system app's lib folder the app may not list
            if (libs != null) for (File lf : libs) if (lf.getName().endsWith(".so")) { File real = lf; try { real = lf.getCanonicalFile(); } catch (Throwable ignored) { } String syms = jniSymbols(real); if (syms.isEmpty()) syms = registeredNatives(real); if (!syms.isEmpty()) r.append("      ").append(lf.getName()).append(" -> ").append(real.getAbsolutePath()).append('\n').append(syms); }
            if (pi.requestedPermissions != null) { StringBuilder pp = new StringBuilder(); for (String q : pi.requestedPermissions) if (!q.startsWith("android.permission.") || q.matches(".*(SERIAL|SYSTEM_ALERT|RECORD_AUDIO|MODIFY_AUDIO|WRITE_SECURE|DEVICE_POWER|INJECT|BROADCAST).*")) pp.append(q).append(' '); r.append("      permissions: ").append(pp).append('\n'); }
            if (pi.receivers != null) { r.append("      receivers: "); for (android.content.pm.ActivityInfo x : pi.receivers) r.append(x.name).append(' '); r.append('\n'); }
            if (pi.services != null) { r.append("      services: "); for (android.content.pm.ServiceInfo x : pi.services) r.append(x.name).append(' '); r.append('\n'); }
            if (pi.packageName.matches("(?i).*(intercom|interphone|walkie|dmr|ptt|quickchat|chamsion|prize|talkie).*")) candidates.add(pi.packageName);
        }
        p.step("Ranking the walkie-talkie app candidates");
        List<RadioAppFinder.App> ranked = RadioAppFinder.rank(c);
        h(r, "Walkie-talkie app candidates, ranked (score: evidence)"); r.append(RadioAppFinder.describe(ranked));
        if (!ranked.isEmpty()) {                          // the evidence wins over package names; name matches stay only when nothing scored
            candidates.clear();
            for (RadioAppFinder.App x : ranked) if (candidates.size() < 3 && x.score >= 5) candidates.add(x.pkg);
            if (candidates.isEmpty()) candidates.add(ranked.get(0).pkg);
        }
        r.append("  examined below: ").append(String.join(", ", candidates)).append('\n');
        for (String pkg : candidates) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                List<String> apks = new ArrayList<>(); apks.add(ai.sourceDir); if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apks.add(s);
                for (String a : apks) { File src = new File(a); File dst = new File(dir, pkg + "_" + src.getName()); p.step("Copying " + pkg); if (copy(src, dst)) r.append("  copied ").append(dst.getName()).append(" (").append(dst.length() / 1024).append(" kB)\n"); else if (root) { RootSetup.run("cp " + a + " " + dst.getAbsolutePath() + " && chmod 644 " + dst.getAbsolutePath()); r.append("  copied with root ").append(dst.getName()).append('\n'); } }
            } catch (Throwable t) { r.append("  could not copy ").append(pkg).append(": ").append(t).append('\n'); }
            if (root) { h(r, "dumpsys package " + pkg + " (root)"); r.append(redact(RootSetup.run("dumpsys package " + pkg + " | grep -v '^ *$' | grep -iE 'Receiver|Service|Activity|action|filter|flags|codePath|resourcePath|nativeLibraryPath|primaryCpuAbi|versionName|sharedUser|uid=|perm' | head -300"))).append('\n'); }
            if (root) { h(r, "Databases of " + pkg + " (structure only: tables, columns, row counts; root)"); r.append(dbStructure(c, pkg)).append('\n'); }
            try { p.step("Reading " + pkg + "'s code"); h(r, "Clues in " + pkg + "'s code (paths, audio parameters, serial speeds, PTT log texts)"); r.append(apkClues(new File(pm.getApplicationInfo(pkg, 0).sourceDir))).append('\n'); }
            catch (Throwable t) { r.append("  ").append(t).append('\n'); }
        }

        p.step("System files");
        h(r, "Framework / vendor files of interest");
        for (String d : new String[] { "/system/framework", "/system_ext/framework", "/vendor/framework", "/system/lib64", "/system/lib", "/vendor/lib64", "/vendor/lib", "/system/etc/init", "/vendor/etc/init", "/system/etc/permissions", "/vendor/etc/permissions", "/system/priv-app", "/system/app", "/vendor/app", "/product/app", "/product/priv-app", "/system_ext/priv-app" })
            r.append(String.format(Locale.US, "  %-26s %s\n", d, ls(d, RADIO)));
        h(r, "Serial-looking system libraries and their JNI classes");
        for (File lf : serialLibs(c, null)) { String syms = jniSymbols(lf); if (syms.isEmpty()) syms = registeredNatives(lf); r.append("  ").append(lf.getAbsolutePath()).append(" (").append(lf.length() / 1024).append(" kB)\n").append(syms); }
        h(r, "Binder services"); r.append(exec("service list")).append('\n');
        h(r, "Audio clues (does radio audio go through Android's audio system?)");
        r.append(exec("getprop | grep -iE 'pdt|intercom|interphone|dmr|walkie|ptt|pcm' | grep -v -E 'known_codenames|macdmr' | head -40")).append('\n');
        if (root) r.append(RootSetup.run("grep -il -E 'pdt|intercom|interphone|dmr|walkie' /vendor/etc/*.xml /vendor/etc/audio/* /odm/etc/*.xml /system/etc/audio* 2>/dev/null | head; "
                + "for f in $(grep -il -E 'pdt|intercom|interphone|dmr|walkie' /vendor/etc/*.xml /vendor/etc/audio/* /odm/etc/*.xml 2>/dev/null | head -5); do echo \"--- $f\"; grep -i -E 'pdt|intercom|interphone|dmr|walkie' \"$f\" | head -20; done; "
                + "dumpsys media.audio_flinger 2>/dev/null | grep -i -E 'pdt|intercom|interphone|dmr|walkie' | grep -v macdmr | head -20; true")).append('\n');
        h(r, "Audio devices"); r.append(soundCards()).append(ls("/dev/snd", null)).append('\n');
        p.step("USB devices");
        h(r, "USB devices (a radio behind a USB MCU shows here: serial = CDC, sound = audio class)"); r.append(usbDevices()).append('\n');
        h(r, "Radio-related system properties"); r.append(mask(exec("getprop | grep -iE '" + PROP_WORDS + "' | grep -v -E 'known_codenames|macdmr' | head -80"))).append('\n');

        if (root) {
            p.step("Root extras");
            h(r, "Processes with a radio-looking name (root)"); r.append(RootSetup.run("ps -AZ | grep -iE 'intercom|interphone|walkie|dmr|ptt|quickchat|tiny|serial|uart' | grep -v grep")).append('\n');
            h(r, "Who holds the serial ports (root)"); r.append(RootSetup.run("for p in /proc/[0-9]*; do ls -l $p/fd 2>/dev/null | grep -q '/dev/tty' && echo \"$(cat $p/comm) ($(basename $p)): $(ls -l $p/fd 2>/dev/null | grep -o '/dev/tty[A-Za-z0-9]*' | sort -u | tr '\\n' ' ')\"; done")).append('\n');
            h(r, "Recent logcat lines mentioning the radio (root, redacted)"); r.append(redact(RootSetup.run("logcat -d -t 3000 2>/dev/null | grep -iE 'ptt|intercom|interphone|walkie|dmr|serial|tty|tiny' | grep -vE 'BufferQueue|SurfaceFlinger|MTK_APPList|WindowManager|InputManager|ImeTracker|InputMethod|ApkAssets|PackageConfig|ViewRootImpl' | tail -120"))).append('\n');
            h(r, "SELinux denials seen so far (root)"); r.append(labelDenials(RootSetup.run("logcat -d -b all 2>/dev/null | grep -i 'avc: denied' | tail -120; dmesg 2>/dev/null | grep -i 'avc: denied' | tail -60"), c.getPackageName())).append('\n');
            p.step("Vendor services");
            h(r, "Vendor radio services and programs (root: HIDL list, vendor HAL and daemon names and sizes)"); r.append(vendorServices()).append('\n');
            h(r, "Programs listening on local sockets (root)"); r.append(listeningSockets()).append('\n');
            h(r, "Programs holding USB serial, sound or radio devices (root, full command line)"); r.append(deviceHolders()).append('\n');
            h(r, "Magisk modules (root: id, name, version, file names)"); r.append(magiskModules()).append('\n');
            p.step("Sound card");
            h(r, "Sound card streams and mixer (root)"); r.append(soundCard(c)).append('\n');
        } else {
            h(r, "Root"); r.append("  not available — with Magisk root the report also lists SELinux labels, which process holds the serial ports, and the stock app's receivers (the PTT key broadcasts).\n");
        }

        File txt = new File(dir, "report.txt");
        try (OutputStream o = new FileOutputStream(txt)) { o.write(r.toString().getBytes("UTF-8")); }
        writeJson(dir, build);                                             // Run all checks rewrites it with its summary and draft
        try (OutputStream o = new FileOutputStream(new File(dir, "00_SUMMARY.txt"))) { o.write(summary(RadioService.running, txt).getBytes("UTF-8")); } catch (Throwable ignored) { }   // also when the folder is sent by hand, not through Share
        p.step("Zipping");
        File zip = new File(new File(c.getCacheDir(), "share"), dir.getName() + ".zip"); zip.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) { z.putNextEntry(new ZipEntry(f.getName())); try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) z.write(b, 0, n); } z.closeEntry(); }
        }
        return zip;
    }

    /** The phone fields of report.json. */
    static java.util.Map<String, String> phoneFields() {
        java.util.Map<String, String> ph = new java.util.LinkedHashMap<>();
        ph.put("manufacturer", Build.MANUFACTURER); ph.put("model", Build.MODEL); ph.put("device", Build.DEVICE);
        ph.put("android", Build.VERSION.RELEASE); ph.put("build", Build.DISPLAY);
        return ph;
    }

    /** report.json beside report.txt: the report's facts as data, with the build identity. */
    public static void writeJson(File dir, BuildIdentity.Info build) {
        if (dir == null) return;
        try (OutputStream o = new FileOutputStream(new File(dir, "report.json"))) {
            o.write(ReportJson.build(readReport(dir), phoneFields(), build == null ? null : build.toJson()).toString(2).getBytes("UTF-8"));
        } catch (Throwable t) { android.util.Log.w("DeviceReport", "report.json: " + t); }
    }

    /** The newest report folder, or null. */
    /** The most recently created report folder (by the time in its name: appending to a report changes its mtime). */
    public static File newestDir() {
        File[] fs = reportRoot().listFiles(); if (fs == null) return null;
        File newest = null; for (File f : fs) if (f.isDirectory() && (newest == null || createdAt(f) > createdAt(newest))) newest = f;
        return newest;
    }

    /** When a report folder was made, from its name (…_yyyyMMdd_HHmmss); its mtime if the name has none. */
    public static long createdAt(File dir) {
        java.util.regex.Matcher m = Pattern.compile("_(\\d{8}_\\d{6})$").matcher(dir.getName());
        if (m.find()) try { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.group(1)).getTime(); } catch (Throwable ignored) { }
        return dir.lastModified();
    }

    /** The report folder a follow-up step (listen check) should add to: the newest one if it was made today, else a new report. */
    public static File currentOrNew(Context c) {
        File d = newestDir();
        if (d != null && now() - createdAt(d) < 12 * 3600_000L) return d;
        try { generate(c, s -> { }); } catch (Throwable ignored) { }
        return newestDir();
    }

    /**
     * Strings from the stock app's code and native libraries that tell how it drives the radio: every /sys and /dev path,
     * audio-system parameters ("pdt_play=off"), serial speeds, and log texts about PTT, receive, transmit, power and PCM
     * (QuickChat's "setPttLaunch" / "setPttReceive" told us that ptt_control 0 keys its transmitter), and the radio module's
     * command names ("AT+DMOSETGROUP=": the W6's SA818-class module). Native libraries of every ABI are read: the W6 is a
     * 32-bit phone whose app ships only lib/armeabi-v7a. Only short ASCII strings from the code, never resources or user data.
     */
    public static String apkClues(File apk) {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>(), params = new java.util.TreeSet<>(), bauds = new java.util.TreeSet<>(), logs = new java.util.TreeSet<>(),
                cmds = new java.util.TreeSet<>(), libs = new java.util.TreeSet<>(), actions = new java.util.TreeSet<>(), hidl = new java.util.TreeSet<>(),
                dmrNames = new java.util.TreeSet<>(), named = new java.util.TreeSet<>();
        Pattern pPath = Pattern.compile("/(sys|dev|proc)/[A-Za-z0-9_./-]{2,80}"), pParam = Pattern.compile("\\b[a-z][a-z0-9_]{2,30}=(on|off|1|0|true|false)\\b"),
                pBaud = Pattern.compile("\\bB(9600|19200|38400|57600|115200|230400|460800|921600)\\b"),
                // a keyword that starts a word or a camelCase part ("isPttButton", "PTT key"), not one inside a word ("onInterceptTouchEvent")
                pLog = Pattern.compile("[ -~]{0,50}((?<![A-Za-z])(?i:ptt|pcm|dmr)|(?<=[a-z])(?:Ptt|PTT|Pcm|PCM|Dmr|DMR)|(?i:serial ?port|intercom|interphone|walkie))[ -~]{0,50}"),
                pCmd = Pattern.compile("\\bAT\\+[A-Z][A-Z0-9]{2,20}[=?]?"),
                // broadcast names with "ptt" in their last parts (android.intent.action.PTT.down on the Atom XL) and the vendor HIDL interfaces the app calls
                pAction = Pattern.compile("\\b[a-z][a-z0-9_]*(?:\\.[A-Za-z0-9_]+)+\\.[A-Za-z0-9_]*(?i:ptt)[A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)?\\b"),
                pHidl = Pattern.compile("\\bvendor(?:\\.[a-z0-9_]+)+@\\d+\\.\\d+::I[A-Za-z0-9_]+"),
                pFramework = Pattern.compile("(?i).*(activity|intent|content|receiver|launcher|shortcut|broadcast|permission|fragment|view|layout|leanback|androidx|java|kotlin).*");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement(); String n = e.getName();
                boolean dex = n.matches("classes\\d*\\.dex"), so = n.startsWith("lib/") && n.endsWith(".so");
                if (so) libs.add(n);
                if ((!dex && !so) || e.getSize() > 40_000_000) continue;
                byte[] b; try (InputStream in = z.getInputStream(e)) { java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] t = new byte[65536]; int k; while ((k = in.read(t)) > 0) bo.write(t, 0, k); b = bo.toByteArray(); }
                // split into printable runs, as `strings` would
                StringBuilder run = new StringBuilder();
                for (int i = 0; i <= b.length; i++) {
                    int ch = i < b.length ? b[i] & 0xFF : 0;
                    if (ch >= 0x20 && ch < 0x7F) { if (run.length() < 200) run.append((char) ch); continue; }
                    if (run.length() >= 4) {
                        String s = run.toString();
                        if (dex && s.charAt(0) == s.length() - 1) s = s.substring(1);        // the dex string's length byte happened to be printable
                        java.util.regex.Matcher m = pPath.matcher(s); while (m.find()) paths.add(m.group());
                        m = pParam.matcher(s); while (m.find()) params.add(m.group());
                        m = pBaud.matcher(s); while (m.find()) bauds.add(m.group());
                        m = pCmd.matcher(s); while (m.find() && cmds.size() < 60) cmds.add(m.group());
                        if (dex && !s.contains("/") && !s.contains(" ")) { m = pAction.matcher(s); while (m.find() && actions.size() < 20) actions.add(m.group()); }
                        m = pHidl.matcher(s); while (m.find() && hidl.size() < 10) hidl.add(m.group());
                        if (dex && DMR_COMMAND_NAMES.contains(s)) dmrNames.add(s);
                        if (dex && s.length() <= 100 && logs.size() < 120 && !s.contains("/") && (s.contains(" ") || s.contains(":") || s.contains(","))
                                && pLog.matcher(s).matches() && !pFramework.matcher(s).matches()) logs.add(s.trim());           // log-like texts, not framework names
                    }
                    run.setLength(0);
                }
            }
        } catch (Throwable t) { return "  could not read " + apk + ": " + t + "\n"; }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {               // the lines it names: static String fields holding a /sys or /dev path
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements() && named.size() < 40) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes\\d*\\.dex") || e.getSize() > 40_000_000) continue;
                byte[] b; try (InputStream in = z.getInputStream(e)) { java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] t = new byte[65536]; int k; while ((k = in.read(t)) > 0) bo.write(t, 0, k); b = bo.toByteArray(); }
                for (String[] f : DexFields.staticStrings(b, v -> v.matches("/(sys|dev)/[A-Za-z0-9_./-]{2,80}")))
                    if (named.size() < 40 && !f[0].startsWith("Landroid") && !f[0].startsWith("Ljava")) named.add(f[1] + "=" + f[2]);
            }
        } catch (Throwable ignored) { }
        StringBuilder out = new StringBuilder();
        out.append("  paths: ").append(paths.isEmpty() ? "-" : String.join("  ", paths)).append('\n');
        out.append("  named lines: ").append(named.isEmpty() ? "-" : String.join("  ", named)).append('\n');
        out.append("  audio/system parameters: ").append(params.isEmpty() ? "-" : String.join("  ", params)).append('\n');
        out.append("  serial speeds: ").append(bauds.isEmpty() ? "-" : String.join(" ", bauds)).append('\n');
        out.append("  native libraries: ").append(libs.isEmpty() ? "-" : String.join("  ", libs)).append('\n');
        out.append("  module commands: ").append(cmds.isEmpty() ? "-" : String.join("  ", cmds)).append('\n');
        out.append("  PTT broadcasts: ").append(actions.isEmpty() ? "-" : String.join(" ", actions)).append('\n');
        out.append("  HIDL interfaces: ").append(hidl.isEmpty() ? "-" : String.join(" ", hidl)).append('\n');
        String fam = commandFamily(cmds, dmrNames); if (fam != null) out.append("  protocol hint from its code: ").append(fam).append('\n');
        out.append("  log texts about the radio:\n"); for (String s : logs) out.append("    ").append(mask(s)).append('\n');
        return out.toString();
    }

    /** Command names of the DMR module's 68 … 10 command set as an app's enum spells them (the W6Plus DMR's CMD: SET_DIGITAL = 0x22 …). */
    static final java.util.Set<String> DMR_COMMAND_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "SET_DIGITAL", "SET_ANALOG", "QUERY_DIGITAL_CHANNEL", "QUERY_ANALOG_CHANNEL", "SET_TRANSMIT", "QUERY_DIGITAL_VOICE_INFORMATION",
            "PRINT_STATE", "TRANSMISSION_INTERRUPT", "QUERY_MODULE_CONSISTENCY", "SET_MODULE_CONSISTENCY", "SET_SAVE_POWER_MODE", "SET_TRUNK_MODE"));

    /** The module family the command names in an app's code point to, or null. */
    static String commandFamily(java.util.Collection<String> cmds) { return commandFamily(cmds, java.util.Collections.emptySet()); }
    /**
     * The same, with the DMR command names found in its code: an app may carry both (the W6Plus DMR's has a few AT+DMO
     * strings beside its 68 … 10 command enum), and then the serial watch decides.
     */
    static String commandFamily(java.util.Collection<String> cmds, java.util.Collection<String> dmrNames) {
        int dmo = 0; for (String c : cmds) if (c.startsWith("AT+DMO")) dmo++;
        int dmr = dmrNames.size();
        if (dmr >= 4 && dmo >= 2) return "both the DMR module's 68 … 10 command names (" + dmr + ") and SA818-class AT commands (" + dmo + " AT+DMO… names): the serial watch decides";
        if (dmr >= 4) return "the DMR module's 68 … 10 command set (" + dmr + " command names)";
        return dmo >= 2 ? "SA818-class AT commands (" + dmo + " AT+DMO… names)" : null;
    }

    /** Bytes read from each device-tree property (pin-name lists run to a few hundred). */
    static final int DT_MAX = 512;

    /** A device-tree property from its hex dump: NUL-separated text ("coosea,dmr009") as text, anything else (GPIO specifiers, phandles) as 32-bit cells. */
    static String dtValue(String hex) {
        String[] hb = hex.trim().split("\\s+"); byte[] b = new byte[hb.length]; int n = 0;
        for (String h : hb) if (h.matches("[0-9a-fA-F]{2}")) b[n++] = (byte) Integer.parseInt(h, 16);
        boolean cut = n >= DT_MAX;                        // read limit reached: a text list has no closing NUL yet
        boolean text = n > 0 && (b[n - 1] == 0 || cut) && (b[0] & 0xFF) >= 0x20;
        for (int i = 0; i < n && text; i++) { int ch = b[i] & 0xFF; if (ch != 0 && (ch < 0x20 || ch > 0x7E)) text = false; }
        if (text) return "\"" + new String(b, 0, b[n - 1] == 0 ? n - 1 : n, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0000', '|') + (cut ? "\u2026" : "") + "\"";
        StringBuilder s = new StringBuilder("<");
        for (int i = 0; i + 3 < n; i += 4) s.append(i == 0 ? "" : " ").append(String.format(Locale.US, "0x%02x%02x%02x%02x", b[i], b[i + 1], b[i + 2], b[i + 3]));
        if (n % 4 != 0) for (int i = n - n % 4; i < n; i++) s.append(String.format(Locale.US, " %02x", b[i]));
        return s.append(">").toString();
    }

    /** Copy a library folder with root into the cache (to scan its JNI names); lists what it holds. */
    private static File[] rootCopyLibs(Context c, String dir, StringBuilder r) {
        File dst = new File(c.getCacheDir(), "libscan"); dst.mkdirs();
        String ls = RootSetup.run("ls " + dir + " 2>&1");
        r.append("      libs (root): ").append(ls.replace('\n', ' ')).append('\n');
        RootSetup.run("cp " + dir + "/*.so " + dst.getAbsolutePath() + "/ 2>/dev/null; chmod 644 " + dst.getAbsolutePath() + "/*; true");
        return dst.listFiles();
    }

    /** Tables, columns and row counts (never the rows) of the stock app's databases: what a channel importer needs. */
    static String dbStructure(Context c, String pkg) {
        StringBuilder out = new StringBuilder();
        File dst = new File(c.getCacheDir(), "dbscan"); dst.mkdirs();
        // the folder may not exist (Armor 20WT, 2026-10-24: an error message was read as file names)
        String names = RootSetup.run("d=/data/data/" + pkg + "/databases; [ -d $d ] && ls -1 $d; true").trim();
        if (names.isEmpty()) return out.append("  files: (no databases folder)\n").toString();
        out.append("  files: ").append(names.replace('\n', ' ')).append('\n');
        for (String n : names.split("\\s+")) {
            if (n.isEmpty() || n.endsWith("-wal") || n.endsWith("-shm") || n.endsWith("-journal") || n.contains("(")) continue;
            File f = new File(dst, n);
            String src = "/data/data/" + pkg + "/databases/" + n;
            RootSetup.run("cp " + src + " " + f + "; (cp " + src + "-wal " + f + "-wal; cp " + src + "-shm " + f + "-shm) 2>/dev/null; chmod 666 " + f + "*; chown $(stat -c %u:%g " + c.getCacheDir() + ") " + f + "*; true");
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)) {
                out.append("  ").append(n).append(":\n");
                try (android.database.Cursor t = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
                    while (t.moveToNext()) {
                        String tn = t.getString(0); StringBuilder cols = new StringBuilder();
                        try (android.database.Cursor ci = db.rawQuery("PRAGMA table_info(\"" + tn + "\")", null)) { while (ci.moveToNext()) cols.append(ci.getString(1)).append(' '); }
                        long rows = 0; try (android.database.Cursor k = db.rawQuery("SELECT count(*) FROM \"" + tn + "\"", null)) { if (k.moveToFirst()) rows = k.getLong(0); }
                        out.append("    ").append(tn).append(" (").append(rows).append(" rows): ").append(cols.toString().trim()).append('\n');
                    }
                }
            } catch (Throwable e) { out.append("  ").append(n).append(": ").append(e.getMessage()).append('\n'); }
            for (File x : new File[] { f, new File(f + "-wal"), new File(f + "-shm") }) x.delete();
        }
        return out.toString();
    }

    /** Append a section to the newest report folder (PTT capture, radio probe). */
    public static File append(String title, String body) {
        File newest = newestDir();
        if (newest == null) return null;
        try (OutputStream o = new FileOutputStream(new File(newest, "report.txt"), true)) { o.write(("\n\n==== " + title + " ====\n" + body + "\n").getBytes("UTF-8")); } catch (Throwable ignored) { }
        return newest;
    }

    /** Re-zip the newest report folder (after appends) and return the zip. */
    public static File zipNewest(Context c) throws Exception { return zipNewest(c, true); }
    public static File zipNewest(Context c, boolean withApks) throws Exception { return zipNewest(c, withApks, true); }
    /** The station recording (listen_station.wav) can hold other people's voices: shared only when the owner says so. */
    public static final String STATION_CLIP = "listen_station.wav";
    public static File zipNewest(Context c, boolean withApks, boolean withStation) throws Exception {
        File newest = newestDir();
        if (newest == null) throw new Exception("no report yet");
        try (OutputStream o = new FileOutputStream(new File(newest, "00_SUMMARY.txt"))) { o.write(summary(RadioService.running, new File(newest, "report.txt")).getBytes("UTF-8")); } catch (Throwable ignored) { }
        File zip = new File(new File(c.getCacheDir(), "share"), newest.getName() + (withApks ? "" : "_noapk") + (withStation ? "" : "_nostation") + ".zip"); zip.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            File[] in = newest.listFiles();
            if (in != null) for (File f : in) { if (!withApks && f.getName().endsWith(".apk")) continue; if (!withStation && f.getName().equals(STATION_CLIP)) continue; z.putNextEntry(new ZipEntry(f.getName())); try (InputStream i = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) z.write(b, 0, n); } z.closeEntry(); }
        }
        try { java.nio.file.Files.copy(zip.toPath(), new File(reportRoot(), zip.getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Throwable ignored) { }   // a copy the owner can always find
        return zip;
    }

    /**
     * The first page of a report: what a profile needs to know at a glance (phone, profile, audio path, the latest
     * check results, which commands the module refused or answered slowly, odd signal values), so nobody has to dig
     * through the raw sections below.
     */
    /** The body of the last "==== title ====" section of a report, or null. */
    static String lastSection(String report, String title) {
        String h = "==== " + title + " ====";
        int a = report == null ? -1 : report.lastIndexOf(h); if (a < 0) return null;
        int from = a + h.length(), end = report.indexOf("\n==== ", from);
        return report.substring(from, end < 0 ? report.length() : end);
    }

    static String summary(RadioService svc, File reportTxt) {
        StringBuilder b = new StringBuilder("SIGNALDECK DEVICE REPORT \u2014 SUMMARY\n\n");
        b.append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" \u00B7 Android ").append(Build.VERSION.RELEASE).append(" \u00B7 ").append(Build.DISPLAY).append('\n');
        String vn = vendorName(); if (vn != null) b.append("phone per its vendor partition: ").append(vn).append('\n');   // a GSI names itself, not the phone
        b.append("SignalDeck: ").append(BuildConfig.VERSION_NAME).append(" \u00B7 up ").append(android.os.SystemClock.elapsedRealtime() / 60_000).append(" min since the phone booted\n");
        try { b.append(BuildIdentity.gather(svc).text()); } catch (Throwable ignored) { }     // commit, repository, official or fork, profile flags
        if (svc != null) {
            DeviceProfile dp = svc.profile();
            b.append("profile: ").append(dp.name).append(" (").append(dp.id).append(" revision ").append(dp.revision).append(dp.verified ? ", verified" : ", not verified yet").append(")\n");
            b.append("radio: ").append(svc.isReady() ? "owned by SignalDeck" : "not owned (" + svc.link() + ")").append(svc.moduleVersion().isEmpty() ? "" : " \u00B7 module " + svc.moduleVersion() + (Firmware.differs(svc.stockFirmware(), svc.moduleVersion().trim()) ? " (stock " + svc.stockFirmware() + ")" : "")).append('\n');
            b.append("receive audio: ").append(svc.audioBackendName()).append(svc.audioError() != null ? " \u00B7 ERROR " + svc.audioError() : "").append(" \u00B7 ").append(svc.rxAudioBytes()).append(" bytes so far\n");
            String line = svc.receptionLineState(); if (line != null) b.append("reception line: ").append(line).append('\n');
        } else b.append("radio: SignalDeck's radio service was not running when this was made\n");
        String rep = "";
        try { rep = new String(java.nio.file.Files.readAllBytes(reportTxt.toPath()), "UTF-8"); } catch (Throwable ignored) { }
        String audio = lastVerdict(rep, "==== Radio audio check ====");
        if (audio != null) b.append("\nlatest Radio audio check: ").append(audio).append('\n');
        int k = rep.lastIndexOf("==== Second radio test ====");
        if (k >= 0) {
            b.append("\nlatest second radio test:\n");
            for (String line : rep.substring(k).split("\n")) { String t = line.trim(); if (t.startsWith("verdict:") || t.matches("[0-9]\\..*") || t.startsWith("scanDwellMs") || t.startsWith("noise floor")) b.append("  ").append(t).append('\n'); }
        }
        if (svc != null) {
            b.append("\nradio commands (sent / answered / refused / slow > 1 s / average / slowest answer):\n");
            for (java.util.Map.Entry<Integer, RadioEngine.CommandStats> e : svc.engine().stats().entrySet()) {
                RadioEngine.CommandStats c = e.getValue();
                b.append(String.format(java.util.Locale.US, "  0x%02X  %d / %d / %d / %d / %d ms / %d ms%s\n", e.getKey(), c.sent, c.answered, c.refused, c.slow, c.answered > 0 ? c.sumMs / c.answered : 0, c.maxMs, c.refused > 0 || c.slow > 0 ? "   <-- look" : ""));
            }
            int[] r = svc.engine().rssiCounts();
            b.append(String.format(java.util.Locale.US, "signal readings: %d, of them %d the module's \"no reading\" value%s\n", r[0], r[1], r[1] > 0 ? " (shown as no signal)" : ""));
        }
        b.append(txSection(reportTxt.getParentFile()));
        String checks = lastSection(rep, "Summary");                    // Run all checks' verdict, which ends with the draft profile
        if (checks != null) b.append("\nlatest Run all checks:\n").append(checks.trim()).append('\n');
        b.append("\nThe raw sections follow in report.txt (newest checks at the end).\n");
        return b.toString();
    }
    /** The transmit test session's readable section from tx-report.json in a report folder (already redacted), or "". */
    static String txSection(File dir) {
        File f = dir == null ? null : new File(dir, TxReport.FILE);
        if (f == null || !f.exists()) return "";
        try { return "\n" + TxReport.fromJson(new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"))).summaryText(); }
        catch (Throwable t) { return "\ntransmit test session: " + TxReport.FILE + " could not be read (" + t.getMessage() + ")\n"; }
    }

    private static String lastVerdict(String rep, String header) {
        int k = rep.lastIndexOf(header); if (k < 0) return null;
        int v = rep.indexOf("verdict:\n", k); if (v < 0) return null;
        int e = rep.indexOf("\n\n", v); String t = rep.substring(v + 9, e > v ? e : Math.min(rep.length(), v + 600)).trim();
        return t.length() > 400 ? t.substring(0, 400) + "\u2026" : t;
    }

    /** Open a new GitHub issue in the browser with the phone facts filled in; the zip is attached by hand (drag it into the issue). */
    public static void openIssue(Context c) {
        StringBuilder body = new StringBuilder("## Device support request\n\n");
        body.append("- Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(", ").append(Build.BOARD).append(")\n");
        body.append("- Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append("\n- Build: ").append(Build.DISPLAY).append("\n- Platform: ").append(getprop("ro.board.platform")).append("\n");
        body.append("- Rooted (Magisk): ").append(suPresent() ? "yes" : "no").append("\n- SignalDeck app: ").append(BuildConfig.VERSION_NAME).append("\n\n");
        File newest = newestDir();
        if (newest != null) {
            body.append("Device report folder: `Download/DMR/DeviceReport/").append(newest.getName()).append("`\n**Please drag the report zip (Device → Share last report, or the folder zipped) into this issue.**\n\n");
            String rep = cat(new File(newest, "report.txt").getAbsolutePath());
            int a = rep.indexOf("==== Platform devices"), b = rep.indexOf("==== GPIO");
            if (a > 0 && b > a) body.append("Radio-module nodes found:\n```\n").append(rep, a, Math.min(b, a + 1500)).append("```\n");
            int s1 = rep.indexOf("==== Installed apps"), s2 = rep.indexOf("==== dumpsys", s1); if (s2 < 0) s2 = rep.indexOf("==== Framework", s1);
            if (s1 > 0 && s2 > s1) body.append("Walkie-talkie app(s):\n```\n").append(rep, s1, Math.min(s2, s1 + 1500)).append("```\n");
        } else body.append("(No device report generated yet — Device → Generate device report, then attach its zip.)\n");
        body.append("\nWhat the stock walkie-talkie app can do (DMR? FM? text? GPS?), and anything odd you noticed:\n\n");
        String title = "Device support: " + Build.MANUFACTURER + " " + Build.MODEL;
        String url = "https://github.com/IIMacGyverII/SignalDeck/issues/new?title=" + android.net.Uri.encode(title) + "&labels=" + android.net.Uri.encode("device-support") + "&body=" + android.net.Uri.encode(body.length() > 6000 ? body.substring(0, 6000) + "\n…(trimmed; everything is in the zip)" : body.toString());
        try { c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { android.widget.Toast.makeText(c, "No browser available", android.widget.Toast.LENGTH_LONG).show(); }
    }

    public static void share(Context c, File zip) {
        android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", zip);
        Intent i = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_SUBJECT, "SignalDeck device report: " + Build.MANUFACTURER + " " + Build.MODEL)
                .putExtra(Intent.EXTRA_TEXT, "Device report for a DMR phone, generated by the SignalDeck app. Attach it to a GitHub issue at https://github.com/IIMacGyverII/SignalDeck/issues").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, "Share the device report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * PTT key capture: for {@code seconds}, record every hardware key the activity sees, every input event (root,
     * getevent), and every broadcast/log line that mentions ptt/intercom. The user presses the side button meanwhile.
     */
    static String[] pttActions() { return PTT_ACTIONS; }
    private static final String[] PTT_ACTIONS = { "com.interphone.ptt.down", "com.interphone.ptt.up", "com.android.intent.ptt.down", "com.android.intent.ptt.up", "android.intent.action.PTT.down", "android.intent.action.PTT.up", "android.intent.action.PTT", "com.runbo.ptt.key.down", "com.runbo.ptt.key.up", "com.android.action.ptt.down", "com.android.action.ptt.up", "android.intent.action.ptt.down", "android.intent.action.ptt.up", "com.sonim.intent.action.PTT_KEY_DOWN", "com.sonim.intent.action.PTT_KEY_UP", "com.kyocera.android.intent.action.PTT_BUTTON", "android.intent.action.PTT_BUTTON_DOWN", "android.intent.action.PTT_BUTTON_UP", "com.zello.ptt.down", "com.zello.ptt.up" };

    public static String captureKeys(Context c, int seconds) {
        final StringBuilder keys = new StringBuilder();
        final android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() { @Override public void onReceive(Context ctx, Intent i) { keys.append("  broadcast ").append(i.getAction()).append(i.getExtras() != null ? " extras=" + i.getExtras() : "").append('\n'); } };
        android.content.IntentFilter f = new android.content.IntentFilter(); for (String a : PTT_ACTIONS) f.addAction(a);
        try { if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(br, f, Context.RECEIVER_EXPORTED); else c.registerReceiver(br, f); } catch (Throwable ignored) { }
        keyCapture = e -> keys.append(String.format(Locale.US, "  key %s code=%d scan=%d device=%d flags=0x%x\n", e.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP  ", e.getKeyCode(), e.getScanCode(), e.getDeviceId(), e.getFlags()));
        boolean root = suPresent() && RootSetup.haveRoot();
        String events = "", logs = "", devices = "";
        try {
            if (root) {
                RootSetup.run("logcat -c");
                devices = keyDevices(RootSetup.run("getevent -pl 2>&1 | head -400"));
                events = keyEvents(RootSetup.run("timeout " + seconds + " getevent -lt 2>&1 | grep -v 'SYN_REPORT' | head -6000"));
                logs = redact(RootSetup.run("logcat -d 2>/dev/null | grep -iE 'ptt|intercom|interphone|walkie|KeyEvent|keycode' | head -80"));
            } else Thread.sleep(seconds * 1000L);
        } catch (Throwable ignored) { }
        keyCapture = null;
        try { c.unregisterReceiver(br); } catch (Throwable ignored) { }
        String layouts = root ? keyLayouts(rawKeys(events)) : "";
        return "Activity key events and known PTT broadcasts:\n" + (keys.length() == 0 ? "  (none reached the app)\n" : keys)
                + (devices.isEmpty() ? "" : "\nInput devices with keys (root getevent -pl; a side key is often on its own device):\n" + devices)
                + "\nInput events (root getevent; keys and switches only):\n" + (events.isEmpty() ? "  (n/a)\n" : events + "\n")
                + (layouts.isEmpty() ? "" : "\nWhat the key layouts make of those keys (root):\n" + layouts)
                + "\nLog lines during the capture (root):\n" + (logs.isEmpty() ? "  (n/a)\n" : logs + "\n");
    }

    /**
     * The key and switch events of a getevent -lt capture, with the device list it starts with; motion and sensor events
     * (EV_ABS, EV_REL, EV_MSC: the W6's accelerometer sent about 1000 lines in 10 s and buried any key) are counted per
     * device instead of listed. At most 300 lines.
     */
    static String keyEvents(String raw) {
        StringBuilder out = new StringBuilder();
        java.util.Map<String, Integer> dropped = new java.util.TreeMap<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        String lastDev = null; int lines = 0;
        for (String l : (raw == null ? "" : raw).split("\n")) {
            String t = l.trim(); if (t.isEmpty()) continue;
            java.util.regex.Matcher add = Pattern.compile("^add device \\d+: (/dev/input/\\S+)").matcher(t);
            if (add.find()) { lastDev = add.group(1); continue; }
            java.util.regex.Matcher nm = Pattern.compile("^name:\\s+\"(.*)\"").matcher(t);
            if (nm.find() && lastDev != null) { names.put(lastDev, nm.group(1)); continue; }
            java.util.regex.Matcher ev = Pattern.compile("(/dev/input/\\S+): (EV_[A-Z]+)").matcher(t);
            if (!ev.find()) continue;
            if (ev.group(2).equals("EV_KEY") || ev.group(2).equals("EV_SW")) { if (lines++ < 300) out.append("  ").append(t).append(names.containsKey(ev.group(1)) ? "   (" + names.get(ev.group(1)) + ")" : "").append('\n'); }
            else dropped.merge(ev.group(1), 1, Integer::sum);
        }
        if (lines == 0) out.append("  no key or switch events\n");
        if (lines > 300) out.append("  … ").append(lines - 300).append(" more key events\n");
        for (java.util.Map.Entry<String, Integer> e : dropped.entrySet())
            out.append("  (").append(e.getValue()).append(" motion/sensor events from ").append(e.getKey()).append(names.containsKey(e.getKey()) ? " " + names.get(e.getKey()) : "").append(" left out)\n");
        return out.toString();
    }

    /** From getevent -pl: each input device that reports keys, with its key names ("event12 hook_dmr: KEY_F2 KEY_F3"). */
    static String keyDevices(String pl) {
        StringBuilder out = new StringBuilder();
        String dev = null, name = ""; java.util.List<String> keys = null; boolean inKeys = false;
        for (String l : ((pl == null ? "" : pl) + "\nadd device 0: /dev/null").split("\n")) {
            java.util.regex.Matcher add = Pattern.compile("^add device \\d+: /dev/input/(\\S+)|^add device \\d+: (/dev/null)").matcher(l.trim());
            if (add.find()) {
                if (dev != null && keys != null && !keys.isEmpty()) out.append("  ").append(dev).append(" ").append(name).append(": ").append(String.join(" ", keys.subList(0, Math.min(40, keys.size())))).append(keys.size() > 40 ? " …" : "").append('\n');
                dev = add.group(1); name = ""; keys = new java.util.ArrayList<>(); inKeys = false; continue;
            }
            String t = l.trim();
            java.util.regex.Matcher nm = Pattern.compile("^name:\\s+\"(.*)\"").matcher(t);
            if (nm.find()) { name = nm.group(1); continue; }
            if (t.startsWith("KEY (0001):")) { inKeys = true; t = t.substring(11); }
            else if (t.matches("^[A-Z]{2,4}\\s+\\([0-9a-f]{4}\\):.*") || t.startsWith("input props") || t.startsWith("events:")) { inKeys = false; continue; }
            if (inKeys && keys != null) for (String k : t.split("\\s+")) if (k.matches("(KEY|BTN)_[A-Z0-9_]+|[0-9a-f]{4}")) keys.add(k);
        }
        return out.length() == 0 ? "  (none)\n" : out.toString();
    }

    /** JNI entry points of a native library, by scanning for "Java_" symbol names: tells us the Java class the vendor's serial opener binds to. */
    public static String jniSymbols(File lib) {
        StringBuilder out = new StringBuilder();
        try (InputStream in = new FileInputStream(lib)) {
            byte[] b = new byte[(int) Math.min(lib.length(), 4_000_000)]; int n = 0, k; while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
            String s = new String(b, 0, n, "ISO-8859-1");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Java_([A-Za-z0-9_]+)").matcher(s);
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            while (m.find()) {
                String sym = m.group(1); if (!seen.add(sym)) continue;
                int cut = sym.lastIndexOf('_'); if (cut <= 0) continue;
                String cls = sym.substring(0, cut).replace("_1", "\u0001").replace('_', '.').replace("\u0001", "_"), method = sym.substring(cut + 1).replaceFirst("__.*$", "");
                out.append("      JNI ").append(cls).append('.').append(method).append("()\n");
            }
        } catch (Throwable ignored) { }
        return out.toString();
    }

    /**
     * For a library without exported Java_ symbols (it calls RegisterNatives from JNI_OnLoad, like the Xplore 1's
     * libwonder_serialport.so): the Java class paths, method signatures and method-like names it carries as strings,
     * which is what a wrapper class needs.
     */
    public static String registeredNatives(File lib) {
        StringBuilder out = new StringBuilder();
        try (InputStream in = new FileInputStream(lib)) {
            byte[] b = new byte[(int) Math.min(lib.length(), 4_000_000)]; int n = 0, k; while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
            String s = new String(b, 0, n, "ISO-8859-1");
            if (!s.contains("JNI_OnLoad")) return "";
            java.util.LinkedHashSet<String> cls = new java.util.LinkedHashSet<>(), sig = new java.util.LinkedHashSet<>(), names = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z][a-z0-9_]*(?:/[A-Za-z0-9_$]+){2,}").matcher(s);
            while (m.find()) if (!m.group().matches("(java|android|dalvik|system|vendor|apex|data|proc|dev|sys)/.*")) cls.add(m.group().replace('/', '.'));
            m = java.util.regex.Pattern.compile("\\([A-Za-z0-9/;\\[$]*\\)[A-Za-z0-9/;\\[$]+").matcher(s);
            while (m.find()) sig.add(m.group());
            m = java.util.regex.Pattern.compile("\u0000([a-z][A-Za-z0-9_]{2,30})(?=\u0000)").matcher(s);
            while (m.find()) if (m.group(1).matches("(?i).*(open|close|read|write|setup|serial|baud).*")) names.add(m.group(1));
            if (cls.isEmpty() && sig.isEmpty()) return "";
            out.append("      registered at load time; classes: ").append(String.join(" ", cls)).append('\n');
            out.append("      signatures: ").append(String.join(" ", sig)).append('\n');
            out.append("      method-like names: ").append(String.join(" ", names)).append('\n');
        } catch (Throwable ignored) { }
        return out.toString();
    }

    /** Serial-looking native libraries: the stock app's own plus anything in the system library folders. */
    public static List<File> serialLibs(Context c, String stockPkg) {
        List<File> out = new ArrayList<>();
        try { if (stockPkg != null && !stockPkg.isEmpty()) { String d = c.getPackageManager().getApplicationInfo(stockPkg, 0).nativeLibraryDir; File[] fs = d == null ? null : new File(d).listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".so")) out.add(f); } } catch (Throwable ignored) { }
        for (String d : new String[] { "/system/lib64", "/vendor/lib64", "/system/lib", "/vendor/lib" }) { File[] fs = new File(d).listFiles(); if (fs != null) for (File f : fs) if (f.getName().matches("(?i).*(serial|uart|intercom|interphone|walkie|dmr).*\\.so")) out.add(f); }
        return out;
    }

    /** Log excerpts go into a report that may be posted publicly: drop message content, mask long numbers (DMR IDs, phone numbers), mask e-mails. */
    static String redact(String log) {
        if (log == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : log.split("\n")) {
            if (line.matches("(?i).*(msgContent|SmsMessage|content=|callNumber|passcode|password|token|api_key|apikey).*")) continue;
            out.append(mask(line)).append('\n');
        }
        return out.toString();
    }

    /** Long numbers (DMR IDs, phone numbers), e-mail addresses and amateur callsigns. */
    static String mask(String line) {
        line = AgoldFrames.redact(line);                                       // the radio frames' own private bytes; their hex is left alone below
        java.util.List<int[]> frames = AgoldFrames.spans(line);
        if (frames.isEmpty()) return maskText(line);
        StringBuilder out = new StringBuilder(); int at = 0;
        for (int[] f : frames) { out.append(maskText(line.substring(at, f[0]))).append(line, f[0], f[1]); at = f[1]; }
        return out.append(maskText(line.substring(at))).toString();
    }

    private static String maskText(String line) {
        return line.replaceAll("\\b\\d{6,}\\b", "######").replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***@***")
                .replaceAll("(?<![\\w.])[A-Z]{1,2}[0-9][A-Z]{1,3}(-[0-9]{1,2})?(?![\\w.])", "CALL");   // not inside a dotted firmware name (DMR003.UV4T.V022)
    }

    /** Frames whose bytes carry IDs, keys or message text: digital channel (own ID), encryption, caller info, texts. */
    private static final Pattern PRIVATE_FRAME = Pattern.compile("(?i).*cmd=0x(22|29|2b|2c|2d)\\b.*");

    /**
     * SignalDeck's own log for a report that may be posted publicly: what the radio and the app did (bring-up steps,
     * port and power lines, module answers, errors) stays; anything about people, places or content goes: packet,
     * position, message, caption and caller lines are dropped, coordinates (4+ decimals) and the rest masked.
     */
    public static String redactAppLog(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String l : lines) {
            if (l.matches("(?i).*(aprs|packet|pkt|position|beacon|igate|digipeat|sms|text|message|caption|heard|caller|contact|location|gps|coverage|lat[ :=]|lon[ :=]|pin|passcode|password|token|key=|radioid|talker|from the web).*")) continue;
            if (PRIVATE_FRAME.matcher(l).matches()) l = l.replaceAll("\\[[^\\]]*\\]", "[…]").replaceAll("<[^>]*>", "<…>");
            out.append(mask(l).replaceAll("-?\\b\\d{1,3}\\.\\d{4,}\\b", "#.####")).append('\n');
        }
        return out.length() == 0 ? "(empty)\n" : out.toString();
    }

    // ---- phones the Agold/Atom XL/Armor 3WT reports taught us about ----

    private static final String[] VENDOR_PROPS = { "ro.product.vendor.brand", "ro.product.vendor.manufacturer", "ro.product.vendor.model", "ro.product.vendor.device", "ro.product.odm.model", "ro.product.board", "ro.board.platform" };
    private static final String PROP_WORDS = "agold|agui|extmodule|intercom|interphone|dmr|walkie|ptt|smartkey|prize|pdt";

    /** "Ulefone Armor 3WT (board s62v71c2k_gq_3wt)" from the vendor partition when Build says something generic (a GSI), else null. */
    static String vendorName() { return vendorName(Build.MODEL, getprop("ro.product.vendor.brand"), getprop("ro.product.vendor.model"), getprop("ro.product.board").isEmpty() ? Build.BOARD : getprop("ro.product.board")); }
    static String vendorName(String model, String vBrand, String vModel, String board) {
        boolean generic = model == null || model.matches("(?i).*(treble|gsi|phh|lineage|generic|aosp|sdk_|^unknown$).*");
        String v = ((vBrand == null ? "" : vBrand.trim()) + " " + (vModel == null ? "" : vModel.trim())).trim();
        if (v.isEmpty() || v.matches("(?i).*(treble|gsi|phh|generic).*")) v = "";
        if (!generic) return null;                                             // a stock ROM's own name is right
        String b = board == null || board.trim().isEmpty() || board.equalsIgnoreCase("unknown") ? "" : board.trim();
        if (v.isEmpty() && b.isEmpty()) return null;
        return (v.isEmpty() ? "unknown" : v) + (b.isEmpty() ? "" : " (board " + b + ")");
    }

    /** /proc/asound/cards with each USB sound card marked (the Agold phones' radio audio is the MCU's USB audio class card). */
    static String soundCards() {
        String cards = cat("/proc/asound/cards");
        StringBuilder out = new StringBuilder();
        for (String l : cards.split("\n")) out.append(l).append(l.matches("\\s*\\d+ \\[.*") && l.matches("(?i).*usb.*") ? "   <- USB sound card" : "").append('\n');
        return out.toString();
    }

    /** A USB interface class byte in words. */
    static String usbClass(String hex) {
        switch (hex == null ? "" : hex.trim().toLowerCase(Locale.US)) {
            case "01": return "audio"; case "02": return "serial (CDC)"; case "03": return "HID"; case "08": return "storage"; case "09": return "hub";
            case "0a": return "CDC data"; case "0e": return "video"; case "e0": return "wireless"; case "fe": return "DFU/app"; case "ff": return "vendor";
            default: return hex == null || hex.trim().isEmpty() ? "?" : "class " + hex.trim();
        }
    }

    /** Every USB device: vendor:product, maker and product names, and what each interface is. No serial numbers. */
    static String usbDevices() {
        File[] ds = new File("/sys/bus/usb/devices").listFiles();
        if (ds == null) return "  (not readable)\n";
        java.util.Arrays.sort(ds);
        StringBuilder out = new StringBuilder();
        for (File d : ds) {
            String vid = cat(d + "/idVendor").trim(); if (vid.isEmpty() || d.getName().startsWith("usb")) continue;   // root hubs
            out.append(String.format(Locale.US, "  %-8s %s:%s  %s %s\n", d.getName(), vid, cat(d + "/idProduct").trim(), cat(d + "/manufacturer").trim(), cat(d + "/product").trim()));
            File[] is = d.listFiles((x, n) -> n.startsWith(d.getName() + ":"));
            if (is != null) { java.util.Arrays.sort(is); for (File i : is) out.append("           ").append(i.getName()).append(": ").append(usbClass(cat(i + "/bInterfaceClass"))).append(ttyOf(i)).append('\n'); }
        }
        return out.length() == 0 ? "  (none besides the root hubs)\n" : out.toString();
    }
    /** The tty an interface made: CDC ACM puts it under tty/ttyACMn, USB serial converters as ttyUSBn directly. */
    private static String ttyOf(File iface) {
        File[] sub = new File(iface, "tty").listFiles();
        if (sub != null && sub.length > 0) return " -> /dev/" + sub[0].getName();
        File[] t = iface.listFiles((x, n) -> n.matches("tty[A-Z]+\\d+"));
        return t != null && t.length > 0 ? " -> /dev/" + t[0].getName() : "";
    }

    private static String vendorServices() {
        StringBuilder out = new StringBuilder("HIDL services (lshal):\n");
        String hal = RootSetup.run("lshal --neat 2>/dev/null | grep -iE '" + PROP_WORDS + "|serial|tty|mcu' | head -20; true").trim();
        out.append(hal.isEmpty() ? "  (none matching)" : hal).append("\nprograms (size, path):\n");
        int n = 0;
        for (String d : new String[] { "/vendor/bin/hw", "/vendor/bin", "/system/bin", "/system_ext/bin", "/odm/bin" }) {
            for (String l : RootSetup.run("ls -l " + d + " 2>/dev/null").split("\n")) {
                String[] w = l.trim().split("\\s+");
                if (w.length < 8 || !w[w.length - 1].matches("(?i).*(" + PROP_WORDS + "|mcu|tinyserver|dfu).*") || w[0].startsWith("l")) continue;
                out.append(String.format(Locale.US, "  %9s  %s/%s\n", w[4], d, w[w.length - 1]));
                if (++n >= 30) break;
            }
        }
        if (n == 0) out.append("  (none matching)\n");
        return out.toString();
    }

    /** TCP and unix sockets in LISTEN state with the program behind each (the Armor 3WT's radio daemon serves 127.0.0.1:9980/9985). */
    private static String listeningSockets() {
        java.util.Map<String, String> owner = socketOwners(RootSetup.run("ls -l /proc/[0-9]*/fd 2>/dev/null | grep -E '^/proc/|socket:'"));
        StringBuilder out = new StringBuilder();
        for (String[] t : tcpTable(RootSetup.run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"))) {
            if (!t[3].equals("0A")) continue;
            String pid = owner.get(t[4]);
            out.append(String.format(Locale.US, "  tcp %s:%d  %s\n", t[0], Integer.parseInt(t[1], 16), pid == null ? "(owner not found)" : "pid " + pid + "  " + RootSetup.run("tr '\\0' ' ' < /proc/" + pid + "/cmdline 2>/dev/null | cut -c1-120").trim()));
        }
        if (out.length() == 0) out.append("  (no TCP port is listening)\n");
        out.append("unix sockets with a radio-looking name:\n");
        int n = 0;
        for (String l : RootSetup.run("cat /proc/net/unix 2>/dev/null").split("\n")) {
            String[] w = l.trim().split("\\s+");
            if (w.length < 8 || !w[7].matches("(?i).*(" + PROP_WORDS + "|radio|mcu|serial).*")) continue;
            String pid = owner.get(w[6]);
            out.append("  ").append(w[7]).append(pid == null ? "" : "  pid " + pid + "  " + RootSetup.run("tr '\\0' ' ' < /proc/" + pid + "/cmdline 2>/dev/null | cut -c1-120").trim()).append('\n');
            if (++n >= 20) break;
        }
        if (n == 0) out.append("  (none)\n");
        return out.toString();
    }

    /** /proc/net/tcp(6) rows as {local address hex, local port hex, remote port hex, state hex, inode}. */
    static List<String[]> tcpTable(String procNetTcp) {
        List<String[]> out = new ArrayList<>();
        for (String l : (procNetTcp == null ? "" : procNetTcp).split("\n")) {
            String[] w = l.trim().split("\\s+");
            if (w.length < 10 || !w[1].contains(":") || !w[0].endsWith(":")) continue;
            String[] loc = w[1].split(":"), rem = w[2].split(":");
            out.add(new String[] { addr(loc[0]), loc[1], rem.length > 1 ? rem[1] : "0", w[3].toUpperCase(Locale.US), w[9] });
        }
        return out;
    }
    /** 0100007F -> 127.0.0.1; an IPv6 hex address is shortened to "[v6]" unless it is all zeros or the v4-mapped loopback. */
    static String addr(String hex) {
        if (hex.length() == 8) { long v = Long.parseLong(hex, 16); return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF); }
        if (hex.matches("0+")) return "[::]";
        if (hex.toUpperCase(Locale.US).startsWith("0000000000000000FFFF0000")) return addr(hex.substring(24));
        return "[v6]";
    }
    /** "ls -l /proc/[0-9]*\/fd" output -> socket inode -> pid. */
    static java.util.Map<String, String> socketOwners(String ls) {
        java.util.Map<String, String> m = new java.util.HashMap<>(); String pid = null;
        for (String l : (ls == null ? "" : ls).split("\n")) {
            java.util.regex.Matcher d = Pattern.compile("^/proc/(\\d+)/fd:?").matcher(l.trim());
            if (d.find()) { pid = d.group(1); continue; }
            java.util.regex.Matcher s = Pattern.compile("socket:\\[(\\d+)\\]").matcher(l);
            if (s.find() && pid != null && !m.containsKey(s.group(1))) m.put(s.group(1), pid);
        }
        return m;
    }
    /** One process's TCP sockets ("ls -l /proc/PID/fd" output against /proc/net/tcp): "local port -> remote port (state); ...". */
    static String tcpOf(String procNetTcp, String lsFd) {
        java.util.Set<String> mine = new java.util.HashSet<>(socketOwners("/proc/0/fd:\n" + lsFd).keySet());
        StringBuilder out = new StringBuilder();
        for (String[] t : tcpTable(procNetTcp)) if (mine.contains(t[4]))
            out.append(String.format(Locale.US, "%d -> %d (%s); ", Integer.parseInt(t[1], 16), Integer.parseInt(t[2], 16), t[3].equals("01") ? "connected" : t[3].equals("0A") ? "listening" : "state " + t[3]));
        return out.length() == 0 ? "(none)" : out.toString().trim();
    }

    /** Every process with a USB serial port, a sound device or a known radio device open, with its full command line. */
    private static String deviceHolders() {
        return RootSetup.run("for p in /proc/[0-9]*; do d=$(ls -l $p/fd 2>/dev/null | grep -oE '/dev/(tty(ACM|USB|GS)[0-9]*|snd/[A-Za-z0-9]+|stmcu|ttyTTCMD|dmr[A-Za-z0-9_]*)' | sort -u | tr '\\n' ' '); "
                + "[ -n \"$d\" ] && echo \"  $(tr '\\0' ' ' < $p/cmdline 2>/dev/null | cut -c1-160) (pid ${p#/proc/}): $d\"; done; true");
    }

    /** Installed Magisk modules: id, name, version, state and their file names (never their contents). */
    private static String magiskModules() {
        String out = RootSetup.run("for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue; id=$(basename $d); "
                + "echo \"  $id: $(grep -m1 '^name=' $d/module.prop 2>/dev/null | cut -d= -f2-) $(grep -m1 '^version=' $d/module.prop 2>/dev/null | cut -d= -f2-)$([ -f $d/disable ] && echo ' (disabled)')$([ -f $d/remove ] && echo ' (to be removed)')\"; "
                + "(cd $d && find . -type f ! -name module.prop | sed 's|^\\./|      |' | head -40); done; true");
        return out.trim().isEmpty() ? "  (none)\n" : out;
    }

    /** getevent -lt key lines that carry a raw scancode (no name): {event device, hex code}, each once. */
    static List<String[]> rawKeys(String getevent) {
        List<String[]> out = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = Pattern.compile("(/dev/input/event\\d+):\\s+EV_KEY\\s+([0-9a-fA-F]{4})\\s").matcher(getevent == null ? "" : getevent);
        while (m.find()) if (seen.add(m.group(1) + m.group(2))) out.add(new String[] { m.group(1), m.group(2).toLowerCase(Locale.US) });
        return out;
    }

    /** For each raw scancode: the input device's name, the key layout file Android uses for it, and that file's line for the code. */
    private static String keyLayouts(List<String[]> keys) {
        StringBuilder out = new StringBuilder();
        String input = keys.isEmpty() ? "" : RootSetup.run("dumpsys input 2>/dev/null");
        for (String[] k : keys) {
            int code = Integer.parseInt(k[1], 16);
            String[] dev = inputDevice(input, k[0]); String name = dev[0], kl = dev[1];
            String line = kl.isEmpty() ? "" : RootSetup.run("grep -E '^key +" + code + " ' " + kl + " 2>/dev/null | head -1").trim();
            out.append(String.format(Locale.US, "  scancode 0x%s (%d) on %s%s: ", k[1], code, k[0], name.isEmpty() ? "" : " (" + name + ")"));
            if (kl.isEmpty()) out.append("no key layout file listed for this device\n");
            else if (line.isEmpty()) out.append(kl).append(" has no line for it, so it never becomes an Android key; only getevent sees it\n");
            else { String[] w = line.split("\\s+"); int kc = w.length > 2 ? KeyEvent.keyCodeFromString("KEYCODE_" + w[2]) : 0; out.append(kl).append(" maps it to ").append(w.length > 2 ? w[2] : line).append(kc > 0 ? " (Android keycode " + kc + ")" : "").append(vendorKeyNote(w.length > 2 ? w[2] : "", kc)).append('\n'); }
        }
        return out.toString();
    }

    /** The last keycode of Android 11's list (KEYCODE_PROFILE_SWITCH); a phone maker's own keys come after it. */
    static final int AOSP_LAST_KEYCODE = 288;

    /**
     * A key layout name Android does not know, or a keycode past its list, is a phone maker's own key: on the Atom XL the
     * side PTT key is FUNC1 (keycode 289), which the framework turns into the PTT broadcasts.
     */
    static String vendorKeyNote(String name, int keycode) {
        if (keycode > AOSP_LAST_KEYCODE || (keycode == 0 && name.matches("[A-Z][A-Z0-9_]*")))
            return ": a vendor key, not in Android's list (the PTT key on some phones: the Atom XL's is FUNC1, keycode 289)";
        return "";
    }

    /** {device name, key layout file} of an input device in dumpsys input's Event Hub list ("  3: mtk-kpd ... Path: /dev/input/event1 ... KeyLayoutFile: ..."). */
    static String[] inputDevice(String dumpsysInput, String path) {
        String in = dumpsysInput == null ? "" : dumpsysInput;
        java.util.regex.Matcher p = Pattern.compile("Path: " + Pattern.quote(path) + "\\s").matcher(in);
        if (!p.find()) return new String[] { "", "" };
        int at = p.start();
        String name = "";
        java.util.regex.Matcher n = Pattern.compile("\\n\\s*-?\\d+: ([^\\n]+)").matcher(in.substring(Math.max(0, at - 2000), at));
        while (n.find()) name = n.group(1).trim();
        java.util.regex.Matcher next = Pattern.compile("\\n\\s*-?\\d+: ").matcher(in);
        int end = next.find(at) ? next.start() : in.length();
        java.util.regex.Matcher f = Pattern.compile("KeyLayoutFile: (\\S*)").matcher(in.substring(at, end));
        return new String[] { name, f.find() ? f.group(1) : "" };
    }

    /** SELinux denial lines with SignalDeck's own harmless ones counted instead of listed (Magisk's su looks into /data/local/tests on every call). */
    static String labelDenials(String raw, String pkg) {
        StringBuilder out = new StringBuilder(); int ownTests = 0, own = 0;
        for (String l : (raw == null ? "" : raw).split("\n")) {
            if (l.trim().isEmpty()) continue;
            boolean mine = l.contains("app=" + pkg);
            if (mine && l.contains("name=\"tests\"") && l.contains("shell_test_data_file")) { ownTests++; continue; }
            if (mine) own++;
            out.append(mine ? "  [SignalDeck] " : "  ").append(l.trim()).append('\n');
        }
        if (out.length() == 0 && ownTests == 0) return "  (none)\n";
        if (ownTests > 0) out.append(String.format(Locale.US, "  (+%d of SignalDeck's own root calls looking into /data/local/tests, left out: harmless, Magisk's su checks that folder)\n", ownTests));
        if (own > 0) out.append(String.format(Locale.US, "  (%d line%s marked [SignalDeck] come from this app itself, mostly the report reading what an app may not)\n", own, own == 1 ? "" : "s"));
        return out.toString();
    }

    // ---- helpers ----
    private static void h(StringBuilder r, String s) { r.append("\n==== ").append(s).append(" ====\n"); }

    /**
     * The sound card's streams, which of them run and who holds them, and every mixer control with its value (through
     * SignalDeck's sdalsa helper): what a direct audio path for a ROM without the vendor audio service is built from.
     */
    static String soundCard(Context c) {
        StringBuilder s = new StringBuilder(RootSetup.run("cat /proc/asound/pcm 2>/dev/null; "
                + "for f in /proc/asound/card*/pcm*/sub*/status; do grep -q RUNNING $f 2>/dev/null && { echo \"running: $f\"; cat ${f%status}hw_params; }; done; "
                + "for p in /proc/[0-9]*; do ls -l $p/fd 2>/dev/null | grep -q '/dev/snd/pcm' && echo \"$(cat $p/comm) ($(basename $p)): $(ls -l $p/fd 2>/dev/null | grep -o '/dev/snd/pcm[A-Za-z0-9]*' | sort -u | tr '\\n' ' ')\"; done; true")).append('\n');
        try { s.append(RootSetup.run(AlsaDirectAudio.quote(AlsaDirectAudio.install(c)) + " list 0")); } catch (Throwable t) { s.append("mixer: ").append(t); }
        return s.toString();
    }
    private static void kv(StringBuilder r, String k, String v) { r.append(String.format(Locale.US, "  %-28s %s\n", k, v == null ? "" : v.trim())); }
    private static boolean suPresent() { for (String p : new String[] { "/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk", "/debug_ramdisk/su" }) if (new File(p).exists()) return true; return false; }
    /** A report folder's whole report.txt ({@link #cat} stops at 200 lines; the summary and the clue lines sit far below that). */
    public static String readReport(File dir) {
        try { return new String(java.nio.file.Files.readAllBytes(new File(dir, "report.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8); } catch (Throwable t) { return ""; }
    }
    public static String cat(String path) { try (BufferedReader b = new BufferedReader(new InputStreamReader(new FileInputStream(path)))) { StringBuilder s = new StringBuilder(); String l; int n = 0; while ((l = b.readLine()) != null && n++ < 200) s.append(l).append('\n'); return s.toString(); } catch (Throwable t) { return ""; } }
    static String ls(String dir, Pattern filter) { File[] fs = dir == null ? null : new File(dir).listFiles(); if (fs == null) return "(not readable)"; StringBuilder s = new StringBuilder(); for (File f : fs) if (filter == null || filter.matcher(f.getName()).find()) s.append(f.getName()).append(' '); return s.length() == 0 ? "-" : s.toString(); }
    static String getprop(String k) { return exec("getprop " + k).trim(); }
    static String exec(String cmd) { try { Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start(); BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream())); StringBuilder s = new StringBuilder(); String l; int n = 0; while ((l = b.readLine()) != null && n++ < 400) s.append(l).append('\n'); p.waitFor(); return s.toString(); } catch (Throwable t) { return "(" + t.getMessage() + ")"; } }
    public static boolean copyFile(File src, File dst) { return copy(src, dst); }
    private static boolean copy(File src, File dst) { try (InputStream i = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) { byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); return true; } catch (Throwable t) { return false; } }
}
