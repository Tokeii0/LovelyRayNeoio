package com.rayneo.customai;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.rayneo.airuntime.controller.AiAgentConfig;
import com.rayneo.airuntime.controller.AiResultListener;
import com.rayneo.airuntime.controller.AskAIResultListener;
import com.rayneo.airuntime.controller.AskAITask;
import com.rayneo.airuntime.controller.AssistantController;
import com.rayneo.airuntime.controller.NlpCommand;
import com.rayneo.airuntime.controller.NlpResult;
import com.rayneo.airuntime.controller.RayClawToolCallInfo;
import com.rayneo.airuntime.controller.RayNeoAiConfig;
import com.rayneo.airuntime.controller.RayNeoAskAIGenerationOptions;
import com.rayneo.airuntime.controller.RayNeoAskAIParams;
import com.rayneo.airuntime.controller.RayNeoAskAIReasonDetail;
import com.rayneo.airuntime.controller.RayNeoAskAIResponse;
import com.rayneo.airuntime.controller.RayNeoAskAIResponseEventType;
import com.rayneo.airuntime.controller.RayNeoWorkflowParams;
import com.rayneo.airuntime.controller.ShareHandler;
import com.rayneo.airuntime.controller.SpeechStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Central Smali hook dispatcher (MOD-01..MOD-04).
 * Replaces ONLY general LLM chat inference while keeping RayNeo ASR, stock TTS,
 * and ALL official skills (weather/web search, todos/reminders/schedules/alarms,
 * music next/prev/play/pause, hardware controls, phone, navigation, vision, memory) intact.
 */
public final class CustomAiHooks {

    private static final String TAG = "RayNeoCustomAI";
    private static final long LOCAL_SKILL_GRACE_MS = 180L;
    private static final long VOLUME_COMBO_WINDOW_MS = 1500L;
    private static final long AUTO_NEXT_ROUND_DELAY_MS = 800L;
    private static final long SKILL_PRE_ASR_WINDOW_MS = 2000L;

    // ===== Tier-A local intent pre-classifier patterns for zero-delay official skill pass-through =====
    // Note: Music transport & now-playing ("切歌/下一首/上一首/暂停/继续播放/什么歌") are handled
    // directly by SystemMediaBridge via Android MediaSessionManager! Only radio/podcast stay on official.
    private static final Pattern PAT_RADIO_PODCAST = Pattern.compile(
            "(播放电台|换个电台|听电台|听播客|换台)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_TODO_REMINDER_ALARM = Pattern.compile(
            "(提醒我|提醒一下|设[个置]?提醒|定[个]?提醒|加[个]?提醒|创建提醒|取消提醒|删除提醒|查看提醒"
                    + "|待办|建[个]?任务|创建任务|记一下|帮我记|记[个]?备忘|备忘录"
                    + "|日程|安排一下|加[个]?日程|创建日程|查看日程"
                    + "|定[个]?闹钟|设[个置]?闹钟|加[个]?闹钟|关闭闹钟|取消闹钟|闹钟"
                    + "|倒计时|计时器|定[个]?计时|开始计时|取消计时"
                    + "|(分钟|小时|秒钟?)后.{0,12}(叫|喊|提醒|喝|吃|去|开|看|拿|关|收|打|发|买|交)"
                    + "|半小时后.{0,12}(叫|喊|提醒|喝|吃|去|开)"
                    + "|remind\\s+me|set\\s+a\\s+(reminder|timer|alarm)|create\\s+a\\s+task|add\\s+a\\s+todo)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_WEATHER_REALTIME = Pattern.compile(
            "(天气|气温|温度|几度|多少度|下雨|下雪|刮风|台风|暴雨|防晒|紫外线|空气质量|雾霾|pm2\\.?5|湿度|穿什么衣服|要带伞|带伞吗"
                    + "|实时新闻|今日新闻|今天新闻|最新新闻|热搜|头条新闻|最新消息"
                    + "|联网|上网查|帮我搜一下|搜索一下|查一下.{0,15}(最新|今天|今日|现在|实时|价格|比分|结果|天气)"
                    + "|股价|股票|大盘指数|汇率|金价|黄金.{0,4}多少|油价|比特币"
                    + "|比分|赛程|比赛结果|航班|高铁票|限行|实时路况|堵不堵车)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_SYSTEM_HARDWARE = Pattern.compile(
            "(音量|声音大|声音小|大点声|小点声|大声点|小声点|调大声音|调小声音|最大声|最小声|静音|取消静音|解除静音"
                    + "|亮度|亮一点|暗一点|调亮|调暗|最亮|最暗"
                    + "|电量|多少电|剩多少电|还有电吗|电池百分比"
                    + "|拍[一张]*照|拍照|拍照片|照相|帮我拍一张|连拍|开始录像|拍视频|录视频|停止录像|结束录像"
                    + "|开始录音|停止录音|结束录音|打开录音"
                    + "|关机|重启眼镜|退下|退出语音|不用了|没事了|闭嘴|别说了"
                    + "|导音鳍|轻语模式|澎湃模式|标准模式|切换音效|播报通知|读一下通知|查看通知|勿扰模式|锁屏|息屏|截屏)"
                    + "|^(再见|拜拜|退出)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PHONE_CALL = Pattern.compile(
            "(打电话|拨打|打给|呼叫|回电话|接电话|接听电话|挂断电话|挂电话|拒接电话|发短信给|打视频|视频通话)"
                    + "|^(接听|挂断|拒接)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_NAVIGATION_MAP = Pattern.compile(
            "(导航到|导航去|开始导航|退出导航|结束导航|关闭导航|路线规划|去.{1,20}怎么走|离这里多远|还有多远|多久能到"
                    + "|附近的.{1,12}|周边的.{1,12}|找一下附近|附近有什么|最近(的)?(加油站|洗手间|厕所|地铁站|公交站|停车场|便利店|医院|银行))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_VISION_TRANSLATE_MEMORY = Pattern.compile(
            "(拍一下看看|帮我看看这|看看这是什么|看一下这是什么|眼前是什么|前面是什么|帮我识别一下|看图识物"
                    + "|同声传译|打开同传|开启同传|打开实时翻译|开启翻译|面对面翻译"
                    + "|我刚才放哪|我之前说过|查找我的记忆|搜索我的记忆)",
            Pattern.CASE_INSENSITIVE);

    private static volatile Application appContext;
    private static volatile String lastSessionUuid = "";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = initMainHandler();

