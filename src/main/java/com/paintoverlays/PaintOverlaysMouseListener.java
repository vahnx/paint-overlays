package com.paintoverlays;

import java.awt.event.MouseEvent;
import net.runelite.client.input.MouseAdapter;

class PaintOverlaysMouseListener extends MouseAdapter
{
    private final PaintOverlaysPlugin plugin;

    PaintOverlaysMouseListener(PaintOverlaysPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent mouseEvent)
    {
        plugin.updatePreviewMouseCanvasPosition(mouseEvent.getPoint());
        if (plugin.handleMousePressed(mouseEvent))
        {
            mouseEvent.consume();
        }
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent mouseEvent)
    {
        plugin.updatePreviewMouseCanvasPosition(mouseEvent.getPoint());
        if (plugin.handleMouseDragged(mouseEvent))
        {
            mouseEvent.consume();
        }
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent mouseEvent)
    {
        plugin.updatePreviewMouseCanvasPosition(mouseEvent.getPoint());
        if (plugin.handleMouseReleased(mouseEvent))
        {
            mouseEvent.consume();
        }
        return mouseEvent;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent mouseEvent)
    {
        plugin.updatePreviewMouseCanvasPosition(mouseEvent.getPoint());
        return mouseEvent;
    }
}
