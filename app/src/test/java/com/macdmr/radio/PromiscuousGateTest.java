package com.macdmr.radio;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PromiscuousGateTest {
    @Test public void noImageSendsThePromiscuousFlagUntilOneIsProven() {
        assertFalse("FW57 with a DMR channel", RadioService.promiscuousCapable(true, "DMR003.FW57.V022"));
        assertFalse("FW26, the first image that answered op 15", RadioService.promiscuousCapable(true, "DMR003.FW26.V022"));
        assertFalse("stock firmware", RadioService.promiscuousCapable(true, "DMR003.UV4T.V022"));
        assertFalse("analog channel", RadioService.promiscuousCapable(false, "DMR003.FW57.V022"));
    }
}
