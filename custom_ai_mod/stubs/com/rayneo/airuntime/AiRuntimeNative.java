package com.rayneo.airuntime;

import com.rayneo.airuntime.controller.AssistantController;
import com.rayneo.airuntime.controller.TTSController;

public final class AiRuntimeNative {
    public static final AiRuntimeNative INSTANCE = new AiRuntimeNative();

    private AiRuntimeNative() {}

    public final AssistantController getAssistantController() {
        return null;
    }

    public final TTSController getTtsController() {
        return null;
    }
}
