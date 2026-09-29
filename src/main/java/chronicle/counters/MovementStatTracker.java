/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

@RequiredArgsConstructor
public class MovementStatTracker implements StatTracker
{
	private static final int RUN_STEP_TILES = 2;

	private static final int FAIRY_RING_ANIM = 3265;

	private static final int TELEPORT_PENDING_WINDOW_TICKS = 10;
	private static final int MENU_PENDING_WINDOW_TICKS = 50;

	private static final int TELEPORT_MIN_JUMP = 15;

	private static final JsonObject TELEPORTS = Tables.load("counters_teleports.json");
	private static final Map<String, String> DESTINATIONS = Tables.map(TELEPORTS, "destinations");
	private static final String[] JEWELLERY = Tables.strings(TELEPORTS.get("jewellery"));

	private final StatStore statStore;
	private final Client client;
	private final ItemManager itemManager;

	private WorldPoint lastPlayerPos;

	private String pendingLabel;
	private int pendingTick = -1;
	private boolean pendingFromNexus;
	private String pendingMethod;
	private static final int RUB_MENU_WINDOW_TICKS = 25;
	private int rubTick = -1;

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption() == null ? "" : event.getMenuOption();
		String target = event.getMenuTarget() == null ? "" : event.getMenuTarget();
		String optLow = option.toLowerCase(Locale.ROOT);
		String tgtLow = Text.removeTags(target).toLowerCase(Locale.ROOT);

		if (optLow.equals("walk here"))
		{
			clearPending();
			return;
		}

		int group = event.getWidgetId() >> 16;

		if (optLow.equals("close")
			&& (group == InterfaceID.TELENEXUS_TELEPORT || group == InterfaceID.POH_JEWELLERY_BOX))
		{
			clearPending();
			return;
		}

		if (group == InterfaceID.TELENEXUS_TELEPORT)
		{
			armTeleport(rowOfList(InterfaceID.TelenexusTeleport.TEXT1, event.getParam0()), true);
			return;
		}

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

		if (tgtLow.contains("jewellery box"))
		{
			if (optLow.equals("teleport menu") || matchDestinationKey(optLow) != null)
			{
				armTeleport(optLow + " " + tgtLow, false);
				pendingMethod = "teleportsViaJewellery";
			}
			return;
		}

		if (optLow.equals("teleport to") || optLow.equals("teleport menu")
			|| tgtLow.contains("teleport platform") || isInventoryManagement(optLow))
		{
			return;
		}

		if (tgtLow.contains("portal nexus"))
		{
			if (!optLow.contains("configuration"))
			{
				armTeleport(optLow, true);
			}
			return;
		}

		if (optLow.equals("enter") && tgtLow.endsWith("portal") && matchDestinationKey(tgtLow) != null)
		{
			armTeleport(tgtLow, false);
			return;
		}

		if (tgtLow.equals("portal")
			&& (optLow.equals("enter") || optLow.equals("home")
			|| optLow.equals("build mode") || optLow.equals("friend's house")))
		{
			if (client.isInInstancedRegion())
			{
				clearPending();
			}
			else
			{
				armTeleport("house", false);
			}
			return;
		}

		if ((tgtLow.contains("spirit tree")
			&& (optLow.startsWith("travel") || optLow.startsWith("last-destination")))
			|| (tgtLow.contains("spiritual fairy tree") && optLow.startsWith("travel")))
		{
			armTeleport("spirit tree", false);
			return;
		}

		String place = isHomeRow(optLow) ? "house" : optLow;
		if (matchDestinationKey(place) != null && !isWearHandling(optLow)
			&& (tgtLow.isEmpty() || event.isItemOp() || isTeleportCape(tgtLow)))
		{
			String item = tgtLow.isEmpty() ? itemName(event.getItemId()) : tgtLow;
			armTeleport(place, false);
			pendingMethod = methodOf(optLow, item);
			return;
		}

		if (optLow.contains("tele") || tgtLow.contains("tele"))
		{
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = methodOf(optLow, tgtLow);
			return;
		}

		if (isTeleportJewellery(tgtLow) && !isWearHandling(optLow))
		{
			if (optLow.startsWith("rub"))
			{
				rubTick = client.getTickCount();
			}
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = "teleportsViaJewellery";
			return;
		}

