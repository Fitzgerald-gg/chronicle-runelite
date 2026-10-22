/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.counters.ExperienceStatTracker.SkillGain;
import com.google.gson.JsonObject;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

interface Live
{
	boolean ready();

	String displayRsn();

	long sessionStart();

	long sessionElapsedMinutes();

	Map<String, Integer> sessionView();

	List<SkillGain> sessionSkillXp();

	JsonObject liveSessionLine();

	ChronicleEventCapture.SlayerView slayerView();

	boolean slayerSeenThisSession();

	Map<String, long[]> skillSheet();

	PaceBook.Pace pace(String skill);

	long gamePlaytimeMinutes();

	int clogFinished();

	int clogAvailable();

	TreeMap<LocalDate, HistoryLog.Baseline> historyBaselines();

	String captureWarning();

	String captureWarningWhy();

	void turnOnMissingCapture();

	void actionImport(File file);
}
