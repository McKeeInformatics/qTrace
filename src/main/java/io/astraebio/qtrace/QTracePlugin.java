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
    default void replay(QuPathGUI qupath, ActionLogger logger) {}

    /**
     * Validates a .qtlicense JWT token.
     * Returns a decoded {@link LicenseInfo} if the signature is valid and the token is not expired,
     * or null if the token is invalid.
     */
    default LicenseInfo validateLicense(String token) { return null; }

    /**
     * Returns the LicenseInfo for the currently loaded license, or null if none/invalid.
     * Used by the panel to display the license holder's name.
     */
    default LicenseInfo getActiveLicenseInfo() { return null; }

    /**
     * Called before any stamp is recorded. Returns true if the user is authorized to stamp
     * (PIN verified, or no PIN set). Returns false if the user cancelled or entered a wrong PIN.
     * Default: always authorized (no PIN without Enterprise).
     */
    default boolean requirePinToStamp(javafx.stage.Window owner) { return true; }
}
