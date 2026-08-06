package appeng.crafting;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2.quickcalculation.calculator.CraftingCalculator;
import com.ae2.quickcalculation.AE2QuickCalculation;
import com.ae2.quickcalculation.compat.AE2FluidCraftCompat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import com.ae2.quickcalculation.network.AE2QuickCalculationNetwork;

import java.util.Optional;

/**
 * Drop-in replacement for AE2UEL's root CraftingTreeNode.
 *
 * Keeping this class in appeng.crafting is intentional: the original tree's
 * lifecycle methods are package-private, and CraftingJob/CraftingCPUCluster
 * must continue to call them through the normal AE2 path.
 */
public final class QuickCalculationTreeNode extends CraftingTreeNode {
    private final ICraftingGrid craftingGrid;
    private final CraftingJob craftingJob;
    private final IAEItemStack requestedOutput;
    private final World world;
    private final IGrid grid;
    private boolean nativeFallback;
    private boolean startStatusReported;
    private boolean terminalStatusReported;
    private boolean quantityLimitFailure;
    private long quantityLimitAmount;
    private CraftingCalculator.Result result;

    public QuickCalculationTreeNode(ICraftingGrid craftingGrid,
                                    CraftingJob craftingJob,
                                    IAEItemStack requestedOutput,
                                    World world,
                                    IActionSource source,
                                    IGrid grid) {
        super(craftingGrid, craftingJob, requestedOutput, null, -1, 0);
        this.craftingGrid = craftingGrid;
        this.craftingJob = craftingJob;
        this.requestedOutput = requestedOutput.copy();
        this.world = world;
        this.grid = grid;
    }

    @Override
    IAEItemStack request(MECraftingInventory inventory, long amount, IActionSource source)
            throws CraftBranchFailure, InterruptedException {
        if (nativeFallback) {
            return super.request(inventory, amount, source);
        }

        craftingJob.handlePausing();
        try {
            reportStartStatus(source);
            result = calculate(inventory, amount);
            if (result.hasMissingItems() && !craftingJob.isSimulation()) {
                throw new CraftBranchFailure(requestedOutput, amount);
            }
            reportTerminalStatus(source, result.isCycleOptimized()
                    ? AE2QuickCalculation.STATUS_OPTIMIZED_CYCLE
                    : AE2QuickCalculation.STATUS_OPTIMIZED);
            return result.getOutput().copy();
        } catch (CraftingCalculator.QuantityLimitException failure) {
            result = null;
            quantityLimitFailure = true;
            quantityLimitAmount = amount > 0L
                    ? amount
                    : Math.max(1L, requestedOutput.getStackSize());
            AE2QuickCalculation.LOGGER.warn(
                    "Quick calculation stopped for {}: {}",
                    requestedOutput, failure.getMessage());
            reportTerminalStatus(source, AE2QuickCalculation.STATUS_QUANTITY_LIMIT);
            throw new CraftingCalculationFailure(requestedOutput, quantityLimitAmount);
        } catch (CraftingCalculator.CalculationFallbackException failure) {
            nativeFallback = true;
            result = null;
            AE2QuickCalculation.LOGGER.warn(
                    "Quick calculation falling back to native crafting for {} [{}]: {}",
                    requestedOutput, failure.getReason(), failure.getMessage());
            reportTerminalStatus(source,
                    AE2QuickCalculation.statusFor(failure.getReason()));
            return super.request(inventory, amount, source);
        } catch (RuntimeException failure) {
            nativeFallback = true;
            result = null;
            AE2QuickCalculation.LOGGER.warn(
                    "Quick calculation failed for {}, using native crafting",
                    requestedOutput, failure);
            reportTerminalStatus(source, AE2QuickCalculation.STATUS_RUNTIME_FAILURE);
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
        quantityLimitFailure = false;
        quantityLimitAmount = 0L;
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
        if (quantityLimitFailure) {
            throw new CraftBranchFailure(requestedOutput, quantityLimitAmount);
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
        if (quantityLimitFailure) {
            IAEItemStack unresolved = requestedOutput.copy();
            unresolved.setStackSize(quantityLimitAmount > 0L
                    ? quantityLimitAmount
                    : Math.max(1L, requestedOutput.getStackSize()));
            // This is deliberately a missing entry, never a requestable one:
            // the 1.12.2 long-based CPU API cannot represent the calculation.
            plan.add(unresolved);
            return;
        }
        if (result != null) {
            result.populatePlan(plan);
        }
    }

    private CraftingCalculator.Result calculate(MECraftingInventory inventory,
                                                long amount) {
        IAEItemStack key = requestedOutput.copy();
        key.reset();

        // CraftingTreeNode treats an emitter as authoritative, even when a
        // crafting pattern for the same key is also present.
        if (craftingGrid.canEmitFor(key)) {
            return CraftingCalculator.Result.direct(key, amount, true);
        }

        ICraftingPatternDetails selected = null;
        for (ICraftingPatternDetails candidate : craftingGrid.getCraftingFor(
                key, null, -1, world)) {
            IAEItemStack primary = candidate.getPrimaryOutput();
            if (primary != null && primary.isSameType(key)) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            return CraftingCalculator.Result.direct(
                    key, amount, craftingGrid.canEmitFor(key));
        }

        // Use a private, current snapshot for the direct calculator. CraftingJob's
        // original list is created from NetworkMonitor#getStorageList(), which is
        // a cached item view and does not reliably contain AE2FC's virtual-fluid
        // entries. The native fallback still receives the original inventory above.
        IItemList<IAEItemStack> copiedItems = createCalculationSnapshot(inventory);
        MECraftingInventory calculationInventory =
                new MECraftingInventory(copiedItems);
        return new CraftingCalculator(craftingGrid, world).calculate(
                selected, amount, calculationInventory);
    }

    private IItemList<IAEItemStack> createCalculationSnapshot(
            MECraftingInventory inventory) {
        IItemStorageChannel itemChannel = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class);
        IItemList<IAEItemStack> sourceItems = itemChannel.createList();

        boolean readCurrentItems = false;
        if (grid != null) {
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid != null) {
                IMEMonitor<IAEItemStack> itemInventory =
                        storageGrid.getInventory(itemChannel);
                if (itemInventory != null) {
                    itemInventory.getAvailableItems(sourceItems);
                    readCurrentItems = true;
                }
            }
        }

        if (!readCurrentItems) {
            for (IAEItemStack item : inventory.getItemList()) {
                if (item != null && item.getStackSize() > 0L) {
                    sourceItems.add(item.copy());
                }
            }
        }

        IItemList<IAEItemStack> copiedItems = itemChannel.createList();
        for (IAEItemStack item : sourceItems) {
            if (item == null || item.getStackSize() <= 0L
                    || requestedOutput.isSameType(item)) {
                continue;
            }
            copiedItems.add(item.copy());
        }

        mergeFluidItems(copiedItems);
        return copiedItems;
    }

