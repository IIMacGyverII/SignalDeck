/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.fw;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import org.junit.Test;

public class FirmwareCatalogKeyTest {
    private static KeyPair pair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); return g.generateKeyPair();
    }
    private static String pub(KeyPair k) { return Base64.getEncoder().encodeToString(k.getPublic().getEncoded()); }
    private static String sign(byte[] data, KeyPair k) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(k.getPrivate()); s.update(data); return Base64.getEncoder().encodeToString(s.sign());
    }
    private static byte[] doc(int serial) { return ("{\"serial\":" + serial + ",\"images\":[]}").getBytes(StandardCharsets.UTF_8); }

    @Test public void serialSixAndLaterNeedTheFirmwareKey() throws Exception {
        KeyPair profile = pair(), firmware = pair();
        byte[] six = doc(6);
        assertNotNull("serial 6 signed with the firmware key", FirmwareCatalog.accept(six, sign(six, firmware), 0, pub(profile), pub(firmware)));
        assertNull("serial 6 signed with the profile key is refused", FirmwareCatalog.accept(six, sign(six, profile), 0, pub(profile), pub(firmware)));
    }

    @Test public void earlierSerialsStillVerifyWithTheProfileKey() throws Exception {
        KeyPair profile = pair(), firmware = pair();
        byte[] five = doc(5);
        assertNotNull("a cached serial 5 still loads", FirmwareCatalog.accept(five, sign(five, profile), 0, pub(profile), pub(firmware)));
        assertNull("serial 5 signed with the firmware key is refused", FirmwareCatalog.accept(five, sign(five, firmware), 0, pub(profile), pub(firmware)));
    }

    @Test public void theShippedFirmwareKeyIsTheRepositorysKey() {
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEm8EYMfAtrrQnIqRzAPjH2ZdZjKm9z8SnrVQep042zlGbaxPomUz8qw7ref7vBOBY/hB1UrBNyElaF6SqOydVcw==", FirmwareCatalog.FIRMWARE_PUBLIC_KEY);
        assertNotEquals(com.macdmr.radio.ProfileRegistry.PUBLIC_KEY, FirmwareCatalog.FIRMWARE_PUBLIC_KEY);
    }
}
