/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import chronicle.panel.StatRegistryTest;
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
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":120,\"kills\":5},"
			+ "\"Zulrah\":{\"qty\":2,\"value\":30,\"kills\":1}},"
			+ "\"slayer\":{\"completed\":7,\"tasks\":[]},"
			+ "\"collection_log\":{"
			+ "\"clog_items\":{\"Abyssal whip\":1,\"Abyssal head\":1},"
			+ "\"by_cat\":{\"Abyssal Sire\":{\"abyssal whip\":1,\"Abyssal orphan\":1}}}}");
		LocalStore store = mounted();
		Map<String, Long> x = store.spineExtras();
		assertEquals(Long.valueOf(5), x.get("dropsReceived"));
		assertEquals(Long.valueOf(750), x.get("lootValue"));
		assertEquals(Long.valueOf(42), x.get("lootLeftCount"));
		assertEquals(Long.valueOf(150), x.get("lootLeftValue"));
		assertEquals(Long.valueOf(6), x.get("lootLeftKills"));
		assertEquals(Long.valueOf(11), x.get("kills"));
		assertEquals(Long.valueOf(7), x.get("slayerTasksCompleted"));
		assertEquals(Long.valueOf(3), x.get("clogSlotsObtained"));
		assertEquals(8, x.size());

		Map<String, Long> trackers = store.trackersSnapshot();
		for (String key : x.keySet())
		{
			assertTrue(key, StatRegistryTest.summaryKeys().contains(key));
			assertFalse(key, trackers.containsKey(key));
		}
		assertEquals(StatRegistryTest.summaryKeys(), x.keySet());
	}

	@Test
	public void killsAreTheSumOfTheBaseTheKillsListDraws() throws Exception
	{
		journal("{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{"
			+ "\"Nechryael\":{\"loots\":622,\"value\":100},"
			+ "\"Tormented Demon\":{\"kc\":1302,\"loots\":1305,\"value\":100},"
			+ "\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":100}},"
			+ "\"collection_log\":{\"kcs\":{\"Tormented Demons\":1310,\"Vorkath\":19,"
			+ "\"Soul Wars\":346}}}");
		LocalStore store = mounted();
		Map<String, Long> perSource = LocalStore.sourceKills(store.clogSnapshot(), store.dropSources());
		assertEquals(Long.valueOf(622), perSource.get("Nechryael"));
		assertEquals(Long.valueOf(1310), perSource.get("Tormented Demons"));
		assertEquals(Long.valueOf(156), perSource.get("Vorkath"));
		assertEquals(perSource.toString(), 3, perSource.size());
		long sum = 0;
		for (long v : perSource.values())
		{
			sum += v;
		}
		assertEquals(622 + 1310 + 156, sum);
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
		journal("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":120,\"kills\":5},"
			+ "\"Zulrah\":{\"qty\":2,\"value\":30}}}");
		LocalStore store = mounted();
		Map<String, Long> x = store.spineExtras();
		assertEquals(Long.valueOf(5), x.get("lootLeftKills"));
		assertEquals(Long.valueOf(42), x.get("lootLeftCount"));

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
		assertEquals(Long.valueOf(5), line.get("logsChopped"));
		assertEquals(Long.valueOf(90), line.get("damageDealt"));
		assertEquals(Long.valueOf(5), line.get("dropsReceived"));
		assertEquals(Long.valueOf(750), line.get("lootValue"));
		assertEquals(Long.valueOf(11), line.get("kills"));
		assertEquals(2 + 8, line.size());
		assertEquals(2, store.trackersSnapshot().size());
		assertFalse(store.trackersSnapshot().containsKey("dropsReceived"));
	}

	@Test
	public void theBaselineIsAppendedFromTheSpineCounters() throws Exception
	{
		File src = new File("src/main/java/chronicle/ChroniclePlugin.java");
		if (!src.isFile())
		{
			return;
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
