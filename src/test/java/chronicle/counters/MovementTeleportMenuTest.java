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
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static chronicle.counters.MovementStatTracker.matchDestinationKey;
import static chronicle.counters.StatKeys.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Teleports chosen from a menu in MovementStatTracker: the scrollable list a cape's
 * "Teleport" opens (group 187), the chatbox options a rubbed item shows (group 219), and
 * the POH jewellery box (group 590). The opener arms the means, the row chosen re-arms
 * with the place, and while a chat menu is on screen the pending does not expire, so a
 * slow choice or a keyboard-picked row still lands inside the window. Closing the
 * interface, or walking out of the house, drops it: no phantom on the exit.
 *
 * <p>The clock is the mocked tick count; a "jump" is a one-tick move far past
 * TELEPORT_MIN_JUMP, which is what a landing looks like to the tracker.
 */
public class MovementTeleportMenuTest
{
	// a child of the jewellery box outside the six sections DUELING..GLORY, in case the
	// live interface keeps its rows under a layer of its own
	private static final int JEWELLERY_BOX_OTHER_CHILD = InterfaceID.PohJewelleryBox.GLORY + 1;

	private StatStore store;
	private Client client;
	private Player local;
	private MovementStatTracker tracker;
	private int tick;
	private int landingX = 2694;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		local = Mockito.mock(Player.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(local);
		tracker = new MovementStatTracker(store, client);
		at(0);
		standAt(3200, 3200);   // settle the tracker's last position
	}

	// set the clock
	private void at(int t)
	{
		tick = t;
		Mockito.when(client.getTickCount()).thenReturn(t);
	}

	// let the ticks pass, the player standing still, up to and including `upTo`
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

	// a landing: the player is far from where they stood a tick ago. Each landing
	// takes a fresh tile, so two in a row both read as jumps.
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

	// a click on row `index` of the component `componentId`, whose child there reads
	// `text`. param1 is the component, param0 the row, as the client posts them.
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

	// a click on a static child of an interface, no row under it: the close button,
	// which the client posts with param0 -1
	private void closeClick(int componentId)
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn("Close");
		Mockito.when(entry.getTarget()).thenReturn("");
		Mockito.when(entry.getParam0()).thenReturn(-1);
		Mockito.when(entry.getParam1()).thenReturn(componentId);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	// a scene rebuild: what a house exit, an instance hop or a boat looks like
	private void loadingAt(int t)
	{
		at(t);
		GameStateChanged e = new GameStateChanged();
		e.setGameState(GameState.LOADING);
		tracker.onGameStateChanged(e);
	}

	// put a menu's root on screen, or take it down
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

	// every teleport counter credited, the total and the means left out: the places
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

	/**
	 * A click on a row that is only a hitbox: the row you click carries no words
	 * and the label sits at the same index of a list beside it, the way the
	 * nexus's ROWS and TEXT1 are built.
	 */
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

	// the nth component of the menu's own interface that no other stub in this
	// test has taken, in the order the scan walks them
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

	// a component with no child list at all, carrying one line of its own: a
	// title, which answers for every index if anything lets it
	private void headerReading(int componentId, String text)
	{
		Widget w = Mockito.mock(Widget.class);
		Mockito.when(w.getChildren()).thenReturn(null);
		Mockito.when(w.getText()).thenReturn(text);
		Mockito.when(client.getWidget(componentId)).thenReturn(w);
	}

