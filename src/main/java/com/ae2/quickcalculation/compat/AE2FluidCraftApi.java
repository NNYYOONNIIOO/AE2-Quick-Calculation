package com.ae2.quickcalculation.compat;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import com.glodblock.github.common.item.fake.FakeItemRegister;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.glodblock.github.loader.FCItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Direct AE2FC calls isolated in a class that is only reached when ae2fc is
 * present. This keeps the main calculation path free of an eager optional API
 * reference while avoiding reflective method lookup.
 */
final class AE2FluidCraftApi {
    private AE2FluidCraftApi() {
    }

    static boolean isFluidFakeItem(ItemStack stack) {
        return FakeFluids.isFluidFakeItem(stack);
    }

    static IAEItemStack packFluid(IAEFluidStack fluid) {
        return FakeFluids.packFluid2AEDrops(fluid);
    }

    static IAEItemStack normalizeFluidItem(IAEItemStack stack) {
        if (stack == null || !FakeFluids.isFluidFakeItem(stack.getDefinition())) {
            return stack == null ? null : stack.copy();
        }

        // FLUID_DROP is the item-channel representation used by AE2FC's
        // network monitor. Keep its long quantity unchanged; parsing it back
        // through ItemStack would truncate very large requests.
        if (stack.getItem() == FCItems.FLUID_DROP) {
            return stack.copy();
        }

        // Processing patterns can retain FLUID_PACKET entries. Convert the
        // packet's embedded FluidStack to the same drop key used by storage.
        FluidStack fluid = FakeItemRegister.getStack(stack);
        return fluid == null ? null : FakeFluids.packFluid2AEDrops(fluid);
    }

    static IAEItemStack packFluidPacket(IAEItemStack stack) {
        return packFluidPacket(stack, 0L);
    }

    static IAEItemStack packFluidPacket(IAEItemStack stack, long amountHint) {
        if (stack == null || !FakeFluids.isFluidFakeItem(stack.getDefinition())) {
            return null;
        }

        FluidStack fluid = FakeItemRegister.getStack(stack);
        if (fluid == null && stack.getItem() == FCItems.FLUID_DROP
                && amountHint > 0L) {
            // A calculation key has normally been reset to size zero. A drop
            // does not store its amount in NBT, so reconstruct a bounded
            // definition stack when querying packet-indexed patterns.
            IAEItemStack sized = stack.copy().setStackSize(
                    Math.min(Integer.MAX_VALUE, amountHint));
            fluid = FakeItemRegister.getStack(sized);
        }
        return fluid == null ? null : FakeFluids.packFluid2AEPacket(fluid);
    }
}
