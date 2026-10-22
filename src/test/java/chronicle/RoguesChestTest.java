package chronicle;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

public class RoguesChestTest
{
	private ChronicleEventCapture capture;
	private Client client;
	private LocalStore store;
	private int tick = 100;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		store = Mockito.mock(LocalStore.class);
		Player me = Mockito.mock(Player.class);
		Mockito.when(me.getName()).thenReturn("Tester");
		Mockito.when(client.getLocalPlayer()).thenReturn(me);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(client.getTickCount()).thenAnswer(i -> tick);
		Mockito.when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		Mockito.when(client.getVarbitValue(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE)).thenReturn(1);
		Mockito.when(client.getItemDefinition(Mockito.anyInt())).thenAnswer(i ->
		{
			int id = i.getArgument(0);
			ItemComposition c = Mockito.mock(ItemComposition.class);
			boolean noted = id == ItemID.UNCUT_EMERALD + 1;
			Mockito.when(c.getNote()).thenReturn(noted ? 799 : -1);
			Mockito.when(c.getLinkedNoteId()).thenReturn(noted ? ItemID.UNCUT_EMERALD : -1);
			return c;
		});
		capture = new ChronicleEventCapture(client, Mockito.mock(ClientThread.class),
			Mockito.mock(ConfigManager.class), Mockito.mock(ChronicleConfig.class),
			Mockito.mock(ChronicleApiClient.class), store);
	}

	private void chat(String line)
	{
		capture.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", line, null, 0));
	}

	private void tracker(String name, ItemStack... items)
	{
		capture.onLootReceived(new LootReceived(name, -1, LootRecordType.EVENT, Arrays.asList(items), 1, null));
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			capture.onGameTick(new GameTick());
		}
	}

	private List<JsonObject> loots()
	{
		ArgumentCaptor<JsonObject> cap = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.atLeast(0)).record(Mockito.eq("LOOT"), cap.capture(), Mockito.eq("Tester"));
		return cap.getAllValues();
	}

	private static Map<Integer, Integer> items(JsonObject loot)
	{
		Map<Integer, Integer> out = new HashMap<>();
		loot.getAsJsonArray("items").forEach(e -> out.merge(e.getAsJsonObject().get("id").getAsInt(),
			e.getAsJsonObject().get("quantity").getAsInt(), Integer::sum));
		return out;
	}

	@Test
	public void anOpeningTheBagSwallowedIsRecordedFromItsLine()
	{
		chat("You find some death runes inside.");
		ticks(8);
		List<JsonObject> l = loots();
		assertEquals(1, l.size());
		assertEquals("Rogues' Chest", l.get(0).get("source").getAsString());
		assertEquals(Map.of(ItemID.DEATHRUNE, 62), items(l.get(0)));
	}

	@Test
	public void theMediumDiaryGivesTheSmallerAmount()
	{
		Mockito.when(client.getVarbitValue(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE)).thenReturn(0);
		chat("You find some coins inside.");
		ticks(8);
		assertEquals(Map.of(ItemID.COINS, 4500), items(loots().get(0)));
	}

	@Test
	public void aReportedOpeningIsRecordedOnceFromTheTracker()
	{
		chat("You find some uncut emeralds inside.");
		ticks(4);
		tracker("Rogues' Chest", new ItemStack(ItemID.UNCUT_EMERALD + 1, 12));
		ticks(8);
		List<JsonObject> l = loots();
		assertEquals(1, l.size());
		assertEquals(Map.of(ItemID.UNCUT_EMERALD + 1, 12), items(l.get(0)));
	}

	@Test
	public void aReportBeforeTheLineStillCountsOnce()
	{
		tracker("Rogues' Chest", new ItemStack(ItemID.COINS, 5625));
		chat("You find some coins inside.");
		ticks(8);
		assertEquals(1, loots().size());
	}

	@Test
	public void theBagAndAVialAreNotChestLoot()
	{
		chat("You find a prayer potion inside.");
		tracker("Rogues' Chest", new ItemStack(ItemID.LOOTING_BAG, 1), new ItemStack(ItemID.VIAL_EMPTY, 1));
		ticks(8);
		List<JsonObject> l = loots();
		assertEquals(1, l.size());
		assertEquals(Map.of(ItemID._2DOSEPRAYERRESTORE, 1), items(l.get(0)));
	}

	@Test
	public void otherChestsLoseOnlyTheLootingBag()
	{
		tracker("Larran's big chest", new ItemStack(ItemID.LOOTING_BAG_OPEN, 1), new ItemStack(ItemID.COINS, 900));
		assertEquals(Map.of(ItemID.COINS, 900), items(loots().get(0)));
	}

	@Test
	public void anUnknownLineOrOneOutsideTheWildernessRecordsNothing()
	{
		chat("You find a clue scroll inside.");
		ticks(8);
		Mockito.when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(0);
		chat("You find some coins inside.");
		ticks(8);
		assertEquals(0, loots().size());
	}

	@Test
	public void backToBackOpeningsEachCount()
	{
		chat("You find some law runes inside.");
		ticks(2);
		chat("You find some coal inside.");
		ticks(8);
		List<JsonObject> l = loots();
		assertEquals(2, l.size());
		assertEquals(Map.of(ItemID.LAWRUNE, 50), items(l.get(0)));
		assertEquals(Map.of(ItemID.COAL, 25), items(l.get(1)));
	}
}
