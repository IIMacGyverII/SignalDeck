package com.macdmr.radio.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every field-help text of the channel editor renders (they are format strings filled from the phone profile; a
 * placeholder of the wrong type, %1$d given a String, threw when the help card opened).
 */
public class ChannelEditorHelpTest {
    @Test public void everyHelpTextRenders() {
        String[][] help = ChannelEditorView.helpForWeb();
        assertTrue(help.length > 20);
        for (String[] h : help) {
            assertFalse(h[0] + ": empty", h[1].trim().isEmpty());
            assertFalse(h[0] + ": an unfilled placeholder", h[1].matches("(?s).*%\\d*\\$?[sd].*"));
        }
    }
}
