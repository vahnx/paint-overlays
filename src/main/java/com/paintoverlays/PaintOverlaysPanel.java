package com.paintoverlays;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
    private PaintTool displayedTool;
    private boolean toolRequestPending;
    private long pendingToolRequestId;

    PaintOverlaysPanel(PaintOverlaysPlugin plugin, ColorPickerManager colorPickerManager, PaintPanelState initialState)
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
        JPanel drawingSection = sectionPanel("Drawing Tools");
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
            plugin.beginClearPreview();
            ClearScope scope;
            try
            {
                scope = promptClearScope();
            }
            finally
            {
                plugin.endClearPreview();
            }

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
                updatePendingText();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                updatePendingText();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                updatePendingText();
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

        FocusAdapter reconcileEditedField = new FocusAdapter()
        {
            @Override
            public void focusLost(FocusEvent event)
            {
                plugin.requestPanelRefresh();
            }
        };
        sizeField.addFocusListener(reconcileEditedField);
        textField.addFocusListener(reconcileEditedField);

        refreshState(initialState);
    }

    void refreshState(PaintPanelState state)
    {
        if (state == null)
        {
            return;
        }

        refreshing = true;
        try
        {
            if (!toolRequestPending
                || !state.editingAvailable
                || state.handledPanelToolRequestId >= pendingToolRequestId)
            {
                displayedTool = state.tool;
                toolRequestPending = false;
            }
            setSelectedToolButton(displayedTool);
            shapeTypeBox.setSelectedItem(state.shapeType);
            fontBox.setSelectedItem(state.fontStyle);
            frameStyleBox.setSelectedItem(state.frameStyle);
            PaintTool effectiveTool = toolRequestPending ? displayedTool : state.tool;
            int selectedSize = getSelectedToolSize(state, effectiveTool);
            if (!toolRequestPending && !sizeSlider.getValueIsAdjusting())
            {
                sizeSlider.setMinimum(getSelectedSizeMinimum(effectiveTool));
                sizeSlider.setMaximum(Math.max(getSelectedSizeSliderMaximum(effectiveTool), selectedSize));
                sizeSlider.setValue(selectedSize);
            }
            if (!toolRequestPending
                && !sizeField.isFocusOwner()
                && !sizeField.getText().equals(String.valueOf(selectedSize)))
            {
                sizeField.setText(String.valueOf(selectedSize));
            }
            if (!textField.isFocusOwner() && !textField.getText().equals(state.pendingText))
            {
                textField.setText(state.pendingText);
            }

            Color color = state.color;
            syncButtonColor(colorButton, color);
            syncButtonColor(textBackgroundColorButton, opaque(state.textBackgroundColor));
            syncButtonColor(textBorderColorButton, opaque(state.textBorderColor));
            syncButtonColor(frameColorButton, opaque(state.frameColor));
            frameRainbowCheck.setSelected(state.frameRainbowEnabled);
            boolean editingAvailable = state.editingAvailable;
            boolean textTool = effectiveTool == PaintTool.TEXT;
            boolean shapeTool = effectiveTool == PaintTool.SHAPE;
            boolean sizeEnabled = editingAvailable && effectiveTool != null;
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
            colorButton.setEnabled(editingAvailable && effectiveTool != PaintTool.ERASER);
            frameColorButton.setEnabled(editingAvailable && !state.frameRainbowEnabled);
            undoButton.setText(plugin.getUndoActionText());
            undoButton.setEnabled(editingAvailable && state.undoAvailable);
            clearButton.setText(state.clearActionText);
            clearButton.setEnabled(editingAvailable && state.clearAvailable);
            drawingTestButton.setEnabled(state.drawingTestAvailable);
            exportDebugButton.setEnabled(editingAvailable && state.debugToolsEnabled);
            statusLabel.setText(html(state.inputStatusText));
            hintLabel.setText(buildHintText(state, effectiveTool));
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
            PaintTool nextTool = displayedTool == tool ? null : tool;
            displayedTool = nextTool;
            toolRequestPending = true;
            setSelectedToolButton(nextTool);
            pendingToolRequestId = plugin.requestPanelToolChange(nextTool);
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

    private void updatePendingText()
    {
        if (!refreshing)
        {
            plugin.setPendingText(textField.getText());
        }
    }

    private void applySelectedToolSize(int value)
    {
        PaintTool tool = displayedTool;
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

    private static int getSelectedToolSize(PaintPanelState state, PaintTool tool)
    {
        if (tool == PaintTool.SHAPE)
        {
            return state.shapeSize;
        }
        if (tool == PaintTool.TEXT)
        {
            return state.textSize;
        }
        if (tool == PaintTool.BRUSH || tool == PaintTool.ERASER)
        {
            return state.brushSize;
        }
        return state.brushSize;
    }

    private static int getSelectedSizeMinimum(PaintTool tool)
    {
        if (tool == PaintTool.SHAPE)
        {
            return 4;
        }
        return 1;
    }

    private static int getSelectedSizeSliderMaximum(PaintTool tool)
    {
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

    void disposePanel()
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            closeActiveColorPicker();
            return;
        }

        SwingUtilities.invokeLater(this::closeActiveColorPicker);
    }

    private ClearScope promptClearScope()
    {
        boolean worldMap = plugin.isWorldMapOpen();
        String message = worldMap
            ? "Choose how much map paint to clear:\n\n"
                + "Current Region clears the current map region.\n"
                + "Visible Regions clears the map regions currently visible on screen."
            : "Choose how much in-game paint to clear:\n\n"
                + "Current Chunk clears only the chunk your player is currently in.\n"
                + "Surrounding Chunks clears the current chunk plus the surrounding 3x3 chunk area around your player.";
        String[] options = worldMap
            ? new String[]
            {
                "Current Region",
                "Visible Regions",
                "Cancel"
            }
            : new String[]
            {
                "Current Chunk",
                "Surrounding Chunks",
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

    private static String buildHintText(PaintPanelState state, PaintTool tool)
    {
        PaintInputMode mode = state.inputMode;
        if (!state.editingAvailable)
        {
            return html("Paint tools are available after logging in.");
        }

        if (mode == PaintInputMode.NONE)
        {
            return html("Drawing is off. Select a tool at the very top of this panel to paint. While editing is active, click the same tool again or press ESC to turn drawing off and resume playing.");
        }

        if (mode == PaintInputMode.WORLD_MAP && !state.worldMapInputAvailable)
        {
            if (!state.sceneInputAvailable)
            {
                return html("Paint input is disabled until you are logged into the game.");
            }

            return html("World Map mode only captures input while the world map is open.");
        }

        if (mode == PaintInputMode.SCENE && !state.sceneInputAvailable)
        {
            return html("In-Game mode only captures input while you are logged into the game and the world map is closed.");
        }

        if (tool == PaintTool.SHAPE)
        {
            return html("Left-click to place a " + state.shapeType.toString().toLowerCase() + " with the current size. Click the same tool button again or press ESC to turn drawing off.");
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
