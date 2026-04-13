package com.dzwnk.exporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CharacterStateExporterPluginTest
{
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(CharacterStateExporterPlugin.class);
        RuneLite.main(args);
    }
}
