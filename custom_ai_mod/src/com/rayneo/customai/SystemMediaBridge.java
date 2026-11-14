package com.rayneo.customai;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct Android system media controller, active track inspector, and voice song-on-demand engine.
 * Uses RayNeo's existing NotificationListenerService permission with MediaSessionManager
 * (plus AudioManager media key fallback) to control third-party music apps (网易云音乐, QQ音乐,
 * 汽水音乐, Apple Music, Spotify, Bilibili, etc.), read real-time song title/artist/album,
 * and search + deep-link play requested songs/artists ("我想听李荣浩的模特", "我想听李荣浩").
 */
public final class SystemMediaBridge {

    private static final String TAG = "RayNeoCustomAI";
    private static final String KNOWN_NLS_CLASS =
            "im.zoe.labs.flutter_notification_listener.NotificationsHandlerService";

    public static final String PKG_NETEASE = "com.netease.cloudmusic";
    public static final String PKG_QQMUSIC = "com.tencent.qqmusic";

    private static final String PREFS_NAME = "rayneo_custom_ai_prefs";
    private static final String PREF_KEY_MUSIC_PKG = "preferred_music_pkg";
    private static final int SEARCH_HTTP_TIMEOUT_MS = 4200;

    public static final int ACTION_NEXT = 1;
    public static final int ACTION_PREVIOUS = 2;
    public static final int ACTION_PAUSE = 3;
    public static final int ACTION_PLAY = 4;
    public static final int ACTION_QUERY_SONG = 5;
    public static final int ACTION_FAVORITE = 6;
    public static final int ACTION_PLAY_SEARCH = 7;

    private static final Pattern PAT_NEXT = Pattern.compile(
            "(切[歌割哥]|切[个一首]歌|下一[首歌曲]|下首歌|下一个歌|换[一]?首歌|换[一个]?歌"
                    + "|换一首($|[歌吧呀啊呗]|别的)|换首别的|跳过这首歌"
                    + "|next\\s+(song|track)|skip\\s+(song|track))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PREV = Pattern.compile(
            "(上一[首歌曲]|上首歌|上一个歌|切回上一首|放上一首|回到上一首"
                    + "|previous\\s+(song|track)|prev\\s+(song|track))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PAUSE = Pattern.compile(
            "(暂停播放|停止播放|暂停音乐|关闭音乐|关掉音乐|把歌停了|把音乐停了|别放歌了|音乐暂停|停止放歌"
                    + "|pause\\s+music|stop\\s+music)"
                    + "|^(暂停|停一下)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PLAY = Pattern.compile(
            "^(继续播放|恢复播放|继续放歌|接着放|开始播放|播放音乐|打开音乐|放首歌|放一首歌|放歌|听歌|听音乐|来首歌"
                    + "|我想听歌|我想听音乐|我要听歌|我要听音乐|帮我放歌|给我放歌|帮我放首歌|给我放首歌"
                    + "|放点音乐|放点歌|来点音乐|来点歌"
                    + "|播放|继续播|继续放|play\\s+music|resume\\s+music)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_QUERY_SONG = Pattern.compile(
            "(什么歌|哪首歌|这首歌叫什么|这首歌是什么|这首歌是谁唱|谁唱的歌|谁唱的|正在播放.{0,6}(什么|哪)|正在放.{0,6}(什么|哪)"
                    + "|现在放的.{0,6}(什么|哪)|现在播的.{0,6}(什么|哪)|当前播放|当前歌曲|放的是什么歌|听的是什么歌"
                    + "|歌名叫什么|歌手是谁|查一下这首歌|what\\s+song\\s+is\\s+this|what'?s\\s+playing|now\\s+playing)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_FAVORITE = Pattern.compile(
            "(收藏这首歌|收藏当前歌曲|喜欢这首歌|把这首歌加到喜欢|红心这首歌)",
            Pattern.CASE_INSENSITIVE);

    // ===== Voice Song-On-Demand ("我想听 xxx的xx歌" / "我想听 xxx" / "每日推荐" / "私人FM") =====
    private static final Pattern PAT_WAKE_PREFIX = Pattern.compile(
            "^(?:小雷小雷|小雷|嘿小雷|你好小雷)[，,、\\s]*");

