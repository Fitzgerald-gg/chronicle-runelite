/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import com.google.gson.JsonObject;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Tallies tiles covered on foot (split run vs walk) and every teleport, attributed to
 * its destination.
 *
 * <p>Teleport methods share no common animation (the portal nexus fires none this code
 * can key off), so a teleport is spotted by its effect: the player moves far in a single
 * tick. Credit for that jump is gated on a recent teleport-initiating click, which keeps
 * logins, world hops, instance entries and staircases out of the count and supplies the
 * destination label. Fairy rings have their own animation and credit straight off it.
 *
 * <p>Some clicks only open a menu the destination is chosen from: a cape's "Teleport"
 * opens the scrollable list, a rubbed ring or amulet the chatbox options, the POH
 * jewellery box its own interface. The opener arms the means, the row chosen re-arms
 * with the place, and while the menu is on screen the pending waits rather than
 * expiring, so a slow choice or one made from the keyboard still lands in the window.
 * Closing the interface instead, or walking out of the house, drops it.
 *
 * <p>Run and walk are split off the step itself: two tiles in a tick can only be a run.
 * Only a one-tile step is ambiguous, and it falls back on the run toggle and a non-empty
 * energy bar, so the residual error is a single tile at a time and it leans one way -
 * a player with run on and a sliver of energy too small to spend walks, and that tile
 * books as a run. Run is therefore very slightly over-counted, never under-counted.
 */
public class MovementStatTracker implements StatTracker
{
	// A player covers two tiles in a tick only by running; walking is always one.
	private static final int RUN_STEP_TILES = 2;

	private static final int FAIRY_RING_ANIM = 3265;

	// How long a teleport click stays armed. The slowest cast animates ~5 ticks
	// before the move.
	private static final int TELEPORT_PENDING_WINDOW_TICKS = 10;
	// A pending whose destination is still to be chosen waits far longer than one
	// already cast. Six seconds is not long enough to read a list of ten places,
	// and the client shows some of those lists in interfaces this code cannot
	// name, so it cannot rely on seeing one open to know it should wait.
	private static final int MENU_PENDING_WINDOW_TICKS = 50;

	// One-tick move (Chebyshev tiles) that counts as a teleport landing on its own.
	// Sits above the agility-shortcut ceiling (~8-10 tiles) so a grapple or dive can't
	// consume a stale pending.
	private static final int TELEPORT_MIN_JUMP = 15;

	// A teleport is attributed to the FIRST substring that occurs in the click label
	// (option + target) or a menu row's text. Substring rather than exact, since a
	// nexus row is keybind-prefixed ("5 :  Camelot"). ORDER IS LOAD-BEARING: any
	// substring that contains another comes first, and a diary switch destination
	// (Grand Exchange, Seers', Yanille) comes before its base town. Some places carry
	// a second alias where the nexus row and the spell/item name differ. The rows,
	// and the teleport-jewellery family by item name, live in counters_teleports.json.
	private static final JsonObject TELEPORTS = Tables.load("counters_teleports.json");
	private static final Map<String, String> DESTINATIONS = Tables.map(TELEPORTS, "destinations");
	private static final String[] JEWELLERY = Tables.strings(TELEPORTS.get("jewellery"));

	private final StatStore statStore;
	private final Client client;
	// only to name the item a click was made on: an item operation carries the item
	// in its id and leaves the target empty
	private final ItemManager itemManager;

	// player tile last tick, for measuring this tick's step
	private WorldPoint lastPlayerPos;

	// the click behind a teleport that hasn't landed yet: its destination label, the
	// tick it was armed, and whether it came from the nexus (an unrecognised nexus
	// place still counts as Nexus)
	private String pendingLabel;
	private int pendingTick = -1;
	private boolean pendingFromNexus;
	// jewellery/tablet/scroll/spell/cape, or null when the click doesn't reveal it
	private String pendingMethod;
	// Far longer than TELEPORT_PENDING_WINDOW_TICKS: the rub menu can sit open as long as
	// the player likes before a row is picked, and nothing moves until one is.
	private static final int RUB_MENU_WINDOW_TICKS = 25;
	// tick of the last "Rub" on teleport jewellery; the destination arrives as a
	// chat-menu row click shortly after. The pending itself is what lets that row
	// through; this is the gate for a row whose own arm was missed. -1 = idle
	private int rubTick = -1;

