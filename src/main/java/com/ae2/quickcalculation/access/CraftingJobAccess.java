package com.ae2.quickcalculation.access;

import appeng.api.networking.crafting.ICraftingGrid;

/** Access to the crafting grid held by AE2's CraftingJob without reflection. */
public interface CraftingJobAccess {
    ICraftingGrid ae2quickcalculation$getCraftingGrid();
}
