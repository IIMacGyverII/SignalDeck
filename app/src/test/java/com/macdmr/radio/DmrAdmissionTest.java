/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** The app's DMR admission (FW57 / factory: an open speaker passes every talkgroup, so the app decides what plays). */
public class DmrAdmissionTest {
    private static final int[] LIST = { 1, 9 };

    @Test public void talkgroupsInTheListPlay() {
        assertTrue(RadioService.dmrAdmits(1, 9, LIST, 1234567, 0));
        assertTrue(RadioService.dmrAdmits(1, 1, LIST, 1234567, 0));
    }

    @Test public void talkgroupsOutsideTheListStayMuted() {
        assertFalse(RadioService.dmrAdmits(1, 99, LIST, 1234567, 0));
        assertFalse(RadioService.dmrAdmits(1, 0, LIST, 1234567, 0));
        assertFalse(RadioService.dmrAdmits(1, 9, null, 1234567, 0));
    }

    @Test public void allCallPlays() {
        assertTrue(RadioService.dmrAdmits(2, 16777215, LIST, 1234567, 0));
        assertTrue(RadioService.dmrAdmits(1, 16777215, LIST, 1234567, 0));   // a group call to the all-call ID
    }

    @Test public void privateCallsOnlyToThisRadio() {
        assertTrue(RadioService.dmrAdmits(0, 1234567, LIST, 1234567, 0));
        assertTrue(RadioService.dmrAdmits(0, 7654321, LIST, 1234567, 7654321));   // the VFO's own ID
        assertFalse(RadioService.dmrAdmits(0, 2345678, LIST, 1234567, 0));
    }

    @Test public void onlyTheArmor26UltraUsesAppAdmission() {
        assertTrue(DeviceProfile.ARMOR_26_ULTRA.dmrAppAdmission);
        for (DeviceProfile p : new DeviceProfile[] { DeviceProfile.ARMOR_20WT, DeviceProfile.XPLORE_1, DeviceProfile.ATOM_XL, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.ARMOR_3WT, DeviceProfile.UNKNOWN })
            assertFalse(p.name, p.dmrAppAdmission);
        assertTrue(DeviceProfile.ARMOR_26_ULTRA.copy(true, true).dmrAppAdmission);
    }
}
