/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The state a spine stands at on a date, and the levels it draws. A line that
 * carries an overall its skills add up to is a complete snapshot: a skill it
 * does not list was at zero that day, so it replaces the standing skills
 * wholly. Any other line says only what it lists and merges over what stands.
 * Counters and kill counts are cumulative and always merge. A state resting on
 * a complete snapshot draws level 1 for a skill the record never carried, since
 * a complete line lists every skill that had any xp at all, so the total counts
 * every skill in the game.
 */
public class SpineStateTest
{
	private static final String RSN = "Tester";

	private HistoryLog log;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		log = new HistoryLog(new Gson());
		dir = Files.createTempDirectory("chronicle-spine-state").toFile();
	}

	// The spine as the client reads it, so each line's completeness is derived
	// where the line is parsed rather than set by hand.
	private TreeMap<LocalDate, HistoryLog.Baseline> spine(String... lines) throws Exception
	{
		File f = new File(dir, LocalStore.slug(RSN) + HistoryLog.SPINE_SUFFIX);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
		{
			for (String line : lines)
			{
				w.write(line);
				w.write('\n');
			}
		}
		return log.read(dir, RSN);
	}

	private static LocalDate on(String date)
	{
		return LocalDate.parse(date);
	}

	private static long xp(HistoryLog.Baseline state, String skill)
	{
		Long v = state.skills.get(skill);
		assertTrue(skill + " is not on the state: " + state.skills, v != null);
		return v;
	}

	@Test
	public void aLineWhoseSkillsSumToItsOverallIsACompleteSnapshot() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}",
			"{\"date\":\"2022-01-02\",\"skills\":{\"attack\":500}}",
			"{\"date\":\"2022-01-03\",\"skills\":{\"attack\":500,\"hitpoints\":1151,\"overall\":9999}}");
		assertTrue(s.get(on("2022-01-01")).complete);
		// no overall at all: it says only what it lists
		assertFalse(s.get(on("2022-01-02")).complete);
		// an overall its parts do not add up to: the same
		assertFalse(s.get(on("2022-01-03")).complete);
	}

	@Test
	public void aCompleteLineZeroesTheSkillsItOmits() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2021-12-30\",\"skills\":{\"attack\":500,\"mining\":700}}",
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}");
		HistoryLog.Baseline state = HistoryLog.stateAt(s, on("2022-01-01"));
		assertEquals(1151L, xp(state, "hitpoints"));
		// the complete line accounts for every xp the account had, so the two
		// skills it leaves out stood at zero
		assertEquals(0L, xp(state, "attack"));
		assertEquals(0L, xp(state, "mining"));
		assertTrue(state.complete);
	}

	@Test
	public void aPartialLineCarriesTheSkillsItOmitsForward() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2023-01-01\",\"skills\":{\"attack\":500,\"mining\":700,\"overall\":1200}}",
			"{\"date\":\"2023-11-30\",\"skills\":{\"attack\":900}}");
		HistoryLog.Baseline state = HistoryLog.stateAt(s, on("2023-11-30"));
		assertEquals(900L, xp(state, "attack"));
		// the line says nothing about mining, so mining stands where it was
		assertEquals(700L, xp(state, "mining"));
	}

	@Test
	public void aLineThatDoesNotAddUpToItsOverallCarriesForwardToo() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2023-01-01\",\"skills\":{\"attack\":500,\"mining\":700,\"overall\":1200}}",
			"{\"date\":\"2023-03-20\",\"skills\":{\"attack\":900,\"overall\":4242}}");
		HistoryLog.Baseline state = HistoryLog.stateAt(s, on("2023-03-20"));
		assertEquals(700L, xp(state, "mining"));
	}

	@Test
	public void aKeyNoLineHasRecordedYetStaysAbsent() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}",
			"{\"date\":\"2025-11-19\",\"skills\":{\"hitpoints\":1151,\"sailing\":101779,\"overall\":102930}}");
		assertNull(HistoryLog.stateAt(s, on("2022-01-01")).skills.get("sailing"));
		assertEquals(101779L, xp(HistoryLog.stateAt(s, on("2025-11-19")), "sailing"));
	}

	@Test
	public void countersAndKillCountsCarryForwardAcrossACompleteLine() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2026-01-01\",\"skills\":{\"attack\":500,\"overall\":500},"
				+ "\"counters\":{\"tilesWalked\":40},\"kcs\":{\"Zulrah\":100}}",
			"{\"date\":\"2026-01-02\",\"skills\":{\"attack\":900,\"overall\":900},"
				+ "\"counters\":{},\"kcs\":{}}");
		HistoryLog.Baseline state = HistoryLog.stateAt(s, on("2026-01-02"));
		// cumulative: a complete skills line says nothing about either of them
		assertEquals(40L, (long) state.counters.get("tilesWalked"));
		assertEquals(100L, (long) state.kcs.get("Zulrah"));
	}

	@Test
	public void aPeriodOpeningOnACompleteSnapshotMeasuresEverySkillsWholeGain() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}",
			"{\"date\":\"2022-12-31\",\"skills\":{\"attack\":1000000,\"hitpoints\":1250000,"
				+ "\"overall\":2250000}}");
		HistoryLog.Baseline opening = HistoryLog.stateAt(s, on("2022-01-01"));
		HistoryLog.Baseline closing = HistoryLog.stateAt(s, on("2022-12-31"));
		HistoryLog.Baseline earliest = HistoryLog.earliest(s, on("2022-12-31"));
		Map<String, Long> gained = HistoryLog.gained(opening.skills, earliest.skills,
			closing.skills, opening.complete);
		// attack was at zero on the opening snapshot, so the year gained all of it
		assertEquals(1_000_000L, (long) gained.get("attack"));
		assertEquals(1_250_000L - 1151L, (long) gained.get("hitpoints"));
	}

	@Test
	public void aPeriodOpeningOnAPartialLineMeasuresFromTheEarliestValueOnRecord() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151}}",
			"{\"date\":\"2022-12-31\",\"skills\":{\"attack\":1000000,\"hitpoints\":1250000}}");
		HistoryLog.Baseline opening = HistoryLog.stateAt(s, on("2022-01-01"));
		HistoryLog.Baseline closing = HistoryLog.stateAt(s, on("2022-12-31"));
		HistoryLog.Baseline earliest = HistoryLog.earliest(s, on("2022-12-31"));
		Map<String, Long> gained = HistoryLog.gained(opening.skills, earliest.skills,
			closing.skills, opening.complete);
		// the opening says nothing about attack, so its earliest recorded value
		// stands in and the year shows no gain it cannot vouch for
		assertNull(gained.get("attack"));
	}

	private static final List<String> THREE = Arrays.asList("attack", "hitpoints", "sailing");

	@Test
	public void aStateOnACompleteSnapshotDrawsAnUnrecordedSkillAtLevelOne() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-12-31\",\"skills\":{\"attack\":1000000,\"hitpoints\":1250000,"
				+ "\"overall\":2250000}}");
		HistoryLog.Levels levels = HistoryLog.levels(HistoryLog.stateAt(s, on("2022-12-31")), THREE);
		assertEquals(73, (int) levels.of.get("attack"));
		assertEquals(75, (int) levels.of.get("hitpoints"));
		// no line ever carried sailing, and a complete line accounts for every
		// xp there was, so it stood at zero, which is level 1
		assertEquals(1, (int) levels.of.get("sailing"));
		assertEquals(73 + 75 + 1, levels.total);
		assertEquals(3, levels.drawn);
	}

	@Test
	public void aStateOnPartialLinesAloneDrawsNoLevelForASkillItLacks() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2022-12-31\",\"skills\":{\"attack\":1000000,\"hitpoints\":1250000}}");
		HistoryLog.Levels levels = HistoryLog.levels(HistoryLog.stateAt(s, on("2022-12-31")), THREE);
		assertEquals(0, (int) levels.of.get("sailing"));
		assertEquals(73 + 75, levels.total);
		assertEquals(2, levels.drawn);
	}

	@Test
	public void ninetyNinesAreCounted() throws Exception
	{
		TreeMap<LocalDate, HistoryLog.Baseline> s = spine(
			"{\"date\":\"2026-01-01\",\"skills\":{\"attack\":13034431,\"hitpoints\":13034430,"
				+ "\"overall\":26068861}}");
		HistoryLog.Levels levels = HistoryLog.levels(HistoryLog.stateAt(s, on("2026-01-01")), THREE);
		assertEquals(1, levels.nines);
	}

	@Test
	public void compactionSortsTheDatesAndChangesNoLine() throws Exception
	{
		String a = "{\"date\":\"2026-01-03\",\"skills\":{\"attack\":300},\"counters\":{},\"kcs\":{}}";
		String b = "{\"date\":\"2026-01-01\",\"skills\":{\"attack\":100},\"counters\":{},\"kcs\":{}}";
		String c = "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":200},\"counters\":{},\"kcs\":{}}";
		File f = new File(dir, LocalStore.slug(RSN) + HistoryLog.SPINE_SUFFIX);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
		{
			w.write(a + "\n" + b + "\n" + c + "\n");
		}
		log.compact(dir, RSN);
		List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
		// every date still there, in the calendar's order, each line's own text
		// untouched
		assertEquals(Arrays.asList(b, c, a), lines);
	}

	@Test
	public void compactionSortsWhileItFoldsARepeatedDay() throws Exception
	{
		String a = "{\"date\":\"2026-01-03\",\"skills\":{\"attack\":300},\"counters\":{},\"kcs\":{}}";
		String b = "{\"date\":\"2026-01-01\",\"skills\":{\"attack\":100},\"counters\":{},\"kcs\":{}}";
		String c = "{\"date\":\"2026-01-01\",\"skills\":{\"attack\":150},\"counters\":{},\"kcs\":{}}";
		File f = new File(dir, LocalStore.slug(RSN) + HistoryLog.SPINE_SUFFIX);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
		{
			w.write(a + "\n" + b + "\n" + c + "\n");
		}
		assertEquals(1, log.compact(dir, RSN));
		assertEquals(Arrays.asList(c, a),
			Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
	}
}