	public MovementStatTracker(StatStore statStore, Client client, ItemManager itemManager)
	{
		this.statStore = statStore;
		this.client = client;
		this.itemManager = itemManager;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption() == null ? "" : event.getMenuOption();
		String target = event.getMenuTarget() == null ? "" : event.getMenuTarget();
		String optLow = option.toLowerCase(java.util.Locale.ROOT);
		String tgtLow = Text.removeTags(target).toLowerCase(java.util.Locale.ROOT);

		// walking drops the pending. Left armed, a cancelled cast gets consumed by
		// whatever region hop comes next and credits the wrong method.
		if (optLow.equals("walk here"))
		{
			clearPending();
			return;
		}

		int group = event.getWidgetId() >> 16;

		// the close button of a teleport interface (the nexus list, the jewellery box):
		// the player chose nothing and the box is gone, so whatever its opener armed is
		// dropped rather than left for the next house exit to claim. The nexus's Close
		// is a static child of its group, which the row branch below would otherwise
		// read as an empty row and arm as Nexus.
		if (optLow.equals("close")
			&& (group == InterfaceID.TELENEXUS_TELEPORT || group == InterfaceID.POH_JEWELLERY_BOX))
		{
			clearPending();
			return;
		}

		// a row click in the nexus teleport list
		if (group == InterfaceID.TELENEXUS_TELEPORT)
		{
			armTeleport(nexusRowText(event.getParam0()), true);
			return;
		}

		// the POH jewellery box lists its destinations as text rows. Which child holds
		// them is not pinned down (the six sections DUELING..GLORY, or a layer of the
		// box's own), so any row in the group whose text names a place arms, whatever
		// it sits under; the scroll furniture names none and arms nothing, and Close
		// returned above with the opener's arm dropped. A row the table can't place
		// still credits the total and the means through the box's opener, armed
		// further down.
		if (group == InterfaceID.POH_JEWELLERY_BOX)
		{
			String row = rowLabel(event, optLow, tgtLow);
			if (matchDestinationKey(row) != null)
			{
				armTeleport(row, false);
				pendingMethod = "teleportsViaJewellery";
			}
			return;
		}

		// A row of the list the destination is chosen from. The chatbox options a
		// rubbed ring shows are group 219 and the scrollable list is 187, but a
		// cape's own list need not be either of them, and naming every interface
		// the client might use is a game of catch-up. So any click at all is
		// offered to the chooser while a teleport is waiting to be told where it
		// went: it takes the click only when a pending is open and the row names
		// a place the table knows, and leaves it alone otherwise.
		if (group == InterfaceID.MENU || group == InterfaceID.CHATMENU)
		{
			chooseMenuRow(rowLabel(event, optLow, tgtLow), true);
			return;
		}
		if (teleportPending() && awaitsAMenu()
			&& chooseMenuRow(rowLabel(event, optLow, tgtLow), false))
		{
			return;
		}

		// the jewellery box itself. Its rows arrive as group 590 clicks above, but a
		// row chosen from the keyboard posts none, so the "Teleport Menu" opener arms
		// the means on its own and a recognised row re-arms with the place. An option
		// that names a place outright (should the box offer one) arms the same way.
		if (tgtLow.contains("jewellery box"))
		{
			if (optLow.equals("teleport menu") || matchDestinationKey(optLow) != null)
			{
				armTeleport(optLow + " " + tgtLow, false);
				pendingMethod = "teleportsViaJewellery";
			}
			return;
		}

		// these only open the nexus list; the row click above carries the destination
		if (optLow.equals("teleport to") || optLow.equals("teleport menu"))
		{
			return;
		}

		// a POH "Teleport Platform" is house stairs under a teleport-sounding label
		if (tgtLow.contains("teleport platform"))
		{
			return;
		}

		// bank/inventory handling of an item whose name carries "teleport"
		if (isInventoryManagement(optLow))
		{
			return;
		}

		// a left-click on the nexus with a primary destination set teleports straight
		// away, no list interface: the option text is the place itself ("Last Boat",
		// "Great Kourend"). "Configuration" opens the destination editor, the one nexus
		// option to skip. The openers and Examine returned above.
		if (tgtLow.contains("portal nexus"))
		{
			if (!optLow.contains("configuration"))
			{
				armTeleport(optLow, true);
			}
			return;
		}

		// a POH portal-chamber portal: option "Enter", target "<Place> Portal", with no
		// "tele" anywhere for the generic arming below to catch. Requiring the table to
		// know the place excludes the bare house exit and Clan Wars' "Free-for-all
		// portal". No method family fits a house portal; it stays null.
		if (optLow.equals("enter") && tgtLow.endsWith("portal") && matchDestinationKey(tgtLow) != null)
		{
			armTeleport(tgtLow, false);
			return;
		}

		// the world's house portal names no place at all (target just "Portal"), so the
		// gate above can't see it. The same click inside the house is the exit, on
		// foot: no teleport can still be in flight, so a pending left by a jewellery
		// box dismissed from the keyboard is dropped there rather than credited to the
		// exit's scene rebuild.
		if (tgtLow.equals("portal")
			&& (optLow.equals("enter") || optLow.equals("home")
			|| optLow.equals("build mode") || optLow.equals("friend's house")))
		{
			if (client.isInInstancedRegion())
			{
				clearPending();
				return;
			}
			armTeleport("house", false);
			return;
		}

		// spirit trees say "tele" nowhere; the option is "Travel"
		if ((tgtLow.contains("spirit tree")
			&& (optLow.startsWith("travel") || optLow.startsWith("last-destination")))
			|| (tgtLow.contains("spiritual fairy tree") && optLow.startsWith("travel")))
		{
			armTeleport("spirit tree", false);
			return;
		}

		// An item whose right-click names the place outright. Read off a live
		// client: a construction cape teleport to Pollnivneach arrives as
		// option "Pollnivneach", target EMPTY, on the inventory interface, with
		// the item in the click's own item id. There is no "Teleport" click and
		// no list to choose from; the destinations are options of their own. An
		// earlier fix tested the target for "cape", which is empty here, so it
		// could never match and every one of these went uncounted.
		// "Home" is the cape's first destination and names the player's house.
		String place = isHomeRow(optLow) ? "house" : optLow;
		if (matchDestinationKey(place) != null
			&& !isWearHandling(optLow)
			&& (tgtLow.isEmpty() || event.isItemOp() || isTeleportCape(tgtLow)))
		{
			// The target is what this must NOT lean on. isItemOp() is not it
			// either: the client only returns true there when the entry's
			// identifier falls inside a 1-to-7 switch, and a sub-option's
			// identifier is built as ((sub + 1) << 16) | (op + 1), which never
			// does. What was actually observed is an option naming a place and
			// a target holding nothing, so that is what is tested.
			//
			// The means is read off the item, since the click names it nowhere.
			// Where the id cannot be resolved the place and the total still
			// count, which is the part that matters.
			String item = tgtLow.isEmpty() ? itemName(event.getItemId()) : tgtLow;
			armTeleport(place, false);
			pendingMethod = methodOf(optLow, item);
			return;
		}

		// any spell/tab/cape/item teleport. The destination can sit on either half: a
		// spell's "Cast <place>", a tab's "<place> teleport". Arm with both joined and
		// let the table find it.
		if (optLow.contains("tele") || tgtLow.contains("tele"))
		{
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = methodOf(optLow, tgtLow);
			return;
		}

		// worn or rubbed teleport jewellery never says "tele" either; the option is the
		// destination itself ("Castle Wars" on a ring of dueling). Arm off the item name.
		if (isTeleportJewellery(tgtLow) && !isWearHandling(optLow))
		{
			if (optLow.startsWith("rub"))
			{
				// remember the rub so the destination row click just after is
				// recognised. The arm below stands in if that click is missed.
				rubTick = client.getTickCount();
			}
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = "teleportsViaJewellery";
			return;
		}

		// a few items say "tele" nowhere at all (the Ectophial's option is "Empty", the
		// Royal seed pod's is "Commune"), so arm off the item name. No method family.
		if (isNamedTeleportItem(tgtLow) && !isWearHandling(optLow))
		{
			armTeleport(optLow + " " + tgtLow, false);
		}
	}

