package com.rayneo.airuntime.controller;

public interface AskAIResultListener {
    void onAskAiResponse(RayNeoAskAIResponse response);
    void onError(int code, String message);
    void onResponseComplete();
    void onStartResult(boolean success, String message);
}
