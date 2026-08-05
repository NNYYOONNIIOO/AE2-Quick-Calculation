package com.ae2.quickcalculation.calculator;

import appeng.api.AEApi;
import appeng.api.networking.IGridCache;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.util.item.AEItemStack;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * End-to-end calculator stress test for very deep, non-recursive chains.
 * Run with: gradlew.bat stressTest --offline --no-daemon
 */
public final class CraftingCalculatorStressTest {
    private static final long MAX_CALCULATION_NANOS = 10_000_000_000L;

    private CraftingCalculatorStressTest() {
    }

    public static void main(String[] args) {
        if (!Bootstrap.isRegistered()) {
            Bootstrap.register();
        }
        runChain("20k-node chain", 20_000);
        runChain("50k-depth chain", 50_000);
        verifyQuantityOverflowIsRejected();
        System.out.println("CraftingCalculatorStressTest passed");
    }

    private static void runChain(String label, int depth) {
        Item item = new Item();
        IAEItemStack[] stacks = new IAEItemStack[depth + 1];
        ChainPattern[] patterns = new ChainPattern[depth];
        Map<IAEItemStack, ICraftingPatternDetails> byOutput =
                new HashMap<IAEItemStack, ICraftingPatternDetails>(depth * 2);

        for (int index = 0; index <= depth; index++) {
            // Four inputs produce four outputs. This keeps every intermediate
            // quantity representable while exercising the same inverse-chain
            // shape as a *4 recipe.
            stacks[index] = AEItemStack.fromItemStack(
                    new ItemStack(item, 4, index));
        }

        for (int index = 1; index <= depth; index++) {
            ChainPattern pattern = new ChainPattern(stacks[index], stacks[index - 1]);
            patterns[index - 1] = pattern;
            byOutput.put(stacks[index], pattern);
        }

        StressGrid grid = new StressGrid(stacks[0], byOutput);
        IItemList<IAEItemStack> empty = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        MECraftingInventory inventory = new MECraftingInventory(empty);

        long started = System.nanoTime();
        CraftingCalculator.Result result = new CraftingCalculator(grid, null)
                .calculate(patterns[depth - 1], 4L, inventory);
        long elapsed = System.nanoTime() - started;

        require(elapsed <= MAX_CALCULATION_NANOS,
                label + " exceeded 10 seconds: " + formatMillis(elapsed) + " ms");
        require(!result.hasMissingItems(), label + " reported missing items");
        require(result.getOutput().getStackSize() == 4L,
                label + " returned the wrong output quantity");

        IAEItemStack emitted = result.getEmittedItems().findPrecise(stacks[0]);
        require(emitted != null && emitted.getStackSize() == 4L,
                label + " did not preserve the leaf material quantity");

        IItemList<IAEItemStack> plan = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        result.populatePlan(plan);
        require(plan.size() == depth + 1,
                label + " produced an incomplete plan: " + plan.size());

        System.out.println(label + ": " + formatMillis(elapsed) + " ms, plan entries="
                + plan.size());
    }

    private static void verifyQuantityOverflowIsRejected() {
        Item item = new Item();
        Map<IAEItemStack, ICraftingPatternDetails> byOutput =
                new HashMap<IAEItemStack, ICraftingPatternDetails>();
        IAEItemStack leaf = AEItemStack.fromItemStack(new ItemStack(item, 1, 0));
        IAEItemStack currentOutput = leaf;
        for (int index = 1; index <= 64; index++) {
            IAEItemStack next = AEItemStack.fromItemStack(
                    new ItemStack(item, 1, index));
            IAEItemStack input = currentOutput.copy().setStackSize(4L);
            ChainPattern nextPattern = new ChainPattern(next, input);
            byOutput.put(next, nextPattern);
            currentOutput = next;
        }

        IItemList<IAEItemStack> empty = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        try {
            new CraftingCalculator(new StressGrid(leaf, byOutput), null)
                    .calculate((ICraftingPatternDetails) byOutput.get(currentOutput), 1L,
                            new MECraftingInventory(empty));
            throw new AssertionError("quantity overflow was not rejected");
        } catch (UnsupportedOperationException expected) {
            // Expected: the 1.12.2 API carries quantities as long.
        }
    }

    private static String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0D);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class ChainPattern implements ICraftingPatternDetails {
        private final IAEItemStack output;
        private final IAEItemStack input;

        private ChainPattern(IAEItemStack output, IAEItemStack input) {
            this.output = output;
            this.input = input;
        }

        @Override
        public ItemStack getPattern() {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
            return input.isSameType(itemStack);
        }

        @Override
        public boolean isCraftable() {
            return false;
        }

        @Override
        public IAEItemStack[] getInputs() {
            return new IAEItemStack[]{input};
        }

        @Override
        public IAEItemStack[] getCondensedInputs() {
            return new IAEItemStack[]{input};
        }

        @Override
        public IAEItemStack[] getCondensedOutputs() {
            return new IAEItemStack[]{output};
        }

        @Override
        public IAEItemStack[] getOutputs() {
            return new IAEItemStack[]{output};
        }

        @Override
        public boolean canSubstitute() {
            return false;
        }

        @Override
        public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
            return output.createItemStack();
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void setPriority(int priority) {
        }
    }

    private static final class StressGrid implements ICraftingGrid {
        private final IAEItemStack emitted;
        private final Map<IAEItemStack, ICraftingPatternDetails> byOutput;

        private StressGrid(IAEItemStack emitted,
                           Map<IAEItemStack, ICraftingPatternDetails> byOutput) {
            this.emitted = emitted;
            this.byOutput = byOutput;
        }

        @Override
        public ImmutableCollection<ICraftingPatternDetails> getCraftingFor(
                IAEItemStack whatToCraft,
                ICraftingPatternDetails details,
                int slot,
                World world) {
            ICraftingPatternDetails pattern = byOutput.get(whatToCraft);
            return pattern == null
                    ? ImmutableList.<ICraftingPatternDetails>of()
                    : ImmutableList.of(pattern);
        }

        @Override
        public boolean canEmitFor(IAEItemStack what) {
            return emitted.isSameType(what);
        }

        @Override
        public boolean isRequesting(IAEItemStack what) {
            return false;
        }

        @Override
        public long requesting(IAEItemStack what) {
            return 0L;
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public Future<ICraftingJob> beginCraftingJob(World world,
                                                       IGrid grid,
                                                       IActionSource actionSrc,
                                                       IAEItemStack craftWhat,
                                                       ICraftingCallback callback) {
            return null;
        }

        @Override
        public ICraftingLink submitJob(ICraftingJob job,
                                       ICraftingRequester requestingMachine,
                                       appeng.api.networking.crafting.ICraftingCPU target,
                                       boolean prioritizePower,
                                       IActionSource src) {
            return null;
        }

        @Override
        public void onUpdateTick() {
        }

        @Override
        public void removeNode(IGridNode gridNode, IGridHost machine) {
        }

        @Override
        public void addNode(IGridNode gridNode, IGridHost machine) {
        }

        @Override
        public void onSplit(IGridStorage destinationStorage) {
        }

        @Override
        public void onJoin(IGridStorage sourceStorage) {
        }

        @Override
        public void populateGridStorage(IGridStorage destinationStorage) {
        }
    }
}
