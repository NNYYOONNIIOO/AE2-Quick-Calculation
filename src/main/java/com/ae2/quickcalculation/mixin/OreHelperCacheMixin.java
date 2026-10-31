package com.ae2.quickcalculation.mixin;

import java.util.Collections;
import java.util.Map;
import appeng.util.item.OreHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Protects OreHelper's shared item-to-ore-reference cache from concurrent writes. */
@Mixin(value = OreHelper.class, remap = false)
public abstract class OreHelperCacheMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Object, Object> references;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void ae2qc$synchronizeReferenceCache(CallbackInfo callbackInfo) {
        this.references = Collections.synchronizedMap(this.references);
    }
}
