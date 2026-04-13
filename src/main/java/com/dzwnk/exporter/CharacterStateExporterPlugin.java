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

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.WorldType;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
    name = "Character Export",
    description = "Saves stats, quests, diaries, combat achievements, bank, inventory, equipment, and collection log as local JSON files in .runelite/character-exporter/.",
    tags = {"export", "data", "bank", "quests", "stats", "seed", "inventory", "equipment", "diary", "combat", "collection", "log"}
)
public class CharacterStateExporterPlugin extends Plugin
{
    private static final String PLUGIN_VERSION = "0.6.0";
    private static final Path RUNELITE_DIR = RuneLite.RUNELITE_DIR.toPath().toAbsolutePath().normalize();
    private static final Path BASE_OUTPUT_DIR = RUNELITE_DIR.resolve("character-exporter").normalize();
    // Initialised in startUp() from the injected Gson to satisfy plugin-hub rules.
    private Gson prettyGson;

    private static final String CHARACTER_FILE = "character.json";
    private static final String QUESTS_FILE = "quests.json";
    private static final String DIARIES_FILE = "diaries.json";
    private static final String COMBAT_ACHIEVEMENTS_FILE = "combat_achievements.json";
    private static final String COLLECTION_LOG_FILE = "collection_log.json";
    private static final String STATUS_FILE = "status.json";
    private static final String RECENT_EVENTS_FILE = "recent_events.json";
    private static final String EXPORTER_LOG_FILE = "exporter.log";

    private static final long CHARACTER_EXPORT_INTERVAL_MS = 1000L;
    private static final long QUEST_EXPORT_INTERVAL_MS = 5000L;
    private static final long CONTAINER_EXPORT_INTERVAL_MS = 1000L;
    private static final long INVENTORY_EXPORT_INTERVAL_MS = 5000L;
    private static final long DIARY_EXPORT_INTERVAL_MS = 5000L;
    private static final long COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS = 5000L;
    private static final long COLLECTION_LOG_EXPORT_INTERVAL_MS = 2000L;
    private static final long OBSERVABILITY_WRITE_INTERVAL_MS = 1000L;

    // Achievement diary definitions: {name, easy_varbit, medium_varbit, hard_varbit, elite_varbit}
    // Order must match DIARY_COUNT_VARBITS and DIARY_WIDGET_TITLES
    private static final Object[][] DIARY_DEFINITIONS = {
        {"Ardougne", 4458, 4459, 4460, 4461},
        {"Desert", 4483, 4484, 4485, 4486},
        {"Falador", 4462, 4463, 4464, 4465},
        {"Fremennik", 4491, 4492, 4493, 4494},
        {"Kandarin", 4475, 4476, 4477, 4478},
        {"Karamja", 3578, 3599, 3611, 4566},
        {"Kourend & Kebos", 7925, 7926, 7927, 7928},
        {"Lumbridge & Draynor", 4495, 4496, 4497, 4498},
        {"Morytania", 4487, 4488, 4489, 4490},
        {"Varrock", 4479, 4480, 4481, 4482},
        {"Western Provinces", 4471, 4472, 4473, 4474},
        {"Wilderness", 4466, 4467, 4468, 4469},
    };
    private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

    // Task count varbits per diary area/tier — order matches DIARY_DEFINITIONS
    private static final int[][] DIARY_COUNT_VARBITS = {
        {6291, 6292, 6293, 6294}, // Ardougne
        {6307, 6308, 6309, 6310}, // Desert
        {6299, 6300, 6301, 6302}, // Falador
        {6323, 6324, 6325, 6326}, // Fremennik
        {6327, 6328, 6329, 6330}, // Kandarin
        {2423, 6288, 6289, 6290}, // Karamja
        {7933, 7934, 7935, 7936}, // Kourend & Kebos
        {6295, 6296, 6297, 6298}, // Lumbridge & Draynor
        {6315, 6316, 6317, 6318}, // Morytania
        {6303, 6304, 6305, 6306}, // Varrock
        {6319, 6320, 6321, 6322}, // Western Provinces
        {6311, 6312, 6313, 6314}, // Wilderness
    };

    // Normalised title text from the diary journal widget — order matches DIARY_DEFINITIONS
    private static final String[] DIARY_WIDGET_TITLES = {
        "ARDOUGNE_AREA_TASKS", "DESERT_TASKS", "FALADOR_AREA_TASKS",
        "FREMENNIK_TASKS", "KANDARIN_TASKS", "KARAMJA_AREA_TASKS",
        "KOUREND_&_KEBOS_TASKS", "LUMBRIDGE_&_DRAYNOR_TASKS", "MORYTANIA_TASKS",
        "VARROCK_TASKS", "WESTERN_AREA_TASKS", "WILDERNESS_AREA_TASKS",
    };

    // Karamja individual task varbits (legacy per-task varbits, Easy/Medium/Hard only)
    private static final String[] KARAMJA_EASY_TASK_NAMES = {
        "Pick 5 bananas from the plantation east of the volcano",
        "Use the rope swing to reach Moss Giant Island north-west of Karamja",
        "Mine gold from the rocks on the north-west peninsula",
        "Travel to Port Sarim by boat from Musa Point",
        "Travel to Ardougne by charter ship from Musa Point",
        "Explore Cairn Island to the west of Karamja",
        "Fish at the south end of Karamja island",
        "Collect 5 seaweed from Karamja",
        "Enter the TzHaar Fight Cave",
        "Kill a Jogre in Pothole Dungeon",
    };
    private static final int[] KARAMJA_EASY_TASK_VARBITS = {3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575};

    private static final String[] KARAMJA_MEDIUM_TASK_NAMES = {
        "Claim a ticket from the Agility Arena in Brimhaven",
        "Discover the hidden wall in the dungeon below the volcano",
        "Visit the Isle of Crandor via the dungeon below the volcano",
        "Use Vigroy and Hajedy's cart service",
        "Earn 100% favour in the Tai Bwo Wannai Cleanup",
        "Cook a spider on a stick",
        "Mine a red topaz from a gem rock",
        "Cut a log from a teak tree",
        "Cut a log from a mahogany tree",
        "Catch a karambwan",
        "Exchange gems for a machete with Gabooty",
        "Use the gnome glider to travel to Karamja",
        "Grow a healthy fruit tree in the patch near Brimhaven",
        "Trap a horned graahk",
        "Chop the vines to gain access to Brimhaven Dungeon",
        "Cross the lava using stepping stones within Brimhaven Dungeon",
        "Climb the stairs within Brimhaven Dungeon",
        "Charter the Lady of the Waves from Cairn Isle to Port Khazard",
        "Charter a ship from the shipyard in the far east of Karamja",
    };
    private static final int[] KARAMJA_MEDIUM_TASK_VARBITS = {
        3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588,
        3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597,
    };

    private static final String[] KARAMJA_HARD_TASK_NAMES = {
        "Become the champion of the Fight Pits",
        "Kill TzTok-Jad in the TzHaar Fight Cave",
        "Eat an Oomlie wrap",
        "Craft some nature runes from essence",
        "Cook a karambwan thoroughly",
        "Kill a deathwing in the dungeon under the Kharazi Jungle",
        "Use the crossbow shortcut south of the volcano",
        "Collect 5 palm leaves",
        "Be assigned a Slayer task by Duradel in Shilo Village",
        "Kill a metal dragon in Brimhaven Dungeon",
    };
    private static final int[] KARAMJA_HARD_TASK_VARBITS = {3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609};

    // Combat achievement struct param IDs
    private static final int CA_PARAM_NAME = 1308;
    private static final int CA_PARAM_TIER = 1310;

    // Combat achievement varPlayers (20, covering all 637 tasks as bit-packed completion flags)
    private static final int[] COMBAT_TASK_VARPS = {
        3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
        3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496, 4721,
    };

    // Combat achievement tier names and completion varbits
    private static final String[] CA_TIER_NAMES = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
    private static final int[] CA_TIER_COMPLETE_VARBITS = {12863, 12864, 12865, 12866, 12867, 12868};
    private static final int[] CA_TASK_COUNT_VARBITS = {12885, 12886, 12887, 12888, 12889, 12890};

