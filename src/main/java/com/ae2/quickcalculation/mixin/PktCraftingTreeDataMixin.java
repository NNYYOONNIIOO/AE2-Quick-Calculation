package com.ae2.quickcalculation.mixin;

import appeng.crafting.CraftingTreeCompatibility;
import appeng.crafting.CraftingTreeNode;
import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional direct adapter for AE2CT-Legacy's serialized tree model. */
@Pseudo
@Mixin(targets = "github.kasuminova.ae2ctl.common.network.PktCraftingTreeData", remap = false)
public abstract class PktCraftingTreeDataMixin {
    @Inject(method = "<init>(Lappeng/crafting/CraftingTreeNode;)V", at = @At("RETURN"), remap = false)
    private void ae2QuickCalculation$replaceRoot(final CraftingTreeNode tree,
                                                   final CallbackInfo callbackInfo) {
        final Object replacement = CraftingTreeCompatibility.createLiteTree(tree);
        if (replacement == null) {
            return;
        }
        try {
            final Field rootField = this.getClass().getDeclaredField("root");
            rootField.setAccessible(true);
            rootField.set(this, replacement);
        } catch (final Throwable ignored) {
            // Keep AE2CT's native tree if a future version changes its field.
        }
    }
}

