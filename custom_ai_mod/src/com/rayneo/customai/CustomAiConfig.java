package com.rayneo.customai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SharedPreferences-backed configuration for Custom LLM API integration.
 */
public final class CustomAiConfig {

    public static final String PREF_NAME = "rayneo_custom_ai_prefs";
    public static final String KEY_SYNC_LYRICS_TELEPROMPTER = "sync_lyrics_teleprompter";
    public static final String KEY_LYRICS_DISPLAY_MODE = "lyrics_display_mode";

    public static final String LYRICS_MODE_ORIGINAL = "original";
    public static final String LYRICS_MODE_BILINGUAL = "bilingual";
    public static final String LYRICS_MODE_ROMAJI = "romaji";
    public static final String LYRICS_MODE_TRILINGUAL = "trilingual";

    public static final String SUBTEXT_SCOPE_BOTH = "both";
    public static final String SUBTEXT_SCOPE_PROACTIVE = "proactive";
    public static final String SUBTEXT_SCOPE_CAPTION = "caption";

    public static final String SUBTEXT_SCENE_AUTO = "auto";
    public static final String SUBTEXT_SCENE_WORK = "work";
    public static final String SUBTEXT_SCENE_LOVE = "love";
    public static final String SUBTEXT_SCENE_SOCIAL = "social";

    public static final String SUBTEXT_STYLE_DISCO = "disco";
    public static final String SUBTEXT_STYLE_CLEAN = "clean";

    private static final String FLUTTER_PREF_NAME = "FlutterSharedPreferences";
    private static final String FLUTTER_ASK_AI_OVERRIDE_KEY = "flutter.dev_ask_ai_transport_mode_override";

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是雷鸟智能眼镜的语音与随身 AI 助手。请用自然、简洁、口语化的中文回答，适合语音播报与眼镜屏幕快速阅读，避免冗长的 Markdown 符号和表格。";

    public boolean enabled = true;
    public boolean interceptGlassesVoice = true;
    public boolean interceptPhoneAskAi = true;
    public boolean keepLocalHardwareControl = true;
    public boolean showFloatingButton = true;
    public boolean showCallToast = true;
    public boolean autoContinueAfterTts = false;
    public boolean passThroughOfficialSkills = true;
    public boolean syncLyricsToTeleprompter = false;
    public String lyricsDisplayMode = LYRICS_MODE_BILINGUAL;
    public int skillRouteTimeoutMs = 3500;

    public boolean enableMcpAndSkills = true;
    public boolean enableBuiltinSkills = true;
    public String mcpServersJson = "[]";
    public String customSkillsJson = "[]";

    public boolean enableSubtextMode = true;
    public String subtextScope = SUBTEXT_SCOPE_BOTH;
    public int subtextOptionCount = 2;
    public String subtextScene = SUBTEXT_SCENE_AUTO;
    public String subtextStyle = SUBTEXT_STYLE_DISCO;
    public String subtextContextNote = "";
    public String subtextFastModel = "";

    public String apiProtocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
    public String baseUrl = "https://api.openai.com/v1";
    public String apiKey = "";
    public String model = "gpt-6-luna";
    public String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    public double temperature = 0.7;
    public int maxTokens = 1024;
    public boolean streamEnabled = true;
    public String reasoningEffort = "";
    public int maxHistoryTurns = 6;
    public int timeoutSeconds = 45;
    public String customHeadersJson = "{}";

