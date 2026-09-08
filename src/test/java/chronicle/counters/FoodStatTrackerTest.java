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
import net.runelite.api.events.ChatMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.StatKeys.CONSUMED_VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
	private final Map<String, Integer> sunk = new HashMap<>();
	private int searches;

	/**
	 * RuneLite's ItemManager.search is a case-insensitive substring match over item
	 * names (runelite master: name.toLowerCase().contains(itemName)). The stub keeps
	 * that shape, since it is exactly why "super restore potion(4)" finds nothing.
	 */
	private FoodStatTracker trackerOver(Object... catalogue)
	{
		List<ItemPrice> rows = new ArrayList<>();
		for (int i = 0; i < catalogue.length; i += 2)
		{
			ItemPrice row = new ItemPrice();
			row.setId(i);
			row.setName((String) catalogue[i]);
			row.setPrice((Integer) catalogue[i + 1]);
			rows.add(row);
		}
		Mockito.when(items.search(Mockito.anyString())).thenAnswer(inv -> {
			searches++;
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
		return new FoodStatTracker(store, Mockito.mock(Client.class), items, sunk::put);
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
}
