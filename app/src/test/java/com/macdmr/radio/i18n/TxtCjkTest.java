package com.macdmr.radio.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Txt.cjk: Japanese layout only when Japanese is the language shown, the override or the phone's shipped language. */
public class TxtCjkTest {
    private static final String[] WITH_JA = { "en", "de", "ja" };

    @Test public void overrideDecides() {
        assertEquals(Locale.JAPAN, Txt.cjkLocale(Locale.forLanguageTag("ja"), Collections.singletonList(Locale.US), WITH_JA));
        assertEquals(Locale.JAPAN, Txt.cjkLocale(Locale.JAPAN, Collections.singletonList(Locale.US), Txt.LANGUAGES));   // a forced override, ja not shipped: the layout check
        assertNull(Txt.cjkLocale(Locale.GERMAN, Collections.singletonList(Locale.JAPAN), WITH_JA));
        assertNull(Txt.cjkLocale(Locale.ENGLISH, Collections.singletonList(Locale.JAPAN), WITH_JA));
    }

    @Test public void phoneLanguageWhenShipped() {
        assertEquals(Locale.JAPAN, Txt.cjkLocale(null, Arrays.asList(Locale.JAPAN, Locale.US), WITH_JA));
        assertEquals(Locale.JAPAN, Txt.cjkLocale(null, Arrays.asList(Locale.forLanguageTag("fr-FR"), Locale.forLanguageTag("ja")), WITH_JA));   // first shipped one
        assertNull(Txt.cjkLocale(null, Arrays.asList(Locale.US, Locale.JAPAN), WITH_JA));
        assertNull(Txt.cjkLocale(null, Arrays.asList(Locale.JAPAN, Locale.US), Txt.LANGUAGES));   // not shipped: the app shows English
        assertNull(Txt.cjkLocale(null, Collections.singletonList(Locale.CHINA), WITH_JA));
        assertNull(Txt.cjkLocale(null, Collections.<Locale>emptyList(), WITH_JA));
    }

    @Test public void onlyJapaneseListed() {
        assertEquals(Collections.singletonList("ja"), Arrays.asList(Txt.CJK));
        assertFalse(Arrays.asList(Txt.LANGUAGES).contains("ja"));          // no Japanese strings yet
        assertFalse(Txt.cjk());                                            // host: no Context, English
    }
}