    public static CustomAiConfig load(Context ctx) {
        CustomAiConfig c = new CustomAiConfig();
        if (ctx == null) return c;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        c.enabled = sp.getBoolean("enabled", true);
        c.interceptGlassesVoice = sp.getBoolean("interceptGlassesVoice", true);
        c.interceptPhoneAskAi = sp.getBoolean("interceptPhoneAskAi", true);
        c.keepLocalHardwareControl = sp.getBoolean("keepLocalHardwareControl", true);
        c.showFloatingButton = sp.getBoolean("showFloatingButton", true);
        c.showCallToast = sp.getBoolean("showCallToast", true);
        c.autoContinueAfterTts = sp.getBoolean("autoContinueAfterTts", false);
        c.passThroughOfficialSkills = sp.getBoolean("passThroughOfficialSkills", true);
        c.syncLyricsToTeleprompter = sp.getBoolean(KEY_SYNC_LYRICS_TELEPROMPTER, false);
        c.lyricsDisplayMode = normalizeLyricsMode(sp.getString(KEY_LYRICS_DISPLAY_MODE, LYRICS_MODE_BILINGUAL));
        c.enableMcpAndSkills = sp.getBoolean("enableMcpAndSkills", true);
        c.enableBuiltinSkills = sp.getBoolean("enableBuiltinSkills", true);
        c.mcpServersJson = sp.getString("mcpServersJson", "[]");
        c.customSkillsJson = sp.getString("customSkillsJson", "[]");
        c.enableSubtextMode = sp.getBoolean("enableSubtextMode", true);
        c.subtextScope = normalizeSubtextScope(sp.getString("subtextScope", SUBTEXT_SCOPE_BOTH));
        c.subtextOptionCount = normalizeSubtextOptionCount(sp.getInt("subtextOptionCount", 2));
        c.subtextScene = normalizeSubtextScene(sp.getString("subtextScene", SUBTEXT_SCENE_AUTO));
        c.subtextStyle = normalizeSubtextStyle(sp.getString("subtextStyle", SUBTEXT_STYLE_DISCO));
        c.subtextContextNote = sp.getString("subtextContextNote", "");
        c.subtextFastModel = sp.getString("subtextFastModel", "");
        int rawRouteMs = sp.getInt("skillRouteTimeoutMs", 3500);
        if (rawRouteMs == 2000 && !sp.contains("skillRouteTimeoutUpgradedV2")) {
            rawRouteMs = 3500;
            sp.edit().putInt("skillRouteTimeoutMs", 3500).putBoolean("skillRouteTimeoutUpgradedV2", true).apply();
        }
        c.skillRouteTimeoutMs = rawRouteMs;
        c.apiProtocol = sp.getString("apiProtocol", OpenAiApiClient.PROTO_CHAT_COMPLETIONS);
        c.baseUrl = sp.getString("baseUrl", "https://api.openai.com/v1");
        c.apiKey = sp.getString("apiKey", "");
        c.model = sp.getString("model", "gpt-6-luna");
        c.systemPrompt = sp.getString("systemPrompt", DEFAULT_SYSTEM_PROMPT);
        c.temperature = Double.longBitsToDouble(
                sp.getLong("temperatureBits", Double.doubleToLongBits(0.7)));
        c.maxTokens = sp.getInt("maxTokens", 1024);
        c.streamEnabled = sp.getBoolean("streamEnabled", true);
        c.reasoningEffort = sp.getString("reasoningEffort", "");
        c.maxHistoryTurns = sp.getInt("maxHistoryTurns", 6);
        c.timeoutSeconds = sp.getInt("timeoutSeconds", 45);
        c.customHeadersJson = sp.getString("customHeadersJson", "{}");
        return c;
    }

    public void save(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putBoolean("enabled", enabled)
                .putBoolean("interceptGlassesVoice", interceptGlassesVoice)
                .putBoolean("interceptPhoneAskAi", interceptPhoneAskAi)
                .putBoolean("keepLocalHardwareControl", keepLocalHardwareControl)
                .putBoolean("showFloatingButton", showFloatingButton)
                .putBoolean("showCallToast", showCallToast)
                .putBoolean("autoContinueAfterTts", autoContinueAfterTts)
                .putBoolean("passThroughOfficialSkills", passThroughOfficialSkills)
                .putBoolean(KEY_SYNC_LYRICS_TELEPROMPTER, syncLyricsToTeleprompter)
                .putString(KEY_LYRICS_DISPLAY_MODE, normalizeLyricsMode(lyricsDisplayMode))
                .putBoolean("enableMcpAndSkills", enableMcpAndSkills)
                .putBoolean("enableBuiltinSkills", enableBuiltinSkills)
                .putString("mcpServersJson", safe(mcpServersJson, "[]"))
                .putString("customSkillsJson", safe(customSkillsJson, "[]"))
                .putBoolean("enableSubtextMode", enableSubtextMode)
                .putString("subtextScope", normalizeSubtextScope(subtextScope))
                .putInt("subtextOptionCount", normalizeSubtextOptionCount(subtextOptionCount))
                .putString("subtextScene", normalizeSubtextScene(subtextScene))
                .putString("subtextStyle", normalizeSubtextStyle(subtextStyle))
                .putString("subtextContextNote", subtextContextNote == null ? "" : subtextContextNote.trim())
                .putString("subtextFastModel", subtextFastModel == null ? "" : subtextFastModel.trim())
                .putInt("skillRouteTimeoutMs", Math.max(500, Math.min(10000, skillRouteTimeoutMs)))
                .putBoolean("skillRouteTimeoutUpgradedV2", true)
                .putString("apiProtocol", safe(apiProtocol, OpenAiApiClient.PROTO_CHAT_COMPLETIONS))
                .putString("baseUrl", safe(baseUrl, "https://api.openai.com/v1"))
                .putString("apiKey", apiKey == null ? "" : apiKey.trim())
                .putString("model", safe(model, "gpt-6-luna"))
                .putString("systemPrompt", systemPrompt == null ? "" : systemPrompt)
                .putLong("temperatureBits", Double.doubleToLongBits(temperature))
                .putInt("maxTokens", maxTokens)
                .putBoolean("streamEnabled", streamEnabled)
                .putString("reasoningEffort", reasoningEffort == null ? "" : reasoningEffort.trim())
                .putInt("maxHistoryTurns", maxHistoryTurns)
                .putInt("timeoutSeconds", timeoutSeconds)
                .putString("customHeadersJson", safe(customHeadersJson, "{}"))
                .apply();
        syncFlutterAskAiOverride(app);
    }

