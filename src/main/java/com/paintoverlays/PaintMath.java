package com.paintoverlays;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.GlyphVector;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.Shape;
import net.runelite.api.Point;

final class PaintMath
{
    static final int MAX_TEXT_LENGTH = 80;
    private static final FontRenderContext FONT_RENDER_CONTEXT = new FontRenderContext(null, true, true);

    private PaintMath()
    {
    }

    static double continuousCoordinate(int worldCoordinate, int offset)
    {
        return worldCoordinate + (offset - 64) / 128.0;
    }

    static int cursorRadius(int brushSize)
    {
        return Math.max(2, brushSize);
    }

    static boolean withinCanvasRadius(Point canvasPoint, int mouseX, int mouseY, int radius)
    {
        if (canvasPoint == null)
        {
            return false;
        }

        long dx = (long) canvasPoint.getX() - mouseX;
        long dy = (long) canvasPoint.getY() - mouseY;
        long limit = (long) radius * radius;
        return dx * dx + dy * dy <= limit;
    }

    static boolean segmentWithinCanvasRadius(Point start, Point end, int mouseX, int mouseY, int radius)
    {
        if (start == null || end == null)
        {
            return false;
        }

        long dx = (long) end.getX() - start.getX();
        long dy = (long) end.getY() - start.getY();
        if (dx == 0L && dy == 0L)
        {
            return withinCanvasRadius(start, mouseX, mouseY, radius);
        }

        double t = ((mouseX - start.getX()) * (double) dx + (mouseY - start.getY()) * (double) dy)
            / (dx * (double) dx + dy * (double) dy);
        if (t < 0.0)
        {
            t = 0.0;
        }
        else if (t > 1.0)
        {
            t = 1.0;
        }

        double closestX = start.getX() + dx * t;
        double closestY = start.getY() + dy * t;
        double diffX = closestX - mouseX;
        double diffY = closestY - mouseY;
        return diffX * diffX + diffY * diffY <= radius * (double) radius;
    }

    static boolean textWithinCanvasRadius(Point baselinePoint, Font font, String text, int mouseX, int mouseY, int radius)
    {
        Rectangle2D bounds = textBounds(baselinePoint, font, text);
        if (bounds == null)
        {
            return false;
        }

        double closestX = clamp(mouseX, bounds.getMinX(), bounds.getMaxX());
        double closestY = clamp(mouseY, bounds.getMinY(), bounds.getMaxY());
        double diffX = closestX - mouseX;
        double diffY = closestY - mouseY;
        return diffX * diffX + diffY * diffY <= radius * (double) radius;
    }

