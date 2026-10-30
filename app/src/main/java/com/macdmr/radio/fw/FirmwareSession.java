/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import com.macdmr.radio.ModuleEngine;
import com.macdmr.radio.RadioEngine;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.RootSetup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One firmware load: pre-flight, the YModem sender, the tag check, and a factory restore when the tag is wrong. */
public final class FirmwareSession {
    public static final class Check {
        public final String name, detail;
        public final boolean ok, warn;
        public Check(String name, boolean ok, String detail) { this(name, ok, detail, false); }
        public Check(String name, boolean ok, String detail, boolean warn) { this.name = name; this.ok = ok; this.detail = detail; this.warn = warn; }
    }

    public interface Ui {
        void phase(String name);
        void log(String line);
        void block(int done, int total, long ackMs, int retries);
        void checks(List<Check> checks);
        void finished(String line);
    }

    private FirmwareSession() { }

    /** The pre-flight list, safe to show before a load. A null image fails the hash check. */
    public static List<Check> inspect(RadioService svc, ImageTable.Row row, byte[] image) {
        Context c = svc.getApplicationContext();
        FirmwareVault.Copy backup = FirmwareVault.factory(c);
        String module = svc.moduleVersion() == null ? "" : svc.moduleVersion().trim();
        boolean stockOn = RootSetup.stockAppEnabled(svc.profile());
        boolean stockThere = FirmwareVault.stockInstalled(c, svc.profile().stockApp);
        String pkgVer = FirmwareVault.stockVersion(c, svc.profile().stockApp);
        int pct = batteryPercent(c);
        boolean charging = batteryCharging(c);
        boolean fileOk = image != null && row != null && image.length == row.size && row.sha256.equals(FirmwareVault.sha256(image)) && row.tag.equals(ImageTable.tagOf(image));
        boolean busy = svc.isTransmitting() || svc.scanning() || svc.m17Running() || svc.ft8Running() || svc.packetActive() || svc.scope().running();
        String policy;
        if (!fileOk) policy = "The file does not match the known image";
        else if (row.unlisted && !Unlisted.allowed(c)) policy = "That file is not a known module image";
        else if (row.unlisted) policy = ImageTable.unlistedPolicy(module, backup.verified, android.os.Build.MODEL, svc.profile().stockApp, FirmwareCatalog.rows(c));
        else policy = ImageTable.refuse(row, module, backup.sha256, backup.tag, android.os.Build.MODEL, svc.profile().stockApp, pkgVer, backup.verified, stockThere, FirmwareCatalog.rows(c));
        if (policy == null) {
            String variant = FirmwareCatalog.variantRefusal(c, android.os.Build.MODEL, svc.profile().stockApp);
            if (variant != null) policy = variant;
        }
        String versionNote = ImageTable.versionWarning(backup.sha256, pkgVer);
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Module answers", !module.isEmpty() || (row != null && row.factory), module.isEmpty() ? "No version yet" : module));
        checks.add(new Check("Radio is idle", !busy, busy ? "Stop the transmission, scan or data mode first" : "Idle"));
        checks.add(new Check("Stock app paused", !stockOn, stockOn ? "Take over the radio first" : "Paused"));
        checks.add(new Check("Battery", (pct > 40 && charging) || pct > 60, pct + "% " + (charging ? "charging" : "on battery")));
        checks.add(new Check("Image hash", fileOk, fileOk ? row.sha256.substring(0, 12) : "The file does not match the known image"));
        checks.add(new Check("Factory backup", backup.verified, backup.verified ? backup.source : "Missing or it does not match"));
        checks.add(new Check("This phone", policy == null, policy == null ? svc.profile().name + (versionNote == null ? "" : " · " + versionNote) : policy, versionNote != null && policy == null));
        return checks;
    }

