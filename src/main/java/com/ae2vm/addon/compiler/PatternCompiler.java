package com.ae2vm.addon.compiler;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.Opcode;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Compiles AE2UEL patterns into flat bytecode and caches them by pattern. */
public final class PatternCompiler {
    private static final Map<ICraftingPatternDetails, CraftingBytecode> CACHE =
            new ConcurrentHashMap<ICraftingPatternDetails, CraftingBytecode>();

    private PatternCompiler() {
    }

    public static void compileIfAbsent(ICraftingPatternDetails pattern) {
        if (pattern != null) {
            CACHE.computeIfAbsent(pattern, PatternCompiler::compilePattern);
        }
    }

    public static CraftingBytecode getCompiled(ICraftingPatternDetails pattern) {
        return pattern == null ? null : CACHE.get(pattern);
    }

    public static CraftingBytecode compileRequest(ICraftingPatternDetails pattern, long requestedAmount) {
        compileIfAbsent(pattern);
        CraftingBytecode compiled = getCompiled(pattern);
        if (compiled == null) {
            throw new IllegalStateException("Pattern could not be compiled: " + pattern);
        }

        long outputPerCraft = compiled.getOutputAmountPerCraft();
        if (outputPerCraft <= 0) {
            throw new IllegalArgumentException("Pattern has no positive primary output");
        }

        BigInteger requested = BigInteger.valueOf(Math.max(0L, requestedAmount));
        BigInteger crafts = requested.add(BigInteger.valueOf(outputPerCraft - 1L))
                .divide(BigInteger.valueOf(outputPerCraft));

        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIndex = builder.addConstant(compiled.getOutput());
        builder.setOutput(outputIndex, outputPerCraft);
        builder.setRequestedAmount(requestedAmount);
        int patternIndex = builder.addPattern(pattern);
        builder.emitPushLong(crafts.longValue());
        builder.emitCall(patternIndex);
        return builder.build();
    }

    /**
     * Returns whether the VM can preserve the native 1.12.2 slot semantics for
     * this pattern. Unsupported patterns are deliberately left to AE2's tree.
     */
    public static boolean isSupported(ICraftingPatternDetails pattern) {
        if (pattern == null || pattern.canSubstitute()) {
            return false;
        }

        IAEItemStack[] inputs = pattern.getCondensedInputs();
        if (inputs == null) {
            return false;
        }

        for (IAEItemStack input : inputs) {
            if (input == null || input.getStackSize() <= 0) {
                continue;
            }
            Item item = input.getItem();
            if (item == null || item.isDamageable() || Platform.isGTDamageableItem(item)) {
                return false;
            }
        }

        return true;
    }

    private static CraftingBytecode compilePattern(ICraftingPatternDetails pattern) {
        if (!isSupported(pattern)) {
            throw new UnsupportedOperationException("Pattern uses native-only slot semantics: " + pattern);
        }

        IAEItemStack primary = pattern.getPrimaryOutput();
        if (primary == null || primary.getStackSize() <= 0) {
            throw new IllegalArgumentException("Pattern has no primary output: " + pattern);
        }

        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIndex = builder.addConstant(primary);
        int patternIndex = builder.addPattern(pattern);
        builder.setOutput(outputIndex, primary.getStackSize());

        builder.emit(Opcode.DUP);
        builder.emitRecordPattern(patternIndex);

        IAEItemStack[] inputs = pattern.getCondensedInputs();
        if (inputs != null) {
            for (IAEItemStack input : inputs) {
                if (input == null || input.getStackSize() <= 0) {
                    continue;
                }
                int inputIndex = builder.addConstant(input);
                IAEItemStack container = getContainer(input);
                if (container == null) {
                    builder.emit(Opcode.DUP);
                    builder.emitPushLong(input.getStackSize());
                    builder.emit(Opcode.MUL);
                    builder.emitExtractIngredient(inputIndex);
                    builder.emit(Opcode.DUP);
                    builder.emitCallByKey(inputIndex);
                    builder.emitExtractIngredient(inputIndex);
                    builder.emit(Opcode.POP);
                } else {
                    int containerIndex = builder.addConstant(container);
                    // Reusable inputs are acquired in one batch: consume as
                    // many existing copies as the request can use, then craft
                    // only the shortfall needed for one simultaneous craft.
                    builder.emit(Opcode.DUP);
                    builder.emitPushLong(input.getStackSize());
                    builder.emit(Opcode.MUL);
                    builder.emitExtractReusableIngredient(
                            inputIndex, containerIndex, input.getStackSize());
                    builder.emit(Opcode.DUP);
                    builder.emitCallByKey(inputIndex);
                    builder.emitExtractContainer(inputIndex, containerIndex);
                    builder.emit(Opcode.POP);
                }
            }
        }

        IAEItemStack[] outputs = pattern.getOutputs();
        if (outputs != null) {
            for (IAEItemStack output : outputs) {
                if (output == null || output.getStackSize() <= 0) {
                    continue;
                }
                int outputConstant = builder.addConstant(output);
                builder.emit(Opcode.DUP);
                builder.emitPushLong(output.getStackSize());
                builder.emit(Opcode.MUL);
                builder.emitInsertOutput(outputConstant);
            }
        }

        builder.emit(Opcode.POP);
        builder.emit(Opcode.RETURN);
        return builder.build();
    }

    private static IAEItemStack getContainer(IAEItemStack input) {
        Item item = input.getItem();
        if (item == null || !item.hasContainerItem(input.getDefinition())) {
            return null;
        }

        ItemStack container = Platform.getContainerItem(input.copy().setStackSize(1).createItemStack());
        return container == null || container.isEmpty() ? null : AEItemStack.fromItemStack(container);
    }

    public static void invalidate(ICraftingPatternDetails pattern) {
        if (pattern != null) {
            CACHE.remove(pattern);
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static int getCompiledCount() {
        return CACHE.size();
    }

    public static ICraftingPatternDetails findCompiledByOutput(IAEItemStack output) {
        if (output == null) {
            return null;
        }
        for (ICraftingPatternDetails pattern : CACHE.keySet()) {
            IAEItemStack primary = pattern.getPrimaryOutput();
            if (primary != null && primary.isSameType(output)) {
                return pattern;
            }
            IAEItemStack[] outputs = pattern.getOutputs();
            if (outputs != null) {
                for (IAEItemStack candidate : outputs) {
                    if (candidate != null && candidate.isSameType(output)) {
                        return pattern;
                    }
                }
            }
        }
        return null;
    }
}
