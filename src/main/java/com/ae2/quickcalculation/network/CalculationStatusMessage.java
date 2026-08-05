package com.ae2.quickcalculation.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/** Server-to-client localized status key. */
public final class CalculationStatusMessage implements IMessage {
    private String translationKey;

    public CalculationStatusMessage() {
    }

    public CalculationStatusMessage(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.translationKey = ByteBufUtils.readUTF8String(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        ByteBufUtils.writeUTF8String(buffer, translationKey);
    }

    /** Common-side handler delegates to the active sided proxy. */
    public static final class Handler
            implements IMessageHandler<CalculationStatusMessage, IMessage> {
        @Override
        public IMessage onMessage(CalculationStatusMessage message,
                                  MessageContext context) {
            com.ae2.quickcalculation.AE2QuickCalculation.PROXY.showStatus(
                    message.getTranslationKey());
            return null;
        }
    }
}
