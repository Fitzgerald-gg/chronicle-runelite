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

/**
 * The kill counts the History spine stores and its Kills toggle draws: every
 * drop-ledger source at the most any record has seen of it, its kill-count
 * line, its loot events or the collection log's count for the page of the same
 * name, under the log's spelling where the two name one thing, with the log's
 * own pages beside. An ordinary slayer monster has no kill-count line and no
 * log page, so its loot events alone carry it in; before this it never reached
 * the History tab at all.
 */
public class KillCountsTest
{
	private static final String RSN = "Tester";

	private File dir;

	// the store the last plugin() mounted, for the spine's own reading of it
	private LocalStore store;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-kill-counts").toFile();
	}

	// a plugin over a mounted journal, the store handed in the way Guice would
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

	private static final String JOURNAL = "{\"schema\":1,\"rsn\":\"Tester\","
		+ "\"drops\":{"
		// ordinary slayer monsters: no kill-count line, only their loot events
		+ "\"Nechryael\":{\"loots\":622,\"value\":100},"
		+ "\"Dust devil\":{\"kc\":0,\"loots\":2997,\"value\":100},"
		// a page the log spells in the plural, with more loot events than either count
		+ "\"Tormented Demon\":{\"kc\":1302,\"loots\":1305,\"value\":100},"
		// a boss the ledger has watched longer than the log has counted
		+ "\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":100},"
		// a boss the log counts higher than the ledger
		+ "\"Kraken\":{\"kc\":200,\"loots\":180,\"value\":100},"
		// nothing counted at all
		+ "\"Empty\":{\"kc\":0,\"loots\":0,\"value\":0}},"
		+ "\"collection_log\":{\"kcs\":{\"Tormented Demons\":1302,\"Vorkath\":19,"
		+ "\"Kraken\":237,\"Soul Wars\":346,\"Zulrah\":0,\"Rift\":\"many\"}}}";

	@Test
	public void everyLedgerSourceCountsAtTheMostAnyRecordSaw() throws Exception
	{
		Map<String, Long> kc = plugin(JOURNAL).killCounts();
		// loot events alone carry a source with no kill-count line
		assertEquals(Long.valueOf(622), kc.get("Nechryael"));
		assertEquals(Long.valueOf(2997), kc.get("Dust devil"));
		// a source the log pages keeps the log's spelling and the highest count,
		// whichever record holds it
		assertEquals(Long.valueOf(1305), kc.get("Tormented Demons"));
		assertFalse(kc.toString(), kc.containsKey("Tormented Demon"));
		assertEquals(Long.valueOf(156), kc.get("Vorkath"));
		assertEquals(Long.valueOf(237), kc.get("Kraken"));
		// a page the ledger never saw loot from stands at the log's count
		assertEquals(Long.valueOf(346), kc.get("Soul Wars"));
		// nothing counted stays out: a zero page, a zero source, a non-numeric entry
		assertFalse(kc.toString(), kc.containsKey("Zulrah"));
		assertFalse(kc.toString(), kc.containsKey("Empty"));
		assertFalse(kc.toString(), kc.containsKey("Rift"));
		assertEquals(kc.toString(), 6, kc.size());
	}

	@Test
	public void theLedgersOwnSourcesStandApartAtTheSameFigures() throws Exception
	{
		ChroniclePlugin plugin = plugin(JOURNAL);
		Map<String, Long> own = plugin.ledgerKills();
		// the sources the log has no page for, and only those
		assertEquals(own.toString(), 2, own.size());
		assertEquals(Long.valueOf(622), own.get("Nechryael"));
		assertEquals(Long.valueOf(2997), own.get("Dust devil"));
		// at the figures the spine carries for them: one base
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
		assertEquals(java.util.Collections.singletonMap("Nechryael", 3L), plugin.ledgerKills());
	}

	@Test
	public void twoLedgerSpellingsOfOnePageFoldIntoOneEntry() throws Exception
	{
		// the ledger has seen one monster under two spellings (a chat line and a
		// loot event name), both of which name the log's one page: the page holds
		// the most any of the three records saw, not their sum
		ChroniclePlugin plugin = plugin("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"drops\":{"
			+ "\"Deranged archaeologist\":{\"kc\":4,\"loots\":6,\"value\":100},"
			+ "\"Deranged Archaeologist\":{\"kc\":0,\"loots\":10,\"value\":100}},"
			+ "\"collection_log\":{\"kcs\":{\"Deranged Archaeologist\":8}}}");
		Map<String, Long> kc = plugin.killCounts();
		assertEquals(kc.toString(),
			java.util.Collections.singletonMap("Deranged Archaeologist", 10L), kc);
		// both spellings are paged, so the ledger's own list is empty
		assertEquals(plugin.ledgerKills().toString(), 0, plugin.ledgerKills().size());
		// the per-source base holds the one entry too, and the summary sums it once
		Map<String, Long> perSource = store.sourceKills();
		assertEquals(perSource.toString(),
			java.util.Collections.singletonMap("Deranged Archaeologist", 10L), perSource);
		assertEquals(Long.valueOf(10), store.spineExtras().get("kills"));
	}
}
