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
import com.google.gson.JsonObject;
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
			// the untaken ledger, per source: items left, what they were worth, and
			// the kills that left them
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":120,\"kills\":5},"
			+ "\"Zulrah\":{\"qty\":2,\"value\":30,\"kills\":1}},"
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
		assertEquals(Long.valueOf(6), x.get("lootLeftKills"));
		// kills share the Kills list's per-source base: each source at the most
		// of its kill-count line and its loot events (9 and 2 here), never a log
		// page the ledger has no source for
		assertEquals(Long.valueOf(11), x.get("kills"));
		assertEquals(Long.valueOf(7), x.get("slayerTasksCompleted"));
		assertEquals(Long.valueOf(3), x.get("clogSlotsObtained"));
		assertEquals(8, x.size());

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
	public void killsAreTheSumOfTheBaseTheKillsListDraws() throws Exception
	{
		journal("{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{"
			// an ordinary slayer monster: no kill-count line, only its loot events
			+ "\"Nechryael\":{\"loots\":622,\"value\":100},"
			// the log counts this one higher, under its own plural
			+ "\"Tormented Demon\":{\"kc\":1302,\"loots\":1305,\"value\":100},"
			// and this one lower than the ledger has watched
			+ "\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":100}},"
			+ "\"collection_log\":{\"kcs\":{\"Tormented Demons\":1310,\"Vorkath\":19,"
			+ "\"Soul Wars\":346}}}");
		LocalStore store = mounted();
		Map<String, Long> perSource = store.sourceKills();
		assertEquals(Long.valueOf(622), perSource.get("Nechryael"));
		assertEquals(Long.valueOf(1310), perSource.get("Tormented Demons"));
		assertEquals(Long.valueOf(156), perSource.get("Vorkath"));
		// a page the ledger never saw loot from is no source
		assertEquals(perSource.toString(), 3, perSource.size());
		long sum = 0;
		for (long v : perSource.values())
		{
			sum += v;
		}
		assertEquals(622 + 1310 + 156, sum);
		// the summary's Kills line and the per-source list read one base, and the
		// FIGURE is the reconciliation's, not the ledger fold's. Soul Wars
		// is a page the ledger never saw loot from: the Kills list files it under
		// Activities rather than beside the bosses, so it must not reach this line
		// even though reconciledKills knows about it.
		assertEquals("a page with no ledger row is not a fight this line counts",
			Long.valueOf(sum),
			store.spineExtras().get("kills"));
		java.util.Map<String, Long> reconciled = LocalStore.reconciledKills(
			store.clogSnapshot(), store.dropSources(),
			new java.util.HashMap<>(), new java.util.HashMap<>());
		assertEquals("reconciledKills does hold it; the membership rule excludes it",
			Long.valueOf(346), reconciled.get("Soul Wars"));
	}

	@Test
	public void leftKillsSumTheLedgerAndARowWithoutTheFigureReadsAsNone() throws Exception
	{
		// a source written by an older build carries no kills: its stacks still
		// count, its kills read as none
		journal("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":120,\"kills\":5},"
			+ "\"Zulrah\":{\"qty\":2,\"value\":30}}}");
		LocalStore store = mounted();
		Map<String, Long> x = store.spineExtras();
		assertEquals(Long.valueOf(5), x.get("lootLeftKills"));
		assertEquals(Long.valueOf(42), x.get("lootLeftCount"));

		// an import floors the figure up like the row's other two, never down
		JsonObject higher = new Gson().fromJson("{\"untaken\":{\"Nechryael\":"
			+ "{\"qty\":40,\"value\":120,\"kills\":9}}}", JsonObject.class);
		store.importJournal(higher, RSN);
		assertEquals(Long.valueOf(9), store.spineExtras().get("lootLeftKills"));
		JsonObject lower = new Gson().fromJson("{\"untaken\":{\"Nechryael\":"
			+ "{\"qty\":40,\"value\":120,\"kills\":2}}}", JsonObject.class);
		store.importJournal(lower, RSN);
		assertEquals(Long.valueOf(9), store.spineExtras().get("lootLeftKills"));
	}

	@Test
	public void aFreshJournalReadsAsZeroes() throws Exception
	{
		Map<String, Long> x = mounted().spineExtras();
		assertEquals(8, x.size());
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
		assertEquals(2 + 8, line.size());
		// a copy: the trackers themselves never take the extras
		assertEquals(2, store.trackersSnapshot().size());
		assertFalse(store.trackersSnapshot().containsKey("dropsReceived"));
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
