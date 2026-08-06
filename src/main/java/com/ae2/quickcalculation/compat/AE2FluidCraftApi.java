package com.ae2.quickcalculation.compat;

import com.glodblock.github.common.item.fake.FakeFluids;
import net.minecraft.item.ItemStack;

/**
 * Direct AE2FC calls isolated in a class that is only reached when ae2fc is
 * present. This keeps the main calculation path free of an eager optional API
 * reference while avoiding reflective method lookup.
 */
final class AE2FluidCraftApi {
    private AE2FluidCraftApi() {
    }

    static boolean isFluidFakeItem(ItemStack stack) {
        return FakeFluids.isFluidFakeItem(stack);
    }
}
