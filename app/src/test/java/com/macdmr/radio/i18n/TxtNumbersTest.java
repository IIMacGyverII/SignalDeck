package com.macdmr.radio.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Numbers and dates on screen: the app language's decimal sign, English byte-identical, typed commas accepted. */
public class TxtNumbersTest {
    @Test public void englishKeepsThePoint() {
        assertEquals("467.6250", Txt.freqMhz(467_625_000L, 4));
        assertEquals("467.6250", Txt.decimal(Locale.ENGLISH, 467.625, 4));
        assertEquals("467.6250", Txt.decimal(Locale.US, 467.625, 4));
        assertEquals("1.5", Txt.decimal(1.5, 1));
        assertEquals("+1.5", Txt.signed(1.5, 1));
        assertEquals("-0.4", Txt.signed(-0.4, 1));
        assertEquals("  7.5", Txt.decimalf("%5.1f", 7.5));
        assertEquals("12.5", Txt.decimalText("12.5"));
        for (double v : new double[] { 0, 1.25, -3.14159, 146.52, 1e6 / 3 })   // the same text as String.format(Locale.US, ...)
            for (int p = 0; p <= 6; p++) assertEquals(String.format(Locale.US, "%." + p + "f", v), Txt.decimal(v, p));
    }

    @Test public void germanUsesTheComma() {
        assertEquals("467,6250", Txt.decimal(Locale.GERMAN, 467.625, 4));
        assertEquals("467,6250", Txt.decimal(Locale.forLanguageTag("de"), 467_625_000L / 1e6, 4));
        assertEquals("1234567,5", Txt.decimal(Locale.GERMAN, 1234567.5, 1));   // never grouped
        assertEquals("+1,5", Txt.number(Locale.GERMAN, "%+.1f", 1.5));
        assertEquals("12,5", Txt.decimalText(Locale.GERMAN, "12.5"));
        assertEquals("−0,600", Txt.decimalText(Locale.GERMAN, "−0.600"));
    }

    @Test public void typedNumbersTakeAPointOrAComma() {
        assertEquals(446.00625, Txt.parseDecimal(" 446.00625 "), 1e-9);
        assertEquals(446.00625, Txt.parseDecimal("446,00625"), 1e-9);
        assertEquals(-0.6, Txt.parseDecimal("-0,6"), 1e-9);
        assertEquals(12.5, Txt.parseDecimal("12,5"), 1e-9);
        assertEquals(145.0, Txt.parseDecimal("145"), 1e-9);
        for (String bad : new String[] { "1,234,5", "1.234,5", "", "abc" })
            try { Txt.parseDecimal(bad); fail(bad); } catch (NumberFormatException expected) { }
    }

    @Test public void englishDatesUnchanged() {
        Date d = new Date(1_793_227_440_000L);
        for (String p : new String[] { "MMM d, HH:mm", "MMM d  HH:mm", "EEE HH:mm", "MM-dd HH:mm", "MM-dd HH:mm:ss", "EEE MM-dd HH:mm:ss", "EEEE, MMM d", "EEE MMM d, HH:mm:ss", "EEE MMM d yyyy, HH:mm" })
            assertEquals(p, new SimpleDateFormat(p, Locale.US).format(d), Txt.dates(p, "MMMdHHmm").format(d));
        assertEquals(new SimpleDateFormat("EEE d MMM, HH:mm", Locale.ENGLISH).format(d), Txt.dates("EEE d MMM, HH:mm", "EEEdMMMHHmm", false).format(d));
    }
}
