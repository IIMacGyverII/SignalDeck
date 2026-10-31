package com.macdmr.radio.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.macdmr.radio.R;

import org.junit.Test;

/** History lines kept as a message: English stored, shown in the app's language, old rows and bad rows fall back. */
public class MsgTest {
    @Test public void englishAndKeyRoundTrip() {
        Msg m = Msg.of(R.string.device_language_phone, "Deutsch");
        assertEquals("Phone's language (Deutsch)", m.english());
        assertEquals("device_language_phone", m.key());
        Msg back = Msg.from(m.key(), m.args());
        assertEquals(m.english(), back.english());
        assertEquals("Phone's language (Deutsch)", Msg.display("stored", m.key(), m.args()));
    }

    @Test public void nestedMessageAndNumbers() {
        Msg inner = Msg.of(R.string.common_cancel);
        Msg m = Msg.of(R.string.device_language_phone, inner);
        assertEquals("Phone's language (Cancel)", m.english());
        assertEquals("Phone's language (Cancel)", Msg.from(m.key(), m.args()).text());
        assertTrue(Msg.of(R.string.device_language_phone, 42).args().contains("42"));
    }

    @Test public void oldAndBrokenRowsShowTheStoredText() {
        assertEquals("📻 Received 4 s", Msg.display("📻 Received 4 s", null, null));
        assertEquals("kept", Msg.display("kept", "no_such_string_anywhere", "{\"k\":\"no_such_string_anywhere\",\"a\":[]}"));
        assertEquals("kept", Msg.display("kept", "device_language", "not json"));
        assertEquals("kept", Msg.display("kept", "device_language", "{\"k\":\"common_cancel\",\"a\":[]}"));   // key and args disagree
        assertNull(Msg.from("", "{}"));
    }

    /** An error a method returned in the app language (Txt.s) goes into a history Msg as its own Msg, so english() is all English. */
    @Test public void aReturnedLocalisedTextBecomesItsMessage() {
        try {
            Txt.recordForTest(true);
            String german = Txt.noteForTest("Noch kein Standort", R.string.service_no_fix_location);   // as Txt.s returns it in German
            Object arg = Msg.shown(german);
            assertTrue(arg instanceof Msg);
            Msg line = Msg.of(R.string.service_iss_resume_failed, arg);
            assertEquals(Txt.en(R.string.service_iss_resume_failed, Txt.en(R.string.service_no_fix_location)), line.english());
            assertEquals(Txt.en(R.string.service_no_fix_location), Txt.english(german));
            assertEquals("a received text", Msg.shown("a received text"));                              // not ours: stays as it is
            assertEquals("a received text", Txt.english("a received text"));
        } finally { Txt.recordForTest(false); }
        assertNull(Txt.madeBy("Noch kein Standort"));                                                    // English: nothing kept
    }

    @Test public void controlCharactersNeverReachTheScreen() {
        assertEquals("Phone's language (A·B)", Msg.of(R.string.device_language_phone, "A\u0007B").english());
    }
}
