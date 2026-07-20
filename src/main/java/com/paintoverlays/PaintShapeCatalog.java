package com.paintoverlays;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PaintShapeCatalog
{
    private static final String SHAPE_CATALOG_RESOURCE = "/com/paintoverlays/shapes/catalog.json";
    private static volatile Map<PaintShapeType, ShapeDefinition> definitions;

    private PaintShapeCatalog()
    {
    }

    static Shape shapeOutline(net.runelite.api.Point center, int size, PaintShapeType shapeType)
    {
        ShapeDefinition definition = getDefinition(shapeType);
        if (definition == null || center == null)
        {
            return null;
        }

        double safeSize = Math.max(1, size);
        double left = center.getX() - safeSize / 2.0;
        double top = center.getY() - safeSize / 2.0;
        Path2D.Double path = new Path2D.Double();
        for (ShapeCommand command : definition.commands)
        {
            if (command == null || command.op == null)
            {
                return null;
            }

            switch (command.op)
            {
                case "move":
                    path.moveTo(scaleX(left, safeSize, command.x), scaleY(top, safeSize, command.y));
                    break;
                case "line":
                    path.lineTo(scaleX(left, safeSize, command.x), scaleY(top, safeSize, command.y));
                    break;
                case "quad":
                    path.quadTo(
                        scaleX(left, safeSize, command.cx),
                        scaleY(top, safeSize, command.cy),
                        scaleX(left, safeSize, command.x),
                        scaleY(top, safeSize, command.y));
                    break;
                case "cubic":
                    path.curveTo(
                        scaleX(left, safeSize, command.cx1),
                        scaleY(top, safeSize, command.cy1),
                        scaleX(left, safeSize, command.cx2),
                        scaleY(top, safeSize, command.cy2),
                        scaleX(left, safeSize, command.x),
                        scaleY(top, safeSize, command.y));
                    break;
                case "close":
                    path.closePath();
                    break;
                case "rect":
                    path.append(new Rectangle2D.Double(
                        scaleX(left, safeSize, command.x),
                        scaleY(top, safeSize, command.y),
                        safeSize * command.w,
                        safeSize * command.h), false);
                    break;
                case "ellipse":
                    path.append(new Ellipse2D.Double(
                        scaleX(left, safeSize, command.x),
                        scaleY(top, safeSize, command.y),
                        safeSize * command.w,
                        safeSize * command.h), false);
                    break;
                case "polygon":
                    appendPolygon(path, left, top, safeSize, command);
                    break;
                default:
                    return null;
            }
        }
        return path;
    }

    static Boolean canFillShape(PaintShapeType shapeType)
    {
        ShapeDefinition definition = getDefinition(shapeType);
        return definition == null ? null : Boolean.TRUE.equals(definition.fillable);
    }

    private static ShapeDefinition getDefinition(PaintShapeType shapeType)
    {
        if (shapeType == null)
        {
            return null;
        }

        Map<PaintShapeType, ShapeDefinition> current = definitions;
        if (current == null)
        {
            synchronized (PaintShapeCatalog.class)
            {
                current = definitions;
                if (current == null)
                {
                    current = loadDefinitions();
                    definitions = current;
                }
            }
        }
        return current.get(shapeType);
    }

    private static Map<PaintShapeType, ShapeDefinition> loadDefinitions()
    {
        try (InputStream inputStream = PaintShapeCatalog.class.getResourceAsStream(SHAPE_CATALOG_RESOURCE))
        {
            if (inputStream == null)
            {
                return Collections.emptyMap();
            }

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject())
            {
                return Collections.emptyMap();
            }

            Map<PaintShapeType, ShapeDefinition> loaded = new EnumMap<>(PaintShapeType.class);
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet())
            {
                PaintShapeType type = PaintShapeType.valueOf(entry.getKey());
                ShapeDefinition definition = parseShapeDefinition(entry.getValue());
                if (definition != null && definition.commands != null && !definition.commands.isEmpty())
                {
                    loaded.put(type, definition);
                }
            }
            return loaded;
        }
        catch (IOException | RuntimeException ex)
        {
            return Collections.emptyMap();
        }
    }

    private static ShapeDefinition parseShapeDefinition(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        JsonArray commands = getArray(object, "commands");
        if (commands == null)
        {
            return null;
        }

        ShapeDefinition definition = new ShapeDefinition();
        definition.fillable = getBoolean(object, "fillable");
        definition.commands = new ArrayList<>();
        for (JsonElement commandElement : commands)
        {
            ShapeCommand command = parseShapeCommand(commandElement);
            if (command != null)
            {
                definition.commands.add(command);
            }
        }
        return definition;
    }

    private static ShapeCommand parseShapeCommand(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        ShapeCommand command = new ShapeCommand();
        command.op = getString(object, "op");
        command.x = getDouble(object, "x");
        command.y = getDouble(object, "y");
        command.w = getDouble(object, "w");
        command.h = getDouble(object, "h");
        command.cx = getDouble(object, "cx");
        command.cy = getDouble(object, "cy");
        command.cx1 = getDouble(object, "cx1");
        command.cy1 = getDouble(object, "cy1");
        command.cx2 = getDouble(object, "cx2");
        command.cy2 = getDouble(object, "cy2");
        command.r = getDouble(object, "r");
        command.sides = getInt(object, "sides");
        command.start = getDouble(object, "start");
        command.closed = getBoolean(object, "closed");
        return command;
    }

    private static JsonArray getArray(JsonObject object, String name)
    {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonArray() ? null : element.getAsJsonArray();
    }

    private static String getString(JsonObject object, String name)
    {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static Boolean getBoolean(JsonObject object, String name)
    {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsBoolean();
    }

    private static double getDouble(JsonObject object, String name)
    {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? 0.0 : element.getAsDouble();
    }

    private static int getInt(JsonObject object, String name)
    {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }

    private static void appendPolygon(Path2D.Double path, double left, double top, double safeSize, ShapeCommand command)
    {
        int sides = Math.max(3, command.sides);
        double centerX = scaleX(left, safeSize, command.cx);
        double centerY = scaleY(top, safeSize, command.cy);
        double radius = safeSize * command.r;
        double start = command.start;
        for (int i = 0; i < sides; i++)
        {
            double angle = start + i * (Math.PI * 2.0 / sides);
            double x = centerX + Math.cos(angle) * radius;
            double y = centerY + Math.sin(angle) * radius;
            if (i == 0)
            {
                path.moveTo(x, y);
            }
            else
            {
                path.lineTo(x, y);
            }
        }
        if (Boolean.TRUE.equals(command.closed))
        {
            path.closePath();
        }
    }

    private static double scaleX(double left, double size, double value)
    {
        return left + value * size;
    }

    private static double scaleY(double top, double size, double value)
    {
        return top + value * size;
    }

    private static final class ShapeDefinition
    {
        Boolean fillable;
        List<ShapeCommand> commands;
    }

    private static final class ShapeCommand
    {
        String op;
        double x;
        double y;
        double w;
        double h;
        double cx;
        double cy;
        double cx1;
        double cy1;
        double cx2;
        double cy2;
        double r;
        int sides;
        double start;
        Boolean closed;
    }
}
