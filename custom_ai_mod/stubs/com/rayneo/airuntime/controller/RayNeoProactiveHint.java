package com.rayneo.airuntime.controller;

public final class RayNeoProactiveHint {
    private String text;
    private boolean isFinish;
    private String asrTextId;
    private String proactiveHintId;
    private String channelId;

    public RayNeoProactiveHint() {}

    public final String getText() { return text; }
    public final void setText(String v) { this.text = v; }

    public final boolean isFinish() { return isFinish; }
    public final void setFinish(boolean v) { this.isFinish = v; }

    public final String getAsrTextId() { return asrTextId; }
    public final void setAsrTextId(String v) { this.asrTextId = v; }

    public final String getProactiveHintId() { return proactiveHintId; }
    public final void setProactiveHintId(String v) { this.proactiveHintId = v; }

    public final String getChannelId() { return channelId; }
    public final void setChannelId(String v) { this.channelId = v; }
}
