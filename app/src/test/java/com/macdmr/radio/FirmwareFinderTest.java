/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

public class FirmwareFinderTest {
    @Test public void versionStringsInsideAnImage() {
        byte[] img = new byte[4096];
        byte[] a = "xxDMR006.TEST.V082\0".getBytes(), b = "ver0.5.4_470M_TONE".getBytes();
        System.arraycopy(a, 0, img, 100, a.length); System.arraycopy(b, 0, img, 900, b.length); System.arraycopy(a, 0, img, 2000, a.length);
        assertEquals("DMR006.TEST.V082, ver0.5.4_470M_TONE", FirmwareFinder.versionsIn(img));
        assertEquals("", FirmwareFinder.versionsIn(new byte[64]));
    }

    @Test public void onlyRadioImagesInSharedFolders() {
        assertTrue(FirmwareFinder.radioName("/vendor/firmware/dmr_module.bin"));
        assertTrue(FirmwareFinder.radioName("/vendor/firmware/MCU_20181221.bin"));
        assertFalse(FirmwareFinder.radioName("/vendor/firmware/WIFI_RAM_CODE_soc1_0_1_1.bin"));
        assertFalse(FirmwareFinder.radioName("/vendor/firmware/camera_ov13b10.bin"));
    }

    @Test public void imagesInsideAnApk() {
        assertTrue(FirmwareFinder.apkImage("assets/dmr/DMR003.bin"));
        assertTrue(FirmwareFinder.apkImage("res/raw/mcu.hex"));
        assertFalse(FirmwareFinder.apkImage("assets/web/index.html"));
        assertFalse(FirmwareFinder.apkImage("lib/arm64-v8a/libx.so"));
    }

    @Test public void copiedNamesAreSafeAndUnique() {
        java.util.Set<String> used = new java.util.HashSet<>();
        assertEquals("mcu.bin", FirmwareFinder.uniqueName(used, FirmwareFinder.safe("mcu.bin")));
        assertEquals("mcu_2.bin", FirmwareFinder.uniqueName(used, "mcu.bin"));
        assertEquals("a_b_c.bin", FirmwareFinder.safe("a b/c.bin"));
        assertEquals("00ff10", FirmwareFinder.hex(new byte[] { 0, (byte) 0xFF, 16 }));
    }

    /** A folder tree on the host standing in for the phone's (paths are the phone's, files live under {@code root}). */
    private static FirmwareFinder.Fs treeAt(java.io.File root) {
        return new FirmwareFinder.Fs() {
            @Override public java.util.List<String> files(String dir) {
                java.util.List<String> l = new java.util.ArrayList<>(); walk(new java.io.File(root, dir), dir, 0, l); java.util.Collections.sort(l); return l;
            }
            private void walk(java.io.File d, String path, int depth, java.util.List<String> l) {
                java.io.File[] fs = d.listFiles(); if (fs == null) return;
                for (java.io.File f : fs) { if (f.isFile()) l.add(path + "/" + f.getName()); else if (depth < 1) walk(f, path + "/" + f.getName(), depth + 1, l); }
            }
            @Override public long size(String path) { return new java.io.File(root, path).length(); }
            @Override public boolean copy(String path, java.io.File to) {
                try { java.nio.file.Files.copy(new java.io.File(root, path).toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); return true; } catch (Exception e) { return false; }
            }
        };
    }
    private static void put(java.io.File root, String path, int size, String version) throws Exception {
        java.io.File f = new java.io.File(root, path); f.getParentFile().mkdirs();
        byte[] b = new byte[size]; if (version != null) { byte[] v = version.getBytes("US-ASCII"); System.arraycopy(v, 0, b, size / 2, v.length); }
        java.nio.file.Files.write(f.toPath(), b);
    }

