package com.ae2.quickcalculation.mixin.compat;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import com.ae2.quickcalculation.AE2QuickCalculation;
import com.ae2.quickcalculation.compat.AE2FluidCraftCompat;
import com.ae2.quickcalculation.access.CraftingJobAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps AE2Enhanced's DAG planner from replacing the QCALC root for fluid
 * requests. AE2Enhanced remains responsible for pure-item and cycle plans.
 */
@Pseudo
@Mixin(targets = "com.github.aeddddd.ae2enhanced.craftingplan.dag.DagCraftingJob",
        remap = false)
public abstract class AE2EnhancedDagCraftingJobMixin {
    private static final int MAX_SCAN_NODES = 8192;

    @Shadow
    @Final
    protected World world;

    @Inject(method = "computeDagPlan", at = @At("HEAD"), cancellable = true,
            require = 0)
    private void ae2quickcalculation$useFluidCalculator(
            CallbackInfoReturnable<CraftingTreeNode> callback) {
        CraftingJob job = (CraftingJob) (Object) this;
        if (!AE2FluidCraftCompat.isAvailable()
                || !(job instanceof CraftingJobAccess)) {
            return;
        }

        CraftingJobAccess access = (CraftingJobAccess) job;
        ICraftingGrid craftingGrid = access.ae2quickcalculation$getCraftingGrid();
        IAEItemStack output = job.getOutput();
        if (craftingGrid == null || output == null
                || !containsFluidDependency(craftingGrid, output, this.world)) {
            return;
        }

        AE2QuickCalculation.LOGGER.info(
                "[QCALC][DIAG] AE2Enhanced DAG bypassed for AE2FC fluid dependency output={} job={}",
                AE2FluidCraftCompat.debugStack(output),
                Integer.toHexString(System.identityHashCode(job)));
        // DagCraftingJob.run() delegates to CraftingJob.run() when this is
        // null. The constructor mixin has already installed our root node.
        callback.setReturnValue(null);
    }

    private static boolean containsFluidDependency(ICraftingGrid grid,
                                                     IAEItemStack output,
                                                     World world) {
        ArrayDeque<IAEItemStack> pending = new ArrayDeque<IAEItemStack>();
        Set<IAEItemStack> visited = new HashSet<IAEItemStack>();
        IAEItemStack root = output.copy();
        root.reset();
        pending.add(root);

        while (!pending.isEmpty() && visited.size() < MAX_SCAN_NODES) {
            IAEItemStack key = pending.removeFirst();
            if (key == null || !visited.add(key)) {
                continue;
            }
            if (AE2FluidCraftCompat.isFluidFakeItem(key)) {
                return true;
            }

            Collection<ICraftingPatternDetails> patterns;
            try {
                patterns = grid.getCraftingFor(key, null, -1, world);
            } catch (Throwable ignored) {
                return false;
            }
            if (patterns == null) {
                continue;
            }

            for (ICraftingPatternDetails pattern : patterns) {
                if (pattern == null) {
                    continue;
                }
                IAEItemStack[] inputs = safeInputs(pattern);
                IAEItemStack[] condensedInputs = safeCondensedInputs(pattern);
                IAEItemStack[] outputs = safeOutputs(pattern);
                IAEItemStack[] condensedOutputs = safeCondensedOutputs(pattern);
                if (containsFluid(inputs) || containsFluid(condensedInputs)
                        || containsFluid(outputs) || containsFluid(condensedOutputs)) {
                    return true;
                }

                IAEItemStack[] nextInputs = condensedInputs;
                if (nextInputs == null || nextInputs.length == 0) {
                    nextInputs = inputs;
                }
                if (nextInputs == null) {
                    continue;
                }
                for (IAEItemStack input : nextInputs) {
                    if (input == null || input.getStackSize() <= 0L) {
                        continue;
                    }
                    IAEItemStack child = input.copy();
                    child.reset();
                    pending.add(child);
                }
            }
        }
        return false;
    }

    private static boolean containsFluid(IAEItemStack[] stacks) {
        if (stacks == null) {
            return false;
        }
        for (IAEItemStack stack : stacks) {
            if (AE2FluidCraftCompat.isFluidFakeItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private static IAEItemStack[] safeInputs(ICraftingPatternDetails pattern) {
        try {
            return pattern.getInputs();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IAEItemStack[] safeCondensedInputs(
            ICraftingPatternDetails pattern) {
        try {
            return pattern.getCondensedInputs();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IAEItemStack[] safeOutputs(ICraftingPatternDetails pattern) {
        try {
            return pattern.getOutputs();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IAEItemStack[] safeCondensedOutputs(
            ICraftingPatternDetails pattern) {
        try {
            return pattern.getCondensedOutputs();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
