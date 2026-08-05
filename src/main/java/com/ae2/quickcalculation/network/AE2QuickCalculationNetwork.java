package com.ae2.quickcalculation.network;

import com.ae2.quickcalculation.AE2QuickCalculation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Network channel for localized calculation status messages. */
public final class AE2QuickCalculationNetwork {
    private static SimpleNetworkWrapper channel;

    private AE2QuickCalculationNetwork() {
    }

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(AE2QuickCalculation.MOD_ID);
        channel.registerMessage(
                CalculationStatusMessage.Handler.class,
                CalculationStatusMessage.class,
                0,
                Side.CLIENT);
    }

    public static void sendStatus(EntityPlayer player, String translationKey) {
        if (channel != null && player instanceof EntityPlayerMP) {
            channel.sendTo(new CalculationStatusMessage(translationKey),
                    (EntityPlayerMP) player);
        }
    }
}
