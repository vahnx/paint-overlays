package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Path2D;
import java.awt.Shape;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class PaintOverlaysOverlay extends Overlay
{
    private static final float CLEAR_PREVIEW_PERIOD_MILLIS = 1800f;
    private static final int SCENE_CHUNK_SIZE_TILES = 64;
    private static final int LOCAL_TILE_SIZE = 128;
    private static final int HALF_TILE = LOCAL_TILE_SIZE / 2;
    private static final Color CLEAR_PREVIEW_AREA_BASE = new Color(215, 82, 82);
    private static final Color CLEAR_PREVIEW_CHUNK_BASE = new Color(248, 96, 96);
    private static final BasicStroke SHAPE_STROKE = new BasicStroke(2f);
    private static final BasicStroke TEXT_BORDER_STROKE = new BasicStroke(1.5f);
    private final Client client;
    private final PaintOverlaysPlugin plugin;

    @Inject
    private PaintOverlaysOverlay(Client client, PaintOverlaysPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (client.getLocalPlayer() == null || worldView == null)
        {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Collection<PaintChunkData> chunks = plugin.getVisibleSceneChunks();
        for (PaintChunkData chunk : chunks)
        {
            for (PaintStroke stroke : chunk.strokes)
            {
                renderStroke(graphics, stroke, worldView);
            }
        }

        PaintStroke activeStroke = plugin.getActiveSceneStroke();
        if (activeStroke != null)
        {
            renderStroke(graphics, activeStroke, worldView);
        }

        for (PaintChunkData chunk : chunks)
        {
            for (PaintShape shape : chunk.shapes)
            {
                renderShape(graphics, shape, worldView);
            }
        }

        for (PaintChunkData chunk : chunks)
        {
            for (PaintText text : chunk.texts)
            {
                renderText(graphics, text, worldView);
            }
        }

        renderSceneClearPreview(graphics, worldView);

        if (plugin.getPluginConfig().showCursorPreview() && plugin.getInputMode() == PaintInputMode.SCENE && plugin.isSceneInputAvailable())
        {
            PaintTool tool = plugin.getTool();
            PaintTarget previewTarget = tool == PaintTool.SHAPE || tool == PaintTool.TEXT
                ? plugin.getScenePreviewTarget()
                : null;
            renderPreview(graphics, previewTarget, plugin.getMouseCanvasPosition(), worldView);
        }

        return null;
    }

    private void renderStroke(Graphics2D graphics, PaintStroke stroke, WorldView worldView)
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

            Point canvasPoint = toCanvasPoint(worldView, stroke.plane, point);
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

    private void renderText(Graphics2D graphics, PaintText text, WorldView worldView)
    {
        Point point = toCanvasPoint(worldView, text.plane, text.worldX, text.worldY, text.offsetX, text.offsetY);
        if (point == null)
        {
            return;
        }

        Font previous = graphics.getFont();
        Font textFont = text.fontStyle.createFont(text.fontSize);
        graphics.setFont(textFont);
        renderTextDecoration(graphics, point, textFont, text.text,
            text.getBackgroundColor(),
            text.getBorderColor());
        graphics.setColor(text.getShadowColor());
        graphics.drawString(text.text, point.getX() + 1, point.getY() + 1);
        graphics.setColor(text.getColor());
        graphics.drawString(text.text, point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private void renderSceneClearPreview(Graphics2D graphics, WorldView worldView)
    {
        PaintOverlaysPlugin.SceneClearPreview preview = plugin.getSceneClearPreview();
        if (preview == null)
        {
            return;
        }

        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() * ((Math.PI * 2.0) / CLEAR_PREVIEW_PERIOD_MILLIS));
        Set<Integer> nearbyRegionIds = new HashSet<>(preview.nearbyRegionIds);
        renderSceneChunkPreview(graphics, worldView, preview, nearbyRegionIds, false, pulse);
        renderSceneChunkPreview(graphics, worldView, preview, nearbyRegionIds, true, pulse);
    }

    private void renderSceneChunkPreview(
        Graphics2D graphics,
        WorldView worldView,
        PaintOverlaysPlugin.SceneClearPreview preview,
        Set<Integer> nearbyRegionIds,
        boolean currentChunk,
        float pulse)
    {
        Scene scene = worldView.getScene();
        if (scene == null)
        {
            return;
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = worldView.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length)
        {
            return;
        }

        Tile[][] planeTiles = tiles[plane];
        if (planeTiles == null)
        {
            return;
        }

        Path2D.Float fillPath = new Path2D.Float();
        boolean hasGeometry = false;

        for (Tile[] column : planeTiles)
        {
            if (column == null)
            {
                continue;
            }

            for (Tile tile : column)
            {
                if (tile == null || tile.getRenderLevel() != plane)
                {
                    continue;
                }

                WorldPoint worldLocation = tile.getWorldLocation();
                if (worldLocation == null)
                {
                    continue;
                }

                int regionId = worldLocation.getRegionID();
                boolean inCurrentChunk = regionId == preview.currentRegionId;
                if (currentChunk != inCurrentChunk)
                {
                    continue;
                }
                if (!currentChunk && !nearbyRegionIds.contains(regionId))
                {
                    continue;
                }

                Polygon polygon = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());
                if (polygon == null)
                {
                    continue;
                }

                fillPath.append(polygon, false);
                hasGeometry = true;
            }
        }

        if (!hasGeometry)
        {
            return;
        }

        java.awt.Stroke previousStroke = graphics.getStroke();
        Color baseColor = currentChunk ? CLEAR_PREVIEW_CHUNK_BASE : CLEAR_PREVIEW_AREA_BASE;
        int fillAlpha = currentChunk ? Math.round(46 + pulse * 18f) : Math.round(18 + pulse * 10f);
        int outlineAlpha = currentChunk ? Math.round(185 + pulse * 28f) : Math.round(88 + pulse * 18f);

        graphics.setColor(withAlpha(baseColor, fillAlpha));
        graphics.fill(fillPath);

        graphics.setColor(withAlpha(baseColor.brighter(), outlineAlpha));
        graphics.setStroke(new BasicStroke(currentChunk ? 3f : 1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Integer regionId : nearbyRegionIds)
        {
            if (regionId == null)
            {
                continue;
            }
            if (currentChunk != (regionId == preview.currentRegionId))
            {
                continue;
            }

            Polygon outline = sceneChunkOutlinePolygon(worldView, plane, regionId);
            if (outline != null)
            {
                graphics.drawPolygon(outline);
            }
        }
        graphics.setStroke(previousStroke);
    }

    private void renderShape(Graphics2D graphics, PaintShape shape, WorldView worldView)
    {
        Point center = toCanvasPoint(worldView, shape.plane, shape.worldX, shape.worldY, shape.offsetX, shape.offsetY);
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

    private void renderPreview(Graphics2D graphics, PaintTarget target, Point mouseCanvas, WorldView worldView)
    {
        if (plugin.getTool() == PaintTool.SHAPE)
        {
            if (target == null)
            {
                return;
            }

            Point point = toCanvasPoint(worldView, target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY);
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
            if (mouseCanvas == null)
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

        Point point = toCanvasPoint(worldView, target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY);
        if (point == null)
        {
            return;
        }

        Font previous = graphics.getFont();
        Font textFont = plugin.getFontStyle().createFont(plugin.getTextSize());
        graphics.setFont(textFont);
        renderTextDecoration(graphics, point, textFont, plugin.getPendingText().trim().isEmpty() ? "Text" : plugin.getPendingText().trim(),
            plugin.getTextBackgroundColor(),
            plugin.getTextBorderColor());
        graphics.setColor(new Color(plugin.getColor().getRed(), plugin.getColor().getGreen(), plugin.getColor().getBlue(), 180));
        graphics.drawString(plugin.getPendingText().trim().isEmpty() ? "Text" : plugin.getPendingText().trim(), point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private static void renderTextDecoration(Graphics2D graphics, Point point, Font font, String text,
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

        int paddingX = 4;
        int paddingY = 2;
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
            graphics.setStroke(TEXT_BORDER_STROKE);
            graphics.drawRoundRect(x, y, width, height, 6, 6);
            graphics.setStroke(previousStroke);
        }
    }

    private Point toCanvasPoint(WorldView worldView, int plane, PaintPoint point)
    {
        return toCanvasPoint(worldView, plane, point.worldX, point.worldY, point.offsetX, point.offsetY);
    }

    private Polygon sceneChunkOutlinePolygon(WorldView worldView, int plane, int regionId)
    {
        if (worldView == null || plane != worldView.getPlane())
        {
            return null;
        }

        int regionX = regionId >>> 8;
        int regionY = regionId & 0xFF;
        LocalPoint origin = LocalPoint.fromWorld(worldView, regionX << 6, regionY << 6);
        if (origin == null)
        {
            return null;
        }

        LocalPoint chunkCenter = origin.plus(
            SCENE_CHUNK_SIZE_TILES * HALF_TILE - HALF_TILE,
            SCENE_CHUNK_SIZE_TILES * HALF_TILE - HALF_TILE);
        return Perspective.getCanvasTileAreaPoly(client, chunkCenter, SCENE_CHUNK_SIZE_TILES);
    }

    private Point toCanvasPoint(WorldView worldView, int plane, int worldX, int worldY, int offsetX, int offsetY)
    {
        LocalPoint tileCenter = LocalPoint.fromWorld(worldView, worldX, worldY);
        if (tileCenter == null)
        {
            return null;
        }

        LocalPoint localPoint = tileCenter.plus(offsetX - 64, offsetY - 64);
        return Perspective.localToCanvas(client, localPoint, plane);
    }

    private static Color withAlpha(Color color, int alpha)
    {
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha);
    }
}
