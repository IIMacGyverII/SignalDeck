/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Before FW31 the channel is rewritten after a symbol key; from FW31 the firmware unmutes itself. */
public class SymUnmuteTest {
    @Test public void rewriteOnlyBeforeFw31() {
        assertTrue(RadioService.rewriteAfterSym("DMR003.FW26.V022"));
        assertTrue(RadioService.rewriteAfterSym("DMR003.FW30.V022"));
        assertTrue("unnumbered bench tag", RadioService.rewriteAfterSym("DMR003.F30C.V022"));
        assertFalse(RadioService.rewriteAfterSym("DMR003.FW31.V022"));
        assertFalse(RadioService.rewriteAfterSym("DMR003.FW32.V022"));
        assertFalse(RadioService.rewriteAfterSym("DMR003.FW36.V022"));
        assertTrue("FW37+: the firmware restores the key state; unkey and one write again", RadioService.rewriteAfterSym("DMR003.FW37.V022"));
        assertTrue(RadioService.rewriteAfterSym("DMR003.FW38.V022"));
        assertTrue("stock", RadioService.rewriteAfterSym("DMR003.UV4T.V022"));
    }
}
