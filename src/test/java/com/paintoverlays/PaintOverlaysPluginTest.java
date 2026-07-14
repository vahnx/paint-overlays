package com.paintoverlays;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PaintOverlaysPluginTest
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("Loading local Paint Overlays from: "
            + PaintOverlaysPlugin.class.getProtectionDomain().getCodeSource().getLocation());
        ExternalPluginManager.loadBuiltin(PaintOverlaysPlugin.class);
        RuneLite.main(args);
    }
}
