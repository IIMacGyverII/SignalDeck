/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** Restricted mode's policy and PIN, as pure objects. */
public class RestrictionTest {
    @Test public void noRestrictionAllowsEverything() {
        Restriction r = Restriction.none();
        assertTrue(r.isEmpty());
        assertTrue(r.zoneVisible("gmrs")); assertTrue(r.zoneVisible(null));
        assertTrue(r.mayTransmit("anything")); assertTrue(r.modeAllowed("ft8")); assertTrue(r.adminAllowed("firmware"));
    }

    @Test public void limitedZonesHideOthersAndTransmitFollows() {
        Restriction r = new Restriction(false, Restriction.set("gmrs", "frs"), Restriction.TX_VISIBLE, null, null);
        assertTrue(r.zoneVisible("gmrs")); assertFalse(r.zoneVisible("ham70")); assertFalse(r.zoneVisible(null));
        assertTrue(r.mayTransmit("frs")); assertFalse(r.mayTransmit("ham70")); assertFalse(r.mayTransmit(""));
        assertFalse(r.isEmpty());
    }

    @Test public void receiveOnlyRefusesEveryTransmitAndHidesModes() {
        Restriction r = Restriction.receiveOnly(true, null);
        assertTrue(r.zoneVisible("any")); assertFalse(r.mayTransmit("any"));
        for (String m : Restriction.MODES) assertFalse(m, r.modeAllowed(m));
        for (String a : Restriction.ADMIN) assertFalse(a, r.adminAllowed(a));
    }

    @Test public void simpleRadioKeepsTalkOnVisibleZonesOnly() {
        Restriction r = Restriction.simpleRadio(false, Restriction.set("gmrs"));
        assertTrue(r.mayTransmit("gmrs")); assertFalse(r.mayTransmit("ham2m"));
        assertFalse(r.modeAllowed("aprs")); assertFalse(r.adminAllowed("editChannels"));
        assertTrue(r.adminAllowed("web")); assertTrue(r.adminAllowed("exit"));
    }

    @Test public void jsonRoundTripAndBrokenJson() {
        Restriction r = new Restriction(false, Restriction.set("z1", "z2"), Restriction.TX_NONE, Restriction.set("ft8", "m17"), Restriction.set("firmware"));
        Restriction back = Restriction.fromJson(r.toJson());
        assertEquals(r.toJson(), back.toJson());
        assertFalse(back.allZones); assertEquals(Restriction.TX_NONE, back.transmit);
        assertTrue(back.hiddenModes.contains("m17")); assertTrue(back.hiddenAdmin.contains("firmware"));
        assertTrue(Restriction.fromJson("not json").isEmpty());
        assertTrue(Restriction.fromJson(null).isEmpty());
        assertEquals(Restriction.TX_ALL, new Restriction(true, null, 9, null, null).transmit);   // out of range falls back
    }

    @Test public void pinSetCheckTriesAndWait() {
        assertFalse(RestrictionLock.validPin("123")); assertFalse(RestrictionLock.validPin("12a4")); assertTrue(RestrictionLock.validPin("2468"));
        RestrictionLock.State st = RestrictionLock.setPin("2468");
        assertTrue(st.hasPin()); assertNotEquals("", st.salt); assertNotEquals("2468", st.hash);
        long t = 1_000_000L;
        assertEquals(RestrictionLock.Try.OK, RestrictionLock.check(st, "2468", t));
        for (int i = 0; i < 4; i++) assertEquals(RestrictionLock.Try.WRONG, RestrictionLock.check(st, "0000", t));
        assertEquals(RestrictionLock.Try.WAIT, RestrictionLock.check(st, "0000", t));          // the fifth wrong try starts the wait
        assertEquals(RestrictionLock.Try.WAIT, RestrictionLock.check(st, "2468", t + 1000));    // even the right PIN waits
        assertEquals(RestrictionLock.WAIT_MS - 1000, RestrictionLock.waitLeft(st, t + 1000));
        assertEquals(RestrictionLock.Try.OK, RestrictionLock.check(st, "2468", t + RestrictionLock.WAIT_MS));
        assertEquals(0, st.wrong);
    }

    @Test public void stateSurvivesPackingAndUnlockIsTimed() {
        RestrictionLock.State st = RestrictionLock.setPin("13579");
        RestrictionLock.unlockFor(st, 500, 60_000);
        RestrictionLock.State back = RestrictionLock.State.unpack(st.pack());
        assertEquals(st.hash, back.hash); assertEquals(st.salt, back.salt);
        assertTrue(RestrictionLock.unlocked(back, 10_000)); assertFalse(RestrictionLock.unlocked(back, 70_000));
        RestrictionLock.lock(back); assertFalse(RestrictionLock.unlocked(back, 10_000));
        assertEquals(RestrictionLock.Try.OK, RestrictionLock.check(back, "13579", 0));
        assertEquals(RestrictionLock.Try.NO_PIN, RestrictionLock.check(new RestrictionLock.State(), "13579", 0));
        assertFalse(RestrictionLock.State.unpack(null).hasPin());
        assertFalse(RestrictionLock.State.unpack("garbage").hasPin());
    }
}
