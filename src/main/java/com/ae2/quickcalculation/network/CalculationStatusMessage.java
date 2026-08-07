package com.ae2.quickcalculation.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/** Server-to-client localized status key. */
public final class CalculationStatusMessage implements IMessage {
    private String translationKey;
    private long elapsedMillis;

    public CalculationStatusMessage() {
    }

    public CalculationStatusMessage(String translationKey, long elapsedMillis) {
        this.translationKey = translationKey;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.translationKey = ByteBufUtils.readUTF8String(buffer);
        this.elapsedMillis = buffer.readLong();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        ByteBufUtils.writeUTF8String(buffer, translationKey);
        buffer.writeLong(elapsedMillis);
    }

    /** Common-side handler delegates to the active sided proxy. */
    public static final class Handler
            implements IMessageHandler<CalculationStatusMessage, IMessage> {
        @Override
        public IMessage onMessage(CalculationStatusMessage message,
                                  MessageContext context) {
            com.ae2.quickcalculation.AE2QuickCalculation.PROXY.showStatus(
                    message.getTranslationKey(), message.getElapsedMillis());
            return null;
        }
    }
}
