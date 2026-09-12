/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.StatKeys.CONSUMED_VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Key derivation for the per-food and per-potion counters. A wrong key fails silently:
 * it splits one tally across two counters.
 */
public class FoodStatTrackerTest
{
	@Test
	public void plainFoodsBecomeCamelCaseKeys()
	{
		assertEquals("lobsterEaten", FoodStatTracker.perFoodKey("Lobster"));
		assertEquals("sharkEaten", FoodStatTracker.perFoodKey("Shark"));
		assertEquals("mantaRayEaten", FoodStatTracker.perFoodKey("Manta ray"));
		assertEquals("cookedKarambwanEaten", FoodStatTracker.perFoodKey("Cooked karambwan"));
	}

	@Test
	public void historicalKeyNamesAreReproduced()
	{
		// These names predate the derivation rule. If it stops matching them the
		// journal totals fork.
		assertEquals("troutEaten", FoodStatTracker.perFoodKey("Trout"));
		assertEquals("cabbageEaten", FoodStatTracker.perFoodKey("Cabbage"));
	}

	@Test
	public void punctuationAndApostrophesCollapse()
	{
		assertEquals("chefsDelightEaten", FoodStatTracker.perFoodKey("Chef's delight"));
		assertEquals("admiralPieEaten", FoodStatTracker.perFoodKey("Admiral pie"));
	}

