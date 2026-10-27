/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The stock APK's firmware asset is read in-process, without a shell unzip. */
public class FirmwareVaultAssetTest {
    private static File apk(String... entries) throws Exception {
        File f = File.createTempFile("stock", ".apk"); f.deleteOnExit();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (String e : entries) { z.putNextEntry(new ZipEntry(e)); z.write(e.getBytes("UTF-8")); z.closeEntry(); }
        }
        return f;
    }

    @Test public void readsTheSingleDmrAsset() throws Exception {
        byte[] b = FirmwareVault.dmrAsset(apk("classes.dex", "assets/DMR003.UV4T.V022.bin", "res/raw/x.bin"));
        assertNotNull(b);
        assertEquals("assets/DMR003.UV4T.V022.bin", new String(b, "UTF-8"));
    }

    @Test public void noneOrTwoAssetsGiveNull() throws Exception {
        assertNull(FirmwareVault.dmrAsset(apk("classes.dex")));
        assertNull(FirmwareVault.dmrAsset(apk("assets/DMR003.A.bin", "assets/DMR003.B.bin")));
        assertNull(FirmwareVault.dmrAsset(new File("does-not-exist.apk")));
    }
}
