import com.rayneo.customai.ConversationMemory;
import com.rayneo.customai.CustomAiCallLog;
import com.rayneo.customai.CustomAiConfig;
import com.rayneo.customai.CustomAiHooks;
import com.rayneo.customai.JsonMini;
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
