/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.ui;

import static org.junit.Assert.*;

import com.macdmr.radio.fw.ImageTable;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Bench images are listed only on the bench: their file is on the phone, or the module runs them. */
public class FirmwareScreenRowsTest {
    @Test public void factoryAlwaysBenchOnlyWithItsFileOrRunning() {
        Set<String> none = Collections.emptySet();
        assertTrue(FirmwareScreen.showRow(ImageTable.FACTORY, none, "DMR003.UV4T.V022"));
        assertFalse(FirmwareScreen.showRow(ImageTable.FW22, none, "DMR003.UV4T.V022"));
        Set<String> have = new HashSet<>(); have.add(ImageTable.FW22.tag);
        assertTrue(FirmwareScreen.showRow(ImageTable.FW22, have, "DMR003.UV4T.V022"));
        assertTrue(FirmwareScreen.showRow(ImageTable.FW22, none, ImageTable.FW22.tag));
    }
}
