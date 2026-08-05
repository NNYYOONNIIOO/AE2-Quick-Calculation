package com.ae2.quickcalculation.calculator;

import net.minecraftforge.fml.common.launcher.FMLServerTweaker;

/**
 * Runs the stress entry point through the Forge launch class loader so the
 * normal 1.12.2 development remapper is applied to AE2's classes.
 */
public final class CraftingCalculatorStressTweaker extends FMLServerTweaker {
    @Override
    public String getLaunchTarget() {
        return CraftingCalculatorStressTest.class.getName();
    }
}
