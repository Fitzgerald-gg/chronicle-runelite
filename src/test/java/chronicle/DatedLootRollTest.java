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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.time.LocalDate;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The dated loot roll. A loot event knows when it happened and the ledger kept
 * only what it was, so a period could be told a lifetime total and nothing else.
 * One entry a day now carries the day's take and the day's floor, with the items
 * and sources beside them, which is as fine as any window the panel offers.
 */
public class DatedLootRollTest
{
	@Rule
	public TemporaryFolder dir = new TemporaryFolder();

	private LocalStore store;

	@Before
	public void setUp() throws Exception
	{
		ItemManager items = Mockito.mock(ItemManager.class);
		Mockito.when(items.getItemPrice(Mockito.anyInt())).thenReturn(100);
		store = new LocalStore(items, new Gson());
		store.load(dir.getRoot(), "Tester");
	}

	private static JsonObject loot(String source, int id, int qty, String name)
	{
		JsonObject d = new JsonObject();
		d.addProperty("source", source);
		JsonArray arr = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", id);
		it.addProperty("quantity", qty);
		it.addProperty("name", name);
		arr.add(it);
		d.add("items", arr);
		return d;
	}

	private static JsonObject untaken(String source, int id, int qty, int kills)
	{
		JsonObject d = loot(source, id, qty, "Left thing");
		d.addProperty("kills", kills);
		return d;
	}

	@Test
	public void aDaysTakeIsCountedAgainstThatDay()
	{
		store.record("LOOT", loot("Nechryael", 25772, 3, "Malicious ashes"), "Tester");
		store.record("LOOT", loot("Nechryael", 25772, 2, "Malicious ashes"), "Tester");
		LocalDate today = LocalDate.now();
		LocalStore.LootWindow w = store.lootBetween(today, today);
		assertEquals(2, w.loots);
		assertTrue("the day's take was priced at nothing", w.value > 0);
		assertEquals("Nechryael", w.sources.get(0)[0]);
		assertEquals("2", w.sources.get(0)[1]);
		assertEquals("5", w.items.get(0)[1]);
	}

	@Test
	public void aWindowThatMissesTheDayHoldsNoneOfIt()
	{
		store.record("LOOT", loot("Zulrah", 1, 1, "Thing"), "Tester");
		LocalDate today = LocalDate.now();
		LocalStore.LootWindow before = store.lootBetween(today.minusDays(9), today.minusDays(1));
		assertEquals(0, before.loots);
		assertEquals(0, before.value);
		LocalStore.LootWindow spanning = store.lootBetween(today.minusDays(9), today);
		assertEquals(1, spanning.loots);
	}

	@Test
	public void whatWasLeftOnTheFloorIsDatedTheSameWay()
	{
		store.record("LOOT_UNTAKEN", untaken("Dust devil", 201, 7, 2), "Tester");
		LocalDate today = LocalDate.now();
		LocalStore.LootWindow w = store.lootBetween(today, today);
		assertEquals(7, w.left);
		assertEquals(2, w.leftKills);
		assertTrue(w.leftValue > 0);
		assertEquals("the floor named nothing", 1, w.leftItems.size());
	}

	@Test
	public void theRollSaysWhichDayItBeginsOn()
	{
		assertEquals("an empty roll speaks for no day", 0, store.lootRollFrom());
		store.record("LOOT", loot("Zulrah", 1, 1, "Thing"), "Tester");
		long from = store.lootRollFrom();
		assertEquals(LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
			.toInstant().toEpochMilli(), from);
	}

	@Test
	public void theRollSurvivesBeingWrittenAndReadBack() throws Exception
	{
		store.record("LOOT", loot("Vorkath", 11286, 4, "Draconic visage"), "Tester");
		File folder = dir.getRoot();
		store.flush(folder);
		LocalStore again = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		again.load(folder, "Tester");
		LocalDate today = LocalDate.now();
		LocalStore.LootWindow w = again.lootBetween(today, today);
		assertEquals(1, w.loots);
		assertEquals("Vorkath", w.sources.get(0)[0]);
	}

	@Test
	public void theRollRanksTheBiggestTakeFirst()
	{
		store.record("LOOT", loot("Zulrah", 1, 1, "Cheap thing"), "Tester");
		store.record("LOOT", loot("Vorkath", 2, 50, "Dear thing"), "Tester");
		LocalDate today = LocalDate.now();
		LocalStore.LootWindow w = store.lootBetween(today, today);
		// a source is named by the payload, so the ranking reads off it directly.
		// Items are named from the game's own cache, which a test has none of.
		assertEquals(w.sources.toString(), 2, w.sources.size());
		assertEquals("Vorkath", w.sources.get(0)[0]);
		assertEquals("Zulrah", w.sources.get(1)[0]);
		assertTrue(w.sources.toString(),
			Long.parseLong(w.sources.get(0)[2]) > Long.parseLong(w.sources.get(1)[2]));
	}
}