    public static void run(RadioService svc, ImageTable.Row row, byte[] image, Ui ui, AtomicBoolean halt, boolean treatTagAsWrong) {
        ui.phase("preparing");
        Context c = svc.getApplicationContext();
        List<Check> checks = inspect(svc, row, image);
        ui.checks(checks);
        String why = null;
        for (Check check : checks) if (!check.ok) { why = check.detail; break; }
        if (why != null) { ui.finished(why); return; }
        FirmwareVault.Copy backup = FirmwareVault.factory(c);
        PowerManager.WakeLock lock = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signaldeck:firmware");
        lock.acquire(20 * 60 * 1000L);
        try {
            if (row.unlisted) Unlisted.allow(c, false);
            String got = send(svc, row, image, ui, halt);
            if (got == null) return;
            if (treatTagAsWrong) got = "DMR003.NOPE.V022";
            ui.phase("verify");
            if (!row.tag.equals(got)) {
                ui.log("tag " + got + " is not " + row.tag);
                svc.history("fw", "Firmware load mismatch: wanted " + row.tag + ", module said " + got + ". Restoring the factory image.");
                if (row.factory || backup.bytes == null) { ui.finished("The module answered " + (got.isEmpty() ? "nothing" : got) + " and the factory copy is not here"); return; }
                ui.log("restoring the factory image");
                String back = send(svc, ImageTable.FACTORY, backup.bytes, ui, new AtomicBoolean(false));
                ui.finished(ImageTable.FACTORY.tag.equals(back) ? "Restored the factory image (" + back + ") because the tag did not match" : "Factory restore failed (" + back + ")");
                return;
            }
            if (row.readBack) {
                String sample = sample(svc, image, ui);
                if (sample != null) {
                    svc.history("fw", "Firmware " + got + " read-back failed. Restoring the factory image.");
                    String back = send(svc, ImageTable.FACTORY, backup.bytes, ui, new AtomicBoolean(false));
                    ui.finished(sample + (ImageTable.FACTORY.tag.equals(back) ? " Factory image restored." : " Factory restore failed."));
                    return;
                }
            }
            if (row.unlisted) {
                svc.history("fw", "unlisted image " + row.sha256);
                svc.log("unlisted image " + row.sha256);
            } else svc.history("fw", "Firmware loaded: " + got);
            ui.finished(got + (row.readBack ? " · flash sample matched" : ""));
        } finally {
            if (lock.isHeld()) lock.release();
        }
    }

    /** @return the tag, or null when the transfer itself failed (the UI was already told) */
    private static String send(RadioService svc, ImageTable.Row row, byte[] image, Ui ui, AtomicBoolean halt) {
        ModuleEngine engine = svc.engine();
        AtomicInteger retries = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        boolean diverted = false;
        try {
            if (!engine.isOpen()) engine.open();
            engine.startDivert();
            diverted = true;
            final boolean[] still = { true };
            YModemSender.Result r = new YModemSender(null, ui::log).watch(new YModemSender.Watch() {
                public void phase(String name) { ui.phase(name); }
                public void block(int done, int total, long ackMs) { ui.block(done, total, ackMs, retries.get()); }
                public void retry() { retries.incrementAndGet(); }
            }).halt(halt::get).send(image, row.tag + ".bin", engine.powerNode(), engine.pttNode(),
                    new YModemSender.Wire() {
                        public void write(byte[] b) throws IOException { engine.writeRaw(b); }
                        public int read(int ms) throws IOException { return engine.readRaw(ms); }
                        public void close() { engine.close(); }
                    },
                    (node, high) -> com.macdmr.radio.SysfsGpio.write(node, high ? "1" : "0"),
                    false,
                    () -> { engine.stopDivert(); still[0] = false; ui.phase("verify"); return svc.readModuleTag(); });
            diverted = still[0];
            ui.log("result " + r + " in " + ((System.currentTimeMillis() - t0) / 1000) + " s");
            if (!r.ok()) { ui.finished(r.toString()); return null; }
            return r.detail == null ? "" : r.detail.trim();
        } catch (Exception e) {
            ui.log("io-error " + e.getMessage());
            ui.finished("io-error " + e.getMessage());
            return null;
        } finally {
            if (diverted) engine.stopDivert();
            try { if (!engine.isOpen()) engine.open(); } catch (IOException e) { ui.log("reopen " + e.getMessage()); }
        }
    }

    /** @return a failure sentence, or null when the first 4 KiB match */
    private static String sample(RadioService svc, byte[] image, Ui ui) {
        ui.log("reading the first 4 KiB back");
        int off = 0;
        try {
            while (off < 4096) {
                int n = Math.min(240, 4096 - off);
                byte[] body = new byte[7];
                body[0] = 12;
                body[1] = (byte) off; body[2] = (byte) (off >> 8); body[3] = (byte) (off >> 16); body[4] = (byte) (off >> 24);
                body[5] = (byte) n; body[6] = (byte) (n >> 8);
                com.macdmr.radio.Frame pic = svc.engine().diag40(body, 800);
                if (pic == null || pic.sr != 0 || pic.body == null || pic.body.length < n) return "Flash read failed at " + off;
                for (int i = 0; i < n; i++) if (pic.body[i] != image[off + i]) return "Flash sample differs at byte " + (off + i);
                off += n;
            }
        } catch (IOException e) { return "Flash read failed: " + e.getMessage(); }
        ui.log("4 KiB sample matched");
        return null;
    }

    private static int batteryPercent(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return 0;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return scale <= 0 ? 0 : level * 100 / scale;
    }

    private static boolean batteryCharging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int s = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
    }
}
