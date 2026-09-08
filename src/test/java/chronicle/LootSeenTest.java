/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a live kill leaves on its source besides the counters: first_seen and
 * last_seen as a running min/max in epoch millis, and the bag's names as the
 * ledger's obtained set.
 */
public class LootSeenTest
{
	private static final String RSN = "Tester";
	private static final String FILE = "tester.json";

	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-seen").toFile();
	}

	private LocalStore mounted()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(100);
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition comp = Mockito.mock(ItemComposition.class);
			Mockito.when(comp.getName()).thenReturn(
				(int) inv.getArgument(0) == 22006 ? "Draconic visage" : "Rune dagger");
			return comp;
		});
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, RSN);
		return store;
	}

	private void kill(LocalStore store, String source, int kc, int itemId, int qty)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", source);
		data.addProperty("killCount", kc);
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		store.record("LOOT", data, RSN);
	}

	private LocalStore.SourceRow source(LocalStore store, String name)
	{
		for (LocalStore.SourceRow row : store.dropSources())
		{
			if (name.equals(row.name))
			{
				return row;
			}
		}
		throw new AssertionError("no drop source " + name);
	}

	private void write(String content) throws Exception
	{
		Files.write(new File(dir, FILE).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void aFirstLiveKillDatesTheSource()
	{
		LocalStore store = mounted();
		long before = System.currentTimeMillis();
		kill(store, "Hoop Snake", 1, 4151, 1);
		long after = System.currentTimeMillis();
		LocalStore.SourceRow row = source(store, "Hoop Snake");
		assertTrue("first_seen " + row.firstMs, row.firstMs >= before && row.firstMs <= after);
		assertTrue("last_seen " + row.lastMs, row.lastMs >= before && row.lastMs <= after);
	}

	// An imported source keeps the tracker's earlier date; only the latest moves.
	@Test
	public void anEarlierFirstSeenStandsAndLastSeenExtends() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Nechryael\":{\"kc\":5,\"loots\":5,"
			+ "\"value\":0,\"first_seen\":1000,\"last_seen\":9000,\"items\":{}}}}");
		LocalStore store = mounted();
		long before = System.currentTimeMillis();
		kill(store, "Nechryael", 6, 4151, 1);
		LocalStore.SourceRow row = source(store, "Nechryael");
		assertEquals(1000L, row.firstMs);
		assertTrue("last_seen " + row.lastMs, row.lastMs >= before);
	}

	// The bag's names, lower-cased, are the row's looted set; a zero-quantity entry
	// is a name the bag once knew, not an item in hand.
	@Test
	public void theBagNamesAreTheLootedSet() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Vorkath\":{\"kc\":1,\"loots\":1,"
			+ "\"value\":0,\"items\":{\"22006\":{\"id\":22006,\"name\":\"Draconic visage\","
			+ "\"qty\":1,\"value\":0},\"22007\":{\"id\":22007,\"name\":\"Skeletal visage\","
			+ "\"qty\":0,\"value\":0}}}}}");
		LocalStore store = mounted();
		LocalStore.SourceRow row = source(store, "Vorkath");
		assertTrue(row.looted.toString(), row.looted.contains("draconic visage"));
		assertFalse(row.looted.toString(), row.looted.contains("skeletal visage"));

		kill(store, "Vorkath", 2, 4151, 1);
		assertTrue(source(store, "Vorkath").looted.contains("rune dagger"));
	}
}
