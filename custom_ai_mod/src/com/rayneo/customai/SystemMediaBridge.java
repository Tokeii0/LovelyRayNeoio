package com.rayneo.customai;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Direct Android system media controller & active track inspector.
 * Uses RayNeo's existing NotificationListenerService permission with MediaSessionManager
 * (plus AudioManager media key fallback) to control third-party music apps (网易云音乐, QQ音乐,
 * 汽水音乐, Apple Music, Spotify, Bilibili, etc.) and read real-time song title/artist/album.
 */
public final class SystemMediaBridge {

    private static final String TAG = "RayNeoCustomAI";
    private static final String KNOWN_NLS_CLASS =
            "im.zoe.labs.flutter_notification_listener.NotificationsHandlerService";

    public static final int ACTION_NEXT = 1;
    public static final int ACTION_PREVIOUS = 2;
    public static final int ACTION_PAUSE = 3;
    public static final int ACTION_PLAY = 4;
    public static final int ACTION_QUERY_SONG = 5;
    public static final int ACTION_FAVORITE = 6;

    private static final Pattern PAT_NEXT = Pattern.compile(
            "(切[歌割哥]|切[个一首]歌|下一[首歌曲]|下首歌|下一个歌|换[一]?首歌|换[一个]?歌|换一首|换首别的|跳过这首歌"
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

    private static final Map<String, String> KNOWN_APP_LABELS = new HashMap<>();
    static {
        KNOWN_APP_LABELS.put("com.netease.cloudmusic", "网易云音乐");
        KNOWN_APP_LABELS.put("com.tencent.qqmusic", "QQ音乐");
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

        public ActionResult(
                int action,
                boolean dispatchedViaController,
                String targetPackage,
                TrackInfo beforeTrack,
                TrackInfo afterTrack,
                String spokenReply,
                String debugTrace
        ) {
            this.action = action;
            this.dispatchedViaController = dispatchedViaController;
            this.targetPackage = targetPackage == null ? "" : targetPackage;
            this.beforeTrack = beforeTrack;
            this.afterTrack = afterTrack;
            this.spokenReply = spokenReply == null ? "" : spokenReply;
            this.debugTrace = debugTrace == null ? "" : debugTrace;
        }
    }

    private SystemMediaBridge() {}

    /**
     * Matches local media control & now-playing queries. Returns 0 if not a media command.
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
            default: return "媒体控制";
        }
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

    /**
     * Executes a media command against Android's active MediaSession (or AudioManager fallback)
     * and polls updated MediaMetadata so we can announce the exact song name & artist.
     */
    public static ActionResult execute(Context ctx, int action, long logId) {
        boolean nlsEnabled = isNotificationListenerEnabled(ctx);
        MediaController ctrl = findBestController(ctx);
        TrackInfo before = ctrl != null ? extractTrackInfo(ctx, ctrl) : null;
        String beforeKey = before != null ? before.key() : "";
        String targetPkg = ctrl != null ? safe(ctrl.getPackageName()) : "";
        boolean usedController = false;

        if (logId > 0L) {
            CustomAiCallLog.addEvent(logId, "MEDIA_BEFORE(nls=" + nlsEnabled
                    + ", pkg=" + targetPkg
                    + ", song=" + (before != null ? before.formatSongDisplay() : "none")
                    + ", state=" + (before != null ? before.playbackState : -1) + ")");
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

    /**
     * Called ~350ms after voice assistant TTS/SCO completes so transient AudioFocus release
     * doesn't leave a newly skipped song paused (or re-resume an explicitly paused song).
     */
    public static void enforcePostAnnouncementState(Context ctx, int action, String targetPkg, long logId) {
        try {
            if (ctx == null) return;
            MediaController ctrl = findControllerForPkgOrBest(ctx, targetPkg);
            if (action == ACTION_NEXT || action == ACTION_PREVIOUS || action == ACTION_PLAY) {
                if (ctrl != null) {
                    PlaybackState ps = ctrl.getPlaybackState();
                    int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
                    if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_BUFFERING) {
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