    private static Handler initMainHandler() {
        try {
            return new Handler(Looper.getMainLooper());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class SessionState {
        volatile String uuid = "";
        volatile RayNeoAiConfig aiConfig;
        volatile AiResultListener listener;
        volatile OpenAiApiClient.CancelToken activeCancel;
        volatile Runnable pendingStartTask;
        volatile Runnable pendingNextRoundTask;
        volatile boolean asrFinished = false;
        volatile boolean handledByLocalSkill = false;
        volatile boolean customTurnActive = false;
        volatile boolean recordEndForwarded = false;
        volatile boolean internalStoppingNative = false;
        volatile boolean internalRestartingRound = false;
        volatile boolean nativeWorkflowStopped = false;
        volatile long activeLogId = 0L;
        volatile long activeSkillLogId = 0L;
        volatile String lastSkillSummary = "";
        final StringBuilder skillAnswerAcc = new StringBuilder();
        volatile RouteGate routeGate;
        volatile Runnable routeTimeoutTask;
        volatile long lastSkillPassMs = 0L;
        final AtomicInteger turnSeq = new AtomicInteger(0);
    }

    /**
     * Buffers custom-LLM output until RayNeo cloud NLU / RayClaw decides whether an unclassified
     * utterance is an official skill/tool (pass-through) or plain chat (custom LLM).
     */
    private static final class RouteGate {
        static final int PENDING = 0;
        static final int CUSTOM = 1;
        static final int SKILL = 2;
        private int state = PENDING;
        private final List<Runnable> queue = new ArrayList<>();

        synchronized int state() {
            return state;
        }

        synchronized void post(Runnable r) {
            if (state == CUSTOM) {
                postToNativeCb(r);
            } else if (state == PENDING) {
                queue.add(r);
            }
        }

        synchronized boolean openCustom() {
            if (state != PENDING) return false;
            state = CUSTOM;
            for (Runnable r : queue) postToNativeCb(r);
            queue.clear();
            return true;
        }

        synchronized boolean routeSkill() {
            if (state != PENDING) return false;
            state = SKILL;
            queue.clear();
            return true;
        }
    }

    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<AskAITask, OpenAiApiClient.CancelToken> ASK_AI_CANCELS = new ConcurrentHashMap<>();
    private static final Map<AskAITask, Long> ASK_AI_LOG_IDS = new ConcurrentHashMap<>();
    private static final Map<AskAITask, Integer> ASK_AI_ROUNDS = new ConcurrentHashMap<>();

    private static long lastVolUpTime = 0L;
    private static int volUpCount = 0;

    private CustomAiHooks() {}

    public static Application getAppContext() {
        return appContext;
    }

    /**
     * Classifies whether a user utterance clearly targets an official RayNeo skill or real-time
     * online tool (weather, reminders/todos/schedules/alarms, music/media control, hardware/system,
     * phone calls, navigation, camera/vision/live-translation/memory).
     * Returns a human-readable category label if matched, or null for general open-domain chat.
     */
    public static String classifySkillOrRealtimeIntent(String rawQuery) {
        if (rawQuery == null) return null;
        String q = rawQuery.replaceAll("[\\s，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]]+", "").trim();
        if (q.isEmpty()) return null;
        String trimmedRaw = rawQuery.trim();

        // Local Android MediaSession commands ("切歌/下一首/上一首/暂停/继续播放/什么歌") are handled
        // directly by SystemMediaBridge, NOT passed to RayClaw!
        if (SystemMediaBridge.matchCommand(rawQuery) != 0) {
            return null;
        }
        if (PAT_RADIO_PODCAST.matcher(q).find() || PAT_RADIO_PODCAST.matcher(trimmedRaw).find()) {
            return "电台/播客";
        }
        if (PAT_TODO_REMINDER_ALARM.matcher(q).find() || PAT_TODO_REMINDER_ALARM.matcher(trimmedRaw).find()) {
            return "待办/提醒/日程";
        }
        if (PAT_WEATHER_REALTIME.matcher(q).find()) {
            return "天气/实时联网";
        }
        if (PAT_SYSTEM_HARDWARE.matcher(q).find()) {
            return "系统/硬件控制";
        }
        if (PAT_PHONE_CALL.matcher(q).find()) {
            return "电话/通讯";
        }
        if (PAT_NAVIGATION_MAP.matcher(q).find()) {
            return "地图/导航";
        }
        if (PAT_VISION_TRANSLATE_MEMORY.matcher(q).find()) {
            return "视觉/翻译/记忆";
        }
        return null;
    }

    // ==================== MOD-01: VenusApplication.onCreate ====================

    public static void onAppCreate(Application app) {
        try {
            if (app == null) return;
            appContext = app;
            CustomAiConfig cfg = CustomAiConfig.load(app);
            cfg.syncFlutterAskAiOverride(app);

            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    try {
                        if (activity != null && activity.getClass().getName().endsWith(".MainActivity")) {
                            CustomAiFloatingBadge.attachIfEnabled(activity);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Lifecycle badge attach err", t);
                    }
                }
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            Log.i(TAG, "CustomAI initialized (enabled=" + cfg.enabled + ", protocol=" + cfg.apiProtocol + ")");
        } catch (Throwable t) {
            Log.e(TAG, "onAppCreate error", t);
        }
    }

    // ==================== MOD-02: MainActivity key + badge ====================

    public static boolean onMainActivityKeyEvent(Activity activity, KeyEvent event) {
        try {
            if (activity == null || event == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                    && event.getRepeatCount() == 0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastVolUpTime <= VOLUME_COMBO_WINDOW_MS) {
                    volUpCount++;
                } else {
                    volUpCount = 1;
                }
                lastVolUpTime = now;
                if (volUpCount >= 3) {
                    volUpCount = 0;
                    CustomAiSettingsDialog.show(activity);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onMainActivityKeyEvent error", t);
        }
        return false;
    }

    public static void onMainActivityResume(Activity activity) {
        try {
            if (activity == null) return;
            if (appContext == null && activity.getApplication() != null) {
                appContext = activity.getApplication();
            }
            CustomAiFloatingBadge.attachIfEnabled(activity);
        } catch (Throwable t) {
            Log.e(TAG, "onMainActivityResume error", t);
        }
    }

    // ==================== MOD-03: AssistantController lifecycle & ASR/NLP ====================

    public static void onAssistantInitWorkflow(
            AssistantController ctrl, RayNeoAiConfig config, AiResultListener listener) {
        try {
            if (config == null || listener == null) return;
            String uuid = safeStr(config.getUuid());
            lastSessionUuid = uuid;
            SessionState st = getOrCreateSession(uuid);
            st.uuid = uuid;
            st.aiConfig = config;
            st.listener = listener;
            resetTurnFlags(st);
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantInitWorkflow error", t);
        }
    }

    public static boolean onAssistantStartWorkflow(
            final AssistantController ctrl, final RayNeoAiConfig config, RayNeoWorkflowParams params) {
        try {
            String uuid = config != null ? safeStr(config.getUuid()) : safeStr(lastSessionUuid);
            if (!uuid.isEmpty()) lastSessionUuid = uuid;
            final SessionState st = getOrCreateSession(uuid);
            st.uuid = uuid;
            if (config != null) st.aiConfig = config;

            if (!st.internalRestartingRound) {
                finishOfficialSkillLogIfRunning(st);
                cancelTurnResources(st, "新对话 startWorkflow");
            }
            resetTurnFlags(st);

            // Always return false so nativeStartWorkflow initializes ASR/VAD/OfflineAS/RayClaw
            // (and handles RayNeoWorkflowParamType.TTS broadcast announcements via stock TTS).
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantStartWorkflow error", t);
            return false;
        }
    }

    public static void onAssistantStopWorkflow(AssistantController ctrl, RayNeoAiConfig config) {
        try {
            String uuid = config != null ? safeStr(config.getUuid()) : "";
            SessionState st = resolveSession(uuid);
            if (st != null) {
                if (st.internalStoppingNative) {
                    if (st.activeLogId > 0L) {
                        CustomAiCallLog.addEvent(st.activeLogId, "INTERNAL_NATIVE_STOP_PASSTHROUGH");
                    }
                    return;
                }
                finishOfficialSkillLogIfRunning(st);
                String caller = summarizeCaller();
                if (st.activeLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeLogId, "STOP_WORKFLOW_EXT:" + caller);
                }
                st.turnSeq.incrementAndGet();
                cancelTurnResources(st, "外部调用 stopWorkflow (" + caller + ")");
            } else {
                RayNeoOriginalTtsBridge.stop();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantStopWorkflow error", t);
        }
    }

    public static void onAssistantDestroyWorkflow(AssistantController ctrl, RayNeoAiConfig config) {
        try {
            String uuid = config != null ? safeStr(config.getUuid()) : "";
            SessionState st = SESSIONS.remove(uuid);
            if (st == null && !uuid.isEmpty()) {
                st = resolveSession(uuid);
                if (st != null) SESSIONS.remove(st.uuid);
            }
            if (st != null) {
                finishOfficialSkillLogIfRunning(st);
                String caller = summarizeCaller();
                if (st.activeLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeLogId, "DESTROY_WORKFLOW:" + caller);
                }
                st.turnSeq.incrementAndGet();
                cancelTurnResources(st, "销毁会话 destroyWorkflow (" + caller + ")");
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantDestroyWorkflow error", t);
        }
    }

    /**
     * MOD-03Q: Handles manual or automatic startNextRound() when C++ native workflow
     * was cleanly stopped earlier to protect our CustomAI response.
     */
    public static boolean onAssistantStartNextRound(AssistantController ctrl) {
        try {
            SessionState st = resolveSession(null);
            if (st == null || !isInterceptingGlassesSession(st) || st.aiConfig == null) {
                return false;
            }
            if (st.activeLogId > 0L) {
                CustomAiCallLog.addEvent(st.activeLogId, "START_NEXT_ROUND(" + summarizeCaller() + ")");
            }
            st.turnSeq.incrementAndGet();
            cancelTurnResources(st, "开启新一轮对话 startNextRound");

            if (st.nativeWorkflowStopped && ctrl != null) {
                resetTurnFlags(st);
                st.internalRestartingRound = true;
                try {
                    ctrl.startWorkflow(st.aiConfig, null);
                } finally {
                    st.internalRestartingRound = false;
                }
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantStartNextRound error", t);
        }
        return false;
    }

    /**
     * MOD-03E: Called on every nativeOnAsrResult from native C++ ASR (stock ASR runs untouched).
     */
    public static void onNativeAsrResult(
            final AssistantController ctrl,
            final String uuid,
            final String asrText,
            final boolean finished,
            final String sid
    ) {
        try {
            if (!finished || asrText == null || asrText.trim().isEmpty()) return;
            final Application app = appContext;
            if (app == null) return;
            final CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptGlassesVoice) return;

            String cleanUuid = safeStr(uuid);
            if (!cleanUuid.isEmpty()) lastSessionUuid = cleanUuid;
            final SessionState st = getOrCreateSession(cleanUuid);
            st.uuid = cleanUuid;
            if (st.listener == null) return;

            final String cleanQuery = asrText.trim();

            // Check BEFORE cancelling if an offline skill/tool already claimed this utterance right before ASR final
            if (st.handledByLocalSkill && SystemClock.uptimeMillis() - st.lastSkillPassMs < SKILL_PRE_ASR_WINDOW_MS) {
                CustomAiCallLog.updateQueryIfEmpty(st.activeSkillLogId, cleanQuery);
                CustomAiCallLog.addEvent(st.activeSkillLogId, "ASR_FINAL(\"" + CustomAiCallLog.previewSingleLine(cleanQuery, 30) + "\")");
                return;
            }

            finishOfficialSkillLogIfRunning(st);
            cancelTurnResources(st, "收到新 ASR 终稿，准备新一轮");
            st.asrFinished = true;
            st.handledByLocalSkill = false;
            st.customTurnActive = false;
            st.lastSkillSummary = "";
            synchronized (st.skillAnswerAcc) {
                st.skillAnswerAcc.setLength(0);
            }

            final int seq = st.turnSeq.incrementAndGet();
            final String safeSid = sid == null ? "" : sid;

            AiAgentConfig agent = st.aiConfig != null ? st.aiConfig.getAiAgentConfig() : null;
            String devModel = st.aiConfig != null ? safeStr(st.aiConfig.getDeviceModel()) : "";
            boolean enableTts = agent != null && agent.getEnableTTS();
            boolean autoNext = agent != null && agent.getAutoNextRound();

            // ===== System Media Control & Active Song Grabber (切歌/上一首/暂停/继续播放/正在放什么歌) =====
            final int mediaAction = SystemMediaBridge.matchCommand(cleanQuery);
            if (mediaAction != 0) {
                st.customTurnActive = true;
                String actLabel = SystemMediaBridge.actionLabel(mediaAction);
                final long mediaLogId = CustomAiCallLog.startSystemMediaCall(
                        app, "🎵 眼镜播控 [" + actLabel + "]", cleanQuery, devModel, enableTts, autoNext);
                st.activeLogId = mediaLogId;
                maybeShowCallToast(app, cfg, "🎵 " + actLabel, "MediaSession");

                // Stop native C++ workflow immediately so RayClaw doesn't misfire another tool!
                stopNativeWorkflowCleanly(ctrl, st, mediaLogId);

                EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        executeSystemMediaTurn(ctrl, st, seq, cleanQuery, safeSid, mediaAction, mediaLogId);
                    }
                });
                return;
            }

            if (cfg.passThroughOfficialSkills) {
                // ===== Tier A: Immediate 0ms Pass-Through for Known Official Skills & Online Real-Time Queries =====
                String skillCategory = classifySkillOrRealtimeIntent(cleanQuery);
                if (skillCategory != null) {
                    st.handledByLocalSkill = true;
                    st.customTurnActive = false;
                    st.lastSkillPassMs = SystemClock.uptimeMillis();
                    st.lastSkillSummary = skillCategory;
                    long skillLogId = CustomAiCallLog.startOfficialSkillCall(
                            app,
                            "👓 眼镜技能 [" + skillCategory + "]",
                            cleanQuery,
                            devModel,
                            enableTts,
                            autoNext,
                            "TIER_A_DIRECT_SKILL(" + skillCategory + " -> 直通雷鸟官方引擎)"
                    );
                    st.activeSkillLogId = skillLogId;
                    Log.i(TAG, "Tier-A direct official pass-through [" + skillCategory + "]: " + cleanQuery);
                    return;
                }

                // ===== Tier B: General Chat with Cloud Skill Guard (RayClawToolCall + Non-Empty NLP Gate) =====
                final long logId = CustomAiCallLog.startCall(
                        app, "👓 眼镜语音", cfg, cleanQuery, devModel, enableTts, autoNext);
                st.activeLogId = logId;
                int waitMs = Math.max(500, Math.min(10000, cfg.skillRouteTimeoutMs));
                CustomAiCallLog.addEvent(logId, "ROUTE_PENDING(wait<=" + waitMs + "ms, prefetch CustomLLM)");
                st.customTurnActive = true;
                final RouteGate gate = new RouteGate();
                st.routeGate = gate;

                EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        executeAssistantTurn(ctrl, st, seq, cleanQuery, safeSid, logId, gate);
                    }
                });

