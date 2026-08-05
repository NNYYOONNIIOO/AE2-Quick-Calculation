package com.ae2vm.addon.core;

import org.spongepowered.asm.mixin.Mixins;
import zone.rong.mixinbooter.MixinLoader;

/** Registers the late Mixin configuration supplied by MixinBooter. */
@MixinLoader
public final class AE2VMMixinLoader {
    {
        Mixins.addConfiguration("mixins.ae2vm.json");
    }
}
