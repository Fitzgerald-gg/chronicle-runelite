/*
 * Copyright (c) 2026, Chronicle - BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A stated count plus what has been seen since it. The Kill Log is read by
 * opening an interface, so on its own it is frozen: Abyssal demons sat on 1,798
 * while the game itself had reached 2,523. Held beside the ledger's own count
 * at the moment it was read, it moves again without anything being opened.
 */
public class AnchoredKillCountTest
{
	private static final String RSN = "Tester";
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-anchor").toFile();
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

	/** One kill that dropped something, under the ledger's spelling. */
	private void kill(LocalStore store, String source, Integer stated)
	{
		JsonObject d = new JsonObject();
		d.addProperty("source", source);
		if (stated != null)
		{
			d.addProperty("killCount", stated);
		}
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", 592);
		it.addProperty("quantity", 1);
		items.add(it);
		d.add("items", items);
		store.record("LOOT", d, RSN);
	}

	@Test
	public void aStatedCountKeepsMovingOnItsOwn()
	{
		LocalStore store = mounted();
		for (int i = 0; i < 5; i++)
		{
			kill(store, "Abyssal demon", null);
		}
		// the Kill Log is opened and says 2,523 -- everything, including kills
		// made before this journal existed and kills that dropped nothing
		store.anchorKill("Abyssal demons", 2_523, "log", RSN);
		assertEquals(Long.valueOf(2_523), store.anchoredKills().get("Abyssal demons"));

		// four more kills, no interface opened
		for (int i = 0; i < 4; i++)
		{
			kill(store, "Abyssal demon", null);
		}
		assertEquals("the count froze the moment the interface closed",
			Long.valueOf(2_527), store.anchoredKills().get("Abyssal demons"));
	}

	@Test
	public void theKillItWasStatedAtIsNotCountedTwice()
	{
		// The chat box speaks BEFORE the drop lands: "Your Sarachnis kill count
		// is: 52" arrives, then the loot event for that same kill. Counting that
		// row as a kill SINCE would read 53.
		LocalStore store = mounted();
		kill(store, "Sarachnis", 51);
		store.noteKillCount("Sarachnis", 52, RSN);
		kill(store, "Sarachnis", 52);        // the lagging row for kill 52
		assertEquals(Long.valueOf(52), store.anchoredKills().get("Sarachnis"));

		// and the next kill still counts
		kill(store, "Sarachnis", 53);
		assertEquals(Long.valueOf(53), store.anchoredKills().get("Sarachnis"));
	}

	@Test
	public void aSlayerTaskCounterIsNotMistakenForTheStatedKill()
	{
		// A slayer monster's loot row carries its TASK counter, not a lifetime
		// count. If that were read as "the kill the anchor was taken at", every
		// slayer kill would be absorbed and the number would never move again.
		LocalStore store = mounted();
		store.anchorKill("Nechryael", 1_234, "log", RSN);
		kill(store, "Nechryael", 12);
		kill(store, "Nechryael", 13);
		assertEquals(Long.valueOf(1_236), store.anchoredKills().get("Nechryael"));
	}

	@Test
	public void theLootTrackerImportIsNotHundredsOfKills()
	{
		LocalStore store = mounted();
		store.anchorKill("Zulrah", 500, "log", RSN);
		LocalStore.LootSeed seed = new LocalStore.LootSeed("Zulrah", 430, 0, 0,
			new java.util.ArrayList<>());
		store.floorLootTracker(java.util.Collections.singletonList(seed), RSN);
		assertEquals("an import read as four hundred kills since",
			Long.valueOf(500), store.anchoredKills().get("Zulrah"));
	}

	@Test
	public void aJournalWithFewerObservationsIsNotNegativeKills() throws Exception
	{
		// carried to a machine that watched less of it, or restored from a backup
		LocalStore store = mounted();
		for (int i = 0; i < 6; i++)
		{
			kill(store, "Vorkath", null);
		}
		store.anchorKill("Vorkath", 156, "log", RSN);
		store.flush(dir);
		// the journal comes back from a backup that watched two kills, not six
		File f = new File(dir, "tester.json");
		String json = new String(java.nio.file.Files.readAllBytes(f.toPath()),
			java.nio.charset.StandardCharsets.UTF_8).replace("\"loots\":6", "\"loots\":2");
		java.nio.file.Files.write(f.toPath(),
			json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		LocalStore back = mounted();
		assertEquals(Long.valueOf(156), back.anchoredKills().get("Vorkath"));
	}

	@Test
	public void aSecondReadingOfTheSameNumberDoesNotThrowAwayWhatCameSinceIt()
	{
		LocalStore store = mounted();
		store.anchorKill("Vorkath", 156, "log", RSN);
		kill(store, "Vorkath", null);
		kill(store, "Vorkath", null);
		assertEquals(Long.valueOf(158), store.anchoredKills().get("Vorkath"));
		// the Kill Log is opened again and still reads 156, because it was read
		// before those two landed. Re-baselining here would lose them.
		store.anchorKill("Vorkath", 156, "log", RSN);
		assertEquals(Long.valueOf(158), store.anchoredKills().get("Vorkath"));
	}

	@Test
	public void theChatBoxOutranksTheKillLogAndMayCorrectItDownward()
	{
		LocalStore store = mounted();
		store.anchorKill("Zulrah", 600, "log", RSN);
		// the game itself then says otherwise on the kill; a dated reading
		// superseding a dated reading, not a guess at a maximum
		store.anchorKill("Zulrah", 501, "chat", RSN);
		assertEquals(Long.valueOf(501), store.anchoredKills().get("Zulrah"));
		// and the older kind may not displace it again
		store.anchorKill("Zulrah", 600, "log", RSN);
		assertEquals(Long.valueOf(501), store.anchoredKills().get("Zulrah"));
	}

	@Test
	public void oneFightIsOneRowHoweverItIsSpelled()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Abyssal demon", 2_346L);      // the ledger's spelling
		Map<String, Long> stated = new LinkedHashMap<>();
		stated.put("Abyssal demons", 1_798L);  // the Kill Log's
		LocalStore.placeByKind(out, stated, true);
		assertEquals("a stale reading pulled a live count down",
			Long.valueOf(2_346), out.get("Abyssal demon"));
		assertEquals("the same monster is listed twice", 1, out.size());
	}

	@Test
	public void anAnchoredCountReplacesWhereABareOneOnlyFloors()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Zalcano", 2_024L);            // the ledger, one row above the truth
		Map<String, Long> anchored = new HashMap<>();
		anchored.put("Zalcano", 2_023L);       // stated, and knows what came after
		LocalStore.placeByKind(out, anchored, false);
		assertEquals(Long.valueOf(2_023), out.get("Zalcano"));
	}

	@Test
	public void nothingIsAnchoredForAJournalThatIsNotMounted()
	{
		LocalStore store = mounted();
		store.anchorKill("Zulrah", 500, "log", "Someone Else");
		store.anchorKill("", 500, "log", RSN);
		store.anchorKill("Zulrah", 0, "log", RSN);
		assertTrue(store.anchoredKills().isEmpty());
	}
}
