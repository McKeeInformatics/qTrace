package io.astraebio.qtrace;

public final class QTracePluginManager {
    private static QTracePlugin plugin = null;
    // Optimistic by default: Enterprise stays active until a *certain* signal
    // (offline-expired license, or server says inactive) downgrades it. A network
    // error never flips this — an offline pathologist keeps certification.
    private static volatile boolean entitled = true;
    // Why the license is inactive, when it is: "expired" | "corrupted" | "inactive" | null.
    private static volatile String inactiveReason = null;

    private QTracePluginManager() {}

    public static void register(QTracePlugin p) { plugin = p; }
    public static QTracePlugin get()            { return plugin; }

    /** True if the Enterprise JAR is loaded (regardless of license state). */
    public static boolean hasEnterprise()       { return plugin != null; }

    /** True when Enterprise features (certification & compliance) are licensed and active. */
    public static boolean isEntitled()          { return plugin != null && entitled; }

    /**
     * The plugin only when entitled — use this to gate Enterprise *features*
     * (signing, push, identity lock, PIN). Use {@link #get()} for things that must
     * keep working regardless of license state (version, update & license checks).
     */
    public static QTracePlugin getEntitled()    { return isEntitled() ? plugin : null; }

    /** Reason the license is inactive: "expired" | "corrupted" | "inactive", or null when active/none. */
    public static String inactiveReason()       { return inactiveReason; }

    static void setEntitled(boolean e)          { entitled = e; if (e) inactiveReason = null; }

    /** Downgrade to Core with a reason that the UI uses to pick wording/severity. */
    static void setInactive(String reason)      { entitled = false; inactiveReason = reason; }
}
