package com.ae2vm.addon.vm;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.MECraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;
import net.minecraft.world.World;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Iterative stack VM for AE2UEL crafting calculations.
 *
 * The interpreter never recurses through the crafting tree. Calls are stored
 * in an explicit frame stack, while quantities use BigInteger until they are
 * converted to AE2's long-based 1.12.2 API.
 */
public final class CraftingVM {
    private static final int MAX_STACK = 1024;
    private static final int MAX_CALL_DEPTH = 1024;
    private static final BigInteger BIG_ZERO = BigInteger.ZERO;
    private static final BigInteger BIG_ONE = BigInteger.ONE;
    private static final BigInteger BIG_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger BIG_EIGHT = BigInteger.valueOf(8L);

    private final ICraftingGrid grid;
    private final World world;

    private BigInteger[] stack;
    private int stackPointer;
    private byte[] code;
    private int programCounter;
    private IAEItemStack[] constantPool;
    private ICraftingPatternDetails[] patternPool;
    private Deque<CallFrame> callStack;

    private MECraftingInventory inventory;
    private IActionSource actionSource;
    private IItemList<IAEItemStack> usedItems;
    private IItemList<IAEItemStack> missingItems;
    private IItemList<IAEItemStack> emittedItems;
    private IItemList<IAEItemStack> internalItems;
    private Map<ICraftingPatternDetails, Long> patternTimes;
    private Set<IAEItemStack> resolvingKeys;
    private long bytes;

    public CraftingVM(ICraftingGrid grid, World world) {
        this.grid = grid;
        this.world = world;
    }

    public Result execute(CraftingBytecode request,
                          MECraftingInventory inventory,
                          IActionSource actionSource) {
        this.stack = new BigInteger[MAX_STACK];
        this.stackPointer = 0;
        this.code = null;
        this.programCounter = 0;
        this.constantPool = null;
        this.patternPool = null;
        this.callStack = new ArrayDeque<CallFrame>();
        this.inventory = inventory;
        this.actionSource = actionSource;
        this.usedItems = newItemList();
        this.missingItems = newItemList();
        this.emittedItems = newItemList();
        this.internalItems = newItemList();
        this.patternTimes = new LinkedHashMap<ICraftingPatternDetails, Long>();
        this.resolvingKeys = new HashSet<IAEItemStack>();
        this.bytes = 0L;

        load(request);
        while (programCounter < code.length) {
            executeInstruction();
        }
        return buildResult(request);
    }

    private void executeInstruction() {
        int operation = code[programCounter++] & 0xFF;
        switch (operation) {
            case 0:
                readShort();
                push(pop().multiply(BigInteger.valueOf(readLong())));
                break;
            case 1:
                push(BigInteger.valueOf(readLong()));
                break;
            case 2:
                push(pop().add(pop()));
                break;
            case 3: {
                BigInteger right = pop();
                BigInteger left = pop();
                push(left.subtract(right));
                break;
            }
            case 4:
                push(pop().multiply(pop()));
                break;
            case 5: {
                BigInteger perCraft = pop();
                BigInteger required = pop();
                if (required.signum() <= 0 || perCraft.signum() <= 0) {
                    push(BIG_ZERO);
                } else {
                    push(required.add(perCraft).subtract(BIG_ONE).divide(perCraft));
                }
                break;
            }
            case 6: {
                int index = readShort();
                IAEItemStack key = constantPool[index];
                BigInteger required = pop();
                if (required.signum() <= 0) {
                    push(BIG_ZERO);
                    break;
                }
                long got = extract(key, required);
                push(required.subtract(BigInteger.valueOf(got)).max(BIG_ZERO));
                break;
            }
            case 7:
                readShort();
                pop();
                break;
            case 8:
                readShort();
                pop();
                break;
            case 9: {
                int index = readShort();
                addCount(missingItems, constantPool[index], pop());
                break;
            }
            case 10:
                push(peek());
                break;
            case 11:
                pop();
                break;
            case 12: {
                BigInteger right = pop();
                BigInteger left = pop();
                push(right);
                push(left);
                break;
            }
            case 13: {
                int index = readShort();
                addPattern(patternPool[index], pop());
                break;
            }
            case 14:
                callCompiled(patternPool[readShort()], pop(), null);
                break;
            case 15:
                returnFromCall();
                break;
            case 16:
                callByKey(constantPool[readShort()], pop());
                break;
            case 17:
                insertOutput(constantPool[readShort()], pop());
                break;
            case 18: {
                int inputIndex = readShort();
                int containerIndex = readShort();
                long perCraft = readLong();
                extractReusableIngredient(constantPool[inputIndex],
                        constantPool[containerIndex], pop(), perCraft);
                break;
            }
            case 19: {
                int inputIndex = readShort();
                int containerIndex = readShort();
                extractContainer(constantPool[inputIndex], constantPool[containerIndex], pop());
                break;
            }
            case 255:
                programCounter = code.length;
                break;
            default:
                throw new UnsupportedOperationException("Unknown AE2 VM opcode: " + operation);
        }
    }

