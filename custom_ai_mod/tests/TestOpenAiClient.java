import com.rayneo.customai.ConversationMemory;
import com.rayneo.customai.CustomAiCallLog;
import com.rayneo.customai.CustomAiConfig;
import com.rayneo.customai.CustomAiHooks;
import com.rayneo.customai.JsonMini;
import com.rayneo.customai.LyricsTeleprompterBridge;
import com.rayneo.customai.McpSkillManager;
import com.rayneo.customai.OpenAiApiClient;
import com.rayneo.customai.SystemMediaBridge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone unit test for JsonMini, ConversationMemory, and OpenAiApiClient
 * (/v1/chat/completions + /v1/responses, SSE streaming + non-streaming JSON + reasoning rules).
 */
public final class TestOpenAiClient {

    private static volatile String lastRequestPath = "";
    private static volatile String lastRequestBody = "";
    private static volatile String lastAuthHeader = "";
    private static volatile String lastCustomHeader = "";

    public static void main(String[] args) throws Exception {
        testUrlResolution();
        testMemory();
        testCallLogLifecycle();
        testSkillIntentRouting();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/v1";

        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                lastRequestPath = ex.getRequestURI().getPath();
                lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
                lastCustomHeader = ex.getRequestHeaders().getFirst("X-Test-Header");
                lastRequestBody = readAll(ex.getRequestBody());
                Map<String, Object> req = JsonMini.parseObject(lastRequestBody);
                boolean stream = Boolean.TRUE.equals(req.get("stream"));

                if (stream) {
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先思考\"}}]}\n\n");
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"content\":\"你好，\"}}]}\n\n");
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"content\":\"雷鸟io！\"}}]}\n\n");
                        writeSse(os, "data: [DONE]\n\n");
                    }
                } else {
                    byte[] body = ("{\"choices\":[{\"message\":{\"reasoning_content\":\"think\",\"content\":\"JSON回复\"}}]}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                }
            }
        });

        server.createContext("/v1/responses", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                lastRequestPath = ex.getRequestURI().getPath();
                lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
                lastRequestBody = readAll(ex.getRequestBody());
                Map<String, Object> req = JsonMini.parseObject(lastRequestBody);
                boolean stream = Boolean.TRUE.equals(req.get("stream"));

                if (stream) {
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "event: response.reasoning_summary_text.delta\n"
                                + "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"推理中\"}\n\n");
                        writeSse(os, "event: response.output_text.delta\n"
                                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Responses \"}\n\n");
                        writeSse(os, "event: response.output_text.delta\n"
                                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n");
                        writeSse(os, "event: response.completed\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Responses OK\"}]}]}}\n\n");
                    }
                } else {
                    byte[] body = ("{\"id\":\"resp_1\",\"output\":["
                            + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"摘要\"}]},"
                            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"非流式Resp\"}]}"
                            + "]}").getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                }
            }
        });

        server.start();
        try {
            testChatCompletionsSse(baseUrl);
            testChatCompletionsJson(baseUrl);
            testResponsesSseWithReasoning(baseUrl);
            testResponsesJson(baseUrl);
            testMusicSearchMockHttp(server, "http://127.0.0.1:" + port);
            testLyricsTeleprompterUnitAndMockHttp(server, "http://127.0.0.1:" + port);
            testBilingualAndRomajiLyrics(server, "http://127.0.0.1:" + port);
            testMcpAndSkillsEngine(server, "http://127.0.0.1:" + port);
            testR8DynamicCoreConnectReflection();
            System.out.println("ALL TESTS PASSED!");
        } finally {
            server.stop(0);
        }
    }

    private static void testUrlResolution() {
        assertEq("https://api.openai.com/v1/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://api.openai.com", "chat_completions"), "root->chat");
        assertEq("https://api.openai.com/v1/responses",
                OpenAiApiClient.resolveEndpointUrl("https://api.openai.com/v1/", "responses"), "v1->resp");
        assertEq("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://dashscope.aliyuncs.com/compatible-mode/v1", "chat_completions"), "bailian");
        assertEq("https://proxy.example.com/v1/responses",
                OpenAiApiClient.resolveEndpointUrl("https://proxy.example.com/v1/chat/completions", "responses"), "swap-to-resp");
        assertEq("https://proxy.example.com/v1/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://proxy.example.com/v1/responses", "chat_completions"), "swap-to-chat");
        assertEq("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://open.bigmodel.cn/api/paas/v4", "chat_completions"), "v4");
        assertEq("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://ark.cn-beijing.volces.com/api/v3", "chat_completions"), "volc-ark-v3");
        assertEq("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OpenAiApiClient.resolveEndpointUrl("https://generativelanguage.googleapis.com/v1beta/openai", "chat_completions"), "gemini-compat");
        assertEq(true, OpenAiApiClient.shouldOmitTemperature("gemini-4-hydrogen", 0.7), "gemini-4 omit temp");
        assertEq(true, OpenAiApiClient.shouldOmitTemperature("google/gemini-4-carbon", 0.7), "openrouter gemini-4 omit temp");
        assertEq(true, OpenAiApiClient.shouldOmitTemperature("gemini-3.8-flash", 0.7), "gemini-3.8 omit temp");
        assertEq(false, OpenAiApiClient.shouldOmitTemperature("deepseek-flash", 0.7), "deepseek-flash keeps temp");
        assertEq(false, OpenAiApiClient.shouldOmitTemperature("gpt-6-luna", 0.7), "gpt-6-luna keeps temp");
    }

    private static void testMemory() {
        ConversationMemory.clearAll();
        ConversationMemory.appendTurn("d1", "q1", "a1", 2);
        ConversationMemory.appendTurn("d1", "q2", "a2", 2);
        ConversationMemory.appendTurn("d1", "q3", "a3", 2);
        List<ConversationMemory.Message> h = ConversationMemory.getHistory("d1", 2);
        assertEq(4, h.size(), "history size capped");
        assertEq("q2", h.get(0).content, "oldest kept");
        assertEq("a3", h.get(3).content, "newest kept");
    }

    private static void testChatCompletionsSse(String baseUrl) throws Exception {
        OpenAiApiClient.EndpointConfig cfg = new OpenAiApiClient.EndpointConfig();
        cfg.protocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
        cfg.baseUrl = baseUrl;
        cfg.apiKey = "sk-chat-test";
        cfg.model = "deepseek-chat";
        cfg.systemPrompt = "sys";
        cfg.streamEnabled = true;
        cfg.customHeadersJson = "{\"X-Test-Header\":\"rayneo\"}";

        final List<String> textDeltas = new ArrayList<>();
        final StringBuilder reason = new StringBuilder();
        String full = OpenAiApiClient.streamChat(cfg, null, "嗨", new OpenAiApiClient.StreamCallback() {
            @Override public void onReasoningDelta(String d) { reason.append(d); }
            @Override public void onTextDelta(String d) { textDeltas.add(d); }
        }, new OpenAiApiClient.CancelToken());

        assertEq("/v1/chat/completions", lastRequestPath, "chat path");
        assertEq("Bearer sk-chat-test", lastAuthHeader, "chat auth");
        assertEq("rayneo", lastCustomHeader, "custom header");
        assertEq("先思考", reason.toString(), "chat reasoning");
        assertEq(2, textDeltas.size(), "chat delta count");
        assertEq("你好，", textDeltas.get(0), "chat delta 0");
        assertEq("雷鸟io！", textDeltas.get(1), "chat delta 1");
        assertEq("你好，雷鸟io！", full, "chat full");
    }

    private static void testChatCompletionsJson(String baseUrl) throws Exception {
        OpenAiApiClient.EndpointConfig cfg = new OpenAiApiClient.EndpointConfig();
        cfg.protocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
        cfg.baseUrl = baseUrl;
        cfg.apiKey = "k";
        cfg.streamEnabled = false;
        String full = OpenAiApiClient.streamChat(cfg, null, "hi", null, null);
        assertEq("JSON回复", full, "chat json reply");
    }

    private static void testResponsesSseWithReasoning(String baseUrl) throws Exception {
        OpenAiApiClient.EndpointConfig cfg = new OpenAiApiClient.EndpointConfig();
        cfg.protocol = OpenAiApiClient.PROTO_RESPONSES;
        cfg.baseUrl = baseUrl;
        cfg.apiKey = "sk-resp-test";
        cfg.model = "gpt-5-mini";
        cfg.systemPrompt = "be concise";
        cfg.reasoningEffort = "medium";
        cfg.streamEnabled = true;

        final List<String> deltas = new ArrayList<>();
        final StringBuilder reason = new StringBuilder();
        String full = OpenAiApiClient.streamChat(cfg, ConversationMemory.getHistory("d1", 1), "问题",
                new OpenAiApiClient.StreamCallback() {
                    @Override public void onReasoningDelta(String d) { reason.append(d); }
                    @Override public void onTextDelta(String d) { deltas.add(d); }
                }, null);

        assertEq("/v1/responses", lastRequestPath, "resp path");
        assertEq("Bearer sk-resp-test", lastAuthHeader, "resp auth");
        Map<String, Object> reqMap = JsonMini.parseObject(lastRequestBody);
        if (reqMap.containsKey("temperature")) {
            throw new AssertionError("temperature MUST be omitted on /v1/responses when reasoningEffort is set!");
        }
        Map<String, Object> reasoningObj = JsonMini.getMap(reqMap, "reasoning");
        if (reasoningObj == null || !"medium".equals(reasoningObj.get("effort"))) {
            throw new AssertionError("missing reasoning.effort=medium: " + lastRequestBody);
        }
        assertEq("推理中", reason.toString(), "resp reasoning");
        assertEq(2, deltas.size(), "resp delta count");
        assertEq("Responses OK", full, "resp full");
    }

    private static void testResponsesJson(String baseUrl) throws Exception {
        OpenAiApiClient.EndpointConfig cfg = new OpenAiApiClient.EndpointConfig();
        cfg.protocol = OpenAiApiClient.PROTO_RESPONSES;
        cfg.baseUrl = baseUrl;
        cfg.apiKey = "k";
        cfg.streamEnabled = false;
        final StringBuilder reason = new StringBuilder();
        String full = OpenAiApiClient.streamChat(cfg, null, "hi", new OpenAiApiClient.StreamCallback() {
            @Override public void onReasoningDelta(String d) { reason.append(d); }
            @Override public void onTextDelta(String d) {}
        }, null);
        assertEq("摘要", reason.toString(), "resp json reasoning");
        assertEq("非流式Resp", full, "resp json reply");
    }

    private static void testCallLogLifecycle() {
        CustomAiCallLog.clearAll(null);
        CustomAiConfig cfg = new CustomAiConfig();
        cfg.apiProtocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
        cfg.baseUrl = "https://api.deepseek.com/v1";
        cfg.model = "deepseek-chat";
        cfg.streamEnabled = true;

        long id = CustomAiCallLog.startCall(null, "👓 眼镜语音", cfg, "今天杭州天气", "rayneo_io", true, false);
        assertEq("⚡请求中…", CustomAiCallLog.getLiveBadgeLabel(), "badge running");
        CustomAiCallLog.addEvent(id, "NATIVE_WORKFLOW_STOPPED_CLEANLY");
        CustomAiCallLog.onReasoningDelta(id, "推理1");
        CustomAiCallLog.onTextDelta(id, "你好");
        assertEq("⚡输出中…", CustomAiCallLog.getLiveBadgeLabel(), "badge streaming");
        CustomAiCallLog.onTextDelta(id, "世界");
        CustomAiCallLog.markTtsPlaying(id);
        assertEq("⚡播报中…", CustomAiCallLog.getLiveBadgeLabel(), "badge tts");
        CustomAiCallLog.finishSuccess(null, id, "你好世界！");
        assertEq(null, CustomAiCallLog.getLiveBadgeLabel(), "badge idle after finish");

        List<CustomAiCallLog.Entry> list = CustomAiCallLog.getEntries(null);
        assertEq(1, list.size(), "call log count");
        CustomAiCallLog.Entry e = list.get(0);
        assertEq(CustomAiCallLog.STATUS_SUCCESS, e.status, "entry status");
        assertEq(2, e.chunkCount, "entry chunks");
        assertEq("你好世界！", e.response, "entry response");
        assertEq("推理1", e.reasoning, "entry reasoning");
        assertEq("https://api.deepseek.com/v1/chat/completions", e.endpointUrl, "entry url");

        String json = CustomAiCallLog.serializeEntries(list);
        List<CustomAiCallLog.Entry> parsed = CustomAiCallLog.deserializeEntries(json);
        assertEq(1, parsed.size(), "deserialized count");
        assertEq("你好世界！", parsed.get(0).response, "deserialized response");

        String detail = CustomAiCallLog.formatEntryDetail(parsed.get(0));
        if (!detail.contains("NATIVE_WORKFLOW_STOPPED_CLEANLY") || !detail.contains("你好世界！")) {
            throw new AssertionError("detail missing expected fields: " + detail);
        }
        CustomAiCallLog.clearAll(null);
        assertEq(0, CustomAiCallLog.getCount(null), "cleared count");
    }

    private static void testSkillIntentRouting() {
        // SystemMediaBridge owns music transport & now-playing song queries!
        assertEq(SystemMediaBridge.ACTION_NEXT, SystemMediaBridge.matchCommand("切歌"), "切歌");
        assertEq(SystemMediaBridge.ACTION_NEXT, SystemMediaBridge.matchCommand("切哥。"), "切哥ASR同音");
        assertEq(SystemMediaBridge.ACTION_NEXT, SystemMediaBridge.matchCommand("欢迎下一首歌。"), "欢迎下一首歌ASR同音");
        assertEq(SystemMediaBridge.ACTION_NEXT, SystemMediaBridge.matchCommand("下一首歌"), "下一首");
        assertEq(SystemMediaBridge.ACTION_NEXT, SystemMediaBridge.matchCommand("换一首"), "换一首->下一首");
        assertEq(SystemMediaBridge.ACTION_PREVIOUS, SystemMediaBridge.matchCommand("切回上一首"), "上一首");
        assertEq(SystemMediaBridge.ACTION_PAUSE, SystemMediaBridge.matchCommand("暂停播放"), "暂停播放");
        assertEq(SystemMediaBridge.ACTION_PLAY, SystemMediaBridge.matchCommand("继续播放"), "继续播放");
        assertEq(SystemMediaBridge.ACTION_PLAY, SystemMediaBridge.matchCommand("我想听歌"), "我想听歌->继续播放");
        assertEq(SystemMediaBridge.ACTION_PLAY, SystemMediaBridge.matchCommand("我想听音乐"), "我想听音乐->继续播放");
        assertEq(SystemMediaBridge.ACTION_QUERY_SONG, SystemMediaBridge.matchCommand("现在放的是什么歌？"), "查正在播放的歌");
        assertEq(SystemMediaBridge.ACTION_QUERY_SONG, SystemMediaBridge.matchCommand("这首歌叫什么"), "这首歌叫什么");
        assertEq(SystemMediaBridge.ACTION_FAVORITE, SystemMediaBridge.matchCommand("收藏这首歌"), "收藏歌曲");

        // Voice song-on-demand ("我想听 xxx的xx歌", "我想听 xxx", special modes, explicit player)
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听李荣浩的模特"), "点歌:歌手+歌名");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听李荣浩"), "点歌:仅歌手");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("来点周杰伦的歌"), "点歌:歌手的歌");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听隐形的翅膀"), "点歌:含的字歌名");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听张韶涵的隐形的翅膀"), "点歌:歌手+含的字歌名");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("用QQ音乐放陈奕迅的十年"), "点歌:指定QQ音乐");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("换一首李荣浩的李白"), "换一首指定歌");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听你怎么舍得我难过"), "点歌:含你怎么歌名");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("我想听每日推荐"), "点歌:每日推荐");
        assertEq(SystemMediaBridge.ACTION_PLAY_SEARCH, SystemMediaBridge.matchCommand("打开私人FM"), "点歌:私人FM");

        SystemMediaBridge.PlaySearchRequest r1 = SystemMediaBridge.parsePlaySearchRequest("我想听李荣浩的模特这首歌吧");
        assertEq("李荣浩", r1.possibleArtist, "r1 artist");
        assertEq("模特", r1.possibleSong, "r1 song");
        assertEq(true, r1.hasDeSplit, "r1 hasDeSplit");

        SystemMediaBridge.PlaySearchRequest r2 = SystemMediaBridge.parsePlaySearchRequest("用QQ音乐播放周杰伦的晴天");
        assertEq(SystemMediaBridge.PKG_QQMUSIC, r2.preferredPackage, "r2 qq pkg");
        assertEq("周杰伦 晴天", r2.splitKeyword(), "r2 split keyword");

        SystemMediaBridge.PlaySearchRequest r3 = SystemMediaBridge.parsePlaySearchRequest("来点李荣浩的歌");
        assertEq(true, r3.artistOnly, "r3 artistOnly");
        assertEq("李荣浩", r3.rawTarget, "r3 rawTarget");

        // Negative checks: non-music "我想听..." / "播放..." must NOT trigger ACTION_PLAY_SEARCH!
        assertEq(0, SystemMediaBridge.matchCommand("我想听个笑话"), "笑话不走点歌");
        assertEq(0, SystemMediaBridge.matchCommand("我想听睡前故事"), "故事不走点歌");
        assertEq(0, SystemMediaBridge.matchCommand("我想听听你的建议"), "听建议不走点歌");
        assertEq(0, SystemMediaBridge.matchCommand("我想听今天的新闻"), "听新闻不走点歌");
        assertEq(0, SystemMediaBridge.matchCommand("播放电台"), "播放电台不走点歌");
        assertEq(0, SystemMediaBridge.matchCommand("我刚才放哪了"), "放哪了不走点歌");

        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("切歌"), "切歌不走RayClaw");
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("我想听李荣浩的模特"), "点歌不走RayClaw");
        assertEq("电台/播客", CustomAiHooks.classifySkillOrRealtimeIntent("播放电台"), "电台走官方");
        assertEq("待办/提醒/日程", CustomAiHooks.classifySkillOrRealtimeIntent("提醒我十分钟后喝水"), "提醒喝水");
        assertEq("待办/提醒/日程", CustomAiHooks.classifySkillOrRealtimeIntent("十分钟后叫我开会"), "十分钟后叫我");
        assertEq("待办/提醒/日程", CustomAiHooks.classifySkillOrRealtimeIntent("帮我建个待办明天交报告"), "建待办");
        assertEq("待办/提醒/日程", CustomAiHooks.classifySkillOrRealtimeIntent("定个五分钟的倒计时"), "倒计时");
        assertEq("天气/实时联网", CustomAiHooks.classifySkillOrRealtimeIntent("今天深圳天气怎么样？"), "查天气");
        assertEq("天气/实时联网", CustomAiHooks.classifySkillOrRealtimeIntent("明天会下雨吗"), "下雨");
        assertEq("天气/实时联网", CustomAiHooks.classifySkillOrRealtimeIntent("联网查一下今天金价"), "联网查金价");
        assertEq("系统/硬件控制", CustomAiHooks.classifySkillOrRealtimeIntent("声音大一点"), "调音量");
        assertEq("系统/硬件控制", CustomAiHooks.classifySkillOrRealtimeIntent("帮我拍张照"), "拍照");
        assertEq("电话/通讯", CustomAiHooks.classifySkillOrRealtimeIntent("打电话给张三"), "打电话");
        assertEq("地图/导航", CustomAiHooks.classifySkillOrRealtimeIntent("导航去深圳北站"), "导航");
        assertEq("视觉/翻译/记忆", CustomAiHooks.classifySkillOrRealtimeIntent("帮我看看这是什么"), "视觉识物");

        // General open-domain chat must return null -> routed to Custom LLM!
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("给我讲个冷笑话"), "讲笑话->CustomLLM");
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("用通俗的话解释一下量子纠缠"), "解释概念->CustomLLM");
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("把你好世界翻译成英语"), "普通文本翻译->CustomLLM");
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("鲁迅和周树人是什么关系"), "百科问答->CustomLLM");
    }

    private static void testMusicSearchMockHttp(HttpServer server, String mockHost) {
        SystemMediaBridge.clearRecentPlayedForTest();

        server.createContext("/api/cloudsearch/pc", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String query = ex.getRequestURI().getRawQuery();
                String json;
                if (query != null && query.contains("%E6%A8%A1%E7%89%B9")) {
                    // Contains grayed-out song (-200), Live version, and studio original 27731176
                    json = "{\"result\":{\"songs\":["
                            + "{\"id\":999999,\"name\":\"模特\",\"ar\":[{\"name\":\"李荣浩\"}],\"al\":{\"name\":\"无版权\"},\"privilege\":{\"st\":-200}},"
                            + "{\"id\":31877157,\"name\":\"模特 (Live)\",\"ar\":[{\"name\":\"李荣浩\"}],\"al\":{\"name\":\"演唱会\"},\"privilege\":{\"st\":0}},"
                            + "{\"id\":27731176,\"name\":\"模特\",\"ar\":[{\"name\":\"李荣浩\"}],\"al\":{\"name\":\"模特\"},\"privilege\":{\"st\":0}}"
                            + "]}}";
                } else {
                    // Artist-only "李荣浩"
                    json = "{\"result\":{\"songs\":["
                            + "{\"id\":2600493765,\"name\":\"恋人\",\"ar\":[{\"name\":\"李荣浩\"}],\"al\":{\"name\":\"黑马\"},\"privilege\":{\"st\":0}},"
                            + "{\"id\":31654343,\"name\":\"不将就\",\"ar\":[{\"name\":\"李荣浩\"}],\"al\":{\"name\":\"有理想\"},\"privilege\":{\"st\":0}}"
                            + "]}}";
                }
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.createContext("/splcloud/fcgi-bin/smartbox_new.fcg", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String rawQ = ex.getRequestURI().getRawQuery();
                String json;
                if (rawQ != null && rawQ.contains("%E7%9A%84")) {
                    // Simulate QQ smartbox returning empty when '的' is not split
                    json = "{\"code\":0,\"data\":{\"song\":{\"itemlist\":[]}}}";
                } else {
                    json = "{\"code\":0,\"data\":{\"song\":{\"itemlist\":["
                            + "{\"mid\":\"003qMjZQ0V8Fhf\",\"name\":\"周杰伦《晴天》：故事的小黄花，从出生那年就飘着\",\"singer\":\"音乐帕特\"},"
                            + "{\"mid\":\"0039MnYb0qxYhV\",\"name\":\"晴天\",\"singer\":\"周杰伦\"}"
                            + "]}}}";
                }
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        // 1. NetEase artist + song: skips st=-200 and prefers studio original over Live!
        SystemMediaBridge.PlaySearchRequest neReq =
                SystemMediaBridge.parsePlaySearchRequest("我想听李荣浩的模特");
        SystemMediaBridge.SongCandidate neHit =
                SystemMediaBridge.searchNetEaseSong(neReq, "", mockHost);
        assertEq("27731176", neHit.id, "NetEase studio id");
        assertEq("模特", neHit.title, "NetEase title");
        assertEq("李荣浩", neHit.artist, "NetEase artist");
        String neUri = SystemMediaBridge.buildNetEaseSongPlayUri(neHit.id);
        if (!neUri.startsWith("orpheus://song/27731176?") || !neUri.contains("switch=false")) {
            throw new AssertionError("Unexpected NetEase URI: " + neUri);
        }

        // 2. NetEase artist-only ("我想听李荣浩"): skips currently playing song!
        SystemMediaBridge.PlaySearchRequest neArtistReq =
                SystemMediaBridge.parsePlaySearchRequest("我想听李荣浩");
        SystemMediaBridge.SongCandidate neNextHit =
                SystemMediaBridge.searchNetEaseSong(neArtistReq, "恋人|李荣浩", mockHost);
        assertEq("31654343", neNextHit.id, "NetEase skips currently playing 恋人 -> picks 不将就");
        assertEq("不将就", neNextHit.title, "NetEase next hit title");

        // 3. QQ Music artist + song: auto-splits '的' -> ' ', skips long commentary track, picks 0039MnYb0qxYhV!
        SystemMediaBridge.PlaySearchRequest qqReq =
                SystemMediaBridge.parsePlaySearchRequest("用QQ音乐放周杰伦的晴天");
        SystemMediaBridge.SongCandidate qqHit =
                SystemMediaBridge.searchQqMusicSong(qqReq, "", mockHost);
        assertEq("0039MnYb0qxYhV", qqHit.id, "QQ Music晴天 mid");
        assertEq("晴天", qqHit.title, "QQ Music title");
        assertEq("周杰伦", qqHit.artist, "QQ Music artist");
        String qqUri = SystemMediaBridge.buildQqMusicPlayUri(qqHit.id);
        if (!qqUri.startsWith("qqmusic://qq.com/media/playSonglist?p=")) {
            throw new AssertionError("Unexpected QQ URI: " + qqUri);
        }
    }

    private static void testLyricsTeleprompterUnitAndMockHttp(HttpServer server, String mockHost) {
        // 1. Exact 32-bit FNV-1a checksum test vectors
        assertEq("811c9dc5", LyricsTeleprompterBridge.computeFnv1aHex(new byte[0]), "fnv1a empty");
        assertEq("e40c292c", LyricsTeleprompterBridge.computeFnv1aHex("a".getBytes(StandardCharsets.UTF_8)), "fnv1a 'a'");
        assertEq("bf9cf968", LyricsTeleprompterBridge.computeFnv1aHex("foobar".getBytes(StandardCharsets.UTF_8)), "fnv1a 'foobar'");
        String capturedRayNeoSample =
                "testlineabcdefghijklmnopqrstuvwxyzrayneolyricssynctes"
                + "tlineabcdefghijklmnopqrstuvwxyzrayneolyricssynctestli"
                + "ne3abcdefghijklmnopqrstuvwxyzrayneolyricssynctestlin"
                + "e4abcdefghijklmnopqrstuvwxyzrayneolyricssynctestline"
                + "5abcdefghijklmnopqrstuvwxyzrayneolyricssynctestneab"
                + "cdefghijklmnopqrstuvwxyzrayneolyricssynctestline7abc"
                + "defghijklmnopqrstuvwxyzrayneolyricssynctest";
        assertEq("f4d8664f",
                LyricsTeleprompterBridge.computeFnv1aHex(capturedRayNeoSample.getBytes(StandardCharsets.UTF_8)),
                "fnv1a live RayNeo iO ground-truth match");

        // 2. HTML entity decoding (QQ Music style)
        String htmlLrc = "[00&#58;01&#46;50]词&#58;周杰伦&nbsp;&amp;&nbsp;方文山&#10;[00&#58;05&#46;00]故事的小黄花&#x21;";
        String decoded = LyricsTeleprompterBridge.decodeHtmlEntities(htmlLrc);
        assertEq("[00:01.50]词:周杰伦 & 方文山\n[00:05.00]故事的小黄花!", decoded, "html entity decode");

        // 3. LRC parser + CJK HUD line pre-wrapping + UTF-8 '\n' byte offset alignment
        String sampleLrc = "[ti:模特]\n[ar:李荣浩]\n"
                + "[00:02.00]穿华丽的服装 为原始的渴望而站着\n"
                + "[00:08.50][00:28.00]用完美的表情 为脆弱的城市而撑着\n"
                + "[00:14.00]这是一整行非常非常长超过十七个中文字需要自动切分换行的歌词测试内容\n";
        LyricsTeleprompterBridge.LyricsManuscript ms =
                LyricsTeleprompterBridge.parseLrcAndBuildManuscript("模特", "李荣浩", sampleLrc, "网易云音乐");
        assertEq(true, ms.hasSyncedLyrics, "ms hasSyncedLyrics");
        assertEq(true, LyricsTeleprompterBridge.isLyricsDid(ms.did), "ms did prefix 99");
        assertEq(LyricsTeleprompterBridge.computeFnv1aHex(ms.utf8Bytes), ms.checksum, "ms checksum matches utf8");

        String fullUtf8 = new String(ms.utf8Bytes, StandardCharsets.UTF_8);
        String[] splitCheck = fullUtf8.split("\n", -1);
        assertEq(ms.lines.length, splitCheck.length, "1:1 line count with \\n");
        for (int i = 0; i < ms.lines.length; i++) {
            assertEq(ms.lines[i], splitCheck[i], "line[" + i + "] content");
            if (LyricsTeleprompterBridge.measureHalfWidth(ms.lines[i]) > LyricsTeleprompterBridge.MAX_LINE_HALF_WIDTH) {
                throw new AssertionError("Line exceeded HUD width: " + ms.lines[i]);
            }
            byte[] lineBytes = ms.lines[i].getBytes(StandardCharsets.UTF_8);
            String slice = new String(ms.utf8Bytes, ms.lineByteOffsets[i], lineBytes.length, StandardCharsets.UTF_8);
            assertEq(ms.lines[i], slice, "byte offset alignment at line " + i);
            if (i > 0 && ms.lineTimesMs[i] < ms.lineTimesMs[i - 1]) {
                throw new AssertionError("Timestamps not monotonic at " + i);
            }
        }

        // Binary search check
        int idxAt9s = LyricsTeleprompterBridge.findActiveLineIndex(ms.lineTimesMs, 9000L);
        assertEq("用完美的表情 为脆弱的城市而撑着", ms.lines[idxAt9s], "active line at 9s");
        int idxAt29s = LyricsTeleprompterBridge.findActiveLineIndex(ms.lineTimesMs, 29000L);
        assertEq("用完美的表情 为脆弱的城市而撑着", ms.lines[idxAt29s], "repeated chorus tag at 29s");

        // Fallback manuscript when no timed lyrics exist
        LyricsTeleprompterBridge.LyricsManuscript emptyMs =
                LyricsTeleprompterBridge.parseLrcAndBuildManuscript("纯音乐", "测试艺术家", "", "");
        assertEq(false, emptyMs.hasSyncedLyrics, "fallback hasSyncedLyrics=false");
        assertEq("暂无滚动歌词", emptyMs.lines[emptyMs.lines.length - 1], "fallback text");

        // 4. 6-Field GlassesMsg protobuf encode/decode + mandatory version=1 check
        String startJson = LyricsTeleprompterBridge.buildAppStartSnippetRequestJson(
                ms, ms.lineByteOffsets[0], ms.lineByteOffsets[1]);
        byte[] encoded = LyricsTeleprompterBridge.encodeGlassesMsg(
                LyricsTeleprompterBridge.MSG_START, startJson, ms.utf8Bytes, 42);
        assertEq(0x08, encoded[0] & 0xFF, "protobuf field 1 tag 0x08");
        assertEq(0x01, encoded[1] & 0xFF, "protobuf version == 1");

        LyricsTeleprompterBridge.GlassesMsg decodedMsg = LyricsTeleprompterBridge.decodeGlassesMsg(encoded);
        assertEq(1, decodedMsg.version, "decoded version");
        assertEq(LyricsTeleprompterBridge.MSG_START, decodedMsg.type, "decoded type 0x02");
        assertEq(42, decodedMsg.sequence, "decoded sequence");
        assertEq(startJson, decodedMsg.jsonMessage, "decoded jsonMessage");
        assertEq(ms.utf8Bytes.length, decodedMsg.data.length, "decoded snippet data length");

        Map<String, Object> startObj = JsonMini.parseObject(startJson);
        assertEq(true, startObj.get("snippetMode"), "start snippetMode");
        assertEq(3, JsonMini.getInt(startObj, "scroll", 0), "start manual scroll=3");
        assertEq(420, JsonMini.getInt(startObj, "width", 0), "start HUD width=420");
        assertEq(4, JsonMini.getInt(startObj, "leading", 0), "start HUD leading=4");
        Map<String, Object> sInfo = JsonMini.getMap(startObj, "snippetInfo");
        assertEq(ms.lineByteOffsets[0], JsonMini.getInt(sInfo, "inlineOffset", -1), "start inlineOffset==pageOffset");

        // Verify RayNeo iO ground-truth 0x01(type=3) + 0x02(file-transfer) + 0x03 + 0x08(autoSync=false) builders
        assertEq(LyricsTeleprompterBridge.LYRICS_SLOT_DID, ms.did, "canonical UUIDv7 slot DID");
        assertEq(false, LyricsTeleprompterBridge.isLyricsDid("01a1d990-2df3-7085-ba63-039e3f574cdc"), "user note DID is foreign");

        String listJson = LyricsTeleprompterBridge.buildListUpsertJson(ms, ms.lineByteOffsets[1]);
        Map<String, Object> listObj = JsonMini.parseObject(listJson);
        assertEq(2, JsonMini.getInt(listObj, "action", 0), "0x01 list action=2");
        assertEq(3, JsonMini.getInt(listObj, "type", 0), "0x01 list type=3 upsert");

        String fileStartJson = LyricsTeleprompterBridge.buildAppStartRequestJson(
                ms, ms.lineByteOffsets[1], ms.lineByteOffsets[1]);
        Map<String, Object> fileStartObj = JsonMini.parseObject(fileStartJson);
        assertEq(null, fileStartObj.get("snippetMode"), "RayNeo iO 0x02 omits snippetMode");
        assertEq(ms.checksum, JsonMini.getString(fileStartObj, "checksum"), "RayNeo iO 0x02 checksum");
        assertEq(3, LyricsTeleprompterBridge.HUD_COUNTDOWN, "HUD_COUNTDOWN must be 3");
        assertEq(3, JsonMini.getInt(fileStartObj, "countdown", 0), "0x02 countdown==3 (never 0)");

        String glassesAcceptJson = LyricsTeleprompterBridge.buildGlassesStartAcceptJson(
                ms, 0, 0);
        Map<String, Object> glassesAcceptObj = JsonMini.parseObject(glassesAcceptJson);
        assertEq(2, JsonMini.getInt(glassesAcceptObj, "action", 0), "0x02 accept action=2");
        assertEq(1, JsonMini.getInt(glassesAcceptObj, "code", 0), "0x02 accept code=1");
        assertEq(3, JsonMini.getInt(glassesAcceptObj, "countdown", 0), "0x02 accept countdown==3");

        String cueStartJson = LyricsTeleprompterBridge.buildAppCueingStartRequestJson(
                ms, ms.lineByteOffsets[1], ms.lineByteOffsets[1]);
        Map<String, Object> cueStartObj = JsonMini.parseObject(cueStartJson);
        assertEq(1, JsonMini.getInt(cueStartObj, "code", 0), "0x03 cueing code=1");
        assertEq(3, JsonMini.getInt(cueStartObj, "countdown", 0), "0x03 countdown==3 (never 0)");

        // BMP sanitizer & [歌词] header verification + event interception verification
        assertEq("Hello 世界!", LyricsTeleprompterBridge.sanitizeBmpText("🎵Hello\u3000世界🔥\uFEFF!"), "BMP sanitizer strips 4-byte emoji");
        assertEq(true, ms.lines[0].startsWith("[歌词] "), "manuscript header uses BMP [歌词]");
        assertEq(false, listJson.contains("🎵"), "0x01 list upsert never emits 4-byte emoji");
        assertEq(true, LyricsTeleprompterBridge.shouldInterceptTeleprompterPayload(encoded), "intercepts LYRICS_SLOT_DID");
        byte[] foreignPkt = LyricsTeleprompterBridge.encodeGlassesMsg(
                LyricsTeleprompterBridge.MSG_START,
                "{\"action\":1,\"did\":\"01a1d990-2df3-7085-ba63-039e3f574cdc\"}", null, 1);
        assertEq(false, LyricsTeleprompterBridge.shouldInterceptTeleprompterPayload(foreignPkt), "passes foreign DID");
        byte[] emptyDidStopAck = LyricsTeleprompterBridge.encodeGlassesMsg(
                LyricsTeleprompterBridge.MSG_STOP,
                "{\"action\":2,\"code\":1}", null, 1);
        assertEq(false, LyricsTeleprompterBridge.shouldInterceptTeleprompterPayload(emptyDidStopAck), "passes empty-DID 0x06 ack");

        String ioProgJson = LyricsTeleprompterBridge.buildProgressUpdateJson(
                ms.did, ms.lineByteOffsets[1], ms.lineByteOffsets[1], false);
        Map<String, Object> ioProgObj = JsonMini.parseObject(ioProgJson);
        assertEq(false, ioProgObj.get("autoSync"), "RayNeo iO 0x08 autoSync=false");
        assertEq(null, ioProgObj.get("snippetMode"), "RayNeo iO 0x08 omits snippetMode");

        String progJson = LyricsTeleprompterBridge.buildProgressUpdateJson(
                ms.did, ms.lineByteOffsets[1], ms.lineByteOffsets[2], true);
        Map<String, Object> progObj = JsonMini.parseObject(progJson);
        assertEq(1, JsonMini.getInt(progObj, "action", 0), "prog action=1");
        assertEq(ms.lineByteOffsets[1], JsonMini.getInt(progObj, "pageOffset", -1), "prog pageOffset");
        assertEq(ms.lineByteOffsets[2], JsonMini.getInt(progObj, "highLightOffset", -1), "prog highLightOffset");
        assertEq(false, progObj.get("autoSync"), "prog autoSync=false");
        assertEq(ms.lineByteOffsets[1],
                JsonMini.getInt(JsonMini.getMap(progObj, "snippetInfo"), "inlineOffset", -1),
                "prog snippetInfo.inlineOffset");

        // Conflict code classifier & nested message unwrapping
        assertEq(true, LyricsTeleprompterBridge.isConflictCode(8), "conflict code 8");
        assertEq(true, LyricsTeleprompterBridge.isConflictCode(21), "conflict code 21");
        assertEq(false, LyricsTeleprompterBridge.isConflictCode(23), "code 23 is snippet success, not conflict");

        Map<String, Object> innerMsg = JsonMini.parseObject("{\"businessId\":\"TELEPROMPTER\",\"deviceId\":\"dev_1\"}");
        innerMsg.put("payload", encoded);
        Map<String, Object> outerEvent = new java.util.HashMap<>();
        outerEvent.put("message", innerMsg);
        Map unwrapped = LyricsTeleprompterBridge.unwrapMessageMap(outerEvent);
        assertEq("TELEPROMPTER", unwrapped.get("businessId"), "unwrap nested messageReceived map");

        // 5. Voice commands & CustomAiConfig serialization
        assertEq(LyricsTeleprompterBridge.CMD_ENABLE_SYNC,
                LyricsTeleprompterBridge.matchVoiceCommand("打开歌词"), "voice: 打开歌词");
        assertEq(LyricsTeleprompterBridge.CMD_ENABLE_SYNC,
                LyricsTeleprompterBridge.matchVoiceCommand("小雷小雷，把歌词投到眼镜上"), "voice: 把歌词投到眼镜上");
        assertEq(LyricsTeleprompterBridge.CMD_ENABLE_SYNC,
                LyricsTeleprompterBridge.matchVoiceCommand("开启提词器歌词联动"), "voice: 开启提词器歌词联动");
        assertEq(LyricsTeleprompterBridge.CMD_DISABLE_SYNC,
                LyricsTeleprompterBridge.matchVoiceCommand("关闭歌词"), "voice: 关闭歌词");
        assertEq(LyricsTeleprompterBridge.CMD_DISABLE_SYNC,
                LyricsTeleprompterBridge.matchVoiceCommand("取消歌词联动"), "voice: 取消歌词联动");
        // Negative checks: open-ended LLM questions about lyrics must go to Custom LLM!
        assertEq(LyricsTeleprompterBridge.CMD_NONE,
                LyricsTeleprompterBridge.matchVoiceCommand("帮我写一关于夏天的歌词"), "写歌词->CustomLLM");
        assertEq(LyricsTeleprompterBridge.CMD_NONE,
                LyricsTeleprompterBridge.matchVoiceCommand("这首歌的歌词是什么意思"), "歌词意思->CustomLLM");
        assertEq(null, CustomAiHooks.classifySkillOrRealtimeIntent("打开歌词"), "打开歌词不走RayClaw");

        CustomAiConfig cfg1 = new CustomAiConfig();
        cfg1.syncLyricsToTeleprompter = true;
        CustomAiConfig cfg2 = CustomAiConfig.fromJsonString(cfg1.toJsonString());
        assertEq(true, cfg2.syncLyricsToTeleprompter, "CustomAiConfig syncLyricsToTeleprompter roundtrip");

        // 6. Mock HTTP NetEase & QQ Music LRC fetching + VIP Japanese / Multi-Artist Lyrics Resolver
        server.removeContext("/api/cloudsearch/pc");
        server.createContext("/api/cloudsearch/pc", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String q = java.net.URLDecoder.decode(
                        ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(),
                        "UTF-8");
                String resp;
                if (q.contains("クリスマスソング")) {
                    // #1 is free Eng.ver cover by another singer; #2 is VIP (st=-1) original by back number with tns:["圣诞颂歌"]
                    resp = "{\"result\":{\"songs\":["
                            + "{\"id\":999001,\"name\":\"クリスマスソング (Eng.ver Cover)\",\"ar\":[{\"name\":\"Yuniko_南南\"}],\"al\":{\"name\":\"Cover\"},\"dt\":250000,\"privilege\":{\"st\":0}},"
                            + "{\"id\":36871044,\"name\":\"クリスマスソング\",\"tns\":[\"圣诞颂歌\"],\"ar\":[{\"name\":\"back number\"}],\"al\":{\"name\":\"シャンデリア\"},\"dt\":340000,\"privilege\":{\"st\":-1}}"
                            + "]}}";
                } else if (q.contains("幸せ")) {
                    // #1 is unrelated Chinese song "幸福" by 齐豫; #2 is VIP (st=-1) original "幸せ" by back number
                    resp = "{\"result\":{\"songs\":["
                            + "{\"id\":888002,\"name\":\"幸福\",\"ar\":[{\"name\":\"齐豫\"}],\"al\":{\"name\":\"幸福\"},\"dt\":260000,\"privilege\":{\"st\":0}},"
                            + "{\"id\":699012,\"name\":\"幸せ\",\"tns\":[\"幸福\"],\"ar\":[{\"name\":\"back number\"}],\"al\":{\"name\":\"花束\"},\"dt\":395000,\"privilege\":{\"st\":-1}}"
                            + "]}}";
                } else if (q.contains("素颜")) {
                    // #1 is DJ karaoke cover; #2 is copyright-restricted (st=-100) original duet 许嵩/何曼婷
                    resp = "{\"result\":{\"songs\":["
                            + "{\"id\":777003,\"name\":\"素颜 (DJ慢摇伴奏)\",\"ar\":[{\"name\":\"DJ阿奇\"}],\"al\":{\"name\":\"热歌\"},\"dt\":210000,\"privilege\":{\"st\":0}},"
                            + "{\"id\":167827,\"name\":\"素颜\",\"ar\":[{\"name\":\"许嵩\"},{\"name\":\"何曼婷\"}],\"al\":{\"name\":\"素颜\"},\"dt\":238000,\"privilege\":{\"st\":-100}}"
                            + "]}}";
                } else {
                    resp = "{\"result\":{\"songs\":[]}}";
                }
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.createContext("/api/song/lyric", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String q = ex.getRequestURI().getRawQuery();
                String resp;
                if (q != null && q.contains("id=27731176")) {
                    resp = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]穿华丽的服装\\n[00:05.20]为原始的渴望而站着\\n\"}}";
                } else if (q != null && q.contains("id=36871044")) {
                    resp = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:26.10]どこかで鐘が鳴って\\n[00:31.50]らしくない言葉が浮かんで\\n\"}}";
                } else if (q != null && q.contains("id=699012")) {
                    resp = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:18.00]最初からあなたの幸せしか願っていないから\\n\"}}";
                } else if (q != null && q.contains("id=167827")) {
                    resp = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:21.00]又是一个安静的晚上\\n[00:25.00]一个人窝在摇椅里乘凉\\n\"}}";
                } else {
                    resp = "{\"code\":200,\"nolyric\":true}";
                }
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.createContext("/lyric/fcgi-bin/fcg_query_lyric_new.fcg", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String resp = "MusicJsonCallback({\"retcode\":0,\"lyric\":\"[00&#58;02&#46;00]故事的小黄花&#10;[00&#58;06&#46;50]从出生那年就飘着\"})";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        SystemMediaBridge.recordResolvedSongHint(
                SystemMediaBridge.PKG_NETEASE, "27731176", "", "模特", "李荣浩");
        LyricsTeleprompterBridge.LyricsManuscript neOnline =
                LyricsTeleprompterBridge.fetchLyricsManuscriptOnline(
                        "模特", "李荣浩", SystemMediaBridge.PKG_NETEASE, mockHost, mockHost);
        assertEq(true, neOnline.hasSyncedLyrics, "mock NetEase LRC synced");
        assertEq("网易云音乐", neOnline.sourcePlatform, "mock NetEase sourcePlatform");
        assertEq("为原始的渴望而站着", neOnline.lines[neOnline.lines.length - 1], "mock NetEase last line");

        SystemMediaBridge.recordResolvedSongHint(
                SystemMediaBridge.PKG_QQMUSIC, "", "0039MnYb0qxYhV", "晴天", "周杰伦");
        LyricsTeleprompterBridge.LyricsManuscript qqOnline =
                LyricsTeleprompterBridge.fetchLyricsManuscriptOnline(
                        "晴天", "周杰伦", SystemMediaBridge.PKG_QQMUSIC, mockHost, mockHost);
        assertEq(true, qqOnline.hasSyncedLyrics, "mock QQ LRC synced");
        assertEq("QQ音乐", qqOnline.sourcePlatform, "mock QQ sourcePlatform");
        assertEq("从出生那年就飘着", qqOnline.lines[qqOnline.lines.length - 1], "mock QQ last line");

        // Verify VIP Japanese + translated suffix + multi-artist lyric resolution without hints!
        assertEq("36871044",
                LyricsTeleprompterBridge.resolveNetEaseSongIdForLyrics(
                        "クリスマスソング (圣诞颂歌)", "back number", 340000L, mockHost),
                "VIP Japanese クリスマスソング resolves to original back number (not Eng.ver cover)");
        LyricsTeleprompterBridge.LyricsManuscript xmasMs =
                LyricsTeleprompterBridge.fetchLyricsManuscriptOnline(
                        "クリスマスソング (圣诞颂歌)", "back number", 340000L,
                        SystemMediaBridge.PKG_NETEASE, mockHost, mockHost);
        assertEq(true, xmasMs.hasSyncedLyrics, "クリスマスソング synced");
        assertEq("らしくない言葉が浮かんで", xmasMs.lines[xmasMs.lines.length - 1], "クリスマスソング Japanese lyric");

        assertEq("699012",
                LyricsTeleprompterBridge.resolveNetEaseSongIdForLyrics(
                        "幸せ (幸福)", "back number", 395000L, mockHost),
                "VIP Japanese 幸せ resolves to back number (not 齐豫 幸福)");

        assertEq("167827",
                LyricsTeleprompterBridge.resolveNetEaseSongIdForLyrics(
                        "素颜", "许嵩/何曼婷", 238000L, mockHost),
                "Grayed-out duet 素颜 resolves to 许嵩/何曼婷 (not DJ karaoke)");
    }

    // ===== Mock R8-obfuscated CoreConnect shapes (v1.0.25 shifted names: i4, v, u0, f0, lc_h, mc_q) =====
    public enum MockBizEnum { VOICE_ASSISTANT, TELEPROMPTER }
    public enum MockPrioEnum { HIGH, LOW, NORMAL }
    public enum MockCoreEnum { SmallCore, LargeCore }

    public static final class MockI4 {
        public final String a;
        public final String b;
        public final boolean c;
        public MockI4(String a, String b, boolean c) { this.a = a; this.b = b; this.c = c; }
    }

    public static final class MockF0 {
        public final String a;
        public final boolean connected;
        public MockF0(String id, boolean connected) { this.a = id; this.connected = connected; }
        public String a() { return "RayNeo iO"; }
        public boolean b() { return connected; }
    }

    public static final class MockU0 {
        public final byte[] payload;
        public final String msgId;
        public final String devId;
        public final MockBizEnum biz;
        public final MockPrioEnum prio;
        public final boolean needAck;
        public MockU0(byte[] b, String id, String dev, MockBizEnum biz, Object r0,
                      MockPrioEnum prio, boolean ack, boolean lowPwr, Object p,
                      kotlin.jvm.functions.Function2 cb) {
            this.payload = b;
            this.msgId = id;
            this.devId = dev;
            this.biz = biz;
            this.prio = prio;
            this.needAck = ack;
        }
    }

    public static final class MockCoreConnectV {
        public static volatile MockCoreConnectV h;
        public static Map<String, Object> mockDevices = new java.util.LinkedHashMap<>();
        public MockU0 lastSentMsg;
        public String lastSharedDev;
        public MockCoreEnum lastSharedCore;

        public static Map<String, Object> j() { return mockDevices; }

        public void u(MockU0 msg, kotlin.jvm.functions.Function2 cb) {
            this.lastSentMsg = msg;
            if (cb != null) cb.invoke(msg, null);
        }

        public String v(java.io.File file, String devId, MockCoreEnum core, String extra) {
            this.lastSharedDev = devId;
            this.lastSharedCore = core;
            return "task_1025";
        }
    }

    public static final class MockBridgeP4 {
        public static volatile MockBridgeP4 Q;
        public MockI4 z;
        public MockCoreConnectV mgr;
        public MockCoreConnectV q() { return mgr; }
    }

    private static void testR8DynamicCoreConnectReflection() throws Exception {
        MockCoreConnectV mgr = new MockCoreConnectV();
        MockCoreConnectV.h = mgr;
        MockCoreConnectV.mockDevices.clear();
        MockCoreConnectV.mockDevices.put("AA:BB:CC:11:22:33", new MockF0("AA:BB:CC:11:22:33", true));

        MockBridgeP4 bridge = new MockBridgeP4();
        bridge.mgr = mgr;
        bridge.z = new MockI4("AA:BB:CC:11:22:33", "RayNeo iO", true);
        MockBridgeP4.Q = bridge;

        LyricsTeleprompterBridge.CoreConnectHandles handles =
                LyricsTeleprompterBridge.resolveCoreConnectHandlesForClass(MockBridgeP4.class);
        if (handles == null) {
            throw new AssertionError("Dynamic CoreConnectHandles resolution returned null!");
        }
        assertEq(mgr, handles.resolveMgrInstance(null), "resolveMgrInstance via singleton Q.q()");
        assertEq(MockCoreEnum.SmallCore, handles.smallCoreConst, "resolved SmallCore enum");
        assertEq(MockBizEnum.TELEPROMPTER, handles.teleprompterBizConst, "resolved TELEPROMPTER enum");
        assertEq(MockPrioEnum.NORMAL, handles.normalPriorityConst, "resolved NORMAL priority enum");

        String[] idOut = new String[1];
        boolean[] connOut = new boolean[1];
        Object devEntry = handles.getDevicesMap().values().iterator().next();
        assertEq(true, handles.inspectDeviceEntry(devEntry, idOut, connOut), "inspectDeviceEntry ok");
        assertEq("AA:BB:CC:11:22:33", idOut[0], "device id field a");
        assertEq(true, connOut[0], "device connected method b()");

        java.io.File tmp = java.io.File.createTempFile("lyr_test", ".bin");
        tmp.deleteOnExit();
        String taskId = handles.invokeFileShare(mgr, tmp, "AA:BB:CC:11:22:33");
        assertEq("task_1025", taskId, "invokeFileShare taskId");
        assertEq(MockCoreEnum.SmallCore, mgr.lastSharedCore, "fileShare passed SmallCore");

        final boolean[] cbFired = new boolean[1];
        handles.invokeSendBle(mgr, new byte[]{1, 2, 3}, "lyr_1", "AA:BB:CC:11:22:33", true,
                new kotlin.jvm.functions.Function2() {
                    @Override public Object invoke(Object a, Object b) {
                        cbFired[0] = (b == null);
                        return null;
                    }
                });
        assertEq(true, cbFired[0], "invokeSendBle callback fired");
        assertEq("lyr_1", mgr.lastSentMsg.msgId, "invokeSendBle msgId");
        assertEq(MockBizEnum.TELEPROMPTER, mgr.lastSentMsg.biz, "invokeSendBle biz==TELEPROMPTER");
        assertEq(true, mgr.lastSentMsg.needAck, "invokeSendBle needAck==true");
    }

    private static void testBilingualAndRomajiLyrics(HttpServer server, String mockHost) {
        // 1. Offline Hepburn Kana -> Romaji transliterator
        assertEq("kurisumasusongu",
                LyricsTeleprompterBridge.transliterateJapaneseKanaToRomaji("クリスマスソング"),
                "katakana long vowel + digraphs");
        assertEq("chottomatte",
                LyricsTeleprompterBridge.transliterateJapaneseKanaToRomaji("ちょっとまって"),
                "hiragana sokuon gemination + cho");
        assertEq("arigatou",
                LyricsTeleprompterBridge.transliterateJapaneseKanaToRomaji("ありがとう"),
                "hiragana arigatou");

        // 2. Bilingual / Romaji / Trilingual / Original-only voice commands & fast-lane check
        assertEq(LyricsTeleprompterBridge.CMD_MODE_BILINGUAL,
                LyricsTeleprompterBridge.matchVoiceCommand("小雷小雷，打开双语歌词"),
                "voice cmd bilingual");
        assertEq(LyricsTeleprompterBridge.CMD_MODE_BILINGUAL,
                LyricsTeleprompterBridge.matchVoiceCommand("歌词显示中文翻译"),
                "voice cmd show translation");
        assertEq(LyricsTeleprompterBridge.CMD_MODE_ROMAJI,
                LyricsTeleprompterBridge.matchVoiceCommand("打开罗马音"),
                "voice cmd romaji");
        assertEq(LyricsTeleprompterBridge.CMD_MODE_TRILINGUAL,
                LyricsTeleprompterBridge.matchVoiceCommand("切换到三语歌词"),
                "voice cmd trilingual");
        assertEq(LyricsTeleprompterBridge.CMD_MODE_ORIGINAL,
                LyricsTeleprompterBridge.matchVoiceCommand("仅显示原词"),
                "voice cmd original only");
        assertEq(LyricsTeleprompterBridge.CMD_MODE_ORIGINAL,
                LyricsTeleprompterBridge.matchVoiceCommand("关闭罗马音"),
                "voice cmd disable romaji -> original");
        assertEq(null,
                CustomAiHooks.classifySkillOrRealtimeIntent("帮我把演讲提纲投到眼镜提词器上"),
                "teleprompter skill fast-lane bypasses RayClaw");
        assertEq(null,
                CustomAiHooks.classifySkillOrRealtimeIntent("看看我剪贴板里是什么"),
                "clipboard skill fast-lane bypasses RayClaw");

        // 3. LrcBundle alignment + companion line wrapping + findCueLineIndex anchor snapping
        String origLrc = "[00:05.00]どこかで鐘が鳴って\n[00:12.00]ありがとう\n";
        String transLrc = "[00:00.00]by: 翻译贡献者\n[00:05.05]某处钟声响起\n[00:12.10]谢谢你\n";
        String romaLrc = "[00:05.00]dokoka de kane ga natte\n"; // 00:12.00 intentionally omitted -> offline Kana fallback!
        LyricsTeleprompterBridge.LrcBundle bundle =
                new LyricsTeleprompterBridge.LrcBundle(origLrc, transLrc, romaLrc);

        LyricsTeleprompterBridge.LyricsManuscript triMs =
                LyricsTeleprompterBridge.parseLrcBundleAndBuildManuscript(
                        "クリスマスソング", "back number", bundle,
                        CustomAiConfig.LYRICS_MODE_TRILINGUAL, "网易云音乐");
        assertEq(true, triMs.hasSyncedLyrics, "triMs synced");
        assertEq(CustomAiConfig.LYRICS_MODE_TRILINGUAL, triMs.displayMode, "triMs mode");

        boolean sawRoma1 = false;
        boolean sawOfflineRoma2 = false;
        boolean sawTrans1 = false;
        for (int i = 0; i < triMs.lines.length; i++) {
            String line = triMs.lines[i];
            if (LyricsTeleprompterBridge.measureHalfWidth(line) > LyricsTeleprompterBridge.MAX_LINE_HALF_WIDTH) {
                throw new AssertionError("Trilingual line exceeded 34 half-width units: " + line);
            }
            if (line.equals("~ dokoka de kane ga natte")) sawRoma1 = true;
            if (line.equals("~ arigatou")) sawOfflineRoma2 = true;
            if (line.equals("· 某处钟声响起")) sawTrans1 = true;
            if (line.contains("翻译贡献")) {
                throw new AssertionError("Boilerplate translator credit should have been filtered: " + line);
            }
        }
        assertEq(true, sawRoma1, "saw online romalrc line");
        assertEq(true, sawOfflineRoma2, "saw offline Kana->Romaji fallback line");
        assertEq(true, sawTrans1, "saw aligned Chinese translation line");

        // CRITICAL: At t=6000ms, raw binary search lands on the last companion line sharing t=5000ms,
        // whereas findCueLineIndex(6000L) MUST snap back to the Japanese original line "どこかで鐘が鳴って"!
        int cueIdx6s = triMs.findCueLineIndex(6000L);
        assertEq("どこかで鐘が鳴って", triMs.lines[cueIdx6s], "findCueLineIndex snaps to primary original line at 6s");
        assertEq(false, triMs.companionFlags[cueIdx6s], "anchor line has companionFlags==false");

        int cueIdx13s = triMs.findCueLineIndex(13000L);
        assertEq("ありがとう", triMs.lines[cueIdx13s], "findCueLineIndex snaps to 2nd primary original line at 13s");

        // 4. Custom Teleprompter Note Manuscript builder (used by push_to_teleprompter Skill & Clipboard push)
        LyricsTeleprompterBridge.LyricsManuscript noteMs =
                LyricsTeleprompterBridge.buildCustomNoteManuscript(
                        "三分钟演讲提纲",
                        "第一部分：开场问候与核心痛点介绍，控制在三十秒以内完成。\n第二部分：演示雷鸟眼镜双语歌词与 MCP 技能联动。");
        assertEq(true, noteMs.isCustomNote, "noteMs isCustomNote");
        assertEq(true, noteMs.lines[0].startsWith("[提词] "), "noteMs header prefix");
        for (String nl : noteMs.lines) {
            if (LyricsTeleprompterBridge.measureHalfWidth(nl) > LyricsTeleprompterBridge.MAX_LINE_HALF_WIDTH) {
                throw new AssertionError("Custom note line exceeded HUD width: " + nl);
            }
        }

        // 5. Mock Online QQ Music playing Japanese song in Trilingual mode -> QQ trans + NetEase romalrc cross-enrichment!
        server.removeContext("/api/song/lyric");
        server.createContext("/api/song/lyric", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String q = ex.getRequestURI().getRawQuery();
                if (q == null || !q.contains("tv=-1") || !q.contains("rv=-1")) {
                    throw new AssertionError("NetEase lyric request missing tv=-1&rv=-1: " + q);
                }
                String resp = "{\"code\":200,"
                        + "\"lrc\":{\"lyric\":\"[00:26.10]どこかで鐘が鳴って\\n\"},"
                        + "\"tlyric\":{\"lyric\":\"[00:26.10]某处钟声响起\\n\"},"
                        + "\"romalrc\":{\"lyric\":\"[00:26.10]dokoka de kane ga natte\\n\"}}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.removeContext("/lyric/fcgi-bin/fcg_query_lyric_new.fcg");
        server.createContext("/lyric/fcgi-bin/fcg_query_lyric_new.fcg", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String resp = "MusicJsonCallback({\"retcode\":0,"
                        + "\"lyric\":\"[00&#58;26&#46;10]どこかで鐘が鳴って&#10;\","
                        + "\"trans\":\"[00&#58;26&#46;10]QQ翻译：某处钟声敲响&#10;\"})";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        SystemMediaBridge.recordResolvedSongHint(
                SystemMediaBridge.PKG_QQMUSIC, "36871044", "001XmasMid", "クリスマスソング", "back number");
        LyricsTeleprompterBridge.LyricsManuscript qqEnriched =
                LyricsTeleprompterBridge.fetchLyricsManuscriptOnline(
                        "クリスマスソング", "back number", 340000L,
                        SystemMediaBridge.PKG_QQMUSIC,
                        CustomAiConfig.LYRICS_MODE_TRILINGUAL,
                        mockHost, mockHost);
        String joined = String.join("\n", qqEnriched.lines);
        if (!joined.contains("~ dokoka de kane ga natte") || !joined.contains("· QQ翻译：某处钟声敲响")) {
            throw new AssertionError("Expected QQ trans + NetEase romalrc enrichment, got:\n" + joined);
        }
    }

    private static void testMcpAndSkillsEngine(HttpServer server, final String mockHost) throws Exception {
        McpSkillManager.clearMcpCache();

        // 1. Config & parser round-trip (both array format and Claude/Cursor {"mcpServers":{...}} format)
        CustomAiConfig c1 = new CustomAiConfig();
        c1.lyricsDisplayMode = CustomAiConfig.LYRICS_MODE_TRILINGUAL;
        c1.enableMcpAndSkills = true;
        c1.enableBuiltinSkills = false;
        c1.mcpServersJson = "{\"mcpServers\":{\"lab\":{\"url\":\"" + mockHost + "/mcp\",\"token\":\"tok_123\"}}}";
        c1.customSkillsJson = "[{\"name\":\"save_memo\",\"description\":\"保存闪念笔记\",\"webhookUrl\":\""
                + mockHost + "/hook/memo\",\"method\":\"POST\"}]";
        CustomAiConfig c2 = CustomAiConfig.fromJsonString(c1.toJsonString());
        assertEq(CustomAiConfig.LYRICS_MODE_TRILINGUAL, c2.lyricsDisplayMode, "cfg lyricsDisplayMode roundtrip");
        assertEq(false, c2.enableBuiltinSkills, "cfg enableBuiltinSkills roundtrip");

        List<McpSkillManager.McpServerConfig> parsedMcp = McpSkillManager.parseMcpServers(c2.mcpServersJson);
        assertEq(1, parsedMcp.size(), "parsed Claude/Cursor mcpServers object");
        assertEq("lab", parsedMcp.get(0).name, "mcp server name");
        assertEq("Bearer tok_123", parsedMcp.get(0).headers.get("Authorization"), "mcp bearer token header");

        // 2. Mock Streamable HTTP MCP Server (/mcp) + Webhook Skill (/hook/memo)
        final List<String> mcpMethodsSeen = new ArrayList<>();
        final String[] lastWebhookPayload = new String[]{""};

        server.createContext("/mcp", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (!"Bearer tok_123".equals(auth)) {
                    ex.sendResponseHeaders(401, -1);
                    return;
                }
                String body = readAll(ex.getRequestBody());
                Map<String, Object> rpc = JsonMini.parseObject(body);
                String method = JsonMini.getString(rpc, "method");
                Object id = rpc.get("id");
                mcpMethodsSeen.add(method);

                if ("initialize".equals(method)) {
                    ex.getResponseHeaders().set("Mcp-Session-Id", "sess_rayneo_01");
                    String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + JsonMini.stringify(id)
                            + ",\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},"
                            + "\"serverInfo\":{\"name\":\"MockLabMcp\",\"version\":\"1.0\"}}}";
                    byte[] b = resp.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                } else if ("notifications/initialized".equals(method)) {
                    ex.sendResponseHeaders(202, -1);
                } else if ("tools/list".equals(method)) {
                    if (!"sess_rayneo_01".equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                        throw new AssertionError("Missing Mcp-Session-Id on tools/list");
                    }
                    String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + JsonMini.stringify(id)
                            + ",\"result\":{\"tools\":[{\"name\":\"get_room_temp\","
                            + "\"description\":\"查询房间温度\","
                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"room\":{\"type\":\"string\"}}}}]}}";
                    byte[] b = resp.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                } else if ("tools/call".equals(method)) {
                    if (!"sess_rayneo_01".equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                        throw new AssertionError("Missing Mcp-Session-Id on tools/call");
                    }
                    Map<String, Object> params = JsonMini.getMap(rpc, "params");
                    Map<String, Object> args = JsonMini.getMap(params, "arguments");
                    String room = JsonMini.getString(args, "room");
                    // Test SSE-formatted Streamable HTTP MCP response!
                    String sseResp = "event: message\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"id\":" + JsonMini.stringify(id)
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + room + "当前温度 24.5°C，湿度 48%\"}]}}\n\n";
                    byte[] b = sseResp.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            }
        });

        server.createContext("/hook/memo", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                lastWebhookPayload[0] = readAll(ex.getRequestBody());
                byte[] b = "{\"saved\":true,\"id\":\"memo_88\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
        });

        // Probe verification
        String probeReport = McpSkillManager.probeAllMcpServers(c2.mcpServersJson);
        if (!probeReport.contains("✅ lab (1 个工具): get_room_temp")) {
            throw new AssertionError("Unexpected MCP probe report: " + probeReport);
        }

        // 3. End-to-end /v1/chat/completions SSE streaming tool-call loop + Prompt Skill injection
        final int[] chatRound = new int[]{0};
        final String[] round1SystemPrompt = new String[]{""};
        server.createContext("/tool_test/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                int r = ++chatRound[0];
                String body = readAll(ex.getRequestBody());
                Map<String, Object> req = JsonMini.parseObject(body);
                List<Object> msgs = JsonMini.getList(req, "messages");

                if (r == 1) {
                    Map<String, Object> sysMsg = JsonMini.asMap(msgs.get(0));
                    round1SystemPrompt[0] = JsonMini.getString(sysMsg, "content");
                    List<Object> tools = JsonMini.getList(req, "tools");
                    if (tools == null || tools.isEmpty()) {
                        throw new AssertionError("Expected tools array in round 1 chat request!");
                    }
                    // Stream chunked tool_calls delta across 2 SSE frames
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_mcp_1\",\"type\":\"function\",\"function\":{\"name\":\"mcp__lab__get_room_temp\",\"arguments\":\"{\\\"room\\\":\"}}]}}]}\n\n");
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"书房\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n");
                        writeSse(os, "data: [DONE]\n\n");
                    }
                } else {
                    // Verify tool result message is present in round 2
                    Map<String, Object> lastMsg = JsonMini.asMap(msgs.get(msgs.size() - 1));
                    assertEq("tool", JsonMini.getString(lastMsg, "role"), "round 2 tool message role");
                    assertEq("call_mcp_1", JsonMini.getString(lastMsg, "tool_call_id"), "round 2 tool_call_id");
                    String toolOut = JsonMini.getString(lastMsg, "content");
                    if (!toolOut.contains("书房当前温度 24.5°C")) {
                        throw new AssertionError("Tool output missing MCP result: " + toolOut);
                    }
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "data: {\"choices\":[{\"delta\":{\"content\":\"书房现在 24.5°C，体感舒适。\"}}]}\n\n");
                        writeSse(os, "data: [DONE]\n\n");
                    }
                }
            }
        });

        CustomAiConfig toolCfg = new CustomAiConfig();
        toolCfg.enableMcpAndSkills = true;
        toolCfg.enableBuiltinSkills = false;
        toolCfg.mcpServersJson = c2.mcpServersJson;
        toolCfg.customSkillsJson = "[{\"name\":\"concise_sop\",\"instructions\":\"务必带温度单位°C\",\"enabled\":true},"
                + "{\"name\":\"save_memo\",\"description\":\"保存闪念笔记\",\"webhookUrl\":\"" + mockHost + "/hook/memo\",\"method\":\"POST\"}]";

        OpenAiApiClient.EndpointConfig epChat = new OpenAiApiClient.EndpointConfig();
        epChat.protocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
        epChat.baseUrl = mockHost + "/tool_test/v1";
        epChat.apiKey = "sk-tool";
        epChat.model = "gpt-5-mini";
        epChat.systemPrompt = "你是眼镜助手";
        epChat.streamEnabled = true;
        epChat.toolExecutor = McpSkillManager.createExecutor(null, toolCfg, 0L);

        final List<String> statusNotes = new ArrayList<>();
        String chatToolAnswer = OpenAiApiClient.streamChat(epChat, null, "查一下书房温度",
                new OpenAiApiClient.StreamCallback() {
                    @Override public void onReasoningDelta(String d) { statusNotes.add(d); }
                    @Override public void onTextDelta(String d) {}
                }, null);
        assertEq("书房现在 24.5°C，体感舒适。", chatToolAnswer, "chat SSE MCP tool loop final answer");
        assertEq(2, chatRound[0], "chat completed in 2 rounds");
        if (!round1SystemPrompt[0].contains("务必带温度单位°C")) {
            throw new AssertionError("Prompt skill instructions were not injected into system prompt: " + round1SystemPrompt[0]);
        }

        // 4. End-to-end /v1/responses SSE streaming function_call -> Webhook Skill -> function_call_output loop
        final int[] respRound = new int[]{0};
        server.createContext("/tool_test/v1/responses", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                int r = ++respRound[0];
                String body = readAll(ex.getRequestBody());
                Map<String, Object> req = JsonMini.parseObject(body);
                if (r == 1) {
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "event: response.output_item.done\n"
                                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\","
                                + "\"call_id\":\"fc_99\",\"name\":\"save_memo\",\"arguments\":\"{\\\"text\\\":\\\"买牛奶\\\"}\"}}\n\n");
                        writeSse(os, "event: response.completed\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n");
                    }
                } else {
                    List<Object> input = JsonMini.getList(req, "input");
                    Map<String, Object> lastItem = JsonMini.asMap(input.get(input.size() - 1));
                    assertEq("function_call_output", JsonMini.getString(lastItem, "type"), "responses function_call_output");
                    assertEq("fc_99", JsonMini.getString(lastItem, "call_id"), "responses call_id");
                    if (!JsonMini.getString(lastItem, "output").contains("memo_88")) {
                        throw new AssertionError("Expected webhook response in function_call_output: " + lastItem);
                    }
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        writeSse(os, "event: response.output_text.delta\n"
                                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"已帮你记下买牛奶！\"}\n\n");
                        writeSse(os, "event: response.completed\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n");
                    }
                }
            }
        });

        OpenAiApiClient.EndpointConfig epResp = new OpenAiApiClient.EndpointConfig();
        epResp.protocol = OpenAiApiClient.PROTO_RESPONSES;
        epResp.baseUrl = mockHost + "/tool_test/v1";
        epResp.apiKey = "sk-tool";
        epResp.model = "gpt-5-mini";
        epResp.streamEnabled = true;
        epResp.toolExecutor = McpSkillManager.createExecutor(null, toolCfg, 0L);

        String respToolAnswer = OpenAiApiClient.streamChat(epResp, null, "记个备忘买牛奶", null, null);
        assertEq("已帮你记下买牛奶！", respToolAnswer, "responses SSE webhook skill final answer");
        if (!lastWebhookPayload[0].contains("买牛奶")) {
            throw new AssertionError("Webhook did not receive expected JSON args: " + lastWebhookPayload[0]);
        }

        // 5. Automatic HTTP 400 fallback when an upstream model/proxy does not support `tools`
        final int[] fallbackCalls = new int[]{0};
        server.createContext("/no_tools_model/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                fallbackCalls[0]++;
                String body = readAll(ex.getRequestBody());
                Map<String, Object> req = JsonMini.parseObject(body);
                if (req.containsKey("tools")) {
                    byte[] err = "{\"error\":{\"message\":\"tools is not supported by this model\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(400, err.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(err); }
                } else {
                    byte[] ok = "{\"choices\":[{\"message\":{\"content\":\"降级纯文本成功\"}}]}"
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, ok.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(ok); }
                }
            }
        });

        OpenAiApiClient.EndpointConfig epFallback = new OpenAiApiClient.EndpointConfig();
        epFallback.protocol = OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
        epFallback.baseUrl = mockHost + "/no_tools_model/v1";
        epFallback.apiKey = "sk-fallback";
        epFallback.model = "deepseek-reasoner-lite";
        epFallback.streamEnabled = false;
        epFallback.toolExecutor = McpSkillManager.createExecutor(null, toolCfg, 0L);

        String fallbackAns = OpenAiApiClient.streamChat(epFallback, null, "你好", null, null);
        assertEq("降级纯文本成功", fallbackAns, "auto-retry without tools on HTTP 400");
        assertEq(2, fallbackCalls[0], "fallback retried once without tools");
    }

    private static void writeSse(OutputStream os, String chunk) throws IOException {
        os.write(chunk.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String readAll(InputStream is) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static void assertEq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}
