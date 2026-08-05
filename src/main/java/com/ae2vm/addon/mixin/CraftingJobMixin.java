package com.ae2vm.addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.VMTreeNode;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the root calculation node; the rest of AE2's job lifecycle stays intact. */
@Mixin(value = CraftingJob.class, remap = false)
public abstract class CraftingJobMixin {
    @Shadow
    private CraftingTreeNode tree;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ae2vm$replaceRoot(World world,
                                   IGrid grid,
                                   IActionSource source,
                                   IAEItemStack output,
                                   ICraftingCallback callback,
                                   CallbackInfo callbackInfo) {
        if (grid == null || output == null) {
            return;
        }
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid != null) {
            this.tree = new VMTreeNode(
                    craftingGrid,
                    (CraftingJob) (Object) this,
                    output,
                    world,
                    source);
        }
    }
}