                Runnable timeoutTask = new Runnable() {
                    @Override
                    public void run() {
                        if (st.turnSeq.get() != seq) return;
                        decideCustom(ctrl, st, gate, logId, "ROUTE_TIMEOUT(" + waitMs + "ms no tool/skill)");
                    }
                };
                st.routeTimeoutTask = timeoutTask;
                MAIN_HANDLER.postDelayed(timeoutTask, waitMs);
                return;
            }

            // Fallback when passThroughOfficialSkills == false (only local offline hardware grace window)
            Runnable triggerCustom = new Runnable() {
                @Override
                public void run() {
                    if (st.turnSeq.get() != seq || st.handledByLocalSkill) return;
                    CustomAiConfig latestCfg = CustomAiConfig.load(app);
                    if (!latestCfg.enabled || !latestCfg.interceptGlassesVoice) return;

                    st.customTurnActive = true;
                    final long logId = CustomAiCallLog.startCall(
                            app, "👓 眼镜语音", latestCfg, cleanQuery, devModel, enableTts, autoNext);
                    st.activeLogId = logId;
                    maybeShowCallToast(app, latestCfg, "👓 眼镜语音", latestCfg.model);

                    stopNativeWorkflowCleanly(ctrl, st, logId);

                    EXECUTOR.execute(new Runnable() {
                        @Override
                        public void run() {
                            executeAssistantTurn(ctrl, st, seq, cleanQuery, safeSid, logId, null);
                        }
                    });
                }
            };

            st.pendingStartTask = triggerCustom;
            long delay = cfg.keepLocalHardwareControl ? LOCAL_SKILL_GRACE_MS : 0L;
            if (delay > 0L) {
                MAIN_HANDLER.postDelayed(triggerCustom, delay);
            } else {
                triggerCustom.run();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onNativeAsrResult error", t);
        }
    }

    /**
     * MOD-03H: Called at top of AssistantController.nativeOnRayClawToolCallInfo.
     * Whenever RayClaw invokes a cloud or client tool (weather, web_search, create_task,
     * set_reminder, music, navigation, memory, etc.), immediately route the turn to the
     * stock official RayNeo pipeline and return false so Flutter receives the tool event!
     */
    public static boolean onNativeRayClawToolCall(
            AssistantController ctrl, String uuid, RayClawToolCallInfo info) {
        try {
            Application app = appContext;
            if (app == null) return false;
            CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptGlassesVoice) return false;

            String toolName = info != null ? safeStr(info.getToolName()) : "";
            String displayName = info != null ? safeStr(info.getDisplayName()) : "";
            String status = info != null ? safeStr(info.getStatus()) : "";
            String callId = info != null ? safeStr(info.getCallId()) : "";
            String desc = "tool=" + toolName
                    + (displayName.isEmpty() ? "" : "(" + displayName + ")")
                    + (status.isEmpty() ? "" : " status=" + status)
                    + (callId.isEmpty() ? "" : " id=" + callId);

            Log.i(TAG, "onNativeRayClawToolCall: " + desc);
            SessionState st = resolveSession(uuid);

            if (st != null && st.handledByLocalSkill) {
                if (!toolName.isEmpty() || !displayName.isEmpty()) {
                    st.lastSkillSummary = displayName.isEmpty() ? toolName : (displayName + " [" + toolName + "]");
                }
                if (st.activeSkillLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeSkillLogId, "RAYCLAW_TOOL(" + desc + ")");
                }
                return false;
            }

            if (cfg.passThroughOfficialSkills) {
                if (st == null) {
                    st = getOrCreateSession(uuid);
                }
                RouteGate gate = st.routeGate;
                // Route to official skill if gate is PENDING or not created yet
                if (gate == null || gate.state() == RouteGate.PENDING) {
                    routeToOfficialSkill(st, gate, "RayClawTool: " + desc);
                    if (!toolName.isEmpty() || !displayName.isEmpty()) {
                        st.lastSkillSummary = displayName.isEmpty() ? toolName : (displayName + " [" + toolName + "]");
                    }
                    return false;
                }
            }

            if (st != null && st.activeLogId > 0L) {
                CustomAiCallLog.addEvent(st.activeLogId, "SUPPRESSED(RayClawTool " + desc + ")");
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "onNativeRayClawToolCall error", t);
            return false;
        }
    }

    /** Route decision: custom LLM wins this turn -> flush buffered output and stop native cloud workflow. */
    private static void decideCustom(
            AssistantController ctrl, SessionState st, RouteGate gate, long logId, String reason) {
        if (st == null || gate == null) return;
        if (st.routeTimeoutTask != null) {
            MAIN_HANDLER.removeCallbacks(st.routeTimeoutTask);
            st.routeTimeoutTask = null;
        }
        if (!gate.openCustom()) return;
        CustomAiCallLog.addEvent(logId, "ROUTE->CUSTOM_LLM: " + reason);
        Application app = appContext;
        if (app != null) {
            CustomAiConfig cfg = CustomAiConfig.load(app);
            maybeShowCallToast(app, cfg, "👓 眼镜语音", cfg.model);
        }
        if (ctrl != null) {
            final AssistantController c = ctrl;
            final SessionState s = st;
            final long lid = logId;
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    stopNativeWorkflowCleanly(c, s, lid);
                }
            });
        }
    }

    /** Route decision: official RayNeo skill/tool wins -> cancel Custom LLM prefetch, let stock pipeline execute! */
    private static void routeToOfficialSkill(SessionState st, RouteGate gate, String desc) {
        if (st == null) return;
        if (gate != null && !gate.routeSkill()) return;
        if (st.routeTimeoutTask != null) {
            MAIN_HANDLER.removeCallbacks(st.routeTimeoutTask);
            st.routeTimeoutTask = null;
        }
        if (st.pendingStartTask != null) {
            MAIN_HANDLER.removeCallbacks(st.pendingStartTask);
            st.pendingStartTask = null;
        }
        st.handledByLocalSkill = true;
        st.customTurnActive = false;
        st.lastSkillPassMs = SystemClock.uptimeMillis();
        st.turnSeq.incrementAndGet();
        if (st.activeCancel != null) {
            st.activeCancel.cancel();
            st.activeCancel = null;
        }

        long existingCustomLog = st.activeLogId;
        st.activeLogId = 0L;
        if (existingCustomLog > 0L) {
            // Convert the existing call log entry in-place into an official skill trace entry!
            CustomAiCallLog.markRouteConvertedToOfficialSkill(appContext, existingCustomLog, desc);
            st.activeSkillLogId = existingCustomLog;
        } else if (st.activeSkillLogId <= 0L && appContext != null) {
            AiAgentConfig agent = st.aiConfig != null ? st.aiConfig.getAiAgentConfig() : null;
            String devModel = st.aiConfig != null ? safeStr(st.aiConfig.getDeviceModel()) : "";
            boolean enableTts = agent != null && agent.getEnableTTS();
            boolean autoNext = agent != null && agent.getAutoNextRound();
            st.activeSkillLogId = CustomAiCallLog.startOfficialSkillCall(
                    appContext, "👓 眼镜离线/技能", "", devModel, enableTts, autoNext,
                    "ROUTE->OFFICIAL_SKILL (" + desc + ")");
        } else if (st.activeSkillLogId > 0L) {
            CustomAiCallLog.addEvent(st.activeSkillLogId, "OFFICIAL_SKILL_EVENT(" + desc + ")");
        }
    }

    private static void recordOfficialSkillNlp(SessionState st, NlpResult r) {
        if (st == null || r == null) return;
        long lid = st.activeSkillLogId;
        String q = safeStr(r.getQuery());
        if (lid > 0L && !q.isEmpty()) {
            CustomAiCallLog.updateQueryIfEmpty(lid, q);
        }

        String domain = safeStr(r.getDomain()).trim();
        String intent = safeStr(r.getIntent()).trim();
        String cmdDesc = "";
        try {
            NlpCommand c = r.getCommand();
            if (c != null && !safeStr(c.getName()).trim().isEmpty()) {
                cmdDesc = safeStr(c.getName()).trim();
                Map p = c.getParams();
                if (p != null && !p.isEmpty()) {
                    cmdDesc += " " + p;
                }
            }
        } catch (Throwable ignored) {}

        if (!cmdDesc.isEmpty()) {
            st.lastSkillSummary = (domain.isEmpty() ? "" : (domain + "/")) + cmdDesc;
        } else if (!domain.isEmpty() && !"chat".equalsIgnoreCase(domain) && !"llm".equalsIgnoreCase(domain)) {
            st.lastSkillSummary = domain + (intent.isEmpty() ? "" : ("/" + intent))
                    + (r.getOffline() ? " [offline]" : "");
        }

        if (lid > 0L) {
            CustomAiCallLog.addEvent(lid, "OFFICIAL_NLP(" + describeNlp(r) + ")");
        }

        String ans = safeStr(r.getAnswer());
        if (!ans.isEmpty()) {
            synchronized (st.skillAnswerAcc) {
                if (st.skillAnswerAcc.length() < 8000) {
                    st.skillAnswerAcc.append(ans);
                }
            }
            if (lid > 0L) {
                CustomAiCallLog.onTextDelta(lid, ans);
            }
        }

        if (r.getFinished()) {
            finishOfficialSkillLogIfRunning(st);
        }
    }

    private static void finishOfficialSkillLogIfRunning(SessionState st) {
        if (st == null) return;
        long lid = st.activeSkillLogId;
        if (lid <= 0L) return;
        st.activeSkillLogId = 0L;
        String acc;
        synchronized (st.skillAnswerAcc) {
            acc = st.skillAnswerAcc.toString().trim();
        }
        String summary = safeStr(st.lastSkillSummary).trim();
        String finalMsg;
        if (!acc.isEmpty() && !summary.isEmpty()) {
            finalMsg = "↪ [" + summary + "] " + acc;
        } else if (!acc.isEmpty()) {
            finalMsg = acc;
        } else if (!summary.isEmpty()) {
            finalMsg = "↪ [官方指令已下发执行] " + summary;
        } else {
            finalMsg = "↪ [官方技能执行完成]";
        }
        CustomAiCallLog.finishSuccess(appContext, lid, finalMsg);
    }

    private static boolean isOfficialSkillResult(NlpResult r) {
        if (r == null) return false;
        String domain = safeStr(r.getDomain()).trim().toLowerCase(Locale.ROOT);
        // Stock music domain on RayNeo io is unsupported for third-party players; SystemMediaBridge handles it!
        if ("music".equals(domain)) return false;
        if (r.getOffline()) return true;
        String intent = safeStr(r.getIntent()).trim().toLowerCase(Locale.ROOT);
        String cmd = "";
        try {
            NlpCommand c = r.getCommand();
            if (c != null) cmd = safeStr(c.getName()).trim().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        if (!cmd.isEmpty() && !"chat".equals(cmd) && !"askai".equals(cmd)) return true;
        boolean chatLike = domain.isEmpty() || domain.contains("chat")
                || "fake.unknown".equals(domain) || "agent".equals(domain)
                || "askai".equals(domain) || "llm".equals(domain);
        if (chatLike) {
            return "exit".equals(intent) || "quit".equals(intent);
        }
        return true;
    }

    private static String describeNlp(NlpResult r) {
        if (r == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("domain=").append(safeStr(r.getDomain()))
                .append(", intent=").append(safeStr(r.getIntent()))
                .append(", sub=").append(safeStr(r.getSub()))
                .append(", offline=").append(r.getOffline())
                .append(", fin=").append(r.getFinished());
        try {
            NlpCommand c = r.getCommand();
            if (c != null) {
                sb.append(", cmd=").append(safeStr(c.getName()));
                Map p = c.getParams();
                if (p != null && !p.isEmpty()) sb.append(", params=").append(p);
                String rid = c.getCommandRequestId();
                if (rid != null && !rid.trim().isEmpty()) sb.append(", reqId=").append(rid);
            }
        } catch (Throwable ignored) {}
        String ans = safeStr(r.getAnswer());
        if (!ans.isEmpty()) sb.append(", answer=").append(CustomAiCallLog.previewSingleLine(ans, 40));
        return sb.toString();
    }

    /**
     * MOD-03F: Called at top of AssistantController.nativeOnNlpResult.
     * Return true to suppress native cloud NLP result; return false to let official skill/command execute.
     */
    public static boolean shouldInterceptNativeNlpResult(
            AssistantController ctrl, String uuid, NlpResult result) {
        try {
            Application app = appContext;
            if (app == null) return false;
            CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptGlassesVoice) return false;

            SessionState st = resolveSession(uuid);

            // A skill/weather/tool turn already handed over to stock pipeline: trace & let through!
            if (st != null && st.handledByLocalSkill) {
                recordOfficialSkillNlp(st, result);
                return false;
            }

            if (cfg.passThroughOfficialSkills) {
                RouteGate gate = st != null ? st.routeGate : null;
                boolean skill = isOfficialSkillResult(result);
                if (gate != null && gate.state() == RouteGate.PENDING) {
                    if (skill) {
                        String desc = describeNlp(result);
                        routeToOfficialSkill(st, gate, desc);
                        recordOfficialSkillNlp(st, result);
                        Log.i(TAG, "Route -> official skill: " + desc);
                        return false;
                    }
                    // Ignore empty stream-open header frames so pending RayClawToolCall / NlpCommand isn't cut off!
                    String ans = result != null ? safeStr(result.getAnswer()).trim() : "";
                    String spk = result != null ? safeStr(result.getSpoken()).trim() : "";
                    boolean fin = result != null && result.getFinished();
                    if (ans.isEmpty() && spk.isEmpty() && !fin) {
                        if (st.activeLogId > 0L) {
                            CustomAiCallLog.addEvent(st.activeLogId, "HOLD_GATE(EmptyNativeNlp " + describeNlp(result) + ")");
                        }
                        return true;
                    }
                    decideCustom(ctrl, st, gate, st.activeLogId, "CLOUD_CHAT(" + describeNlp(result) + ")");
                    return true;
                }
                if (gate == null && skill) {
                    // Offline/skill NlpResult arrived BEFORE ASR final: claim turn immediately!
                    if (st == null) {
                        st = getOrCreateSession(uuid);
                    }
                    routeToOfficialSkill(st, null, describeNlp(result));
                    recordOfficialSkillNlp(st, result);
                    Log.i(TAG, "Pre-ASR route -> official skill: " + describeNlp(result));
                    return false;
                }
                if (st != null && st.activeLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeLogId, "SUPPRESSED(NativeNlp " + describeNlp(result) + ")");
                }
                return true;
            }

            if (cfg.keepLocalHardwareControl && isLocalHardwareResult(result)) {
                if (st == null) {
                    st = getOrCreateSession(uuid);
                }
                routeToOfficialSkill(st, null, "LocalHardware: " + describeNlp(result));
                recordOfficialSkillNlp(st, result);
                Log.i(TAG, "Pass-through local hardware command: " + describeNlp(result));
                return false;
            }
            if (st != null && st.activeLogId > 0L) {
                CustomAiCallLog.addEvent(st.activeLogId, "SUPPRESSED(NativeNlp)");
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "shouldInterceptNativeNlpResult error", t);
            return false;
        }
    }

    /**
     * Shared across MOD-03G/I/J/K/N/P (DeepThink, TtsStatus, ResponseComplete, Error,
     * AudioRecordStart, Notify) to stay below the DEX 65,535 method_ids limit in classes5.dex.
     */
    public static boolean shouldSuppressNativePostAsr(AssistantController ctrl, String uuid) {
        try {
            SessionState st = resolveSession(uuid);
            if (st != null && st.handledByLocalSkill) {
                if (st.activeSkillLogId > 0L) {
                    String cb = detectDirectNativeCaller();
                    if ("nativeOnResponseComplete".equals(cb)) {
                        finishOfficialSkillLogIfRunning(st);
                    } else if ("nativeOnTtsStatusChange".equals(cb)) {
                        CustomAiCallLog.markTtsPlaying(st.activeSkillLogId);
                    } else if ("nativeOnError".equals(cb)) {
                        CustomAiCallLog.addEvent(st.activeSkillLogId, "OFFICIAL_NATIVE_ERR");
                    }
                }
                return false;
            }
        } catch (Throwable ignored) {}
        return shouldSuppressAssistantNative(uuid, "NativePostAsr", true);
    }

    /**
     * Shared across MOD-03L/M (SpeechStatus, VadStatusChange) with native status code logging.
     */
    public static boolean shouldSuppressNativeIntStatus(AssistantController ctrl, String uuid, int status) {
        return shouldSuppressAssistantNative(uuid, "NativeStatus=" + status, true);
    }

    public static boolean shouldSuppressNativeAudioRecordEnd(AssistantController ctrl, String uuid) {
        try {
            Application app = appContext;
            if (app == null) return false;
            CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptGlassesVoice) return false;

            SessionState st = resolveSession(uuid);
            if (st == null || st.handledByLocalSkill) return false;

            // Let the first onAudioRecordEnd pass through once so wl0/d$c pauses BLE mic forwarding
            if (!st.recordEndForwarded) {
                st.recordEndForwarded = true;
                if (st.activeLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeLogId, "PASS_ONCE(AudioRecordEnd)");
                }
                return false;
            }
            if (st.asrFinished || st.customTurnActive || st.nativeWorkflowStopped) {
                if (st.activeLogId > 0L) {
                    CustomAiCallLog.addEvent(st.activeLogId, "SUPPRESSED(DuplicateAudioRecordEnd)");
                }
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldSuppressAssistantNative(String uuid, String label, boolean requireAsrFinished) {
        try {
            Application app = appContext;
            if (app == null) return false;
            CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptGlassesVoice) return false;
            SessionState st = resolveSession(uuid);
            if (st == null) return !requireAsrFinished;
            if (st.handledByLocalSkill) return false;
            boolean active = st.asrFinished || st.customTurnActive || st.nativeWorkflowStopped;
            if (requireAsrFinished && !active) return false;
            if (st.activeLogId > 0L && label != null) {
                CustomAiCallLog.addEvent(st.activeLogId, "SUPPRESSED(" + label + ")");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void stopNativeWorkflowCleanly(AssistantController ctrl, SessionState st, long logId) {
        if (ctrl == null || st == null || st.aiConfig == null || st.nativeWorkflowStopped || st.handledByLocalSkill) {
            return;
        }
        st.internalStoppingNative = true;
        try {
            ctrl.stopWorkflow(st.aiConfig);
            st.nativeWorkflowStopped = true;
            CustomAiCallLog.addEvent(logId, "NATIVE_WORKFLOW_STOPPED_CLEANLY");
        } catch (Throwable t) {
            CustomAiCallLog.addEvent(logId, "NATIVE_STOP_ERR:" + safeStr(t.getMessage()));
        } finally {
            st.internalStoppingNative = false;
        }
    }

    private static void executeSystemMediaTurn(
            final AssistantController ctrl,
            final SessionState st,
            final int seq,
            final String query,
            final String sessionId,
            final int mediaAction,
            final long logId
    ) {
        final AiResultListener listener = st.listener;
        final RayNeoAiConfig aiConfig = st.aiConfig;
        final Application app = appContext;
        if (listener == null || app == null) {
            CustomAiCallLog.finishError(app, logId, "监听器或上下文为空");
            return;
        }
        final CustomAiConfig cfg = CustomAiConfig.load(app);
        AiAgentConfig agent = aiConfig != null ? aiConfig.getAiAgentConfig() : null;
        final String dialogId = safeStr(agent != null ? agent.getDialogId() : null);
        final boolean enableTts = agent != null && agent.getEnableTTS();
        final boolean autoNextRound = agent != null && agent.getAutoNextRound();
        final boolean allowAutoNext = autoNextRound && cfg.autoContinueAfterTts;

        postToNativeCb(new Runnable() {
            @Override
            public void run() {
                if (st.turnSeq.get() != seq) return;
                listener.onSpeechStatus(SpeechStatus.THINKING);
            }
        });

        final SystemMediaBridge.ActionResult res = SystemMediaBridge.execute(app, mediaAction, logId);
        if (st.turnSeq.get() != seq) {
            CustomAiCallLog.finishInterrupted(app, logId, "媒体指令执行期间被新轮打断");
            return;
        }

        final String reply = res.spokenReply;
        CustomAiCallLog.onTextDelta(logId, reply);
        ConversationMemory.appendTurn(dialogId, query, reply, cfg.maxHistoryTurns);

        postToNativeCb(new Runnable() {
            @Override
            public void run() {
                if (st.turnSeq.get() != seq) return;
                listener.onSpeechStatus(SpeechStatus.RESPONDING);
                listener.onNlpResult(buildNlpResult(dialogId, sessionId, query, reply, true, allowAutoNext, seq));
                listener.onResponseComplete();

                final RayNeoOriginalTtsBridge.CompletionCallback onTtsDone =
                        new RayNeoOriginalTtsBridge.CompletionCallback() {
                            @Override
                            public void onComplete(boolean playedNormally) {
                                if (st.activeLogId == logId) {
                                    st.activeLogId = 0L;
                                }
                                CustomAiCallLog.finishSuccess(app, logId, reply + " [" + res.debugTrace + "]");
                                // Ensure track plays (or stays paused) once voice assistant SCO/AudioFocus releases!
                                if (MAIN_HANDLER != null) {
                                    MAIN_HANDLER.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            EXECUTOR.execute(new Runnable() {
                                                @Override
                                                public void run() {
                                                    SystemMediaBridge.enforcePostAnnouncementState(
                                                            app, mediaAction, res.targetPackage, logId);
                                                }
                                            });
                                        }
                                    }, 380L);
                                }
                                if (playedNormally && allowAutoNext && ctrl != null && MAIN_HANDLER != null) {
                                    Runnable nextTask = new Runnable() {
                                        @Override
                                        public void run() {
                                            if (st.turnSeq.get() != seq) return;
                                            try {
                                                ctrl.startNextRound();
                                            } catch (Throwable ignored) {}
                                        }
                                    };
                                    st.pendingNextRoundTask = nextTask;
                                    MAIN_HANDLER.postDelayed(nextTask, AUTO_NEXT_ROUND_DELAY_MS);
                                }
                            }
                        };

                if (enableTts && !reply.isEmpty()) {
                    RayNeoOriginalTtsBridge.speak(aiConfig, reply, sessionId, logId, listener, onTtsDone);
                } else {
                    onTtsDone.onComplete(false);
                }
            }
        });
    }

    private static void executeAssistantTurn(
            final AssistantController ctrl,
            final SessionState st,
            final int seq,
            final String query,
            final String sessionId,
            final long logId,
            final RouteGate gate
    ) {
        final AiResultListener listener = st.listener;
        final RayNeoAiConfig aiConfig = st.aiConfig;
        if (listener == null) {
            CustomAiCallLog.finishError(appContext, logId, "AiResultListener 为空");
            return;
        }

        final Application app = appContext;
        if (app == null) return;
        final CustomAiConfig cfg = CustomAiConfig.load(app);

        AiAgentConfig agent = aiConfig != null ? aiConfig.getAiAgentConfig() : null;
        final String dialogId = safeStr(agent != null ? agent.getDialogId() : null);
        final boolean enableTts = agent != null && agent.getEnableTTS();
        final boolean autoNextRound = agent != null && agent.getAutoNextRound();
        final int round = seq;

        if (!cfg.hasApiKey() && !isCustomAuthProvided(cfg.customHeadersJson)) {
            final String hint = "未配置自定义 API Key。请在手机雷鸟 App 点击 ⚡LLM（或连按 3 次音量+）填写 API Key 后重试。";
            CustomAiCallLog.finishError(app, logId, "未配置 API Key");
            emitGated(gate, new Runnable() {
                @Override
                public void run() {
                    if (st.turnSeq.get() != seq) return;
                    listener.onSpeechStatus(SpeechStatus.RESPONDING);
                    listener.onNlpResult(buildNlpResult(dialogId, sessionId, query, hint, true, false, round));
                    listener.onResponseComplete();
                }
            });
            return;
        }

        final OpenAiApiClient.CancelToken cancel = new OpenAiApiClient.CancelToken();
        st.activeCancel = cancel;

        emitGated(gate, new Runnable() {
            @Override
            public void run() {
                if (st.turnSeq.get() != seq) return;
                listener.onSpeechStatus(SpeechStatus.THINKING);
            }
        });

        final boolean[] responded = {false};
        final StringBuilder reasoningAcc = new StringBuilder();

        try {
            List<ConversationMemory.Message> history =
                    ConversationMemory.getHistory(dialogId, cfg.maxHistoryTurns);

            OpenAiApiClient.EndpointConfig ep = cfg.toEndpointConfig();
            String mediaHint = SystemMediaBridge.buildPromptContextLine(app);
            if (!mediaHint.isEmpty()) {
                ep.extraContextHint = mediaHint;
            }

            String fullReply = OpenAiApiClient.streamChat(
                    ep,
                    history,
                    query,
                    new OpenAiApiClient.StreamCallback() {
                        @Override
                        public void onReasoningDelta(final String delta) {
                            if (delta == null || delta.isEmpty() || st.turnSeq.get() != seq) return;
                            CustomAiCallLog.onReasoningDelta(logId, delta);
                            reasoningAcc.append(delta);
                            final String snapshot = reasoningAcc.toString();
                            emitGated(gate, new Runnable() {
                                @Override
                                public void run() {
                                    if (st.turnSeq.get() != seq) return;
                                    listener.onDeepThinkStatus("thinking", snapshot);
                                }
                            });
                        }

                        @Override
                        public void onTextDelta(final String delta) {
                            if (delta == null || delta.isEmpty() || st.turnSeq.get() != seq) return;
                            CustomAiCallLog.onTextDelta(logId, delta);
                            final boolean first = !responded[0];
                            responded[0] = true;
                            emitGated(gate, new Runnable() {
                                @Override
                                public void run() {
                                    if (st.turnSeq.get() != seq) return;
                                    if (first) {
                                        listener.onSpeechStatus(SpeechStatus.RESPONDING);
                                    }
                                    // Emit ONLY incremental delta; RayNeo's u2 coalescer appends chunks
                                    listener.onNlpResult(buildNlpResult(
                                            dialogId, sessionId, query, delta, false,
                                            autoNextRound && cfg.autoContinueAfterTts, round));
                                }
                            });
                        }
                    },
                    cancel
            );

            if (cancel.isCancelled() || st.turnSeq.get() != seq) {
                if (!st.handledByLocalSkill) {
                    CustomAiCallLog.finishInterrupted(app, logId, "请求已被取消或新轮覆盖");
                }
                return;
            }

            final String trimmedReply = fullReply == null ? "" : fullReply.trim();
            ConversationMemory.appendTurn(dialogId, query, trimmedReply, cfg.maxHistoryTurns);
            CustomAiCallLog.addEvent(logId, "HTTP_DONE(chars=" + trimmedReply.length() + ")");

            final boolean needFallbackText = !responded[0] && !trimmedReply.isEmpty();
            emitGated(gate, new Runnable() {
                @Override
                public void run() {
                    if (st.turnSeq.get() != seq) return;
                    if (!responded[0]) {
                        listener.onSpeechStatus(SpeechStatus.RESPONDING);
                    }
                    final boolean allowAutoNext = autoNextRound && cfg.autoContinueAfterTts;
                    listener.onNlpResult(buildNlpResult(
                            dialogId,
                            sessionId,
                            query,
                            needFallbackText ? trimmedReply : "",
                            true,
                            allowAutoNext,
                            round));
                    listener.onResponseComplete();

                    final RayNeoOriginalTtsBridge.CompletionCallback onTtsDone =
                            new RayNeoOriginalTtsBridge.CompletionCallback() {
                                @Override
                                public void onComplete(boolean playedNormally) {
                                    if (st.activeLogId == logId) {
                                        st.activeLogId = 0L;
                                    }
                                    if (st.turnSeq.get() != seq || cancel.isCancelled()) {
                                        if (!st.handledByLocalSkill) {
                                            CustomAiCallLog.finishInterrupted(app, logId, "播报期间被新操作打断");
                                        }
                                        return;
                                    }
                                    CustomAiCallLog.finishSuccess(app, logId, trimmedReply);
                                    if (playedNormally && allowAutoNext && ctrl != null) {
                                        Runnable nextTask = new Runnable() {
                                            @Override
                                            public void run() {
                                                if (st.turnSeq.get() != seq || cancel.isCancelled()) return;
                                                CustomAiCallLog.addEvent(logId, "AUTO_NEXT_ROUND_TRIGGERED");
                                                try {
                                                    ctrl.startNextRound();
                                                } catch (Throwable ignored) {}
                                            }
                                        };
                                        st.pendingNextRoundTask = nextTask;
                                        MAIN_HANDLER.postDelayed(nextTask, AUTO_NEXT_ROUND_DELAY_MS);
                                    }
                                }
                            };

                    if (enableTts && !trimmedReply.isEmpty()) {
                        RayNeoOriginalTtsBridge.speak(aiConfig, trimmedReply, sessionId, logId, listener, onTtsDone);
                    } else {
                        CustomAiCallLog.addEvent(logId, "TTS_SKIPPED(enableTts=" + enableTts + ")");
                        onTtsDone.onComplete(false);
                    }
                }
            });
        } catch (CancellationException ignored) {
            if (!st.handledByLocalSkill) {
                CustomAiCallLog.finishInterrupted(app, logId, "HTTP 请求被取消");
            }
        } catch (final Throwable err) {
            if (cancel.isCancelled() || st.turnSeq.get() != seq || st.handledByLocalSkill) {
                if (!st.handledByLocalSkill) {
                    CustomAiCallLog.finishInterrupted(app, logId, "异常前已被取消");
                }
                return;
            }
            Log.e(TAG, "Custom LLM error (glasses)", err);
            final String errText = "请求自定义 AI 失败：" + safeStr(err.getMessage());
            CustomAiCallLog.finishError(app, logId, errText);
            if (st.activeLogId == logId) st.activeLogId = 0L;

            emitGated(gate, new Runnable() {
                @Override
                public void run() {
                    if (st.turnSeq.get() != seq) return;
                    listener.onSpeechStatus(SpeechStatus.RESPONDING);
                    listener.onNlpResult(buildNlpResult(
                            dialogId, sessionId, query, responded[0] ? ("\n[" + errText + "]") : errText,
                            true, false, round));
                    listener.onResponseComplete();
                }
            });
        } finally {
            if (st.activeCancel == cancel) {
                st.activeCancel = null;
            }
        }
    }

    // ==================== MOD-04: Phone AskAITask ====================

    public static boolean onAskAiInit(AskAITask task) {
        try {
            if (task != null) {
                ASK_AI_ROUNDS.putIfAbsent(task, 0);
            }
            Application app = appContext;
            if (app != null) {
                CustomAiConfig cfg = CustomAiConfig.load(app);
                return cfg.enabled && cfg.interceptPhoneAskAi;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean onAskAiStart(final AskAITask task, final RayNeoAskAIParams params) {
        try {
            final Application app = appContext;
            if (app == null || task == null || params == null) return false;
            final CustomAiConfig cfg = CustomAiConfig.load(app);
            if (!cfg.enabled || !cfg.interceptPhoneAskAi) return false;

            final AskAIResultListener listener = task.getListener();
            if (listener == null) return false;

            final String query = safeStr(params.getText());

            // Direct SystemMediaBridge support inside Phone AskAI ("切歌/上一首/暂停/继续播放/正在播放什么歌")
            final int mediaAction = SystemMediaBridge.matchCommand(query);
            if (mediaAction != 0) {
                final String dialogId = safeStr(params.getDialogId());
                final int round = ASK_AI_ROUNDS.getOrDefault(task, 0) + 1;
                ASK_AI_ROUNDS.put(task, round);
                final String roundId = "media_" + System.currentTimeMillis();
                final String actLabel = SystemMediaBridge.actionLabel(mediaAction);
                final long mediaLogId = CustomAiCallLog.startSystemMediaCall(
                        app, "🎵 手机 AskAI [" + actLabel + "]", query, "PhoneApp", false, false);
                EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        final long startMs = SystemClock.uptimeMillis();
                        postToNativeCb(new Runnable() {
                            @Override
                            public void run() {
                                listener.onStartResult(true, "ok");
                            }
                        });
                        final SystemMediaBridge.ActionResult res = SystemMediaBridge.execute(app, mediaAction, mediaLogId);
                        final String reply = res.spokenReply;
                        CustomAiCallLog.onTextDelta(mediaLogId, reply);
                        CustomAiCallLog.finishSuccess(app, mediaLogId, reply + " [" + res.debugTrace + "]");
                        ConversationMemory.appendTurn("ask_" + dialogId, query, reply, cfg.maxHistoryTurns);
                        final long elapsed = SystemClock.uptimeMillis() - startMs;
                        postToNativeCb(new Runnable() {
                            @Override
                            public void run() {
                                listener.onAskAiResponse(buildAskAiResp(
                                        RayNeoAskAIResponseEventType.TEXT_DELTA,
                                        dialogId, query, reply, null, false, round, roundId, "SystemMedia", elapsed));
                                listener.onAskAiResponse(buildAskAiResp(
                                        RayNeoAskAIResponseEventType.FINISHED,
                                        dialogId, query, "", null, true, round, roundId, "SystemMedia", elapsed));
                                listener.onAskAiResponse(buildAskAiResp(
                                        RayNeoAskAIResponseEventType.COMPLETED,
                                        dialogId, query, "", null, true, round, roundId, "SystemMedia", elapsed));
                                listener.onResponseComplete();
                            }
                        });
                    }
                });
                return true;
            }

            // Pass through to stock RayClaw AskAI when official skills/tools/weather/refer attachments are needed!
            if (cfg.passThroughOfficialSkills) {
                List<String> selectedSkills = null;
                boolean hasRefer = false;
                try {
                    selectedSkills = params.getSelectedSkillKeys();
                    hasRefer = params.getReferConfig() != null;
                } catch (Throwable ignored) {}
                boolean hasSelectedSkill = selectedSkills != null && !selectedSkills.isEmpty();
                String skillCat = classifySkillOrRealtimeIntent(query);
                if (hasSelectedSkill || hasRefer || skillCat != null) {
                    String reason = hasSelectedSkill ? ("选中技能:" + selectedSkills)
                            : (hasRefer ? "引用附件/笔记" : skillCat);
                    long lid = CustomAiCallLog.startOfficialSkillCall(
                            app, "📱 手机 AskAI [" + reason + "]", query, "PhoneApp", false, false,
                            "ASK_AI_OFFICIAL_PASSTHROUGH(" + reason + ")");
                    CustomAiCallLog.finishSuccess(app, lid, "↪ 已直通雷鸟官方 AskAI 引擎处理 (" + reason + ")");
                    return false;
                }
            }

            OpenAiApiClient.CancelToken old = ASK_AI_CANCELS.remove(task);
            if (old != null) old.cancel();
            Long oldLogId = ASK_AI_LOG_IDS.remove(task);
            if (oldLogId != null && oldLogId > 0L) {
                CustomAiCallLog.finishInterrupted(app, oldLogId, "被新 AskAI 提问覆盖");
            }

            final OpenAiApiClient.CancelToken cancel = new OpenAiApiClient.CancelToken();
            ASK_AI_CANCELS.put(task, cancel);

            final int round = ASK_AI_ROUNDS.getOrDefault(task, 0) + 1;
            ASK_AI_ROUNDS.put(task, round);

            final String dialogId = safeStr(params.getDialogId());
            final String roundId = "custom_" + System.currentTimeMillis();
            final String modelUsed = safeStr(cfg.model);
            final long logId = CustomAiCallLog.startCall(
                    app, "📱 手机 AskAI", cfg, query, "PhoneApp", false, false);
            ASK_AI_LOG_IDS.put(task, logId);
            maybeShowCallToast(app, cfg, "📱 手机 AskAI", modelUsed);

            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    runAskAiTurn(task, listener, cfg, params, dialogId, query, round, roundId, modelUsed, cancel, logId);
                }
            });
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "onAskAiStart error", t);
            return false;
        }
    }

    public static void onAskAiStop(AskAITask task) {
        try {
            if (task == null) return;
            OpenAiApiClient.CancelToken ct = ASK_AI_CANCELS.remove(task);
            if (ct != null) ct.cancel();
            Long logId = ASK_AI_LOG_IDS.remove(task);
            if (logId != null && logId > 0L && appContext != null) {
                CustomAiCallLog.finishInterrupted(appContext, logId, "手机 AskAI 停止请求");
            }
        } catch (Throwable ignored) {}
    }

    public static void onAskAiRelease(AskAITask task) {
        try {
            if (task == null) return;
            onAskAiStop(task);
            ASK_AI_ROUNDS.remove(task);
        } catch (Throwable ignored) {}
    }

    private static void runAskAiTurn(
            final AskAITask task,
            final AskAIResultListener listener,
            final CustomAiConfig cfg,
            final RayNeoAskAIParams params,
            final String dialogId,
            final String query,
            final int round,
            final String roundId,
            final String modelUsed,
            final OpenAiApiClient.CancelToken cancel,
            final long logId
    ) {
        postToNativeCb(new Runnable() {
            @Override
            public void run() {
                listener.onStartResult(true, "ok");
            }
        });

        if (!cfg.hasApiKey() && !isCustomAuthProvided(cfg.customHeadersJson)) {
            final String msg = "请先点击右上角 ⚡LLM 悬浮按钮（或连按 3 次音量+）配置 API Key。";
            CustomAiCallLog.finishError(appContext, logId, "未配置 API Key");
            ASK_AI_LOG_IDS.remove(task, logId);
            postToNativeCb(new Runnable() {
                @Override
                public void run() {
                    listener.onAskAiResponse(buildAskAiResp(
                            RayNeoAskAIResponseEventType.TEXT_DELTA,
                            dialogId, query, msg, null, false, round, roundId, modelUsed, 0L));
                    listener.onAskAiResponse(buildAskAiResp(
                            RayNeoAskAIResponseEventType.FINISHED,
                            dialogId, query, "", null, true, round, roundId, modelUsed, 0L));
                    listener.onAskAiResponse(buildAskAiResp(
                            RayNeoAskAIResponseEventType.COMPLETED,
                            dialogId, query, "", null, true, round, roundId, modelUsed, 0L));
                    listener.onResponseComplete();
                }
            });
            return;
        }

        final long startMs = SystemClock.uptimeMillis();
        try {
            OpenAiApiClient.EndpointConfig ep = cfg.toEndpointConfig();
            RayNeoAskAIGenerationOptions genOpts = params.getGenerationOptions();
            if (genOpts != null && genOpts.getEnableReasoning() && ep.reasoningEffort.trim().isEmpty()) {
                ep.reasoningEffort = "medium";
            }
            StringBuilder extraHints = new StringBuilder();
            String loc = safeStr(params.getCurrentLocation()).trim();
            if (!loc.isEmpty()) {
                extraHints.append("[当前用户位置：").append(loc).append(']');
            }
            String mediaHint = SystemMediaBridge.buildPromptContextLine(appContext);
            if (!mediaHint.isEmpty()) {
                if (extraHints.length() > 0) extraHints.append('\n');
                extraHints.append(mediaHint);
            }
            if (extraHints.length() > 0) {
                ep.extraContextHint = extraHints.toString();
            }
            List<ConversationMemory.Message> history =
                    ConversationMemory.getHistory("ask_" + dialogId, cfg.maxHistoryTurns);

            String fullReply = OpenAiApiClient.streamChat(
                    ep,
                    history,
                    query,
                    new OpenAiApiClient.StreamCallback() {
                        @Override
                        public void onReasoningDelta(final String delta) {
                            if (delta == null || delta.isEmpty() || cancel.isCancelled()) return;
                            CustomAiCallLog.onReasoningDelta(logId, delta);
                            final long elapsed = SystemClock.uptimeMillis() - startMs;
                            postToNativeCb(new Runnable() {
                                @Override
                                public void run() {
                                    if (cancel.isCancelled()) return;
                                    RayNeoAskAIReasonDetail rd = new RayNeoAskAIReasonDetail();
                                    rd.setStatus("thinking");
                                    rd.setContent(delta);
                                    listener.onAskAiResponse(buildAskAiResp(
                                            RayNeoAskAIResponseEventType.REASONING_DELTA,
                                            dialogId, query, "", rd, false, round, roundId, modelUsed, elapsed));
                                }
                            });
                        }

                        @Override
                        public void onTextDelta(final String delta) {
                            if (delta == null || delta.isEmpty() || cancel.isCancelled()) return;
                            CustomAiCallLog.onTextDelta(logId, delta);
                            final long elapsed = SystemClock.uptimeMillis() - startMs;
                            postToNativeCb(new Runnable() {
                                @Override
                                public void run() {
                                    if (cancel.isCancelled()) return;
                                    listener.onAskAiResponse(buildAskAiResp(
                                            RayNeoAskAIResponseEventType.TEXT_DELTA,
                                            dialogId, query, delta, null, false, round, roundId, modelUsed, elapsed));
                                }
                            });
                        }
                    },
                    cancel
            );

            if (cancel.isCancelled()) {
                CustomAiCallLog.finishInterrupted(appContext, logId, "手机 AskAI 被取消");
                return;
            }
            ConversationMemory.appendTurn("ask_" + dialogId, query, fullReply, cfg.maxHistoryTurns);
            CustomAiCallLog.finishSuccess(appContext, logId, fullReply);

            final long totalMs = SystemClock.uptimeMillis() - startMs;
            postToNativeCb(new Runnable() {
                @Override
                public void run() {
                    if (cancel.isCancelled()) return;
                    listener.onAskAiResponse(buildAskAiResp(
                            RayNeoAskAIResponseEventType.FINISHED,
                            dialogId, query, "", null, true, round, roundId, modelUsed, totalMs));
                    listener.onAskAiResponse(buildAskAiResp(
                            RayNeoAskAIResponseEventType.COMPLETED,
                            dialogId, query, "", null, true, round, roundId, modelUsed, totalMs));
                    listener.onResponseComplete();
                }
            });
        } catch (CancellationException ignored) {
            CustomAiCallLog.finishInterrupted(appContext, logId, "手机 AskAI 请求取消");
        } catch (final Throwable t) {
            if (cancel.isCancelled()) return;
            Log.e(TAG, "Custom LLM error (AskAI)", t);
            CustomAiCallLog.finishError(appContext, logId, safeStr(t.getMessage()));
            postToNativeCb(new Runnable() {
                @Override
                public void run() {
                    listener.onError(-1, "自定义 API 请求失败: " + safeStr(t.getMessage()));
                    listener.onResponseComplete();
                }
            });
        } finally {
            ASK_AI_CANCELS.remove(task, cancel);
            ASK_AI_LOG_IDS.remove(task, logId);
        }
    }

    // ==================== Helpers ====================

    private static void resetTurnFlags(SessionState st) {
        if (st == null) return;
        st.asrFinished = false;
        st.handledByLocalSkill = false;
        st.customTurnActive = false;
        st.recordEndForwarded = false;
        st.nativeWorkflowStopped = false;
        st.activeLogId = 0L;
        st.activeSkillLogId = 0L;
        st.lastSkillSummary = "";
        synchronized (st.skillAnswerAcc) {
            st.skillAnswerAcc.setLength(0);
        }
    }

    private static SessionState getOrCreateSession(String uuid) {
        String key = safeStr(uuid);
        SessionState st = SESSIONS.get(key);
        if (st == null) {
            st = new SessionState();
            st.uuid = key;
            SessionState existing = SESSIONS.putIfAbsent(key, st);
            if (existing != null) return existing;
        }
        return st;
    }

    private static SessionState resolveSession(String uuid) {
        String key = safeStr(uuid);
        if (!key.isEmpty()) {
            SessionState st = SESSIONS.get(key);
            if (st != null) return st;
        }
        String fallbackKey = safeStr(lastSessionUuid);
        SessionState st = SESSIONS.get(fallbackKey);
        if (st != null) return st;
        if (!SESSIONS.isEmpty()) {
            return SESSIONS.values().iterator().next();
        }
        return null;
    }

    private static boolean isInterceptingGlassesSession(SessionState st) {
        if (st == null || st.handledByLocalSkill) return false;
        Application app = appContext;
        if (app == null) return false;
        CustomAiConfig cfg = CustomAiConfig.load(app);
        return cfg.enabled && cfg.interceptGlassesVoice;
    }

    private static void cancelTurnResources(SessionState st, String reason) {
        if (st == null) return;
        if (st.pendingStartTask != null) {
            MAIN_HANDLER.removeCallbacks(st.pendingStartTask);
            st.pendingStartTask = null;
        }
        if (st.routeTimeoutTask != null) {
            MAIN_HANDLER.removeCallbacks(st.routeTimeoutTask);
            st.routeTimeoutTask = null;
        }
        st.routeGate = null;
        if (st.pendingNextRoundTask != null) {
            MAIN_HANDLER.removeCallbacks(st.pendingNextRoundTask);
            st.pendingNextRoundTask = null;
        }
        if (st.activeCancel != null) {
            st.activeCancel.cancel();
            st.activeCancel = null;
        }
        long lid = st.activeLogId;
        st.activeLogId = 0L;
        if (lid > 0L && appContext != null) {
            CustomAiCallLog.finishInterrupted(appContext, lid, reason);
        }
        RayNeoOriginalTtsBridge.stop();
    }

    private static void maybeShowCallToast(final Context ctx, final CustomAiConfig cfg, final String channel, final String model) {
        if (ctx == null || cfg == null || !cfg.showCallToast) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx,
                            "⚡ 自定义AI [" + channel + "] -> " + (model == null || model.isEmpty() ? cfg.baseUrl : model),
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String detectDirectNativeCaller() {
        try {
            StackTraceElement[] frames = new Throwable().getStackTrace();
            for (StackTraceElement el : frames) {
                String cn = el.getClassName();
                if (cn.endsWith("AssistantController")) {
                    return el.getMethodName();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String summarizeCaller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (StackTraceElement el : st) {
                String cn = el.getClassName();
                if (cn.startsWith("dalvik.") || cn.startsWith("java.lang.Thread")
                        || cn.startsWith("com.rayneo.customai.")) {
                    continue;
                }
                if (cn.endsWith("AssistantController") && sb.length() == 0) {
                    continue;
                }
                String shortCls = cn;
                int dot = cn.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < cn.length()) {
                    shortCls = cn.substring(dot + 1);
                }
                if (sb.length() > 0) sb.append("<-");
                sb.append(shortCls).append('.').append(el.getMethodName()).append(':').append(el.getLineNumber());
                if (++count >= 3) break;
            }
            return sb.length() > 0 ? sb.toString() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isLocalHardwareResult(NlpResult r) {
        if (r == null) return false;
        String domain = safeStr(r.getDomain()).toLowerCase(Locale.ROOT);
        if ("music".equals(domain)) return false;
        if (r.getOffline()) return true;
        if ("system_ctrl".equals(domain) || "exit".equals(domain) || "quit".equals(domain)
                || "volume".equals(domain) || "brightness".equals(domain)
                || "media".equals(domain)
                || "camera".equals(domain) || "audio".equals(domain)
                || "telephone".equals(domain) || "phone".equals(domain)
                || "bluetooth".equals(domain) || "translate".equals(domain)) {
            return true;
        }
        String intent = safeStr(r.getIntent()).toLowerCase(Locale.ROOT);
        return "exit".equals(intent) || "quit".equals(intent);
    }

    private static boolean isCustomAuthProvided(String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) return false;
        return headersJson.toLowerCase(Locale.ROOT).contains("authorization")
                || headersJson.toLowerCase(Locale.ROOT).contains("x-api-key");
    }

    private static NlpResult buildNlpResult(
            String dialogId, String sessionId, String query, String answer,
            boolean finished, boolean hasNextRound, int round) {
        NlpResult r = new NlpResult();
        r.setDialogId(safeStr(dialogId));
        r.setSessionId(safeStr(sessionId));
        r.setQuery(safeStr(query));
        r.setAnswer(safeStr(answer));
        r.setSpoken(safeStr(answer));
        r.setDomain("chat");
        r.setIntent("chat");
        r.setSub("llm");
        r.setRawData("");
        r.setOffline(false);
        r.setFinished(finished);
        r.setHasNextRound(hasNextRound);
        r.setRound(round);
        return r;
    }

    private static RayNeoAskAIResponse buildAskAiResp(
            RayNeoAskAIResponseEventType type, String dialogId, String query,
            String text, RayNeoAskAIReasonDetail reasonDetail, boolean finished,
            int round, String roundId, String model, long thinkingMs) {
        RayNeoAskAIResponse resp = new RayNeoAskAIResponse();
        resp.setEventType(type);
        resp.setDialogId(safeStr(dialogId));
        resp.setQuery(safeStr(query));
        resp.setText(safeStr(text));
        resp.setSpoken(safeStr(text));
        resp.setFinished(finished);
        resp.setRound(round);
        resp.setRoundId(safeStr(roundId));
        resp.setModel(safeStr(model));
        resp.setMsgSource("custom_ai");
        resp.setThinkingTimeMs(thinkingMs);
        if (reasonDetail != null) {
            resp.setReasonDetail(reasonDetail);
        }
        return resp;
    }

    private static void emitGated(RouteGate gate, Runnable r) {
        if (gate == null) {
            postToNativeCb(r);
        } else {
            gate.post(r);
        }
    }

    private static void postToNativeCb(Runnable r) {
        try {
            Handler h = ShareHandler.INSTANCE.getHandler();
            if (h != null) {
                h.post(r);
                return;
            }
        } catch (Throwable ignored) {}
        MAIN_HANDLER.post(r);
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }
}
