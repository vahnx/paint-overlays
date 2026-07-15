package com.paintoverlays;

import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PaintInputQueueTest
{
    private static final Class<?> PENDING_DRAG_CLASS;
    private static final Constructor<?> PENDING_DRAG_CONSTRUCTOR;

    static
    {
        try
        {
            PENDING_DRAG_CLASS = Class.forName("com.paintoverlays.PaintOverlaysPlugin$PendingMouseDrag");
            PENDING_DRAG_CONSTRUCTOR = PENDING_DRAG_CLASS.getDeclaredConstructor(long.class, Point.class, boolean.class);
            PENDING_DRAG_CONSTRUCTOR.setAccessible(true);
        }
        catch (ReflectiveOperationException ex)
        {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Test
    public void samePixelWithChangedMapUsabilityIsNotDeduplicated() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        enqueue(plugin, pending(1L, 10, 10, true));
        enqueue(plugin, pending(1L, 10, 10, false));

        assertEquals(2, pendingDrags(plugin).size());
    }

    @Test
    public void simplifiedAlternatingMapPointsRetainBreakBarriers() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        for (int i = 0; i < 16; i++)
        {
            enqueue(plugin, pending(1L, i * 10, i % 3, i % 2 == 0));
        }

        List<?> retained = pendingDrags(plugin);
        assertEquals(8, retained.size());
        for (int i = 1; i < retained.size(); i++)
        {
            assertTrue((boolean) field(retained.get(i), "breakBefore"));
        }
    }

    @Test
    public void processorRetainsPointsForFutureGesture() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        enqueue(plugin, pending(1L, 10, 10, true));
        enqueue(plugin, pending(2L, 20, 20, true));
        setField(plugin, "clientInputGestureId", 1L);
        setField(plugin, "clientInputCaptureActive", false);

        Method process = PaintOverlaysPlugin.class.getDeclaredMethod("processPendingMouseDrag");
        process.setAccessible(true);
        process.invoke(plugin);

        List<?> retained = pendingDrags(plugin);
        assertEquals(1, retained.size());
        assertEquals(2L, field(retained.get(0), "gestureId"));
    }

    @Test
    public void perGestureLimitDoesNotDiscardRapidGestures() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        for (long gestureId = 1; gestureId <= 12; gestureId++)
        {
            enqueue(plugin, pending(gestureId, (int) gestureId, 0, true));
        }

        assertEquals(12, pendingDrags(plugin).size());
    }

    @Test
    public void rejectedGestureDropsItsQueuedPoints() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        enqueue(plugin, pending(7L, 10, 10, true));
        enqueue(plugin, pending(7L, 20, 20, true));

        Method reject = PaintOverlaysPlugin.class.getDeclaredMethod("rejectInputGesture", long.class);
        reject.setAccessible(true);
        reject.invoke(plugin, 7L);

        assertTrue(pendingDrags(plugin).isEmpty());
        assertEquals(7L, field(plugin, "cancelledInputGestureId"));
    }

    private static Object pending(long gestureId, int x, int y, boolean mapPointUsable) throws Exception
    {
        return PENDING_DRAG_CONSTRUCTOR.newInstance(gestureId, new Point(x, y), mapPointUsable);
    }

    private static void enqueue(PaintOverlaysPlugin plugin, Object pending) throws Exception
    {
        Method enqueue = PaintOverlaysPlugin.class.getDeclaredMethod("enqueuePendingMouseDrag", PENDING_DRAG_CLASS);
        enqueue.setAccessible(true);
        enqueue.invoke(plugin, pending);
    }

    @SuppressWarnings("unchecked")
    private static List<?> pendingDrags(PaintOverlaysPlugin plugin) throws Exception
    {
        return (List<?>) field(plugin, "pendingMouseDrags");
    }

    private static Object field(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
