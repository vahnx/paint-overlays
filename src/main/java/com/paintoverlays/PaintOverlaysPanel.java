package com.paintoverlays;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;

class PaintOverlaysPanel extends PluginPanel
{
    private static final int DEFAULT_BRUSH_SLIDER_MAX = 12;
    private static final int DEFAULT_TEXT_SLIDER_MAX = 200;
    private static final int DEFAULT_SHAPE_SLIDER_MAX = 200;

    private final PaintOverlaysPlugin plugin;
    private final ColorPickerManager colorPickerManager;

    private final JComboBox<PaintShapeType> shapeTypeBox = new JComboBox<>(PaintShapeType.values());
    private final JComboBox<PaintFontStyle> fontBox = new JComboBox<>(PaintFontStyle.values());
    private final JComboBox<PaintFrameStyle> frameStyleBox = new JComboBox<>(PaintFrameStyle.values());
    private final JToggleButton brushButton = new JToggleButton("Brush");
    private final JToggleButton shapeButton = new JToggleButton("Shape");
    private final JToggleButton textButton = new JToggleButton("Text");
    private final JToggleButton eraserButton = new JToggleButton("Eraser");
    private final JButton colorButton = new JButton("Pick Color");
    private final JButton textBackgroundColorButton = new JButton("Pick Background");
    private final JButton textBorderColorButton = new JButton("Pick Border");
    private final JButton frameColorButton = new JButton("Pick Frame");
    private final JCheckBox frameRainbowCheck = new JCheckBox("Rainbow");
    private final JSlider sizeSlider = new JSlider(1, DEFAULT_TEXT_SLIDER_MAX, 16);
    private final JTextField textField = new JTextField();
    private final JTextField sizeField = new JTextField(4);
    private final JLabel statusLabel = new JLabel();
    private final JLabel hintLabel = new JLabel();
    private final JButton undoButton = new JButton();
    private final JButton clearButton = new JButton();
    private final JButton drawingTestButton = new JButton("Generate Drawing Test");
    private final JButton exportDebugButton = new JButton("Export Debug Snapshot");
    private RuneliteColorPicker activeColorPicker;
    private boolean refreshing;

