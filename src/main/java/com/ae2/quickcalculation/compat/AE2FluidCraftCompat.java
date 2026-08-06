package com.ae2.quickcalculation.compat;

import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;

/** Optional AE2 Fluid Crafting bridge without a runtime class dependency. */
public final class AE2FluidCraftCompat {
    private static final Method IS_FLUID_FAKE_ITEM = findFluidFakeItemMethod();

    private AE2FluidCraftCompat() {
    }

    public static boolean isFluidFakeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || IS_FLUID_FAKE_ITEM == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(IS_FLUID_FAKE_ITEM.invoke(null, stack));
        } catch (Throwable ignored) {
            // An optional integration must never turn a missing or incompatible
            // AE2FC installation into a crafting-job failure.
            return false;
        }
    }

    private static Method findFluidFakeItemMethod() {
        try {
            Class<?> fakeFluids = Class.forName(
                    "com.glodblock.github.common.item.fake.FakeFluids",
                    false,
                    AE2FluidCraftCompat.class.getClassLoader());
            return fakeFluids.getMethod("isFluidFakeItem", ItemStack.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
