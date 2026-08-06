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
import appeng.util.inv.ItemListIgnoreCrafting;
import com.ae2.quickcalculation.calculator.CraftingCalculator;
import com.ae2.quickcalculation.AE2QuickCalculation;
import com.ae2.quickcalculation.compat.AE2FluidCraftCompat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import com.ae2.quickcalculation.network.AE2QuickCalculationNetwork;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

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
        IAEItemStack originalKey = requestedOutput.copy();
        IAEItemStack calculationKey = AE2FluidCraftCompat.normalizeFluidItem(
                originalKey);
        if (calculationKey == null) {
            calculationKey = originalKey;
        }
        long amountHint = calculationKey.getStackSize();
        calculationKey.reset();
        IAEItemStack key = calculationKey.copy();

        ICraftingPatternDetails selected = null;
        for (ICraftingPatternDetails candidate : getCraftingFor(
                calculationKey, amountHint)) {
            IAEItemStack primary = AE2FluidCraftCompat.normalizeFluidItem(
                    candidate.getPrimaryOutput());
            if (primary != null && primary.isSameType(calculationKey)) {
                selected = candidate;
                break;
            }
        }

        // A level emitter can mark an output as emitable even when a crafting
        // pattern for the same key exists. The native tree treats that flag as
        // an unconditional shortcut, but doing so here drops every pattern
        // input from the plan. Prefer the actual pattern; use the external
        // emitter only when no matching pattern exists.
        if (selected == null) {
            return CraftingCalculator.Result.direct(
                    key, amount, canEmitFor(key, calculationKey, amountHint));
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

    private boolean canEmitFor(IAEItemStack originalKey,
                               IAEItemStack calculationKey,
                               long amountHint) {
        if (craftingGrid.canEmitFor(originalKey)
                || craftingGrid.canEmitFor(calculationKey)) {
            return true;
        }

        long[] queryAmounts = new long[]{amountHint, 1000L, 1L};
        for (long queryAmount : queryAmounts) {
            if (queryAmount <= 0L) {
                continue;
            }
            IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                    calculationKey, queryAmount);
            if (packet != null && craftingGrid.canEmitFor(packet)) {
                return true;
            }
        }
        return false;
    }

    private Collection<ICraftingPatternDetails> getCraftingFor(IAEItemStack key) {
        return getCraftingFor(key, 0L);
    }

    private Collection<ICraftingPatternDetails> getCraftingFor(IAEItemStack key,
                                                                long amountHint) {
        Set<ICraftingPatternDetails> result =
                new LinkedHashSet<ICraftingPatternDetails>();
        addCraftingFor(result, key);

        if (AE2FluidCraftCompat.isFluidFakeItem(
                key == null ? null : key.getDefinition())) {
            long[] queryAmounts = new long[]{amountHint, key.getStackSize(), 1000L, 1L};
            for (long queryAmount : queryAmounts) {
                if (queryAmount <= 0L) {
                    continue;
                }
                IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                        key, queryAmount);
                if (packet != null && !packet.isSameType(key)) {
                    addCraftingFor(result, packet);
                }
            }
        }
        return result;
    }

    private void addCraftingFor(Set<ICraftingPatternDetails> result,
                                IAEItemStack key) {
        if (key != null) {
            result.addAll(craftingGrid.getCraftingFor(key, null, -1, world));
        }
    }

    private IItemList<IAEItemStack> createCalculationSnapshot(
            MECraftingInventory inventory) {
        IItemStorageChannel itemChannel = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class);
        IItemList<IAEItemStack> sourceItems = itemChannel.createList();

        if (grid != null) {
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid != null) {
                IMEMonitor<IAEItemStack> itemInventory =
                        storageGrid.getInventory(itemChannel);
                if (itemInventory != null) {
                    // CraftingGridCache contributes craftable entries to
                    // getAvailableItems(). They are not network stock and
                    // must not satisfy a calculation input.
                    itemInventory.getAvailableItems(
                            new ItemListIgnoreCrafting<IAEItemStack>(sourceItems));
                }
            }
        }

        IItemList<IAEItemStack> copiedItems = itemChannel.createList();
        for (IAEItemStack item : sourceItems) {
            addSnapshotItem(copiedItems, item, true);
        }
        // CraftingJob's original list is still useful for ordinary items when
        // an integration supplies only a partial getAvailableItems() view.
        // Never add a duplicate here: the fresh network snapshot wins.
        for (IAEItemStack item : inventory.getItemList()) {
            addSnapshotItem(copiedItems, item, false);
        }

        mergeFluidItems(copiedItems);
        return copiedItems;
    }

    private void addSnapshotItem(IItemList<IAEItemStack> copiedItems,
                                 IAEItemStack item,
                                 boolean replaceExisting) {
        IAEItemStack normalized = AE2FluidCraftCompat.normalizeFluidItem(item);
        if (normalized == null || normalized.getStackSize() <= 0L
                || isRequestedOutput(normalized)) {
            return;
        }
        // The calculator's snapshot is a storage-only ledger. Status bits
        // from a monitor list must not turn a stored stack into a craftable or
        // requestable entry while it is copied into the private inventory.
        normalized.setCraftable(false);
        normalized.setCountRequestable(0L);
        IAEItemStack existing = copiedItems.findPrecise(normalized);
        if (existing == null) {
            copiedItems.add(normalized);
        } else if (replaceExisting) {
            existing.setStackSize(normalized.getStackSize());
        }
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
            itemInventory.getAvailableItems(
                    new ItemListIgnoreCrafting<IAEItemStack>(available));
            for (IAEItemStack item : available) {
                IAEItemStack normalized = AE2FluidCraftCompat.normalizeFluidItem(item);
                if (normalized == null || normalized.getStackSize() <= 0L
                        || !AE2FluidCraftCompat.isFluidFakeItem(
                        normalized.getDefinition())
                        || isRequestedOutput(normalized)) {
                    continue;
                }
                IAEItemStack existing = copiedItems.findPrecise(normalized);
                if (existing == null) {
                    copiedItems.add(normalized);
                } else {
                    // getAvailableItems() is the authoritative view for AE2FC's
                    // virtual fluid entries. Do not retain a stale cached count.
                    existing.setStackSize(normalized.getStackSize());
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
        fluidInventory.getAvailableItems(
                new ItemListIgnoreCrafting<IAEFluidStack>(fluids));
        for (IAEFluidStack fluid : fluids) {
            if (fluid == null || fluid.getStackSize() <= 0L) {
                continue;
            }
            IAEItemStack fakeFluid = AE2FluidCraftCompat.packFluid(fluid);
            if (fakeFluid == null || fakeFluid.getStackSize() <= 0L
                    || isRequestedOutput(fakeFluid)) {
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

    private boolean isRequestedOutput(IAEItemStack item) {
        IAEItemStack output = AE2FluidCraftCompat.normalizeFluidItem(requestedOutput);
        return output != null && item != null && output.isSameType(item);
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
