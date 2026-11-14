package com.rayneo.customai;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.ffalconxr.mercury.coreconnect.b;
import com.ffalconxr.mercury.coreconnect.g0;
import com.ffalconxr.mercury.coreconnect.v0;
import com.ffalconxr.mercury.coreconnect.w;
import com.rayneo.rayneo_venus_sdk_plugin.h4;
import com.rayneo.rayneo_venus_sdk_plugin.o4;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-time Music Lyrics <-> RayNeo Glasses Teleprompter Bridge.
 *
 * Architecture:
 * 1. Polls active Android MediaSession track & interpolated playback position (250ms when playing).
 * 2. Fetches millisecond-synced .lrc lyrics from NetEase Cloud Music with QQ Music fallback,
 *    decodes HTML entities / Base64, pre-wraps CJK lines to fit the glasses HUD width (420px @ 20sp)
 *    1:1 with UTF-8 '\n' byte offsets, and caches parsed manuscripts in an LRU cache.
 * 3. Drives the RayNeo CoreConnect BLE Teleprompter channel (vb.h.TELEPROMPTER = 0x14) using the
 *    exact GlassesMsg 6-field protobuf wire format and TeleprompterHandshakeAction state machine
 *    (0x02 AppStartRequest snippetMode -> code 1/23 active | code 7 sendFile+0x03 fallback |
 *    conflict code acknowledge, plus 0x08 ProgressUpdateRequest on line transitions).
 */
public final class LyricsTeleprompterBridge {

    private static final String TAG = "RayNeoLyricsPrompter";

    // GlassesMsg protobuf message types (field 2)
    public static final int MSG_LIST_UPDATE = 0x01;
    public static final int MSG_START = 0x02;
    public static final int MSG_CUEING_START = 0x03;
    public static final int MSG_PAUSE = 0x04;
    public static final int MSG_RESUME = 0x05;
    public static final int MSG_STOP = 0x06;
    public static final int MSG_SETTINGS_UPDATE = 0x07;
    public static final int MSG_PROGRESS_UPDATE = 0x08;
    public static final int MSG_AUDIO_DATA = 0x09;
    public static final int MSG_CHECK_STATE = 0x0A;
    public static final int MSG_TEMPLE_STATE = 0x0B;

    // Voice command actions
    public static final int CMD_NONE = 0;
    public static final int CMD_ENABLE_SYNC = 1;
    public static final int CMD_DISABLE_SYNC = 2;

    // Session state machine
    private static final int SESSION_IDLE = 0;
    private static final int SESSION_STARTING = 1;
    private static final int SESSION_WAITING_FILE_SHARE = 2;
    private static final int SESSION_STARTING_CUEING = 3;
    private static final int SESSION_ACTIVE = 4;
    private static final int SESSION_PAUSED = 5;

    // HUD layout constants matching TeleprompterScene.speech (width=420, size=20, leading=24, scroll=manual)
    public static final int HUD_WIDTH = 420;
    public static final int HUD_FONT_SIZE = 20;
    public static final int HUD_LEADING = 24;
    public static final int HUD_SCROLL_MANUAL = 3;
    public static final int HUD_SPEED = 120;
    public static final int HUD_GEAR_LOW = 1;
    public static final int HUD_DEPTH_DEEP = 3;

    // 34 half-width units = 17 full-width CJK chars -> guaranteed single visual line on 420px @ 20sp HUD
    public static final int MAX_LINE_HALF_WIDTH = 34;
    private static final int MAX_SNIPPET_BYTES = 28000;
    private static final int HTTP_TIMEOUT_MS = 4200;
    private static final long TICK_ACTIVE_MS = 250L;
    private static final long TICK_IDLE_MS = 1800L;
    private static final long LYRIC_LEAD_COMPENSATION_MS = 120L;
    private static final long HANDSHAKE_TIMEOUT_MS = 4500L;
    private static final long BACKOFF_RETRY_MS = 10000L;
    private static final long VOICE_BUSY_MAX_MS = 22000L;

    private static final String LYRICS_DID_PREFIX = "99";

    private static final Pattern PAT_WAKE_PREFIX = Pattern.compile(
            "^(?:小雷小雷|小雷|嘿小雷|你好小雷)[，,、\\s]*");

