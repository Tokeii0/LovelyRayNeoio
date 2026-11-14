package com.rayneo.customai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Pure JDK client supporting OpenAI /v1/chat/completions AND /v1/responses
 * (both SSE streaming and non-streaming JSON responses).
 */
public final class OpenAiApiClient {

    public static final String PROTO_CHAT_COMPLETIONS = "chat_completions";
    public static final String PROTO_RESPONSES = "responses";

    public static final class EndpointConfig {
        public String protocol = PROTO_CHAT_COMPLETIONS;
        public String baseUrl = "https://api.openai.com/v1";
        public String apiKey = "";
        public String model = "gpt-6-luna";
        public String systemPrompt = "";
        public String extraContextHint = "";
        public boolean injectTimeContext = true;
        public double temperature = 0.7;
        public int maxTokens = 1024;
        public boolean streamEnabled = true;
        public String reasoningEffort = "";
        public int timeoutSeconds = 45;
        public String customHeadersJson = "{}";
    }

    public interface StreamCallback {
        void onReasoningDelta(String delta);
        void onTextDelta(String delta);
    }

    public static final class CancelToken {
        private volatile boolean cancelled = false;
        private volatile HttpURLConnection conn;

        public boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpURLConnection connection) {
            this.conn = connection;
            if (cancelled && connection != null) {
                disconnectSafe(connection);
            }
        }

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = this.conn;
            if (c != null) {
                disconnectSafe(c);
            }
        }

        private static void disconnectSafe(final HttpURLConnection c) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        c.disconnect();
                    } catch (Throwable ignored) {}
                }
            }, "CustomAiCancel");
            t.setDaemon(true);
            t.start();
        }
    }

    private OpenAiApiClient() {}

    public static boolean isResponsesProtocol(String protocol) {
        return PROTO_RESPONSES.equalsIgnoreCase(protocol != null ? protocol.trim() : "");
    }

    /**
     * Resolves arbitrary baseUrl inputs into a full chat/completions or responses endpoint URL.
     */
    public static String resolveEndpointUrl(String rawBaseUrl, String protocol) {
        String u = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.isEmpty()) {
            u = "https://api.openai.com/v1";
        }
        boolean useResponses = isResponsesProtocol(protocol);
        String targetSuffix = useResponses ? "/responses" : "/chat/completions";

        if (u.endsWith("/responses")) {
            return useResponses ? u : u.substring(0, u.length() - "/responses".length()) + "/chat/completions";
        }
        if (u.endsWith("/chat/completions")) {
            return useResponses ? u.substring(0, u.length() - "/chat/completions".length()) + "/responses" : u;
        }
        if (u.matches(".*/v\\d+$") || u.endsWith("/v1beta/openai") || u.endsWith("/compatible-mode/v1")) {
            return u + targetSuffix;
        }
        return u + "/v1" + targetSuffix;
    }

    public static String streamChat(
            EndpointConfig cfg,
            List<ConversationMemory.Message> history,
            String userText,
            StreamCallback callback,
            CancelToken cancelToken
    ) throws IOException {
        if (cfg == null) throw new IllegalArgumentException("EndpointConfig is null");
        String urlStr = resolveEndpointUrl(cfg.baseUrl, cfg.protocol);
        boolean useResponses = isResponsesProtocol(cfg.protocol);

        Map<String, Object> bodyMap = useResponses
                ? buildResponsesRequest(cfg, history, userText)
                : buildChatCompletionsRequest(cfg, history, userText);
        String jsonBody = JsonMini.stringify(bodyMap);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (cancelToken != null) {
                cancelToken.attach(conn);
                if (cancelToken.isCancelled()) {
                    throw new CancellationException("Cancelled");
                }
            }
            int timeoutMs = Math.max(5, cfg.timeoutSeconds) * 1000;
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", cfg.streamEnabled ? "text/event-stream, application/json" : "application/json");
            if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey.trim());
            }
            applyCustomHeaders(conn, cfg.customHeadersJson);

            byte[] outBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(outBytes);
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String errBody = readAll(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream());
                String hint = "HTTP " + status + " (" + urlStr + ")";
                String extracted = extractErrorMessage(errBody);
                if (extracted != null && !extracted.isEmpty()) {
                    hint += ": " + extracted;
                } else if (!errBody.isEmpty()) {
                    hint += ": " + truncate(errBody, 240);
                }
                throw new IOException(hint);
            }

            String contentType = conn.getContentType();
            boolean isSse = cfg.streamEnabled && contentType != null
                    && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");

            try (InputStream is = conn.getInputStream()) {
                if (isSse) {
                    return useResponses
                            ? parseResponsesSse(is, callback, cancelToken)
                            : parseChatCompletionsSse(is, callback, cancelToken);
                } else {
                    String respText = readAll(is);
                    // Fallback: some proxies stream SSE even with text/plain or omitted Content-Type
                    String trimmed = respText.trim();
                    if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
                        return useResponses
                                ? parseResponsesSseText(respText, callback, cancelToken)
                                : parseChatCompletionsSseText(respText, callback, cancelToken);
                    }
                    return useResponses
                            ? parseResponsesJson(respText, callback)
                            : parseChatCompletionsJson(respText, callback);
                }
            }
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Cleartext HTTP traffic")) {
                throw new IOException("Android 禁止明文 HTTP 请求，请改用 HTTPS 地址或反代域名 (" + msg + ")", e);
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static String testConnection(EndpointConfig cfg) throws IOException {
        EndpointConfig probe = new EndpointConfig();
        probe.protocol = cfg.protocol;
        probe.baseUrl = cfg.baseUrl;
        probe.apiKey = cfg.apiKey;
        probe.model = cfg.model;
        probe.systemPrompt = "Reply briefly with OK.";
        probe.injectTimeContext = false;
        probe.temperature = 0.2;
        probe.maxTokens = 32;
        probe.streamEnabled = false;
        probe.reasoningEffort = ""; // keep connectivity test fast even for reasoning models
        probe.timeoutSeconds = Math.min(25, Math.max(5, cfg.timeoutSeconds));
        probe.customHeadersJson = cfg.customHeadersJson;
        String reply = streamChat(probe, null, "Ping", null, null);
        return reply == null ? "" : reply.trim();
    }

    public static boolean shouldOmitTemperature(String model, double temperature) {
        if (temperature < 0) return true;
        if (model == null) return false;
        String m = model.trim().toLowerCase(Locale.ROOT);
        int slash = m.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < m.length()) {
            m = m.substring(slash + 1);
        }
        // Gemini 3.5-flash-lite / 3.6+ / Gemini 4 officially deprecated sampling params (temperature/top_p)
        if (m.startsWith("gemini-4")
                || m.startsWith("gemini-3.6")
                || m.startsWith("gemini-3.7")
                || m.startsWith("gemini-3.8")
                || m.startsWith("gemini-3.5-flash-lite")) {
            return true;
        }
        return false;
    }

    static Map<String, Object> buildChatCompletionsRequest(
            EndpointConfig cfg, List<ConversationMemory.Message> history, String userText) {
        Map<String, Object> root = new LinkedHashMap<>();
        String modelName = nonEmpty(cfg.model, "gpt-6-luna");
        root.put("model", modelName);
        root.put("stream", cfg.streamEnabled);

        String effort = trimOrEmpty(cfg.reasoningEffort);
        if (!effort.isEmpty()) {
            root.put("reasoning_effort", effort);
            if (cfg.maxTokens > 0) {
                root.put("max_completion_tokens", cfg.maxTokens);
            }
        } else {
            if (!shouldOmitTemperature(modelName, cfg.temperature)) {
                root.put("temperature", cfg.temperature);
            }
            if (cfg.maxTokens > 0) {
                root.put("max_tokens", cfg.maxTokens);
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String effectiveSys = buildEffectiveSystemPrompt(cfg);
        if (!effectiveSys.isEmpty()) {
            messages.add(msgObj("system", effectiveSys));
        }
        if (history != null) {
            for (ConversationMemory.Message m : history) {
                if (m != null && m.content != null && !m.content.isEmpty()) {
                    messages.add(msgObj(nonEmpty(m.role, "user"), m.content));
                }
            }
        }
        messages.add(msgObj("user", userText == null ? "" : userText));
        root.put("messages", messages);
        return root;
    }

    static Map<String, Object> buildResponsesRequest(
            EndpointConfig cfg, List<ConversationMemory.Message> history, String userText) {
        Map<String, Object> root = new LinkedHashMap<>();
        String modelName = nonEmpty(cfg.model, "gpt-6-luna");
        root.put("model", modelName);
        root.put("stream", cfg.streamEnabled);

        String effectiveSys = buildEffectiveSystemPrompt(cfg);
        if (!effectiveSys.isEmpty()) {
            root.put("instructions", effectiveSys);
        }
        if (cfg.maxTokens > 0) {
            root.put("max_output_tokens", cfg.maxTokens);
        }

        String effort = trimOrEmpty(cfg.reasoningEffort);
        if (!effort.isEmpty()) {
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("effort", effort);
            reasoning.put("summary", "auto");
            root.put("reasoning", reasoning);
            // OpenAI /v1/responses rejects temperature when reasoning is configured
        } else if (!shouldOmitTemperature(modelName, cfg.temperature)) {
            root.put("temperature", cfg.temperature);
        }

        List<Map<String, Object>> input = new ArrayList<>();
        if (history != null) {
            for (ConversationMemory.Message m : history) {
                if (m != null && m.content != null && !m.content.isEmpty()) {
                    input.add(msgObj(nonEmpty(m.role, "user"), m.content));
                }
            }
        }
        input.add(msgObj("user", userText == null ? "" : userText));
        root.put("input", input);
        return root;
    }

    private static String buildEffectiveSystemPrompt(EndpointConfig cfg) {
        StringBuilder sb = new StringBuilder();
        if (cfg.systemPrompt != null && !cfg.systemPrompt.trim().isEmpty()) {
            sb.append(cfg.systemPrompt.trim());
        }
        if (cfg.injectTimeContext) {
            try {
                String nowStr = new SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(new Date());
                if (sb.length() > 0) sb.append('\n');
                sb.append("[当前设备本地时间：").append(nowStr).append(']');
            } catch (Throwable ignored) {}
        }
        if (cfg.extraContextHint != null && !cfg.extraContextHint.trim().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(cfg.extraContextHint.trim());
        }
        return sb.toString();
    }

    private static Map<String, Object> msgObj(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String parseChatCompletionsSse(
            InputStream is, StreamCallback cb, CancelToken cancel) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder full = new StringBuilder();
        StringBuilder dataBuf = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (cancel != null && cancel.isCancelled()) {
                throw new CancellationException("Cancelled");
            }
            if (line.isEmpty()) {
                if (dataBuf.length() > 0) {
                    boolean done = handleChatSseData(dataBuf.toString().trim(), full, cb);
                    dataBuf.setLength(0);
                    if (done) break;
                }
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("data:")) {
                String part = line.substring(5).trim();
                if ("[DONE]".equals(part)) break;
                if (dataBuf.length() > 0) dataBuf.append('\n');
                dataBuf.append(part);
            }
        }
        if (dataBuf.length() > 0) {
            handleChatSseData(dataBuf.toString().trim(), full, cb);
        }
        return full.toString();
    }

    private static String parseChatCompletionsSseText(
            String text, StreamCallback cb, CancelToken cancel) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (InputStream bais = new java.io.ByteArrayInputStream(bytes)) {
            return parseChatCompletionsSse(bais, cb, cancel);
        }
    }

    private static boolean handleChatSseData(String data, StringBuilder full, StreamCallback cb) throws IOException {
        if (data.isEmpty() || "[DONE]".equals(data)) return true;
        Map<String, Object> root;
        try {
            root = JsonMini.parseObject(data);
        } catch (RuntimeException e) {
            return false;
        }
        Map<String, Object> err = JsonMini.getMap(root, "error");
        if (err != null) {
            throw new IOException(nonEmpty(JsonMini.getString(err, "message"), JsonMini.stringify(err)));
        }
        List<Object> choices = JsonMini.getList(root, "choices");
        if (choices == null || choices.isEmpty()) return false;
        Map<String, Object> c0 = JsonMini.asMap(choices.get(0));
        if (c0 == null) return false;

        Map<String, Object> delta = JsonMini.getMap(c0, "delta");
        if (delta == null) {
            delta = JsonMini.getMap(c0, "message");
        }
        if (delta != null) {
            String reasoning = JsonMini.getString(delta, "reasoning_content");
            if (reasoning == null) reasoning = JsonMini.getString(delta, "reasoning");
            if (reasoning != null && !reasoning.isEmpty() && cb != null) {
                cb.onReasoningDelta(reasoning);
            }
            String content = JsonMini.getString(delta, "content");
            if (content != null && !content.isEmpty()) {
                full.append(content);
                if (cb != null) {
                    cb.onTextDelta(content);
                }
            }
        }
        return false;
    }

    private static String parseChatCompletionsJson(String json, StreamCallback cb) throws IOException {
        Map<String, Object> root;
        try {
            root = JsonMini.parseObject(json);
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON response: " + truncate(json, 180), e);
        }
        Map<String, Object> err = JsonMini.getMap(root, "error");
        if (err != null) {
            throw new IOException(nonEmpty(JsonMini.getString(err, "message"), JsonMini.stringify(err)));
        }
        List<Object> choices = JsonMini.getList(root, "choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("Empty choices in response: " + truncate(json, 180));
        }
        Map<String, Object> c0 = JsonMini.asMap(choices.get(0));
        Map<String, Object> msg = JsonMini.getMap(c0, "message");
        if (msg == null) msg = JsonMini.getMap(c0, "delta");
        if (msg == null) return "";

        String reasoning = JsonMini.getString(msg, "reasoning_content");
        if (reasoning == null) reasoning = JsonMini.getString(msg, "reasoning");
        if (reasoning != null && !reasoning.isEmpty() && cb != null) {
            cb.onReasoningDelta(reasoning);
        }
        String content = JsonMini.getString(msg, "content");
        if (content != null && !content.isEmpty() && cb != null) {
            cb.onTextDelta(content);
        }
        return content == null ? "" : content;
    }

    private static String parseResponsesSse(
            InputStream is, StreamCallback cb, CancelToken cancel) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder full = new StringBuilder();
        String currentEvent = "";
        StringBuilder dataBuf = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (cancel != null && cancel.isCancelled()) {
                throw new CancellationException("Cancelled");
            }
            if (line.isEmpty()) {
                if (dataBuf.length() > 0) {
                    handleResponsesSseEvent(currentEvent, dataBuf.toString().trim(), full, cb);
                    dataBuf.setLength(0);
                    currentEvent = "";
                }
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String part = line.substring(5).trim();
                if ("[DONE]".equals(part)) break;
                if (dataBuf.length() > 0) dataBuf.append('\n');
                dataBuf.append(part);
            }
        }
        if (dataBuf.length() > 0) {
            handleResponsesSseEvent(currentEvent, dataBuf.toString().trim(), full, cb);
        }
        return full.toString();
    }

    private static String parseResponsesSseText(
            String text, StreamCallback cb, CancelToken cancel) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (InputStream bais = new java.io.ByteArrayInputStream(bytes)) {
            return parseResponsesSse(bais, cb, cancel);
        }
    }

    private static void handleResponsesSseEvent(
            String eventName, String data, StringBuilder full, StreamCallback cb) throws IOException {
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        Map<String, Object> root;
        try {
            root = JsonMini.parseObject(data);
        } catch (RuntimeException e) {
            return;
        }
        String type = nonEmpty(JsonMini.getString(root, "type"), eventName);
        if ("response.output_text.delta".equals(type)) {
            String delta = JsonMini.getString(root, "delta");
            if (delta != null && !delta.isEmpty()) {
                full.append(delta);
                if (cb != null) cb.onTextDelta(delta);
            }
        } else if ("response.reasoning_summary_text.delta".equals(type)
                || "response.reasoning_text.delta".equals(type)
                || "response.reasoning.delta".equals(type)) {
            String delta = JsonMini.getString(root, "delta");
            if (delta != null && !delta.isEmpty() && cb != null) {
                cb.onReasoningDelta(delta);
            }
        } else if ("response.completed".equals(type)) {
            if (full.length() == 0) {
                Map<String, Object> resp = JsonMini.getMap(root, "response");
                if (resp != null) {
                    String fallback = extractResponsesOutputText(resp);
                    if (!fallback.isEmpty()) {
                        full.append(fallback);
                        if (cb != null) cb.onTextDelta(fallback);
                    }
                }
            }
        } else if ("response.failed".equals(type) || "error".equals(type)) {
            Map<String, Object> resp = JsonMini.getMap(root, "response");
            Map<String, Object> err = resp != null ? JsonMini.getMap(resp, "error") : JsonMini.getMap(root, "error");
            String msg = err != null ? JsonMini.getString(err, "message") : JsonMini.getString(root, "message");
            throw new IOException(nonEmpty(msg, "Responses API failed"));
        }
    }

    private static String parseResponsesJson(String json, StreamCallback cb) throws IOException {
        Map<String, Object> root;
        try {
            root = JsonMini.parseObject(json);
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON response: " + truncate(json, 180), e);
        }
        Map<String, Object> err = JsonMini.getMap(root, "error");
        if (err != null) {
            throw new IOException(nonEmpty(JsonMini.getString(err, "message"), JsonMini.stringify(err)));
        }
        String reasoning = extractResponsesReasoningText(root);
        if (!reasoning.isEmpty() && cb != null) {
            cb.onReasoningDelta(reasoning);
        }
        String text = extractResponsesOutputText(root);
        if (!text.isEmpty() && cb != null) {
            cb.onTextDelta(text);
        }
        return text;
    }

    private static String extractResponsesOutputText(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        List<Object> output = JsonMini.getList(root, "output");
        if (output != null) {
            for (Object itemObj : output) {
                Map<String, Object> item = JsonMini.asMap(itemObj);
                if (item == null) continue;
                String itemType = JsonMini.getString(item, "type");
                if ("message".equals(itemType) || itemType == null) {
                    List<Object> content = JsonMini.getList(item, "content");
                    if (content != null) {
                        for (Object partObj : content) {
                            Map<String, Object> part = JsonMini.asMap(partObj);
                            if (part == null) continue;
                            String pt = JsonMini.getString(part, "type");
                            if ("output_text".equals(pt) || "text".equals(pt) || pt == null) {
                                String t = JsonMini.getString(part, "text");
                                if (t != null) sb.append(t);
                            }
                        }
                    }
                }
            }
        }
        if (sb.length() == 0) {
            String topText = JsonMini.getString(root, "output_text");
            if (topText != null) sb.append(topText);
        }
        return sb.toString();
    }

    private static String extractResponsesReasoningText(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        List<Object> output = JsonMini.getList(root, "output");
        if (output == null) return "";
        for (Object itemObj : output) {
            Map<String, Object> item = JsonMini.asMap(itemObj);
            if (item == null) continue;
            if (!"reasoning".equals(JsonMini.getString(item, "type"))) continue;
            List<Object> summary = JsonMini.getList(item, "summary");
            if (summary != null) {
                for (Object sObj : summary) {
                    Map<String, Object> sm = JsonMini.asMap(sObj);
                    if (sm != null) {
                        String t = JsonMini.getString(sm, "text");
                        if (t != null) sb.append(t);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void applyCustomHeaders(HttpURLConnection conn, String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) return;
        try {
            Map<String, Object> map = JsonMini.parseObject(headersJson.trim());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String k = e.getKey().trim();
                String v = String.valueOf(e.getValue());
                if (!k.isEmpty()) {
                    conn.setRequestProperty(k, v);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String extractErrorMessage(String errBody) {
        if (errBody == null || errBody.trim().isEmpty()) return "";
        try {
            Map<String, Object> root = JsonMini.parseObject(errBody.trim());
            Map<String, Object> err = JsonMini.getMap(root, "error");
            if (err != null) {
                String msg = JsonMini.getString(err, "message");
                if (msg != null) return msg;
            }
            String m = JsonMini.getString(root, "message");
            return m == null ? "" : m;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String nonEmpty(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
