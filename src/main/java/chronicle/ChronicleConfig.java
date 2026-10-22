/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ChronicleConfig.GROUP)
public interface ChronicleConfig extends Config
{
	String GROUP = "chronicle";

	@ConfigSection(
		name = "Advanced",
		description = "Cloud sync and how often the journal is saved. "
			+ "Nothing here uses the network until you turn it on and set a server.",
		position = 1,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "cloudSync",
		name = "Enable cloud sync",
		description = "Also send your journal to the server below: loot taken and left behind, "
			+ "levels and xp, kill counts and other counters, collection log, clues, quests, "
			+ "diaries, combat achievements, slayer tasks, pets, deaths and group storage "
			+ "changes, with your display name, account type and RuneLite account hash. "
			+ "Nothing is read back and the plugin works the same with this off. "
			+ "Off by default. Needs a server and a token.",
		warning = "This sends your IP address and your account's activity to the third-party "
			+ "server you set below. The RuneLite developers do not control or check that server.",
		position = 10,
		section = advancedSection
	)
	default boolean cloudSync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "serverBaseUrl",
		name = "Cloud server",
		description = "Address of a Chronicle server to sync to, such as one you run yourself. "
			+ "Cloud sync does nothing while this is blank.",
		position = 11,
		section = advancedSection
	)
	default String serverBaseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "manualToken",
		name = "Cloud token",
		description = "This account's token, from whoever runs the server. "
			+ "Cloud sync sends nothing without one.",
		position = 12,
		section = advancedSection
	)
	default String manualToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pushIntervalMinutes",
		name = "Journal write interval",
		description = "How often the session and the day's history are saved to disk. "
			+ "A crash loses at most this much. The journal is also saved at logout and "
			+ "when the plugin stops. Cloud sync sends on the same schedule.",
		position = 14,
		section = advancedSection
	)
	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	default int pushIntervalMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "importJournal",
		name = "Import a journal",
		description = "Tick to choose a journal file, such as a backup or one from another "
			+ "computer, to merge into this account's journal. Journals are JSON files in "
			+ ".runelite/chronicle/. Importing the same file twice changes nothing. "
			+ "The tick clears when the file chooser opens.",
		position = 40,
		section = advancedSection
	)
	default boolean importJournal()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pushNow",
		name = "Push stats now",
		description = "Tick to send to the server now instead of at the next save. "
			+ "Needs cloud sync on. The tick clears itself.",
		position = 41,
		section = advancedSection
	)
	default boolean pushNow()
	{
		return false;
	}
}
