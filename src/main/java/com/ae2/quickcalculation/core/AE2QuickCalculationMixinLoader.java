package com.ae2.quickcalculation.core;

import zone.rong.mixinbooter.ILateMixinLoader;
import java.util.ArrayList;
import java.util.List;

/** Registers the late Mixin configuration supplied by MixinBooter. */
public final class AE2QuickCalculationMixinLoader implements ILateMixinLoader {
    private static final String CONFIG = "mixins.ae2_quick_calculation.json";
    private static final String AE2_ENHANCED_CONFIG =
            "mixins.ae2_quick_calculation.ae2enhanced.json";

    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<String>();
        configs.add(CONFIG);
        // Late loaders are queried before Forge has finished populating the
        // mod list. Registering this optional config unconditionally lets
        // @Pseudo skip it cleanly when AE2Enhanced is absent.
        configs.add(AE2_ENHANCED_CONFIG);
        return configs;
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        return CONFIG.equals(mixinConfig) || AE2_ENHANCED_CONFIG.equals(mixinConfig);
    }
}
