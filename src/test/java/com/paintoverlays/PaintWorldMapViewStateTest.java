package com.paintoverlays;

import java.awt.Rectangle;
import net.runelite.api.Point;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PaintWorldMapViewStateTest
{
    @Test
    public void containsCanvasPointRejectsOverviewAndSelectorAreas()
    {
        PaintWorldMapViewState state = PaintWorldMapViewState.of(
            new Rectangle(20, 30, 300, 200),
            new Rectangle(20, 30, 40, 40),
            new Rectangle(260, 30, 60, 30),
            4.0f,
            new Point(3200, 3200));

        assertTrue(state.containsCanvasPoint(120, 120));
        assertFalse(state.containsCanvasPoint(30, 40));
        assertFalse(state.containsCanvasPoint(280, 40));
        assertFalse(state.containsCanvasPoint(5, 5));
    }

    @Test
    public void targetLookupUsesSameClipRules()
    {
        PaintWorldMapViewState state = PaintWorldMapViewState.of(
            new Rectangle(20, 30, 300, 200),
            new Rectangle(20, 30, 40, 40),
            null,
            3.0f,
            new Point(3200, 3200));

        assertNull(state.getTarget(25, 35));
        assertNotNull(state.getTarget(120, 120));
    }

    @Test
    public void eraserSweepCannotCrossExcludedMapControls()
    {
        PaintWorldMapViewState state = PaintWorldMapViewState.of(
            new Rectangle(0, 0, 200, 200),
            new Rectangle(80, 80, 40, 40),
            null,
            3.0f,
            new Point(3200, 3200));

        assertTrue(state.canSweepBetweenCanvasPoints(40, 50, 160, 50));
        assertFalse(state.canSweepBetweenCanvasPoints(40, 100, 160, 100));
        assertFalse(state.canSweepBetweenCanvasPoints(40, 100, 100, 100));
    }

    @Test
    public void eraserRadiusCannotReachBehindExcludedMapControls()
    {
        PaintWorldMapViewState state = PaintWorldMapViewState.of(
            new Rectangle(0, 0, 200, 200),
            new Rectangle(80, 80, 40, 40),
            null,
            3.0f,
            new Point(3200, 3200));

        assertTrue(state.canEraseBetweenCanvasPoints(30, 70, 170, 70, 5));
        assertFalse(state.canEraseBetweenCanvasPoints(30, 70, 170, 70, 15));
        assertFalse(state.canEraseBetweenCanvasPoints(70, 100, 70, 100, 15));
    }

    @Test
    public void visibleRegionIdsAreComputedForAvailableState()
    {
        PaintWorldMapViewState state = PaintWorldMapViewState.of(
            new Rectangle(20, 30, 300, 200),
            null,
            null,
            4.0f,
            new Point(3200, 3200));

        assertFalse(state.getVisibleRegionIds().isEmpty());
    }

    @Test
    public void matchesOnlyEquivalentViewInputs()
    {
        Rectangle bounds = new Rectangle(20, 30, 300, 200);
        Rectangle overview = new Rectangle(20, 30, 40, 40);
        Point center = new Point(3200, 3200);
        PaintWorldMapViewState state = PaintWorldMapViewState.of(bounds, overview, null, 4.0f, center);

        assertTrue(state.matches(new Rectangle(bounds), new Rectangle(overview), null, 4.0f, new Point(3200, 3200)));
        assertFalse(state.matches(bounds, overview, null, 4.0f, new Point(3201, 3200)));
        assertFalse(state.matches(bounds, overview, null, 3.0f, center));
    }
}
