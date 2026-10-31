package com.macdmr.radio.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Neo.fit's line count: the word count for every non-CJK label (whatever the width), the measured width for CJK. */
public class NeoFitTest {
    private static final String[] LABELS = { "", " ", "Save", "SCAN", "Start scan", "Zone:  All  ", "Kanalspeicher bearbeiten", "TG 91 Worldwide", "保存", "無線 設定" };

    @Test public void nonCjkIsTheWordCount() {
        for (String s : LABELS) {
            String t = s.trim(); int words = Math.min(2, t.isEmpty() ? 1 : t.split(" +").length);
            for (float w : new float[] { 0, 10, 500, 5000 }) for (int room : new int[] { 0, 1, 100, 1000 })
                assertEquals(s, words, Neo.fitLines(s, false, w, room));
        }
        assertEquals(1, Neo.fitLines(null, false, 0, 0));
    }

    @Test public void cjkFollowsTheWidth() {
        assertEquals(1, Neo.fitLines("無線設定を保存", true, 300, 0));        // before the layout: one line
        assertEquals(1, Neo.fitLines("無線設定を保存", true, 300, 300));
        assertEquals(2, Neo.fitLines("無線設定を保存", true, 301, 300));
        assertEquals(2, Neo.fitLines("スキャン開始", true, 400, 200));
        assertEquals(1, Neo.fitLines("保存", true, 400, 100));               // too short to split: it shrinks instead
        assertEquals(1, Neo.fitLines("無線設", true, 400, 100));
        assertEquals(2, Neo.fitLines("無線 設定", true, 0, 0));              // a space: the word rule
        assertEquals(1, Neo.fitLines("", true, 100, 10));
    }
}