    private static final Pattern PAT_LEADING_PLAYER = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我|请你|能不能|可以|可不可以)?\\s*"
                    + "(?:(?:用|打开|在|通过|使用|拿)\\s*)?(网易云音乐|网易云|云音乐|qq音乐|QQ音乐)\\s*"
                    + "(?:帮我|给我|替我|请)?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_TRAILING_PLAYER = Pattern.compile(
            "^(.+?)\\s*(?:用|在|通过)(网易云音乐|网易云|云音乐|qq音乐|QQ音乐)(?:播放|放|听|播)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_SPECIAL_MODE_CMD = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我)?\\s*"
                    + "(?:我?想听|我?要听|打开|开启|进入|播放|放点|来点|听点|放|播|听)?\\s*"
                    + "(?:网易云(?:音乐)?的?)?(每日推荐歌曲|每日推荐|日推|私人fm|私人电台|私人漫游)"
                    + "(?:吧|呀|啊|呗|好吗|好不好)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PLAY_SEARCH_VERB = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我|请你|能不能|可以|可不可以)?\\s*"
                    + "(?:我?想听一下|我?想听听|我?想听|我?要听|想放|要放|点播|搜歌播放|搜索播放"
                    + "|点一首|来一首|放一首|听一首|播一首|换一首|切一首|整一首"
                    + "|点首|来首|放首|听首|播首|整首"
                    + "|来点|放点|播点|听点"
                    + "|放一下|播一下|听一下"
                    + "|播放歌曲|播放音乐|播放)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PREFIXED_SHORT_PLAY = Pattern.compile(
            "^(?:帮我|给我|替我|麻烦你?)\\s*(?:播放|放歌|放|播)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PLAYER_SHORT_PLAY = Pattern.compile(
            "^(?:播放|放歌|放|播|听)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_TRAILING_PARTICLES = Pattern.compile(
            "(?:吧|呀|啊|呢|哦|噢|呗|啦|嘛|好吗|好不好|可以吗|行吗|行不行|谢谢|谢啦)+$");

    private static final Pattern PAT_TRAILING_THIS_SONG = Pattern.compile(
            "(?:这首歌|那首歌|这首曲子|这首音乐|首歌)$");

    private static final Pattern PAT_ARTIST_GENERIC_SONGS = Pattern.compile(
            "^(.{1,18}?)的(?:歌|歌曲|音乐|曲子|歌儿|热门歌曲|热歌|代表作|成名曲)$");

    private static final Pattern PAT_GENERIC_MUSIC_ONLY = Pattern.compile(
            "^(?:歌|歌曲|音乐|曲子|首歌|一首歌|点歌|点音乐|歌儿|音乐吧|歌吧)$");

    private static final Pattern PAT_NON_MUSIC_TARGET = Pattern.compile(
            "^(?:个|一个|一段|篇|点|些)?(?:笑话|冷笑话|段子|故事|睡前故事|鬼故事|童话.*|神话.*|寓言.*"
                    + "|新闻|.*新闻|早报|晚报|日报|天下事|热搜|广播|电台|.*电台|播客|.*播客"
                    + "|相声|小品|评书|戏曲|有声书|小说|课文|英语听力"
                    + "|录音|.*录音|视频|.*视频|通知|.*通知|消息|.*消息|微信.*|短信.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_CONVERSATIONAL_TARGET = Pattern.compile(
            "^(?:你|您)(?:的|说|讲|来|觉得|认为|怎么看|怎么想|分析|解释|介绍).*"
                    + "|.*(?:怎么回事|什么意思|的看法|的建议|的意见|的分析|的总结|的解释|的评价|的原因)$");

    private static final Pattern PAT_VERSION_SUFFIX = Pattern.compile(
            "\\s*[（(\\[【].*?[）)\\]】]\\s*$");

    private static final LinkedList<String> RECENT_SEARCH_PLAYED_KEYS = new LinkedList<>();
    private static final int MAX_RECENT_PLAYED = 8;

    private static final Map<String, String> KNOWN_APP_LABELS = new HashMap<>();
    static {
        KNOWN_APP_LABELS.put(PKG_NETEASE, "网易云音乐");
        KNOWN_APP_LABELS.put(PKG_QQMUSIC, "QQ音乐");
        KNOWN_APP_LABELS.put("com.luna.music", "汽水音乐");
        KNOWN_APP_LABELS.put("com.kugou.android", "酷狗音乐");
        KNOWN_APP_LABELS.put("cn.kuwo.player", "酷我音乐");
        KNOWN_APP_LABELS.put("cmccwm.mobilemusic", "咪咕音乐");
        KNOWN_APP_LABELS.put("com.apple.android.music", "Apple Music");
        KNOWN_APP_LABELS.put("com.spotify.music", "Spotify");
        KNOWN_APP_LABELS.put("com.miui.player", "小米音乐");
        KNOWN_APP_LABELS.put("tv.danmaku.bili", "哔哩哔哩");
        KNOWN_APP_LABELS.put("com.喜马拉雅.ting.android", "喜马拉雅");
        KNOWN_APP_LABELS.put("com.ximalaya.ting.android", "喜马拉雅");
        KNOWN_APP_LABELS.put("fm.qingting.qtradio", "蜻蜓FM");
        KNOWN_APP_LABELS.put("io.github.echomusic", "Echo");
    }

    public static final class PlaySearchRequest {
        public static final int MODE_SONG_SEARCH = 0;
        public static final int MODE_DAILY_RECOMMEND = 1;
        public static final int MODE_PERSONAL_FM = 2;

        public final int mode;
        public final String preferredPackage;
        public final String rawTarget;
        public final String possibleArtist;
        public final String possibleSong;
        public final boolean artistOnly;
        public final boolean hasDeSplit;

        public PlaySearchRequest(
                int mode,
                String preferredPackage,
                String rawTarget,
                String possibleArtist,
                String possibleSong,
                boolean artistOnly,
                boolean hasDeSplit
        ) {
            this.mode = mode;
            this.preferredPackage = safe(preferredPackage);
            this.rawTarget = safe(rawTarget);
            this.possibleArtist = safe(possibleArtist);
            this.possibleSong = safe(possibleSong);
            this.artistOnly = artistOnly;
            this.hasDeSplit = hasDeSplit;
        }

        public String splitKeyword() {
            if (hasDeSplit && !possibleArtist.isEmpty() && !possibleSong.isEmpty()) {
                return possibleArtist + " " + possibleSong;
            }
            return rawTarget;
        }
    }

    public static final class SongCandidate {
        public final String id;
        public final String title;
        public final String artist;
        public final String album;
        public int score;

        public SongCandidate(String id, String title, String artist, String album) {
            this.id = safe(id);
            this.title = safe(title);
            this.artist = safe(artist);
            this.album = safe(album);
        }

        public String key() {
            return title + "|" + artist;
        }

        public TrackInfo toTrackInfo(String pkg, String appLabel) {
            TrackInfo ti = new TrackInfo();
            ti.packageName = safe(pkg);
            ti.appName = safe(appLabel);
            ti.title = title;
            ti.artist = artist;
            ti.album = album;
            ti.playbackState = PlaybackState.STATE_PLAYING;
            return ti;
        }
    }

    public static final class TrackInfo {
        public String packageName = "";
        public String appName = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public int playbackState = PlaybackState.STATE_NONE;
        public long positionMs = 0L;
        public long durationMs = 0L;

        public boolean hasSong() {
            return title != null && !title.trim().isEmpty();
        }

        public boolean isPlaying() {
            return playbackState == PlaybackState.STATE_PLAYING
                    || playbackState == PlaybackState.STATE_BUFFERING;
        }

        public String key() {
            return safe(title) + "|" + safe(artist);
        }

        public String formatSongDisplay() {
            String t = safe(title);
            String a = safe(artist);
            if (t.isEmpty()) return "";
            if (!a.isEmpty() && !t.contains(a)) {
                return a + "《" + t + "》";
            }
            return "《" + t + "》";
        }
    }

    public static final class ActionResult {
        public final int action;
        public final boolean dispatchedViaController;
        public final String targetPackage;
        public final TrackInfo beforeTrack;
        public final TrackInfo afterTrack;
        public final String spokenReply;
        public final String debugTrace;
        public final String deferredLaunchUri;
        public final PlaySearchRequest searchRequest;

        public ActionResult(
                int action,
                boolean dispatchedViaController,
                String targetPackage,
                TrackInfo beforeTrack,
                TrackInfo afterTrack,
                String spokenReply,
                String debugTrace
        ) {
            this(action, dispatchedViaController, targetPackage, beforeTrack, afterTrack,
                    spokenReply, debugTrace, "", null);
        }

        public ActionResult(
                int action,
                boolean dispatchedViaController,
                String targetPackage,
                TrackInfo beforeTrack,
                TrackInfo afterTrack,
                String spokenReply,
                String debugTrace,
                String deferredLaunchUri,
                PlaySearchRequest searchRequest
        ) {
            this.action = action;
            this.dispatchedViaController = dispatchedViaController;
            this.targetPackage = targetPackage == null ? "" : targetPackage;
            this.beforeTrack = beforeTrack;
            this.afterTrack = afterTrack;
            this.spokenReply = spokenReply == null ? "" : spokenReply;
            this.debugTrace = debugTrace == null ? "" : debugTrace;
            this.deferredLaunchUri = deferredLaunchUri == null ? "" : deferredLaunchUri;
            this.searchRequest = searchRequest;
        }
    }

    private SystemMediaBridge() {}

    /**
     * Matches local media control, now-playing queries, and voice song-on-demand ("我想听...").
     * Returns 0 if not a media command.
     */
    public static int matchCommand(String rawQuery) {
        if (rawQuery == null) return 0;
        String q = rawQuery.replaceAll("[\\s，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]]+", "").trim();
        if (q.isEmpty()) return 0;
        String trimmed = rawQuery.trim();

        if (PAT_NEXT.matcher(q).find() || PAT_NEXT.matcher(trimmed).find()) {
            return ACTION_NEXT;
        }
        if (PAT_PREV.matcher(q).find() || PAT_PREV.matcher(trimmed).find()) {
            return ACTION_PREVIOUS;
        }
        if (PAT_QUERY_SONG.matcher(q).find() || PAT_QUERY_SONG.matcher(trimmed).find()) {
            return ACTION_QUERY_SONG;
        }
        if (PAT_FAVORITE.matcher(q).find()) {
            return ACTION_FAVORITE;
        }
        if (PAT_PAUSE.matcher(q).find() || PAT_PAUSE.matcher(trimmed).find()) {
            return ACTION_PAUSE;
        }
        if (PAT_PLAY.matcher(q).find() || PAT_PLAY.matcher(trimmed).find()) {
            return ACTION_PLAY;
        }
        if (parsePlaySearchRequest(rawQuery) != null) {
            return ACTION_PLAY_SEARCH;
        }
        return 0;
    }

    public static String actionLabel(int action) {
        switch (action) {
            case ACTION_NEXT: return "切歌/下一首";
            case ACTION_PREVIOUS: return "上一首";
            case ACTION_PAUSE: return "暂停播放";
            case ACTION_PLAY: return "继续播放";
            case ACTION_QUERY_SONG: return "查询当前歌曲";
            case ACTION_FAVORITE: return "收藏当前歌曲";
            case ACTION_PLAY_SEARCH: return "语音点歌播放";
            default: return "媒体控制";
        }
    }

    /**
     * Parses natural Chinese voice song-on-demand utterances such as:
     * - "我想听李荣浩的模特" / "播放周杰伦的晴天" / "用QQ音乐放陈奕迅的十年"
     * - "我想听李荣浩" / "来点周杰伦的歌" / "我想听隐形的翅膀"
     * - "我想听每日推荐" / "打开私人FM"
     * Returns null if not a music search/play request (e.g. "我想听个笑话", "播放电台", "我想听新闻").
     */
    public static PlaySearchRequest parsePlaySearchRequest(String rawQuery) {
        if (rawQuery == null) return null;
        String s = rawQuery.trim().replaceAll("\\s+", " ");
        s = s.replaceAll("^[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+", "")
                .replaceAll("[，。！？、,.!?~～“”\"'‘’（）()【】\\[\\]\\s]+$", "");
        if (s.isEmpty()) return null;

        s = PAT_WAKE_PREFIX.matcher(s).replaceFirst("").trim();
        if (s.isEmpty()) return null;

        String preferredPkg = "";
        Matcher mLeadPlayer = PAT_LEADING_PLAYER.matcher(s);
        if (mLeadPlayer.matches()) {
            preferredPkg = mapPlayerAliasToPkg(mLeadPlayer.group(1));
            s = safe(mLeadPlayer.group(2));
        } else {
            Matcher mTrailPlayer = PAT_TRAILING_PLAYER.matcher(s);
            if (mTrailPlayer.matches()) {
                preferredPkg = mapPlayerAliasToPkg(mTrailPlayer.group(2));
                s = safe(mTrailPlayer.group(1));
            }
        }

        Matcher mSpecialCmd = PAT_SPECIAL_MODE_CMD.matcher(s);
        if (mSpecialCmd.matches()) {
            String modeWord = safe(mSpecialCmd.group(1)).toLowerCase(Locale.ROOT);
            int mode = (modeWord.contains("每日推荐") || modeWord.contains("日推"))
                    ? PlaySearchRequest.MODE_DAILY_RECOMMEND
                    : PlaySearchRequest.MODE_PERSONAL_FM;
            return new PlaySearchRequest(mode, preferredPkg, mSpecialCmd.group(1), "", "", false, false);
        }

        String target = null;
        Matcher mVerb = PAT_PLAY_SEARCH_VERB.matcher(s);
        if (mVerb.matches()) {
            target = safe(mVerb.group(1));
        } else {
            Matcher mShort = PAT_PREFIXED_SHORT_PLAY.matcher(s);
            if (mShort.matches()) {
                target = safe(mShort.group(1));
            } else if (!preferredPkg.isEmpty()) {
                Matcher mPlayerShort = PAT_PLAYER_SHORT_PLAY.matcher(s);
                if (mPlayerShort.matches()) {
                    target = safe(mPlayerShort.group(1));
                }
            }
        }
        if (target == null || target.isEmpty()) return null;

        // Normalize book-title marks: "李荣浩《模特》" -> "李荣浩的模特", "《晴天》" -> "晴天"
        target = target.replaceAll("([\\u4e00-\\u9fa5A-Za-z0-9])\\s*《", "$1的")
                .replace("《", "")
                .replace("》", "")
                .trim();

        // Strip leading "一首" when used after "我想听..." (while preserving "一首简单的歌")
        if (target.startsWith("一首") && target.length() > 2 && !target.startsWith("一首简单")) {
            target = target.substring(2).trim();
        } else if (target.startsWith("首") && target.length() > 1) {
            target = target.substring(1).trim();
        }

        target = PAT_TRAILING_PARTICLES.matcher(target).replaceFirst("").trim();
        target = PAT_TRAILING_THIS_SONG.matcher(target).replaceFirst("").trim();
        target = PAT_TRAILING_PARTICLES.matcher(target).replaceFirst("").trim();

        if (target.isEmpty() || target.length() > 28
                || "看".equals(target) || "一下".equals(target) || "听".equals(target)) {
            return null;
        }

        // Re-check special modes after stripping particles
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        if ("每日推荐".equals(lowerTarget) || "日推".equals(lowerTarget) || "每日推荐歌曲".equals(lowerTarget)
                || "网易云每日推荐".equals(lowerTarget) || "网易云日推".equals(lowerTarget)) {
            return new PlaySearchRequest(
                    PlaySearchRequest.MODE_DAILY_RECOMMEND, preferredPkg, "每日推荐", "", "", false, false);
        }
        if ("私人fm".equals(lowerTarget) || "私人电台".equals(lowerTarget) || "私人漫游".equals(lowerTarget)
                || "网易云私人fm".equals(lowerTarget)) {
            return new PlaySearchRequest(
                    PlaySearchRequest.MODE_PERSONAL_FM, preferredPkg, "私人FM", "", "", false, false);
        }

        String compactTarget = target.replaceAll("\\s+", "");
        if (PAT_GENERIC_MUSIC_ONLY.matcher(compactTarget).matches()) {
            return null;
        }
        if (PAT_NON_MUSIC_TARGET.matcher(compactTarget).matches()
                || PAT_CONVERSATIONAL_TARGET.matcher(compactTarget).matches()) {
            return null;
        }

        // Check "<歌手>的歌 / 的音乐 / 的代表作" -> artist-only request
        Matcher mArtistSongs = PAT_ARTIST_GENERIC_SONGS.matcher(target);
        if (mArtistSongs.matches()) {
            String prefix = safe(mArtistSongs.group(1));
            if (!"我们".equals(prefix) && !"咱们".equals(prefix)
                    && !"我".equals(prefix) && !"你".equals(prefix) && !"他".equals(prefix) && !"她".equals(prefix)) {
                return new PlaySearchRequest(
                        PlaySearchRequest.MODE_SONG_SEARCH, preferredPkg, prefix, prefix, "", true, false);
            }
        }

        // Split "<歌手>的<歌名>" on the first '的' (handling both "李荣浩的模特" and "张韶涵的隐形的翅膀")
        int firstDe = target.indexOf('的');
        if (firstDe > 0 && firstDe < target.length() - 1) {
            String pArtist = target.substring(0, firstDe).trim();
            String pSong = target.substring(firstDe + 1).trim();
            if (!pArtist.isEmpty() && !pSong.isEmpty()) {
                return new PlaySearchRequest(
                        PlaySearchRequest.MODE_SONG_SEARCH, preferredPkg, target, pArtist, pSong, false, true);
            }
        }

        return new PlaySearchRequest(
                PlaySearchRequest.MODE_SONG_SEARCH, preferredPkg, target, target, target, false, false);
    }

    private static String mapPlayerAliasToPkg(String alias) {
        if (alias == null) return "";
        String low = alias.trim().toLowerCase(Locale.ROOT);
        if (low.contains("qq")) return PKG_QQMUSIC;
        if (low.contains("网易") || low.contains("云音乐")) return PKG_NETEASE;
        return "";
    }

    /**
     * Reads current system media session info to enrich Custom LLM context on open-ended questions
     * (e.g. "介绍一下现在放的这首歌", "推荐几首和现在这首风格像的歌").
     */
    public static String buildPromptContextLine(Context ctx) {
        try {
            if (ctx == null) return "";
            TrackInfo info = getCurrentTrack(ctx);
            if (info == null || !info.hasSong()) return "";
            String stateStr = info.isPlaying() ? "正在播放" : "当前停留/已暂停在";
            StringBuilder sb = new StringBuilder();
            sb.append("[当前手机系统音乐播放器状态：");
            if (!info.appName.isEmpty()) {
                sb.append(info.appName).append(' ');
            }
            sb.append(stateStr).append(' ').append(info.formatSongDisplay());
            if (!info.album.isEmpty()) {
                sb.append("（专辑：").append(info.album).append("）");
            }
            sb.append(']');
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static TrackInfo getCurrentTrack(Context ctx) {
        MediaController ctrl = findBestController(ctx);
        if (ctrl == null) return null;
        return extractTrackInfo(ctx, ctrl);
    }

    public static ActionResult execute(Context ctx, int action, long logId) {
        return execute(ctx, action, "", logId);
    }

    /**
     * Executes a media command against Android's active MediaSession (or searches & prepares
     * a deep-link URI for ACTION_PLAY_SEARCH so Glasses TTS can announce before audio switches).
     */
    public static ActionResult execute(Context ctx, int action, String rawQuery, long logId) {
        boolean nlsEnabled = isNotificationListenerEnabled(ctx);
        MediaController ctrl = findBestController(ctx);
        TrackInfo before = ctrl != null ? extractTrackInfo(ctx, ctrl) : null;
        String beforeKey = before != null ? before.key() : "";
        String targetPkg = ctrl != null ? safe(ctrl.getPackageName()) : "";
        boolean usedController = false;

        if (PKG_NETEASE.equals(targetPkg) || PKG_QQMUSIC.equals(targetPkg)) {
            savePreferredMusicPkg(ctx, targetPkg);
        }

        if (logId > 0L) {
            CustomAiCallLog.addEvent(logId, "MEDIA_BEFORE(nls=" + nlsEnabled
                    + ", pkg=" + targetPkg
                    + ", song=" + (before != null ? before.formatSongDisplay() : "none")
                    + ", state=" + (before != null ? before.playbackState : -1) + ")");
        }

        if (action == ACTION_PLAY_SEARCH) {
            return executePlaySearch(ctx, rawQuery, before, beforeKey, logId);
        }

        if (action == ACTION_QUERY_SONG) {
            String reply;
            if (before != null && before.hasSong()) {
                String appPrefix = before.appName.isEmpty() ? "" : ("通过" + before.appName);
                if (before.isPlaying()) {
                    reply = "当前正在" + appPrefix + "播放：" + before.formatSongDisplay();
                } else {
                    reply = "当前" + (before.appName.isEmpty() ? "音乐播放器" : before.appName)
                            + "停留在：" + before.formatSongDisplay();
                }
                if (!before.album.isEmpty() && !before.album.equals(before.title)) {
                    reply += "，专辑《" + before.album + "》";
                }
            } else if (!nlsEnabled) {
                reply = "暂未读取到播放信息。请在手机系统设置「通知使用权」中确认雷鸟 App 已开启权限。";
            } else {
                reply = "当前系统没有正在播放或挂起的音乐。";
            }
            return new ActionResult(action, ctrl != null, targetPkg, before, before, reply, "QUERY_ONLY");
        }

        if (action == ACTION_FAVORITE) {
            boolean favSent = false;
            if (ctrl != null && ctrl.getPlaybackState() != null) {
                List<PlaybackState.CustomAction> customs = ctrl.getPlaybackState().getCustomActions();
                if (customs != null) {
                    for (PlaybackState.CustomAction ca : customs) {
                        if (ca == null) continue;
                        String actId = safe(ca.getAction()).toLowerCase(Locale.ROOT);
                        String actName = ca.getName() != null ? ca.getName().toString().toLowerCase(Locale.ROOT) : "";
                        if (actId.contains("like") || actId.contains("fav") || actId.contains("heart")
                                || actName.contains("like") || actName.contains("收藏") || actName.contains("喜欢")) {
                            ctrl.getTransportControls().sendCustomAction(ca, ca.getExtras());
                            favSent = true;
                            usedController = true;
                            break;
                        }
                    }
                }
            }
            String songStr = (before != null && before.hasSong()) ? before.formatSongDisplay() : "当前歌曲";
            String reply = favSent
                    ? ("已为您在" + (before != null && !before.appName.isEmpty() ? before.appName : "播放器") + "切换收藏状态：" + songStr)
                    : ("当前播放器未开放系统级收藏快捷按钮（歌曲：" + songStr + "）");
            return new ActionResult(action, usedController, targetPkg, before, before, reply, "FAV=" + favSent);
        }

        // Transport controls: NEXT / PREVIOUS / PAUSE / PLAY
        try {
            if (ctrl != null) {
                MediaController.TransportControls tc = ctrl.getTransportControls();
                switch (action) {
                    case ACTION_NEXT:
                        tc.skipToNext();
                        usedController = true;
                        break;
                    case ACTION_PREVIOUS:
                        tc.skipToPrevious();
                        usedController = true;
                        break;
                    case ACTION_PAUSE:
                        tc.pause();
                        usedController = true;
                        break;
                    case ACTION_PLAY:
                        tc.play();
                        usedController = true;
                        break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TransportControls failed, falling back to AudioManager key event", t);
            usedController = false;
        }

        if (!usedController) {
            int keyCode;
            switch (action) {
                case ACTION_NEXT: keyCode = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                case ACTION_PREVIOUS: keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                case ACTION_PAUSE: keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
                case ACTION_PLAY: default: keyCode = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            }
            dispatchMediaKey(ctx, keyCode);
        }

        // Wait & poll MediaSession metadata update for NEXT / PREVIOUS / PLAY
        TrackInfo after = null;
        if (action == ACTION_NEXT || action == ACTION_PREVIOUS) {
            SystemClock.sleep(320L);
            for (int i = 0; i < 10; i++) {
                MediaController latestCtrl = findControllerForPkgOrBest(ctx, targetPkg);
                after = latestCtrl != null ? extractTrackInfo(ctx, latestCtrl) : null;
                if (after != null && after.hasSong() && !after.key().equals(beforeKey)) {
                    break;
                }
                SystemClock.sleep(130L);
            }
        } else {
            SystemClock.sleep(220L);
            MediaController latestCtrl = findControllerForPkgOrBest(ctx, targetPkg);
            after = latestCtrl != null ? extractTrackInfo(ctx, latestCtrl) : before;
        }

        if (after == null) {
            after = before;
        }

        if (logId > 0L) {
            CustomAiCallLog.addEvent(logId, "MEDIA_AFTER(viaCtrl=" + usedController
                    + ", song=" + (after != null ? after.formatSongDisplay() : "none")
                    + ", state=" + (after != null ? after.playbackState : -1) + ")");
        }

        String appSuffix = (after != null && !after.appName.isEmpty()) ? ("（" + after.appName + "）") : "";
        String reply;
        switch (action) {
            case ACTION_NEXT:
                if (after != null && after.hasSong()) {
                    reply = "已切到下一首：" + after.formatSongDisplay() + appSuffix;
                } else {
                    reply = "已切到下一首";
                }
                break;
            case ACTION_PREVIOUS:
                if (after != null && after.hasSong()) {
                    reply = "已切回上一首：" + after.formatSongDisplay() + appSuffix;
                } else {
                    reply = "已切回上一首";
                }
                break;
            case ACTION_PAUSE:
                if (after != null && after.hasSong()) {
                    reply = "已暂停播放：" + after.formatSongDisplay();
                } else {
                    reply = "已暂停音乐播放";
                }
                break;
            case ACTION_PLAY:
            default:
                if (after != null && after.hasSong()) {
                    reply = "正在播放：" + after.formatSongDisplay() + appSuffix;
                } else {
                    reply = "已发送继续播放指令";
                }
                break;
        }

        String trace = "via=" + (usedController ? "MediaController" : "AudioManagerKey")
                + ", pkg=" + targetPkg
                + ", before=" + (before != null ? before.formatSongDisplay() : "-")
                + ", after=" + (after != null ? after.formatSongDisplay() : "-");
        return new ActionResult(action, usedController, targetPkg, before, after, reply, trace);
    }

    private static ActionResult executePlaySearch(
            Context ctx, String rawQuery, TrackInfo before, String beforeKey, long logId) {
        PlaySearchRequest req = parsePlaySearchRequest(rawQuery);
        if (req == null) {
            return new ActionResult(ACTION_PLAY_SEARCH, false, "", before, before,
                    "未识别到要播放的歌曲名称", "PARSE_NULL");
        }

        String primaryPkg = resolvePrimaryMusicPackage(ctx, req.preferredPackage, before);

        if (req.mode == PlaySearchRequest.MODE_DAILY_RECOMMEND) {
            String uri = "orpheus://songrcmd?autoplay=true&forceopenplayer=false&skipLoading=true";
            String reply = "正在为您播放网易云音乐每日推荐";
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "MEDIA_SEARCH_MODE(DAILY_RECOMMEND -> " + uri + ")");
            }
            return new ActionResult(ACTION_PLAY_SEARCH, true, PKG_NETEASE, before, null,
                    reply, "MODE=DAILY_RECOMMEND", uri, req);
        }

        if (req.mode == PlaySearchRequest.MODE_PERSONAL_FM) {
            String uri = "orpheus://radio?autoplay=true&skipLoading=true";
            String reply = "正在为您开启网易云音乐私人FM";
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "MEDIA_SEARCH_MODE(PERSONAL_FM -> " + uri + ")");
            }
            return new ActionResult(ACTION_PLAY_SEARCH, true, PKG_NETEASE, before, null,
                    reply, "MODE=PERSONAL_FM", uri, req);
        }

        if (PKG_QQMUSIC.equals(primaryPkg)) {
            SongCandidate qqHit = searchQqMusicSong(req, beforeKey, null);
            if (qqHit != null) {
                rememberPlayedCandidate(qqHit);
                String uri = buildQqMusicPlayUri(qqHit.id);
                TrackInfo after = qqHit.toTrackInfo(PKG_QQMUSIC, "QQ音乐");
                String reply = "正在通过QQ音乐播放：" + after.formatSongDisplay();
                String trace = "via=QQMusicDeepLink, mid=" + qqHit.id + ", score=" + qqHit.score;
                if (logId > 0L) {
                    CustomAiCallLog.addEvent(logId,
                            "MEDIA_SEARCH_HIT(QQ音乐 mid=" + qqHit.id + " " + after.formatSongDisplay() + " score=" + qqHit.score + ")");
                }
                return new ActionResult(ACTION_PLAY_SEARCH, true, PKG_QQMUSIC, before, after,
                        reply, trace, uri, req);
            }
        }

        // Default & primary path: NetEase Cloud Music
        SongCandidate neHit = searchNetEaseSong(req, beforeKey, null);
        if (neHit != null) {
            rememberPlayedCandidate(neHit);
            String uri = buildNetEaseSongPlayUri(neHit.id);
            TrackInfo after = neHit.toTrackInfo(PKG_NETEASE, "网易云音乐");
            String reply = "正在通过网易云音乐播放：" + after.formatSongDisplay();
            String trace = "via=NetEaseOrpheusSong, id=" + neHit.id + ", score=" + neHit.score;
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId,
                        "MEDIA_SEARCH_HIT(网易云音乐 id=" + neHit.id + " " + after.formatSongDisplay() + " score=" + neHit.score + ")");
            }
            return new ActionResult(ACTION_PLAY_SEARCH, true, PKG_NETEASE, before, after,
                    reply, trace, uri, req);
        }

        // Fallback if offline / HTTP timeout: NetEase native Cortana voice search deep link
        String fallbackUri = buildNetEaseVoiceFallbackUri(req.splitKeyword());
        String reply = "正在为您搜索播放：" + req.rawTarget;
        if (logId > 0L) {
            CustomAiCallLog.addEvent(logId, "MEDIA_SEARCH_FALLBACK(" + fallbackUri + ")");
        }
        return new ActionResult(ACTION_PLAY_SEARCH, true, PKG_NETEASE, before, null,
                reply, "via=NetEaseVoiceFallback", fallbackUri, req);
    }

    /**
     * Launches the resolved deep-link URI to start playback in NetEase Cloud Music / QQ Music.
     * Automatically falls back across players (and Android's standard MEDIA_PLAY_FROM_SEARCH)
     * if the primary app is not installed on the user's phone.
     */
    public static boolean launchDeferredUri(Context ctx, ActionResult res, long logId) {
        if (ctx == null || res == null || safe(res.deferredLaunchUri).isEmpty()) {
            return false;
        }
        String uri = res.deferredLaunchUri;
        String pkg = res.targetPackage;

        if (tryStartViewUri(ctx, uri, pkg)) {
            savePreferredMusicPkg(ctx, pkg);
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_URI(ok=true, pkg=" + pkg + ", uri=" + uri + ")");
            }
            return true;
        }

        // Retry without explicit package name (handles dual-app / beta package variants)
        if (!pkg.isEmpty() && tryStartViewUri(ctx, uri, "")) {
            if (logId > 0L) {
                CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_URI(ok=true, pkg=implicit, uri=" + uri + ")");
            }
            return true;
        }

        // Cross-player fallback if primary music app is not installed on this device
        PlaySearchRequest req = res.searchRequest;
        if (req != null && req.mode == PlaySearchRequest.MODE_SONG_SEARCH) {
            if (PKG_NETEASE.equals(pkg)) {
                SongCandidate qqHit = searchQqMusicSong(req, "", null);
                if (qqHit != null) {
                    String qqUri = buildQqMusicPlayUri(qqHit.id);
                    if (tryStartViewUri(ctx, qqUri, PKG_QQMUSIC) || tryStartViewUri(ctx, qqUri, "")) {
                        savePreferredMusicPkg(ctx, PKG_QQMUSIC);
                        if (logId > 0L) {
                            CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_FALLBACK(QQ音乐 mid=" + qqHit.id + ")");
                        }
                        return true;
                    }
                }
            } else if (PKG_QQMUSIC.equals(pkg)) {
                SongCandidate neHit = searchNetEaseSong(req, "", null);
                if (neHit != null) {
                    String neUri = buildNetEaseSongPlayUri(neHit.id);
                    if (tryStartViewUri(ctx, neUri, PKG_NETEASE) || tryStartViewUri(ctx, neUri, "")) {
                        savePreferredMusicPkg(ctx, PKG_NETEASE);
                        if (logId > 0L) {
                            CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_FALLBACK(网易云音乐 id=" + neHit.id + ")");
                        }
                        return true;
                    }
                }
            }

            // Universal Android MediaStore voice search intent fallback (Spotify, Apple Music, 汽水音乐, etc.)
            try {
                Intent searchPlay = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH");
                searchPlay.putExtra("query", req.splitKeyword());
                searchPlay.putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*");
                searchPlay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                ctx.startActivity(searchPlay);
                if (logId > 0L) {
                    CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_FALLBACK(MEDIA_PLAY_FROM_SEARCH)");
                }
                return true;
            } catch (Throwable ignored) {}
        }

        if (logId > 0L) {
            CustomAiCallLog.addEvent(logId, "MEDIA_LAUNCH_URI(ok=false, pkg=" + pkg + ")");
        }
        return false;
    }

    private static boolean tryStartViewUri(Context ctx, String uriStr, String pkg) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
            if (pkg != null && !pkg.isEmpty()) {
                intent.setPackage(pkg);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException notFound) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "tryStartViewUri failed (" + pkg + "): " + uriStr, t);
            return false;
        }
    }

    public static String buildNetEaseSongPlayUri(String songId) {
        return "orpheus://song/" + safe(songId)
                + "?switch=false&replacePlayList=false&waitColdStartPlayConnected=true"
                + "&skipLoading=true&noNeedLoading=true&redirectFromExternal=true";
    }

    public static String buildNetEaseVoiceFallbackUri(String keyword) {
        String json = "{\"q\":\"\",\"entities\":[{\"type\":\"keyword\",\"value\":\""
                + escapeJsonString(keyword) + "\"}]}";
        return "orpheus-cortana://voice?action=play&type=general&params=" + urlEncode(json);
    }

    public static String buildQqMusicPlayUri(String songMid) {
        String payload = "{\"action\":\"play\",\"song\":[{\"type\":\"0\",\"songmid\":\""
                + escapeJsonString(songMid) + "\"}]}";
        return "qqmusic://qq.com/media/playSonglist?p=" + urlEncode(payload);
    }

    /**
     * Searches NetEase Cloud Music for the best playable studio track matching {@code req}.
     * {@code baseUrlOverride} may be null (uses https://music.163.com) or a mock URL in unit tests.
     */
    public static SongCandidate searchNetEaseSong(
            PlaySearchRequest req, String beforeKey, String baseUrlOverride) {
        if (req == null) return null;
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://music.163.com";

        int deCount = countChar(req.rawTarget, '的');
        String primaryKeyword = (deCount >= 2 && req.hasDeSplit) ? req.splitKeyword() : req.rawTarget;

        List<SongCandidate> list = fetchNetEaseCandidates(base, primaryKeyword);
        if (list.isEmpty() && req.hasDeSplit && !primaryKeyword.equals(req.splitKeyword())) {
            list = fetchNetEaseCandidates(base, req.splitKeyword());
        }
        return pickBestCandidate(list, req, beforeKey);
    }

    private static List<SongCandidate> fetchNetEaseCandidates(String base, String keyword) {
        List<SongCandidate> out = new ArrayList<>();
        String encoded = urlEncode(keyword);
        String[] paths = new String[] {
                base + "/api/cloudsearch/pc?s=" + encoded + "&type=1&offset=0&limit=15",
                base + "/api/search/get/web?s=" + encoded + "&type=1&offset=0&total=true&limit=15"
        };

        for (String url : paths) {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://music.163.com/");
                String body = httpRequest("GET", url, null, headers);
                if (body == null || body.isEmpty()) continue;

                Map<String, Object> root = JsonMini.parseObject(body);
                Map<String, Object> result = JsonMini.getMap(root, "result");
                List<Object> songs = JsonMini.getList(result, "songs");
                if (songs == null || songs.isEmpty()) continue;

                for (Object item : songs) {
                    Map<String, Object> sm = JsonMini.asMap(item);
                    if (sm == null) continue;

                    // Filter out grayed-out / copyright-restricted tracks (privilege.st < 0)
                    Map<String, Object> priv = JsonMini.getMap(sm, "privilege");
                    if (priv != null && priv.get("st") instanceof Number) {
                        if (((Number) priv.get("st")).intValue() < 0) {
                            continue;
                        }
                    } else if (sm.get("status") instanceof Number) {
                        if (((Number) sm.get("status")).intValue() < 0) {
                            continue;
                        }
                    }

                    Object idObj = sm.get("id");
                    if (!(idObj instanceof Number)) continue;
                    String id = String.valueOf(((Number) idObj).longValue());
                    String name = safe(JsonMini.getString(sm, "name"));
                    if (name.isEmpty()) continue;

                    List<Object> arList = JsonMini.getList(sm, "ar");
                    if (arList == null) arList = JsonMini.getList(sm, "artists");
                    StringBuilder artistSb = new StringBuilder();
                    if (arList != null) {
                        for (Object ao : arList) {
                            Map<String, Object> am = JsonMini.asMap(ao);
                            String an = safe(JsonMini.getString(am, "name"));
                            if (!an.isEmpty()) {
                                if (artistSb.length() > 0) artistSb.append('/');
                                artistSb.append(an);
                            }
                        }
                    }

                    Map<String, Object> alMap = JsonMini.getMap(sm, "al");
                    if (alMap == null) alMap = JsonMini.getMap(sm, "album");
                    String album = safe(JsonMini.getString(alMap, "name"));

                    out.add(new SongCandidate(id, name, artistSb.toString(), album));
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Throwable t) {
                Log.w(TAG, "fetchNetEaseCandidates error: " + t.getMessage());
            }
        }
        return out;
    }

    /**
     * Searches QQ Music via smartbox_new.fcg (with musicu.fcg fallback) for {@code req}.
     * {@code baseUrlOverride} may be null (uses https://c.y.qq.com) or a mock URL in unit tests.
     */
    public static SongCandidate searchQqMusicSong(
            PlaySearchRequest req, String beforeKey, String baseUrlOverride) {
        if (req == null) return null;
        String base = (baseUrlOverride != null && !baseUrlOverride.isEmpty())
                ? stripTrailingSlash(baseUrlOverride)
                : "https://c.y.qq.com";

        int deCount = countChar(req.rawTarget, '的');
        List<String> queries = new ArrayList<>();
        if (deCount >= 2 && req.hasDeSplit) {
            queries.add(req.splitKeyword());
        } else {
            queries.add(req.rawTarget);
            if (req.hasDeSplit && !req.splitKeyword().equals(req.rawTarget)) {
                queries.add(req.splitKeyword());
            }
        }

        List<SongCandidate> candidates = new ArrayList<>();
        for (String q : queries) {
            candidates = fetchQqSmartboxCandidates(base, q);
            if (!candidates.isEmpty()) break;
        }
        if (candidates.isEmpty() && baseUrlOverride == null) {
            candidates = fetchQqDesktopSearchCandidates(req.splitKeyword());
        }
        return pickBestCandidate(candidates, req, beforeKey);
    }

    private static List<SongCandidate> fetchQqSmartboxCandidates(String base, String keyword) {
        List<SongCandidate> out = new ArrayList<>();
        try {
            String url = base + "/splcloud/fcgi-bin/smartbox_new.fcg?is_xml=0&format=json&key="
                    + urlEncode(keyword);
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");
            String body = httpRequest("GET", url, null, headers);
            if (body == null || body.isEmpty()) return out;

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
                if (!mid.isEmpty() && !name.isEmpty()) {
                    out.add(new SongCandidate(mid, name, singer, ""));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchQqSmartboxCandidates error: " + t.getMessage());
        }
        return out;
    }

    private static List<SongCandidate> fetchQqDesktopSearchCandidates(String keyword) {
        List<SongCandidate> out = new ArrayList<>();
        try {
            String payload = "{\"req\":{\"module\":\"music.search.SearchCgiService\","
                    + "\"method\":\"DoSearchForQQMusicDesktop\","
                    + "\"param\":{\"query\":\"" + escapeJsonString(keyword)
                    + "\",\"num_per_page\":10,\"page_num\":1,\"search_type\":0}}}";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            headers.put("Referer", "https://y.qq.com/");
            String body = httpRequest("POST", "https://u.y.qq.com/cgi-bin/musicu.fcg", payload, headers);
            if (body == null || body.isEmpty()) return out;

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

                List<Object> singers = JsonMini.getList(sm, "singer");
                StringBuilder artistSb = new StringBuilder();
                if (singers != null) {
                    for (Object so : singers) {
                        Map<String, Object> sMap = JsonMini.asMap(so);
                        String sn = safe(JsonMini.getString(sMap, "name"));
                        if (!sn.isEmpty()) {
                            if (artistSb.length() > 0) artistSb.append('/');
                            artistSb.append(sn);
                        }
                    }
                }
                Map<String, Object> albumMap = JsonMini.getMap(sm, "album");
                String album = safe(JsonMini.getString(albumMap, "name"));
                out.add(new SongCandidate(mid, name, artistSb.toString(), album));
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchQqDesktopSearchCandidates error: " + t.getMessage());
        }
        return out;
    }

    public static SongCandidate pickBestCandidate(
            List<SongCandidate> candidates, PlaySearchRequest req, String beforeKey) {
        if (candidates == null || candidates.isEmpty() || req == null) return null;
        SongCandidate best = null;
        for (int i = 0; i < candidates.size(); i++) {
            SongCandidate c = candidates.get(i);
            c.score = scoreCandidate(c, req, beforeKey, i);
            if (best == null || c.score > best.score) {
                best = c;
            }
        }
        return best;
    }

    private static int scoreCandidate(
            SongCandidate c, PlaySearchRequest req, String beforeKey, int rankIndex) {
        int score = 100 - (rankIndex * 4);
        String normTitle = normalizeForMatch(c.title);
        String cleanTitle = normalizeForMatch(PAT_VERSION_SUFFIX.matcher(c.title).replaceFirst(""));
        String normArtist = normalizeForMatch(c.artist);
        String normRaw = normalizeForMatch(req.rawTarget);
        String normPArtist = normalizeForMatch(req.possibleArtist);
        String normPSong = normalizeForMatch(req.possibleSong);

        boolean isArtistAnySong = false;

        if (req.artistOnly) {
            isArtistAnySong = true;
            if (artistMatches(normArtist, normPArtist)) {
                score += 170;
            } else {
                score -= 90;
            }
        } else if (req.hasDeSplit) {
            boolean aMatch = artistMatches(normArtist, normPArtist);
            boolean sExact = !normPSong.isEmpty() && normTitle.equals(normPSong);
            boolean sClean = !normPSong.isEmpty() && cleanTitle.equals(normPSong);
            boolean fullExact = normTitle.equals(normRaw);
            boolean fullClean = cleanTitle.equals(normRaw);

            if (aMatch && (sExact || sClean)) {
                score += 190 + (sExact ? 40 : 0);
            } else if (fullExact || fullClean) {
                score += 180 + (fullExact ? 35 : 0);
            } else if (sExact || sClean) {
                score += 75 + (sExact ? 20 : 0);
            } else if (aMatch && !normPSong.isEmpty() && normTitle.contains(normPSong)) {
                score += 110;
            } else if (aMatch) {
                score += 45;
            }
        } else {
            boolean titleExact = normTitle.equals(normRaw);
            boolean titleClean = cleanTitle.equals(normRaw);
            boolean aMatch = artistMatches(normArtist, normRaw);

            if (aMatch && !titleExact && !titleClean) {
                isArtistAnySong = true;
                score += 155;
            } else if (titleExact) {
                score += 160;
            } else if (titleClean) {
                score += 125;
            } else if (normTitle.contains(normRaw)) {
                score += 55;
            }
        }

        // Penalize karaoke/instrumental/live/DJ/commentary tracks unless explicitly requested
        String lowTitle = c.title.toLowerCase(Locale.ROOT);
        String lowQuery = req.rawTarget.toLowerCase(Locale.ROOT);

        if (!containsAny(lowQuery, "伴奏", "instrumental", "karaoke", "卡拉ok", "纯音乐")) {
            if (containsAny(lowTitle, "伴奏", "instrumental", "karaoke", "卡拉ok", "消音")) {
                score -= 240;
            }
        }
        if (!containsAny(lowQuery, "live", "现场", "演唱会")) {
            if (containsAny(lowTitle, "live", "现场", "演唱会")) {
                score -= 48;
            }
        }
        if (!containsAny(lowQuery, "dj", "remix", "串烧", "慢摇", "倍速", "0.8", "1.2")) {
            if (containsAny(lowTitle, "dj", "remix", "串烧", "慢摇", "0.8x", "0.9x", "1.2x", "倍速", "铃声")) {
                score -= 95;
            }
        }
        if (!containsAny(lowQuery, "翻唱", "cover", "demo")) {
            if (containsAny(lowTitle, "demo", "翻唱", "cover") || c.title.length() > 26) {
                score -= 80;
            }
        }

        // Cycle through different hit tracks when user asks for an artist ("我想听李荣浩")
        if (isArtistAnySong) {
            if (beforeKey != null && !beforeKey.isEmpty() && c.key().equalsIgnoreCase(beforeKey)) {
                score -= 600;
            }
            synchronized (RECENT_SEARCH_PLAYED_KEYS) {
                if (RECENT_SEARCH_PLAYED_KEYS.contains(c.key().toLowerCase(Locale.ROOT))) {
                    score -= 350;
                }
            }
        }

        return score;
    }

    private static void rememberPlayedCandidate(SongCandidate c) {
        if (c == null) return;
        String k = c.key().toLowerCase(Locale.ROOT);
        synchronized (RECENT_SEARCH_PLAYED_KEYS) {
            RECENT_SEARCH_PLAYED_KEYS.remove(k);
            RECENT_SEARCH_PLAYED_KEYS.addLast(k);
            while (RECENT_SEARCH_PLAYED_KEYS.size() > MAX_RECENT_PLAYED) {
                RECENT_SEARCH_PLAYED_KEYS.removeFirst();
            }
        }
    }

    public static void clearRecentPlayedForTest() {
        synchronized (RECENT_SEARCH_PLAYED_KEYS) {
            RECENT_SEARCH_PLAYED_KEYS.clear();
        }
    }

    private static String resolvePrimaryMusicPackage(Context ctx, String explicitPkg, TrackInfo before) {
        if (explicitPkg != null && !explicitPkg.isEmpty()) {
            return explicitPkg;
        }
        if (before != null) {
            if (PKG_QQMUSIC.equals(before.packageName)) return PKG_QQMUSIC;
            if (PKG_NETEASE.equals(before.packageName)) return PKG_NETEASE;
        }
        String saved = loadPreferredMusicPkg(ctx);
        if (!saved.isEmpty()) {
            return saved;
        }
        return PKG_NETEASE;
    }

    private static void savePreferredMusicPkg(Context ctx, String pkg) {
        if (ctx == null || (!PKG_NETEASE.equals(pkg) && !PKG_QQMUSIC.equals(pkg))) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(PREF_KEY_MUSIC_PKG, pkg).apply();
        } catch (Throwable ignored) {}
    }

    private static String loadPreferredMusicPkg(Context ctx) {
        if (ctx == null) return "";
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return safe(sp.getString(PREF_KEY_MUSIC_PKG, ""));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String httpRequest(
            String method, String urlStr, String jsonBody, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(SEARCH_HTTP_TIMEOUT_MS);
            conn.setReadTimeout(SEARCH_HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return "";
            }
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

    /**
     * Called after voice assistant TTS/SCO completes (or after deep-link launch) so transient
     * AudioFocus release doesn't leave a newly skipped/searched song paused.
     */
    public static void enforcePostAnnouncementState(Context ctx, int action, String targetPkg, long logId) {
        try {
            if (ctx == null) return;
            MediaController ctrl = findControllerForPkgOrBest(ctx, targetPkg);
            if (action == ACTION_NEXT || action == ACTION_PREVIOUS
                    || action == ACTION_PLAY || action == ACTION_PLAY_SEARCH) {
                if (ctrl != null) {
                    PlaybackState ps = ctrl.getPlaybackState();
                    int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
                    if (st != PlaybackState.STATE_PLAYING
                            && st != PlaybackState.STATE_BUFFERING
                            && st != PlaybackState.STATE_CONNECTING) {
                        ctrl.getTransportControls().play();
                        if (logId > 0L) {
                            CustomAiCallLog.addEvent(logId, "POST_TTS_ENSURE_PLAY(prevState=" + st + ")");
                        }
                    }
                }
            } else if (action == ACTION_PAUSE) {
                if (ctrl != null) {
                    PlaybackState ps = ctrl.getPlaybackState();
                    int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
                    if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                        ctrl.getTransportControls().pause();
                        if (logId > 0L) {
                            CustomAiCallLog.addEvent(logId, "POST_TTS_ENSURE_PAUSE");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static MediaController findControllerForPkgOrBest(Context ctx, String preferredPkg) {
        if (preferredPkg != null && !preferredPkg.isEmpty()) {
            List<MediaController> list = getActiveControllers(ctx);
            if (list != null) {
                for (MediaController mc : list) {
                    if (mc != null && preferredPkg.equals(mc.getPackageName())) {
                        return mc;
                    }
                }
            }
        }
        return findBestController(ctx);
    }

    private static MediaController findBestController(Context ctx) {
        List<MediaController> list = getActiveControllers(ctx);
        if (list == null || list.isEmpty()) return null;

        String selfPkg = ctx.getPackageName();
        MediaController firstPlaying = null;
        MediaController firstWithMeta = null;
        MediaController firstValid = null;

        for (MediaController mc : list) {
            if (mc == null) continue;
            String pkg = safe(mc.getPackageName());
            if (pkg.isEmpty() || pkg.equals(selfPkg) || pkg.startsWith("com.android.server.telecom")) {
                continue;
            }
            if (firstValid == null) firstValid = mc;

            PlaybackState ps = mc.getPlaybackState();
            int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            boolean playing = (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING);
            if (playing && firstPlaying == null) {
                firstPlaying = mc;
            }

            MediaMetadata mm = mc.getMetadata();
            if (mm != null && firstWithMeta == null) {
                String title = firstNonEmpty(
                        mm.getString(MediaMetadata.METADATA_KEY_TITLE),
                        mm.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                if (!title.isEmpty()) {
                    firstWithMeta = mc;
                }
            }
        }

        if (firstPlaying != null) return firstPlaying;
        if (firstWithMeta != null) return firstWithMeta;
        return firstValid;
    }

    private static List<MediaController> getActiveControllers(Context ctx) {
        if (ctx == null) return null;
        MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;

        ComponentName primary = new ComponentName(ctx.getPackageName(), KNOWN_NLS_CLASS);
        try {
            return msm.getActiveSessions(primary);
        } catch (SecurityException ignored) {
            // Try discovering any NotificationListenerService declared in this APK
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent probe = new Intent("android.service.notification.NotificationListenerService");
                probe.setPackage(ctx.getPackageName());
                List<ResolveInfo> services = pm.queryIntentServices(probe, 0);
                if (services != null) {
                    for (ResolveInfo ri : services) {
                        if (ri != null && ri.serviceInfo != null) {
                            ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                            try {
                                return msm.getActiveSessions(cn);
                            } catch (SecurityException ignored2) {}
                        }
                    }
                }
            } catch (Throwable ignored3) {}
        } catch (Throwable t) {
            Log.w(TAG, "getActiveSessions error", t);
        }
        return null;
    }

    public static boolean isNotificationListenerEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName() + "/");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static TrackInfo extractTrackInfo(Context ctx, MediaController mc) {
        TrackInfo ti = new TrackInfo();
        ti.packageName = safe(mc.getPackageName());
        ti.appName = resolveAppLabel(ctx, ti.packageName);

        PlaybackState ps = mc.getPlaybackState();
        if (ps != null) {
            ti.playbackState = ps.getState();
            ti.positionMs = ps.getPosition();
        }

        MediaMetadata mm = mc.getMetadata();
        if (mm != null) {
            MediaDescription desc = mm.getDescription();
            ti.title = firstNonEmpty(
                    mm.getString(MediaMetadata.METADATA_KEY_TITLE),
                    mm.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    desc != null && desc.getTitle() != null ? desc.getTitle().toString() : "");
            ti.artist = firstNonEmpty(
                    mm.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    mm.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    mm.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                    mm.getString(MediaMetadata.METADATA_KEY_WRITER),
                    mm.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    desc != null && desc.getSubtitle() != null ? desc.getSubtitle().toString() : "");
            ti.album = firstNonEmpty(
                    mm.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    mm.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
                    desc != null && desc.getDescription() != null ? desc.getDescription().toString() : "");
            ti.durationMs = mm.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        return ti;
    }

    private static String resolveAppLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String known = KNOWN_APP_LABELS.get(pkg);
        if (known != null) return known;
        if (ctx == null) return pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                return cs.toString();
            }
        } catch (Throwable ignored) {}
        return pkg;
    }

    private static void dispatchMediaKey(Context ctx, int keyCode) {
        if (ctx == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable t) {
            Log.w(TAG, "dispatchMediaKey error", t);
        }
    }

    private static boolean artistMatches(String candidateArtists, String targetArtist) {
        if (candidateArtists.isEmpty() || targetArtist.isEmpty()) return false;
        if (candidateArtists.equals(targetArtist) || candidateArtists.contains(targetArtist)) {
            return true;
        }
        String[] parts = candidateArtists.split("[/,&、]");
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty() && trimmed.equals(targetArtist)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String n : needles) {
            if (n != null && !n.isEmpty() && haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static int countChar(String s, char ch) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) n++;
        }
        return n;
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

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
