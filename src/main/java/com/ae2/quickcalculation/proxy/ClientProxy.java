package com.ae2.quickcalculation.proxy;

import com.ae2.quickcalculation.client.CalculationStatusOverlay;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client-side registration and dispatch for the GUI status overlay. */
@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {
    @Override
    public void preInit() {
        CalculationStatusOverlay.register();
    }

    @Override
    public void showStatus(final String translationKey) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                CalculationStatusOverlay.show(translationKey);
            }
        });
    }
}
