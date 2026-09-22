/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * A change in how kill counts are reckoned, or a count the game states for the
 * first time, is not a day of kills.
 *
 * <p>Both happened on one account inside a week: a new reckoning and a first
 * Kill Log reading put Tempoross from 46 to 455, Nechryael from 686 to 1,236
 * and Kurask from 1,156 to 1,624 on one day, and every period across it
 * counted them as kills. The spine now carries what of a step was not play,
 * worked out when it happens.
 */
public class KillsVersionTest
{
	private static final String RSN = "Tester";
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-kv").toFile();
	}

	private LocalStore mounted()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(i -> i.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(10);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Ashes");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, RSN);
		return store;
	}

	private void kill(LocalStore store, String source, int times)
	{
		for (int i = 0; i < times; i++)
		{
			JsonObject d = new JsonObject();
			d.addProperty("source", source);
			JsonArray items = new JsonArray();
			JsonObject it = new JsonObject();
			it.addProperty("id", 592);
			it.addProperty("quantity", 1);
			items.add(it);
			d.add("items", items);
			store.record("LOOT", d, RSN);
		}
	}

	private static Map<String, Object> clog(String map, String key, long value)
	{
		Map<String, Object> inner = new HashMap<>();
		inner.put(key, value);
		Map<String, Object> out = new HashMap<>();
		out.put(map, inner);
		return out;
	}

	/** The first time the Kill Log is read, its figure is the past arriving, not kills. */
	@Test
	public void aFirstKillLogReadingIsACorrection()
	{
		LocalStore store = mounted();
		kill(store, "Kurask", 3);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Kurask", 1_624L), null);
		HistoryLog.Adjust adj = store.takePendingAdjust();
		assertEquals(Long.valueOf(1_621L), adj.kcs.get("Kurask"));
		assertEquals("the kills sum is corrected with it", Long.valueOf(1_621L), adj.counters.get("kills"));
	}

	/**
	 * And a later one is play: kills the ledger never saw (no drop, another
	 * device) are kills, and the reading is the only way the record learns of them.
	 */
	@Test
	public void aLaterReadingIsPlay()
	{
		LocalStore store = mounted();
		kill(store, "Kurask", 3);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Kurask", 1_624L), null);
		store.takePendingAdjust();
		kill(store, "Kurask", 2);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Kurask", 1_630L), null);
		// read the flag before anything takes the pile
		boolean corrected = store.hasPendingAdjust();
		assertFalse("a second reading was taken for a correction: "
			+ store.takePendingAdjust().kcs, corrected);
	}

	/** A count that falls is always a correction: Wintertodt's page counts rewards. */
	@Test
	public void aFallIsACorrection()
	{
		LocalStore store = mounted();
		store.setCharacter(RSN, null, 0, clog("kcs", "Wintertodt", 1_078L), null);
		store.takePendingAdjust();
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Wintertodt", 447L), null);
		assertEquals(Long.valueOf(-631L), store.takePendingAdjust().kcs.get("Wintertodt"));
	}

	/** A chat line's first word on a count brings the past too, and its own kill. */
	@Test
	public void aChatLinesFirstWordKeepsItsOwnKill()
	{
		LocalStore store = mounted();
		kill(store, "Vorkath", 1);
		store.noteKillCount("Vorkath", 157, RSN);
		assertEquals(Long.valueOf(155L), store.takePendingAdjust().kcs.get("Vorkath"));
		// and the next line is a kill, not a correction
		kill(store, "Vorkath", 1);
		store.noteKillCount("Vorkath", 158, RSN);
		assertFalse(store.hasPendingAdjust());
	}

	/** A page with no kills line, a Kill Log below it, and the ledger watching. */
	private static JsonObject wintertodtPage()
	{
		JsonObject kcs = new JsonObject();
		kcs.addProperty("Wintertodt", 50);
		JsonObject stated = new JsonObject();
		stated.addProperty("Wintertodt", 20);
		JsonObject cl = new JsonObject();
		cl.add("kcs", kcs);
		cl.add("slayer_kcs", stated);
		return cl;
	}

	private static List<LocalStore.SourceRow> wintertodtLedger()
	{
		List<LocalStore.SourceRow> rows = new ArrayList<>();
		rows.add(new LocalStore.SourceRow("Wintertodt", 0, 15, 0L, null, 0, 0));
		return rows;
	}

	/**
	 * The two reckonings, and what moving between them is. Version 0 raised the
	 * ledger to the page's counter, which on a page counting rewards brought
	 * the counter back over the Kill Log; version 1 does not.
	 */
	@Test
	public void theTwoReckoningsAndTheStepBetweenThem()
	{
		JsonObject cl = wintertodtPage();
		List<LocalStore.SourceRow> ledger = wintertodtLedger();
		Map<String, Long> none = new HashMap<>();
		Map<String, Long> v0 = LocalStore.reconciledKills(cl, ledger, none, none, 0);
		Map<String, Long> v1 = LocalStore.reconciledKills(cl, ledger, none, none, 1);
		assertEquals(Long.valueOf(50L), v0.get("Wintertodt"));
		assertEquals(Long.valueOf(20L), v1.get("Wintertodt"));
		assertEquals(50L, LocalStore.spineKills(cl, ledger, v0, 0));
		assertEquals(20L, LocalStore.spineKills(cl, ledger, v1, 1));
	}

	/**
	 * TRAP: the reckoning changed without the version moving. That is the one
	 * mistake this whole mechanism cannot catch, so the current reckoning is
	 * pinned here: a change that fails this test must bump
	 * LocalStore.KILLS_VERSION and keep the old version's branch, or every
	 * spine on every install reads the change as a day's kills.
	 */
	@Test
	public void theCurrentReckoningIsPinnedToItsVersion()
	{
		assertEquals("bump the pin with the version", 1, LocalStore.KILLS_VERSION);
		JsonObject cl = wintertodtPage();
		JsonObject lines = new JsonObject();
		JsonObject page = new JsonObject();
		page.addProperty("Tempoross kills", 455);
		lines.add("Tempoross", page);
		cl.add("kc_lines", lines);
		cl.getAsJsonObject("kcs").addProperty("Tempoross", 46);
		cl.getAsJsonObject("slayer_kcs").addProperty("Bloodvelds", 3_748);
		List<LocalStore.SourceRow> ledger = wintertodtLedger();
		ledger.add(new LocalStore.SourceRow("Bloodveld", 0, 504, 0L, null, 0, 0));
		ledger.add(new LocalStore.SourceRow("Zalcano", 2_023, 2_024, 0L, null, 0, 0));
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("Zalcano", 2_023L);
		Map<String, Long> got = LocalStore.reconciledKills(cl, ledger, chat, new HashMap<>(),
			LocalStore.KILLS_VERSION);
		Map<String, Long> want = new LinkedHashMap<>();
		want.put("Wintertodt", 20L);
		want.put("Tempoross", 455L);
		want.put("Bloodvelds", 3_748L);
		want.put("Zalcano", 2_024L);
		assertEquals(new java.util.TreeMap<>(want), new java.util.TreeMap<>(got));
		assertEquals(2_044L, LocalStore.spineKills(cl, ledger, got, LocalStore.KILLS_VERSION));
	}

	/**
	 * The first login under a newer reckoning works out the step over the same
	 * journal, and a line that does not say which version wrote it is placed
	 * by whichever the journal reproduces.
	 */
	@Test
	public void theStepIsWorkedOutOverTheSameJournal() throws Exception
	{
		Files.write(new File(dir, LocalStore.slug(RSN) + ".json").toPath(),
			("{\"schema\":1,\"rsn\":\"" + RSN + "\",\"drops\":{},\"collection_log\":"
				+ "{\"kcs\":{\"Wintertodt\":50},\"slayer_kcs\":{\"Wintertodt\":20}},"
				+ "\"trackers\":{},\"skills\":{},\"feed\":[]}").getBytes(StandardCharsets.UTF_8));
		LocalStore store = mounted();
		kill(store, "Wintertodt", 15);
		HistoryLog.Adjust shift = store.definitionShift(0);
		assertEquals(Long.valueOf(-30L), shift.kcs.get("Wintertodt"));
		assertEquals(Long.valueOf(-30L), shift.counters.get("kills"));
		assertNull("no step from the version in hand", store.definitionShift(1).kcs.get("Wintertodt"));
		Map<String, Long> oldLine = new HashMap<>();
		oldLine.put("Wintertodt", 50L);
		assertEquals(0, store.inferKillsVersion(oldLine, 50L));
		Map<String, Long> newLine = new HashMap<>();
		newLine.put("Wintertodt", 20L);
		assertEquals(1, store.inferKillsVersion(newLine, 20L));
	}
}
