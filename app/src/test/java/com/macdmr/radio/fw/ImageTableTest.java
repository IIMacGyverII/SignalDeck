/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import static org.junit.Assert.*;

import org.junit.Test;

public class ImageTableTest {
    private static String ok() {
        return ImageTable.refuse(ImageTable.FW22, ImageTable.FACTORY.tag, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, ImageTable.FACTORY.stockVersion, true, true);
    }

    @Test public void aKnownPhoneMayLoadFw22() { assertNull(ok()); }

    @Test public void anAlteredIdentityIsRefused() {
        assertNotNull(ImageTable.refuse(ImageTable.FW22, ImageTable.FACTORY.tag, "deadbeef", ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", false, true));
    }

    @Test public void stockRemovedWithNoBackupIsRefused() {
        String why = ImageTable.refuse(ImageTable.FACTORY, "", null, null, "Armor 26 Ultra",
                ImageTable.FACTORY.stockPackage, "V1.0", false, false);
        assertNotNull(why);
        assertTrue(why.contains("stock app is gone"));
    }

    @Test public void aSilentModuleRecoversOnlyTheFactoryImage() {
        assertNotNull(ImageTable.refuse(ImageTable.FW22, "", ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", true, true));
        assertNull(ImageTable.refuse(ImageTable.FACTORY, "", ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", true, true));
    }

    @Test public void anUnknownTagIsRefused() {
        assertNotNull(ImageTable.refuse(ImageTable.FW22, "DMR003.NOPE.V022", ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", true, true));
        assertNotNull(ImageTable.refuse(ImageTable.FACTORY, "DMR003.NOPE.V022", ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", false, true));
    }

    @Test public void aVerifiedBackupMayRestoreFactoryOverAnUnknownTag() {
        assertNull(ImageTable.refuse(ImageTable.FACTORY, "DMR003.UNL1.V022", ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V1.0", true, true));
    }

    @Test public void aDifferentStockAppVersionWarnsWhenTheFileMatches() {
        assertNull(ImageTable.refuse(ImageTable.FW22, ImageTable.FACTORY.tag, ImageTable.FACTORY.sha256, ImageTable.FACTORY.tag,
                "Armor 26 Ultra", ImageTable.FACTORY.stockPackage, "V9.9", true, true));
        String warn = ImageTable.versionWarning(ImageTable.FACTORY.sha256, "V9.9");
        assertNotNull(warn);
        assertTrue(warn.contains("V9.9"));
        assertNull(ImageTable.versionWarning("deadbeef", "V9.9"));
    }

    @Test public void anUnlistedFileNeedsTheFactoryBackup() {
        assertNotNull(ImageTable.unlistedPolicy(ImageTable.FW22.tag, false, "Armor 26 Ultra", ImageTable.FACTORY.stockPackage));
        assertNull(ImageTable.unlistedPolicy(ImageTable.FW22.tag, true, "Armor 26 Ultra", ImageTable.FACTORY.stockPackage));
        assertNotNull(ImageTable.unlistedPolicy("DMR003.NOPE.V022", true, "Armor 26 Ultra", ImageTable.FACTORY.stockPackage));
    }

    @Test public void theTagSitsAtTheKnownOffset() {
        byte[] image = new byte[ImageTable.TAG_AT + 16];
        byte[] tag = "DMR003.UV4T.V022".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, image, ImageTable.TAG_AT, tag.length);
        assertEquals("DMR003.UV4T.V022", ImageTable.tagOf(image));
    }

    @Test public void loaderModelUsesTheVendorModelOnAGsi() {
        assertEquals("Armor 26 Ultra", ImageTable.loaderModel("Armor 26 Ultra", "Armor 26 Ultra"));
        assertEquals("Armor 26 Ultra", ImageTable.loaderModel("TrebleDroid vanilla", "Armor 26 Ultra"));
        assertEquals("TrebleDroid vanilla", ImageTable.loaderModel("TrebleDroid vanilla", ""));
        assertEquals("TrebleDroid vanilla", ImageTable.loaderModel("TrebleDroid vanilla", null));
        assertEquals("Atom_XL", ImageTable.loaderModel("Atom_XL", "Some Other Phone"));
        assertFalse(ImageTable.modelMatches(ImageTable.loaderModel("TrebleDroid vanilla", "Armor 20WT")));
    }
}
