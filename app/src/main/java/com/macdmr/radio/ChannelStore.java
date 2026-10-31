/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Our own channel table (one row per channel, numbered 1..n in display order). The field set is the
 * module's: it maps 1:1 onto the analog (0x23) / digital (0x22) command bodies.
 * First run: imports the stock app's database (per-area SQLite, table "channel_name") through a root copy,
 * so a user keeps every channel they already programmed.
 */
public final class ChannelStore extends SQLiteOpenHelper {
    /** Longest channel name the editors accept and the repeater planner writes (the stock app's column width). One place to change it. */
    public static final int NAME_MAX = 32;

    /** A typed or generated channel name, trimmed and cut to {@link #NAME_MAX}. */
    public static String fitName(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= NAME_MAX ? s : s.substring(0, NAME_MAX).trim();
    }

    public static final class Channel {
        public static final int TYPE_DMR = 0, TYPE_FM = 1, TYPE_M17 = 2;
        public long id; public int number; public String name = ""; public int type;          // 0 digital, 1 analog, 2 M17
        public int rxFreq, txFreq, power, band = 1, sq = 2, rxType, rxSubCode, txType, txSubCode, relay = 2, mode;
        public int cc = 1, inSlot, outSlot, contactType = 1, txContact, encryptSw = 2, interrupt = 2;
        public String encryptKey = ""; public int[] groups = new int[0]; public boolean active;
        public String location = "";
        public String notes = "";                  // the owner's notes on this channel (shareable as an option)
        public boolean rxOnly;                     // the owner made this channel receive only (never transmits)
        public String m17dest = "@ALL";            // who an M17 channel calls; @ALL is everyone. The channel-access number is cc (0-15).
        public double lat, lon, elev;              // from the module's location table (0 = none)
        public boolean softSq; public int softSqLevel = 5;   // software squelch (analog)
        public boolean isDigital() { return type == TYPE_DMR; }
        public boolean isM17() { return type == TYPE_M17; }
        /** Channel access number, 0 to 15. Stored in {@code cc} so a DMR colour code and an M17 CAN share one column. */
        public int can() { return cc < 0 ? 0 : Math.min(15, cc); }
        /** Callsign the air will accept, or @ALL. Blank becomes @ALL. */
        public String m17Dest() { return cleanM17Dest(m17dest); }
        public static String cleanM17Dest(String raw) {
            if (raw == null) return "@ALL";
            String u = raw.trim().toUpperCase(java.util.Locale.US);
            if (u.isEmpty() || u.equals("@ALL") || u.equals("ALL")) return "@ALL";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < u.length() && sb.length() < 9; i++) {
                char ch = u.charAt(i);
                if (" ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/.".indexOf(ch) >= 0 && ch != ' ') sb.append(ch);
            }
            return sb.length() == 0 ? "@ALL" : sb.toString();
        }
        public String freqText(int hz) { return String.format(java.util.Locale.US, "%.3f", hz / 1e6); }
    }

    /**
     * A contact: a DMR ID or talkgroup (type 0 private, 1 group, 2 all) plus SignalDeck's own identity fields, which the
     * module's contact table never sees: a callsign (optional -SSID), pager RICs, notes, a home location the user enters
     * and a last-heard position the app fills by itself (APRS). The two locations are separate on purpose: an automatic
     * fix never overwrites what the user typed.
     */
    public static final class Contact {
        public long id; public String name = ""; public int number; public int type;
        public String callsign = "", rics = "", notes = "", homeLabel = "";
        /** 0 nothing filled yet, 1 the callsign came from the RadioID list, 2 the user set or cleared it (never refilled). */
        public int callsignAuto;
        public double homeLat = Double.NaN, homeLon = Double.NaN, lastLat = Double.NaN, lastLon = Double.NaN;
        public long lastTs; public String lastSource = "";
        public boolean hasHome() { return !Double.isNaN(homeLat) && !Double.isNaN(homeLon); }
        public boolean hasLast() { return !Double.isNaN(lastLat) && !Double.isNaN(lastLon) && lastTs > 0; }
        /** The RICs as numbers, in the order typed; junk and out-of-range values dropped. */
        public int[] ricList() { return parseRics(rics); }
        /** The callsign without its SSID, upper case. */
        public String baseCall() { String c = callsign.trim().toUpperCase(java.util.Locale.US); int d = c.indexOf('-'); return d > 0 ? c.substring(0, d) : c; }
    }
    /** "123, 456;789" to {123, 456, 789}; a RIC is 0 to 2097151 (21 bits). */
    public static int[] parseRics(String s) {
        List<Integer> out = new ArrayList<>();
        if (s != null) for (String x : s.split("[,;\\s]+")) { try { int r = Integer.parseInt(x.trim()); if (r >= 0 && r <= 2097151 && !out.contains(r)) out.add(r); } catch (Exception e) { } }
        int[] a = new int[out.size()]; for (int i = 0; i < a.length; i++) a[i] = out.get(i); return a;
    }
    /** The canonical text of a RIC list: "123;456". */
    public static String ricText(int[] rics) { StringBuilder b = new StringBuilder(); for (int r : rics) { if (b.length() > 0) b.append(';'); b.append(r); } return b.toString(); }
    public static final class Message { public long id; public int peer, type; public boolean outgoing; public long ts; public String text; public int status; public int sender; }   // sender: who wrote a talkgroup text (0 = the peer)   // status 0 pending 1 sent 2 failed
    public static final class Conversation { public int peer, type; public String name, lastText; public long lastTs; public boolean outgoing; public String transport = "dmr"; public String threadKey = ""; public int unread; public String address = ""; }
    /** One stored page or M17 text. transport is "page" or "m17". */
    public static final class PageRow { public long id, ts; public int hz, ric, func, baud, corrected, unread; public boolean outgoing; public String text = "", signer = "", threadKey = "", transport = "page"; }

    public static final class Zone { public long id; public String name; public int[] numbers = new int[0]; }

    /** The radio's two bands: 136–174 and 400–480 MHz. */
    public static boolean inBand(int hz) { return com.macdmr.radio.DeviceProfile.inBands(hz); }   // the phone's radio (the W6 tunes 400-470 MHz only)
    /** Whether this phone's radio can tune the channel: its receive and transmit frequencies both inside the profile's bands (the module refuses the channel otherwise). */
    public static boolean tunable(Channel c) { return tunable(com.macdmr.radio.DeviceProfile.detect(), c); }
    public static boolean tunable(com.macdmr.radio.DeviceProfile p, Channel c) { return c != null && p.tunable(c.rxFreq) && p.tunable(c.txFreq); }
    /** The channels of {@code in} this radio can tune, in order. */
    public static List<Channel> tunable(List<Channel> in) { List<Channel> out = new ArrayList<>(); if (in != null) for (Channel c : in) if (tunable(c)) out.add(c); return out; }

    public ChannelStore(Context c) { super(c, "radio.db", null, 5); }
    /** Run after every change to the channel list or the zones, from any caller (RadioService turns it into one onChannels event). */
    public volatile Runnable onChange;
    private void changed() { Runnable r = onChange; if (r != null) try { r.run(); } catch (Throwable ignored) { } }
    /** A separate database file (the backup self-test restores into a scratch copy). */
    public ChannelStore(Context c, String file) { super(c, file, null, 5); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channels (_id INTEGER PRIMARY KEY AUTOINCREMENT, number INTEGER, name TEXT, type INTEGER, rxFreq INTEGER, txFreq INTEGER,"
                + " power INTEGER, band INTEGER, sq INTEGER, rxType INTEGER, rxSubCode INTEGER, txType INTEGER, txSubCode INTEGER, relay INTEGER, mode INTEGER,"
                + " cc INTEGER, inSlot INTEGER, outSlot INTEGER, contactType INTEGER, txContact INTEGER, encryptSw INTEGER, encryptKey TEXT, interrupt INTEGER,"
                + " groups TEXT, active INTEGER, location TEXT, lat REAL DEFAULT 0, lon REAL DEFAULT 0, elev REAL DEFAULT 0, softSq INTEGER DEFAULT 0, softSqLevel INTEGER DEFAULT 5)");
        db.execSQL("CREATE TABLE zones (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, numbers TEXT)");
        db.execSQL("CREATE TABLE contacts (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, number INTEGER, type INTEGER)");
        db.execSQL("CREATE TABLE history (_id INTEGER PRIMARY KEY AUTOINCREMENT, channel INTEGER, ts INTEGER, kind TEXT, text TEXT, rssi INTEGER, dmrId INTEGER)");
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER, type INTEGER, outgoing INTEGER, ts INTEGER, text TEXT, status INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // nothing user-created yet at v1: rebuild and let the stock import run again
        db.execSQL("DROP TABLE IF EXISTS channels"); db.execSQL("DROP TABLE IF EXISTS contacts"); db.execSQL("DROP TABLE IF EXISTS history"); db.execSQL("DROP TABLE IF EXISTS messages"); db.execSQL("DROP TABLE IF EXISTS zones");
        onCreate(db);
    }

    /** Tables added after v5 are created here (onUpgrade rebuilds everything, so the version stays put). */
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        db.execSQL("CREATE TABLE IF NOT EXISTS tglists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, tgs TEXT)");
        // nets held on a channel: tied to the channel's row id, so renumbering, zones and imports never move them
        db.execSQL("CREATE TABLE IF NOT EXISTS nets (_id INTEGER PRIMARY KEY AUTOINCREMENT, channelId INTEGER, name TEXT, days INTEGER, minute INTEGER, duration INTEGER, notes TEXT)");
        boolean hasNotes = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(channels)", null)) { while (c.moveToNext()) if ("notes".equals(c.getString(1))) hasNotes = true; }
        if (!hasNotes) db.execSQL("ALTER TABLE channels ADD COLUMN notes TEXT DEFAULT ''");
        boolean hasRxOnly = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(channels)", null)) { while (c.moveToNext()) if ("rxOnly".equals(c.getString(1))) hasRxOnly = true; }
        if (!hasRxOnly) db.execSQL("ALTER TABLE channels ADD COLUMN rxOnly INTEGER DEFAULT 0");
        boolean hasM17 = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(channels)", null)) { while (c.moveToNext()) if ("m17dest".equals(c.getString(1))) hasM17 = true; }
        if (!hasM17) db.execSQL("ALTER TABLE channels ADD COLUMN m17dest TEXT DEFAULT '@ALL'");
        boolean hasSender = false;                                             // talkgroup texts: the thread is the talkgroup, each message keeps who wrote it
        try (Cursor c = db.rawQuery("PRAGMA table_info(messages)", null)) { while (c.moveToNext()) if ("sender".equals(c.getString(1))) hasSender = true; }
        if (!hasSender) db.execSQL("ALTER TABLE messages ADD COLUMN sender INTEGER DEFAULT 0");
        // contact identity fields (2026-10-25): SignalDeck's own, never written to the module's contact table
        java.util.Set<String> cc = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(contacts)", null)) { while (c.moveToNext()) cc.add(c.getString(1)); }
        String[][] cols = { { "callsign", "TEXT DEFAULT ''" }, { "rics", "TEXT DEFAULT ''" }, { "notes", "TEXT DEFAULT ''" }, { "homeLabel", "TEXT DEFAULT ''" },
                { "callsignAuto", "INTEGER DEFAULT 0" }, { "homeLat", "REAL" }, { "homeLon", "REAL" }, { "lastLat", "REAL" }, { "lastLon", "REAL" },
                { "lastTs", "INTEGER DEFAULT 0" }, { "lastSource", "TEXT DEFAULT ''" } };
        for (String[] k : cols) if (!cc.contains(k[0])) db.execSQL("ALTER TABLE contacts ADD COLUMN " + k[0] + " " + k[1]);
        // when and how each contact was heard, with the position when the transmission carried one (owner, 2026-10-25)
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_heard (_id INTEGER PRIMARY KEY AUTOINCREMENT, contactId INTEGER, ts INTEGER, source TEXT, detail TEXT, lat REAL, lon REAL, dir INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS contact_heard_ix ON contact_heard (contactId, ts)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pages (_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, hz INTEGER, ric INTEGER, func INTEGER, baud INTEGER, text TEXT, corrected INTEGER, signer TEXT, threadKey TEXT, transport TEXT, outgoing INTEGER DEFAULT 0, unread INTEGER DEFAULT 1)");
        db.execSQL("CREATE INDEX IF NOT EXISTS pages_thread ON pages (threadKey, ts)");
    }

    /** A named talkgroup list (the module's TG lists): a set of talkgroups to drop into a channel's RX group list. */
    public static final class TgList { public long id; public String name = ""; public int[] tgs = new int[0]; }

    public List<TgList> tgLists() {
        List<TgList> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, tgs FROM tglists ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) { TgList t = new TgList(); t.id = c.getLong(0); t.name = c.getString(1); t.tgs = parseInts(c.getString(2)); out.add(t); }
        }
        return out;
    }

    public void saveTgList(TgList t) {
        ContentValues v = new ContentValues(); v.put("name", t.name); v.put("tgs", joinInts(t.tgs));
        if (t.id > 0) getWritableDatabase().update("tglists", v, "_id=?", new String[] { String.valueOf(t.id) }); else t.id = getWritableDatabase().insert("tglists", null, v);
    }

    public void deleteTgList(TgList t) { getWritableDatabase().delete("tglists", "_id=?", new String[] { String.valueOf(t.id) }); }

    /** Up to 32 talkgroups: the channel's own first, then the list's, without duplicates. */
    static int[] mergeGroups(int[] own, int[] add) {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        for (int g : own) if (g > 0) s.add(g);
        for (int g : add) if (g > 0 && s.size() < 32) s.add(g);
        int[] out = new int[Math.min(32, s.size())]; int i = 0; for (int g : s) { if (i >= out.length) break; out[i++] = g; }
        return out;
    }

    /**
     * The module's TG lists (dmrmod_tglists.db): copy the named lists into {@code tglists} and merge each channel's assigned
     * list into its RX group list. {@code stockIdToNumber} maps the stock row _id (what the module keys on) to our channel number.
     */
    private int importModuleTgLists(Context ctx, String stockPackage, java.util.Map<Integer, Integer> stockIdToNumber, StringBuilder err) {
        SQLiteDatabase tdb = copyStock(ctx, stockPackage, "dmrmod_tglists.db", "stock_tglists.db");
        if (tdb == null) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int merged = 0;
        try {
            java.util.Map<Integer, int[]> byId = new java.util.HashMap<>();
            db.delete("tglists", null, null);
            try (Cursor c = tdb.rawQuery("SELECT id, name, tg_ids FROM tg_lists", null)) {
                while (c.moveToNext()) {
                    int[] tgs = parseInts(c.getString(2)); byId.put(c.getInt(0), tgs);
                    if (tgs.length == 0) continue;
                    TgList t = new TgList(); t.name = c.getString(1); t.tgs = tgs; saveTgList(t);
                }
            }
            try (Cursor c = tdb.rawQuery("SELECT channel_id, tg_list_id FROM channel_tglist_assignments", null)) {
                while (c.moveToNext()) {
                    Integer num = stockIdToNumber.get(c.getInt(0)); int[] tgs = byId.get(c.getInt(1));
                    if (num == null || tgs == null || tgs.length == 0) continue;
                    Channel ch = byNumber(num); if (ch == null || !ch.isDigital()) continue;
                    int[] g = mergeGroups(ch.groups, tgs);
                    if (g.length != ch.groups.length) { ContentValues v = new ContentValues(); v.put("groups", joinInts(g)); db.update("channels", v, "number=?", new String[] { String.valueOf(num) }); merged++; }
                }
            }
        } catch (Throwable t) { err.append("tglists: ").append(t.getMessage()).append("; "); }
        finally { tdb.close(); }
        return merged;
    }

    /**
     * For an install imported before TG lists were read: map the stock rows to our channels by name and receive frequency
     * (numbers may have changed since) and import the module's lists and assignments without touching anything else.
     */
    public int repairModuleTgLists(Context ctx, String stockPackage, String areaKey, StringBuilder err) { return repairModuleTgLists(ctx, stockPackage, "database_channel_area_", areaKey, err); }
    /** {@code prefix}: the stock app's per-area file and table name before the area (DeviceProfile.stockAreaPrefix). */
    public int repairModuleTgLists(Context ctx, String stockPackage, String prefix, String areaKey, StringBuilder err) {
        SQLiteDatabase sdb = copyStock(ctx, stockPackage, prefix + areaKey + ".db", "stock_repair.db");
        if (sdb == null) { err.append("stock channels not readable"); return -1; }
        java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
        try (Cursor c = sdb.rawQuery("SELECT _id, channel_name, channel_rxFreq FROM " + prefix + areaKey, null)) {
            List<Channel> mine = all();
            while (c.moveToNext()) {
                String name = c.getString(1); int rx = c.getInt(2);
                for (Channel ch : mine) if (ch.rxFreq == rx && ch.name != null && ch.name.equals(name)) { map.put(c.getInt(0), ch.number); break; }
            }
        } finally { sdb.close(); }
        return importModuleTgLists(ctx, stockPackage, map, err);
    }

    public int count() { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM channels", null)) { return c.moveToFirst() ? c.getInt(0) : 0; } }

    public List<Channel> all() {
        List<Channel> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("channels", null, null, null, null, null, "number")) { while (c.moveToNext()) out.add(read(c)); }
        return out;
    }

    public Channel byNumber(int n) {
        try (Cursor c = getReadableDatabase().query("channels", null, "number=?", new String[] { String.valueOf(n) }, null, null, null)) { return c.moveToFirst() ? read(c) : null; }
    }

    public int activeNumber() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT number FROM channels WHERE active=1 LIMIT 1", null)) { return c.moveToFirst() ? c.getInt(0) : 1; }
    }

    public void setActive(int number) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE channels SET active=0"); db.execSQL("UPDATE channels SET active=1 WHERE number=?", new Object[] { number });
    }

    public long insert(Channel ch) {
        if (ch.number <= 0) ch.number = count() + 1;
        ch.id = getWritableDatabase().insert("channels", null, values(ch)); changed(); return ch.id;
    }

    public void delete(Channel ch) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("channels", "_id=?", new String[] { String.valueOf(ch.id) });
        db.delete("nets", "channelId=?", new String[] { String.valueOf(ch.id) });
        db.execSQL("UPDATE channels SET number = number - 1 WHERE number > ?", new Object[] { ch.number });   // keep 1..n contiguous
        for (Zone z : zones()) {   // zones hold channel numbers: drop this one and follow the renumbering
            List<Integer> l = new ArrayList<>(); boolean changed = false;
            for (int n : z.numbers) { if (n == ch.number) { changed = true; continue; } if (n > ch.number) { n--; changed = true; } l.add(n); }
            if (!changed) continue;
            z.numbers = new int[l.size()]; for (int i = 0; i < l.size(); i++) z.numbers[i] = l.get(i);
            saveZone(z);
        }
        changed();
    }

    /** Frequencies this radio never transmits on: NOAA weather (162.400 to 162.550 MHz), the marine VHF band (156.000 to 157.450 MHz) and the AAR railroad channels (159.810 to 161.565 MHz). */
    /** This channel never transmits: by its frequency (above) or because the owner set it receive only. */
    public static boolean receiveOnly(Channel ch) { return ch != null && (ch.rxOnly || receiveOnly(ch.txFreq)); }
    public static boolean receiveOnly(int txHz) {
        return (txHz >= 162_400_000 && txHz <= 162_550_000) || (txHz >= 156_000_000 && txHz <= 157_450_000) || (txHz >= 159_810_000 && txHz <= 161_565_000);
    }

    public void update(Channel ch) { getWritableDatabase().update("channels", values(ch), "_id=?", new String[] { String.valueOf(ch.id) }); changed(); }

    /**
     * Delete packet lines ("📡 SRC>…") whose source is not a callsign: noise frames that passed the FCS were logged
     * before {@code Ax25.plausible} existed. Returns how many were removed.
     */
    public int purgeGarbledPacketHistory() {
        java.util.List<Long> bad = new java.util.ArrayList<>();
        java.util.regex.Pattern call = java.util.regex.Pattern.compile("^[A-Z0-9]{1,6}(-\\d{1,2})?$");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id, text FROM history WHERE kind = 'pkt'", null)) {
            while (c.moveToNext()) {
                String t = c.getString(1); if (t == null || !t.startsWith("\uD83D\uDCE1 ")) continue;   // only received-packet lines
                String body = t.startsWith("\uD83D\uDCE1 ") ? t.substring(3) : t;
                boolean control = false; for (int i = 0; i < t.length(); i++) { char ch = t.charAt(i); if (ch < 0x20 || ch == 0x7F || ch == '\uFFFD') { control = true; break; } }
                int end = body.length(); for (char stop : new char[] { '>', ':' }) { int k = body.indexOf(stop); if (k > 0 && k < end) end = k; }
                String src = body.substring(0, end).trim();
                boolean outgoing = body.startsWith("Sent ") || body.startsWith("Beacon ") || body.startsWith("Digipeated") || body.startsWith("APRS") || body.startsWith("SSTV") || body.startsWith("DTMF");
                if (control || (!outgoing && !call.matcher(src).matches())) bad.add(c.getLong(0));
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        for (long id : bad) db.delete("history", "_id=?", new String[] { String.valueOf(id) });
        return bad.size();
    }

    public void addHistory(int channel, String kind, String text, int rssi, int dmrId) {
        ContentValues v = new ContentValues();
        v.put("channel", channel); v.put("ts", System.currentTimeMillis()); v.put("kind", kind); v.put("text", text); v.put("rssi", rssi); v.put("dmrId", dmrId);
        getWritableDatabase().insert("history", null, v);
    }

    /** Finished caption sentences, newest first: [ts, channel, text]. */
    /** Finished caption sentences, newest first, optionally only those containing {@code q}: [ts, channel, text]. */
    public List<Object[]> captions(int limit, String q) {
        List<Object[]> out = new ArrayList<>();
        String like = q == null || q.trim().isEmpty() ? null : "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (Cursor c = like == null ? getReadableDatabase().rawQuery("SELECT ts, channel, text FROM history WHERE kind='txt' ORDER BY ts DESC LIMIT " + limit, null)
                : getReadableDatabase().rawQuery("SELECT ts, channel, text FROM history WHERE kind='txt' AND text LIKE ? ESCAPE '\\' ORDER BY ts DESC LIMIT " + limit, new String[] { like })) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2) });
        }
        return out;
    }

    public List<Object[]> captions(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, text FROM history WHERE kind='txt' ORDER BY ts DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2) });
        }
        return out;
    }

    /** A channel's own history, newest first: [ts, kind, text, rssi, dmrId]. Captions (their own page) and car, media, coverage and channel-change events (Last heard's event sections) are left out. */
    public List<Object[]> channelHistory(int number, int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, kind, text, rssi, dmrId FROM history WHERE channel=? AND kind NOT IN ('txt','car','media','cov','chan') ORDER BY ts DESC LIMIT " + limit, new String[] { String.valueOf(number) })) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) });
        }
        return out;
    }
    public int channelHistoryCount(int number) { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history WHERE channel=? AND kind NOT IN ('txt','car','media','cov','chan')", new String[] { String.valueOf(number) })) { return c.moveToFirst() ? c.getInt(0) : 0; } }
    /** Removes a channel's history lines except the captions (they have their own page). */
    public void clearChannelHistory(int number) { getWritableDatabase().delete("history", "channel=? AND kind<>'txt'", new String[] { String.valueOf(number) }); }
    /** Per channel, the newest thing heard on it: channel number to [ts, kind, text]. */
    public java.util.Map<Integer, Object[]> channelLastHeard() {
        java.util.Map<Integer, Object[]> out = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT channel, MAX(ts), kind, text FROM history WHERE kind IN ('rx','dmr','sms','pkt','tone','rec','m17') GROUP BY channel", null)) {
            while (c.moveToNext()) out.put(c.getInt(0), new Object[] { c.getLong(1), c.getString(2), c.getString(3) });
        }
        return out;
    }

    /** Newest events first for Last heard: [ts, channel, kind, text, dmrId]. */
    public List<Object[]> lastHeard(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, kind, text, dmrId FROM history WHERE kind IN ('rx','dmr','sms','rec','pkt') ORDER BY ts DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.getInt(4) });
        }
        return out;
    }

    /** Warnings, errors and car/coverage events (the Alerts and Events sections of Last heard), newest first. */
    public List<Object[]> events(long sinceTs, int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, kind, text, dmrId FROM history WHERE ts>=? AND (kind IN ('car','cov','warn','busy','alert') OR text LIKE '⛔%' OR text LIKE '⚠%') ORDER BY ts DESC LIMIT " + limit,
                new String[] { String.valueOf(sinceTs) })) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.getInt(4) });
        }
        return out;
    }

    /** Receptions since {@code sinceTs} (analog and DMR), for the activity statistics: [ts, channel, kind, text]. */
    /** Receptions and captions since {@code sinceTs}, for finding nets in what the radio heard. */
    public List<com.macdmr.radio.ham.NetFinder.Rx> netRows(long sinceTs) {
        List<com.macdmr.radio.ham.NetFinder.Rx> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, kind, text, dmrId FROM history WHERE ts>=? AND kind IN ('rx','dmr','txt')", new String[] { String.valueOf(sinceTs) })) {
            while (c.moveToNext()) out.add(new com.macdmr.radio.ham.NetFinder.Rx(c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.isNull(4) ? 0 : c.getInt(4)));
        }
        return out;
    }

    public List<Object[]> activity(long sinceTs) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, kind, text FROM history WHERE ts>=? AND kind IN ('rx','dmr')", new String[] { String.valueOf(sinceTs) })) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2), c.getString(3) });
        }
        return out;
    }

    /** Every history line within {@code ms} of {@code ts}, oldest first (the "Around this time" list of an event). */
    public List<Object[]> around(long ts, long ms) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, channel, kind, text FROM history WHERE ts BETWEEN ? AND ? ORDER BY ts ASC LIMIT 40",
                new String[] { String.valueOf(ts - ms), String.valueOf(ts + ms) })) {
            while (c.moveToNext()) out.add(new Object[] { c.getLong(0), c.getInt(1), c.getString(2), c.getString(3) });
        }
        return out;
    }

    public List<String> recentHistory(int channel, int limit) {
        List<String> out = new ArrayList<>();
        // the module shows the newest N lines in chronological order (oldest at the top)
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, text, rssi FROM (SELECT ts, text, rssi FROM history WHERE channel=? AND kind<>'txt' ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC", new String[] { String.valueOf(channel), String.valueOf(limit) })) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            while (c.moveToNext()) out.add(f.format(new java.util.Date(c.getLong(0))) + " " + c.getString(1) + (c.getInt(2) != -999 ? " " + c.getInt(2) + " dBm" : ""));
        }
        return out;
    }

    // ---- nets held on channels ----

    private static final String NET_QUERY = "SELECT n._id, n.channelId, c.number, n.name, n.days, n.minute, n.duration, n.notes FROM nets n JOIN channels c ON c._id = n.channelId";
    private static com.macdmr.radio.ham.NetFinder.Manual readNet(Cursor c) {
        com.macdmr.radio.ham.NetFinder.Manual m = new com.macdmr.radio.ham.NetFinder.Manual();
        m.id = c.getLong(0); m.channelId = c.getLong(1); m.channel = c.getInt(2); m.name = c.getString(3) == null ? "" : c.getString(3);
        m.days = c.getInt(4); m.minute = c.getInt(5); m.durationMin = c.getInt(6); m.notes = c.getString(7) == null ? "" : c.getString(7);
        return m;
    }
    /** Every net on a channel that still exists, by channel number and start time. */
    public List<com.macdmr.radio.ham.NetFinder.Manual> nets() {
        List<com.macdmr.radio.ham.NetFinder.Manual> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(NET_QUERY + " ORDER BY c.number, n.minute", null)) { while (c.moveToNext()) out.add(readNet(c)); }
        return out;
    }
    public List<com.macdmr.radio.ham.NetFinder.Manual> netsOf(long channelId) {
        List<com.macdmr.radio.ham.NetFinder.Manual> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(NET_QUERY + " WHERE n.channelId=? ORDER BY n.minute", new String[] { String.valueOf(channelId) })) { while (c.moveToNext()) out.add(readNet(c)); }
        return out;
    }
    /** Insert (id 0) or update a net; a net without a channel id is put on the channel with its number. */
    public void saveNet(com.macdmr.radio.ham.NetFinder.Manual m) {
        if (m.channelId <= 0) { Channel ch = byNumber(m.channel); if (ch == null) return; m.channelId = ch.id; }
        ContentValues v = new ContentValues();
        v.put("channelId", m.channelId); v.put("name", m.name); v.put("days", m.days); v.put("minute", m.minute); v.put("duration", m.durationMin); v.put("notes", m.notes == null ? "" : m.notes);
        if (m.id > 0 && getWritableDatabase().update("nets", v, "_id=?", new String[] { String.valueOf(m.id) }) > 0) return;
        m.id = getWritableDatabase().insert("nets", null, v);
    }
    public void deleteNet(long id) { getWritableDatabase().delete("nets", "_id=?", new String[] { String.valueOf(id) }); }

    /** Nets added before they lived in this database (prefs "nets"/"manual", by channel number) move here once. */
    public void migrateNets(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("nets", Context.MODE_PRIVATE);
        String old = p.getString("manual", null);
        if (old == null) return;
        for (com.macdmr.radio.ham.NetFinder.Manual m : com.macdmr.radio.ham.NetFinder.decode(old)) { m.id = 0; m.channelId = 0; saveNet(m); }
        p.edit().remove("manual").apply();
    }

    // ---- favourites: starred channels form the "★ Favourites" zone (zone picker, stepping and scanning use it) ----
    public static final String FAV_ZONE = "\u2605 Favourites";
    public Zone favZone() { for (Zone z : zones()) if (FAV_ZONE.equals(z.name)) return z; return null; }
    public java.util.Set<Integer> favourites() { java.util.Set<Integer> s = new java.util.HashSet<>(); Zone z = favZone(); if (z != null) for (int n : z.numbers) s.add(n); return s; }
    public void setFavourite(int number, boolean on) {
        Zone z = favZone();
        if (z == null) { if (!on) return; z = new Zone(); z.name = FAV_ZONE; z.numbers = new int[0]; }
        List<Integer> nums = new ArrayList<>(); for (int n : z.numbers) if (n != number) nums.add(n);
        if (on) nums.add(number);
        z.numbers = new int[nums.size()]; for (int i = 0; i < nums.size(); i++) z.numbers[i] = nums.get(i);
        saveZone(z);
    }

    // ---- zones ----
    public List<Zone> zones() {
        List<Zone> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("zones", null, null, null, null, null, "name COLLATE NOCASE")) {
            while (c.moveToNext()) { Zone z = new Zone(); z.id = c.getLong(0); z.name = c.getString(1); z.numbers = parseInts(c.getString(2)); out.add(z); }
        }
        return out;
    }
    public Zone zone(long id) { for (Zone z : zones()) if (z.id == id) return z; return null; }
    public long saveZone(Zone z) {
        ContentValues v = new ContentValues(); v.put("name", z.name); v.put("numbers", joinInts(z.numbers));
        if (z.id > 0) getWritableDatabase().update("zones", v, "_id=?", new String[] { String.valueOf(z.id) });
        else z.id = getWritableDatabase().insert("zones", null, v);
        changed(); return z.id;
    }
    public void deleteZone(long id) { getWritableDatabase().delete("zones", "_id=?", new String[] { String.valueOf(id) }); changed(); }

    /** A zone's channel numbers with {@code add} joined in: sorted, each number once. */
    static int[] withNumbers(int[] zone, java.util.Collection<Integer> add) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>(); for (int n : zone) s.add(n); for (Integer n : add) if (n != null && n > 0) s.add(n);
        int[] out = new int[s.size()]; int i = 0; for (int n : s) out[i++] = n; return out;
    }
    /** A zone's channel numbers without {@code remove}, in their order. */
    static int[] withoutNumbers(int[] zone, java.util.Collection<Integer> remove) {
        java.util.List<Integer> l = new ArrayList<>(); for (int n : zone) if (!remove.contains(n)) l.add(n);
        int[] out = new int[l.size()]; for (int i = 0; i < out.length; i++) out[i] = l.get(i); return out;
    }
    /** The order to delete several channels in: highest number first, each once, so deleting one never renumbers one still to go. */
    static List<Integer> deleteOrder(java.util.Collection<Integer> numbers) {
        List<Integer> l = new ArrayList<>(new java.util.TreeSet<>(numbers)); java.util.Collections.reverse(l); return l;
    }
    private static int[] parseInts(String s) {
        List<Integer> l = new ArrayList<>();
        if (s != null) for (String t : s.split("[,;\\- ]")) { t = t.trim(); if (!t.isEmpty()) try { l.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { } }
        int[] a = new int[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a;
    }
    private static String joinInts(int[] a) { StringBuilder b = new StringBuilder(); for (int x : a) { if (b.length() > 0) b.append(','); b.append(x); } return b.toString(); }

    // ---- contacts ----
    public List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("contacts", null, null, null, null, null, "name COLLATE NOCASE")) { while (c.moveToNext()) out.add(readContact(c)); }
        return out;
    }
    private static Contact readContact(Cursor c) {
        Contact ct = new Contact();
        ct.id = c.getLong(c.getColumnIndexOrThrow("_id")); ct.name = col(c, "name", ""); ct.number = coli(c, "number", 0); ct.type = coli(c, "type", 0);
        ct.callsign = col(c, "callsign", ""); ct.rics = col(c, "rics", ""); ct.notes = col(c, "notes", ""); ct.homeLabel = col(c, "homeLabel", "");
        ct.callsignAuto = coli(c, "callsignAuto", 0); ct.homeLat = cold(c, "homeLat"); ct.homeLon = cold(c, "homeLon"); ct.lastLat = cold(c, "lastLat"); ct.lastLon = cold(c, "lastLon");
        int i = c.getColumnIndex("lastTs"); ct.lastTs = i >= 0 && !c.isNull(i) ? c.getLong(i) : 0; ct.lastSource = col(c, "lastSource", "");
        return ct;
    }
    private static double cold(Cursor c, String name) { int i = c.getColumnIndex(name); return i >= 0 && !c.isNull(i) ? c.getDouble(i) : Double.NaN; }
    private static Double dbl(double v) { return Double.isNaN(v) ? null : v; }
    public String contactName(int number, int type) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM contacts WHERE number=? ORDER BY (type=?) DESC LIMIT 1", new String[] { String.valueOf(number), String.valueOf(type) })) { return c.moveToFirst() ? c.getString(0) : ""; }
    }
    public Contact contactById(long id) {
        try (Cursor c = getReadableDatabase().query("contacts", null, "_id=?", new String[] { String.valueOf(id) }, null, null, null)) { return c.moveToFirst() ? readContact(c) : null; }
    }
    /** The contact with this number of the wanted type first, else any contact with the number. */
    public Contact contactByNumber(int number, int type) {
        try (Cursor c = getReadableDatabase().query("contacts", null, "number=?", new String[] { String.valueOf(number) }, null, null, "(type=" + Math.max(0, Math.min(2, type)) + ") DESC LIMIT 1")) { return c.moveToFirst() ? readContact(c) : null; }
    }
    /** The contact whose callsign is this one: exact (with SSID) first, else the same base callsign. */
    public Contact contactByCallsign(String call) {
        if (call == null) return null;
        String want = call.trim().toUpperCase(java.util.Locale.US); if (want.isEmpty()) return null;
        int d = want.indexOf('-'); String base = d > 0 ? want.substring(0, d) : want;
        Contact sameBase = null;
        for (Contact ct : contacts()) {
            if (ct.callsign.isEmpty()) continue;
            String have = ct.callsign.trim().toUpperCase(java.util.Locale.US);
            if (have.equals(want)) return ct;
            if (sameBase == null && ct.baseCall().equals(base)) sameBase = ct;
        }
        return sameBase;
    }
    /** The contact that has this pager RIC. */
    public Contact contactByRic(int ric) {
        for (Contact ct : contacts()) for (int r : ct.ricList()) if (r == ric) return ct;
        return null;
    }
    private static ContentValues contactValues(Contact ct) {
        ContentValues v = new ContentValues();
        v.put("name", ct.name); v.put("number", ct.number); v.put("type", ct.type);
        v.put("callsign", ct.callsign == null ? "" : ct.callsign.trim().toUpperCase(java.util.Locale.US)); v.put("rics", ricText(parseRics(ct.rics)));
        v.put("notes", ct.notes == null ? "" : ct.notes); v.put("homeLabel", ct.homeLabel == null ? "" : ct.homeLabel); v.put("callsignAuto", ct.callsignAuto);
        v.put("homeLat", dbl(ct.homeLat)); v.put("homeLon", dbl(ct.homeLon)); v.put("lastLat", dbl(ct.lastLat)); v.put("lastLon", dbl(ct.lastLon));
        v.put("lastTs", ct.lastTs); v.put("lastSource", ct.lastSource == null ? "" : ct.lastSource);
        return v;
    }
    public void insertContact(Contact ct) { ct.id = getWritableDatabase().insert("contacts", null, contactValues(ct)); }
    public void updateContact(Contact ct) { getWritableDatabase().update("contacts", contactValues(ct), "_id=?", new String[] { String.valueOf(ct.id) }); }
    /** One entry of a contact's history: heard from them (dir 0) or sent to them (dir 1); how (APRS, DMR voice, DMR text, M17, pager), what, and where when the transmission said. */
    public static final class Heard { public long id, contactId, ts; public String source = "", detail = ""; public double lat = Double.NaN, lon = Double.NaN; public boolean sent; public boolean hasPosition() { return !Double.isNaN(lat) && !Double.isNaN(lon); } }
    private static final int HEARD_KEEP = 300;
    /**
     * Records that a contact was heard (automatic data: the home location and everything the user typed stay as they
     * are). With a position it also becomes the contact's last-heard position; without one only the time and source
     * move. Repeats inside 20 s from the same source are folded into one entry. The history keeps the last 300 per contact.
     */
    public void contactHeard(long id, long ts, String source, String detail, double lat, double lon) {
        if (id <= 0) return;
        boolean pos = !Double.isNaN(lat) && !Double.isNaN(lon);
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT _id, ts, lat FROM contact_heard WHERE contactId=? AND source=? AND dir=0 ORDER BY ts DESC LIMIT 1", new String[] { String.valueOf(id), source == null ? "" : source })) {
            if (c.moveToFirst() && ts - c.getLong(1) < 20_000 && (pos || c.isNull(2))) {
                ContentValues u = new ContentValues(); u.put("ts", ts); if (pos) { u.put("lat", lat); u.put("lon", lon); } if (detail != null && !detail.isEmpty()) u.put("detail", detail);
                db.update("contact_heard", u, "_id=?", new String[] { String.valueOf(c.getLong(0)) });
                touchLastHeard(db, id, ts, source, pos, lat, lon); return;
            }
        }
        ContentValues v = new ContentValues(); v.put("contactId", id); v.put("ts", ts); v.put("source", source == null ? "" : source); v.put("detail", detail == null ? "" : detail);
        if (pos) { v.put("lat", lat); v.put("lon", lon); }
        db.insert("contact_heard", null, v);
        db.execSQL("DELETE FROM contact_heard WHERE contactId=? AND _id NOT IN (SELECT _id FROM contact_heard WHERE contactId=? ORDER BY ts DESC LIMIT " + HEARD_KEEP + ")", new Object[] { id, id });
        touchLastHeard(db, id, ts, source, pos, lat, lon);
    }
    /** Records something sent to a contact: a text, a voice call keyed to their ID, an APRS message, an M17 call, a page. */
    public void contactSent(long id, long ts, String source, String detail) {
        if (id <= 0) return;
        ContentValues v = new ContentValues(); v.put("contactId", id); v.put("ts", ts); v.put("source", source == null ? "" : source); v.put("detail", detail == null ? "" : detail); v.put("dir", 1);
        SQLiteDatabase db = getWritableDatabase(); db.insert("contact_heard", null, v);
        db.execSQL("DELETE FROM contact_heard WHERE contactId=? AND _id NOT IN (SELECT _id FROM contact_heard WHERE contactId=? ORDER BY ts DESC LIMIT " + HEARD_KEEP + ")", new Object[] { id, id });
    }
    private static void touchLastHeard(SQLiteDatabase db, long id, long ts, String source, boolean pos, double lat, double lon) {
        ContentValues v = new ContentValues(); v.put("lastTs", ts); v.put("lastSource", source == null ? "" : source);
        if (pos) { v.put("lastLat", lat); v.put("lastLon", lon); }
        db.update("contacts", v, "_id=?", new String[] { String.valueOf(id) });
    }
    /** The history of a contact, heard and sent, newest first. */
    public List<Heard> contactHeard(long id, int limit) {
        List<Heard> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("contact_heard", null, "contactId=?", new String[] { String.valueOf(id) }, null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                Heard h = new Heard(); h.id = c.getLong(0); h.contactId = c.getLong(1); h.ts = c.getLong(2); h.source = c.getString(3) == null ? "" : c.getString(3); h.detail = c.getString(4) == null ? "" : c.getString(4);
                if (!c.isNull(5)) h.lat = c.getDouble(5); if (!c.isNull(6)) h.lon = c.getDouble(6); h.sent = coli(c, "dir", 0) == 1; out.add(h);
            }
        }
        return out;
    }
    public int contactHeardCount(long id) { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM contact_heard WHERE contactId=?", new String[] { String.valueOf(id) })) { return c.moveToFirst() ? c.getInt(0) : 0; } }
    public void clearContactHeard(long id) {
        getWritableDatabase().delete("contact_heard", "contactId=?", new String[] { String.valueOf(id) });
        ContentValues v = new ContentValues(); v.putNull("lastLat"); v.putNull("lastLon"); v.put("lastTs", 0); v.put("lastSource", "");
        getWritableDatabase().update("contacts", v, "_id=?", new String[] { String.valueOf(id) });
    }
    public void deleteContact(Contact ct) { getWritableDatabase().delete("contact_heard", "contactId=?", new String[] { String.valueOf(ct.id) }); getWritableDatabase().delete("contacts", "_id=?", new String[] { String.valueOf(ct.id) }); }

    // ---- messages ----
    public long addMessage(int peer, int type, boolean outgoing, String text, int status) { return addMessage(peer, type, outgoing, text, status, 0); }
    public long addMessage(int peer, int type, boolean outgoing, String text, int status, int sender) {
        ContentValues v = new ContentValues(); v.put("peer", peer); v.put("type", type); v.put("outgoing", outgoing ? 1 : 0); v.put("ts", System.currentTimeMillis()); v.put("text", text); v.put("status", status); v.put("sender", sender);
        return getWritableDatabase().insert("messages", null, v);
    }
    /** Bench only: remove the texts injected by DIAG smsbench. */
    public int deleteBenchMessages() { return getWritableDatabase().delete("messages", "text LIKE '[bench] %'", null); }
    public void setMessageStatus(long id, int status) { ContentValues v = new ContentValues(); v.put("status", status); getWritableDatabase().update("messages", v, "_id=?", new String[] { String.valueOf(id) }); }
    public List<Message> messages(int peer, int type) {
        List<Message> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null, "peer=? AND type=?", new String[] { String.valueOf(peer), String.valueOf(type) }, null, null, "ts")) {
            while (c.moveToNext()) { Message m = new Message(); m.id = c.getLong(0); m.peer = c.getInt(1); m.type = c.getInt(2); m.outgoing = c.getInt(3) == 1; m.ts = c.getLong(4); m.text = c.getString(5); m.status = c.getInt(6); int si = c.getColumnIndex("sender"); m.sender = si >= 0 ? c.getInt(si) : 0; out.add(m); }
        }
        return out;
    }
    public List<Conversation> conversations() {
        List<Conversation> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer, type, text, ts, outgoing FROM messages m WHERE ts = (SELECT MAX(ts) FROM messages WHERE peer=m.peer AND type=m.type) ORDER BY ts DESC", null)) {
            while (c.moveToNext()) { Conversation cv = new Conversation(); cv.peer = c.getInt(0); cv.type = c.getInt(1); cv.lastText = c.getString(2); cv.lastTs = c.getLong(3); cv.outgoing = c.getInt(4) == 1; cv.name = contactName(cv.peer, cv.type); out.add(cv); }
        }
        return out;
    }

    public long addPage(PageRow p) {
        ContentValues v = new ContentValues();
        v.put("ts", p.ts > 0 ? p.ts : System.currentTimeMillis()); v.put("hz", p.hz); v.put("ric", p.ric); v.put("func", p.func); v.put("baud", p.baud);
        v.put("text", p.text == null ? "" : p.text); v.put("corrected", p.corrected); v.put("signer", p.signer == null ? "" : p.signer);
        v.put("threadKey", p.threadKey == null ? "" : p.threadKey); v.put("transport", p.transport == null ? "page" : p.transport);
        v.put("outgoing", p.outgoing ? 1 : 0); v.put("unread", p.outgoing ? 0 : 1);
        long id = getWritableDatabase().insert("pages", null, v);
        getWritableDatabase().execSQL("DELETE FROM pages WHERE _id NOT IN (SELECT _id FROM pages ORDER BY ts DESC LIMIT 2000)");
        return id;
    }
    public List<PageRow> recentPages(int limit) { return pageQuery(null, limit); }
    public List<PageRow> pagesIn(String threadKey) { return pageQuery(threadKey, 500); }
    private List<PageRow> pageQuery(String thread, int limit) {
        List<PageRow> out = new ArrayList<>();
        String where = thread == null ? null : "threadKey=?";
        String[] args = thread == null ? null : new String[] { thread };
        try (Cursor c = getReadableDatabase().query("pages", null, where, args, null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(readPage(c));
        }
        if (thread != null) java.util.Collections.reverse(out);
        return out;
    }
    public void markThreadRead(String threadKey) {
        ContentValues v = new ContentValues(); v.put("unread", 0);
        getWritableDatabase().update("pages", v, "threadKey=?", new String[] { threadKey });
    }
    /** One row per page or M17 thread, newest first. */
    public List<Conversation> pageConversations() {
        List<Conversation> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT threadKey, transport, text, ts, outgoing, signer, ric, (SELECT COUNT(*) FROM pages u WHERE u.threadKey=p.threadKey AND u.unread=1) FROM pages p WHERE ts = (SELECT MAX(ts) FROM pages WHERE threadKey=p.threadKey) ORDER BY ts DESC", null)) {
            while (c.moveToNext()) {
                Conversation cv = new Conversation();
                cv.threadKey = c.getString(0); cv.transport = c.getString(1); cv.lastText = c.getString(2); cv.lastTs = c.getLong(3); cv.outgoing = c.getInt(4) == 1;
                String signer = c.getString(5); int ric = c.getInt(6); cv.unread = c.getInt(7);
                cv.name = "m17".equals(cv.transport) ? (signer == null || signer.isEmpty() ? "M17" : signer) : (ric < 0 ? "RIC unreadable" : ric == 0 ? cv.threadKey : ("RIC " + ric));
                cv.address = "m17".equals(cv.transport) ? signer : String.valueOf(ric);
                out.add(cv);
            }
        }
        return out;
    }
    private static PageRow readPage(Cursor c) {
        PageRow p = new PageRow();
        p.id = c.getLong(c.getColumnIndexOrThrow("_id")); p.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        p.hz = c.getInt(c.getColumnIndexOrThrow("hz")); p.ric = c.getInt(c.getColumnIndexOrThrow("ric")); p.func = c.getInt(c.getColumnIndexOrThrow("func"));
        p.baud = c.getInt(c.getColumnIndexOrThrow("baud")); p.text = c.getString(c.getColumnIndexOrThrow("text")); p.corrected = c.getInt(c.getColumnIndexOrThrow("corrected"));
        p.signer = c.getString(c.getColumnIndexOrThrow("signer")); p.threadKey = c.getString(c.getColumnIndexOrThrow("threadKey")); p.transport = c.getString(c.getColumnIndexOrThrow("transport"));
        p.outgoing = c.getInt(c.getColumnIndexOrThrow("outgoing")) == 1; p.unread = c.getInt(c.getColumnIndexOrThrow("unread"));
        return p;
    }

    private static ContentValues values(Channel ch) {
        ContentValues v = new ContentValues();
        v.put("number", ch.number); v.put("name", ch.name); v.put("type", ch.type); v.put("rxFreq", ch.rxFreq); v.put("txFreq", ch.txFreq);
        v.put("power", ch.power); v.put("band", ch.band); v.put("sq", ch.sq); v.put("rxType", ch.rxType); v.put("rxSubCode", ch.rxSubCode);
        v.put("txType", ch.txType); v.put("txSubCode", ch.txSubCode); v.put("relay", ch.relay); v.put("mode", ch.mode); v.put("cc", ch.cc);
        v.put("inSlot", ch.inSlot); v.put("outSlot", ch.outSlot); v.put("contactType", ch.contactType); v.put("txContact", ch.txContact);
        v.put("encryptSw", ch.encryptSw); v.put("encryptKey", ch.encryptKey); v.put("interrupt", ch.interrupt);
        StringBuilder g = new StringBuilder(); for (int x : ch.groups) { if (g.length() > 0) g.append(','); g.append(x); }
        v.put("groups", g.toString()); v.put("active", ch.active ? 1 : 0); v.put("location", ch.location);
        v.put("lat", ch.lat); v.put("lon", ch.lon); v.put("elev", ch.elev); v.put("softSq", ch.softSq ? 1 : 0); v.put("softSqLevel", ch.softSqLevel);
        v.put("notes", ch.notes == null ? "" : ch.notes);
        v.put("rxOnly", ch.rxOnly ? 1 : 0);
        v.put("m17dest", ch.m17Dest());
        return v;
    }

    private static Channel read(Cursor c) {
        Channel ch = new Channel();
        ch.id = c.getLong(c.getColumnIndexOrThrow("_id")); ch.number = c.getInt(c.getColumnIndexOrThrow("number")); ch.name = c.getString(c.getColumnIndexOrThrow("name"));
        ch.type = c.getInt(c.getColumnIndexOrThrow("type")); ch.rxFreq = c.getInt(c.getColumnIndexOrThrow("rxFreq")); ch.txFreq = c.getInt(c.getColumnIndexOrThrow("txFreq"));
        ch.power = c.getInt(c.getColumnIndexOrThrow("power")); ch.band = c.getInt(c.getColumnIndexOrThrow("band")); ch.sq = c.getInt(c.getColumnIndexOrThrow("sq"));
        ch.rxType = c.getInt(c.getColumnIndexOrThrow("rxType")); ch.rxSubCode = c.getInt(c.getColumnIndexOrThrow("rxSubCode")); ch.txType = c.getInt(c.getColumnIndexOrThrow("txType"));
        ch.txSubCode = c.getInt(c.getColumnIndexOrThrow("txSubCode")); ch.relay = c.getInt(c.getColumnIndexOrThrow("relay")); ch.mode = c.getInt(c.getColumnIndexOrThrow("mode"));
        ch.cc = c.getInt(c.getColumnIndexOrThrow("cc")); ch.inSlot = c.getInt(c.getColumnIndexOrThrow("inSlot")); ch.outSlot = c.getInt(c.getColumnIndexOrThrow("outSlot"));
        ch.contactType = c.getInt(c.getColumnIndexOrThrow("contactType")); ch.txContact = c.getInt(c.getColumnIndexOrThrow("txContact"));
        ch.encryptSw = c.getInt(c.getColumnIndexOrThrow("encryptSw")); ch.encryptKey = c.getString(c.getColumnIndexOrThrow("encryptKey")); ch.interrupt = c.getInt(c.getColumnIndexOrThrow("interrupt"));
        String g = c.getString(c.getColumnIndexOrThrow("groups")); List<Integer> gl = new ArrayList<>();
        if (g != null) for (String s : g.split(",")) { s = s.trim(); if (!s.isEmpty()) try { gl.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) { } }
        ch.groups = new int[gl.size()]; for (int i = 0; i < gl.size(); i++) ch.groups[i] = gl.get(i);
        ch.active = c.getInt(c.getColumnIndexOrThrow("active")) == 1; ch.location = c.getString(c.getColumnIndexOrThrow("location"));
        ch.lat = c.getDouble(c.getColumnIndexOrThrow("lat")); ch.lon = c.getDouble(c.getColumnIndexOrThrow("lon")); ch.elev = c.getDouble(c.getColumnIndexOrThrow("elev"));
        ch.softSq = c.getInt(c.getColumnIndexOrThrow("softSq")) == 1; ch.softSqLevel = c.getInt(c.getColumnIndexOrThrow("softSqLevel"));
        int ni = c.getColumnIndex("notes"); ch.notes = ni >= 0 && !c.isNull(ni) ? c.getString(ni) : "";
        int ro = c.getColumnIndex("rxOnly"); ch.rxOnly = ro >= 0 && !c.isNull(ro) && c.getInt(ro) != 0;
        int md = c.getColumnIndex("m17dest"); ch.m17dest = md >= 0 && !c.isNull(md) ? c.getString(md) : "@ALL";
        return ch;
    }

    // ---- import from the stock app (root copy of its per-area database) ----------------------------

    /** @return number of channels imported, or -1 with the reason in {@code err}. */
    public int importFromStock(Context ctx, String stockPackage, String areaKey, StringBuilder err) { return importFromStock(ctx, stockPackage, "database_channel_area_", areaKey, err); }
    /**
     * {@code prefix}: the stock app's per-area file and table name before the area (DeviceProfile.stockAreaPrefix): the
     * Armor 26 Ultra's app keeps database_channel_area_&lt;area&gt;, the Armor 20WT's channel_database_&lt;area&gt; (without the
     * relay and interrupt columns, which take their defaults).
     */
    public int importFromStock(Context ctx, String stockPackage, String prefix, String areaKey, StringBuilder err) {
        try {
            // stock files: databases/<prefix><area>.db holding table <prefix><area>
            File dst = new File(ctx.getCacheDir(), "stock_" + areaKey + ".db");
            String r = "";
            for (String base : new String[] { "/data/user/0/", "/data/data/" }) {        // su may see a different mount view
                String src = base + stockPackage + "/databases/" + prefix + areaKey + ".db";
                r = RootSetup.run("cp " + src + " " + dst.getAbsolutePath() + " && chmod 644 " + dst.getAbsolutePath() + " && chown $(stat -c %u:%g " + ctx.getCacheDir().getAbsolutePath() + ") " + dst.getAbsolutePath());
                if (dst.exists() && dst.length() > 0) break;
            }
            if (!dst.exists() || dst.length() == 0) {
                err.append("copy failed: ").append(r).append(" | ls: ").append(RootSetup.run("ls /data/user/0/" + stockPackage + "/databases/ 2>&1 | head -5; id; cat /proc/self/attr/current"));
                return -1;
            }
            SQLiteDatabase sdb = SQLiteDatabase.openDatabase(dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            int n = 0, skipped = 0;
            java.util.Map<Integer, Integer> stockIdToNumber = new java.util.HashMap<>();   // module side-tables key on the stock row _id
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try (Cursor c = sdb.query(prefix + areaKey, null, null, null, null, null, "_id")) {   // rows in display order
                db.delete("channels", null, null);
                while (c.moveToNext()) {
                    Channel ch = new Channel();
                    ch.number = ++n;
                    ch.name = col(c, "channel_name", ""); ch.type = coli(c, "channel_type", 1);
                    ch.rxFreq = coli(c, "channel_rxFreq", 0); ch.txFreq = coli(c, "channel_txFreq", 0); ch.power = coli(c, "channel_power", 0);
                    if (!tunable(ch)) { n--; skipped++; continue; }              // outside what this radio tunes: the module would refuse it
                    stockIdToNumber.put(coli(c, "_id", -1), ch.number);
                    ch.band = coli(c, "channel_band", 1); ch.sq = coli(c, "channel_sq", 2); ch.rxType = coli(c, "channel_rxType", 0); ch.rxSubCode = coli(c, "channel_rxSubCode", 0);
                    ch.txType = coli(c, "channel_txType", 0); ch.txSubCode = coli(c, "channel_txSubCode", 0); ch.relay = coli(c, "channel_relay", 2); ch.mode = coli(c, "channel_mode", 0);
                    ch.cc = coli(c, "channel_cc", 1); ch.inSlot = coli(c, "channel_inBoundSlot", 0); ch.outSlot = coli(c, "channel_outBoundSlot", 0);
                    ch.contactType = coli(c, "channel_contactType", 1); ch.txContact = coli(c, "channel_txContact", 0); ch.encryptSw = coli(c, "channel_encryptSw", 2);
                    ch.encryptKey = col(c, "channel_encryptKey", ""); ch.interrupt = coli(c, "channel_interrupt", 2); ch.active = coli(c, "channel_active", 0) == 1;
                    if (ch.relay == 0) ch.relay = 2;
                    String groups = col(c, "channel_groups", null);
                    if (groups != null && !groups.trim().isEmpty()) {
                        List<Integer> gl = new ArrayList<>();
                        for (String s : groups.split("[,;\\- ]")) { s = s.trim(); if (!s.isEmpty()) try { int v = Integer.parseInt(s); if (v > 0) gl.add(v); } catch (NumberFormatException ignored) { } }
                        ch.groups = new int[gl.size()]; for (int i = 0; i < gl.size(); i++) ch.groups[i] = gl.get(i);
                    }
                    db.insert("channels", null, values(ch));
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            skippedNote(err, skipped);
            File cdst = new File(ctx.getCacheDir(), "stock_contacts.db");
            SQLiteDatabase cdbTmp = copyStock(ctx, stockPackage, "contact_database.db", "stock_contacts.db");
            SQLiteDatabase cdb = cdbTmp != null ? cdbTmp : sdb;
            try (Cursor c = cdb.query("contact_database", null, null, null, null, null, null)) {
                db.delete("contacts", null, null);
                while (c.moveToNext()) {
                    ContentValues v = new ContentValues();
                    v.put("name", col(c, "contact_name", "")); v.put("number", coli(c, "contact_number", 0)); v.put("type", coli(c, "contact_type", 0));
                    db.insert("contacts", null, v);
                }
            } catch (Throwable t) { err.append("contacts: ").append(t.getMessage()).append("; "); }
            // module side-tables: locations (per channel number) and the activity history
            try {
                SQLiteDatabase ldb = copyStock(ctx, stockPackage, "dmrmod_locations.db", "stock_locations.db");
                if (ldb != null) {
                    try (Cursor c = ldb.rawQuery("SELECT * FROM channel_locations", null)) {
                        while (c.moveToNext()) {
                            Integer mapped = stockIdToNumber.get(coli(c, "channel_number", 0));   // "channel_number" holds the stock _id
                            int num = mapped != null ? mapped : coli(c, "channel_number", 0);
                            ContentValues v = new ContentValues();
                            v.put("lat", c.getDouble(c.getColumnIndexOrThrow("latitude"))); v.put("lon", c.getDouble(c.getColumnIndexOrThrow("longitude")));
                            int ei = c.getColumnIndex("elevation"); v.put("elev", ei >= 0 && !c.isNull(ei) ? c.getDouble(ei) : 0);
                            db.update("channels", v, "number=?", new String[] { String.valueOf(num) });
                        }
                    }
                    ldb.close();
                }
            } catch (Throwable t) { err.append("locations: ").append(t.getMessage()).append("; "); }
            try {
                SQLiteDatabase hdb = copyStock(ctx, stockPackage, "dmrmod_history.db", "stock_history.db");
                if (hdb != null) {
                    db.delete("history", null, null);
                    try (Cursor c = hdb.rawQuery("SELECT channel_number, dmr_id, activity_type, rssi_dbm, created_at FROM channel_history ORDER BY created_at", null)) {
                        while (c.moveToNext()) {
                            ContentValues v = new ContentValues();
                            v.put("channel", c.getInt(0)); v.put("ts", c.getLong(4)); v.put("kind", "mod"); v.put("text", c.getString(2));
                            v.put("rssi", c.isNull(3) ? -999 : c.getInt(3));
                            int id = 0; try { id = Integer.parseInt(c.getString(1)); } catch (Exception ignored) { }
                            v.put("dmrId", id);
                            db.insert("history", null, v);
                        }
                    }
                    hdb.close();
                }
            } catch (Throwable t) { err.append("history: ").append(t.getMessage()).append("; "); }
            try {
                SQLiteDatabase zdb = copyStock(ctx, stockPackage, "dmrmod_zones.db", "stock_zones.db");
                if (zdb != null) {
                    db.delete("zones", null, null);
                    try (Cursor c = zdb.rawQuery("SELECT name, channel_list FROM zones", null)) {
                        while (c.moveToNext()) {
                            List<Integer> nums = new ArrayList<>();
                            for (int sid : parseInts(c.getString(1))) { Integer num = stockIdToNumber.get(sid); if (num != null) nums.add(num); }
                            Zone z = new Zone(); z.name = c.getString(0); z.numbers = new int[nums.size()]; for (int i = 0; i < nums.size(); i++) z.numbers[i] = nums.get(i);
                            ContentValues v = new ContentValues(); v.put("name", z.name); v.put("numbers", joinInts(z.numbers)); db.insert("zones", null, v);
                        }
                    }
                    zdb.close();
                }
            } catch (Throwable t) { err.append("zones: ").append(t.getMessage()).append("; "); }
            try {
                SQLiteDatabase qdb = copyStock(ctx, stockPackage, "dmrmod_softsq.db", "stock_softsq.db");
                if (qdb != null) {
                    try (Cursor c = qdb.rawQuery("SELECT channel_id, enabled, level FROM channel_softsq", null)) {
                        while (c.moveToNext()) {
                            Integer num = stockIdToNumber.get(c.getInt(0)); if (num == null) continue;
                            ContentValues v = new ContentValues(); v.put("softSq", c.getInt(1)); v.put("softSqLevel", c.getInt(2));
                            db.update("channels", v, "number=?", new String[] { String.valueOf(num) });
                        }
                    }
                    qdb.close();
                }
            } catch (Throwable t) { err.append("softsq: ").append(t.getMessage()).append("; "); }
            importModuleTgLists(ctx, stockPackage, stockIdToNumber, err);
            sdb.close();
            changed();
            return n;
        } catch (Throwable t) { err.append(t.toString()); return -1; }
    }

    /** Root-copy one of the stock app's database files into our cache and open it read-only (null if unavailable). */
    /**
     * Blackview Xplore 1: QuickChat keeps its channels in a Room database {@code databases/channel_database} (table
     * {@code channel_table}) and contacts in {@code contacts_table}; the fields mean the same as the module's (type 0 digital /
     * 1 analog, Hz, relay 2, encryptSw 2 = off, groupList "1-0-0-…", contact type 0 private / 1 group). Room writes through a
     * WAL, so the -wal/-shm files are copied too.
     * @return channels imported, or -1 (err says why)
     */
    /**
     * The W6's stock app (com.flyscale.analogpoc): table CHANNEL_INFO in channel_list_detial.db, one row per channel with
     * strings GBW (0 = 12.5 kHz, 1 = 25 kHz), TFV / RFV (MHz), RXCXCSS / TXCXCSS (the module's tone codes, Sa818Tones),
     * SQ (0–8) and POWER (0 low, 1 high). All analog. Returns the number imported, or -1.
     */
    public int importFromFlyscale(Context ctx, String stockPackage, StringBuilder err) {
        SQLiteDatabase sdb = copyRoom(ctx, stockPackage, new String[] { "channel_list_detial.db" }, "fs_channels.db");
        if (sdb == null) { err.append("stock channels not readable: ").append(RootSetup.run("ls /data/data/" + stockPackage + "/databases/ 2>&1 | head -8")); return -1; }
        int n = 0, skipped = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try (Cursor c = sdb.query("CHANNEL_INFO", null, null, null, null, null, "CHANNEL_ID")) {
            db.delete("channels", null, null);
            while (c.moveToNext()) {
                Channel ch = flyscaleChannel(coli(c, "CHANNEL_ID", n), col(c, "GBW", "1"), col(c, "TFV", ""), col(c, "RFV", ""), col(c, "TXCXCSS", "0"), col(c, "RXCXCSS", "0"), col(c, "SQ", "3"), col(c, "POWER", "1"));
                if (ch == null) continue;
                if (!tunable(ch)) { skipped++; continue; }
                ch.number = ++n; ch.active = n == 1;
                db.insert("channels", null, values(ch));
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) { err.append("channels: ").append(t.getMessage()); n = -1; }
        finally { db.endTransaction(); sdb.close(); }
        if (n >= 0) skippedNote(err, skipped);
        changed();
        return n;
    }

    /** "3 skipped: outside what this radio tunes (400–470 MHz)" into an import's notes. */
    private static void skippedNote(StringBuilder err, int skipped) {
        if (skipped > 0) err.append(err.length() > 0 ? "; " : "").append(skipped).append(skipped == 1 ? " channel" : " channels").append(" skipped: outside what this radio tunes (").append(com.macdmr.radio.DeviceProfile.detect().bandsText()).append(")");
    }

    /** One stock W6 channel row as a SignalDeck analog channel, or null when its frequencies do not parse. */
    static Channel flyscaleChannel(int id, String gbw, String tfv, String rfv, String txCss, String rxCss, String sq, String power) {
        try {
            Channel ch = new Channel();
            ch.type = Channel.TYPE_FM; ch.name = "CH " + (id + 1);
            ch.txFreq = (int) Math.round(Double.parseDouble(tfv.trim()) * 1e6); ch.rxFreq = (int) Math.round(Double.parseDouble(rfv.trim()) * 1e6);
            ch.band = "0".equals(gbw.trim()) ? 0 : 1; ch.power = "0".equals(power.trim()) ? 0 : 1;
            ch.sq = Math.max(0, Math.min(8, Integer.parseInt(sq.trim())));
            int[] tx = Sa818Tones.fromModule(Integer.parseInt(txCss.trim())), rx = Sa818Tones.fromModule(Integer.parseInt(rxCss.trim()));
            ch.txType = tx[0]; ch.txSubCode = tx[1]; ch.rxType = rx[0]; ch.rxSubCode = rx[1];
            return ch;
        } catch (RuntimeException e) { return null; }
    }

    /**
     * The W6Plus DMR's stock app (com.flyscale.dmr): channel_list_detial.db holds 128 slots, each with an analog row
     * (ANALOG_CHANNEL_INFO) and a digital row (DIGITAL_CHANNEL_INFO) and a CHANNEL_LIST type saying which one the slot
     * uses (1 analog, 0 digital, the module's own). The rows keep the module's bytes as hex strings, the same layout as
     * the 0x23 and 0x22 bodies; channel_index in its config.xml is the slot in use. Returns the count, or -1.
     * {@code dmrIdOut[0]} gets the in-use slot's DMR ID (0 when there is none).
     */
    public int importFromFlyscaleDmr(Context ctx, String stockPackage, int activeSlot, int[] dmrIdOut, StringBuilder err) {
        SQLiteDatabase sdb = copyRoom(ctx, stockPackage, new String[] { "channel_list_detial.db" }, "fsd_channels.db");
        if (sdb == null) { err.append("stock channels not readable: ").append(RootSetup.run("ls /data/data/" + stockPackage + "/databases/ 2>&1 | head -8")); return -1; }
        int n = 0;
        java.util.Map<Integer, Integer> types = new java.util.TreeMap<>();
        java.util.Map<Integer, java.util.Map<String, String>> analog = new java.util.HashMap<>(), digital = new java.util.HashMap<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = sdb.query("CHANNEL_LIST", null, null, null, null, null, "CHANNEL_ID")) { while (c.moveToNext()) types.put(coli(c, "CHANNEL_ID", -1), coli(c, "CHANNEL_TYPE", 1)); }
            for (String[] t : new String[][] { { "ANALOG_CHANNEL_INFO", "CHANNEL_ID" }, { "DIGITAL_CHANNEL_INFO", "ID" } })
                try (Cursor c = sdb.query(t[0], null, null, null, null, null, t[1])) {
                    while (c.moveToNext()) {
                        java.util.Map<String, String> r = new java.util.HashMap<>();
                        for (int i = 0; i < c.getColumnCount(); i++) r.put(c.getColumnName(i), c.isNull(i) ? null : c.getString(i));
                        (t[0].startsWith("ANALOG") ? analog : digital).put(coli(c, t[1], -1), r);
                    }
                }
            db.delete("channels", null, null);
            for (java.util.Map.Entry<Integer, Integer> e : types.entrySet()) {
                int slot = e.getKey();
                Channel ch = flyscaleDmrChannel(slot, e.getValue(), analog.get(slot), digital.get(slot));
                if (ch == null) continue;
                ch.number = ++n; ch.active = slot == activeSlot;
                if (ch.active && dmrIdOut != null && dmrIdOut.length > 0) { java.util.Map<String, String> d = digital.get(slot); dmrIdOut[0] = d == null ? 0 : flyscaleDmrId(d.get("LOCAL_ID")); }
                db.insert("channels", null, values(ch));
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) { err.append("channels: ").append(t.getMessage()); n = -1; }
        finally { db.endTransaction(); sdb.close(); }
        changed();
        return n;
    }

    /**
     * One slot of the W6Plus DMR's stock app as a SignalDeck channel: type 0 takes the digital row, else the analog row;
     * frequencies are MHz text, the rest the module's bytes in hex (sub-audio 0 none, 1 CTCSS, 2/3 DCS; encryption 1 on,
     * 2 off; relay 2 normal); the DMR ID, the call number and the 32 receive talkgroups are little-endian 32-bit hex.
     * Null when the row is missing or its frequency does not parse.
     */
    static Channel flyscaleDmrChannel(int slot, int type, java.util.Map<String, String> a, java.util.Map<String, String> d) {
        java.util.Map<String, String> r = type == 0 ? d : a;
        if (r == null || r.get("RX_FREQ") == null) return null;
        try {
            Channel ch = new Channel();
            ch.name = "CH " + (slot + 1);
            ch.rxFreq = (int) Math.round(Double.parseDouble(r.get("RX_FREQ").trim()) * 1e6);
            String tx = r.get("TX_FREQ");
            ch.txFreq = tx == null || tx.trim().isEmpty() ? ch.rxFreq : (int) Math.round(Double.parseDouble(tx.trim()) * 1e6);
            if (ch.rxFreq <= 0) return null;
            ch.power = hx(r.get("POWER"), 1); ch.relay = hx(r.get("RELAY"), 2); if (ch.relay == 0) ch.relay = 2;
            if (type == 0) {
                ch.type = Channel.TYPE_DMR;
                ch.cc = hx(r.get("CC"), 1); ch.inSlot = hx(r.get("INBOUND_SLOT"), 0); ch.outSlot = hx(r.get("OUTBOUND_SLOT"), 0);
                ch.mode = hx(r.get("CHANNEL_MODE"), 0); ch.contactType = hx(r.get("CONTACT_TYPE"), 1); ch.txContact = leInt(r.get("TX_CONTACT"));
                ch.encryptSw = hx(r.get("ENCRYPT_SW"), 2) == 1 ? 1 : 2; ch.encryptKey = r.get("ENCRYPT_KEY") == null ? "" : r.get("ENCRYPT_KEY");
                List<Integer> gl = new ArrayList<>();
                String g = r.get("GROUP_LIST");
                if (g != null) for (int i = 0; i + 8 <= g.length() && gl.size() < 32; i += 8) { int v = leInt(g.substring(i, i + 8)); if (v > 0 && !gl.contains(v)) gl.add(v); }
                ch.groups = new int[gl.size()]; for (int i = 0; i < gl.size(); i++) ch.groups[i] = gl.get(i);
            } else {
                ch.type = Channel.TYPE_FM;
                ch.band = hx(r.get("BAND"), 1); ch.sq = Math.max(0, Math.min(9, hx(r.get("SQ"), 2)));
                ch.rxType = hx(r.get("RX_TYPE"), 0); ch.rxSubCode = ch.rxType == 0 ? 0 : hx(r.get("RX_SUBCODE"), 0);
                ch.txType = hx(r.get("TX_TYPE"), 0); ch.txSubCode = ch.txType == 0 ? 0 : hx(r.get("TX_SUBCODE"), 0);
            }
            return ch;
        } catch (RuntimeException e) { return null; }
    }
    private static int hx(String s, int d) { if (s == null || s.trim().isEmpty()) return d; try { return Integer.parseInt(s.trim(), 16); } catch (NumberFormatException e) { return d; } }
    /** The highest DMR ID the module takes (the stock apps' MAX_ID); the W6Plus DMR's module refuses a channel with a higher one and stops answering. */
    public static final int MAX_DMR_ID = 16776415;
    /**
     * The stock app's LOCAL_ID: unlike its call number and talkgroups it is stored most significant byte first (the factory
     * value "00000001" is ID 1). Read that way, the other way only if that is out of range; 0 for the factory 1 (no ID set)
     * or anything outside 2..{@link #MAX_DMR_ID}.
     */
    static int flyscaleDmrId(String hex) {
        if (hex == null || hex.trim().length() != 8) return 0;
        int be;
        try { be = (int) Long.parseLong(hex.trim(), 16); } catch (NumberFormatException e) { return 0; }
        if (be >= 1 && be <= MAX_DMR_ID) return be > 1 ? be : 0;
        int le = leInt(hex);
        return le > 1 && le <= MAX_DMR_ID ? le : 0;
    }
    /** Little-endian 32-bit hex ("01000000" = 1), 0 when it does not parse. */
    static int leInt(String s) {
        if (s == null || s.trim().length() != 8) return 0;
        try { long v = Long.parseLong(s.trim(), 16); return (int) (((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF)); }
        catch (NumberFormatException e) { return 0; }
    }

    public int importFromQuickChat(Context ctx, String stockPackage, StringBuilder err) {
        SQLiteDatabase sdb = copyRoom(ctx, stockPackage, new String[] { "channel_database", "channel.db" }, "qc_channels.db");
        if (sdb == null) { err.append("QuickChat channels not readable: ").append(RootSetup.run("ls /data/data/" + stockPackage + "/databases/ 2>&1 | head -8")); return -1; }
        int n = 0, skipped = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        // the bundled database has channel_table; the phone's own (QuickChat on the Xplore 1, 2026-10-21) has "channels" with
        // 311 rows in 15 country lists (countryId, channelIdInCountry): take the country that holds the active channel
        String table = "channel_table", where = null, order = "id";
        try (Cursor t = sdb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='channels'", null)) {
            if (t.moveToFirst()) {
                table = "channels"; order = "channelIdInCountry, id";
                try (Cursor k = sdb.rawQuery("SELECT countryId FROM channels ORDER BY enbale DESC, id LIMIT 1", null)) { if (k.moveToFirst()) where = "countryId=" + k.getInt(0); }
            }
        } catch (Throwable ignored) { }
        try (Cursor c = sdb.query(table, null, where, null, null, null, order)) {
            db.delete("channels", null, null);
            while (c.moveToNext()) {
                Channel ch = new Channel();
                ch.number = ++n;
                ch.name = col(c, "channelName", "Channel " + n); ch.type = coli(c, "channelType", 1);
                ch.rxFreq = coli(c, "rx_freq", 0); ch.txFreq = coli(c, "tx_freq", 0); ch.power = coli(c, "power", 1);
                if (!tunable(ch)) { n--; skipped++; continue; }                  // outside what this radio tunes
                ch.band = coli(c, "band", 1); ch.sq = coli(c, "sq", 2); ch.rxType = coli(c, "rx_type", 0); ch.rxSubCode = coli(c, "rx_subcode", 0);
                ch.txType = coli(c, "tx_type", 0); ch.txSubCode = coli(c, "tx_subcode", 0); ch.relay = coli(c, "relay", 2); ch.mode = coli(c, "channelMode", 0);
                ch.cc = coli(c, "cc", 1); ch.inSlot = coli(c, "inboundSlot", 0); ch.outSlot = coli(c, "outboundSlot", 0);
                ch.contactType = coli(c, "contactType", 1); ch.txContact = coli(c, "tx_contact", 0); ch.encryptSw = coli(c, "encryptSw", 2);
                ch.encryptKey = col(c, "encryptKey", ""); ch.active = coli(c, "enbale", 0) == 1;
                if (ch.relay == 0) ch.relay = 2;
                String groups = col(c, "groupList", null);
                if (groups != null) {
                    List<Integer> gl = new ArrayList<>();
                    for (String s : groups.split("[,;\\- ]")) { s = s.trim(); if (!s.isEmpty()) try { int v = Integer.parseInt(s); if (v > 0) gl.add(v); } catch (NumberFormatException ignored) { } }
                    ch.groups = new int[gl.size()]; for (int i = 0; i < gl.size(); i++) ch.groups[i] = gl.get(i);
                }
                db.insert("channels", null, values(ch));
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) { err.append("channels: ").append(t.getMessage()); n = -1; }
        finally { db.endTransaction(); sdb.close(); }
        if (n >= 0) skippedNote(err, skipped);
        SQLiteDatabase cdb = copyRoom(ctx, stockPackage, new String[] { "contacts_database", "contact_database", "contacts.db" }, "qc_contacts.db");
        if (cdb != null) {
            try (Cursor c = cdb.query("contacts_table", null, null, null, null, null, "id")) {
                db.delete("contacts", null, null);
                while (c.moveToNext()) {
                    ContentValues v = new ContentValues();
                    v.put("name", col(c, "name", "")); v.put("number", coli(c, "number", 0)); v.put("type", coli(c, "type", 0));
                    db.insert("contacts", null, v);
                }
            } catch (Throwable t) { err.append("contacts: ").append(t.getMessage()).append("; "); }
            finally { cdb.close(); }
        } else err.append("no QuickChat contacts database; ");
        changed();
        return n;
    }

    /**
     * The Unihertz Atom XL's stock app (com.agold.intercom): intercom.db, table channel, one row per channel with its area
     * (1 = "Default", the others regional FRS/PMR lists). Replaces SignalDeck's channels with one area's.
     */
    public int importFromAgold(Context ctx, String stockPackage, int area, StringBuilder err) {
        SQLiteDatabase sdb = copyStock(ctx, stockPackage, "intercom.db", "agold_intercom.db");
        if (sdb == null) { err.append("the stock app's channels are not readable: ").append(RootSetup.run("ls /data/data/" + stockPackage + "/databases/ 2>&1 | head -4")); return -1; }
        String areaName = null;
        try (Cursor a = sdb.rawQuery("SELECT name FROM area WHERE num=?", new String[] { String.valueOf(area) })) { if (a.moveToFirst()) areaName = a.getString(0); } catch (Throwable ignored) { }
        int n = 0, skipped = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try (Cursor c = sdb.rawQuery("SELECT * FROM channel WHERE area=? ORDER BY num, _id", new String[] { String.valueOf(area) })) {
            db.delete("channels", null, null);
            while (c.moveToNext()) {
                java.util.Map<String, String> row = new java.util.HashMap<>();
                for (int i = 0; i < c.getColumnCount(); i++) row.put(c.getColumnName(i), c.isNull(i) ? null : c.getString(i));
                Channel ch = agoldChannel(row, ++n, areaName);
                if (ch == null) { n--; continue; }
                if (!tunable(ch)) { n--; skipped++; continue; }                  // outside what this radio tunes (the Atom XL: UHF only)
                ch.active = n == 1;
                db.insert("channels", null, values(ch));
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) { err.append("channels: ").append(t.getMessage()); n = -1; }
        finally { db.endTransaction(); sdb.close(); }
        if (n >= 0) skippedNote(err, skipped);
        if (n == 0 && skipped == 0) err.append("area ").append(area).append(" has no channels");
        changed();
        return n;
    }

    /**
     * One row of the Atom XL stock app's channel table as a SignalDeck channel, with the stock app's own encoding
     * (ExtModuleProtocol.setDmr09AnalogGroup / setDmr09DigitalGroup): type 0 DMR, 1 analog; band 128 = wide; sub-audio
     * type stored +1 (1 = none); contactType stored +1 (2 = group, 1 = private, 4 = all call, sent as 0xFFFFFA); contactList
     * "tg:tg:…"; encryptSwitch 1 = on. Null when the row has no usable frequency.
     */
    static Channel agoldChannel(java.util.Map<String, String> r, int number, String areaName) {
        int rx = agoldInt(r.get("recvfreq"), 0), tx = agoldInt(r.get("sendfreq"), rx);
        if (rx <= 0) return null;
        Channel ch = new Channel();
        ch.number = number;
        ch.type = agoldInt(r.get("type"), 1) == 0 ? Channel.TYPE_DMR : 1;
        ch.rxFreq = rx; ch.txFreq = tx > 0 ? tx : rx;
        ch.power = agoldInt(r.get("power"), 1);
        String name = r.get("name");
        ch.name = name != null && !name.trim().isEmpty() ? name.trim() : (areaName != null && !areaName.trim().isEmpty() ? areaName.trim() : "Channel") + " " + agoldInt(r.get("num"), number);
        if (ch.type == Channel.TYPE_DMR) {
            ch.cc = agoldInt(r.get("colorCode"), 1);
            ch.inSlot = agoldInt(r.get("inboundSlot"), 0); ch.outSlot = agoldInt(r.get("outboundSlot"), 0);
            ch.mode = agoldInt(r.get("channelMode"), 0);
            int ct = agoldInt(r.get("contactType"), 2), num = agoldInt(r.get("contactNum"), 1);
            ch.contactType = Math.max(0, ct - 1);
            ch.txContact = ct == 4 ? 0xFFFFFA : num;
            ch.encryptSw = agoldInt(r.get("encryptSwitch"), 2) == 1 ? 1 : 2;
            List<Integer> gl = new ArrayList<>();
            String list = r.get("contactList");
            if (list != null) for (String s : list.split(":")) { int v = agoldInt(s, 0); if (v > 0 && !gl.contains(v) && gl.size() < 32) gl.add(v); }
            else if (ct == 2 && num > 0) gl.add(num);
            ch.groups = new int[gl.size()]; for (int i = 0; i < gl.size(); i++) ch.groups[i] = gl.get(i);
        } else {
            ch.band = agoldInt(r.get("band"), 128) == 128 ? 1 : 0;
            ch.sq = agoldInt(r.get("squelchLevel"), 2);
            ch.rxType = Math.max(0, agoldInt(r.get("recvSubAudioType"), 1) - 1);
            ch.rxSubCode = ch.rxType == 0 ? 0 : agoldInt(r.get("recvCtcssFreq"), 0);
            ch.txType = Math.max(0, agoldInt(r.get("sendAubAudioType"), 1) - 1);
            ch.txSubCode = ch.txType == 0 ? 0 : agoldInt(r.get("sendCtcssFreq"), 0);
        }
        return ch;
    }
    private static int agoldInt(String s, int d) { if (s == null) return d; try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return d; } }

    /** The Atom XL stock app's areas ("num name"), or empty when its database is not readable. */
    public static List<String> agoldAreas(Context ctx, String stockPackage) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase sdb = copyStock(ctx, stockPackage, "intercom.db", "agold_intercom.db");
        if (sdb == null) return out;
        try (Cursor c = sdb.rawQuery("SELECT a.num, a.name, COUNT(c._id) FROM area a LEFT JOIN channel c ON c.area = a.num GROUP BY a.num ORDER BY a.num", null)) {
            while (c.moveToNext()) out.add(c.getInt(0) + " " + (c.isNull(1) ? "" : c.getString(1)) + " (" + c.getInt(2) + " channels)");
        } catch (Throwable ignored) { }
        finally { sdb.close(); }
        return out;
    }

    /** Copy the first Room database found (with its -wal/-shm) into the cache and open the copy. */
    private static SQLiteDatabase copyRoom(Context ctx, String stockPackage, String[] names, String localName) {
        File dst = new File(ctx.getCacheDir(), localName);
        for (String name : names) for (String base : new String[] { "/data/user/0/", "/data/data/" }) {
            String src = base + stockPackage + "/databases/" + name, owner = "$(stat -c %u:%g " + ctx.getCacheDir().getAbsolutePath() + ")";
            RootSetup.run("rm -f " + dst + " " + dst + "-wal " + dst + "-shm; cp " + src + " " + dst + " && (cp " + src + "-wal " + dst + "-wal; cp " + src + "-shm " + dst + "-shm; true) 2>/dev/null; chmod 666 " + dst + "*; chown " + owner + " " + dst + "*");
            if (dst.exists() && dst.length() > 0) {
                try { return SQLiteDatabase.openDatabase(dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE); } catch (Throwable t) { return null; }   // the copy, so the WAL can be replayed
            }
        }
        return null;
    }

    private static SQLiteDatabase copyStock(Context ctx, String stockPackage, String file, String localName) {
        File dst = new File(ctx.getCacheDir(), localName);
        for (String base : new String[] { "/data/user/0/", "/data/data/" }) {
            RootSetup.run("cp " + base + stockPackage + "/databases/" + file + " " + dst.getAbsolutePath() + " && chmod 644 " + dst.getAbsolutePath());
            if (dst.exists() && dst.length() > 0) break;
        }
        if (!dst.exists() || dst.length() == 0) return null;
        try { return SQLiteDatabase.openDatabase(dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY); } catch (Throwable t) { return null; }
    }

    private static String col(Cursor c, String n, String d) { int i = c.getColumnIndex(n); return i < 0 || c.isNull(i) ? d : c.getString(i); }
    private static int coli(Cursor c, String n, int d) { int i = c.getColumnIndex(n); return i < 0 || c.isNull(i) ? d : c.getInt(i); }
}
