package com.rayneo.airuntime.controller;

public final class SpeechStatus {
    public static final SpeechStatus INSTANCE = new SpeechStatus();
    public static final int LISTENING = 1;
    public static final int RESPONDING = 2;
    public static final int THINKING = 3;

    private SpeechStatus() {}
}
