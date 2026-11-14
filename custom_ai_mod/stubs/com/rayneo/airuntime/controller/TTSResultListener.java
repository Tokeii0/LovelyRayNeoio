package com.rayneo.airuntime.controller;

public interface TTSResultListener {
    void onError(int errorCode, String errorMessage);
    void onStartResult(boolean success, String message);
    void onTTSCancel();
    void onTTSResponse(RayNeoTTSDataResponse response);
    void onTtsStatusChange(int status);
}
