package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
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
    private static final double GROUND_PROJECTED_STAMP_SCALE = 3.0;
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
            for (PaintStamp stamp : chunk.stamps)
            {
                renderStamp(graphics, stamp, worldView);
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
                || tool == PaintTool.STAMP
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
            text.getBorderColor(),
            text.frameStyle);
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

        int size = scaledSceneObjectSize(shape.size, worldView);
        Shape outline = PaintMath.shapeOutline(center, size, shape.shapeType);
        if (outline == null)
        {
            return;
        }

        java.awt.Stroke previousStroke = graphics.getStroke();
        java.awt.geom.AffineTransform previousTransform = graphics.getTransform();
        graphics.setColor(shape.getColor());
        graphics.setStroke(SHAPE_STROKE);
        applyObjectTransform(graphics, center, shape.rotationDegrees, shape.flipHorizontal);
        if (PaintMath.shouldFillShape(shape.shapeType, shape.filled))
        {
            graphics.fill(outline);
        }
        else
        {
            graphics.draw(outline);
        }
        graphics.setTransform(previousTransform);
        graphics.setStroke(previousStroke);
    }

    private void renderStamp(Graphics2D graphics, PaintStamp stamp, WorldView worldView)
    {
        Point center = toCanvasPoint(worldView, stamp.plane, stamp.worldX, stamp.worldY, stamp.offsetX, stamp.offsetY);
        BufferedImage image = PaintStamps.getImage(stamp);
        if (center == null || image == null)
        {
            return;
        }

        int size = scaledSceneObjectSize(stamp.size, worldView);
        Object previousInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        AffineTransform previousTransform = graphics.getTransform();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (!drawGroundProjectedImage(graphics, image, worldView, stamp.plane, stamp.worldX, stamp.worldY, stamp.offsetX, stamp.offsetY,
            size, stamp.rotationDegrees, stamp.flipHorizontal))
        {
            applyObjectTransform(graphics, center, stamp.rotationDegrees, stamp.flipHorizontal);
            graphics.drawImage(image, center.getX() - size / 2, center.getY() - size / 2, size, size, null);
        }
        graphics.setTransform(previousTransform);
        restoreInterpolationHint(graphics, previousInterpolation);
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

            int size = scaledSceneObjectSize(plugin.getShapeSize(), worldView);
            Shape outline = PaintMath.shapeOutline(point, size, plugin.getShapeType());
            if (outline == null)
            {
                return;
            }

            java.awt.Stroke previousStroke = graphics.getStroke();
            java.awt.geom.AffineTransform previousTransform = graphics.getTransform();
            graphics.setColor(new Color(plugin.getColor().getRGB() & 0x00FFFFFF | 0xB4000000, true));
            graphics.setStroke(new BasicStroke(2f));
            applyObjectTransform(graphics, point, plugin.getShapeRotationDegrees(), plugin.isShapeFlipHorizontal());
            if (PaintMath.shouldFillShape(plugin.getShapeType(), plugin.isShapeFillEnabled()))
            {
                graphics.fill(outline);
            }
            else
            {
                graphics.draw(outline);
            }
            graphics.setTransform(previousTransform);
            graphics.setStroke(previousStroke);
            return;
        }

        if (plugin.getTool() == PaintTool.STAMP)
        {
            if (target == null)
            {
                return;
            }

            Point point = toCanvasPoint(worldView, target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY);
            BufferedImage image = PaintStamps.getImage(plugin.getStampType());
            if (point == null || image == null)
            {
                return;
            }

            int size = scaledSceneObjectSize(plugin.getShapeSize(), worldView);
            Object previousInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            AffineTransform previousTransform = graphics.getTransform();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if (!drawGroundProjectedImage(graphics, image, worldView, target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY,
                size, plugin.getShapeRotationDegrees(), plugin.isShapeFlipHorizontal()))
            {
                applyObjectTransform(graphics, point, plugin.getShapeRotationDegrees(), plugin.isShapeFlipHorizontal());
                graphics.drawImage(image, point.getX() - size / 2, point.getY() - size / 2, size, size, null);
            }
            graphics.setTransform(previousTransform);
            restoreInterpolationHint(graphics, previousInterpolation);
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
            plugin.getTextBorderColor(),
            plugin.getTextFrameStyle());
        graphics.setColor(new Color(plugin.getColor().getRed(), plugin.getColor().getGreen(), plugin.getColor().getBlue(), 180));
        graphics.drawString(plugin.getPendingText().trim().isEmpty() ? "Text" : plugin.getPendingText().trim(), point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private static void renderTextDecoration(Graphics2D graphics, Point point, Font font, String text,
                                             Color backgroundColor, Color borderColor, PaintTextFrameStyle frameStyle)
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
        Shape frame = textFrameShape(x, y, width, height, 6, frameStyle);
        if (backgroundColor != null && backgroundColor.getAlpha() > 0)
        {
            graphics.setColor(backgroundColor);
            graphics.fill(frame);
        }

        if (borderColor != null && borderColor.getAlpha() > 0)
        {
            java.awt.Stroke previousStroke = graphics.getStroke();
            graphics.setColor(borderColor);
            graphics.setStroke(TEXT_BORDER_STROKE);
            graphics.draw(frame);
            graphics.setStroke(previousStroke);
        }
    }

    private static Shape textFrameShape(int x, int y, int width, int height, int arc, PaintTextFrameStyle frameStyle)
    {
        if (frameStyle != PaintTextFrameStyle.SPEECH_BUBBLE)
        {
            return new RoundRectangle2D.Double(x, y, width, height, arc, arc);
        }

        int radius = Math.max(2, arc / 2);
        int tailStartX = x + Math.min(width - radius - 12, Math.max(radius + 8, 12));
        int tailTipX = tailStartX - 8;
        int tailEndX = tailStartX + 16;
        int bottom = y + height;
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x + radius, y);
        path.lineTo(x + width - radius, y);
        path.quadTo(x + width, y, x + width, y + radius);
        path.lineTo(x + width, bottom - radius);
        path.quadTo(x + width, bottom, x + width - radius, bottom);
        path.lineTo(tailEndX, bottom);
        path.lineTo(tailTipX, bottom + 10);
        path.lineTo(tailStartX, bottom);
        path.lineTo(x + radius, bottom);
        path.quadTo(x, bottom, x, bottom - radius);
        path.lineTo(x, y + radius);
        path.quadTo(x, y, x + radius, y);
        path.closePath();
        return path;
    }

    private static void restoreInterpolationHint(Graphics2D graphics, Object previousInterpolation)
    {
        if (previousInterpolation == null)
        {
            graphics.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
            return;
        }

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation);
    }

    private static void applyObjectTransform(Graphics2D graphics, Point center, int rotationDegrees, boolean flipHorizontal)
    {
        graphics.translate(center.getX(), center.getY());
        graphics.rotate(Math.toRadians(rotationDegrees));
        if (flipHorizontal)
        {
            graphics.scale(-1.0, 1.0);
        }
        graphics.translate(-center.getX(), -center.getY());
    }

    private boolean drawGroundProjectedImage(
        Graphics2D graphics,
        BufferedImage image,
        WorldView worldView,
        int plane,
        int worldX,
        int worldY,
        int offsetX,
        int offsetY,
        int size,
        int rotationDegrees,
        boolean flipHorizontal)
    {
        Point center = toCanvasPoint(worldView, plane, worldX, worldY, offsetX, offsetY);
        if (center == null || image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || size <= 0)
        {
            return false;
        }

        double centerX = PaintMath.continuousCoordinate(worldX, offsetX);
        double centerY = PaintMath.continuousCoordinate(worldY, offsetY);
        double halfTiles = size * GROUND_PROJECTED_STAMP_SCALE / (double) (LOCAL_TILE_SIZE * 2);
        double radians = Math.toRadians(rotationDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double flip = flipHorizontal ? -1.0 : 1.0;

        Point xAxis = toCanvasPoint(worldView, plane, centerX + cos * halfTiles * flip, centerY - sin * halfTiles * flip);
        Point yAxis = toCanvasPoint(worldView, plane, centerX - sin * halfTiles, centerY - cos * halfTiles);
        if (xAxis == null || yAxis == null)
        {
            return false;
        }

        double xVectorX = xAxis.getX() - center.getX();
        double xVectorY = xAxis.getY() - center.getY();
        double yVectorX = yAxis.getX() - center.getX();
        double yVectorY = yAxis.getY() - center.getY();
        if ((Math.abs(xVectorX) + Math.abs(xVectorY) < 0.5)
            || (Math.abs(yVectorX) + Math.abs(yVectorY) < 0.5))
        {
            return false;
        }

        AffineTransform transform = new AffineTransform(
            (xVectorX * 2.0) / image.getWidth(),
            (xVectorY * 2.0) / image.getWidth(),
            (yVectorX * 2.0) / image.getHeight(),
            (yVectorY * 2.0) / image.getHeight(),
            center.getX() - xVectorX - yVectorX,
            center.getY() - xVectorY - yVectorY);
        graphics.drawImage(image, transform, null);
        return true;
    }

    private int scaledSceneObjectSize(int baseSize, WorldView worldView)
    {
        return Math.max(1, baseSize);
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

        Polygon tilePoly = Perspective.getCanvasTilePoly(client, tileCenter);
        if (tilePoly != null && tilePoly.npoints == 4)
        {
            return interpolateTileCanvasPoint(tilePoly, offsetX, offsetY);
        }

        LocalPoint localPoint = tileCenter.plus(offsetX - 64, offsetY - 64);
        return Perspective.localToCanvas(client, localPoint, plane);
    }

    private Point toCanvasPoint(WorldView worldView, int plane, double continuousX, double continuousY)
    {
        int worldX = (int) Math.floor(continuousX + 0.5);
        int worldY = (int) Math.floor(continuousY + 0.5);
        int offsetX = PaintOverlaysPlugin.clampOffset((int) Math.round((continuousX - worldX) * LOCAL_TILE_SIZE + HALF_TILE));
        int offsetY = PaintOverlaysPlugin.clampOffset((int) Math.round((continuousY - worldY) * LOCAL_TILE_SIZE + HALF_TILE));
        return toCanvasPoint(worldView, plane, worldX, worldY, offsetX, offsetY);
    }

    private static Point interpolateTileCanvasPoint(Polygon polygon, int offsetX, int offsetY)
    {
        double u = offsetY / (double) LOCAL_TILE_SIZE;
        double v = offsetX / (double) LOCAL_TILE_SIZE;
        int x = (int) Math.round(bilerp(
            polygon.xpoints[0],
            polygon.xpoints[1],
            polygon.xpoints[2],
            polygon.xpoints[3],
            u,
            v));
        int y = (int) Math.round(bilerp(
            polygon.ypoints[0],
            polygon.ypoints[1],
            polygon.ypoints[2],
            polygon.ypoints[3],
            u,
            v));
        return new Point(x, y);
    }

    private static double bilerp(double a, double b, double c, double d, double u, double v)
    {
        double west = a * (1.0 - v) + b * v;
        double east = d * (1.0 - v) + c * v;
        return west * (1.0 - u) + east * u;
    }

    private static Color withAlpha(Color color, int alpha)
    {
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha);
    }
}


