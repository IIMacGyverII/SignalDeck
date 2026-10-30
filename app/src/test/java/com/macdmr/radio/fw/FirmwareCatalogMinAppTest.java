package com.macdmr.radio.fw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FirmwareCatalogMinAppTest {
    @Test public void releasedMinIsOfferedOnlyToNewEnoughApps() {
        assertTrue(FirmwareCatalog.offered("released", 0, 120));
        assertTrue(FirmwareCatalog.offered("stock", 0, 1));
        assertFalse("beta.21 (120) must not see an image that needs beta.22 (121)", FirmwareCatalog.offered("released-min", 121, 120));
        assertTrue(FirmwareCatalog.offered("released-min", 121, 121));
        assertFalse("a released-min row without a minimum is not offered", FirmwareCatalog.offered("released-min", 0, 200));
        assertFalse(FirmwareCatalog.offered("bench", 0, 200));
    }

    @Test public void theMinimumIsReadFromTheRow() throws Exception {
        String json = "{\"serial\":9,\"images\":[{\"tag\":\"DMR003.FW57.V022\",\"sha256\":\"" + "a".repeat(64) + "\",\"status\":\"released-min\",\"minAppVersionCode\":121}]}";
        FirmwareCatalog.Doc d = FirmwareCatalog.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(121, d.images.get(0).minAppVersionCode);
    }
}