    private void callByKey(IAEItemStack key, BigInteger required) {
        if (required.signum() <= 0) {
            return;
        }

        IAEItemStack normalized = key.copy();
        normalized.reset();

        // AE2's native tree gives emitters precedence over crafting patterns.
        if (grid.canEmitFor(normalized)) {
            provideExternal(normalized, required);
            return;
        }

        ICraftingPatternDetails pattern = findPattern(normalized);
        if (pattern == null) {
            addCount(missingItems, normalized, required);
            addBytes(required);
            return;
        }

        if (!PatternCompiler.isSupported(pattern)) {
            throw new UnsupportedOperationException("Sub-pattern requires AE2 native slot handling: " + pattern);
        }

        CraftingBytecode compiled = PatternCompiler.getCompiled(pattern);
        if (compiled == null) {
            PatternCompiler.compileIfAbsent(pattern);
            compiled = PatternCompiler.getCompiled(pattern);
        }
        if (compiled == null) {
            throw new UnsupportedOperationException("Pattern compilation failed: " + pattern);
        }

        if (containsKey(resolvingKeys, normalized)) {
            consumeRecursiveStock(normalized, required);
            return;
        }
        if (callStack.size() >= MAX_CALL_DEPTH) {
            throw new UnsupportedOperationException("Crafting tree exceeded VM call depth");
        }

        long outputAmount = outputAmountFor(pattern, normalized);
        if (outputAmount <= 0) {
            throw new UnsupportedOperationException("Pattern does not produce requested output: " + normalized);
        }
        BigInteger crafts = required.add(BigInteger.valueOf(outputAmount))
                .subtract(BIG_ONE)
                .divide(BigInteger.valueOf(outputAmount));
        resolvingKeys.add(normalized);
        callStack.push(new CallFrame(programCounter, code, constantPool, patternPool, normalized));
        load(compiled);
        push(crafts);
    }

    private void callCompiled(ICraftingPatternDetails pattern,
                              BigInteger crafts,
                              IAEItemStack resolvingKey) {
        if (crafts.signum() <= 0) {
            return;
        }
        CraftingBytecode compiled = PatternCompiler.getCompiled(pattern);
        if (compiled == null) {
            PatternCompiler.compileIfAbsent(pattern);
            compiled = PatternCompiler.getCompiled(pattern);
        }
        if (compiled == null || callStack.size() >= MAX_CALL_DEPTH) {
            throw new UnsupportedOperationException("Crafting VM call could not be dispatched");
        }
        callStack.push(new CallFrame(programCounter, code, constantPool, patternPool, resolvingKey));
        load(compiled);
        push(crafts);
    }

    private void returnFromCall() {
        if (callStack.isEmpty()) {
            programCounter = code.length;
            return;
        }
        CallFrame frame = callStack.pop();
        if (frame.resolvingKey != null) {
            removeKey(resolvingKeys, frame.resolvingKey);
        }
        this.code = frame.code;
        this.constantPool = frame.constantPool;
        this.patternPool = frame.patternPool;
        this.programCounter = frame.returnPc;
    }

