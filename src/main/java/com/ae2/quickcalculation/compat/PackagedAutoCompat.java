package com.ae2.quickcalculation.compat;

import appeng.api.networking.crafting.ICraftingPatternDetails;

/**
 * Optional PackagedAuto semantics without linking its classes into this mod.
 *
 * PackagedAuto's package machine consumes every encoded input while producing
 * the package. It does not apply vanilla container or tool-return behavior.
 */
public final class PackagedAutoCompat {
    private static final String PACKAGE_PATTERN_HELPER =
            "thelm.packagedauto.integration.appeng.recipe.PackageCraftingPatternHelper";

    private PackagedAutoCompat() {
    }

    public static boolean consumesAllInputs(ICraftingPatternDetails pattern) {
        return pattern != null
                && PACKAGE_PATTERN_HELPER.equals(pattern.getClass().getName());
    }
}
