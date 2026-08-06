package com.ae2.quickcalculation.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/** Draws calculation status above the active GUI, including AE2 CPU screens. */
@SideOnly(Side.CLIENT)
public final class CalculationStatusOverlay {
    private static final long DISPLAY_TIME_MILLIS = 12000L;
    private static final CalculationStatusOverlay INSTANCE =
            new CalculationStatusOverlay();

    private static String translationKey;
    private static long displayUntil;
    private static boolean drawn;
    private static boolean registered;

    private CalculationStatusOverlay() {
    }

    public static void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
    }

    public static void show(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        translationKey = key;
        displayUntil = System.currentTimeMillis() + DISPLAY_TIME_MILLIS;
        drawn = false;
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiScreen gui = event.getGui();
        draw(gui.width, gui.height);
    }

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL
                && minecraft.currentScreen == null) {
            draw(event.getResolution().getScaledWidth(),
                    event.getResolution().getScaledHeight());
        }
    }

    private static void draw(int screenWidth, int screenHeight) {
        long now = System.currentTimeMillis();
        if (translationKey == null || now >= displayUntil) {
            return;
        }

        // A calculation can finish while the confirm screen is being opened.
        // Keep the message alive for a full interval after its first actual
        // draw instead of letting that race consume the whole lifetime.
        if (!drawn) {
            drawn = true;
            displayUntil = now + DISPLAY_TIME_MILLIS;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        FontRenderer font = minecraft.fontRenderer;
        String text = I18n.format(translationKey);
        int maxTextWidth = Math.max(100, screenWidth - 28);
        List<String> lines = font.listFormattedStringToWidth(text, maxTextWidth);
        int longestLine = 0;
        for (String line : lines) {
            longestLine = Math.max(longestLine, font.getStringWidth(line));
        }
        int boxWidth = Math.min(screenWidth - 8,
                Math.max(160, longestLine + 20));
        int boxHeight = Math.max(20, lines.size() * 10 + 10);
        int x = (screenWidth - boxWidth) / 2;
        int y = 3;

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        Gui.drawRect(x, y, x + boxWidth, y + boxHeight, 0xCC101820);
        for (int index = 0; index < lines.size(); index++) {
            font.drawStringWithShadow(lines.get(index),
                    x + 10, y + 5 + index * 10, 0xFFFFFF);
        }
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
}
