package com.ae2.quickcalculation.compat;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

/** Optional AE2 Fluid Crafting bridge. */
public final class AE2FluidCraftCompat {
    private static final String MOD_ID = "ae2fc";

    private AE2FluidCraftCompat() {
    }

    public static boolean isAvailable() {
        return Loader.isModLoaded(MOD_ID);
    }

    public static boolean isFluidFakeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isAvailable()) {
            return false;
        }

        try {
            return AE2FluidCraftApi.isFluidFakeItem(stack);
        } catch (LinkageError ignored) {
            // The integration is optional and must remain harmless if an older
            // AE2FC build does not expose the expected API class.
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
