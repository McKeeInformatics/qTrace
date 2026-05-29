package io.astraebio.qtrace;

public final class QTracePluginManager {
    private static QTracePlugin plugin = null;
    private QTracePluginManager() {}

    public static void register(QTracePlugin p) { plugin = p; }
    public static QTracePlugin get()            { return plugin; }
    public static boolean hasEnterprise()       { return plugin != null; }
}
