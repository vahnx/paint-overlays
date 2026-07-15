package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
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
    private static final float CLEAR_PREVIEW_PERIOD_MILLIS = 1800f;
    private static final Color CLEAR_PREVIEW_AREA_BASE = new Color(126, 28, 28);
    private static final Color CLEAR_PREVIEW_CHUNK_BASE = new Color(92, 0, 0);
    private static final BasicStroke SHAPE_STROKE = new BasicStroke(2f);
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

        Shape previousClip = graphics.getClip();
        try
        {
            graphics.clip(currentViewState.getClipArea());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Collection<PaintChunkData> chunks = plugin.getVisibleMapChunks(currentViewState.getVisibleRegionIds());
            for (PaintChunkData chunk : chunks)
            {
                for (PaintStroke stroke : chunk.strokes)
                {
                    renderStroke(graphics, stroke, currentViewState);
                }
            }

            PaintStroke activeStroke = plugin.getActiveMapStroke();
            if (activeStroke != null)
            {
                renderStroke(graphics, activeStroke, currentViewState);
            }

            for (PaintChunkData chunk : chunks)
            {
                for (PaintShape shape : chunk.shapes)
                {
                    renderShape(graphics, shape, currentViewState);
                }
            }

            for (PaintChunkData chunk : chunks)
            {
                for (PaintText text : chunk.texts)
                {
                    renderText(graphics, text, currentViewState);
                }
            }

            renderMapClearPreview(graphics, currentViewState);

            if (plugin.getPluginConfig().showCursorPreview() && plugin.getInputMode() == PaintInputMode.WORLD_MAP)
            {
                Point mouseCanvas = plugin.getMouseCanvasPosition();
                PaintTool tool = plugin.getTool();
                PaintTarget previewTarget = mouseCanvas != null && (tool == PaintTool.SHAPE || tool == PaintTool.TEXT)
                    ? currentViewState.getTarget(mouseCanvas.getX(), mouseCanvas.getY())
                    : null;
                renderPreview(
                    graphics,
                    previewTarget,
                    mouseCanvas,
                    currentViewState);
            }
        }
        finally
        {
            graphics.setClip(previousClip);
        }

        return null;
    }

    Collection<Integer> getVisibleRegionIds()
    {
        return viewState.getVisibleRegionIds();
    }

    int getCenterRegionId()
    {
        return viewState.getCenterRegionId();
    }

    void resetViewState()
    {
        viewState = PaintWorldMapViewState.unavailable();
    }

    PaintTarget getTarget(int mouseX, int mouseY)
    {
        return viewState.getTarget(mouseX, mouseY);
    }

    boolean containsCanvasPoint(int mouseX, int mouseY)
    {
        return viewState.containsCanvasPoint(mouseX, mouseY);
    }

    boolean canSweepBetweenCanvasPoints(int startX, int startY, int endX, int endY)
    {
        return viewState.canSweepBetweenCanvasPoints(startX, startY, endX, endY);
    }

    boolean canEraseBetweenCanvasPoints(int startX, int startY, int endX, int endY, int radius)
    {
        return viewState.canEraseBetweenCanvasPoints(startX, startY, endX, endY, radius);
    }

    private void renderStroke(Graphics2D graphics, PaintStroke stroke, PaintWorldMapViewState currentViewState)
    {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty())
        {
            return;
        }

        java.awt.Stroke previousStroke = graphics.getStroke();
        graphics.setColor(stroke.getColor());
        graphics.setStroke(PaintMath.roundStroke(stroke.width));

        Path2D.Float path = new Path2D.Float();
        boolean started = false;
        Point segmentStart = null;
        int segmentPointCount = 0;
        for (PaintPoint point : stroke.points)
        {
            if (point == null || point.startsNewSegment())
            {
                renderSingletonStrokeSegment(graphics, segmentStart, segmentPointCount, stroke.width);
                started = false;
                segmentStart = null;
                segmentPointCount = 0;
            }
            if (point == null)
            {
                continue;
            }

            Point canvasPoint = toCanvasPoint(point, currentViewState);
            if (canvasPoint == null)
            {
                renderSingletonStrokeSegment(graphics, segmentStart, segmentPointCount, stroke.width);
                started = false;
                segmentStart = null;
                segmentPointCount = 0;
                continue;
            }

            if (!started)
            {
                path.moveTo(canvasPoint.getX(), canvasPoint.getY());
                started = true;
                segmentStart = canvasPoint;
                segmentPointCount = 1;
            }
            else
            {
                path.lineTo(canvasPoint.getX(), canvasPoint.getY());
                segmentPointCount++;
            }
        }

        renderSingletonStrokeSegment(graphics, segmentStart, segmentPointCount, stroke.width);
        graphics.draw(path);
        graphics.setStroke(previousStroke);
    }

    private static void renderSingletonStrokeSegment(Graphics2D graphics, Point point, int pointCount, int width)
    {
        if (point == null || pointCount != 1)
        {
            return;
        }

        int radius = Math.max(2, width / 2);
        graphics.fillOval(point.getX() - radius, point.getY() - radius, radius * 2, radius * 2);
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
            text.getBackgroundColor(),
            text.getBorderColor());
        graphics.setColor(text.getShadowColor());
        graphics.drawString(text.text, point.getX() + 1, point.getY() + 1);
        graphics.setColor(text.getColor());
        graphics.drawString(text.text, point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private void renderMapClearPreview(Graphics2D graphics, PaintWorldMapViewState currentViewState)
    {
        PaintOverlaysPlugin.MapClearPreview preview = plugin.getMapClearPreview();
        if (preview == null)
        {
            return;
        }

        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() * ((Math.PI * 2.0) / CLEAR_PREVIEW_PERIOD_MILLIS));
        for (Integer regionId : preview.visibleRegionIds)
        {
            if (regionId == null || regionId == preview.currentRegionId)
            {
                continue;
            }

            renderMapRegionPreview(graphics, currentViewState, regionId, false, pulse);
        }

        if (preview.currentRegionId >= 0)
        {
            renderMapRegionPreview(graphics, currentViewState, preview.currentRegionId, true, pulse);
        }
    }

    private void renderMapRegionPreview(Graphics2D graphics, PaintWorldMapViewState currentViewState, int regionId, boolean currentChunk, float pulse)
    {
        Polygon polygon = mapRegionPolygon(regionId, currentViewState);
        if (polygon == null)
        {
            return;
        }

        java.awt.Stroke previousStroke = graphics.getStroke();
        Color baseColor = currentChunk ? CLEAR_PREVIEW_CHUNK_BASE : CLEAR_PREVIEW_AREA_BASE;
        graphics.setColor(withAlpha(baseColor, currentChunk ? Math.round(88 + pulse * 52f) : Math.round(34 + pulse * 22f)));
        graphics.fillPolygon(polygon);

        graphics.setColor(withAlpha(baseColor.brighter(), currentChunk ? Math.round(94 + pulse * 42f) : Math.round(58 + pulse * 30f)));
        graphics.setStroke(new BasicStroke(currentChunk ? 6f + pulse * 2f : 3f + pulse * 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.drawPolygon(polygon);

        graphics.setColor(withAlpha(baseColor.brighter(), currentChunk ? Math.round(185 + pulse * 45f) : Math.round(118 + pulse * 35f)));
        graphics.setStroke(new BasicStroke(currentChunk ? 2.5f + pulse : 1.5f + pulse * 0.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.drawPolygon(polygon);
        graphics.setStroke(previousStroke);
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
        graphics.setColor(shape.getColor());
        graphics.setStroke(SHAPE_STROKE);
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

    private Polygon mapRegionPolygon(int regionId, PaintWorldMapViewState currentViewState)
    {
        int regionX = regionId >>> 8;
        int regionY = regionId & 0xFF;
        int worldX = regionX << 6;
        int worldY = regionY << 6;

        Point southWest = toCanvasPoint(worldX, worldY, 0, 0, currentViewState);
        Point southEast = toCanvasPoint(worldX + 63, worldY, 128, 0, currentViewState);
        Point northEast = toCanvasPoint(worldX + 63, worldY + 63, 128, 128, currentViewState);
        Point northWest = toCanvasPoint(worldX, worldY + 63, 0, 128, currentViewState);
        if (southWest == null || southEast == null || northEast == null || northWest == null)
        {
            return null;
        }

        Polygon polygon = new Polygon();
        polygon.addPoint(northWest.getX(), northWest.getY());
        polygon.addPoint(northEast.getX(), northEast.getY());
        polygon.addPoint(southEast.getX(), southEast.getY());
        polygon.addPoint(southWest.getX(), southWest.getY());
        return polygon;
    }

    Point toCanvasPoint(int worldX, int worldY, int offsetX, int offsetY)
    {
        return viewState.toCanvasPoint(worldX, worldY, offsetX, offsetY);
    }

    Font getRenderedTextFont(PaintText text)
    {
        return text.fontStyle.createFont(scaledWorldMapTextSize(text.fontSize, viewState));
    }

    private Point toCanvasPoint(int worldX, int worldY, int offsetX, int offsetY, PaintWorldMapViewState currentViewState)
    {
        return currentViewState.toCanvasPoint(worldX, worldY, offsetX, offsetY);
    }

    private static Color withAlpha(Color color, int alpha)
    {
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha);
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

        Rectangle mapBounds = mapWidget.getBounds();
        float pixelsPerTile = worldMap.getWorldMapZoom();
        Point center = worldMap.getWorldMapPosition();
        PaintWorldMapViewState current = viewState;
        if (current.matches(mapBounds, overviewBounds, surfaceSelectorBounds, pixelsPerTile, center))
        {
            return current;
        }

        return PaintWorldMapViewState.of(
            mapBounds,
            overviewBounds,
            surfaceSelectorBounds,
            pixelsPerTile,
            center);
    }
}
