package com.rayneo.customai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rayneo.airuntime.AiRuntimeNative;
import com.rayneo.airuntime.controller.AiAgentConfig;
import com.rayneo.airuntime.controller.AiResultListener;
import com.rayneo.airuntime.controller.RayNeoAiConfig;
import com.rayneo.airuntime.controller.RayNeoTTSConfig;
import com.rayneo.airuntime.controller.RayNeoTTSDataResponse;
import com.rayneo.airuntime.controller.RayNeoTTSWorkflowParams;
import com.rayneo.airuntime.controller.ShareHandler;
import com.rayneo.airuntime.controller.TTSController;
import com.rayneo.airuntime.controller.TTSPlayStatus;
import com.rayneo.airuntime.controller.TTSResultListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reuses RayNeo's untouched original TTSController + voice settings whenever
 * AiAgentConfig.getEnableTTS() is true, leaving TTS completely stock.
 */
public final class RayNeoOriginalTtsBridge {

    public interface CompletionCallback {
        void onComplete(boolean playedNormally);
    }

    private static final String TAG = "RayNeoCustomAI";
    private static final Handler WATCHDOG = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static boolean ttsInited = false;
    private static boolean workflowDirty = false;
    private static volatile Runnable pendingTimeout;

    private RayNeoOriginalTtsBridge() {}

