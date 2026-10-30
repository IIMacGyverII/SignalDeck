package com.macdmr.radio.fw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The pre-flight accepts the catalogue images this build is offered, both as the file and as the running image (step (d), 2026-10-30). */
public class CatalogPreflightTest {
    private static final String F = ImageTable.FACTORY.tag, PKG = ImageTable.FACTORY.stockPackage, MODEL = "Armor 26 Ultra";
    private static final String SHA57 = "5f16ef683ce9" + "0".repeat(52), SHA58 = "a1" + "0".repeat(62);

    private static FirmwareCatalog.Image released(String tag, String sha, int min) {
        FirmwareCatalog.Image im = new FirmwareCatalog.Image(tag, sha, 378620, "Released image.", F, "Armor 26 Ultra", PKG, "V1.0",
                "https://example.invalid/" + tag + ".bin", "released-min", "armor26ultra", false, false);
        im.minAppVersionCode = min;
        return im;
    }
    private static final List<FirmwareCatalog.Image> CATALOGUE = Arrays.asList(
            released("DMR003.FW57.V022", SHA57, 121), released("DMR003.FW58.V022", SHA58, 121));

    private static String refuse(ImageTable.Row image, String running, List<ImageTable.Row> known) {
        return ImageTable.refuse(image, running, ImageTable.FACTORY.sha256, F, MODEL, PKG, "V1.0", true, true, known);
    }
    private static ImageTable.Row row(List<ImageTable.Row> known, String sha) { return ImageTable.bySha(sha, known); }

    @Test public void aReleasedMinImageLoadsFromTheFactoryImage() {
        List<ImageTable.Row> known = FirmwareCatalog.rows(CATALOGUE, 121);
        ImageTable.Row fw57 = row(known, SHA57);
        assertNotNull("offered to versionCode 121", fw57);
        assertEquals(F, fw57.baseTag);
        assertNull(refuse(fw57, F, known));
    }

    @Test public void notOfferedToAnOlderBuild() {
        List<ImageTable.Row> known = FirmwareCatalog.rows(CATALOGUE, 120);
        assertNull("not offered to versionCode 120", row(known, SHA57));
        ImageTable.Row fw57 = row(FirmwareCatalog.rows(CATALOGUE, 121), SHA57);
        assertEquals("That file is not a known module image", refuse(fw57, F, known));
        assertEquals("the built-in list alone still refuses it", "That file is not a known module image",
                ImageTable.refuse(fw57, F, ImageTable.FACTORY.sha256, F, MODEL, PKG, "V1.0", true, true));
    }

    @Test public void whileACatalogueImageRuns() {
        List<ImageTable.Row> known = FirmwareCatalog.rows(CATALOGUE, 121);
        assertNull("restore the factory image", refuse(ImageTable.FACTORY, "DMR003.FW57.V022", known));
        assertNull("another released image on the same base", refuse(row(known, SHA58), "DMR003.FW57.V022", known));
        assertNull("an unlisted file is judged with the running image known", ImageTable.unlistedPolicy("DMR003.FW57.V022", true, MODEL, PKG, known));
        assertTrue("an older build does not know the running image", refuse(row(known, SHA58), "DMR003.FW57.V022", FirmwareCatalog.rows(CATALOGUE, 120)) != null);
        assertNull("but it can still restore the factory image", refuse(ImageTable.FACTORY, "DMR003.FW57.V022", FirmwareCatalog.rows(CATALOGUE, 120)));
    }

    @Test public void anUnknownHashIsStillRefused() {
        List<ImageTable.Row> known = FirmwareCatalog.rows(CATALOGUE, 121);
        ImageTable.Row stranger = new ImageTable.Row("DMR003.FW99.V022", "b2" + "0".repeat(62), 378620, "?", F, MODEL, PKG, "V1.0", false, false);
        assertEquals("That file is not a known module image", refuse(stranger, F, known));
    }
}
