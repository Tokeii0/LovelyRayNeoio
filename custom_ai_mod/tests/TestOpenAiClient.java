import com.rayneo.customai.ConversationMemory;
import com.rayneo.customai.CustomAiCallLog;
import com.rayneo.customai.CustomAiConfig;
import com.rayneo.customai.CustomAiHooks;
import com.rayneo.customai.JsonMini;
import com.rayneo.customai.LyricsTeleprompterBridge;
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