	@Test
	public void partEatenFoodsFoldOntoTheWholeItem()
	{
		// Digits survive the key builder, so leaving the portion prefix on would give
		// a fresh 23CakeEaten per bite.
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("2/3 cake"));
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("1/3 cake"));
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("Slice of cake"));
		assertEquals("plainPizzaEaten", FoodStatTracker.perFoodKey("1/2 plain pizza"));
		assertEquals("pineapplePizzaEaten", FoodStatTracker.perFoodKey("Half a pineapple pizza"));
		assertEquals("admiralPieEaten", FoodStatTracker.perFoodKey("Half an admiral pie"));
	}

	@Test
	public void baseNameStripsOnlyTheLeadingQualifier()
	{
		assertEquals("cake", FoodStatTracker.baseFoodName("2/3 cake"));
		assertEquals("Chocolate cake", FoodStatTracker.baseFoodName("Chocolate cake"));
		assertEquals("Cooked karambwan", FoodStatTracker.baseFoodName("Cooked karambwan"));
		// "half" only marks a portion when an "a"/"an" follows it. Half moon is an item.
		assertEquals("Half moon", FoodStatTracker.baseFoodName("Half moon"));
	}

	@Test
	public void unnamedItemsProduceNoKey()
	{
		// itemName() returns "" for an unresolvable id; that must not become "Eaten".
		assertEquals("", FoodStatTracker.perFoodKey(""));
		assertEquals("", FoodStatTracker.perFoodKey("   "));
	}

	@Test
	public void potionNameFromDrinkMessage()
	{
		// The doses-left tally is a second sentence, so the first full stop ends the name.
		assertEquals("prayer potion", FoodStatTracker.potionName(
			"You drink some of your prayer potion. You have 2 doses of potion left."));
		assertEquals("divine super combat potion", FoodStatTracker.potionName(
			"You drink some of your divine super combat potion."));
		assertEquals("Saradomin brew", FoodStatTracker.potionName(
			"You drink some of the Saradomin brew."));
		assertEquals("", FoodStatTracker.potionName("You drink the wine."));
	}

	@Test
	public void perPotionKeyMintsLikePerFoodKey()
	{
		assertEquals("prayerPotionDoses", FoodStatTracker.perPotionKey("prayer potion"));
		assertEquals("divineSuperCombatPotionDoses",
			FoodStatTracker.perPotionKey("divine super combat potion"));
		assertEquals("ranarrWeedTeaDoses", FoodStatTracker.perPotionKey("Ranarr weed tea"));
		assertEquals("", FoodStatTracker.perPotionKey(""));
	}

	@Test
	public void fourDoseNamesBridgeTheDrinkNameAndTheCatalogueName()
	{
		// As drunk first, then the sibling spelling, so an exact catalogue row wins.
		assertEquals(List.of("super restore potion(4)", "super restore(4)"),
			FoodStatTracker.fourDoseNames("super restore potion"));
		assertEquals(List.of("Saradomin brew(4)", "Saradomin brew potion(4)"),
			FoodStatTracker.fourDoseNames("Saradomin brew"));
		// The trailing word is matched without regard to case.
		assertEquals(List.of("Prayer Potion(4)", "Prayer(4)"),
			FoodStatTracker.fourDoseNames("Prayer Potion"));
	}

	// ---- pricing through the chat handler, against a substring-matching catalogue ----

	private final StatStore store = new StatStore();
	private final ItemManager items = Mockito.mock(ItemManager.class);
	private final Client client = Mockito.mock(Client.class);
	private final ItemContainer pack = Mockito.mock(ItemContainer.class);
	private final List<ItemPrice> rows = new ArrayList<>();
	private final Map<String, Integer> sunk = new HashMap<>();
	private final List<String> queries = new ArrayList<>();
	private int searches;

	/**
	 * RuneLite's ItemManager.search is a case-insensitive substring match over item
	 * names (runelite master: name.toLowerCase().contains(itemName)). The stub keeps
	 * that shape, since it is exactly why "super restore potion(4)" finds nothing.
	 * getItemPrice answers off the same rows by id, and the client hands back one
	 * inventory container so pack changes are seen.
	 */
	private FoodStatTracker trackerOver(Object... catalogue)
	{
		for (int i = 0; i < catalogue.length; i += 2)
		{
			priced(i, (String) catalogue[i], (Integer) catalogue[i + 1]);
		}
		Mockito.when(items.search(Mockito.anyString())).thenAnswer(inv -> {
			searches++;
			queries.add(inv.getArgument(0, String.class));
			String query = inv.getArgument(0, String.class).toLowerCase(Locale.ROOT);
			List<ItemPrice> hits = new ArrayList<>();
			for (ItemPrice row : rows)
			{
				if (row.getName().toLowerCase(Locale.ROOT).contains(query))
				{
					hits.add(row);
				}
			}
			return hits;
		});
		Mockito.when(items.getItemPrice(Mockito.anyInt())).thenAnswer(inv -> {
			int id = inv.getArgument(0, Integer.class);
			for (ItemPrice row : rows)
			{
				if (row.getId() == id)
				{
					return row.getPrice();
				}
			}
			return 0;
		});
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);
		return new FoodStatTracker(store, client, items, sunk::put);
	}

	/** A catalogue row under the id the pack would show it by. */
	private void priced(int id, String name, int price)
	{
		ItemPrice row = new ItemPrice();
		row.setId(id);
		row.setName(name);
		row.setPrice(price);
		rows.add(row);
	}

	/**
	 * What the client calls this item id, and the five actions its pack menu offers. A
	 * potion's menu unless the test says otherwise: "Drink" first, "Drop" last, the
	 * three between empty.
	 */
	private void named(int id, String name, String... actions)
	{
		ItemComposition definition = Mockito.mock(ItemComposition.class);
		Mockito.when(definition.getName()).thenReturn(name);
		Mockito.when(definition.getInventoryActions()).thenReturn(
			actions.length == 0 ? new String[]{"Drink", null, null, null, "Drop"} : actions);
		Mockito.when(client.getItemDefinition(id)).thenReturn(definition);
	}

	/** The pack as the client now reports it, as id, quantity pairs. */
	private void pack(FoodStatTracker tracker, int... idQty)
	{
		Item[] held = new Item[idQty.length / 2];
		for (int i = 0; i < idQty.length; i += 2)
		{
			held[i / 2] = new Item(idQty[i], idQty[i + 1]);
		}
		Mockito.when(pack.getItems()).thenReturn(held);
		tracker.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), pack));
	}

	private void tick(FoodStatTracker tracker)
	{
		tracker.onGameTick(new GameTick());
	}

	private void drink(FoodStatTracker tracker, String potion)
	{
		tracker.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"You drink some of your " + potion + ". You have 3 doses of potion left.", null, 0));
	}

	@Test
	public void potionDrunkWithATrailingPotionPricesOffTheBareCatalogueName()
	{
		// The audit's case: chat "super restore potion", GE "Super restore(4)".
		FoodStatTracker tracker = trackerOver(
			"Super restore(4)", 10000,
			"Super restore(3)", 7000,
			"Antipoison(4)", 400,
			"Anti-venom(4)", 2000,
			"Extended antifire(4)", 3200);

		drink(tracker, "super restore potion");
		assertEquals(2500, store.getStat(CONSUMED_VALUE));
		assertEquals(1, store.getStat("superRestorePotionDoses"));
		assertEquals(Integer.valueOf(2500), sunk.get("superRestorePotionDoses"));

		// Others of the same shape.
		drink(tracker, "antipoison potion");
		assertEquals(Integer.valueOf(100), sunk.get("antipoisonPotionDoses"));
		drink(tracker, "anti-venom potion");
		assertEquals(Integer.valueOf(500), sunk.get("antiVenomPotionDoses"));
		drink(tracker, "extended antifire potion");
		assertEquals(Integer.valueOf(800), sunk.get("extendedAntifirePotionDoses"));
		assertEquals(2500 + 100 + 500 + 800, store.getStat(CONSUMED_VALUE));
	}

	@Test
	public void potionDrunkBareStillPricesWhenTheCatalogueSaysPotion()
	{
		// The rule runs both ways, as the site's alias did: a drink name without the
		// word finds a "<x> potion(4)" row. No live potion is known to need this
		// direction; it is here so the bridge stays symmetric.
		FoodStatTracker tracker = trackerOver("Stamina potion(4)", 8000);
		drink(tracker, "stamina");
		assertEquals(2000, store.getStat(CONSUMED_VALUE));
		assertEquals(Integer.valueOf(2000), sunk.get("staminaDoses"));
	}

	@Test
	public void exactCatalogueRowOutranksTheSibling()
	{
		FoodStatTracker tracker = trackerOver(
			"Stamina potion(4)", 8000,
			"Stamina(4)", 400);
		drink(tracker, "stamina potion");
		assertEquals(2000, store.getStat(CONSUMED_VALUE));
	}

	@Test
	public void siblingMatchIsRememberedSoTheCatalogueWalkRunsOnce()
	{
		FoodStatTracker tracker = trackerOver("Super restore(4)", 10000);
		drink(tracker, "super restore potion");
		int walked = searches;
		assertEquals(2, walked);   // as drunk (empty), then the sibling (hit)
		drink(tracker, "super restore potion");
		drink(tracker, "super restore potion");
		assertEquals(walked, searches);
		assertEquals(7500, store.getStat(CONSUMED_VALUE));
	}

	@Test
	public void unloadedPriceListIsNotCachedAsUnpriced()
	{
		// Neither spelling came back with anything: the client's price list may not
		// be up yet. Try again next dose rather than pinning a zero.
		FoodStatTracker tracker = trackerOver();
		drink(tracker, "super restore potion");
		drink(tracker, "super restore potion");
		assertEquals(4, searches);
		assertEquals(0, store.getStat(CONSUMED_VALUE));
		assertEquals(2, store.getStat("superRestorePotionDoses"));
		assertNull(sunk.get("superRestorePotionDoses"));
	}

	// ---- pricing off the item the pack shows leaving ----

	private static final int PRAYER_POTION4 = 2434;
	private static final int PRAYER_POTION3 = 139;
	private static final int PRAYER_POTION2 = 141;
	private static final int PRAYER_POTION1 = 143;
	private static final int VIAL = 229;

	private FoodStatTracker prayerTracker()
	{
		FoodStatTracker tracker = trackerOver("Super restore(4)", 10000);
		priced(PRAYER_POTION4, "Prayer potion(4)", 12000);
		named(PRAYER_POTION4, "Prayer potion(4)");
		named(PRAYER_POTION3, "Prayer potion(3)");
		named(PRAYER_POTION2, "Prayer potion(2)");
		named(PRAYER_POTION1, "Prayer potion(1)");
		named(VIAL, "Vial");
		return tracker;
	}

	@Test
	public void prayerPotionIsPricedOffTheItemThatLeftThePack()
	{
		// The owner's journal: 58 restorePrayerPotionDoses and no gp against them. The
		// chat says "restore prayer potion", the catalogue "Prayer potion(4)", and no
		// spelling of the one bridges to the other. The pack knows which item it was.
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, PRAYER_POTION3, 1);

		// (3) -> (2): a lower dose finds its 4-dose sibling by catalogue name, once,
		// and is priced by that row's id.
		pack(tracker, PRAYER_POTION2, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(3000, store.getStat(CONSUMED_VALUE));
		assertEquals(1, store.getStat("restorePrayerPotionDoses"));
		assertEquals(Integer.valueOf(3000), sunk.get("restorePrayerPotionDoses"));
		assertEquals(List.of("Prayer potion(4)"), queries);
		Mockito.verify(items).getItemPrice(PRAYER_POTION4);

		// (2) -> (1): remembered, no second walk.
		pack(tracker, PRAYER_POTION1, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(6000, store.getStat(CONSUMED_VALUE));
		assertEquals(1, searches);
		assertEquals(2, store.getStat("restorePrayerPotionDoses"));
	}

	@Test
	public void fourDoseInThePackIsTheCatalogueRowItself()
	{
		// No walk at all: the item that shrank is the (4) row, priced by its own id.
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, PRAYER_POTION4, 1);
		pack(tracker, PRAYER_POTION3, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(3000, store.getStat(CONSUMED_VALUE));
		assertEquals(0, searches);
		Mockito.verify(items).getItemPrice(PRAYER_POTION4);
	}

	@Test
	public void drinkLineArrivingBeforeThePackChangeWaitsForIt()
	{
		// The server writes the message before it flushes the pack, so the line can
		// land first. Nothing is priced until the pack says which potion.
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, PRAYER_POTION3, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(1, store.getStat("restorePrayerPotionDoses"));
		assertEquals(0, store.getStat(CONSUMED_VALUE));
		assertNull(sunk.get("restorePrayerPotionDoses"));

		pack(tracker, PRAYER_POTION2, 1);
		assertEquals(3000, store.getStat(CONSUMED_VALUE));
		assertEquals(Integer.valueOf(3000), sunk.get("restorePrayerPotionDoses"));
		// The name path was never consulted for it.
		assertEquals(List.of("Prayer potion(4)"), queries);
	}

	@Test
	public void drinkLineWithNoPackChangeFallsBackToTheNameAsItsWindowCloses()
	{
		// A pack is watched but never shows a dose leaving. The line waits its two
		// ticks, then prices the old way, off the name as drunk.
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, 995, 100);
		drink(tracker, "super restore potion");
		tick(tracker);
		assertEquals(0, store.getStat(CONSUMED_VALUE));
		tick(tracker);
		assertEquals(2500, store.getStat(CONSUMED_VALUE));
		assertEquals(Integer.valueOf(2500), sunk.get("superRestorePotionDoses"));
		assertEquals(List.of("super restore potion(4)", "super restore(4)"), queries);
		// Priced once; the window does not re-run.
		tick(tracker);
		assertEquals(2500, store.getStat(CONSUMED_VALUE));
	}

	@Test
	public void lastDoseLeavesAVialAndIsStillPriced()
	{
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, PRAYER_POTION1, 1);

		// (1) -> Vial: nothing of the potion is left to grow.
		pack(tracker, VIAL, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(3000, store.getStat(CONSUMED_VALUE));

		// (1) -> nothing: the vial was smashed on the same tick.
		pack(tracker, VIAL, 1, PRAYER_POTION1, 1);
		pack(tracker, VIAL, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(6000, store.getStat(CONSUMED_VALUE));
		assertEquals(2, store.getStat("restorePrayerPotionDoses"));
		assertEquals(Integer.valueOf(3000), sunk.get("restorePrayerPotionDoses"));
	}

	@Test
	public void aDoseThatShrinksWithoutItsNextDoseAppearingIsNotADrink()
	{
		// A (3) dropped or banked is not a dose drunk: no (2) took its place. The line
		// that follows finds nothing to pair with and falls back to its name.
		FoodStatTracker tracker = prayerTracker();
		pack(tracker, PRAYER_POTION3, 1);
		pack(tracker);
		drink(tracker, "restore prayer potion");
		tick(tracker);
		tick(tracker);
		assertEquals(0, store.getStat(CONSUMED_VALUE));
		Mockito.verify(items, Mockito.never()).getItemPrice(PRAYER_POTION4);
	}

	@Test
	public void unloadedPriceListDoesNotPinTheItemPathAtZero()
	{
		// The first dose sees no price at all; the second, once the list is up, must
		// price. A remembered zero would leave the potion unpriced all session.
		FoodStatTracker tracker = trackerOver();
		named(PRAYER_POTION3, "Prayer potion(3)");
		named(PRAYER_POTION2, "Prayer potion(2)");
		named(PRAYER_POTION1, "Prayer potion(1)");
		pack(tracker, PRAYER_POTION3, 1);
		pack(tracker, PRAYER_POTION2, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(0, store.getStat(CONSUMED_VALUE));

		priced(PRAYER_POTION4, "Prayer potion(4)", 12000);
		pack(tracker, PRAYER_POTION1, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(3000, store.getStat(CONSUMED_VALUE));
	}

	@Test
	public void potionWithoutAFourDoseFormIsLeftUnpriced()
	{
		// Named only in the client, not in the catalogue: an untradeable potion. The
		// pack pairs it (the brackets in its own name survive the dose suffix coming
		// off), the catalogue has no (4) row, nothing is guessed.
		FoodStatTracker tracker = prayerTracker();
		named(11731, "Overload (+)(3)");
		named(11732, "Overload (+)(2)");
		pack(tracker, 11731, 1);
		pack(tracker, 11732, 1);
		drink(tracker, "overload potion");
		assertEquals(List.of("Overload (+)(4)"), queries);
		assertEquals(0, store.getStat(CONSUMED_VALUE));
		assertEquals(1, store.getStat("overloadPotionDoses"));
		assertNull(sunk.get("overloadPotionDoses"));
	}

	@Test
	public void aLowerDoseFindsItsOwnFourDoseRowNotALongerNameAroundIt()
	{
		// The client's search is a substring match and walks the catalogue in its own
		// order: "Combat potion(4)" sits inside "Super combat potion(4)", which here
		// comes first. Only the row spelt exactly as wanted prices the sip.
		FoodStatTracker tracker = trackerOver();
		priced(SUPER_COMBAT4, "Super combat potion(4)", 12000);
		priced(COMBAT_POTION4, "Combat potion(4)", 1000);
		named(COMBAT_POTION3, "Combat potion(3)");
		named(COMBAT_POTION2, "Combat potion(2)");
		pack(tracker, COMBAT_POTION3, 1);
		pack(tracker, COMBAT_POTION2, 1);
		drink(tracker, "combat potion");
		assertEquals(250, store.getStat(CONSUMED_VALUE));
		Mockito.verify(items).getItemPrice(COMBAT_POTION4);
		Mockito.verify(items, Mockito.never()).getItemPrice(SUPER_COMBAT4);
	}

	// ---- what the pack shows leaving is not always a potion ----

	private static final int COMBAT_POTION4 = 9739;
	private static final int COMBAT_POTION3 = 9741;
	private static final int COMBAT_POTION2 = 9743;
	private static final int SUPER_COMBAT4 = 12695;
	private static final int COMBAT_BRACELET4 = 11126;
	private static final int COMBAT_BRACELET3 = 11124;
	private static final int RING_OF_DUELING8 = 2552;
	private static final int RING_OF_DUELING7 = 2554;
	private static final int RING_OF_DUELING4 = 2560;
	private static final int WATERING_CAN8 = 5340;
	private static final int WATERING_CAN7 = 5339;
	private static final int WATERSKIN4 = 1823;
	private static final int WATERSKIN3 = 1825;
	private static final int WATERSKIN2 = 1827;
	private static final int STAMINA4 = 12625;
	private static final int STAMINA3 = 12627;
	private static final int STAMINA2 = 12629;
	private static final int STAMINA1 = 12631;

	private static final String[] JEWELLERY_MENU = {"Rub", "Wear", null, null, "Drop"};
	private static final String[] CAN_MENU = {null, null, null, null, "Drop"};

	@Test
	public void aChargedItemCountingDownIsNoDoseWhateverItsNameShares()
	{
		// "Combat bracelet(4)" -> "(3)" is a teleport: it counts down in a potion's
		// brackets and shares a word with the combat potion sipped on the next tick.
		// The menu decides. Only an item offering "Drink" can be the dose a line is
		// paired with, so the sip is priced off the potion and never off the bracelet.
		FoodStatTracker tracker = trackerOver();
		priced(COMBAT_BRACELET4, "Combat bracelet(4)", 20000);
		priced(COMBAT_POTION4, "Combat potion(4)", 1000);
		named(COMBAT_BRACELET4, "Combat bracelet(4)", JEWELLERY_MENU);
		named(COMBAT_BRACELET3, "Combat bracelet(3)", JEWELLERY_MENU);
		named(COMBAT_POTION3, "Combat potion(3)");
		named(COMBAT_POTION2, "Combat potion(2)");
		pack(tracker, COMBAT_BRACELET4, 1, COMBAT_POTION3, 1);
		pack(tracker, COMBAT_BRACELET3, 1, COMBAT_POTION3, 1);
		drink(tracker, "combat potion");
		pack(tracker, COMBAT_BRACELET3, 1, COMBAT_POTION2, 1);
		assertEquals(250, store.getStat(CONSUMED_VALUE));
		assertEquals(Integer.valueOf(250), sunk.get("combatPotionDoses"));
		assertEquals(List.of("Combat potion(4)"), queries);
		Mockito.verify(items, Mockito.never()).getItemPrice(COMBAT_BRACELET4);
	}

	@Test
	public void aTeleportAndAWateringBesideTheSipLeaveThePotionToPriceIt()
	{
		// A ring rubbed in the very event the potion shrinks, a patch watered the
		// tick before, and the line arriving inside the window: neither charge is
		// asked its price, and the (4) row of the ring is never looked up.
		FoodStatTracker tracker = prayerTracker();
		priced(RING_OF_DUELING4, "Ring of dueling(4)", 1000);
		named(RING_OF_DUELING8, "Ring of dueling(8)", JEWELLERY_MENU);
		named(RING_OF_DUELING7, "Ring of dueling(7)", JEWELLERY_MENU);
		named(WATERING_CAN8, "Watering can(8)", CAN_MENU);
		named(WATERING_CAN7, "Watering can(7)", CAN_MENU);
		pack(tracker, RING_OF_DUELING8, 1, WATERING_CAN8, 1, PRAYER_POTION3, 1);
		pack(tracker, RING_OF_DUELING8, 1, WATERING_CAN7, 1, PRAYER_POTION3, 1);
		tick(tracker);
		pack(tracker, RING_OF_DUELING7, 1, WATERING_CAN7, 1, PRAYER_POTION2, 1);
		drink(tracker, "restore prayer potion");
		assertEquals(3000, store.getStat(CONSUMED_VALUE));
		assertEquals(List.of("Prayer potion(4)"), queries);
		Mockito.verify(items, Mockito.never()).getItemPrice(RING_OF_DUELING4);
	}

	@Test
	public void aWaterskinSipInTheWindowIsNotTakenForThePotion()
	{
		// Waterskin(4) -> (3) drinks like a potion and counts down like one, so the
		// menu lets it through. The desert sips it beside the stamina dose, and the
		// dose and the line are paired by name: the waterskin's shrink waits on a line
		// of its own that never comes, and the potion prices the sip, whichever half
		// lands first.
		FoodStatTracker tracker = trackerOver();
		priced(WATERSKIN4, "Waterskin(4)", 40);
		priced(STAMINA4, "Stamina potion(4)", 8000);
		named(WATERSKIN4, "Waterskin(4)");
		named(WATERSKIN3, "Waterskin(3)");
		named(WATERSKIN2, "Waterskin(2)");
		named(STAMINA3, "Stamina potion(3)");
		named(STAMINA2, "Stamina potion(2)");
		named(STAMINA1, "Stamina potion(1)");
		pack(tracker, WATERSKIN4, 1, STAMINA3, 1);

		// the pack first: the waterskin's shrink is waiting when the line arrives
		pack(tracker, WATERSKIN3, 1, STAMINA3, 1);
		tick(tracker);
		drink(tracker, "stamina potion");
		pack(tracker, WATERSKIN3, 1, STAMINA2, 1);
		assertEquals(2000, store.getStat(CONSUMED_VALUE));

		// the line first: the waterskin's shrink arrives while the line is parked
		tick(tracker);
		tick(tracker);
		tick(tracker);
		drink(tracker, "stamina potion");
		pack(tracker, WATERSKIN2, 1, STAMINA2, 1);
		pack(tracker, WATERSKIN2, 1, STAMINA1, 1);
		assertEquals(4000, store.getStat(CONSUMED_VALUE));
		assertEquals(Integer.valueOf(2000), sunk.get("staminaPotionDoses"));
		assertEquals(List.of("Stamina potion(4)"), queries);
		Mockito.verify(items, Mockito.never()).getItemPrice(WATERSKIN4);
	}

	@Test
	public void aDrinkLineAndAPackItemAgreeWhenTheyNameTheSamePotion()
	{
		assertTrue(FoodStatTracker.namesAgree("restore prayer potion", "Prayer potion"));
		assertTrue(FoodStatTracker.namesAgree("super restore potion", "Super restore"));
		assertTrue(FoodStatTracker.namesAgree("overload potion", "Overload (+)"));
		assertTrue(FoodStatTracker.namesAgree("Saradomin brew", "Saradomin brew"));
		// one spelling runs on into the other
		assertTrue(FoodStatTracker.namesAgree("super antipoison potion", "Superantipoison"));
		// a shared word other than "potion"
		assertTrue(FoodStatTracker.namesAgree("prayer regeneration potion", "Prayer potion"));
		assertFalse(FoodStatTracker.namesAgree("stamina potion", "Waterskin"));
		assertFalse(FoodStatTracker.namesAgree("stamina potion", "Prayer potion"));
		assertFalse(FoodStatTracker.namesAgree("", "Prayer potion"));
		assertFalse(FoodStatTracker.namesAgree("stamina potion", ""));
	}
}