    private ICraftingPatternDetails findPattern(IAEItemStack key) {
        for (ICraftingPatternDetails candidate : grid.getCraftingFor(key, null, -1, world)) {
            if (produces(candidate, key)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean produces(ICraftingPatternDetails pattern, IAEItemStack key) {
        IAEItemStack[] outputs = pattern.getOutputs();
        if (outputs == null) {
            return false;
        }
        for (IAEItemStack output : outputs) {
            if (output != null && output.isSameType(key)) {
                return true;
            }
        }
        return false;
    }

    private static long outputAmountFor(ICraftingPatternDetails pattern, IAEItemStack key) {
        IAEItemStack[] outputs = pattern.getOutputs();
        if (outputs != null) {
            for (IAEItemStack output : outputs) {
                if (output != null && output.isSameType(key)) {
                    return output.getStackSize();
                }
            }
        }
        return 0L;
    }

    private void consumeRecursiveStock(IAEItemStack key, BigInteger required) {
        long got = extract(key, required);
        if (got > 0) {
            insertInternal(key, got);
        }
        BigInteger missing = required.subtract(BigInteger.valueOf(got));
        if (missing.signum() > 0) {
            addCount(missingItems, key, missing);
            addBytes(missing);
        }
    }

    private void provideExternal(IAEItemStack key, BigInteger amount) {
        long provided = toLong(amount);
        if (provided <= 0) {
            return;
        }
        insertInternal(key, provided);
        addCount(emittedItems, key, BigInteger.valueOf(provided));
        addBytes(BigInteger.valueOf(provided));
    }

    private long extract(IAEItemStack key, BigInteger required) {
        long requested = toLong(required);
        if (requested <= 0) {
            return 0L;
        }
        IAEItemStack request = key.copy();
        request.setStackSize(requested);
        IAEItemStack result = inventory.extractItems(request, Actionable.MODULATE, actionSource);
        long got = result == null ? 0L : Math.max(0L, result.getStackSize());
        if (got > 0) {
            long internal = getCount(internalItems, key);
            long fromInternal = Math.min(got, internal);
            if (fromInternal > 0) {
                changeCount(internalItems, key, -fromInternal);
            }
            long fromNetwork = got - fromInternal;
            if (fromNetwork > 0) {
                addCount(usedItems, key, BigInteger.valueOf(fromNetwork));
            }
            addBytes(BigInteger.valueOf(got));
        }
        return got;
    }

    private void insertOutput(IAEItemStack key, BigInteger amount) {
        long count = toLong(amount);
        if (count > 0) {
            insertInternal(key, count);
        }
    }

    private void extractReusableIngredient(IAEItemStack input,
                                           IAEItemStack container,
                                           BigInteger totalRequired,
                                           long perCraft) {
        if (totalRequired.signum() <= 0) {
            push(BIG_ZERO);
            return;
        }

        long got = extract(input, totalRequired);
        if (got > 0) {
            insertInternal(container, got);
        }

        BigInteger oneCraft = BigInteger.valueOf(Math.max(0L, perCraft));
        BigInteger available = BigInteger.valueOf(got);
        push(oneCraft.subtract(available).max(BIG_ZERO));
    }

    private void extractContainer(IAEItemStack input,
                                   IAEItemStack container,
                                   BigInteger required) {
        if (required.signum() <= 0) {
            push(BIG_ZERO);
            return;
        }

        long got = extract(input, required);
        if (got > 0) {
            insertInternal(container, got);
        }
        push(required.subtract(BigInteger.valueOf(got)).max(BIG_ZERO));
    }

    private void insertInternal(IAEItemStack key, long count) {
        IAEItemStack stack = key.copy();
        stack.setStackSize(count);
        IAEItemStack remainder = inventory.injectItems(stack, Actionable.MODULATE, actionSource);
        if (remainder != null && remainder.getStackSize() > 0) {
            throw new UnsupportedOperationException("Simulation inventory rejected crafted output");
        }
        addCount(internalItems, key, BigInteger.valueOf(count));
    }

    private void addPattern(ICraftingPatternDetails pattern, BigInteger count) {
        long value = toLong(count);
        if (value <= 0) {
            return;
        }
        Long old = patternTimes.get(pattern);
        long previous = old == null ? 0L : old;
        long combined = Long.MAX_VALUE - previous < value ? Long.MAX_VALUE : previous + value;
        patternTimes.put(pattern, combined);
        addBytes(BigInteger.valueOf(value).multiply(BIG_EIGHT));
    }

    private void addBytes(BigInteger amount) {
        if (amount.signum() <= 0) {
            return;
        }
        BigInteger total = BigInteger.valueOf(bytes).add(amount);
        bytes = total.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : total.longValue();
    }

    private Result buildResult(CraftingBytecode request) {
        long requestedAmount = request.getRequestedAmount();
        if (requestedAmount <= 0) {
            requestedAmount = request.getOutputAmountPerCraft();
        }
        IAEItemStack output = request.getOutput().copy();
        output.setStackSize(requestedAmount);
        return new Result(output, bytes, usedItems, missingItems, emittedItems,
                patternTimes);
    }

    private void load(CraftingBytecode bytecode) {
        this.code = bytecode.getCode();
        this.constantPool = bytecode.getConstantPool();
        this.patternPool = bytecode.getPatternPool();
        this.programCounter = 0;
    }

    private void push(BigInteger value) {
        if (stackPointer >= stack.length) {
            throw new UnsupportedOperationException("Crafting VM operand stack overflow");
        }
        stack[stackPointer++] = value;
    }

    private BigInteger pop() {
        if (stackPointer <= 0) {
            throw new UnsupportedOperationException("Crafting VM operand stack underflow");
        }
        BigInteger value = stack[--stackPointer];
        stack[stackPointer] = null;
        return value;
    }

    private BigInteger peek() {
        if (stackPointer <= 0) {
            throw new UnsupportedOperationException("Crafting VM operand stack is empty");
        }
        return stack[stackPointer - 1];
    }

    private int readShort() {
        return ((code[programCounter++] & 0xFF) << 8) | (code[programCounter++] & 0xFF);
    }

    private long readLong() {
        long value = 0L;
        for (int shift = 56; shift >= 0; shift -= 8) {
            value |= ((long) code[programCounter++] & 0xFFL) << shift;
        }
        return value;
    }

    private static long toLong(BigInteger value) {
        if (value.signum() <= 0) {
            return 0L;
        }
        return value.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : value.longValue();
    }

    private static IItemList<IAEItemStack> newItemList() {
        return AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class)
                .createList();
    }

    private static void addCount(IItemList<IAEItemStack> list,
                                 IAEItemStack key,
                                 BigInteger count) {
        long value = toLong(count);
        if (value <= 0) {
            return;
        }
        IAEItemStack copy = key.copy();
        copy.setStackSize(value);
        list.add(copy);
    }

    private static long getCount(IItemList<IAEItemStack> list, IAEItemStack key) {
        IAEItemStack current = list.findPrecise(key);
        return current == null ? 0L : Math.max(0L, current.getStackSize());
    }

    private static void changeCount(IItemList<IAEItemStack> list,
                                    IAEItemStack key,
                                    long delta) {
        IAEItemStack current = list.findPrecise(key);
        if (current != null) {
            current.setStackSize(Math.max(0L, current.getStackSize() + delta));
        }
    }

    private static boolean containsKey(Set<IAEItemStack> keys, IAEItemStack wanted) {
        for (IAEItemStack key : keys) {
            if (key.isSameType(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static void removeKey(Set<IAEItemStack> keys, IAEItemStack wanted) {
        IAEItemStack found = null;
        for (IAEItemStack key : keys) {
            if (key.isSameType(wanted)) {
                found = key;
                break;
            }
        }
        if (found != null) {
            keys.remove(found);
        }
    }

    private static final class CallFrame {
        private final int returnPc;
        private final byte[] code;
        private final IAEItemStack[] constantPool;
        private final ICraftingPatternDetails[] patternPool;
        private final IAEItemStack resolvingKey;

        private CallFrame(int returnPc,
                          byte[] code,
                          IAEItemStack[] constantPool,
                          ICraftingPatternDetails[] patternPool,
                          IAEItemStack resolvingKey) {
            this.returnPc = returnPc;
            this.code = code;
            this.constantPool = constantPool;
            this.patternPool = patternPool;
            this.resolvingKey = resolvingKey;
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

        private Result(IAEItemStack output,
                       long bytes,
                       IItemList<IAEItemStack> usedItems,
                       IItemList<IAEItemStack> missingItems,
                       IItemList<IAEItemStack> emittedItems,
                       Map<ICraftingPatternDetails, Long> patternTimes) {
            this.output = output;
            this.bytes = bytes;
            this.usedItems = usedItems;
            this.missingItems = missingItems;
            this.emittedItems = emittedItems;
            this.patternTimes = new LinkedHashMap<ICraftingPatternDetails, Long>(patternTimes);
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
                    new LinkedHashMap<ICraftingPatternDetails, Long>());
        }

        public boolean hasMissingItems() {
            return !missingItems.isEmpty();
        }

        public IAEItemStack getOutput() {
            return output;
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
                    if (output == null || output.getStackSize() <= 0) {
                        continue;
                    }
                    IAEItemStack requestable = output.copy();
                    requestable.setCountRequestable(saturatedMultiply(output.getStackSize(), crafts));
                    plan.addRequestable(requestable);
                }
            }
        }

        public void apply(MECraftingInventory storage,
                          CraftingCPUCluster cpu,
                          IActionSource source) throws CraftBranchFailure {
            // Validate the complete extraction set before mutating the
            // transaction inventory. MECraftingInventory commits its log only
            // after this method returns, so this keeps failed submissions
            // recoverable and matches CraftingTreeNode's two-phase behavior.
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(request, Actionable.SIMULATE, source);
                if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                    throw new CraftBranchFailure(request, request.getStackSize());
                }
            }
            for (IAEItemStack used : usedItems) {
                IAEItemStack request = used.copy();
                IAEItemStack extracted = storage.extractItems(request, Actionable.MODULATE, source);
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

        private static long saturatedMultiply(long left, long right) {
            if (left <= 0 || right <= 0) {
                return 0L;
            }
            return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
        }
    }
}