    public static void speak(
            final RayNeoAiConfig aiConfig,
            final String text,
            final String sessionId,
            final long logId,
            final AiResultListener aiListener,
            final CompletionCallback onComplete
    ) {
        final String safeSid = sessionId == null ? "" : sessionId;
        final String spokenText = text == null ? "" : text.trim();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean sawSpeakBegin = new AtomicBoolean(false);
        final AtomicBoolean sawSpeakCompleted = new AtomicBoolean(false);

        final class Completer {
            void finish(final boolean playedNormally) {
                if (!finished.compareAndSet(false, true)) return;
                cancelWatchdog();
                if (!playedNormally && sawSpeakBegin.get() && !sawSpeakCompleted.get() && aiListener != null) {
                    postToNativeCb(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                aiListener.onTtsStatusChange(TTSPlayStatus.STATE_TTS_SPEAK_COMPLETED, safeSid);
                            } catch (Throwable ignored) {}
                        }
                    });
                }
                if (onComplete != null) {
                    postToNativeCb(new Runnable() {
                        @Override
                        public void run() {
                            onComplete.onComplete(playedNormally);
                        }
                    });
                }
            }
        }
        final Completer completer = new Completer();

        if (spokenText.isEmpty()) {
            CustomAiCallLog.addEvent(logId, "TTS_EMPTY_SKIP");
            completer.finish(false);
            return;
        }

        try {
            synchronized (LOCK) {
                TTSController tts = AiRuntimeNative.INSTANCE.getTtsController();
                if (!ttsInited) {
                    ttsInited = tts.init();
                }
                if (workflowDirty) {
                    try {
                        tts.destroyWorkflow();
                    } catch (Throwable ignored) {}
                    workflowDirty = false;
                }

                RayNeoTTSConfig ttsConfig = buildTtsConfig(aiConfig);
                boolean initOk = tts.initWorkflow(ttsConfig, new TTSResultListener() {
                    @Override
                    public void onStartResult(boolean success, String message) {
                        if (!success) {
                            Log.w(TAG, "Stock TTS onStartResult failed: " + message);
                            CustomAiCallLog.addEvent(logId, "TTS_START_FAIL:" + safeMsg(message));
                            completer.finish(false);
                        } else {
                            CustomAiCallLog.addEvent(logId, "TTS_START_OK");
                        }
                    }

                    @Override
                    public void onTtsStatusChange(final int status) {
                        if (status == TTSPlayStatus.STATE_TTS_SPEAK_BEGIN) {
                            sawSpeakBegin.set(true);
                            CustomAiCallLog.markTtsPlaying(logId);
                        } else if (status == TTSPlayStatus.STATE_TTS_SPEAK_COMPLETED) {
                            sawSpeakCompleted.set(true);
                            CustomAiCallLog.addEvent(logId, "TTS_STATUS:COMPLETED");
                        } else {
                            CustomAiCallLog.addEvent(logId, "TTS_STATUS:" + status);
                        }

                        postToNativeCb(new Runnable() {
                            @Override
                            public void run() {
                                if (aiListener != null) {
                                    aiListener.onTtsStatusChange(status, safeSid);
                                }
                            }
                        });
                        if (status == TTSPlayStatus.STATE_TTS_SPEAK_COMPLETED) {
                            completer.finish(true);
                        }
                    }

                    @Override
                    public void onTTSResponse(RayNeoTTSDataResponse response) {
                        // Stock TTS audio playback is driven internally by native/AudioPlayerBridge
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage) {
                        Log.w(TAG, "Stock TTS error " + errorCode + ": " + errorMessage);
                        CustomAiCallLog.addEvent(logId, "TTS_ERROR:" + errorCode + ":" + safeMsg(errorMessage));
                        completer.finish(false);
                    }

                    @Override
                    public void onTTSCancel() {
                        CustomAiCallLog.addEvent(logId, "TTS_CANCELLED");
                        completer.finish(false);
                    }
                });

                if (!initOk) {
                    Log.w(TAG, "Stock TTS initWorkflow returned false");
                    CustomAiCallLog.addEvent(logId, "TTS_INIT_FAIL");
                    completer.finish(false);
                    return;
                }
                CustomAiCallLog.addEvent(logId, "TTS_INIT_OK(chars=" + spokenText.length() + ")");
                workflowDirty = true;

                long timeoutMs = Math.max(15000L, Math.min(90000L, spokenText.length() * 450L + 10000L));
                scheduleWatchdog(timeoutMs, new Runnable() {
                    @Override
                    public void run() {
                        CustomAiCallLog.addEvent(logId, "TTS_WATCHDOG_TIMEOUT");
                        completer.finish(false);
                    }
                });

                RayNeoTTSWorkflowParams wfParams = new RayNeoTTSWorkflowParams();
                wfParams.setText(spokenText);
                boolean started = tts.startWorkflow(wfParams);
                if (!started) {
                    Log.w(TAG, "Stock TTS startWorkflow returned false");
                    CustomAiCallLog.addEvent(logId, "TTS_START_WF_FALSE");
                    completer.finish(false);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Stock TTS exception", t);
            CustomAiCallLog.addEvent(logId, "TTS_EXCEPTION:" + safeMsg(t.getMessage()));
            completer.finish(false);
        }
    }

    public static void stop() {
        cancelWatchdog();
        synchronized (LOCK) {
            try {
                TTSController tts = AiRuntimeNative.INSTANCE.getTtsController();
                tts.stopWorkflow();
                if (workflowDirty) {
                    tts.destroyWorkflow();
                    workflowDirty = false;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static RayNeoTTSConfig buildTtsConfig(RayNeoAiConfig aiConfig) {
        RayNeoTTSConfig tc = new RayNeoTTSConfig();
        tc.setUserName(nonEmpty(aiConfig != null ? aiConfig.getUserName() : null, "675402547064773"));
        tc.setDeviceName(nonEmpty(aiConfig != null ? aiConfig.getDeviceName() : null, "6409AC905A8C171"));
        tc.setDeviceModel(nonEmpty(aiConfig != null ? aiConfig.getDeviceModel() : null, "RayNeoS3"));
        if (aiConfig != null && aiConfig.getServerConfig() != null) {
            tc.setServerConfig(aiConfig.getServerConfig());
        }
        tc.setTimeout(5000L);
        tc.setEnvDebug(aiConfig != null && aiConfig.getEnvDebug());

        AiAgentConfig agent = aiConfig != null ? aiConfig.getAiAgentConfig() : null;
        String voice = agent != null ? agent.getVoice() : null;
        tc.setVoice(nonEmpty(voice, "loongbella_v2"));

        String lang = aiConfig != null ? aiConfig.getLanguageCode() : null;
        if (lang != null && !lang.trim().isEmpty()) {
            tc.setLanguageCode(lang.trim());
        }
        return tc;
    }

    private static void scheduleWatchdog(long delayMs, final Runnable target) {
        cancelWatchdog();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "Stock TTS watchdog fired, completing turn");
                target.run();
            }
        };
        pendingTimeout = r;
        WATCHDOG.postDelayed(r, delayMs);
    }

    private static void cancelWatchdog() {
        Runnable r = pendingTimeout;
        if (r != null) {
            WATCHDOG.removeCallbacks(r);
            pendingTimeout = null;
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
        WATCHDOG.post(r);
    }

    private static String nonEmpty(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.isEmpty() ? def : t;
    }

    private static String safeMsg(String s) {
        return s == null ? "" : s;
    }
}
