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

	@Test
	public void theChatCountOvertakesAFrozenKillLog()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Wintertodt", 447L);
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("subdued Wintertodt", 448L);
		LocalStore.foldChatCounts(out, chat, java.util.Collections.emptySet());
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
		LocalStore.foldChatCounts(out, chat, java.util.Collections.emptySet());
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
		chat.put("Gauntlet", 32L);
		chat.put("Barrows", 512L);
		chat.put("Tempoross", 456L);
		LocalStore.foldChatCounts(out, chat, java.util.Collections.emptySet());
		assertEquals(Long.valueOf(32), out.get("The Gauntlet"));
		assertEquals(Long.valueOf(512), out.get("Barrows Chests"));
		assertEquals(Long.valueOf(456), out.get("Tempoross"));
		assertEquals("a name was carried in under a second spelling", 3, out.size());
	}

	@Test
	public void aSourceNothingElseKnowsYetIsStillCarriedIn()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		Map<String, Long> chat = new LinkedHashMap<>();
		chat.put("Amoxliatl", 1L);
		LocalStore.foldChatCounts(out, chat, java.util.Collections.emptySet());
		assertEquals(Long.valueOf(1), out.get("Amoxliatl"));
	}

	@Test
	public void foldingNothingChangesNothing()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("Zulrah", 600L);
		LocalStore.foldChatCounts(out, new LinkedHashMap<>(), java.util.Collections.emptySet());
		LocalStore.foldChatCounts(out, null, java.util.Collections.emptySet());
		assertEquals(1, out.size());
		assertEquals(Long.valueOf(600), out.get("Zulrah"));
	}
}
