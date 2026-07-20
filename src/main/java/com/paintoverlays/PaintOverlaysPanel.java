package com.paintoverlays;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
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
    private static final Dimension SIZE_FIELD_DIMENSION = new Dimension(34, 24);
    private static final Dimension ROW_LABEL_DIMENSION = new Dimension(42, 24);
    private static final Dimension WIDE_ROW_LABEL_DIMENSION = new Dimension(74, 24);

    private final PaintOverlaysPlugin plugin;
    private final ColorPickerManager colorPickerManager;

    private final javax.swing.JComboBox<PaintFontStyle> fontBox = new javax.swing.JComboBox<>(PaintFontStyle.values());
    private final javax.swing.JComboBox<PaintFrameStyle> frameStyleBox = new javax.swing.JComboBox<>(PaintFrameStyle.values());
    private final JToggleButton brushButton = new JToggleButton("Brush");
    private final JToggleButton shapesButton = new JToggleButton("Shapes");
    private final JToggleButton textButton = new JToggleButton("Text");
    private final JToggleButton eraserButton = new JToggleButton("Eraser");
    private final JButton colorButton = new JButton("Pick Color");
    private final JButton textBackgroundColorButton = new JButton("Pick Background");
    private final JButton textBorderColorButton = new JButton("Pick Border");
    private final JCheckBox textSpeechBubbleCheck = new JCheckBox("Speech Bubble");
    private final JButton frameColorButton = new JButton("Pick Frame");
    private final JCheckBox frameRainbowCheck = new JCheckBox("Rainbow");
    private final JSlider sizeSlider = new JSlider(1, DEFAULT_TEXT_SLIDER_MAX, 16);
    private final JTextField textField = new JTextField();
    private final JTextField sizeField = new JTextField(4);
    private final RotationDial rotationDial = new RotationDial();
    private final JLabel rotationLabel = new JLabel("0 deg");
    private final JLabel rotationPreviewLabel = new JLabel();
    private final JCheckBox flipHorizontalCheck = new JCheckBox("Flip");
    private final JLabel statusLabel = new JLabel();
    private final JLabel hintLabel = new JLabel();
    private final JButton undoButton = new JButton();
    private final JButton clearButton = new JButton();
    private final JButton drawingTestButton = new JButton("Generate Drawing Test");
    private final JCheckBox shapeFillCheck = new JCheckBox("Fill");
    private final JPanel shapeOptionsRow;
    private final JPanel rotationRow;
    private final JPanel textSection;
    private RuneliteColorPicker activeColorPicker;
    private JDialog activeAssetPicker;
    private int shapePickerScrollY;
    private int stampPickerScrollY;
    private boolean refreshing;
    private PaintTool displayedTool;
    private PaintTool selectedAssetTool;
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
        rotationRow = row("Rotate", rotationControl());
        drawingSection.add(rotationRow);
        shapeOptionsRow = fullWidthRow(shapeOptionsControl());
        drawingSection.add(shapeOptionsRow);
        content.add(drawingSection);

        textSection = sectionPanel("Text");
        textField.setText(plugin.getPendingText());
        textSection.add(row("Font", fontBox));
        textSection.add(row("Text", textField));
        textSection.add(row("Background", textBackgroundColorButton));
        textSection.add(row("Border", textBorderColorButton));
        textSection.add(fullWidthRow(textSpeechBubbleCheck));
        content.add(textSection);

        JPanel frameSection = sectionPanel("Frame");
        frameSection.add(wideRow("Frame Style", frameStyleBox));
        frameSection.add(wideRow("Frame FX", frameRainbowCheck));
        frameSection.add(wideRow("Frame", frameColorButton));
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
        if (plugin.areDebugToolsEnabled())
        {
            JPanel debugSection = sectionPanel("Debug");
            debugSection.add(fullWidthRow(drawingTestButton));
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
        shapeFillCheck.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setShapeFillEnabled(shapeFillCheck.isSelected());
            }
        });
        flipHorizontalCheck.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setShapeFlipHorizontal(flipHorizontalCheck.isSelected());
            }
        });
        textSpeechBubbleCheck.addActionListener(e ->
        {
            if (!refreshing)
            {
                plugin.setTextFrameStyle(textSpeechBubbleCheck.isSelected()
                    ? PaintTextFrameStyle.SPEECH_BUBBLE
                    : PaintTextFrameStyle.RECTANGLE);
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
        rotationDial.setRotationChangeListener(plugin::setShapeRotationDegrees);

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
            fontBox.setSelectedItem(state.fontStyle);
            frameStyleBox.setSelectedItem(state.frameStyle);
            shapeFillCheck.setSelected(state.shapeFillEnabled);
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
            textSpeechBubbleCheck.setSelected(state.textFrameStyle == PaintTextFrameStyle.SPEECH_BUBBLE);
            syncButtonColor(frameColorButton, opaque(state.frameColor));
            frameRainbowCheck.setSelected(state.frameRainbowEnabled);
            boolean editingAvailable = state.editingAvailable;
            boolean textTool = effectiveTool == PaintTool.TEXT;
            boolean shapeTool = effectiveTool == PaintTool.SHAPE;
            boolean stampTool = effectiveTool == PaintTool.STAMP;
            boolean sizeEnabled = editingAvailable && effectiveTool != null;
            brushButton.setEnabled(editingAvailable);
            shapesButton.setEnabled(editingAvailable);
            textButton.setEnabled(editingAvailable);
            eraserButton.setEnabled(editingAvailable);
            rotationRow.setVisible(shapeTool || stampTool);
            rotationDial.setEnabled(editingAvailable && (shapeTool || stampTool));
            rotationDial.setRotationDegrees(state.shapeRotationDegrees);
            rotationLabel.setText(state.shapeRotationDegrees + " deg");
            rotationPreviewLabel.setIcon(new ImageIcon(createRotationPreview(state, effectiveTool)));
            shapeOptionsRow.setVisible(shapeTool || stampTool);
            shapeFillCheck.setVisible(shapeTool);
            shapeFillCheck.setEnabled(editingAvailable && shapeTool && PaintMath.canFillShape(state.shapeType));
            flipHorizontalCheck.setEnabled(editingAvailable && (shapeTool || stampTool));
            flipHorizontalCheck.setSelected(state.shapeFlipHorizontal);
            textSection.setVisible(textTool);
            fontBox.setEnabled(editingAvailable && textTool);
            frameStyleBox.setEnabled(editingAvailable);
            frameRainbowCheck.setEnabled(editingAvailable);
            sizeSlider.setEnabled(sizeEnabled);
            sizeField.setEnabled(sizeEnabled);
            textField.setEnabled(editingAvailable && textTool);
            textBackgroundColorButton.setEnabled(editingAvailable && textTool);
            textBorderColorButton.setEnabled(editingAvailable && textTool);
            textSpeechBubbleCheck.setEnabled(editingAvailable && textTool);
            colorButton.setEnabled(editingAvailable && effectiveTool != PaintTool.ERASER && !stampTool);
            frameColorButton.setEnabled(editingAvailable && !state.frameRainbowEnabled);
            undoButton.setText(plugin.getUndoActionText());
            undoButton.setEnabled(editingAvailable && state.undoAvailable);
            clearButton.setText(state.clearActionText);
            clearButton.setEnabled(editingAvailable && state.clearAvailable);
            drawingTestButton.setEnabled(state.drawingTestAvailable);
            statusLabel.setText(html(state.inputStatusText));
            hintLabel.setText(buildHintText(state, effectiveTool));
            revalidate();
        }
        finally
        {
            refreshing = false;
        }
    }

    private void configureToolButtons()
    {
        configureToolButton(brushButton, PaintTool.BRUSH);
        configureShapesButton();
        configureToolButton(textButton, PaintTool.TEXT);
        configureToolButton(eraserButton, PaintTool.ERASER);
    }

    private void configureShapesButton()
    {
        shapesButton.addActionListener(e ->
        {
            if (refreshing)
            {
                return;
            }

            closeActiveColorPicker();
            if (displayedTool == PaintTool.SHAPE || displayedTool == PaintTool.STAMP)
            {
                if (activeAssetPicker != null)
                {
                    closeActiveAssetPickerAndFocusCanvas();
                    return;
                }

                SwingUtilities.invokeLater(this::openAssetPicker);
                return;
            }

            if (selectedAssetTool == null)
            {
                selectedAssetTool = PaintTool.SHAPE;
            }
            displayedTool = selectedAssetTool == PaintTool.STAMP ? PaintTool.STAMP : PaintTool.SHAPE;
            toolRequestPending = true;
            setSelectedToolButton(displayedTool);
            pendingToolRequestId = plugin.requestPanelToolChange(displayedTool);
            SwingUtilities.invokeLater(this::openAssetPicker);
        });
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
        shapesButton.setPreferredSize(toolSize);
        textButton.setPreferredSize(toolSize);
        eraserButton.setPreferredSize(toolSize);
        panel.add(brushButton);
        panel.add(shapesButton);
        panel.add(textButton);
        panel.add(eraserButton);
        return panel;
    }

    private void setSelectedToolButton(PaintTool tool)
    {
        brushButton.setSelected(tool == PaintTool.BRUSH);
        shapesButton.setSelected(tool == PaintTool.SHAPE || tool == PaintTool.STAMP);
        textButton.setSelected(tool == PaintTool.TEXT);
        eraserButton.setSelected(tool == PaintTool.ERASER);

        if (tool == null)
        {
            return;
        }

        switch (tool)
        {
            case SHAPE:
            case STAMP:
                shapesButton.setSelected(true);
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
        sizeField.setPreferredSize(SIZE_FIELD_DIMENSION);
        sizeField.setMinimumSize(SIZE_FIELD_DIMENSION);
        sizeField.setMaximumSize(SIZE_FIELD_DIMENSION);
        sizeField.setHorizontalAlignment(JTextField.CENTER);
        panel.add(sizeSlider, BorderLayout.CENTER);
        panel.add(sizeField, BorderLayout.EAST);
        return panel;
    }

    private JPanel rotationControl()
    {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        rotationLabel.setForeground(Color.WHITE);
        rotationLabel.setPreferredSize(new Dimension(48, 24));
        rotationPreviewLabel.setPreferredSize(new Dimension(44, 44));
        rotationPreviewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(rotationDial, BorderLayout.CENTER);
        JPanel eastPanel = new JPanel(new BorderLayout(4, 0));
        eastPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        eastPanel.add(rotationPreviewLabel, BorderLayout.CENTER);
        eastPanel.add(rotationLabel, BorderLayout.EAST);
        panel.add(eastPanel, BorderLayout.EAST);
        return panel;
    }

    private JPanel shapeOptionsControl()
    {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.add(shapeFillCheck);
        panel.add(flipHorizontalCheck);
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
            case STAMP:
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
        if (tool == PaintTool.SHAPE || tool == PaintTool.STAMP)
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
        if (tool == PaintTool.SHAPE || tool == PaintTool.STAMP)
        {
            return 4;
        }
        return 1;
    }

    private static int getSelectedSizeSliderMaximum(PaintTool tool)
    {
        if (tool == PaintTool.SHAPE || tool == PaintTool.STAMP)
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

    private void openAssetPicker()
    {
        closeActiveAssetPicker();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Paint Shapes");
        activeAssetPicker = dialog;

        JPanel content = new JPanel(new GridLayout(1, 2, 12, 0));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        PaintTool assetTool = selectedAssetTool != null ? selectedAssetTool : plugin.getTool();
        PaintShapeType selectedShapeType = plugin.getShapeType();
        PaintStampType selectedStampType = plugin.getStampType();
        JToggleButton selectedAssetButton = null;

        JPanel shapeGrid = assetGrid("Shapes");
        for (PaintShapeType type : PaintShapeType.values())
        {
            if (!shouldShowShapeInPicker(type))
            {
                continue;
            }

            JToggleButton button = assetButton(type.toString(), createShapePreview(type, 40));
            if (assetTool == PaintTool.SHAPE && type == selectedShapeType)
            {
                button.setSelected(true);
                selectedAssetButton = button;
            }
            button.addActionListener(e ->
            {
                plugin.setShapeType(type);
                selectedAssetTool = PaintTool.SHAPE;
                displayedTool = PaintTool.SHAPE;
                toolRequestPending = true;
                setSelectedToolButton(PaintTool.SHAPE);
                pendingToolRequestId = plugin.requestPanelToolChange(PaintTool.SHAPE);
                closeActiveAssetPicker();
                SwingUtilities.invokeLater(plugin::focusGameCanvasForEditing);
            });
            shapeGrid.add(button);
        }

        JPanel stampGrid = assetGrid("Stamps");
        for (PaintStampType type : PaintStampType.values())
        {
            boolean locked = isStampLocked(type);
            JToggleButton button = assetButton(type.toString(), stampButtonPreview(type, locked));
            if (assetTool == PaintTool.STAMP && type == selectedStampType)
            {
                button.setSelected(true);
                selectedAssetButton = button;
            }
            button.setEnabled(!locked);
            if (!locked)
            {
                button.addActionListener(e ->
                {
                    plugin.setStampType(type);
                    selectedAssetTool = PaintTool.STAMP;
                    displayedTool = PaintTool.STAMP;
                    toolRequestPending = true;
                    setSelectedToolButton(PaintTool.STAMP);
                    pendingToolRequestId = plugin.requestPanelToolChange(PaintTool.STAMP);
                    closeActiveAssetPicker();
                    SwingUtilities.invokeLater(plugin::focusGameCanvasForEditing);
                });
            }
            stampGrid.add(button);
        }

        JScrollPane shapeScrollPane = assetScrollPane(shapeGrid);
        JScrollPane stampScrollPane = assetScrollPane(stampGrid);
        int savedShapeScrollY = shapePickerScrollY;
        int savedStampScrollY = stampPickerScrollY;
        content.add(shapeScrollPane);
        content.add(stampScrollPane);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(
            event -> closeActiveAssetPickerAndFocusCanvas(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                if (activeAssetPicker == dialog)
                {
                    activeAssetPicker = null;
                }
            }

            @Override
            public void windowClosed(WindowEvent event)
            {
                if (activeAssetPicker == dialog)
                {
                    activeAssetPicker = null;
                }
            }
        });
        dialog.setVisible(true);
        restoreAssetPickerScroll(
            shapeScrollPane,
            stampScrollPane,
            savedShapeScrollY,
            savedStampScrollY,
            selectedAssetButton,
            assetTool == PaintTool.SHAPE);
    }

    private static JPanel assetGrid(String title)
    {
        JPanel panel = new JPanel(new GridLayout(0, 3, 8, 8));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), title),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        return panel;
    }

    private static JScrollPane assetScrollPane(JPanel grid)
    {
        JScrollPane scrollPane = new JScrollPane(
            grid,
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(360, 360));
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    private static JToggleButton assetButton(String text, BufferedImage image)
    {
        JToggleButton button = new JToggleButton(assetButtonText(text), new ImageIcon(image));
        button.setToolTipText(text);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setPreferredSize(new Dimension(104, 78));
        return button;
    }

    private void restoreAssetPickerScroll(
        JScrollPane shapeScrollPane,
        JScrollPane stampScrollPane,
        int savedShapeScrollY,
        int savedStampScrollY,
        JToggleButton selectedAssetButton,
        boolean selectedAssetIsShape)
    {
        SwingUtilities.invokeLater(() ->
        {
            shapeScrollPane.getVerticalScrollBar().setValue(savedShapeScrollY);
            stampScrollPane.getVerticalScrollBar().setValue(savedStampScrollY);

            int selectedScrollY = selectedAssetIsShape ? savedShapeScrollY : savedStampScrollY;
            if (selectedScrollY <= 0)
            {
                scrollSelectedAssetButtonIntoView(selectedAssetButton);
            }
            focusSelectedAssetButton(selectedAssetButton);

            shapePickerScrollY = shapeScrollPane.getVerticalScrollBar().getValue();
            stampPickerScrollY = stampScrollPane.getVerticalScrollBar().getValue();
            shapeScrollPane.getVerticalScrollBar().addAdjustmentListener(event -> shapePickerScrollY = event.getValue());
            stampScrollPane.getVerticalScrollBar().addAdjustmentListener(event -> stampPickerScrollY = event.getValue());
        });
    }

    private static void scrollSelectedAssetButtonIntoView(JToggleButton selectedButton)
    {
        if (selectedButton != null)
        {
            selectedButton.scrollRectToVisible(new java.awt.Rectangle(
                0,
                0,
                selectedButton.getWidth(),
                selectedButton.getHeight()));
        }
    }

    private static void focusSelectedAssetButton(JToggleButton selectedButton)
    {
        if (selectedButton != null)
        {
            selectedButton.requestFocusInWindow();
        }
    }

    private boolean isStampLocked(PaintStampType type)
    {
        return !plugin.areStampsUnlocked()
            && type != PaintStampType.CHICKEN
            && type != PaintStampType.DELRITH;
    }

    private static BufferedImage stampButtonPreview(PaintStampType type, boolean locked)
    {
        BufferedImage preview = PaintStamps.createPreview(type, 40);
        if (!locked || preview == null)
        {
            return preview;
        }

        BufferedImage lockedPreview = new BufferedImage(preview.getWidth(), preview.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = lockedPreview.createGraphics();
        try
        {
            graphics.drawImage(preview, 0, 0, null);
            graphics.setColor(new Color(0, 0, 0, 125));
            graphics.fillRect(0, 0, preview.getWidth(), preview.getHeight());
            graphics.setColor(new Color(230, 230, 230, 230));
            graphics.fillRoundRect(3, 10, 13, 11, 3, 3);
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawArc(5, 2, 9, 12, 0, 180);
        }
        finally
        {
            graphics.dispose();
        }
        return lockedPreview;
    }

    private static String assetButtonText(String text)
    {
        if (text == null || text.length() <= 12)
        {
            return text;
        }

        int splitIndex = text.lastIndexOf(' ', 12);
        if (splitIndex <= 0)
        {
            return text.substring(0, 11) + "...";
        }
        return "<html><center>"
            + text.substring(0, splitIndex)
            + "<br>"
            + text.substring(splitIndex + 1)
            + "</center></html>";
    }

    private static BufferedImage createShapePreview(PaintShapeType type, int size)
    {
        return createShapePreview(type, size, 0);
    }

    private static BufferedImage createShapePreview(PaintShapeType type, int size, int rotationDegrees)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x26FF00));
            graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Shape outline = PaintMath.shapeOutline(new net.runelite.api.Point(size / 2, size / 2), size - 8, type);
            if (outline != null)
            {
                graphics.rotate(Math.toRadians(rotationDegrees), size / 2.0, size / 2.0);
                graphics.draw(outline);
            }
        }
        finally
        {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage createRotationPreview(PaintPanelState state, PaintTool effectiveTool)
    {
        int size = 40;
        if (effectiveTool == PaintTool.STAMP)
        {
            BufferedImage source = PaintStamps.getImage(state.stampType);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            if (source == null)
            {
                return image;
            }

            Graphics2D graphics = image.createGraphics();
            try
            {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                applyPreviewTransform(graphics, size, state.shapeRotationDegrees, state.shapeFlipHorizontal);
                graphics.drawImage(source, 4, 4, size - 8, size - 8, null);
            }
            finally
            {
                graphics.dispose();
            }
            return image;
        }

        if (effectiveTool == PaintTool.SHAPE)
        {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try
            {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(new Color(0x26FF00));
                graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Shape outline = PaintMath.shapeOutline(new net.runelite.api.Point(size / 2, size / 2), size - 8, state.shapeType);
                if (outline != null)
                {
                    applyPreviewTransform(graphics, size, state.shapeRotationDegrees, state.shapeFlipHorizontal);
                    graphics.draw(outline);
                }
            }
            finally
            {
                graphics.dispose();
            }
            return image;
        }

        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    private static void applyPreviewTransform(Graphics2D graphics, int size, int rotationDegrees, boolean flipHorizontal)
    {
        graphics.translate(size / 2.0, size / 2.0);
        graphics.rotate(Math.toRadians(rotationDegrees));
        if (flipHorizontal)
        {
            graphics.scale(-1.0, 1.0);
        }
        graphics.translate(-size / 2.0, -size / 2.0);
    }

    private static boolean shouldShowShapeInPicker(PaintShapeType type)
    {
        return type != PaintShapeType.LOBSTER
            && type != PaintShapeType.DRAGON;
    }

    boolean closeActiveAssetPickerIfOpen()
    {
        JDialog dialog = activeAssetPicker;
        activeAssetPicker = null;
        if (dialog == null)
        {
            return false;
        }

        dialog.setVisible(false);
        dialog.dispose();
        return true;
    }

    private void closeActiveAssetPicker()
    {
        closeActiveAssetPickerIfOpen();
    }

    private void closeActiveAssetPickerAndFocusCanvas()
    {
        if (closeActiveAssetPickerIfOpen())
        {
            SwingUtilities.invokeLater(plugin::focusGameCanvasForEditing);
        }
    }

    void disposePanel()
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            closeActiveColorPicker();
            closeActiveAssetPicker();
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            closeActiveColorPicker();
            closeActiveAssetPicker();
        });
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
        return row(labelText, component, ROW_LABEL_DIMENSION);
    }

    private static JPanel wideRow(String labelText, Component component)
    {
        return row(labelText, component, WIDE_ROW_LABEL_DIMENSION);
    }

    private static JPanel row(String labelText, Component component, Dimension labelDimension)
    {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JLabel label = new JLabel(labelText);
        label.setForeground(Color.WHITE);
        label.setPreferredSize(labelDimension);

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

    private static final class RotationDial extends JPanel
    {
        private int rotationDegrees;
        private java.util.function.IntConsumer rotationChangeListener;

        RotationDial()
        {
            setPreferredSize(new Dimension(58, 58));
            setMinimumSize(new Dimension(58, 58));
            setOpaque(false);

            MouseAdapter mouseAdapter = new MouseAdapter()
            {
                @Override
                public void mousePressed(MouseEvent event)
                {
                    updateRotationFromMouse(event);
                }

                @Override
                public void mouseDragged(MouseEvent event)
                {
                    updateRotationFromMouse(event);
                }
            };
            addMouseListener(mouseAdapter);
            addMouseMotionListener(mouseAdapter);
        }

        void setRotationChangeListener(java.util.function.IntConsumer rotationChangeListener)
        {
            this.rotationChangeListener = rotationChangeListener;
        }

        void setRotationDegrees(int rotationDegrees)
        {
            int normalized = normalizeRotation(rotationDegrees);
            if (this.rotationDegrees != normalized)
            {
                this.rotationDegrees = normalized;
                repaint();
            }
        }

        private void updateRotationFromMouse(MouseEvent event)
        {
            if (!isEnabled())
            {
                return;
            }

            double centerX = getWidth() / 2.0;
            double centerY = getHeight() / 2.0;
            double radians = Math.atan2(event.getY() - centerY, event.getX() - centerX);
            int degrees = normalizeRotation((int) Math.round(Math.toDegrees(radians) + 90.0));
            setRotationDegrees(degrees);
            if (rotationChangeListener != null)
            {
                rotationChangeListener.accept(degrees);
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics)
        {
            super.paintComponent(graphics);
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            try
            {
                graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 6;
                int left = (getWidth() - size) / 2;
                int top = (getHeight() - size) / 2;
                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                int radius = size / 2;

                graphics2D.setColor(isEnabled() ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
                graphics2D.fillOval(left, top, size, size);
                graphics2D.setColor(Color.WHITE);
                graphics2D.drawOval(left, top, size, size);

                double radians = Math.toRadians(rotationDegrees - 90.0);
                int needleX = centerX + (int) Math.round(Math.cos(radians) * (radius - 6));
                int needleY = centerY + (int) Math.round(Math.sin(radians) * (radius - 6));
                graphics2D.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics2D.setColor(isEnabled() ? new Color(0x26FF00) : Color.GRAY);
                graphics2D.drawLine(centerX, centerY, needleX, needleY);
                graphics2D.fillOval(centerX - 3, centerY - 3, 6, 6);
            }
            finally
            {
                graphics2D.dispose();
            }
        }

        private static int normalizeRotation(int rotationDegrees)
        {
            int normalized = rotationDegrees % 360;
            return normalized < 0 ? normalized + 360 : normalized;
        }
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
            return html("Left-click to place a " + state.shapeType.toString().toLowerCase() + " with the current size. Fill applies to closed shapes only.");
        }

        if (tool == PaintTool.STAMP)
        {
            return html("Left-click to place a " + state.stampType.toString().toLowerCase() + " stamp with the current size. Stamps keep their built-in colors.");
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
