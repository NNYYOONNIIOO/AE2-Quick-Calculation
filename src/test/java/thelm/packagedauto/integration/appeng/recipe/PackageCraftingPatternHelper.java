package thelm.packagedauto.integration.appeng.recipe;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Test fixture with the runtime class name used by PackagedAuto. */
public final class PackageCraftingPatternHelper implements ICraftingPatternDetails {
    private final IAEItemStack input;
    private final IAEItemStack output;

    public PackageCraftingPatternHelper(IAEItemStack input, IAEItemStack output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public ItemStack getPattern() {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
        throw new IllegalStateException("PackagedAuto package patterns do not expose slots");
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
        return true;
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