    public void syncFlutterAskAiOverride(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences flutterPrefs = ctx.getApplicationContext()
                    .getSharedPreferences(FLUTTER_PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = flutterPrefs.edit();
            if (enabled && interceptPhoneAskAi) {
                ed.putString(FLUTTER_ASK_AI_OVERRIDE_KEY, "airuntime");
            } else {
                String cur = flutterPrefs.getString(FLUTTER_ASK_AI_OVERRIDE_KEY, null);
                if ("airuntime".equals(cur)) {
                    ed.remove(FLUTTER_ASK_AI_OVERRIDE_KEY);
                }
            }
            ed.apply();
        } catch (Throwable ignored) {}
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public OpenAiApiClient.EndpointConfig toEndpointConfig() {
        OpenAiApiClient.EndpointConfig ec = new OpenAiApiClient.EndpointConfig();
        ec.protocol = apiProtocol;
        ec.baseUrl = baseUrl;
        ec.apiKey = apiKey;
        ec.model = model;
        ec.systemPrompt = systemPrompt;
        ec.temperature = temperature;
        ec.maxTokens = maxTokens;
        ec.streamEnabled = streamEnabled;
        ec.reasoningEffort = reasoningEffort;
        ec.timeoutSeconds = timeoutSeconds;
        ec.customHeadersJson = customHeadersJson;
        return ec;
    }

    public boolean isSubtextEnabledForProactive() {
        if (!enabled || !enableSubtextMode || !hasApiKey()) return false;
        String sc = normalizeSubtextScope(subtextScope);
        return SUBTEXT_SCOPE_BOTH.equals(sc) || SUBTEXT_SCOPE_PROACTIVE.equals(sc);
    }

    public boolean isSubtextEnabledForCaption() {
        if (!enabled || !enableSubtextMode || !hasApiKey()) return false;
        String sc = normalizeSubtextScope(subtextScope);
        return SUBTEXT_SCOPE_BOTH.equals(sc) || SUBTEXT_SCOPE_CAPTION.equals(sc);
    }

    public OpenAiApiClient.EndpointConfig toSubtextEndpointConfig(String subtextSystemPrompt) {
        OpenAiApiClient.EndpointConfig ec = toEndpointConfig();
        String fast = subtextFastModel == null ? "" : subtextFastModel.trim();
        if (!fast.isEmpty()) {
            ec.model = fast;
            ec.reasoningEffort = "";
        } else if (OpenAiApiClient.isResponsesProtocol(ec.protocol)
                && reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
            // On /v1/responses reasoning models, downgrade medium/high -> low for minimal latency without HTTP 400
            ec.reasoningEffort = "low";
        } else {
            ec.reasoningEffort = "";
        }
        ec.systemPrompt = subtextSystemPrompt;
        ec.temperature = -1.0; // Omit temperature so reasoning/Gemini-4/GPT-5+ models never reject with HTTP 400
        ec.maxTokens = Math.max(512, maxTokens);
        ec.streamEnabled = true;
        ec.timeoutSeconds = Math.min(20, Math.max(8, timeoutSeconds));
        ec.toolExecutor = null;
        return ec;
    }

    public String toJsonString() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("interceptGlassesVoice", interceptGlassesVoice);
        m.put("interceptPhoneAskAi", interceptPhoneAskAi);
        m.put("keepLocalHardwareControl", keepLocalHardwareControl);
        m.put("showFloatingButton", showFloatingButton);
        m.put("showCallToast", showCallToast);
        m.put("autoContinueAfterTts", autoContinueAfterTts);
        m.put("passThroughOfficialSkills", passThroughOfficialSkills);
        m.put(KEY_SYNC_LYRICS_TELEPROMPTER, syncLyricsToTeleprompter);
        m.put(KEY_LYRICS_DISPLAY_MODE, normalizeLyricsMode(lyricsDisplayMode));
        m.put("enableMcpAndSkills", enableMcpAndSkills);
        m.put("enableBuiltinSkills", enableBuiltinSkills);
        m.put("mcpServersJson", safe(mcpServersJson, "[]"));
        m.put("customSkillsJson", safe(customSkillsJson, "[]"));
        m.put("enableSubtextMode", enableSubtextMode);
        m.put("subtextScope", normalizeSubtextScope(subtextScope));
        m.put("subtextOptionCount", normalizeSubtextOptionCount(subtextOptionCount));
        m.put("subtextScene", normalizeSubtextScene(subtextScene));
        m.put("subtextStyle", normalizeSubtextStyle(subtextStyle));
        m.put("subtextContextNote", subtextContextNote == null ? "" : subtextContextNote);
        m.put("subtextFastModel", subtextFastModel == null ? "" : subtextFastModel);
        m.put("skillRouteTimeoutMs", skillRouteTimeoutMs);
        m.put("apiProtocol", apiProtocol);
        m.put("baseUrl", baseUrl);
        m.put("apiKey", apiKey);
        m.put("model", model);
        m.put("systemPrompt", systemPrompt);
        m.put("temperature", temperature);
        m.put("maxTokens", maxTokens);
        m.put("streamEnabled", streamEnabled);
        m.put("reasoningEffort", reasoningEffort);
        m.put("maxHistoryTurns", maxHistoryTurns);
        m.put("timeoutSeconds", timeoutSeconds);
        m.put("customHeadersJson", customHeadersJson);
        return JsonMini.stringify(m);
    }

