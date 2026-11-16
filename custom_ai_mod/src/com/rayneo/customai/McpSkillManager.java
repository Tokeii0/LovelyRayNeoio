package com.rayneo.customai;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight, extensible MCP (Model Context Protocol - Streamable HTTP JSON-RPC 2.0)
 * + Agent Skill manager implementing {@link OpenAiApiClient.ToolExecutor}.
 *
 * Designed for plug-and-play extensibility:
 * 1. Native Java Skills: call {@link #registerBuiltinSkill(LocalSkillHandler)} anywhere.
 * 2. Declarative Skills (JSON): Prompt/SOP skills (instructions injection) + Webhook HTTP tools.
 * 3. Remote/LAN MCP Servers (JSON): supports both [{"name","url","headers"}] and
 *    Cursor/Claude {"mcpServers": {"name": {"url": ...}}} formats.
 */
public final class McpSkillManager {

    public static final String MCP_TOOL_PREFIX = "mcp__";
    private static final long MCP_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int DEFAULT_HTTP_TIMEOUT_MS = 12000;
    private static final AtomicInteger JSON_RPC_ID = new AtomicInteger(1);

    /**
     * Plug-in SPI for adding native Android/Java Skills in future iterations.
     */
    public interface LocalSkillHandler {
        String getName();
        String getDescription();
        Map<String, Object> getParametersSchema();
        String execute(Context context, Map<String, Object> args, long logId) throws Exception;
    }

    public static final class McpServerConfig {
        public final String name;
        public final String url;
        public final Map<String, String> headers;
        public final boolean enabled;
        public final int timeoutMs;

        public McpServerConfig(
                String name, String url, Map<String, String> headers, boolean enabled, int timeoutMs) {
            this.name = sanitizeIdentifier(name, "mcp");
            this.url = safe(url);
            this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
            this.enabled = enabled;
            this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_HTTP_TIMEOUT_MS;
        }
    }

    public static final class McpToolDef {
        public final String serverName;
        public final String remoteName;
        public final String exposedName;
        public final String description;
        public final Map<String, Object> inputSchema;

        public McpToolDef(
                String serverName,
                String remoteName,
                String description,
                Map<String, Object> inputSchema) {
            this.serverName = serverName;
            this.remoteName = remoteName;
            this.exposedName = MCP_TOOL_PREFIX + serverName + "__" + sanitizeToolName(remoteName);
            this.description = description == null ? "" : description;
            this.inputSchema = inputSchema != null ? inputSchema : defaultEmptyObjectSchema();
        }
    }

    public static final class CustomSkillDef {
        public final String name;
        public final String description;
        public final String instructions;
        public final String webhookUrl;
        public final String method;
        public final Map<String, String> headers;
        public final Map<String, Object> parametersSchema;
        public final boolean enabled;

        public CustomSkillDef(
                String name,
                String description,
                String instructions,
                String webhookUrl,
                String method,
                Map<String, String> headers,
                Map<String, Object> parametersSchema,
                boolean enabled) {
            this.name = sanitizeToolName(name);
            this.description = safe(description);
            this.instructions = safe(instructions);
            this.webhookUrl = safe(webhookUrl);
            this.method = safe(method).isEmpty() ? "POST" : safe(method).toUpperCase(Locale.ROOT);
            this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
            this.parametersSchema = parametersSchema != null ? parametersSchema : defaultQuerySchema();
            this.enabled = enabled;
        }

        public boolean isCallableTool() {
            return !webhookUrl.isEmpty() && !name.isEmpty();
        }

        public boolean hasPromptInstructions() {
            return !instructions.isEmpty();
        }
    }

    private static final class CachedMcpServerState {
        volatile String sessionId = "";
        volatile long fetchedAtMs = 0L;
        volatile List<McpToolDef> tools = Collections.emptyList();
    }

    private static final CopyOnWriteArrayList<LocalSkillHandler> BUILTIN_SKILLS =
            new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, CachedMcpServerState> MCP_CACHE =
            new ConcurrentHashMap<>();

    static {
        registerDefaultBuiltinSkills();
    }

    private McpSkillManager() {}

    public static void registerBuiltinSkill(LocalSkillHandler handler) {
        if (handler == null || safe(handler.getName()).isEmpty()) return;
        for (int i = 0; i < BUILTIN_SKILLS.size(); i++) {
            if (BUILTIN_SKILLS.get(i).getName().equals(handler.getName())) {
                BUILTIN_SKILLS.set(i, handler);
                return;
            }
        }
        BUILTIN_SKILLS.add(handler);
    }

    public static List<LocalSkillHandler> getRegisteredBuiltinSkills() {
        return new ArrayList<>(BUILTIN_SKILLS);
    }

    public static void clearMcpCache() {
        MCP_CACHE.clear();
    }

    /**
     * Creates a turn-scoped {@link OpenAiApiClient.ToolExecutor} from {@link CustomAiConfig},
     * or returns {@code null} if MCP & Skills are disabled in config.
     */
    public static OpenAiApiClient.ToolExecutor createExecutor(
            final Context ctx, final CustomAiConfig cfg, final long logId) {
        if (cfg == null || !cfg.enableMcpAndSkills) {
            return null;
        }
        final List<McpServerConfig> mcpServers = parseMcpServers(cfg.mcpServersJson);
        final List<CustomSkillDef> customSkills = parseCustomSkills(cfg.customSkillsJson);
        final boolean includeBuiltins = cfg.enableBuiltinSkills;

        boolean hasAnySource = includeBuiltins;
        if (!hasAnySource) {
            for (McpServerConfig s : mcpServers) {
                if (s.enabled && !s.url.isEmpty()) {
                    hasAnySource = true;
                    break;
                }
            }
        }
        if (!hasAnySource) {
            for (CustomSkillDef sk : customSkills) {
                if (sk.enabled) {
                    hasAnySource = true;
                    break;
                }
            }
        }
        if (!hasAnySource) {
            return null;
        }

        return new SessionToolExecutor(ctx, includeBuiltins, mcpServers, customSkills, logId);
    }

    // ==================== Turn-Scoped Executor ====================

    private static final class SessionToolExecutor implements OpenAiApiClient.ToolExecutor {
        private final Context context;
        private final boolean includeBuiltins;
        private final List<McpServerConfig> mcpServers;
        private final List<CustomSkillDef> customSkills;
        private final long logId;
        private List<ResolvedTool> resolvedToolsCache;

        private static final class ResolvedTool {
            final String name;
            final String description;
            final Map<String, Object> schema;
            final LocalSkillHandler localHandler;
            final CustomSkillDef webhookSkill;
            final McpServerConfig mcpServer;
            final McpToolDef mcpTool;

            ResolvedTool(
                    String name,
                    String description,
                    Map<String, Object> schema,
                    LocalSkillHandler localHandler,
                    CustomSkillDef webhookSkill,
                    McpServerConfig mcpServer,
                    McpToolDef mcpTool) {
                this.name = name;
                this.description = description;
                this.schema = schema;
                this.localHandler = localHandler;
                this.webhookSkill = webhookSkill;
                this.mcpServer = mcpServer;
                this.mcpTool = mcpTool;
            }
        }

        SessionToolExecutor(
                Context context,
                boolean includeBuiltins,
                List<McpServerConfig> mcpServers,
                List<CustomSkillDef> customSkills,
                long logId) {
            this.context = context != null ? context.getApplicationContext() : null;
            this.includeBuiltins = includeBuiltins;
            this.mcpServers = mcpServers;
            this.customSkills = customSkills;
            this.logId = logId;
        }

        private synchronized List<ResolvedTool> ensureToolsResolved() {
            if (resolvedToolsCache != null) return resolvedToolsCache;
            LinkedHashMap<String, ResolvedTool> map = new LinkedHashMap<>();

            if (includeBuiltins) {
                for (LocalSkillHandler h : BUILTIN_SKILLS) {
                    map.put(h.getName(), new ResolvedTool(
                            h.getName(), h.getDescription(), h.getParametersSchema(),
                            h, null, null, null));
                }
            }

            for (CustomSkillDef sk : customSkills) {
                if (sk.enabled && sk.isCallableTool()) {
                    map.put(sk.name, new ResolvedTool(
                            sk.name,
                            sk.description.isEmpty() ? ("Custom skill tool: " + sk.name) : sk.description,
                            sk.parametersSchema,
                            null, sk, null, null));
                }
            }

            for (McpServerConfig srv : mcpServers) {
                if (!srv.enabled || srv.url.isEmpty()) continue;
                try {
                    List<McpToolDef> remoteTools = getOrDiscoverMcpTools(srv, false);
                    for (McpToolDef td : remoteTools) {
                        String desc = "[MCP:" + srv.name + "] "
                                + (td.description.isEmpty() ? td.remoteName : td.description);
                        map.put(td.exposedName, new ResolvedTool(
                                td.exposedName, desc, td.inputSchema,
                                null, null, srv, td));
                    }
                } catch (Throwable t) {
                    if (logId > 0L) {
                        CustomAiCallLog.addEvent(logId,
                                "MCP_LIST_ERR(" + srv.name + ":" + safe(t.getMessage()) + ")");
                    }
                }
            }

            resolvedToolsCache = new ArrayList<>(map.values());
            return resolvedToolsCache;
        }

        @Override
        public List<Map<String, Object>> getChatCompletionsTools() {
            List<ResolvedTool> list = ensureToolsResolved();
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (ResolvedTool rt : list) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", rt.name);
                fn.put("description", rt.description);
                fn.put("parameters", rt.schema != null ? rt.schema : defaultEmptyObjectSchema());
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("type", "function");
                wrap.put("function", fn);
                out.add(wrap);
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> getResponsesTools() {
            List<ResolvedTool> list = ensureToolsResolved();
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (ResolvedTool rt : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "function");
                item.put("name", rt.name);
                item.put("description", rt.description);
                item.put("parameters", rt.schema != null ? rt.schema : defaultEmptyObjectSchema());
                out.add(item);
            }
            return out;
        }

        @Override
        public String getActiveSkillInstructions() {
            StringBuilder sb = new StringBuilder();
            for (CustomSkillDef sk : customSkills) {
                if (sk.enabled && sk.hasPromptInstructions()) {
                    if (sb.length() == 0) {
                        sb.append("[已启用的自定义技能规范 (Active Skills)：\n");
                    } else {
                        sb.append('\n');
                    }
                    sb.append("- Skill `").append(sk.name.isEmpty() ? "custom" : sk.name).append("`");
                    if (!sk.description.isEmpty()) {
                        sb.append(" (").append(sk.description).append(")");
                    }
                    sb.append(": ").append(sk.instructions);
                }
            }
            if (sb.length() > 0) {
                sb.append("\n]");
            }
            return sb.toString();
        }

        @Override
        public String executeTool(String toolName, String argumentsJson) throws Exception {
            List<ResolvedTool> list = ensureToolsResolved();
            ResolvedTool target = null;
            for (ResolvedTool rt : list) {
                if (rt.name.equals(toolName)) {
                    target = rt;
                    break;
                }
            }
            if (target == null) {
                return "{\"error\":\"Unknown tool: " + safe(toolName) + "\"}";
            }

            Map<String, Object> argsMap = parseArgsObjectSafe(argumentsJson);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "SKILL_TOOL_CALL(name=" + toolName + ")");
            }

            String result;
            if (target.localHandler != null) {
                result = target.localHandler.execute(context, argsMap, logId);
            } else if (target.webhookSkill != null) {
                result = executeWebhookSkill(target.webhookSkill, argsMap);
            } else if (target.mcpServer != null && target.mcpTool != null) {
                result = callMcpTool(target.mcpServer, target.mcpTool.remoteName, argsMap);
            } else {
                result = "{\"error\":\"No handler bound for " + toolName + "\"}";
            }

            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "SKILL_TOOL_OK(name=" + toolName
                        + ", chars=" + (result != null ? result.length() : 0) + ")");
            }
            return result == null ? "" : result;
        }
    }

    // ==================== MCP Streamable HTTP JSON-RPC 2.0 Client ====================

    public static String probeAllMcpServers(String mcpServersJson) {
        List<McpServerConfig> servers = parseMcpServers(mcpServersJson);
        if (servers.isEmpty()) {
            return "未配置 MCP Server（列表为空 []）";
        }
        StringBuilder sb = new StringBuilder();
        int activeCount = 0;
        for (McpServerConfig srv : servers) {
            if (!srv.enabled) {
                sb.append("⏸ ").append(srv.name).append(": 已禁用\n");
                continue;
            }
            activeCount++;
            try {
                List<McpToolDef> tools = getOrDiscoverMcpTools(srv, true);
                sb.append("✅ ").append(srv.name).append(" (").append(tools.size()).append(" 个工具): ");
                for (int i = 0; i < tools.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(tools.get(i).remoteName);
                }
                sb.append('\n');
            } catch (Throwable e) {
                sb.append("❌ ").append(srv.name).append(": ").append(safe(e.getMessage())).append('\n');
            }
        }
        if (activeCount == 0 && sb.length() == 0) {
            return "暂无启用的 MCP Server";
        }
        return sb.toString().trim();
    }

    public static List<McpToolDef> getOrDiscoverMcpTools(McpServerConfig srv, boolean forceRefresh)
            throws IOException {
        String cacheKey = srv.name + "|" + srv.url;
        long now = System.currentTimeMillis();
        CachedMcpServerState state = MCP_CACHE.get(cacheKey);
        if (!forceRefresh && state != null && (now - state.fetchedAtMs) < MCP_CACHE_TTL_MS && !state.tools.isEmpty()) {
            return state.tools;
        }
        if (state == null) {
            state = new CachedMcpServerState();
            MCP_CACHE.put(cacheKey, state);
        }

        ensureMcpInitialized(srv, state, true);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", JSON_RPC_ID.incrementAndGet());
        req.put("method", "tools/list");
        req.put("params", new LinkedHashMap<String, Object>());

        Map<String, Object> resp = sendMcpJsonRpc(srv, state, req, true);
        Map<String, Object> result = JsonMini.getMap(resp, "result");
        List<Object> rawTools = JsonMini.getList(result, "tools");
        List<McpToolDef> parsed = new ArrayList<>();
        if (rawTools != null) {
            for (Object o : rawTools) {
                Map<String, Object> tm = JsonMini.asMap(o);
                if (tm == null) continue;
                String tName = safe(JsonMini.getString(tm, "name"));
                if (tName.isEmpty()) continue;
                String tDesc = safe(JsonMini.getString(tm, "description"));
                Map<String, Object> schema = JsonMini.getMap(tm, "inputSchema");
                if (schema == null) schema = JsonMini.getMap(tm, "parameters");
                parsed.add(new McpToolDef(srv.name, tName, tDesc, schema));
            }
        }
        state.tools = parsed;
        state.fetchedAtMs = System.currentTimeMillis();
        return parsed;
    }

    public static String callMcpTool(
            McpServerConfig srv, String remoteToolName, Map<String, Object> argsMap) throws IOException {
        String cacheKey = srv.name + "|" + srv.url;
        CachedMcpServerState state = MCP_CACHE.get(cacheKey);
        if (state == null) {
            state = new CachedMcpServerState();
            MCP_CACHE.put(cacheKey, state);
        }
        if (state.sessionId.isEmpty() && state.fetchedAtMs == 0L) {
            ensureMcpInitialized(srv, state, false);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", remoteToolName);
        params.put("arguments", argsMap != null ? argsMap : new LinkedHashMap<String, Object>());

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", JSON_RPC_ID.incrementAndGet());
        req.put("method", "tools/call");
        req.put("params", params);

        Map<String, Object> resp;
        try {
            resp = sendMcpJsonRpc(srv, state, req, true);
        } catch (McpSessionExpiredException expired) {
            // Re-initialize once if Mcp-Session-Id expired on server
            ensureMcpInitialized(srv, state, true);
            resp = sendMcpJsonRpc(srv, state, req, true);
        }

        Map<String, Object> result = JsonMini.getMap(resp, "result");
        if (result == null) {
            return JsonMini.stringify(resp);
        }
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        List<Object> contentList = JsonMini.getList(result, "content");
        if (contentList != null && !contentList.isEmpty()) {
            StringBuilder textOut = new StringBuilder();
            for (Object itemObj : contentList) {
                Map<String, Object> ci = JsonMini.asMap(itemObj);
                if (ci == null) continue;
                String txt = JsonMini.getString(ci, "text");
                if (txt != null && !txt.isEmpty()) {
                    if (textOut.length() > 0) textOut.append('\n');
                    textOut.append(txt);
                } else {
                    if (textOut.length() > 0) textOut.append('\n');
                    textOut.append(JsonMini.stringify(ci));
                }
            }
            if (isError) {
                return "{\"isError\":true,\"message\":" + JsonMini.stringify(textOut.toString()) + "}";
            }
            return textOut.toString();
        }
        Object structured = result.get("structuredContent");
        if (structured != null) {
            return JsonMini.stringify(structured);
        }
        return JsonMini.stringify(result);
    }

    private static final class McpSessionExpiredException extends IOException {
        McpSessionExpiredException(String msg) { super(msg); }
    }

    private static void ensureMcpInitialized(
            McpServerConfig srv, CachedMcpServerState state, boolean force) throws IOException {
        if (!force && state.fetchedAtMs > 0L) return;
        state.sessionId = "";

        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "LovelyRayNeoio");
        clientInfo.put("version", "1.0.25");

        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", "2025-03-26");
        initParams.put("capabilities", new LinkedHashMap<String, Object>());
        initParams.put("clientInfo", clientInfo);

        Map<String, Object> initReq = new LinkedHashMap<>();
        initReq.put("jsonrpc", "2.0");
        initReq.put("id", JSON_RPC_ID.incrementAndGet());
        initReq.put("method", "initialize");
        initReq.put("params", initParams);

        sendMcpJsonRpc(srv, state, initReq, true);

        // Best-effort notifications/initialized per MCP spec
        try {
            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/initialized");
            sendMcpJsonRpc(srv, state, notif, false);
        } catch (Throwable ignored) {}
    }

    private static Map<String, Object> sendMcpJsonRpc(
            McpServerConfig srv,
            CachedMcpServerState state,
            Map<String, Object> payload,
            boolean expectResponse) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(srv.url).openConnection();
            conn.setConnectTimeout(srv.timeoutMs);
            conn.setReadTimeout(srv.timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (state != null && !state.sessionId.isEmpty()) {
                conn.setRequestProperty("Mcp-Session-Id", state.sessionId);
            }
            for (Map.Entry<String, String> h : srv.headers.entrySet()) {
                if (!safe(h.getKey()).isEmpty() && h.getValue() != null) {
                    conn.setRequestProperty(h.getKey().trim(), h.getValue());
                }
            }

            byte[] out = JsonMini.stringify(payload).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
                os.flush();
            }

            int status = conn.getResponseCode();
            // Capture Mcp-Session-Id case-insensitively
            if (state != null) {
                for (Map.Entry<String, List<String>> he : conn.getHeaderFields().entrySet()) {
                    if (he.getKey() != null && "mcp-session-id".equalsIgnoreCase(he.getKey())
                            && he.getValue() != null && !he.getValue().isEmpty()) {
                        String sid = safe(he.getValue().get(0));
                        if (!sid.isEmpty()) {
                            state.sessionId = sid;
                        }
                    }
                }
            }

            if (status == 404 && state != null && !state.sessionId.isEmpty()) {
                throw new McpSessionExpiredException("MCP session expired");
            }
            if (status < 200 || status >= 300) {
                String errBody = readStreamString(conn.getErrorStream());
                throw new IOException("MCP HTTP " + status + " (" + srv.name + "): " + truncate(errBody, 200));
            }
            if (!expectResponse || status == 202 || status == 204) {
                return Collections.emptyMap();
            }

            String ctype = conn.getContentType();
            boolean isSse = ctype != null && ctype.toLowerCase(Locale.ROOT).contains("text/event-stream");
            String raw = readStreamString(conn.getInputStream());
            Map<String, Object> rpcObj = isSse || raw.trim().startsWith("event:") || raw.trim().startsWith("data:")
                    ? parseFirstJsonRpcFromSse(raw)
                    : JsonMini.parseObject(raw);

            Map<String, Object> err = JsonMini.getMap(rpcObj, "error");
            if (err != null) {
                String msg = JsonMini.getString(err, "message");
                throw new IOException("MCP error (" + srv.name + "): "
                        + (msg != null ? msg : JsonMini.stringify(err)));
            }
            return rpcObj;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static Map<String, Object> parseFirstJsonRpcFromSse(String rawSse) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(rawSse.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        StringBuilder dataBuf = new StringBuilder();
        Map<String, Object> lastValid = null;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                if (dataBuf.length() > 0) {
                    Map<String, Object> parsed = tryParseRpcData(dataBuf.toString().trim());
                    if (parsed != null) {
                        lastValid = parsed;
                        if (parsed.containsKey("result") || parsed.containsKey("error")) {
                            return parsed;
                        }
                    }
                    dataBuf.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                String part = line.substring(5).trim();
                if (dataBuf.length() > 0) dataBuf.append('\n');
                dataBuf.append(part);
            }
        }
        if (dataBuf.length() > 0) {
            Map<String, Object> parsed = tryParseRpcData(dataBuf.toString().trim());
            if (parsed != null) return parsed;
        }
        if (lastValid != null) return lastValid;
        throw new IOException("Empty SSE response from MCP server");
    }

    private static Map<String, Object> tryParseRpcData(String s) {
        if (s.isEmpty() || "[DONE]".equals(s)) return null;
        try {
            return JsonMini.parseObject(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== Webhook Skill Execution ====================

    private static String executeWebhookSkill(CustomSkillDef sk, Map<String, Object> argsMap) throws IOException {
        String targetUrl = sk.webhookUrl;
        boolean isGet = "GET".equalsIgnoreCase(sk.method);
        if (isGet && argsMap != null && !argsMap.isEmpty()) {
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, Object> e : argsMap.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (qs.length() > 0) qs.append('&');
                qs.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            }
            if (qs.length() > 0) {
                targetUrl += (targetUrl.contains("?") ? "&" : "?") + qs;
            }
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setConnectTimeout(DEFAULT_HTTP_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_HTTP_TIMEOUT_MS);
            conn.setRequestMethod(isGet ? "GET" : sk.method);
            conn.setUseCaches(false);
            for (Map.Entry<String, String> h : sk.headers.entrySet()) {
                if (!safe(h.getKey()).isEmpty() && h.getValue() != null) {
                    conn.setRequestProperty(h.getKey().trim(), h.getValue());
                }
            }
            if (!isGet) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] body = JsonMini.stringify(
                        argsMap != null ? argsMap : Collections.emptyMap()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }
            }
            int code = conn.getResponseCode();
            String resp = readStreamString(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                return "{\"status\":" + code + ",\"error\":" + JsonMini.stringify(truncate(resp, 400)) + "}";
            }
            return resp.isEmpty() ? "{\"ok\":true}" : truncate(resp, 4000);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== Built-in Local Android Skills ====================

    private static void registerDefaultBuiltinSkills() {
        // 1. Push outline / notes / script to glasses Teleprompter HUD
        registerBuiltinSkill(new LocalSkillHandler() {
            @Override
            public String getName() {
                return "push_to_teleprompter";
            }

            @Override
            public String getDescription() {
                return "将演讲提纲、要点备忘、长文总结或台词推送到雷鸟智能眼镜的提词器 HUD 屏幕上常驻显示，方便用户抬眼阅读或滑动镜腿翻页。当用户要求『投到眼镜上 / 放到提词器里 / 发到眼镜屏幕上看』时调用。";
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("title", propString("提词标题（简短概括，如『三分钟自我介绍提纲』）"));
                props.put("content", propString("要显示在眼镜提词器上的正文内容（分条清晰，纯文本无复杂 Markdown 表格）"));
                List<Object> req = new ArrayList<>();
                req.add("content");
                return buildObjectSchema(props, req);
            }

            @Override
            public String execute(Context context, Map<String, Object> args, long logId) {
                String title = safe(JsonMini.getString(args, "title"));
                String content = safe(JsonMini.getString(args, "content"));
                if (content.isEmpty()) {
                    return "{\"ok\":false,\"error\":\"content 不能为空\"}";
                }
                String msg = LyricsTeleprompterBridge.pushCustomTextToGlasses(context, title, content);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("message", msg);
                return JsonMini.stringify(out);
            }
        });

        // 2. Phone Clipboard Read / Write
        registerBuiltinSkill(new LocalSkillHandler() {
            @Override
            public String getName() {
                return "clipboard_manage";
            }

            @Override
            public String getDescription() {
                return "读取或写入手机系统剪贴板。当用户说『看看我剪贴板里复制了什么』『帮我翻译/总结剪贴板内容』或『把这段话复制到手机剪贴板』时调用。";
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                Map<String, Object> actionProp = propString("操作类型：read（读取剪贴板）或 write（写入剪贴板）");
                List<Object> enums = new ArrayList<>();
                enums.add("read");
                enums.add("write");
                actionProp.put("enum", enums);

                Map<String, Object> props = new LinkedHashMap<>();
                props.put("action", actionProp);
                props.put("text", propString("当 action=write 时要写入手机剪贴板的文本内容"));
                List<Object> req = new ArrayList<>();
                req.add("action");
                return buildObjectSchema(props, req);
            }

            @Override
            public String execute(final Context context, Map<String, Object> args, long logId) throws Exception {
                if (context == null) {
                    return "{\"ok\":false,\"error\":\"Android Context 不可用\"}";
                }
                final String action = safe(JsonMini.getString(args, "action")).toLowerCase(Locale.ROOT);
                final String textToWrite = safe(JsonMini.getString(args, "text"));
                final String[] resultHolder = new String[1];
                final CountDownLatch latch = new CountDownLatch(1);

                Runnable uiTask = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                            Map<String, Object> res = new LinkedHashMap<>();
                            if (cm == null) {
                                res.put("ok", false);
                                res.put("error", "ClipboardManager unavailable");
                            } else if ("write".equals(action)) {
                                cm.setPrimaryClip(ClipData.newPlainText("RayNeoAI", textToWrite));
                                res.put("ok", true);
                                res.put("writtenChars", textToWrite.length());
                            } else {
                                String clipText = "";
                                if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                                        && cm.getPrimaryClip().getItemCount() > 0) {
                                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                                    if (cs != null) clipText = cs.toString();
                                }
                                res.put("ok", true);
                                res.put("clipboard", truncate(clipText, 3000));
                            }
                            resultHolder[0] = JsonMini.stringify(res);
                        } catch (Throwable t) {
                            resultHolder[0] = "{\"ok\":false,\"error\":" + JsonMini.stringify(t.getMessage()) + "}";
                        } finally {
                            latch.countDown();
                        }
                    }
                };

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    uiTask.run();
                } else {
                    new Handler(Looper.getMainLooper()).post(uiTask);
                    latch.await(2500L, TimeUnit.MILLISECONDS);
                }
                return resultHolder[0] != null ? resultHolder[0] : "{\"ok\":false,\"error\":\"Clipboard timeout\"}";
            }
        });

        // 3. Phone & Media Live Context Inspector
        registerBuiltinSkill(new LocalSkillHandler() {
            @Override
            public String getName() {
                return "get_phone_status";
            }

            @Override
            public String getDescription() {
                return "查询用户手机当前实时电量、充电状态、正在播放的音乐详情及眼镜歌词提词器状态。注意：仅当用户询问手机自身状态时调用（询问眼镜硬件电量请走原版硬件指令）。";
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                return defaultEmptyObjectSchema();
            }

            @Override
            public String execute(Context context, Map<String, Object> args, long logId) {
                Map<String, Object> out = new LinkedHashMap<>();
                if (context != null) {
                    try {
                        Intent batt = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        if (batt != null) {
                            int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                            int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                            if (level >= 0 && scale > 0) {
                                out.put("phoneBatteryPercent", (level * 100) / scale);
                            }
                            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                    || status == BatteryManager.BATTERY_STATUS_FULL;
                            out.put("phoneCharging", charging);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        SystemMediaBridge.TrackInfo ti = SystemMediaBridge.getCurrentTrack(context);
                        if (ti != null && ti.hasSong()) {
                            out.put("playingSong", ti.formatSongDisplay());
                            out.put("musicApp", ti.appName);
                            out.put("isPlaying", ti.isPlaying());
                        } else {
                            out.put("playingSong", "none");
                        }
                    } catch (Throwable ignored) {}
                }
                out.put("lyricsTeleprompterStatus", LyricsTeleprompterBridge.getStatusSummary());
                return JsonMini.stringify(out);
            }
        });
    }

    // ==================== Configuration Parsers & Templates ====================

    /**
     * Parses MCP servers JSON supporting BOTH:
     * 1) Array format: [{"name":"home","url":"https://...","token":"...","headers":{},"enabled":true}]
     * 2) Standard Claude/Cursor object format: {"mcpServers":{"home":{"url":"https://...","headers":{}}}}
     */
    public static List<McpServerConfig> parseMcpServers(String rawJson) {
        List<McpServerConfig> out = new ArrayList<>();
        if (rawJson == null || rawJson.trim().isEmpty()) return out;
        try {
            Object parsed = JsonMini.parse(rawJson.trim());
            List<Object> items = JsonMini.asList(parsed);
            if (items != null) {
                int idx = 1;
                for (Object o : items) {
                    Map<String, Object> m = JsonMini.asMap(o);
                    if (m == null) continue;
                    McpServerConfig cfg = mapToMcpServer(
                            nonEmpty(JsonMini.getString(m, "name"), "mcp" + idx), m);
                    if (!cfg.url.isEmpty()) {
                        out.add(cfg);
                        idx++;
                    }
                }
                return out;
            }
            Map<String, Object> obj = JsonMini.asMap(parsed);
            if (obj != null) {
                Map<String, Object> serversMap = JsonMini.getMap(obj, "mcpServers");
                if (serversMap == null) serversMap = obj;
                for (Map.Entry<String, Object> e : serversMap.entrySet()) {
                    Map<String, Object> sm = JsonMini.asMap(e.getValue());
                    if (sm == null) continue;
                    McpServerConfig cfg = mapToMcpServer(e.getKey(), sm);
                    if (!cfg.url.isEmpty()) {
                        out.add(cfg);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static McpServerConfig mapToMcpServer(String defaultName, Map<String, Object> m) {
        String name = nonEmpty(JsonMini.getString(m, "name"), defaultName);
        String url = nonEmpty(JsonMini.getString(m, "url"), JsonMini.getString(m, "serverUrl"));
        boolean enabled = !(m.get("enabled") instanceof Boolean) || (Boolean) m.get("enabled");
        if (Boolean.TRUE.equals(m.get("disabled"))) enabled = false;
        int timeoutMs = JsonMini.getInt(m, "timeoutMs", DEFAULT_HTTP_TIMEOUT_MS);

        Map<String, String> headers = new LinkedHashMap<>();
        String token = safe(JsonMini.getString(m, "token"));
        if (token.isEmpty()) token = safe(JsonMini.getString(m, "apiKey"));
        if (!token.isEmpty()) {
            headers.put("Authorization", token.startsWith("Bearer ") ? token : ("Bearer " + token));
        }
        Map<String, Object> rawHeaders = JsonMini.getMap(m, "headers");
        if (rawHeaders != null) {
            for (Map.Entry<String, Object> he : rawHeaders.entrySet()) {
                if (he.getKey() != null && he.getValue() != null) {
                    headers.put(he.getKey().trim(), String.valueOf(he.getValue()));
                }
            }
        }
        return new McpServerConfig(name, url, headers, enabled, timeoutMs);
    }

    public static List<CustomSkillDef> parseCustomSkills(String rawJson) {
        List<CustomSkillDef> out = new ArrayList<>();
        if (rawJson == null || rawJson.trim().isEmpty()) return out;
        try {
            Object parsed = JsonMini.parse(rawJson.trim());
            List<Object> items = JsonMini.asList(parsed);
            if (items == null) {
                Map<String, Object> wrap = JsonMini.asMap(parsed);
                if (wrap != null) {
                    items = JsonMini.getList(wrap, "skills");
                }
            }
            if (items == null) return out;

            int idx = 1;
            for (Object o : items) {
                Map<String, Object> m = JsonMini.asMap(o);
                if (m == null) continue;
                String name = nonEmpty(JsonMini.getString(m, "name"), "skill_" + idx);
                String desc = safe(JsonMini.getString(m, "description"));
                String inst = nonEmpty(JsonMini.getString(m, "instructions"), JsonMini.getString(m, "prompt"));
                String webhook = nonEmpty(JsonMini.getString(m, "webhookUrl"), JsonMini.getString(m, "url"));
                String method = nonEmpty(JsonMini.getString(m, "method"), "POST");
                boolean enabled = !(m.get("enabled") instanceof Boolean) || (Boolean) m.get("enabled");

                Map<String, String> headers = new LinkedHashMap<>();
                Map<String, Object> rawHeaders = JsonMini.getMap(m, "headers");
                if (rawHeaders != null) {
                    for (Map.Entry<String, Object> he : rawHeaders.entrySet()) {
                        if (he.getKey() != null && he.getValue() != null) {
                            headers.put(he.getKey().trim(), String.valueOf(he.getValue()));
                        }
                    }
                }
                Map<String, Object> schema = JsonMini.getMap(m, "parameters");
                if (schema == null) schema = JsonMini.getMap(m, "inputSchema");

                out.add(new CustomSkillDef(name, desc, inst, webhook, method, headers, schema, enabled));
                idx++;
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static String sampleMcpServersTemplate() {
        return "[\n"
                + "  {\n"
                + "    \"name\": \"my_mcp\",\n"
                + "    \"url\": \"https://your-mcp-server.example.com/mcp\",\n"
                + "    \"token\": \"\",\n"
                + "    \"enabled\": false\n"
                + "  }\n"
                + "]";
    }

    public static String sampleCustomSkillsTemplate() {
        return "[\n"
                + "  {\n"
                + "    \"name\": \"jp_study_helper\",\n"
                + "    \"description\": \"日语学习口语助手规范\",\n"
                + "    \"instructions\": \"当用户询问日语表达或歌词含义时，简洁给出原句、罗马音与中文释义。\",\n"
                + "    \"enabled\": true\n"
                + "  },\n"
                + "  {\n"
                + "    \"name\": \"webhook_memo\",\n"
                + "    \"description\": \"发送随身闪念笔记到外部 Webhook (飞书/Notion/HomeAssistant/OpenClaw)\",\n"
                + "    \"webhookUrl\": \"https://your-webhook.example.com/hook\",\n"
                + "    \"method\": \"POST\",\n"
                + "    \"parameters\": {\n"
                + "      \"type\": \"object\",\n"
                + "      \"properties\": {\n"
                + "        \"text\": {\"type\": \"string\", \"description\": \"笔记或指令内容\"}\n"
                + "      },\n"
                + "      \"required\": [\"text\"]\n"
                + "    },\n"
                + "    \"enabled\": false\n"
                + "  }\n"
                + "]";
    }

    // ==================== Schema & String Helpers ====================

    private static Map<String, Object> parseArgsObjectSafe(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return JsonMini.parseObject(json.trim());
        } catch (Throwable ignored) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private static Map<String, Object> defaultEmptyObjectSchema() {
        return buildObjectSchema(new LinkedHashMap<String, Object>(), Collections.emptyList());
    }

    private static Map<String, Object> defaultQuerySchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", propString("请求内容或参数说明"));
        return buildObjectSchema(props, Collections.emptyList());
    }

    private static Map<String, Object> propString(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    private static Map<String, Object> buildObjectSchema(Map<String, Object> props, List<Object> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (required != null && !required.isEmpty()) {
            s.put("required", required);
        }
        return s;
    }

    private static String sanitizeIdentifier(String raw, String fallback) {
        String s = safe(raw).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return s.isEmpty() ? fallback : s;
    }

    private static String sanitizeToolName(String raw) {
        return safe(raw).replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String readStreamString(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nonEmpty(String s, String def) {
        String t = safe(s);
        return t.isEmpty() ? safe(def) : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : (s.substring(0, max) + "...");
    }
}
