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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("characterStateExporter")
public interface CharacterStateExporterConfig extends Config
{
    @ConfigItem(
        keyName = "exportCharacter",
        name = "Export character",
        description = "Write character stats and lightweight live state"
    )
    default boolean exportCharacter()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportQuests",
        name = "Export quests",
        description = "Write quest state snapshot"
    )
    default boolean exportQuests()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportDiaries",
        name = "Export diaries",
        description = "Write achievement diary completion state"
    )
    default boolean exportDiaries()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportCombatAchievements",
        name = "Export combat achievements",
        description = "Write combat achievement tier and task completion state"
    )
    default boolean exportCombatAchievements()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportBank",
        name = "Export bank",
        description = "Write bank snapshot on bank container changes"
    )
    default boolean exportBank()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportSeedVault",
        name = "Export seed vault",
        description = "Write seed vault snapshot on seed-vault container changes"
    )
    default boolean exportSeedVault()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportInventory",
        name = "Export inventory",
        description = "Write carried-inventory snapshot on inventory container changes"
    )
    default boolean exportInventory()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportEquipment",
        name = "Export equipment",
        description = "Write worn-equipment snapshot on equipment container changes"
    )
    default boolean exportEquipment()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportCollectionLog",
        name = "Export collection log",
        description = "Write collection log entries as you browse each page in-game"
    )
    default boolean exportCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Write detailed export trigger and result logs to exporter.log, status.json, and recent_events.json in the output folder. Takes effect immediately — no restart needed."
    )
    default boolean debugLogging()
    {
        return false;
    }
}
