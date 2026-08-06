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
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private final ICraftingGrid grid;
    private final World world;

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
        this.grid = grid;
        this.world = world;
    }

    public Result calculate(ICraftingPatternDetails rootPattern,
                            long requestedAmount,
                            MECraftingInventory inventory) {
        if (!isSupported(rootPattern)) {
            throw unsupported(FallbackReason.UNSUPPORTED_PATTERN,
                    "Pattern requires AE2 native slot handling: " + rootPattern);
        }

        IAEItemStack output = rootPattern.getPrimaryOutput();
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
        return new Result(finalOutput, bytes, usedItems, missingItems,
                emittedItems, patternTimes, cycleOptimized);
    }

    private void craftPattern(PatternInfo rootPattern, long crafts) {
        if (crafts <= 0L) {
            return;
        }
        Deque<PatternFrame> frames = new ArrayDeque<PatternFrame>();
        IAEItemStack rootKey = rootPattern.pattern.getPrimaryOutput().copy();
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
                                input.durability.capacity(input.input.getItemDamage()));
                        frame.continuation = InputContinuation.durable(input, missingUses);
                        scheduleRequest(input.key, freshItems, frames);
                    }
                } else if (input.container == null) {
                    long missing = acquireNormalInput(input, totalRequired);
                    if (missing > 0L) {
                        InputOption option = selectCraftingOption(input);
                        frame.continuation = InputContinuation.normal(
                                option.key, null, missing, false);
                        scheduleRequest(option.key, missing, frames);
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
                        scheduleRequest(option.key, missing, frames);
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
                        PatternChoice choice = resolvePattern(option.key);
                        long minimumCopies = Math.min(totalRequired, input.perCraft);
                        long requestAmount = choice.external || choice.pattern != null
                                ? missingForParallelBatch
                                : Math.max(0L, minimumCopies - acquiredForBatch);
                        if (requestAmount > 0L) {
                            frame.continuation = InputContinuation.normal(
                                    option.key, option.container,
                                    requestAmount, true);
                            scheduleRequest(option.key, requestAmount, frames);
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

        IAEItemStack rootKey = rootPattern.pattern.getPrimaryOutput().copy();
        rootKey.reset();
        List<CycleStepInfo> candidate = findRootCycle(rootPattern, rootKey);
        if (candidate == null) {
            return false;
        }

        CyclePlan plan = buildCyclePlan(candidate, rootKey);
        executeCycle(plan, rootKey, requested);
        return true;
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

        for (ICraftingPatternDetails candidate
                : grid.getCraftingFor(currentKey, null, -1, world)) {
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
        PatternChoice choice = resolvePattern(option.key);
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
            if (input.returnedByPattern && sameKey(input.key, output)) {
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
            PatternChoice choice = resolvePattern(option.key);
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
        List<IAEItemStack> candidates = null;
        IAEItemStack onlyCandidate = null;
        for (IAEItemStack candidate : fuzzyCandidates) {
            if (isValidDurableCandidate(input, candidate)
                    && input.durability.capacity(candidate.getItemDamage()) > 0L) {
                if (onlyCandidate == null) {
                    onlyCandidate = candidate;
                } else {
                    if (candidates == null) {
                        candidates = new ArrayList<IAEItemStack>();
                        candidates.add(onlyCandidate);
                    }
                    candidates.add(candidate);
                }
            }
        }

        if (candidates == null) {
            if (onlyCandidate == null) {
                return requiredUses;
            }
            return consumeDurableCandidate(input, onlyCandidate, requiredUses, fromNetwork);
        }

        // Prefer the least damaged copies. This minimizes the number of fresh
        // tools requested when the network contains mixed durability states.
        Collections.sort(candidates, new Comparator<IAEItemStack>() {
            @Override
            public int compare(IAEItemStack left, IAEItemStack right) {
                return Integer.compare(left.getItemDamage(), right.getItemDamage());
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

    private long consumeDurableCandidate(InputInfo input,
                                         IAEItemStack candidate,
                                         long remaining,
                                         boolean fromNetwork) {
        if (remaining <= 0L) {
            return 0L;
        }

        long count = Math.max(0L, candidate.getStackSize());
        long capacity = input.durability.capacity(candidate.getItemDamage());
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
                || !ItemStack.areItemStackTagsEqual(expected, actual)) {
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

        int currentDamage = current.getItemDamage();
        long targetDamageLong = (long) currentDamage
                + uses * (long) durability.damageStep;
        if (targetDamageLong >= durability.maxDamage
                || targetDamageLong > Integer.MAX_VALUE) {
            return null;
        }

        ItemStack currentStack = current.copy().setStackSize(1).createItemStack();
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
        if (required <= 0L) {
            return;
        }

        PatternChoice choice = resolvePattern(normalizedKey);
        if (choice.external) {
            provideExternal(normalizedKey, required);
            return;
        }
        if (choice.pattern == null) {
            addCount(missingItems, normalizedKey, required);
            addBytes(required);
            return;
        }

        long crafts = divideRoundUp(required, choice.outputAmount);
        IAEItemStack childKey = normalizedKey.copy();
        if (!activeKeys.add(childKey)) {
            throw unsupported(FallbackReason.CYCLE_NOT_PROVABLE,
                    "Crafting dependency cycle detected for " + normalizedKey);
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
        IAEItemStack[] patternOutputs = pattern.getOutputs();
        if (patternOutputs != null) {
            for (IAEItemStack output : patternOutputs) {
                if (output != null && output.getStackSize() > 0L) {
                    outputs.add(new OutputInfo(output, output.getStackSize()));
                }
            }
        }

        List<InputInfo> inputs = new ArrayList<InputInfo>();
        IAEItemStack[] condensedInputs = pattern.getCondensedInputs();
        for (IAEItemStack input : condensedInputs) {
            if (input == null || input.getStackSize() <= 0L) {
                continue;
            }
            ContainerInfo container = inspectContainer(input);
            boolean returnedByPattern = false;
            IAEItemStack returned = findExactReturnedInput(
                    pattern.getOutputs(), input);
            if (returned == null) {
                // Some third-party pattern details expose the clean output
                // view more accurately than their slot-preserving view.
                returned = findExactReturnedInput(
                        pattern.getCondensedOutputs(), input);
            }
            if (returned != null && (container == null
                    || (container.durability == null
                    && sameReusableContainer(input, container.stack)))) {
                if (container == null) {
                    container = new ContainerInfo(returned, null);
                }
                returnedByPattern = true;
            }
            DurabilityInfo durability = container == null ? null : container.durability;
            boolean reusable = container != null
                    && durability == null
                    && sameReusableContainer(input, container.stack);
            int[] slots = findInputSlots(pattern, input);
            int slot = slots.length == 0 ? -1 : slots[0];
            inputs.add(new InputInfo(
                    pattern,
                    slot,
                    input,
                    input.getStackSize(),
                    container == null ? null : container.stack,
                    durability,
                    returnedByPattern,
                    reusable,
                    buildInputOptions(pattern, input, slots, container, durability)));
        }

        PatternInfo info = new PatternInfo(
                pattern,
                inputs.toArray(new InputInfo[inputs.size()]),
                outputs.toArray(new OutputInfo[outputs.size()]));
        patternInfoCache.put(pattern, info);
        return info;
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

    private InputOption[] buildInputOptions(ICraftingPatternDetails pattern,
                                             IAEItemStack input,
                                             int[] slots,
                                             ContainerInfo primaryContainer,
                                             DurabilityInfo primaryDurability) {
        List<InputOption> options = new ArrayList<InputOption>();
        options.add(new InputOption(input,
                primaryContainer == null ? null : primaryContainer.stack,
                primaryDurability));

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
        PatternChoice cached = patternCache.get(key);
        if (cached != null) {
            return cached;
        }

        if (grid.canEmitFor(key)) {
            PatternChoice external = new PatternChoice(true, null, 0L);
            patternCache.put(key, external);
            return external;
        }

        ICraftingPatternDetails pattern = null;
        long outputAmount = 0L;
        for (ICraftingPatternDetails candidate : grid.getCraftingFor(key, null, -1, world)) {
            IAEItemStack[] outputs = candidate.getOutputs();
            if (outputs == null) {
                continue;
            }
            for (IAEItemStack output : outputs) {
                if (output != null && output.getStackSize() > 0L
                        && output.isSameType(key)) {
                    pattern = candidate;
                    outputAmount = output.getStackSize();
                    break;
                }
            }
            if (pattern != null) {
                break;
            }
        }

        PatternChoice choice = new PatternChoice(false, pattern, outputAmount);
        patternCache.put(key, choice);
        return choice;
    }

    private void provideExternal(IAEItemStack key, long amount) {
        if (amount <= 0L) {
            return;
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

        IAEItemStack[] inputs = pattern.getCondensedInputs();
        if (inputs == null) {
            return false;
        }

        for (IAEItemStack input : inputs) {
            if (input == null || input.getStackSize() <= 0L) {
                continue;
            }
            Item item = input.getItem();
            if (item == null) {
                return false;
            }

            ContainerInfo container = inspectContainer(input);
            if (item.hasContainerItem(input.getDefinition()) && container == null) {
                // Treat an unrecognised container transition as consumed input
                // would be incorrect, so leave it to AE2's native path.
                return false;
            }

            if (container != null
                    && !sameReusableContainer(input, container.stack)
                    && !pattern.isCraftable()) {
                // AE2's 1.12 processing executor does not return container
                // items after a processing operation. Vanilla crafting does,
                // so only that path can safely provide a different container.
                return false;
            }

            if (isDamageableItem(item)) {
                if (container == null || container.durability == null
                        || input.getStackSize() != 1L) {
                    return false;
                }
            }
        }
        return true;
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
        if (item == null || !item.hasContainerItem(input.getDefinition())) {
            return null;
        }

        ItemStack source = input.copy().setStackSize(1).createItemStack();
        ItemStack container = Platform.getContainerItem(source.copy());
        if (container == null || container.isEmpty()) {
            return null;
        }

        DurabilityInfo durability = null;
        if (isDamageableItem(item)) {
            int currentDamage = source.getItemDamage();
            int maxDamage = source.getMaxDamage();
            int nextDamage = container.getItemDamage();
            if (container.getItem() != item
                    || !ItemStack.areItemStackTagsEqual(source, container)
                    || maxDamage <= currentDamage
                    || nextDamage <= currentDamage
                    || nextDamage > maxDamage
                    || nextDamage - currentDamage != 1) {
                return null;
            }
            durability = new DurabilityInfo(maxDamage, nextDamage - currentDamage);
        }

        IAEItemStack stack = AEItemStack.fromItemStack(container);
        return stack == null ? null : new ContainerInfo(stack, durability);
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
            if (input != null && sameItemAndTags(input, condensedInput)) {
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
        return item != null && (item.isDamageable() || Platform.isGTDamageableItem(item));
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
        private final int maxDamage;
        private final int damageStep;

        private DurabilityInfo(int maxDamage, int damageStep) {
            this.maxDamage = maxDamage;
            this.damageStep = damageStep;
        }

        private long capacity(int damage) {
            if (damage < 0 || damage >= maxDamage || damageStep <= 0) {
                return 0L;
            }
            return (maxDamage - (long) damage) / damageStep;
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

        private Result(IAEItemStack output,
                       long bytes,
                       IItemList<IAEItemStack> usedItems,
                       IItemList<IAEItemStack> missingItems,
                       IItemList<IAEItemStack> emittedItems,
                       Map<ICraftingPatternDetails, Long> patternTimes,
                       boolean cycleOptimized) {
            this.output = output;
            this.bytes = bytes;
            this.usedItems = usedItems;
            this.missingItems = missingItems;
            this.emittedItems = emittedItems;
            this.patternTimes = new LinkedHashMap<ICraftingPatternDetails, Long>(patternTimes);
            this.cycleOptimized = cycleOptimized;
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
                    new LinkedHashMap<ICraftingPatternDetails, Long>(), false);
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
            for (IAEItemStack missing : missingItems) {
                plan.add(missing.copy());
            }
            for (IAEItemStack used : usedItems) {
                plan.add(used.copy());
            }
            for (IAEItemStack emitted : emittedItems) {
                IAEItemStack requestable = emitted.copy();
                requestable.setCountRequestable(requestable.getStackSize());
                plan.addRequestable(requestable);
            }
            for (Map.Entry<ICraftingPatternDetails, Long> entry : patternTimes.entrySet()) {
                long crafts = entry.getValue();
                IAEItemStack[] outputs = entry.getKey().getOutputs();
                if (outputs == null) {
                    continue;
                }
                for (IAEItemStack output : outputs) {
                    if (output == null || output.getStackSize() <= 0L) {
                        continue;
                    }
                    IAEItemStack requestable = output.copy();
                    requestable.setCountRequestable(
                            checkedMultiply(output.getStackSize(), crafts));
                    plan.addRequestable(requestable);
                }
            }
        }

        public void apply(MECraftingInventory storage,
                          CraftingCPUCluster cpu,
                          IActionSource source) throws CraftBranchFailure {
            // Validate the complete extraction set before mutating the
            // transaction inventory, matching CraftingTreeNode's two-phase behavior.
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(
                        request, Actionable.SIMULATE, source);
                if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                    throw new CraftBranchFailure(request, request.getStackSize());
                }
            }
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(
                        request, Actionable.MODULATE, source);
                if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
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