    static Rectangle2D textBounds(Point baselinePoint, Font font, String text)
    {
        if (baselinePoint == null || font == null || text == null || text.isEmpty())
        {
            return null;
        }

        GlyphVector glyphVector = font.createGlyphVector(FONT_RENDER_CONTEXT, text);
        Rectangle bounds = glyphVector.getPixelBounds(
            FONT_RENDER_CONTEXT,
            baselinePoint.getX(),
            baselinePoint.getY());
        Rectangle shadowBounds = glyphVector.getPixelBounds(
            FONT_RENDER_CONTEXT,
            baselinePoint.getX() + 1,
            baselinePoint.getY() + 1);
        bounds = bounds.union(shadowBounds);
        return new Rectangle2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    static Shape shapeOutline(Point center, int size, PaintShapeType shapeType)
    {
        if (center == null || shapeType == null)
        {
            return null;
        }

        double safeSize = Math.max(1, size);
        double half = safeSize / 2.0;
        double centerX = center.getX();
        double centerY = center.getY();
        double left = centerX - half;
        double top = centerY - half;
        double right = centerX + half;
        double bottom = centerY + half;

        switch (shapeType)
        {
            case CIRCLE:
                return new Ellipse2D.Double(left, top, safeSize, safeSize);
            case X:
                return buildX(left, top, right, bottom);
            case TRIANGLE:
                return buildTriangle(centerX, top, left, bottom, right, bottom);
            case DIAMOND:
                return buildDiamond(centerX, top, right, centerY, centerX, bottom, left, centerY);
            case STAR:
                return buildStar(centerX, centerY, half);
            case PLUS:
                return buildPlus(centerX, centerY, half);
            case SKULL:
                return buildSkull(centerX, centerY, safeSize);
            case PRAYER_STAR:
                return buildPrayerStar(centerX, centerY, safeSize);
            case TREASURE_CHEST:
                return buildTreasureChest(centerX, centerY, safeSize);
            case SPIDER_WEB:
                return buildSpiderWeb(centerX, centerY, safeSize);
            case TARGET:
                return buildTarget(centerX, centerY, safeSize);
            case RECTANGLE:
            default:
                return new Rectangle2D.Double(left, top, safeSize, safeSize);
        }
    }

    static boolean shouldFillShape(PaintShapeType shapeType)
    {
        return false;
    }

    static String sanitizePendingText(String text)
    {
        if (text == null || text.isEmpty())
        {
            return "";
        }

        StringBuilder builder = new StringBuilder(Math.min(text.length(), MAX_TEXT_LENGTH));
        for (int i = 0; i < text.length() && builder.length() < MAX_TEXT_LENGTH; i++)
        {
            char current = text.charAt(i);
            if (current == '\r' || current == '\n' || current == '\t')
            {
                current = ' ';
            }

            if (Character.isISOControl(current))
            {
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }

    static PaintTarget worldMapTarget(Rectangle bounds, float pixelsPerTile, Point center, int mouseX, int mouseY)
    {
        if (bounds == null || center == null || pixelsPerTile <= 0 || !bounds.contains(mouseX, mouseY))
        {
            return null;
        }

        int widthInTiles = (int) Math.ceil(bounds.getWidth() / pixelsPerTile);
        int heightInTiles = (int) Math.ceil(bounds.getHeight() / pixelsPerTile);
        double adjustment = pixelsPerTile - Math.ceil(pixelsPerTile / 2.0);

        double xGraphDiff = mouseX - bounds.getX();
        double yGraphDiff = bounds.getHeight() - (mouseY - bounds.getY());

        double worldX = center.getX() - widthInTiles / 2.0 + (xGraphDiff - adjustment) / pixelsPerTile;
        double worldY = (center.getY() - heightInTiles / 2.0) - 1.0 + (yGraphDiff + adjustment) / pixelsPerTile;

        int tileX = (int) Math.floor(worldX + 0.5);
        int tileY = (int) Math.floor(worldY + 0.5);
        int offsetX = PaintOverlaysPlugin.clampOffset((int) Math.round((worldX - tileX) * 128.0 + 64.0));
        int offsetY = PaintOverlaysPlugin.clampOffset((int) Math.round((worldY - tileY) * 128.0 + 64.0));
        return new PaintTarget(tileX, tileY, 0, offsetX, offsetY);
    }

    static Point worldMapCanvasPoint(Rectangle bounds, float pixelsPerTile, Point center, int worldX, int worldY, int offsetX, int offsetY)
    {
        if (bounds == null || center == null || pixelsPerTile <= 0)
        {
            return null;
        }

        int widthInTiles = (int) Math.ceil(bounds.getWidth() / pixelsPerTile);
        int heightInTiles = (int) Math.ceil(bounds.getHeight() / pixelsPerTile);

        double worldContinuousX = continuousCoordinate(worldX, offsetX);
        double worldContinuousY = continuousCoordinate(worldY, offsetY);
        double adjustment = pixelsPerTile - Math.ceil(pixelsPerTile / 2.0);
        double yTileMax = center.getY() - heightInTiles / 2.0;
        double xTileOffset = worldContinuousX + widthInTiles / 2.0 - center.getX();
        double yTileOffset = (yTileMax - worldContinuousY - 1.0) * -1.0;
        double xGraphDiff = xTileOffset * pixelsPerTile + adjustment;
        double yGraphDiff = yTileOffset * pixelsPerTile - adjustment;
        int screenX = (int) Math.round(bounds.getX() + xGraphDiff);
        int screenY = (int) Math.round(bounds.getY() + bounds.getHeight() - yGraphDiff);
        return new Point(screenX, screenY);
    }

    private static double clamp(double value, double min, double max)
    {
        if (value < min)
        {
            return min;
        }
        if (value > max)
        {
            return max;
        }
        return value;
    }

    private static Shape buildX(double left, double top, double right, double bottom)
    {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(left, top);
        path.lineTo(right, bottom);
        path.moveTo(right, top);
        path.lineTo(left, bottom);
        return path;
    }

    private static Shape buildTriangle(double x1, double y1, double x2, double y2, double x3, double y3)
    {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        return path;
    }

    private static Shape buildDiamond(double x1, double y1, double x2, double y2,
                                      double x3, double y3, double x4, double y4)
    {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.closePath();
        return path;
    }

    private static Shape buildStar(double centerX, double centerY, double outerRadius)
    {
        double innerRadius = outerRadius * 0.45;
        Path2D.Double path = new Path2D.Double();

        for (int i = 0; i < 10; i++)
        {
            double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
            double radius = (i % 2 == 0) ? outerRadius : innerRadius;
            double x = centerX + Math.cos(angle) * radius;
            double y = centerY + Math.sin(angle) * radius;
            if (i == 0)
            {
                path.moveTo(x, y);
            }
            else
            {
                path.lineTo(x, y);
            }
        }

        path.closePath();
        return path;
    }

    private static Shape buildPlus(double centerX, double centerY, double half)
    {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(centerX - half, centerY);
        path.lineTo(centerX + half, centerY);
        path.moveTo(centerX, centerY - half);
        path.lineTo(centerX, centerY + half);
        return path;
    }

    private static Shape buildSkull(double centerX, double centerY, double size)
    {
        double half = size / 2.0;
        double left = centerX - half;
        double top = centerY - half;
        double right = centerX + half;
        double bottom = centerY + half;

        Path2D.Double path = new Path2D.Double();
        path.moveTo(centerX, top + size * 0.05);
        path.lineTo(right - size * 0.20, top + size * 0.12);
        path.lineTo(right - size * 0.08, top + size * 0.28);
        path.lineTo(right - size * 0.10, top + size * 0.58);
        path.lineTo(right - size * 0.24, top + size * 0.76);
        path.lineTo(centerX + size * 0.12, bottom - size * 0.08);
        path.lineTo(centerX - size * 0.12, bottom - size * 0.08);
        path.lineTo(left + size * 0.24, top + size * 0.76);
        path.lineTo(left + size * 0.10, top + size * 0.58);
        path.lineTo(left + size * 0.08, top + size * 0.28);
        path.lineTo(left + size * 0.20, top + size * 0.12);
        path.lineTo(centerX, top + size * 0.05);

        path.moveTo(left + size * 0.32, top + size * 0.35);
        path.lineTo(left + size * 0.43, top + size * 0.47);
        path.lineTo(left + size * 0.28, top + size * 0.48);
        path.closePath();

        path.moveTo(right - size * 0.32, top + size * 0.35);
        path.lineTo(right - size * 0.43, top + size * 0.47);
        path.lineTo(right - size * 0.28, top + size * 0.48);
        path.closePath();

        path.moveTo(centerX, top + size * 0.49);
        path.lineTo(centerX - size * 0.07, top + size * 0.61);
        path.lineTo(centerX + size * 0.07, top + size * 0.61);
        path.closePath();

        path.moveTo(centerX - size * 0.18, bottom - size * 0.08);
        path.lineTo(centerX - size * 0.18, bottom - size * 0.22);
        path.moveTo(centerX - size * 0.06, bottom - size * 0.08);
        path.lineTo(centerX - size * 0.06, bottom - size * 0.22);
        path.moveTo(centerX + size * 0.06, bottom - size * 0.08);
        path.lineTo(centerX + size * 0.06, bottom - size * 0.22);
        path.moveTo(centerX + size * 0.18, bottom - size * 0.08);
        path.lineTo(centerX + size * 0.18, bottom - size * 0.22);
        return path;
    }

    private static Shape buildPrayerStar(double centerX, double centerY, double size)
    {
        double outer = size / 2.0;
        double inner = size * 0.18;
        Path2D.Double path = new Path2D.Double();

        path.moveTo(centerX, centerY - outer);
        path.lineTo(centerX + inner, centerY - inner);
        path.lineTo(centerX + outer, centerY);
        path.lineTo(centerX + inner, centerY + inner);
        path.lineTo(centerX, centerY + outer);
        path.lineTo(centerX - inner, centerY + inner);
        path.lineTo(centerX - outer, centerY);
        path.lineTo(centerX - inner, centerY - inner);
        path.closePath();

        path.moveTo(centerX, centerY - size * 0.62);
        path.lineTo(centerX, centerY - outer);
        path.moveTo(centerX + size * 0.62, centerY);
        path.lineTo(centerX + outer, centerY);
        path.moveTo(centerX, centerY + size * 0.62);
        path.lineTo(centerX, centerY + outer);
        path.moveTo(centerX - size * 0.62, centerY);
        path.lineTo(centerX - outer, centerY);
        return path;
    }

    private static Shape buildTreasureChest(double centerX, double centerY, double size)
    {
        double left = centerX - size / 2.0;
        double top = centerY - size / 2.0;
        double right = centerX + size / 2.0;
        double bottom = centerY + size / 2.0;
        double lidY = top + size * 0.38;
        double bodyTop = top + size * 0.42;
        double hingeLeft = left + size * 0.16;
        double hingeRight = right - size * 0.16;
        double lockWidth = size * 0.14;

        Path2D.Double path = new Path2D.Double();

        path.moveTo(left + size * 0.10, lidY);
        path.quadTo(centerX, top + size * 0.04, right - size * 0.10, lidY);
        path.lineTo(right - size * 0.06, bodyTop);
        path.lineTo(left + size * 0.06, bodyTop);
        path.closePath();

        path.moveTo(left + size * 0.06, bodyTop);
        path.lineTo(right - size * 0.06, bodyTop);
        path.lineTo(right - size * 0.10, bottom - size * 0.10);
        path.lineTo(left + size * 0.10, bottom - size * 0.10);
        path.closePath();

        path.moveTo(hingeLeft, bodyTop);
        path.lineTo(hingeLeft, bottom - size * 0.10);
        path.moveTo(centerX, bodyTop);
        path.lineTo(centerX, bottom - size * 0.10);
        path.moveTo(hingeRight, bodyTop);
        path.lineTo(hingeRight, bottom - size * 0.10);

        path.moveTo(centerX - lockWidth / 2.0, bodyTop);
        path.lineTo(centerX + lockWidth / 2.0, bodyTop);
        path.lineTo(centerX + lockWidth / 2.0, bodyTop + size * 0.18);
        path.lineTo(centerX - lockWidth / 2.0, bodyTop + size * 0.18);
        path.closePath();

        path.moveTo(centerX - size * 0.05, top + size * 0.24);
        path.lineTo(centerX + size * 0.05, top + size * 0.24);
        return path;
    }

    private static Shape buildSpiderWeb(double centerX, double centerY, double size)
    {
        Path2D.Double path = new Path2D.Double();
        double outer = size / 2.0;
        double mid = size * 0.34;
        double inner = size * 0.18;

        addPolygon(path, centerX, centerY, outer, 8, -Math.PI / 2.0, true);
        addPolygon(path, centerX, centerY, mid, 8, -Math.PI / 2.0, true);
        addPolygon(path, centerX, centerY, inner, 8, -Math.PI / 2.0, true);

        for (int i = 0; i < 8; i++)
        {
            double angle = -Math.PI / 2.0 + i * (Math.PI / 4.0);
            double xOuter = centerX + Math.cos(angle) * outer;
            double yOuter = centerY + Math.sin(angle) * outer;
            path.moveTo(centerX, centerY);
            path.lineTo(xOuter, yOuter);
        }

        return path;
    }

    private static Shape buildTarget(double centerX, double centerY, double size)
    {
        double outer = size / 2.0;
        double middle = size * 0.32;
        double inner = size * 0.14;
        Path2D.Double path = new Path2D.Double();

        path.append(new Ellipse2D.Double(centerX - outer, centerY - outer, outer * 2.0, outer * 2.0), false);
        path.append(new Ellipse2D.Double(centerX - middle, centerY - middle, middle * 2.0, middle * 2.0), false);
        path.append(new Ellipse2D.Double(centerX - inner, centerY - inner, inner * 2.0, inner * 2.0), false);

        path.moveTo(centerX - outer, centerY);
        path.lineTo(centerX + outer, centerY);
        path.moveTo(centerX, centerY - outer);
        path.lineTo(centerX, centerY + outer);
        return path;
    }

    private static void addPolygon(Path2D.Double path, double centerX, double centerY, double radius,
                                   int sides, double startAngle, boolean closed)
    {
        for (int i = 0; i < sides; i++)
        {
            double angle = startAngle + i * (Math.PI * 2.0 / sides);
            double x = centerX + Math.cos(angle) * radius;
            double y = centerY + Math.sin(angle) * radius;
            if (i == 0)
            {
                path.moveTo(x, y);
            }
            else
            {
                path.lineTo(x, y);
            }
        }
        if (closed)
        {
            path.closePath();
        }
    }
}
