package com.paintoverlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Path2D;
import java.awt.Shape;
import java.util.Collection;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class PaintOverlaysOverlay extends Overlay
{
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
        if (client.getLocalPlayer() == null)
        {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Collection<PaintChunkData> chunks = plugin.getVisibleSceneChunks();
        for (PaintChunkData chunk : chunks)
        {
            renderChunk(graphics, chunk);
        }

        PaintStroke activeStroke = plugin.getActiveSceneStroke();
        if (activeStroke != null)
        {
            renderStroke(graphics, activeStroke);
        }

        if (plugin.getPluginConfig().showCursorPreview() && plugin.getInputMode() == PaintInputMode.SCENE && plugin.isSceneInputAvailable())
        {
            PaintTool tool = plugin.getTool();
            PaintTarget previewTarget = tool == PaintTool.SHAPE || tool == PaintTool.TEXT
                ? plugin.getScenePreviewTarget()
                : null;
            renderPreview(graphics, previewTarget, plugin.getMouseCanvasPosition());
        }

        return null;
    }

    private void renderChunk(Graphics2D graphics, PaintChunkData chunk)
    {
        for (PaintStroke stroke : chunk.strokes)
        {
            renderStroke(graphics, stroke);
        }

        for (PaintShape shape : chunk.shapes)
        {
            renderShape(graphics, shape);
        }

        for (PaintText text : chunk.texts)
        {
            renderText(graphics, text);
        }
    }

    private void renderStroke(Graphics2D graphics, PaintStroke stroke)
    {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty())
        {
            return;
        }

        graphics.setColor(new Color(stroke.colorArgb, true));
        graphics.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (stroke.points.size() == 1)
        {
            Point point = toCanvasPoint(stroke.plane, stroke.points.get(0));
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
            Point canvasPoint = toCanvasPoint(stroke.plane, point);
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

    private void renderText(Graphics2D graphics, PaintText text)
    {
        Point point = toCanvasPoint(text.plane, text.worldX, text.worldY, text.offsetX, text.offsetY);
        if (point == null)
        {
            return;
        }

        Font previous = graphics.getFont();
        Font textFont = text.fontStyle.createFont(text.fontSize);
        graphics.setFont(textFont);
        renderTextDecoration(graphics, point, textFont, text.text,
            new Color(text.backgroundColorArgb, true),
            new Color(text.borderColorArgb, true));
        graphics.setColor(new Color(0, 0, 0, Math.min(255, new Color(text.colorArgb, true).getAlpha())));
        graphics.drawString(text.text, point.getX() + 1, point.getY() + 1);
        graphics.setColor(new Color(text.colorArgb, true));
        graphics.drawString(text.text, point.getX(), point.getY());
        graphics.setFont(previous);
    }

    private void renderShape(Graphics2D graphics, PaintShape shape)
    {
        Point center = toCanvasPoint(shape.plane, shape.worldX, shape.worldY, shape.offsetX, shape.offsetY);
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

    private void renderPreview(Graphics2D graphics, PaintTarget target, Point mouseCanvas)
    {
        if (plugin.getTool() == PaintTool.SHAPE)
        {
            if (target == null)
            {
                return;
            }

            Point point = toCanvasPoint(target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY);
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

        Point point = toCanvasPoint(target.plane, target.worldX, target.worldY, target.offsetX, target.offsetY);
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
            graphics.setStroke(new BasicStroke(1.5f));
            graphics.drawRoundRect(x, y, width, height, 6, 6);
            graphics.setStroke(previousStroke);
        }
    }

    private Point toCanvasPoint(int plane, PaintPoint point)
    {
        return toCanvasPoint(plane, point.worldX, point.worldY, point.offsetX, point.offsetY);
    }

    private Point toCanvasPoint(int plane, int worldX, int worldY, int offsetX, int offsetY)
    {
        LocalPoint tileCenter = LocalPoint.fromWorld(client.getTopLevelWorldView(), worldX, worldY);
        if (tileCenter == null)
        {
            return null;
        }

        LocalPoint localPoint = tileCenter.plus(offsetX - 64, offsetY - 64);
        return Perspective.localToCanvas(client, localPoint, plane);
    }
}
