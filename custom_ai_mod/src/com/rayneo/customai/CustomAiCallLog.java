package com.rayneo.customai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe persistent ring buffer (max 30 records) and live lifecycle event timeline
 * for verifying every Custom LLM call, returned response, latency, and any cutoffs.
 */
public final class CustomAiCallLog {

    public static final String PREF_NAME = "rayneo_custom_ai_call_logs";
    private static final String KEY_ENTRIES_JSON = "entries_json";
    private static final int MAX_ENTRIES = 30;
    private static final int MAX_EVENTS_PER_ENTRY = 40;
    private static final int MAX_TEXT_STORE_LEN = 8000;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_TTS_PLAYING = "TTS_PLAYING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    public static final String STATUS_ERROR = "ERROR";

    public static final class Entry {
        public long id;
        public long startWallMs;
        public long startMonoMs;
        public String timeStr = "";
        public String channel = "";
        public String protocol = "";
        public String endpointUrl = "";
        public String model = "";
        public boolean stream = true;
        public String deviceModel = "";
        public boolean enableTts = false;
        public boolean autoNextRound = false;
        public String query = "";
        public String reasoning = "";
        public String response = "";
        public int chunkCount = 0;
        public long firstTokenMs = -1L;
        public long totalMs = 0L;
        public String status = STATUS_RUNNING;
        public String errorMsg = "";
        public final List<String> events = new ArrayList<>();

        public Entry copy() {
            Entry c = new Entry();
            c.id = id;
            c.startWallMs = startWallMs;
            c.startMonoMs = startMonoMs;
            c.timeStr = timeStr;
            c.channel = channel;
            c.protocol = protocol;
            c.endpointUrl = endpointUrl;
            c.model = model;
            c.stream = stream;
            c.deviceModel = deviceModel;
            c.enableTts = enableTts;
            c.autoNextRound = autoNextRound;
            c.query = query;
            c.reasoning = reasoning;
            c.response = response;
            c.chunkCount = chunkCount;
            c.firstTokenMs = firstTokenMs;
            c.totalMs = totalMs;
            c.status = status;
            c.errorMsg = errorMsg;
            c.events.addAll(events);
            return c;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loadedFromPrefs = false;
    private static final AtomicLong ID_GEN = new AtomicLong(System.currentTimeMillis() * 100L);
    private static volatile Runnable uiRefreshCallback;
    private static volatile Handler mainHandler;

    private CustomAiCallLog() {}

    public static void setUiRefreshCallback(Runnable callback) {
        uiRefreshCallback = callback;
    }

    public static long startCall(
            Context ctx,
            String channel,
            CustomAiConfig cfg,
            String query,
            String deviceModel,
            boolean enableTts,
            boolean autoNextRound
    ) {
        long nowWall = System.currentTimeMillis();
        long nowMono = monoMs();
        Entry e = new Entry();
        e.id = ID_GEN.incrementAndGet();
        e.startWallMs = nowWall;
        e.startMonoMs = nowMono;
        e.timeStr = formatTime(nowWall);
        e.channel = safe(channel);
        if (cfg != null) {
            e.protocol = safe(cfg.apiProtocol);
            e.endpointUrl = OpenAiApiClient.resolveEndpointUrl(cfg.baseUrl, cfg.apiProtocol);
            e.model = safe(cfg.model);
            e.stream = cfg.streamEnabled;
        }
        e.deviceModel = safe(deviceModel);
        e.enableTts = enableTts;
        e.autoNextRound = autoNextRound;
        e.query = safe(query);
        e.status = STATUS_RUNNING;
        e.events.add("+0ms CALL_START (" + e.channel + ", tts=" + enableTts + ", autoNext=" + autoNextRound + ")");

        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            ENTRIES.add(0, e);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.remove(ENTRIES.size() - 1);
            }
            saveLocked(ctx);
        }
        notifyUiRefresh();
        return e.id;
    }