    // Combat achievement struct IDs: index = sortId (0–636), value = game cache structId
    // Source: osrs-reldo/task-json-store tasks/COMBAT.min.json
    private static final int[] COMBAT_STRUCT_IDS = {
        327, 3164, 3168, 3172, 3173, 3175, 3177, 3179, 3180, 3181,     // 0–9
        3216, 3220, 3538, 3260, 3261, 3262, 3269, 3271, 3274, 3302,     // 10–19
        3314, 3600, 3317, 3346, 3419, 3421, 3422, 3425, 3427, 3493,     // 20–29
        3496, 3497, 3500, 834, 836, 2625, 2628, 3060, 6382, 6387,       // 30–39
        6408, 3539, 3165, 3166, 3167, 3174, 3176, 3178, 3186, 3188,     // 40–49
        3201, 3203, 3210, 3212, 3213, 3217, 3218, 3219, 3237, 3270,     // 50–59
        3272, 3273, 3303, 3304, 3305, 3306, 3313, 3318, 3322, 3337,     // 60–69
        3341, 3344, 3347, 3350, 3363, 3373, 3420, 3423, 3424, 3184,     // 70–79
        3494, 3499, 835, 837, 838, 917, 918, 925, 926, 1024,            // 80–89
        1030, 1035, 1040, 2577, 2623, 3814, 6383, 6384, 6385, 6409,     // 90–99
        6411, 328, 380, 399, 404, 410, 550, 3156, 3158, 3160,           // 100–109
        3169, 3170, 3182, 3185, 3197, 3199, 3200, 3202, 3204, 3211,     // 110–119
        3222, 3224, 3225, 3226, 3227, 3228, 3263, 3264, 3265, 3297,     // 120–129
        3299, 3307, 3308, 3309, 3310, 3319, 3321, 3323, 3338, 3342,     // 130–139
        3348, 3349, 3351, 3353, 3358, 3360, 3361, 3364, 3374, 3537,     // 140–149
        3404, 3406, 3426, 3472, 3474, 3475, 3479, 3495, 3498, 4433,     // 150–159
        4434, 4436, 4437, 4483, 920, 921, 924, 927, 928, 890,           // 160–169
        919, 1025, 1028, 1031, 1036, 1037, 1039, 1042, 1043, 2535,      // 170–179
        2626, 2627, 2630, 6386, 6388, 6412, 366, 388, 400, 405,         // 180–189
        443, 3157, 3159, 3171, 3183, 3187, 3189, 3191, 3194, 3195,      // 190–199
        3196, 3198, 3205, 3207, 3208, 3209, 3214, 3215, 3221, 3223,     // 200–209
        3229, 3230, 3232, 3235, 3238, 3241, 3249, 3251, 3254, 3255,     // 210–219
        3256, 3259, 3266, 3267, 3275, 3287, 3293, 3298, 3300, 3301,     // 220–229
        3311, 3316, 3320, 3324, 3327, 3329, 3331, 3334, 3339, 3340,     // 230–239
        3343, 3345, 3352, 3356, 3359, 3362, 3365, 3367, 3368, 3370,     // 240–249
        3375, 3376, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533,     // 250–259
        3534, 3535, 3377, 3401, 3402, 3403, 3405, 3407, 3408, 3413,     // 260–269
        3411, 3432, 3439, 3442, 3443, 3448, 3447, 3451, 3452, 3454,     // 270–279
        3456, 3460, 3461, 3468, 3469, 3470, 3471, 3473, 3477, 3476,     // 280–289
        3481, 3289, 3501, 3504, 3507, 304, 4137, 4139, 4435, 4440,      // 290–299
        4441, 4402, 4403, 4397, 4421, 4423, 4430, 4404, 4407, 4410,     // 300–309
        4424, 4480, 4484, 4478, 4487, 826, 791, 802, 813, 821,          // 310–319
        786, 799, 810, 808, 916, 935, 938, 943, 970, 971,               // 320–329
        972, 974, 1000, 1008, 1002, 1032, 1026, 1027, 1038, 1041,       // 330–339
        3019, 4739, 4809, 4861, 999, 4558, 4566, 6410, 446, 602,        // 340–349
        3190, 3192, 3193, 3206, 3231, 3233, 3236, 3239, 3242, 3243,     // 350–359
        3245, 3247, 3250, 3252, 3253, 3257, 3268, 3276, 3277, 3278,     // 360–369
        3279, 3280, 3281, 3282, 3284, 3286, 3288, 3290, 3292, 3295,     // 370–379
        3312, 3325, 3328, 3332, 3335, 3355, 3366, 3369, 3371, 3390,     // 380–389
        3384, 3380, 3385, 3386, 3382, 3387, 3391, 3388, 3381, 3389,     // 390–399
        3393, 3395, 3397, 3399, 3378, 3409, 3410, 3412, 3416, 3418,     // 400–409
        3428, 3430, 3431, 3433, 3435, 3437, 3440, 3444, 3445, 3446,     // 410–419
        3449, 3450, 3453, 3455, 3457, 3458, 3459, 3462, 3466, 3464,     // 420–429
        3486, 3502, 3505, 3508, 3509, 3511, 3521, 3536, 922, 940,       // 430–439
        958, 959, 967, 4138, 4140, 4141, 4142, 4143, 4147, 4396,        // 440–449
        4438, 4439, 4393, 4419, 4442, 4425, 4427, 4428, 4429, 4431,     // 450–459
        4432, 4398, 4399, 4400, 4401, 4405, 4408, 4411, 4413, 4414,     // 460–469
        4416, 4477, 4479, 4481, 4485, 4488, 4486, 807, 827, 792,        // 470–479
        803, 816, 822, 789, 800, 811, 830, 829, 819, 818,               // 480–489
        798, 794, 929, 933, 937, 939, 944, 973, 1001, 1003,             // 490–499
        1005, 1007, 1010, 1011, 1029, 1033, 4740, 4810, 4857, 4859,     // 500–509
        4860, 4556, 4559, 4560, 4565, 4567, 718, 1690, 3161, 3162,      // 510–519
        3163, 3234, 3240, 3244, 3246, 3248, 3258, 3283, 3285, 3291,     // 520–529
        3294, 3296, 3326, 3330, 3333, 3336, 3354, 3357, 3372, 3379,     // 530–539
        3383, 3392, 3394, 3396, 3398, 3400, 3414, 3415, 3417, 3429,     // 540–549
        3434, 3436, 3438, 3441, 3463, 3467, 3465, 3478, 3480, 3482,     // 550–559
        3483, 3484, 3485, 3487, 3488, 3489, 3490, 3491, 3492, 3503,     // 560–569
        3506, 3510, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519,     // 570–579
        3520, 3522, 3523, 3524, 3525, 931, 932, 950, 2860, 4144,        // 580–589
        4145, 4146, 4394, 4395, 4418, 4420, 4443, 4422, 4426, 4406,     // 590–599
        4409, 4412, 4415, 4417, 4489, 4482, 828, 793, 804, 817,         // 600–609
        825, 790, 801, 812, 795, 820, 809, 831, 930, 934,               // 610–619
        936, 942, 945, 1004, 1006, 1009, 1034, 4863, 4856, 4862,        // 620–629
        4557, 4561, 4562, 4563, 4564, 4568, 4569,                       // 630–636
    };

    // Collection log interface and script IDs
    private static final int COLLECTION_LOG_INTERFACE_ID = 621;
    private static final int COLLECTION_LOG_ACTIVE_TAB_VARBIT = 6905;
    private static final String[] COLLECTION_LOG_TABS = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
    // Script 2729 = COLLECTION_DRAW_LIST (fires when entry is selected); 2730/2731 tried as fallbacks
    private static final int[] COLLECTION_LOG_SCRIPT_IDS = {2729, 2730, 2731};
    // Candidate child IDs for the entry title widget (searched in order)
    private static final int[] COLLECTION_LOG_TITLE_CHILDREN = {19, 18, 20, 21, 22};
    // Candidate child IDs for the items container (searched in order)
    private static final int[] COLLECTION_LOG_ITEMS_CHILDREN = {36, 37, 35, 38, 34};

    // Diary journal interface ID (shared with quest journal)
    private static final int JOURNAL_INTERFACE_ID = 741;
    private static final int JOURNAL_TITLE_CHILD = 2;
    private static final int JOURNAL_TEXTLAYER_CHILD = 3;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final long EXPORTER_LOG_MAX_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_RECENT_EVENTS = 200;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private CharacterStateExporterConfig config;

    @Inject
    private Gson gson;

    private final Map<String, AtomicLong> lastExportAt = new ConcurrentHashMap<>();
    private final Map<String, String> lastStablePayloadByFile = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> datasetStatus = new ConcurrentHashMap<>();
    private final Map<String, Object> readinessSnapshot = new ConcurrentHashMap<>();
    private final Set<String> warnedFailureKeys = ConcurrentHashMap.newKeySet();
    private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();
    private final AtomicLong lastObservabilityWriteAt = new AtomicLong(0L);
    private final String sessionId = UUID.randomUUID().toString();

    // Combat achievement task cache: loaded from game cache structs at login
    private final List<CombatTask> combatTaskCache = new ArrayList<>();

    // Diary journal widget-scraped task data: area name → tier → list of {name, complete}
    private final Map<String, Map<String, List<Map<String, Object>>>> diaryWidgetCache = new ConcurrentHashMap<>();

    // Collection log accumulated data: entry name → {tab, items, counts, last_scraped}
    private final Map<String, Map<String, Object>> collectionLogCache = new ConcurrentHashMap<>();

