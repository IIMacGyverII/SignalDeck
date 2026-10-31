package com.macdmr.radio.i18n;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@code // i18n: <reason>} note must be the last thing on its line. Inserted in the middle of a line that holds
 * two statements, it silently comments out the second one (it happened twice: the SCAN key colour and the emergency
 * message's single-line input). This fails when the comment text after {@code // i18n:} looks like code.
 */
public class I18nCommentGuardTest {
    private static final Pattern CODE = Pattern.compile(
            "[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*\\([^;]*\\)\\s*;"   // call(...);  a.b(...);  (prose "word (aside);" has a space before the parenthesis)
            + "|\\bif\\s*\\(|\\bfor\\s*\\(|\\bwhile\\s*\\(|\\breturn\\b[^;]*;|\\bthrow\\s+new\\b"
            + "|\\b[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*|\\[[^\\]]*\\])*\\s*[-+*/|&]?=(?!=)[^;]*;"   // x = …;  a.b += …;
            + "|[{}]\\s*$");

    /** True when the text after a {@code // i18n:} on this line looks like a statement. */
    static boolean swallowsCode(String line) {
        int at = line.indexOf("// i18n:"); if (at < 0) return false;
        return CODE.matcher(line.substring(at + "// i18n:".length())).find();
    }

    @Test public void detectorCatchesTheTwoSlips() {
        assertTrue(swallowsCode("            scanBtn.setText(scanning ? (holding ? \"HOLD\" : \"SCANNING\") : \"SCAN\");   // i18n: key legend scanBtn.setTextColor(scanning ? Neo.AMBER_LIGHT : Neo.CYAN_LIGHT);"));
        assertTrue(swallowsCode("        msg.setText(p.getString(\"text\", \"Need help\"));   // i18n: sent on air (the default emergency message) msg.setSingleLine(true); msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);"));
        assertTrue(swallowsCode("x(\"a\");   // i18n: why if (on) go();"));
        assertTrue(swallowsCode("x(\"a\");   // i18n: why n = 3;"));
        assertTrue(swallowsCode("x(\"a\");   // i18n: why return y;"));
        assertFalse(swallowsCode("x(\"a\");   // i18n: sent on air (bench lead test) and SignalDeck Web's list; screens use leadKindNames()"));
        assertFalse(swallowsCode("x(\"a\");   // i18n: the chooser title is shown to the user; the subject and text stay English with the report"));
        assertFalse(swallowsCode("x(\"a\");   // i18n: also served to the web page (/api/mission); the sheet shows device_mission_text"));
    }

    @Test public void noCodeAfterAnI18nNote() throws IOException {
        List<File> files = new ArrayList<>(); I18nLiteralGuardTest.walk(I18nLiteralGuardTest.srcRoot(), files);
        List<String> bad = new ArrayList<>();
        for (File f : files) {
            String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) if (swallowsCode(lines[i])) bad.add(f.getName() + ":" + (i + 1));
        }
        assertTrue("Code after a // i18n: note is commented out; move the note to the end of the line: " + bad, bad.isEmpty());
    }
}
