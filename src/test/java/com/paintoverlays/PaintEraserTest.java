package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import net.runelite.api.Point;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PaintEraserTest
{
    @Test
    public void missReusesOriginalStrokeWithoutCopying()
    {
        PaintStroke stroke = horizontalStroke();

        PaintOverlaysPlugin.EraseStrokeResult result = PaintOverlaysPlugin.eraseStroke(
            stroke,
            200,
            200,
            2,
            point -> new Point(point.worldX * 10, 0));

        assertFalse(result.changed);
        assertEquals(1, result.strokes.size());
        assertSame(stroke, result.strokes.get(0));
    }

    @Test
    public void hitKeepsOneStrokeAndMarksAPathBreak()
    {
        PaintStroke stroke = horizontalStroke();

        PaintOverlaysPlugin.EraseStrokeResult result = PaintOverlaysPlugin.eraseStroke(
            stroke,
            20,
            0,
            1,
            point -> new Point(point.worldX * 10, 0));

        assertTrue(result.changed);
        assertEquals(1, result.strokes.size());
        PaintStroke remaining = result.strokes.get(0);
        assertEquals(4, remaining.points.size());
        assertEquals(3, remaining.points.get(2).worldX);
        assertTrue(remaining.points.get(2).startsNewSegment());
    }

    @Test
    public void sweptCursorErasesAcrossFastMouseMovement()
    {
        PaintStroke stroke = horizontalStroke();

        PaintOverlaysPlugin.EraseStrokeResult result = PaintOverlaysPlugin.eraseStroke(
            stroke,
            new java.awt.Point(15, -20),
            new java.awt.Point(15, 20),
            1,
            point -> new Point(point.worldX * 10, 0));

        assertTrue(result.changed);
        assertEquals(1, result.strokes.size());
        assertEquals(5, result.strokes.get(0).points.size());
        assertTrue(result.strokes.get(0).points.get(2).startsNewSegment());
    }

    @Test
    public void existingPathBreakPreventsHitAcrossItsGap()
    {
        PaintStroke stroke = new PaintStroke(0, Color.WHITE, 4);
        stroke.points.add(new PaintPoint(new PaintTarget(0, 0, 0, 64, 64)));
        PaintPoint disconnected = new PaintPoint(new PaintTarget(2, 0, 0, 64, 64));
        disconnected.startsNewSegment = Boolean.TRUE;
        stroke.points.add(disconnected);

        PaintOverlaysPlugin.EraseStrokeResult result = PaintOverlaysPlugin.eraseStroke(
            stroke,
            10,
            0,
            1,
            point -> new Point(point.worldX * 10, 0));

        assertFalse(result.changed);
        assertSame(stroke, result.strokes.get(0));
    }

    @Test
    public void offCanvasPointIsPreservedWhenAnotherPointIsErased()
    {
        PaintStroke stroke = new PaintStroke(0, Color.WHITE, 4);
        stroke.points.add(new PaintPoint(new PaintTarget(0, 0, 0, 64, 64)));
        stroke.points.add(new PaintPoint(new PaintTarget(1, 0, 0, 64, 64)));
        stroke.points.add(new PaintPoint(new PaintTarget(2, 0, 0, 64, 64)));

        PaintOverlaysPlugin.EraseStrokeResult result = PaintOverlaysPlugin.eraseStroke(
            stroke,
            0,
            0,
            1,
            point -> point.worldX == 1 ? null : new Point(point.worldX * 10, 0));

        assertTrue(result.changed);
        assertEquals(2, result.strokes.get(0).points.size());
        assertEquals(1, result.strokes.get(0).points.get(0).worldX);
    }

    @Test
    public void pathBreakMarkerRoundTripsAndLegacyPointsStayConnected()
    {
        Gson gson = new Gson();
        PaintPoint marked = new PaintPoint(new PaintTarget(1, 2, 0, 64, 64));
        marked.startsNewSegment = Boolean.TRUE;

        PaintPoint decodedMarked = gson.fromJson(gson.toJson(marked), PaintPoint.class);
        PaintPoint decodedLegacy = gson.fromJson("{\"worldX\":1,\"worldY\":2,\"offsetX\":64,\"offsetY\":64}", PaintPoint.class);

        assertTrue(decodedMarked.startsNewSegment());
        assertFalse(decodedLegacy.startsNewSegment());
    }

    private static PaintStroke horizontalStroke()
    {
        PaintStroke stroke = new PaintStroke(0, Color.WHITE, 4);
        for (int x = 0; x < 5; x++)
        {
            stroke.points.add(new PaintPoint(new PaintTarget(x, 0, 0, 64, 64)));
        }
        return stroke;
    }
}
