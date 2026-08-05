package appeng.crafting;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.World;

/**
 * Drop-in replacement for AE2UEL's root CraftingTreeNode.
 *
 * Keeping this class in appeng.crafting is intentional: the original tree's
 * lifecycle methods are package-private, and CraftingJob/CraftingCPUCluster
 * must continue to call them through the normal AE2 path.
 */
public final class VMTreeNode extends CraftingTreeNode {
    private final ICraftingGrid craftingGrid;
    private final CraftingJob craftingJob;
    private final IAEItemStack requestedOutput;
    private final World world;
    private boolean nativeFallback;
    private CraftingVM.Result result;

    public VMTreeNode(ICraftingGrid craftingGrid,
                      CraftingJob craftingJob,
                      IAEItemStack requestedOutput,
                      World world,
                      IActionSource source) {
        super(craftingGrid, craftingJob, requestedOutput, null, -1, 0);
        this.craftingGrid = craftingGrid;
        this.craftingJob = craftingJob;
        this.requestedOutput = requestedOutput.copy();
        this.world = world;
        this.nativeFallback = isUnregisteredThirdParty(source);
    }

    @Override
    IAEItemStack request(MECraftingInventory inventory, long amount, IActionSource source)
            throws CraftBranchFailure, InterruptedException {
        if (nativeFallback) {
            return super.request(inventory, amount, source);
        }

        craftingJob.handlePausing();
        try {
            result = calculate(inventory, amount, source);
            if (result.hasMissingItems() && !craftingJob.isSimulation()) {
                throw new CraftBranchFailure(requestedOutput, amount);
            }
            return result.getOutput().copy();
        } catch (UnsupportedOperationException failure) {
            nativeFallback = true;
            result = null;
            AE2VMAddon.LOGGER.warn("AE2 VM falling back to native crafting for {}: {}",
                    requestedOutput, failure.getMessage());
            return super.request(inventory, amount, source);
        } catch (RuntimeException failure) {
            nativeFallback = true;
            result = null;
            AE2VMAddon.LOGGER.warn("AE2 VM calculation failed for {}, using native crafting",
                    requestedOutput, failure);
            return super.request(inventory, amount, source);
        }
    }

    @Override
    void dive(CraftingJob job) {
        if (nativeFallback) {
            super.dive(job);
            return;
        }
        if (result != null) {
            job.addBytes(result.getBytes());
        }
    }

    @Override
    void setSimulate() {
        if (nativeFallback) {
            super.setSimulate();
            return;
        }
        result = null;
    }

    @Override
    public void setJob(MECraftingInventory storage,
                       CraftingCPUCluster craftingCPUCluster,
                       IActionSource source) throws CraftBranchFailure {
        if (nativeFallback) {
            super.setJob(storage, craftingCPUCluster, source);
            return;
        }
        if (result == null || result.hasMissingItems()) {
            throw new CraftBranchFailure(requestedOutput, requestedOutput.getStackSize());
        }
        result.apply(storage, craftingCPUCluster, source);
    }

    @Override
    void getPlan(IItemList<IAEItemStack> plan) {
        if (nativeFallback) {
            super.getPlan(plan);
            return;
        }
        if (result != null) {
            result.populatePlan(plan);
        }
    }

    private CraftingVM.Result calculate(MECraftingInventory inventory,
                                        long amount,
                                        IActionSource source) {
        IAEItemStack key = requestedOutput.copy();
        key.reset();

        // CraftingTreeNode treats an emitter as authoritative, even when a
        // crafting pattern for the same key is also present.
        if (craftingGrid.canEmitFor(key)) {
            return CraftingVM.Result.direct(key, amount, true);
        }

        ICraftingPatternDetails selected = null;
        for (ICraftingPatternDetails candidate : craftingGrid.getCraftingFor(key, null, -1, world)) {
            IAEItemStack primary = candidate.getPrimaryOutput();
            if (primary != null && primary.isSameType(key)) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            return CraftingVM.Result.direct(key, amount, craftingGrid.canEmitFor(key));
        }

        if (!PatternCompiler.isSupported(selected)) {
            throw new UnsupportedOperationException("Pattern requires AE2 native slot handling: " + selected);
        }

        PatternCompiler.compileIfAbsent(selected);
        CraftingBytecode request = PatternCompiler.compileRequest(selected, amount);
        // Run against a private copy. The native fallback must see the exact
        // inventory state that CraftingJob supplied, even after a VM failure.
        MECraftingInventory vmInventory = new MECraftingInventory(inventory.getItemList());
        return new CraftingVM(craftingGrid, world).execute(request, vmInventory, source);
    }

    private static boolean isUnregisteredThirdParty(IActionSource source) {
        if (source == null || !source.machine().isPresent()) {
            return false;
        }
        return AE2VMCraftingRegistry.isUnregisteredThirdParty(
                source.machine().get().getClass().getName());
    }
}
