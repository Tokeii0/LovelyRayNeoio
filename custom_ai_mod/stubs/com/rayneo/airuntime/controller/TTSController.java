package com.rayneo.airuntime.controller;

public final class TTSController {
    public TTSController() {}
    public final boolean init() { return false; }
    public final boolean initWorkflow(RayNeoTTSConfig config, TTSResultListener listener) { return false; }
    public final boolean startWorkflow(RayNeoTTSWorkflowParams params) { return false; }
    public final boolean stopWorkflow() { return false; }
    public final boolean destroyWorkflow() { return false; }
    public final boolean release() { return false; }
}