    public static CustomAiConfig fromJsonString(String json) {
        Map<String, Object> m = JsonMini.parseObject(json);
        CustomAiConfig c = new CustomAiConfig();
        c.enabled = readBool(m, "enabled", c.enabled);
        c.interceptGlassesVoice = readBool(m, "interceptGlassesVoice", c.interceptGlassesVoice);
        c.interceptPhoneAskAi = readBool(m, "interceptPhoneAskAi", c.interceptPhoneAskAi);
        c.keepLocalHardwareControl = readBool(m, "keepLocalHardwareControl", c.keepLocalHardwareControl);
        c.showFloatingButton = readBool(m, "showFloatingButton", c.showFloatingButton);
        c.showCallToast = readBool(m, "showCallToast", c.showCallToast);
        c.autoContinueAfterTts = readBool(m, "autoContinueAfterTts", c.autoContinueAfterTts);
        c.passThroughOfficialSkills = readBool(m, "passThroughOfficialSkills", c.passThroughOfficialSkills);
        c.syncLyricsToTeleprompter = readBool(m, KEY_SYNC_LYRICS_TELEPROMPTER,
                readBool(m, "syncLyricsToTeleprompter", c.syncLyricsToTeleprompter));
        c.lyricsDisplayMode = normalizeLyricsMode(readStr(m, KEY_LYRICS_DISPLAY_MODE,
                readStr(m, "lyricsDisplayMode", c.lyricsDisplayMode)));
        c.enableMcpAndSkills = readBool(m, "enableMcpAndSkills", c.enableMcpAndSkills);
        c.enableBuiltinSkills = readBool(m, "enableBuiltinSkills", c.enableBuiltinSkills);
        c.mcpServersJson = readStr(m, "mcpServersJson", c.mcpServersJson);
        c.customSkillsJson = readStr(m, "customSkillsJson", c.customSkillsJson);
        c.enableSubtextMode = readBool(m, "enableSubtextMode", c.enableSubtextMode);
        c.subtextScope = normalizeSubtextScope(readStr(m, "subtextScope", c.subtextScope));
        c.subtextOptionCount = normalizeSubtextOptionCount(readInt(m, "subtextOptionCount", c.subtextOptionCount));
        c.subtextScene = normalizeSubtextScene(readStr(m, "subtextScene", c.subtextScene));
        c.subtextStyle = normalizeSubtextStyle(readStr(m, "subtextStyle", c.subtextStyle));
        c.subtextContextNote = readStr(m, "subtextContextNote", c.subtextContextNote);
        c.subtextFastModel = readStr(m, "subtextFastModel", c.subtextFastModel);
        c.skillRouteTimeoutMs = readInt(m, "skillRouteTimeoutMs", c.skillRouteTimeoutMs);
        c.apiProtocol = readStr(m, "apiProtocol", c.apiProtocol);
        c.baseUrl = readStr(m, "baseUrl", c.baseUrl);
        c.apiKey = readStr(m, "apiKey", c.apiKey);
        c.model = readStr(m, "model", c.model);
        c.systemPrompt = readStr(m, "systemPrompt", c.systemPrompt);
        c.temperature = readDouble(m, "temperature", c.temperature);
        c.maxTokens = readInt(m, "maxTokens", c.maxTokens);
        c.streamEnabled = readBool(m, "streamEnabled", c.streamEnabled);
        c.reasoningEffort = readStr(m, "reasoningEffort", c.reasoningEffort);
        c.maxHistoryTurns = readInt(m, "maxHistoryTurns", c.maxHistoryTurns);
        c.timeoutSeconds = readInt(m, "timeoutSeconds", c.timeoutSeconds);
        c.customHeadersJson = readStr(m, "customHeadersJson", c.customHeadersJson);
        return c;
    }

