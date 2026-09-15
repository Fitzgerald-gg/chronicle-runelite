/*
 * Copyright (c) 2026, Chronicle - BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The kill count the game announces on the kill itself. It is the only reading
 * that is both the game's own count and arrives without the player opening
 * anything, which is what the plugin is supposed to be: invisible after setup.
 */
public class ChatKillCountTest
{
	private ChronicleEventCapture capture;
	private LocalStore store;

	@Before
	public void setUp()
	{
		Client client = Mockito.mock(Client.class);
		store = Mockito.mock(LocalStore.class);
		Player me = Mockito.mock(Player.class);
		Mockito.when(me.getName()).thenReturn("Tester");
		Mockito.when(client.getLocalPlayer()).thenReturn(me);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(store.isReadyFor("Tester")).thenReturn(true);
		capture = new ChronicleEventCapture(client, Mockito.mock(ClientThread.class),
			Mockito.mock(ConfigManager.class), Mockito.mock(ChronicleConfig.class),
			Mockito.mock(ChronicleApiClient.class), store);
	}

	private void said(String line)
	{
		capture.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", line, null, 0));
	}

	@Test
	public void theCountIsBankedWithoutAnyLootEvent()
	{
		// Wintertodt's reward is a crate, not an NPC drop, so no loot event ever
		// follows. The count used to be parsed into an in-memory map read only
		// when stamping a drop, and was therefore discarded on every single kill,
		// which is why Wintertodt sat on a Kill Log reading of 447 that only moved
		// when the player opened an interface.
		said("Your subdued Wintertodt count is: 448.");
		Mockito.verify(store).noteKillCount("subdued Wintertodt", 448, "Tester");
	}

	@Test
	public void everyShapeTheGameUsesIsBanked()
	{
		said("Your Zulrah kill count is: 501.");
		said("Your completed Theatre of Blood: Hard Mode count is: 40.");
		said("Your Gauntlet completion count is: 32.");
		said("Your Barrows chest count is: 512.");
		said("Your Yama success count is: 10.");
		Mockito.verify(store).noteKillCount("Zulrah", 501, "Tester");
		Mockito.verify(store).noteKillCount("Theatre of Blood: Hard Mode", 40, "Tester");
		Mockito.verify(store).noteKillCount("Gauntlet", 32, "Tester");
		Mockito.verify(store).noteKillCount("Barrows", 512, "Tester");
		Mockito.verify(store).noteKillCount("Yama", 10, "Tester");
	}

	@Test
	public void aLapAndAHarvestAreNotKills()
	{
		// The expression admits these because they annotate a loot event the same
		// way, but an agility lap is not a kill and their sources are followed by
		// the loot ledger already. Banking them would put a course in the kill
		// counts.
		said("Your Ape Atoll Agility lap count is: 1337.");
		said("Your Herbiboar harvest count is: 1169.");
		Mockito.verify(store, Mockito.never())
			.noteKillCount(Mockito.anyString(), Mockito.anyInt(), Mockito.anyString());
	}

	@Test
	public void nothingIsBankedBeforeTheJournalIsMounted()
	{
		Mockito.when(store.isReadyFor("Tester")).thenReturn(false);
		said("Your Zulrah kill count is: 501.");
		Mockito.verify(store, Mockito.never())
			.noteKillCount(Mockito.anyString(), Mockito.anyInt(), Mockito.anyString());
	}

	// ── the reading side ───────────────────────────────────────────────────

	@Test
	public void theChatCountOvertakesAFrozenKillLog()
	{
		// The Kill Log said 447 when the player last opened it. One kill later the
		// game itself has said 448, and that is the answer.
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Wintertodt", 447L);
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("subdued Wintertodt", 448L);
		LocalStore.foldChatCounts(out, chat);
		assertEquals(Long.valueOf(448), out.get("Wintertodt"));
		assertEquals("it was carried in twice, under two names", 1, out.size());
	}

	@Test
	public void anOlderChatReadingNeverPullsACountBack()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Zulrah", 600L);
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("Zulrah", 501L);
		LocalStore.foldChatCounts(out, chat);
		assertEquals(Long.valueOf(600), out.get("Zulrah"));
	}

	@Test
	public void theNamesTheGameUsesFindTheirSource()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("The Gauntlet", 31L);
		out.put("Barrows Chests", 500L);
		out.put("Tempoross", 455L);
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("Gauntlet", 32L);          // the log says "The Gauntlet"
		chat.put("Barrows", 512L);          // the log says "Barrows Chests"
		chat.put("Tempoross", 456L);        // said the same way
		LocalStore.foldChatCounts(out, chat);
		assertEquals(Long.valueOf(32), out.get("The Gauntlet"));
		assertEquals(Long.valueOf(512), out.get("Barrows Chests"));
		assertEquals(Long.valueOf(456), out.get("Tempoross"));
		assertEquals("a name was carried in under a second spelling", 3, out.size());
	}

	@Test
	public void aSourceNothingElseKnowsYetIsStillCarriedIn()
	{
		// A first kill is announced in chat before there is a log page or a loot
		// row to hang it on. Waiting for one would mean a fresh install shows
		// nothing until it opens an interface, which is the whole complaint.
		Map<String, Long> out = new LinkedHashMap<>();
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("Amoxliatl", 1L);
		LocalStore.foldChatCounts(out, chat);
		assertEquals(Long.valueOf(1), out.get("Amoxliatl"));
	}

	@Test
	public void foldingNothingChangesNothing()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Zulrah", 600L);
		LocalStore.foldChatCounts(out, new LinkedHashMap<>());
		LocalStore.foldChatCounts(out, null);
		assertEquals(1, out.size());
		assertEquals(Long.valueOf(600), out.get("Zulrah"));
	}
}
