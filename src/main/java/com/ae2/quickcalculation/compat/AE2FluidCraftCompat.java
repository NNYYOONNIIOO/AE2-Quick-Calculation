package com.ae2.quickcalculation.compat;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2.quickcalculation.AE2QuickCalculation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

/** Optional AE2FC integration used by the direct calculator. */
public final class AE2FluidCraftCompat {
    private static final String MOD_ID = "ae2fc";
    private static volatile boolean availabilityReported;

    private AE2FluidCraftCompat() {
    }

    public static boolean isAvailable() {
        boolean available = Loader.isModLoaded(MOD_ID);
        if (!availabilityReported) {
            synchronized (AE2FluidCraftCompat.class) {
                if (!availabilityReported) {
                    AE2QuickCalculation.LOGGER.info(
                            "[QCALC][DIAG] AE2FC compatibility available={}", available);
                    availabilityReported = true;
                }
            }
        }
        return available;
    }

    /** Compact, lossless-enough representation used by the temporary diagnostics. */
    public static String debugStack(IAEItemStack stack) {
        if (stack == null) {
            return "null";
        }
        try {
            ItemStack definition = stack.getDefinition();
            String item = stack.getItem() == null
                    ? "null"
                    : String.valueOf(stack.getItem().getRegistryName());
            String nbt = definition != null && definition.hasTagCompound()
                    ? String.valueOf(definition.getTagCompound())
                    : "-";
            int minecraftCount = definition == null ? 0 : definition.getCount();
            return item + " dmg=" + stack.getItemDamage()
                    + " ae=" + stack.getStackSize()
                    + " mc=" + minecraftCount
                    + " nbt=" + nbt;
        } catch (Throwable failure) {
            return String.valueOf(stack);
        }
    }

    public static String debugArray(IAEItemStack[] stacks) {
        if (stacks == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < stacks.length; index++) {
            if (index > 0) {
                result.append(", ");
            }
            IAEItemStack stack = stacks[index];
            result.append(index).append('=').append(debugStack(stack));
            if (isFluidFakeItem(stack)) {
                result.append(" -> ").append(debugStack(normalizeFluidItem(stack)));
            }
        }
        return result.append(']').toString();
    }

    /** Lists only matching entries and caps output so a large network is readable. */
    public static String debugList(IItemList<IAEItemStack> list,
                                    boolean fluidsOnly) {
        if (list == null) {
            return "null";
        }
        final int maxEntries = 96;
        int matching = 0;
        int shown = 0;
        StringBuilder result = new StringBuilder("[");
        for (IAEItemStack stack : list) {
            if (fluidsOnly && !isFluidFakeItem(stack)) {
                continue;
            }
            matching++;
            if (shown >= maxEntries) {
                continue;
            }
            if (shown > 0) {
                result.append(", ");
            }
            result.append(debugStack(stack));
            shown++;
        }
        if (matching > shown) {
            result.append(", ... +").append(matching - shown).append(" more");
        }
        return result.append("] (entries=").append(matching).append(')').toString();
    }

    public static String debugFluidList(IItemList<IAEFluidStack> list) {
        if (list == null) {
            return "null";
        }
        final int maxEntries = 96;
        int shown = 0;
        int total = 0;
        StringBuilder result = new StringBuilder("[");
        for (IAEFluidStack stack : list) {
            total++;
            if (shown >= maxEntries) {
                continue;
            }
            if (shown > 0) {
                result.append(", ");
            }
            result.append(debugFluidStack(stack));
            shown++;
        }
        if (total > shown) {
            result.append(", ... +").append(total - shown).append(" more");
        }
        return result.append("] (entries=").append(total).append(')').toString();
    }

    private static String debugFluidStack(IAEFluidStack stack) {
        if (stack == null) {
            return "null";
        }
        try {
            return String.valueOf(stack.getFluid())
                    + " amount=" + stack.getStackSize()
                    + " nbt=" + String.valueOf(stack.getFluidStack().tag);
        } catch (Throwable failure) {
            return String.valueOf(stack);
        }
    }

    public static boolean isFluidFakeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isAvailable()) {
            return false;
        }
        try {
            return AE2FluidCraftApi.isFluidFakeItem(stack);
        } catch (LinkageError ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean isFluidFakeItem(IAEItemStack stack) {
        if (stack == null || !isAvailable()) {
            return false;
        }
        try {
            return AE2FluidCraftApi.isFluidFakeItem(stack);
        } catch (LinkageError ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Converts a native fluid snapshot to AE2FC's item-channel drop key. */
    public static IAEItemStack packFluid(IAEFluidStack fluid) {
        if (fluid == null || fluid.getStackSize() <= 0L || !isAvailable()) {
            return null;
        }
        try {
            return AE2FluidCraftApi.packFluid(fluid);
        } catch (LinkageError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Converts an AE2FC drop or packet to the canonical drop key. Ordinary
     * AE2 item stacks are copied unchanged.
     */
    public static IAEItemStack normalizeFluidItem(IAEItemStack stack) {
        if (stack == null || !isAvailable()) {
            return stack == null ? null : stack.copy();
        }
        try {
            return AE2FluidCraftApi.normalizeFluidItem(stack);
        } catch (LinkageError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Builds the exact packet key for a pattern lookup. The amount is the
     * fluid amount consumed or produced by one execution of that pattern.
     */
    public static IAEItemStack packFluidPacket(IAEItemStack stack,
                                                long amountHint) {
        if (stack == null || !isAvailable()) {
            return null;
        }
        try {
            return AE2FluidCraftApi.packFluidPacket(stack, amountHint);
        } catch (LinkageError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
