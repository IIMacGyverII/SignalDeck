/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Guide;
import com.macdmr.radio.i18n.Txt;

import java.util.Locale;

/**
 * In-app help as a navigable guide: a home page (Start here, search, topic tiles grouped by area) and one page
 * per topic with headed sections and Previous / Next. Search matches every section and opens the topic at it.
 * Written for someone holding the radio for the first time; keep it in step with the screens.
 */
public class HelpView extends LinearLayout {

    /** The guide for SignalDeck Web: per topic {group, title, summary, heading 1, text 1, heading 2, text 2, …}. */
    public static java.util.List<String[]> topicsForWeb() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (Guide.Topic t : Guide.topics()) {
            String[] r = new String[3 + 2 * t.sections.length]; r[0] = t.group; r[1] = t.title; r[2] = t.summary;
            for (int i = 0; i < t.sections.length; i++) { r[3 + 2 * i] = t.sections[i].head; r[4 + 2 * i] = t.sections[i].text; }
            out.add(r);
        }
        return out;
    }

    /** A topic's accent colour from its name in the guide file. */
    static int accent(Guide.Topic t) {
        switch (t.accent) { case "green": return Neo.GREEN; case "amber": return Neo.AMBER; case "violet": return Neo.VIOLET; case "red": return Neo.RED; default: return Neo.CYAN; }
    }

    /** The guide's text lives in assets/guide/en.json (one file per language); see {@link Guide}. */
    private final Guide.Topic[] TOPICS = Guide.topics();

    private final Runnable onClose;
    private final FrameLayout stage;
    private LinearLayout home, homeGrid, results;
    private EditText search;
    private int current = -1;

    public HelpView(Context c, Runnable onClose) {
        super(c);
        this.onClose = onClose;
        setOrientation(VERTICAL); setBackground(Neo.page(c));
        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        showHome();
    }

    /** Back key: topic → home → close. Returns true when handled here. */
    public boolean back() { if (current >= 0) { showHome(); return true; } return false; }

    // ---- home ----
    private void showHome() {
        final Context c = getContext(); final float d = Theme.dp(c);
        current = -1;
        stage.removeAllViews();
        LinearLayout page = new LinearLayout(c); page.setOrientation(VERTICAL);
        LinearLayout header = Neo.header(c, Txt.s(R.string.intercom_help_title), Txt.p(R.plurals.intercom_help_topics, TOPICS.length, TOPICS.length));
        ImageView close = Neo.iconButton(c, "close", Neo.CYAN, Txt.s(R.string.intercom_help_close)); close.setOnClickListener(v -> onClose.run());
        Neo.addAction(header, close);
        page.addView(header);

        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        home = new LinearLayout(c); home.setOrientation(VERTICAL); home.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (28 * d));
        sv.addView(home);
        page.addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // search
        LinearLayout sf = new LinearLayout(c); sf.setGravity(Gravity.CENTER_VERTICAL);
        sf.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x33), 12)); sf.setPadding((int) (14 * d), 0, (int) (10 * d), 0);
        ImageView si = new ImageView(c); si.setImageDrawable(new Neo.IconDrawable("search", Neo.MUTED));
        sf.addView(si, new LayoutParams((int) (18 * d), (int) (18 * d)));
        search = new EditText(c); search.setHint(Txt.s(R.string.intercom_help_search_hint)); search.setSingleLine(true); search.setTextSize(16); search.setTypeface(Neo.uiSemi(c));
        search.setTextColor(Neo.TEXT); search.setHintTextColor(Neo.DIM); search.setBackground(null); search.setPadding((int) (10 * d), 0, 0, 0);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sf.addView(search, new LayoutParams(0, (int) (44 * d), 1f));
        home.addView(sf, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (46 * d)));

        results = new LinearLayout(c); results.setOrientation(VERTICAL); results.setVisibility(GONE);
        home.addView(results);
        homeGrid = new LinearLayout(c); homeGrid.setOrientation(VERTICAL);
        home.addView(homeGrid);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { } @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) { runSearch(e.toString()); }
        });

        // start here: featured card
        final Guide.Topic start = TOPICS[0];
        LinearLayout hero = new LinearLayout(c); hero.setOrientation(VERTICAL); hero.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
        android.graphics.drawable.StateListDrawable hs = new android.graphics.drawable.StateListDrawable();
        hs.addState(new int[] { android.R.attr.state_pressed }, Neo.tint(c, Neo.CYAN, 18, 1.5f)); hs.addState(new int[] { }, Neo.tint(c, Neo.CYAN, 18, 1));
        hero.setBackground(hs); hero.setClickable(true); hero.setFocusable(true);
        LinearLayout ht = new LinearLayout(c); ht.setGravity(Gravity.CENTER_VERTICAL);
        ht.addView(Neo.iconTile(c, start.icon, Neo.CYAN, 52), new LayoutParams((int) (52 * d), (int) (52 * d)));
        LinearLayout hc = new LinearLayout(c); hc.setOrientation(VERTICAL); hc.setPadding((int) (14 * d), 0, 0, 0);
        TextView kicker = Neo.caps(c, Txt.s(R.string.intercom_help_start_here)); kicker.setTextColor(Neo.CYAN_LIGHT); hc.addView(kicker);
        TextView htitle = Neo.text(c, start.title, Neo.TEXT, 21, Neo.ui(c)); htitle.setPadding(0, (int) (3 * d), 0, 0); hc.addView(htitle);
        ht.addView(hc, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageView arrow = new ImageView(c); arrow.setImageDrawable(new Neo.IconDrawable("right", Neo.CYAN)); ht.addView(arrow, new LayoutParams((int) (22 * d), (int) (22 * d)));
        hero.addView(ht);
        for (int i = 0; i < start.sections.length; i++) {
            LinearLayout step = new LinearLayout(c); step.setGravity(Gravity.CENTER_VERTICAL); step.setPadding(0, (int) (i == 0 ? 14 * d : 8 * d), 0, 0);
            TextView num = Neo.text(c, String.valueOf(i + 1), Neo.CYAN, 14, Neo.display(c)); num.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable ng = new android.graphics.drawable.GradientDrawable(); ng.setShape(android.graphics.drawable.GradientDrawable.OVAL); ng.setStroke((int) (1.5f * d), Neo.CYAN); num.setBackground(ng);
            step.addView(num, new LayoutParams((int) (26 * d), (int) (26 * d)));
            TextView st = Neo.text(c, start.sections[i].head.replaceFirst("^\\d+\\s+", ""), Neo.TEXT_SOFT, 15.5f, Neo.uiSemi(c)); st.setPadding((int) (10 * d), 0, 0, 0);
            step.addView(st);
            hero.addView(step);
        }
        hero.setOnClickListener(v -> showTopic(0, -1));
        LayoutParams hl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hl.topMargin = (int) (14 * d);
        homeGrid.addView(hero, hl);

        // featured: the newest major feature
        int fi = -1; for (int i = 0; i < TOPICS.length; i++) if ("coverage-maps".equals(TOPICS[i].id)) fi = i;
        if (fi > 0) {
            final int fIdx = fi; Guide.Topic ft = TOPICS[fi];
            LinearLayout feat = new LinearLayout(c); feat.setOrientation(VERTICAL); feat.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
            android.graphics.drawable.StateListDrawable fs = new android.graphics.drawable.StateListDrawable();
            fs.addState(new int[] { android.R.attr.state_pressed }, Neo.tint(c, Neo.VIOLET, 18, 1.5f)); fs.addState(new int[] { }, Neo.tint(c, Neo.VIOLET, 18, 1));
            feat.setBackground(fs); feat.setClickable(true); feat.setFocusable(true);
            LinearLayout fr = new LinearLayout(c); fr.setGravity(Gravity.CENTER_VERTICAL);
            fr.addView(Neo.iconTile(c, ft.icon, Neo.VIOLET, 46), new LayoutParams((int) (46 * d), (int) (46 * d)));
            LinearLayout fc = new LinearLayout(c); fc.setOrientation(VERTICAL); fc.setPadding((int) (14 * d), 0, 0, 0);
            TextView fk = Neo.caps(c, Txt.s(R.string.intercom_help_featured_kicker)); fk.setTextColor(Neo.VIOLET_LIGHT); fc.addView(fk);
            TextView ftt = Neo.text(c, ft.title, Neo.TEXT, 20, Neo.ui(c)); ftt.setPadding(0, (int) (3 * d), 0, 0); fc.addView(ftt);
            fr.addView(fc, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            ImageView fa = new ImageView(c); fa.setImageDrawable(new Neo.IconDrawable("right", Neo.VIOLET)); fr.addView(fa, new LayoutParams((int) (22 * d), (int) (22 * d)));
            feat.addView(fr);
            TextView fsum = Neo.text(c, Txt.s(R.string.intercom_help_featured_text), Neo.TEXT_SOFT, 14.5f, Neo.uiMedium(c));
            fsum.setPadding(0, (int) (10 * d), 0, 0); fsum.setLineSpacing(0, 1.1f); feat.addView(fsum);
            feat.setOnClickListener(v -> showTopic(fIdx, -1));
            LayoutParams fl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); fl.topMargin = (int) (12 * d);
            homeGrid.addView(feat, fl);
        }

        // topic tiles by group
        String group = null; LinearLayout rowOpen = null;
        for (int i = 1; i < TOPICS.length; i++) {
            final int idx = i; Guide.Topic t = TOPICS[i];
            if (!t.group.equals(group)) {
                group = t.group; rowOpen = null;
                TextView gh = Neo.caps(c, group); gh.setPadding((int) (2 * d), (int) (22 * d), 0, (int) (8 * d)); homeGrid.addView(gh);
            }
            LinearLayout tile = Neo.tile(c, t.icon, accent(t), t.title, t.summary);
            tile.setOnClickListener(v -> showTopic(idx, -1));
            if (rowOpen == null) {
                rowOpen = new LinearLayout(c); rowOpen.setBaselineAligned(false);
                LayoutParams l1 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); l1.setMargins(0, 0, (int) (4 * d), (int) (8 * d));
                rowOpen.addView(tile, l1);
                // a lone tile still takes half the row
                View spacer = new View(c); spacer.setTag("spacer");
                LayoutParams l2 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); l2.setMargins((int) (4 * d), 0, 0, (int) (8 * d));
                rowOpen.addView(spacer, l2);
                homeGrid.addView(rowOpen);
            } else {
                View spacer = rowOpen.findViewWithTag("spacer"); LayoutParams l2 = (LayoutParams) spacer.getLayoutParams();
                rowOpen.removeView(spacer); rowOpen.addView(tile, l2);
                rowOpen = null;
            }
        }
        stage.addView(page);
    }

    private void runSearch(String q) {
        final Context c = getContext(); final float d = Theme.dp(c);
        q = q.trim().toLowerCase(Locale.US);
        results.removeAllViews();
        if (q.isEmpty()) { results.setVisibility(GONE); homeGrid.setVisibility(VISIBLE); return; }
        homeGrid.setVisibility(GONE); results.setVisibility(VISIBLE);
        int n = 0;
        for (int i = 0; i < TOPICS.length; i++) {
            Guide.Topic t = TOPICS[i];
            for (int k = 0; k < t.sections.length; k++) {
                Guide.Section sec = t.sections[k];
                String body = sec.text.toLowerCase(Locale.US);
                boolean hit = sec.head.toLowerCase(Locale.US).contains(q) || body.contains(q) || (k == 0 && t.title.toLowerCase(Locale.US).contains(q));
                if (!hit) continue;
                final int ti = i, sk = k;
                LinearLayout card = new LinearLayout(c); card.setOrientation(VERTICAL); card.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
                card.setBackground(Neo.card(c, Neo.alpha(accent(t), 0x55), 14)); card.setClickable(true);
                LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
                ImageView ic = new ImageView(c); ic.setImageDrawable(new Neo.IconDrawable(t.icon, accent(t))); top.addView(ic, new LayoutParams((int) (18 * d), (int) (18 * d)));
                TextView tt = Neo.caps(c, t.title + "  ·  " + sec.head.replaceFirst("^\\d+\\s+", "")); tt.setTextColor(Neo.alpha(accent(t), 0xFF)); tt.setPadding((int) (8 * d), 0, 0, 0); top.addView(tt);
                card.addView(top);
                int at = body.indexOf(q); int from = Math.max(0, at - 50);
                String snip = at < 0 ? sec.text : (from > 0 ? "…" : "") + sec.text.substring(from, Math.min(sec.text.length(), at + 110)) + (at + 110 < sec.text.length() ? "…" : "");
                TextView sn = Neo.text(c, snip, Neo.TEXT_SOFT, 14, Neo.uiMedium(c)); sn.setPadding(0, (int) (6 * d), 0, 0); sn.setLineSpacing(0, 1.1f);
                card.addView(sn);
                card.setOnClickListener(v -> showTopic(ti, sk));
                LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (10 * d);
                results.addView(card, lp);
                n++;
            }
        }
        if (n == 0) {
            TextView none = Neo.text(c, Txt.s(R.string.intercom_help_no_match, q), Neo.MUTED, 15, Neo.uiMedium(c)); none.setPadding((int) (4 * d), (int) (20 * d), 0, 0);
            results.addView(none);
        }
    }

    // ---- topic page ----
    private void showTopic(final int idx, final int focusSection) {
        final Context c = getContext(); final float d = Theme.dp(c);
        current = idx;
        final Guide.Topic t = TOPICS[idx];
        stage.removeAllViews();
        LinearLayout page = new LinearLayout(c); page.setOrientation(VERTICAL);
        LinearLayout header = Neo.header(c, t.title, Txt.s(R.string.intercom_help_topic_position, t.group, idx + 1, TOPICS.length));
        ImageView back = Neo.iconButton(c, "left", Neo.CYAN, Txt.s(R.string.intercom_help_all_topics)); back.setOnClickListener(v -> showHome());
        LayoutParams bl = new LayoutParams((int) (44 * d), (int) (44 * d)); bl.rightMargin = (int) (12 * d);
        header.addView(back, 0, bl);
        ImageView close = Neo.iconButton(c, "close", Neo.CYAN, Txt.s(R.string.intercom_help_close)); close.setOnClickListener(v -> onClose.run());
        Neo.addAction(header, close);
        page.addView(header);

        final ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (28 * d));
        sv.addView(body);
        page.addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout lead = new LinearLayout(c); lead.setGravity(Gravity.CENTER_VERTICAL);
        lead.addView(Neo.iconTile(c, t.icon, accent(t), 56), new LayoutParams((int) (56 * d), (int) (56 * d)));
        TextView sum = Neo.text(c, t.summary, Neo.TEXT, 18, Neo.ui(c)); sum.setPadding((int) (14 * d), 0, 0, 0); sum.setLineSpacing(0, 1.05f);
        lead.addView(sum, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        body.addView(lead);

        final View[] secViews = new View[t.sections.length];
        for (int k = 0; k < t.sections.length; k++) {
            Guide.Section sec = t.sections[k];
            LinearLayout card = new LinearLayout(c); card.setOrientation(VERTICAL); card.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (16 * d));
            card.setBackground(Neo.card(c, k == focusSection ? accent(t) : Neo.LINE, 16));
            LinearLayout hrow = new LinearLayout(c); hrow.setGravity(Gravity.CENTER_VERTICAL);
            View bar = new View(c); android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(accent(t)); bg.setCornerRadius(2 * d); bar.setBackground(bg);
            hrow.addView(bar, new LayoutParams((int) (3 * d), (int) (16 * d)));
            TextView h = Neo.text(c, sec.head, Neo.TEXT, 16.5f, Neo.ui(c)); h.setPadding((int) (10 * d), 0, 0, 0); hrow.addView(h);
            card.addView(hrow);
            TextView tx = Neo.text(c, sec.text, Neo.TEXT_SOFT, 15, Neo.uiMedium(c)); tx.setLineSpacing(0, 1.18f); tx.setPadding(0, (int) (8 * d), 0, 0);
            tx.setTextIsSelectable(false);
            card.addView(tx);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (12 * d);
            body.addView(card, lp);
            secViews[k] = card;
        }

        // previous / next
        LinearLayout nav = new LinearLayout(c); nav.setBaselineAligned(false);
        LayoutParams nl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); nl.topMargin = (int) (20 * d);
        if (idx > 0) {
            View prev = navCard(c, Txt.s(R.string.intercom_help_previous), TOPICS[idx - 1], false); prev.setOnClickListener(v -> showTopic(idx - 1, -1));
            LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); p.rightMargin = (int) (4 * d); nav.addView(prev, p);
        }
        if (idx < TOPICS.length - 1) {
            View next = navCard(c, Txt.s(R.string.intercom_help_next), TOPICS[idx + 1], true); next.setOnClickListener(v -> showTopic(idx + 1, -1));
            LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); p.leftMargin = idx > 0 ? (int) (4 * d) : 0; nav.addView(next, p);
        }
        body.addView(nav, nl);
        TextView all = Neo.button(c, Txt.s(R.string.intercom_help_all_topics), "menu", Neo.CYAN); all.setOnClickListener(v -> showHome());
        LayoutParams al = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); al.topMargin = (int) (10 * d);
        body.addView(all, al);

        stage.addView(page);
        if (focusSection >= 0) sv.post(() -> sv.smoothScrollTo(0, Math.max(0, secViews[focusSection].getTop() - (int) (8 * d))));
    }

    private static View navCard(Context c, String label, Guide.Topic t, boolean next) {
        float d = Theme.dp(c);
        LinearLayout card = new LinearLayout(c); card.setOrientation(VERTICAL); card.setGravity(next ? Gravity.END : Gravity.START);
        card.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, Neo.tint(c, accent(t), 14, 1.5f)); s.addState(new int[] { }, Neo.card(c, Neo.alpha(accent(t), 0x66), 14));
        card.setBackground(s); card.setClickable(true); card.setFocusable(true);
        TextView l = Neo.caps(c, label); card.addView(l);
        TextView tt = Neo.text(c, t.title, Neo.TEXT, 16, Neo.ui(c)); tt.setPadding(0, (int) (4 * d), 0, 0); tt.setGravity(next ? Gravity.END : Gravity.START); card.addView(tt);
        return card;
    }
}
