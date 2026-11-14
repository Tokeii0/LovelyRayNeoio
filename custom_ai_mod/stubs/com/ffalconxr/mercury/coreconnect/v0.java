package com.ffalconxr.mercury.coreconnect;

import kotlin.jvm.functions.Function2;

public final class v0 {
    public final String a;
    public final String b;
    public final vb.h c;
    public final byte[] e;
    public final b g;
    public final boolean h;
    public final boolean i;

    public v0(
            byte[] payload,
            String msgId,
            String deviceId,
            vb.h businessID,
            vb.r0 r0,
            b priority,
            boolean needAck,
            boolean preferLowPower,
            wb.p p,
            Function2 cb) {
        this.e = payload;
        this.a = msgId;
        this.b = deviceId;
        this.c = businessID;
        this.g = priority;
        this.i = needAck;
        this.h = preferLowPower;
    }
}
