package com.macdmr.radio.i18n;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The extraction guard: English text left in the code. It flags every string literal that holds a word, wherever it
 * goes, so a sentence glued together ("Keyed " + name + " from here"), a format pattern ("%.3f MHz"), an append chain
 * or a string kept in a variable and shown later is caught, not only {@code setText("…")}. Exceptions:
 * <ul>
 *   <li>literals passed straight to a non-UI sink: logs, preference and JSON keys, SQL, regexes, shell, intents,
 *       reflection, exceptions;</li>
 *   <li>a line ending in {@code // i18n: <reason>} (mode names, protocol words, text sent on the air);</li>
 *   <li>a file whose header says {@code i18n: stays English} (reports and bench text, plan §1).</li>
 * </ul>
 * It is a ratchet while the extraction runs: {@code src/test/resources/i18n/literal-baseline.txt} holds each file's
 * count, and the test fails when a file has more English literals than that (new text must go in strings*.xml).
 * When a file's count drops, rewrite the baseline:
 * {@code ./gradlew testReleaseUnitTest --tests '*I18nLiteralGuardTest' -PexportI18n=true --rerun-tasks}.
 */
public class I18nLiteralGuardTest {
    static final String BASELINE = "src/test/resources/i18n/literal-baseline.txt";

    private static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** A word: two letters in a row, and either a space or a capitalised word ("Cancel", "Channel busy"). */
    private static final Pattern WORDY = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern SENTENCEISH = Pattern.compile("[A-Za-z]{2,}[ ,.:;!?…)]|^[A-Z][a-z]+");
    /** Keys, paths, identifiers, format-only and symbol-only literals. */
    private static final Pattern NOT_TEXT = Pattern.compile("^[\\s\\W\\d_]*$|^[a-z0-9_.:/#-]+$|^[A-Z0-9_]+$|^%[\\d$.a-z]*$");
    /** The call a literal is handed to, when it is not user-facing text. */
    private static final Pattern NON_UI_CALL = Pattern.compile(
            "(?:^|[^\\w])(?:Log\\.\\w+|log\\w*|diag\\w*|trace|lg|put\\w*|opt\\w*|get\\w*Extra|putExtra|getSharedPreferences|has|remove|contains\\w*|"
            + "equals\\w*|startsWith|endsWith|indexOf|lastIndexOf|matches|split|replace\\w*|Pattern\\.compile|compile|SimpleDateFormat|DecimalFormat|"
            + "execSQL|rawQuery|query|getColumnIndex\\w*|update|delete|insert|File|exec|run|runAs|su|sh|getprop|setprop|forName|getMethod|getDeclared\\w*|"
            + "getField|Intent|setAction|IntentFilter|setClassName|ComponentName|\\w*Exception|Error|URL|Uri\\.parse|parse|loadLibrary|"
            + "getIdentifier|getAssets\\(\\)\\.open|open|ICONS\\.put|tag|setTag|findViewWithTag|getSystemService|setRequestProperty|getHeaderField|"
            + "param|JSONObject|JSONArray|getString|getInt|getLong|getBoolean|getDouble|getJSONObject|getJSONArray|isNull|"
            + "createNotificationChannel|NotificationChannel|PendingIntent\\.\\w+|newWakeLock|createTempFile|beginSection|"
            + "Txt\\.\\w+|R\\.\\w+)\\s*\\((?:[^()\"]|\"(?:[^\"\\\\]|\\\\.)*\")*$");
    private static final Pattern LOG_LINE = Pattern.compile("\\b(?:Log\\.[a-z]\\(|android\\.util\\.Log\\.|log\\(|diag\\(|DIAG|trace\\(|Trace\\.|System\\.out|printStackTrace)");

    static File srcRoot() {
        File wd = new File(System.getProperty("user.dir"));
        File f = new File(wd, "src/main/java"); if (f.isDirectory()) return f;
        return new File(wd, "app/src/main/java");
    }
    static File baselineFile() {
        File wd = new File(System.getProperty("user.dir"));
        return new File(new File(wd, "src").isDirectory() ? wd : new File(wd, "app"), BASELINE);
    }

    /** English literals per source file (repository-relative to src/main/java), files with none left out. */
    static Map<String, Integer> scan() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        File root = srcRoot();
        List<File> files = new ArrayList<>(); walk(root, files);
        for (File f : files) {
            String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            int n = count(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (n > 0) out.put(rel, n);
        }
        return out;
    }

    static void walk(File d, List<File> out) {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) if (f.isDirectory()) walk(f, out); else if (f.getName().endsWith(".java")) out.add(f);
    }

    static int count(String src) {
        int head = src.indexOf("\npackage "); String header = head > 0 ? src.substring(0, src.indexOf('\n', head + 1) + 1) + src.substring(head, Math.min(src.length(), head + 4000)) : "";
        if (header.contains("i18n: stays English")) return 0;
        int n = 0; boolean block = false;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (block) { if (t.contains("*/")) block = false; continue; }
            if (t.startsWith("/*")) { if (!t.contains("*/")) block = true; continue; }
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("import ") || t.startsWith("package ") || t.startsWith("@")) continue;
            if (line.contains("// i18n:")) continue;
            if (LOG_LINE.matcher(line).find()) continue;
            String code = stripComment(line);
            Matcher m = LIT.matcher(code);
            while (m.find()) {
                String lit = m.group(1);
                if (!WORDY.matcher(lit).find() || NOT_TEXT.matcher(lit).find() || !SENTENCEISH.matcher(lit).find()) continue;
                if (NON_UI_CALL.matcher(code.substring(0, m.start())).find()) continue;
                n++;
            }
        }
        return n;
    }

    /** The line without a trailing // comment (outside string literals). */
    static String stripComment(String line) {
        boolean q = false;
        for (int i = 0; i + 1 < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && q) { i++; continue; }
            if (c == '"') q = !q;
            else if (!q && c == '/' && line.charAt(i + 1) == '/') return line.substring(0, i);
        }
        return line;
    }

    static Map<String, Integer> readBaseline() throws IOException {
        Map<String, Integer> b = new TreeMap<>();
        File f = baselineFile(); if (!f.isFile()) return b;
        for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            l = l.trim(); if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+", 2); b.put(p[1], Integer.parseInt(p[0]));
        }
        return b;
    }

    @Test public void noNewEnglishInCode() throws IOException {
        Map<String, Integer> now = scan();
        if (Boolean.parseBoolean(System.getProperty("signaldeck.exportI18n", "false"))) {
            StringBuilder b = new StringBuilder("# English literals left in the code, per file (I18nLiteralGuardTest). Only ever goes down.\n");
            int total = 0; for (Map.Entry<String, Integer> e : now.entrySet()) { b.append(e.getValue()).append(' ').append(e.getKey()).append('\n'); total += e.getValue(); }
            b.append("# total ").append(total).append('\n');
            File f = baselineFile(); f.getParentFile().mkdirs();
            Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Integer> base = readBaseline();
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int was = base.getOrDefault(e.getKey(), 0);
            if (e.getValue() > was) worse.add(e.getKey() + " (" + was + " → " + e.getValue() + ")");
        }
        assertTrue("New English text in code: " + worse + ". Put user-facing text in res/values/strings_*.xml and read it with Txt.s(R.string.…); "
                + "mark text that must stay English with // i18n: <reason>.", worse.isEmpty());
    }

    @Test public void scannerSeesBuiltSentences() {
        assertTrue(count("x.setText(\"Channel busy\");\n") == 1);
        assertTrue(count("host.event(\"Keyed \" + ch.name + \" from here (tail \" + best + \" dBm)\");\n") == 3);   // every fragment of the sentence, the unit too
        assertTrue(count("String s = String.format(Locale.US, \"%.3f MHz on air\", f);\n") == 1);
        assertTrue(count("b.append(\"TG list \").append(n);\n") == 1);
        assertTrue(count("Log.d(TAG, \"Channel busy now\");\n") == 0);
        assertTrue(count("prefs.getBoolean(\"volPtt\", false); put(\"Some key\", 1);\n") == 0);
        assertTrue(count("x.setText(\"Mode name\"); // i18n: protocol word\n") == 0);
        assertTrue(count("x.setText(Txt.s(R.string.common_cancel));\n") == 0);
    }
}
