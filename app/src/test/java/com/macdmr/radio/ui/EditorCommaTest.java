package com.macdmr.radio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** German keyboards type a decimal comma: the editor's number fields take it as well as the point. */
public class EditorCommaTest {
    @Test public void commaOrPoint() {
        assertEquals(446.00625, ChannelEditorView.number("446,00625", "x"), 1e-9);
        assertEquals(446.00625, ChannelEditorView.number(" 446.00625 ", "x"), 1e-9);
        assertEquals(-122.41942, ChannelEditorView.number("-122,41942", "x"), 1e-9);
        try { ChannelEditorView.number("446,006,25", "the receive frequency (MHz)"); fail(); }
        catch (IllegalArgumentException e) { assertEquals("\"446,006,25\" is not a number: check the receive frequency (MHz)", e.getMessage()); }
    }

    @Test public void readoutStaysEnglishOnTheHost() {
        assertEquals("467.6250", ChannelsView.mhz(467_625_000));
        assertEquals("12.5", BandScopeScreen.khzShown(12_500));
        assertEquals("6.25", BandScopeScreen.khzShown(6_250));
    }
}
