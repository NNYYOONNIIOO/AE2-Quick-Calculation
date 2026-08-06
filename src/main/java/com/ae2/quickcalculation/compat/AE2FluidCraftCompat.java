package com.ae2.quickcalculation.compat;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
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

    public static IAEItemStack packFluid(IAEFluidStack fluid) {
        if (fluid == null || fluid.getStackSize() <= 0L || !isAvailable()) {
            return null;
        }

        try {
            return AE2FluidCraftApi.packFluid(fluid);
        } catch (LinkageError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Returns the canonical AE2FC item-channel key for a fake fluid item.
     * Processing patterns may store packets while the network monitor exposes
     * drops, so both forms must be normalized before exact list lookups.
     */
    public static IAEItemStack normalizeFluidItem(IAEItemStack stack) {
        if (stack == null || !isAvailable()) {
            return stack == null ? null : stack.copy();
        }

        try {
            return AE2FluidCraftApi.normalizeFluidItem(stack);
        } catch (LinkageError ignored) {
            return stack.copy();
        } catch (RuntimeException ignored) {
            return stack.copy();
        }
    }

    /** Returns the packet form used when querying pattern providers. */
    public static IAEItemStack packFluidPacket(IAEItemStack stack) {
        return packFluidPacket(stack, 0L);
    }

    /**
     * Returns a packet query key using the per-craft fluid amount when the
     * canonical drop key itself has no amount left after reset().
     */
    public static IAEItemStack packFluidPacket(IAEItemStack stack,
                                                long amountHint) {
        if (stack == null || !isAvailable()) {
            return null;
        }

        try {
            return AE2FluidCraftApi.packFluidPacket(stack, amountHint);
        } catch (LinkageError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