		if (isNamedTeleportItem(tgtLow) && !isWearHandling(optLow))
		{
			armTeleport(optLow + " " + tgtLow, false);
		}
	}

	private static boolean isTeleportCape(String tgtLow)
	{
		return tgtLow.contains("cape") || tgtLow.contains("max hood");
	}

	private String itemName(int itemId)
	{
		if (itemId <= 0 || itemManager == null)
		{
			return "";
		}
		try
		{
			return itemManager.getItemComposition(itemManager.canonicalize(itemId))
				.getName().toLowerCase(Locale.ROOT);
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private static boolean isNamedTeleportItem(String tgtLow)
	{
		return tgtLow.contains("ectophial") || tgtLow.contains("royal seed pod");
	}

	private static boolean isTeleportJewellery(String tgtLow)
	{
		return Arrays.stream(JEWELLERY).anyMatch(tgtLow::contains);
	}

	private static boolean isWearHandling(String option)
	{
		return startsWithAny(option, "wear", "wield", "remove", "check", "destroy")
			|| isInventoryManagement(option);
	}

	private static String methodOf(String optLow, String tgtLow)
	{
		return optLow.startsWith("break") ? "teleportsViaTablet"
			: optLow.equals("cast") || optLow.startsWith("cast ") ? "teleportsViaSpell"
			: tgtLow.contains("scroll") ? "teleportsViaScroll"
			: isTeleportCape(tgtLow) || optLow.contains("tele to poh") ? "teleportsViaCape"
			: isTeleportJewellery(tgtLow) ? "teleportsViaJewellery" : null;
	}

	private static boolean isInventoryManagement(String option)
	{
		return startsWithAny(option, "withdraw", "deposit", "examine", "drop", "value", "take",
			"bank", "sell", "buy", "use");
	}

	private static boolean startsWithAny(String s, String... prefixes)
	{
		return Arrays.stream(prefixes).anyMatch(s::startsWith);
	}

	private void armTeleport(String label, boolean fromNexus)
	{
		pendingLabel = label.toLowerCase(Locale.ROOT);
		pendingFromNexus = fromNexus;
		pendingTick = client.getTickCount();
		pendingMethod = null;
	}

	private boolean chooseMenuRow(String row, boolean fromChatMenu)
	{
		boolean rubbed = rubTick >= 0 && client.getTickCount() - rubTick <= RUB_MENU_WINDOW_TICKS;
		if (!teleportPending() && !rubbed)
		{
			return false;
		}
		if (teleportPending() && "spirit tree".equals(pendingLabel))
		{
			return false;
		}
		String place = isHomeRow(row) ? "house" : row;
		boolean placed = matchDestinationKey(place) != null;
		if (fromChatMenu && rubbed && !placed)
		{
			rubTick = -1;
			clearPending();
			return false;
		}
		if (!placed)
		{
			return false;
		}
		if (rubbed)
		{
			rubTick = -1;
		}
		String method = pendingMethod != null ? pendingMethod : (rubbed ? "teleportsViaJewellery" : null);
		armTeleport(place, false);
		pendingMethod = method;
		return true;
	}

	private static boolean isHomeRow(String row)
	{
		return row.matches("(?s)(?:.*\\W)?home(?:\\W.*)?");
	}

	private String rowLabel(MenuOptionClicked event, String optLow, String tgtLow)
	{
		String row = menuRowText(event.getWidgetId(), event.getParam0()).toLowerCase(Locale.ROOT);
		return (row + " " + optLow + " " + tgtLow).trim();
	}

	private static final int MENU_CHILD_SCAN = 24;

	private String menuRowText(int componentId, int index)
	{
		String own = widgetChildText(componentId, index);
		if (index < 0 || matchDestinationKey(own) != null)
		{
			return own;
		}
		int group = componentId >>> 16;
		for (int child = 0; child < MENU_CHILD_SCAN; child++)
		{
			String beside = rowOfList((group << 16) | child, index);
			if (!beside.isEmpty() && matchDestinationKey(beside) != null)
			{
				return beside;
			}
		}
		return own;
	}

	private String rowOfList(int componentId, int index)
	{
		Widget w = client.getWidget(componentId);
		String kid = w == null ? null : kidText(w, index);
		return kid == null ? "" : kid;
	}

	private static String kidText(Widget w, int index)
	{
		Widget[] kids = w.getChildren();
		return kids != null && index >= 0 && index < kids.length && kids[index] != null
			&& kids[index].getText() != null ? Text.removeTags(kids[index].getText()).trim() : null;
	}

	private boolean awaitsAMenu()
	{
		return "teleportsViaCape".equals(pendingMethod)
			|| "teleportsViaJewellery".equals(pendingMethod)
			|| rubTick >= 0
			|| "spirit tree".equals(pendingLabel);
	}

	private boolean chatMenuOpen()
	{
		return showing(InterfaceID.Chatmenu.UNIVERSE)
			|| showing(InterfaceID.Menu.LJ_LAYER2) || showing(InterfaceID.Menu.LJ_LAYER1)
			|| showing(InterfaceID.MenuNew.UNIVERSE) || showing(InterfaceID.MenuNew.CONTENT);
	}

	private boolean showing(int componentId)
	{
		Widget w = client.getWidget(componentId);
		return w != null && !w.isHidden();
	}

	private String widgetChildText(int compositeId, int index)
	{
		Widget w = client.getWidget(compositeId);
		if (w == null)
		{
			return "";
		}
		String kid = kidText(w, index);
		return kid != null ? kid : w.getText() == null ? "" : Text.removeTags(w.getText()).trim();
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
			int step = Math.max(Math.abs(current.getX() - lastPlayerPos.getX()),
				Math.abs(current.getY() - lastPlayerPos.getY()));
			boolean regionChanged = current.getRegionID() != lastPlayerPos.getRegionID();
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

		if (pendingTick >= 0 && awaitsAMenu() && chatMenuOpen())
		{
			pendingTick = client.getTickCount();
		}

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

	private int pendingWindow()
	{
		return awaitsAMenu() ? MENU_PENDING_WINDOW_TICKS : TELEPORT_PENDING_WINDOW_TICKS;
	}

	private void creditPendingTeleport()
	{
		statStore.incrementStat("teleportsTotal");
		String key = matchDestinationKey(pendingLabel);
		if (key != null || pendingFromNexus)
		{
			statStore.incrementStat(key != null ? key : "teleportsNexus");
		}
		if (pendingMethod != null)
		{
			statStore.incrementStat(pendingMethod);
		}
		clearPending();
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING && teleportPending())
		{
			creditPendingTeleport();
		}
	}

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() == FAIRY_RING_ANIM)
		{
			statStore.incrementStat("teleportsTotal");
			statStore.incrementStat("teleportsFairyRing");
			clearPending();
		}
	}

	private boolean isRunStep(int step)
	{
		return step >= RUN_STEP_TILES || (client.getVarpValue(VarPlayerID.OPTION_RUN) == 1 && client.getEnergy() > 0);
	}

	private static String matchDestinationKey(String label)
	{
		if (label == null)
		{
			return null;
		}
		String clean = label.toLowerCase(Locale.ROOT);
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