    public static String normalizeLyricsMode(String raw) {
        if (raw == null) return LYRICS_MODE_BILINGUAL;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (LYRICS_MODE_ORIGINAL.equals(s)) return LYRICS_MODE_ORIGINAL;
        if (LYRICS_MODE_BILINGUAL.equals(s)) return LYRICS_MODE_BILINGUAL;
        if (LYRICS_MODE_ROMAJI.equals(s)) return LYRICS_MODE_ROMAJI;
        if (LYRICS_MODE_TRILINGUAL.equals(s)) return LYRICS_MODE_TRILINGUAL;
        return LYRICS_MODE_BILINGUAL;
    }

    public static String normalizeSubtextScope(String raw) {
        if (raw == null) return SUBTEXT_SCOPE_BOTH;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (SUBTEXT_SCOPE_PROACTIVE.equals(s)) return SUBTEXT_SCOPE_PROACTIVE;
        if (SUBTEXT_SCOPE_CAPTION.equals(s)) return SUBTEXT_SCOPE_CAPTION;
        return SUBTEXT_SCOPE_BOTH;
    }

    public static int normalizeSubtextOptionCount(int count) {
        return count == 3 ? 3 : 2;
    }

    public static String normalizeSubtextScene(String raw) {
        if (raw == null) return SUBTEXT_SCENE_AUTO;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (SUBTEXT_SCENE_WORK.equals(s)) return SUBTEXT_SCENE_WORK;
        if (SUBTEXT_SCENE_LOVE.equals(s)) return SUBTEXT_SCENE_LOVE;
        if (SUBTEXT_SCENE_SOCIAL.equals(s)) return SUBTEXT_SCENE_SOCIAL;
        return SUBTEXT_SCENE_AUTO;
    }

    public static String normalizeSubtextStyle(String raw) {
        if (raw == null) return SUBTEXT_STYLE_DISCO;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (SUBTEXT_STYLE_CLEAN.equals(s)) return SUBTEXT_STYLE_CLEAN;
        return SUBTEXT_STYLE_DISCO;
    }

    private static boolean readBool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    private static String readStr(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return (v instanceof String) ? (String) v : def;
    }

    private static double readDouble(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).doubleValue() : def;
    }

    private static int readInt(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).intValue() : def;
    }

    private static String safe(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.isEmpty() ? def : t;
    }
}
