/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Txt;
import java.util.ArrayList;
import java.util.List;

/**
 * A talkgroup picked on the intercom for the DMR channel in use, without editing the channel: the radio transmits a
 * group call to it and hears it, until another channel (or the VFO) is tuned. The saved channel never changes.
 */
public final class TalkgroupOverride {
    private long channelId = -1;
    private int tg;

    /** One entry of the intercom's list: the channel's own call first, then the group contacts. */
    public static final class Option {
        public final int number, type; public final String label; public final boolean programmed;
        Option(int number, int type, String label, boolean programmed) { this.number = number; this.type = type; this.label = label; this.programmed = programmed; }
    }

    /** A channel is about to be written to the radio: tuning another channel, or the VFO, ends the override (the same channel rewritten keeps it). */
    public synchronized void onApply(ChannelStore.Channel ch) {
        if (ch == null || ch.id <= 0 || ch.id != channelId) { channelId = -1; tg = 0; }
    }

    /** Pick {@code talkgroup} for {@code ch}; its own call number (as a group call) or 0 goes back to the channel. False: not a saved DMR channel. */
    public synchronized boolean set(ChannelStore.Channel ch, int talkgroup) {
        if (ch == null || !ch.isDigital() || ch.id <= 0) return false;
        if (talkgroup <= 0 || (talkgroup == ch.txContact && ch.contactType == 1)) { channelId = -1; tg = 0; }
        else { channelId = ch.id; tg = talkgroup; }
        return true;
    }

    /** The picked talkgroup for this channel, or 0 when it transmits to its own call number. */
    public synchronized int talkgroup(ChannelStore.Channel ch) { return ch != null && ch.id > 0 && ch.id == channelId ? tg : 0; }

    /** The call written to the radio for {@code ch}: {txContact, contactType}. */
    public int[] call(ChannelStore.Channel ch) { int t = talkgroup(ch); return t > 0 ? new int[] { t, 1 } : new int[] { ch.txContact, ch.contactType }; }

    /** The receive list written with it: the picked talkgroup is added (the radio passes only listed talkgroups), the 32nd entry giving way when full. */
    public int[] groups(ChannelStore.Channel ch) { return withTalkgroup(ch.groups, talkgroup(ch)); }

    static int[] withTalkgroup(int[] groups, int t) {
        if (t <= 0) return groups;
        for (int g : groups) if (g == t) return groups;
        int n = Math.min(groups.length, 31);
        int[] out = new int[n + 1]; out[0] = t; System.arraycopy(groups, 0, out, 1, n);
        return out;
    }

    /** The intercom's list: the channel's programmed call, then every group contact (by name) not already listed. */
    public static List<Option> options(ChannelStore.Channel ch, List<ChannelStore.Contact> contacts) {
        List<Option> out = new ArrayList<>();
        String kind = ch.contactType == 1 ? "TG " : ch.contactType == 2 ? Txt.s(R.string.channels_tg_kind_all) + " " : "ID ";   // i18n: protocol words (TG, ID)
        String own = null;
        if (contacts != null) for (ChannelStore.Contact c : contacts) if (c.number == ch.txContact && c.type == (ch.contactType == 0 ? 0 : 1)) own = c.name;
        out.add(new Option(ch.txContact, ch.contactType, Txt.s(R.string.channels_tg_programmed, kind + ch.txContact + (own != null && !own.isEmpty() ? "  " + own : "")), true));
        if (contacts != null) for (ChannelStore.Contact c : contacts) {
            if (c.type != 1 || c.number <= 0 || (c.number == ch.txContact && ch.contactType == 1)) continue;
            boolean dup = false; for (Option o : out) if (o.number == c.number && o.type == 1) dup = true;
            if (!dup) out.add(new Option(c.number, 1, "TG " + c.number + (c.name.isEmpty() ? "" : "  " + c.name), false));   // i18n: protocol word (TG) and data
        }
        return out;
    }
}
