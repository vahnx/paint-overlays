package com.paintoverlays;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(PaintOverlaysConfig.GROUP)
public interface PaintOverlaysConfig extends Config
{
    String GROUP = "paint-overlays";
    @ConfigSection(
        name = "Paint Options",
        description = "Use the Paint Overlays side panel button to see all your paint options.",
        position = 0
    )
    String paintOptionsSection = "paintOptionsSection";

    @ConfigItem(
        keyName = "showCursorPreview",
        name = "Show cursor preview",
        description = "Show a live preview for the active tool while paint input is enabled",
        position = 1,
        section = paintOptionsSection
    )
    default boolean showCursorPreview()
    {
        return true;
    }
}
