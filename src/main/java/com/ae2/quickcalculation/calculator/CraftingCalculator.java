package com.ae2.quickcalculation.calculator;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.MECraftingInventory;
import appeng.container.ContainerNull;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import com.ae2.quickcalculation.AE2QuickCalculation;
import com.ae2.quickcalculation.compat.AE2FluidCraftCompat;
import com.ae2.quickcalculation.compat.PackagedAutoCompat;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct crafting calculation for the compatible 1.12.2 pattern subset.
 *
 * The calculator deliberately works on pattern objects and quantities rather
 * than compiling an intermediate instruction stream. Internal results are
 * kept in a private stock ledger, so nested calculations do not perform an
 * inject/extract round trip through MECraftingInventory.
 */
public final class CraftingCalculator {
    private static final long BYTE_COST_PER_CRAFT = 8L;
    private static final long LONG_MAX = Long.MAX_VALUE;
    private static final int MAX_ALTERNATIVE_CYCLE_SCAN_NODES = 512;
    /*
     * NBT-backed durability has no Forge-wide maximum-damage contract. Keep
     * the fallback bounded, but make it logarithmic rather than walking every
     * durability point. Normal tools never reach this path.
     */
    private static final long MAX_NBT_DURABILITY_PROBE_USES = 1L << 20;
    private static final String[] NBT_DURABILITY_FIELD_NAMES = {
            "Dmg", "Damage", "damage", "Durability", "durability",
            "Wear", "wear"
    };

    private final ICraftingGrid grid;
    private final World world;
    private final String debugTag;

    private IItemList<IAEItemStack> availableItems;
    private IItemList<IAEItemStack> usedItems;
    private IItemList<IAEItemStack> missingItems;
    private IItemList<IAEItemStack> emittedItems;
    private IItemList<IAEItemStack> internalItems;
    private Map<ICraftingPatternDetails, Long> patternTimes;
    private Map<IAEItemStack, PatternChoice> patternCache;
    private Map<ICraftingPatternDetails, PatternInfo> patternInfoCache;
    private Set<IAEItemStack> activeKeys;
    private long bytes;

    public CraftingCalculator(ICraftingGrid grid, World world) {
        this(grid, world, "unbound");
    }

    public CraftingCalculator(ICraftingGrid grid, World world, String debugTag) {
        this.grid = grid;
        this.world = world;
        this.debugTag = debugTag == null ? "unbound" : debugTag;
    }

