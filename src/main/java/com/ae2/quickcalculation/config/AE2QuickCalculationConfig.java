package com.ae2.quickcalculation.config;

import com.ae2.quickcalculation.AE2QuickCalculation;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Level;

import java.io.File;

/** Runtime configuration for the server-side calculator and optional toast. */
public final class AE2QuickCalculationConfig {
    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_NOTIFICATIONS = "notifications";

    /** High-volume QCALC INFO diagnostics; disabled by default. */
    public static boolean DEBUG_LOGGING = false;
    /** Successful calculation toast; disabled by default. */
    public static boolean SHOW_SUCCESS_TOAST = false;
    /** Fallback/failure calculation toast; enabled by default. */
    public static boolean SHOW_FAILURE_TOAST = true;

    private AE2QuickCalculationConfig() {
    }

    public static void load(File file) {
        DEBUG_LOGGING = false;
        SHOW_SUCCESS_TOAST = false;
        SHOW_FAILURE_TOAST = true;
        if (file == null) {
            return;
        }

        Configuration configuration = new Configuration(file);
        try {
            configuration.load();
            DEBUG_LOGGING = configuration.getBoolean(
                    "debugLogging",
                    CATEGORY_GENERAL,
                    false,
                    "Output high-volume AE2 Quick Calculation debug logs.");
            SHOW_SUCCESS_TOAST = configuration.getBoolean(
                    "showSuccessToast",
                    CATEGORY_NOTIFICATIONS,
                    false,
                    "Show a toast when optimized calculation succeeds.");
            SHOW_FAILURE_TOAST = configuration.getBoolean(
                    "showFailureToast",
                    CATEGORY_NOTIFICATIONS,
                    true,
                    "Show a toast when optimization fails or falls back to AE2.");
        } catch (RuntimeException failure) {
            AE2QuickCalculation.LOGGER.warn(
                    "Could not load configuration {}; using defaults",
                    file,
                    failure);
        } finally {
            if (configuration.hasChanged()) {
                configuration.save();
            }
        }
    }

    public static boolean shouldShowToast(String translationKey) {
        boolean success = AE2QuickCalculation.TOAST_OPTIMIZED.equals(translationKey)
                || AE2QuickCalculation.TOAST_OPTIMIZED_CYCLE.equals(translationKey);
        return success ? SHOW_SUCCESS_TOAST : SHOW_FAILURE_TOAST;
    }

    /** Suppress the mod's INFO diagnostics while retaining warnings/errors. */
    public static void applyLoggingConfiguration() {
        if (!DEBUG_LOGGING
                && AE2QuickCalculation.LOGGER
                instanceof org.apache.logging.log4j.core.Logger) {
            ((org.apache.logging.log4j.core.Logger) AE2QuickCalculation.LOGGER)
                    .setLevel(Level.WARN);
        }
    }
}
