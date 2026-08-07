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
    private final String debugTag;
    private boolean nativeFallback;
    private long calculationStartNanos;
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
        this.debugTag = "job@" + Integer.toHexString(
                System.identityHashCode(craftingJob));
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] root node created output={} ae2fc={}",
                debugTag,
                AE2FluidCraftCompat.debugStack(this.requestedOutput),
                AE2FluidCraftCompat.isAvailable());
    }

    @Override
    IAEItemStack request(MECraftingInventory inventory, long amount, IActionSource source)
            throws CraftBranchFailure, InterruptedException {
        if (nativeFallback) {
            return super.request(inventory, amount, source);
        }

        craftingJob.handlePausing();
        try {
            startCalculationTimer();
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] request start amount={} simulation={} output={}",
                    debugTag,
                    amount,
                    craftingJob.isSimulation(),
                    AE2FluidCraftCompat.debugStack(requestedOutput));
            result = calculate(inventory, amount);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] result output={} missing={} used={} emitted={} patterns={} bytes={} cycle={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(result.getOutput()),
                    AE2FluidCraftCompat.debugList(result.getMissingItems(), false),
                    AE2FluidCraftCompat.debugList(result.getUsedItems(), false),
                    AE2FluidCraftCompat.debugList(result.getEmittedItems(), false),
                    result.getPatternCount(),
                    result.getBytes(),
                    result.isCycleOptimized());
            if (result.hasMissingItems() && !craftingJob.isSimulation()) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] non-simulation request rejected because missing={}",
                        debugTag,
                        AE2FluidCraftCompat.debugList(result.getMissingItems(), false));
                throw new CraftBranchFailure(requestedOutput, amount);
            }
            reportTerminalStatus(source, result.isCycleOptimized()
                    ? AE2QuickCalculation.TOAST_OPTIMIZED_CYCLE
                    : AE2QuickCalculation.TOAST_OPTIMIZED);
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
            reportTerminalStatus(source, AE2QuickCalculation.TOAST_QUANTITY_LIMIT);
            throw new CraftingCalculationFailure(requestedOutput, quantityLimitAmount);
        } catch (CraftingCalculator.CalculationFallbackException failure) {
            nativeFallback = true;
            result = null;
            AE2QuickCalculation.LOGGER.warn(
                    "Quick calculation falling back to native crafting for {} [{}]: {}",
                    requestedOutput, failure.getReason(), failure.getMessage());
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] fallback reason={} detail={}",
                    debugTag, failure.getReason(), failure.getMessage());
            reportTerminalStatus(source,
                    AE2QuickCalculation.statusFor(failure.getReason()));
            return super.request(inventory, amount, source);
        } catch (RuntimeException failure) {
            nativeFallback = true;
            result = null;
            AE2QuickCalculation.LOGGER.warn(
                    "Quick calculation failed for {}, using native crafting",
                    requestedOutput, failure);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] runtime failure type={} detail={}",
                    debugTag, failure.getClass().getName(), failure.getMessage());
            reportTerminalStatus(source, AE2QuickCalculation.TOAST_RUNTIME_FAILURE);
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
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] getPlan quantity-limit unresolved={}",
                    debugTag, AE2FluidCraftCompat.debugStack(unresolved));
            return;
        }
        if (result != null) {
            int before = countEntries(plan);
            result.populatePlan(plan);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] getPlan before={} after={} entries={}",
                    debugTag,
                    before,
                    countEntries(plan),
                    AE2FluidCraftCompat.debugList(plan, false));
        }
    }

    private CraftingCalculator.Result calculate(MECraftingInventory inventory,
                                                long amount) {
        IAEItemStack originalKey = requestedOutput.copy();
        IAEItemStack calculationKey = AE2FluidCraftCompat.normalizeFluidItem(
                originalKey);
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] root key raw={} normalized={}",
                debugTag,
                AE2FluidCraftCompat.debugStack(originalKey),
                AE2FluidCraftCompat.debugStack(calculationKey));
        if (calculationKey == null) {
            if (AE2FluidCraftCompat.isAvailable()) {
                throw CraftingCalculator.fallback(
                        CraftingCalculator.FallbackReason.UNSUPPORTED_PATTERN,
                        "AE2FC fluid item could not be normalized safely");
            }
            calculationKey = originalKey;
        }
        long amountHint = calculationKey.getStackSize();
        calculationKey.reset();
        IAEItemStack key = calculationKey.copy();

        ICraftingPatternDetails selected = null;
        Collection<ICraftingPatternDetails> candidates = getCraftingFor(
                calculationKey, amountHint);
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] pattern lookup key={} amountHint={} candidates={}",
                debugTag,
                AE2FluidCraftCompat.debugStack(calculationKey),
                amountHint,
                candidates.size());
        int candidateIndex = 0;
        for (ICraftingPatternDetails candidate : candidates) {
            IAEItemStack rawPrimary = candidate.getPrimaryOutput();
            IAEItemStack primary = AE2FluidCraftCompat.normalizeFluidItem(
                    rawPrimary);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] candidate #{} {} primaryRaw={} primaryNormalized={} inputs={} condensedInputs={} outputs={} condensedOutputs={}",
                    debugTag,
                    candidateIndex++,
                    candidate.getClass().getName(),
                    AE2FluidCraftCompat.debugStack(rawPrimary),
                    AE2FluidCraftCompat.debugStack(primary),
                    debugPatternInputs(candidate),
                    debugPatternCondensedInputs(candidate),
                    debugPatternOutputs(candidate),
                    debugPatternCondensedOutputs(candidate));
            if (rawPrimary != null && primary == null
                    && AE2FluidCraftCompat.isAvailable()) {
                throw CraftingCalculator.fallback(
                        CraftingCalculator.FallbackReason.UNSUPPORTED_PATTERN,
                        "AE2FC pattern output could not be normalized safely");
            }
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
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] no matching pattern; direct result key={} canEmit={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(key),
                    canEmitFor(key, calculationKey, amountHint));
            return CraftingCalculator.Result.direct(
                    key, amount, canEmitFor(key, calculationKey, amountHint));
        }

        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] selected pattern {} for key={}",
                debugTag,
                selected.getClass().getName(),
                AE2FluidCraftCompat.debugStack(key));

        // Use a private, current snapshot for the direct calculator. CraftingJob's
        // original list is created from NetworkMonitor#getStorageList(), which is
        // a cached item view and does not reliably contain AE2FC's virtual-fluid
        // entries. The native fallback still receives the original inventory above.
        IItemList<IAEItemStack> copiedItems = createCalculationSnapshot(inventory);
        MECraftingInventory calculationInventory =
                new MECraftingInventory(copiedItems);
        return new CraftingCalculator(craftingGrid, world, debugTag).calculate(
                selected, amount, calculationInventory);
    }

    private boolean canEmitFor(IAEItemStack originalKey,
                               IAEItemStack calculationKey,
                               long amountHint) {
        if (craftingGrid.canEmitFor(originalKey)
                || craftingGrid.canEmitFor(calculationKey)) {
            return true;
        }

        IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                calculationKey, amountHint);
        if (packet != null && craftingGrid.canEmitFor(packet)) {
            return true;
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

        if (AE2FluidCraftCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                    key, amountHint);
            if (packet != null && !packet.isSameType(key)) {
                addCraftingFor(result, packet);
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
        IItemList<IAEItemStack> bridgeItems = itemChannel.createList();
        IItemList<IAEFluidStack> fluidItems = null;
        boolean fluidChannelAuthoritative = false;

        if (grid != null) {
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid != null) {
                IMEMonitor<IAEItemStack> itemInventory =
                        storageGrid.getInventory(itemChannel);
                if (itemInventory != null) {
                    itemInventory.getAvailableItems(
                            new ItemListIgnoreCrafting<IAEItemStack>(bridgeItems));
                }

                if (AE2FluidCraftCompat.isAvailable()) {
                    IFluidStorageChannel fluidChannel = AEApi.instance()
                            .storage().getStorageChannel(IFluidStorageChannel.class);
                    IMEMonitor<IAEFluidStack> fluidInventory =
                            storageGrid.getInventory(fluidChannel);
                    if (fluidInventory != null) {
                        // The native fluid channel is authoritative. AE2FC's
                        // item-channel view is only a compatibility fallback;
                        // keeping both would allow one fluid to be counted
                        // twice or preserve a stale virtual entry.
                        fluidItems = fluidChannel.createList();
                        fluidInventory.getAvailableItems(
                                new ItemListIgnoreCrafting<IAEFluidStack>(fluidItems));
                        fluidChannelAuthoritative = true;
                    }
                }
            }
        }

        IItemList<IAEItemStack> copiedItems = itemChannel.createList();
        for (IAEItemStack item : bridgeItems) {
            if (!fluidChannelAuthoritative
                    || !AE2FluidCraftCompat.isFluidFakeItem(item)) {
                addSnapshotItem(copiedItems, item, true);
            }
        }
        // The job's original list is a useful fallback for ordinary items.
        // Do not use its cached virtual-fluid entries when the real fluid
        // monitor is available.
        for (IAEItemStack item : inventory.getItemList()) {
            if (!fluidChannelAuthoritative
                    || !AE2FluidCraftCompat.isFluidFakeItem(item)) {
                addSnapshotItem(copiedItems, item, false);
            }
        }

        if (fluidChannelAuthoritative && fluidItems != null) {
            for (IAEFluidStack fluid : fluidItems) {
                if (fluid == null || fluid.getStackSize() <= 0L) {
                    continue;
                }
                IAEItemStack fakeFluid = AE2FluidCraftCompat.packFluid(fluid);
                if (fakeFluid != null) {
                    addSnapshotItem(copiedItems, fakeFluid, true);
                }
            }
        } else {
            // Older AE2FC builds may expose only the virtual item monitor.
            // Use it only when no native fluid monitor can be read.
            for (IAEItemStack item : bridgeItems) {
                if (AE2FluidCraftCompat.isFluidFakeItem(item)) {
                    addSnapshotItem(copiedItems, item, true);
                }
            }
        }

        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] snapshot authoritativeFluid={} bridgeAll={} bridgeFluids={} jobFluids={} nativeFluids={} copiedFluids={} copiedAll={}",
                debugTag,
                fluidChannelAuthoritative,
                AE2FluidCraftCompat.debugList(bridgeItems, false),
                AE2FluidCraftCompat.debugList(bridgeItems, true),
                AE2FluidCraftCompat.debugList(inventory.getItemList(), true),
                AE2FluidCraftCompat.debugFluidList(fluidItems),
                AE2FluidCraftCompat.debugList(copiedItems, true),
                AE2FluidCraftCompat.debugList(copiedItems, false));
        return copiedItems;
    }

    private void addSnapshotItem(IItemList<IAEItemStack> copiedItems,
                                 IAEItemStack item,
                                 boolean replaceExisting) {
        IAEItemStack normalized = normalizeSnapshotItem(item);
        if (normalized == null || normalized.getStackSize() <= 0L
                || isRequestedOutput(normalized)) {
            if (AE2FluidCraftCompat.isFluidFakeItem(item)) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] snapshot fluid skipped raw={} normalized={} requestedOutput={}",
                        debugTag,
                        AE2FluidCraftCompat.debugStack(item),
                        AE2FluidCraftCompat.debugStack(normalized),
                        normalized != null && isRequestedOutput(normalized));
            }
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
        if (AE2FluidCraftCompat.isFluidFakeItem(item)
                || AE2FluidCraftCompat.isFluidFakeItem(normalized)) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] snapshot fluid accepted raw={} normalized={} replace={} existingBefore={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(item),
                    AE2FluidCraftCompat.debugStack(normalized),
                    replaceExisting,
                    AE2FluidCraftCompat.debugStack(existing));
        }
    }

    private IAEItemStack normalizeSnapshotItem(IAEItemStack item) {
        IAEItemStack normalized = AE2FluidCraftCompat.normalizeFluidItem(item);
        if (normalized == null && item != null
                && AE2FluidCraftCompat.isAvailable()
                && AE2FluidCraftCompat.isFluidFakeItem(item.getDefinition())) {
            throw CraftingCalculator.fallback(
                    CraftingCalculator.FallbackReason.UNSUPPORTED_PATTERN,
                    "AE2FC storage fluid could not be normalized safely");
        }
        return normalized;
    }

    private boolean isRequestedOutput(IAEItemStack item) {
        IAEItemStack output = AE2FluidCraftCompat.normalizeFluidItem(requestedOutput);
        return output != null && item != null && output.isSameType(item);
    }

    private void startCalculationTimer() {
        if (calculationStartNanos != 0L) {
            return;
        }
        calculationStartNanos = System.nanoTime();
    }

    private void reportTerminalStatus(IActionSource source, String message) {
        if (terminalStatusReported) {
            return;
        }
        terminalStatusReported = true;
        sendStatus(source, message, getElapsedMillis());
    }

    private long getElapsedMillis() {
        if (calculationStartNanos == 0L) {
            return 0L;
        }
        return Math.max(0L, (System.nanoTime() - calculationStartNanos) / 1000000L);
    }

    private void sendStatus(IActionSource source, String message, long elapsedMillis) {
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] status={} elapsedMs={} output={}",
                debugTag,
                message,
                elapsedMillis,
                AE2FluidCraftCompat.debugStack(requestedOutput));

        if (source == null) {
            return;
        }
        Optional<EntityPlayer> player = source.player();
        if (player.isPresent()) {
            AE2QuickCalculationNetwork.sendStatus(player.get(), message, elapsedMillis);
        }
    }

    private static int countEntries(IItemList<IAEItemStack> list) {
        int count = 0;
        if (list != null) {
            for (IAEItemStack ignored : list) {
                count++;
            }
        }
        return count;
    }

    private static String debugPatternInputs(ICraftingPatternDetails pattern) {
        try {
            return AE2FluidCraftCompat.debugArray(pattern.getInputs());
        } catch (Throwable failure) {
            return "<error:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String debugPatternCondensedInputs(ICraftingPatternDetails pattern) {
        try {
            return AE2FluidCraftCompat.debugArray(pattern.getCondensedInputs());
        } catch (Throwable failure) {
            return "<error:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String debugPatternOutputs(ICraftingPatternDetails pattern) {
        try {
            return AE2FluidCraftCompat.debugArray(pattern.getOutputs());
        } catch (Throwable failure) {
            return "<error:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String debugPatternCondensedOutputs(ICraftingPatternDetails pattern) {
        try {
            return AE2FluidCraftCompat.debugArray(pattern.getCondensedOutputs());
        } catch (Throwable failure) {
            return "<error:" + failure.getClass().getSimpleName() + ">";
        }
    }
}
