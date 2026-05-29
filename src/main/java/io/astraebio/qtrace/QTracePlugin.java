package io.astraebio.qtrace;

import qupath.lib.gui.QuPathGUI;

/**
 * Service interface implemented by the Enterprise module.
 * Discovered at runtime via ServiceLoader — if no enterprise JAR is present,
 * QTracePluginManager.get() returns null and premium features are silently absent.
 *
 * Enterprise features: cryptographic certification (Ed25519 + OpenTimestamps),
 * contributor identity verification, blockchain anchoring (planned v0.7.0).
 * All methods are default no-ops for forward compatibility.
 */
public interface QTracePlugin {
    default void certifyStamp(ValidationStamp stamp, QuPathGUI qupath) {}
    default void verifyContributor(String contributorId, QuPathGUI qupath) {}
}
