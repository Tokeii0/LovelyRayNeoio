package com.rayneo.airuntime.controller;

public interface AiResultListener {
    void onAsrResult(String asr, boolean finished, String sessionId);
    void onAudioRecordEnd();
    void onAudioRecordStart();
    void onDeepThinkStatus(String status, String text);
    void onError(int code, String message);
    void onNlpResult(NlpResult result);
    void onNotify(int type, int code, String message);
    void onRayClawToolCallInfo(RayClawToolCallInfo info);
    void onResponseComplete();
    void onSpeechStatus(int status);
    void onTtsStatusChange(int status, String sessionId);
    void onVadStatusChange(int status);
}
