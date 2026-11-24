package com.rayneo.customai;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rayneo.airuntime.controller.ASRResultListener;
import com.rayneo.airuntime.controller.ASRTask;
import com.rayneo.airuntime.controller.RayNeoASRConfig;
import com.rayneo.airuntime.controller.RayNeoProactiveHint;
import com.rayneo.airuntime.controller.ShareHandler;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-life "Disco Elysium" Subtext Decoder + GALGAME Dialogue Tree Engine.
 *
 * Dual-carrier architecture:
 * 1) Proactive AI ("有问必答", {@link ASRTask} name="venus_proactive_ai_asr" / bizType="MEETING_ASSISTANT"):
 *    Aggregates ambient speech with smart pause debouncing, suppresses generic encyclopedic stock hints,
 *    and streams structured {"question": subtextHeader, "answer": galgameChoices} cards through
 *    {@link ASRResultListener#onProactiveHint(RayNeoProactiveHint)} directly to RayNeo iO HUD (BLE 0x17) & phone UI.
 * 2) Live Captions / Simultaneous Translate ("实时字幕", Caption Hub dynamic forwarder):
 *    Intercepts guest utterances before segment sealing, streams subtext + RPG reply choices into
 *    translationText (bilingual mode) or appended source transcript (pure transcription mode),
 *    and cleanly seals the segment on completion.
 */
public final class SubtextGalgameEngine {

    private static final String TAG = "RayNeoSubtext";

    public static final String PROACTIVE_TASK_NAME = "venus_proactive_ai_asr";
    public static final String PROACTIVE_BIZ_TYPE = "MEETING_ASSISTANT";

    // Voice commands for hands-free switching
    public static final int CMD_NONE = 0;
    public static final int CMD_ENABLE = 1;
    public static final int CMD_DISABLE = 2;
    public static final int CMD_SCENE_AUTO = 3;
    public static final int CMD_SCENE_WORK = 4;
    public static final int CMD_SCENE_LOVE = 5;
    public static final int CMD_SCENE_SOCIAL = 6;
    public static final int CMD_OPTIONS_2 = 7;
    public static final int CMD_OPTIONS_3 = 8;

    private static final long FAST_DEBOUNCE_MS = 360L;
    private static final long NORMAL_DEBOUNCE_MS = 720L;
    private static final long STREAM_PUSH_THROTTLE_MS = 200L;
    private static final long CAPTION_SEAL_WATCHDOG_MS = 3800L;
    private static final long ECHO_OPTION_TTL_MS = 30000L;
    private static final int MAX_DIALOGUE_HISTORY = 10;

    private static final Pattern PAT_WAKE_PREFIX = Pattern.compile(
            "^(?:小雷小雷|小雷|嘿小雷|你好小雷)[，,、\\s]*");

    private static final Pattern PAT_PURE_FILLER = Pattern.compile(
            "^(?:嗯+|哦+|噢+|啊+|呃+|额+|哈+|嘿+|唔+|噢噢|好的*|好呀|好嘞|行+|可以|对+|是的*|没错|明白|收到|知道了|了解|谢谢|多谢|稍等|等一下|等会儿|喂+|你好|您好|再见|拜拜)+$");

    // High-signal reverse-meaning / subtext / question cues that MUST NEVER be filtered even when short
    private static final String[] HIGH_ALERT_SUBTEXT_CUES = new String[]{
            "没事", "随便", "算了", "不用管", "你去吧", "去你的吧", "你忙吧", "你看着办", "都行", "无所谓",
            "沉淀", "对齐", "赋能", "闭环", "抓手", "回头聊", "改天", "下次一定", "再议", "研究一下",
            "考虑一下", "内部讨论", "原则上", "预算", "太贵", "便宜点", "底价", "竞品", "排期",
            "怎么看", "你觉得", "行不行", "好不好", "可以吗", "能不能", "什么时候", "为什么", "凭什么"
    };

    private static final Pattern PAT_OPTION_LINE = Pattern.compile(
            "^\\s*(?:[-*•]\\s*)?\\[?([ABCabc])\\]?\\s*(?:[（(\\[【]([^）)\\]】]{1,8})[）)\\]】])?\\s*[:：.、\\-]?\\s*(.+)$");

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
                private final AtomicLong idx = new AtomicLong(1L);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "RayNeoSubtext-" + idx.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final ThreadLocal<Boolean> CAPTION_REENTRANT_GUARD = new ThreadLocal<>();

    private static volatile Application appContext;
    private static volatile CustomAiConfig testConfigOverride;

    // Active Proactive AI ("有问必答") state
    private static final Object PROACTIVE_LOCK = new Object();
    private static WeakReference<ASRTask> activeProactiveTaskRef;
    private static volatile ASRResultListener activeProactiveListener;
    private static final StringBuilder pendingProactiveBuffer = new StringBuilder();
    private static ScheduledFuture<?> pendingProactiveFuture;
    private static volatile OpenAiApiClient.CancelToken inFlightProactiveCancel;
    private static volatile boolean inFlightProactiveEmittedCard = false;
    private static final AtomicLong HINT_SEQ = new AtomicLong(1L);

    // Dialogue memory & GALGAME branch echo tracking
    private static final Object MEMORY_LOCK = new Object();
    private static final Deque<String> dialogueRingBuffer = new ArrayDeque<>();
    private static volatile List<OptionChoice> recentOfferedOptions = Collections.emptyList();
    private static volatile long recentOptionsTimestampMs = 0L;

    // Cached reflection handles for Caption Hub (im0/c0)
    private static volatile CaptionHubHandles cachedCaptionHandles;

    private SubtextGalgameEngine() {}

    public static void init(Application app) {
        if (app != null) {
            appContext = app;
        }
    }

    public static void setTestConfigOverride(CustomAiConfig cfg) {
        testConfigOverride = cfg;
    }

    public static void clearStateForTest() {
        synchronized (MEMORY_LOCK) {
            dialogueRingBuffer.clear();
            recentOfferedOptions = Collections.emptyList();
            recentOptionsTimestampMs = 0L;
        }
        synchronized (PROACTIVE_LOCK) {
            pendingProactiveBuffer.setLength(0);
            if (pendingProactiveFuture != null) {
                pendingProactiveFuture.cancel(false);
                pendingProactiveFuture = null;
            }
            if (inFlightProactiveCancel != null) {
                inFlightProactiveCancel.cancel();
                inFlightProactiveCancel = null;
            }
        }
    }

