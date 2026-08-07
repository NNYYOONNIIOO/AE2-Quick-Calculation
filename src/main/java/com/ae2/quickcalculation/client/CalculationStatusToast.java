package com.ae2.quickcalculation.client;

import com.ae2.quickcalculation.AE2QuickCalculation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Right-top notification matching AE2's crafting toast placement. */
@SideOnly(Side.CLIENT)
public final class CalculationStatusToast implements IToast {
    private static final long DISPLAY_TIME_MILLIS = 5000L;

    private final String translationKey;
    private final long elapsedMillis;
    private long firstDrawTime;
    private boolean firstDraw = true;

    public CalculationStatusToast(String translationKey, long elapsedMillis) {
        this.translationKey = translationKey == null ? "" : translationKey;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
    }

    @Override
    public Visibility draw(GuiToast toastGui, long delta) {
        if (firstDraw) {
            firstDrawTime = delta;
            firstDraw = false;
        }

        Minecraft minecraft = toastGui.getMinecraft();
        FontRenderer font = minecraft.fontRenderer;

        minecraft.getTextureManager().bindTexture(IToast.TEXTURE_TOASTS);
        GlStateManager.color(1.0F, 1.0F, 1.0F);
        toastGui.drawTexturedModalRect(0, 0, 0, 32, 160, 32);

        String title = I18n.format(AE2QuickCalculation.TOAST_TITLE);
        String status = I18n.format(translationKey);
        String elapsed = I18n.format(AE2QuickCalculation.TOAST_ELAPSED,
                elapsedMillis);
        drawFitted(font, title + I18n.format(AE2QuickCalculation.TOAST_SEPARATOR)
                        + status,
                8.0F, 7.0F, 144, -11534256);
        drawFitted(font, elapsed, 8.0F, 18.0F, 144, -16777216);

        return delta - firstDrawTime < DISPLAY_TIME_MILLIS
                ? Visibility.SHOW
                : Visibility.HIDE;
    }

    private static void drawFitted(FontRenderer font, String text,
                                   float x, float y, int maxWidth, int color) {
        int width = Math.max(1, font.getStringWidth(text));
        float scale = Math.min(1.0F, (float) maxWidth / width);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        font.drawString(text, 0, 0, color);
        GlStateManager.popMatrix();
    }
}
