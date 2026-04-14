/*
 * Copyright (c) 2026, DZWNK
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.dzwnk.exporter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
class CharacterStateExporterPanel extends PluginPanel
{
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm:ss a");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a");
    private static final int BUTTON_WIDTH = 170;
    private static final String[] DATASET_FILES = {
        "bank.json", "character.json", "diaries.json", "quests.json",
        "inventory.json", "equipment.json", "seed_vault.json", "combat_achievements.json", "collection_log.json"
    };

    static final String[] DATASET_KEYS = {
        "bank", "character", "diaries", "quests",
        "inventory", "equipment", "seed_vault", "combat_achievements", "collection_log"
    };
    private static final String[] DATASET_LABELS = {
        "Bank", "Stats", "Diaries", "Quests",
        "Inventory", "Equipment", "Seed Vault", "Combat Tasks", "Collection Log"
    };
    private static final String[] DATASET_HINTS = {
        "Log in", "Log in", "Log in", "Log in",
        "Log in", "Log in", "Log in", "Log in", "Log in"
    };
    private static final String[] DATASET_READY_HINTS = {
        "Open bank",
        "Sync or train",
        "Sync or open diary",
        "Sync or quest",
        "Sync or move item",
        "Sync or change gear",
        "Open vault",
        "Sync or complete task",
        "Open log"
    };

    enum SyncOutcome
    {
        UPDATED,
        UP_TO_DATE,
        UNAVAILABLE,
        FAILED
    }

    private static final class StatusRow
    {
        private final JLabel timeLabel;
        private final JLabel detailLabel;

        private StatusRow(JLabel timeLabel, JLabel detailLabel)
        {
            this.timeLabel = timeLabel;
            this.detailLabel = detailLabel;
        }
    }

    private final Map<String, StatusRow> statusRows = new LinkedHashMap<>();
    private final Map<String, String> datasetFiles = new LinkedHashMap<>();
    private final JLabel directoryLabel;
    private final JLabel accountLabel;
    private final JComboBox<String> openFileDropdown;
    private volatile Path currentOutputDir;
    private final Object manualSyncLock = new Object();
    private boolean manualSyncInProgress;
    private final Map<String, SyncOutcome> manualSyncOutcomes = new LinkedHashMap<>();
    private boolean dropdownRebuilding;

    CharacterStateExporterPanel(Runnable exportAllAction)
    {
        super(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Account name
        accountLabel = new JLabel("Not logged in");
        accountLabel.setHorizontalAlignment(JLabel.CENTER);
        accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        accountLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        accountLabel.setAlignmentX(CENTER_ALIGNMENT);
        accountLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountLabel.getPreferredSize().height + 4));
        add(accountLabel);

        // Status header
        JLabel statusHeader = new JLabel("Export Status");
        statusHeader.setHorizontalAlignment(JLabel.CENTER);
        statusHeader.setForeground(Color.WHITE);
        statusHeader.setBorder(new EmptyBorder(0, 0, 6, 0));
        statusHeader.setAlignmentX(CENTER_ALIGNMENT);
        statusHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, statusHeader.getPreferredSize().height + 4));
        add(statusHeader);

        // Dataset status rows
        JPanel statusGrid = new JPanel();
        statusGrid.setLayout(new BoxLayout(statusGrid, BoxLayout.Y_AXIS));
        statusGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusGrid.setBorder(new EmptyBorder(8, 8, 8, 8));

        for (int i = 0; i < DATASET_KEYS.length; i++)
        {
            final String datasetKey = DATASET_KEYS[i];
            final String datasetFile = DATASET_FILES[i];
            datasetFiles.put(datasetKey, datasetFile);

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);

            JLabel nameLabel = new JLabel(DATASET_LABELS[i]);
            nameLabel.setHorizontalAlignment(JLabel.LEFT);
            nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            nameLabel.setPreferredSize(new Dimension(80, 28));
            row.add(nameLabel, BorderLayout.WEST);

            JPanel statusPanel = new JPanel();
            statusPanel.setOpaque(false);
            statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));

            JLabel timeLabel = new JLabel(DATASET_HINTS[i]);
            timeLabel.setAlignmentX(CENTER_ALIGNMENT);
            timeLabel.setHorizontalAlignment(JLabel.CENTER);
            timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            statusPanel.add(timeLabel);

            JLabel detailLabel = new JLabel(" ");
            detailLabel.setAlignmentX(CENTER_ALIGNMENT);
            detailLabel.setHorizontalAlignment(JLabel.CENTER);
            detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            statusPanel.add(detailLabel);
            row.add(statusPanel, BorderLayout.CENTER);

            statusRows.put(datasetKey, new StatusRow(timeLabel, detailLabel));
            statusGrid.add(row);
            if (i < DATASET_KEYS.length - 1)
            {
                statusGrid.add(Box.createRigidArea(new Dimension(0, 6)));
            }
        }

        statusGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, statusGrid.getPreferredSize().height + 10));
        add(statusGrid);
        add(Box.createRigidArea(new Dimension(0, 10)));

        // Buttons
        JButton exportAllButton = createCenteredButton("Sync Available");
        exportAllButton.addActionListener(e -> exportAllAction.run());
        add(exportAllButton);

        add(Box.createRigidArea(new Dimension(0, 6)));

        JButton openFolderButton = createCenteredButton("Open Folder");
        openFolderButton.addActionListener(e -> openOutputFolder());
        add(openFolderButton);

        add(Box.createRigidArea(new Dimension(0, 6)));

        JLabel openFileLabel = new JLabel("Open File");
        openFileLabel.setHorizontalAlignment(JLabel.CENTER);
        openFileLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        openFileLabel.setAlignmentX(CENTER_ALIGNMENT);
        openFileLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, openFileLabel.getPreferredSize().height + 2));
        add(openFileLabel);
        add(Box.createRigidArea(new Dimension(0, 4)));

        openFileDropdown = new JComboBox<>();
        openFileDropdown.setAlignmentX(CENTER_ALIGNMENT);
        openFileDropdown.setFont(UIManager.getFont("Button.font"));
        openFileDropdown.setRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setHorizontalAlignment(JLabel.CENTER);
                return label;
            }
        });
        Dimension dropdownSize = new Dimension(BUTTON_WIDTH, 30);
        openFileDropdown.setPreferredSize(dropdownSize);
        openFileDropdown.setMinimumSize(dropdownSize);
        openFileDropdown.setMaximumSize(dropdownSize);
        openFileDropdown.addActionListener(e -> handleOpenFileSelection());
        add(openFileDropdown);
        rebuildOpenFileDropdown();

        add(Box.createRigidArea(new Dimension(0, 12)));

        // Output directory
        JPanel dirPanel = new JPanel(new BorderLayout());
        dirPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dirPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)
        ));

        JLabel dirTitle = new JLabel("Output folder:");
        dirTitle.setHorizontalAlignment(JLabel.CENTER);
        dirTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        dirPanel.add(dirTitle, BorderLayout.NORTH);

        directoryLabel = new JLabel("character-exporter/");
        directoryLabel.setHorizontalAlignment(JLabel.CENTER);
        directoryLabel.setForeground(Color.WHITE);
        directoryLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        dirPanel.add(directoryLabel, BorderLayout.CENTER);

        dirPanel.setAlignmentX(CENTER_ALIGNMENT);
        dirPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, dirPanel.getPreferredSize().height + 20));
        add(dirPanel);
        add(Box.createRigidArea(new Dimension(0, 10)));

        // Notes — export trigger guide and collection log caveat.
        // getMaximumSize() is overridden so BoxLayout never expands the panel
        // beyond its actual content height.
        JPanel notesPanel = new JPanel(new BorderLayout())
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        notesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notesPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)
        ));

        JLabel notesLabel = new JLabel(
            "<html>"
                + "'Sync Available' exports Stats, Quests, Diaries, Tasks, "
                + "Inventory &amp; Equipment.<br><br>"
                + "Bank &amp; Seed Vault need to be opened in-game.<br><br>"
                + "Collection Log needs to have each entry clicked in-game."
                + "</html>"
        );
        notesLabel.setHorizontalAlignment(JLabel.LEFT);
        notesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        notesPanel.add(notesLabel, BorderLayout.NORTH);
        notesPanel.setAlignmentX(CENTER_ALIGNMENT);
        add(notesPanel);
    }

    void setAccount(String playerName, Path outputDir)
    {
        currentOutputDir = outputDir;
        SwingUtilities.invokeLater(() ->
        {
            accountLabel.setText(playerName);
            accountLabel.setForeground(Color.WHITE);
            directoryLabel.setText("character-exporter/" + playerName + "/");
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                StatusRow row = statusRows.get(DATASET_KEYS[i]);
                if (row != null && !hasExistingDatasetFile(DATASET_KEYS[i]))
                {
                    row.timeLabel.setText(DATASET_READY_HINTS[i]);
                    row.timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.timeLabel.setToolTipText(null);
                    row.detailLabel.setText(" ");
                    row.detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.detailLabel.setToolTipText(null);
                }
            }
            rebuildOpenFileDropdown();
        });
    }

    void clearAccount()
    {
        currentOutputDir = null;
        SwingUtilities.invokeLater(() ->
        {
            accountLabel.setText("Not logged in");
            accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            directoryLabel.setText("character-exporter/");
            synchronized (manualSyncLock)
            {
                manualSyncInProgress = false;
                manualSyncOutcomes.clear();
            }
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                StatusRow row = statusRows.get(DATASET_KEYS[i]);
                if (row != null)
                {
                    row.timeLabel.setText(DATASET_HINTS[i]);
                    row.timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.timeLabel.setToolTipText(null);
                    row.detailLabel.setText(" ");
                    row.detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                }
            }
            rebuildOpenFileDropdown();
        });
    }

    void beginManualSync()
    {
        synchronized (manualSyncLock)
        {
            manualSyncInProgress = true;
            manualSyncOutcomes.clear();
        }

    }

    void markExported(String datasetKey)
    {
        String time = formatStatusTime(Instant.now());
        String tooltip = formatStatusTooltip(Instant.now(), "Updated");
        SwingUtilities.invokeLater(() ->
        {
            setRowState(datasetKey, time, "Updated", Color.GREEN, tooltip);
            rebuildOpenFileDropdown();
        });
        recordManualOutcome(datasetKey, SyncOutcome.UPDATED);
    }

    void markChecked(String datasetKey)
    {
        String time = formatStatusTime(Instant.now());
        String tooltip = formatStatusTooltip(Instant.now(), "Checked");
        SwingUtilities.invokeLater(() ->
        {
            setRowState(datasetKey, time, "Up to date", Color.GREEN, tooltip);
        });
        recordManualOutcome(datasetKey, SyncOutcome.UP_TO_DATE);
    }

    void markUnavailable(String datasetKey, String detail, String tooltip)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (hasExistingDatasetFile(datasetKey))
            {
                StatusRow row = statusRows.get(datasetKey);
                if (row != null)
                {
                    row.timeLabel.setToolTipText(tooltip);
                    row.detailLabel.setToolTipText(tooltip);
                }
            }
            else
            {
                setRowState(
                    datasetKey,
                    "Not available",
                    detail != null ? detail : "Unavailable",
                    ColorScheme.LIGHT_GRAY_COLOR,
                    tooltip
                );
            }
        });
        recordManualOutcome(datasetKey, SyncOutcome.UNAVAILABLE);
    }

    /**
     * Called for datasets the Sync button cannot update (e.g. collection log).
     * Records the sync outcome so the sync-complete counter advances, but leaves
     * the row display exactly as it was.
     */
    void skipDatasetInSync(String datasetKey)
    {
        recordManualOutcome(datasetKey, SyncOutcome.UNAVAILABLE);
    }

    void markFailed(String datasetKey, String tooltip)
    {
        SwingUtilities.invokeLater(() -> setRowState(
            datasetKey,
            "Failed",
            "Try again",
            new Color(196, 64, 64),
            tooltip
        ));
        recordManualOutcome(datasetKey, SyncOutcome.FAILED);
    }

    void restoreFromDisk(Path accountDir)
    {
        if (accountDir == null || !Files.isDirectory(accountDir))
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                Path file = accountDir.resolve(DATASET_FILES[i]);
                if (!Files.exists(file))
                {
                    continue;
                }

                try
                {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    String exportedAt = formatStatusTime(modified);
                    String tooltip = formatStatusTooltip(modified, "Saved");

                    setRowState(DATASET_KEYS[i], exportedAt, "Saved", Color.GREEN, tooltip);
                }
                catch (IOException ignored)
                {
                }
            }
            rebuildOpenFileDropdown();
        });
    }

    private static JButton createCenteredButton(String text)
    {
        JButton button = new JButton(text);
        button.setAlignmentX(CENTER_ALIGNMENT);
        Dimension size = new Dimension(BUTTON_WIDTH, 30);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        return button;
    }

    private void setRowState(String datasetKey, String primary, String detail, Color color, String tooltip)
    {
        StatusRow row = statusRows.get(datasetKey);
        if (row == null)
        {
            return;
        }

        row.timeLabel.setText(primary);
        row.timeLabel.setForeground(color);
        row.timeLabel.setToolTipText(tooltip);
        row.detailLabel.setText(detail);
        row.detailLabel.setForeground(color);
        row.detailLabel.setToolTipText(tooltip);
    }

    private boolean hasExistingDatasetFile(String datasetKey)
    {
        Path dir = currentOutputDir;
        String fileName = datasetFiles.get(datasetKey);
        return dir != null && fileName != null && Files.isRegularFile(dir.resolve(fileName));
    }

    private void recordManualOutcome(String datasetKey, SyncOutcome outcome)
    {
        synchronized (manualSyncLock)
        {
            if (!manualSyncInProgress)
            {
                return;
            }

            manualSyncOutcomes.put(datasetKey, outcome);
            if (manualSyncOutcomes.size() == DATASET_KEYS.length)
            {
                manualSyncInProgress = false;
            }
        }
    }

    static String formatStatusTime(Instant instant)
    {
        ZonedDateTime localTime = instant.atZone(ZoneId.systemDefault());
        return localTime.toLocalTime().format(TIME_FMT);
    }

    static String formatStatusTooltip(Instant instant, String prefix)
    {
        ZonedDateTime localTime = instant.atZone(ZoneId.systemDefault());
        return prefix + " " + localTime.format(DATE_TIME_FMT);
    }

    private void rebuildOpenFileDropdown()
    {
        if (openFileDropdown == null)
        {
            return;
        }

        dropdownRebuilding = true;
        openFileDropdown.removeAllItems();

        Path dir = currentOutputDir;
        for (int i = 0; i < DATASET_KEYS.length; i++)
        {
            String datasetKey = DATASET_KEYS[i];
            String fileName = datasetFiles.get(datasetKey);
            if (dir != null && fileName != null && Files.exists(dir.resolve(fileName)))
            {
                openFileDropdown.addItem(DATASET_LABELS[i]);
            }
        }

        openFileDropdown.setEnabled(openFileDropdown.getItemCount() > 0);
        dropdownRebuilding = false;
    }

    private void openOutputFolder()
    {
        Path dir = currentOutputDir;
        if (dir == null || !Files.isDirectory(dir))
        {
            return;
        }

        try
        {
            Desktop.getDesktop().open(dir.toFile());
        }
        catch (IOException | UnsupportedOperationException ex)
        {
            log.debug("Could not open output folder: {}", ex.toString());
        }
    }

    private void openDatasetFile(String datasetKey)
    {
        Path dir = currentOutputDir;
        String fileName = datasetFiles.get(datasetKey);
        if (dir == null || fileName == null)
        {
            return;
        }

        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            Desktop.getDesktop().open(file.toFile());
        }
        catch (IOException | UnsupportedOperationException ex)
        {
            log.debug("Could not open dataset file {}: {}", file, ex.toString());
        }
    }

    private void handleOpenFileSelection()
    {
        if (dropdownRebuilding)
        {
            return;
        }

        Object selected = openFileDropdown.getSelectedItem();
        if (!(selected instanceof String))
        {
            return;
        }

        String label = (String) selected;
        for (int i = 0; i < DATASET_LABELS.length; i++)
        {
            if (DATASET_LABELS[i].equals(label))
            {
                openDatasetFile(DATASET_KEYS[i]);
                break;
            }
        }
    }
}