    private static CustomAiConfig loadConfig() {
        CustomAiConfig override = testConfigOverride;
        if (override != null) return override;
        Application app = appContext;
        return app != null ? CustomAiConfig.load(app) : new CustomAiConfig();
    }

    // ==================== Data Models ====================

    public static final class OptionChoice {
        public final String key;        // "A", "B", "C"
        public final String tag;        // e.g. "哄", "邀", "探底"
        public final String spokenText; // First-person line user can read aloud directly

        public OptionChoice(String key, String tag, String spokenText) {
            this.key = safe(key).toUpperCase(Locale.ROOT);
            this.tag = safe(tag);
            this.spokenText = safe(spokenText);
        }

        public String formatHudLine() {
            if (tag.isEmpty()) {
                return key + ". " + spokenText;
            }
            return key + "(" + tag + ") " + spokenText;
        }
    }

    public static final class SubtextCard {
        public final boolean isPass;
        public final String headerLine;
        public final List<OptionChoice> options;
        public final String optionsBody;

        public SubtextCard(boolean isPass, String headerLine, List<OptionChoice> options, String optionsBody) {
            this.isPass = isPass;
            this.headerLine = safe(headerLine);
            this.options = options != null ? options : Collections.<OptionChoice>emptyList();
            this.optionsBody = safe(optionsBody);
        }

        public String formatCombinedBlock() {
            if (optionsBody.isEmpty()) return headerLine;
            if (headerLine.isEmpty()) return optionsBody;
            return headerLine + "\n" + optionsBody;
        }

