package com.rayneo.airuntime.controller;

public enum RayNeoAskAIResponseEventType {
    UNSPECIFIED,
    TEXT_DELTA,
    REASONING_DELTA,
    TOOL_CALL,
    CLIENT_ACTION,
    FINISHED,
    COMPLETED
}
