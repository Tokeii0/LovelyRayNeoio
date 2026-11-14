package com.rayneo.airuntime.controller;

public final class TTSPlayStatus {
    public static final TTSPlayStatus INSTANCE = new TTSPlayStatus();
    public static final int STATE_TTS_SPEAK_BEGIN = 1;
    public static final int STATE_TTS_SPEAK_PAUSED = 2;
    public static final int STATE_TTS_SPEAK_RESUMED = 3;
    public static final int STATE_TTS_SPEAK_PROGRESS = 4;
    public static final int STATE_TTS_SPEAK_COMPLETED = 5;

    private TTSPlayStatus() {}
}