    public Result calculate(ICraftingPatternDetails rootPattern,
                            long requestedAmount,
                            MECraftingInventory inventory) {
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] calculator start pattern={} request={} rawInputs={} rawOutputs={}",
                debugTag,
                rootPattern == null ? "null" : rootPattern.getClass().getName(),
                requestedAmount,
                safeInputs(rootPattern),
                safeOutputs(rootPattern));
        if (!isSupported(rootPattern)) {
            throw unsupported(FallbackReason.UNSUPPORTED_PATTERN,
                    "Pattern requires AE2 native slot handling: " + rootPattern);
        }

        IAEItemStack output = normalizeForCalculation(
                rootPattern.getPrimaryOutput());
        if (output == null || output.getStackSize() <= 0) {
            throw unsupported(FallbackReason.INVALID_OUTPUT,
                    "Pattern has no positive primary output");
        }

        this.availableItems = inventory.getItemList();
        this.usedItems = newItemList();
        this.missingItems = newItemList();
        this.emittedItems = newItemList();
        this.internalItems = newItemList();
        this.patternTimes = new LinkedHashMap<ICraftingPatternDetails, Long>();
        this.patternCache = new HashMap<IAEItemStack, PatternChoice>();
        this.patternInfoCache = new HashMap<ICraftingPatternDetails, PatternInfo>();
        this.activeKeys = new HashSet<IAEItemStack>();
        this.bytes = 0L;

        long requested = Math.max(0L, requestedAmount);
        long crafts = divideRoundUp(requested, output.getStackSize());
        PatternInfo rootInfo = getPatternInfo(rootPattern);
        boolean cycleOptimized = tryCraftRootCycle(rootInfo, requested);
        if (!cycleOptimized) {
            craftPattern(rootInfo, crafts);
        }

        long resultAmount = requestedAmount > 0L
                ? requestedAmount
                : output.getStackSize();
        IAEItemStack finalOutput = output.copy();
        finalOutput.setStackSize(resultAmount);
        Result result = new Result(finalOutput, bytes, usedItems, missingItems,
                emittedItems, patternTimes, cycleOptimized, debugTag);
        AE2QuickCalculation.LOGGER.info(
                "[QCALC][{}] calculator complete output={} missing={} used={} emitted={} patterns={} bytes={} cycle={}",
                debugTag,
                AE2FluidCraftCompat.debugStack(result.getOutput()),
                AE2FluidCraftCompat.debugList(result.getMissingItems(), false),
                AE2FluidCraftCompat.debugList(result.getUsedItems(), false),
                AE2FluidCraftCompat.debugList(result.getEmittedItems(), false),
                result.getPatternCount(),
                result.getBytes(),
                result.isCycleOptimized());
        return result;
    }

    private void craftPattern(PatternInfo rootPattern, long crafts) {
        if (crafts <= 0L) {
            return;
        }
        Deque<PatternFrame> frames = new ArrayDeque<PatternFrame>();
        IAEItemStack rootKey = normalizeForCalculation(
                rootPattern.pattern.getPrimaryOutput());
        if (rootKey == null) {
            throw unsupported(FallbackReason.INVALID_OUTPUT,
                    "Pattern has no valid primary output");
        }
        rootKey.reset();
        activeKeys.add(rootKey);
        frames.push(new PatternFrame(rootPattern, crafts, rootKey));
        while (!frames.isEmpty()) {
            PatternFrame frame = frames.peek();

            if (frame.continuation != null) {
                InputContinuation continuation = frame.continuation;
                frame.continuation = null;
                if (continuation.durable) {
                    // The child request may have produced fresh tools. Consume
                    // them in one arithmetic pass; an unresolved remainder is
                    // already represented by missingItems.
                    consumeDurableContainer(
                            continuation.reusableInput, continuation.requiredUses);
                } else {
                    long acquired = extract(continuation.input, continuation.required);
                    if (continuation.returnContainer && acquired > 0L) {
                        if (continuation.deferContainerReturn) {
                            addPendingReturn(frame, continuation.container, acquired);
                        } else {
                            insertInternal(continuation.container, acquired);
                        }
                    }
                }
                continue;
            }

            if (!frame.started) {
                addPattern(frame.pattern.pattern, frame.crafts);
                frame.started = true;
            }

            if (frame.inputIndex < frame.pattern.inputs.length) {
                InputInfo input = frame.pattern.inputs[frame.inputIndex++];
                long totalRequired = checkedMultiply(frame.crafts, input.perCraft);
                if (input.durability != null) {
                    long missingUses = consumeDurableContainer(input, totalRequired);
                    if (missingUses > 0L) {
                        long freshItems = divideRoundUp(
                                missingUses,
                                input.durability.capacity(input.input));
                        frame.continuation = InputContinuation.durable(input, missingUses);
                        scheduleRequest(input.key, freshItems, input.perCraft, frames);
                    }
                } else if (input.container == null) {
                    long missing = acquireNormalInput(input, totalRequired);
                    if (missing > 0L) {
                        InputOption option = selectCraftingOption(input);
                        frame.continuation = InputContinuation.normal(
                                option.key, null, missing, false);
                        scheduleRequest(option.key, missing, input.perCraft, frames);
                    }
                } else if (!input.reusable) {
                    // A non-identical container is returned once for every
                    // consumed input. It is not a reusable catalyst and must
                    // never be reserved once per craft as a parallel copy.
                    long missing = acquireNormalInput(input, totalRequired);
                    long acquired = totalRequired - missing;
                    if (acquired > 0L) {
                        addPendingReturn(frame, input.container, acquired);
                    }
                    if (missing > 0L) {
                        InputOption option = selectCraftingOption(input);
                        frame.continuation = InputContinuation.returned(
                                option.key, input.container, missing);
                        scheduleRequest(option.key, missing, input.perCraft, frames);
                    }
                } else {
                    // Only simultaneous crafts need distinct reusable copies.
                    // Keeping the acquisition bounded prevents a large order
                    // from reserving one catalyst per craft.
                    long parallelTarget = Math.min(totalRequired,
                            saturatedMultiply(input.perCraft, reusableParallelism()));
                    long missingForParallelBatch = acquireNormalInput(
                            input, parallelTarget);
                    long acquiredForBatch = parallelTarget - missingForParallelBatch;
                    if (acquiredForBatch > 0L) {
                        insertInternal(input.container, acquiredForBatch);
                    }

                    // Keep enough copies for the useful parallel batch. The
                    // bound comes from the network CPUs instead of a fixed
                    // one-item or whole-order request.
                    if (missingForParallelBatch > 0L) {
                        InputOption option = selectCraftingOption(input);
                        PatternChoice choice = resolvePattern(option.key, input.perCraft);
                        long minimumCopies = Math.min(totalRequired, input.perCraft);
                        long requestAmount = choice.external || choice.pattern != null
                                ? missingForParallelBatch
                                : Math.max(0L, minimumCopies - acquiredForBatch);
                        if (requestAmount > 0L) {
                            frame.continuation = InputContinuation.normal(
                                    option.key, option.container,
                                    requestAmount, true);
                            scheduleRequest(option.key, requestAmount, input.perCraft, frames);
                        }
                    }
                }
                continue;
            }

            for (PendingReturn pending : frame.pendingReturns) {
                insertInternal(pending.stack, pending.amount);
            }
            frame.pendingReturns.clear();

            for (OutputInfo output : frame.pattern.outputs) {
                if (isPatternReturnedCatalyst(frame.pattern, output.output)) {
                    // This output is the reusable copy of an input. Its
                    // initial/resupplied copies are already represented in
                    // internalItems; adding the pattern output here would
                    // count the same catalyst twice in the private ledger.
                    continue;
                }
                insertInternal(output.output,
                        checkedMultiply(frame.crafts, output.amount));
            }
            frames.pop();
            activeKeys.remove(frame.outputKey);
        }
    }

    /**
     * Handles a root request that is part of a small, provably productive
     * cycle. The ordinary DAG path never performs this search unless the root
     * pattern is recursive or a bounded producer walk actually closes a loop.
     */
    private boolean tryCraftRootCycle(PatternInfo rootPattern, long requested) {
        if (requested <= 0L) {
            return false;
        }

        IAEItemStack rootKey = normalizeForCalculation(
                rootPattern.pattern.getPrimaryOutput());
        if (rootKey == null) {
            throw unsupported(FallbackReason.INVALID_OUTPUT,
                    "Pattern has no valid primary output");
        }
        rootKey.reset();
        List<CycleStepInfo> candidate = findRootCycle(rootPattern, rootKey);
        if (candidate == null) {
            return false;
        }

        try {
            CyclePlan plan = buildCyclePlan(candidate, rootKey);
            executeCycle(plan, rootKey, requested);
            return true;
        } catch (CalculationFallbackException failure) {
            // A neutral/dissipative cycle, or a productive cycle without a
            // seed, is still safe to traverse as an ordinary dependency graph.
            // The active-key guard will stop at the first cycle boundary and
            // report that boundary as a missing material.
            if (isOrdinaryCycleBoundary(failure.getReason())) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] root cycle downgraded to ordinary dependency traversal reason={}",
                        debugTag,
                        failure.getReason());
                return false;
            }
            throw failure;
        }
    }

    private static boolean isOrdinaryCycleBoundary(FallbackReason reason) {
        return reason == FallbackReason.CYCLE_NO_SEED
                || reason == FallbackReason.CYCLE_NEUTRAL
                || reason == FallbackReason.CYCLE_DISSIPATIVE;
    }

    private List<CycleStepInfo> findRootCycle(PatternInfo rootPattern,
                                               IAEItemStack rootKey) {
        for (InputInfo input : rootPattern.inputs) {
            if (sameKey(input.key, rootKey)) {
                List<CycleStepInfo> self = new ArrayList<CycleStepInfo>();
                self.add(new CycleStepInfo(rootPattern, rootKey, rootKey,
                        input.perCraft,
                        outputAmount(rootPattern, rootKey)));
                return self;
            }
        }

        Set<IAEItemStack> keys = new LinkedHashSet<IAEItemStack>();
        keys.add(rootKey);
        for (InputInfo input : rootPattern.inputs) {
            if (input == null || input.perCraft <= 0L
                    || sameKey(input.key, rootKey)) {
                continue;
            }
            CycleStepInfo first = new CycleStepInfo(
                    rootPattern, input.key, rootKey, input.perCraft,
                    outputAmount(rootPattern, rootKey));
            List<CycleStepInfo> path = new ArrayList<CycleStepInfo>();
            path.add(first);
            Set<IAEItemStack> pathKeys = new LinkedHashSet<IAEItemStack>(keys);
            pathKeys.add(input.key);
            List<CycleStepInfo> found = findCycleBackToRoot(
                    rootKey, input.key, path, pathKeys, new int[]{256});
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<CycleStepInfo> findCycleBackToRoot(IAEItemStack rootKey,
                                                    IAEItemStack currentKey,
                                                    List<CycleStepInfo> path,
                                                    Set<IAEItemStack> pathKeys,
                                                    int[] budget) {
        if (budget[0]-- <= 0) {
            return null;
        }

        for (ICraftingPatternDetails candidate : getCraftingFor(currentKey)) {
            if (!isSupported(candidate)) {
                continue;
            }
            PatternInfo info = getPatternInfo(candidate);
            long currentOutput = outputAmount(info, currentKey);
            if (currentOutput <= 0L) {
                continue;
            }

            for (InputInfo input : info.inputs) {
                if (input == null || input.perCraft <= 0L
                        || sameKey(input.key, currentKey)) {
                    continue;
                }
                CycleStepInfo step = new CycleStepInfo(
                        info, input.key, currentKey, input.perCraft,
                        currentOutput);
                path.add(step);
                if (sameKey(input.key, rootKey)) {
                    List<CycleStepInfo> result = new ArrayList<CycleStepInfo>(path);
                    Collections.reverse(result);
                    return result;
                }
                if (pathKeys.add(input.key)) {
                    List<CycleStepInfo> result = findCycleBackToRoot(
                            rootKey, input.key, path, pathKeys, budget);
                    if (result != null) {
                        return result;
                    }
                    pathKeys.remove(input.key);
                }
                path.remove(path.size() - 1);
            }
        }
        return null;
    }

    private CyclePlan buildCyclePlan(List<CycleStepInfo> candidate,
                                     IAEItemStack rootKey) {
        if (candidate == null || candidate.isEmpty() || candidate.size() > 32) {
            throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                    "Cycle is empty or exceeds the supported cycle length");
        }

        List<CycleStepInfo> steps = rotateCycleToRoot(candidate, rootKey);
        Set<IAEItemStack> cycleKeys = new LinkedHashSet<IAEItemStack>();
        Set<ICraftingPatternDetails> patterns = new HashSet<ICraftingPatternDetails>();
        for (CycleStepInfo step : steps) {
            cycleKeys.add(step.fromKey);
            cycleKeys.add(step.toKey);
            if (!patterns.add(step.pattern.pattern)) {
                throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                        "A cycle reuses the same crafting pattern more than once");
            }
        }

        for (int index = 0; index < steps.size(); index++) {
            CycleStepInfo step = steps.get(index);
            CycleStepInfo next = steps.get((index + 1) % steps.size());
            if (!sameKey(step.toKey, next.fromKey)) {
                throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                        "Cycle keys are not connected");
            }

            int cycleInputs = 0;
            for (InputInfo input : step.pattern.inputs) {
                if (input.container != null || input.durability != null) {
                    throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                            "Cycle contains a reusable or damageable input");
                }
                if (containsKey(cycleKeys, input.key)) {
                    cycleInputs++;
                    if (!sameKey(input.key, step.fromKey)
                            || step.pattern.pattern.canSubstitute()) {
                        throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                                "Cycle input has ambiguous substitution semantics");
                    }
                }
            }

            int cycleOutputs = 0;
            for (OutputInfo output : step.pattern.outputs) {
                if (containsKey(cycleKeys, output.output)) {
                    cycleOutputs++;
                    if (!sameKey(output.output, step.toKey)) {
                        throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                                "Cycle pattern emits more than one cycle key");
                    }
                }
            }
            if (cycleInputs != 1 || cycleOutputs != 1) {
                throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                        "Cycle pattern must have exactly one cycle input and output");
            }
        }

        Fraction[] ratios = new Fraction[steps.size()];
        ratios[0] = Fraction.ONE;
        BigInteger scale = BigInteger.ONE;
        for (int index = 1; index < steps.size(); index++) {
            CycleStepInfo previous = steps.get(index - 1);
            CycleStepInfo current = steps.get(index);
            ratios[index] = ratios[index - 1]
                    .multiply(previous.outputAmount)
                    .divide(current.inputAmount);
            scale = lcm(scale, ratios[index].denominator);
        }

        BigInteger[] times = new BigInteger[steps.size()];
        BigInteger common = BigInteger.ZERO;
        for (int index = 0; index < ratios.length; index++) {
            times[index] = ratios[index].numerator
                    .multiply(scale)
                    .divide(ratios[index].denominator);
            common = common.equals(BigInteger.ZERO)
                    ? times[index]
                    : common.gcd(times[index]);
        }
        if (common.signum() <= 0) {
            throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                    "Cycle has no positive integer execution ratio");
        }
        for (int index = 0; index < times.length; index++) {
            times[index] = times[index].divide(common);
        }

        CycleStepInfo first = steps.get(0);
        CycleStepInfo last = steps.get(steps.size() - 1);
        BigInteger netGain = times[times.length - 1]
                .multiply(BigInteger.valueOf(last.outputAmount))
                .subtract(times[0].multiply(BigInteger.valueOf(first.inputAmount)));
        if (netGain.signum() == 0) {
            throw unsupported(FallbackReason.CYCLE_NEUTRAL,
                    "Cycle has no net output gain");
        }
        if (netGain.signum() < 0) {
            throw unsupported(FallbackReason.CYCLE_DISSIPATIVE,
                    "Cycle consumes more of its own material than it produces");
        }

        return new CyclePlan(steps, cycleKeys,
                toLong(times[0].multiply(BigInteger.valueOf(first.inputAmount)),
                        "cycle seed"),
                toLong(netGain, "cycle net gain"),
                toLongArray(times, "cycle execution ratio"));
    }

    private void executeCycle(CyclePlan plan, IAEItemStack rootKey, long requested) {
        long rounds = divideRoundUp(requested, plan.netGain);
        long[] totalTimes = new long[plan.timesPerRound.length];
        for (int index = 0; index < totalTimes.length; index++) {
            totalTimes[index] = checkedMultiply(rounds, plan.timesPerRound[index]);
        }

        if (amountOf(rootKey) < plan.seed) {
            throw unsupported(FallbackReason.CYCLE_NO_SEED,
                    "Cycle requires a seed of " + plan.seed + " for " + rootKey);
        }

        Map<IAEItemStack, CycleNeed> needs =
                new LinkedHashMap<IAEItemStack, CycleNeed>();
        for (int index = 0; index < plan.steps.size(); index++) {
            CycleStepInfo step = plan.steps.get(index);
            for (InputInfo input : step.pattern.inputs) {
                if (containsKey(plan.cycleKeys, input.key)) {
                    continue;
                }
                long required = checkedMultiply(totalTimes[index], input.perCraft);
                if (required <= 0L) {
                    continue;
                }
                CycleNeed previous = needs.get(input.key);
                if (previous == null) {
                    needs.put(input.key, new CycleNeed(input, required));
                } else {
                    previous.required = checkedAdd(previous.required, required);
                }
            }
        }

        extractRequiredSeed(rootKey, plan.seed);
        for (CycleNeed need : needs.values()) {
            satisfyCycleNeed(need);
        }
        for (int index = 0; index < totalTimes.length; index++) {
            addPattern(plan.steps.get(index).pattern.pattern, totalTimes[index]);
        }

        long netOutput = checkedMultiply(rounds, plan.netGain);
        insertInternal(rootKey, checkedAdd(plan.seed, netOutput));
    }

    private void extractRequiredSeed(IAEItemStack key, long required) {
        long acquired = extract(key, required);
        if (acquired != required) {
            throw unsupported(FallbackReason.CYCLE_NO_SEED,
                    "Cycle seed became unavailable for " + key);
        }
    }

    private void satisfyCycleNeed(CycleNeed need) {
        long missing = acquireNormalInput(need.input, need.required);
        if (missing <= 0L) {
            return;
        }

        InputOption option = selectCraftingOption(need.input);
        PatternChoice choice = resolvePattern(option.key, need.input.perCraft);
        if (choice.external) {
            provideExternal(option.key, missing);
        } else if (choice.pattern == null) {
            addCount(missingItems, option.key, missing);
            addBytes(missing);
            return;
        } else {
            throw unsupported(FallbackReason.CYCLE_EXTERNAL_RECURSION,
                    "Cycle external input requires recursive crafting: " + option.key);
        }

        long acquired = acquireNormalInput(need.input, missing);
        if (acquired != missing) {
            throw unsupported(FallbackReason.CYCLE_EXTERNAL_RECURSION,
                    "Cycle external input could not be supplied: " + option.key);
        }
    }

    private long amountOf(IAEItemStack key) {
        long amount = 0L;
        IAEItemStack internal = internalItems.findPrecise(key);
        if (internal != null) {
            amount = checkedAdd(amount, Math.max(0L, internal.getStackSize()));
        }
        IAEItemStack available = availableItems.findPrecise(key);
        if (available != null) {
            amount = checkedAdd(amount, Math.max(0L, available.getStackSize()));
        }
        return amount;
    }

    private static List<CycleStepInfo> rotateCycleToRoot(List<CycleStepInfo> steps,
                                                          IAEItemStack rootKey) {
        for (int start = 0; start < steps.size(); start++) {
            if (!sameKey(steps.get(start).fromKey, rootKey)) {
                continue;
            }
            List<CycleStepInfo> rotated = new ArrayList<CycleStepInfo>(steps.size());
            for (int offset = 0; offset < steps.size(); offset++) {
                rotated.add(steps.get((start + offset) % steps.size()));
            }
            return rotated;
        }
        throw unsupported(FallbackReason.CYCLE_TOO_COMPLEX,
                "Cycle does not contain the requested output as its boundary");
    }

    private static long outputAmount(PatternInfo pattern, IAEItemStack key) {
        long amount = 0L;
        for (OutputInfo output : pattern.outputs) {
            if (sameKey(output.output, key)) {
                amount = checkedAdd(amount, output.amount);
            }
        }
        return amount;
    }

    private static boolean isPatternReturnedCatalyst(PatternInfo pattern,
                                                       IAEItemStack output) {
        for (InputInfo input : pattern.inputs) {
            if (input.returnedByPattern
                    && (sameKey(input.key, output)
                    || (input.durability != null
                    && input.durability.matchesReturned(input.input, output)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsKey(Collection<IAEItemStack> keys,
                                       IAEItemStack key) {
        for (IAEItemStack candidate : keys) {
            if (sameKey(candidate, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameKey(IAEItemStack left, IAEItemStack right) {
        return left != null && right != null && left.isSameType(right);
    }

    private static BigInteger lcm(BigInteger left, BigInteger right) {
        if (left.signum() == 0 || right.signum() == 0) {
            return BigInteger.ZERO;
        }
        return left.divide(left.gcd(right)).multiply(right).abs();
    }

    private static long toLong(BigInteger value, String what) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new QuantityLimitException(
                    "" + what + " exceeds the 1.12.2 long stack limit");
        }
    }

    private static long[] toLongArray(BigInteger[] values, String what) {
        long[] result = new long[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = toLong(values[index], what);
        }
        return result;
    }

    private long acquireNormalInput(InputInfo input, long required) {
        long remaining = required;
        for (InputOption option : input.options) {
            if (remaining <= 0L) {
                break;
            }
            long acquired = extract(option.key, remaining);
            remaining -= acquired;
        }
        return remaining;
    }

    private InputOption selectCraftingOption(InputInfo input) {
        for (InputOption option : input.options) {
            PatternChoice choice = resolvePattern(option.key, input.perCraft);
            if (choice.external || choice.pattern != null) {
                return option;
            }
        }

        // Keep missing-item reporting tied to the encoded input when no
        // substitute can actually be supplied or crafted. scheduleRequest()
        // records that missing quantity exactly once.
        return input.options[0];
    }

    private long reusableParallelism() {
        long parallelism = 1L;
        if (grid == null || grid.getCpus() == null) {
            return parallelism;
        }
        for (ICraftingCPU cpu : grid.getCpus()) {
            if (cpu == null) {
                continue;
            }
            parallelism = Math.max(parallelism,
                    1L + Math.max(0, cpu.getCoProcessors()));
        }
        return parallelism;
    }

    /**
     * Consumes a damageable reusable input without simulating one craft per
     * durability point. The returned value is the number of uses that could
     * not be covered by the current internal/network stock.
     */
    private long consumeDurableContainer(InputInfo input, long requiredUses) {
        if (requiredUses <= 0L) {
            return 0L;
        }

        long remaining = requiredUses;
        remaining = consumeDurableFrom(internalItems, input, remaining, false);
        return consumeDurableFrom(availableItems, input, remaining, true);
    }

    private long consumeDurableFrom(IItemList<IAEItemStack> source,
                                    InputInfo input,
                                    long requiredUses,
                                    boolean fromNetwork) {
        if (requiredUses <= 0L) {
            return 0L;
        }

        Collection<IAEItemStack> fuzzyCandidates = source.findFuzzy(
                input.input, FuzzyMode.IGNORE_ALL);
        List<IAEItemStack> candidates = findDurableCandidates(
                fuzzyCandidates, input);

        // Some AE2 1.12 item indexes only classify vanilla damageable items as
        // fuzzy. NBT-backed tools can therefore be present in the storage list
        // while findFuzzy() returns nothing. The fallback is used only when the
        // indexed query produced no usable candidate, keeping the normal path
        // fast and making the NBT behavior independent of a mod-specific item
        // interface.
        if (candidates.isEmpty()) {
            candidates = findDurableCandidates(source, input);
        }

        if (candidates.isEmpty()) {
            return requiredUses;
        }

        if (candidates.size() == 1) {
            return consumeDurableCandidate(input, candidates.get(0),
                    requiredUses, fromNetwork);
        }

        // Prefer the least damaged copies. This minimizes the number of fresh
        // tools requested when the network contains mixed durability states.
        Collections.sort(candidates, new Comparator<IAEItemStack>() {
            @Override
            public int compare(IAEItemStack left, IAEItemStack right) {
                return Long.compare(input.durability.currentDamage(left),
                        input.durability.currentDamage(right));
            }
        });

        long remaining = requiredUses;
        for (IAEItemStack candidate : candidates) {
            if (remaining <= 0L) {
                break;
            }
            remaining = consumeDurableCandidate(input, candidate, remaining, fromNetwork);
        }

        return remaining;
    }

    private List<IAEItemStack> findDurableCandidates(
            Iterable<IAEItemStack> source,
            InputInfo input) {
        List<IAEItemStack> candidates = new ArrayList<IAEItemStack>();
        if (source == null) {
            return candidates;
        }
        for (IAEItemStack candidate : source) {
            if (isValidDurableCandidate(input, candidate)
                    && input.durability.capacity(candidate) > 0L) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private long consumeDurableCandidate(InputInfo input,
                                         IAEItemStack candidate,
                                         long remaining,
                                         boolean fromNetwork) {
        if (remaining <= 0L) {
            return 0L;
        }

        long count = Math.max(0L, candidate.getStackSize());
        long capacity = input.durability.capacity(candidate);
        if (count <= 0L || capacity <= 0L) {
            return remaining;
        }

        long availableUses = saturatedMultiply(count, capacity);
        long usedUses = Math.min(remaining, availableUses);
        long exhaustedCopies = usedUses / capacity;
        long partialUses = usedUses % capacity;
        long usedCopies = exhaustedCopies + (partialUses > 0L ? 1L : 0L);

        if (usedCopies <= 0L || usedCopies > count) {
            throw unsupported(FallbackReason.DAMAGEABLE_ALLOCATION,
                    "Damageable container allocation overflow for " + input.input);
        }

        if (fromNetwork) {
            addCount(usedItems, candidate, usedCopies);
        }
        IAEItemStack originalCandidate = candidate.copy().setStackSize(1L);
        candidate.setStackSize(count - usedCopies);

        if (partialUses > 0L) {
            IAEItemStack returned = createDurableContainer(
                    input.durability, originalCandidate, partialUses);
            if (returned == null) {
                throw unsupported(FallbackReason.NON_LINEAR_CONTAINER,
                        "Damageable container has a non-linear durability transition: "
                                + input.input);
            }
            insertInternal(returned, 1L);
        }

        addBytes(usedUses);
        return remaining - usedUses;
    }

    private boolean isValidDurableCandidate(InputInfo input, IAEItemStack candidate) {
        if (candidate == null || candidate.getItem() != input.input.getItem()) {
            return false;
        }

        ItemStack expected = input.input.copy().setStackSize(1).createItemStack();
        ItemStack actual = candidate.copy().setStackSize(1).createItemStack();
        if (expected == null || actual == null
                || expected.getItemDamage() != actual.getItemDamage()) {
            return false;
        }

        if (input.durability.isNbt()) {
            if (!sameNbtExceptPath(expected.getTagCompound(),
                    actual.getTagCompound(), input.durability.nbtPath)) {
                return false;
            }
        } else if (!ItemStack.areItemStackTagsEqual(expected, actual)) {
            return false;
        }

        if (input.durability.currentDamage(candidate) < 0L) {
            return false;
        }

        if (input.pattern.isCraftable() && input.slot >= 0) {
            return input.pattern.isValidItemForSlot(input.slot, actual, world);
        }
        return true;
    }

    private static IAEItemStack createDurableContainer(DurabilityInfo durability,
                                                        IAEItemStack current,
                                                        long uses) {
        if (uses <= 0L) {
            return null;
        }

        long currentDamage = durability.currentDamage(current);
        if (currentDamage < 0L || durability.damageStep <= 0L
                || uses > (LONG_MAX - currentDamage) / durability.damageStep) {
            return null;
        }
        long targetDamageLong = currentDamage
                + uses * durability.damageStep;
        if (targetDamageLong >= durability.maxDamage) {
            return null;
        }

        ItemStack currentStack = current.copy().setStackSize(1).createItemStack();
        if (currentStack == null || currentStack.isEmpty()) {
            return null;
        }

        if (durability.isNbt()) {
            ItemStack returned = currentStack.copy();
            if (!writeNbtDurability(returned, durability,
                    targetDamageLong)) {
                return null;
            }
            returned.setCount(1);
            return AEItemStack.fromItemStack(returned);
        }

        ItemStack first = Platform.getContainerItem(currentStack.copy());
        if (first == null || first.isEmpty()
                || first.getItem() != currentStack.getItem()
                || !ItemStack.areItemStackTagsEqual(currentStack, first)
                || first.getItemDamage() != currentDamage + durability.damageStep) {
            return null;
        }

        ItemStack returned = first.copy();
        returned.setCount(1);
        returned.setItemDamage((int) targetDamageLong);
        return AEItemStack.fromItemStack(returned);
    }

    private void scheduleRequest(IAEItemStack normalizedKey,
                                 long required,
                                 Deque<PatternFrame> frames) {
        scheduleRequest(normalizedKey, required, 0L, frames);
    }

    private void scheduleRequest(IAEItemStack normalizedKey,
                                 long required,
                                 long amountHint,
                                 Deque<PatternFrame> frames) {
        if (required <= 0L) {
            return;
        }

        PatternChoice choice = resolvePattern(normalizedKey, amountHint);
        if (choice.external) {
            provideExternal(normalizedKey, required);
            return;
        }
        if (choice.pattern == null) {
            addCount(missingItems, normalizedKey, required);
            addBytes(required);
            if (isFluidKey(normalizedKey)) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] fluid missing key={} required={} amountHint={}",
                        debugTag,
                        AE2FluidCraftCompat.debugStack(normalizedKey),
                        required,
                        amountHint);
            }
            return;
        }

        long crafts = divideRoundUp(required, choice.outputAmount);
        IAEItemStack childKey = normalizedKey.copy();
        if (!activeKeys.add(childKey)) {
            // This is the final guard for a cycle that was not rejected during
            // candidate selection. Treat the edge as an external boundary so
            // the plan asks for the material that entered the cycle instead of
            // recursing forever or falling back to AE2's recursive walker.
            addCount(missingItems, normalizedKey, required);
            addBytes(required);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] cycle boundary recorded as missing key={} required={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(normalizedKey),
                    required);
            return;
        }
        frames.push(new PatternFrame(getPatternInfo(choice.pattern), crafts, childKey));
    }

    private PatternInfo getPatternInfo(ICraftingPatternDetails pattern) {
        PatternInfo cached = patternInfoCache.get(pattern);
        if (cached != null) {
            return cached;
        }
        if (!isSupported(pattern)) {
            throw unsupported(FallbackReason.UNSUPPORTED_PATTERN,
                    "Pattern requires AE2 native slot handling: " + pattern);
        }

        List<OutputInfo> outputs = new ArrayList<OutputInfo>();
        IAEItemStack[] patternOutputs = getCalculationOutputs(pattern);
        IAEItemStack[] rawInputs = safeInputs(pattern);
        IAEItemStack[] rawCondensedInputs = safeCondensedInputs(pattern);
        IAEItemStack[] rawOutputs = safeOutputs(pattern);
        IAEItemStack[] rawCondensedOutputs = safeCondensedOutputs(pattern);
        if (containsFluid(rawInputs) || containsFluid(rawCondensedInputs)
                || containsFluid(rawOutputs) || containsFluid(rawCondensedOutputs)) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] fluid pattern info type={} rawInputs={} rawCondensedInputs={} calcInputs={} rawOutputs={} rawCondensedOutputs={} calcOutputs={}",
                    debugTag,
                    pattern.getClass().getName(),
                    safeArray(rawInputs),
                    safeArray(rawCondensedInputs),
                    safeArray(getCalculationInputs(pattern)),
                    safeArray(rawOutputs),
                    safeArray(rawCondensedOutputs),
                    safeArray(patternOutputs));
        }
        if (patternOutputs != null) {
            for (IAEItemStack output : patternOutputs) {
                if (output != null && output.getStackSize() > 0L) {
                    outputs.add(new OutputInfo(output, output.getStackSize()));
                }
            }
        }

        boolean packageConsumesInputs = PackagedAutoCompat.consumesAllInputs(pattern);
        boolean implicitCraftingRemainders =
                usesImplicitCraftingRemainders(pattern);
        if (packageConsumesInputs) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] applying PackagedAuto package input semantics pattern={}",
                    debugTag, pattern.getClass().getName());
        }

        List<InputInfo> inputs = new ArrayList<InputInfo>();
        IAEItemStack[] condensedInputs = getCalculationInputs(pattern);
        if (condensedInputs == null || condensedInputs.length == 0) {
            throw unsupported(FallbackReason.UNSUPPORTED_PATTERN,
                    "Pattern has no usable inputs: " + pattern);
        }
        for (IAEItemStack input : condensedInputs) {
            if (input == null || input.getStackSize() <= 0L) {
                continue;
            }
            // Processing patterns hand their encoded inputs to an external
            // machine. AE2 does not apply Item#getContainerItem() on that
            // path; only an explicit pattern output can return a catalyst or
            // a worn tool. Vanilla crafting patterns use recipe remainders.
            ContainerInfo container = implicitCraftingRemainders
                    ? inspectContainer(input) : null;
            boolean returnedByPattern = false;
            int[] slots = findInputSlots(pattern, input);

            // Crafting patterns carry the recipe's remaining-item semantics,
            // while their condensed output list intentionally contains only
            // the requested result. Read the recipe remainder before looking
            // at pattern outputs so a tool that is returned in a damaged NBT
            // state is not mistaken for a consumed input.
            if (implicitCraftingRemainders
                    && (container == null || container.durability == null)
                    && shouldProbeRecipeContainer(input)) {
                ContainerInfo recipeContainer = inspectRecipeContainer(
                        pattern, input, slots);
                if (recipeContainer != null) {
                    container = recipeContainer;
                    returnedByPattern = true;
                }
            }

            if (!packageConsumesInputs) {
                IAEItemStack returned = findExactReturnedInput(
                        patternOutputs, input);
                if (returned == null) {
                    // Some third-party pattern details expose the clean output
                    // view more accurately than their slot-preserving view.
                    returned = findExactReturnedInput(
                            getCalculationOutputsFromCondensed(pattern), input);
                }
                if (returned != null && (container == null
                        || (container.durability == null
                        && sameReusableContainer(input, container.stack)))) {
                    if (container == null) {
                        container = new ContainerInfo(returned, null);
                    }
                    returnedByPattern = true;
                }
            }

            // Some tools encode their wear in a numeric NBT field instead of
            // ItemStack damage. Treat the unique, provable input -> output
            // transition as the returned durable container.
            if (!packageConsumesInputs
                    && (container == null || container.durability == null)) {
                ContainerInfo nbtContainer = findPatternDurabilityContainer(
                        input, patternOutputs);
                if (nbtContainer == null) {
                    nbtContainer = findPatternDurabilityContainer(input,
                            getCalculationOutputsFromCondensed(pattern));
                }
                if (nbtContainer != null) {
                    container = nbtContainer;
                    returnedByPattern = true;
                }
            }
            DurabilityInfo durability = container == null ? null : container.durability;
            if (durability != null && durability.isNbt()) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] NBT durability transition input={} returned={} path={} step={} max={}",
                        debugTag,
                        AE2FluidCraftCompat.debugStack(input),
                        AE2FluidCraftCompat.debugStack(container.stack),
                        durability.nbtPath,
                        durability.damageStep,
                        durability.maxDamage);
            }
            boolean reusable = container != null
                    && durability == null
                    && sameReusableContainer(input, container.stack);
            int slot = slots.length == 0 ? -1 : slots[0];
            InputInfo inputInfo = new InputInfo(
                    pattern,
                    slot,
                    input,
                    input.getStackSize(),
                    container == null ? null : container.stack,
                    durability,
                    returnedByPattern,
                    reusable,
                    buildInputOptions(pattern, input, slots, container, durability));
            inputs.add(inputInfo);
            if (AE2FluidCraftCompat.isFluidFakeItem(input)
                    || (container != null
                    && AE2FluidCraftCompat.isFluidFakeItem(container.stack))) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] fluid input parsed key={} perCraft={} container={} durability={} returnedByPattern={} reusable={} options={}",
                        debugTag,
                        AE2FluidCraftCompat.debugStack(inputInfo.key),
                        inputInfo.perCraft,
                        AE2FluidCraftCompat.debugStack(inputInfo.container),
                        inputInfo.durability == null ? "-"
                                : (inputInfo.durability.maxDamage + "/"
                                + inputInfo.durability.damageStep),
                        inputInfo.returnedByPattern,
                        inputInfo.reusable,
                        inputInfo.options.length);
            }
        }

        PatternInfo info = new PatternInfo(
                pattern,
                inputs.toArray(new InputInfo[inputs.size()]),
                outputs.toArray(new OutputInfo[outputs.size()]));
        patternInfoCache.put(pattern, info);
        return info;
    }

    private static boolean shouldProbeRecipeContainer(IAEItemStack input) {
        if (input == null || input.getItem() == null) {
            return false;
        }
        ItemStack stack = input.copy().setStackSize(1L).createItemStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        // Ordinary buckets and other vanilla containers are already resolved
        // by inspectContainer(). The recipe walk is useful for custom
        // NBT-bearing tools and native damageable items whose recipe supplies
        // the remainder itself.
        return stack.hasTagCompound() || stack.getItem().isDamageable();
    }

    private ContainerInfo inspectRecipeContainer(ICraftingPatternDetails pattern,
                                                 IAEItemStack input,
                                                 int[] slots) {
        boolean trace = input != null && input.getDefinition() != null
                && input.getDefinition().hasTagCompound();
        if (trace) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder probe start pattern={} craftable={} world={} input={} slots={} rawInputs={}",
                    debugTag,
                    pattern == null ? "null" : pattern.getClass().getName(),
                    pattern != null && pattern.isCraftable(),
                    world == null ? "null" : world.getClass().getName(),
                    AE2FluidCraftCompat.debugStack(input),
                    slots == null ? "null" : Arrays.toString(slots),
                    safeArray(safeInputs(pattern)));
        }
        if (pattern == null || input == null || slots == null
                || slots.length == 0 || !pattern.isCraftable() || world == null) {
            if (trace) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] recipe remainder probe skipped: invalid context",
                        debugTag);
            }
            return null;
        }

        InventoryCrafting template = createCraftingInventory(pattern);
        if (template == null) {
            if (trace) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] recipe remainder probe skipped: crafting inventory could not be created",
                        debugTag);
            }
            return null;
        }
        if (trace) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder probe template={}",
                    debugTag,
                    debugCraftingInventory(template));
            ItemStack source = input.copy().setStackSize(1L)
                    .createItemStack();
            Item item = source == null || source.isEmpty()
                    ? null : source.getItem();
            ItemStack direct = item == null
                    ? ItemStack.EMPTY : getReturnedContainer(item, source);
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder probe itemContainer hasContainer={} direct={}",
                    debugTag,
                    item != null && source != null
                            && item.hasContainerItem(source),
                    debugMinecraftStack(direct));
        }

        IRecipe recipe;
        try {
            recipe = CraftingManager.findMatchingRecipe(template, world);
        } catch (Throwable failure) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder lookup failed pattern={} reason={}",
                    debugTag,
                    pattern.getClass().getName(),
                    failure.toString());
            return null;
        }
        if (recipe == null) {
            if (trace) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] recipe remainder probe found no matching IRecipe",
                        debugTag);
            }
            return null;
        }
        if (trace) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder probe matched recipe={}",
                    debugTag,
                    recipe.getClass().getName());
        }

        ContainerInfo selected = null;
        for (final int slot : slots) {
            if (slot < 0 || slot >= template.getSizeInventory()) {
                if (trace) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] recipe remainder probe invalid slot={} size={}",
                            debugTag, slot, template.getSizeInventory());
                }
                return null;
            }

            InventoryCrafting attempt = copyCraftingInventory(template);
            ItemStack slotInput = input.copy().setStackSize(1L)
                    .createItemStack();
            if (slotInput == null || slotInput.isEmpty()) {
                if (trace) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] recipe remainder probe input conversion failed slot={}",
                            debugTag, slot);
                }
                return null;
            }
            attempt.setInventorySlotContents(slot, slotInput);

            ItemStack remainder = getRecipeRemainder(recipe, attempt, slot);
            boolean syntheticInput = false;
            if (remainder == null || remainder.isEmpty()) {
                remainder = findSyntheticRecipeRemainder(
                        recipe, template, slot, slotInput);
                syntheticInput = remainder != null && !remainder.isEmpty();
            }
            if (trace) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] recipe remainder probe slot={} syntheticInput={} attempt={} remainder={}",
                        debugTag,
                        slot,
                        syntheticInput,
                        debugCraftingInventory(attempt),
                        debugMinecraftStack(remainder));
            }
            if (remainder == null || remainder.isEmpty()) {
                return null;
            }

            final InventoryCrafting probeTemplate = template;
            final IRecipe probeRecipe = recipe;
            final int probeSlot = slot;
            NbtMaxDamageResolver resolver =
                    new NbtMaxDamageResolver() {
                        @Override
                        public long resolve(ItemStack probeInput,
                                             ItemStack probeOutput,
                                             NbtPath path,
                                             NbtNumberType type,
                                             long step) {
                            return probeRecipeMaxDamage(
                                    probeRecipe,
                                    probeTemplate,
                                    probeSlot,
                                    probeInput,
                                    path,
                                    type,
                                    step);
                        }
                    };
            IAEItemStack returned = AEItemStack.fromItemStack(remainder);
            ContainerInfo current = inspectReturnedContainer(
                    input, returned, resolver);
            if (current == null) {
                if (trace) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] recipe remainder probe transition rejected slot={} returned={}",
                            debugTag, slot, AE2FluidCraftCompat.debugStack(returned));
                }
                return null;
            }

            if (selected == null) {
                selected = current;
            } else if (!sameContainerTransition(input, selected, current)) {
                // Every occurrence of a repeated input must return the same
                // transition. Otherwise one aggregate durability stream could
                // consume or create the wrong number of tools.
                if (trace) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] recipe remainder probe transitions disagree slot={} selected={} current={}",
                            debugTag,
                            slot,
                            AE2FluidCraftCompat.debugStack(selected.stack),
                            AE2FluidCraftCompat.debugStack(current.stack));
                }
                return null;
            }
        }

        if (selected != null && selected.durability != null
                && selected.durability.isNbt()) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] recipe remainder NBT durability recipe={} input={} returned={} path={} step={} max={}",
                    debugTag,
                    recipe.getClass().getName(),
                    AE2FluidCraftCompat.debugStack(input),
                    AE2FluidCraftCompat.debugStack(selected.stack),
                    selected.durability.nbtPath,
                    selected.durability.damageStep,
                    selected.durability.maxDamage);
        }
        return selected;
    }

    private static String debugCraftingInventory(InventoryCrafting inventory) {
        if (inventory == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < inventory.getSizeInventory(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(index).append('=').append(
                    debugMinecraftStack(inventory.getStackInSlot(index)));
        }
        return result.append(']').toString();
    }

    private static String debugMinecraftStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return String.valueOf(stack.getItem().getRegistryName())
                + " dmg=" + stack.getItemDamage()
                + " count=" + stack.getCount()
                + " nbt=" + (stack.hasTagCompound()
                ? String.valueOf(stack.getTagCompound()) : "-");
    }

    private static InventoryCrafting createCraftingInventory(
            ICraftingPatternDetails pattern) {
        IAEItemStack[] inputs = safeInputs(pattern);
        if (inputs == null || inputs.length != 9) {
            return null;
        }

        InventoryCrafting inventory = new InventoryCrafting(
                new ContainerNull(), 3, 3);
        for (int index = 0; index < inputs.length; index++) {
            IAEItemStack input = inputs[index];
            if (input == null || input.getStackSize() <= 0L) {
                continue;
            }
            ItemStack stack = input.copy().setStackSize(1L).createItemStack();
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            inventory.setInventorySlotContents(index, stack);
        }
        return inventory;
    }

    private static InventoryCrafting copyCraftingInventory(
            InventoryCrafting source) {
        InventoryCrafting copy = new InventoryCrafting(
                new ContainerNull(), 3, 3);
        for (int index = 0; index < source.getSizeInventory(); index++) {
            ItemStack stack = source.getStackInSlot(index);
            if (stack != null && !stack.isEmpty()) {
                copy.setInventorySlotContents(index, stack.copy());
            }
        }
        return copy;
    }

    private static ItemStack getRecipeRemainder(IRecipe recipe,
                                                InventoryCrafting inventory,
                                                int slot) {
        try {
            NonNullList<ItemStack> remaining = recipe.getRemainingItems(
                    inventory);
            if (remaining == null || slot < 0 || slot >= remaining.size()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = remaining.get(slot);
            return result == null ? ItemStack.EMPTY : result;
        } catch (Throwable failure) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack findSyntheticRecipeRemainder(
            IRecipe recipe,
            InventoryCrafting template,
            int slot,
            ItemStack source) {
        if (recipe == null || template == null || source == null
                || source.isEmpty() || slot < 0
                || slot >= template.getSizeInventory()) {
            return ItemStack.EMPTY;
        }

        IAEItemStack original = AEItemStack.fromItemStack(source);
        if (original == null) {
            return ItemStack.EMPTY;
        }
        List<NbtPath> paths = new ArrayList<NbtPath>();
        collectMissingDurabilityPaths(source.getTagCompound(),
                new ArrayList<String>(), paths);
        NbtMaxDamageResolver unboundedResolver =
                new NbtMaxDamageResolver() {
                    @Override
                    public long resolve(ItemStack input,
                                         ItemStack output,
                                         NbtPath path,
                                         NbtNumberType type,
                                         long step) {
                        return LONG_MAX;
                    }
                };

        for (NbtPath path : paths) {
            for (NbtNumberType type : NbtNumberType.values()) {
                ItemStack probe = source.copy();
                NBTTagCompound tag = probe.getTagCompound();
                if (tag == null) {
                    tag = new NBTTagCompound();
                    probe.setTagCompound(tag);
                }
                if (!setTagAtPath(tag, path, type.write(0L))) {
                    continue;
                }

                InventoryCrafting attempt = copyCraftingInventory(template);
                attempt.setInventorySlotContents(slot, probe);
                ItemStack remainder = getRecipeRemainder(recipe, attempt, slot);
                if (remainder == null || remainder.isEmpty()) {
                    continue;
                }

                IAEItemStack returned = AEItemStack.fromItemStack(remainder);
                ContainerInfo transition = inspectReturnedContainer(
                        original, returned, unboundedResolver);
                if (transition != null && transition.durability != null
                        && transition.durability.isNbt()) {
                    return remainder;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static long probeRecipeMaxDamage(IRecipe recipe,
                                             InventoryCrafting template,
                                             int slot,
                                             ItemStack input,
                                             NbtPath path,
                                             NbtNumberType type,
                                             long step) {
        if (recipe == null || template == null || input == null
                || input.isEmpty() || path == null || type == null
                || step <= 0L) {
            return 0L;
        }

        /*
         * Most NBT tools expose the same transition through Item#getContainerItem
         * that the crafting recipe returns. Prefer that path when the first
         * observed result agrees; it avoids rebuilding an InventoryCrafting for
         * every binary-search probe. If a recipe has custom remainder logic,
         * retain the recipe callback as the conservative fallback.
         */
        ItemStack direct = getReturnedContainer(input.getItem(), input);
        ItemStack firstOutput = getRecipeRemainder(
                recipe,
                replaceCraftingSlot(template, slot, input),
                slot);
        if (sameMinecraftStack(direct, firstOutput)) {
            return probeNbtMaxDamage(input, path, type, step,
                    new NbtTransitionProbe() {
                        @Override
                        public ItemStack apply(ItemStack state) {
                            return getReturnedContainer(state.getItem(), state);
                        }
                    });
        }

        return probeNbtMaxDamage(input, path, type, step,
                new NbtTransitionProbe() {
                    @Override
                    public ItemStack apply(ItemStack state) {
                        InventoryCrafting attempt = copyCraftingInventory(template);
                        attempt.setInventorySlotContents(slot, state.copy());
                        return getRecipeRemainder(recipe, attempt, slot);
                    }
                });
    }

    private static InventoryCrafting replaceCraftingSlot(
            InventoryCrafting template,
            int slot,
            ItemStack input) {
        InventoryCrafting attempt = copyCraftingInventory(template);
        if (slot >= 0 && slot < attempt.getSizeInventory()
                && input != null && !input.isEmpty()) {
            attempt.setInventorySlotContents(slot, input.copy());
        }
        return attempt;
    }

    private static long probeNbtMaxDamage(ItemStack input,
                                          NbtPath path,
                                          NbtNumberType type,
                                          long step) {
        return probeNbtMaxDamage(input, path, type, step,
                new NbtTransitionProbe() {
                    @Override
                    public ItemStack apply(ItemStack state) {
                        return getReturnedContainer(state.getItem(), state);
                    }
                });
    }

    /**
     * Finds the first exhausted durability state with O(log(max)) calls. An
     * arbitrary NBT number is not assumed to be durability until every tested
     * transition preserves the item and all non-durability NBT.
     */
    private static long probeNbtMaxDamage(ItemStack input,
                                          NbtPath path,
                                          NbtNumberType type,
                                          long step,
                                          NbtTransitionProbe transitionProbe) {
        if (input == null || input.isEmpty() || path == null || type == null
                || step <= 0L || transitionProbe == null) {
            return 0L;
        }

        ItemStack zero = createNbtProbeState(input, path, type, 0L);
        if (zero == null) {
            return 0L;
        }
        int zeroStatus = classifyNbtTransition(
                zero, transitionProbe.apply(zero), path, type, step);
        if (zeroStatus == NBT_PROBE_INVALID) {
            return 0L;
        }
        if (zeroStatus == NBT_PROBE_EXHAUSTED) {
            return step;
        }

        long lastValidState = 0L;
        long firstExhaustedState = 0L;
        long high = 1L;
        while (high <= MAX_NBT_DURABILITY_PROBE_USES) {
            long damage = multiplyProbeUses(high, step);
            if (damage < 0L) {
                return 0L;
            }
            ItemStack state = createNbtProbeState(input, path, type, damage);
            if (state == null) {
                return 0L;
            }
            int status = classifyNbtTransition(
                    state, transitionProbe.apply(state), path, type, step);
            if (status == NBT_PROBE_INVALID) {
                return 0L;
            }
            if (status == NBT_PROBE_EXHAUSTED) {
                firstExhaustedState = high;
                break;
            }

            lastValidState = high;
            if (high == MAX_NBT_DURABILITY_PROBE_USES) {
                return 0L;
            }
            high = high > MAX_NBT_DURABILITY_PROBE_USES / 2L
                    ? MAX_NBT_DURABILITY_PROBE_USES
                    : high * 2L;
        }

        if (firstExhaustedState <= lastValidState) {
            return 0L;
        }

        long low = lastValidState;
        long upper = firstExhaustedState;
        while (upper - low > 1L) {
            long middle = low + (upper - low) / 2L;
            long damage = multiplyProbeUses(middle, step);
            if (damage < 0L) {
                return 0L;
            }
            ItemStack state = createNbtProbeState(input, path, type, damage);
            if (state == null) {
                return 0L;
            }
            int status = classifyNbtTransition(
                    state, transitionProbe.apply(state), path, type, step);
            if (status == NBT_PROBE_INVALID) {
                return 0L;
            }
            if (status == NBT_PROBE_EXHAUSTED) {
                upper = middle;
            } else {
                low = middle;
            }
        }

        return multiplyProbeUses(upper + 1L, step);
    }

    private static final int NBT_PROBE_INVALID = 0;
    private static final int NBT_PROBE_VALID = 1;
    private static final int NBT_PROBE_EXHAUSTED = 2;

    private static int classifyNbtTransition(ItemStack state,
                                             ItemStack next,
                                             NbtPath path,
                                             NbtNumberType type,
                                             long step) {
        if (next == null || next.isEmpty()) {
            return NBT_PROBE_EXHAUSTED;
        }
        if (state.getItem() != next.getItem()
                || state.getItemDamage() != next.getItemDamage()) {
            return NBT_PROBE_INVALID;
        }

        Long current = readNumericValue(getTagAtPath(
                state.getTagCompound(), path));
        Long returned = readNumericValue(getTagAtPath(
                next.getTagCompound(), path));
        if (current == null || returned == null
                || current.longValue() > LONG_MAX - step
                || returned.longValue() != current.longValue() + step
                || !sameNbtExceptPath(state.getTagCompound(),
                next.getTagCompound(), path)) {
            return NBT_PROBE_INVALID;
        }
        return NBT_PROBE_VALID;
    }

    private static ItemStack createNbtProbeState(ItemStack input,
                                                 NbtPath path,
                                                 NbtNumberType type,
                                                 long damage) {
        NBTBase encoded = type.write(damage);
        if (encoded == null) {
            return null;
        }
        ItemStack state = input.copy();
        state.setCount(1);
        NBTTagCompound tag = state.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            state.setTagCompound(tag);
        }
        return setTagAtPath(tag, path, encoded) ? state : null;
    }

    private static long multiplyProbeUses(long uses, long step) {
        if (uses < 0L || step <= 0L || uses > LONG_MAX / step) {
            return -1L;
        }
        return uses * step;
    }

    private interface NbtTransitionProbe {
        ItemStack apply(ItemStack state);
    }

    private static boolean sameContainerTransition(IAEItemStack input,
                                                   ContainerInfo left,
                                                   ContainerInfo right) {
        if (left == null || right == null || left.stack == null
                || right.stack == null
                || !sameItemAndDamage(left.stack, right.stack)
                || !sameNbt(left.stack, right.stack)) {
            return false;
        }
        if (left.durability == null || right.durability == null) {
            return left.durability == right.durability
                    && left.durability == null
                    && sameReusableContainer(input, left.stack)
                    && sameReusableContainer(input, right.stack);
        }
        return left.durability.sameTransition(right.durability);
    }

    private static ContainerInfo inspectReturnedContainer(
            IAEItemStack input,
            IAEItemStack returned,
            NbtMaxDamageResolver maxDamageResolver) {
        if (input == null || returned == null) {
            return null;
        }

        ItemStack source = input.copy().setStackSize(1L).createItemStack();
        ItemStack container = returned.copy().setStackSize(1L)
                .createItemStack();
        if (source == null || source.isEmpty()
                || container == null || container.isEmpty()) {
            return null;
        }

        DurabilityInfo durability = null;
        if (isDamageableItem(source.getItem())) {
            int currentDamage = source.getItemDamage();
            int maxDamage = source.getMaxDamage();
            int nextDamage = container.getItemDamage();
            if (container.getItem() == source.getItem()
                    && ItemStack.areItemStackTagsEqual(source, container)
                    && maxDamage > currentDamage
                    && nextDamage > currentDamage
                    && nextDamage <= maxDamage
                    && nextDamage - currentDamage == 1) {
                durability = new DurabilityInfo(maxDamage,
                        nextDamage - currentDamage);
            }
        }

        IAEItemStack stack = AEItemStack.fromItemStack(container);
        if (stack == null) {
            return null;
        }
        if (durability == null) {
            NbtDurabilityTransition nbtTransition =
                    findNbtDurabilityTransition(input, stack,
                            maxDamageResolver);
            if (nbtTransition != null) {
                durability = nbtTransition.durability;
            }
        }
        return new ContainerInfo(stack, durability);
    }

    private static IAEItemStack findExactReturnedInput(IAEItemStack[] outputs,
                                                        IAEItemStack input) {
        long returned = 0L;
        if (outputs != null) {
            for (IAEItemStack output : outputs) {
                if (output != null && output.getStackSize() > 0L
                        && sameKey(output, input)) {
                    returned = checkedAdd(returned, output.getStackSize());
                }
            }
        }
        if (returned != input.getStackSize()) {
            return null;
        }
        return input.copy().setStackSize(1L);
    }

    private static ContainerInfo findPatternDurabilityContainer(
            IAEItemStack input,
            IAEItemStack[] outputs) {
        if (input == null || outputs == null || input.getStackSize() <= 0L) {
            return null;
        }

        long sameItemAmount = 0L;
        long transitionedAmount = 0L;
        NbtDurabilityTransition selected = null;
        for (IAEItemStack output : outputs) {
            if (output == null || output.getStackSize() <= 0L
                    || !sameItemAndDamage(input, output)) {
                continue;
            }

            sameItemAmount = checkedAdd(sameItemAmount, output.getStackSize());
            NbtDurabilityTransition transition =
                    findNbtDurabilityTransition(input, output);
            if (transition == null) {
                continue;
            }

            transitionedAmount = checkedAdd(transitionedAmount,
                    output.getStackSize());
            if (selected == null) {
                selected = transition;
            } else if (!selected.durability.sameTransition(
                    transition.durability)
                    || selected.output.getStackSize() != transition.output.getStackSize()
                    || !sameNbt(selected.output, transition.output)) {
                // Multiple different returned states are ambiguous. Treating
                // one of them as a single durability stream could duplicate
                // or destroy a tool, so let AE2 handle this pattern.
                return null;
            }
        }

        if (selected == null || sameItemAmount != input.getStackSize()
                || transitionedAmount != input.getStackSize()) {
            return null;
        }

        IAEItemStack returned = selected.output.copy().setStackSize(1L);
        return new ContainerInfo(returned, selected.durability);
    }

    private static NbtDurabilityTransition findNbtDurabilityTransition(
            IAEItemStack input,
            IAEItemStack output) {
        return findNbtDurabilityTransition(input, output, null);
    }

    private static NbtDurabilityTransition findNbtDurabilityTransition(
            IAEItemStack input,
            IAEItemStack output,
            NbtMaxDamageResolver maxDamageResolver) {
        if (input == null || output == null
                || input.getStackSize() <= 0L
                || output.getStackSize() <= 0L
                || !sameItemAndDamage(input, output)) {
            return null;
        }

        ItemStack inputStack = input.copy().setStackSize(1).createItemStack();
        ItemStack outputStack = output.copy().setStackSize(1).createItemStack();
        if (inputStack == null || outputStack == null) {
            return null;
        }

        NBTTagCompound inputTag = inputStack.getTagCompound();
        NBTTagCompound outputTag = outputStack.getTagCompound();
        if (outputTag == null) {
            return null;
        }

        List<NbtPath> paths = new ArrayList<NbtPath>();
        collectNumericPaths(outputTag, new ArrayList<String>(), paths);
        NbtDurabilityTransition selected = null;
        for (NbtPath path : paths) {
            NBTBase outputValueTag = getTagAtPath(outputTag, path);
            NbtNumberType outputType = NbtNumberType.from(outputValueTag);
            if (outputType == null) {
                continue;
            }

            NBTBase inputValueTag = getTagAtPath(inputTag, path);
            if (inputValueTag != null
                    && NbtNumberType.from(inputValueTag) == null) {
                continue;
            }

            Long inputValue = inputValueTag == null
                    ? Long.valueOf(0L)
                    : readNumericValue(inputValueTag);
            Long outputValue = readNumericValue(outputValueTag);
            if (inputValue == null || outputValue == null
                    || outputValue.longValue() <= inputValue.longValue()) {
                continue;
            }

            long step = outputValue.longValue() - inputValue.longValue();
            if (step <= 0L
                    || !sameNbtExceptPath(inputTag, outputTag, path)) {
                continue;
            }

            long maxDamage = resolveMaxDamage(inputStack, outputStack,
                    path, outputType, step, maxDamageResolver);
            if (maxDamage <= 0L || outputValue.longValue() > maxDamage) {
                continue;
            }

            DurabilityInfo durability = DurabilityInfo.nbt(
                    maxDamage, step, path, outputType);
            NbtDurabilityTransition candidate =
                    new NbtDurabilityTransition(durability, output.copy());
            if (selected != null) {
                return null;
            }
            selected = candidate;
        }
        return selected;
    }

    private static long resolveMaxDamage(ItemStack input,
                                         ItemStack output,
                                         NbtPath path,
                                         NbtNumberType type,
                                         long step,
                                         NbtMaxDamageResolver maxDamageResolver) {
        if (maxDamageResolver != null) {
            return maxDamageResolver.resolve(input, output, path, type, step);
        }
        // This method is only used for an NBT-backed transition. ItemStack's
        // native max-damage field is not authoritative for such items: many
        // integrations leave it at a default value (for example 256) while
        // storing the real wear limit in their own NBT/tool logic.
        //
        // Probe the public container transition instead. A finite endpoint is
        // required; an unknown or unbounded transition must not be turned into
        // an invented capacity because that could duplicate or destroy tools.
        return probeNbtMaxDamage(input, path, type, step);
    }

    private static boolean sameItemAndDamage(IAEItemStack left,
                                              IAEItemStack right) {
        return left != null && right != null
                && left.getItem() == right.getItem()
                && left.getItemDamage() == right.getItemDamage();
    }

    private static boolean sameMinecraftStack(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return left == right || (left != null && right != null
                    && left.isEmpty() && right.isEmpty());
        }
        return left.getItem() == right.getItem()
                && left.getItemDamage() == right.getItemDamage()
                && ItemStack.areItemStackTagsEqual(left, right);
    }

    private static boolean sameNbt(IAEItemStack left, IAEItemStack right) {
        if (left == null || right == null) {
            return false;
        }
        return sameNbt(left.copy().setStackSize(1).createItemStack(),
                right.copy().setStackSize(1).createItemStack());
    }

    private static boolean sameNbt(ItemStack left, ItemStack right) {
        if (left == null || right == null) {
            return false;
        }
        NBTTagCompound leftTag = left.getTagCompound();
        NBTTagCompound rightTag = right.getTagCompound();
        if (leftTag == null || rightTag == null) {
            return leftTag == rightTag;
        }
        return leftTag.equals(rightTag);
    }

    private static void collectNumericPaths(NBTTagCompound tag,
                                            List<String> prefix,
                                            List<NbtPath> result) {
        if (tag == null) {
            return;
        }
        for (String key : tag.getKeySet()) {
            NBTBase value = tag.getTag(key);
            List<String> path = new ArrayList<String>(prefix);
            path.add(key);
            if (value instanceof NBTTagCompound) {
                collectNumericPaths((NBTTagCompound) value, path, result);
            } else if (NbtNumberType.from(value) != null) {
                result.add(new NbtPath(path));
            }
        }
    }

    private static NBTBase getTagAtPath(NBTTagCompound root, NbtPath path) {
        if (root == null || path == null || path.parts.length == 0) {
            return null;
        }
        NBTTagCompound current = root;
        for (int index = 0; index < path.parts.length; index++) {
            NBTBase value = current.getTag(path.parts[index]);
            if (index == path.parts.length - 1) {
                return value;
            }
            if (!(value instanceof NBTTagCompound)) {
                return null;
            }
            current = (NBTTagCompound) value;
        }
        return null;
    }

    private static boolean sameNbtExceptPath(NBTTagCompound left,
                                             NBTTagCompound right,
                                             NbtPath path) {
        NBTTagCompound leftCopy = left == null
                ? new NBTTagCompound() : left.copy();
        NBTTagCompound rightCopy = right == null
                ? new NBTTagCompound() : right.copy();
        removeTagAtPath(leftCopy, path, 0);
        removeTagAtPath(rightCopy, path, 0);
        return leftCopy.equals(rightCopy);
    }

    private static boolean removeTagAtPath(NBTTagCompound root,
                                           NbtPath path,
                                           int index) {
        if (root == null || path == null || index >= path.parts.length) {
            return false;
        }
        String key = path.parts[index];
        if (index == path.parts.length - 1) {
            if (!root.hasKey(key)) {
                return false;
            }
            root.removeTag(key);
            return true;
        }

        NBTBase child = root.getTag(key);
        if (!(child instanceof NBTTagCompound)) {
            return false;
        }
        NBTTagCompound childCopy = ((NBTTagCompound) child).copy();
        boolean removed = removeTagAtPath(childCopy, path, index + 1);
        if (!removed) {
            return false;
        }
        if (childCopy.getKeySet().isEmpty()) {
            root.removeTag(key);
        } else {
            root.setTag(key, childCopy);
        }
        return true;
    }

    private static Long readNumericValue(NBTBase value) {
        NbtNumberType type = NbtNumberType.from(value);
        return type == null ? null : type.read(value);
    }

    private static boolean writeNbtDurability(ItemStack stack,
                                              DurabilityInfo durability,
                                              long value) {
        if (stack == null || durability == null || !durability.isNbt()
                || durability.nbtType == null) {
            return false;
        }
        NBTBase encoded = durability.nbtType.write(value);
        if (encoded == null) {
            return false;
        }
        NBTTagCompound root = stack.getTagCompound();
        if (root == null) {
            root = new NBTTagCompound();
            stack.setTagCompound(root);
        }
        return setTagAtPath(root, durability.nbtPath, encoded);
    }

    private static boolean setTagAtPath(NBTTagCompound root,
                                         NbtPath path,
                                         NBTBase value) {
        if (root == null || path == null || path.parts.length == 0
                || value == null) {
            return false;
        }
        NBTTagCompound current = root;
        for (int index = 0; index < path.parts.length - 1; index++) {
            String key = path.parts[index];
            NBTBase child = current.getTag(key);
            if (child == null) {
                NBTTagCompound created = new NBTTagCompound();
                current.setTag(key, created);
                current = created;
            } else if (child instanceof NBTTagCompound) {
                current = (NBTTagCompound) child;
            } else {
                return false;
            }
        }
        current.setTag(path.parts[path.parts.length - 1], value);
        return true;
    }

    private InputOption[] buildInputOptions(ICraftingPatternDetails pattern,
                                             IAEItemStack input,
                                             int[] slots,
                                             ContainerInfo primaryContainer,
                                             DurabilityInfo primaryDurability) {
        List<InputOption> options = new ArrayList<InputOption>();
        options.add(new InputOption(input,
                primaryContainer == null ? null : primaryContainer.stack,
                primaryDurability));

        // PackageCraftingPatternHelper reports canSubstitute(), but its source
        // exposes no substitute candidates and its machine consumes the exact
        // encoded inputs. Do not call its unsupported slot validator.
        if (PackagedAutoCompat.consumesAllInputs(pattern)) {
            return options.toArray(new InputOption[options.size()]);
        }

        if (!pattern.canSubstitute()) {
            return options.toArray(new InputOption[options.size()]);
        }

        // ICraftingPatternDetails has a default empty substitute list. A
        // number of integrations set canSubstitute() from the shared AE2
        // pattern flag but do not expose ingredient candidates. In that case
        // the encoded input is still a valid exact input and must remain on
        // the direct-calculation path.
        if (slots.length == 0) {
            return options.toArray(new InputOption[options.size()]);
        }

        List<IAEItemStack> candidates = new ArrayList<IAEItemStack>();
        for (int slot : slots) {
            List<IAEItemStack> slotSubstitutes = pattern.getSubstituteInputs(slot);
            if (slotSubstitutes == null) {
                continue;
            }
            for (IAEItemStack substitute : slotSubstitutes) {
                if (substitute == null || substitute.getStackSize() <= 0L) {
                    continue;
                }
                IAEItemStack candidate = substitute.copy().setStackSize(1L);
                if (candidate.getItem() == null
                        || !isValidSubstitute(pattern, slots, candidate)) {
                    continue;
                }

                boolean duplicate = false;
                for (IAEItemStack existing : candidates) {
                    if (sameKey(existing, candidate)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    candidates.add(candidate);
                }
            }
        }

        boolean hasAlternative = false;
        for (IAEItemStack candidate : candidates) {
            if (!sameKey(input, candidate)) {
                hasAlternative = true;
                break;
            }
        }
        if (!hasAlternative) {
            return options.toArray(new InputOption[options.size()]);
        }

        if (primaryContainer != null || primaryDurability != null
                || isDamageableItem(input.getItem())) {
            throw unsupported(FallbackReason.SUBSTITUTION_CONTAINER,
                    "Substitution with a reusable or damageable input is not supported");
        }

        for (IAEItemStack candidate : candidates) {
            if (sameKey(input, candidate)) {
                continue;
            }
            ContainerInfo container = inspectContainer(candidate);
            if (container != null || candidate.getItem().hasContainerItem(
                    candidate.getDefinition())
                    || isDamageableItem(candidate.getItem())) {
                throw unsupported(FallbackReason.SUBSTITUTION_CONTAINER,
                        "Substitution candidate has unsupported container semantics");
            }
            options.add(new InputOption(candidate, null, null));
        }

        return options.toArray(new InputOption[options.size()]);
    }

    private boolean isValidSubstitute(ICraftingPatternDetails pattern,
                                      int[] slots,
                                      IAEItemStack candidate) {
        ItemStack stack = candidate.createItemStack();
        for (int slot : slots) {
            if (pattern.isValidItemForSlot(slot, stack, world)) {
                return true;
            }
        }
        return false;
    }

    private PatternChoice resolvePattern(IAEItemStack key) {
        return resolvePattern(key, 0L);
    }

    private PatternChoice resolvePattern(IAEItemStack key, long amountHint) {
        boolean activeKey = isActiveKey(key);
        PatternChoice cached = patternCache.get(key);
        // External storage is mutable during one calculation: an earlier
        // lookup may have consumed the last copies of this key. Never reuse
        // an external result from the cache after the ledger changes.
        // A choice made outside a recursive path must also not hide an
        // alternative when the same key is encountered recursively.
        if (!activeKey && cached != null && !cached.external && (!isFluidKey(key)
                || cached.pattern != null
                || amountHint <= 0L)) {
            return cached;
        }

        boolean fluidKey = isFluidKey(key);
        boolean available = hasAvailable(key);
        boolean emitable = canEmitFor(key, amountHint);

        // ICraftingGrid#canEmitFor only describes whether the network can
        // emit this type at all. It does not promise an unlimited quantity.
        // The private inventory ledger is authoritative for the amount that
        // remains after earlier inputs have been reserved.
        if (emitable && available) {
            if (fluidKey) {
                AE2QuickCalculation.LOGGER.info(
                        "[QCALC][{}] fluid resolve external key={} amountHint={} available={} emitable={}",
                        debugTag,
                        AE2FluidCraftCompat.debugStack(key),
                        amountHint,
                        available,
                        emitable);
            }
            return new PatternChoice(true, null, 0L);
        }

        ICraftingPatternDetails pattern = null;
        long outputAmount = 0L;
        int candidateCount = 0;
        boolean cycleCandidateRejected = false;
        boolean cycleCandidateActive = false;
        boolean cycleCandidateUnprovable = false;
        for (ICraftingPatternDetails candidate : getCraftingFor(key, amountHint)) {
            candidateCount++;
            IAEItemStack[] outputs = getCalculationOutputs(candidate);
            if (outputs == null) {
                continue;
            }
            long candidateOutputAmount = 0L;
            boolean matchesKey = false;
            for (IAEItemStack output : outputs) {
                if (output != null && output.getStackSize() > 0L
                        && output.isSameType(key)) {
                    matchesKey = true;
                    candidateOutputAmount = output.getStackSize();
                    break;
                }
            }
            if (!matchesKey) {
                continue;
            }

            if (!activeKeys.isEmpty()) {
                CycleDependencyStatus cycleStatus =
                        checkCycleCandidate(candidate, amountHint);
                if (cycleStatus != CycleDependencyStatus.SAFE) {
                    cycleCandidateRejected = true;
                    cycleCandidateActive |=
                            cycleStatus == CycleDependencyStatus.ACTIVE;
                    cycleCandidateUnprovable |=
                            cycleStatus == CycleDependencyStatus.UNPROVABLE;
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] cycle candidate skipped key={} pattern={} reason={}",
                            debugTag,
                            AE2FluidCraftCompat.debugStack(key),
                            candidate.getClass().getName(),
                            cycleStatus);
                    continue;
                }
            }

            pattern = candidate;
            outputAmount = candidateOutputAmount;
            if (pattern != null) {
                break;
            }
        }

        PatternChoice choice = new PatternChoice(false, pattern, outputAmount);
        if (pattern == null && cycleCandidateRejected
                && cycleCandidateUnprovable && !cycleCandidateActive) {
            throw unsupported(FallbackReason.CYCLE_NOT_PROVABLE,
                    "All crafting patterns for " + key
                            + " are outside the provable dependency boundary");
        }
        if (fluidKey) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] fluid resolve key={} amountHint={} available={} emitable={} candidates={} selected={} outputAmount={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(key),
                    amountHint,
                    available,
                    emitable,
                    candidateCount,
                    pattern == null ? "none" : pattern.getClass().getName(),
                    outputAmount);
        }
        // A packet-indexed fluid pattern can only be found after the caller
        // supplies its per-craft fluid amount. Do not cache a fluid miss from
        // an amount-less probe and hide a later, more precise query.
        if (!activeKey && (!isFluidKey(key) || pattern != null)) {
            patternCache.put(key, choice);
        }
        return choice;
    }

    private boolean isActiveKey(IAEItemStack key) {
        if (key == null || activeKeys == null) {
            return false;
        }
        for (IAEItemStack active : activeKeys) {
            if (sameKey(active, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks a recursive candidate without changing the calculation ledger.
     * A candidate is rejected only when every reachable producer path either
     * returns to an active key or cannot be proven within the scan budget.
     */
    private boolean isCycleCandidateSafe(ICraftingPatternDetails candidate,
                                         long amountHint) {
        return checkCycleCandidate(candidate, amountHint)
                == CycleDependencyStatus.SAFE;
    }

    private CycleDependencyStatus checkCycleCandidate(
            ICraftingPatternDetails candidate,
            long amountHint) {
        PatternInfo info;
        try {
            info = getPatternInfo(candidate);
        } catch (CalculationFallbackException failure) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] cycle proof could not parse pattern={} reason={} detail={}",
                    debugTag,
                    candidate == null ? "null" : candidate.getClass().getName(),
                    failure.getReason(),
                    failure.getMessage());
            return CycleDependencyStatus.UNPROVABLE;
        }

        CycleSearchBudget budget = new CycleSearchBudget(
                MAX_ALTERNATIVE_CYCLE_SCAN_NODES);
        Set<IAEItemStack> path = new LinkedHashSet<IAEItemStack>();
        return candidateReachesActive(info, path, budget);
    }

    private CycleDependencyStatus candidateReachesActive(
            PatternInfo pattern,
            Set<IAEItemStack> path,
            CycleSearchBudget budget) {
        boolean unprovable = false;
        for (InputInfo input : pattern.inputs) {
            if (input == null || input.key == null
                    || input.perCraft <= 0L) {
                continue;
            }
            CycleDependencyStatus status = dependencyReachesActive(
                    input.key, input.perCraft, path, budget);
            if (status == CycleDependencyStatus.ACTIVE) {
                return CycleDependencyStatus.ACTIVE;
            }
            if (status == CycleDependencyStatus.UNPROVABLE) {
                unprovable = true;
            }
        }
        return unprovable ? CycleDependencyStatus.UNPROVABLE
                : CycleDependencyStatus.SAFE;
    }

    private CycleDependencyStatus dependencyReachesActive(
            IAEItemStack key,
            long required,
            Set<IAEItemStack> path,
            CycleSearchBudget budget) {
        if (key == null || key.getStackSize() <= 0L) {
            return CycleDependencyStatus.SAFE;
        }
        if (isActiveKey(key)) {
            return CycleDependencyStatus.ACTIVE;
        }
        if (amountOf(key) >= required) {
            return CycleDependencyStatus.SAFE;
        }
        if (!budget.consume()) {
            return CycleDependencyStatus.UNPROVABLE;
        }

        if (!addCyclePathKey(path, key)) {
            // A closed path that does not include an active key is a possible
            // cycle boundary, not proof that the dependency reaches the
            // current request. The actual traversal will stop when it reaches
            // the active key that entered the cycle.
            return CycleDependencyStatus.SAFE;
        }

        boolean matchedPattern = false;
        boolean safeCandidate = false;
        boolean activeCandidate = false;
        boolean unprovableCandidate = false;
        try {
            for (ICraftingPatternDetails candidate : getCraftingFor(
                    key, Math.max(1L, required))) {
                if (!hasOutputFor(candidate, key)) {
                    continue;
                }
                matchedPattern = true;

                PatternInfo info;
                try {
                    info = getPatternInfo(candidate);
                } catch (CalculationFallbackException failure) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][{}] cycle proof dependency could not parse key={} pattern={} reason={} detail={}",
                            debugTag,
                            AE2FluidCraftCompat.debugStack(key),
                            candidate.getClass().getName(),
                            failure.getReason(),
                            failure.getMessage());
                    unprovableCandidate = true;
                    continue;
                }
                CycleDependencyStatus status = candidateReachesActive(
                        info, path, budget);
                if (status == CycleDependencyStatus.SAFE) {
                    safeCandidate = true;
                    break;
                }
                if (status == CycleDependencyStatus.ACTIVE) {
                    activeCandidate = true;
                } else {
                    unprovableCandidate = true;
                }
            }
        } finally {
            removeCyclePathKey(path, key);
        }

        // No producer means a missing/external input, which terminates this
        // dependency walk. A closed non-active cycle also terminates the
        // proof. Only an active path blocks the candidate; unknown paths stay
        // conservative and request a controlled fallback.
        if (safeCandidate || !matchedPattern) {
            return CycleDependencyStatus.SAFE;
        }
        if (activeCandidate) {
            return CycleDependencyStatus.ACTIVE;
        }
        return unprovableCandidate ? CycleDependencyStatus.UNPROVABLE
                : CycleDependencyStatus.SAFE;
    }

    private boolean hasOutputFor(ICraftingPatternDetails pattern,
                                 IAEItemStack key) {
        IAEItemStack[] outputs = getCalculationOutputs(pattern);
        if (outputs == null) {
            return false;
        }
        for (IAEItemStack output : outputs) {
            if (output != null && output.getStackSize() > 0L
                    && output.isSameType(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean addCyclePathKey(Set<IAEItemStack> path,
                                           IAEItemStack key) {
        if (containsKey(path, key)) {
            return false;
        }
        IAEItemStack copy = key.copy();
        copy.reset();
        path.add(copy);
        return true;
    }

    private static void removeCyclePathKey(Set<IAEItemStack> path,
                                           IAEItemStack key) {
        IAEItemStack found = null;
        for (IAEItemStack candidate : path) {
            if (sameKey(candidate, key)) {
                found = candidate;
                break;
            }
        }
        if (found != null) {
            path.remove(found);
        }
    }

    private boolean hasAvailable(IAEItemStack key) {
        if (key == null || availableItems == null) {
            return false;
        }
        IAEItemStack available = availableItems.findPrecise(key);
        return available != null && available.getStackSize() > 0L;
    }

    private Collection<ICraftingPatternDetails> getCraftingFor(IAEItemStack key) {
        return getCraftingFor(key, 0L);
    }

    private Collection<ICraftingPatternDetails> getCraftingFor(IAEItemStack key,
                                                                long amountHint) {
        Set<ICraftingPatternDetails> result =
                new LinkedHashSet<ICraftingPatternDetails>();
        if (key != null) {
            result.addAll(grid.getCraftingFor(key, null, -1, world));
        }

        if (AE2FluidCraftCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                    key, amountHint);
            if (packet != null && !packet.isSameType(key)) {
                result.addAll(grid.getCraftingFor(packet, null, -1, world));
            }
        }
        return result;
    }

    private boolean canEmitFor(IAEItemStack key, long amountHint) {
        if (key == null) {
            return false;
        }
        if (grid.canEmitFor(key)) {
            return true;
        }

        if (!isFluidKey(key)) {
            return false;
        }

        IAEItemStack packet = AE2FluidCraftCompat.packFluidPacket(
                key, amountHint);
        if (packet != null && grid.canEmitFor(packet)) {
            return true;
        }
        return false;
    }

    private static boolean isFluidKey(IAEItemStack key) {
        return AE2FluidCraftCompat.isFluidFakeItem(key);
    }

    private void provideExternal(IAEItemStack key, long amount) {
        if (amount <= 0L) {
            return;
        }
        if (isFluidKey(key)) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] fluid provided externally key={} amount={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(key),
                    amount);
        }
        insertInternal(key, amount);
        addCount(emittedItems, key, amount);
        addBytes(amount);
    }

    private long extract(IAEItemStack key, long required) {
        if (required <= 0L) {
            return 0L;
        }

        long fromInternal = takeInternal(key, required);
        long remaining = required - fromInternal;
        long fromNetwork = takeAvailable(key, remaining);
        if (fromNetwork > 0L) {
            addCount(usedItems, key, fromNetwork);
        }

        long total = fromInternal + fromNetwork;
        addBytes(total);
        if (isFluidKey(key)) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] fluid extract key={} required={} internal={} network={} total={}",
                    debugTag,
                    AE2FluidCraftCompat.debugStack(key),
                    required,
                    fromInternal,
                    fromNetwork,
                    total);
        }
        return total;
    }

    private long takeAvailable(IAEItemStack key, long requested) {
        if (requested <= 0L) {
            return 0L;
        }

        // This is the unlogged private copy created by QuickCalculationTreeNode,
        // so mutating its local stock has the same effect as MODULATE extraction
        // without allocating a request/result stack for every dependency level.
        IAEItemStack current = availableItems.findPrecise(key);
        if (current == null) {
            return 0L;
        }
        long available = Math.max(0L, current.getStackSize());
        long taken = Math.min(requested, available);
        if (taken > 0L) {
            current.setStackSize(available - taken);
        }
        return taken;
    }

    private long takeInternal(IAEItemStack key, long requested) {
        IAEItemStack current = internalItems.findPrecise(key);
        if (current == null) {
            return 0L;
        }

        long available = Math.max(0L, current.getStackSize());
        long taken = Math.min(requested, available);
        if (taken > 0L) {
            current.setStackSize(available - taken);
        }
        return taken;
    }

    private void addPendingReturn(PatternFrame frame,
                                  IAEItemStack stack,
                                  long amount) {
        if (frame == null || stack == null || amount <= 0L) {
            return;
        }
        for (PendingReturn pending : frame.pendingReturns) {
            if (sameKey(pending.stack, stack)) {
                pending.amount = checkedAdd(pending.amount, amount);
                return;
            }
        }
        IAEItemStack copy = stack.copy();
        copy.setStackSize(1L);
        frame.pendingReturns.add(new PendingReturn(copy, amount));
    }

    private void insertInternal(IAEItemStack key, long count) {
        if (count > 0L) {
            addCount(internalItems, key, count);
        }
    }

    private void addPattern(ICraftingPatternDetails pattern, long count) {
        if (count <= 0L) {
            return;
        }
        Long old = patternTimes.get(pattern);
        long previous = old == null ? 0L : old;
        patternTimes.put(pattern, checkedAdd(previous, count));
        addBytes(saturatedMultiply(count, BYTE_COST_PER_CRAFT));
    }

    private void addBytes(long amount) {
        if (amount <= 0L || bytes == LONG_MAX) {
            return;
        }
        bytes = saturatedAdd(bytes, amount);
    }

    public static boolean isSupported(ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }

        IAEItemStack[] inputs = getCalculationInputs(pattern);
        if (inputs == null || inputs.length == 0) {
            return false;
        }

        IAEItemStack[] outputs = getCalculationOutputs(pattern);
        IAEItemStack[] condensedOutputs =
                getCalculationOutputsFromCondensed(pattern);

        boolean implicitCraftingRemainders =
                usesImplicitCraftingRemainders(pattern);
        for (IAEItemStack input : inputs) {
            if (input == null || input.getStackSize() <= 0L) {
                continue;
            }
            Item item = input.getItem();
            if (item == null) {
                return false;
            }

            if (!implicitCraftingRemainders) {
                // Processing patterns consume their encoded inputs unless a
                // matching return is explicitly present in getOutputs().
                // Their external machine, not AE2's crafting executor,
                // owns any Item#getContainerItem() behavior.
                continue;
            }

            ContainerInfo container = inspectContainer(input);
            if (container == null || container.durability == null) {
                ContainerInfo nbtContainer = findPatternDurabilityContainer(
                        input, outputs);
                if (nbtContainer == null) {
                    nbtContainer = findPatternDurabilityContainer(input,
                            condensedOutputs);
                }
                if (nbtContainer != null) {
                    container = nbtContainer;
                }
            }
            if (item.hasContainerItem(input.getDefinition()) && container == null) {
                // Treat an unrecognised container transition as consumed input
                // would be incorrect, so leave it to AE2's native path.
                return false;
            }

            // A damageable input without a returned state is consumed by the
            // recipe. Only an explicit durability transition needs the
            // one-item restriction used by the batch allocator.
            if (container != null && container.durability != null
                    && input.getStackSize() != 1L) {
                return false;
            }
        }
        return true;
    }

    /**
     * Only AE2's crafting-grid patterns execute vanilla recipe remainders.
     * Processing patterns are fulfilled by an external machine, so their
     * encoded output list is the sole authoritative source of returns.
     */
    private static boolean usesImplicitCraftingRemainders(
            ICraftingPatternDetails pattern) {
        return pattern != null && pattern.isCraftable()
                && !PackagedAutoCompat.consumesAllInputs(pattern);
    }

    private static IAEItemStack[] getCalculationInputs(
            ICraftingPatternDetails pattern) {
        IAEItemStack[] condensed = pattern.getCondensedInputs();
        IAEItemStack[] normalized = normalizeAndCondense(condensed);
        if (normalized != null && normalized.length > 0) {
            return normalized;
        }

        // Some integrations expose only the slot-preserving array. An empty
        // condensed view must not turn a real recipe into a no-input recipe.
        return normalizeAndCondense(pattern.getInputs());
    }

    private static IAEItemStack[] getCalculationOutputs(
            ICraftingPatternDetails pattern) {
        return normalizeAndCondense(pattern.getOutputs());
    }

    private static IAEItemStack[] getCalculationOutputsFromCondensed(
            ICraftingPatternDetails pattern) {
        return normalizeAndCondense(pattern.getCondensedOutputs());
    }

    private static IAEItemStack[] safeInputs(ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            return pattern.getInputs();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static IAEItemStack[] safeCondensedInputs(
            ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            return pattern.getCondensedInputs();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static IAEItemStack[] safeOutputs(ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            return pattern.getOutputs();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static IAEItemStack[] safeCondensedOutputs(
            ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            return pattern.getCondensedOutputs();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String safeArray(IAEItemStack[] stacks) {
        try {
            return AE2FluidCraftCompat.debugArray(stacks);
        } catch (Throwable failure) {
            return "<diagnostic-error:" + failure.getClass().getSimpleName() + ">";
        }
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

    private static IAEItemStack[] normalizeAndCondense(IAEItemStack[] stacks) {
        if (stacks == null) {
            return null;
        }

        IItemList<IAEItemStack> condensed = newItemList();
        for (IAEItemStack stack : stacks) {
            if (stack == null || stack.getStackSize() <= 0L) {
                continue;
            }
            IAEItemStack normalized = normalizeForCalculation(stack);
            if (normalized == null || normalized.getStackSize() <= 0L) {
                return null;
            }
            condensed.add(normalized);
        }

        List<IAEItemStack> result = new ArrayList<IAEItemStack>();
        for (IAEItemStack stack : condensed) {
            result.add(stack);
        }
        return result.toArray(new IAEItemStack[result.size()]);
    }

    private static IAEItemStack normalizeForCalculation(IAEItemStack stack) {
        return AE2FluidCraftCompat.normalizeFluidItem(stack);
    }

    private static boolean sameReusableContainer(IAEItemStack input,
                                                  IAEItemStack container) {
        if (!sameItemAndTags(input, container)) {
            return false;
        }
        return isDamageableItem(input.getItem())
                || input.getItemDamage() == container.getItemDamage();
    }

    private static ContainerInfo inspectContainer(IAEItemStack input) {
        Item item = input.getItem();
        if (item == null) {
            return null;
        }

        ItemStack source = input.copy().setStackSize(1).createItemStack();
        if (source == null || source.isEmpty()) {
            return null;
        }
        boolean declaresContainer = item.hasContainerItem(source);
        ItemStack container = getReturnedContainer(item, source);
        ContainerInfo direct = null;
        if (container != null && !container.isEmpty()) {
            IAEItemStack returned = AEItemStack.fromItemStack(container);
            direct = inspectReturnedContainer(input, returned, null);
            if (direct != null && direct.durability != null) {
                return direct;
            }
        }

        // Some custom tools create their first wear field only when they are
        // damaged. Probe those pristine states with a small semantic field
        // allowlist, then require the returned stack to prove a single NBT
        // transition and a finite endpoint before accepting it as durability.
        // Do not synthesize dozens of NBT fields for every ordinary item. A
        // missing container declaration cannot become a safe reusable tool by
        // guessing arbitrary NBT keys; recipe-specific probing handles the
        // exceptional integrations that explicitly return a remainder.
        if (!declaresContainer && (container == null || container.isEmpty())) {
            return direct;
        }

        NbtDurabilityTransition synthetic =
                findSyntheticNbtDurabilityTransition(source, input);
        if (synthetic != null) {
            return new ContainerInfo(synthetic.output, synthetic.durability);
        }
        return direct;
    }

    private static ItemStack getReturnedContainer(Item item, ItemStack source) {
        if (item == null || source == null || source.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            // Read the item's transition directly first. Platform's helper
            // applies the vanilla ItemStack damage limit and may discard a
            // valid custom-NBT transition before the caller can inspect it.
            ItemStack direct = item.getContainerItem(source.copy());
            if (direct != null && !direct.isEmpty()) {
                return direct;
            }
            if (item.hasContainerItem(source)) {
                return Platform.getContainerItem(source.copy());
            }
            return ItemStack.EMPTY;
        } catch (Throwable failure) {
            return ItemStack.EMPTY;
        }
    }

    private static NbtDurabilityTransition findSyntheticNbtDurabilityTransition(
            ItemStack source,
            IAEItemStack original) {
        if (source == null || source.isEmpty() || original == null) {
            return null;
        }

        List<NbtPath> paths = new ArrayList<NbtPath>();
        collectMissingDurabilityPaths(source.getTagCompound(),
                new ArrayList<String>(), paths);
        for (NbtPath path : paths) {
            for (NbtNumberType type : NbtNumberType.values()) {
                ItemStack probe = source.copy();
                NBTTagCompound tag = probe.getTagCompound();
                if (tag == null) {
                    tag = new NBTTagCompound();
                    probe.setTagCompound(tag);
                }
                if (!setTagAtPath(tag, path, type.write(0L))) {
                    continue;
                }

                ItemStack returned = getReturnedContainer(
                        source.getItem(), probe);
                if (returned == null || returned.isEmpty()) {
                    continue;
                }
                IAEItemStack returnedStack = AEItemStack.fromItemStack(
                        returned);
                NbtDurabilityTransition transition =
                        findNbtDurabilityTransition(original, returnedStack,
                                null);
                if (transition != null) {
                    return transition;
                }
            }
        }
        return null;
    }

    private static void collectMissingDurabilityPaths(
            NBTTagCompound tag,
            List<String> prefix,
            List<NbtPath> result) {
        for (String fieldName : NBT_DURABILITY_FIELD_NAMES) {
            List<String> candidate = new ArrayList<String>(prefix);
            candidate.add(fieldName);
            NbtPath path = new NbtPath(candidate);
            if (getTagAtPath(tag, path) == null) {
                result.add(path);
            }
        }
        if (tag == null) {
            return;
        }
        for (String key : tag.getKeySet()) {
            NBTBase value = tag.getTag(key);
            if (value instanceof NBTTagCompound) {
                List<String> childPrefix = new ArrayList<String>(prefix);
                childPrefix.add(key);
                collectMissingDurabilityPaths((NBTTagCompound) value,
                        childPrefix, result);
            }
        }
    }

    private static int[] findInputSlots(ICraftingPatternDetails pattern,
                                         IAEItemStack condensedInput) {
        IAEItemStack[] inputs = pattern.getInputs();
        if (inputs == null) {
            return new int[0];
        }
        List<Integer> slots = new ArrayList<Integer>();
        for (int index = 0; index < inputs.length; index++) {
            IAEItemStack input = inputs[index];
            IAEItemStack normalized = normalizeForCalculation(input);
            if (normalized != null && sameItemAndTags(normalized, condensedInput)) {
                slots.add(index);
            }
        }
        int[] result = new int[slots.size()];
        for (int index = 0; index < slots.size(); index++) {
            result[index] = slots.get(index);
        }
        return result;
    }

    private static boolean sameItemAndTags(IAEItemStack left,
                                           IAEItemStack right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.isSameType(right)) {
            return true;
        }
        // AE2 deliberately treats damageable items fuzzily when resolving a
        // crafting slot. Preserve that behavior only for damageable items;
        // silently ignoring metadata on ordinary items can select a different
        // slot or a different fluid fake item.
        return left.getItem() == right.getItem()
                && isDamageableItem(left.getItem())
                && ItemStack.areItemStackTagsEqual(
                        left.copy().setStackSize(1).createItemStack(),
                        right.copy().setStackSize(1).createItemStack());
    }

    private static boolean isDamageableItem(Item item) {
        // NBT-backed wear is detected from the pattern transition itself;
        // do not depend on a mod-specific item class or API here.
        return item != null && item.isDamageable();
    }

    private static long divideRoundUp(long required, long perCraft) {
        if (required <= 0L || perCraft <= 0L) {
            return 0L;
        }
        return ((required - 1L) / perCraft) + 1L;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        return left > LONG_MAX / right ? LONG_MAX : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return right > LONG_MAX - left ? LONG_MAX : left + right;
    }

    private static long checkedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > LONG_MAX / right) {
            throw new QuantityLimitException(
                    "Crafting quantity exceeds the 1.12.2 long stack limit");
        }
        return left * right;
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0L || right < 0L || right > LONG_MAX - left) {
            throw new QuantityLimitException(
                    "Crafting quantity exceeds the 1.12.2 long stack limit");
        }
        return left + right;
    }

    private static IItemList<IAEItemStack> newItemList() {
        return AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class)
                .createList();
    }

    private static void addCount(IItemList<IAEItemStack> list,
                                 IAEItemStack key,
                                 long count) {
        if (count <= 0L) {
            return;
        }
        IAEItemStack existing = list.findPrecise(key);
        if (existing != null && existing.getStackSize() > LONG_MAX - count) {
            throw new QuantityLimitException(
                    "Crafting quantity exceeds the 1.12.2 long stack limit");
        }
        IAEItemStack copy = key.copy();
        copy.setStackSize(count);
        list.add(copy);
    }

    private static CalculationFallbackException unsupported(FallbackReason reason,
                                                              String message) {
        return new CalculationFallbackException(reason, message);
    }

    /** Creates a controlled fallback for the root integration bridge. */
    public static CalculationFallbackException fallback(FallbackReason reason,
                                                          String message) {
        return unsupported(reason, message);
    }

    /** Reasons that are safe to expose to the localized status overlay. */
    public enum FallbackReason {
        UNSUPPORTED_PATTERN("unsupported_pattern"),
        INVALID_OUTPUT("invalid_output"),
        SUBSTITUTION_CONTAINER("substitution_container"),
        NON_LINEAR_CONTAINER("non_linear_container"),
        DAMAGEABLE_ALLOCATION("damageable_allocation"),
        CYCLE_NOT_PROVABLE("cycle_not_provable"),
        CYCLE_NO_SEED("cycle_no_seed"),
        CYCLE_NEUTRAL("cycle_neutral"),
        CYCLE_DISSIPATIVE("cycle_dissipative"),
        CYCLE_TOO_COMPLEX("cycle_too_complex"),
        CYCLE_EXTERNAL_RECURSION("cycle_external_recursion"),
        QUANTITY_LIMIT("quantity_limit");

        private final String translationSuffix;

        FallbackReason(String translationSuffix) {
            this.translationSuffix = translationSuffix;
        }

        public String getTranslationSuffix() {
            return translationSuffix;
        }
    }

    /** Controlled fallback carrying a reason instead of relying on log text. */
    public static class CalculationFallbackException extends UnsupportedOperationException {
        private static final long serialVersionUID = 1L;
        private final FallbackReason reason;

        private CalculationFallbackException(FallbackReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public FallbackReason getReason() {
            return reason;
        }
    }

    /**
     * The native 1.12.2 crafting API cannot carry a larger quantity. Keeping
     * this separate from ordinary unsupported-pattern failures prevents a
     * huge request from falling back into AE2's unbounded recursive search.
     */
    public static final class QuantityLimitException extends CalculationFallbackException {
        private static final long serialVersionUID = 1L;

        private QuantityLimitException(String message) {
            super(FallbackReason.QUANTITY_LIMIT, message);
        }
    }

    private static final class PatternInfo {
        private final ICraftingPatternDetails pattern;
        private final InputInfo[] inputs;
        private final OutputInfo[] outputs;

        private PatternInfo(ICraftingPatternDetails pattern,
                            InputInfo[] inputs,
                            OutputInfo[] outputs) {
            this.pattern = pattern;
            this.inputs = inputs;
            this.outputs = outputs;
        }
    }

    private static final class CycleStepInfo {
        private final PatternInfo pattern;
        private final IAEItemStack fromKey;
        private final IAEItemStack toKey;
        private final long inputAmount;
        private final long outputAmount;

        private CycleStepInfo(PatternInfo pattern,
                              IAEItemStack fromKey,
                              IAEItemStack toKey,
                              long inputAmount,
                              long outputAmount) {
            this.pattern = pattern;
            this.fromKey = fromKey.copy();
            this.fromKey.reset();
            this.toKey = toKey.copy();
            this.toKey.reset();
            this.inputAmount = inputAmount;
            this.outputAmount = outputAmount;
        }
    }

    private static final class CyclePlan {
        private final List<CycleStepInfo> steps;
        private final Set<IAEItemStack> cycleKeys;
        private final long seed;
        private final long netGain;
        private final long[] timesPerRound;

        private CyclePlan(List<CycleStepInfo> steps,
                          Set<IAEItemStack> cycleKeys,
                          long seed,
                          long netGain,
                          long[] timesPerRound) {
            this.steps = steps;
            this.cycleKeys = cycleKeys;
            this.seed = seed;
            this.netGain = netGain;
            this.timesPerRound = timesPerRound;
        }
    }

    private static final class CycleNeed {
        private final InputInfo input;
        private long required;

        private CycleNeed(InputInfo input, long required) {
            this.input = input;
            this.required = required;
        }
    }

    private static final class CycleSearchBudget {
        private int remaining;

        private CycleSearchBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean consume() {
            return remaining-- > 0;
        }
    }

    private enum CycleDependencyStatus {
        SAFE,
        ACTIVE,
        UNPROVABLE
    }

    private static final class Fraction {
        private static final Fraction ONE = new Fraction(
                BigInteger.ONE, BigInteger.ONE);

        private final BigInteger numerator;
        private final BigInteger denominator;

        private Fraction(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) {
                throw new IllegalArgumentException("zero fraction denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        private Fraction multiply(long value) {
            return new Fraction(numerator.multiply(BigInteger.valueOf(value)),
                    denominator);
        }

        private Fraction divide(long value) {
            return new Fraction(numerator,
                    denominator.multiply(BigInteger.valueOf(value)));
        }
    }

    private static final class InputInfo {
        private final ICraftingPatternDetails pattern;
        private final int slot;
        private final IAEItemStack input;
        private final IAEItemStack key;
        private final long perCraft;
        private final IAEItemStack container;
        private final DurabilityInfo durability;
        private final boolean returnedByPattern;
        private final boolean reusable;
        private final InputOption[] options;

        private InputInfo(ICraftingPatternDetails pattern,
                          int slot,
                          IAEItemStack input,
                          long perCraft,
                          IAEItemStack container,
                          DurabilityInfo durability,
                          boolean returnedByPattern,
                          boolean reusable,
                          InputOption[] options) {
            this.pattern = pattern;
            this.slot = slot;
            this.input = input;
            this.key = input.copy();
            this.key.reset();
            this.perCraft = perCraft;
            this.container = container;
            this.durability = durability;
            this.returnedByPattern = returnedByPattern;
            this.reusable = reusable;
            this.options = options;
        }
    }

    private static final class InputOption {
        private final IAEItemStack input;
        private final IAEItemStack key;
        private final IAEItemStack container;
        private final DurabilityInfo durability;

        private InputOption(IAEItemStack input,
                            IAEItemStack container,
                            DurabilityInfo durability) {
            this.input = input;
            this.key = input.copy();
            this.key.reset();
            this.container = container;
            this.durability = durability;
        }
    }

    private static final class ContainerInfo {
        private final IAEItemStack stack;
        private final DurabilityInfo durability;

        private ContainerInfo(IAEItemStack stack, DurabilityInfo durability) {
            this.stack = stack;
            this.durability = durability;
        }
    }

    private static final class DurabilityInfo {
        private final long maxDamage;
        private final long damageStep;
        private final NbtPath nbtPath;
        private final NbtNumberType nbtType;

        private DurabilityInfo(int maxDamage, int damageStep) {
            this((long) maxDamage, (long) damageStep, null, null);
        }

        private DurabilityInfo(long maxDamage,
                               long damageStep,
                               NbtPath nbtPath,
                               NbtNumberType nbtType) {
            this.maxDamage = maxDamage;
            this.damageStep = damageStep;
            this.nbtPath = nbtPath;
            this.nbtType = nbtType;
        }

        private static DurabilityInfo nbt(long maxDamage,
                                          long damageStep,
                                          NbtPath path,
                                          NbtNumberType type) {
            return new DurabilityInfo(maxDamage, damageStep, path, type);
        }

        private boolean isNbt() {
            return nbtPath != null;
        }

        private long currentDamage(IAEItemStack stack) {
            if (stack == null) {
                return 0L;
            }
            ItemStack itemStack = stack.copy().setStackSize(1).createItemStack();
            return currentDamage(itemStack);
        }

        private long currentDamage(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return -1L;
            }
            if (!isNbt()) {
                return stack.getItemDamage();
            }
            NBTBase value = getTagAtPath(stack.getTagCompound(), nbtPath);
            if (value == null) {
                // A missing numeric field is the zero-damage state. This is
                // needed for tools whose pristine stack omits Dmg entirely.
                return 0L;
            }
            return readNumericValue(value) == null
                    ? -1L : readNumericValue(value).longValue();
        }

        private long capacity(IAEItemStack stack) {
            long damage = currentDamage(stack);
            if (damage < 0L || damage >= maxDamage || damageStep <= 0L) {
                return 0L;
            }
            return (maxDamage - damage) / damageStep;
        }

        private boolean sameTransition(DurabilityInfo other) {
            if (other == null || maxDamage != other.maxDamage
                    || damageStep != other.damageStep) {
                return false;
            }
            if (nbtPath == null || other.nbtPath == null) {
                return nbtPath == other.nbtPath;
            }
            return nbtType == other.nbtType
                    && Arrays.equals(nbtPath.parts, other.nbtPath.parts);
        }

        private boolean matchesReturned(IAEItemStack input,
                                        IAEItemStack returned) {
            if (!isNbt() || input == null || returned == null
                    || !sameItemAndDamage(input, returned)) {
                return false;
            }
            ItemStack inputStack = input.copy().setStackSize(1).createItemStack();
            ItemStack returnedStack = returned.copy().setStackSize(1)
                    .createItemStack();
            long inputDamage = currentDamage(inputStack);
            long returnedDamage = currentDamage(returnedStack);
            if (inputDamage < 0L || returnedDamage < 0L
                    || inputDamage > LONG_MAX - damageStep) {
                return false;
            }
            return returnedDamage == inputDamage + damageStep
                    && sameNbtExceptPath(inputStack.getTagCompound(),
                    returnedStack.getTagCompound(), nbtPath);
        }

        @Override
        public String toString() {
            return isNbt() ? String.valueOf(nbtPath) : "item_damage";
        }
    }

    private static final class NbtDurabilityTransition {
        private final DurabilityInfo durability;
        private final IAEItemStack output;

        private NbtDurabilityTransition(DurabilityInfo durability,
                                        IAEItemStack output) {
            this.durability = durability;
            this.output = output;
        }
    }

    private interface NbtMaxDamageResolver {
        long resolve(ItemStack input,
                     ItemStack output,
                     NbtPath path,
                     NbtNumberType type,
                     long step);
    }

    private static final class NbtPath {
        private final String[] parts;

        private NbtPath(List<String> parts) {
            this.parts = parts.toArray(new String[parts.size()]);
        }

        @Override
        public String toString() {
            return Arrays.toString(parts);
        }
    }

    private enum NbtNumberType {
        BYTE,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE;

        private static NbtNumberType from(NBTBase value) {
            if (value instanceof NBTTagByte) {
                return BYTE;
            }
            if (value instanceof NBTTagShort) {
                return SHORT;
            }
            if (value instanceof NBTTagInt) {
                return INT;
            }
            if (value instanceof NBTTagLong) {
                return LONG;
            }
            if (value instanceof NBTTagFloat) {
                return FLOAT;
            }
            if (value instanceof NBTTagDouble) {
                return DOUBLE;
            }
            return null;
        }

        private Long read(NBTBase value) {
            double asDouble;
            switch (this) {
                case BYTE:
                    return Long.valueOf(((NBTTagByte) value).getByte());
                case SHORT:
                    return Long.valueOf(((NBTTagShort) value).getShort());
                case INT:
                    return Long.valueOf(((NBTTagInt) value).getInt());
                case LONG:
                    return Long.valueOf(((NBTTagLong) value).getLong());
                case FLOAT:
                    asDouble = ((NBTTagFloat) value).getFloat();
                    break;
                case DOUBLE:
                    asDouble = ((NBTTagDouble) value).getDouble();
                    break;
                default:
                    return null;
            }
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)
                    || asDouble != Math.rint(asDouble)
                    || asDouble < Long.MIN_VALUE
                    || asDouble > Long.MAX_VALUE) {
                return null;
            }
            return Long.valueOf((long) asDouble);
        }

        private NBTBase write(long value) {
            switch (this) {
                case BYTE:
                    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                        return null;
                    }
                    return new NBTTagByte((byte) value);
                case SHORT:
                    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                        return null;
                    }
                    return new NBTTagShort((short) value);
                case INT:
                    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                        return null;
                    }
                    return new NBTTagInt((int) value);
                case LONG:
                    return new NBTTagLong(value);
                case FLOAT:
                    float asFloat = (float) value;
                    return Float.isInfinite(asFloat) || Float.isNaN(asFloat)
                            || (long) asFloat != value
                            ? null : new NBTTagFloat(asFloat);
                case DOUBLE:
                    double asDouble = (double) value;
                    return Double.isInfinite(asDouble) || Double.isNaN(asDouble)
                            || (long) asDouble != value
                            ? null : new NBTTagDouble(asDouble);
                default:
                    return null;
            }
        }
    }

    private static final class OutputInfo {
        private final IAEItemStack output;
        private final long amount;

        private OutputInfo(IAEItemStack output, long amount) {
            this.output = output;
            this.amount = amount;
        }
    }

    private static final class PatternFrame {
        private final PatternInfo pattern;
        private final long crafts;
        private final IAEItemStack outputKey;
        private final List<PendingReturn> pendingReturns =
                new ArrayList<PendingReturn>();
        private int inputIndex;
        private boolean started;
        private InputContinuation continuation;

        private PatternFrame(PatternInfo pattern,
                             long crafts,
                             IAEItemStack outputKey) {
            this.pattern = pattern;
            this.crafts = crafts;
            this.outputKey = outputKey;
        }
    }

    private static final class PendingReturn {
        private final IAEItemStack stack;
        private long amount;

        private PendingReturn(IAEItemStack stack, long amount) {
            this.stack = stack;
            this.amount = amount;
        }
    }

    private static final class InputContinuation {
        private final IAEItemStack input;
        private final IAEItemStack container;
        private final long required;
        private final boolean returnContainer;
        private final boolean deferContainerReturn;
        private final InputInfo reusableInput;
        private final long requiredUses;
        private final boolean durable;

        private InputContinuation(IAEItemStack input,
                                  IAEItemStack container,
                                  long required,
                                  boolean returnContainer,
                                  boolean deferContainerReturn,
                                  InputInfo reusableInput,
                                  long requiredUses,
                                  boolean durable) {
            this.input = input;
            this.container = container;
            this.required = required;
            this.returnContainer = returnContainer;
            this.deferContainerReturn = deferContainerReturn;
            this.reusableInput = reusableInput;
            this.requiredUses = requiredUses;
            this.durable = durable;
        }

        private static InputContinuation normal(IAEItemStack input,
                                                IAEItemStack container,
                                                long required,
                                                boolean returnContainer) {
            return new InputContinuation(input, container, required, returnContainer, false,
                    null, 0L, false);
        }

        private static InputContinuation returned(IAEItemStack input,
                                                  IAEItemStack container,
                                                  long required) {
            return new InputContinuation(input, container, required, true, true,
                    null, 0L, false);
        }

        private static InputContinuation durable(InputInfo input,
                                                 long requiredUses) {
            return new InputContinuation(null, null, 0L, false, false,
                    input, requiredUses, true);
        }
    }

    private static final class PatternChoice {
        private final boolean external;
        private final ICraftingPatternDetails pattern;
        private final long outputAmount;

        private PatternChoice(boolean external,
                              ICraftingPatternDetails pattern,
                              long outputAmount) {
            this.external = external;
            this.pattern = pattern;
            this.outputAmount = outputAmount;
        }
    }

    /** Result consumed by the 1.12.2 CraftingJob tree adapter. */
    public static final class Result {
        private final IAEItemStack output;
        private final long bytes;
        private final IItemList<IAEItemStack> usedItems;
        private final IItemList<IAEItemStack> missingItems;
        private final IItemList<IAEItemStack> emittedItems;
        private final Map<ICraftingPatternDetails, Long> patternTimes;
        private final boolean cycleOptimized;
        private final String debugTag;

        private Result(IAEItemStack output,
                       long bytes,
                       IItemList<IAEItemStack> usedItems,
                       IItemList<IAEItemStack> missingItems,
                       IItemList<IAEItemStack> emittedItems,
                       Map<ICraftingPatternDetails, Long> patternTimes,
                       boolean cycleOptimized,
                       String debugTag) {
            this.output = output;
            this.bytes = bytes;
            this.usedItems = usedItems;
            this.missingItems = missingItems;
            this.emittedItems = emittedItems;
            this.patternTimes = new LinkedHashMap<ICraftingPatternDetails, Long>(patternTimes);
            this.cycleOptimized = cycleOptimized;
            this.debugTag = debugTag == null ? "unbound" : debugTag;
        }

        public static Result direct(IAEItemStack output, long amount, boolean external) {
            IItemList<IAEItemStack> used = newItemList();
            IItemList<IAEItemStack> missing = newItemList();
            IItemList<IAEItemStack> emitted = newItemList();
            IAEItemStack key = output.copy();
            key.reset();
            if (external) {
                IAEItemStack provided = key.copy();
                provided.setStackSize(amount);
                emitted.add(provided);
            } else {
                IAEItemStack absent = key.copy();
                absent.setStackSize(amount);
                missing.add(absent);
            }
            IAEItemStack finalOutput = key.copy();
            finalOutput.setStackSize(amount);
            return new Result(finalOutput, amount, used, missing, emitted,
                    new LinkedHashMap<ICraftingPatternDetails, Long>(), false,
                    "direct");
        }

        public boolean hasMissingItems() {
            return !missingItems.isEmpty();
        }

        public IAEItemStack getOutput() {
            return output;
        }

        public boolean isCycleOptimized() {
            return cycleOptimized;
        }

        public long getBytes() {
            return bytes;
        }

        public int getPatternCount() {
            return patternTimes.size();
        }

        public Map<ICraftingPatternDetails, Long> getPatternTimes() {
            return new LinkedHashMap<ICraftingPatternDetails, Long>(patternTimes);
        }

        public IItemList<IAEItemStack> getUsedItems() {
            return usedItems;
        }

        public IItemList<IAEItemStack> getMissingItems() {
            return missingItems;
        }

        public IItemList<IAEItemStack> getEmittedItems() {
            return emittedItems;
        }

        public void populatePlan(IItemList<IAEItemStack> plan) {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] populatePlan missing={} used={} emitted={} patterns={}",
                    debugTag,
                    AE2FluidCraftCompat.debugList(missingItems, false),
                    AE2FluidCraftCompat.debugList(usedItems, false),
                    AE2FluidCraftCompat.debugList(emittedItems, false),
                    patternTimes.size());
            for (IAEItemStack missing : missingItems) {
                addPlanStorage(plan, missing);
            }
            for (IAEItemStack used : usedItems) {
                addPlanStorage(plan, used);
            }
            for (IAEItemStack emitted : emittedItems) {
                IAEItemStack requestable = normalizeForPlan(emitted);
                if (requestable == null) {
                    continue;
                }
                requestable.setCountRequestable(requestable.getStackSize());
                plan.addRequestable(requestable);
            }
            for (Map.Entry<ICraftingPatternDetails, Long> entry : patternTimes.entrySet()) {
                long crafts = entry.getValue();
                IAEItemStack[] outputs = getCalculationOutputs(entry.getKey());
                if (outputs == null) {
                    continue;
                }
                for (IAEItemStack output : outputs) {
                    if (output == null || output.getStackSize() <= 0L) {
                        continue;
                    }
                    IAEItemStack requestable = normalizeForPlan(output);
                    if (requestable == null) {
                        continue;
                    }
                    requestable.setCountRequestable(
                            checkedMultiply(output.getStackSize(), crafts));
                    plan.addRequestable(requestable);
                }
            }
        }

        private static void addPlanStorage(IItemList<IAEItemStack> plan,
                                            IAEItemStack stack) {
            IAEItemStack normalized = normalizeForPlan(stack);
            if (normalized == null || normalized.getStackSize() <= 0L) {
                return;
            }
            normalized.setCountRequestable(0L);
            normalized.setCraftable(false);
            plan.add(normalized);
        }

        private static IAEItemStack normalizeForPlan(IAEItemStack stack) {
            if (stack == null) {
                return null;
            }
            IAEItemStack normalized = normalizeForCalculation(stack);
            // A calculation cannot silently lose a deficit. Invalid AE2FC
            // data is normally rejected before a Result is created, but keep
            // the original key here as a final planning invariant.
            return normalized == null ? stack.copy() : normalized;
        }

        public void apply(MECraftingInventory storage,
                          CraftingCPUCluster cpu,
                          IActionSource source) throws CraftBranchFailure {
            AE2QuickCalculation.LOGGER.info(
                    "[QCALC][{}] apply used={} emitted={} patterns={}",
                    debugTag,
                    AE2FluidCraftCompat.debugList(usedItems, false),
                    AE2FluidCraftCompat.debugList(emittedItems, false),
                    patternTimes.size());
            // Validate the complete extraction set before mutating the
            // transaction inventory, matching CraftingTreeNode's two-phase behavior.
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(
                        request, Actionable.SIMULATE, source);
                if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                    AE2QuickCalculation.LOGGER.warn(
                            "[QCALC][{}] apply simulation extraction failed request={} extracted={}",
                            debugTag,
                            AE2FluidCraftCompat.debugStack(request),
                            AE2FluidCraftCompat.debugStack(extracted));
                    throw new CraftBranchFailure(request, request.getStackSize());
                }
            }
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(
                        request, Actionable.MODULATE, source);
                if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                    AE2QuickCalculation.LOGGER.warn(
                            "[QCALC][{}] apply modulation extraction failed request={} extracted={}",
                            debugTag,
                            AE2FluidCraftCompat.debugStack(request),
                            AE2FluidCraftCompat.debugStack(extracted));
                    throw new CraftBranchFailure(request, request.getStackSize());
                }
                cpu.addStorage(extracted);
            }
            for (IAEItemStack emitted : emittedItems) {
                cpu.addEmitable(emitted.copy());
            }
            for (Map.Entry<ICraftingPatternDetails, Long> entry : patternTimes.entrySet()) {
                cpu.addCrafting(entry.getKey(), entry.getValue());
            }
        }

    }
}
