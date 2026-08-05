package com.ae2vm.addon.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable compiled representation of one AE2 pattern or request wrapper. */
public final class CraftingBytecode {
    private final IAEItemStack[] constantPool;
    private final ICraftingPatternDetails[] patternPool;
    private final byte[] code;
    private final int outputIndex;
    private final long outputAmountPerCraft;
    private final long requestedAmount;
    private final int hash;

    private CraftingBytecode(IAEItemStack[] constantPool,
                             ICraftingPatternDetails[] patternPool,
                             byte[] code,
                             int outputIndex,
                             long outputAmountPerCraft,
                             long requestedAmount) {
        this.constantPool = constantPool;
        this.patternPool = patternPool;
        this.code = code;
        this.outputIndex = outputIndex;
        this.outputAmountPerCraft = outputAmountPerCraft;
        this.requestedAmount = requestedAmount;
        this.hash = Arrays.hashCode(code) * 31 + Arrays.hashCode(constantPool);
    }

    public IAEItemStack[] getConstantPool() {
        return constantPool;
    }

    public ICraftingPatternDetails[] getPatternPool() {
        return patternPool;
    }

    public byte[] getCode() {
        return code;
    }

    public long getOutputAmountPerCraft() {
        return outputAmountPerCraft;
    }

    public long getRequestedAmount() {
        return requestedAmount;
    }

    public IAEItemStack getOutput() {
        return constantPool[outputIndex];
    }

    public int getCodeLength() {
        return code.length;
    }

    public int getIngredientCount() {
        return constantPool.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CraftingBytecode)) {
            return false;
        }
        CraftingBytecode that = (CraftingBytecode) other;
        return hash == that.hash
                && Arrays.equals(constantPool, that.constantPool)
                && Arrays.equals(patternPool, that.patternPool)
                && Arrays.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    public static final class Builder {
        private final List<IAEItemStack> constantPool = new ArrayList<>();
        private final List<ICraftingPatternDetails> patternPool = new ArrayList<>();
        private final ByteArrayOutputStream code = new ByteArrayOutputStream();
        private int outputIndex = -1;
        private long outputAmountPerCraft;
        private long requestedAmount;

        public int addConstant(IAEItemStack key) {
            if (key == null) {
                throw new IllegalArgumentException("A bytecode constant cannot be null");
            }
            for (int i = 0; i < constantPool.size(); i++) {
                if (constantPool.get(i).isSameType(key)) {
                    return i;
                }
            }
            IAEItemStack copy = key.copy();
            copy.reset();
            constantPool.add(copy);
            return constantPool.size() - 1;
        }

        public int addPattern(ICraftingPatternDetails pattern) {
            int existing = patternPool.indexOf(pattern);
            if (existing >= 0) {
                return existing;
            }
            patternPool.add(pattern);
            return patternPool.size() - 1;
        }

        public void setOutput(int index, long amountPerCraft) {
            this.outputIndex = index;
            this.outputAmountPerCraft = amountPerCraft;
        }

        public void setRequestedAmount(long amount) {
            this.requestedAmount = amount;
        }

        public void emit(Opcode opcode) {
            code.write(opcode.code);
        }

        public void emitShort(int value) {
            code.write((value >>> 8) & 0xFF);
            code.write(value & 0xFF);
        }

        public void emitLong(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                code.write((int) (value >>> shift) & 0xFF);
            }
        }

        public void emitPushLong(long value) {
            emit(Opcode.PUSH_LONG);
            emitLong(value);
        }

        public void emitExtractIngredient(int constantIndex) {
            emit(Opcode.EXTRACT_INGREDIENT);
            emitShort(constantIndex);
        }

        public void emitRecordOutput(int constantIndex) {
            emit(Opcode.RECORD_OUTPUT);
            emitShort(constantIndex);
        }

        public void emitRecordIngredient(int constantIndex) {
            emit(Opcode.RECORD_INGREDIENT);
            emitShort(constantIndex);
        }

        public void emitRecordMissing(int constantIndex) {
            emit(Opcode.RECORD_MISSING);
            emitShort(constantIndex);
        }

        public void emitRecordPattern(int patternIndex) {
            emit(Opcode.RECORD_PATTERN);
            emitShort(patternIndex);
        }

        public void emitCall(int patternIndex) {
            emit(Opcode.CALL);
            emitShort(patternIndex);
        }

        public void emitCallByKey(int constantIndex) {
            emit(Opcode.CALL_BY_KEY);
            emitShort(constantIndex);
        }

        public void emitInsertOutput(int constantIndex) {
            emit(Opcode.INSERT_OUTPUT);
            emitShort(constantIndex);
        }

        public CraftingBytecode build() {
            if (outputIndex < 0) {
                throw new IllegalStateException("Bytecode output was not set");
            }
            emit(Opcode.HALT);
            return new CraftingBytecode(
                    constantPool.toArray(new IAEItemStack[constantPool.size()]),
                    patternPool.toArray(new ICraftingPatternDetails[patternPool.size()]),
                    code.toByteArray(),
                    outputIndex,
                    outputAmountPerCraft,
                    requestedAmount);
        }
    }
}