	// the capes and hoods that carry teleports; methodOf reads the same names
	private static boolean isTeleportCape(String tgtLow)
	{
		return tgtLow.contains("cape") || tgtLow.contains("max hood");
	}

	// the name of the item a click was made on, lower-cased, or "" when the id
	// names nothing. An item operation leaves the target empty, so this is the
	// only way to tell a cape from a ring.
	private String itemName(int itemId)
	{
		if (itemId <= 0 || itemManager == null)
		{
			return "";
		}
		try
		{
			return itemManager.getItemComposition(itemManager.canonicalize(itemId))
				.getName().toLowerCase(java.util.Locale.ROOT);
		}
		catch (RuntimeException e)   // an id the cache cannot name
		{
			return "";
		}
	}

	// items whose activating option names neither "tele" nor a jewellery family
	private static boolean isNamedTeleportItem(String tgtLow)
	{
		return tgtLow.contains("ectophial") || tgtLow.contains("royal seed pod");
	}

	// the teleport-jewellery family, by item name
	private static boolean isTeleportJewellery(String tgtLow)
	{
		for (String j : JEWELLERY)
		{
			if (tgtLow.contains(j))
			{
				return true;
			}
		}
		return false;
	}

	// wearing, removing or checking jewellery isn't teleporting with it
	private static boolean isWearHandling(String option)
	{
		return option.startsWith("wear") || option.startsWith("wield")
			|| option.startsWith("remove") || option.startsWith("check")
			|| option.startsWith("destroy") || isInventoryManagement(option);
	}

