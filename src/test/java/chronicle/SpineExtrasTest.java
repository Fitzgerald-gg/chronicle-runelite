/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import chronicle.panel.StatRegistry;
import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The journal-derived totals the plugin lays beside the counters on each history
 * line: loot events and their gp across every drop source, the items left on the
 * floor and their gp from the untaken ledger, the ledger's kills, slayer tasks
 * completed, and the collection log's obtained count. They are read off the
 * journal, never written into the trackers.
 */
public class SpineExtrasTest
{
	private static final String RSN = "Tester";

	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-spine-extras").toFile();
	}

	private LocalStore mounted() throws Exception
	{
		LocalStore store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		store.load(dir, RSN);
		return store;
	}

	private void journal(String json) throws Exception
	{
		Files.write(new File(dir, "tester.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	private static final String DROPS = "\"drops\":{"
		+ "\"Nechryael\":{\"kc\":9,\"loots\":3,\"value\":500,\"items\":{}},"
		+ "\"Zulrah\":{\"kc\":2,\"loots\":2,\"value\":250}}";

	@Test
	public void extrasSumTheJournal() throws Exception
	{
		journal("{\"schema\":1,\"rsn\":\"Tester\","
			+ DROPS + ","
			// the untaken ledger, per source: items left and what they were worth
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":120},\"Zulrah\":{\"qty\":2,\"value\":30}},"
			+ "\"slayer\":{\"completed\":7,\"tasks\":[]},"
			+ "\"collection_log\":{"
			+ "\"clog_items\":{\"Abyssal whip\":1,\"Abyssal head\":1},"
			// the page capture repeats the whip under a different case: one slot
			+ "\"by_cat\":{\"Abyssal Sire\":{\"abyssal whip\":1,\"Abyssal orphan\":1}}}}");
		LocalStore store = mounted();
		Map<String, Long> x = store.spineExtras();
		assertEquals(Long.valueOf(5), x.get("dropsReceived"));
		assertEquals(Long.valueOf(750), x.get("lootValue"));
		assertEquals(Long.valueOf(42), x.get("lootLeftCount"));
		assertEquals(Long.valueOf(150), x.get("lootLeftValue"));
		// kills are the ledger's own counts, the same base as the loot events,
		// never the collection log's page counts
		assertEquals(Long.valueOf(11), x.get("kills"));
		assertEquals(Long.valueOf(7), x.get("slayerTasksCompleted"));
		assertEquals(Long.valueOf(3), x.get("clogSlotsObtained"));
		assertEquals(7, x.size());

		// every extra has its home in the registry, and none of them is a tracker
		Map<String, Long> trackers = store.trackersSnapshot();
		for (String key : x.keySet())
		{
			assertTrue(key, StatRegistry.isSummary(key));
			assertFalse(key, trackers.containsKey(key));
		}
		assertEquals(StatRegistry.summaryKeys(), x.keySet());
	}

	@Test
	public void aFreshJournalReadsAsZeroes() throws Exception
	{
		Map<String, Long> x = mounted().spineExtras();
		assertEquals(7, x.size());
		for (Map.Entry<String, Long> e : x.entrySet())
		{
			assertEquals(e.getKey(), Long.valueOf(0), e.getValue());
		}
	}

	@Test
	public void clogSlotsReadTheLogsOwnCountWhenTheJournalHoldsOne() throws Exception
	{
		// the header count moves on every log open and every COLLECTION event;
		// the name union lags it while a page sits unvisited, so it only stands
		// in for a journal that has no header count at all
		journal("{\"schema\":1,\"rsn\":\"Tester\",\"collection_log\":{"
			+ "\"finished\":212,\"available\":1717,"
			+ "\"clog_items\":{\"Abyssal whip\":1,\"Abyssal head\":1,\"Abyssal orphan\":1}}}");
		assertEquals(Long.valueOf(212), mounted().spineExtras().get("clogSlotsObtained"));

		journal("{\"schema\":1,\"rsn\":\"Tester\",\"collection_log\":{"
			+ "\"finished\":0,"
			+ "\"clog_items\":{\"Abyssal whip\":1,\"Abyssal head\":1,\"Abyssal orphan\":1}}}");
		assertEquals(Long.valueOf(3), mounted().spineExtras().get("clogSlotsObtained"));
	}

	@Test
	public void theSpineLineIsTheTrackersWithTheExtrasBeside() throws Exception
	{
		journal("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"trackers\":{\"logsChopped\":5,\"damageDealt\":90},"
			+ DROPS + "}");
		LocalStore store = mounted();
		Map<String, Long> line = store.spineCounters();
		// the trackers as they stand
		assertEquals(Long.valueOf(5), line.get("logsChopped"));
		assertEquals(Long.valueOf(90), line.get("damageDealt"));
		// and the journal's own totals beside them
		assertEquals(Long.valueOf(5), line.get("dropsReceived"));
		assertEquals(Long.valueOf(750), line.get("lootValue"));
		assertEquals(Long.valueOf(11), line.get("kills"));
		assertEquals(2 + 7, line.size());
		// a copy: the trackers themselves never take the extras
		assertEquals(2, store.trackersSnapshot().size());
		assertFalse(store.trackersSnapshot().containsKey("dropsReceived"));
	}

	@Test
	public void peakKeysMatchTheStoresHighWaterKeys()
	{
		// the registry keeps its own copy for the panel model; a key added to one
		// and not the other would show a meaningless delta on the History tab
		assertEquals(LocalStore.MAX_KEYS, StatRegistry.peakKeys());
	}

	/**
	 * The one seam no harness reaches: appendHistoryBaseline is wired to the live
	 * client, so the only way to pin that it hands the spine the extras is to read
	 * it. A future edit that reaches for trackersSnapshot() there would leave every
	 * new line without dropsReceived, lootValue and the rest, and nothing else
	 * would notice.
	 */
	@Test
	public void theBaselineIsAppendedFromTheSpineCounters() throws Exception
	{
		File src = new File("src/main/java/chronicle/ChroniclePlugin.java");
		if (!src.isFile())
		{
			return;   // packaged run without sources; the seam is read, not compiled
		}
		String java = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
		int start = java.indexOf("void appendHistoryBaseline(");
		int end = java.indexOf("historyLog.append(", start);
		assertTrue("appendHistoryBaseline must append through historyLog", start > 0 && end > start);
		String body = java.substring(start, end);
		assertTrue("the spine line's counters come from spineCounters()",
			body.contains("localStore.spineCounters()"));
		assertFalse("trackersSnapshot() alone would drop the spine extras",
			body.contains("trackersSnapshot()"));
	}
}
