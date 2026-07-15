package com.paintoverlays;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Point;

final class PaintWorldMapViewState
{
    private static final PaintWorldMapViewState UNAVAILABLE = new PaintWorldMapViewState(null, null, null, 0, null, Collections.emptySet());

    private final Rectangle bounds;
    private final Rectangle overviewBounds;
    private final Rectangle surfaceSelectorBounds;
    private final float pixelsPerTile;
    private final Point center;
    private final Set<Integer> visibleRegionIds;
    private final Shape clipArea;

    private PaintWorldMapViewState(Rectangle bounds, Rectangle overviewBounds, Rectangle surfaceSelectorBounds, float pixelsPerTile, Point center, Set<Integer> visibleRegionIds)
    {
        this.bounds = bounds;
        this.overviewBounds = overviewBounds;
        this.surfaceSelectorBounds = surfaceSelectorBounds;
        this.pixelsPerTile = pixelsPerTile;
        this.center = center;
        this.visibleRegionIds = visibleRegionIds;
        this.clipArea = buildClipArea(bounds, overviewBounds, surfaceSelectorBounds);
    }

    static PaintWorldMapViewState unavailable()
    {
        return UNAVAILABLE;
    }

    static PaintWorldMapViewState of(Rectangle bounds, Rectangle overviewBounds, Rectangle surfaceSelectorBounds, float pixelsPerTile, Point center)
    {
        if (bounds == null || center == null || pixelsPerTile <= 0)
        {
            return unavailable();
        }

        Rectangle mapBoundsCopy = new Rectangle(bounds);
        Rectangle overviewCopy = overviewBounds == null ? null : new Rectangle(overviewBounds);
        Rectangle selectorCopy = surfaceSelectorBounds == null ? null : new Rectangle(surfaceSelectorBounds);
        Point centerCopy = new Point(center.getX(), center.getY());
        return new PaintWorldMapViewState(
            mapBoundsCopy,
            overviewCopy,
            selectorCopy,
            pixelsPerTile,
            centerCopy,
            Collections.unmodifiableSet(computeVisibleRegionIds(mapBoundsCopy, pixelsPerTile, centerCopy)));
    }

    boolean isAvailable()
    {
        return bounds != null && center != null && pixelsPerTile > 0;
    }

    boolean matches(Rectangle bounds, Rectangle overviewBounds, Rectangle surfaceSelectorBounds, float pixelsPerTile, Point center)
    {
        if (!isAvailable() || bounds == null || center == null)
        {
            return false;
        }

        return this.bounds.equals(bounds)
            && Objects.equals(this.overviewBounds, overviewBounds)
            && Objects.equals(this.surfaceSelectorBounds, surfaceSelectorBounds)
            && Float.compare(this.pixelsPerTile, pixelsPerTile) == 0
            && this.center.getX() == center.getX()
            && this.center.getY() == center.getY();
    }

    Collection<Integer> getVisibleRegionIds()
    {
        return visibleRegionIds;
    }

    int getCenterRegionId()
    {
        if (!isAvailable())
        {
            return -1;
        }

        int regionX = center.getX() >> 6;
        int regionY = center.getY() >> 6;
        return regionX < 0 || regionY < 0 ? -1 : (regionX << 8) | regionY;
    }

    float getPixelsPerTile()
    {
        return pixelsPerTile;
    }

    boolean containsCanvasPoint(int mouseX, int mouseY)
    {
        if (!isAvailable() || !bounds.contains(mouseX, mouseY))
        {
            return false;
        }

        if (overviewBounds != null && overviewBounds.contains(mouseX, mouseY))
        {
            return false;
        }

        return surfaceSelectorBounds == null || !surfaceSelectorBounds.contains(mouseX, mouseY);
    }

    boolean canSweepBetweenCanvasPoints(int startX, int startY, int endX, int endY)
    {
        return containsCanvasPoint(startX, startY)
            && containsCanvasPoint(endX, endY)
            && (overviewBounds == null || !overviewBounds.intersectsLine(startX, startY, endX, endY))
            && (surfaceSelectorBounds == null || !surfaceSelectorBounds.intersectsLine(startX, startY, endX, endY));
    }

    boolean canEraseBetweenCanvasPoints(int startX, int startY, int endX, int endY, int radius)
    {
        if (!containsCanvasPoint(startX, startY) || !containsCanvasPoint(endX, endY))
        {
            return false;
        }

        int safeRadius = Math.max(0, radius);
        return !expandedRectangleIntersectsLine(overviewBounds, safeRadius, startX, startY, endX, endY)
            && !expandedRectangleIntersectsLine(surfaceSelectorBounds, safeRadius, startX, startY, endX, endY);
    }

    private static boolean expandedRectangleIntersectsLine(
        Rectangle rectangle,
        int radius,
        int startX,
        int startY,
        int endX,
        int endY)
    {
        if (rectangle == null)
        {
            return false;
        }

        Rectangle expanded = new Rectangle(rectangle);
        expanded.grow(radius, radius);
        return expanded.contains(startX, startY)
            || expanded.contains(endX, endY)
            || expanded.intersectsLine(startX, startY, endX, endY);
    }

    PaintTarget getTarget(int mouseX, int mouseY)
    {
        if (!containsCanvasPoint(mouseX, mouseY))
        {
            return null;
        }

        return PaintMath.worldMapTarget(bounds, pixelsPerTile, center, mouseX, mouseY);
    }

    Point toCanvasPoint(int worldX, int worldY, int offsetX, int offsetY)
    {
        if (!isAvailable())
        {
            return null;
        }

        return PaintMath.worldMapCanvasPoint(bounds, pixelsPerTile, center, worldX, worldY, offsetX, offsetY);
    }

    Shape getClipArea()
    {
        return clipArea;
    }

    private static Shape buildClipArea(Rectangle bounds, Rectangle overviewBounds, Rectangle surfaceSelectorBounds)
    {
        if (bounds == null)
        {
            return null;
        }

        Area result = new Area(bounds);
        boolean subtracted = false;
        if (overviewBounds != null)
        {
            result.subtract(new Area(overviewBounds));
            subtracted = true;
        }

        if (surfaceSelectorBounds != null)
        {
            result.subtract(new Area(surfaceSelectorBounds));
            subtracted = true;
        }

        return subtracted ? result : new Rectangle(bounds);
    }

    private static Set<Integer> computeVisibleRegionIds(Rectangle bounds, float pixelsPerTile, Point center)
    {
        Set<Integer> regionIds = new LinkedHashSet<>();
        int widthInTiles = (int) Math.ceil(bounds.getWidth() / pixelsPerTile);
        int heightInTiles = (int) Math.ceil(bounds.getHeight() / pixelsPerTile);

        int minRegionX = (int) Math.floor((center.getX() - widthInTiles / 2.0) / 64.0);
        int maxRegionX = (int) Math.floor((center.getX() + widthInTiles / 2.0) / 64.0);
        int minRegionY = (int) Math.floor((center.getY() - heightInTiles / 2.0) / 64.0);
        int maxRegionY = (int) Math.floor((center.getY() + heightInTiles / 2.0) / 64.0);

        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++)
        {
            for (int regionY = minRegionY; regionY <= maxRegionY; regionY++)
            {
                if (regionX >= 0 && regionY >= 0)
                {
                    regionIds.add((regionX << 8) | regionY);
                }
            }
        }

        return regionIds;
    }
}
