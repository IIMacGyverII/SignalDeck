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

    @Test public void controlCharactersNeverReachTheScreen() {
        assertEquals("Phone's language (A·B)", Msg.of(R.string.device_language_phone, "A\u0007B").english());
    }
}