	// The means of travel, ticked alongside the destination: a jewellery hop to
	// Castle Wars bumps both.
	private static String methodOf(String optLow, String tgtLow)
	{
		if (optLow.startsWith("break"))
		{
			return "teleportsViaTablet";
		}
		// the whole word: a spell's option is "Cast" or "Cast <spell>", and
		// "Castle Wars" on a ring of dueling is a place, not a spellbook
		if (optLow.equals("cast") || optLow.startsWith("cast "))
		{
			return "teleportsViaSpell";
		}
		if (tgtLow.contains("scroll"))
		{
			return "teleportsViaScroll";
		}
		if (isTeleportCape(tgtLow) || optLow.contains("tele to poh"))
		{
			return "teleportsViaCape";
		}
		if (isTeleportJewellery(tgtLow))
		{
			return "teleportsViaJewellery";
		}
		return null;
	}

	// bank/inventory verbs that name a teleport item without activating it
	private static boolean isInventoryManagement(String option)
	{
		return option.startsWith("withdraw") || option.startsWith("deposit")
			|| option.startsWith("examine") || option.startsWith("drop")
			|| option.startsWith("value") || option.startsWith("take")
			|| option.startsWith("bank") || option.startsWith("sell")
			|| option.startsWith("buy") || option.startsWith("use");
	}

	private void armTeleport(String label, boolean fromNexus)
	{
		pendingLabel = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
		pendingFromNexus = fromNexus;
		pendingTick = client.getTickCount();
		pendingMethod = null;   // callers that know the means set it after arming
	}