    private volatile ExecutorService writer;
    private volatile boolean pendingInitialCharacterExport;
    private volatile boolean writesEnabled;
    private volatile Path accountOutputDir;
    private volatile String currentAccountName;
    private CharacterStateExporterPanel panel;
    private NavigationButton navButton;

    @Provides
    CharacterStateExporterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CharacterStateExporterConfig.class);
    }

    @Override
    protected void startUp()
    {
        prettyGson = gson.newBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

        writer = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("character-state-exporter-%d")
                .build()
        );
        writesEnabled = initializeBaseOutputDirectory();

        panel = new CharacterStateExporterPanel(this::manualExportAll);
        navButton = NavigationButton.builder()
            .tooltip("Character Export")
            .icon(createSidebarIcon())
            .priority(10)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        debug("startup", "export toggles character={} quests={} diaries={} combatAchievements={} bank={} seedVault={} inventory={} equipment={}",
            config.exportCharacter(), config.exportQuests(), config.exportDiaries(), config.exportCombatAchievements(),
            config.exportBank(), config.exportSeedVault(), config.exportInventory(), config.exportEquipment());

        if (writesEnabled && config.debugLogging())
        {
            submitWriter(this::resetExporterLogSync);
        }

        recordEvent("startup", "plugin_started", ImmutableMap.of(
            "writes_enabled", writesEnabled
        ));

        clientThread.invokeLater(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                pendingInitialCharacterExport = true;
                resolveAccount();
                loadCombatTaskCache();
                restoreCollectionLogCache();
            }

            updateReadinessSnapshot();
            requestObservabilityWrite(true);

            if (pendingInitialCharacterExport)
            {
                exportQuestSnapshot("startup");
                exportDiarySnapshot("startup");
                exportCombatAchievementSnapshot("startup");
            }
        });
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(navButton);
        debug("shutdown", "plugin shutting down");
        recordEvent("shutdown", "plugin_stopped", ImmutableMap.of());
        requestObservabilityWrite(true);

        ExecutorService active = writer;
        writer = null;
        if (active == null)
        {
            return;
        }

        active.shutdown();
        try
        {
            if (!active.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                log.warn("Character State Exporter writer did not drain within {}s; forcing shutdown",
                    SHUTDOWN_TIMEOUT_SECONDS);
                active.shutdownNow();
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            active.shutdownNow();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gameState = event.getGameState();
        debug("game_state", "GameStateChanged -> {}", gameState);
        recordEvent("game_state", "game_state_changed", ImmutableMap.of("game_state", gameState.name()));

        if (gameState == GameState.LOGGED_IN)
        {
            pendingInitialCharacterExport = true;
            resolveAccount();
            clientThread.invokeLater(() ->
            {
                loadCombatTaskCache();
                restoreCollectionLogCache();
                exportQuestSnapshot("game_state_changed");
                exportDiarySnapshot("game_state_changed");
                exportCombatAchievementSnapshot("game_state_changed");
            });
        }
        else if (gameState == GameState.LOGIN_SCREEN)
        {
            accountOutputDir = null;
            currentAccountName = null;
            combatTaskCache.clear();
            diaryWidgetCache.clear();
            collectionLogCache.clear();
            panel.clearAccount();
            lastStablePayloadByFile.clear();
        }

        updateReadinessSnapshot();
        requestObservabilityWrite(true);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        updateReadinessSnapshot();

        if (currentAccountName == null)
        {
            resolveAccount();
        }

        if (!pendingInitialCharacterExport || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (!isCharacterStateReady())
        {
            debug("character", "Waiting for character state to become ready on game tick");
            return;
        }

        pendingInitialCharacterExport = false;
        updateReadinessSnapshot();
        exportCharacterSnapshot("initial_game_tick");
        requestObservabilityWrite(false);
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        updateReadinessSnapshot();
        debug("stat_changed", "StatChanged skill={} real={} boosted={} xp={}",
            event.getSkill(), event.getLevel(), event.getBoostedLevel(), event.getXp());
        exportCharacterSnapshot("stat_changed", CHARACTER_EXPORT_INTERVAL_MS);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        boolean anyExportNeeded = false;

        if (config.exportQuests())
        {
            AtomicLong lastRun = lastExportAt.get(QUESTS_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= QUEST_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (config.exportDiaries())
        {
            AtomicLong lastRun = lastExportAt.get(DIARIES_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= DIARY_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (config.exportCombatAchievements())
        {
            AtomicLong lastRun = lastExportAt.get(COMBAT_ACHIEVEMENTS_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (!anyExportNeeded)
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            exportQuestSnapshot("varbit_changed", QUEST_EXPORT_INTERVAL_MS);
            exportDiarySnapshot("varbit_changed", DIARY_EXPORT_INTERVAL_MS);
            exportCombatAchievementSnapshot("varbit_changed", COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS);
        });
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        int groupId = event.getGroupId();
        if (groupId == JOURNAL_INTERFACE_ID)
        {
            clientThread.invokeLater(this::scrapeDiaryJournal);
        }
        else if (groupId == COLLECTION_LOG_INTERFACE_ID)
        {
            // Fires when the collection log interface first opens; scrape whatever entry is visible
            clientThread.invokeLater(this::scrapeCollectionLogPage);
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        int scriptId = event.getScriptId();
        for (int id : COLLECTION_LOG_SCRIPT_IDS)
        {
            if (scriptId == id)
            {
                clientThread.invokeLater(this::scrapeCollectionLogPage);
                return;
            }
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        String command = event.getCommand();
        if (command == null || !command.equalsIgnoreCase("charexport"))
        {
            return;
        }

        String[] arguments = event.getArguments();
        String target = arguments.length > 0 ? arguments[0].toLowerCase() : "all";
        debug("manual_command", "Received ::charexport target={}", target);
        recordEvent("manual_command", "manual_command", ImmutableMap.of("target", target));

        ContainerKind kind = ContainerKind.fromCommand(target);
        if (kind != null)
        {
            exportContainerSnapshot(kind, "manual_command");
            return;
        }

        switch (target)
        {
            case "character":
                exportCharacterSnapshot("manual_command");
                return;
            case "quests":
                clientThread.invokeLater(() -> exportQuestSnapshot("manual_command"));
                return;
            case "diaries":
                clientThread.invokeLater(() -> exportDiarySnapshot("manual_command"));
                return;
            case "combat":
            case "combat_achievements":
                clientThread.invokeLater(() -> exportCombatAchievementSnapshot("manual_command"));
                return;
            case "log":
            case "collection_log":
            case "collectionlog":
                clientThread.invokeLater(() -> writeCollectionLogSnapshot("manual_command"));
                return;
            case "all":
                exportCharacterSnapshot("manual_command");
                for (ContainerKind containerKind : ContainerKind.values())
                {
                    exportContainerSnapshot(containerKind, "manual_command");
                }
                clientThread.invokeLater(() ->
                {
                    exportQuestSnapshot("manual_command");
                    exportDiarySnapshot("manual_command");
                    exportCombatAchievementSnapshot("manual_command");
                    writeCollectionLogSnapshot("manual_command");
                });
                return;
            default:
                debug("manual_command", "Unknown ::charexport target={}", target);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer changedContainer = event.getItemContainer();
        if (changedContainer == null)
        {
            debug("container_changed", "ItemContainerChanged with null container");
            updateDatasetStatus("container", "skipped", "null_container", null);
            requestObservabilityWrite(false);
            return;
        }

        for (ContainerKind kind : ContainerKind.values())
        {
            if (!kind.isEnabled(config))
            {
                continue;
            }

            ItemContainer liveContainer = client.getItemContainer(kind.getInventoryId());
            if (liveContainer != null && changedContainer == liveContainer)
            {
                debug("container_changed", "Matched {} container", kind.getDatasetKey());
                exportContainerSnapshot(kind, "item_container_changed", kind.getMinimumIntervalMs());
                return;
            }
        }
    }

    private void resolveAccount()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null || player.getName().trim().isEmpty())
        {
            return;
        }

        String name = sanitizeFileName(player.getName().trim());
        if (name.equals(currentAccountName))
        {
            return;
        }

        currentAccountName = name;
        Path dir = BASE_OUTPUT_DIR.resolve(name).normalize();

        if (!dir.startsWith(BASE_OUTPUT_DIR))
        {
            log.warn("Character State Exporter: account directory escapes base dir: {}", dir);
            return;
        }

        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException ex)
        {
            log.warn("Character State Exporter: could not create account directory {}", dir, ex);
            return;
        }

        accountOutputDir = dir;
        panel.setAccount(player.getName().trim(), dir);
        panel.restoreFromDisk(dir);
        debug("account", "Resolved account directory for {}", name);
    }

    private static String sanitizeFileName(String name)
    {
        return name.replaceAll("[^a-zA-Z0-9_\\- ]", "_");
    }

    private void exportCharacterSnapshot(String reason)
    {
        exportCharacterSnapshot(reason, 0L);
    }

    private void exportCharacterSnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportCharacter() || gameState != GameState.LOGGED_IN)
        {
            debug("character", "Skipped character export reason={} enabled={} gameState={}",
                reason, config.exportCharacter(), gameState);
            updateDatasetStatus("character", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason,
                "enabled", config.exportCharacter(),
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("character", "Not ready", "Stats are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!isCharacterStateReady())
        {
            debug("character", "Skipped character export reason={} because character state is not ready", reason);
            pendingInitialCharacterExport = true;
            updateReadinessSnapshot();
            updateDatasetStatus("character", "waiting", "character_state_not_ready", ImmutableMap.of(
                "reason", reason
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("character", "Not ready", "Stats are not ready yet. Try again in a moment.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(CHARACTER_FILE, minimumIntervalMs))
        {
            debug("character", "Throttled character export reason={} minimumIntervalMs={}",
                reason, minimumIntervalMs);
            updateDatasetStatus("character", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("character");
            }
            requestObservabilityWrite(false);
            return;
        }

        Player player = client.getLocalPlayer();
        Map<String, Object> payload = basePayload(reason);
        payload.put("account_name", player != null ? player.getName() : null);
        payload.put("world", client.getWorld());
        payload.put("game_state", gameState.name());
        payload.put("world_types", worldTypes());
        payload.put("stats", buildStats());

        debug("character", "Prepared character export reason={} world={}", reason, client.getWorld());
        updateDatasetStatus("character", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "world", client.getWorld()
        ));
        writeJson("character", CHARACTER_FILE, payload);
    }

    private void exportQuestSnapshot(String reason)
    {
        exportQuestSnapshot(reason, 0L);
    }

    private void exportQuestSnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportQuests() || gameState != GameState.LOGGED_IN)
        {
            debug("quests", "Skipped quest export reason={} enabled={} gameState={}",
                reason, config.exportQuests(), gameState);
            updateDatasetStatus("quests", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason,
                "enabled", config.exportQuests(),
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("quests", "Not ready", "Quests are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(QUESTS_FILE, minimumIntervalMs))
        {
            debug("quests", "Throttled quest export reason={} minimumIntervalMs={}",
                reason, minimumIntervalMs);
            updateDatasetStatus("quests", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("quests");
            }
            requestObservabilityWrite(false);
            return;
        }

        List<Map<String, Object>> quests = new ArrayList<>();
        int finished = 0;
        int inProgress = 0;
        int notStarted = 0;

        for (Quest quest : Quest.values())
        {
            QuestState state = quest.getState(client);
            if (state == QuestState.FINISHED)
            {
                finished++;
            }
            else if (state == QuestState.IN_PROGRESS)
            {
                inProgress++;
            }
            else
            {
                notStarted++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", quest.getId());
            row.put("name", quest.getName());
            row.put("state", state.name());
            quests.add(row);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("summary", ImmutableMap.of(
            "finished", finished,
            "in_progress", inProgress,
            "not_started", notStarted
        ));
        payload.put("quests", quests);

        debug("quests", "Prepared quest export reason={} finished={} inProgress={} notStarted={}",
            reason, finished, inProgress, notStarted);
        updateDatasetStatus("quests", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "finished", finished,
            "in_progress", inProgress,
            "not_started", notStarted
        ));
        writeJson("quests", QUESTS_FILE, payload);
    }

    private void exportDiarySnapshot(String reason)
    {
        exportDiarySnapshot(reason, 0L);
    }

    private void exportDiarySnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportDiaries() || gameState != GameState.LOGGED_IN)
        {
            debug("diaries", "Skipped diary export reason={} enabled={} gameState={}",
                reason, config.exportDiaries(), gameState);
            updateDatasetStatus("diaries", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason, "enabled", config.exportDiaries(), "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("diaries", "Not ready", "Diaries are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(DIARIES_FILE, minimumIntervalMs))
        {
            debug("diaries", "Throttled diary export reason={} minimumIntervalMs={}", reason, minimumIntervalMs);
            updateDatasetStatus("diaries", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason, "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("diaries");
            }
            requestObservabilityWrite(false);
            return;
        }

        Map<String, Object> diaries = new LinkedHashMap<>();
        int tiersComplete = 0;
        int tiersPossible = 0;

        for (int areaIdx = 0; areaIdx < DIARY_DEFINITIONS.length; areaIdx++)
        {
            Object[] area = DIARY_DEFINITIONS[areaIdx];
            String areaName = (String) area[0];
            boolean isKaramja = "Karamja".equals(areaName);
            Map<String, Object> tierMap = new LinkedHashMap<>();

            // Widget-scraped named task data for this area (if available)
            Map<String, List<Map<String, Object>>> widgetAreaData = diaryWidgetCache.get(areaName);

            for (int t = 0; t < DIARY_TIERS.length; t++)
            {
                String tierName = DIARY_TIERS[t];
                int completionVarbit = (int) area[t + 1];
                boolean tierComplete = client.getVarbitValue(completionVarbit) == 1;
                int tasksDone = client.getVarbitValue(DIARY_COUNT_VARBITS[areaIdx][t]);

                Map<String, Object> tierData = new LinkedHashMap<>();
                tierData.put("complete", tierComplete);
                tierData.put("tasks_done", tasksDone);

                // Add named tasks if available
                if (isKaramja && t < 3)
                {
                    // Karamja Easy/Medium/Hard have individual per-task varbits
                    tierData.put("tasks", buildKaramjaTaskList(t));
                }
                else if (widgetAreaData != null && widgetAreaData.containsKey(tierName))
                {
                    // Widget-scraped when player opened the diary journal
                    tierData.put("tasks", widgetAreaData.get(tierName));
                }

                tierMap.put(tierName, tierData);
                tiersPossible++;
                if (tierComplete)
                {
                    tiersComplete++;
                }
            }
            diaries.put(areaName, tierMap);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("summary", ImmutableMap.of(
            "tiers_complete", tiersComplete,
            "tiers_possible", tiersPossible,
            "note", "tasks_done counts are always available; named task lists require opening the diary journal in-game"
        ));
        payload.put("diaries", diaries);

        debug("diaries", "Prepared diary export reason={} tiersComplete={}/{}", reason, tiersComplete, tiersPossible);
        updateDatasetStatus("diaries", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason, "tiers_complete", tiersComplete, "tiers_possible", tiersPossible
        ));
        writeJson("diaries", DIARIES_FILE, payload);
    }

    private List<Map<String, Object>> buildKaramjaTaskList(int tier)
    {
        String[] names;
        int[] varbits;
        if (tier == 0)
        {
            names = KARAMJA_EASY_TASK_NAMES;
            varbits = KARAMJA_EASY_TASK_VARBITS;
        }
        else if (tier == 1)
        {
            names = KARAMJA_MEDIUM_TASK_NAMES;
            varbits = KARAMJA_MEDIUM_TASK_VARBITS;
        }
        else
        {
            names = KARAMJA_HARD_TASK_NAMES;
            varbits = KARAMJA_HARD_TASK_VARBITS;
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
        {
            boolean complete = client.getVarbitValue(varbits[i]) > 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", names[i]);
            row.put("complete", complete);
            tasks.add(row);
        }
        return tasks;
    }

    private void exportCombatAchievementSnapshot(String reason)
    {
        exportCombatAchievementSnapshot(reason, 0L);
    }

    private void exportCombatAchievementSnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportCombatAchievements() || gameState != GameState.LOGGED_IN)
        {
            debug("combat_achievements", "Skipped combat achievement export reason={} enabled={} gameState={}",
                reason, config.exportCombatAchievements(), gameState);
            updateDatasetStatus("combat_achievements", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason, "enabled", config.exportCombatAchievements(), "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("combat_achievements", "Not ready", "Combat achievements are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(COMBAT_ACHIEVEMENTS_FILE, minimumIntervalMs))
        {
            debug("combat_achievements", "Throttled combat achievement export reason={} minimumIntervalMs={}",
                reason, minimumIntervalMs);
            updateDatasetStatus("combat_achievements", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason, "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("combat_achievements");
            }
            requestObservabilityWrite(false);
            return;
        }

        // Ensure task cache is loaded (may not be if plugin was hot-loaded mid-session)
        if (combatTaskCache.isEmpty())
        {
            loadCombatTaskCache();
        }

        // Build per-tier task lists
        Map<String, List<Map<String, Object>>> tierTaskLists = new LinkedHashMap<>();
        for (String tier : CA_TIER_NAMES)
        {
            tierTaskLists.put(tier, new ArrayList<>());
        }

        for (CombatTask task : combatTaskCache)
        {
            if (task.tier < 1 || task.tier > CA_TIER_NAMES.length)
            {
                continue;
            }
            String tierName = CA_TIER_NAMES[task.tier - 1];

            int varpIndex = task.sortId / 32;
            int bitIndex = task.sortId % 32;
            boolean complete = false;
            if (varpIndex < COMBAT_TASK_VARPS.length)
            {
                int varpValue = client.getVarpValue(COMBAT_TASK_VARPS[varpIndex]);
                complete = (varpValue & (1 << bitIndex)) != 0;
            }

            Map<String, Object> taskRow = new LinkedHashMap<>();
            taskRow.put("id", task.sortId);
            taskRow.put("name", task.name);
            taskRow.put("complete", complete);
            tierTaskLists.get(tierName).add(taskRow);
        }

        Map<String, Object> tiers = new LinkedHashMap<>();
        int totalTasksDone = 0;
        int totalTiersComplete = 0;

        for (int i = 0; i < CA_TIER_NAMES.length; i++)
        {
            String tierName = CA_TIER_NAMES[i];
            boolean tierComplete = client.getVarbitValue(CA_TIER_COMPLETE_VARBITS[i]) == 1;
            List<Map<String, Object>> tasks = tierTaskLists.get(tierName);

            int tierTasksDone = 0;
            for (Map<String, Object> t : tasks)
            {
                if (Boolean.TRUE.equals(t.get("complete")))
                {
                    tierTasksDone++;
                }
            }
            totalTasksDone += tierTasksDone;

            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("complete", tierComplete);
            tier.put("tasks_completed", tierTasksDone);
            tier.put("tasks_total", tasks.size());
            tier.put("tasks", tasks);
            tiers.put(tierName, tier);

            if (tierComplete)
            {
                totalTiersComplete++;
            }
        }

        // If cache is empty (struct loading failed), fall back to varbit counts
        boolean namedDataAvailable = !combatTaskCache.isEmpty();

        Map<String, Object> payload = basePayload(reason);
        payload.put("summary", ImmutableMap.of(
            "total_tasks_completed", totalTasksDone,
            "total_tiers_completed", totalTiersComplete,
            "named_data_available", namedDataAvailable
        ));
        payload.put("tiers", tiers);

        debug("combat_achievements", "Prepared combat achievement export reason={} tasks={} tiersComplete={} named={}",
            reason, totalTasksDone, totalTiersComplete, namedDataAvailable);
        updateDatasetStatus("combat_achievements", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason, "total_tasks_completed", totalTasksDone, "total_tiers_completed", totalTiersComplete
        ));
        writeJson("combat_achievements", COMBAT_ACHIEVEMENTS_FILE, payload);
    }

    private void loadCombatTaskCache()
    {
        combatTaskCache.clear();
        int loaded = 0;
        for (int sortId = 0; sortId < COMBAT_STRUCT_IDS.length; sortId++)
        {
            int structId = COMBAT_STRUCT_IDS[sortId];
            StructComposition struct = client.getStructComposition(structId);
            if (struct == null)
            {
                continue;
            }
            String name = struct.getStringValue(CA_PARAM_NAME);
            if (name == null || name.isEmpty())
            {
                name = "Task " + sortId;
            }
            int tier = struct.getIntValue(CA_PARAM_TIER);
            if (tier < 1 || tier > CA_TIER_NAMES.length)
            {
                tier = 1;
            }
            combatTaskCache.add(new CombatTask(sortId, name, tier));
            loaded++;
        }
        debug("combat", "Loaded {} / {} combat tasks from game cache", loaded, COMBAT_STRUCT_IDS.length);
    }

    private void scrapeDiaryJournal()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Widget titleWidget = client.getWidget(JOURNAL_INTERFACE_ID, JOURNAL_TITLE_CHILD);
        if (titleWidget == null)
        {
            return;
        }

        String titleNorm = stripWidgetTags(titleWidget.getText()).trim().toUpperCase().replace(' ', '_');
        if (!titleNorm.startsWith("ACHIEVEMENT_DIARY"))
        {
            return;
        }

        Widget textLayer = client.getWidget(JOURNAL_INTERFACE_ID, JOURNAL_TEXTLAYER_CHILD);
        if (textLayer == null)
        {
            return;
        }

        Widget[] children = textLayer.getStaticChildren();
        if (children == null || children.length == 0)
        {
            children = textLayer.getDynamicChildren();
        }
        if (children == null || children.length == 0)
        {
            return;
        }

        // Identify which area this page is for from the first text child
        String areaName = null;
        for (Widget child : children)
        {
            String text = child.getText();
            if (text == null || text.isEmpty())
            {
                continue;
            }
            String norm = stripWidgetTags(text).trim().toUpperCase().replace(' ', '_');
            for (int i = 0; i < DIARY_WIDGET_TITLES.length; i++)
            {
                if (DIARY_WIDGET_TITLES[i].equals(norm))
                {
                    areaName = (String) DIARY_DEFINITIONS[i][0];
                    break;
                }
            }
            if (areaName != null)
            {
                break;
            }
        }

        if (areaName == null)
        {
            return;
        }

        // Parse task lines grouped by tier header
        Map<String, List<Map<String, Object>>> tierTasks = new LinkedHashMap<>();
        for (String tier : DIARY_TIERS)
        {
            tierTasks.put(tier, new ArrayList<>());
        }

        String currentTier = null;
        boolean foundAreaTitle = false;

        for (Widget child : children)
        {
            String raw = child.getText();
            if (raw == null || raw.isEmpty())
            {
                continue;
            }

            String clean = stripWidgetTags(raw).trim();
            if (clean.isEmpty())
            {
                continue;
            }

            // Skip the area title line
            String norm = clean.toUpperCase().replace(' ', '_');
            boolean isAreaTitle = false;
            for (String title : DIARY_WIDGET_TITLES)
            {
                if (title.equals(norm))
                {
                    isAreaTitle = true;
                    foundAreaTitle = true;
                    break;
                }
            }
            if (isAreaTitle)
            {
                continue;
            }

            if (!foundAreaTitle)
            {
                continue;
            }

            // Check for tier header
            String upperClean = clean.toUpperCase();
            if (upperClean.startsWith("EASY"))
            {
                currentTier = "easy";
                continue;
            }
            else if (upperClean.startsWith("MEDIUM"))
            {
                currentTier = "medium";
                continue;
            }
            else if (upperClean.startsWith("HARD"))
            {
                currentTier = "hard";
                continue;
            }
            else if (upperClean.startsWith("ELITE"))
            {
                currentTier = "elite";
                continue;
            }

            // It is a task line
            if (currentTier != null)
            {
                boolean complete = raw.contains("<str>");
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("name", clean);
                task.put("complete", complete);
                tierTasks.get(currentTier).add(task);
            }
        }

        // Only store tiers that have at least one task
        Map<String, List<Map<String, Object>>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tierTasks.entrySet())
        {
            if (!entry.getValue().isEmpty())
            {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }

        if (!filtered.isEmpty())
        {
            diaryWidgetCache.put(areaName, filtered);
            debug("diaries", "Scraped diary journal for {} — {} tiers with tasks", areaName, filtered.size());
            exportDiarySnapshot("diary_journal_scraped", DIARY_EXPORT_INTERVAL_MS);
        }
    }

    private void scrapeCollectionLogPage()
    {
        if (!config.exportCollectionLog() || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        // Resolve the entry title from the first candidate child that yields non-empty text.
        // We try the widget's own text first, then its static children, then its dynamic children.
        String entryName = null;
        int foundTitleChild = -1;
        for (int childId : COLLECTION_LOG_TITLE_CHILDREN)
        {
            Widget w = client.getWidget(COLLECTION_LOG_INTERFACE_ID, childId);
            if (w == null)
            {
                continue;
            }

            // Direct text on the widget
            String text = w.getText();
            if (text != null && !text.trim().isEmpty())
            {
                entryName = stripWidgetTags(text).trim();
                foundTitleChild = childId;
                break;
            }

            // Static children
            Widget[] statics = w.getStaticChildren();
            if (statics != null)
            {
                for (Widget child : statics)
                {
                    text = child.getText();
                    if (text != null && !text.trim().isEmpty())
                    {
                        entryName = stripWidgetTags(text).trim();
                        foundTitleChild = childId;
                        break;
                    }
                }
            }
            if (entryName != null)
            {
                break;
            }

            // Dynamic children
            Widget[] dynamics = w.getDynamicChildren();
            if (dynamics != null)
            {
                for (Widget child : dynamics)
                {
                    text = child.getText();
                    if (text != null && !text.trim().isEmpty())
                    {
                        entryName = stripWidgetTags(text).trim();
                        foundTitleChild = childId;
                        break;
                    }
                }
            }
            if (entryName != null)
            {
                break;
            }
        }

        if (entryName == null || entryName.isEmpty())
        {
            recordEvent("collection_log", "collection_log_title_not_found", ImmutableMap.of(
                "note", "interface may not be open or title child IDs need updating",
                "children_tried", Arrays.toString(COLLECTION_LOG_TITLE_CHILDREN)
            ));
            return;
        }

        recordEvent("collection_log", "collection_log_title_found", ImmutableMap.of(
            "entry", entryName, "title_child", foundTitleChild
        ));

        // Determine the active tab from varbit
        int tabIndex = client.getVarbitValue(COLLECTION_LOG_ACTIVE_TAB_VARBIT);
        String tabName = (tabIndex >= 0 && tabIndex < COLLECTION_LOG_TABS.length)
            ? COLLECTION_LOG_TABS[tabIndex] : "Other";

        // Find the items container — the first candidate child that has dynamic children with item IDs
        List<Map<String, Object>> items = null;
        int foundItemsChild = -1;
        for (int childId : COLLECTION_LOG_ITEMS_CHILDREN)
        {
            Widget w = client.getWidget(COLLECTION_LOG_INTERFACE_ID, childId);
            if (w == null)
            {
                continue;
            }
            Widget[] dynamics = w.getDynamicChildren();
            if (dynamics == null || dynamics.length == 0)
            {
                continue;
            }
            List<Map<String, Object>> candidates = extractCollectionLogItems(dynamics);
            if (!candidates.isEmpty())
            {
                items = candidates;
                foundItemsChild = childId;
                break;
            }
            debug("collection_log", "scrapeCollectionLogPage: child {} has {} dynamic children but 0 item widgets",
                childId, dynamics.length);
        }

        if (items == null || items.isEmpty())
        {
            recordEvent("collection_log", "collection_log_items_not_found", ImmutableMap.of(
                "entry", entryName,
                "note", "no item widgets found — items child IDs may need updating",
                "children_tried", Arrays.toString(COLLECTION_LOG_ITEMS_CHILDREN)
            ));
            return;
        }

        int obtainedCount = 0;
        for (Map<String, Object> row : items)
        {
            if (Boolean.TRUE.equals(row.get("obtained")))
            {
                obtainedCount++;
            }
        }

        recordEvent("collection_log", "collection_log_page_scraped", ImmutableMap.of(
            "entry", entryName, "tab", tabName,
            "obtained", obtainedCount, "total", items.size(), "items_child", foundItemsChild
        ));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tab", tabName);
        entry.put("obtained_count", obtainedCount);
        entry.put("total_items", items.size());
        entry.put("items", items);
        entry.put("last_scraped", OffsetDateTime.now().toString());
        collectionLogCache.put(entryName, entry);

        writeCollectionLogSnapshot("collection_log_scraped");
    }

    private List<Map<String, Object>> extractCollectionLogItems(Widget[] widgets)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Widget w : widgets)
        {
            int itemId = w.getItemId();
            if (itemId <= 0)
            {
                continue;
            }
            int canonicalId = itemManager.canonicalize(itemId);
            // opacity 0 = fully visible = obtained; any other value = greyed out = not obtained
            boolean obtained = w.getOpacity() == 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", canonicalId);
            row.put("name", safeItemName(canonicalId));
            row.put("obtained", obtained);
            items.add(row);
        }
        return items;
    }

    private void writeCollectionLogSnapshot(String reason)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportCollectionLog() || gameState != GameState.LOGGED_IN)
        {
            if (manualPanelRequest)
            {
                panel.markUnavailable("collection_log", "Not ready", "Collection log is only available while logged in.");
            }
            return;
        }

        if (collectionLogCache.isEmpty())
        {
            // Nothing scraped yet — don't write an empty file or consume the throttle slot
            if (manualPanelRequest)
            {
                panel.markUnavailable("collection_log", "Open log", "Open the Collection Log in-game and click each entry to capture it.");
            }
            return;
        }

        if (!acquireExportSlot(COLLECTION_LOG_FILE, COLLECTION_LOG_EXPORT_INTERVAL_MS))
        {
            if (manualPanelRequest)
            {
                panel.markChecked("collection_log");
            }
            return;
        }

        // Organise entries by tab
        Map<String, Map<String, Object>> tabs = new LinkedHashMap<>();
        for (String tab : COLLECTION_LOG_TABS)
        {
            tabs.put(tab, new LinkedHashMap<>());
        }
        for (Map.Entry<String, Map<String, Object>> e : collectionLogCache.entrySet())
        {
            String entryName = e.getKey();
            Map<String, Object> data = e.getValue();
            String tab = (String) data.getOrDefault("tab", "Other");
            Map<String, Object> tabMap = tabs.get(tab);
            if (tabMap == null)
            {
                tabMap = tabs.computeIfAbsent("Other", k -> new LinkedHashMap<>());
            }
            tabMap.put(entryName, data);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("entries_scraped", collectionLogCache.size());
        payload.put("note", "Open each collection log page in-game to capture it. Data accumulates across sessions.");
        payload.put("tabs", tabs);

        debug("collection_log", "Writing collection log snapshot reason={} entries={}", reason, collectionLogCache.size());
        writeJson("collection_log", COLLECTION_LOG_FILE, payload);
    }

    private void restoreCollectionLogCache()
    {
        Path dir = accountOutputDir;
        if (dir == null)
        {
            return;
        }

        Path file = dir.resolve(COLLECTION_LOG_FILE);
        if (!Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = gson.fromJson(content, Map.class);
            if (saved == null)
            {
                return;
            }

            Object tabsObj = saved.get("tabs");
            if (!(tabsObj instanceof Map))
            {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> savedTabs = (Map<String, Object>) tabsObj;
            int restored = 0;
            for (Map.Entry<String, Object> tabEntry : savedTabs.entrySet())
            {
                if (!(tabEntry.getValue() instanceof Map))
                {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entries = (Map<String, Object>) tabEntry.getValue();
                for (Map.Entry<String, Object> entry : entries.entrySet())
                {
                    if (entry.getValue() instanceof Map)
                    {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entryData = (Map<String, Object>) entry.getValue();
                        collectionLogCache.put(entry.getKey(), entryData);
                        restored++;
                    }
                }
            }
            debug("collection_log", "Restored {} collection log entries from disk", restored);
        }
        catch (Exception ex)
        {
            debug("collection_log", "Could not restore collection log cache from disk: {}", ex.toString());
        }
    }

    private static String stripWidgetTags(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replaceAll("<[^>]+>", "");
    }

    private void exportContainerSnapshot(ContainerKind kind, String reason)
    {
        exportContainerSnapshot(kind, reason, CONTAINER_EXPORT_INTERVAL_MS);
    }

    private void exportContainerSnapshot(ContainerKind kind, String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!kind.isEnabled(config))
        {
            updateDatasetStatus(kind.getDatasetKey(), "skipped", "disabled", ImmutableMap.of("reason", reason));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Disabled", "This dataset is disabled in plugin settings.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (gameState != GameState.LOGGED_IN)
        {
            debug(kind.getDatasetKey(), "Skipped {} export reason={} because gameState={}",
                kind.getDatasetKey(), reason, gameState);
            updateDatasetStatus(kind.getDatasetKey(), "skipped", "not_logged_in", ImmutableMap.of(
                "reason", reason,
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Not ready", "This data is only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(kind.getFileName(), minimumIntervalMs))
        {
            debug(kind.getDatasetKey(), "Throttled {} export reason={} minimumIntervalMs={}",
                kind.getDatasetKey(), reason, minimumIntervalMs);
            updateDatasetStatus(kind.getDatasetKey(), "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked(kind.getDatasetKey());
            }
            requestObservabilityWrite(false);
            return;
        }

        ItemContainer container = client.getItemContainer(kind.getInventoryId());
        if (container == null)
        {
            debug(kind.getDatasetKey(), "Skipped {} export reason={} because container {} is unavailable",
                kind.getDatasetKey(), reason, kind.getInventoryId());
            updateDatasetStatus(kind.getDatasetKey(), "waiting", "container_unavailable", ImmutableMap.of(
                "reason", reason,
                "inventory_id", kind.getInventoryId()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Open first", containerUnavailableMessage(kind));
            }
            requestObservabilityWrite(false);
            return;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Item[] containerItems = container.getItems();
        for (int slot = 0; slot < containerItems.length; slot++)
        {
            Item item = containerItems[slot];
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
            {
                continue;
            }

            int canonicalId = itemManager.canonicalize(item.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("id", canonicalId);
            row.put("quantity", item.getQuantity());
            row.put("name", safeItemName(canonicalId));
            items.add(row);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("kind", kind.getDatasetKey());
        payload.put("item_count", items.size());
        payload.put("items", items);

        debug(kind.getDatasetKey(), "Prepared {} export reason={} itemCount={}",
            kind.getDatasetKey(), reason, items.size());
        updateDatasetStatus(kind.getDatasetKey(), "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "inventory_id", kind.getInventoryId(),
            "item_count", items.size()
        ));
        writeJson(kind.getDatasetKey(), kind.getFileName(), payload);
    }

    private Map<String, Object> buildStats()
    {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Skill skill : Skill.values())
        {
            if (!isTrackedSkill(skill))
            {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("real_level", client.getRealSkillLevel(skill));
            row.put("boosted_level", client.getBoostedSkillLevel(skill));
            row.put("experience", client.getSkillExperience(skill));
            stats.put(skill.getName(), row);
        }
        return stats;
    }

    private boolean isCharacterStateReady()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null || player.getName().trim().isEmpty())
        {
            return false;
        }

        for (Skill skill : Skill.values())
        {
            if (!isTrackedSkill(skill))
            {
                continue;
            }

            if (client.getRealSkillLevel(skill) > 0)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isTrackedSkill(Skill skill)
    {
        return !"OVERALL".equals(skill.name());
    }

    private List<String> worldTypes()
    {
        List<String> worldTypes = new ArrayList<>();
        for (WorldType worldType : client.getWorldType())
        {
            worldTypes.add(worldType.name());
        }
        return worldTypes;
    }

    private String safeItemName(int canonicalId)
    {
        try
        {
            ItemComposition composition = itemManager.getItemComposition(canonicalId);
            if (composition == null)
            {
                return "";
            }

            String name = composition.getName();
            return name != null ? name : "";
        }
        catch (RuntimeException ex)
        {
            debug("item_lookup", "Failed to resolve item name for id={} error={}", canonicalId, ex.toString());
            return "";
        }
    }

    private boolean acquireExportSlot(String key, long minimumIntervalMs)
    {
        if (minimumIntervalMs <= 0L)
        {
            lastExportAt.computeIfAbsent(key, ignored -> new AtomicLong()).set(System.currentTimeMillis());
            return true;
        }

        AtomicLong lastRun = lastExportAt.computeIfAbsent(key, ignored -> new AtomicLong(0L));
        long now = System.currentTimeMillis();
        long previous = lastRun.get();
        if (now - previous < minimumIntervalMs)
        {
            return false;
        }

        lastRun.set(now);
        return true;
    }

    private void writeJson(String datasetKey, String fileName, Map<String, Object> payload)
    {
        Path outputDir = accountOutputDir;
        if (!writesEnabled || outputDir == null)
        {
            updateDatasetStatus(datasetKey, "error", "writes_disabled", ImmutableMap.of("file", fileName));
            requestObservabilityWrite(false);
            return;
        }

        String json = serializePayload(fileName, payload);
        String stableJson = stableJson(payload);
        if (json == null || stableJson == null)
        {
            updateDatasetStatus(datasetKey, "error", "serialization_failed", ImmutableMap.of("file", fileName));
            requestObservabilityWrite(false);
            return;
        }

        Path outputPath = outputDir.resolve(fileName);
        boolean manualRequest = isManualPanelRequest(payload);
        submitWriter(() ->
        {
            try
            {
                String previousStable = lastStablePayloadByFile.get(fileName);
                if (stableJson.equals(previousStable))
                {
                    debug("writer", "Skipped write for {} because stable payload is unchanged", outputPath);
                    updateDatasetStatus(datasetKey, "unchanged", "stable_payload_unchanged", ImmutableMap.of(
                        "file", fileName
                    ));
                    if (manualRequest)
                    {
                        panel.markChecked(datasetKey);
                    }
                    requestObservabilityWrite(false);
                    return;
                }

                writeTextFileSync(outputPath, json + System.lineSeparator());
                lastStablePayloadByFile.put(fileName, stableJson);
                warnedFailureKeys.remove("write:" + fileName);

                debug("writer", "Wrote export file {}", outputPath);
                updateDatasetStatus(datasetKey, "success", "write_complete", ImmutableMap.of(
                    "file", fileName
                ));
                recordEvent("writer", "write_complete", ImmutableMap.of(
                    "file", fileName
                ));
                panel.markExported(datasetKey);
                requestObservabilityWrite(false);
            }
            catch (IOException ex)
            {
                updateDatasetStatus(datasetKey, "error", "write_failed", ImmutableMap.of(
                    "file", fileName,
                    "error", ex.toString()
                ));
                recordEvent("writer", "write_failed", ImmutableMap.of(
                    "file", fileName,
                    "error", ex.toString()
                ));
                if (manualRequest)
                {
                    panel.markFailed(datasetKey, ex.toString());
                }
                requestObservabilityWrite(false);
                warnOnce("write:" + fileName, "Failed writing export file " + outputPath, ex);
            }
        });
    }

    private static boolean isManualPanelRequest(Map<String, Object> payload)
    {
        Object reason = payload.get("reason");
        return reason instanceof String && isManualPanelReason((String) reason);
    }

    private static boolean isManualPanelReason(String reason)
    {
        return reason != null && reason.startsWith("manual_panel");
    }

    private void manualExportAll()
    {
        panel.beginManualSync();
        // Collection log can only be updated by physically clicking entries in-game;
        // skip it from the sync so the row display is left untouched.
        panel.skipDatasetInSync("collection_log");
        clientThread.invokeLater(() ->
        {
            exportCharacterSnapshot("manual_panel");
            for (ContainerKind containerKind : ContainerKind.values())
            {
                exportContainerSnapshot(containerKind, "manual_panel", 0L);
            }
            exportQuestSnapshot("manual_panel");
            exportDiarySnapshot("manual_panel");
            exportCombatAchievementSnapshot("manual_panel");
        });
    }

    private static String containerUnavailableMessage(ContainerKind kind)
    {
        switch (kind)
        {
            case BANK:
                return "Open your bank first to refresh this file.";
            case SEED_VAULT:
                return "Open your seed vault first to refresh this file.";
            case INVENTORY:
                return "Inventory data is not available yet. Try again in a moment.";
            case EQUIPMENT:
                return "Equipment data is not available yet. Try again in a moment.";
            default:
                return "This data is not available right now.";
        }
    }

    private void updateDatasetStatus(String dataset, String state, String reason, Map<String, Object> details)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", dataset);
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("state", state);
        payload.put("reason", reason);
        payload.put("updated_at", OffsetDateTime.now().toString());
        if (details != null && !details.isEmpty())
        {
            payload.put("details", details);
        }
        datasetStatus.put(dataset, payload);
    }

    private void recordEvent(String area, String type, Map<String, Object> details)
    {
        if (!config.debugLogging())
        {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", OffsetDateTime.now().toString());
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("area", area);
        payload.put("type", type);
        if (details != null && !details.isEmpty())
        {
            payload.put("details", details);
        }

        synchronized (recentEvents)
        {
            recentEvents.addLast(payload);
            while (recentEvents.size() > MAX_RECENT_EVENTS)
            {
                recentEvents.removeFirst();
            }
        }

        if (!writesEnabled)
        {
            return;
        }

        String serialized = serializePayload("event:" + type, payload);
        if (serialized == null)
        {
            return;
        }

        submitWriter(() -> appendExporterLogSync(serialized));
    }

    private void resetExporterLogSync()
    {
        Path outputPath = BASE_OUTPUT_DIR.resolve(EXPORTER_LOG_FILE);
        try
        {
            writeTextFileSync(outputPath, "");
            warnedFailureKeys.remove("log:" + EXPORTER_LOG_FILE);
        }
        catch (IOException ex)
        {
            warnOnce("log:" + EXPORTER_LOG_FILE, "Failed resetting exporter log " + outputPath, ex);
        }
    }

    private void appendExporterLogSync(String serializedLine)
    {
        Path outputPath = BASE_OUTPUT_DIR.resolve(EXPORTER_LOG_FILE);
        try
        {
            Files.createDirectories(outputPath.getParent());
            if (Files.exists(outputPath) && Files.size(outputPath) >= EXPORTER_LOG_MAX_BYTES)
            {
                Files.write(
                    outputPath,
                    new byte[0],
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            }

            Files.write(
                outputPath,
                (serializedLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
            warnedFailureKeys.remove("log:" + EXPORTER_LOG_FILE);
        }
        catch (IOException ex)
        {
            warnOnce("log:" + EXPORTER_LOG_FILE, "Failed writing exporter log " + outputPath, ex);
        }
    }

    private void requestObservabilityWrite(boolean force)
    {
        if (!writesEnabled || !config.debugLogging())
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && !acquireObservabilitySlot(now))
        {
            return;
        }
        if (force)
        {
            lastObservabilityWriteAt.set(now);
        }

        Map<String, Object> statusPayload = new LinkedHashMap<>();
        statusPayload.put("updated_at", OffsetDateTime.now().toString());
        statusPayload.put("plugin_version", PLUGIN_VERSION);
        statusPayload.put("session_id", sessionId);
        statusPayload.put("game_state", readinessSnapshot.get("game_state"));
        statusPayload.put("readiness", new LinkedHashMap<>(readinessSnapshot));
        statusPayload.put("datasets", new LinkedHashMap<>(datasetStatus));

        List<Map<String, Object>> eventsPayload;
        synchronized (recentEvents)
        {
            eventsPayload = new ArrayList<>(recentEvents);
        }

        Map<String, Object> eventsFile = new LinkedHashMap<>();
        eventsFile.put("updated_at", OffsetDateTime.now().toString());
        eventsFile.put("plugin_version", PLUGIN_VERSION);
        eventsFile.put("session_id", sessionId);
        eventsFile.put("events", eventsPayload);

        String statusJson = serializePayload(STATUS_FILE, statusPayload);
        String eventsJson = serializePayload(RECENT_EVENTS_FILE, eventsFile);
        if (statusJson == null || eventsJson == null)
        {
            return;
        }

        submitWriter(() ->
        {
            writeAuxJsonSync(STATUS_FILE, statusJson);
            writeAuxJsonSync(RECENT_EVENTS_FILE, eventsJson);
        });
    }

    private boolean acquireObservabilitySlot(long now)
    {
        long previous = lastObservabilityWriteAt.get();
        if (now - previous < OBSERVABILITY_WRITE_INTERVAL_MS)
        {
            return false;
        }

        return lastObservabilityWriteAt.compareAndSet(previous, now);
    }

    private void writeAuxJsonSync(String fileName, String json)
    {
        Path outputPath = BASE_OUTPUT_DIR.resolve(fileName);
        try
        {
            writeTextFileSync(outputPath, json + System.lineSeparator());
            warnedFailureKeys.remove("aux:" + fileName);
        }
        catch (IOException ex)
        {
            warnOnce("aux:" + fileName, "Failed writing observability file " + outputPath, ex);
        }
    }

    private void writeTextFileSync(Path outputPath, String content) throws IOException
    {
        Files.createDirectories(outputPath.getParent());
        Files.write(
            outputPath,
            content.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    private void submitWriter(Runnable task)
    {
        ExecutorService active = writer;
        if (active == null || active.isShutdown())
        {
            return;
        }

        try
        {
            active.submit(task);
        }
        catch (RuntimeException ex)
        {
            log.debug("Dropped writer task during shutdown: {}", ex.toString());
        }
    }

    private void updateReadinessSnapshot()
    {
        Player player = client.getLocalPlayer();
        readinessSnapshot.put("updated_at", OffsetDateTime.now().toString());
        readinessSnapshot.put("plugin_version", PLUGIN_VERSION);
        readinessSnapshot.put("session_id", sessionId);
        readinessSnapshot.put("writes_enabled", writesEnabled);
        readinessSnapshot.put("pending_initial_character_export", pendingInitialCharacterExport);
        readinessSnapshot.put("local_player_present", player != null);
        readinessSnapshot.put("character_state_ready", isCharacterStateReady());
        readinessSnapshot.put("bank_container_present", client.getItemContainer(InventoryID.BANK) != null);
        readinessSnapshot.put("seed_vault_container_present", client.getItemContainer(InventoryID.SEED_VAULT) != null);
        readinessSnapshot.put("inventory_container_present", client.getItemContainer(InventoryID.INV) != null);
        readinessSnapshot.put("equipment_container_present", client.getItemContainer(InventoryID.WORN) != null);
        readinessSnapshot.put("world", client.getWorld());
        readinessSnapshot.put("game_state", client.getGameState().name());
    }

    private Map<String, Object> basePayload(String reason)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exported_at", OffsetDateTime.now().toString());
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("reason", reason);
        return payload;
    }

    private String serializePayload(String context, Object payload)
    {
        try
        {
            return prettyGson.toJson(payload);
        }
        catch (RuntimeException ex)
        {
            warnOnce("serialize:" + context, "Failed serializing payload for " + context, ex);
            return null;
        }
    }

    private String stableJson(Map<String, Object> payload)
    {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet())
        {
            String key = entry.getKey();
            if ("exported_at".equals(key) || "reason".equals(key))
            {
                continue;
            }
            copy.put(key, entry.getValue());
        }
        return serializePayload("stable_payload", copy);
    }

    private boolean initializeBaseOutputDirectory()
    {
        if (!BASE_OUTPUT_DIR.startsWith(RUNELITE_DIR))
        {
            log.warn("Character State Exporter output directory escapes RuneLite dir: {}", BASE_OUTPUT_DIR);
            return false;
        }

        try
        {
            Files.createDirectories(BASE_OUTPUT_DIR);
            return true;
        }
        catch (IOException ex)
        {
            log.warn("Character State Exporter could not create output directory {}", BASE_OUTPUT_DIR, ex);
            return false;
        }
    }

    private void warnOnce(String key, String message, Exception ex)
    {
        if (warnedFailureKeys.add(key))
        {
            log.warn(message, ex);
        }
        else
        {
            log.debug("{}: {}", message, ex.toString());
        }
    }

    private void debug(String area, String message, Object... args)
    {
        if (!config.debugLogging())
        {
            return;
        }

        log.debug("[" + area + "] " + message, args);
    }

    private static BufferedImage createSidebarIcon()
    {
        BufferedImage resourceIcon = loadSidebarIconResource();
        if (resourceIcon != null)
        {
            return resourceIcon;
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color outline = new Color(78, 58, 34);
        Color parchment = new Color(191, 168, 122);
        Color parchmentShadow = new Color(150, 127, 86);
        Color accent = new Color(109, 42, 30);

        g.setColor(outline);
        g.fillRoundRect(2, 1, 11, 14, 3, 3);

        g.setColor(parchment);
        g.fillRoundRect(3, 2, 9, 12, 2, 2);

        g.setColor(parchmentShadow);
        g.fillPolygon(new int[]{9, 12, 12}, new int[]{2, 2, 5}, 3);

        g.setColor(outline);
        g.drawLine(8, 2, 12, 2);
        g.drawLine(12, 2, 12, 5);

        g.setColor(new Color(121, 93, 58));
        g.drawLine(5, 6, 10, 6);
        g.drawLine(5, 8, 9, 8);

        g.setColor(accent);
        g.fillRect(7, 8, 2, 3);
        g.fillPolygon(new int[]{5, 8, 11}, new int[]{10, 13, 10}, 3);

        g.dispose();
        return image;
    }

    private static BufferedImage loadSidebarIconResource()
    {
        try (InputStream stream = CharacterStateExporterPlugin.class.getResourceAsStream("sidebar_icon.png"))
        {
            if (stream == null)
            {
                return null;
            }

            BufferedImage source = ImageIO.read(stream);
            if (source == null)
            {
                return null;
            }

            BufferedImage scaled = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, 16, 16, null);
            g.dispose();
            return scaled;
        }
        catch (IOException ex)
        {
            return null;
        }
    }

    private static final class CombatTask
    {
        final int sortId;
        final String name;
        final int tier; // 1 = Easy, 2 = Medium, 3 = Hard, 4 = Elite, 5 = Master, 6 = Grandmaster

        CombatTask(int sortId, String name, int tier)
        {
            this.sortId = sortId;
            this.name = name;
            this.tier = tier;
        }
    }

    private enum ContainerKind
    {
        BANK("bank", "bank.json", InventoryID.BANK, CONTAINER_EXPORT_INTERVAL_MS),
        SEED_VAULT("seed_vault", "seed_vault.json", InventoryID.SEED_VAULT, CONTAINER_EXPORT_INTERVAL_MS),
        INVENTORY("inventory", "inventory.json", InventoryID.INV, INVENTORY_EXPORT_INTERVAL_MS),
        EQUIPMENT("equipment", "equipment.json", InventoryID.WORN, CONTAINER_EXPORT_INTERVAL_MS);

        private final String datasetKey;
        private final String fileName;
        private final int inventoryId;
        private final long minimumIntervalMs;

        ContainerKind(String datasetKey, String fileName, int inventoryId, long minimumIntervalMs)
        {
            this.datasetKey = datasetKey;
            this.fileName = fileName;
            this.inventoryId = inventoryId;
            this.minimumIntervalMs = minimumIntervalMs;
        }

        String getDatasetKey()
        {
            return datasetKey;
        }

        String getFileName()
        {
            return fileName;
        }

        int getInventoryId()
        {
            return inventoryId;
        }

        long getMinimumIntervalMs()
        {
            return minimumIntervalMs;
        }

        boolean isEnabled(CharacterStateExporterConfig config)
        {
            switch (this)
            {
                case BANK:
                    return config.exportBank();
                case SEED_VAULT:
                    return config.exportSeedVault();
                case INVENTORY:
                    return config.exportInventory();
                case EQUIPMENT:
                    return config.exportEquipment();
                default:
                    return false;
            }
        }

        static ContainerKind fromCommand(String command)
        {
            switch (command)
            {
                case "bank":
                    return BANK;
                case "seedvault":
                case "seed_vault":
                case "seeds":
                    return SEED_VAULT;
                case "inventory":
                case "inv":
                    return INVENTORY;
                case "equipment":
                case "gear":
                    return EQUIPMENT;
                default:
                    return null;
            }
        }
    }
}
