package com.GameMacro.mini;

/** 进程级共享:MainActivity 把 BleClient 与帧流暴露给 MacroEditorActivity。 */
public final class Holder {

    public interface FrameSink { void onFrame(byte[] f, String hex); }

    private static volatile BleClient client;
    private static volatile FrameSink sink;

    public static BleClient get() { return client; }
    public static void set(BleClient c) { client = c; }

    public static FrameSink getSink() { return sink; }
    public static void setSink(FrameSink s) { sink = s; }

    private Holder() {}
}
