package com.rayneo.airuntime.controller;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RayNeoASRConfig {
    private String bizType = "";
    private String languageCode = "zh-CN";
    private Map<String, Integer> channelRole = new LinkedHashMap<>();

    public RayNeoASRConfig() {}

    public final String getBizType() { return bizType; }
    public final void setBizType(String v) { this.bizType = v; }

    public final String getLanguageCode() { return languageCode; }
    public final void setLanguageCode(String v) { this.languageCode = v; }

    public final Map<String, Integer> getChannelRole() { return channelRole; }
    public final void setChannelRole(Map<String, Integer> v) { this.channelRole = v; }
}