    private static final Pattern PAT_CMD_ENABLE = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:打开|开启|显示|启动|恢复|同步|投屏|投影|看看|查看)(?:一下)?(?:眼镜|提词器|音乐|歌曲|当前)?(?:的)?(?:实时|滚动|动态)?歌词(?:联动|提词器|提词|同步|显示|投屏)?"
                    + "|(?:打开|开启|启动)(?:歌词)?提词器(?:歌词)?联动"
                    + "|歌词(?:实时)?(?:联动|同步|投屏|上屏|显示)"
                    + "|把(?:当前)?歌词(?:投到|放到|同步到|显示在|推到)(?:眼镜|提词器)(?:上)?"
                    + ")"
                    + "(?:吧|呀|啊|呗|好吗|好不好)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_CMD_DISABLE = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:关闭|关掉|停止|退出|隐藏|取消|断开)(?:一下)?(?:眼镜|提词器|音乐|歌曲)?(?:的)?(?:实时|滚动|动态)?歌词(?:联动|提词器|提词|同步|显示|投屏)?"
                    + "|(?:关闭|退出|取消)(?:歌词)?提词器(?:歌词)?联动"
                    + "|不(?:要)?(?:看|显示)歌词(?:了)?"
                    + ")"
                    + "(?:吧|呀|啊|呗)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_LRC_OFFSET = Pattern.compile(
            "^\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_LRC_TIME = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private static final Pattern PAT_LRC_META_LINE = Pattern.compile(
            "^\\[[a-zA-Z]{2,8}:[^\\]]*\\]\\s*$");

    private static final Pattern PAT_NUMERIC_DEC_ENTITY = Pattern.compile("&#(\\d{1,7});");
    private static final Pattern PAT_NUMERIC_HEX_ENTITY = Pattern.compile("&#[xX]([0-9a-fA-F]{1,6});");

    // ==================== Data Models ====================

    public static final class LyricsManuscript {
        public final String trackKey;
        public final String did;
        public final String title;
        public final String artist;
        public final String[] lines;
        public final long[] lineTimesMs;
        public final int[] lineByteOffsets;
        public final byte[] utf8Bytes;
        public final String checksum;
        public final boolean hasSyncedLyrics;
        public final String sourcePlatform;

        public LyricsManuscript(
                String trackKey,
                String did,
                String title,
                String artist,
                String[] lines,
                long[] lineTimesMs,
                int[] lineByteOffsets,
                byte[] utf8Bytes,
                String checksum,
                boolean hasSyncedLyrics,
                String sourcePlatform
        ) {
            this.trackKey = trackKey;
            this.did = did;
            this.title = title;
            this.artist = artist;
            this.lines = lines;
            this.lineTimesMs = lineTimesMs;
            this.lineByteOffsets = lineByteOffsets;
            this.utf8Bytes = utf8Bytes;
            this.checksum = checksum;
            this.hasSyncedLyrics = hasSyncedLyrics;
            this.sourcePlatform = sourcePlatform == null ? "" : sourcePlatform;
        }
    }

    public static final class GlassesMsg {
        public int version = 1;
        public int type = 0;
        public String jsonMessage = "";
        public byte[] data = new byte[0];
        public int sequence = 0;
        public int mode = 0;
    }

    private static final class TimedLine {
        final long timeMs;
        final String text;
        final int order;

        TimedLine(long timeMs, String text, int order) {
            this.timeMs = timeMs;
            this.text = text;
            this.order = order;
        }
    }

    // ==================== Runtime State ====================

    private static volatile Application appContext;
    private static volatile o4 lastSeenBridge;
    private static volatile String lastSeenDeviceId = "";
    private static volatile HandlerThread workerThread;
    private static volatile Handler workerHandler;

    private static final AtomicInteger SEQ_GEN = new AtomicInteger(1);
    private static final AtomicInteger MSG_ID_GEN = new AtomicInteger(1000);

    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, LyricsManuscript> MANUSCRIPT_CACHE =
            new LinkedHashMap<String, LyricsManuscript>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LyricsManuscript> eldest) {
                    return size() > 24;
                }
            };

    // State accessed exclusively on workerHandler thread
    private static LyricsManuscript activeManuscript;
    private static int sessionState = SESSION_IDLE;
    private static boolean sessionSnippetMode = true;
    private static int lastSentLineIdx = -1;
    private static long stateEnteredUptimeMs = 0L;
    private static int handshakeAttempts = 0;
    private static boolean conflictRetried = false;
    private static long backoffUntilUptimeMs = 0L;
    private static String pendingFileTaskId = "";
    private static String stoppedByGlassesTrackKey = "";
    private static boolean foreignTeleprompterActive = false;
    private static volatile boolean voiceAssistantBusy = false;
    private static volatile long voiceBusySinceUptimeMs = 0L;
    private static volatile String lastStatusSummary = "未启动";

    private static final Runnable TICK_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            onWorkerTick();
        }
    };

    private LyricsTeleprompterBridge() {}

    // ==================== Lifecycle & Public Triggers ====================

    public static synchronized void init(Application app) {
        if (app == null) return;
        appContext = app;
        if (workerThread == null) {
            HandlerThread ht = new HandlerThread("RayNeoLyricsPrompter");
            ht.setDaemon(true);
            ht.start();
            workerThread = ht;
            workerHandler = new Handler(ht.getLooper());
        }
        scheduleTick(400L);
    }

    public static String getStatusSummary() {
        return lastStatusSummary;
    }

    public static void onConfigChanged(Context ctx) {
        if (ctx != null && appContext == null && ctx.getApplicationContext() instanceof Application) {
            init((Application) ctx.getApplicationContext());
        }
        Handler h = workerHandler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                Application app = appContext;
                if (app == null) return;
                CustomAiConfig cfg = CustomAiConfig.load(app);
                if (!cfg.syncLyricsToTeleprompter) {
                    stopSessionOnGlasses("开关已关闭");
                    lastStatusSummary = "已关闭";
                } else {
                    stoppedByGlassesTrackKey = "";
                    foreignTeleprompterActive = false;
                    backoffUntilUptimeMs = 0L;
                    onWorkerTick();
                }
            }
        });
    }

    /**
     * Triggered by UI button or voice command ("打开歌词") to immediately push current song lyrics.
     */
    public static void syncNow(final Context ctx, final Runnable onUiDone) {
        if (ctx != null && appContext == null && ctx.getApplicationContext() instanceof Application) {
            init((Application) ctx.getApplicationContext());
        }
        Handler h = workerHandler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                stoppedByGlassesTrackKey = "";
                foreignTeleprompterActive = false;
                backoffUntilUptimeMs = 0L;
                handshakeAttempts = 0;
                conflictRetried = false;
                if (sessionState != SESSION_IDLE && activeManuscript != null) {
                    sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                    resetSessionVars();
                }
                onWorkerTick();
                if (onUiDone != null) {
                    onUiDone.run();
                }
            }
        });
    }

    public static void notifyMediaChanged() {
        Handler h = workerHandler;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                backoffUntilUptimeMs = 0L;
                onWorkerTick();
            }
        }, 350L);
    }

    public static void onVoiceSessionActive(final boolean active) {
        voiceAssistantBusy = active;
        voiceBusySinceUptimeMs = active ? SystemClock.uptimeMillis() : 0L;
        if (!active) {
            scheduleTick(200L);
        }
    }

    // ==================== Voice Command Matching ====================

    public static int matchVoiceCommand(String rawQuery) {
        if (rawQuery == null) return CMD_NONE;
        String s = rawQuery.trim();
        s = s.replaceAll("^[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+", "")
                .replaceAll("[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+$", "");
        if (s.isEmpty()) return CMD_NONE;
        s = PAT_WAKE_PREFIX.matcher(s).replaceFirst("").trim();
        if (s.isEmpty()) return CMD_NONE;

        if (PAT_CMD_DISABLE.matcher(s).matches()) {
            return CMD_DISABLE_SYNC;
        }
        if (PAT_CMD_ENABLE.matcher(s).matches()) {
            return CMD_ENABLE_SYNC;
        }
        return CMD_NONE;
    }

    public static String executeVoiceCommand(Context ctx, int cmd, long logId) {
        if (ctx == null) return "上下文为空";
        CustomAiConfig cfg = CustomAiConfig.load(ctx);
        if (cmd == CMD_ENABLE_SYNC) {
            cfg.syncLyricsToTeleprompter = true;
            cfg.save(ctx);
            syncNow(ctx, null);
            SystemMediaBridge.TrackInfo ti = SystemMediaBridge.getCurrentTrack(ctx);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "LYRICS_SYNC_ENABLED(track="
                        + (ti != null ? ti.formatSongDisplay() : "none") + ")");
            }
            if (ti != null && ti.hasSong()) {
                return "已开启眼镜歌词联动，正在同步：" + ti.formatSongDisplay();
            }
            return "已开启眼镜歌词提词器联动，播放音乐时将自动同步滚动歌词";
        } else if (cmd == CMD_DISABLE_SYNC) {
            cfg.syncLyricsToTeleprompter = false;
            cfg.save(ctx);
            onConfigChanged(ctx);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "LYRICS_SYNC_DISABLED");
            }
            return "已关闭眼镜歌词提词器联动";
        }
        return "";
    }

    // ==================== MOD-05: Incoming RayneoNet Events ====================

    public static void onRayneoNetEvent(final Object bridgeObj, final String eventName, final Map data) {
        try {
            if (bridgeObj instanceof o4) {
                lastSeenBridge = (o4) bridgeObj;
            }
            if (eventName == null) return;

            if ("messageReceived".equals(eventName)) {
                Map msgMap = unwrapMessageMap(data);
                if (msgMap == null) return;
                Object devObj = msgMap.get("deviceId");
                if (devObj instanceof String && !((String) devObj).isEmpty()) {
                    lastSeenDeviceId = (String) devObj;
                }
                Object bizObj = msgMap.get("businessId");
                if (!"TELEPROMPTER".equals(bizObj)) {
                    return;
                }
                Object payloadObj = msgMap.get("payload");
                if (!(payloadObj instanceof byte[])) {
                    return;
                }
                final byte[] payload = (byte[]) payloadObj;
                Handler h = workerHandler;
                if (h != null) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            handleIncomingTeleprompterPayload(payload);
                        }
                    });
                }
            } else if ("fileShareSuccess".equals(eventName) || "fileShareFailed".equals(eventName)) {
                if (data == null) return;
                Object taskObj = data.get("taskId");
                final String taskId = taskObj instanceof String ? (String) taskObj : "";
                final boolean ok = "fileShareSuccess".equals(eventName);
                Handler h = workerHandler;
                if (h != null && !taskId.isEmpty()) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            handleFileShareEvent(taskId, ok);
                        }
                    });
                }
            } else if ("brStateChanged".equals(eventName) || "bondStateChanged".equals(eventName)) {
                Handler h = workerHandler;
                if (h != null) {
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            backoffUntilUptimeMs = 0L;
                            onWorkerTick();
                        }
                    }, 600L);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRayneoNetEvent error", t);
        }
    }

    public static Map unwrapMessageMap(Map eventData) {
        if (eventData == null) return null;
        Object nested = eventData.get("message");
        if (nested instanceof Map) {
            return (Map) nested;
        }
        if (eventData.containsKey("businessId") && eventData.containsKey("payload")) {
            return eventData;
        }
        return null;
    }

    // ==================== Worker Loop & Handshake State Machine ====================

    private static void scheduleTick(long delayMs) {
        Handler h = workerHandler;
        if (h == null) return;
        h.removeCallbacks(TICK_RUNNABLE);
        h.postDelayed(TICK_RUNNABLE, Math.max(40L, delayMs));
    }

    private static void onWorkerTick() {
        Application app = appContext;
        if (app == null) return;

        try {
            CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.syncLyricsToTeleprompter) {
                if (sessionState != SESSION_IDLE) {
                    stopSessionOnGlasses("配置已关闭");
                }
                lastStatusSummary = "未启用";
                return;
            }

            // Safety auto-expire for voiceAssistantBusy
            if (voiceAssistantBusy && SystemClock.uptimeMillis() - voiceBusySinceUptimeMs > VOICE_BUSY_MAX_MS) {
                voiceAssistantBusy = false;
            }
            if (voiceAssistantBusy) {
                scheduleTick(450L);
                return;
            }

            SystemMediaBridge.TrackInfo track = SystemMediaBridge.getCurrentTrack(app);
            if (track == null || !track.hasSong()) {
                if (sessionState != SESSION_IDLE) {
                    stopSessionOnGlasses("无正在播放的歌曲");
                }
                lastStatusSummary = "等待音乐播放…";
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            String trackKey = normalizeTrackKey(track.title, track.artist);

            // If user manually exited teleprompter on glasses temple for this song, respect until next song
            if (trackKey.equals(stoppedByGlassesTrackKey)) {
                lastStatusSummary = "已在眼镜端手动退出（切歌后自动恢复）";
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            if (foreignTeleprompterActive) {
                lastStatusSummary = "原生提词器使用中（已自动避让）";
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            String deviceId = resolveConnectedGlassesDeviceId();
            if (deviceId.isEmpty()) {
                lastStatusSummary = "等待眼镜蓝牙连接…";
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            boolean isPlaying = track.isPlaying();
            long interpPosMs = track.getInterpolatedPositionMs() + (isPlaying ? LYRIC_LEAD_COMPENSATION_MS : 0L);

            // Track changed?
            if (activeManuscript == null || !trackKey.equals(activeManuscript.trackKey)) {
                if (!isPlaying && sessionState == SESSION_IDLE) {
                    // Music is paused and no session open yet -> wait until user hits play
                    lastStatusSummary = "音乐已暂停：" + track.formatSongDisplay();
                    scheduleTick(TICK_IDLE_MS);
                    return;
                }
                LyricsManuscript ms = getOrFetchManuscript(track);
                activeManuscript = ms;
                sessionState = SESSION_IDLE;
                lastSentLineIdx = -1;
                handshakeAttempts = 0;
                conflictRetried = false;
                backoffUntilUptimeMs = 0L;
                pendingFileTaskId = "";
            }

            long now = SystemClock.uptimeMillis();
            if (now < backoffUntilUptimeMs) {
                scheduleTick(1000L);
                return;
            }

            // Handle music pause/stop transitions
            if (!isPlaying) {
                if (sessionState == SESSION_ACTIVE) {
                    sendPausePacket(activeManuscript);
                    sessionState = SESSION_PAUSED;
                    stateEnteredUptimeMs = now;
                    lastStatusSummary = "已暂停：" + track.formatSongDisplay();
                }
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            // Music IS playing!
            if (sessionState == SESSION_PAUSED) {
                sendResumePacket(activeManuscript);
                sessionState = SESSION_ACTIVE;
                stateEnteredUptimeMs = now;
                lastSentLineIdx = -1; // Force immediate progress sync on resume
            }

            if (sessionState == SESSION_IDLE) {
                startSessionForManuscript(activeManuscript, interpPosMs, deviceId);
                scheduleTick(TICK_ACTIVE_MS);
                return;
            }

            if (sessionState == SESSION_STARTING
                    || sessionState == SESSION_WAITING_FILE_SHARE
                    || sessionState == SESSION_STARTING_CUEING) {
                if (now - stateEnteredUptimeMs > HANDSHAKE_TIMEOUT_MS) {
                    if (handshakeAttempts < 2) {
                        Log.i(TAG, "Handshake timeout in state=" + sessionState + ", retrying...");
                        startSessionForManuscript(activeManuscript, interpPosMs, deviceId);
                    } else {
                        Log.w(TAG, "Handshake unanswered after 2 tries, backing off 10s");
                        sessionState = SESSION_IDLE;
                        handshakeAttempts = 0;
                        backoffUntilUptimeMs = now + BACKOFF_RETRY_MS;
                        lastStatusSummary = "眼镜提词器未响应（稍后重试）";
                    }
                }
                scheduleTick(TICK_ACTIVE_MS);
                return;
            }

            if (sessionState == SESSION_ACTIVE && activeManuscript != null) {
                int lineIdx = findActiveLineIndex(activeManuscript.lineTimesMs, interpPosMs);
                if (lineIdx != lastSentLineIdx) {
                    sendProgressUpdatePacket(activeManuscript, lineIdx);
                    lastSentLineIdx = lineIdx;
                    String curLine = (lineIdx >= 0 && lineIdx < activeManuscript.lines.length)
                            ? activeManuscript.lines[lineIdx] : "";
                    lastStatusSummary = "同步中：" + track.formatSongDisplay()
                            + (curLine.isEmpty() ? "" : (" · " + curLine));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onWorkerTick error", t);
        }

        scheduleTick(TICK_ACTIVE_MS);
    }

    private static void startSessionForManuscript(LyricsManuscript ms, long posMs, String deviceId) {
        if (ms == null) return;
        int lineIdx = findActiveLineIndex(ms.lineTimesMs, posMs);
        int hiOff = ms.lineByteOffsets[lineIdx];
        int pageOff = ms.lineByteOffsets[Math.max(0, lineIdx - 1)];

        handshakeAttempts++;
        stateEnteredUptimeMs = SystemClock.uptimeMillis();
        lastSentLineIdx = lineIdx;

        if (ms.utf8Bytes.length <= MAX_SNIPPET_BYTES) {
            sessionSnippetMode = true;
            sessionState = SESSION_STARTING;
            String json = buildAppStartSnippetRequestJson(ms, pageOff, hiOff);
            byte[] pkt = encodeGlassesMsg(MSG_START, json, ms.utf8Bytes, SEQ_GEN.getAndIncrement());
            sendBleTeleprompter(pkt, deviceId, true);
            lastStatusSummary = "正在投送歌词：" + ms.title;
            Log.i(TAG, "Sent 0x02 AppStartRequest (snippetMode, bytes=" + ms.utf8Bytes.length
                    + ", lines=" + ms.lines.length + ", did=" + ms.did + ")");
        } else {
            // Very large LRC (>28KB): go straight to file share + 0x03 cueing start
            initiateFileShareFallback(ms, deviceId);
        }
    }

    private static void initiateFileShareFallback(LyricsManuscript ms, String deviceId) {
        Application app = appContext;
        if (app == null || ms == null) return;
        try {
            File dir = new File(app.getCacheDir(), "teleprompter");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, ms.did + ".txt");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(ms.utf8Bytes);
            }
            w wInst = resolveCoreConnectW();
            if (wInst != null) {
                String taskId = wInst.v(f, deviceId, wb.q.SmallCore, null);
                if (taskId != null && !taskId.isEmpty()) {
                    pendingFileTaskId = taskId;
                    sessionSnippetMode = false;
                    sessionState = SESSION_WAITING_FILE_SHARE;
                    stateEnteredUptimeMs = SystemClock.uptimeMillis();
                    Log.i(TAG, "Started fileShare fallback taskId=" + taskId + " for did=" + ms.did);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "initiateFileShareFallback failed", t);
        }
        backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
        sessionState = SESSION_IDLE;
    }

    private static void handleFileShareEvent(String taskId, boolean success) {
        if (sessionState != SESSION_WAITING_FILE_SHARE || !taskId.equals(pendingFileTaskId)) {
            return;
        }
        pendingFileTaskId = "";
        if (!success || activeManuscript == null) {
            sessionState = SESSION_IDLE;
            backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
            return;
        }
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty()) return;

        int lineIdx = Math.max(0, lastSentLineIdx);
        int hiOff = activeManuscript.lineByteOffsets[Math.min(lineIdx, activeManuscript.lineByteOffsets.length - 1)];
        int pageOff = activeManuscript.lineByteOffsets[Math.max(0, lineIdx - 1)];

        sessionState = SESSION_STARTING_CUEING;
        stateEnteredUptimeMs = SystemClock.uptimeMillis();
        String json = buildAppCueingStartRequestJson(activeManuscript, pageOff, hiOff);
        byte[] pkt = encodeGlassesMsg(MSG_CUEING_START, json, null, SEQ_GEN.getAndIncrement());
        sendBleTeleprompter(pkt, deviceId, true);
        Log.i(TAG, "File share succeeded -> sent 0x03 AppCueingStartRequest for did=" + activeManuscript.did);
    }

    private static void handleIncomingTeleprompterPayload(byte[] rawPayload) {
        GlassesMsg msg = decodeGlassesMsg(rawPayload);
        if (msg == null) return;

        Map<String, Object> jb;
        if (msg.jsonMessage == null || msg.jsonMessage.trim().isEmpty()) {
            jb = Collections.emptyMap();
        } else {
            try {
                jb = JsonMini.parseObject(msg.jsonMessage);
            } catch (Throwable ignored) {
                jb = Collections.emptyMap();
            }
        }
        String did = safe(JsonMini.getString(jb, "did"));
        if (did.isEmpty() && jb.get("did") instanceof Number) {
            did = String.valueOf(((Number) jb.get("did")).longValue());
        }
        int action = JsonMini.getInt(jb, "action", 0);
        int code = JsonMini.getInt(jb, "code", 0);

        // Detect foreign (official Flutter) teleprompter session so we never collide with user's notes
        if (!did.isEmpty() && !isLyricsDid(did)) {
            if (msg.type == MSG_STOP) {
                foreignTeleprompterActive = false;
            } else if (msg.type == MSG_START || msg.type == MSG_CUEING_START || msg.type == MSG_PROGRESS_UPDATE) {
                foreignTeleprompterActive = true;
                resetSessionVars();
            }
            return;
        }

        String deviceId = resolveConnectedGlassesDeviceId();

        switch (msg.type) {
            case MSG_START: {
                if (action == 2) {
                    // GlassesStartResponse to our 0x02 AppStartRequest
                    if (activeManuscript == null || (!did.isEmpty() && !did.equals(activeManuscript.did))) {
                        return;
                    }
                    if (code == 1 || code == 23) {
                        sessionState = SESSION_ACTIVE;
                        stateEnteredUptimeMs = SystemClock.uptimeMillis();
                        handshakeAttempts = 0;
                        lastSentLineIdx = -1; // Push exact current line on next tick
                        Log.i(TAG, "Teleprompter 0x02 handshake ACTIVE (code=" + code + ", did=" + did + ")");
                        scheduleTick(50L);
                    } else if (code == 7) {
                        Log.i(TAG, "Glasses requested full file transfer (code=7) for did=" + did);
                        if (!deviceId.isEmpty()) {
                            initiateFileShareFallback(activeManuscript, deviceId);
                        }
                    } else if (isConflictCode(code) && !conflictRetried) {
                        conflictRetried = true;
                        Log.i(TAG, "Teleprompter conflict code=" + code + " -> sending conflict ack & retrying");
                        String ackJson = buildConflictAckJson(activeManuscript.did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_START, ackJson, null, SEQ_GEN.getAndIncrement()),
                                deviceId, true);
                        sessionState = SESSION_IDLE;
                        handshakeAttempts = 0;
                        scheduleTick(280L);
                    } else if (code == 18 || code == 24) {
                        // Folded temple legs / busy state
                        sessionState = SESSION_IDLE;
                        backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
                        lastStatusSummary = "眼镜镜腿已折叠或界面占用";
                    } else {
                        Log.w(TAG, "Unhandled 0x02 response code=" + code);
                    }
                } else if (action == 1 && activeManuscript != null && did.equals(activeManuscript.did)) {
                    // SnippetServe: Glasses requesting snippet data for our manuscript!
                    Map<String, Object> sInfo = JsonMini.getMap(jb, "snippetInfo");
                    boolean skipData = sInfo != null && Boolean.TRUE.equals(sInfo.get("skipData"));
                    boolean isCurrent = sInfo == null || !Boolean.FALSE.equals(sInfo.get("isCurrent"));
                    int idx = Math.max(0, lastSentLineIdx);
                    int hiOff = activeManuscript.lineByteOffsets[Math.min(idx, activeManuscript.lineByteOffsets.length - 1)];
                    int pageOff = activeManuscript.lineByteOffsets[Math.max(0, idx - 1)];

                    String respJson = buildSnippetServeResponseJson(
                            activeManuscript, pageOff, hiOff, skipData, isCurrent);
                    byte[] dataBytes = skipData ? null : activeManuscript.utf8Bytes;
                    sendBleTeleprompter(
                            encodeGlassesMsg(MSG_START, respJson, dataBytes, msg.sequence),
                            deviceId, true);
                    sessionState = SESSION_ACTIVE;
                    stateEnteredUptimeMs = SystemClock.uptimeMillis();
                    Log.i(TAG, "Served 0x02 snippet request (skipData=" + skipData + ") for did=" + did);
                }
                break;
            }

            case MSG_CUEING_START: {
                if (action == 2 && activeManuscript != null && (did.isEmpty() || did.equals(activeManuscript.did))) {
                    if (code == 1 || code == 23) {
                        sessionState = SESSION_ACTIVE;
                        stateEnteredUptimeMs = SystemClock.uptimeMillis();
                        handshakeAttempts = 0;
                        lastSentLineIdx = -1;
                        Log.i(TAG, "Teleprompter 0x03 cueing ACTIVE (code=" + code + ")");
                        scheduleTick(50L);
                    } else if (isConflictCode(code) && !conflictRetried) {
                        conflictRetried = true;
                        String ackJson = buildConflictAckJson(activeManuscript.did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_CUEING_START, ackJson, null, SEQ_GEN.getAndIncrement()),
                                deviceId, true);
                        sessionState = SESSION_IDLE;
                        handshakeAttempts = 0;
                        scheduleTick(280L);
                    }
                }
                break;
            }

            case MSG_PAUSE:
            case MSG_RESUME: {
                if (action == 1 && isLyricsDid(did) && !deviceId.isEmpty()) {
                    String ack = buildSimpleAckJson(did);
                    sendBleTeleprompter(
                            encodeGlassesMsg(msg.type, ack, null, msg.sequence),
                            deviceId, true);
                }
                break;
            }

            case MSG_STOP: {
                if (action == 1 && isLyricsDid(did)) {
                    if (!deviceId.isEmpty()) {
                        String ack = buildSimpleAckJson(did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_STOP, ack, null, msg.sequence),
                                deviceId, true);
                    }
                    if (activeManuscript != null) {
                        stoppedByGlassesTrackKey = activeManuscript.trackKey;
                    }
                    resetSessionVars();
                    lastStatusSummary = "已在眼镜端退出当前歌曲歌词";
                    Log.i(TAG, "Glasses temple exited lyrics teleprompter (did=" + did + ")");
                }
                break;
            }

            case MSG_CHECK_STATE: {
                if (action == 1 && activeManuscript != null
                        && (sessionState == SESSION_ACTIVE || sessionState == SESSION_PAUSED)
                        && !deviceId.isEmpty()) {
                    int idx = Math.max(0, lastSentLineIdx);
                    int hiOff = activeManuscript.lineByteOffsets[Math.min(idx, activeManuscript.lineByteOffsets.length - 1)];
                    int pageOff = activeManuscript.lineByteOffsets[Math.max(0, idx - 1)];
                    int stateVal = (sessionState == SESSION_ACTIVE) ? 1 : 2;
                    String respJson = buildCheckStateResponseJson(activeManuscript, stateVal, pageOff, hiOff);
                    sendBleTeleprompter(
                            encodeGlassesMsg(MSG_CHECK_STATE, respJson, null, msg.sequence),
                            deviceId, true);
                }
                break;
            }
        }
    }

    public static boolean isConflictCode(int code) {
        switch (code) {
            case 8:
            case 9:
            case 10:
            case 20:
            case 21:
            case 22:
            case 25:
            case 26:
            case 28:
                return true;
            default:
                return false;
        }
    }

    public static boolean isLyricsDid(String did) {
        return did != null && did.length() == 10 && did.startsWith(LYRICS_DID_PREFIX);
    }

    private static void sendProgressUpdatePacket(LyricsManuscript ms, int lineIdx) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        int safeIdx = Math.max(0, Math.min(lineIdx, ms.lineByteOffsets.length - 1));
        int hiOff = ms.lineByteOffsets[safeIdx];
        int pageOff = ms.lineByteOffsets[Math.max(0, safeIdx - 1)];
        String json = buildProgressUpdateJson(ms.did, pageOff, hiOff, sessionSnippetMode);
        byte[] pkt = encodeGlassesMsg(MSG_PROGRESS_UPDATE, json, null, SEQ_GEN.getAndIncrement());
        sendBleTeleprompter(pkt, deviceId, false);
    }

    private static void sendPausePacket(LyricsManuscript ms) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_PAUSE, JsonMini.stringify(m), null, SEQ_GEN.getAndIncrement()),
                deviceId, true);
    }

    private static void sendResumePacket(LyricsManuscript ms) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_RESUME, JsonMini.stringify(m), null, SEQ_GEN.getAndIncrement()),
                deviceId, true);
    }

    private static void sendStopPacket(LyricsManuscript ms, int pageOff) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        String json = buildStopRequestJson(ms.did, pageOff);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_STOP, json, null, SEQ_GEN.getAndIncrement()),
                deviceId, true);
    }

    private static void stopSessionOnGlasses(String reason) {
        if (sessionState != SESSION_IDLE && activeManuscript != null) {
            int idx = Math.max(0, lastSentLineIdx);
            int pageOff = activeManuscript.lineByteOffsets[Math.max(0, Math.min(idx - 1, activeManuscript.lineByteOffsets.length - 1))];
            sendStopPacket(activeManuscript, pageOff);
            Log.i(TAG, "Stopped lyrics teleprompter session (" + reason + ")");
        }
        activeManuscript = null;
        resetSessionVars();
    }

    private static void resetSessionVars() {
        sessionState = SESSION_IDLE;
        lastSentLineIdx = -1;
        handshakeAttempts = 0;
        conflictRetried = false;
        pendingFileTaskId = "";
    }

    private static int computeCurrentPageOffset(LyricsManuscript ms, long posMs) {
        if (ms == null || ms.lineByteOffsets.length == 0) return 0;
        int idx = findActiveLineIndex(ms.lineTimesMs, posMs);
        return ms.lineByteOffsets[Math.max(0, idx - 1)];
    }

    // ==================== CoreConnect BLE Device & Sender ====================

    private static w resolveCoreConnectW() {
        try {
            o4 b = lastSeenBridge != null ? lastSeenBridge : o4.Q;
            if (b != null) {
                w inst = b.q();
                if (inst != null) return inst;
            }
        } catch (Throwable ignored) {}
        try {
            return w.h;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveConnectedGlassesDeviceId() {
        // 1. Check o4.z (h4 connected glasses record: z.c == connected, z.a == deviceId)
        try {
            o4 b = lastSeenBridge != null ? lastSeenBridge : o4.Q;
            if (b != null && b.z != null) {
                h4 z = b.z;
                if (z.c && z.a != null && !z.a.isEmpty()) {
                    return z.a;
                }
            }
        } catch (Throwable ignored) {}

        // 2. Iterate w.j() device map -> CRITICAL: read field g0.a (deviceId), NEVER g0.a() (displayName)!
        String fallbackAnyId = "";
        try {
            Map devices = w.j();
            if (devices != null && !devices.isEmpty()) {
                for (Object val : devices.values()) {
                    if (val instanceof g0) {
                        g0 dev = (g0) val;
                        String devId = dev.a;
                        if (devId != null && !devId.isEmpty()) {
                            if (dev.b()) {
                                return devId;
                            }
                            if (fallbackAnyId.isEmpty()) {
                                fallbackAnyId = devId;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!fallbackAnyId.isEmpty()) {
            return fallbackAnyId;
        }
        return safe(lastSeenDeviceId);
    }

    private static boolean sendBleTeleprompter(byte[] payload, String deviceId, boolean needAck) {
        if (payload == null || deviceId == null || deviceId.isEmpty()) return false;
        try {
            w wInst = resolveCoreConnectW();
            if (wInst == null) return false;
            String msgId = "lyr_" + MSG_ID_GEN.incrementAndGet();
            v0 msg = new v0(
                    payload,
                    msgId,
                    deviceId,
                    vb.h.TELEPROMPTER,
                    null,
                    b.NORMAL,
                    needAck,
                    false,
                    null,
                    null
            );
            wInst.t(msg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendBleTeleprompter error", t);
            return false;
        }
    }

    // ==================== Teleprompter Wire JSON Builders ====================

    private static void putDisplaySettings(Map<String, Object> m) {
        m.put("countdown", 0);
        m.put("scroll", HUD_SCROLL_MANUAL);
        m.put("speed", HUD_SPEED);
        m.put("gear", HUD_GEAR_LOW);
        m.put("depth", HUD_DEPTH_DEEP);
        m.put("size", HUD_FONT_SIZE);
        m.put("width", HUD_WIDTH);
        m.put("leading", HUD_LEADING);
    }

    public static String buildAppStartSnippetRequestJson(LyricsManuscript ms, int pageOffset, int highLightOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        m.put("total", ms.utf8Bytes.length);
        putDisplaySettings(m);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("checksum", ms.checksum);
        m.put("snippetMode", true);

        Map<String, Object> sInfo = new LinkedHashMap<>();
        sInfo.put("skipData", false);
        sInfo.put("isCurrent", true);
        sInfo.put("index", 0);
        sInfo.put("total_index", 0);
        sInfo.put("inlineOffset", pageOffset);
        sInfo.put("startOffset", 0);
        sInfo.put("total", ms.utf8Bytes.length);
        m.put("snippetInfo", sInfo);
        return JsonMini.stringify(m);
    }

    public static String buildSnippetServeResponseJson(
            LyricsManuscript ms, int pageOffset, int highLightOffset, boolean skipData, boolean isCurrent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 2);
        m.put("did", ms.did);
        m.put("code", 1);
        putDisplaySettings(m);
        m.put("total", ms.utf8Bytes.length);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("checksum", ms.checksum);
        m.put("snippetMode", true);

        Map<String, Object> sInfo = new LinkedHashMap<>();
        sInfo.put("skipData", skipData);
        sInfo.put("isCurrent", isCurrent);
        sInfo.put("index", 0);
        sInfo.put("total_index", 0);
        sInfo.put("inlineOffset", pageOffset);
        sInfo.put("startOffset", 0);
        sInfo.put("total", ms.utf8Bytes.length);
        m.put("snippetInfo", sInfo);
        return JsonMini.stringify(m);
    }

    public static String buildAppCueingStartRequestJson(LyricsManuscript ms, int pageOffset, int highLightOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        m.put("total", ms.utf8Bytes.length);
        putDisplaySettings(m);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("code", 1);
        m.put("checksum", ms.checksum);
        return JsonMini.stringify(m);
    }

    public static String buildProgressUpdateJson(
            String did, int pageOffset, int highLightOffset, boolean snippetMode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", did);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        if (snippetMode) {
            m.put("snippetMode", true);
            Map<String, Object> sInfo = new LinkedHashMap<>();
            sInfo.put("index", 0);
            sInfo.put("inlineOffset", pageOffset);
            m.put("snippetInfo", sInfo);
        }
        return JsonMini.stringify(m);
    }

    public static String buildStopRequestJson(String did, int pageOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", did);
        m.put("offset", pageOffset);
        m.put("code", 1);
        m.put("isCompleted", false);
        return JsonMini.stringify(m);
    }

    public static String buildConflictAckJson(String did) {
        return buildSimpleAckJson(did);
    }

    public static String buildSimpleAckJson(String did) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 2);
        m.put("did", did);
        m.put("code", 1);
        return JsonMini.stringify(m);
    }

    public static String buildCheckStateResponseJson(
            LyricsManuscript ms, int stateVal, int pageOffset, int highLightOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 2);
        m.put("did", ms.did);
        m.put("code", 1);
        m.put("state", stateVal);
        m.put("total", ms.utf8Bytes.length);
        putDisplaySettings(m);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("checksum", ms.checksum);
        return JsonMini.stringify(m);
    }

    // ==================== 6-Field GlassesMsg Protobuf Codec ====================

    /**
     * Encodes GlassesMsg matching libapp.so GlassesMsg wire schema:
     * field 1 (0x08): version = 1 (ALWAYS written!)
     * field 2 (0x10): type
     * field 3 (0x1A): message (UTF-8 JSON)
     * field 4 (0x22): data (optional raw UTF-8 manuscript snippet bytes)
     * field 5 (0x28): sequence
     * field 6 (0x30): mode (0)
     */
    public static byte[] encodeGlassesMsg(int type, String jsonMessage, byte[] data, int sequence) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 + (data != null ? data.length : 0));
        // field 1: version = 1
        out.write(0x08);
        writeVarint(out, 1);

        // field 2: type
        out.write(0x10);
        writeVarint(out, type);

        // field 3: message (JSON string)
        byte[] msgBytes = (jsonMessage == null ? "" : jsonMessage).getBytes(StandardCharsets.UTF_8);
        if (msgBytes.length > 0) {
            out.write(0x1A);
            writeVarint(out, msgBytes.length);
            out.write(msgBytes, 0, msgBytes.length);
        }

        // field 4: data (raw snippet bytes)
        if (data != null && data.length > 0) {
            out.write(0x22);
            writeVarint(out, data.length);
            out.write(data, 0, data.length);
        }

        // field 5: sequence
        if (sequence > 0) {
            out.write(0x28);
            writeVarint(out, sequence);
        }

        return out.toByteArray();
    }

    public static GlassesMsg decodeGlassesMsg(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        GlassesMsg msg = new GlassesMsg();
        int pos = 0;
        int len = raw.length;
        try {
            while (pos < len) {
                long[] tagRes = readVarint(raw, pos, len);
                if (tagRes == null) break;
                int tag = (int) tagRes[0];
                pos = (int) tagRes[1];
                int fieldNum = tag >>> 3;
                int wireType = tag & 0x07;

                if (wireType == 0) {
                    long[] valRes = readVarint(raw, pos, len);
                    if (valRes == null) break;
                    long val = valRes[0];
                    pos = (int) valRes[1];
                    switch (fieldNum) {
                        case 1: msg.version = (int) val; break;
                        case 2: msg.type = (int) val; break;
                        case 5: msg.sequence = (int) val; break;
                        case 6: msg.mode = (int) val; break;
                    }
                } else if (wireType == 2) {
                    long[] lenRes = readVarint(raw, pos, len);
                    if (lenRes == null) break;
                    int subLen = (int) lenRes[0];
                    pos = (int) lenRes[1];
                    if (subLen < 0 || pos + subLen > len) break;
                    if (fieldNum == 3) {
                        msg.jsonMessage = new String(raw, pos, subLen, StandardCharsets.UTF_8);
                    } else if (fieldNum == 4) {
                        byte[] copy = new byte[subLen];
                        System.arraycopy(raw, pos, copy, 0, subLen);
                        msg.data = copy;
                    }
                    pos += subLen;
                } else if (wireType == 5) {
                    pos += 4;
                } else if (wireType == 1) {
                    pos += 8;
                } else {
                    break;
                }
            }
            return msg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        long v = value & 0xFFFFFFFFL;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long[] readVarint(byte[] buf, int pos, int limit) {
        long result = 0L;
        int shift = 0;
        while (pos < limit && shift < 64) {
            int b = buf[pos++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{result, pos};
            }
            shift += 7;
        }
        return null;
    }

    // ==================== LRC Fetching (NetEase + QQ Music + LRU Cache) ====================

    private static LyricsManuscript getOrFetchManuscript(SystemMediaBridge.TrackInfo track) {
        String key = normalizeTrackKey(track.title, track.artist);
        synchronized (CACHE_LOCK) {
            LyricsManuscript cached = MANUSCRIPT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        LyricsManuscript fetched = fetchLyricsManuscriptOnline(
                track.title, track.artist, track.packageName, null, null);
        if (fetched.hasSyncedLyrics) {
            synchronized (CACHE_LOCK) {
                MANUSCRIPT_CACHE.put(key, fetched);
            }
        }
        return fetched;
    }

    /**
     * Fetches LRC from NetEase Cloud Music and/or QQ Music, parses timestamps, pre-wraps lines,
     * and returns a ready-to-stream LyricsManuscript (never null; falls back to static song card).
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            String playerPkg,
            String netEaseBaseOverride,
            String qqBaseOverride
    ) {
        String title = safe(rawTitle);
        String artist = safe(rawArtist);
        boolean preferQq = SystemMediaBridge.PKG_QQMUSIC.equals(playerPkg);

        SystemMediaBridge.SongHint hint = SystemMediaBridge.getLastResolvedSongHint();
        String hintedNeId = "";
        String hintedQqMid = "";
        if (hint != null && titlesRoughlyMatch(title, hint.title)) {
            if (SystemMediaBridge.PKG_NETEASE.equals(hint.platform)) {
                hintedNeId = safe(hint.songId);
            } else if (SystemMediaBridge.PKG_QQMUSIC.equals(hint.platform)) {
                hintedQqMid = safe(hint.songMid);
            }
        }

        if (preferQq) {
            String qqLrc = fetchQqLrc(title, artist, hintedQqMid, qqBaseOverride);
            LyricsManuscript qqMs = parseLrcAndBuildManuscript(title, artist, qqLrc, "QQ音乐");
            if (qqMs.hasSyncedLyrics) return qqMs;

            String neLrc = fetchNetEaseLrc(title, artist, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcAndBuildManuscript(title, artist, neLrc, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;
            return qqMs;
        } else {
            String neLrc = fetchNetEaseLrc(title, artist, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcAndBuildManuscript(title, artist, neLrc, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;

            String qqLrc = fetchQqLrc(title, artist, hintedQqMid, qqBaseOverride);
            LyricsManuscript qqMs = parseLrcAndBuildManuscript(title, artist, qqLrc, "QQ音乐");
            if (qqMs.hasSyncedLyrics) return qqMs;
            return neMs;
        }
    }

    public static String fetchNetEaseLrc(
            String title, String artist, String knownSongId, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://music.163.com";
        try {
            String songId = safe(knownSongId);
            if (songId.isEmpty()) {
                String rawKeyword = artist.isEmpty() ? title : (artist + " " + title);
                SystemMediaBridge.PlaySearchRequest req = new SystemMediaBridge.PlaySearchRequest(
                        SystemMediaBridge.PlaySearchRequest.MODE_SONG_SEARCH,
                        SystemMediaBridge.PKG_NETEASE,
                        rawKeyword,
                        artist,
                        title,
                        false,
                        !artist.isEmpty()
                );
                SystemMediaBridge.SongCandidate cand =
                        SystemMediaBridge.searchNetEaseSong(req, "", baseUrlOverride);
                if (cand == null || !titlesRoughlyMatch(title, cand.title)) {
                    return "";
                }
                songId = cand.id;
            }
            if (songId.isEmpty()) return "";

            String url = base + "/api/song/lyric?id=" + urlEncode(songId) + "&lv=1&kv=1&tv=-1";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://music.163.com/");
            String body = httpGet(url, headers);
            if (body.isEmpty()) return "";

            Map<String, Object> root = JsonMini.parseObject(body);
            if (Boolean.TRUE.equals(root.get("nolyric")) || Boolean.TRUE.equals(root.get("uncollected"))) {
                return "";
            }
            Map<String, Object> lrcObj = JsonMini.getMap(root, "lrc");
            String lyric = safe(JsonMini.getString(lrcObj, "lyric"));
            if (!lyric.isEmpty()) return lyric;

            Map<String, Object> klyricObj = JsonMini.getMap(root, "klyric");
            return safe(JsonMini.getString(klyricObj, "lyric"));
        } catch (Throwable t) {
            Log.w(TAG, "fetchNetEaseLrc error: " + t.getMessage());
            return "";
        }
    }

    public static String fetchQqLrc(
            String title, String artist, String knownSongMid, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://c.y.qq.com";
        try {
            String mid = safe(knownSongMid);
            if (mid.isEmpty()) {
                String rawKeyword = artist.isEmpty() ? title : (artist + " " + title);
                SystemMediaBridge.PlaySearchRequest req = new SystemMediaBridge.PlaySearchRequest(
                        SystemMediaBridge.PlaySearchRequest.MODE_SONG_SEARCH,
                        SystemMediaBridge.PKG_QQMUSIC,
                        rawKeyword,
                        artist,
                        title,
                        false,
                        !artist.isEmpty()
                );
                SystemMediaBridge.SongCandidate cand =
                        SystemMediaBridge.searchQqMusicSong(req, "", baseUrlOverride);
                if (cand == null || !titlesRoughlyMatch(title, cand.title)) {
                    return "";
                }
                mid = cand.id;
            }
            if (mid.isEmpty()) return "";

            String url = base + "/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                    + urlEncode(mid) + "&format=json&nobase64=1";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");
            String body = stripJsonpWrapper(httpGet(url, headers));
            if (body.isEmpty()) return "";

            Map<String, Object> root = JsonMini.parseObject(body);
            String lyric = safe(JsonMini.getString(root, "lyric"));
            if (lyric.isEmpty()) return "";

            // Handle Base64 payload if nobase64=1 was ignored by an upstream mirror
            if (!lyric.contains("[") && !lyric.contains("&#")) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(lyric.replaceAll("\\s+", ""));
                    lyric = new String(decoded, StandardCharsets.UTF_8);
                } catch (Throwable ignored) {}
            }
            return decodeHtmlEntities(lyric);
        } catch (Throwable t) {
            Log.w(TAG, "fetchQqLrc error: " + t.getMessage());
            return "";
        }
    }

    private static String stripJsonpWrapper(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("{")) return s;
        int open = s.indexOf('{');
        int close = s.lastIndexOf('}');
        if (open >= 0 && close > open) {
            return s.substring(open, close + 1);
        }
        return s;
    }

    private static boolean titlesRoughlyMatch(String expectedTitle, String candidateTitle) {
        String a = normalizeCompareText(expectedTitle);
        String b = normalizeCompareText(candidateTitle);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        // Strip bracketed suffixes like "(Live)" or "（电影主题曲）"
        String aClean = normalizeCompareText(stripBracketSuffix(expectedTitle));
        String bClean = normalizeCompareText(stripBracketSuffix(candidateTitle));
        return !aClean.isEmpty() && !bClean.isEmpty()
                && (aClean.equals(bClean) || aClean.contains(bClean) || bClean.contains(aClean));
    }

    private static String stripBracketSuffix(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[（(\\[【].*?[）)\\]】]\\s*", "").trim();
    }

    private static String normalizeCompareText(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、：“”‘’《》【】（）()\\[\\]·\\-]+", "");
    }

    // ==================== LRC Parser, HTML Decoder, Pre-Wrapper &Checksum ====================

    public static LyricsManuscript parseLrcAndBuildManuscript(String title, String artist, String rawLrc) {
        return parseLrcAndBuildManuscript(title, artist, rawLrc, "");
    }

    public static LyricsManuscript parseLrcAndBuildManuscript(
            String title, String artist, String rawLrc, String sourcePlatform) {
        String cleanTitle = safe(title).isEmpty() ? "未知歌曲" : safe(title);
        String cleanArtist = safe(artist);
        String trackKey = normalizeTrackKey(cleanTitle, cleanArtist);
        String did = computeLyricsDid(trackKey);

        String headerRaw = cleanArtist.isEmpty()
                ? ("🎵 " + cleanTitle)
                : ("🎵 " + cleanTitle + " - " + cleanArtist);
        List<String> headerLines = wrapLineToHudWidth(headerRaw, MAX_LINE_HALF_WIDTH);

        List<TimedLine> parsed = parseRawLrcLines(rawLrc);
        List<String> finalLines = new ArrayList<>();
        List<Long> finalTimes = new ArrayList<>();
        boolean hasSynced = !parsed.isEmpty();

        if (!hasSynced) {
            for (String hl : headerLines) {
                finalLines.add(hl);
                finalTimes.add(0L);
            }
            finalLines.add("暂无滚动歌词");
            finalTimes.add(1000L);
        } else {
            // Prepend song title header if first lyric starts >= 800ms (or at 0ms before lyrics)
            long firstTime = parsed.get(0).timeMs;
            if (firstTime >= 600L) {
                for (int i = 0; i < headerLines.size(); i++) {
                    finalLines.add(headerLines.get(i));
                    finalTimes.add(Math.min(firstTime - 100L, i * 200L));
                }
            }

            for (int i = 0; i < parsed.size(); i++) {
                TimedLine cur = parsed.get(i);
                long nextTime = (i + 1 < parsed.size()) ? parsed.get(i + 1).timeMs : (cur.timeMs + 4000L);
                List<String> wrapped = wrapLineToHudWidth(cur.text, MAX_LINE_HALF_WIDTH);
                if (wrapped.size() == 1) {
                    finalLines.add(wrapped.get(0));
                    finalTimes.add(cur.timeMs);
                } else {
                    long span = Math.max(600L, Math.min(6000L, nextTime - cur.timeMs));
                    int k = wrapped.size();
                    for (int j = 0; j < k; j++) {
                        finalLines.add(wrapped.get(j));
                        finalTimes.add(cur.timeMs + (span * j) / k);
                    }
                }
            }
        }

        int n = finalLines.size();
        String[] linesArr = finalLines.toArray(new String[0]);
        long[] timesArr = new long[n];
        int[] offsetsArr = new int[n];

        ByteArrayOutputStream bos = new ByteArrayOutputStream(n * 40);
        for (int i = 0; i < n; i++) {
            timesArr[i] = finalTimes.get(i);
            offsetsArr[i] = bos.size();
            byte[] lb = linesArr[i].getBytes(StandardCharsets.UTF_8);
            bos.write(lb, 0, lb.length);
            if (i < n - 1) {
                bos.write('\n');
            }
        }

        byte[] utf8 = bos.toByteArray();
        String checksum = computeFnv1aHex(utf8);

        return new LyricsManuscript(
                trackKey,
                did,
                cleanTitle,
                cleanArtist,
                linesArr,
                timesArr,
                offsetsArr,
                utf8,
                checksum,
                hasSynced,
                sourcePlatform
        );
    }

    private static List<TimedLine> parseRawLrcLines(String rawLrc) {
        List<TimedLine> out = new ArrayList<>();
        if (rawLrc == null || rawLrc.trim().isEmpty()) return out;

        String decoded = decodeHtmlEntities(rawLrc)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        long globalOffsetMs = 0L;
        int orderSeq = 0;
        String[] rawLines = decoded.split("\n");

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher mOff = PAT_LRC_OFFSET.matcher(line);
            if (mOff.find()) {
                try {
                    globalOffsetMs = Long.parseLong(mOff.group(1));
                } catch (Throwable ignored) {}
                continue;
            }

            if (PAT_LRC_META_LINE.matcher(line).matches()) {
                continue;
            }

            Matcher mTime = PAT_LRC_TIME.matcher(line);
            List<Long> lineTimestamps = new ArrayList<>();
            int lastTagEnd = 0;
            while (mTime.find()) {
                try {
                    long mins = Long.parseLong(mTime.group(1));
                    long secs = Long.parseLong(mTime.group(2));
                    String frac = mTime.group(3);
                    long ms = 0L;
                    if (frac != null && !frac.isEmpty()) {
                        if (frac.length() == 1) {
                            ms = Long.parseLong(frac) * 100L;
                        } else if (frac.length() == 2) {
                            ms = Long.parseLong(frac) * 10L;
                        } else {
                            ms = Long.parseLong(frac.substring(0, 3));
                        }
                    }
                    long ts = Math.max(0L, mins * 60_000L + secs * 1000L + ms - globalOffsetMs);
                    lineTimestamps.add(ts);
                    lastTagEnd = mTime.end();
                } catch (Throwable ignored) {}
            }

            if (lineTimestamps.isEmpty()) continue;

            // Strip any remaining inline karaoke tags like <00:12.40> or [00:12.40]
            String text = line.substring(lastTagEnd)
                    .replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "")
                    .replaceAll("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (text.isEmpty() || "//".equals(text)) {
                continue;
            }

            for (Long ts : lineTimestamps) {
                out.add(new TimedLine(ts, text, orderSeq++));
            }
        }

        Collections.sort(out, new Comparator<TimedLine>() {
            @Override
            public int compare(TimedLine a, TimedLine b) {
                int c = Long.compare(a.timeMs, b.timeMs);
                return c != 0 ? c : Integer.compare(a.order, b.order);
            }
        });

        // Deduplicate exact adjacent (timeMs, text) pairs
        List<TimedLine> deduped = new ArrayList<>(out.size());
        for (TimedLine tl : out) {
            if (!deduped.isEmpty()) {
                TimedLine prev = deduped.get(deduped.size() - 1);
                if (prev.timeMs == tl.timeMs && prev.text.equals(tl.text)) {
                    continue;
                }
            }
            deduped.add(tl);
        }
        return deduped;
    }

    /**
     * Wraps a single lyric line into sub-lines of at most {@code maxHalfWidth} half-width units
     * (ASCII = 1, CJK / Emoji = 2) so each '\n' line fits on a single visual HUD row.
     */
    public static List<String> wrapLineToHudWidth(String text, int maxHalfWidth) {
        List<String> res = new ArrayList<>();
        if (text == null) {
            res.add("");
            return res;
        }
        String s = text.trim();
        if (s.isEmpty() || measureHalfWidth(s) <= maxHalfWidth) {
            res.add(s);
            return res;
        }

        int i = 0;
        int len = s.length();
        while (i < len) {
            while (i < len && s.charAt(i) == ' ') i++;
            if (i >= len) break;

            int width = 0;
            int j = i;
            int lastBreakPos = -1;

            while (j < len) {
                int cp = s.codePointAt(j);
                int charCount = Character.charCount(cp);
                int w = (cp <= 0x7E) ? 1 : 2;
                if (width + w > maxHalfWidth && j > i) {
                    break;
                }
                width += w;
                j += charCount;
                if (isNaturalBreakChar(cp) && width >= maxHalfWidth / 2) {
                    lastBreakPos = j;
                }
            }

            int cut = (j < len && lastBreakPos > i) ? lastBreakPos : j;
            String chunk = s.substring(i, cut).trim();
            if (!chunk.isEmpty()) {
                res.add(chunk);
            }
            i = cut;
        }

        if (res.isEmpty()) {
            res.add(s);
        }
        return res;
    }

    public static int measureHalfWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += (cp <= 0x7E) ? 1 : 2;
            i += Character.charCount(cp);
        }
        return w;
    }

    private static boolean isNaturalBreakChar(int cp) {
        return cp == ' ' || cp == ',' || cp == '.' || cp == ';' || cp == '!' || cp == '?'
                || cp == '/' || cp == '，' || cp == '。' || cp == '、' || cp == '；'
                || cp == '！' || cp == '？' || cp == '—' || cp == '～';
    }

    /**
     * Binary search returning the greatest line index {@code i} with {@code lineTimesMs[i] <= posMs}
     * (clamped to {@code 0} when {@code posMs < lineTimesMs[0]}).
     */
    public static int findActiveLineIndex(long[] lineTimesMs, long posMs) {
        if (lineTimesMs == null || lineTimesMs.length == 0) return 0;
        int lo = 0;
        int hi = lineTimesMs.length - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTimesMs[mid] <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /**
     * 32-bit FNV-1a checksum matching libapp.so TeleprompterChecksumCalculator (0x811c9dc5 / 0x01000193).
     */
    public static String computeFnv1aHex(byte[] data) {
        int hash = 0x811c9dc5;
        if (data != null) {
            for (byte b : data) {
                hash ^= (b & 0xFF);
                hash *= 0x01000193;
            }
        }
        return String.format(Locale.US, "%08x", hash);
    }

    public static String computeLyricsDid(String trackKey) {
        byte[] bytes = safe(trackKey).getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        int positive8Digits = (hash & 0x7FFFFFFF) % 100_000_000;
        return LYRICS_DID_PREFIX + String.format(Locale.US, "%08d", positive8Digits);
    }

    public static String decodeHtmlEntities(String input) {
        if (input == null || input.isEmpty() || !input.contains("&")) {
            return input == null ? "" : input;
        }
        String s = input
                .replace("&nbsp;", " ")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");

        Matcher mDec = PAT_NUMERIC_DEC_ENTITY.matcher(s);
        if (mDec.find()) {
            StringBuffer sb = new StringBuffer(s.length());
            mDec.reset();
            while (mDec.find()) {
                try {
                    int cp = Integer.parseInt(mDec.group(1));
                    String rep = new String(Character.toChars(cp));
                    mDec.appendReplacement(sb, Matcher.quoteReplacement(rep));
                } catch (Throwable ignored) {
                    mDec.appendReplacement(sb, Matcher.quoteReplacement(mDec.group(0)));
                }
            }
            mDec.appendTail(sb);
            s = sb.toString();
        }

        Matcher mHex = PAT_NUMERIC_HEX_ENTITY.matcher(s);
        if (mHex.find()) {
            StringBuffer sb = new StringBuffer(s.length());
            mHex.reset();
            while (mHex.find()) {
                try {
                    int cp = Integer.parseInt(mHex.group(1), 16);
                    String rep = new String(Character.toChars(cp));
                    mHex.appendReplacement(sb, Matcher.quoteReplacement(rep));
                } catch (Throwable ignored) {
                    mHex.appendReplacement(sb, Matcher.quoteReplacement(mHex.group(0)));
                }
            }
            mHex.appendTail(sb);
            s = sb.toString();
        }

        return s.replace("&amp;", "&");
    }

    // ==================== HTTP & String Helpers ====================

    private static String httpGet(String urlStr, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "";
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String normalizeTrackKey(String title, String artist) {
        return safe(title).trim().toLowerCase(Locale.ROOT)
                + "|" + safe(artist).trim().toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingSlash(String s) {
        String out = safe(s);
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(safe(s), "UTF-8").replace("+", "%20");
        } catch (Throwable ignored) {
            return safe(s);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
