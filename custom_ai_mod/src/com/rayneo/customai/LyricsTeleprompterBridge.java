package com.rayneo.customai;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import kotlin.jvm.functions.Function2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    // Lyrics display modes
    public static final String MODE_ORIGINAL = CustomAiConfig.LYRICS_MODE_ORIGINAL;
    public static final String MODE_BILINGUAL = CustomAiConfig.LYRICS_MODE_BILINGUAL;
    public static final String MODE_ROMAJI = CustomAiConfig.LYRICS_MODE_ROMAJI;
    public static final String MODE_TRILINGUAL = CustomAiConfig.LYRICS_MODE_TRILINGUAL;

    // Voice command actions
    public static final int CMD_NONE = 0;
    public static final int CMD_ENABLE_SYNC = 1;
    public static final int CMD_DISABLE_SYNC = 2;
    public static final int CMD_MODE_ORIGINAL = 3;
    public static final int CMD_MODE_BILINGUAL = 4;
    public static final int CMD_MODE_ROMAJI = 5;
    public static final int CMD_MODE_TRILINGUAL = 6;

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

    private static final Pattern PAT_CMD_TRILINGUAL = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:打开|开启|切换(?:到|成)?|显示|用)(?:一下)?(?:三语歌词|双语(?:加|和|带)罗马音(?:歌词)?|罗马音(?:加|和|带)(?:双语|翻译)(?:歌词)?|(?:翻译|中文)(?:和|加|与)罗马音(?:歌词)?)"
                    + "|同时显示(?:歌词)?(?:翻译|双语)(?:和|与|及)罗马音"
                    + "|同时显示罗马音(?:和|与|及)(?:翻译|双语)"
                    + ")"
                    + "(?:吧|呀|啊|呗|模式)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_CMD_ROMAJI = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:打开|开启|切换(?:到|成)?|显示|看看)(?:一下)?(?:日语|日文|歌曲|歌词)?(?:的)?罗马音(?:歌词|注音|模式|同步|联动)?"
                    + "|歌词(?:显示|开启|加上|带上)罗马音"
                    + ")"
                    + "(?:吧|呀|啊|呗)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_CMD_BILINGUAL = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:打开|开启|切换(?:到|成)?|显示|看看)(?:一下)?(?:双语|中英|中日|中韩|中外|翻译|中文翻译|对照)歌词(?:联动|同步|显示|模式)?"
                    + "|(?:打开|开启|显示)(?:一下)?歌词(?:的)?(?:中文)?翻译"
                    + "|歌词(?:显示|开启|加上|带上)(?:中文)?翻译"
                    + ")"
                    + "(?:吧|呀|啊|呗)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_CMD_ORIGINAL_ONLY = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:"
                    + "(?:只|仅)(?:看|显示)(?:原词|原文歌词|原版歌词|原文)"
                    + "|(?:关闭|关掉|隐藏|取消)(?:双语歌词|歌词翻译|翻译歌词|罗马音|罗马音歌词)"
                    + "|切换(?:到|成)?(?:仅)?原词(?:模式)?"
                    + ")"
                    + "(?:吧|呀|啊|呗)?$",
            Pattern.CASE_INSENSITIVE);

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

    public static final class LrcBundle {
        public final String lrc;
        public final String tlyric;
        public final String romalrc;

        public LrcBundle(String lrc, String tlyric, String romalrc) {
            this.lrc = lrc == null ? "" : lrc;
            this.tlyric = tlyric == null ? "" : tlyric;
            this.romalrc = romalrc == null ? "" : romalrc;
        }

        public boolean hasMainLrc() {
            return !lrc.isEmpty() && lrc.contains("[");
        }
    }

    public static final class LyricsManuscript {
        public final String trackKey;
        public final String did;
        public final String title;
        public final String artist;
        public final String[] lines;
        public final long[] lineTimesMs;
        public final int[] lineByteOffsets;
        public final boolean[] companionFlags;
        public final byte[] utf8Bytes;
        public final String checksum;
        public final boolean hasSyncedLyrics;
        public final String sourcePlatform;
        public final String displayMode;
        public final boolean isCustomNote;

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
            this(trackKey, did, title, artist, lines, lineTimesMs, lineByteOffsets,
                    null, utf8Bytes, checksum, hasSyncedLyrics, sourcePlatform, MODE_ORIGINAL, false);
        }

        public LyricsManuscript(
                String trackKey,
                String did,
                String title,
                String artist,
                String[] lines,
                long[] lineTimesMs,
                int[] lineByteOffsets,
                boolean[] companionFlags,
                byte[] utf8Bytes,
                String checksum,
                boolean hasSyncedLyrics,
                String sourcePlatform,
                String displayMode,
                boolean isCustomNote
        ) {
            this.trackKey = trackKey;
            this.did = did;
            this.title = title;
            this.artist = artist;
            this.lines = lines;
            this.lineTimesMs = lineTimesMs;
            this.lineByteOffsets = lineByteOffsets;
            this.companionFlags = companionFlags != null ? companionFlags : new boolean[lines != null ? lines.length : 0];
            this.utf8Bytes = utf8Bytes;
            this.checksum = checksum;
            this.hasSyncedLyrics = hasSyncedLyrics;
            this.sourcePlatform = sourcePlatform == null ? "" : sourcePlatform;
            this.displayMode = CustomAiConfig.normalizeLyricsMode(displayMode);
            this.isCustomNote = isCustomNote;
        }

        /**
         * Resolves the active primary anchor line index at {@code posMs}, snapping backwards across
         * companion sub-lines (romaji / translation) so 0x08 ProgressUpdate keeps the full lyric block
         * (original + romaji + translation) visible together at the top of the glasses HUD.
         */
        public int findCueLineIndex(long posMs) {
            int idx = findActiveLineIndex(this.lineTimesMs, posMs);
            while (idx > 0 && idx < this.companionFlags.length && this.companionFlags[idx]) {
                idx--;
            }
            return idx;
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
    private static volatile Object lastSeenBridge;
    private static volatile String configuredBridgeClassName = "com.rayneo.rayneo_venus_sdk_plugin.o4";
    private static volatile CoreConnectHandles cachedHandles;
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
    private static LyricsManuscript customOverrideManuscript;
    private static String customOverrideBaselineTrackKey = "";
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
                customOverrideManuscript = null;
                customOverrideBaselineTrackKey = "";
                stoppedByGlassesTrackKey = "";
                foreignTeleprompterActive = false;
                backoffUntilUptimeMs = 0L;
                handshakeAttempts = 0;
                conflictRetried = false;
                forcePushUntilUptimeMs = SystemClock.uptimeMillis() + 15000L;
                if (sessionState != SESSION_IDLE && activeManuscript != null) {
                    sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                    activeManuscript = null;
                    resetSessionVars();
                    scheduleTick(280L);
                } else {
                    activeManuscript = null;
                    onWorkerTick();
                }
                if (onUiDone != null) {
                    onUiDone.run();
                }
            }
        });
    }

    /**
     * Pushes an arbitrary custom text / AI-generated outline / clipboard note onto the glasses
     * Teleprompter HUD. If glasses TTS / voice session is currently active, waits automatically
     * until voiceAssistantBusy clears so the HUD never collides with voice announcements.
     */
    public static String pushCustomTextToGlasses(Context ctx, String title, String content) {
        if (ctx != null && appContext == null && ctx.getApplicationContext() instanceof Application) {
            init((Application) ctx.getApplicationContext());
        }
        final LyricsManuscript noteMs = buildCustomNoteManuscript(title, content);
        Handler h = workerHandler;
        if (h == null) {
            return "眼镜提词器工作线程尚未初始化";
        }
        final Context appCtx = ctx != null ? ctx.getApplicationContext() : appContext;
        h.post(new Runnable() {
            @Override
            public void run() {
                String curSongKey = "";
                if (appCtx != null) {
                    SystemMediaBridge.TrackInfo t = getCurrentLyricsTrack(appCtx, null);
                    if (t != null && t.hasSong()) {
                        curSongKey = normalizeTrackKey(t.title, t.artist);
                    }
                }
                customOverrideManuscript = noteMs;
                customOverrideBaselineTrackKey = curSongKey;
                stoppedByGlassesTrackKey = "";
                foreignTeleprompterActive = false;
                backoffUntilUptimeMs = 0L;
                handshakeAttempts = 0;
                conflictRetried = false;
                forcePushUntilUptimeMs = SystemClock.uptimeMillis() + 30000L;
                if (sessionState != SESSION_IDLE && activeManuscript != null) {
                    sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                    activeManuscript = null;
                    resetSessionVars();
                    scheduleTick(300L);
                } else {
                    onWorkerTick();
                }
            }
        });
        return "已将《" + noteMs.title + "》（共 " + noteMs.lines.length + " 行）加入眼镜提词器推送队列";
    }

    /**
     * Resolves the active track eligible to trigger lyrics sync according to the user's selected app list.
     */
    public static SystemMediaBridge.TrackInfo getCurrentLyricsTrack(Context ctx, CustomAiConfig cfgOrNull) {
        if (ctx == null) return null;
        CustomAiConfig cfg = cfgOrNull != null ? cfgOrNull : CustomAiConfig.load(ctx);
        return SystemMediaBridge.getCurrentTrackFiltered(ctx, cfg.getAllowedLyricsPackageSet());
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

    public static String formatDisplayModeLabel(String mode) {
        String m = CustomAiConfig.normalizeLyricsMode(mode);
        if (MODE_ORIGINAL.equals(m)) return "仅原词";
        if (MODE_ROMAJI.equals(m)) return "原词+罗马音";
        if (MODE_TRILINGUAL.equals(m)) return "原词+罗马音+译文";
        return "双语对照";
    }

    public static String voiceCommandActionLabel(int cmd) {
        switch (cmd) {
            case CMD_DISABLE_SYNC: return "关闭歌词提词联动";
            case CMD_MODE_ORIGINAL: return "切换仅原词模式";
            case CMD_MODE_BILINGUAL: return "开启双语对照歌词";
            case CMD_MODE_ROMAJI: return "开启罗马音歌词";
            case CMD_MODE_TRILINGUAL: return "开启三语全显歌词";
            default: return "开启歌词提词联动";
        }
    }

    public static int matchVoiceCommand(String rawQuery) {
        if (rawQuery == null) return CMD_NONE;
        String s = rawQuery.trim();
        s = s.replaceAll("^[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+", "")
                .replaceAll("[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+$", "");
        if (s.isEmpty()) return CMD_NONE;
        s = PAT_WAKE_PREFIX.matcher(s).replaceFirst("").trim();
        if (s.isEmpty()) return CMD_NONE;

        if (PAT_CMD_ORIGINAL_ONLY.matcher(s).matches()) {
            return CMD_MODE_ORIGINAL;
        }
        if (PAT_CMD_TRILINGUAL.matcher(s).matches()) {
            return CMD_MODE_TRILINGUAL;
        }
        if (PAT_CMD_ROMAJI.matcher(s).matches()) {
            return CMD_MODE_ROMAJI;
        }
        if (PAT_CMD_BILINGUAL.matcher(s).matches()) {
            return CMD_MODE_BILINGUAL;
        }
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
        if (cmd == CMD_DISABLE_SYNC) {
            cfg.syncLyricsToTeleprompter = false;
            cfg.save(ctx);
            onConfigChanged(ctx);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "LYRICS_SYNC_DISABLED");
            }
            return "已关闭眼镜歌词提词器联动";
        }
        if (cmd == CMD_MODE_ORIGINAL) {
            cfg.lyricsDisplayMode = MODE_ORIGINAL;
        } else if (cmd == CMD_MODE_BILINGUAL) {
            cfg.lyricsDisplayMode = MODE_BILINGUAL;
        } else if (cmd == CMD_MODE_ROMAJI) {
            cfg.lyricsDisplayMode = MODE_ROMAJI;
        } else if (cmd == CMD_MODE_TRILINGUAL) {
            cfg.lyricsDisplayMode = MODE_TRILINGUAL;
        }
        if (cmd == CMD_ENABLE_SYNC
                || cmd == CMD_MODE_ORIGINAL
                || cmd == CMD_MODE_BILINGUAL
                || cmd == CMD_MODE_ROMAJI
                || cmd == CMD_MODE_TRILINGUAL) {
            cfg.syncLyricsToTeleprompter = true;
            cfg.save(ctx);
            syncNow(ctx, null);
            String modeLabel = formatDisplayModeLabel(cfg.lyricsDisplayMode);
            SystemMediaBridge.TrackInfo ti = getCurrentLyricsTrack(ctx, cfg);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "LYRICS_SYNC_ENABLED(mode=" + cfg.lyricsDisplayMode
                        + ", track=" + (ti != null ? ti.formatSongDisplay() : "none") + ")");
            }
            if (ti != null && ti.hasSong()) {
                return "已开启眼镜歌词联动（" + modeLabel + "），正在同步：" + ti.formatSongDisplay();
            }
            SystemMediaBridge.TrackInfo rawAny = SystemMediaBridge.getCurrentTrack(ctx);
            if (rawAny != null && rawAny.hasSong()) {
                String appLabel = !rawAny.appName.isEmpty() ? rawAny.appName : rawAny.packageName;
                return "已开启眼镜歌词联动（" + modeLabel + "）。当前正在播放的「" + appLabel
                        + "」未在触发应用列表中，如需联动可在面板「选择触发歌词的 App」中勾选";
            }
            return "已开启眼镜歌词提词器联动（" + modeLabel + "），播放指定音乐 App 时将自动同步滚动歌词";
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
            captureBridgeInstance(bridgeObj);
            if (eventName == null) return false;

            if ("messageReceived".equals(eventName)) {
                Map msgMap = unwrapMessageMap(data);
                if (msgMap == null) return false;
                Object devObj = msgMap.get("deviceId");
                if (devObj instanceof String && !((String) devObj).isEmpty()) {
                    lastSeenDeviceId = (String) devObj;
                }
                Object bizObj = msgMap.get("businessId");
                if (!isTeleprompterBusinessId(bizObj)) {
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
            captureBridgeInstance(bridgeObj);
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

    public static boolean isTeleprompterBusinessId(Object bizObj) {
        if (bizObj == null) return false;
        if ("TELEPROMPTER".equals(bizObj)) return true;
        if (bizObj instanceof Number) {
            return (((Number) bizObj).intValue() & 0xFF) == 0x14;
        }
        String s = String.valueOf(bizObj).trim();
        return "TELEPROMPTER".equalsIgnoreCase(s) || "20".equals(s) || "0x14".equalsIgnoreCase(s);
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
            final String desiredMode = CustomAiConfig.normalizeLyricsMode(cfg.lyricsDisplayMode);

            // Safety auto-expire for voiceAssistantBusy
            if (voiceAssistantBusy && SystemClock.uptimeMillis() - voiceBusySinceUptimeMs > VOICE_BUSY_MAX_MS) {
                voiceAssistantBusy = false;
            }
            if (voiceAssistantBusy) {
                scheduleTick(450L);
                return;
            }

            SystemMediaBridge.TrackInfo track = getCurrentLyricsTrack(app, cfg);
            String liveTrackKey = (track != null && track.hasSong())
                    ? normalizeTrackKey(track.title, track.artist) : "";

            // Handle custom note / Skill teleprompter push (works even when music lyrics sync is off!)
            if (customOverrideManuscript != null) {
                if (cfg.syncLyricsToTeleprompter && !liveTrackKey.isEmpty()
                        && !liveTrackKey.equals(customOverrideBaselineTrackKey)
                        && track != null && track.isPlaying()) {
                    // User switched to a new playing song -> yield back to live music lyrics
                    customOverrideManuscript = null;
                    customOverrideBaselineTrackKey = "";
                } else {
                    if (foreignTeleprompterActive) {
                        lastStatusSummary = "原生提词器使用中（已自动避让）";
                        scheduleTick(TICK_IDLE_MS);
                        return;
                    }
                    String devId = resolveConnectedGlassesDeviceId();
                    if (devId.isEmpty()) {
                        lastStatusSummary = "等待眼镜蓝牙连接（待投送提词）…";
                        scheduleTick(TICK_IDLE_MS);
                        return;
                    }
                    if (activeManuscript != customOverrideManuscript) {
                        if (sessionState != SESSION_IDLE && activeManuscript != null) {
                            sendStopPacket(activeManuscript, computeCurrentPageOffset(activeManuscript, 0L));
                            activeManuscript = null;
                            resetSessionVars();
                            backoffUntilUptimeMs = SystemClock.uptimeMillis() + TRACK_SWITCH_STOP_SETTLE_MS;
                            scheduleTick(TRACK_SWITCH_STOP_SETTLE_MS + 40L);
                            return;
                        }
                        activeManuscript = customOverrideManuscript;
                        resetSessionVars();
                        backoffUntilUptimeMs = 0L;
                    }
                    long nowNote = SystemClock.uptimeMillis();
                    if (nowNote < backoffUntilUptimeMs) {
                        scheduleTick(Math.min(1000L, Math.max(80L, backoffUntilUptimeMs - nowNote)));
                        return;
                    }
                    if (sessionState == SESSION_IDLE) {
                        startSessionForManuscript(activeManuscript, 0L, devId);
                        scheduleTick(TICK_ACTIVE_MS);
                        return;
                    }
                    if (sessionState == SESSION_STARTING
                            || sessionState == SESSION_WAITING_FILE_SHARE
                            || sessionState == SESSION_STARTING_CUEING) {
                        if (sessionState == SESSION_STARTING_CUEING
                                && !cueingStartScheduled
                                && !cueingStartRetriedOnce
                                && nowNote - stateEnteredUptimeMs > 2600L) {
                            cueingStartRetriedOnce = true;
                            sendCueingStartRequest(activeManuscript, devId);
                        } else if (nowNote - stateEnteredUptimeMs > HANDSHAKE_TIMEOUT_MS) {
                            if (handshakeAttempts < 2) {
                                startSessionForManuscript(activeManuscript, 0L, devId);
                            } else {
                                sessionState = SESSION_IDLE;
                                handshakeAttempts = 0;
                                backoffUntilUptimeMs = nowNote + BACKOFF_RETRY_MS;
                            }
                        }
                        scheduleTick(TICK_ACTIVE_MS);
                        return;
                    }
                    lastStatusSummary = "提词显示中：" + activeManuscript.title;
                    scheduleTick(TICK_IDLE_MS);
                    return;
                }
            }

            if (!cfg.syncLyricsToTeleprompter) {
                if (sessionState != SESSION_IDLE) {
                    stopSessionOnGlasses("配置已关闭");
                }
                lastStatusSummary = "未启用";
                return;
            }

            if (track == null || !track.hasSong()) {
                if (sessionState != SESSION_IDLE) {
                    stopSessionOnGlasses("无正在播放的白名单歌曲");
                }
                SystemMediaBridge.TrackInfo rawAny = SystemMediaBridge.getCurrentTrack(app);
                if (rawAny != null && rawAny.hasSong() && rawAny.isPlaying()) {
                    String appLabel = !rawAny.appName.isEmpty() ? rawAny.appName : rawAny.packageName;
                    lastStatusSummary = "已跳过非勾选应用：" + appLabel;
                } else {
                    lastStatusSummary = "等待选定音乐 App 播放…";
                }
                scheduleTick(TICK_IDLE_MS);
                return;
            }

            String trackKey = liveTrackKey;

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

            // Track OR displayMode changed?
            if (activeManuscript == null
                    || activeManuscript.isCustomNote
                    || !trackKey.equals(activeManuscript.trackKey)
                    || !desiredMode.equals(activeManuscript.displayMode)) {
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
                LyricsManuscript ms = getOrFetchManuscript(track, desiredMode);
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

            if (sessionState == SESSION_ACTIVE && activeManuscript != null && !activeManuscript.isCustomNote) {
                if (now < cueingReadyAtUptimeMs) {
                    scheduleTick(Math.min(TICK_ACTIVE_MS, Math.max(60L, cueingReadyAtUptimeMs - now)));
                    return;
                }
                int lineIdx = activeManuscript.findCueLineIndex(interpPosMs);
                if (lineIdx != lastSentLineIdx && (now - lastProgressSentUptimeMs >= MIN_PROGRESS_INTERVAL_MS)) {
                    sendProgressUpdatePacket(activeManuscript, lineIdx);
                    lastSentLineIdx = lineIdx;
                    lastProgressSentUptimeMs = now;
                    String curLine = (lineIdx >= 0 && lineIdx < activeManuscript.lines.length)
                            ? activeManuscript.lines[lineIdx] : "";
                    lastStatusSummary = "同步中(" + formatDisplayModeLabel(activeManuscript.displayMode) + ")："
                            + track.formatSongDisplay() + (curLine.isEmpty() ? "" : (" · " + curLine));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onWorkerTick error", t);
        }

        scheduleTick(TICK_ACTIVE_MS);
    }

    private static void startSessionForManuscript(LyricsManuscript ms, long posMs, String deviceId) {
        if (ms == null) return;
        int lineIdx = ms.isCustomNote ? 0 : ms.findCueLineIndex(posMs);

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
            CoreConnectHandles handles = resolveCoreConnectHandles();
            Object mgrInst = handles != null ? handles.resolveMgrInstance(lastSeenBridge) : null;
            if (handles != null && mgrInst != null) {
                fileShareDoneForAttempt = false;
                readyCode7ReceivedForAttempt = false;
                cueingStartScheduled = false;
                String taskId = handles.invokeFileShare(mgrInst, f, deviceId);
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
        int lineIdx = ms.isCustomNote ? 0 : Math.max(0, Math.min(lastSentLineIdx, ms.lineByteOffsets.length - 1));
        Application app = appContext;
        if (app != null && !ms.isCustomNote) {
            SystemMediaBridge.TrackInfo liveTrack = getCurrentLyricsTrack(app, null);
            if (liveTrack != null && liveTrack.hasSong()
                    && normalizeTrackKey(liveTrack.title, liveTrack.artist).equals(ms.trackKey)) {
                long targetMs = liveTrack.getInterpolatedPositionMs()
                        + (liveTrack.isPlaying() ? (HUD_COUNTDOWN * 1000L + LYRIC_LEAD_COMPENSATION_MS) : 0L);
                lineIdx = ms.findCueLineIndex(targetMs);
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
                                        SystemMediaBridge.TrackInfo t = getCurrentLyricsTrack(appContext, null);
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
                        SystemMediaBridge.TrackInfo t = getCurrentLyricsTrack(appContext, null);
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
                        if (activeManuscript.isCustomNote) {
                            customOverrideManuscript = null;
                            customOverrideBaselineTrackKey = "";
                            activeManuscript = null;
                        } else {
                            stoppedByGlassesTrackKey = activeManuscript.trackKey;
                        }
                    }
                    resetSessionVars();
                    lastStatusSummary = "已在眼镜端退出当前提词";
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
        int idx = ms.isCustomNote ? 0 : ms.findCueLineIndex(posMs);
        return ms.lineByteOffsets[idx];
    }

    // ==================== CoreConnect BLE Device & Sender (R8-Resilient Dynamic Reflection) ====================

    /**
     * Called at app startup (MOD-01) with the exact RayneoNetBridge class discovered by patch_apk.py
     * so CoreConnect reflection works at t=0ms regardless of R8 class renaming across versions.
     */
    public static void registerBridgeClassName(String className) {
        if (className == null) return;
        String trimmed = className.trim();
        if (trimmed.isEmpty()) return;
        if (!trimmed.equals(configuredBridgeClassName)) {
            configuredBridgeClassName = trimmed;
            cachedHandles = null;
        }
    }

    private static void captureBridgeInstance(Object bridgeObj) {
        if (bridgeObj == null) return;
        lastSeenBridge = bridgeObj;
        CoreConnectHandles cur = cachedHandles;
        if (cur != null && cur.bridgeClass != bridgeObj.getClass()) {
            cachedHandles = null;
        }
    }

    public static final class CoreConnectHandles {
        public final Class<?> bridgeClass;
        public final Field bridgeSingletonField;
        public final Field activeDeviceHolderField;
        public final Field holderIdField;
        public final Field holderConnectedField;
        public final Method bridgeGetMgrMethod;
        public final Class<?> mgrClass;
        public final Field mgrSingletonField;
        public final Field mgrCompanionField;
        public final Method mgrCompanionGetter;
        public final Method mgrGetDevicesMapMethod;
        public final Method mgrFileShareMethod;
        public final Object smallCoreConst;
        public final Method mgrSendBleMethod;
        public final Constructor<?> msgCtor10;
        public final Object teleprompterBizConst;
        public final Object normalPriorityConst;

        private volatile Class<?> cachedDevClass;
        private volatile Field cachedDevIdField;
        private volatile Method cachedDevConnectedMethod;

        CoreConnectHandles(
                Class<?> bridgeClass,
                Field bridgeSingletonField,
                Field activeDeviceHolderField,
                Field holderIdField,
                Field holderConnectedField,
                Method bridgeGetMgrMethod,
                Class<?> mgrClass,
                Field mgrSingletonField,
                Field mgrCompanionField,
                Method mgrCompanionGetter,
                Method mgrGetDevicesMapMethod,
                Method mgrFileShareMethod,
                Object smallCoreConst,
                Method mgrSendBleMethod,
                Constructor<?> msgCtor10,
                Object teleprompterBizConst,
                Object normalPriorityConst) {
            this.bridgeClass = bridgeClass;
            this.bridgeSingletonField = bridgeSingletonField;
            this.activeDeviceHolderField = activeDeviceHolderField;
            this.holderIdField = holderIdField;
            this.holderConnectedField = holderConnectedField;
            this.bridgeGetMgrMethod = bridgeGetMgrMethod;
            this.mgrClass = mgrClass;
            this.mgrSingletonField = mgrSingletonField;
            this.mgrCompanionField = mgrCompanionField;
            this.mgrCompanionGetter = mgrCompanionGetter;
            this.mgrGetDevicesMapMethod = mgrGetDevicesMapMethod;
            this.mgrFileShareMethod = mgrFileShareMethod;
            this.smallCoreConst = smallCoreConst;
            this.mgrSendBleMethod = mgrSendBleMethod;
            this.msgCtor10 = msgCtor10;
            this.teleprompterBizConst = teleprompterBizConst;
            this.normalPriorityConst = normalPriorityConst;
        }

        public Object resolveBridgeInstance(Object lastSeen) {
            if (lastSeen != null && bridgeClass.isInstance(lastSeen)) {
                return lastSeen;
            }
            if (bridgeSingletonField != null) {
                try {
                    return bridgeSingletonField.get(null);
                } catch (Throwable ignored) {}
            }
            return null;
        }

        public Object resolveMgrInstance(Object lastSeen) {
            try {
                Object b = resolveBridgeInstance(lastSeen);
                if (b != null && bridgeGetMgrMethod != null) {
                    Object inst = bridgeGetMgrMethod.invoke(b);
                    if (inst != null) return inst;
                }
            } catch (Throwable ignored) {}
            try {
                if (mgrSingletonField != null) {
                    Object inst = mgrSingletonField.get(null);
                    if (inst != null) return inst;
                }
            } catch (Throwable ignored) {}
            try {
                if (mgrCompanionField != null && mgrCompanionGetter != null) {
                    Object comp = mgrCompanionField.get(null);
                    if (comp != null) {
                        Object inst = mgrCompanionGetter.invoke(comp);
                        if (inst != null) return inst;
                    }
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public String invokeFileShare(Object mgrInst, File file, String deviceId) throws Exception {
            Object res = mgrFileShareMethod.invoke(mgrInst, file, deviceId, smallCoreConst, "");
            return res instanceof String ? (String) res : null;
        }

        public void invokeSendBle(
                Object mgrInst,
                byte[] payload,
                String msgId,
                String deviceId,
                boolean needAck,
                Function2 cb) throws Exception {
            Object msg = msgCtor10.newInstance(
                    payload,
                    msgId,
                    deviceId,
                    teleprompterBizConst,
                    null,
                    normalPriorityConst,
                    Boolean.valueOf(needAck),
                    Boolean.FALSE,
                    null,
                    null);
            mgrSendBleMethod.invoke(mgrInst, msg, cb);
        }

        public Map<?, ?> getDevicesMap() {
            if (mgrGetDevicesMapMethod == null) return null;
            try {
                Object res = mgrGetDevicesMapMethod.invoke(null);
                if (res instanceof Map) {
                    return (Map<?, ?>) res;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public boolean inspectDeviceEntry(Object devVal, String[] outId, boolean[] outConnected) {
            if (devVal == null) return false;
            Class<?> dc = devVal.getClass();
            Field idF = cachedDevIdField;
            Method connM = cachedDevConnectedMethod;
            if (dc != cachedDevClass || idF == null || connM == null) {
                idF = null;
                connM = null;
                for (Field f : dc.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic() && f.getType() == String.class) {
                        f.setAccessible(true);
                        idF = f;
                        break;
                    }
                }
                for (Method m : dc.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes().length == 0
                            && m.getReturnType() == boolean.class) {
                        m.setAccessible(true);
                        connM = m;
                        break;
                    }
                }
                if (idF != null && connM != null) {
                    cachedDevIdField = idF;
                    cachedDevConnectedMethod = connM;
                    cachedDevClass = dc;
                }
            }
            if (idF == null || connM == null) return false;
            try {
                Object rawId = idF.get(devVal);
                Object rawConn = connM.invoke(devVal);
                outId[0] = rawId instanceof String ? safe((String) rawId) : "";
                outConnected[0] = Boolean.TRUE.equals(rawConn);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static Object findEnumConst(Class<?> enumCls, String name) {
        if (enumCls == null || !enumCls.isEnum()) return null;
        Object[] constants = enumCls.getEnumConstants();
        if (constants == null) return null;
        for (Object c : constants) {
            if (c instanceof Enum && name.equals(((Enum<?>) c).name())) {
                return c;
            }
        }
        return null;
    }

    public static CoreConnectHandles resolveCoreConnectHandlesForClass(Class<?> bridgeCls) {
        if (bridgeCls == null) return null;
        try {
            Field bridgeSingleton = null;
            Field holderField = null;
            Field holderId = null;
            Field holderConn = null;

            for (Field f : bridgeCls.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod)) {
                    if (f.getType() == bridgeCls && bridgeSingleton == null) {
                        f.setAccessible(true);
                        bridgeSingleton = f;
                    }
                } else if (!f.isSynthetic()) {
                    Class<?> ft = f.getType();
                    String fn = ft.getName();
                    if (!ft.isPrimitive() && !ft.isArray() && ft != bridgeCls
                            && !fn.startsWith("java.") && !fn.startsWith("android.")
                            && !fn.startsWith("kotlinx.") && !fn.startsWith("androidx.")) {
                        List<Field> instFields = new ArrayList<>();
                        for (Field hf : ft.getDeclaredFields()) {
                            if (!Modifier.isStatic(hf.getModifiers()) && !hf.isSynthetic()) {
                                instFields.add(hf);
                            }
                        }
                        if (instFields.size() == 3
                                && instFields.get(0).getType() == String.class
                                && instFields.get(1).getType() == String.class
                                && instFields.get(2).getType() == boolean.class) {
                            f.setAccessible(true);
                            instFields.get(0).setAccessible(true);
                            instFields.get(2).setAccessible(true);
                            holderField = f;
                            holderId = instFields.get(0);
                            holderConn = instFields.get(2);
                        }
                    }
                }
            }

            Method bridgeGetMgr = null;
            Class<?> mgrCls = null;
            Method mgrFileShare = null;
            Object smallCore = null;
            Method mgrSendBle = null;

            for (Method bm : bridgeCls.getDeclaredMethods()) {
                if (Modifier.isStatic(bm.getModifiers()) || bm.getParameterTypes().length != 0) {
                    continue;
                }
                Class<?> cand = bm.getReturnType();
                if (cand.isPrimitive() || cand.isInterface() || cand.isArray() || cand.isEnum()) {
                    continue;
                }
                String cn = cand.getName();
                if (cn.startsWith("java.") || cn.startsWith("android.")) {
                    continue;
                }
                Method foundFileShare = null;
                Object foundSmallCore = null;
                Method foundSendBle = null;
                for (Method mm : cand.getDeclaredMethods()) {
                    if (Modifier.isStatic(mm.getModifiers())) continue;
                    Class<?>[] pt = mm.getParameterTypes();
                    if (pt.length == 4
                            && pt[0] == File.class
                            && pt[1] == String.class
                            && pt[2].isEnum()
                            && pt[3] == String.class
                            && mm.getReturnType() == String.class) {
                        Object sc = findEnumConst(pt[2], "SmallCore");
                        if (sc != null) {
                            foundFileShare = mm;
                            foundSmallCore = sc;
                        }
                    } else if (pt.length == 2
                            && !pt[0].isPrimitive()
                            && pt[1] == Function2.class
                            && mm.getReturnType() == void.class) {
                        foundSendBle = mm;
                    }
                }
                if (foundFileShare != null && foundSendBle != null) {
                    bm.setAccessible(true);
                    foundFileShare.setAccessible(true);
                    foundSendBle.setAccessible(true);
                    bridgeGetMgr = bm;
                    mgrCls = cand;
                    mgrFileShare = foundFileShare;
                    smallCore = foundSmallCore;
                    mgrSendBle = foundSendBle;
                    break;
                }
            }

            if (mgrCls == null || mgrFileShare == null || mgrSendBle == null) {
                safeLogW("CoreConnectHandles: failed to locate CoreConnect manager on " + bridgeCls.getName());
                return null;
            }

            Field mgrSingleton = null;
            Field mgrCompField = null;
            Method mgrCompGetter = null;
            for (Field mf : mgrCls.getDeclaredFields()) {
                if (!Modifier.isStatic(mf.getModifiers())) continue;
                if (mf.getType() == mgrCls && mgrSingleton == null) {
                    mf.setAccessible(true);
                    mgrSingleton = mf;
                } else if (!mf.getType().isPrimitive() && mf.getType() != mgrCls && mgrCompGetter == null) {
                    for (Method cm : mf.getType().getDeclaredMethods()) {
                        if (!Modifier.isStatic(cm.getModifiers())
                                && cm.getParameterTypes().length == 0
                                && cm.getReturnType() == mgrCls) {
                            mf.setAccessible(true);
                            cm.setAccessible(true);
                            mgrCompField = mf;
                            mgrCompGetter = cm;
                            break;
                        }
                    }
                }
            }

            Method mgrDevicesMap = null;
            for (Method mm : mgrCls.getDeclaredMethods()) {
                if (Modifier.isStatic(mm.getModifiers())
                        && mm.getParameterTypes().length == 0
                        && Map.class.isAssignableFrom(mm.getReturnType())) {
                    mm.setAccessible(true);
                    mgrDevicesMap = mm;
                    break;
                }
            }

            Class<?> msgCls = mgrSendBle.getParameterTypes()[0];
            Constructor<?> msgCtor = null;
            Object teleprompterBiz = null;
            Object normalPrio = null;
            for (Constructor<?> c : msgCls.getDeclaredConstructors()) {
                if (c.isSynthetic()) continue;
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 10
                        && pt[0] == byte[].class
                        && pt[1] == String.class
                        && pt[2] == String.class
                        && pt[3].isEnum()
                        && pt[5].isEnum()
                        && pt[6] == boolean.class
                        && pt[7] == boolean.class
                        && pt[9] == Function2.class) {
                    Object biz = findEnumConst(pt[3], "TELEPROMPTER");
                    Object prio = findEnumConst(pt[5], "NORMAL");
                    if (biz != null && prio != null) {
                        c.setAccessible(true);
                        msgCtor = c;
                        teleprompterBiz = biz;
                        normalPrio = prio;
                        break;
                    }
                }
            }

            if (msgCtor == null) {
                safeLogW("CoreConnectHandles: failed to locate 10-arg BLE message ctor on " + msgCls.getName());
                return null;
            }

            safeLogI("[BRIDGE-RESOLVED] bridge=" + bridgeCls.getName()
                    + " mgr=" + mgrCls.getName()
                    + " msg=" + msgCls.getName()
                    + " biz=" + teleprompterBiz.getClass().getName()
                    + " core=" + smallCore.getClass().getName());

            return new CoreConnectHandles(
                    bridgeCls,
                    bridgeSingleton,
                    holderField,
                    holderId,
                    holderConn,
                    bridgeGetMgr,
                    mgrCls,
                    mgrSingleton,
                    mgrCompField,
                    mgrCompGetter,
                    mgrDevicesMap,
                    mgrFileShare,
                    smallCore,
                    mgrSendBle,
                    msgCtor,
                    teleprompterBiz,
                    normalPrio);
        } catch (Throwable t) {
            safeLogW("resolveCoreConnectHandlesForClass error: " + t);
            return null;
        }
    }

    private static CoreConnectHandles resolveCoreConnectHandles() {
        CoreConnectHandles h = cachedHandles;
        if (h != null) return h;
        synchronized (LyricsTeleprompterBridge.class) {
            h = cachedHandles;
            if (h != null) return h;
            Class<?> bridgeCls = null;
            Object seen = lastSeenBridge;
            if (seen != null) {
                bridgeCls = seen.getClass();
            } else {
                try {
                    Application app = appContext;
                    ClassLoader cl = app != null
                            ? app.getClassLoader()
                            : LyricsTeleprompterBridge.class.getClassLoader();
                    bridgeCls = Class.forName(configuredBridgeClassName, false, cl);
                } catch (Throwable ignored) {}
            }
            if (bridgeCls != null) {
                h = resolveCoreConnectHandlesForClass(bridgeCls);
                cachedHandles = h;
            }
            return h;
        }
    }

    private static String resolveConnectedGlassesDeviceId() {
        CoreConnectHandles handles = resolveCoreConnectHandles();
        String zId = "";
        boolean zFlag = false;
        if (handles != null) {
            try {
                Object b = handles.resolveBridgeInstance(lastSeenBridge);
                if (b != null && handles.activeDeviceHolderField != null) {
                    Object z = handles.activeDeviceHolderField.get(b);
                    if (z != null) {
                        Object rawZId = handles.holderIdField.get(z);
                        Object rawZFlag = handles.holderConnectedField.get(z);
                        zId = rawZId instanceof String ? safe((String) rawZId) : "";
                        zFlag = Boolean.TRUE.equals(rawZFlag);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Iterate CoreConnect device map: map key is canonical deviceId, dev.b() is live BLE/SPP auth state!
        String wConnectedId = "";
        boolean sawDeviceEntries = false;
        StringBuilder mapDiag = new StringBuilder();
        if (handles != null) {
            try {
                Map<?, ?> devices = handles.getDevicesMap();
                if (devices != null && !devices.isEmpty()) {
                    String[] idHolder = new String[1];
                    boolean[] connHolder = new boolean[1];
                    for (Map.Entry<?, ?> en : devices.entrySet()) {
                        Object val = en.getValue();
                        if (handles.inspectDeviceEntry(val, idHolder, connHolder)) {
                            sawDeviceEntries = true;
                            String keyId = (en.getKey() instanceof String) ? safe((String) en.getKey()) : "";
                            String fieldId = idHolder[0];
                            String devId = !fieldId.isEmpty() ? fieldId : keyId;
                            boolean conn = connHolder[0];
                            mapDiag.append(devId).append("(").append(conn).append(");");
                            if (conn && !devId.isEmpty() && wConnectedId.isEmpty()) {
                                wConnectedId = devId;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        String chosen = "";
        if (!wConnectedId.isEmpty()) {
            chosen = wConnectedId;
        } else if (!sawDeviceEntries) {
            // CoreConnect map not initialized yet -> fall back to activeDeviceId (bridge.z.a) or lastSeenDeviceId
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
            CoreConnectHandles handles = resolveCoreConnectHandles();
            Object mgrInst = handles != null ? handles.resolveMgrInstance(lastSeenBridge) : null;
            if (handles == null || mgrInst == null) {
                Log.w(TAG, "sendBleTeleprompter: mgrInst is NULL (handles=" + handles + ", lastSeenBridge=" + lastSeenBridge + ")");
                return false;
            }
            final String msgId = "lyr_" + MSG_ID_GEN.incrementAndGet();
            handles.invokeSendBle(mgrInst, payload, msgId, deviceId, needAck, new Function2() {
                @Override
                public Object invoke(Object msgObj, Object errObj) {
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
        String mode = appContext != null
                ? CustomAiConfig.load(appContext).lyricsDisplayMode
                : MODE_BILINGUAL;
        return getOrFetchManuscript(track, mode);
    }

    private static LyricsManuscript getOrFetchManuscript(SystemMediaBridge.TrackInfo track, String rawMode) {
        String mode = CustomAiConfig.normalizeLyricsMode(rawMode);
        String cacheKey = normalizeTrackKey(track.title, track.artist) + "|mode=" + mode;
        synchronized (CACHE_LOCK) {
            LyricsManuscript cached = MANUSCRIPT_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        LyricsManuscript fetched = fetchLyricsManuscriptOnline(
                track.title, track.artist, track.durationMs, track.packageName, mode, null, null);
        if (fetched.hasSyncedLyrics) {
            synchronized (CACHE_LOCK) {
                MANUSCRIPT_CACHE.put(cacheKey, fetched);
            }
        }
        return fetched;
    }

    /**
     * 5-arg overload preserved for unit test compatibility (delegates with durationMs = 0L, MODE_ORIGINAL).
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            String playerPkg,
            String netEaseBaseOverride,
            String qqBaseOverride
    ) {
        return fetchLyricsManuscriptOnline(
                rawTitle, rawArtist, 0L, playerPkg, MODE_ORIGINAL, netEaseBaseOverride, qqBaseOverride);
    }

    /**
     * 6-arg overload preserved for unit test compatibility (defaults to MODE_ORIGINAL when appContext == null).
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            long durationMs,
            String playerPkg,
            String netEaseBaseOverride,
            String qqBaseOverride
    ) {
        String mode = appContext != null
                ? CustomAiConfig.load(appContext).lyricsDisplayMode
                : MODE_ORIGINAL;
        return fetchLyricsManuscriptOnline(
                rawTitle, rawArtist, durationMs, playerPkg, mode, netEaseBaseOverride, qqBaseOverride);
    }

    /**
     * Fetches multi-track LRC (original + Chinese translation + Romaji) from NetEase Cloud Music
     * and/or QQ Music, aligns timestamps, pre-wraps lines to 34 half-width units, and returns a
     * ready-to-stream LyricsManuscript.
     */
    public static LyricsManuscript fetchLyricsManuscriptOnline(
            String rawTitle,
            String rawArtist,
            long durationMs,
            String playerPkg,
            String displayMode,
            String netEaseBaseOverride,
            String qqBaseOverride
    ) {
        String title = safe(rawTitle);
        String artist = safe(rawArtist);
        String mode = CustomAiConfig.normalizeLyricsMode(displayMode);
        boolean preferQq = SystemMediaBridge.PKG_QQMUSIC.equals(playerPkg);
        boolean wantsRomaji = MODE_ROMAJI.equals(mode) || MODE_TRILINGUAL.equals(mode);

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
            LrcBundle qqBundle = fetchQqLrcBundle(title, artist, durationMs, hintedQqMid, qqBaseOverride);
            if (qqBundle.hasMainLrc() && wantsRomaji && qqBundle.romalrc.isEmpty() && containsJapaneseKana(qqBundle.lrc)) {
                // Enrich QQ Music Japanese track with NetEase Kanji-resolved romalrc when available!
                LrcBundle neEnrich = fetchNetEaseLrcBundle(title, artist, durationMs, hintedNeId, netEaseBaseOverride);
                if (!neEnrich.romalrc.isEmpty()) {
                    qqBundle = new LrcBundle(
                            qqBundle.lrc,
                            !qqBundle.tlyric.isEmpty() ? qqBundle.tlyric : neEnrich.tlyric,
                            neEnrich.romalrc);
                }
            }
            LyricsManuscript qqMs = parseLrcBundleAndBuildManuscript(title, artist, qqBundle, mode, "QQ音乐");
            if (qqMs.hasSyncedLyrics) return qqMs;

            LrcBundle neBundle = fetchNetEaseLrcBundle(title, artist, durationMs, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcBundleAndBuildManuscript(title, artist, neBundle, mode, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;
            return qqMs;
        } else {
            LrcBundle neBundle = fetchNetEaseLrcBundle(title, artist, durationMs, hintedNeId, netEaseBaseOverride);
            LyricsManuscript neMs = parseLrcBundleAndBuildManuscript(title, artist, neBundle, mode, "网易云音乐");
            if (neMs.hasSyncedLyrics) return neMs;

            LrcBundle qqBundle = fetchQqLrcBundle(title, artist, durationMs, hintedQqMid, qqBaseOverride);
            LyricsManuscript qqMs = parseLrcBundleAndBuildManuscript(title, artist, qqBundle, mode, "QQ音乐");
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
        return fetchNetEaseLrcBundle(title, artist, durationMs, knownSongId, baseUrlOverride).lrc;
    }

    public static LrcBundle fetchNetEaseLrcBundle(
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
            if (candidateIds.isEmpty()) return new LrcBundle("", "", "");

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://music.163.com/");

            int tries = Math.min(3, candidateIds.size());
            for (int i = 0; i < tries; i++) {
                String songId = candidateIds.get(i);
                if (songId.isEmpty()) continue;
                // tv=-1 requests translated Chinese lyrics (tlyric); rv=-1 requests Romaji lyrics (romalrc)
                String url = base + "/api/song/lyric?id=" + urlEncode(songId) + "&lv=1&kv=1&tv=-1&rv=-1";
                String body = httpGet(url, headers);
                if (body.isEmpty()) continue;

                Map<String, Object> root = JsonMini.parseObject(body);
                if (Boolean.TRUE.equals(root.get("nolyric")) || Boolean.TRUE.equals(root.get("uncollected"))) {
                    continue;
                }
                Map<String, Object> tlyricObj = JsonMini.getMap(root, "tlyric");
                String tlyric = safe(JsonMini.getString(tlyricObj, "lyric"));

                Map<String, Object> romaObj = JsonMini.getMap(root, "romalrc");
                String romalrc = safe(JsonMini.getString(romaObj, "lyric"));

                Map<String, Object> lrcObj = JsonMini.getMap(root, "lrc");
                String lyric = safe(JsonMini.getString(lrcObj, "lyric"));
                if (!lyric.isEmpty() && lyric.contains("[")) {
                    safeLogI("Resolved NetEase LRC songId=" + songId + " for " + title + " - " + artist
                            + " (hasTrans=" + !tlyric.isEmpty() + ", hasRoma=" + !romalrc.isEmpty() + ")");
                    return new LrcBundle(lyric, tlyric, romalrc);
                }

                Map<String, Object> klyricObj = JsonMini.getMap(root, "klyric");
                String klyric = safe(JsonMini.getString(klyricObj, "lyric"));
                if (!klyric.isEmpty() && klyric.contains("[")) {
                    safeLogI("Resolved NetEase klyric songId=" + songId + " for " + title + " - " + artist);
                    return new LrcBundle(klyric, tlyric, romalrc);
                }
            }
            return new LrcBundle("", "", "");
        } catch (Throwable t) {
            safeLogW("fetchNetEaseLrcBundle error: " + t.getMessage());
            return new LrcBundle("", "", "");
        }
    }

    public static String fetchQqLrc(
            String title, String artist, String knownSongMid, String baseUrlOverride) {
        return fetchQqLrc(title, artist, 0L, knownSongMid, baseUrlOverride);
    }

    public static String fetchQqLrc(
            String title, String artist, long durationMs, String knownSongMid, String baseUrlOverride) {
        return fetchQqLrcBundle(title, artist, durationMs, knownSongMid, baseUrlOverride).lrc;
    }

    public static LrcBundle fetchQqLrcBundle(
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
            if (candidateMids.isEmpty()) return new LrcBundle("", "", "");

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
                String decodedLyric = decodeQqLyricField(safe(JsonMini.getString(root, "lyric")));
                if (decodedLyric.isEmpty() || !decodedLyric.contains("[")) {
                    continue;
                }
                String decodedTrans = decodeQqLyricField(safe(JsonMini.getString(root, "trans")));
                safeLogI("Resolved QQ LRC mid=" + mid + " for " + title + " - " + artist
                        + " (hasTrans=" + !decodedTrans.isEmpty() + ")");
                return new LrcBundle(decodedLyric, decodedTrans, "");
            }
            return new LrcBundle("", "", "");
        } catch (Throwable t) {
            safeLogW("fetchQqLrcBundle error: " + t.getMessage());
            return new LrcBundle("", "", "");
        }
    }

    private static String decodeQqLyricField(String rawField) {
        if (rawField == null || rawField.isEmpty()) return "";
        String val = rawField;
        if (!val.contains("[") && !val.contains("&#")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(val.replaceAll("\\s+", ""));
                val = new String(decoded, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        }
        return decodeHtmlEntities(val);
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
        return parseLrcBundleAndBuildManuscript(
                title, artist, new LrcBundle(rawLrc, "", ""), MODE_ORIGINAL, sourcePlatform);
    }

    public static LyricsManuscript parseLrcBundleAndBuildManuscript(
            String title,
            String artist,
            LrcBundle bundle,
            String displayMode,
            String sourcePlatform
    ) {
        String mode = CustomAiConfig.normalizeLyricsMode(displayMode);
        boolean wantsTrans = MODE_BILINGUAL.equals(mode) || MODE_TRILINGUAL.equals(mode);
        boolean wantsRoma = MODE_ROMAJI.equals(mode) || MODE_TRILINGUAL.equals(mode);

        String rawCleanTitle = sanitizeBmpText(safe(title)).trim();
        String cleanTitle = rawCleanTitle.isEmpty() ? "未知歌曲" : rawCleanTitle;
        String cleanArtist = sanitizeBmpText(safe(artist)).trim();
        String trackKey = normalizeTrackKey(safe(title), safe(artist));
        String did = computeLyricsDid(trackKey);

        String headerRaw = cleanArtist.isEmpty()
                ? ("[歌词] " + cleanTitle)
                : ("[歌词] " + cleanTitle + " - " + cleanArtist);
        List<String> headerLines = wrapLineToHudWidth(headerRaw, MAX_LINE_HALF_WIDTH);

        String mainLrc = bundle != null ? bundle.lrc : "";
        List<TimedLine> parsed = parseRawLrcLines(mainLrc);
        List<TimedLine> parsedTrans = (wantsTrans && bundle != null && !bundle.tlyric.isEmpty())
                ? parseRawLrcLines(bundle.tlyric) : Collections.<TimedLine>emptyList();
        List<TimedLine> parsedRoma = (wantsRoma && bundle != null && !bundle.romalrc.isEmpty())
                ? parseRawLrcLines(bundle.romalrc) : Collections.<TimedLine>emptyList();

        String[] alignedRoma = wantsRoma
                ? alignCompanionLines(parsed, parsedRoma, true) : new String[parsed.size()];
        String[] alignedTrans = wantsTrans
                ? alignCompanionLines(parsed, parsedTrans, false) : new String[parsed.size()];

        List<String> finalLines = new ArrayList<>();
        List<Long> finalTimes = new ArrayList<>();
        List<Boolean> finalCompanions = new ArrayList<>();
        boolean hasSynced = !parsed.isEmpty();

        if (!hasSynced) {
            for (String hl : headerLines) {
                finalLines.add(hl);
                finalTimes.add(0L);
                finalCompanions.add(Boolean.FALSE);
            }
            finalLines.add("暂无滚动歌词");
            finalTimes.add(1000L);
            finalCompanions.add(Boolean.FALSE);
        } else {
            long firstTime = parsed.get(0).timeMs;
            if (firstTime >= 600L) {
                for (int i = 0; i < headerLines.size(); i++) {
                    finalLines.add(headerLines.get(i));
                    finalTimes.add(Math.min(firstTime - 100L, i * 200L));
                    finalCompanions.add(Boolean.FALSE);
                }
            }

            for (int i = 0; i < parsed.size(); i++) {
                TimedLine cur = parsed.get(i);
                long nextTime = (i + 1 < parsed.size()) ? parsed.get(i + 1).timeMs : (cur.timeMs + 4000L);
                List<String> wrapped = wrapLineToHudWidth(cur.text, MAX_LINE_HALF_WIDTH);

                long lastOrigTime = cur.timeMs;
                if (wrapped.size() == 1) {
                    finalLines.add(wrapped.get(0));
                    finalTimes.add(cur.timeMs);
                    finalCompanions.add(Boolean.FALSE);
                } else {
                    long span = Math.max(600L, Math.min(6000L, nextTime - cur.timeMs));
                    int k = wrapped.size();
                    for (int j = 0; j < k; j++) {
                        long subT = cur.timeMs + (span * j) / k;
                        finalLines.add(wrapped.get(j));
                        finalTimes.add(subT);
                        finalCompanions.add(Boolean.FALSE);
                        lastOrigTime = subT;
                    }
                }

                // 1) Append Romaji companion line(s) right below original line
                if (wantsRoma) {
                    String romaText = safe(alignedRoma[i]);
                    if (romaText.isEmpty() && containsPureTransliterableKana(cur.text)) {
                        romaText = transliterateJapaneseKanaToRomaji(cur.text);
                    }
                    if (!romaText.isEmpty() && !romaText.equalsIgnoreCase(cur.text)) {
                        List<String> romaWrapped = wrapLineToHudWidth("~ " + romaText, MAX_LINE_HALF_WIDTH);
                        for (String rl : romaWrapped) {
                            finalLines.add(rl);
                            finalTimes.add(lastOrigTime);
                            finalCompanions.add(Boolean.TRUE);
                        }
                    }
                }

                // 2) Append Chinese translation companion line(s)
                if (wantsTrans) {
                    String transText = safe(alignedTrans[i]);
                    if (!transText.isEmpty() && !transText.equalsIgnoreCase(cur.text)) {
                        List<String> transWrapped = wrapLineToHudWidth("· " + transText, MAX_LINE_HALF_WIDTH);
                        for (String tl : transWrapped) {
                            finalLines.add(tl);
                            finalTimes.add(lastOrigTime);
                            finalCompanions.add(Boolean.TRUE);
                        }
                    }
                }
            }
        }

        return packManuscript(
                trackKey, did, cleanTitle, cleanArtist,
                finalLines, finalTimes, finalCompanions,
                hasSynced, sourcePlatform, mode, false);
    }

    /**
     * Builds a static teleprompter manuscript from plain text (for Skill push_to_teleprompter or clipboard push).
     */
    public static LyricsManuscript buildCustomNoteManuscript(String rawTitle, String rawContent) {
        String cleanTitle = sanitizeBmpText(safe(rawTitle)).trim();
        if (cleanTitle.isEmpty()) cleanTitle = "AI 提词便签";
        String body = sanitizeBmpText(rawContent == null ? "" : rawContent)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (body.isEmpty()) body = cleanTitle;

        List<String> finalLines = new ArrayList<>();
        List<Long> finalTimes = new ArrayList<>();
        List<Boolean> finalCompanions = new ArrayList<>();

        for (String hl : wrapLineToHudWidth("[提词] " + cleanTitle, MAX_LINE_HALF_WIDTH)) {
            finalLines.add(hl);
            finalTimes.add(0L);
            finalCompanions.add(Boolean.FALSE);
        }

        String[] paragraphs = body.split("\n");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            for (String wl : wrapLineToHudWidth(trimmed, MAX_LINE_HALF_WIDTH)) {
                finalLines.add(wl);
                finalTimes.add(0L);
                finalCompanions.add(Boolean.FALSE);
            }
        }

        String noteKey = "custom_note|" + System.currentTimeMillis();
        return packManuscript(
                noteKey, LYRICS_SLOT_DID, cleanTitle, "",
                finalLines, finalTimes, finalCompanions,
                true, "CustomSkill", MODE_ORIGINAL, true);
    }

    private static LyricsManuscript packManuscript(
            String trackKey,
            String did,
            String cleanTitle,
            String cleanArtist,
            List<String> finalLines,
            List<Long> finalTimes,
            List<Boolean> finalCompanions,
            boolean hasSynced,
            String sourcePlatform,
            String mode,
            boolean isCustomNote
    ) {
        int n = finalLines.size();
        String[] linesArr = finalLines.toArray(new String[0]);
        long[] timesArr = new long[n];
        int[] offsetsArr = new int[n];
        boolean[] compArr = new boolean[n];

        ByteArrayOutputStream bos = new ByteArrayOutputStream(n * 40);
        for (int i = 0; i < n; i++) {
            timesArr[i] = finalTimes.get(i);
            compArr[i] = i < finalCompanions.size() && Boolean.TRUE.equals(finalCompanions.get(i));
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
                compArr,
                utf8,
                checksum,
                hasSynced,
                sourcePlatform,
                mode,
                isCustomNote
        );
    }

    private static String[] alignCompanionLines(
            List<TimedLine> origLines, List<TimedLine> compLines, boolean isRomaji) {
        String[] out = new String[origLines.size()];
        if (origLines.isEmpty() || compLines == null || compLines.isEmpty()) {
            return out;
        }
        boolean[] used = new boolean[compLines.size()];
        // Pass 1: exact timestamp match (within 20ms)
        for (int i = 0; i < origLines.size(); i++) {
            long ot = origLines.get(i).timeMs;
            for (int j = 0; j < compLines.size(); j++) {
                if (used[j]) continue;
                TimedLine cl = compLines.get(j);
                if (Math.abs(cl.timeMs - ot) <= 20L) {
                    if (!isBoilerplateCompanionLine(cl.text, isRomaji)) {
                        out[i] = cl.text;
                    }
                    used[j] = true;
                    break;
                }
            }
        }
        // Pass 2: tolerant match (<= 450ms) when orig[i] is mutually closest to comp[j]
        for (int i = 0; i < origLines.size(); i++) {
            if (out[i] != null) continue;
            long ot = origLines.get(i).timeMs;
            int bestJ = -1;
            long bestDiff = 451L;
            for (int j = 0; j < compLines.size(); j++) {
                if (used[j]) continue;
                long d = Math.abs(compLines.get(j).timeMs - ot);
                if (d < bestDiff) {
                    bestDiff = d;
                    bestJ = j;
                }
            }
            if (bestJ >= 0) {
                long ct = compLines.get(bestJ).timeMs;
                boolean closerOtherOrig = false;
                for (int k = 0; k < origLines.size(); k++) {
                    if (k != i && out[k] == null && Math.abs(origLines.get(k).timeMs - ct) < bestDiff) {
                        closerOtherOrig = true;
                        break;
                    }
                }
                if (!closerOtherOrig) {
                    String txt = compLines.get(bestJ).text;
                    if (!isBoilerplateCompanionLine(txt, isRomaji)) {
                        out[i] = txt;
                    }
                    used[bestJ] = true;
                }
            }
        }
        return out;
    }

    private static boolean isBoilerplateCompanionLine(String text, boolean isRomaji) {
        String s = safe(text);
        if (s.isEmpty() || "//".equals(s) || "/".equals(s)) return true;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("by:") || low.startsWith("by：")
                || low.contains("翻译贡献") || low.contains("罗马音贡献")
                || low.contains("歌词翻译：") || low.contains("校对：")) {
            return true;
        }
        return false;
    }

    public static boolean containsJapaneseKana(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3040' && c <= '\u309F') || (c >= '\u30A0' && c <= '\u30FF')) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPureTransliterableKana(String s) {
        if (!containsJapaneseKana(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                // Contains CJK Kanji -> avoid partial half-Kanji romaji when online romalrc wasn't available
                return false;
            }
        }
        return true;
    }

    private static final Map<String, String> KANA_DIGRAPHS = new HashMap<>();
    private static final Map<Character, String> KANA_MONOGRAPHS = new HashMap<>();

    static {
        String[][] di = new String[][]{
                {"きゃ", "kya"}, {"きゅ", "kyu"}, {"きょ", "kyo"},
                {"しゃ", "sha"}, {"しゅ", "shu"}, {"しょ", "sho"},
                {"ちゃ", "cha"}, {"ちゅ", "chu"}, {"ちょ", "cho"},
                {"にゃ", "nya"}, {"にゅ", "nyu"}, {"にょ", "nyo"},
                {"ひゃ", "hya"}, {"ひゅ", "hyu"}, {"ひょ", "hyo"},
                {"みゃ", "mya"}, {"みゅ", "myu"}, {"みょ", "myo"},
                {"りゃ", "rya"}, {"りゅ", "ryu"}, {"りょ", "ryo"},
                {"ぎゃ", "gya"}, {"ぎゅ", "gyu"}, {"ぎょ", "gyo"},
                {"じゃ", "ja"},  {"じゅ", "ju"},  {"じょ", "jo"},
                {"ぢゃ", "ja"},  {"ぢゅ", "ju"},  {"ぢょ", "jo"},
                {"びゃ", "bya"}, {"びゅ", "byu"}, {"びょ", "byo"},
                {"ぴゃ", "pya"}, {"ぴゅ", "pyu"}, {"ぴょ", "pyo"},
                {"ふぁ", "fa"},  {"ふぃ", "fi"},  {"ふぇ", "fe"},  {"ふぉ", "fo"},
                {"てぃ", "ti"},  {"でぃ", "di"},  {"とぅ", "tu"},  {"どぅ", "du"},
                {"うぃ", "wi"},  {"うぇ", "we"},  {"うぉ", "wo"},
                {"しぇ", "she"}, {"ちぇ", "che"}, {"じぇ", "je"},
                {"ヴぁ", "va"},  {"ヴぃ", "vi"},  {"ヴぇ", "ve"},  {"ヴぉ", "vo"}
        };
        for (String[] p : di) {
            KANA_DIGRAPHS.put(p[0], p[1]);
        }
        String[][] mono = new String[][]{
                {"あ", "a"},  {"い", "i"},   {"う", "u"},  {"え", "e"},  {"お", "o"},
                {"か", "ka"}, {"き", "ki"},  {"く", "ku"}, {"け", "ke"}, {"こ", "ko"},
                {"さ", "sa"}, {"し", "shi"}, {"す", "su"}, {"せ", "se"}, {"そ", "so"},
                {"た", "ta"}, {"ち", "chi"}, {"つ", "tsu"},{"て", "te"}, {"と", "to"},
                {"な", "na"}, {"に", "ni"},  {"ぬ", "nu"}, {"ね", "ne"}, {"の", "no"},
                {"は", "ha"}, {"ひ", "hi"},  {"ふ", "fu"}, {"へ", "he"}, {"ほ", "ho"},
                {"ま", "ma"}, {"み", "mi"},  {"む", "mu"}, {"め", "me"}, {"も", "mo"},
                {"や", "ya"}, {"ゆ", "yu"},  {"よ", "yo"},
                {"ら", "ra"}, {"り", "ri"},  {"る", "ru"}, {"れ", "re"}, {"ろ", "ro"},
                {"わ", "wa"}, {"ゐ", "wi"},  {"ゑ", "we"}, {"を", "wo"}, {"ん", "n"},
                {"が", "ga"}, {"ぎ", "gi"},  {"ぐ", "gu"}, {"げ", "ge"}, {"ご", "go"},
                {"ざ", "za"}, {"じ", "ji"},  {"ず", "zu"}, {"ぜ", "ze"}, {"ぞ", "zo"},
                {"だ", "da"}, {"ぢ", "ji"},  {"づ", "zu"}, {"で", "de"}, {"ど", "do"},
                {"ば", "ba"}, {"び", "bi"},  {"ぶ", "bu"}, {"べ", "be"}, {"ぼ", "bo"},
                {"ぱ", "pa"}, {"ぴ", "pi"},  {"ぷ", "pu"}, {"ぺ", "pe"}, {"ぽ", "po"},
                {"ぁ", "a"},  {"ぃ", "i"},   {"ぅ", "u"},  {"ぇ", "e"},  {"ぉ", "o"},
                {"ゃ", "ya"}, {"ゅ", "yu"},  {"ょ", "yo"}, {"ゎ", "wa"}, {"ヴ", "vu"}
        };
        for (String[] p : mono) {
            KANA_MONOGRAPHS.put(p[0].charAt(0), p[1]);
        }
    }

    /**
     * Converts Japanese Hiragana/Katakana phrases into clean Hepburn Romaji (handling digraphs,
     * sokuon っ/ッ gemination, and chōonpu ー long vowels).
     */
    public static String transliterateJapaneseKanaToRomaji(String input) {
        if (input == null || input.isEmpty()) return "";
        // Normalize Katakana (\u30A1..\u30F6) -> Hiragana (\u3041..\u3096), keeping ヴ (\u30F4) and ー (\u30FC)
        StringBuilder norm = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '\u30A1' && c <= '\u30F3') {
                norm.append((char) (c - 0x60));
            } else {
                norm.append(c);
            }
        }
        String s = norm.toString();
        StringBuilder out = new StringBuilder(s.length() * 2);
        boolean doubleNextConsonant = false;
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == 'っ') {
                doubleNextConsonant = true;
                i++;
                continue;
            }
            if (c == 'ー') {
                if (out.length() > 0) {
                    char prev = out.charAt(out.length() - 1);
                    if ("aeiou".indexOf(prev) >= 0) {
                        out.append(prev);
                    }
                }
                i++;
                continue;
            }
            String syl = null;
            int step = 1;
            if (i + 1 < len) {
                String pair = s.substring(i, i + 2);
                syl = KANA_DIGRAPHS.get(pair);
                if (syl != null) step = 2;
            }
            if (syl == null) {
                syl = KANA_MONOGRAPHS.get(c);
            }
            if (syl != null) {
                if (doubleNextConsonant && !syl.isEmpty()) {
                    char fc = syl.charAt(0);
                    if ("aeiou".indexOf(fc) < 0) {
                        out.append(fc == 'c' ? 't' : fc);
                    }
                    doubleNextConsonant = false;
                }
                out.append(syl);
                if (c == 'ん' && i + 1 < len) {
                    char nx = s.charAt(i + 1);
                    String nxSyl = KANA_MONOGRAPHS.get(nx);
                    if (nxSyl != null && !nxSyl.isEmpty() && "aeiouy".indexOf(nxSyl.charAt(0)) >= 0) {
                        out.append('\'');
                    }
                }
                i += step;
            } else {
                doubleNextConsonant = false;
                out.append(c);
                i++;
            }
        }
        return out.toString().replaceAll("\\s+", " ").trim();
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
