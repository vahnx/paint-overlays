package com.paintoverlays;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PaintHotkeyTest
{
    @Test
    public void escapeExitsEditingEvenWhenFocusIsNotOnCanvas() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "client", clientWithCanvas(null, GameState.LOGGED_IN, proxy(Player.class), true));
        setField(plugin, "loadedRsProfileKey", "profile");
        setField(plugin, "tool", PaintTool.BRUSH);

        KeyEvent event = new KeyEvent(
            new JButton("Brush"),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED);

        assertTrue(plugin.handleKeyPressed(event));
        assertNull(plugin.getTool());
    }

    @Test
    public void enteringToolRequestsCanvasFocus() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        FocusTrackingCanvas canvas = new FocusTrackingCanvas();
        setField(plugin, "client", clientWithCanvas(canvas, GameState.LOGGED_IN, proxy(Player.class), true));
        setField(plugin, "loadedRsProfileKey", "profile");

        plugin.setTool(PaintTool.BRUSH);
        SwingUtilities.invokeAndWait(() ->
        {
        });

        assertTrue(canvas.focusRequestedInWindow);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception
    {
        Field field = PaintOverlaysPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Client clientWithCanvas(Canvas canvas, GameState gameState, Player localPlayer, boolean clientThread)
    {
        return (Client) Proxy.newProxyInstance(
            Client.class.getClassLoader(),
            new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getCanvas":
                        return canvas;
                    case "getGameState":
                        return gameState;
                    case "getLocalPlayer":
                        return localPlayer;
                    case "isClientThread":
                        return clientThread;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type)
    {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> returnType)
    {
        if (!returnType.isPrimitive())
        {
            return null;
        }

        if (returnType == boolean.class)
        {
            return false;
        }

        if (returnType == byte.class)
        {
            return (byte) 0;
        }

        if (returnType == short.class)
        {
            return (short) 0;
        }

        if (returnType == int.class)
        {
            return 0;
        }

        if (returnType == long.class)
        {
            return 0L;
        }

        if (returnType == float.class)
        {
            return 0f;
        }

        if (returnType == double.class)
        {
            return 0d;
        }

        if (returnType == char.class)
        {
            return '\0';
        }

        return null;
    }

    private static final class FocusTrackingCanvas extends Canvas
    {
        private boolean focusRequestedInWindow;

        @Override
        public boolean isDisplayable()
        {
            return true;
        }

        @Override
        public boolean requestFocusInWindow()
        {
            focusRequestedInWindow = true;
            return true;
        }
    }
}