	// a chat-menu row chosen while a teleport is pending names its destination: the
	// pending re-arms with the row's text and keeps its means. "Home" in a cape's
	// list is the player's house. A row the table can't place leaves the pending as
	// it is, so the landing still credits the total and the method. A rub whose own
	// arm was missed is the one case that arms from nothing, gated on rubTick; the
	// rub's menu is answered by exactly one row, so that gate closes on whichever row
	// it is, matched or not ("Nowhere" is the glory's own cancel).
	private boolean chooseMenuRow(String row, boolean fromChatMenu)
	{
		boolean rubbed = rubTick >= 0 && client.getTickCount() - rubTick <= RUB_MENU_WINDOW_TICKS;
		if (!teleportPending() && !rubbed)
		{
			return false;
		}
		// a spirit tree's list is stops on one network, like fairy rings: the hop
		// stays credited to the tree, whatever place the row names
		if (teleportPending() && "spirit tree".equals(pendingLabel))
		{
			return false;
		}
		String place = isHomeRow(row) ? "house" : row;
		boolean placed = matchDestinationKey(place) != null;
		// The rub's own menu is answered by exactly one row, so its gate closes on
		// whichever row that is. One naming no place is the cancel: "Nowhere" is
		// the glory's, and nothing was cast, so the pending goes with it. A click
		// from anywhere else is only a guess at the choice and leaves it alone.
		if (fromChatMenu && rubbed)
		{
			rubTick = -1;
			if (!placed)
			{
				clearPending();
				return false;
			}
		}
		if (!placed)
		{
			return false;
		}
		if (rubbed)
		{
			rubTick = -1;
		}
		String method = pendingMethod != null ? pendingMethod
			: (rubbed ? "teleportsViaJewellery" : null);
		armTeleport(place, false);
		pendingMethod = method;
		return true;
	}

	// the word "home" on its own in a row, whatever keybind prefix or click verb
	// sits around it ("Home", "1 :  Home", "home continue")
	private static boolean isHomeRow(String row)
	{
		return row.matches("(?s)(?:.*\\W)?home(?:\\W.*)?");
	}

	// the text a clicked row carries, lower-cased: its widget child's text joined with
	// the click's own option and target, so the place is found wherever the row
	// keeps it. The row text leads, so its place is the one a substring scan sees
	// first when the click's target names an item as well.
	private String rowLabel(MenuOptionClicked event, String optLow, String tgtLow)
	{
		String row = menuRowText(event.getWidgetId(), event.getParam0()).toLowerCase(java.util.Locale.ROOT);
		return (row + " " + optLow + " " + tgtLow).trim();
	}

	// how far along an interface to look for the list that holds a row's label
	private static final int MENU_CHILD_SCAN = 24;

	/**
	 * The label of row {@code index}, for a list whose rows may be bare hitboxes.
	 * The nexus is built that way already: you click ROWS and the words live at
	 * the same index of a TEXT1 list beside it. A cape's list is the same shape,
	 * so reading only the clicked component finds no place, and the teleport was
	 * credited to its means with nowhere against it. The clicked component is
	 * still read first; failing that, the interface is walked for a list carrying
	 * a place at that index.
	 */
	private String menuRowText(int componentId, int index)
	{
		String own = widgetChildText(componentId, index);
		if (index < 0 || matchDestinationKey(own.toLowerCase(java.util.Locale.ROOT)) != null)
		{
			return own;
		}
		int group = componentId >>> 16;
		for (int child = 0; child < MENU_CHILD_SCAN; child++)
		{
			String beside = rowOfList((group << 16) | child, index);
			if (!beside.isEmpty() && matchDestinationKey(beside.toLowerCase(java.util.Locale.ROOT)) != null)
			{
				return beside;
			}
		}
		return own;
	}

	// text at `index` of a component's own child list, and nothing else. The
	// component's own text is no use here: a header reading "Varrock" would answer
	// for every row in the list.
	private String rowOfList(int componentId, int index)
	{
		Widget w = client.getWidget(componentId);
		if (w == null)
		{
			return "";
		}
		Widget[] kids = w.getChildren();
		if (kids == null || index >= kids.length || kids[index] == null
			|| kids[index].getText() == null)
		{
			return "";
		}
		return Text.removeTags(kids[index].getText()).trim();
	}

