package com.ae2.quickcalculation.proxy;

import com.ae2.quickcalculation.client.CalculationStatusToast;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client-side dispatch for the right-top calculation toast. */
@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {
    @Override
    public void showStatus(final String translationKey, final long elapsedMillis) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft.getMinecraft().getToastGui().add(
                        new CalculationStatusToast(translationKey, elapsedMillis));
            }
        });
    }
}