    public static long startOfficialSkillCall(
            Context ctx,
            String channel,
            String query,
            String deviceModel,
            boolean enableTts,
            boolean autoNextRound,
            String initialEvent
    ) {
        long nowWall = System.currentTimeMillis();
        long nowMono = monoMs();
        Entry e = new Entry();
        e.id = ID_GEN.incrementAndGet();
        e.startWallMs = nowWall;
        e.startMonoMs = nowMono;
        e.timeStr = formatTime(nowWall);
        e.channel = safe(channel);
        e.protocol = "rayneo_official";
        e.endpointUrl = "official://rayclaw-and-local-skills";
        e.model = "↪ 官方技能/联网引擎";
        e.stream = true;
        e.deviceModel = safe(deviceModel);
        e.enableTts = enableTts;
        e.autoNextRound = autoNextRound;
        e.query = safe(query);
        e.status = STATUS_RUNNING;
        e.events.add("+0ms OFFICIAL_SKILL_START (" + e.channel + ")");
        if (initialEvent != null && !initialEvent.trim().isEmpty()) {
            e.events.add("+0ms " + initialEvent.trim());
        }

        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            ENTRIES.add(0, e);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.remove(ENTRIES.size() - 1);
            }
            saveLocked(ctx);
        }
        notifyUiRefresh();
        return e.id;
    }

    public static long startSystemMediaCall(
            Context ctx,
            String channel,
            String query,
            String deviceModel,
            boolean enableTts,
            boolean autoNextRound
    ) {
        long nowWall = System.currentTimeMillis();
        long nowMono = monoMs();
        Entry e = new Entry();
        e.id = ID_GEN.incrementAndGet();
        e.startWallMs = nowWall;
        e.startMonoMs = nowMono;
        e.timeStr = formatTime(nowWall);
        e.channel = safe(channel);
        e.protocol = "android_media_session";
        e.endpointUrl = "system://MediaSessionManager";
        e.model = "🎵 系统媒体播控 & 曲目抓取";
        e.stream = false;
        e.deviceModel = safe(deviceModel);
        e.enableTts = enableTts;
        e.autoNextRound = autoNextRound;
        e.query = safe(query);
        e.status = STATUS_RUNNING;
        e.events.add("+0ms SYSTEM_MEDIA_START (" + e.channel + ")");

        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            ENTRIES.add(0, e);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.remove(ENTRIES.size() - 1);
            }
            saveLocked(ctx);
        }
        notifyUiRefresh();
        return e.id;
    }

    public static void markRouteConvertedToOfficialSkill(Context ctx, long id, String reason) {
        if (id <= 0L) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            e.protocol = "rayneo_official";
            e.endpointUrl = "official://rayclaw-and-local-skills";
            e.model = "↪ 官方技能/联网引擎";
            e.reasoning = "";
            e.response = "";
            e.chunkCount = 0;
            e.firstTokenMs = -1L;
            e.status = STATUS_RUNNING;
            e.errorMsg = "";
            appendEventLocked(e, "ROUTE->OFFICIAL_SKILL (" + safe(reason) + ")");
            saveLocked(ctx);
        }
        notifyUiRefresh();
    }

    public static void updateQueryIfEmpty(long id, String query) {
        if (id <= 0L || query == null || query.trim().isEmpty()) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            if (e.query == null || e.query.trim().isEmpty()) {
                e.query = query.trim();
            }
        }
    }

    public static void addEvent(long id, String event) {
        if (id <= 0L || event == null || event.trim().isEmpty()) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            appendEventLocked(e, event.trim());
        }
    }

    public static void onReasoningDelta(long id, String delta) {
        if (id <= 0L || delta == null || delta.isEmpty()) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            if (e.reasoning.length() < MAX_TEXT_STORE_LEN) {
                int remain = MAX_TEXT_STORE_LEN - e.reasoning.length();
                e.reasoning += (delta.length() <= remain ? delta : delta.substring(0, remain));
            }
        }
    }

    public static void onTextDelta(long id, String delta) {
        if (id <= 0L || delta == null || delta.isEmpty()) return;
        boolean notify = false;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            boolean first = (e.chunkCount == 0);
            e.chunkCount++;
            long elapsed = Math.max(0L, monoMs() - e.startMonoMs);
            if (e.firstTokenMs < 0L) {
                e.firstTokenMs = elapsed;
                appendEventLocked(e, "FIRST_DELTA(\"" + previewSingleLine(delta, 20) + "\")");
                notify = true;
            }
            if (e.response.length() < MAX_TEXT_STORE_LEN) {
                int remain = MAX_TEXT_STORE_LEN - e.response.length();
                e.response += (delta.length() <= remain ? delta : delta.substring(0, remain));
            }
            e.totalMs = elapsed;
            if (first) notify = true;
        }
        if (notify) {
            notifyUiRefresh();
        }
    }

    public static void markTtsPlaying(long id) {
        if (id <= 0L) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            if (STATUS_RUNNING.equals(e.status)) {
                e.status = STATUS_TTS_PLAYING;
            }
            appendEventLocked(e, "TTS_PLAYING");
        }
        notifyUiRefresh();
    }

    public static void finishSuccess(Context ctx, long id, String finalText) {
        if (id <= 0L) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            e.totalMs = Math.max(0L, monoMs() - e.startMonoMs);
            if (finalText != null && !finalText.isEmpty()) {
                e.response = clampText(finalText, MAX_TEXT_STORE_LEN);
            }
            if (!STATUS_INTERRUPTED.equals(e.status) && !STATUS_ERROR.equals(e.status)) {
                e.status = STATUS_SUCCESS;
            }
            appendEventLocked(e, "FINISH_SUCCESS(len=" + e.response.length() + ", chunks=" + e.chunkCount + ")");
            saveLocked(ctx);
        }
        notifyUiRefresh();
    }

    public static void finishInterrupted(Context ctx, long id, String reason) {
        if (id <= 0L) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            e.totalMs = Math.max(0L, monoMs() - e.startMonoMs);
            if (STATUS_RUNNING.equals(e.status) || STATUS_TTS_PLAYING.equals(e.status)) {
                e.status = STATUS_INTERRUPTED;
                e.errorMsg = safe(reason);
            }
            appendEventLocked(e, "INTERRUPTED(" + safe(reason) + ")");
            saveLocked(ctx);
        }
        notifyUiRefresh();
    }

    public static void finishError(Context ctx, long id, String error) {
        if (id <= 0L) return;
        synchronized (LOCK) {
            Entry e = findLocked(id);
            if (e == null) return;
            e.totalMs = Math.max(0L, monoMs() - e.startMonoMs);
            e.status = STATUS_ERROR;
            e.errorMsg = safe(error);
            appendEventLocked(e, "ERROR(" + safe(error) + ")");
            saveLocked(ctx);
        }
        notifyUiRefresh();
    }

    public static List<Entry> getEntries(Context ctx) {
        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            List<Entry> out = new ArrayList<>(ENTRIES.size());
            for (Entry e : ENTRIES) {
                out.add(e.copy());
            }
            return out;
        }
    }

    public static Entry getLatest(Context ctx) {
        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            return ENTRIES.isEmpty() ? null : ENTRIES.get(0).copy();
        }
    }

    public static int getCount(Context ctx) {
        synchronized (LOCK) {
            ensureLoadedLocked(ctx);
            return ENTRIES.size();
        }
    }

    public static void clearAll(Context ctx) {
        synchronized (LOCK) {
            ENTRIES.clear();
            loadedFromPrefs = true;
            saveLocked(ctx);
        }
        notifyUiRefresh();
    }

    public static String getLiveBadgeLabel() {
        synchronized (LOCK) {
            if (ENTRIES.isEmpty()) return null;
            Entry top = ENTRIES.get(0);
            long elapsed = Math.max(0L, monoMs() - top.startMonoMs);
            if (elapsed > 120_000L) {
                return null;
            }
            if (STATUS_RUNNING.equals(top.status)) {
                return top.chunkCount > 0 ? "⚡输出中…" : "⚡请求中…";
            }
            if (STATUS_TTS_PLAYING.equals(top.status)) {
                return "⚡播报中…";
            }
            return null;
        }
    }

    public static String statusBadge(String status) {
        if (STATUS_SUCCESS.equals(status)) return "✅ 成功";
        if (STATUS_RUNNING.equals(status)) return "⏳ 请求中";
        if (STATUS_TTS_PLAYING.equals(status)) return "🔊 播报中";
        if (STATUS_INTERRUPTED.equals(status)) return "⚠️ 中断";
        if (STATUS_ERROR.equals(status)) return "❌ 失败";
        return status == null ? "" : status;
    }

    public static String formatEntrySummary(Entry e) {
        if (e == null) return "";
        String firstStr = e.firstTokenMs >= 0 ? (e.firstTokenMs + "ms") : "-";
        String q = previewSingleLine(e.query, 42);
        String a = !e.errorMsg.isEmpty() && !STATUS_SUCCESS.equals(e.status)
                ? ("[原因] " + previewSingleLine(e.errorMsg, 54))
                : previewSingleLine(e.response, 54);
        if (a.isEmpty()) a = "(无返回文本)";
        return statusBadge(e.status) + "  " + shortTime(e.timeStr) + "  " + e.channel + "\n"
                + "模型: " + e.model + " | 首字: " + firstStr + " / 总耗时: " + e.totalMs + "ms | 分片: " + e.chunkCount + "\n"
                + "问: " + q + "\n"
                + "答: " + a;
    }

    public static String formatEntryDetail(Entry e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(statusBadge(e.status)).append('\n');
        sb.append("时间: ").append(e.timeStr).append('\n');
        sb.append("渠道: ").append(e.channel).append('\n');
        sb.append("协议: ").append(e.protocol).append(" (stream=").append(e.stream).append(")\n");
        sb.append("端点: ").append(e.endpointUrl).append('\n');
        sb.append("模型: ").append(e.model).append('\n');
        sb.append("设备/配置: ").append(e.deviceModel.isEmpty() ? "-" : e.deviceModel)
                .append(" | enableTTS=").append(e.enableTts)
                .append(" | autoNextRound=").append(e.autoNextRound).append('\n');
        sb.append("耗时与分片: 首字=").append(e.firstTokenMs >= 0 ? (e.firstTokenMs + "ms") : "-")
                .append(" | 总耗时=").append(e.totalMs).append("ms")
                .append(" | 分片=").append(e.chunkCount).append(" 块")
                .append(" | 长度=").append(e.response.length()).append(" 字\n");
        if (!e.errorMsg.isEmpty()) {
            sb.append("中断/错误说明: ").append(e.errorMsg).append('\n');
        }
        sb.append("\n--- [提问 Query] ---\n").append(e.query.isEmpty() ? "(空)" : e.query).append('\n');
        if (!e.reasoning.isEmpty()) {
            sb.append("\n--- [思考 Reasoning] ---\n").append(e.reasoning).append('\n');
        }
        sb.append("\n--- [完整返回 Response] ---\n").append(e.response.isEmpty() ? "(无返回内容)" : e.response).append('\n');
        sb.append("\n--- [生命周期事件 Timeline] ---\n");
        for (String ev : e.events) {
            sb.append("• ").append(ev).append('\n');
        }
        return sb.toString();
    }

    public static String formatAllEntries(Context ctx) {
        List<Entry> list = getEntries(ctx);
        if (list.isEmpty()) return "暂无调用记录";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append("================ 记录 #").append(i + 1).append(" ================\n");
            sb.append(formatEntryDetail(list.get(i))).append('\n');
        }
        return sb.toString();
    }

    public static String serializeEntries(List<Entry> list) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> arr = new ArrayList<>();
        if (list != null) {
            for (Entry e : list) {
                if (e == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id);
                m.put("startWallMs", e.startWallMs);
                m.put("timeStr", safe(e.timeStr));
                m.put("channel", safe(e.channel));
                m.put("protocol", safe(e.protocol));
                m.put("endpointUrl", safe(e.endpointUrl));
                m.put("model", safe(e.model));
                m.put("stream", e.stream);
                m.put("deviceModel", safe(e.deviceModel));
                m.put("enableTts", e.enableTts);
                m.put("autoNextRound", e.autoNextRound);
                m.put("query", clampText(e.query, MAX_TEXT_STORE_LEN));
                m.put("reasoning", clampText(e.reasoning, MAX_TEXT_STORE_LEN));
                m.put("response", clampText(e.response, MAX_TEXT_STORE_LEN));
                m.put("chunkCount", e.chunkCount);
                m.put("firstTokenMs", e.firstTokenMs);
                m.put("totalMs", e.totalMs);
                m.put("status", safe(e.status));
                m.put("errorMsg", safe(e.errorMsg));
                m.put("events", new ArrayList<>(e.events));
                arr.add(m);
            }
        }
        root.put("entries", arr);
        return JsonMini.stringify(root);
    }

    public static List<Entry> deserializeEntries(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            Map<String, Object> root = JsonMini.parseObject(json);
            List<Object> arr = JsonMini.getList(root, "entries");
            if (arr == null) return out;
            long nowMono = monoMs();
            for (Object item : arr) {
                Map<String, Object> m = JsonMini.asMap(item);
                if (m == null) continue;
                Entry e = new Entry();
                e.id = readLong(m, "id", 0L);
                e.startWallMs = readLong(m, "startWallMs", 0L);
                e.startMonoMs = nowMono;
                e.timeStr = readStr(m, "timeStr");
                e.channel = readStr(m, "channel");
                e.protocol = readStr(m, "protocol");
                e.endpointUrl = readStr(m, "endpointUrl");
                e.model = readStr(m, "model");
                e.stream = readBool(m, "stream", true);
                e.deviceModel = readStr(m, "deviceModel");
                e.enableTts = readBool(m, "enableTts", false);
                e.autoNextRound = readBool(m, "autoNextRound", false);
                e.query = readStr(m, "query");
                e.reasoning = readStr(m, "reasoning");
                e.response = readStr(m, "response");
                e.chunkCount = (int) readLong(m, "chunkCount", 0L);
                e.firstTokenMs = readLong(m, "firstTokenMs", -1L);
                e.totalMs = readLong(m, "totalMs", 0L);
                e.status = readStr(m, "status");
                e.errorMsg = readStr(m, "errorMsg");
                // Any in-flight record loaded from previous process lifetime is incomplete
                if (STATUS_RUNNING.equals(e.status) || STATUS_TTS_PLAYING.equals(e.status)) {
                    e.status = STATUS_INTERRUPTED;
                    if (e.errorMsg.isEmpty()) e.errorMsg = "进程重启，未正常收尾";
                }
                List<Object> evs = JsonMini.getList(m, "events");
                if (evs != null) {
                    for (Object ev : evs) {
                        if (ev != null) e.events.add(String.valueOf(ev));
                    }
                }
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void appendEventLocked(Entry e, String event) {
        if (e.events.size() >= MAX_EVENTS_PER_ENTRY) return;
        long elapsed = Math.max(0L, monoMs() - e.startMonoMs);
        String line = event.startsWith("+") ? event : ("+" + elapsed + "ms " + event);
        e.events.add(line);
    }

    private static Entry findLocked(long id) {
        for (Entry e : ENTRIES) {
            if (e.id == id) return e;
        }
        return null;
    }

    private static void ensureLoadedLocked(Context ctx) {
        if (loadedFromPrefs || ctx == null) return;
        loadedFromPrefs = true;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_ENTRIES_JSON, "");
            if (raw != null && !raw.isEmpty()) {
                List<Entry> loaded = deserializeEntries(raw);
                for (Entry item : loaded) {
                    if (ENTRIES.size() >= MAX_ENTRIES) break;
                    ENTRIES.add(item);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void saveLocked(Context ctx) {
        if (ctx == null) return;
        try {
            String json = serializeEntries(ENTRIES);
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_ENTRIES_JSON, json).apply();
        } catch (Throwable ignored) {}
    }

    private static void notifyUiRefresh() {
        final Runnable cb = uiRefreshCallback;
        if (cb == null) return;
        try {
            Handler h = mainHandler;
            if (h == null) {
                Looper mainLooper = Looper.getMainLooper();
                if (mainLooper != null) {
                    h = new Handler(mainLooper);
                    mainHandler = h;
                }
            }
            if (h != null) {
                h.post(cb);
            }
        } catch (Throwable ignored) {}
    }

    private static long monoMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static String formatTime(long wallMs) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(wallMs));
        } catch (Throwable t) {
            return String.valueOf(wallMs);
        }
    }

    private static String shortTime(String ts) {
        if (ts == null) return "";
        int sp = ts.indexOf(' ');
        if (sp >= 0 && sp + 9 <= ts.length()) {
            return ts.substring(sp + 1, sp + 9);
        }
        return ts;
    }

    public static String previewSingleLine(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\r', ' ').replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String clampText(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : (s.substring(0, max) + "...(truncated)");
    }

    private static String readStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return (v instanceof String) ? (String) v : "";
    }

    private static long readLong(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).longValue() : def;
    }

    private static boolean readBool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
