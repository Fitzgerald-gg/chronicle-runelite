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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class KillCountsTest
{
	private static final String RSN = "Tester";

	private File dir;

	private LocalStore store;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-kill-counts").toFile();
	}

	private ChroniclePlugin plugin(String json) throws Exception
	{
		Files.write(new File(dir, "tester.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		store.load(dir, RSN);
		ChroniclePlugin plugin = new ChroniclePlugin();
		Field f = ChroniclePlugin.class.getDeclaredField("localStore");
		f.setAccessible(true);
		f.set(plugin, store);
		return plugin;
	}

	private Map<String, Long> ledgerOnly()
	{
		com.google.gson.JsonObject cl = store.clogSnapshot();
		java.util.Set<String> paged = LocalStore.clogKillCounts(cl).keySet();
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Long> e : LocalStore.sourceKills(cl, store.dropSources()).entrySet())
		{
			if (!paged.contains(e.getKey()))
			{
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	private static final String JOURNAL = "{\"schema\":1,\"rsn\":\"Tester\","
		+ "\"drops\":{"
		+ "\"Nechryael\":{\"loots\":622,\"value\":100},"
		+ "\"Dust devil\":{\"kc\":0,\"loots\":2997,\"value\":100},"
		+ "\"Tormented Demon\":{\"kc\":1302,\"loots\":1305,\"value\":100},"
		+ "\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":100},"
		+ "\"Kraken\":{\"kc\":200,\"loots\":180,\"value\":100},"
		+ "\"Empty\":{\"kc\":0,\"loots\":0,\"value\":0}},"
		+ "\"collection_log\":{\"kcs\":{\"Tormented Demons\":1302,\"Vorkath\":19,"
		+ "\"Kraken\":237,\"Soul Wars\":346,\"Zulrah\":0,\"Rift\":\"many\"}}}";

	@Test
	public void aRowWithNoKillBehindItIsNotAKill() throws Exception
	{
		Map<String, Long> kc = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"drops\":{\"Zalcano\":{\"kc\":2023,\"loots\":2024,\"value\":1}},"
			+ "\"collection_log\":{\"slayer_kcs\":{\"Zalcano\":2023}}}").killCounts();
		assertEquals(Long.valueOf(2_023), kc.get("Zalcano"));
	}

	@Test
	public void aPartialCountIsNoEvidenceAgainstTheRows() throws Exception
	{
		Map<String, Long> kc = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"drops\":{\"Dust devil\":{\"kc\":2804,\"loots\":2997,\"value\":1}},"
			+ "\"collection_log\":{\"slayer_kcs\":{\"Dust devils\":3328}}}").killCounts();
		assertEquals("the rows were thrown away on a task counter's word",
			Long.valueOf(3_328), kc.get("Dust devils"));
	}

	@Test
	public void theGamesOwnCountBeatsAPageCounterCountingSomethingElse() throws Exception
	{
		Map<String, Long> kc = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"chat_kcs\":{\"subdued Wintertodt\":448},"
			+ "\"collection_log\":{\"kcs\":{\"Wintertodt\":1078}}}").killCounts();
		assertEquals(Long.valueOf(448), kc.get("Wintertodt"));
	}

	@Test
	public void theChatLineMovesACountTheKillLogFroze() throws Exception
	{
		Map<String, Long> kc = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"chat_kcs\":{\"subdued Wintertodt\":448,\"Gauntlet\":32},"
			+ "\"collection_log\":{\"kcs\":{\"Wintertodt\":1078},"
			+ "\"slayer_kcs\":{\"Wintertodt\":447,\"The Gauntlet\":31}}}").killCounts();
		assertEquals(Long.valueOf(448), kc.get("Wintertodt"));
		assertEquals("the chat box names it Gauntlet, the log The Gauntlet",
			Long.valueOf(32), kc.get("The Gauntlet"));
	}

	@Test
	public void aSourceOnlyTheChatBoxHasSeenStillCounts() throws Exception
	{
		Map<String, Long> kc = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"chat_kcs\":{\"Amoxliatl\":7}}").killCounts();
		assertEquals(Long.valueOf(7), kc.get("Amoxliatl"));
	}

	@Test
	public void everyLedgerSourceCountsAtTheMostAnyRecordSaw() throws Exception
	{
		Map<String, Long> kc = plugin(JOURNAL).killCounts();
		assertEquals(Long.valueOf(622), kc.get("Nechryael"));
		assertEquals(Long.valueOf(2997), kc.get("Dust devil"));
		assertEquals(Long.valueOf(1305), kc.get("Tormented Demons"));
		assertFalse(kc.toString(), kc.containsKey("Tormented Demon"));
		assertEquals(Long.valueOf(156), kc.get("Vorkath"));
		assertEquals(Long.valueOf(237), kc.get("Kraken"));
		assertEquals(Long.valueOf(346), kc.get("Soul Wars"));
		assertFalse(kc.toString(), kc.containsKey("Zulrah"));
		assertFalse(kc.toString(), kc.containsKey("Empty"));
		assertFalse(kc.toString(), kc.containsKey("Rift"));
		assertEquals(kc.toString(), 6, kc.size());
	}

	@Test
	public void theLedgersOwnSourcesStandApartAtTheSameFigures() throws Exception
	{
		ChroniclePlugin plugin = plugin(JOURNAL);
		Map<String, Long> own = ledgerOnly();
		assertEquals(own.toString(), 2, own.size());
		assertEquals(Long.valueOf(622), own.get("Nechryael"));
		assertEquals(Long.valueOf(2997), own.get("Dust devil"));
		Map<String, Long> kc = plugin.killCounts();
		for (Map.Entry<String, Long> e : own.entrySet())
		{
			assertEquals(e.getKey(), kc.get(e.getKey()), e.getValue());
		}
	}

	@Test
	public void aJournalWithNoLogStillCountsTheLedger() throws Exception
	{
		ChroniclePlugin plugin = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"drops\":{\"Nechryael\":{\"loots\":3,\"value\":10}}}");
		assertEquals(java.util.Collections.singletonMap("Nechryael", 3L), plugin.killCounts());
		assertEquals(java.util.Collections.singletonMap("Nechryael", 3L), ledgerOnly());
	}

	@Test
	public void twoLedgerSpellingsOfOnePageFoldIntoOneEntry() throws Exception
	{
		ChroniclePlugin plugin = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"drops\":{"
			+ "\"Deranged archaeologist\":{\"kc\":4,\"loots\":6,\"value\":100},"
			+ "\"Deranged Archaeologist\":{\"kc\":0,\"loots\":10,\"value\":100}},"
			+ "\"collection_log\":{\"kcs\":{\"Deranged Archaeologist\":8}}}");
		Map<String, Long> kc = plugin.killCounts();
		assertEquals(kc.toString(),
			java.util.Collections.singletonMap("Deranged Archaeologist", 10L), kc);
		assertEquals(ledgerOnly().toString(), 0, ledgerOnly().size());
		Map<String, Long> perSource = LocalStore.sourceKills(store.clogSnapshot(), store.dropSources());
		assertEquals(perSource.toString(),
			java.util.Collections.singletonMap("Deranged Archaeologist", 10L), perSource);
		assertEquals(Long.valueOf(10), store.spineExtras().get("kills"));
	}
}
