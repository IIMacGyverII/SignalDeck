package com.macdmr.radio;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** The op 6 variant 3 hold is sent only when it changes the module's state (FW57 channel 49 control, 2026-10-30). */
public class HoldTrackingTest {
    @Test public void twoAppliesSendOneFrame() {
        assertArrayEquals("first apply", new int[] { 1 }, RadioService.holdSteps(true, false, false));
        assertArrayEquals("second apply while held: nothing (it would save WORK_MODE 0x80)", new int[0], RadioService.holdSteps(true, true, false));
    }

    @Test public void aReleaseWithoutAHoldSendsNothing() {
        assertArrayEquals(new int[0], RadioService.holdSteps(false, false, false));
        assertArrayEquals(new int[0], RadioService.holdSteps(false, false, true));
        assertArrayEquals(new int[] { 0 }, RadioService.holdSteps(false, true, false));
    }

    @Test public void aRefreshAfterAChannelWriteReleasesFirst() {
        assertArrayEquals("held: release (restores the saved WORK_MODE), then apply (saves it again)", new int[] { 0, 1 }, RadioService.holdSteps(true, true, true));
        assertArrayEquals("not held: a plain apply", new int[] { 1 }, RadioService.holdSteps(true, false, true));
    }

    @Test public void afterARestartTheFirstRequestIsAPlainApply() {
        // a module boot, power-cycle or firmware load clears the flag (RadioService.booted, powerCycleOrGiveUp0, loadFirmware)
        boolean heldAfterBoot = false;
        assertArrayEquals(new int[] { 1 }, RadioService.holdSteps(true, heldAfterBoot, true));
        assertArrayEquals(new int[0], RadioService.holdSteps(false, heldAfterBoot, false));
    }
}
