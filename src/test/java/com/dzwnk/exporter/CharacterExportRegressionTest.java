package com.dzwnk.exporter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CharacterExportRegressionTest
{
    @Test
    public void appendDiaryTaskLineMergesWrappedTaskText()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "Harvest some strawberries from the Ardougne farming", false);
        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "patch.(31 Farming)", false);

        Assert.assertEquals(1, tasks.size());
        Assert.assertEquals(
            "Harvest some strawberries from the Ardougne farming patch.(31 Farming)",
            tasks.get(0).get("name")
        );
        Assert.assertEquals(Boolean.FALSE, tasks.get(0).get("complete"));
    }

    @Test
    public void appendDiaryTaskLineSkipsRewardReclaimHint()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "If I ever lose my Rada's blessing, I can speak to Elise outside Kourend Castle.", false);

        Assert.assertEquals(0, tasks.size());
    }

    @Test
    public void appendDiaryTaskLineMergesRequirementLineWithoutFlippingCompletion()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "Use Kharedst's memoirs to teleport to all five cities in Great Kourend.", true);
        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, The Forsaken Tower, The Ascent of Arceuus)", true);
        CharacterStateExporterPlugin.appendDiaryTaskLine(tasks, "Mine some Volcanic sulphur.(42 Mining)", true);

        Assert.assertEquals(2, tasks.size());
        Assert.assertEquals(
            "Use Kharedst's memoirs to teleport to all five cities in Great Kourend. (The Depths of Despair, The Queen of Thieves, Tale of the Righteous, The Forsaken Tower, The Ascent of Arceuus)",
            tasks.get(0).get("name")
        );
        Assert.assertEquals(Boolean.TRUE, tasks.get(0).get("complete"));
        Assert.assertEquals("Mine some Volcanic sulphur.(42 Mining)", tasks.get(1).get("name"));
        Assert.assertEquals(Boolean.TRUE, tasks.get(1).get("complete"));
    }

    @Test
    public void splitDiaryWidgetLinesPreservesBrSeparatedTasks()
    {
        List<CharacterStateExporterPlugin.DiaryWidgetLine> lines = CharacterStateExporterPlugin.splitDiaryWidgetLines(
            "<col=ff0000>Use Kharedst's memoirs to teleport to all five cities in Great Kourend.</col><br>"
                + "<col=ff0000>(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, The Forsaken Tower, The Ascent of Arceuus)</col><br>"
                + "<str>Mine some Volcanic sulphur.(42 Mining)</str><br>"
                + "<str>Enter the Farming Guild.(45 Farming)</str>"
        );

        Assert.assertEquals(4, lines.size());
        Assert.assertEquals("Use Kharedst's memoirs to teleport to all five cities in Great Kourend.", lines.get(0).getText());
        Assert.assertEquals("(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, The Forsaken Tower, The Ascent of Arceuus)", lines.get(1).getText());
        Assert.assertEquals("Mine some Volcanic sulphur.(42 Mining)", lines.get(2).getText());
        Assert.assertEquals("Enter the Farming Guild.(45 Farming)", lines.get(3).getText());
        Assert.assertEquals(Boolean.FALSE, lines.get(0).isComplete());
        Assert.assertEquals(Boolean.FALSE, lines.get(1).isComplete());
        Assert.assertEquals(Boolean.TRUE, lines.get(2).isComplete());
        Assert.assertEquals(Boolean.TRUE, lines.get(3).isComplete());
    }

    @Test
    public void splitDiaryWidgetLinesKeepsMixedCompletionPerLine()
    {
        List<CharacterStateExporterPlugin.DiaryWidgetLine> lines = CharacterStateExporterPlugin.splitDiaryWidgetLines(
            "<str>Fish a Trout from the River Molch.(20 Fishing)</str><br>"
                + "If I ever lose my Rada's blessing, I can speak to Elise<br>"
                + "outside Kourend Castle."
        );

        Assert.assertEquals(3, lines.size());
        Assert.assertEquals("Fish a Trout from the River Molch.(20 Fishing)", lines.get(0).getText());
        Assert.assertEquals(Boolean.TRUE, lines.get(0).isComplete());
        Assert.assertEquals("If I ever lose my Rada's blessing, I can speak to Elise", lines.get(1).getText());
        Assert.assertEquals(Boolean.FALSE, lines.get(1).isComplete());
        Assert.assertEquals("outside Kourend Castle.", lines.get(2).getText());
        Assert.assertEquals(Boolean.FALSE, lines.get(2).isComplete());
    }

    @Test
    public void formatStatusTimeStaysCompactAndTooltipIncludesDate()
    {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        Instant olderExport = yesterday.atStartOfDay(zone).plusHours(15).toInstant();

        String formatted = CharacterStateExporterPanel.formatStatusTime(olderExport);
        String tooltip = CharacterStateExporterPanel.formatStatusTooltip(olderExport, "Saved");

        Assert.assertFalse(formatted.contains(String.valueOf(yesterday.getDayOfMonth())));
        Assert.assertTrue(tooltip.contains("Saved"));
        Assert.assertTrue(tooltip.contains(String.valueOf(yesterday.getDayOfMonth())));
    }
}
