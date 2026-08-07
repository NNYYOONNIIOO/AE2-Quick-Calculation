package com.ae2.quickcalculation.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.QuickCalculationTreeNode;
import com.ae2.quickcalculation.AE2QuickCalculation;
import com.ae2.quickcalculation.access.CraftingJobAccess;
import com.ae2.quickcalculation.compat.AE2FluidCraftCompat;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the root calculation node; AE2 owns the rest of the job lifecycle. */
@Mixin(value = CraftingJob.class, remap = false)
public abstract class CraftingJobMixin implements CraftingJobAccess {
    @Shadow
    @Final
    private ICraftingGrid cc;

    @Shadow
    private CraftingTreeNode tree;

    @Override
    public ICraftingGrid ae2quickcalculation$getCraftingGrid() {
        return this.cc;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ae2quickcalculation$replaceRoot(World world,
                                                  IGrid grid,
                                                  IActionSource source,
                                                  IAEItemStack output,
                                                  ICraftingCallback callback,
                                                  CallbackInfo callbackInfo) {
        if (grid == null || output == null) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][DIAG] CraftingJob root replacement skipped grid={} output={}",
                    grid != null,
                    AE2FluidCraftCompat.debugStack(output));
            return;
        }
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid != null) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][DIAG] CraftingJob root replacement applied job={} output={} craftingGrid={}",
                    Integer.toHexString(System.identityHashCode(this)),
                    AE2FluidCraftCompat.debugStack(output),
                    craftingGrid.getClass().getName());
            this.tree = new QuickCalculationTreeNode(
                    craftingGrid,
                    (CraftingJob) (Object) this,
                    output,
                    world,
                    source,
                    grid);
        } else {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][DIAG] CraftingJob root replacement skipped because crafting grid cache is null output={}",
                    AE2FluidCraftCompat.debugStack(output));
        }
    }
}
