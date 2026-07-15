package com.paintoverlays;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import net.runelite.api.Point;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PaintMathTest
{
    @Test
    public void sanitizePendingTextRemovesControlCharsAndCapsLength()
    {
        StringBuilder input = new StringBuilder("Line one\nLine\t");
        for (int i = 0; i < 100; i++)
        {
            input.append('x');
        }
        input.append('\u0007');

        String sanitized = PaintMath.sanitizePendingText(input.toString());

        assertEquals(PaintMath.MAX_TEXT_LENGTH, sanitized.length());
        assertTrue(sanitized.startsWith("Line one Line "));
        assertTrue(!sanitized.contains("\n"));
        assertTrue(!sanitized.contains("\t"));
    }

    @Test
    public void sanitizePendingTextDoesNotSplitSurrogatePairs()
    {
        String input = String.join("", java.util.Collections.nCopies(PaintMath.MAX_TEXT_LENGTH - 1, "x")) + "\uD83D\uDE00";

        String sanitized = PaintMath.sanitizePendingText(input);

        assertEquals(PaintMath.MAX_TEXT_LENGTH - 1, sanitized.length());
        assertTrue(!Character.isSurrogate(sanitized.charAt(sanitized.length() - 1)));
    }

    @Test
    public void worldMapRoundTripStaysWithinOnePixel()
    {
        Rectangle bounds = new Rectangle(37, 52, 312, 244);
        Point center = new Point(3200, 3200);
        float[] zoomLevels = new float[] {0.65f, 1.0f, 1.5f, 3.25f, 6.0f};
        int[][] mousePoints = new int[][]
        {
            {40, 55},
            {120, 90},
            {220, 170},
            {345, 292}
        };

        for (float zoom : zoomLevels)
        {
            for (int[] mouse : mousePoints)
            {
                PaintTarget target = PaintMath.worldMapTarget(bounds, zoom, center, mouse[0], mouse[1]);
                assertNotNull(target);

                Point canvasPoint = PaintMath.worldMapCanvasPoint(
                    bounds,
                    zoom,
                    center,
                    target.worldX,
                    target.worldY,
                    target.offsetX,
                    target.offsetY);

                assertNotNull(canvasPoint);
                assertTrue(Math.abs(canvasPoint.getX() - mouse[0]) <= 1);
                assertTrue(Math.abs(canvasPoint.getY() - mouse[1]) <= 1);
            }
        }
    }

    @Test
    public void worldMapTargetRejectsPointsOutsideBounds()
    {
        Rectangle bounds = new Rectangle(10, 10, 100, 80);
        Point center = new Point(3200, 3200);

        assertNull(PaintMath.worldMapTarget(bounds, 2.0f, center, 5, 5));
    }

    @Test
    public void cursorRadiusTracksBrushSize()
    {
        assertEquals(2, PaintMath.cursorRadius(1));
        assertEquals(4, PaintMath.cursorRadius(4));
        assertEquals(12, PaintMath.cursorRadius(12));
    }

    @Test
    public void roundStrokeReusesImmutableStrokeByWidth()
    {
        assertSame(PaintMath.roundStroke(4), PaintMath.roundStroke(4));
        assertEquals(1.0f, PaintMath.roundStroke(0).getLineWidth(), 0.0f);
    }

    @Test
    public void segmentWithinCanvasRadiusHitsMidpoint()
    {
        assertTrue(PaintMath.segmentWithinCanvasRadius(new Point(10, 10), new Point(30, 10), 20, 14, 4));
    }

    @Test
    public void segmentWithinCanvasRadiusRejectsDistantPoint()
    {
        assertTrue(!PaintMath.segmentWithinCanvasRadius(new Point(10, 10), new Point(30, 10), 20, 20, 4));
    }

    @Test
    public void cursorSweepIntersectsCrossingStrokeSegment()
    {
        assertTrue(PaintMath.segmentsWithinCanvasRadius(
            new Point(10, 20),
            new Point(30, 20),
            20,
            0,
            20,
            40,
            1));
    }

    @Test
    public void cursorSweepRejectsDistantStrokeSegment()
    {
        assertTrue(!PaintMath.segmentsWithinCanvasRadius(
            new Point(10, 20),
            new Point(30, 20),
            20,
            40,
            20,
            60,
            3));
    }

    @Test
    public void cursorSweepHitsExpandedRectangle()
    {
        Rectangle2D bounds = new Rectangle2D.Double(20, 20, 20, 20);

        assertTrue(PaintMath.rectangleWithinCanvasSweep(bounds, 0, 15, 60, 15, 5));
        assertTrue(!PaintMath.rectangleWithinCanvasSweep(bounds, 0, 10, 60, 10, 5));
    }

    @Test
    public void textWithinCanvasRadiusHitsAcrossRenderedBounds()
    {
        Font font = new Font("Dialog", Font.PLAIN, 16);
        Point baseline = new Point(100, 100);

        assertTrue(PaintMath.textWithinCanvasRadius(baseline, font, "Hello", 110, 96, 3));
        assertTrue(PaintMath.textWithinCanvasRadius(baseline, font, "Hello", 132, 100, 3));
    }

    @Test
    public void textWithinCanvasRadiusRejectsDistantPoint()
    {
        Font font = new Font("Dialog", Font.PLAIN, 16);
        Point baseline = new Point(100, 100);

        assertTrue(!PaintMath.textWithinCanvasRadius(baseline, font, "Hello", 40, 40, 3));
    }

    @Test
    public void textBoundsIncludeOnePixelShadowOffset()
    {
        Font font = new Font("Dialog", Font.BOLD, 16);
        Point baseline = new Point(100, 100);
        String text = "!! Warning Bring Antifire !!";
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector glyphVector = font.createGlyphVector(frc, text);
        Rectangle expected = glyphVector.getPixelBounds(frc, baseline.getX(), baseline.getY())
            .union(glyphVector.getPixelBounds(frc, baseline.getX() + 1, baseline.getY() + 1));

        Rectangle2D bounds = PaintMath.textBounds(baseline, font, text);

        assertNotNull(bounds);
        assertEquals(expected.getX(), bounds.getX(), 0.0);
        assertEquals(expected.getY(), bounds.getY(), 0.0);
        assertEquals(expected.getWidth(), bounds.getWidth(), 0.0);
        assertEquals(expected.getHeight(), bounds.getHeight(), 0.0);
    }

    @Test
    public void shapeOutlineSupportsAllShapeTypes()
    {
        Point center = new Point(100, 100);

        for (PaintShapeType shapeType : PaintShapeType.values())
        {
            Shape outline = PaintMath.shapeOutline(center, 40, shapeType);
            assertNotNull(outline);
            assertTrue(outline.getBounds2D().getWidth() > 0.0);
            assertTrue(outline.getBounds2D().getHeight() > 0.0);
        }
    }
}
