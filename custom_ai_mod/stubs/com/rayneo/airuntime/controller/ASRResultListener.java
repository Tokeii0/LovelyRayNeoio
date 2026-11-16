package com.rayneo.airuntime.controller;

public interface ASRResultListener {
    void onAsrResult(String asr, boolean finished);
    void onAudioRecordEnd();
    void onAudioRecordStart();
    void onError(int errorCode, String errorMessage);
    void onProactiveHint(RayNeoProactiveHint hint);
    void onStartResult(boolean success, String message);
    void onVadStatusChange(int status);
}
