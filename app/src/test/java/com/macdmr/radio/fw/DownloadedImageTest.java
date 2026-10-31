package com.macdmr.radio.fw;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** A downloaded catalogue image is kept only when size, sha256 and the version string inside it match the signed row. */
public class DownloadedImageTest {
    private static byte[] image(String tag) {
        byte[] b = new byte[378620];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31);
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, b, ImageTable.TAG_AT, t.length);
        for (int i = t.length; i < 16; i++) b[ImageTable.TAG_AT + i] = 0;
        return b;
    }

    @Test public void matchesOnlyTheSignedRow() {
        byte[] b = image("DMR003.FW57.V022");
        String sha = FirmwareVault.sha256(b);
        assertTrue(FirmwareVault.matches(b, "DMR003.FW57.V022", sha, b.length));
        assertFalse("wrong size", FirmwareVault.matches(b, "DMR003.FW57.V022", sha, b.length + 1));
        assertFalse("wrong hash", FirmwareVault.matches(b, "DMR003.FW57.V022", "0".repeat(64), b.length));
        assertFalse("wrong version string", FirmwareVault.matches(b, "DMR003.FW58.V022", sha, b.length));
        byte[] t = b.clone(); t[100] ^= 1;
        assertFalse("one byte changed", FirmwareVault.matches(t, "DMR003.FW57.V022", sha, b.length));
        assertFalse("nothing arrived", FirmwareVault.matches(null, "DMR003.FW57.V022", sha, b.length));
    }
}
