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
import kotlin.jvm.functions.Function2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // HUD layout constants matching official RayNeo iO Teleprompter wire protocol
    // CRITICAL: countdown MUST be >= 3 (official default = 3); countdown=0 causes SmallCore RTOS timer assert freeze!
    public static final int HUD_COUNTDOWN = 3;
    public static final int HUD_WIDTH = 420;
    public static final int HUD_FONT_SIZE = 20;
    public static final int HUD_LEADING = 4;
    public static final int HUD_SCROLL_MANUAL = 3;
    public static final int HUD_SPEED = 120;
    public static final int HUD_GEAR_LOW = 1;
    public static final int HUD_DEPTH_DEEP = 3;

    // 34 half-width units = 17 full-width CJK chars -> guaranteed single visual line on 420px @ 20sp HUD
    public static final int MAX_LINE_HALF_WIDTH = 34;
    private static final int HTTP_TIMEOUT_MS = 4200;
    private static final long TICK_ACTIVE_MS = 250L;
    private static final long TICK_IDLE_MS = 1800L;
    private static final long LYRIC_LEAD_COMPENSATION_MS = 120L;
    private static final long HANDSHAKE_TIMEOUT_MS = 9000L;
    private static final long CUEING_SETTLE_DELAY_MS = 550L;
    private static final long TRACK_SWITCH_STOP_SETTLE_MS = 650L;
    private static final long POST_COUNTDOWN_GUARD_MS = (HUD_COUNTDOWN * 1000L) + 350L;
    private static final long MIN_PROGRESS_INTERVAL_MS = 320L;
    private static final long BACKOFF_RETRY_MS = 10000L;
    private static final long VOICE_BUSY_MAX_MS = 22000L;

    // Canonical UUIDv7-formatted slot DID so lyrics upsert cleanly in place in glasses Teleprompter list
    public static final String LYRICS_SLOT_DID = "01a1d990-7261-796e-856f-6c7972696373";
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
    private static boolean sessionSnippetMode = false;
    private static int lastSentLineIdx = -1;
    private static long stateEnteredUptimeMs = 0L;
    private static long cueingReadyAtUptimeMs = 0L;
    private static long lastProgressSentUptimeMs = 0L;
    private static int handshakeAttempts = 0;
    private static int attemptSeq = 0;
    private static boolean fileShareDoneForAttempt = false;
    private static boolean readyCode7ReceivedForAttempt = false;
    private static boolean cueingStartScheduled = false;
    private static boolean cueingStartRetriedOnce = false;
    private static boolean conflictRetried = false;
    private static long backoffUntilUptimeMs = 0L;
    private static volatile String pendingFileTaskId = "";
    private static String stoppedByGlassesTrackKey = "";
    private static boolean foreignTeleprompterActive = false;
    private static long forcePushUntilUptimeMs = 0L;
    private static String lastLoggedDevDiag = "";
    private static volatile boolean voiceAssistantBusy = false;
    private static volatile long voiceBusySinceUptimeMs = 0L;
    private static volatile String lastStatusSummary = "未启动";

    private static final Runnable TICK_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            onWorkerTick();
        }
    };

    private static final Runnable NOTIFY_MEDIA_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            backoffUntilUptimeMs = 0L;
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
                forcePushUntilUptimeMs = SystemClock.uptimeMillis() + 15000L;
                if (sessionState != SESSION_IDLE && activeManuscript != null) {
                    sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                    resetSessionVars();
                    scheduleTick(280L);
                } else {
                    onWorkerTick();
                }
                if (onUiDone != null) {
                    onUiDone.run();
                }
            }
        });
    }

    public static void notifyMediaChanged() {
        Handler h = workerHandler;
        if (h == null) return;
        h.removeCallbacks(NOTIFY_MEDIA_RUNNABLE);
        h.postDelayed(NOTIFY_MEDIA_RUNNABLE, 350L);
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

    /**
     * Inspects an incoming RayneoNet event, dispatches our lyrics state machine on workerHandler,
     * and returns {@code true} ONLY when the event belongs exclusively to {@link #LYRICS_SLOT_DID}
     * so official Flutter never rejects our private lyrics DID with {@code code=5} (Manuscript missing).
     */
    public static boolean onRayneoNetEvent(final Object bridgeObj, final String eventName, final Map data) {
        try {
            if (bridgeObj instanceof o4) {
                lastSeenBridge = (o4) bridgeObj;
            }
            if (eventName == null) return false;

            if ("messageReceived".equals(eventName)) {
                Map msgMap = unwrapMessageMap(data);
                if (msgMap == null) return false;
                Object devObj = msgMap.get("deviceId");
                if (devObj instanceof String && !((String) devObj).isEmpty()) {
                    lastSeenDeviceId = (String) devObj;
                }
                Object bizObj = msgMap.get("businessId");
                if (!"TELEPROMPTER".equals(bizObj)) {
                    return false;
                }
                Object payloadObj = msgMap.get("payload");
                if (!(payloadObj instanceof byte[])) {
                    return false;
                }
                final byte[] payload = (byte[]) payloadObj;
                final boolean interceptForLyrics = shouldInterceptTeleprompterPayload(payload);
                Handler h = workerHandler;
                if (h != null) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            handleIncomingTeleprompterPayload(payload);
                        }
                    });
                }
                return interceptForLyrics;
            } else if ("messageSendError".equals(eventName)) {
                Log.w(TAG, "RayneoNet messageSendError: " + data);
                return false;
            } else if ("fileShareSuccess".equals(eventName) || "fileShareFailed".equals(eventName)) {
                Log.i(TAG, "RayneoNet " + eventName + ": " + data);
                if (data == null) return false;
                Object taskObj = data.get("taskId");
                final String taskId = taskObj instanceof String ? (String) taskObj : "";
                Object fileObj = data.get("fileName");
                final String fileName = fileObj instanceof String ? (String) fileObj : "";
                final boolean ok = "fileShareSuccess".equals(eventName);
                final boolean isOurs = isLyricsDid(fileName)
                        || (!taskId.isEmpty() && taskId.equals(pendingFileTaskId));
                Handler h = workerHandler;
                if (h != null && !taskId.isEmpty()) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            handleFileShareEvent(taskId, ok);
                        }
                    });
                }
                return isOurs;
            } else if ("brStateChanged".equals(eventName) || "bondStateChanged".equals(eventName)) {
                Log.i(TAG, "RayneoNet " + eventName + ": " + data);
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
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRayneoNetEvent error", t);
        }
        return false;
    }

    public static boolean shouldInterceptTeleprompterPayload(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length == 0) return false;
        GlassesMsg msg = decodeGlassesMsg(rawPayload);
        if (msg == null || msg.jsonMessage == null || msg.jsonMessage.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> jb = JsonMini.parseObject(msg.jsonMessage);
            String did = safe(JsonMini.getString(jb, "did"));
            if (did.isEmpty() && jb.get("did") instanceof Number) {
                did = String.valueOf(((Number) jb.get("did")).longValue());
            }
            return isLyricsDid(did);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onRayneoNetTrace(
            final Object bridgeObj,
            final String direction,
            final String eventName,
            final String summary,
            final Map meta,
            final byte[] rawPayload) {
        try {
            if (bridgeObj instanceof o4) {
                lastSeenBridge = (o4) bridgeObj;
            }
            String biz = "";
            String msgId = "";
            String devId = "";
            if (meta != null) {
                Object dObj = meta.get("deviceId");
                if (dObj instanceof String && !((String) dObj).isEmpty()) {
                    devId = (String) dObj;
                    if ("inbound".equals(direction)) {
                        lastSeenDeviceId = devId;
                    }
                }
                Object bObj = meta.get("businessId");
                if (bObj != null) biz = String.valueOf(bObj);
                Object mObj = meta.get("msgId");
                if (mObj != null) msgId = String.valueOf(mObj);
            }
            boolean isPrompter = "TELEPROMPTER".equalsIgnoreCase(biz) || "20".equals(biz) || "0x14".equalsIgnoreCase(biz);
            boolean isOurs = msgId.startsWith("lyr_");
            boolean isErr = eventName != null && (eventName.contains("error") || eventName.contains("fail"));
            if (isPrompter || isOurs || isErr) {
                GlassesMsg gm = rawPayload != null && rawPayload.length > 0 ? decodeGlassesMsg(rawPayload) : null;
                if (gm != null && gm.type == MSG_AUDIO_DATA && !isErr) {
                    return;
                }
                Log.i(TAG, "[BLE-TRACE] dir=" + direction + " ev=" + eventName
                        + " biz=" + biz + " msgId=" + msgId + " dev=" + devId
                        + " frame=" + formatPayloadDiag(rawPayload));
            }
        } catch (Throwable ignored) {}
    }

    private static String formatPayloadDiag(byte[] raw) {
        if (raw == null) return "null";
        if (raw.length == 0) return "empty";
        GlassesMsg gm = decodeGlassesMsg(raw);
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(raw.length);
        if (gm != null && (gm.type > 0 || (gm.jsonMessage != null && !gm.jsonMessage.isEmpty()))) {
            sb.append(" GlassesMsg{v=").append(gm.version)
              .append(",type=0x").append(Integer.toHexString(gm.type))
              .append(",seq=").append(gm.sequence)
              .append(",mode=").append(gm.mode)
              .append(",dataLen=").append(gm.data != null ? gm.data.length : 0)
              .append(",json=").append(gm.jsonMessage)
              .append("}");
        } else {
            int n = Math.min(raw.length, 48);
            sb.append(" hex=");
            for (int i = 0; i < n; i++) {
                sb.append(String.format(Locale.US, "%02x", raw[i] & 0xFF));
            }
        }
        return sb.toString();
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

            boolean forcePush = SystemClock.uptimeMillis() < forcePushUntilUptimeMs;

            // Track changed?
            if (activeManuscript == null || !trackKey.equals(activeManuscript.trackKey)) {
                if (!isPlaying && !forcePush && sessionState == SESSION_IDLE) {
                    // Music is paused and no session open yet -> wait until user hits play
                    lastStatusSummary = "音乐已暂停：" + track.formatSongDisplay();
                    scheduleTick(TICK_IDLE_MS);
                    return;
                }
                if (sessionState != SESSION_IDLE && activeManuscript != null) {
                    // Cleanly stop previous song's session first and allow 650ms settle before sending new 0x02!
                    sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                    activeManuscript = null;
                    resetSessionVars();
                    backoffUntilUptimeMs = SystemClock.uptimeMillis() + TRACK_SWITCH_STOP_SETTLE_MS;
                    scheduleTick(TRACK_SWITCH_STOP_SETTLE_MS + 40L);
                    return;
                }
                LyricsManuscript ms = getOrFetchManuscript(track);
                activeManuscript = ms;
                resetSessionVars();
                backoffUntilUptimeMs = 0L;
            }

            long now = SystemClock.uptimeMillis();
            if (now < backoffUntilUptimeMs) {
                scheduleTick(Math.min(1000L, Math.max(80L, backoffUntilUptimeMs - now)));
                return;
            }

            // Handle music pause/stop transitions (allow manual syncNow forcePush to finish handshake even when paused)
            if (!isPlaying && !forcePush) {
                if (sessionState == SESSION_ACTIVE) {
                    sendPausePacket(activeManuscript);
                    sessionState = SESSION_PAUSED;
                    stateEnteredUptimeMs = now;
                    lastStatusSummary = "已暂停：" + track.formatSongDisplay();
                }
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            // Music IS playing (or manual syncNow forcePush active)!
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
                // Gentle single retry of 0x03 if Preview took slightly longer to initialize on glasses
                if (sessionState == SESSION_STARTING_CUEING
                        && !cueingStartScheduled
                        && !cueingStartRetriedOnce
                        && now - stateEnteredUptimeMs > 2600L
                        && activeManuscript != null) {
                    cueingStartRetriedOnce = true;
                    Log.i(TAG, "0x03 unanswered after 2.6s -> retrying 0x03 without re-uploading 0x02");
                    sendCueingStartRequest(activeManuscript, deviceId);
                    scheduleTick(TICK_ACTIVE_MS);
                    return;
                }
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
                if (now < cueingReadyAtUptimeMs) {
                    scheduleTick(Math.min(TICK_ACTIVE_MS, Math.max(60L, cueingReadyAtUptimeMs - now)));
                    return;
                }
                int lineIdx = findActiveLineIndex(activeManuscript.lineTimesMs, interpPosMs);
                if (lineIdx != lastSentLineIdx && (now - lastProgressSentUptimeMs >= MIN_PROGRESS_INTERVAL_MS)) {
                    sendProgressUpdatePacket(activeManuscript, lineIdx);
                    lastSentLineIdx = lineIdx;
                    lastProgressSentUptimeMs = now;
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

        attemptSeq++;
        handshakeAttempts++;
        fileShareDoneForAttempt = false;
        readyCode7ReceivedForAttempt = false;
        cueingStartScheduled = false;
        cueingStartRetriedOnce = false;
        pendingFileTaskId = "";
        stateEnteredUptimeMs = SystemClock.uptimeMillis();
        lastSentLineIdx = lineIdx;

        // Send 0x02 AppStartRequest FIRST with pageOffset=0, highLightOffset=0, countdown=3
        // (matching official RayNeo iO wire capture: 0x02 -> code=1 -> fileShare -> code=7 -> 0x01(type=3) -> 0x03)
        String json = buildAppStartRequestJson(ms, 0, 0);
        byte[] pkt = encodeGlassesMsg(MSG_START, json, null, 0);
        boolean queued = sendBleTeleprompter(pkt, deviceId, true);
        if (!queued) {
            Log.w(TAG, "Failed to queue 0x02 AppStartRequest (wInst=null or exception, dev=" + deviceId + ")");
            sessionState = SESSION_IDLE;
            backoffUntilUptimeMs = SystemClock.uptimeMillis() + 3000L;
            lastStatusSummary = "蓝牙通道尚未就绪，稍后重试";
            return;
        }
        sessionSnippetMode = false;
        sessionState = SESSION_STARTING;
        lastStatusSummary = "正在投送歌词：" + ms.title;
        Log.i(TAG, "Sent 0x02 AppStartRequest (bytes=" + ms.utf8Bytes.length
                + ", lines=" + ms.lines.length + ", checksum=" + ms.checksum
                + ", did=" + ms.did + ", dev=" + deviceId + ")");
    }

    private static void sendListUpsertPacket(LyricsManuscript ms, String deviceId, int offset) {
        if (ms == null || deviceId == null || deviceId.isEmpty()) return;
        String listJson = buildListUpsertJson(ms, offset);
        byte[] listPkt = encodeGlassesMsg(MSG_LIST_UPDATE, listJson, null, 0);
        sendBleTeleprompter(listPkt, deviceId, true);
    }

    private static void initiateFileShareTransfer(LyricsManuscript ms, String deviceId) {
        Application app = appContext;
        if (app == null || ms == null) return;
        try {
            File dir = new File(app.getCacheDir(), "teleprompter");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // CRITICAL: Official RayNeo iO firmware expects fileName == exact ms.did (NO ".txt" extension!)
            File f = new File(dir, ms.did);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(ms.utf8Bytes);
            }
            w wInst = resolveCoreConnectW();
            if (wInst != null) {
                fileShareDoneForAttempt = false;
                readyCode7ReceivedForAttempt = false;
                cueingStartScheduled = false;
                String taskId = wInst.v(f, deviceId, wb.q.SmallCore, "");
                if (taskId != null && !taskId.isEmpty()) {
                    pendingFileTaskId = taskId;
                    sessionSnippetMode = false;
                    sessionState = SESSION_WAITING_FILE_SHARE;
                    stateEnteredUptimeMs = SystemClock.uptimeMillis();
                    Log.i(TAG, "Started fileShare taskId=" + taskId + " file=" + f.getName()
                            + " bytes=" + ms.utf8Bytes.length + " dev=" + deviceId);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "initiateFileShareTransfer failed", t);
        }
        backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
        sessionState = SESSION_IDLE;
    }

    private static void handleFileShareEvent(final String taskId, boolean success) {
        if (!taskId.equals(pendingFileTaskId)) {
            return;
        }
        if (!success || activeManuscript == null) {
            pendingFileTaskId = "";
            sessionState = SESSION_IDLE;
            backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
            return;
        }
        fileShareDoneForAttempt = true;
        final int expectedAttempt = attemptSeq;
        if (readyCode7ReceivedForAttempt) {
            scheduleCueingStartAfterPreviewSettle(expectedAttempt, "fileShareSuccess+code7");
            return;
        }
        // Fallback if inbound 0x02 (code=7) packet is omitted on another firmware revision
        Handler h = workerHandler;
        if (h != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (attemptSeq == expectedAttempt
                            && sessionState == SESSION_WAITING_FILE_SHARE
                            && !cueingStartScheduled
                            && activeManuscript != null) {
                        readyCode7ReceivedForAttempt = true;
                        scheduleCueingStartAfterPreviewSettle(expectedAttempt, "fileShareSuccess_1400ms_fallback");
                    }
                }
            }, 1400L);
        }
    }

    private static void scheduleCueingStartAfterPreviewSettle(final int expectedAttempt, final String trigger) {
        if (cueingStartScheduled || activeManuscript == null || attemptSeq != expectedAttempt) {
            return;
        }
        cueingStartScheduled = true;
        pendingFileTaskId = "";
        sessionState = SESSION_STARTING_CUEING;
        stateEnteredUptimeMs = SystemClock.uptimeMillis();

        String devId = resolveConnectedGlassesDeviceId();
        if (!devId.isEmpty()) {
            // Match official RayNeo iO order: upsert 0x01(type=3) right after 0x02(code=7), then settle 550ms -> 0x03
            sendListUpsertPacket(activeManuscript, devId, 0);
        }

        Handler h = workerHandler;
        if (h != null) {
            Log.i(TAG, "Scheduling 0x03 AppCueingStartRequest in " + CUEING_SETTLE_DELAY_MS
                    + "ms (trigger=" + trigger + ")");
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (attemptSeq != expectedAttempt
                            || sessionState != SESSION_STARTING_CUEING
                            || activeManuscript == null) {
                        return;
                    }
                    cueingStartScheduled = false; // 0x03 is now on the wire
                    String curDev = resolveConnectedGlassesDeviceId();
                    if (!curDev.isEmpty()) {
                        sendCueingStartRequest(activeManuscript, curDev);
                    }
                }
            }, CUEING_SETTLE_DELAY_MS);
        }
    }

    private static void sendCueingStartRequest(LyricsManuscript ms, String deviceId) {
        if (ms == null || deviceId == null || deviceId.isEmpty()) return;
        pendingFileTaskId = "";

        // Compute live playback position + 3s countdown lookahead so when the 3s HUD countdown finishes,
        // the glasses land directly on the exact lyric line being sung at that moment!
        int lineIdx = Math.max(0, Math.min(lastSentLineIdx, ms.lineByteOffsets.length - 1));
        Application app = appContext;
        if (app != null) {
            SystemMediaBridge.TrackInfo liveTrack = SystemMediaBridge.getCurrentTrack(app);
            if (liveTrack != null && liveTrack.hasSong()
                    && normalizeTrackKey(liveTrack.title, liveTrack.artist).equals(ms.trackKey)) {
                long targetMs = liveTrack.getInterpolatedPositionMs()
                        + (liveTrack.isPlaying() ? (HUD_COUNTDOWN * 1000L + LYRIC_LEAD_COMPENSATION_MS) : 0L);
                lineIdx = findActiveLineIndex(ms.lineTimesMs, targetMs);
            }
        }
        int hiOff = ms.lineByteOffsets[lineIdx];
        int pageOff = hiOff;
        lastSentLineIdx = lineIdx;

        sessionState = SESSION_STARTING_CUEING;
        stateEnteredUptimeMs = SystemClock.uptimeMillis();
        String json = buildAppCueingStartRequestJson(ms, pageOff, hiOff);
        byte[] pkt = encodeGlassesMsg(MSG_CUEING_START, json, null, 0);
        sendBleTeleprompter(pkt, deviceId, true);
        Log.i(TAG, "Sent 0x03 AppCueingStartRequest (countdown=" + HUD_COUNTDOWN
                + ", offset=" + hiOff + ", lineIdx=" + lineIdx + ", did=" + ms.did + ")");
    }

    private static void handleIncomingTeleprompterPayload(byte[] rawPayload) {
        Log.i(TAG, "[RX-TELEPROMPTER] " + formatPayloadDiag(rawPayload));
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

        // Detect foreign (official Flutter) teleprompter session so we never collide with user's own notes
        if (msg.type == MSG_STOP && !isLyricsDid(did)) {
            foreignTeleprompterActive = false;
            if (!did.isEmpty()) return;
        } else if (!did.isEmpty() && !isLyricsDid(did)) {
            if (msg.type == MSG_START || msg.type == MSG_CUEING_START || msg.type == MSG_PROGRESS_UPDATE) {
                foreignTeleprompterActive = true;
                resetSessionVars();
            }
            return;
        }

        final String deviceId = resolveConnectedGlassesDeviceId();

        switch (msg.type) {
            case MSG_LIST_UPDATE: {
                // Glasses reconnected and requested full list sync (action=1, type=1).
                // Wait 900ms so official Flutter's type=1 list response finishes first, then upsert our lyrics slot!
                if (action == 1 && !deviceId.isEmpty()) {
                    Application app = appContext;
                    if (app != null && CustomAiConfig.load(app).syncLyricsToTeleprompter) {
                        Handler h = workerHandler;
                        if (h != null) {
                            h.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    String curDev = resolveConnectedGlassesDeviceId();
                                    if (curDev.isEmpty()) return;
                                    LyricsManuscript snapMs = activeManuscript;
                                    if (snapMs == null && appContext != null) {
                                        SystemMediaBridge.TrackInfo t = SystemMediaBridge.getCurrentTrack(appContext);
                                        if (t != null && t.hasSong()) {
                                            snapMs = getOrFetchManuscript(t);
                                        }
                                    }
                                    if (snapMs != null) {
                                        sendListUpsertPacket(snapMs, curDev, 0);
                                    }
                                }
                            }, 900L);
                        }
                    }
                }
                break;
            }

            case MSG_START: {
                if (action == 2) {
                    // GlassesStartResponse to our 0x02 AppStartRequest
                    if (activeManuscript == null || (!did.isEmpty() && !did.equals(activeManuscript.did))) {
                        return;
                    }
                    if (code == 1) {
                        // Ground-truth on RayNeo iO: code=1 on 0x02 means "0x02 accepted, now transfer file via fileShare!"
                        Log.i(TAG, "Glasses accepted 0x02 (code=1) -> initiating fileShare for did=" + did);
                        if (!deviceId.isEmpty()) {
                            initiateFileShareTransfer(activeManuscript, deviceId);
                        }
                    } else if (code == 7) {
                        // Ground-truth on RayNeo iO: code=7 means "file received & FNV-1a verified on glasses!"
                        readyCode7ReceivedForAttempt = true;
                        final int expectedAttempt = attemptSeq;
                        if (fileShareDoneForAttempt) {
                            scheduleCueingStartAfterPreviewSettle(expectedAttempt, "code7+fileShareDone");
                        } else {
                            // Wait up to 650ms for SPP fileShareSuccess teardown callback so we never send 0x03 mid-SPP close
                            Log.i(TAG, "Received 0x02(code=7) before fileShareSuccess -> waiting up to 650ms for SPP settle");
                            Handler h = workerHandler;
                            if (h != null) {
                                h.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (attemptSeq == expectedAttempt && !cueingStartScheduled
                                                && sessionState == SESSION_WAITING_FILE_SHARE) {
                                            fileShareDoneForAttempt = true;
                                            scheduleCueingStartAfterPreviewSettle(expectedAttempt, "code7_650ms_settle");
                                        }
                                    }
                                }, 650L);
                            }
                        }
                    } else if (code == 23) {
                        // Cached on glasses without fileShare needed
                        fileShareDoneForAttempt = true;
                        readyCode7ReceivedForAttempt = true;
                        scheduleCueingStartAfterPreviewSettle(attemptSeq, "code23_cached");
                    } else if (code == 2) {
                        // Session rejected / aborted on glasses -> cancel pending fileShare fallback cleanly
                        Log.w(TAG, "Glasses returned 0x02 code=2 (aborted/busy) -> resetting & backing off 4s");
                        resetSessionVars();
                        backoffUntilUptimeMs = SystemClock.uptimeMillis() + 4000L;
                    } else if (isConflictCode(code) && !conflictRetried) {
                        conflictRetried = true;
                        Log.i(TAG, "Teleprompter conflict code=" + code + " -> sending conflict ack & retrying");
                        String ackJson = buildConflictAckJson(activeManuscript.did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_START, ackJson, null, 0),
                                deviceId, true);
                        resetSessionVars();
                        scheduleTick(450L);
                    } else if (code == 18 || code == 24) {
                        // Folded temple legs / busy state
                        resetSessionVars();
                        backoffUntilUptimeMs = SystemClock.uptimeMillis() + BACKOFF_RETRY_MS;
                        lastStatusSummary = "眼镜镜腿已折叠或界面占用";
                    } else {
                        Log.w(TAG, "Unhandled 0x02 response code=" + code);
                    }
                } else if (action == 1 && isLyricsDid(did) && !deviceId.isEmpty()) {
                    if (activeManuscript == null && appContext != null) {
                        SystemMediaBridge.TrackInfo t = SystemMediaBridge.getCurrentTrack(appContext);
                        if (t != null && t.hasSong()) {
                            activeManuscript = getOrFetchManuscript(t);
                        } else {
                            activeManuscript = parseLrcAndBuildManuscript("当前暂无播放歌曲", "", "", "");
                        }
                    }
                    if (activeManuscript == null) break;

                    if (jb.containsKey("snippetInfo")) {
                        Map<String, Object> sInfo = JsonMini.getMap(jb, "snippetInfo");
                        boolean skipData = sInfo != null && Boolean.TRUE.equals(sInfo.get("skipData"));
                        boolean isCurrent = sInfo == null || !Boolean.FALSE.equals(sInfo.get("isCurrent"));
                        int idx = Math.max(0, Math.min(lastSentLineIdx, activeManuscript.lineByteOffsets.length - 1));
                        int hiOff = activeManuscript.lineByteOffsets[idx];
                        String respJson = buildSnippetServeResponseJson(
                                activeManuscript, hiOff, hiOff, skipData, isCurrent);
                        byte[] dataBytes = skipData ? null : activeManuscript.utf8Bytes;
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_START, respJson, dataBytes, msg.sequence),
                                deviceId, true);
                        sessionSnippetMode = true;
                        sessionState = SESSION_ACTIVE;
                        stateEnteredUptimeMs = SystemClock.uptimeMillis();
                        cueingReadyAtUptimeMs = stateEnteredUptimeMs + 400L;
                    } else {
                        // User opened our lyrics item directly from the glasses' Teleprompter menu!
                        attemptSeq++;
                        fileShareDoneForAttempt = false;
                        readyCode7ReceivedForAttempt = false;
                        cueingStartScheduled = false;
                        cueingStartRetriedOnce = false;
                        stoppedByGlassesTrackKey = "";
                        foreignTeleprompterActive = false;
                        int hiOff = 0;
                        String startAcceptJson = buildGlassesStartAcceptJson(activeManuscript, hiOff, hiOff);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_START, startAcceptJson, null, 0),
                                deviceId, true);
                        initiateFileShareTransfer(activeManuscript, deviceId);
                    }
                }
                break;
            }

            case MSG_CUEING_START: {
                if (action == 2 && activeManuscript != null && (did.isEmpty() || did.equals(activeManuscript.did))) {
                    if (code == 1 || code == 23) {
                        long now = SystemClock.uptimeMillis();
                        sessionState = SESSION_ACTIVE;
                        stateEnteredUptimeMs = now;
                        cueingReadyAtUptimeMs = now + POST_COUNTDOWN_GUARD_MS;
                        handshakeAttempts = 0;
                        cueingStartScheduled = false;
                        Log.i(TAG, "Teleprompter 0x03 cueing ACTIVE (code=" + code
                                + ", countdown=" + HUD_COUNTDOWN + "s, did=" + activeManuscript.did + ")");
                        scheduleTick(POST_COUNTDOWN_GUARD_MS);
                    } else if (isConflictCode(code) && !conflictRetried) {
                        conflictRetried = true;
                        String ackJson = buildConflictAckJson(activeManuscript.did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_CUEING_START, ackJson, null, 0),
                                deviceId, true);
                        resetSessionVars();
                        scheduleTick(450L);
                    } else if (code == 5) {
                        // Glasses weren't in Preview yet -> retry smoothly
                        Log.w(TAG, "0x03 returned code=5 -> resetting to retry after 1.5s");
                        resetSessionVars();
                        backoffUntilUptimeMs = SystemClock.uptimeMillis() + 1500L;
                    }
                } else if (action == 1 && isLyricsDid(did) && !deviceId.isEmpty()) {
                    long now = SystemClock.uptimeMillis();
                    stoppedByGlassesTrackKey = "";
                    foreignTeleprompterActive = false;
                    cueingStartScheduled = false;
                    sendBleTeleprompter(
                            encodeGlassesMsg(MSG_CUEING_START, buildSimpleAckJson(did), null, 0),
                            deviceId, true);
                    sessionState = SESSION_ACTIVE;
                    stateEnteredUptimeMs = now;
                    cueingReadyAtUptimeMs = now + POST_COUNTDOWN_GUARD_MS;
                    lastSentLineIdx = -1;
                    scheduleTick(POST_COUNTDOWN_GUARD_MS);
                }
                break;
            }

            case MSG_PAUSE: {
                if (action == 1 && isLyricsDid(did)) {
                    if (!deviceId.isEmpty()) {
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_PAUSE, buildSimpleAckJson(did), null, 0),
                                deviceId, true);
                    }
                    if (Boolean.TRUE.equals(jb.get("isCompleted")) && sessionState == SESSION_ACTIVE) {
                        sessionState = SESSION_PAUSED;
                    }
                }
                break;
            }

            case MSG_RESUME: {
                if (action == 1 && isLyricsDid(did)) {
                    stoppedByGlassesTrackKey = "";
                    if (!deviceId.isEmpty()) {
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_RESUME, buildSimpleAckJson(did), null, 0),
                                deviceId, true);
                    }
                    sessionState = SESSION_ACTIVE;
                    cueingReadyAtUptimeMs = SystemClock.uptimeMillis() + 200L;
                    lastSentLineIdx = -1;
                    scheduleTick(200L);
                }
                break;
            }

            case MSG_PROGRESS_UPDATE: {
                if (action == 1 && isLyricsDid(did) && !deviceId.isEmpty()) {
                    sendBleTeleprompter(
                            encodeGlassesMsg(MSG_PROGRESS_UPDATE, buildSimpleAckJson(did), null, 0),
                            deviceId, false);
                }
                break;
            }

            case MSG_STOP: {
                if (action == 1 && isLyricsDid(did)) {
                    if (!deviceId.isEmpty()) {
                        String ack = buildSimpleAckJson(did);
                        sendBleTeleprompter(
                                encodeGlassesMsg(MSG_STOP, ack, null, 0),
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
                int reportedState = JsonMini.getInt(jb, "state", -1);
                if (action == 2 && reportedState == 3) {
                    foreignTeleprompterActive = false;
                } else if (action == 1 && activeManuscript != null
                        && (sessionState == SESSION_ACTIVE || sessionState == SESSION_PAUSED)
                        && !deviceId.isEmpty()) {
                    int idx = Math.max(0, Math.min(lastSentLineIdx, activeManuscript.lineByteOffsets.length - 1));
                    int hiOff = activeManuscript.lineByteOffsets[idx];
                    int stateVal = (sessionState == SESSION_ACTIVE) ? 1 : 2;
                    String respJson = buildCheckStateResponseJson(activeManuscript, stateVal, hiOff, hiOff);
                    sendBleTeleprompter(
                            encodeGlassesMsg(MSG_CHECK_STATE, respJson, null, 0),
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
        if (did == null || did.isEmpty()) return false;
        if (LYRICS_SLOT_DID.equalsIgnoreCase(did)) return true;
        return did.length() == 10 && did.startsWith(LYRICS_DID_PREFIX);
    }

    private static void sendProgressUpdatePacket(LyricsManuscript ms, int lineIdx) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        int safeIdx = Math.max(0, Math.min(lineIdx, ms.lineByteOffsets.length - 1));
        int hiOff = ms.lineByteOffsets[safeIdx];
        int pageOff = hiOff;
        String json = buildProgressUpdateJson(ms.did, pageOff, hiOff, sessionSnippetMode);
        byte[] pkt = encodeGlassesMsg(MSG_PROGRESS_UPDATE, json, null, 0);
        sendBleTeleprompter(pkt, deviceId, false);
    }

    private static void sendPausePacket(LyricsManuscript ms) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_PAUSE, JsonMini.stringify(m), null, 0),
                deviceId, true);
    }

    private static void sendResumePacket(LyricsManuscript ms) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("did", ms.did);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_RESUME, JsonMini.stringify(m), null, 0),
                deviceId, true);
    }

    private static void sendStopPacket(LyricsManuscript ms, int pageOff) {
        String deviceId = resolveConnectedGlassesDeviceId();
        if (deviceId.isEmpty() || ms == null) return;
        String json = buildStopRequestJson(ms.did, pageOff);
        sendBleTeleprompter(
                encodeGlassesMsg(MSG_STOP, json, null, 0),
                deviceId, true);
    }

    private static void stopSessionOnGlasses(String reason) {
        if (sessionState != SESSION_IDLE && activeManuscript != null) {
            int idx = Math.max(0, Math.min(lastSentLineIdx, activeManuscript.lineByteOffsets.length - 1));
            int pageOff = activeManuscript.lineByteOffsets[idx];
            sendStopPacket(activeManuscript, pageOff);
            Log.i(TAG, "Stopped lyrics teleprompter session (" + reason + ")");
        }
        activeManuscript = null;
        resetSessionVars();
    }

    private static void resetSessionVars() {
        attemptSeq++;
        sessionState = SESSION_IDLE;
        lastSentLineIdx = -1;
        handshakeAttempts = 0;
        fileShareDoneForAttempt = false;
        readyCode7ReceivedForAttempt = false;
        cueingStartScheduled = false;
        cueingStartRetriedOnce = false;
        cueingReadyAtUptimeMs = 0L;
        conflictRetried = false;
        pendingFileTaskId = "";
    }

    private static int computeCurrentPageOffset(LyricsManuscript ms, long posMs) {
        if (ms == null || ms.lineByteOffsets.length == 0) return 0;
        int idx = findActiveLineIndex(ms.lineTimesMs, posMs);
        return ms.lineByteOffsets[idx];
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
            if (w.h != null) return w.h;
        } catch (Throwable ignored) {}
        try {
            if (w.g != null) return w.g.a();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String resolveConnectedGlassesDeviceId() {
        String zId = "";
        boolean zFlag = false;
        try {
            o4 b = lastSeenBridge != null ? lastSeenBridge : o4.Q;
            if (b != null && b.z != null) {
                h4 z = b.z;
                zId = safe(z.a);
                zFlag = z.c;
            }
        } catch (Throwable ignored) {}

        // Iterate CoreConnect w.j() device map: map key is canonical deviceId, dev.b() is live BLE/SPP auth state!
        String wConnectedId = "";
        boolean sawDeviceEntries = false;
        StringBuilder mapDiag = new StringBuilder();
        try {
            Map devices = w.j();
            if (devices != null && !devices.isEmpty()) {
                for (Object entryObj : devices.entrySet()) {
                    if (entryObj instanceof Map.Entry) {
                        Map.Entry en = (Map.Entry) entryObj;
                        Object val = en.getValue();
                        if (val instanceof g0) {
                            sawDeviceEntries = true;
                            g0 dev = (g0) val;
                            String keyId = (en.getKey() instanceof String) ? safe((String) en.getKey()) : "";
                            String fieldId = safe(dev.a);
                            String devId = !fieldId.isEmpty() ? fieldId : keyId;
                            boolean conn = dev.b();
                            mapDiag.append(devId).append("(").append(conn).append(");");
                            if (conn && !devId.isEmpty() && wConnectedId.isEmpty()) {
                                wConnectedId = devId;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        String chosen = "";
        if (!wConnectedId.isEmpty()) {
            chosen = wConnectedId;
        } else if (!sawDeviceEntries) {
            // CoreConnect map not initialized yet -> fall back to activeDeviceId (o4.z.a) or lastSeenDeviceId
            chosen = !zId.isEmpty() ? zId : safe(lastSeenDeviceId);
        }

        String diag = "chosen=" + chosen + " | z=" + zId + "(" + zFlag + ") | wMap={" + mapDiag + "}";
        if (!diag.equals(lastLoggedDevDiag)) {
            lastLoggedDevDiag = diag;
            Log.i(TAG, "[DEV-DIAG] " + diag);
        }
        return chosen;
    }

    private static boolean sendBleTeleprompter(final byte[] payload, final String deviceId, final boolean needAck) {
        if (payload == null || deviceId == null || deviceId.isEmpty()) return false;
        try {
            w wInst = resolveCoreConnectW();
            if (wInst == null) {
                Log.w(TAG, "sendBleTeleprompter: wInst is NULL (lastSeenBridge=" + lastSeenBridge + ")");
                return false;
            }
            final String msgId = "lyr_" + MSG_ID_GEN.incrementAndGet();
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
            wInst.u(msg, new Function2() {
                @Override
                public Object invoke(Object v0Obj, Object errObj) {
                    if (errObj != null) {
                        Log.w(TAG, "[BLE-CB-ERR] msgId=" + msgId + " dev=" + deviceId + " len=" + payload.length + " err=" + errObj);
                    } else if (needAck) {
                        Log.i(TAG, "[BLE-CB-OK] msgId=" + msgId + " dev=" + deviceId + " len=" + payload.length);
                    }
                    return null;
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendBleTeleprompter error", t);
            return false;
        }
    }

    // ==================== Teleprompter Wire JSON Builders ====================

    private static void putDisplaySettings(Map<String, Object> m) {
        m.put("countdown", HUD_COUNTDOWN);
        m.put("scroll", HUD_SCROLL_MANUAL);
        m.put("speed", HUD_SPEED);
        m.put("gear", HUD_GEAR_LOW);
        m.put("depth", HUD_DEPTH_DEEP);
        m.put("size", HUD_FONT_SIZE);
        m.put("width", HUD_WIDTH);
        m.put("leading", HUD_LEADING);
    }

    /**
     * Builds 0x01 ListUpdate (action=2, type=3 upsert) matching official RayNeo iO wire format so the
     * active song's lyrics appear cleanly inside the glasses' Teleprompter menu without wiping user notes.
     */
    public static String buildListUpsertJson(LyricsManuscript ms, int offset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 2);
        m.put("type", 3);
        m.put("total", 1);

        Map<String, Object> chunkInfo = new LinkedHashMap<>();
        chunkInfo.put("chunkid", 1);
        chunkInfo.put("total_items", 1);
        chunkInfo.put("islast", true);
        m.put("chunkinfo", chunkInfo);

        String cleanTitle = sanitizeBmpText(ms.title);
        String cleanArtist = sanitizeBmpText(ms.artist);
        String itemTitle = cleanArtist.isEmpty()
                ? ("[歌词] " + cleanTitle)
                : ("[歌词] " + cleanTitle + " - " + cleanArtist);
        if (itemTitle.length() > 40) {
            itemTitle = itemTitle.substring(0, 40);
        }

        String fullText = sanitizeBmpText(new String(ms.utf8Bytes, StandardCharsets.UTF_8));
        String preview = fullText.length() > 100 ? fullText.substring(0, 100) : fullText;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("did", ms.did);
        item.put("pin", false);
        item.put("title", itemTitle);
        item.put("offset", Math.max(0, offset));
        item.put("scroll", HUD_SCROLL_MANUAL);
        item.put("total", ms.utf8Bytes.length);
        item.put("timestamp", System.currentTimeMillis() / 1000L);
        item.put("preview", preview);
        item.put("timezone", 28800);

        List<Object> items = new ArrayList<>(1);
        items.add(item);
        m.put("items", items);
        return JsonMini.stringify(m);
    }

    /**
     * Builds 0x02 AppStartRequest (file-transfer handshake) matching official RayNeo iO wire format.
     */
    public static String buildAppStartRequestJson(LyricsManuscript ms, int pageOffset, int highLightOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 1);
        m.put("total", ms.utf8Bytes.length);
        putDisplaySettings(m);
        m.put("did", ms.did);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("checksum", ms.checksum);
        return JsonMini.stringify(m);
    }

    /**
     * Builds 0x02 GlassesStartAccept (action=2, code=1) when the user opens the lyrics slot directly
     * from the glasses' Teleprompter menu, immediately followed by fileShare transfer.
     */
    public static String buildGlassesStartAcceptJson(LyricsManuscript ms, int pageOffset, int highLightOffset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", 2);
        m.put("did", ms.did);
        m.put("total", ms.utf8Bytes.length);
        m.put("checksum", ms.checksum);
        putDisplaySettings(m);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("code", 1);
        return JsonMini.stringify(m);
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
        m.put("autoSync", false);
        if (snippetMode) {
            m.put("snippetMode", true);
            Map<String, Object> sInfo = new LinkedHashMap<>();
            sInfo.put("index", 0);
            sInfo.put("inSnippetOffset", pageOffset);
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
        m.put("version", "1.0");
        m.put("state", stateVal);
        m.put("code", 0);
        m.put("pageOffset", pageOffset);
        m.put("highLightOffset", highLightOffset);
        m.put("elapsedSeconds", 0);
        m.put("checksum", ms.checksum);
        putDisplaySettings(m);
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

    private static final class LyricsSearchCandidate {
        final String id;
        final String name;
        final List<String> transNames;
        final List<String> aliases;
        final List<String> artistTokens;
        final String artistDisplay;
        final String album;
        final long durationMs;
        final int popularity;
        int score;
        boolean exactCoreAndTrans;

        LyricsSearchCandidate(
                String id,
                String name,
                List<String> transNames,
                List<String> aliases,
                List<String> artistTokens,
                String artistDisplay,
                String album,
                long durationMs,
                int popularity
        ) {
            this.id = safe(id);
            this.name = safe(name);
            this.transNames = transNames != null ? transNames : Collections.<String>emptyList();
            this.aliases = aliases != null ? aliases : Collections.<String>emptyList();
            this.artistTokens = artistTokens != null ? artistTokens : Collections.<String>emptyList();
            this.artistDisplay = safe(artistDisplay);
            this.album = safe(album);
            this.durationMs = durationMs;
            this.popularity = popularity;
        }
    }

    private static LyricsManuscript getOrFetchManuscript(SystemMediaBridge.TrackInfo track) {
        String key = normalizeTrackKey(track.title, track.artist);
        synchronized (CACHE_LOCK) {
            LyricsManuscript cached = MANUSCRIPT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        LyricsManuscript fetched = fetchLyricsManuscriptOnline(
                track.title, track.artist, track.durationMs, track.packageName, null, null);
        if (fetched.hasSyncedLyrics) {
            synchronized (CACHE_LOCK) {
                MANUSCRIPT_CACHE.put(key, fetched);
            }
        }
        return fetched;
    }

    /**
     * 5-arg overload preserved for unit test compatibility (delegates with durationMs = 0L).
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            String playerPkg,
            String netEaseBaseOverride,
            String qqBaseOverride
    ) {
        return fetchLyricsManuscriptOnline(
                rawTitle, rawArtist, 0L, playerPkg, netEaseBaseOverride, qqBaseOverride);
    }

    /**
     * Fetches LRC from NetEase Cloud Music and/or QQ Music, parses timestamps, pre-wraps lines,
     * and returns a ready-to-stream LyricsManuscript (never null; falls back to static song card).
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            long durationMs,
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
        if (hint != null && titlesRoughlyMatch(title, hint.title)
                && (artist.isEmpty() || safe(hint.artist).isEmpty() || artistsRoughlyOverlap(artist, hint.artist))) {
            if (SystemMediaBridge.PKG_NETEASE.equals(hint.platform)) {
                hintedNeId = safe(hint.songId);
            } else if (SystemMediaBridge.PKG_QQMUSIC.equals(hint.platform)) {
                hintedQqMid = safe(hint.songMid);
            }
        }

        if (preferQq) {
            String qqLrc = fetchQqLrc(title, artist, durationMs, hintedQqMid, qqBaseOverride);
            LyricsManuscript qqMs = parseLrcAndBuildManuscript(title, artist, qqLrc, "QQ音乐");
            if (qqMs.hasSyncedLyrics) return qqMs;

            String neLrc = fetchNetEaseLrc(title, artist, durationMs, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcAndBuildManuscript(title, artist, neLrc, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;
            return qqMs;
        } else {
            String neLrc = fetchNetEaseLrc(title, artist, durationMs, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcAndBuildManuscript(title, artist, neLrc, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;

            String qqLrc = fetchQqLrc(title, artist, durationMs, hintedQqMid, qqBaseOverride);
            LyricsManuscript qqMs = parseLrcAndBuildManuscript(title, artist, qqLrc, "QQ音乐");
            if (qqMs.hasSyncedLyrics) return qqMs;
            return neMs;
        }
    }

    public static String fetchNetEaseLrc(
            String title, String artist, String knownSongId, String baseUrlOverride) {
        return fetchNetEaseLrc(title, artist, 0L, knownSongId, baseUrlOverride);
    }

    public static String fetchNetEaseLrc(
            String title, String artist, long durationMs, String knownSongId, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://music.163.com";
        try {
            List<String> candidateIds = new ArrayList<>();
            String explicitId = safe(knownSongId);
            if (!explicitId.isEmpty()) {
                candidateIds.add(explicitId);
            } else {
                candidateIds.addAll(resolveNetEaseSongIdsForLyrics(title, artist, durationMs, baseUrlOverride));
            }
            if (candidateIds.isEmpty()) return "";

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://music.163.com/");

            int tries = Math.min(3, candidateIds.size());
            for (int i = 0; i < tries; i++) {
                String songId = candidateIds.get(i);
                if (songId.isEmpty()) continue;
                String url = base + "/api/song/lyric?id=" + urlEncode(songId) + "&lv=1&kv=1&tv=-1";
                String body = httpGet(url, headers);
                if (body.isEmpty()) continue;

                Map<String, Object> root = JsonMini.parseObject(body);
                if (Boolean.TRUE.equals(root.get("nolyric")) || Boolean.TRUE.equals(root.get("uncollected"))) {
                    continue;
                }
                Map<String, Object> lrcObj = JsonMini.getMap(root, "lrc");
                String lyric = safe(JsonMini.getString(lrcObj, "lyric"));
                if (!lyric.isEmpty() && lyric.contains("[")) {
                    safeLogI("Resolved NetEase LRC songId=" + songId + " for " + title + " - " + artist);
                    return lyric;
                }

                Map<String, Object> klyricObj = JsonMini.getMap(root, "klyric");
                String klyric = safe(JsonMini.getString(klyricObj, "lyric"));
                if (!klyric.isEmpty() && klyric.contains("[")) {
                    safeLogI("Resolved NetEase klyric songId=" + songId + " for " + title + " - " + artist);
                    return klyric;
                }
            }
            return "";
        } catch (Throwable t) {
            safeLogW("fetchNetEaseLrc error: " + t.getMessage());
            return "";
        }
    }

    public static String fetchQqLrc(
            String title, String artist, String knownSongMid, String baseUrlOverride) {
        return fetchQqLrc(title, artist, 0L, knownSongMid, baseUrlOverride);
    }

    public static String fetchQqLrc(
            String title, String artist, long durationMs, String knownSongMid, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://c.y.qq.com";
        try {
            List<String> candidateMids = new ArrayList<>();
            String explicitMid = safe(knownSongMid);
            if (!explicitMid.isEmpty()) {
                candidateMids.add(explicitMid);
            } else {
                candidateMids.addAll(resolveQqSongMidsForLyrics(title, artist, durationMs, baseUrlOverride));
            }
            if (candidateMids.isEmpty()) return "";

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");

            int tries = Math.min(3, candidateMids.size());
            for (int i = 0; i < tries; i++) {
                String mid = candidateMids.get(i);
                if (mid.isEmpty()) continue;
                String url = base + "/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                        + urlEncode(mid) + "&format=json&nobase64=1";
                String body = stripJsonpWrapper(httpGet(url, headers));
                if (body.isEmpty()) continue;

                Map<String, Object> root = JsonMini.parseObject(body);
                String lyric = safe(JsonMini.getString(root, "lyric"));
                if (lyric.isEmpty()) continue;

                // Handle Base64 payload if nobase64=1 was ignored by an upstream mirror
                if (!lyric.contains("[") && !lyric.contains("&#")) {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(lyric.replaceAll("\\s+", ""));
                        lyric = new String(decoded, StandardCharsets.UTF_8);
                    } catch (Throwable ignored) {}
                }
                String decodedLyric = decodeHtmlEntities(lyric);
                if (decodedLyric.contains("[")) {
                    safeLogI("Resolved QQ LRC mid=" + mid + " for " + title + " - " + artist);
                    return decodedLyric;
                }
            }
            return "";
        } catch (Throwable t) {
            safeLogW("fetchQqLrc error: " + t.getMessage());
            return "";
        }
    }

    /**
     * Resolves the best NetEase song ID for lyrics (includes VIP / copyright-restricted studio tracks
     * with privilege.st < 0 because NetEase's /api/song/lyric endpoint serves their LRC freely!).
     */
    public static String resolveNetEaseSongIdForLyrics(
            String title, String artist, long durationMs, String baseUrlOverride) {
        List<String> ids = resolveNetEaseSongIdsForLyrics(title, artist, durationMs, baseUrlOverride);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    public static List<String> resolveNetEaseSongIdsForLyrics(
            String title, String artist, long durationMs, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://music.163.com";

        List<String> queries = buildLyricsSearchQueries(title, artist);
        LinkedHashMap<String, LyricsSearchCandidate> merged = new LinkedHashMap<>();

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi);
            List<LyricsSearchCandidate> batch = queryNetEaseLyricsCandidates(base, q);
            scoreAndMergeCandidates(batch, title, artist, durationMs, merged);
            // Stop early if we already found an unmistakable studio match (exact title + artist)
            int topScore = getHighestCandidateScore(merged);
            if (topScore >= 420) {
                break;
            }
        }
        return filterAndRankAcceptedIds(merged, title, artist, durationMs);
    }

    public static String resolveQqSongMidForLyrics(
            String title, String artist, long durationMs, String baseUrlOverride) {
        List<String> mids = resolveQqSongMidsForLyrics(title, artist, durationMs, baseUrlOverride);
        return mids.isEmpty() ? "" : mids.get(0);
    }

    public static List<String> resolveQqSongMidsForLyrics(
            String title, String artist, long durationMs, String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://c.y.qq.com";

        List<String> queries = buildLyricsSearchQueries(title, artist);
        LinkedHashMap<String, LyricsSearchCandidate> merged = new LinkedHashMap<>();

        for (String q : queries) {
            List<LyricsSearchCandidate> batch = queryQqSmartboxLyricsCandidates(base, q);
            scoreAndMergeCandidates(batch, title, artist, durationMs, merged);
            if (getHighestCandidateScore(merged) >= 420) {
                break;
            }
        }
        if (baseUrlOverride == null && getHighestCandidateScore(merged) < 350 && !queries.isEmpty()) {
            List<LyricsSearchCandidate> desktop = queryQqDesktopLyricsCandidates(queries.get(0));
            scoreAndMergeCandidates(desktop, title, artist, durationMs, merged);
        }
        return filterAndRankAcceptedIds(merged, title, artist, durationMs);
    }

    private static List<String> buildLyricsSearchQueries(String rawTitle, String rawArtist) {
        String title = safe(rawTitle);
        String artist = safe(rawArtist);
        String coreTitle = stripBracketSuffix(title);
        String primaryArtist = extractPrimaryArtist(artist);

        LinkedHashSet<String> set = new LinkedHashSet<>();
        String baseTitle = !coreTitle.isEmpty() ? coreTitle : title;
        if (!baseTitle.isEmpty() && !primaryArtist.isEmpty()) {
            set.add(baseTitle + " " + primaryArtist);
        }
        if (!title.isEmpty() && !title.equals(baseTitle) && !primaryArtist.isEmpty()) {
            set.add(title + " " + primaryArtist);
        } else if (!baseTitle.isEmpty() && !artist.isEmpty() && !artist.equals(primaryArtist)) {
            set.add(baseTitle + " " + artist.replace('/', ' '));
        }
        if (!baseTitle.isEmpty() && set.isEmpty()) {
            set.add(baseTitle);
        }
        return new ArrayList<>(set);
    }

    private static List<LyricsSearchCandidate> queryNetEaseLyricsCandidates(String base, String keyword) {
        List<LyricsSearchCandidate> out = new ArrayList<>();
        String encoded = urlEncode(keyword);
        String[] urls = new String[] {
                base + "/api/cloudsearch/pc?s=" + encoded + "&type=1&offset=0&limit=20",
                base + "/api/search/get/web?s=" + encoded + "&type=1&offset=0&total=true&limit=20"
        };
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");

        for (String url : urls) {
            try {
                String body = httpGet(url, headers);
                if (body.isEmpty()) continue;
                Map<String, Object> root = JsonMini.parseObject(body);
                Map<String, Object> result = JsonMini.getMap(root, "result");
                List<Object> songs = JsonMini.getList(result, "songs");
                if (songs == null || songs.isEmpty()) continue;

                for (Object item : songs) {
                    Map<String, Object> sm = JsonMini.asMap(item);
                    if (sm == null) continue;
                    // IMPORTANT: Do NOT filter out privilege.st < 0 or status < 0!
                    // VIP / copyright-grayed original tracks still return full synced LRC on /api/song/lyric!
                    String id = "";
                    Object idObj = sm.get("id");
                    if (idObj instanceof Number) {
                        id = String.valueOf(((Number) idObj).longValue());
                    } else if (idObj instanceof String) {
                        id = safe((String) idObj);
                    }
                    String name = safe(JsonMini.getString(sm, "name"));
                    if (id.isEmpty() || name.isEmpty()) continue;

                    List<String> transNames = new ArrayList<>();
                    collectStringList(JsonMini.getList(sm, "tns"), transNames);
                    collectStringList(JsonMini.getList(sm, "transNames"), transNames);

                    List<String> aliases = new ArrayList<>();
                    collectStringList(JsonMini.getList(sm, "alia"), aliases);
                    collectStringList(JsonMini.getList(sm, "alias"), aliases);

                    List<String> artistTokens = new ArrayList<>();
                    StringBuilder artistDisp = new StringBuilder();
                    List<Object> arList = JsonMini.getList(sm, "ar");
                    if (arList == null) arList = JsonMini.getList(sm, "artists");
                    if (arList != null) {
                        for (Object ao : arList) {
                            Map<String, Object> am = JsonMini.asMap(ao);
                            if (am == null) continue;
                            String an = safe(JsonMini.getString(am, "name"));
                            if (!an.isEmpty()) {
                                if (artistDisp.length() > 0) artistDisp.append('/');
                                artistDisp.append(an);
                                addNormalizedArtistTokens(an, artistTokens);
                            }
                            List<String> arTns = new ArrayList<>();
                            collectStringList(JsonMini.getList(am, "tns"), arTns);
                            collectStringList(JsonMini.getList(am, "alias"), arTns);
                            collectStringList(JsonMini.getList(am, "alia"), arTns);
                            for (String at : arTns) {
                                addNormalizedArtistTokens(at, artistTokens);
                            }
                        }
                    }

                    Map<String, Object> alMap = JsonMini.getMap(sm, "al");
                    if (alMap == null) alMap = JsonMini.getMap(sm, "album");
                    String album = safe(JsonMini.getString(alMap, "name"));

                    long dt = 0L;
                    if (sm.get("dt") instanceof Number) {
                        dt = ((Number) sm.get("dt")).longValue();
                    } else if (sm.get("duration") instanceof Number) {
                        dt = ((Number) sm.get("duration")).longValue();
                    }
                    int pop = (sm.get("pop") instanceof Number) ? ((Number) sm.get("pop")).intValue() : 0;

                    out.add(new LyricsSearchCandidate(
                            id, name, transNames, aliases, artistTokens, artistDisp.toString(), album, dt, pop));
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Throwable t) {
                safeLogW("queryNetEaseLyricsCandidates error: " + t.getMessage());
            }
        }
        return out;
    }

    private static List<LyricsSearchCandidate> queryQqSmartboxLyricsCandidates(String base, String keyword) {
        List<LyricsSearchCandidate> out = new ArrayList<>();
        try {
            String url = base + "/splcloud/fcgi-bin/smartbox_new.fcg?is_xml=0&format=json&key="
                    + urlEncode(keyword);
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");
            String body = httpGet(url, headers);
            if (body.isEmpty()) return out;

            Map<String, Object> root = JsonMini.parseObject(body);
            Map<String, Object> data = JsonMini.getMap(root, "data");
            Map<String, Object> songBox = JsonMini.getMap(data, "song");
            List<Object> items = JsonMini.getList(songBox, "itemlist");
            if (items == null) return out;

            for (Object o : items) {
                Map<String, Object> sm = JsonMini.asMap(o);
                if (sm == null) continue;
                String mid = safe(JsonMini.getString(sm, "mid"));
                String name = safe(JsonMini.getString(sm, "name"));
                String singer = safe(JsonMini.getString(sm, "singer"));
                if (mid.isEmpty() || name.isEmpty()) continue;
                List<String> artistTokens = new ArrayList<>();
                addNormalizedArtistTokens(singer, artistTokens);
                out.add(new LyricsSearchCandidate(
                        mid, name, Collections.<String>emptyList(), Collections.<String>emptyList(),
                        artistTokens, singer, "", 0L, 0));
            }
        } catch (Throwable t) {
            safeLogW("queryQqSmartboxLyricsCandidates error: " + t.getMessage());
        }
        return out;
    }

    private static List<LyricsSearchCandidate> queryQqDesktopLyricsCandidates(String keyword) {
        List<LyricsSearchCandidate> out = new ArrayList<>();
        try {
            String escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"");
            String payload = "{\"req\":{\"module\":\"music.search.SearchCgiService\","
                    + "\"method\":\"DoSearchForQQMusicDesktop\","
                    + "\"param\":{\"query\":\"" + escaped
                    + "\",\"num_per_page\":15,\"page_num\":1,\"search_type\":0}}}";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            headers.put("Referer", "https://y.qq.com/");
            String body = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg", payload, headers);
            if (body.isEmpty()) return out;

            Map<String, Object> root = JsonMini.parseObject(body);
            Map<String, Object> reqMap = JsonMini.getMap(root, "req");
            Map<String, Object> data = JsonMini.getMap(reqMap, "data");
            Map<String, Object> bodyMap = JsonMini.getMap(data, "body");
            Map<String, Object> songMap = JsonMini.getMap(bodyMap, "song");
            List<Object> list = JsonMini.getList(songMap, "list");
            if (list == null) return out;

            for (Object o : list) {
                Map<String, Object> sm = JsonMini.asMap(o);
                if (sm == null) continue;
                String mid = safe(JsonMini.getString(sm, "mid"));
                String name = safe(JsonMini.getString(sm, "name"));
                if (mid.isEmpty() || name.isEmpty()) continue;

                List<String> transNames = new ArrayList<>();
                String subtitle = safe(JsonMini.getString(sm, "subtitle"));
                if (!subtitle.isEmpty()) transNames.add(subtitle);

                List<String> artistTokens = new ArrayList<>();
                StringBuilder artistDisp = new StringBuilder();
                List<Object> singers = JsonMini.getList(sm, "singer");
                if (singers != null) {
                    for (Object so : singers) {
                        Map<String, Object> sMap = JsonMini.asMap(so);
                        String sn = safe(JsonMini.getString(sMap, "name"));
                        if (!sn.isEmpty()) {
                            if (artistDisp.length() > 0) artistDisp.append('/');
                            artistDisp.append(sn);
                            addNormalizedArtistTokens(sn, artistTokens);
                        }
                    }
                }
                Map<String, Object> albumMap = JsonMini.getMap(sm, "album");
                String album = safe(JsonMini.getString(albumMap, "name"));
                long dt = (sm.get("interval") instanceof Number)
                        ? (((Number) sm.get("interval")).longValue() * 1000L)
                        : 0L;
                out.add(new LyricsSearchCandidate(
                        mid, name, transNames, Collections.<String>emptyList(),
                        artistTokens, artistDisp.toString(), album, dt, 0));
            }
        } catch (Throwable t) {
            safeLogW("queryQqDesktopLyricsCandidates error: " + t.getMessage());
        }
        return out;
    }

    private static void scoreAndMergeCandidates(
            List<LyricsSearchCandidate> batch,
            String expectedTitle,
            String expectedArtist,
            long expectedDurationMs,
            LinkedHashMap<String, LyricsSearchCandidate> merged
    ) {
        if (batch == null) return;
        for (int i = 0; i < batch.size(); i++) {
            LyricsSearchCandidate c = batch.get(i);
            c.score = scoreLyricsSongCandidate(c, expectedTitle, expectedArtist, expectedDurationMs, i);
            LyricsSearchCandidate existing = merged.get(c.id);
            if (existing == null || c.score > existing.score) {
                merged.put(c.id, c);
            }
        }
    }

    private static int getHighestCandidateScore(Map<String, LyricsSearchCandidate> merged) {
        int best = Integer.MIN_VALUE;
        for (LyricsSearchCandidate c : merged.values()) {
            if (c.score > best) best = c.score;
        }
        return best;
    }

    private static List<String> filterAndRankAcceptedIds(
            Map<String, LyricsSearchCandidate> merged,
            String expectedTitle,
            String expectedArtist,
            long expectedDurationMs
    ) {
        List<LyricsSearchCandidate> ranked = new ArrayList<>(merged.values());
        Collections.sort(ranked, new Comparator<LyricsSearchCandidate>() {
            @Override
            public int compare(LyricsSearchCandidate a, LyricsSearchCandidate b) {
                return Integer.compare(b.score, a.score);
            }
        });

        boolean hasArtist = !safe(expectedArtist).isEmpty();
        List<String> out = new ArrayList<>();
        for (LyricsSearchCandidate c : ranked) {
            boolean strongTransAndDuration = c.exactCoreAndTrans
                    || (expectedDurationMs > 5000L && c.durationMs > 5000L
                    && Math.abs(expectedDurationMs - c.durationMs) <= 2500L);
            int minScore = hasArtist ? (strongTransAndDuration ? 110 : 180) : 140;
            if (c.score >= minScore) {
                out.add(c.id);
            }
        }
        return out;
    }

    private static int scoreLyricsSongCandidate(
            LyricsSearchCandidate c,
            String expectedTitle,
            String expectedArtist,
            long expectedDurationMs,
            int rankIndex
    ) {
        String expFull = normalizeCompareText(expectedTitle);
        String expCore = normalizeCompareText(stripBracketSuffix(expectedTitle));
        String expBracket = normalizeCompareText(extractFirstBracketText(expectedTitle));
        if (expFull.isEmpty() && expCore.isEmpty()) return -9999;

        String candName = normalizeCompareText(c.name);
        String candCore = normalizeCompareText(stripBracketSuffix(c.name));
        Set<String> candTransSet = new HashSet<>();
        StringBuilder transJoinedSb = new StringBuilder();
        for (String tn : c.transNames) {
            String ntn = normalizeCompareText(tn);
            if (!ntn.isEmpty()) candTransSet.add(ntn);
            if (transJoinedSb.length() > 0) transJoinedSb.append(' ');
            transJoinedSb.append(tn);
        }
        String candWithFirstTrans = c.transNames.isEmpty()
                ? candName
                : normalizeCompareText(c.name + " " + c.transNames.get(0));

        int score = 0;
        c.exactCoreAndTrans = false;

        // 1. Title & translated-title match (mandatory)
        if (!expCore.isEmpty() && candCore.equals(expCore)
                && !expBracket.isEmpty() && candTransSet.contains(expBracket)) {
            score += 275;
            c.exactCoreAndTrans = true;
        } else if (!expFull.isEmpty() && (candName.equals(expFull) || candWithFirstTrans.equals(expFull))) {
            score += 260;
        } else if (!expCore.isEmpty() && (candName.equals(expCore) || candCore.equals(expCore))) {
            score += 235;
        } else if ((!expFull.isEmpty() && candTransSet.contains(expFull))
                || (!expCore.isEmpty() && candTransSet.contains(expCore))) {
            score += 205;
        } else {
            String baseExp = !expCore.isEmpty() ? expCore : expFull;
            String baseCand = !candCore.isEmpty() ? candCore : candName;
            int minLen = Math.min(baseExp.length(), baseCand.length());
            if (minLen >= 2 && (baseCand.contains(baseExp) || baseExp.contains(baseCand))) {
                score += 110;
            } else {
                return -9999;
            }
        }

        // 2. Artist token match (handles single artist, multi-artist '/' '&' '、', and translated aliases)
        List<String> expArtistTokens = new ArrayList<>();
        addNormalizedArtistTokens(expectedArtist, expArtistTokens);
        if (!expArtistTokens.isEmpty()) {
            int matched = 0;
            for (String et : expArtistTokens) {
                if (matchesAnyArtistToken(et, c.artistTokens)) {
                    matched++;
                }
            }
            if (matched == expArtistTokens.size()) {
                score += 260;
            } else if (matched > 0) {
                score += 205;
            } else {
                score -= 220;
            }
        }

        // 3. Penalize unrequested covers, English/Chinese re-writes, karaoke/instrumentals, DJ/remixes, live
        StringBuilder aliasSb = new StringBuilder();
        for (String al : c.aliases) {
            if (aliasSb.length() > 0) aliasSb.append(' ');
            aliasSb.append(al);
        }
        String candMetaLow = (c.name + " " + transJoinedSb + " " + aliasSb + " " + c.album)
                .toLowerCase(Locale.ROOT);
        String expMetaLow = (safe(expectedTitle) + " " + safe(expectedArtist)).toLowerCase(Locale.ROOT);

        if (!containsAnyKeyword(expMetaLow, "伴奏", "instrumental", "karaoke", "卡拉ok", "off vocal", "纯音乐")) {
            if (containsAnyKeyword(candMetaLow, "伴奏", "instrumental", "karaoke", "卡拉ok", "off vocal", "消音")) {
                score -= 320;
            }
        }
        if (!containsAnyKeyword(expMetaLow, "cover", "翻唱", "eng.ver", "eng ver", "english ver", "中文版", "英文版", "粤语版")) {
            if (containsAnyKeyword(candMetaLow, "cover", "翻唱", "eng.ver", "eng ver", "english ver", "中文版", "英文版", "粤语版")) {
                score -= 240;
            }
        }
        if (!containsAnyKeyword(expMetaLow, "dj", "remix", "慢摇", "串烧", "0.8x", "1.2x", "sped up", "slowed")) {
            if (containsAnyKeyword(candMetaLow, "dj版", "remix", "慢摇", "串烧", "0.8x", "1.2x", "sped up", "slowed")) {
                score -= 200;
            }
        }
        if (!containsAnyKeyword(expMetaLow, "live", "现场", "演唱会")) {
            if (containsAnyKeyword(candMetaLow, "live", "现场", "演唱会")) {
                score -= 90;
            }
        }

        // 4. Duration proximity bonus / mismatch penalty
        if (expectedDurationMs > 5000L && c.durationMs > 5000L) {
            long diff = Math.abs(expectedDurationMs - c.durationMs);
            if (diff <= 2500L) {
                score += 55;
            } else if (diff <= 6000L) {
                score += 30;
            } else if (diff > 25000L) {
                score -= 70;
            }
        }

        // 5. Search rank & popularity tie-breaker
        score += Math.max(0, 24 - rankIndex * 2) + Math.min(10, Math.max(0, c.popularity / 10));
        return score;
    }

    private static void collectStringList(List<Object> rawList, List<String> dest) {
        if (rawList == null) return;
        for (Object o : rawList) {
            if (o instanceof String) {
                String s = safe((String) o);
                if (!s.isEmpty()) dest.add(s);
            }
        }
    }

    private static String extractPrimaryArtist(String rawArtist) {
        String s = safe(rawArtist);
        if (s.isEmpty()) return "";
        String[] parts = s.split("\\s*(?:/|&|、|,|，|;|；|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*");
        for (String p : parts) {
            String clean = stripBracketSuffix(p).trim();
            if (!clean.isEmpty()) return clean;
        }
        return stripBracketSuffix(s);
    }

    private static void addNormalizedArtistTokens(String rawArtist, List<String> out) {
        String s = safe(rawArtist);
        if (s.isEmpty()) return;
        String[] parts = s.split("\\s*(?:/|&|、|,|，|;|；|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*");
        for (String p : parts) {
            String fullNorm = normalizeCompareText(p);
            if (!fullNorm.isEmpty() && !out.contains(fullNorm)) {
                out.add(fullNorm);
            }
            String cleanNorm = normalizeCompareText(stripBracketSuffix(p));
            if (!cleanNorm.isEmpty() && !out.contains(cleanNorm)) {
                out.add(cleanNorm);
            }
        }
    }

    private static boolean matchesAnyArtistToken(String expToken, List<String> candTokens) {
        if (expToken == null || expToken.isEmpty() || candTokens == null) return false;
        for (String ct : candTokens) {
            if (ct.equals(expToken)) return true;
            int minLen = Math.min(ct.length(), expToken.length());
            if (minLen >= 2 && (ct.contains(expToken) || expToken.contains(ct))) {
                return true;
            }
        }
        return false;
    }

    private static boolean artistsRoughlyOverlap(String a, String b) {
        List<String> ta = new ArrayList<>();
        List<String> tb = new ArrayList<>();
        addNormalizedArtistTokens(a, ta);
        addNormalizedArtistTokens(b, tb);
        for (String x : ta) {
            if (matchesAnyArtistToken(x, tb)) return true;
        }
        return false;
    }

    private static String extractFirstBracketText(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("[（(\\[【]([^）)\\]】]+)[）)\\]】]").matcher(s);
        if (m.find()) {
            return safe(m.group(1));
        }
        return "";
    }

    private static boolean containsAnyKeyword(String haystackLower, String... needles) {
        if (haystackLower == null || haystackLower.isEmpty()) return false;
        for (String n : needles) {
            if (haystackLower.contains(n)) return true;
        }
        return false;
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

    /**
     * Strips 4-byte supplementary/astral codepoints (e.g. emojis > U+FFFF), variation selectors,
     * zero-width/bidi formatters, and normalizes full-width/non-breaking spaces to ASCII ' ' so
     * SmallCore RTOS's BMP glyph renderer never hits unmapped astral codepoints.
     */
    public static String sanitizeBmpText(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length());
        int len = input.length();
        for (int i = 0; i < len; ) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                continue; // Strip 4-byte supplementary plane emojis/symbols
            }
            if (cp == 0x3000 || cp == 0x00A0) {
                sb.append(' ');
                continue;
            }
            if (cp == 0xFEFF
                    || (cp >= 0xFE00 && cp <= 0xFE0F)
                    || (cp >= 0x200B && cp <= 0x200F)
                    || (cp >= 0x2028 && cp <= 0x202F)
                    || (cp >= 0x2060 && cp <= 0x206F)
                    || (cp >= 0xD800 && cp <= 0xDFFF)) {
                continue;
            }
            if (cp < 0x20 && cp != '\n' && cp != '\t') {
                continue;
            }
            if (cp >= 0x7F && cp <= 0x9F) {
                continue;
            }
            sb.append((char) cp);
        }
        return sb.toString();
    }

    // ==================== LRC Parser, HTML Decoder, Pre-Wrapper & Checksum ====================

    public static LyricsManuscript parseLrcAndBuildManuscript(String title, String artist, String rawLrc) {
        return parseLrcAndBuildManuscript(title, artist, rawLrc, "");
    }

    public static LyricsManuscript parseLrcAndBuildManuscript(
            String title, String artist, String rawLrc, String sourcePlatform) {
        String rawCleanTitle = sanitizeBmpText(safe(title)).trim();
        String cleanTitle = rawCleanTitle.isEmpty() ? "未知歌曲" : rawCleanTitle;
        String cleanArtist = sanitizeBmpText(safe(artist)).trim();
        String trackKey = normalizeTrackKey(safe(title), safe(artist));
        String did = computeLyricsDid(trackKey);

        String headerRaw = cleanArtist.isEmpty()
                ? ("[歌词] " + cleanTitle)
                : ("[歌词] " + cleanTitle + " - " + cleanArtist);
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

            // Strip any remaining inline karaoke tags like <00:12.40> or [00:12.40] and sanitize BMP glyphs
            String text = sanitizeBmpText(line.substring(lastTagEnd)
                    .replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "")
                    .replaceAll("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]", ""))
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
        return LYRICS_SLOT_DID;
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
        return httpRequest("GET", urlStr, null, headers);
    }

    private static String httpPost(String urlStr, String body, Map<String, String> headers) throws Exception {
        return httpRequest("POST", urlStr, body, headers);
    }

    private static String httpRequest(
            String method, String urlStr, String reqBody, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
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
            if (reqBody != null && !reqBody.isEmpty()) {
                conn.setDoOutput(true);
                byte[] outBytes = reqBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(outBytes);
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

    private static void safeLogI(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {}
    }

    private static void safeLogW(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {}
    }
}
