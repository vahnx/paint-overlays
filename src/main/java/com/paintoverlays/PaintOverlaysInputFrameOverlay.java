package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class PaintOverlaysInputFrameOverlay extends Overlay
{
    private static final int INSET = 10;
    private static final int STROKE_WIDTH = 15;
    private static final int WORLD_MAP_STROKE_WIDTH = Math.max(1, STROKE_WIDTH / 2);
    private static final int WORLD_MAP_INSET = 4;
    private static final Color BANNER_BACKGROUND = new Color(10, 10, 10, 195);
    private static final Color TITLE_COLOR = new Color(255, 255, 255, 255);
    private static final Color SUBTITLE_COLOR = new Color(255, 244, 176, 255);
    private static final Color TEXT_OUTLINE = new Color(0, 0, 0, 230);

    private final Client client;
    private final PaintOverlaysPlugin plugin;

    @Inject
    private PaintOverlaysInputFrameOverlay(Client client, PaintOverlaysPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        PaintInputMode inputMode = plugin.getInputMode();
        if (inputMode == PaintInputMode.NONE)
        {
            return null;
        }

        if ((inputMode == PaintInputMode.SCENE && !plugin.isSceneInputAvailable())
            || (inputMode == PaintInputMode.WORLD_MAP && !plugin.isWorldMapInputAvailable()))
        {
            return null;
        }

        Rectangle frame = resolveFrameBounds(inputMode);
        if (frame == null)
        {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(plugin.getActiveFrameColor());
        graphics.setStroke(plugin.createFrameStroke(inputMode == PaintInputMode.WORLD_MAP ? WORLD_MAP_STROKE_WIDTH : STROKE_WIDTH));
        graphics.drawRect(frame.x, frame.y, frame.width, frame.height);
        renderEditingBanner(graphics, frame);
        return null;
    }

    private Rectangle resolveFrameBounds(PaintInputMode inputMode)
    {
        if (inputMode == PaintInputMode.WORLD_MAP)
        {
            Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
            if (mapWidget == null || mapWidget.isHidden())
            {
                return null;
            }

            Rectangle bounds = mapWidget.getBounds();
            if (bounds == null || bounds.width <= WORLD_MAP_INSET * 2 || bounds.height <= WORLD_MAP_INSET * 2)
            {
                return null;
            }

            return new Rectangle(
                bounds.x + WORLD_MAP_INSET,
                bounds.y + WORLD_MAP_INSET,
                bounds.width - WORLD_MAP_INSET * 2,
                bounds.height - WORLD_MAP_INSET * 2);
        }

        int viewportWidth = client.getViewportWidth();
        int viewportHeight = client.getViewportHeight();
        if (viewportWidth <= INSET * 2 || viewportHeight <= INSET * 2)
        {
            return null;
        }

        return new Rectangle(
            client.getViewportXOffset() + INSET,
            client.getViewportYOffset() + INSET,
            viewportWidth - INSET * 2,
            viewportHeight - INSET * 2);
    }

    private static void renderEditingBanner(Graphics2D graphics, Rectangle frame)
    {
        String title = "Editing Mode On";
        String subtitle = "Press ESC to Exit";

        Font previous = graphics.getFont();
        Color previousColor = graphics.getColor();

        Font titleFont = previous.deriveFont(Font.BOLD, 26f);
        Font subtitleFont = previous.deriveFont(Font.BOLD, 16f);
        int centerX = frame.x + frame.width / 2;
        int titleY = frame.y + 31;
        int subtitleY = titleY + 20;

        int titleWidth = graphics.getFontMetrics(titleFont).stringWidth(title);
        int subtitleWidth = graphics.getFontMetrics(subtitleFont).stringWidth(subtitle);
        int bannerWidth = Math.max(titleWidth, subtitleWidth) + 32;
        int bannerHeight = 46;
        int bannerX = centerX - bannerWidth / 2;
        int bannerY = frame.y + 8;

        graphics.setColor(BANNER_BACKGROUND);
        graphics.fillRoundRect(bannerX, bannerY, bannerWidth, bannerHeight, 16, 16);

        drawCenteredText(graphics, title, titleFont, centerX, titleY,
            TITLE_COLOR, TEXT_OUTLINE);
        drawCenteredText(graphics, subtitle, subtitleFont, centerX, subtitleY,
            SUBTITLE_COLOR, TEXT_OUTLINE);

        graphics.setFont(previous);
        graphics.setColor(previousColor);
    }

    private static void drawCenteredText(Graphics2D graphics, String text, Font font, int centerX, int baselineY,
                                         Color textColor, Color shadowColor)
    {
        graphics.setFont(font);
        int textWidth = graphics.getFontMetrics(font).stringWidth(text);
        int x = centerX - textWidth / 2;
        graphics.setColor(shadowColor);
        graphics.drawString(text, x - 1, baselineY);
        graphics.drawString(text, x + 1, baselineY);
        graphics.drawString(text, x, baselineY - 1);
        graphics.drawString(text, x, baselineY + 1);
        graphics.drawString(text, x + 2, baselineY + 2);
        graphics.setColor(textColor);
        graphics.drawString(text, x, baselineY);
    }
}