	// a list beside the rows carrying `text` at `index`
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
		// a real menu is more than two components: a title above it, a column of
		// keybind numbers, then the labels. The place must come from the row's own
		// index in the list that holds places, not from the first text anywhere in
		// the interface.
		headerReading(freeMenuComponent(0), "Varrock");          // a title, no rows
		listReading(freeMenuComponent(1), 3, "4:");              // the keybind column
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
		// report 2, raised twice: every construction cape teleport but the house
		// one landed with nowhere against it. The cape's list is built like the
		// nexus, a column of bare hitboxes with the words in a list beside them,
		// so reading only the row that was clicked found no place and the teleport
		// was credited to its means alone.
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
		// the scan reads one index, not the whole list: a row the record cannot
		// place still credits the teleport and its means, and claims no other
		// row's destination
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
		// report 1: "Teleport" on the construction cape opens the list, the player takes
		// twelve ticks over it, picks Taverley, lands. The menu on screen holds the
		// pending open; the row re-arms with the place and keeps the cape as the means.
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
		// the pending alone lets the row through: no rub, no menu on screen
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
		// no row click at all: the list is on screen until tick 13 and the player lands
		// at 14. The total and the cape are known; the place is not.
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
		// a laggy server shows the list only at tick 11. The refresh runs before the
		// expiry check, so the pending is still there for the list to hold.
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
		// tolerant of the live layout: the root may read hidden while the list layer shows
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
		// a rubbed ring of dueling shows its places in the chatbox (group 219)
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
		// the rub gate: no menu on screen, the rub's own arm is twelve ticks stale, and
		// the row still lands as jewellery to its place
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
		// "Tele to POH" is already the house; a row naming nothing must not unseat it
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
		// an ordinary dialogue naming a town, no teleport in flight: no phantom
		at(1);
		rowClick(InterfaceID.Chatmenu.OPTIONS, 0, "Varrock", "Continue");
		jumpAt(2);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VARROCK));
	}

	@Test
	public void aPendingStillExpiresWhenNoMenuIsOnScreen()
	{
		// the refresh is only for a menu that is showing; a cancelled cape click ages out
		click("Teleport", "Construct. cape(t)");
		idleUntil(11);
		jumpAt(12);

		assertEquals(0, stat(TELEPORTS_TOTAL));
		assertEquals(0, stat(TELEPORTS_VIA_CAPE));
	}

	@Test
	public void aMenuRootPresentButHiddenDoesNotHoldThePending()
	{
		// a root the client keeps loaded but off screen is not a menu the player is in
		click("Teleport", "Construct. cape(t)");
		Widget root = Mockito.mock(Widget.class);
		Mockito.when(root.isHidden()).thenReturn(true);
		Mockito.when(client.getWidget(InterfaceID.Menu.LJ_LAYER2)).thenReturn(root);
		idleUntil(11);
		jumpAt(12);

		assertEquals(0, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void aChatMenuRowAfterALandedRubArmsNothing()
	{
		// the rub gate closes with the row it was for: a dialogue a few ticks after the
		// landing must not arm off the old rub
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
		// report 2: left-click "Teleport Menu" opens the box, the row sits under a child
		// outside DUELING..GLORY, and the place is still read off its text
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
		// the layout the handler always knew: a row under one of the six sections
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.SKILLS, 1, "Mining Guild", "Teleport");
		jumpAt(2);

		assertEquals(1, stat(TELEPORTS_MINING_GUILD));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
	}

	@Test
	public void jewelleryBoxCloseArmsNothing()
	{
		// Close names no place, and the house exit that follows is not a teleport
		at(1);
		rowClick(InterfaceID.PohJewelleryBox.FRAME, 0, "Close", "Close");
		jumpAt(2);

		assertEquals(0, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxOpenerAloneCreditsTotalAndJewellery()
	{
		// a row picked from the keyboard posts no click: the opener's own arm credits
		click("Teleport Menu", "Fancy jewellery box");
		jumpAt(3);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_JEWELLERY));
		assertEquals(0, places().size());
	}

	@Test
	public void jewelleryBoxCloseDropsTheOpenersPending()
	{
		// Close is the player choosing nothing: the opener's arm goes with the box, so
		// the house exit that follows is not a teleport
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
		// the exit portal is a room from the box, and its scene rebuild fires well
		// inside the window
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
		// Esc posts no click at all. The exit portal's own click inside the house is
		// on foot, and it drops the opener's arm before the rebuild
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
		// the same click outside is the way in, and a teleport
		at(1);
		click("Enter", "Portal");
		loadingAt(3);

		assertEquals(1, stat(TELEPORTS_HOUSE));
		assertEquals(1, stat(TELEPORTS_TOTAL));
	}

	@Test
	public void jewelleryBoxNamesNoPlaceOfItsOwn()
	{
		// the opener's label must not fall on a table row by accident
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
		// the list's close button is a static child of group 17 (param0 -1, under the
		// frame); read as a row it would arm an empty label as Nexus, and the house
		// exit after it would credit the phantom
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
		// the tree's list is stops on one network, like fairy rings: a hop to the
		// Grand Exchange is a spirit-tree hop, not a Grand Exchange teleport
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
		// "Nowhere" is the glory's own cancel. The rub gate closes on it, so a town
		// named in some dialogue thirteen ticks later arms nothing off the old rub
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
		// the nexus list is not a chat menu and keeps its own path
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
		// a blocked Varrock cast, then an NPC dialogue kept open past the window, then
		// a region hop on foot: nothing the cast can claim
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
		// the guard above must not take the cape's list away from it
		click("Teleport", "Construct. cape(t)");
		menuShowing(InterfaceID.Menu.LJ_LAYER2, true);
		idleUntil(30);
		menuShowing(InterfaceID.Menu.LJ_LAYER2, false);
		jumpAt(31);

		assertEquals(1, stat(TELEPORTS_TOTAL));
		assertEquals(1, stat(TELEPORTS_VIA_CAPE));
	}
}
