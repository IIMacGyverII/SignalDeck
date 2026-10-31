/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ham.ExamPool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Licence exam practice from the FCC question pools: practice exams, study by subelement, missed questions. */
public final class ExamDialog {
    private final Context c; private final float d; private final SharedPreferences prefs;
    private AlertDialog dlg; private LinearLayout body; private ScrollView scroll;

    private ExamDialog(Context c) { this.c = c; d = Theme.dp(c); prefs = c.getSharedPreferences("exam", Context.MODE_PRIVATE); }
    public static void show(Context c) { new ExamDialog(c).home(); }

    private ExamPool load(ExamPool.Element e) {
        try (InputStream in = c.getAssets().open(e.asset)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return ExamPool.parse(new String(o.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) { Toast.makeText(c, Txt.s(R.string.modes_exam_err_pool, String.valueOf(t.getMessage())), Toast.LENGTH_LONG).show(); return null; }
    }

    /** One sheet for the whole session: each screen replaces its content. */
    private void page(String title) {
        LinearLayout b = Neo.sheetBody(c, title, "radio");
        if (dlg == null) {
            scroll = new ScrollView(c); scroll.setVerticalScrollBarEnabled(false); scroll.addView(b);
            dlg = new AlertDialog.Builder(c).setView(scroll).create(); dlg.show();
            if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        } else { scroll.removeAllViews(); scroll.addView(b); scroll.scrollTo(0, 0); }
        body = b;
    }
    private Set<String> missed(ExamPool.Element e) { return new HashSet<>(prefs.getStringSet("missed_" + e.key, new HashSet<>())); }

    private void home() {
        page(Txt.s(R.string.modes_exam_title));
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_exam_hint)));
        for (ExamPool.Element e : ExamPool.ELEMENTS) {
            int best = prefs.getInt("best_" + e.key, -1);
            List<String> facts = new ArrayList<>();
            facts.add(Txt.p(R.plurals.modes_exam_questions_pass, e.questions, e.questions, e.pass));
            if (best >= 0) facts.add(Txt.s(R.string.modes_exam_best, best, e.questions));
            facts.add(Txt.p(R.plurals.modes_exam_missed_review, missed(e).size(), missed(e).size()));
            LinearLayout r = card(e.name, String.join(Txt.s(R.string.modes_dot_separator), facts));
            r.setOnClickListener(v -> element(e));
            body.addView(r);
        }
    }

    private void element(ExamPool.Element e) {
        ExamPool p = load(e); if (p == null) return;
        page(e.name.replaceFirst(" \\(.*", ""));
        body.addView(Neo.sheetHint(c, Txt.p(R.plurals.modes_exam_pool_size, p.all.size(), p.all.size())));
        TextView exam = Neo.button(c, Txt.p(R.plurals.modes_exam_practice, e.questions, e.questions), "check", Neo.GREEN); body.addView(exam, lp(6));
        exam.setOnClickListener(v -> quiz(e, p.exam(new Random()), true, 0, new ArrayList<>()));
        final Set<String> miss = missed(e);
        TextView again = Neo.button(c, Txt.s(R.string.modes_exam_missed_button, miss.size()), "update", Neo.AMBER); body.addView(again, lp(6));
        again.setOnClickListener(v -> {
            List<ExamPool.Question> l = new ArrayList<>(); for (ExamPool.Question q : p.all) if (miss.contains(q.id)) l.add(q);
            if (l.isEmpty()) { Toast.makeText(c, Txt.s(R.string.modes_exam_nothing_missed), Toast.LENGTH_SHORT).show(); return; }
            java.util.Collections.shuffle(l); quiz(e, l, false, 0, new ArrayList<>());
        });
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_exam_study_section)));
        for (java.util.Map.Entry<String, Integer> s : p.subelements().entrySet()) {
            LinearLayout r = card(s.getKey(), Txt.p(R.plurals.modes_exam_study_card, s.getValue(), s.getValue()));
            r.setOnClickListener(v -> quiz(e, p.subelement(s.getKey(), true, new Random()), false, 0, new ArrayList<>()));
            body.addView(r);
        }
        TextView back = Neo.button(c, Txt.s(R.string.common_back), null, Neo.CYAN); body.addView(back, lp(10)); back.setOnClickListener(v -> home());
    }

    /** Question {@code i} of {@code qs}; {@code exam}: no feedback until the end. {@code wrong} collects the misses. */
    private void quiz(ExamPool.Element e, List<ExamPool.Question> qs, boolean exam, int i, List<Object[]> wrong) {
        if (i >= qs.size()) { results(e, qs, exam, wrong); return; }
        ExamPool.Question q = qs.get(i);
        page(Txt.s(exam ? R.string.modes_exam_question_exam : R.string.modes_exam_question_study, i + 1, qs.size()));
        body.addView(Neo.label(c, q.id, Theme.CYAN, 13, true));
        TextView t = Neo.text(c, q.text, Neo.TEXT, 17, Neo.uiMedium(c)); t.setLineSpacing(0, 1.12f); t.setPadding(0, (int) (6 * d), 0, (int) (8 * d)); body.addView(t);
        if (!q.figure.isEmpty()) {
            try (InputStream in = c.getAssets().open("exam/" + q.figure)) {
                ImageView iv = new ImageView(c); iv.setImageBitmap(BitmapFactory.decodeStream(in)); iv.setAdjustViewBounds(true); iv.setBackgroundColor(0xFFFFFFFF); iv.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
                body.addView(iv, lp(4));
            } catch (Throwable ignored) { }
        }
        final TextView[] bs = new TextView[q.answers.length];
        final boolean[] answered = { false };
        for (int k = 0; k < q.answers.length; k++) {
            final int pick = k;
            bs[k] = Neo.button(c, (char) ('A' + k) + ".  " + q.answers[k], null, Neo.CYAN); bs[k].setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); bs[k].setTextSize(15);
            body.addView(bs[k], lp(6));
            bs[k].setOnClickListener(v -> {
                if (answered[0]) return; answered[0] = true;
                boolean right = pick == q.correct;
                Set<String> miss = missed(e); if (right) miss.remove(q.id); else miss.add(q.id);
                prefs.edit().putStringSet("missed_" + e.key, miss).apply();
                if (!right) wrong.add(new Object[] { q, pick });
                if (exam) { quiz(e, qs, true, i + 1, wrong); return; }
                bs[q.correct].setBackground(Neo.outline(c, Neo.GREEN, 0x44, 12, 2)); bs[q.correct].setTextColor(Neo.GREEN_LIGHT);
                if (!right) { bs[pick].setBackground(Neo.outline(c, Neo.RED, 0x33, 12, 2)); bs[pick].setTextColor(Neo.RED); }
                TextView next = Neo.button(c, right ? Txt.s(R.string.modes_exam_right_next) : Txt.s(R.string.modes_exam_answer_next, String.valueOf((char) ('A' + q.correct))), null, right ? Neo.GREEN : Neo.AMBER);
                body.addView(next, lp(12)); next.setOnClickListener(x -> quiz(e, qs, false, i + 1, wrong));
            });
        }
        TextView stop = Neo.button(c, Txt.s(R.string.common_stop), null, Neo.MUTED); body.addView(stop, lp(16)); stop.setOnClickListener(v -> results(e, qs.subList(0, i), exam, wrong));
    }

    private void results(ExamPool.Element e, List<ExamPool.Question> qs, boolean exam, List<Object[]> wrong) {
        int right = qs.size() - wrong.size();
        page(Txt.s(exam ? R.string.modes_exam_result_exam : R.string.modes_exam_result_study));
        boolean full = exam && qs.size() == e.questions;
        boolean pass = full && right >= e.pass;
        TextView big = Neo.text(c, right + " / " + qs.size(), full ? (pass ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT) : Neo.CYAN_LIGHT, 40, Neo.display(c)); big.setGravity(android.view.Gravity.CENTER); body.addView(big);
        body.addView(Neo.sheetHint(c, full ? (pass ? Txt.p(R.plurals.modes_exam_passed, e.pass, e.pass) : Txt.p(R.plurals.modes_exam_not_yet, e.pass, e.pass)) : Txt.s(R.string.modes_exam_percent, String.format(Locale.US, "%.0f", qs.isEmpty() ? 0 : 100.0 * right / qs.size()))));
        if (full && right > prefs.getInt("best_" + e.key, -1)) prefs.edit().putInt("best_" + e.key, right).apply();
        if (!wrong.isEmpty()) body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_exam_missed_section)));
        for (Object[] w : wrong) {
            ExamPool.Question q = (ExamPool.Question) w[0]; int pick = (Integer) w[1];
            LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); r.setBackground(Neo.rowCard(c)); r.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            r.addView(Neo.label(c, q.id + "  " + q.text, Neo.TEXT, 13.5f, false));
            r.addView(Neo.label(c, Txt.s(R.string.modes_exam_answer, String.valueOf((char) ('A' + q.correct)), q.answers[q.correct]), Neo.GREEN_LIGHT, 13, false));
            r.addView(Neo.label(c, Txt.s(R.string.modes_exam_you_chose, String.valueOf((char) ('A' + pick)), q.answers[pick]), 0xFFE08A80, 12.5f, false));
            body.addView(r, lp(6));
        }
        TextView again = Neo.button(c, Txt.s(R.string.modes_exam_back_to, e.name.replaceFirst(" \\(.*", "")), null, Neo.CYAN); body.addView(again, lp(12)); again.setOnClickListener(v -> element(e));
    }

    private LinearLayout card(String title, String sub) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(Neo.rowCard(c)); v.setPadding((int) (14 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        v.addView(Neo.text(c, title, Neo.TEXT, 16, Neo.ui(c))); TextView s = Neo.text(c, sub, Neo.MUTED, 13, Neo.uiMedium(c)); s.setPadding(0, (int) (2 * d), 0, 0); v.addView(s);
        v.setClickable(true); v.setLayoutParams(lp(6)); return v;
    }
    private LinearLayout.LayoutParams lp(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); l.topMargin = (int) (top * d); return l; }
}
