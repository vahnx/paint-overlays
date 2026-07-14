package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.Collection;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class PaintOverlaysWorldMapOverlay extends Overlay
{
    private final Client client;
    private final PaintOverlaysPlugin plugin;
    private volatile PaintWorldMapViewState viewState = PaintWorldMapViewState.unavailable();

    @Inject
    private PaintOverlaysWorldMapOverlay(Client client, PaintOverlaysPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_HIGH);
        setLayer(OverlayLayer.MANUAL);
        drawAfterInterface(InterfaceID.WORLDMAP);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        PaintWorldMapViewState currentViewState = snapshotViewState();
        viewState = currentViewState;
        if (!currentViewState.isAvailable())
        {
            return null;
        }

        graphics.setClip(currentViewState.getClipArea());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (Integer regionId : currentViewState.getVisibleRegionIds())
        {
            PaintChunkData chunk = plugin.getMapChunk(regionId);
            if (chunk != null)
            {
                renderChunk(graphics, chunk, currentViewState);
            }
        }

        PaintStroke activeStroke = plugin.getActiveMapStroke();
        if (activeStroke != null)
        {
            renderStroke(graphics, activeStroke, currentViewState);
        }

        if (plugin.getPluginConfig().showCursorPreview() && plugin.getInputMode() == PaintInputMode.WORLD_MAP)
        {
            Point mouseCanvas = plugin.getMouseCanvasPosition();
            renderPreview(
                graphics,
                currentViewState.getTarget(mouseCanvas.getX(), mouseCanvas.getY()),
                mouseCanvas,
                currentViewState);
        }

        return null;
    }

    Collection<Integer> getVisibleRegionIds()
    {
        return viewState.getVisibleRegionIds();
    }

    PaintTarget getTarget(int mouseX, int mouseY)
    {
        return viewState.getTarget(mouseX, mouseY);
    }

    boolean containsCanvasPoint(int mouseX, int mouseY)
    {
        return viewState.containsCanvasPoint(mouseX, mouseY);
    }

    private void renderChunk(Graphics2D graphics, PaintChunkData chunk, PaintWorldMapViewState currentViewState)
    {
        for (PaintStroke stroke : chunk.strokes)
        {
            renderStroke(graphics, stroke, currentViewState);
        }

        for (PaintShape shape : chunk.shapes)
        {
            renderShape(graphics, shape, currentViewState);
        }

        for (PaintText text : chunk.texts)
        {
            renderText(graphics, text, currentViewState);
        }
    }

    private void renderStroke(Graphics2D graphics, PaintStroke stroke, PaintWorldMapViewState currentViewState)
    {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty())
        {
            return;
        }

        graphics.setColor(new Color(stroke.colorArgb, true));
        graphics.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (stroke.points.size() == 1)
        {
            Point point = toCanvasPoint(stroke.points.get(0), currentViewState);
            if (point != null)
            {
                int radius = Math.max(2, stroke.width / 2);
                graphics.fillOval(point.getX() - radius, point.getY() - radius, radius * 2, radius * 2);
            }
            return;
        }

        Path2D.Double path = new Path2D.Double();
        boolean started = false;
        for (PaintPoint point : stroke.points)
        {
            Point canvasPoint = toCanvasPoint(point, currentViewState);
            if (canvasPoint == null)
            {
                started = false;
                continue;
            }

            if (!started)
            {
                path.moveTo(canvasPoint.getX(), canvasPoint.getY());
                started = true;
            }
            else
            {
                path.lineTo(canvasPoint.getX(), canvasPoint.getY());
            }
        }

        graphics.draw(path);
    }

    private void renderText(Graphics2D graphics, PaintText text, PaintWorldMapViewState currentViewState)
    {
        Point point = toCanvasPoint(text.worldX, text.worldY, text.offsetX, text.offsetY, currentViewState);
        if (point == null)
        {
            return;
        }

        Font previous = graphics.getFont();
        Font textFont = text.fontStyle.createFont(scaledWorldMapTextSize(text.fontSize, currentViewState));
        graphics.setFont(textFont);
        renderTextDecoration(graphics, point, textFont, text.text, worldMapTextScale(currentViewState),
            new Color(text.backgroundColorArgb, true),
            new Color(text.borderColorArgb, true));
        graphics.setColor(new Color(0, 0, 0, Math.min(255, new Color(text.colorArgb, true).getAlpha())));
        graphics.drawString(text.text, point.getX() + 1, point.getY() + 1);
        graphics.setColor(new Color(text.colorArgb, true));
        graphics.drawString(text.text, point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private void renderShape(Graphics2D graphics, PaintShape shape, PaintWorldMapViewState currentViewState)
    {
        Point center = toCanvasPoint(shape.worldX, shape.worldY, shape.offsetX, shape.offsetY, currentViewState);
        if (center == null || shape.shapeType == null)
        {
            return;
        }

        int size = Math.max(1, shape.size);
        Shape outline = PaintMath.shapeOutline(center, size, shape.shapeType);
        if (outline == null)
        {
            return;
        }

        java.awt.Stroke previousStroke = graphics.getStroke();
        graphics.setColor(new Color(shape.colorArgb, true));
        graphics.setStroke(new BasicStroke(2f));
        if (PaintMath.shouldFillShape(shape.shapeType))
        {
            graphics.fill(outline);
        }
        else
        {
            graphics.draw(outline);
        }
        graphics.setStroke(previousStroke);
    }

    private void renderPreview(Graphics2D graphics, PaintTarget target, Point mouseCanvas, PaintWorldMapViewState currentViewState)
    {
        if (plugin.getTool() == PaintTool.SHAPE)
        {
            if (target == null)
            {
                return;
            }

            Point point = toCanvasPoint(target.worldX, target.worldY, target.offsetX, target.offsetY, currentViewState);
            if (point == null)
            {
                return;
            }

            int size = plugin.getShapeSize();
            Shape outline = PaintMath.shapeOutline(point, size, plugin.getShapeType());
            if (outline == null)
            {
                return;
            }

            java.awt.Stroke previousStroke = graphics.getStroke();
            graphics.setColor(new Color(plugin.getColor().getRGB() & 0x00FFFFFF | 0xB4000000, true));
            graphics.setStroke(new BasicStroke(2f));
            if (PaintMath.shouldFillShape(plugin.getShapeType()))
            {
                graphics.fill(outline);
            }
            else
            {
                graphics.draw(outline);
            }
            graphics.setStroke(previousStroke);
            return;
        }

        if (plugin.getTool() != PaintTool.TEXT)
        {
            if (mouseCanvas == null || !currentViewState.containsCanvasPoint(mouseCanvas.getX(), mouseCanvas.getY()))
            {
                return;
            }

            int size = PaintMath.cursorRadius(plugin.getBrushSize()) * 2;
            graphics.setColor(plugin.getTool() == PaintTool.ERASER ? new Color(255, 80, 80, 180) : new Color(plugin.getColor().getRGB() & 0x00FFFFFF | 0xB4000000, true));
            graphics.drawOval(mouseCanvas.getX() - size / 2, mouseCanvas.getY() - size / 2, size, size);
            return;
        }

        if (target == null)
        {
            return;
        }

        Point point = toCanvasPoint(target.worldX, target.worldY, target.offsetX, target.offsetY, currentViewState);
        if (point == null)
        {
            return;
        }

        Font previous = graphics.getFont();
        Font textFont = plugin.getFontStyle().createFont(scaledWorldMapTextSize(plugin.getTextSize(), currentViewState));
        graphics.setFont(textFont);
        renderTextDecoration(graphics, point, textFont, plugin.getPendingText().trim().isEmpty() ? "Text" : plugin.getPendingText().trim(),
            worldMapTextScale(currentViewState),
            plugin.getTextBackgroundColor(),
            plugin.getTextBorderColor());
        graphics.setColor(new Color(plugin.getColor().getRed(), plugin.getColor().getGreen(), plugin.getColor().getBlue(), 180));
        graphics.drawString(plugin.getPendingText().trim().isEmpty() ? "Text" : plugin.getPendingText().trim(), point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private static void renderTextDecoration(Graphics2D graphics, Point point, Font font, String text,
                                             float scale,
                                             Color backgroundColor, Color borderColor)
    {
        if ((backgroundColor == null || backgroundColor.getAlpha() <= 0)
            && (borderColor == null || borderColor.getAlpha() <= 0))
        {
            return;
        }

        Rectangle2D bounds = PaintMath.textBounds(point, font, text);
        if (bounds == null)
        {
            return;
        }

        int paddingX = Math.max(2, Math.round(4 * scale));
        int paddingY = Math.max(1, Math.round(2 * scale));
        int x = (int) Math.floor(bounds.getX()) - paddingX;
        int y = (int) Math.floor(bounds.getY()) - paddingY;
        int width = (int) Math.ceil(bounds.getWidth()) + paddingX * 2;
        int height = (int) Math.ceil(bounds.getHeight()) + paddingY * 2;
        if (backgroundColor != null && backgroundColor.getAlpha() > 0)
        {
            graphics.setColor(backgroundColor);
            graphics.fillRoundRect(x, y, width, height, 6, 6);
        }

        if (borderColor != null && borderColor.getAlpha() > 0)
        {
            java.awt.Stroke previousStroke = graphics.getStroke();
            graphics.setColor(borderColor);
            graphics.setStroke(new BasicStroke(Math.max(1f, 1.5f * scale)));
            graphics.drawRoundRect(x, y, width, height, 6, 6);
            graphics.setStroke(previousStroke);
        }
    }

    private static int scaledWorldMapTextSize(int baseSize, PaintWorldMapViewState currentViewState)
    {
        return Math.max(8, Math.round(baseSize * worldMapTextScale(currentViewState)));
    }

    private static float worldMapTextScale(PaintWorldMapViewState currentViewState)
    {
        if (currentViewState == null)
        {
            return 1.0f;
        }

        float scale = currentViewState.getPixelsPerTile() / 4.0f;
        if (scale < 0.5f)
        {
            return 0.5f;
        }
        return Math.min(1.0f, scale);
    }

    private Point toCanvasPoint(PaintPoint point, PaintWorldMapViewState currentViewState)
    {
        return toCanvasPoint(point.worldX, point.worldY, point.offsetX, point.offsetY, currentViewState);
    }

    Point toCanvasPoint(int worldX, int worldY, int offsetX, int offsetY)
    {
        return viewState.toCanvasPoint(worldX, worldY, offsetX, offsetY);
    }

    private Point toCanvasPoint(int worldX, int worldY, int offsetX, int offsetY, PaintWorldMapViewState currentViewState)
    {
        return currentViewState.toCanvasPoint(worldX, worldY, offsetX, offsetY);
    }

    private PaintWorldMapViewState snapshotViewState()
    {
        Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
        WorldMap worldMap = client.getWorldMap();
        if (mapWidget == null || mapWidget.isHidden() || worldMap == null)
        {
            return PaintWorldMapViewState.unavailable();
        }

        Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
        Widget surfaceSelector = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0);
        Rectangle overviewBounds = overview != null && !overview.isHidden() ? overview.getBounds() : null;
        Rectangle surfaceSelectorBounds = surfaceSelector != null && !surfaceSelector.isHidden() ? surfaceSelector.getBounds() : null;

        return PaintWorldMapViewState.of(
            mapWidget.getBounds(),
            overviewBounds,
            surfaceSelectorBounds,
            worldMap.getWorldMapZoom(),
            worldMap.getWorldMapPosition());
    }
}
