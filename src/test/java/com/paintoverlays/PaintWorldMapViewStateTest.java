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
}
