package com.ae2vm.addon.api;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in markers for third-party automation that wants to use AE2 VM.
 *
 * AE2 requests have no requester interface in the 1.12.2 API, so the marker
 * is matched against the actionable machine class name when available.
 */
public final class AE2VMCraftingRegistry {
    private static final Set<String> REGISTERED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private AE2VMCraftingRegistry() {
    }

    public static void register(String marker) {
        if (marker == null || marker.trim().isEmpty()) {
            return;
        }
        REGISTERED.add(marker);
    }

    public static boolean isRegistered(String className) {
        if (className == null) {
            return false;
        }
        for (String marker : REGISTERED) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUnregisteredThirdParty(String className) {
        if (className == null || className.startsWith("appeng.")) {
            return false;
        }
        return !isRegistered(className);
    }

    public static boolean hasRegistrations() {
        return !REGISTERED.isEmpty();
    }
}
