/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * DMR text messages. Conversation cards (one per peer: DMR ID or talkgroup) with avatar, last text, time and
 * delivery mark; an illustrated empty state; a thread view with bubbles (yours right, theirs left), coloured
 * delivery status, quick replies and a send button, and a note when the current channel cannot carry text.
 * Sending uses the module's 0x2C command; incoming texts arrive through the service (status 5 → fetch 0x2D).
 */
public class MessagesView extends LinearLayout implements RadioService.Listener {
    private final FrameLayout content;
    private final LinearLayout listPage, header;
    private final ListView list;
    private final ConvAdapter convAdapter = new ConvAdapter();
    private final List<ChannelStore.Conversation> convs = new ArrayList<>();
    private RadioService svc;
    private final LinearLayout empty;
    private LinearLayout threadPage;
    private ListView threadList;
    private ThreadAdapter threadAdapter;
    private TextView analogNote;
    private ChannelStore.Contact peer;
    private final List<ChannelStore.Message> msgs = new ArrayList<>();
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm", java.util.Locale.US), df = new SimpleDateFormat("MMM d  HH:mm", java.util.Locale.US);

    public MessagesView(Context c) {
        super(c);
        setOrientation(VERTICAL);   // background: the window's (the thread page, an overlay, keeps its own)
        float d = Theme.dp(c);
        content = new FrameLayout(c);
        listPage = new LinearLayout(c); listPage.setOrientation(VERTICAL);
        header = Neo.header(c, Txt.s(R.string.packet_msg_title), Txt.s(R.string.packet_msg_sub));
        ImageView mon = Neo.iconButton(c, "scan", Neo.VIOLET, Txt.s(R.string.packet_msg_cd_monitor));
        mon.setOnClickListener(v -> toggleMonitor());
        mon.setOnLongClickListener(v -> { editScan(); return true; });
        Neo.addAction(header, mon);
        ImageView add = Neo.iconButton(c, "plus", Neo.GREEN, Txt.s(R.string.packet_msg_new));
        add.setOnClickListener(v -> newMessage());
        Neo.addAction(header, add);
        listPage.addView(header);
        list = new ListView(c); list.setAdapter(convAdapter); list.setDivider(null); list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0)); list.setClipToPadding(false);
        list.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (12 * d));
        list.setOnItemClickListener((p, v, pos, id) -> { ChannelStore.Conversation cv = convs.get(pos); if (!"dmr".equals(cv.transport)) openDataThread(cv); else { ChannelStore.Contact ct = new ChannelStore.Contact(); ct.name = cv.name; ct.number = cv.peer; ct.type = cv.type; openThread(ct); } });

        // empty state: glowing icon, title, how-to, button
        empty = new LinearLayout(c); empty.setOrientation(VERTICAL); empty.setGravity(Gravity.CENTER_HORIZONTAL); empty.setPadding((int) (32 * d), (int) (70 * d), (int) (32 * d), 0);
        ImageView art = Neo.iconTile(c, "messages", Neo.CYAN, 96);
        empty.addView(art, new LayoutParams((int) (96 * d), (int) (96 * d)));
        TextView et = Neo.text(c, Txt.s(R.string.packet_msg_empty_title), Neo.TEXT, 20, Neo.ui(c)); et.setPadding(0, (int) (22 * d), 0, 0); empty.addView(et);
        TextView eb = Neo.text(c, Txt.s(R.string.packet_msg_empty_body), Neo.MUTED, 14.5f, Neo.uiMedium(c));
        eb.setGravity(Gravity.CENTER); eb.setLineSpacing(0, 1.15f); eb.setPadding(0, (int) (8 * d), 0, (int) (22 * d)); empty.addView(eb);
        TextView start = Neo.button(c, Txt.s(R.string.packet_msg_new), "plus", Neo.GREEN);
        start.setOnClickListener(v -> newMessage());
        empty.addView(start, new LayoutParams((int) (220 * d), LayoutParams.WRAP_CONTENT));
        listPage.addView(empty);
        listPage.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(listPage);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void attach(RadioService s) { svc = s; s.addListener(this); reload(); }
    public void detach() { if (svc != null) svc.removeListener(this); svc = null; }

    public void reload() {
        if (svc == null) return;
        convs.clear(); convs.addAll(svc.conversations()); convAdapter.notifyDataSetChanged();
        empty.setVisibility(convs.isEmpty() ? VISIBLE : GONE);
        Neo.headerSubtitle(header).setText((convs.isEmpty() ? Txt.s(R.string.packet_msg_sub) : Txt.p(R.plurals.packet_msg_conversations, convs.size(), convs.size())).toUpperCase(java.util.Locale.US));
    }

    public boolean back() { if (threadPage != null) { content.removeView(threadPage); threadPage = null; peer = null; dataThread = null; if (svc != null) svc.watchingThread(""); reload(); return true; } return false; }

    private String dataThread;
    private TextView pageBanner;

    @Override public void onPage(String threadKey, String line) {
        if (dataThread != null && threadKey != null && !threadKey.equals(dataThread) && pageBanner != null) { pageBanner.setText(Txt.s(R.string.packet_msg_also_heard, line)); pageBanner.setVisibility(View.VISIBLE); }
        reload();
        if (dataThread != null && threadKey != null && threadKey.equals(dataThread)) loadData();
    }

    private void toggleMonitor() {
        if (svc == null) return;
        boolean on = svc.pager() != null && svc.pager().monitoring();
        if (on) { svc.pager().monitor(false); android.widget.Toast.makeText(getContext(), Txt.s(R.string.packet_msg_monitor_off), android.widget.Toast.LENGTH_SHORT).show(); reload(); return; }
        int[] hz = scanHz();
        String e = svc.setPagerMonitor(true, hz, 1200);
        android.widget.Toast.makeText(getContext(), e == null ? Txt.s(R.string.packet_pkt_pager_listening) : e, android.widget.Toast.LENGTH_LONG).show();
        reload();
    }

    private int[] scanHz() {
        String raw = getContext().getSharedPreferences("pager", android.content.Context.MODE_PRIVATE).getString("scan", "467625000,467650000");
        java.util.List<Integer> hz = new java.util.ArrayList<>();
        for (String s : raw.split("[,\\s]+")) { try { int n = Integer.parseInt(s.trim()); if (ChannelStore.inBand(n)) hz.add(n); } catch (Exception ignored) { } }
        int[] a = new int[hz.size()]; for (int i = 0; i < a.length; i++) a[i] = hz.get(i); return a;
    }

    private void editScan() {
        final android.widget.EditText et = new android.widget.EditText(getContext());
        et.setText(getContext().getSharedPreferences("pager", android.content.Context.MODE_PRIVATE).getString("scan", "467625000,467650000"));
        et.setTextColor(Neo.TEXT);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.packet_msg_scan_title)).setMessage(Txt.s(R.string.packet_msg_scan_body)).setView(et)
                .setPositiveButton(Txt.s(R.string.common_save), (d, w) -> getContext().getSharedPreferences("pager", android.content.Context.MODE_PRIVATE).edit().putString("scan", et.getText().toString().trim()).apply())
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void openDataThread(ChannelStore.Conversation cv) {
        final android.content.Context c = getContext(); float d = Theme.dp(c);
        dataThread = cv.threadKey;
        if (svc != null) svc.watchingThread(cv.threadKey);
        if (threadPage != null) content.removeView(threadPage);
        threadPage = new LinearLayout(c); threadPage.setOrientation(VERTICAL); threadPage.setBackground(Neo.page(c));
        String kind = "page".equals(cv.transport) ? Txt.s(R.string.packet_msg_kind_pager) : "M17";
        LinearLayout bar = Neo.header(c, cv.name == null || cv.name.isEmpty() ? kind : cv.name, kind);
        ImageView back = Neo.iconButton(c, "left", Neo.CYAN, Txt.s(R.string.common_back)); back.setOnClickListener(v -> back());
        LayoutParams bl = new LayoutParams((int) (44 * d), (int) (44 * d)); bl.rightMargin = (int) (12 * d);
        bar.addView(back, 0, bl);
        threadPage.addView(bar);
        pageBanner = Neo.text(c, "", Neo.AMBER_LIGHT, 13.5f, Neo.uiSemi(c));
        pageBanner.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        pageBanner.setVisibility(View.GONE);
        threadPage.addView(pageBanner);
        threadList = new ListView(c); threadAdapter = new ThreadAdapter(); threadList.setAdapter(threadAdapter); threadList.setDivider(null); threadList.setDividerHeight(0);
        threadList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        threadList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL); threadList.setStackFromBottom(true);
        threadPage.addView(threadList, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        if (svc == null || svc.pager() == null || !svc.pager().monitoring()) {
            LinearLayout compose = new LinearLayout(c); compose.setGravity(Gravity.CENTER_VERTICAL); compose.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (10 * d));
            compose.setBackgroundColor(Neo.BAR);
            final android.widget.EditText input = new android.widget.EditText(c); input.setHint(Txt.s("page".equals(cv.transport) ? R.string.packet_msg_hint_page : R.string.packet_msg_hint_m17)); input.setTextColor(Neo.TEXT); input.setHintTextColor(Neo.DIM);
            input.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x33), 14)); input.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
            compose.addView(input, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            ImageView send = new ImageView(c); send.setImageDrawable(new Neo.IconDrawable("send", 0xFF04121A)); send.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int sp = (int) (12 * d); send.setPadding(sp, sp, sp, sp); send.setContentDescription(Txt.s(R.string.common_send));
            android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable(); sg.setShape(android.graphics.drawable.GradientDrawable.OVAL); sg.setColor(Neo.GREEN); send.setBackground(sg);
            LayoutParams sl = new LayoutParams((int) (46 * d), (int) (46 * d)); sl.leftMargin = (int) (8 * d);
            compose.addView(send, sl);
            send.setOnClickListener(v -> sendData(cv, input));
            threadPage.addView(compose);
            if ("page".equals(cv.transport) && cv.threadKey != null && cv.threadKey.startsWith("ric:")) {
                TextView save = Neo.button(c, Txt.s(R.string.packet_msg_save_contact), "plus", Neo.CYAN);
                save.setOnClickListener(v -> saveRic(cv));
                threadPage.addView(save);
            }
        }
        content.addView(threadPage);
        loadData();
    }

    private void loadData() {
        if (dataThread == null || svc == null) return;
        msgs.clear();
        for (ChannelStore.PageRow p : svc.store().pagesIn(dataThread)) {
            ChannelStore.Message m = new ChannelStore.Message();
            m.id = p.id; m.ts = p.ts; m.outgoing = p.outgoing; m.text = p.text; m.status = p.corrected > 0 ? 3 : 1;
            msgs.add(m);
        }
        if (threadAdapter != null) threadAdapter.notifyDataSetChanged();
    }

    private void sendData(ChannelStore.Conversation cv, android.widget.EditText input) {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || svc == null) return;
        String err;
        if ("m17".equals(cv.transport)) err = svc.sendM17Sms("everyone".equals(cv.threadKey) ? "@ALL" : cv.address, t);
        else {
            int ric = 0; try { ric = Integer.parseInt(cv.address); } catch (Exception ignored) { }
            err = svc.sendPage(1200, svc.current() == null ? 467625000 : svc.current().rxFreq, new int[] { ric }, 3, t, true);
        }
        if (err != null) android.widget.Toast.makeText(getContext(), err, android.widget.Toast.LENGTH_LONG).show();
        else input.setText("");
        loadData();
    }

    private void saveRic(ChannelStore.Conversation cv) {
        final android.widget.EditText name = new android.widget.EditText(getContext()); name.setHint(Txt.s(R.string.packet_msg_ric_name)); name.setTextColor(Neo.TEXT);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.packet_msg_ric_title, cv.address)).setView(name).setPositiveButton(Txt.s(R.string.common_save), (d, w) -> {
            ChannelStore.Contact ct = new ChannelStore.Contact(); ct.name = name.getText().toString().trim(); ct.rics = cv.address; ct.type = 0;
            if (!ct.name.isEmpty()) svc.store().insertContact(ct);
        }).setNeutralButton(Txt.s(R.string.packet_msg_ric_mute), (d, w) -> { try { svc.muteRic(Integer.parseInt(cv.address), true); } catch (Exception ignored) { } }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void newMessage() {
        final Context c = getContext();
        if (svc == null) return;
        final List<ChannelStore.Contact> cts = svc.store().contacts();
        String[] names = new String[cts.size() + 1]; names[0] = Txt.s(R.string.packet_msg_to_current);
        for (int i = 0; i < cts.size(); i++) names[i + 1] = cts.get(i).name + "  (" + cts.get(i).number + ")";
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_msg_to_title)).setItems(names, (dd, w) -> {
            ChannelStore.Contact ct;
            if (w == 0) { ChannelStore.Channel ch = svc.current(); if (ch == null || ch.txContact <= 0) { android.widget.Toast.makeText(c, Txt.s(R.string.packet_msg_err_no_target), android.widget.Toast.LENGTH_LONG).show(); return; }
                ct = new ChannelStore.Contact(); ct.number = ch.txContact; ct.type = ch.contactType; ct.name = svc.store().contactName(ch.txContact, ch.contactType); }
            else ct = cts.get(w - 1);
            openThread(ct);
        }).show();
    }

    private static String peerLabel(ChannelStore.Contact ct) { return Txt.s(ct.type == 1 ? R.string.packet_msg_peer_tg : ct.type == 2 ? R.string.packet_msg_peer_all : R.string.packet_msg_peer_id, ct.number); }

    public void openThread(ChannelStore.Contact ct) {
        final Context c = getContext(); float d = Theme.dp(c);
        peer = ct;
        if (threadPage != null) content.removeView(threadPage);
        threadPage = new LinearLayout(c); threadPage.setOrientation(VERTICAL); threadPage.setBackground(Neo.page(c));
        String title = ct.name == null || ct.name.isEmpty() ? peerLabel(ct) : ct.name;
        LinearLayout bar = Neo.header(c, title, ct.name == null || ct.name.isEmpty() ? Txt.s(ct.type == 1 ? R.string.packet_msg_type_tg : ct.type == 2 ? R.string.packet_msg_type_all : R.string.packet_msg_type_private) : peerLabel(ct));
        ImageView back = Neo.iconButton(c, "left", Neo.CYAN, Txt.s(R.string.common_back)); back.setOnClickListener(v -> back());
        LayoutParams bl = new LayoutParams((int) (44 * d), (int) (44 * d)); bl.rightMargin = (int) (12 * d);
        bar.addView(back, 0, bl);
        LayoutParams avl = new LayoutParams((int) (38 * d), (int) (38 * d)); avl.rightMargin = (int) (10 * d);
        bar.addView(ContactsView.avatar(c, ct.type, ct.name, 38), 1, avl);
        threadPage.addView(bar);
        analogNote = Neo.text(c, Txt.s(R.string.packet_msg_analog_note), Neo.AMBER_LIGHT, 13.5f, Neo.uiSemi(c));
        analogNote.setGravity(Gravity.CENTER); analogNote.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        analogNote.setBackground(Neo.outline(c, Neo.AMBER, 0x18, 10, 1));
        LayoutParams anl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); anl.setMargins((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        threadPage.addView(analogNote, anl);
        threadList = new ListView(c); threadAdapter = new ThreadAdapter(); threadList.setAdapter(threadAdapter); threadList.setDivider(null); threadList.setDividerHeight(0);
        threadList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        threadList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL); threadList.setStackFromBottom(true);
        threadList.setPadding(0, (int) (6 * d), 0, (int) (6 * d)); threadList.setClipToPadding(false);
        threadPage.addView(threadList, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout compose = new LinearLayout(c); compose.setGravity(Gravity.CENTER_VERTICAL); compose.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (10 * d));
        compose.setBackgroundColor(Neo.BAR);
        ImageView quick = Neo.iconButton(c, "bolt", Neo.AMBER, Txt.s(R.string.packet_msg_quick));
        LayoutParams ql = new LayoutParams((int) (44 * d), (int) (44 * d)); ql.rightMargin = (int) (8 * d);
        compose.addView(quick, ql);
        final EditText input = new EditText(c); input.setHint(Txt.s(R.string.packet_msg_hint)); input.setTextColor(Neo.TEXT); input.setHintTextColor(Neo.DIM); input.setTextSize(16); input.setTypeface(Neo.uiSemi(c));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setMaxLines(3); input.setMinHeight((int) (44 * d));
        input.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x33), 14)); input.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
        quick.setOnClickListener(v -> quickReplies(input));
        compose.addView(input, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageView send = new ImageView(c); send.setImageDrawable(new Neo.IconDrawable("send", 0xFF04121A)); send.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int sp = (int) (12 * d); send.setPadding(sp, sp, sp, sp); send.setContentDescription(Txt.s(R.string.common_send)); send.setClickable(true); send.setFocusable(true);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable(); sg.setShape(android.graphics.drawable.GradientDrawable.OVAL); sg.setColor(Neo.GREEN); send.setBackground(sg);
        LayoutParams sl = new LayoutParams((int) (46 * d), (int) (46 * d)); sl.leftMargin = (int) (8 * d);
        compose.addView(send, sl);
        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty() || svc == null) return;
            if (svc.current() == null || !svc.current().isDigital()) { android.widget.Toast.makeText(c, Txt.s(R.string.packet_msg_err_analog), android.widget.Toast.LENGTH_LONG).show(); return; }
            input.setText("");
            svc.sendSms(peer, t);
            loadThread();
        });
        threadPage.addView(compose);
        content.addView(threadPage);
        refreshAnalogNote();
        loadThread();
    }

    private void refreshAnalogNote() { if (analogNote != null) analogNote.setVisibility(svc != null && svc.current() != null && !svc.current().isDigital() ? VISIBLE : GONE); }

    private void loadThread() {
        if (peer == null || svc == null) return;
        msgs.clear(); msgs.addAll(svc.store().messages(peer.number, peer.type)); threadAdapter.notifyDataSetChanged();
    }

    /** Delivery mark for an outgoing text: colour and wording by module status. */
    private static int statusColor(int status) { return status == 1 ? Neo.GREEN : status == 2 ? Neo.RED : status == 3 ? Neo.AMBER : Neo.DIM; }
    private static String statusText(int status) { return Txt.s(status == 1 ? R.string.packet_msg_st_delivered : status == 2 ? R.string.packet_msg_st_failed : status == 3 ? R.string.packet_msg_st_no_ack : R.string.packet_msg_st_sending); }

    private final class ConvAdapter extends BaseAdapter {
        @Override public int getCount() { return convs.size(); } @Override public Object getItem(int i) { return convs.get(i); } @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            Context c = getContext(); float d = Theme.dp(c);
            if (v == null) {
                LinearLayout outer = new LinearLayout(c); outer.setPadding(0, (int) (3 * d), 0, (int) (3 * d));
                LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setTag("row");
                row.setPadding((int) (10 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d)); row.setMinimumHeight((int) (72 * d));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(0xBF0B1622); g.setCornerRadius(14 * d); g.setStroke((int) d, 0xFF142536);
                row.setBackground(g);
                LinearLayout av = new LinearLayout(c); av.setTag("av"); row.addView(av, new LayoutParams((int) (46 * d), (int) (46 * d)));
                LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL); col.setPadding((int) (12 * d), 0, 0, 0);
                LinearLayout l1 = new LinearLayout(c); l1.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = Neo.text(c, "", Neo.TEXT, 17.5f, Neo.ui(c)); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setTag("name");
                l1.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                TextView time = Neo.text(c, "", Neo.DIM, 12.5f, Neo.mono(c)); time.setTag("time"); time.setPadding((int) (8 * d), 0, 0, 0); l1.addView(time);
                col.addView(l1);
                TextView last = Neo.text(c, "", Neo.MUTED, 14, Neo.uiMedium(c)); last.setSingleLine(true); last.setEllipsize(android.text.TextUtils.TruncateAt.END); last.setTag("last");
                last.setPadding(0, (int) (4 * d), 0, 0); col.addView(last);
                row.addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                outer.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                outer.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v = outer;
            }
            ChannelStore.Conversation cv = convs.get(i);
            LinearLayout row = v.findViewWithTag("row");
            LinearLayout av = row.findViewWithTag("av"); av.removeAllViews();
            av.addView(ContactsView.avatar(c, cv.type, cv.name, 46), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            ChannelStore.Contact tmp = new ChannelStore.Contact(); tmp.type = cv.type; tmp.number = cv.peer;
            String who = cv.name == null || cv.name.isEmpty() ? peerLabel(tmp) : cv.name;
            if ("page".equals(cv.transport)) who = Txt.s(R.string.packet_msg_chip_page, who); else if ("m17".equals(cv.transport)) who = "M17  " + who;
            String unread = cv.unread > 0 ? "  (" + cv.unread + ")" : "";
            ((TextView) row.findViewWithTag("name")).setText(who + unread);
            ((TextView) row.findViewWithTag("time")).setText(df.format(new Date(cv.lastTs)));
            ((TextView) row.findViewWithTag("last")).setText(cv.outgoing ? Txt.s(R.string.packet_msg_you, cv.lastText) : cv.lastText);
            return v;
        }
    }

    private static final String DEFAULT_QUICK = "QSL\nCopy, I will call you back\nOn my way\nWhere are you?\nSwitch to the other channel\nAll OK here";   // i18n: sent on air (default texts; the user edits the list)
    private java.util.List<String> quickList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : getContext().getSharedPreferences("radio", Context.MODE_PRIVATE).getString("quickReplies", DEFAULT_QUICK).split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
    /** Canned texts: one tap fills the composer; the last entry edits the list. */
    private void quickReplies(final EditText input) {
        final java.util.List<String> q = quickList();
        String[] items = new String[q.size() + 1]; for (int i = 0; i < q.size(); i++) items[i] = q.get(i); items[q.size()] = Txt.s(R.string.packet_msg_quick_edit);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.packet_msg_quick)).setItems(items, (d2, w) -> {
            if (w < q.size()) { input.setText(q.get(w)); input.setSelection(input.getText().length()); return; }
            final EditText et = new EditText(getContext()); et.setText(String.join("\n", q)); et.setMinLines(5);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.packet_msg_quick)).setMessage(Txt.s(R.string.packet_msg_quick_body)).setView(et)
                    .setPositiveButton(Txt.s(R.string.common_save), (d3, w3) -> getContext().getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putString("quickReplies", et.getText().toString()).apply())
                    .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        }).show();
    }

    private final class ThreadAdapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); } @Override public Object getItem(int i) { return msgs.get(i); } @Override public long getItemId(int i) { return msgs.get(i).id; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            Context c = getContext(); float d = Theme.dp(c);
            ChannelStore.Message m = msgs.get(i);
            LinearLayout row = new LinearLayout(c); row.setOrientation(VERTICAL); row.setGravity(m.outgoing ? Gravity.END : Gravity.START);
            row.setPadding((int) (14 * d), (int) (5 * d), (int) (14 * d), (int) (5 * d));
            if (!m.outgoing && m.sender > 0) {                                  // a talkgroup thread: who wrote it
                String who = svc.callerName(m.sender);
                TextView nm = Neo.text(c, who == null || who.isEmpty() ? String.valueOf(m.sender) : who, Neo.CYAN_LIGHT, 12.5f, Neo.ui(c)); nm.setPadding((int) (6 * d), 0, 0, (int) (2 * d));
                row.addView(nm, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            }
            TextView b = Neo.text(c, m.text, Neo.TEXT, 16, Neo.uiSemi(c)); b.setMaxWidth((int) (270 * d)); b.setLineSpacing(0, 1.1f);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    m.outgoing ? new int[] { 0xFF0F4258, 0xFF0A2C3C } : new int[] { 0xFF16222F, 0xFF101A25 });
            float r = 18 * d, tail = 5 * d;
            g.setCornerRadii(m.outgoing ? new float[] { r, r, r, r, tail, tail, r, r } : new float[] { r, r, r, r, r, r, tail, tail });
            g.setStroke((int) d, m.outgoing ? Neo.alpha(Neo.CYAN, 0x88) : 0xFF22364A);
            b.setBackground(g); b.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
            row.addView(b, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));   // hug the text so yours sit right, theirs left
            LinearLayout meta = new LinearLayout(c); meta.setPadding((int) (4 * d), (int) (3 * d), (int) (4 * d), 0);
            meta.addView(Neo.text(c, tf.format(new Date(m.ts)), Neo.DIM, 12, Neo.mono(c)));
            if (m.outgoing) { TextView st = Neo.text(c, "  " + statusText(m.status), statusColor(m.status), 12.5f, Neo.ui(c)); meta.addView(st); }
            row.addView(meta, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            return row;
        }
    }

    // RadioService.Listener
    @Override public void onSms(int from, int type, String text) { if (peer != null && peer.number == from) loadThread(); else reload(); }
    @Override public void onSmsResult(boolean ok) { loadThread(); reload(); }
    @Override public void onChannel(ChannelStore.Channel ch) { refreshAnalogNote(); } @Override public void onRx(boolean r) { } @Override public void onTx(boolean t) { } @Override public void onRssi(int dbm) { }
    @Override public void onAudioLevel(int a) { } @Override public void onHistory(String l) { } @Override public void onCaller(String t) { } @Override public void onLog(String s) { }
    @Override public void onLink(String s) { } @Override public void onLocation(String t) { }
    @Override public void onModes(boolean a, int b, boolean c2, boolean d2, boolean e, boolean f2, boolean g, String z) { }
    @Override public void onCaption(boolean en, String live, String sentence) { }
}