    @Test public void theAppsOwnFilesInSdcardDmrAreNotListed() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("fwfind-tree").toFile(), tmp = java.nio.file.Files.createTempDirectory("fwfind-tmp").toFile();
        try {
            put(root, "/sdcard/DMR/uart_logs/uart_20261026_0712.bin", 4096, "DMR003.UV4T.V022");          // a serial capture holding the version reply
            put(root, "/sdcard/DMR/uart_logs/big_capture.bin", 300 * 1024, "DMR003.UV4T.V022");          // even a big one
            put(root, "/sdcard/DMR/Audio/Ch1/rx.bin", 200 * 1024, "DMR003.UV4T.V022");
            put(root, "/sdcard/DMR/DMRDEBUG.bin", 2048, "DMR003.UV4T.V022");                               // too small to be an image
            put(root, "/sdcard/DMR/notes.bin", 200 * 1024, null);                                          // big, but no version inside
            put(root, "/sdcard/DMR/module_backup.txt", 200 * 1024, "DMR003.UV4T.V022");                   // not a .bin
            put(root, "/sdcard/DMR/MODULE_IMAGE.bin", 300 * 1024, "DMR003.UV4T.V022");                    // a module image put there by hand
            put(root, "/system/data/mcu/mcu_small.bin", 4096, null);                                       // the radio folders keep their own rules
            java.util.List<FirmwareFinder.Found> out = new java.util.ArrayList<>();
            FirmwareFinder.scanFolders(new String[] { "/sdcard/DMR", "/system/data/mcu" }, treeAt(root), tmp, s -> { }, out);
            java.util.List<String> got = new java.util.ArrayList<>(); for (FirmwareFinder.Found f : out) got.add(f.source);
            assertEquals("[/sdcard/DMR/MODULE_IMAGE.bin, /system/data/mcu/mcu_small.bin]", got.toString());
            assertEquals("DMR003.UV4T.V022", out.get(0).versions);
            assertEquals(64, out.get(0).sha256.length());
            java.io.File[] left = tmp.listFiles(); assertEquals("only the listed files keep a copy", 2, left == null ? 0 : left.length);
        } finally { deleteTree(root); deleteTree(tmp); }
    }

    @Test public void ownOutputFolders() {
        assertTrue(FirmwareFinder.ownOutput("/sdcard/DMR/uart_logs/x.bin"));
        assertTrue(FirmwareFinder.ownOutput("/sdcard/DMR/FoundFirmware/Ulefone/x.bin"));
        assertFalse(FirmwareFinder.ownOutput("/sdcard/DMR/x.bin"));
        assertFalse(FirmwareFinder.ownOutput("/sdcard/DMR/images/x.bin"));
        assertFalse(FirmwareFinder.ownOutput("/system/data/dmr/uart_logs/x.bin"));
    }

    @Test public void theAtomXlsUpperCaseFolderIsSearched() throws Exception {
        assertTrue(java.util.Arrays.asList(FirmwareFinder.FOLDERS).contains("/vendor/data/DMR"));
        java.io.File root = java.nio.file.Files.createTempDirectory("fwfind-tree").toFile(), tmp = java.nio.file.Files.createTempDirectory("fwfind-tmp").toFile();
        try {
            put(root, "/vendor/data/DMR/dmr/DMR006.TEST.V001.bin", 300 * 1024, "DMR006.TEST.V001");   // two levels down, as in the copy we were sent
            java.util.List<FirmwareFinder.Found> out = new java.util.ArrayList<>();
            FirmwareFinder.scanFolders(new String[] { "/vendor/data/DMR" }, treeAt(root), tmp, s -> { }, out);
            assertEquals(1, out.size());
            assertEquals("/vendor/data/DMR/dmr/DMR006.TEST.V001.bin", out.get(0).source);
            assertEquals("DMR006.TEST.V001", out.get(0).versions);
        } finally { deleteTree(root); deleteTree(tmp); }
    }

    private static String x32(String two) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 32; i++) b.append(two); return b.toString(); }

    private static java.util.List<com.macdmr.radio.fw.FirmwareCatalog.Image> catalogue() throws Exception {
        String json = "{\"format\":1,\"serial\":9,\"images\":["
                + "{\"tag\":\"DMR009.TEST.V001\",\"sha256\":\"" + x32("ab") + "\",\"size\":1,\"model\":\"Test Phone\",\"status\":\"stock\",\"factory\":false},"
                + "{\"tag\":\"DMR009.TEST.V002\",\"sha256\":\"" + x32("cd") + "\",\"size\":1,\"model\":\"Test Phone\",\"status\":\"stock\",\"factory\":true},"
                + "{\"tag\":\"DMR009.FW01.V002\",\"sha256\":\"" + x32("ef") + "\",\"size\":1,\"model\":\"Test Phone\",\"status\":\"released\",\"factory\":false}]}";
        return com.macdmr.radio.fw.FirmwareCatalog.parse(json.getBytes("UTF-8")).images;
    }

    @Test public void knownImagesAreNamed() throws Exception {
        java.util.List<com.macdmr.radio.fw.FirmwareCatalog.Image> cat = catalogue();
        assertEquals("DMR003.UV4T.V022, the factory image for the Armor 26 Ultra", FirmwareFinder.known(com.macdmr.radio.fw.ImageTable.FACTORY.sha256, cat));
        assertEquals("DMR003.UV4T.V022, the factory image for the Armor 26 Ultra", FirmwareFinder.known(com.macdmr.radio.fw.ImageTable.FACTORY.sha256, null));
        assertTrue(FirmwareFinder.known(com.macdmr.radio.fw.ImageTable.FW22.sha256, null).startsWith("DMR003.FW22.V022, a SignalDeck bench image"));
        assertEquals("DMR009.TEST.V001, a stock copy for the Test Phone kept for research (not offered for loading)", FirmwareFinder.known(x32("AB"), cat));
        assertEquals("DMR009.TEST.V002, the factory image for the Test Phone", FirmwareFinder.known(x32("cd"), cat));
        assertEquals("DMR009.FW01.V002, a released image for the Test Phone", FirmwareFinder.known(x32("ef"), cat));
        assertEquals("", FirmwareFinder.known(x32("01"), cat));
        assertEquals("", FirmwareFinder.known("(unreadable)", cat));
    }

    @Test public void theReportMarksKnownFiles() throws Exception {
        FirmwareFinder.Found a = new FirmwareFinder.Found("/vendor/data/DMR/dmr/x.bin", 10), b = new FirmwareFinder.Found("/vendor/data/mcu/y.bin", 10);
        a.sha256 = x32("ab"); b.sha256 = x32("01");
        java.util.List<FirmwareFinder.Found> fs = java.util.Arrays.asList(a, b);
        FirmwareFinder.markKnown(fs, catalogue());
        String r = FirmwareFinder.describe(fs);
        assertTrue(r, r.contains("x.bin  10 bytes  sha256 " + x32("ab") + "  already known: DMR009.TEST.V001, a stock copy"));
        assertFalse(r, r.contains("y.bin  10 bytes  sha256 " + x32("01") + "  already known"));
        assertTrue(r, r.contains("nobody needs a copy of those"));
        b.known = ""; a.known = "";
        assertFalse(FirmwareFinder.describe(fs).contains("nobody needs a copy"));
        assertFalse(FirmwareFinder.describe(fs).contains("by itself"));
    }

    @Test public void filesTheStockAppWouldLoadAreFlagged() {
        FirmwareFinder.Found a = new FirmwareFinder.Found("/sdcard/intercom/dmr/DMR006.U4A.V099.bin", 10);
        a.sha256 = x32("01");
        String r = FirmwareFinder.describe(java.util.Collections.singletonList(a));
        assertTrue(r, r.contains("loads a .bin from /sdcard/intercom/dmr/ into the radio module by itself"));
        assertTrue(FirmwareFinder.autoFlash("/storage/self/primary/intercom/dmr/x.bin"));
        assertFalse(FirmwareFinder.autoFlash("/sdcard/intercom/mcu/x.bin"));
        assertFalse(FirmwareFinder.autoFlash("/vendor/data/dmr/x.bin"));
    }

    private static void deleteTree(java.io.File f) { java.io.File[] k = f.listFiles(); if (k != null) for (java.io.File c : k) deleteTree(c); f.delete(); }

    @Test public void noStockApkEntryNamesThePackageAndReadsAsUnreadable() {
        FirmwareFinder.Found f = FirmwareFinder.noStockApk("com.pri.prizeinterphone");
        assertEquals(0, f.size);
        assertTrue(f.source.startsWith("com.pri.prizeinterphone!"));
        assertTrue(f.line().contains("APK not found"));
        assertTrue(FirmwareFinder.noStockApk("").source.startsWith("stock app!"));
    }

    @Test public void aReleasedMinImageIsNamedForWhatThisBuildMayDo() throws Exception {
        String sha = "b".repeat(64);
        String json = "{\"serial\":9,\"images\":[{\"tag\":\"DMR003.FW57.V022\",\"sha256\":\"" + sha + "\",\"model\":\"Armor 26 Ultra\",\"status\":\"released-min\",\"minAppVersionCode\":121}]}";
        java.util.List<com.macdmr.radio.fw.FirmwareCatalog.Image> cat =
                com.macdmr.radio.fw.FirmwareCatalog.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).images;
        org.junit.Assert.assertEquals("DMR003.FW57.V022, a released image for the Armor 26 Ultra", FirmwareFinder.known(sha, cat, 121));
        String older = FirmwareFinder.known(sha, cat, 120);
        org.junit.Assert.assertTrue(older, older.contains("needs a newer SignalDeck (version code 121 or newer)"));
        org.junit.Assert.assertFalse(older, older.contains("kept for research"));
    }
}
