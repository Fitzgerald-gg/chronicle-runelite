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
import static org.junit.Assert.assertTrue;

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
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(10L);
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

	private static Map<String, Object> clog(String map, String key, Object value)
	{
		Map<String, Object> inner = new HashMap<>();
		inner.put(key, value);
		Map<String, Object> out = new HashMap<>();
		out.put(map, inner);
		return out;
	}

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

	@Test
	public void aLaterReadingIsPlay()
	{
		LocalStore store = mounted();
		kill(store, "Kurask", 3);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Kurask", 1_624L), null);
		store.takePendingAdjust();
		kill(store, "Kurask", 2);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Kurask", 1_630L), null);
		boolean corrected = store.hasPendingAdjust();
		assertFalse("a second reading was taken for a correction: "
			+ store.takePendingAdjust().kcs, corrected);
	}

	@Test
	public void aFallIsACorrection()
	{
		LocalStore store = mounted();
		store.setCharacter(RSN, null, 0, clog("kcs", "Wintertodt", 1_078L), null);
		store.takePendingAdjust();
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Wintertodt", 447L), null);
		assertEquals(Long.valueOf(-631L), store.takePendingAdjust().kcs.get("Wintertodt"));
	}

	@Test
	public void aChatLinesFirstWordKeepsItsOwnKill()
	{
		LocalStore store = mounted();
		kill(store, "Vorkath", 1);
		store.noteKillCount("Vorkath", 157, RSN);
		assertEquals(Long.valueOf(155L), store.takePendingAdjust().kcs.get("Vorkath"));
		kill(store, "Vorkath", 1);
		store.noteKillCount("Vorkath", 158, RSN);
		assertFalse(store.hasPendingAdjust());
	}

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
		rows.add(new LocalStore.SourceRow("Wintertodt", 0, 15, 0L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		return rows;
	}

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
		ledger.add(new LocalStore.SourceRow("Bloodveld", 0, 504, 0L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		ledger.add(new LocalStore.SourceRow("Zalcano", 2_023, 2_024, 0L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
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
		assertEquals(5_792L, LocalStore.spineKills(cl, ledger, got, LocalStore.KILLS_VERSION));
	}

	@Test
	public void theStepIsWorkedOutOverTheSameJournal() throws Exception
	{
		writeJournal();
		LocalStore store = mounted();
		HistoryLog.Adjust shift = store.definitionShift(0);
		assertEquals(Long.valueOf(-30L), shift.kcs.get("Wintertodt"));
		assertEquals(Long.valueOf(-30L), shift.counters.get("kills"));
		assertNull("no step from the version in hand", store.definitionShift(1).kcs.get("Wintertodt"));
	}

	private void writeJournal() throws Exception
	{
		Files.write(new File(dir, LocalStore.slug(RSN) + ".json").toPath(),
			("{\"schema\":1,\"rsn\":\"" + RSN + "\",\"drops\":{\"Wintertodt\":{\"kc\":0,"
				+ "\"loots\":15,\"value\":0,\"items\":{}}},\"collection_log\":"
				+ "{\"kcs\":{\"Wintertodt\":50},\"slayer_kcs\":{\"Wintertodt\":20}},"
				+ "\"trackers\":{},\"skills\":{},\"feed\":[]}").getBytes(StandardCharsets.UTF_8));
	}

	private void spineLine(String json) throws Exception
	{
		Files.write(new File(dir, LocalStore.slug(RSN) + HistoryLog.SPINE_SUFFIX).toPath(),
			(json + "\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void anUnstampedLineIsTheHubsAndLoadLaysTheStep() throws Exception
	{
		writeJournal();
		spineLine("{\"date\":\"2026-09-20\",\"kcs\":{\"Wintertodt\":50},\"counters\":{\"kills\":50}}");
		LocalStore store = mounted();
		assertEquals("laid by before the store is ready, so no line can slip in first",
			Long.valueOf(-30L), store.takePendingAdjust().kcs.get("Wintertodt"));
	}

	@Test
	public void aLineUnderThisReckoningLaysNothing() throws Exception
	{
		writeJournal();
		spineLine("{\"date\":\"2026-09-20\",\"kcs\":{\"Wintertodt\":20},\"kv\":"
			+ LocalStore.KILLS_VERSION + "}");
		assertFalse(mounted().hasPendingAdjust());
	}

	@Test
	public void aCorrectionSurvivesAClosedClientOnce() throws Exception
	{
		writeJournal();
		spineLine("{\"date\":\"2026-09-20\",\"kcs\":{\"Wintertodt\":50}}");
		LocalStore store = mounted();
		store.flush(dir);
		LocalStore again = mounted();
		assertEquals(Long.valueOf(-30L), again.takePendingAdjust().kcs.get("Wintertodt"));
	}

	@Test
	public void aPageCounterThenAStatementIsACorrection()
	{
		LocalStore store = mounted();
		store.setCharacter(RSN, null, 0, clog("kcs", "Tempoross", 46L), null);
		store.takePendingAdjust();
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Tempoross", 455L), null);
		HistoryLog.Adjust adj = store.takePendingAdjust();
		assertEquals(Long.valueOf(409L), adj.kcs.get("Tempoross"));
		assertNull("no loot seen from it, so it is outside the kills sum",
			adj.counters.get("kills"));
	}

	@Test
	public void aPagesLabelledLineIsAStatement()
	{
		LocalStore store = mounted();
		store.setCharacter(RSN, null, 0, clog("kcs", "Grotesque Guardians", 1L), null);
		store.takePendingAdjust();
		Map<String, Object> line = new HashMap<>();
		line.put("Grotesque Guardian kills", 17L);
		store.setCharacter(RSN, null, 0, clog("kc_lines", "Grotesque Guardians", line), null);
		assertEquals(Long.valueOf(16L), store.takePendingAdjust().kcs.get("Grotesque Guardians"));
	}

	@Test
	public void aFightReFiledUnderTheGamesSpellingIsStillCorrected()
	{
		LocalStore store = mounted();
		kill(store, "Abyssal demon", 30);
		long was = store.spineExtras().get("kills");
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Abyssal demons", 45L), null);
		HistoryLog.Adjust adj = store.takePendingAdjust();
		assertEquals(Long.valueOf(15L), adj.kcs.get("Abyssal demons"));
		long now = store.spineExtras().get("kills");
		assertEquals("the sum's whole step is the correction", Long.valueOf(now - was),
			adj.counters.get("kills"));
		assertEquals(15L, now - was);
	}

	@Test
	public void aKillOnAReSpelledFightMovesTheSum()
	{
		LocalStore store = mounted();
		kill(store, "Gargoyle", 5);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Gargoyles", 2_175L), null);
		store.takePendingAdjust();
		long was = store.spineExtras().get("kills");
		assertEquals(2_175L, was);
		kill(store, "Gargoyle", 3);
		assertEquals(3L, store.spineExtras().get("kills") - was);
	}

	@Test
	public void aHandedBackCorrectionWaitsForTheWriteInterval()
	{
		LocalStore store = mounted();
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Tempoross", 455L), null);
		store.setCharacter(RSN, null, 0, clog("kcs", "Wintertodt", 1_078L), null);
		store.setCharacter(RSN, null, 0, clog("slayer_kcs", "Wintertodt", 447L), null);
		assertTrue(store.hasFreshAdjust());
		HistoryLog.Adjust taken = store.takePendingAdjust();
		assertFalse(store.hasFreshAdjust());
		store.restorePendingAdjust(RSN, taken);
		assertTrue(store.hasPendingAdjust());
		assertFalse(store.hasFreshAdjust());
		store.takePendingAdjust();
		store.restorePendingAdjust("Someone Else", taken);
		assertFalse(store.hasPendingAdjust());
	}
}