    PaintOverlaysPanel(PaintOverlaysPlugin plugin, ColorPickerManager colorPickerManager)
    {
        this.plugin = plugin;
        this.colorPickerManager = colorPickerManager;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel content = columnPanel();

        configureToolButtons();

        colorButton.addActionListener(e -> SwingUtilities.invokeLater(() -> openColorPicker("Paint Overlays Color", plugin.getColor(), plugin::setColor)));
        textBackgroundColorButton.addActionListener(e -> SwingUtilities.invokeLater(() -> openColorPicker(
            "Paint Text Background",
            plugin.getTextBackgroundColor(),
            plugin::setTextBackgroundColor)));
        textBorderColorButton.addActionListener(e -> SwingUtilities.invokeLater(() -> openColorPicker(
            "Paint Text Border",
            plugin.getTextBorderColor(),
            plugin::setTextBorderColor)));
        frameColorButton.addActionListener(e -> SwingUtilities.invokeLater(() -> openColorPicker(
            "Paint Mode Frame",
            plugin.getFrameColor(),
            plugin::setFrameColor)));
        JPanel drawingSection = sectionPanel("Drawing");
        drawingSection.add(fullWidthRow(toolButtonRow()));
        drawingSection.add(row("Color", colorButton));
        drawingSection.add(row("Size", sharedSizeRow()));
        drawingSection.add(row("Shape", shapeTypeBox));
        content.add(drawingSection);

        JPanel textSection = sectionPanel("Text");
        textField.setText(plugin.getPendingText());
        textSection.add(row("Font", fontBox));
        textSection.add(row("Text", textField));
        textSection.add(row("Background", textBackgroundColorButton));
        textSection.add(row("Border", textBorderColorButton));
        content.add(textSection);

        JPanel frameSection = sectionPanel("Frame");
        frameSection.add(row("Frame Style", frameStyleBox));
        frameSection.add(row("Frame FX", frameRainbowCheck));
        frameSection.add(row("Frame", frameColorButton));
        content.add(frameSection);

        undoButton.addActionListener(e -> plugin.undoLastAction());
        undoButton.setHorizontalAlignment(SwingConstants.LEFT);
        clearButton.addActionListener(e ->
        {
            ClearScope scope = promptClearScope();
            if (scope == ClearScope.SMALL)
            {
                plugin.clearCurrentSurfaceChunk();
            }
            else if (scope == ClearScope.LARGE)
            {
                plugin.clearSecondarySurfaceSelection();
            }
        });
        clearButton.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel actionsSection = sectionPanel("Actions");
        actionsSection.add(fullWidthRow(undoButton));
        actionsSection.add(fullWidthRow(clearButton));
        content.add(actionsSection);

        drawingTestButton.addActionListener(e ->
        {
            if (confirmGenerateDrawingTest())
            {
                plugin.generateDrawingTest();
            }
        });
        drawingTestButton.setHorizontalAlignment(SwingConstants.LEFT);
        exportDebugButton.addActionListener(e -> plugin.exportDebugSnapshot());
        exportDebugButton.setHorizontalAlignment(SwingConstants.LEFT);
        if (plugin.areDebugToolsEnabled())
        {
            JPanel debugSection = sectionPanel("Debug");
            debugSection.add(fullWidthRow(drawingTestButton));
            debugSection.add(fullWidthRow(exportDebugButton));
            content.add(debugSection);
        }

        statusLabel.setForeground(Color.WHITE);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        hintLabel.setForeground(ColorScheme.TEXT_COLOR);
        hintLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        hintLabel.setHorizontalAlignment(SwingConstants.LEFT);
        hintLabel.setAlignmentX(LEFT_ALIGNMENT);
        JPanel statusSection = sectionPanel("Status");
        statusSection.add(labelRow(statusLabel));
        statusSection.add(labelRow(hintLabel));
        content.add(statusSection);

        add(content, BorderLayout.NORTH);

        fontBox.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setFontStyle((PaintFontStyle) fontBox.getSelectedItem());
            }
        });
        shapeTypeBox.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setShapeType((PaintShapeType) shapeTypeBox.getSelectedItem());
            }
        });
        frameStyleBox.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setFrameStyle((PaintFrameStyle) frameStyleBox.getSelectedItem());
            }
        });
        sizeSlider.addChangeListener(e ->
        {
            if (!refreshing)
            {
                applySelectedToolSize(sizeSlider.getValue());
            }
        });
        frameRainbowCheck.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setFrameRainbowEnabled(frameRainbowCheck.isSelected());
            }
        });
        textField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                plugin.setPendingText(textField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                plugin.setPendingText(textField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                plugin.setPendingText(textField.getText());
            }
        });
        sizeField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                updateSelectedSizeFromField();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                updateSelectedSizeFromField();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                updateSelectedSizeFromField();
            }
        });

        refreshState();
    }

    void refreshState()
    {
        refreshing = true;
        try
        {
            setSelectedToolButton(plugin.getTool());
            shapeTypeBox.setSelectedItem(plugin.getShapeType());
            fontBox.setSelectedItem(plugin.getFontStyle());
            frameStyleBox.setSelectedItem(plugin.getFrameStyle());
            int selectedSize = getSelectedToolSize();
            sizeSlider.setMinimum(getSelectedSizeMinimum());
            sizeSlider.setMaximum(Math.max(getSelectedSizeSliderMaximum(), selectedSize));
            sizeSlider.setValue(selectedSize);
            if (!sizeField.getText().equals(String.valueOf(selectedSize)))
            {
                sizeField.setText(String.valueOf(selectedSize));
            }
            if (!textField.getText().equals(plugin.getPendingText()))
            {
                textField.setText(plugin.getPendingText());
            }

            Color color = plugin.getColor();
            syncButtonColor(colorButton, color);
            syncButtonColor(textBackgroundColorButton, opaque(plugin.getTextBackgroundColor()));
            syncButtonColor(textBorderColorButton, opaque(plugin.getTextBorderColor()));
            syncButtonColor(frameColorButton, opaque(plugin.getFrameColor()));
            frameRainbowCheck.setSelected(plugin.isFrameRainbowEnabled());
            PaintTool tool = plugin.getTool();
            boolean editingAvailable = plugin.isEditingAvailable();
            boolean textTool = tool == PaintTool.TEXT;
            boolean shapeTool = tool == PaintTool.SHAPE;
            boolean sizeEnabled = editingAvailable && tool != null;
            brushButton.setEnabled(editingAvailable);
            shapeButton.setEnabled(editingAvailable);
            textButton.setEnabled(editingAvailable);
            eraserButton.setEnabled(editingAvailable);
            shapeTypeBox.setEnabled(editingAvailable && shapeTool);
            fontBox.setEnabled(editingAvailable && textTool);
            frameStyleBox.setEnabled(editingAvailable);
            frameRainbowCheck.setEnabled(editingAvailable);
            sizeSlider.setEnabled(sizeEnabled);
            sizeField.setEnabled(sizeEnabled);
            textField.setEnabled(editingAvailable && textTool);
            textBackgroundColorButton.setEnabled(editingAvailable && textTool);
            textBorderColorButton.setEnabled(editingAvailable && textTool);
            colorButton.setEnabled(editingAvailable && tool != PaintTool.ERASER);
            frameColorButton.setEnabled(editingAvailable && !plugin.isFrameRainbowEnabled());
            undoButton.setText(plugin.getUndoActionText());
            undoButton.setEnabled(editingAvailable && plugin.canUndo());
            clearButton.setText(plugin.getClearActionText());
            clearButton.setEnabled(editingAvailable && plugin.canClearSurface());
            drawingTestButton.setEnabled(plugin.canGenerateDrawingTest());
            exportDebugButton.setEnabled(editingAvailable && plugin.areDebugToolsEnabled());
            statusLabel.setText(html(plugin.getInputStatusText()));
            hintLabel.setText(buildHintText(plugin));
        }
        finally
        {
            refreshing = false;
        }
    }

    private void configureToolButtons()
    {
        configureToolButton(brushButton, PaintTool.BRUSH);
        configureToolButton(shapeButton, PaintTool.SHAPE);
        configureToolButton(textButton, PaintTool.TEXT);
        configureToolButton(eraserButton, PaintTool.ERASER);
    }

    private void configureToolButton(JToggleButton button, PaintTool tool)
    {
        button.addActionListener(e ->
        {
            if (refreshing)
            {
                return;
            }

            closeActiveColorPicker();
            if (plugin.getTool() == tool)
            {
                plugin.setTool(null);
            }
            else
            {
                plugin.setTool(tool);
            }
        });
    }

    private JPanel toolButtonRow()
    {
        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        Dimension toolSize = new Dimension(110, 32);
        brushButton.setPreferredSize(toolSize);
        shapeButton.setPreferredSize(toolSize);
        textButton.setPreferredSize(toolSize);
        eraserButton.setPreferredSize(toolSize);
        panel.add(brushButton);
        panel.add(shapeButton);
        panel.add(textButton);
        panel.add(eraserButton);
        return panel;
    }

    private void setSelectedToolButton(PaintTool tool)
    {
        brushButton.setSelected(tool == PaintTool.BRUSH);
        shapeButton.setSelected(tool == PaintTool.SHAPE);
        textButton.setSelected(tool == PaintTool.TEXT);
        eraserButton.setSelected(tool == PaintTool.ERASER);

        if (tool == null)
        {
            return;
        }

        switch (tool)
        {
            case SHAPE:
                shapeButton.setSelected(true);
                break;
            case TEXT:
                textButton.setSelected(true);
                break;
            case ERASER:
                eraserButton.setSelected(true);
                break;
            case BRUSH:
            default:
                brushButton.setSelected(true);
                break;
        }
    }

    private JPanel sharedSizeRow()
    {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.add(sizeSlider, BorderLayout.CENTER);
        panel.add(sizeField, BorderLayout.EAST);
        return panel;
    }

    private void updateSelectedSizeFromField()
    {
        if (refreshing)
        {
            return;
        }

        String text = sizeField.getText().trim();
        if (text.isEmpty())
        {
            return;
        }

        try
        {
            applySelectedToolSize(Integer.parseInt(text));
        }
        catch (NumberFormatException ignored)
        {
        }
    }

    private void applySelectedToolSize(int value)
    {
        PaintTool tool = plugin.getTool();
        if (tool == null)
        {
            return;
        }

        switch (tool)
        {
            case SHAPE:
                plugin.setShapeSize(value);
                break;
            case TEXT:
                plugin.setTextSize(value);
                break;
            case BRUSH:
            case ERASER:
            default:
                plugin.setBrushSize(value);
                break;
        }
    }

    private int getSelectedToolSize()
    {
        PaintTool tool = plugin.getTool();
        if (tool == PaintTool.SHAPE)
        {
            return plugin.getShapeSize();
        }
        if (tool == PaintTool.TEXT)
        {
            return plugin.getTextSize();
        }
        if (tool == PaintTool.BRUSH || tool == PaintTool.ERASER)
        {
            return plugin.getBrushSize();
        }
        return plugin.getBrushSize();
    }

    private int getSelectedSizeMinimum()
    {
        PaintTool tool = plugin.getTool();
        if (tool == PaintTool.SHAPE)
        {
            return 4;
        }
        return 1;
    }

    private int getSelectedSizeSliderMaximum()
    {
        PaintTool tool = plugin.getTool();
        if (tool == PaintTool.SHAPE)
        {
            return DEFAULT_SHAPE_SLIDER_MAX;
        }
        if (tool == PaintTool.TEXT)
        {
            return DEFAULT_TEXT_SLIDER_MAX;
        }
        return DEFAULT_BRUSH_SLIDER_MAX;
    }

    private void openColorPicker(String title, Color initialColor, java.util.function.Consumer<Color> consumer)
    {
        closeActiveColorPicker();
        RuneliteColorPicker picker = colorPickerManager.create(
            this,
            initialColor,
            title,
            false);
        activeColorPicker = picker;
        picker.setLocationRelativeTo(this);
        picker.setOnColorChange(consumer::accept);
        picker.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                if (activeColorPicker == picker)
                {
                    activeColorPicker = null;
                }
            }

            @Override
            public void windowClosed(WindowEvent event)
            {
                if (activeColorPicker == picker)
                {
                    activeColorPicker = null;
                }
            }
        });
        picker.setVisible(true);
    }

    private void closeActiveColorPicker()
    {
        RuneliteColorPicker picker = activeColorPicker;
        activeColorPicker = null;
        if (picker == null)
        {
            return;
        }

        picker.setVisible(false);
        picker.dispose();
    }

    private ClearScope promptClearScope()
    {
        String message = plugin.isWorldMapOpen()
            ? "Choose how much map paint to clear:\n\n"
                + "Small Chunk clears only the current map region under your target or the map center.\n"
                + "Large Chunks clears the world map regions currently visible on screen."
            : "Choose how much in-game paint to clear:\n\n"
                + "Small Chunk clears only the current chunk under your target or player position.\n"
                + "Large Chunks clears a wide 3x3 chunk area around your player.";
        String[] options = new String[]
        {
            "Small Chunk",
            "Large Chunks",
            "Cancel"
        };
        int choice = JOptionPane.showOptionDialog(
            this,
            message,
            "Confirm Clear",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]);
        if (choice == 0)
        {
            return ClearScope.SMALL;
        }
        if (choice == 1)
        {
            return ClearScope.LARGE;
        }
        return ClearScope.CANCEL;
    }

    private boolean confirmGenerateDrawingTest()
    {
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Generate a deterministic scene test pattern in the nearby 3x3 chunk area?\n\nThis clears existing nearby scene paint first so each test run is directly comparable.",
            "Generate Drawing Test",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private static void syncButtonColor(JButton button, Color color)
    {
        button.setBackground(color);
        button.setForeground(color.getRed() + color.getGreen() + color.getBlue() > 360 ? Color.BLACK : Color.WHITE);
    }

    private static Color opaque(Color color)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static JPanel row(String labelText, Component component)
    {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JLabel label = new JLabel(labelText);
        label.setForeground(Color.WHITE);
        label.setPreferredSize(new Dimension(68, 24));

        panel.add(label, BorderLayout.WEST);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel fullWidthRow(Component component)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel labelRow(JLabel label)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel columnPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        return panel;
    }

    private static JPanel sectionPanel(String title)
    {
        JPanel panel = columnPanel();
        Border line = BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(line, title),
            BorderFactory.createEmptyBorder(8, 8, 4, 8)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static String buildHintText(PaintOverlaysPlugin plugin)
    {
        PaintInputMode mode = plugin.getInputMode();
        PaintTool tool = plugin.getTool();
        if (!plugin.isEditingAvailable())
        {
            return html("Paint tools are available after logging in.");
        }

        if (mode == PaintInputMode.NONE)
        {
            return html("Drawing is off. Select a tool at the very top of this panel to paint. While editing is active, click the same tool again or press ESC to turn drawing off and resume playing.");
        }

        if (mode == PaintInputMode.WORLD_MAP && !plugin.isWorldMapInputAvailable())
        {
            if (!plugin.isSceneInputAvailable())
            {
                return html("Paint input is disabled until you are logged into the game.");
            }

            return html("World Map mode only captures input while the world map is open.");
        }

        if (mode == PaintInputMode.SCENE && !plugin.isSceneInputAvailable())
        {
            return html("In-Game mode only captures input while you are logged into the game and the world map is closed.");
        }

        if (tool == PaintTool.SHAPE)
        {
            return html("Left-click to place a " + plugin.getShapeType().toString().toLowerCase() + " with the current size. Click the same tool button again or press ESC to turn drawing off.");
        }

        if (tool == PaintTool.TEXT)
        {
            return html("Left-click to place a new text note. Background and border colors are stored per note; set their picker opacity to 0 to remove them. Use Undo Last or Ctrl+Z to revert recent actions. Click the same tool button again or press ESC to turn drawing off.");
        }

        if (tool == PaintTool.ERASER)
        {
            return html("Left-click or drag to erase using the visible cursor size. Use Undo Last or Ctrl+Z to revert recent actions. Click the same tool button again or press ESC to turn drawing off and resume playing.");
        }

        String surface = mode == PaintInputMode.SCENE ? "in-game view" : "world map";
        return html("Left-drag to paint on the " + surface + ". Use Undo Last or Ctrl+Z to revert recent actions. Click the same tool button again or press ESC to turn drawing off and resume playing.");
    }

    private static String html(String text)
    {
        return "<html><div style='width:100%'>" + text + "</div></html>";
    }

    private enum ClearScope
    {
        SMALL,
        LARGE,
        CANCEL
    }
}
