package com.macdmr.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DataHoldTest {
    @Test public void theHoldIsNeverAppliedOnADmrChannel() {
        assertFalse("the hold's WORK_MODE 0x80 takes the sync off a DMR key", RadioService.holdAllowed(true, true));
        assertTrue(RadioService.holdAllowed(true, false));     // analog or M17 channel
        assertTrue(RadioService.holdAllowed(false, true));     // a release is always allowed
        assertTrue(RadioService.holdAllowed(false, false));
    }
}
