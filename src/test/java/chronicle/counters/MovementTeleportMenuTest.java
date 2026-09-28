/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static chronicle.counters.MovementStatTracker.matchDestinationKey;
import static chronicle.counters.CounterTestKeys.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MovementTeleportMenuTest
{
	private static final int JEWELLERY_BOX_OTHER_CHILD = InterfaceID.PohJewelleryBox.GLORY + 1;

	private StatStore store;
	private Client client;
	private Player local;
	private MovementStatTracker tracker;
	private ItemManager items;
	private int tick;
	private int landingX = 2694;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		items = Mockito.mock(ItemManager.class);
		local = Mockito.mock(Player.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(local);
		tracker = new MovementStatTracker(store, client, items);
		at(0);
		standAt(3200, 3200);
	}

	private void at(int t)
	{
		tick = t;
		Mockito.when(client.getTickCount()).thenReturn(t);
	}

	private void idleUntil(int upTo)
	{
		while (tick < upTo)
		{
			at(tick + 1);
			tracker.onGameTick(new GameTick());
		}
	}

	private void standAt(int x, int y)
	{
		Mockito.when(local.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		tracker.onGameTick(new GameTick());
	}

	private void jumpAt(int t)
	{
		at(t);
		landingX += 200;
		standAt(landingX, 3465);
	}

	private void click(String option, String target)
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getTarget()).thenReturn(target);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void rowClick(int componentId, int index, String text, String option)
	{
		Widget row = Mockito.mock(Widget.class);
		Mockito.when(row.getText()).thenReturn(text);
		Widget[] kids = new Widget[index + 1];
		kids[index] = row;
		Widget layer = Mockito.mock(Widget.class);
		Mockito.when(layer.getChildren()).thenReturn(kids);
		Mockito.when(client.getWidget(componentId)).thenReturn(layer);
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(index);
		Mockito.when(entry.getParam1()).thenReturn(componentId);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void closeClick(int componentId)
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn("Close");
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(-1);
		Mockito.when(entry.getParam1()).thenReturn(componentId);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void loadingAt(int t)
	{
		at(t);
		GameStateChanged e = new GameStateChanged();
		e.setGameState(GameState.LOADING);
		tracker.onGameStateChanged(e);
	}

	private void menuShowing(int rootId, boolean open)
	{
		if (!open)
		{
			Mockito.when(client.getWidget(rootId)).thenReturn(null);
			return;
		}
		Widget root = Mockito.mock(Widget.class);
		Mockito.when(root.isHidden()).thenReturn(false);
		Mockito.when(client.getWidget(rootId)).thenReturn(root);
	}

	private int stat(String key)
	{
		return store.getStat(key);
	}

	private Map<String, Integer> places()
	{
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<String, Integer> e : store.snapshotAll().entrySet())
		{
			String k = e.getKey();
			if (k.startsWith("teleports") && !k.equals(TELEPORTS_TOTAL) && !k.startsWith("teleportsVia")
				&& e.getValue() > 0)
			{
				out.put(k, e.getValue());
			}
		}
		return out;
	}

	private void hitboxRowClick(int clickedId, int textId, int index, String text,
		String option)
	{
		Widget bare = Mockito.mock(Widget.class);
		Mockito.when(bare.getText()).thenReturn("");
		Widget[] bareKids = new Widget[index + 1];
		bareKids[index] = bare;
		Widget clicked = Mockito.mock(Widget.class);
		Mockito.when(clicked.getChildren()).thenReturn(bareKids);
		Mockito.when(client.getWidget(clickedId)).thenReturn(clicked);

		Widget cell = Mockito.mock(Widget.class);
		Mockito.when(cell.getText()).thenReturn(text);
		Widget[] cells = new Widget[index + 1];
		cells[index] = cell;
		Widget list = Mockito.mock(Widget.class);
		Mockito.when(list.getChildren()).thenReturn(cells);
		Mockito.when(client.getWidget(textId)).thenReturn(list);

		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(index);
		Mockito.when(entry.getParam1()).thenReturn(clickedId);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private static int freeMenuComponent(int nth)
	{
		int group = InterfaceID.Menu.LJ_LAYER1 >>> 16;
		int found = 0;
		for (int child = 0; ; child++)
		{
			int id = (group << 16) | child;
			if (id == InterfaceID.Menu.LJ_LAYER1 || id == InterfaceID.Menu.LJ_LAYER2)
			{
				continue;
			}
			if (found++ == nth)
			{
				return id;
			}
		}
	}

	private void headerReading(int componentId, String text)
	{
		Widget w = Mockito.mock(Widget.class);
		Mockito.when(w.getChildren()).thenReturn(null);
		Mockito.when(w.getText()).thenReturn(text);
		Mockito.when(client.getWidget(componentId)).thenReturn(w);
	}

	private void listReading(int componentId, int index, String text)
	{
		Widget cell = Mockito.mock(Widget.class);
		Mockito.when(cell.getText()).thenReturn(text);
		Widget[] cells = new Widget[index + 1];
		cells[index] = cell;
		Widget list = Mockito.mock(Widget.class);
		Mockito.when(list.getChildren()).thenReturn(cells);
		Mockito.when(client.getWidget(componentId)).thenReturn(list);
	}

	@Test
	public void theRowsPlaceBeatsAHeadersTextAndAKeybindColumn()
	{
		headerReading(freeMenuComponent(0), "Varrock");
		listReading(freeMenuComponent(1), 3, "4:");
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		hitboxRowClick(InterfaceID.Menu.LJ_LAYER1, freeMenuComponent(2), 3,
			"Hosidius", "Continue");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(4);

		assertEquals(1, stat(TELEPORTS_HOSIDIUS));
		assertEquals(0, stat(TELEPORTS_VARROCK));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aRowThatIsOnlyAHitboxTakesItsPlaceFromTheListBesideIt()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(6);
		hitboxRowClick(InterfaceID.Menu.LJ_LAYER1, freeMenuComponent(0), 3,
			"Hosidius", "Continue");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(7);

		assertEquals(1, stat(TELEPORTS_HOSIDIUS));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aListBesideTheRowsNamesNoPlaceForARowThatIsNotThere()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		hitboxRowClick(InterfaceID.Menu.LJ_LAYER1, freeMenuComponent(0), 2,
			"Somewhere the table has never heard of", "Continue");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(4);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(places().toString(), 0, places().size());
	}

	@Test
	public void capeListRowChosenAfterTheWindowStillCreditsItsPlace()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(12);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 2, "Taverley", "Continue");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(13);

		assertEquals(1, stat(TELEPORTS_TAVERLEY));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void capeListRowInsideTheWindowNeedsNoRubAndNoMenuWidget()
	{
		click("Teleport", "Max cape");
		at(4);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 5, "Rellekka", "Continue");
		jumpAt(6);

		assertEquals(1, stat(TELEPORTS_RELLEKKA));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void keyboardChoiceFromTheCapeListCreditsTotalAndMeans()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(13);
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(14);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(0, places().size());
	}

	@Test
	public void aMenuThatOpensLateStillHoldsThePending()
	{
		click("Teleport", "Construct. cape(t)");
		idleUntil(10);
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(15);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 2, "Taverley", "Continue");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(16);

		assertEquals(1, stat(TELEPORTS_TAVERLEY));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void theListLayerAloneShowingHoldsThePendingToo()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER1, true);
		idleUntil(13);
		menuShowing(InterfaceID.Menu.LJ_LAYER1, false);
		jumpAt(14);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void homeInTheCapeListIsTheHouse()
	{
		click("Teleport", "Construct. cape(t)");
		at(2);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 0, "Home", "Continue");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void homeRowKeepsItsMeaningUnderAKeybindPrefix()
	{
		click("Teleport", "Max cape");
		at(2);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 0, "1 :  Home", "Continue");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void chatboxRowAfterARubCreditsPlaceAndJewellery()
	{
		click("Rub", "Ring of dueling(8)");
		at(2);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 1, "Castle Wars", "Continue");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_CASTLE_WARS));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void chatboxRowAfterARubWhoseArmExpiredStillArmsOffTheRub()
	{
		click("Rub", "Amulet of glory(6)");
		idleUntil(12);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 0, "Edgeville", "Continue");
		jumpAt(13);

		assertEquals(1, stat(TELEPORTS_EDGEVILLE));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void keyboardChoiceFromTheChatboxCreditsTotalAndJewellery()
	{
		click("Rub", "Games necklace(8)");
		menuShowing(InterfaceID.Chatmenu.UNIVERSE, true);
		idleUntil(20);
		menuShowing(InterfaceID.Chatmenu.UNIVERSE, false);
		jumpAt(21);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(0, places().size());
	}

	@Test
	public void aRowTheTableCannotPlaceLeavesThePendingAsItIs()
	{
		click("Tele to POH", "Construct. cape(t)");
		at(2);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 3, "Somewhere new", "Continue");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aChatMenuRowWithNothingPendingArmsNothing()
	{
		at(1);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 0, "Varrock", "Continue");
		jumpAt(2);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VARROCK));
	}

	@Test
	public void aPendingStillExpiresWhenNoMenuIsOnScreen()
	{
		click("Teleport", "Construct. cape(t)");
		idleUntil(51);
		jumpAt(52);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void aMenuRootPresentButHiddenDoesNotHoldThePending()
	{
		click("Teleport", "Construct. cape(t)");
		Widget root = Mockito.mock(Widget.class);
		Mockito.when(root.isHidden()).thenReturn(true);
		Mockito.when(client.getWidget(InterfaceID.Menu.LJ_LAYER2)).thenReturn(root);
		idleUntil(51);
		jumpAt(52);

		assertEquals(0, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aChatMenuRowAfterALandedRubArmsNothing()
	{
		click("Rub", "Ring of dueling(8)");
		at(2);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 1, "Castle Wars", "Continue");
		jumpAt(3);
		at(8);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 0, "Varrock", "Continue");
		jumpAt(9);

		assertEquals(1, stat(TELEPORTS_CASTLE_WARS));
		assertEquals(0, stat(TELEPORTS_VARROCK));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxRowUnderAnotherChildStillCredits()
	{
		click("Teleport Menu", "Ornate jewellery box");
		at(1);
		rowClick(JEWELLERY_BOX_OTHER_CHILD, 4, "Edgeville", "Teleport");
		jumpAt(2);

		assertEquals(1, stat(TELEPORTS_EDGEVILLE));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxRowUnderTheFrameCreditsAsWell()
	{
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.FRAME, 7, "Castle Wars", "Teleport");
		jumpAt(2);

		assertEquals(1, stat(TELEPORTS_CASTLE_WARS));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void jewelleryBoxSectionRowStillCredits()
	{
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.SKILLS, 1, "Mining Guild", "Teleport");
		jumpAt(2);

		assertEquals(1, stat(TELEPORTS_MINING_GUILD));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void jewelleryBoxCloseArmsNothing()
	{
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.FRAME, 0, "Close", "Close");
		jumpAt(2);

		assertEquals(0, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxOpenerAloneCreditsTotalAndJewellery()
	{
		click("Teleport Menu", "Fancy jewellery box");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(0, places().size());
	}

	@Test
	public void jewelleryBoxCloseDropsTheOpenersPending()
	{
		click("Teleport Menu", "Basic jewellery box");
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.FRAME, 0, "Close", "Close");
		jumpAt(2);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void jewelleryBoxClosedThenTheHouseExitRebuildCreditsNothing()
	{
		click("Teleport Menu", "Ornate jewellery box");
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.FRAME, 0, "Close", "Close");
		loadingAt(8);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void jewelleryBoxDismissedFromTheKeyboardThenTheHouseExitCreditsNothing()
	{
		click("Teleport Menu", "Ornate jewellery box");
		Mockito.when(client.isInInstancedRegion()).thenReturn(true);
		at(3);
		click("Enter", "Portal");
		loadingAt(8);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(0, stat(TELEPORTS_HOUSE));
	}

	@Test
	public void theHousePortalOutsideStillArmsTheHouse()
	{
		at(1);
		click("Enter", "Portal");
		loadingAt(3);

		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxNamesNoPlaceOfItsOwn()
	{
		assertNull(matchDestinationKey("teleport menu ornate jewellery box"));
		assertNull(matchDestinationKey("teleport menu fancy jewellery box"));
		assertNull(matchDestinationKey("teleport menu basic jewellery box"));
	}

	@Test
	public void nexusOpenersStillArmNothing()
	{
		click("Teleport Menu", "Portal Nexus");
		jumpAt(2);
		click("Teleport to", "Portal Nexus");
		jumpAt(4);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_NEXUS));
	}

	@Test
	public void nexusCloseButtonArmsNothing()
	{
		click("Teleport Menu", "Portal Nexus");
		at(1);
		closeClick(InterfaceID.TelenexusTeleport.FRAME);
		loadingAt(8);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_NEXUS));
	}

	@Test
	public void aSpiritTreeStopStaysCreditedToTheTree()
	{
		click("Travel", "Spirit tree");
		at(2);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 3, "4 :  Grand Exchange", "Continue");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_SPIRIT_TREE));
		assertEquals(0, stat(TELEPORTS_GRAND_EXCHANGE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aRubAnsweredByNowhereIsSpent()
	{
		click("Rub", "Amulet of glory(6)");
		at(2);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 4, "Nowhere", "Continue");
		idleUntil(14);
		at(15);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 0, "Varrock", "Continue");
		jumpAt(17);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VARROCK));
		assertEquals(0, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void nexusRowStillCreditsThroughItsOwnList()
	{
		Widget cell = Mockito.mock(Widget.class);
		Mockito.when(cell.getText()).thenReturn("5 :  Camelot");
		Widget textList = Mockito.mock(Widget.class);
		Mockito.when(textList.getChildren()).thenReturn(new Widget[] {null, null, cell});
		Mockito.when(client.getWidget(InterfaceID.TelenexusTeleport.TEXT1)).thenReturn(textList);
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn("Teleport");
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(2);
		Mockito.when(entry.getParam1()).thenReturn(InterfaceID.TelenexusTeleport.ROWS1);
		at(1);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
		jumpAt(2);

		assertEquals(1, stat(TELEPORTS_CAMELOT));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aCastThatNeverLandedDoesNotRideOutAnUnrelatedDialogue()
	{
		click("Cast", "Varrock Teleport");
		menuShowing(InterfaceID.Chatmenu.UNIVERSE, true);
		idleUntil(30);
		menuShowing(InterfaceID.Chatmenu.UNIVERSE, false);
		jumpAt(36);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VARROCK));
	}

	@Test
	public void aCapeChoiceStillWaitsOnItsOwnList()
	{
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(30);
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(31);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void handlingACapeIsNotTeleportingWithIt()
	{
		click("Wear", "Construct. cape(t)");
		click("Taverley", "Oak plank");
		jumpAt(3);
		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(places().toString(), 0, places().size());
	}

	@Test
	public void aCapeListTakesAsLongAsItTakesToRead()
	{
		click("Teleport", "Construct. cape(t)");
		idleUntil(28);
		rowClick(InterfaceID.Menu.LJ_LAYER1, 3, "Pollnivneach", "Continue");
		jumpAt(29);

		assertEquals(1, stat(TELEPORTS_POLLNIVNEACH));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void theChoiceCountsFromWhicheverInterfaceTheClientShowsIt()
	{
		int elsewhere = (90 << 16) | 4;
		click("Teleport", "Construct. cape(t)");
		rowClick(elsewhere, 2, "Taverley", "Continue");
		jumpAt(6);

		assertEquals(1, stat(TELEPORTS_TAVERLEY));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void aClickNamingNoPlaceLeavesThePendingAsItWas()
	{
		int elsewhere = (90 << 16) | 4;
		click("Teleport", "Construct. cape(t)");
		rowClick(elsewhere, 1, "Close", "Continue");
		rowClick(elsewhere, 2, "Rellekka", "Continue");
		jumpAt(6);

		assertEquals(1, stat(TELEPORTS_RELLEKKA));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aCastThatNeverLandedStillExpires()
	{
		click("Cast", "Varrock Teleport");
		idleUntil(14);
		jumpAt(15);
		assertEquals(0, stat(TELEPORTS_TOTAL));
	}

	private void itemOpClick(String option, int itemId, String itemName)
	{
		if (itemName != null)
		{
			net.runelite.api.ItemComposition comp =
				Mockito.mock(net.runelite.api.ItemComposition.class);
			Mockito.when(comp.getName()).thenReturn(itemName);
			Mockito.when(items.canonicalize(itemId)).thenReturn(itemId);
			Mockito.when(items.getItemComposition(itemId)).thenReturn(comp);
		}
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getItemId()).thenReturn(itemId);
		Mockito.when(entry.isItemOp()).thenReturn(false);
		Mockito.when(entry.getParam0()).thenReturn(27);
		Mockito.when(entry.getParam1()).thenReturn((149 << 16));
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	@Test
	public void aCapeWhoseOptionIsThePlaceCountsIt()
	{
		itemOpClick("Pollnivneach", 9789, "Construct. cape(t)");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_POLLNIVNEACH));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void theMeansIsReadOffTheItemSinceTheClickDoesNotNameIt()
	{
		itemOpClick("Castle Wars", 2552, "Ring of dueling(8)");
		jumpAt(3);
		assertEquals(1, stat(TELEPORTS_CASTLE_WARS));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(0, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void handlingAnItemIsNotTeleportingWithIt()
	{
		itemOpClick("Wear", 9789, "Construct. cape(t)");
		itemOpClick("Drop", 9789, "Construct. cape(t)");
		itemOpClick("Examine", 9789, "Construct. cape(t)");
		jumpAt(4);
		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(places().toString(), 0, places().size());
	}

	@Test
	public void theCapesHomeOptionIsThePlayersHouse()
	{
		itemOpClick("Home", 9789, "Construct. cape(t)");
		jumpAt(3);
		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void theClickIsRecognisedWithoutTheClientCallingItAnItemOp()
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn("Rellekka");
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(27);
		Mockito.when(entry.getParam1()).thenReturn(149 << 16);
		Mockito.when(entry.getItemId()).thenReturn(0);
		Mockito.when(entry.isItemOp()).thenReturn(false);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_RELLEKKA));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}
}