	// whether the pending is one whose destination is chosen from a chat menu
	private boolean awaitsAMenu()
	{
		return "teleportsViaCape".equals(pendingMethod)
			|| "teleportsViaJewellery".equals(pendingMethod)
			|| rubTick >= 0
			|| "spirit tree".equals(pendingLabel);
	}

	// whether a chat menu is on screen: the chatbox options (group 219), the
	// scrollable list (group 187) or the newer list (group 947). Only asked while a
	// teleport is pending, so an idle tick consults no widget. Group 187 names no
	// UNIVERSE; its child 0 is LJ_LAYER2 in gameval naming, and the list layer
	// LJ_LAYER1 is read as well in case the root's visibility reads differently
	// live.
	private boolean chatMenuOpen()
	{
		return showing(InterfaceID.Chatmenu.UNIVERSE)
			|| showing(InterfaceID.Menu.LJ_LAYER2) || showing(InterfaceID.Menu.LJ_LAYER1)
			// the client has a third list interface, MENU_NEW (947), which this
			// never tested for. Its rows are the nexus shape: you click GRAPHICS
			// and the words sit at the same index of TEXT beside it.
			|| showing(InterfaceID.MenuNew.UNIVERSE) || showing(InterfaceID.MenuNew.CONTENT);
	}

	private boolean showing(int componentId)
	{
		Widget w = client.getWidget(componentId);
		return w != null && !w.isHidden();
	}

	// tag-stripped text of a clicked component's child, falling back to the
	// component's own text
	private String widgetChildText(int compositeId, int index)
	{
		Widget w = client.getWidget(compositeId);
		if (w == null)
		{
			return "";
		}
		Widget[] kids = w.getChildren();
		if (kids != null && index >= 0 && index < kids.length
			&& kids[index] != null && kids[index].getText() != null)
		{
			return Text.removeTags(kids[index].getText()).trim();
		}
		return w.getText() == null ? "" : Text.removeTags(w.getText()).trim();
	}

	// destination name for a clicked nexus row. The ROWS widget you click is a bare
	// hitbox; the label sits at the same index in the parallel TEXT1 list, keybind
	// prefixed ("5 :  Camelot"), which the substring match ignores.
	private String nexusRowText(int index)
	{
		return index < 0 ? "" : rowOfList(InterfaceID.TelenexusTeleport.TEXT1, index);
	}

	@Override
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		WorldPoint current = local.getWorldLocation();
		if (lastPlayerPos != null && current != null)
		{
			// Chebyshev distance. A normal step covers 1-2 tiles.
			int step = Math.max(
				Math.abs(current.getX() - lastPlayerPos.getX()),
				Math.abs(current.getY() - lastPlayerPos.getY()));
			boolean regionChanged = current.getRegionID() != lastPlayerPos.getRegionID();
			// a region change with any real step counts too, which catches cross-region
			// teleports while leaving a 1-2 tile walk over a boundary alone
			boolean jumped = step >= TELEPORT_MIN_JUMP || (regionChanged && step >= 3);

			if (step > 0 && step < 3)
			{
				statStore.incrementStatBy(isRunStep(step) ? "distanceRan" : "distanceWalked", step);
			}
			else if (jumped && teleportPending())
			{
				creditPendingTeleport();
			}
		}

		lastPlayerPos = current;

		// a chat menu holding the destination choice (a cape's list, a rubbed item's
		// options, a spirit tree's stops) sits open as long as the player likes, and a
		// row picked from the keyboard posts no click at all. While one is on screen
		// the pending waits, so the landing still falls inside the window. Only a
		// pending that opens such a menu waits: a cast that never landed must not
		// ride out an unrelated dialogue and claim the next region hop.
		if (pendingTick >= 0 && awaitsAMenu() && chatMenuOpen())
		{
			pendingTick = client.getTickCount();
		}

