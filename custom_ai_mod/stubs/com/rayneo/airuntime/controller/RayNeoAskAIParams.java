package com.rayneo.airuntime.controller;

import java.util.Collections;
import java.util.List;

public final class RayNeoAskAIParams {
    public RayNeoAskAIParams() {}
    public final String getDialogId() { return ""; }
    public final String getText() { return ""; }
    public final String getModel() { return ""; }
    public final String getLanguageCode() { return ""; }
    public final String getCurrentDateTime() { return ""; }
    public final String getCurrentLocation() { return ""; }
    public final RayNeoAskAIReferConfig getReferConfig() { return null; }
    public final RayNeoAskAIGenerationOptions getGenerationOptions() { return null; }
    public final List<String> getSelectedSkillKeys() { return Collections.emptyList(); }
}