    private void mergeFluidItems(IItemList<IAEItemStack> copiedItems) {
        if (grid == null || !AE2FluidCraftCompat.isAvailable()) {
            return;
        }

        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        if (storageGrid == null) {
            return;
        }

        IItemStorageChannel itemChannel = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class);
        IItemList<IAEItemStack> available = itemChannel.createList();
        IMEMonitor<IAEItemStack> itemInventory = storageGrid.getInventory(itemChannel);
        if (itemInventory != null) {
            itemInventory.getAvailableItems(available);
            for (IAEItemStack item : available) {
                if (item == null || item.getStackSize() <= 0L
                        || !AE2FluidCraftCompat.isFluidFakeItem(item.getDefinition())
                        || requestedOutput.isSameType(item)) {
                    continue;
                }
                IAEItemStack existing = copiedItems.findPrecise(item);
                if (existing == null) {
                    copiedItems.add(item.copy());
                } else {
                    // getAvailableItems() is the authoritative view for AE2FC's
                    // virtual fluid entries. Do not retain a stale cached count.
                    existing.setStackSize(item.getStackSize());
                }
            }
        }

        // Read the real fluid channel as well. This is the authoritative source
        // for fluid amounts; the item-channel bridge is retained for compatibility
        // with AE2FC versions that expose only its virtual-item monitor there.
        IFluidStorageChannel fluidChannel = AEApi.instance().storage()
                .getStorageChannel(IFluidStorageChannel.class);
        IMEMonitor<IAEFluidStack> fluidInventory =
                storageGrid.getInventory(fluidChannel);
        if (fluidInventory == null) {
            return;
        }
        IItemList<IAEFluidStack> fluids = fluidChannel.createList();
        fluidInventory.getAvailableItems(fluids);
        for (IAEFluidStack fluid : fluids) {
            if (fluid == null || fluid.getStackSize() <= 0L) {
                continue;
            }
            IAEItemStack fakeFluid = AE2FluidCraftCompat.packFluid(fluid);
            if (fakeFluid == null || fakeFluid.getStackSize() <= 0L
                    || requestedOutput.isSameType(fakeFluid)) {
                continue;
            }
            IAEItemStack existing = copiedItems.findPrecise(fakeFluid);
            if (existing == null) {
                copiedItems.add(fakeFluid.copy());
            } else {
                existing.setStackSize(fakeFluid.getStackSize());
            }
        }
    }

    private void reportStartStatus(IActionSource source) {
        if (startStatusReported) {
            return;
        }
        startStatusReported = true;
        sendStatus(source, AE2QuickCalculation.STATUS_ACTIVE);
    }

    private void reportTerminalStatus(IActionSource source, String message) {
        if (terminalStatusReported) {
            return;
        }
        terminalStatusReported = true;
        sendStatus(source, message);
    }

    private void sendStatus(IActionSource source, String message) {
        AE2QuickCalculation.LOGGER.info(message + " for {}", requestedOutput);

        if (source == null) {
            return;
        }
        Optional<EntityPlayer> player = source.player();
        if (player.isPresent()) {
            AE2QuickCalculationNetwork.sendStatus(player.get(), message);
        }
    }
}