		// expire a pending that never landed: a cancelled cast, a non-teleport nexus
		// click. Left standing it attaches itself to whatever movement comes next.
		if (pendingTick >= 0 && client.getTickCount() - pendingTick > pendingWindow())
		{
			clearPending();
		}
	}

	private void clearPending()
	{
		pendingLabel = null;
		pendingTick = -1;
		pendingFromNexus = false;
		pendingMethod = null;
	}

	private boolean teleportPending()
	{
		return pendingTick >= 0 && client.getTickCount() - pendingTick <= pendingWindow();
	}

	// How long a pending stands. One already cast lands within a few ticks or
	// never; one still waiting to be told where it went waits on a reader, and
	// six seconds is not long enough to read a list of ten places.
	private int pendingWindow()
	{
		return awaitsAMenu() ? MENU_PENDING_WINDOW_TICKS : TELEPORT_PENDING_WINDOW_TICKS;
	}

	// credit the pending teleport to its place, or to the Nexus catch-all
	private void creditPendingTeleport()
	{
		statStore.incrementStat("teleportsTotal");
		String key = matchDestinationKey(pendingLabel);
		String credited;
		if (key != null)
		{
			credited = key;
		}
		else if (pendingFromNexus)
		{
			credited = "teleportsNexus";   // a nexus place with no key of its own
		}
		else
		{
			credited = null;              // an unrecognised teleport: total only
		}
		if (credited != null)
		{
			statStore.incrementStat(credited);
		}
		if (pendingMethod != null)
		{
			statStore.incrementStat(pendingMethod);   // the means, beside the place
		}
		clearPending();
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// a scene rebuild fires for every cross-region and instance hop, including the
		// boat, whose instanced arrival the position jump can't see. Whichever of the
		// two fires first credits and clears the pending, so a teleport that triggers
		// both still counts once.
		if (event.getGameState() == GameState.LOADING && teleportPending())
		{
			creditPendingTeleport();
		}
	}

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		// fires for every actor, including the stranger teleporting beside you
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		if (event.getActor().getAnimation() == FAIRY_RING_ANIM)
		{
			statStore.incrementStat("teleportsTotal");
			statStore.incrementStat("teleportsFairyRing");
			// the ring is the journey. Drop any stale pending before it claims this
			// landing as well
			clearPending();
		}
	}

	/**
	 * Whether a one-tick step of {@code step} tiles was run rather than walked.
	 *
	 * <p>Two tiles in a tick is running, unconditionally: nothing else in the game moves
	 * a player that far on foot, so no toggle or energy reading is allowed to overrule
	 * it. This used to be decided by reading the run orb's sprite, and every way that
	 * read could come back empty - orb not built yet, hidden by a layout, a sprite not
	 * set on the tick sampled - silently booked a run as a walk.
	 *
	 * <p>One tile is the genuinely ambiguous case: a walk, the last tile of a run path,
	 * or running on an empty bar. It is settled from the game's own state - the run
	 * toggle, and energy above zero, since run switched on with nothing left in the bar
	 * still walks.
	 */
	private boolean isRunStep(int step)
	{
		if (step >= RUN_STEP_TILES)
		{
			return true;
		}
		// getEnergy() is hundredths of a percent, 0-10000
		return client.getVarpValue(VarPlayerID.OPTION_RUN) == 1 && client.getEnergy() > 0;
	}

	/**
	 * The teleport counter key a label maps to, or null if it names no tracked place.
	 * First matching DESTINATIONS substring wins, so a nexus hop lands on the same key
	 * as the spell or tab to that place.
	 */
	static String matchDestinationKey(String label)
	{
		if (label == null)
		{
			return null;
		}
		String clean = label.toLowerCase(java.util.Locale.ROOT);
		for (Map.Entry<String, String> d : DESTINATIONS.entrySet())
		{
			if (clean.contains(d.getKey()))
			{
				return d.getValue();
			}
		}
		return null;
	}
}
