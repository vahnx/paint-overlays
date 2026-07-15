package com.paintoverlays;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    private PaintWorldMapViewState(Rectangle bounds, Rectangle overviewBounds, Rectangle surfaceSelectorBounds, float pixelsPerTile, Point center, Set<Integer> visibleRegionIds)
    {
        this.bounds = bounds;
        this.overviewBounds = overviewBounds;
        this.surfaceSelectorBounds = surfaceSelectorBounds;
        this.pixelsPerTile = pixelsPerTile;
        this.center = center;
        this.visibleRegionIds = visibleRegionIds;
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
        if (!isAvailable())
        {
            return null;
        }

        Area clipArea = new Area(bounds);
        boolean subtracted = false;
        if (overviewBounds != null)
        {
            clipArea.subtract(new Area(overviewBounds));
            subtracted = true;
        }

        if (surfaceSelectorBounds != null)
        {
            clipArea.subtract(new Area(surfaceSelectorBounds));
            subtracted = true;
        }

        return subtracted ? clipArea : new Rectangle(bounds);
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
