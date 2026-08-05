package com.ae2.quickcalculation.core;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

/** Registers the late Mixin configuration supplied by MixinBooter. */
public final class AE2QuickCalculationMixinLoader implements ILateMixinLoader {
    private static final String CONFIG = "mixins.ae2_quick_calculation.json";

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList(CONFIG);
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        return CONFIG.equals(mixinConfig);
    }
}