        public String toProactiveHintJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", headerLine);
            m.put("answer", optionsBody);
            return JsonMini.stringify(m);
        }
    }

    // ==================== Voice Command Router ====================

    public static int matchVoiceCommand(String rawAsr) {
        if (rawAsr == null) return CMD_NONE;
        String clean = PAT_WAKE_PREFIX.matcher(rawAsr.trim()).replaceFirst("");
        clean = clean.replaceAll("[。！？!?，,、.\\s]+$", "").trim();
        if (clean.isEmpty() || clean.length() > 24) return CMD_NONE;

        if (clean.matches("^(?:请|帮我)?(?:打开|开启|启动|进入)(?:一下)?(?:极乐迪斯科|潜台词|潜台词解码|嘴替|对话选项)(?:模式|功能|外挂)?(?:吧|呀|啊)?$")) {
            return CMD_ENABLE;
        }
        if (clean.matches("^(?:请|帮我)?(?:关闭|关掉|退出|停止)(?:一下)?(?:极乐迪斯科|潜台词|潜台词解码|嘴替|对话选项)(?:模式|功能|外挂)?(?:吧|呀|啊)?$")
                || clean.matches("^(?:切回|恢复)原版有问必答(?:吧|呀)?$")) {
            return CMD_DISABLE;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)?(?:切换?(?:到|成)?|开启|打开)(?:恋爱|哄人|哄对象|约会|相亲)模式(?:吧|呀)?$")) {
            return CMD_SCENE_LOVE;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)?(?:切换?(?:到|成)?|开启|打开)(?:职场|甲方|商务|谈判|开会)模式(?:吧|呀)?$")) {
            return CMD_SCENE_WORK;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)?(?:切换?(?:到|成)?|开启|打开)(?:社交|饭局|聚会|接梗)模式(?:吧|呀)?$")) {
            return CMD_SCENE_SOCIAL;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)?(?:切换?(?:到|成)?)(?:自动|全能|全场景)模式(?:吧|呀)?$")) {
            return CMD_SCENE_AUTO;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)(?:改成|设为|切换成?|显示)(?:2|两|二)个选项(?:吧|呀)?$")) {
            return CMD_OPTIONS_2;
        }
        if (clean.matches("^(?:潜台词|极乐迪斯科|嘴替)(?:改成|设为|切换成?|显示)(?:3|三)个选项(?:吧|呀)?$")) {
            return CMD_OPTIONS_3;
        }
        return CMD_NONE;
    }

    public static String executeVoiceCommand(Context ctx, int cmd) {
        Context target = ctx != null ? ctx.getApplicationContext() : appContext;
        CustomAiConfig cfg = target != null ? CustomAiConfig.load(target) : loadConfig();
        String reply;
        switch (cmd) {
            case CMD_ENABLE:
                cfg.enableSubtextMode = true;
                reply = "已开启极乐迪斯科潜台词解码模式，当前为"
                        + sceneLabel(cfg.subtextScene) + "，" + cfg.subtextOptionCount + "个对话选项。";
                break;
            case CMD_DISABLE:
                cfg.enableSubtextMode = false;
                reply = "已关闭潜台词解码模式，恢复官方原版有问必答与实时字幕。";
                break;
            case CMD_SCENE_LOVE:
                cfg.enableSubtextMode = true;
                cfg.subtextScene = CustomAiConfig.SUBTEXT_SCENE_LOVE;
                reply = "已切换到恋爱高情商求生剧本，随时帮你识破反话。";
                break;
            case CMD_SCENE_WORK:
                cfg.enableSubtextMode = true;
                cfg.subtextScene = CustomAiConfig.SUBTEXT_SCENE_WORK;
                reply = "已切换到职场与甲方博弈剧本。";
                break;
            case CMD_SCENE_SOCIAL:
                cfg.enableSubtextMode = true;
                cfg.subtextScene = CustomAiConfig.SUBTEXT_SCENE_SOCIAL;
                reply = "已切换到饭局社交接梗剧本。";
                break;
            case CMD_SCENE_AUTO:
                cfg.enableSubtextMode = true;
                cfg.subtextScene = CustomAiConfig.SUBTEXT_SCENE_AUTO;
                reply = "已切换到全场景自动潜台词检定模式。";
                break;
            case CMD_OPTIONS_2:
                cfg.enableSubtextMode = true;
                cfg.subtextOptionCount = 2;
                reply = "已设为每次生成 A、B 两个精品对话选项。";
                break;
            case CMD_OPTIONS_3:
                cfg.enableSubtextMode = true;
                cfg.subtextOptionCount = 3;
                reply = "已设为每次生成 A、B、C 三个对话分支选项。";
                break;
            default:
                return "";
        }
        if (target != null) {
            cfg.save(target);
        }
        return reply;
    }

    public static String sceneLabel(String scene) {
        String s = CustomAiConfig.normalizeSubtextScene(scene);
        if (CustomAiConfig.SUBTEXT_SCENE_WORK.equals(s)) return "职场博弈模式";
        if (CustomAiConfig.SUBTEXT_SCENE_LOVE.equals(s)) return "恋爱求生模式";
        if (CustomAiConfig.SUBTEXT_SCENE_SOCIAL.equals(s)) return "社交接梗模式";
        return "全场景自动检定";
    }

    // ==================== Channel 1: Proactive AI ("有问必答" / ASRTask) ====================

    public static boolean isProactiveAiTask(String taskName, RayNeoASRConfig config) {
        if (PROACTIVE_TASK_NAME.equals(safe(taskName))) return true;
        if (config != null) {
            String biz = safe(config.getBizType());
            if (PROACTIVE_BIZ_TYPE.equalsIgnoreCase(biz) || "MEETING_ASSISTANT".equalsIgnoreCase(biz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnedProactiveTask(ASRTask task, String taskName) {
        if (PROACTIVE_TASK_NAME.equals(safe(taskName))) return true;
        if (task == null) return false;
        synchronized (PROACTIVE_LOCK) {
            ASRTask cur = activeProactiveTaskRef != null ? activeProactiveTaskRef.get() : null;
            return cur != null && cur == task;
        }
    }

    public static void onAsrTaskInit(
            ASRTask task, String taskName, RayNeoASRConfig config, ASRResultListener listener) {
        if (!isProactiveAiTask(taskName, config)) return;
        synchronized (PROACTIVE_LOCK) {
            activeProactiveTaskRef = new WeakReference<>(task);
            activeProactiveListener = listener;
            pendingProactiveBuffer.setLength(0);
            cancelPendingTimerLocked();
        }
        safeLogI("[PROACTIVE-INIT] Hooked ASRTask name=" + taskName + " listener=" + listener);
    }

    public static void onAsrTaskStop(ASRTask task, String taskName) {
        if (!isOwnedProactiveTask(task, taskName)) return;
        synchronized (PROACTIVE_LOCK) {
            ASRTask cur = activeProactiveTaskRef != null ? activeProactiveTaskRef.get() : null;
            if (cur == null || cur == task) {
                activeProactiveTaskRef = null;
                activeProactiveListener = null;
            }
            pendingProactiveBuffer.setLength(0);
            cancelPendingTimerLocked();
            if (inFlightProactiveCancel != null) {
                inFlightProactiveCancel.cancel();
                inFlightProactiveCancel = null;
            }
        }
        safeLogI("[PROACTIVE-STOP] Released ASRTask name=" + taskName);
    }

    /**
     * Returns true to suppress stock encyclopedic C++ hints when Disco Elysium mode owns Proactive AI.
     */
    public static boolean shouldSuppressStockProactiveHint(
            ASRTask task, String taskName, String text, boolean isFinish, String asrTextId) {
        if (!isOwnedProactiveTask(task, taskName)) return false;
        CustomAiConfig cfg = loadConfig();
        if (cfg.isSubtextEnabledForProactive()) {
            safeLogI("[PROACTIVE-SUPPRESS] Blocked stock hint asrTextId=" + asrTextId);
            return true;
        }
        return false;
    }

    public static void onProactiveAsrResult(
            ASRTask task, String taskName, String rawAsr, boolean finished) {
        if (!isOwnedProactiveTask(task, taskName)) return;
        CustomAiConfig cfg = loadConfig();
        if (!cfg.isSubtextEnabledForProactive()) return;

        String cleanText = unwrapAsrPayloadText(rawAsr);
        if (cleanText.isEmpty()) return;

        synchronized (PROACTIVE_LOCK) {
            if (!finished) {
                // Speaker is still mid-sentence: hold debounce window so clauses merge cleanly
                cancelPendingTimerLocked();
                if (inFlightProactiveCancel != null && !inFlightProactiveEmittedCard) {
                    inFlightProactiveCancel.cancel();
                    inFlightProactiveCancel = null;
                }
                return;
            }

            // Append finished clause
            if (pendingProactiveBuffer.length() > 0) {
                char last = pendingProactiveBuffer.charAt(pendingProactiveBuffer.length() - 1);
                if ("，。！？,!?；;".indexOf(last) < 0) {
                    pendingProactiveBuffer.append('，');
                }
            }
            pendingProactiveBuffer.append(cleanText);

            final String snapshot = pendingProactiveBuffer.toString();
            // Immediate GALGAME echo check: did the wearer just read one of our A/B/C options aloud?
            OptionChoice picked = detectSelectedOption(snapshot);
            if (picked != null) {
                pendingProactiveBuffer.setLength(0);
                cancelPendingTimerLocked();
                recordDialogueLine("[我方打出选项" + picked.key + "] " + snapshot);
                safeLogI("[GALGAME-ECHO] Player selected branch " + picked.key + ": " + snapshot);
                return;
            }

            cancelPendingTimerLocked();
            long delayMs = hasHighAlertOrQuestionCue(snapshot) ? FAST_DEBOUNCE_MS : NORMAL_DEBOUNCE_MS;
            pendingProactiveFuture = SCHEDULER.schedule(new Runnable() {
                @Override
                public void run() {
                    flushProactiveUtterance();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void flushProactiveUtterance() {
        final String utterance;
        final ASRResultListener listener;
        final OpenAiApiClient.CancelToken cancelToken = new OpenAiApiClient.CancelToken();

        synchronized (PROACTIVE_LOCK) {
            pendingProactiveFuture = null;
            utterance = pendingProactiveBuffer.toString().trim();
            pendingProactiveBuffer.setLength(0);
            listener = activeProactiveListener;
            if (utterance.isEmpty() || listener == null) return;

            if (inFlightProactiveCancel != null) {
                inFlightProactiveCancel.cancel();
            }
            inFlightProactiveCancel = cancelToken;
            inFlightProactiveEmittedCard = false;
        }

        OptionChoice picked = detectSelectedOption(utterance);
        if (picked != null) {
            recordDialogueLine("[我方打出选项" + picked.key + "] " + utterance);
            return;
        }

        if (isTrivialFillerUtterance(utterance)) {
            safeLogI("[PROACTIVE-SKIP-FILLER] " + utterance);
            return;
        }

        final CustomAiConfig cfg = loadConfig();
        if (!cfg.isSubtextEnabledForProactive()) return;

        final String asrTextId = "subtext_asr_" + HINT_SEQ.getAndIncrement();
        final String hintId = "subtext_hint_" + UUID.randomUUID().toString().substring(0, 8);
        final List<String> recentContext = snapshotDialogueHistory();
        recordDialogueLine("[对方] " + utterance);

        final long logId = CustomAiCallLog.startCall(
                appContext,
                "🎭 有问必答·潜台词",
                cfg,
                utterance,
                "proactive_subtext",
                true,
                false);

        try {
            int optCount = CustomAiConfig.normalizeSubtextOptionCount(cfg.subtextOptionCount);
            String sysPrompt = buildSubtextSystemPrompt(cfg);
            String userPrompt = buildSubtextUserPrompt(utterance, recentContext, optCount);
            OpenAiApiClient.EndpointConfig ep = cfg.toSubtextEndpointConfig(sysPrompt);

            final StringBuilder streamBuf = new StringBuilder();
            final long[] lastEmitMs = new long[]{0L};
            final boolean[] decidedPass = new boolean[]{false};

            String rawReply = OpenAiApiClient.streamChat(
                    ep,
                    null,
                    userPrompt,
                    new OpenAiApiClient.StreamCallback() {
                        @Override
                        public void onReasoningDelta(String delta) {}

                        @Override
                        public void onTextDelta(String delta) {
                            if (delta == null || delta.isEmpty() || decidedPass[0] || cancelToken.isCancelled()) {
                                return;
                            }
                            CustomAiCallLog.onTextDelta(logId, delta);
                            streamBuf.append(delta);
                            String cur = streamBuf.toString().trim();
                            if (cur.length() < 8) {
                                if (startsWithPassToken(cur)) {
                                    decidedPass[0] = true;
                                }
                                return;
                            }
                            if (startsWithPassToken(cur)) {
                                decidedPass[0] = true;
                                return;
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastEmitMs[0] >= STREAM_PUSH_THROTTLE_MS || delta.contains("\n")) {
                                SubtextCard partial = parseSubtextCard(cur, optCount);
                                if (!partial.isPass && !partial.headerLine.isEmpty()) {
                                    lastEmitMs[0] = now;
                                    inFlightProactiveEmittedCard = true;
                                    dispatchProactiveHintToGlasses(
                                            listener, partial.toProactiveHintJson(), false, asrTextId, hintId);
                                }
                            }
                        }
                    },
                    cancelToken);

            if (cancelToken.isCancelled()) {
                CustomAiCallLog.finishInterrupted(appContext, logId, streamBuf.toString());
                return;
            }

            SubtextCard finalCard = parseSubtextCard(rawReply, optCount);
            if (finalCard.isPass || finalCard.headerLine.isEmpty()) {
                CustomAiCallLog.addEvent(logId, "SUBTEXT_PASS_SILENT");
                CustomAiCallLog.finishSuccess(appContext, logId, "[PASS 无需弹卡]");
                return;
            }

            recordGeneratedOptions(finalCard.options);
            inFlightProactiveEmittedCard = true;
            dispatchProactiveHintToGlasses(
                    listener, finalCard.toProactiveHintJson(), true, asrTextId, hintId);
            CustomAiCallLog.finishSuccess(appContext, logId, finalCard.formatCombinedBlock());
        } catch (Throwable t) {
            safeLogW("flushProactiveUtterance error: " + t.getMessage());
            CustomAiCallLog.finishError(appContext, logId, String.valueOf(t.getMessage()));
        }
    }

    private static void dispatchProactiveHintToGlasses(
            final ASRResultListener listener,
            final String hintJson,
            final boolean isFinish,
            final String asrTextId,
            final String proactiveHintId
    ) {
        if (listener == null || hintJson == null || hintJson.isEmpty()) return;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    RayNeoProactiveHint hint = new RayNeoProactiveHint();
                    hint.setText(hintJson);
                    hint.setFinish(isFinish);
                    hint.setAsrTextId(asrTextId);
                    hint.setProactiveHintId(proactiveHintId);
                    hint.setChannelId("1");
                    listener.onProactiveHint(hint);
                } catch (Throwable t) {
                    safeLogW("dispatchProactiveHintToGlasses error: " + t.getMessage());
                }
            }
        };
        postToShareOrMain(task);
    }

    private static void cancelPendingTimerLocked() {
        if (pendingProactiveFuture != null) {
            pendingProactiveFuture.cancel(false);
            pendingProactiveFuture = null;
        }
    }

    // ==================== Channel 2: Live Captions / Translate Hub (MOD-07) ====================

    /**
     * Dynamically hooks the universal Caption Hub forwarder {@code a(c0Instance, jsonString)}.
     * Returns the (possibly modified) JSON string to forward immediately, or {@code null} to suppress.
     */
    public static String onCaptionForwardPayload(final Object c0Instance, final String jsonString) {
        if (jsonString == null || jsonString.isEmpty() || c0Instance == null) {
            return jsonString;
        }
        if (Boolean.TRUE.equals(CAPTION_REENTRANT_GUARD.get())) {
            return jsonString;
        }

        final CustomAiConfig cfg = loadConfig();
        if (!cfg.isSubtextEnabledForCaption()) {
            return jsonString;
        }

        try {
            final Map<String, Object> payload = JsonMini.parseObject(jsonString);
            if (payload.isEmpty()) return jsonString;

            String role = safe(JsonMini.getString(payload, "role")).toLowerCase(Locale.ROOT);
            final String origText = safe(JsonMini.getString(payload, "text"));
            final String origTrans = safe(JsonMini.getString(payload, "translationText"));
            boolean finished = readFinishedFlag(payload);

            // 1. Wearer's own speech ("host"): record context & check GALGAME branch selection, never decode self!
            if ("host".equals(role)) {
                if (finished && !origText.isEmpty()) {
                    OptionChoice picked = detectSelectedOption(origText);
                    if (picked != null) {
                        recordDialogueLine("[我方打出选项" + picked.key + "] " + origText);
                        safeLogI("[CAPTION-ECHO] Host picked branch " + picked.key + ": " + origText);
                    } else {
                        recordDialogueLine("[我方] " + origText);
                    }
                }
                return jsonString;
            }

            // 2. Partial streaming caption: pass through with 0ms latency so wearer sees live words immediately
            if (!finished || origText.isEmpty()) {
                return jsonString;
            }

            // 3. Even if role=="guest" (e.g. single-mic phone mode), check echo against recent A/B/C options
            OptionChoice echoPicked = detectSelectedOption(origText);
            if (echoPicked != null) {
                recordDialogueLine("[我方打出选项" + echoPicked.key + "] " + origText);
                return jsonString;
            }

            // 4. Local 0ms filler filter
            if (isTrivialFillerUtterance(origText)) {
                return jsonString;
            }

            final CaptionHubHandles handles = resolveCaptionHubHandles(c0Instance.getClass());
            if (handles == null) {
                return jsonString;
            }

            // Hold segment seal (`finished=false`) on the immediate pass-through frame so `Lim0/k` keeps
            // `roundId` open while showing the complete original transcript immediately!
            final boolean hadFinishKey = payload.containsKey("finish");
            final boolean hadFinishedKey = payload.containsKey("finished") || !hadFinishKey;
            Map<String, Object> unsealedImmediate = new LinkedHashMap<>(payload);
            setFinishedFlags(unsealedImmediate, false, hadFinishKey, hadFinishedKey);

            final boolean pureTranscription = handles.isTranscriptionOnlyMode(c0Instance);
            final List<String> recentContext = snapshotDialogueHistory();
            recordDialogueLine("[对方] " + origText);

            final AtomicBoolean sealed = new AtomicBoolean(false);
            final WeakReference<Object> c0Ref = new WeakReference<>(c0Instance);

            // Safety watchdog: guarantee segment seal within 3.8s even on network stall
            SCHEDULER.schedule(new Runnable() {
                @Override
                public void run() {
                    if (sealed.compareAndSet(false, true)) {
                        Object target = c0Ref.get();
                        if (target != null) {
                            handles.reinjectOnMain(target, jsonString);
                        }
                    }
                }
            }, CAPTION_SEAL_WATCHDOG_MS, TimeUnit.MILLISECONDS);

            SCHEDULER.execute(new Runnable() {
                @Override
                public void run() {
                    runAsyncCaptionSubtext(
                            c0Ref, handles, payload, jsonString,
                            origText, origTrans, pureTranscription,
                            hadFinishKey, hadFinishedKey, recentContext, cfg, sealed);
                }
            });

            return JsonMini.stringify(unsealedImmediate);
        } catch (Throwable t) {
            safeLogW("onCaptionForwardPayload error: " + t.getMessage());
            return jsonString;
        }
    }

    private static void runAsyncCaptionSubtext(
            final WeakReference<Object> c0Ref,
            final CaptionHubHandles handles,
            final Map<String, Object> basePayload,
            final String fallbackOriginalJson,
            final String origText,
            final String origTrans,
            final boolean pureTranscription,
            final boolean hadFinishKey,
            final boolean hadFinishedKey,
            final List<String> recentContext,
            final CustomAiConfig cfg,
            final AtomicBoolean sealed
    ) {
        final long logId = CustomAiCallLog.startCall(
                appContext,
                "🎭 实时字幕·潜台词",
                cfg,
                origText,
                "caption_subtext",
                true,
                false);
        try {
            final int optCount = CustomAiConfig.normalizeSubtextOptionCount(cfg.subtextOptionCount);
            String sysPrompt = buildSubtextSystemPrompt(cfg);
            String userPrompt = buildSubtextUserPrompt(origText, recentContext, optCount);
            OpenAiApiClient.EndpointConfig ep = cfg.toSubtextEndpointConfig(sysPrompt);

            final StringBuilder streamBuf = new StringBuilder();
            final long[] lastPushMs = new long[]{0L};
            final boolean[] decidedPass = new boolean[]{false};

            String rawReply = OpenAiApiClient.streamChat(
                    ep,
                    null,
                    userPrompt,
                    new OpenAiApiClient.StreamCallback() {
                        @Override
                        public void onReasoningDelta(String delta) {}

                        @Override
                        public void onTextDelta(String delta) {
                            if (delta == null || delta.isEmpty() || decidedPass[0] || sealed.get()) {
                                return;
                            }
                            CustomAiCallLog.onTextDelta(logId, delta);
                            streamBuf.append(delta);
                            String cur = streamBuf.toString().trim();
                            if (cur.length() < 8) {
                                if (startsWithPassToken(cur)) decidedPass[0] = true;
                                return;
                            }
                            if (startsWithPassToken(cur)) {
                                decidedPass[0] = true;
                                return;
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastPushMs[0] >= 240L || delta.contains("\n")) {
                                SubtextCard partial = parseSubtextCard(cur, optCount);
                                if (!partial.isPass && !partial.headerLine.isEmpty()) {
                                    Object target = c0Ref.get();
                                    if (target != null && !sealed.get()) {
                                        lastPushMs[0] = now;
                                        String midJson = buildEnrichedCaptionJson(
                                                basePayload, origText, partial.formatCombinedBlock(),
                                                pureTranscription, false, hadFinishKey, hadFinishedKey);
                                        handles.reinjectOnMain(target, midJson);
                                    }
                                }
                            }
                        }
                    },
                    null);

            if (!sealed.compareAndSet(false, true)) {
                CustomAiCallLog.finishInterrupted(appContext, logId, streamBuf.toString());
                return;
            }

            Object target = c0Ref.get();
            SubtextCard finalCard = parseSubtextCard(rawReply, optCount);
            if (finalCard.isPass || finalCard.headerLine.isEmpty()) {
                if (target != null) {
                    // Ensure bilingual seal invariant (non-empty target_translation when translation is active)
                    Map<String, Object> sealMap = new LinkedHashMap<>(basePayload);
                    if (!pureTranscription && origTrans.isEmpty()) {
                        sealMap.put("translationText", "—");
                    }
                    setFinishedFlags(sealMap, true, hadFinishKey, hadFinishedKey);
                    handles.reinjectOnMain(target, JsonMini.stringify(sealMap));
                }
                CustomAiCallLog.addEvent(logId, "SUBTEXT_PASS_SILENT");
                CustomAiCallLog.finishSuccess(appContext, logId, "[PASS 无需解码]");
                return;
            }

            recordGeneratedOptions(finalCard.options);
            String cardBlock = finalCard.formatCombinedBlock();
            if (target != null) {
                String finalJson = buildEnrichedCaptionJson(
                        basePayload, origText, cardBlock,
                        pureTranscription, true, hadFinishKey, hadFinishedKey);
                handles.reinjectOnMain(target, finalJson);
            }
            CustomAiCallLog.finishSuccess(appContext, logId, cardBlock);
        } catch (Throwable t) {
            if (sealed.compareAndSet(false, true)) {
                Object target = c0Ref.get();
                if (target != null) {
                    handles.reinjectOnMain(target, fallbackOriginalJson);
                }
            }
            CustomAiCallLog.finishError(appContext, logId, String.valueOf(t.getMessage()));
        }
    }

    public static String buildEnrichedCaptionJson(
            Map<String, Object> basePayload,
            String origText,
            String subtextBlock,
            boolean pureTranscriptionMode,
            boolean finished,
            boolean hadFinishKey,
            boolean hadFinishedKey
    ) {
        Map<String, Object> out = new LinkedHashMap<>(basePayload);
        if (pureTranscriptionMode) {
            out.put("text", origText + "\n" + subtextBlock);
        } else {
            out.put("text", origText);
            out.put("translationText", subtextBlock);
        }
        setFinishedFlags(out, finished, hadFinishKey, hadFinishedKey);
        return JsonMini.stringify(out);
    }

    private static boolean readFinishedFlag(Map<String, Object> m) {
        Object f1 = m.get("finish");
        if (f1 instanceof Boolean) return (Boolean) f1;
        if (f1 instanceof Number) return ((Number) f1).intValue() == 1;
        Object f2 = m.get("finished");
        if (f2 instanceof Boolean) return (Boolean) f2;
        if (f2 instanceof Number) return ((Number) f2).intValue() == 1;
        return false;
    }

    private static void setFinishedFlags(
            Map<String, Object> m, boolean finished, boolean hadFinishKey, boolean hadFinishedKey) {
        if (hadFinishKey) {
            Object old = m.get("finish");
            m.put("finish", (old instanceof Number) ? (finished ? 1 : 0) : Boolean.valueOf(finished));
        }
        if (hadFinishedKey) {
            Object old = m.get("finished");
            m.put("finished", (old instanceof Number) ? (finished ? 1 : 0) : Boolean.valueOf(finished));
        }
    }

    public static final class CaptionHubHandles {
        public final Class<?> hubClass;
        public final Method staticForwardMethod;
        public final Field modeHolderField; // Optional 1-level wrapper holder (e.g. v1.0.30+ jm0.b0.S -> am0.b)
        public final Field modeEnumField;   // Enum field (e.g. TmkTranslateDeliveryMode) either on hubClass or modeHolderField
        public final Field isTranscriptionField;

        private CaptionHubHandles(
                Class<?> hubClass,
                Method staticForwardMethod,
                Field modeHolderField,
                Field modeEnumField,
                Field isTranscriptionField) {
            this.hubClass = hubClass;
            this.staticForwardMethod = staticForwardMethod;
            this.modeHolderField = modeHolderField;
            this.modeEnumField = modeEnumField;
            this.isTranscriptionField = isTranscriptionField;
        }

        public boolean isTranscriptionOnlyMode(Object c0Instance) {
            if (c0Instance == null || modeEnumField == null || isTranscriptionField == null) {
                return false;
            }
            try {
                Object owner = c0Instance;
                if (modeHolderField != null) {
                    owner = modeHolderField.get(c0Instance);
                    if (owner == null) return false;
                }
                Object modeEnum = modeEnumField.get(owner);
                if (modeEnum != null) {
                    return isTranscriptionField.getBoolean(modeEnum);
                }
            } catch (Throwable ignored) {}
            return false;
        }

        public void reinjectNow(Object c0Instance, String json) {
            Boolean prev = CAPTION_REENTRANT_GUARD.get();
            CAPTION_REENTRANT_GUARD.set(Boolean.TRUE);
            try {
                staticForwardMethod.invoke(null, c0Instance, json);
            } catch (Throwable t) {
                safeLogW("CaptionHubHandles.reinjectNow error: " + t.getMessage());
            } finally {
                if (prev == null) {
                    CAPTION_REENTRANT_GUARD.remove();
                } else {
                    CAPTION_REENTRANT_GUARD.set(prev);
                }
            }
        }

        public void reinjectOnMain(final Object c0Instance, final String json) {
            postToMainOrInline(new Runnable() {
                @Override
                public void run() {
                    reinjectNow(c0Instance, json);
                }
            });
        }
    }

    public static CaptionHubHandles resolveCaptionHubHandles(Class<?> hubCls) {
        CaptionHubHandles cached = cachedCaptionHandles;
        if (cached != null && cached.hubClass == hubCls) {
            return cached;
        }
        try {
            Method forwardMethod = null;
            for (Method m : hubCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == void.class
                        && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == hubCls
                        && m.getParameterTypes()[1] == String.class) {
                    m.setAccessible(true);
                    forwardMethod = m;
                    break;
                }
            }
            if (forwardMethod == null) return null;

            Field foundHolderField = null;
            Field foundModeField = null;
            Field foundIsTransField = null;

            // Pass 1: Direct enum field on hubCls (RayNeo AI v1.0.25 and earlier)
            for (Field f : hubCls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isEnum()) {
                    try {
                        Field isTrans = ft.getDeclaredField("isTranscription");
                        if (isTrans.getType() == boolean.class) {
                            f.setAccessible(true);
                            isTrans.setAccessible(true);
                            foundModeField = f;
                            foundIsTransField = isTrans;
                            break;
                        }
                    } catch (NoSuchFieldException ignored) {}
                }
            }

            // Pass 2: 1-level config holder field on hubCls (RayNeo AI v1.0.30+ jm0.b0.S -> am0.b.c -> TmkTranslateDeliveryMode)
            if (foundModeField == null) {
                for (Field hf : hubCls.getDeclaredFields()) {
                    if (Modifier.isStatic(hf.getModifiers()) || hf.isSynthetic()) continue;
                    Class<?> hType = hf.getType();
                    if (hType.isPrimitive() || hType.isArray() || hType.isEnum() || hType == hubCls) continue;
                    String hn = hType.getName();
                    if (hn.startsWith("java.") || hn.startsWith("android.")
                            || hn.startsWith("kotlinx.") || hn.startsWith("com.google.")) {
                        continue;
                    }
                    try {
                        for (Field innerF : hType.getDeclaredFields()) {
                            if (Modifier.isStatic(innerF.getModifiers())) continue;
                            Class<?> innerType = innerF.getType();
                            if (innerType.isEnum()) {
                                try {
                                    Field isTrans = innerType.getDeclaredField("isTranscription");
                                    if (isTrans.getType() == boolean.class) {
                                        hf.setAccessible(true);
                                        innerF.setAccessible(true);
                                        isTrans.setAccessible(true);
                                        foundHolderField = hf;
                                        foundModeField = innerF;
                                        foundIsTransField = isTrans;
                                        break;
                                    }
                                } catch (NoSuchFieldException ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                    if (foundModeField != null) break;
                }
            }

            CaptionHubHandles resolved = new CaptionHubHandles(
                    hubCls, forwardMethod, foundHolderField, foundModeField, foundIsTransField);
            cachedCaptionHandles = resolved;
            return resolved;
        } catch (Throwable t) {
            safeLogW("resolveCaptionHubHandles error: " + t.getMessage());
            return null;
        }
    }

    // ==================== Prompt Builder, Parser & GALGAME Echo ====================

    public static String buildSubtextSystemPrompt(CustomAiConfig cfg) {
        int optCount = CustomAiConfig.normalizeSubtextOptionCount(cfg != null ? cfg.subtextOptionCount : 2);
        String scene = CustomAiConfig.normalizeSubtextScene(cfg != null ? cfg.subtextScene : CustomAiConfig.SUBTEXT_SCENE_AUTO);
        String style = CustomAiConfig.normalizeSubtextStyle(cfg != null ? cfg.subtextStyle : CustomAiConfig.SUBTEXT_STYLE_DISCO);
        String note = cfg != null ? safe(cfg.subtextContextNote) : "";

        StringBuilder sb = new StringBuilder(900);
        sb.append("你是佩戴在用户智能眼镜上的《极乐迪斯科》现实对话检定引擎（潜台词解码器 + GALGAME 对话选项嘴替）。\n");
        sb.append("你将收到【对方刚刚说的一句话】及最近几轮对话上下文。请替佩戴者在 0.5 秒余光扫视内看穿对方真心话，并给出可直接脱口照念的第一人称高水准嘴替！\n\n");

        if (CustomAiConfig.SUBTEXT_SCENE_LOVE.equals(scene)) {
            sb.append("【当前剧本滤镜：恋爱/约会/高情商哄人求生】重点捕捉反话试探（如“没事你去吧/不用管我/随便”）、情绪降温、暗戳戳吃醋或期待仪式感。\n");
        } else if (CustomAiConfig.SUBTEXT_SCENE_WORK.equals(scene)) {
            sb.append("【当前剧本滤镜：职场拉扯/商务谈判/甲方博弈】重点解码互联网黑话（如“再沉淀/对齐/很有想象空间”）、推诿甩锅、预算压价试探与排期陷阱。\n");
        } else if (CustomAiConfig.SUBTEXT_SCENE_SOCIAL.equals(scene)) {
            sb.append("【当前剧本滤镜：饭局社交/破冰接梗】重点识破客套话、场面话、台阶信号，提供幽默不冷场的高情商接话。\n");
        } else {
            sb.append("【当前剧本滤镜：全场景自适应】自动识别职场博弈、情侣反话、人情客套或销售话术。\n");
        }

        if (!note.isEmpty()) {
            sb.append("【玩家预设局势底牌/关系备注】：").append(note).append("\n");
        }

        sb.append("\n【严格输出铁律（眼镜屏幕极窄，违反任一条即失败）】：\n");
        sb.append("1. 静默过滤协议：若对方只是毫无潜台词、无情绪博弈、也无需策略接话的纯日常琐碎白话（如“服务员加点水”、“今天周二”），只输出唯一单词：[PASS]\n");
        sb.append("2. 触发检定时，严禁任何寒暄、严禁 Markdown 粗体/星号/代码块，严格只输出 ").append(optCount + 1).append(" 行纯文本：\n");
        if (CustomAiConfig.SUBTEXT_STYLE_CLEAN.equals(style)) {
            sb.append("   第1行格式：⚠[潜台词] <14字以内一针见血拆穿对方真实意图/风险>\n");
        } else {
            sb.append("   第1行格式：<BMP符号如⚡/⚠/◆/★>[<极乐迪斯科技能名>·<态势>] <13字以内一针见血拆穿真心话>\n");
            sb.append("   （可用技能名如：同理心、修辞学、逻辑思维、权威、直觉、食髓知味；态势如：高危反话、看穿、试探、升温；请勿用彩色4字节Emoji）\n");
        }
        sb.append("   第2行格式：A(2字策略) <16字以内第一人称可直接张嘴照念的原话，优先高情商稳妥路线>\n");
        sb.append("   第3行格式：B(2字策略) <16字以内第一人称可直接张嘴照念的原话，进取破局或反向拉扯路线>\n");
        if (optCount == 3) {
            sb.append("   第4行格式：C(2字策略) <16字以内第一人称可直接照念的原话，幽默整活或守住底线硬刚路线>\n");
        }
        sb.append("3. A/B").append(optCount == 3 ? "/C" : "").append(" 后面必须是玩家本人第一人称能直接念出口的台词，绝不准出现“你可以说/建议回复”等说明字眼！");
        return sb.toString();
    }

    public static String buildSubtextUserPrompt(
            String latestUtterance, List<String> recentHistory, int optionCount) {
        StringBuilder sb = new StringBuilder(360);
        if (recentHistory != null && !recentHistory.isEmpty()) {
            sb.append("【近期对话轨迹】\n");
            int start = Math.max(0, recentHistory.size() - 6);
            for (int i = start; i < recentHistory.size(); i++) {
                sb.append(recentHistory.get(i)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【对方刚说】：「").append(safe(latestUtterance)).append("」\n");
        sb.append("请立即检定（无需弹卡则只回 [PASS]；需弹卡则严格输出 1 行潜台词 + ")
                .append(optionCount).append(" 行嘴替选项）：");
        return sb.toString();
    }

    public static SubtextCard parseSubtextCard(String rawOutput, int optionCount) {
        int targetOpts = CustomAiConfig.normalizeSubtextOptionCount(optionCount);
        if (rawOutput == null) {
            return new SubtextCard(true, "", Collections.<OptionChoice>emptyList(), "");
        }
        String cleaned = LyricsTeleprompterBridge.sanitizeBmpText(rawOutput)
                .replace("```", "")
                .replace("**", "")
                .replace("__", "")
                .trim();
        if (cleaned.isEmpty() || startsWithPassToken(cleaned)) {
            return new SubtextCard(true, "", Collections.<OptionChoice>emptyList(), "");
        }

        String[] rawLines = cleaned.split("\\r?\\n");
        String header = "";
        List<OptionChoice> parsedChoices = new ArrayList<>();
        List<String> formattedOptionLines = new ArrayList<>();

        for (String rl : rawLines) {
            String line = rl.trim();
            if (line.isEmpty()) continue;
            if (startsWithPassToken(line) && header.isEmpty()) {
                return new SubtextCard(true, "", Collections.<OptionChoice>emptyList(), "");
            }

            Matcher om = PAT_OPTION_LINE.matcher(line);
            if (om.matches() && !header.isEmpty()) {
                if (parsedChoices.size() >= targetOpts) continue;
                String key = safe(om.group(1)).toUpperCase(Locale.ROOT);
                String tag = safe(om.group(2));
                String spoken = stripQuotes(safe(om.group(3)));
                if (!spoken.isEmpty()) {
                    OptionChoice oc = new OptionChoice(key, tag, spoken);
                    parsedChoices.add(oc);
                    formattedOptionLines.add(oc.formatHudLine());
                }
            } else if (header.isEmpty()) {
                header = line.replaceFirst("^[#>*\\-\\s]+", "").trim();
            } else if (formattedOptionLines.size() < targetOpts) {
                formattedOptionLines.add(line);
            }
        }

        StringBuilder bodySb = new StringBuilder();
        for (int i = 0; i < formattedOptionLines.size(); i++) {
            if (i > 0) bodySb.append('\n');
            bodySb.append(formattedOptionLines.get(i));
        }
        return new SubtextCard(false, header, parsedChoices, bodySb.toString());
    }

    public static void recordGeneratedOptions(List<OptionChoice> options) {
        if (options == null || options.isEmpty()) return;
        synchronized (MEMORY_LOCK) {
            recentOfferedOptions = new ArrayList<>(options);
            recentOptionsTimestampMs = System.currentTimeMillis();
        }
    }

    /**
     * Checks if an incoming ASR line is the wearer reading aloud one of the recently offered A/B/C options.
     */
    public static OptionChoice detectSelectedOption(String rawUtterance) {
        String normSay = normalizeSpeechForCompare(rawUtterance);
        if (normSay.length() < 4) return null;

        List<OptionChoice> candidates;
        long ts;
        synchronized (MEMORY_LOCK) {
            candidates = recentOfferedOptions;
            ts = recentOptionsTimestampMs;
        }
        if (candidates == null || candidates.isEmpty()) return null;
        if (System.currentTimeMillis() - ts > ECHO_OPTION_TTL_MS) return null;

        OptionChoice best = null;
        double bestScore = 0.0;
        for (OptionChoice oc : candidates) {
            String normOpt = normalizeSpeechForCompare(oc.spokenText);
            if (normOpt.length() < 3) continue;
            double sim = computeBigramDiceSimilarity(normSay, normOpt);
            if (normSay.contains(normOpt) || (normSay.length() >= 5 && normOpt.contains(normSay))) {
                sim = Math.max(sim, 0.85);
            }
            if (sim > bestScore) {
                bestScore = sim;
                best = oc;
            }
        }

        if (best != null && bestScore >= 0.45) {
            synchronized (MEMORY_LOCK) {
                recentOfferedOptions = Collections.emptyList();
            }
            return best;
        }
        return null;
    }

    public static boolean isTrivialFillerUtterance(String raw) {
        if (raw == null) return true;
        String stripped = normalizeSpeechForCompare(raw);
        if (stripped.isEmpty()) return true;
        if (hasHighAlertOrQuestionCue(raw)) {
            return false;
        }
        if (stripped.length() <= 3) {
            return true;
        }
        return PAT_PURE_FILLER.matcher(stripped).matches();
    }

    public static boolean hasHighAlertOrQuestionCue(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        if (raw.indexOf('?') >= 0 || raw.indexOf('？') >= 0) return true;
        for (String cue : HIGH_ALERT_SUBTEXT_CUES) {
            if (raw.contains(cue)) return true;
        }
        return false;
    }

    // ==================== Helpers ====================

    private static void recordDialogueLine(String line) {
        synchronized (MEMORY_LOCK) {
            if (dialogueRingBuffer.size() >= MAX_DIALOGUE_HISTORY) {
                dialogueRingBuffer.pollFirst();
            }
            dialogueRingBuffer.addLast(line);
        }
    }

    private static List<String> snapshotDialogueHistory() {
        synchronized (MEMORY_LOCK) {
            return new ArrayList<>(dialogueRingBuffer);
        }
    }

    private static String unwrapAsrPayloadText(String rawAsr) {
        String s = safe(rawAsr);
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                Map<String, Object> m = JsonMini.parseObject(s);
                String t = JsonMini.getString(m, "text");
                if (t != null && !t.trim().isEmpty()) {
                    return t.trim();
                }
            } catch (Throwable ignored) {}
        }
        return s;
    }

    private static boolean startsWithPassToken(String s) {
        String u = safe(s).toUpperCase(Locale.ROOT);
        return u.startsWith("[PASS]") || u.equals("PASS") || u.startsWith("PASS\n") || u.startsWith("[SKIP]");
    }

    private static String stripQuotes(String s) {
        String t = safe(s);
        while (t.length() >= 2) {
            char f = t.charAt(0);
            char l = t.charAt(t.length() - 1);
            if ((f == '“' && l == '”') || (f == '「' && l == '」')
                    || (f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            } else {
                break;
            }
        }
        return t;
    }

    private static String normalizeSpeechForCompare(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：“”‘’「」『』《》【】（）()\\[\\]·~—…\\-]+", "");
    }

    private static double computeBigramDiceSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.length() < 2 || b.length() < 2) return 0.0;
        List<String> bgA = new ArrayList<>(a.length() - 1);
        for (int i = 0; i < a.length() - 1; i++) {
            bgA.add(a.substring(i, i + 2));
        }
        List<String> bgB = new ArrayList<>(b.length() - 1);
        for (int i = 0; i < b.length() - 1; i++) {
            bgB.add(b.substring(i, i + 2));
        }
        boolean[] usedB = new boolean[bgB.size()];
        int matches = 0;
        for (String x : bgA) {
            for (int j = 0; j < bgB.size(); j++) {
                if (!usedB[j] && x.equals(bgB.get(j))) {
                    usedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        return (2.0 * matches) / (bgA.size() + bgB.size());
    }

    private static void postToShareOrMain(Runnable r) {
        try {
            Handler h = ShareHandler.INSTANCE.getHandler();
            if (h != null) {
                h.post(r);
                return;
            }
        } catch (Throwable ignored) {}
        postToMainOrInline(r);
    }

    private static void postToMainOrInline(Runnable r) {
        try {
            Looper main = Looper.getMainLooper();
            if (main != null) {
                if (Looper.myLooper() == main) {
                    r.run();
                } else {
                    new Handler(main).post(r);
                }
                return;
            }
        } catch (Throwable ignored) {}
        r.run();
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
