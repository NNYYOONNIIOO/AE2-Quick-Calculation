package com.ae2.quickcalculation.mixin;

import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.PatternHelper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes PatternHelper's lazy substitute cache safe for concurrent calculators. */
@Mixin(value = PatternHelper.class, remap = false)
public abstract class PatternHelperCacheMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Integer, List<IAEItemStack>> substituteInputs;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void ae2qc$synchronizeSubstituteCache(ItemStack pattern,
                                                  World world,
                                                  CallbackInfo callbackInfo) {
        this.substituteInputs = Collections.synchronizedMap(this.substituteInputs);
    }
}
