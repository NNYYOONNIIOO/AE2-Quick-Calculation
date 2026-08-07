package com.ae2.quickcalculation.network;

import com.ae2.quickcalculation.AE2QuickCalculation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
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

    public static void sendStatus(EntityPlayer player, String translationKey,
                                  long elapsedMillis) {
        if (channel == null || !(player instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP serverPlayer = (EntityPlayerMP) player;
        if (!hasClientMod(serverPlayer)) {
            // acceptableRemoteVersions="*" permits a vanilla/mod-less client.
            // Do not send a custom payload to a connection that did not
            // advertise our channel.
            return;
        }

        channel.sendTo(new CalculationStatusMessage(translationKey, elapsedMillis),
                serverPlayer);
    }

    private static boolean hasClientMod(EntityPlayerMP player) {
        try {
            if (player == null || player.connection == null
                    || player.connection.netManager == null) {
                return false;
            }
            NetworkDispatcher dispatcher = NetworkDispatcher.get(
                    player.connection.netManager);
            return dispatcher != null && dispatcher.getModList() != null
                    && dispatcher.getModList().containsKey(
                    AE2QuickCalculation.MOD_ID);
        } catch (Throwable ignored) {
            // A status toast is optional; a handshake race must never fail a
            // crafting request or disconnect a client.
            return false;
        }
    }
}
