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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

import static chronicle.counters.StatKeys.BEERS_DRUNK;
import static chronicle.counters.StatKeys.DIVINE_POTION_DAMAGE;
import static chronicle.counters.StatKeys.CONSUMED_VALUE;
import static chronicle.counters.StatKeys.FOOD_EATEN;
import static chronicle.counters.StatKeys.HITPOINTS_REGENERATED;
import static chronicle.counters.StatKeys.POTION_DOSES;
import static chronicle.counters.StatKeys.VIALS_SHATTERED;

/**
 * Counts eating, drinking and passive hitpoint regeneration.
 *
 * <p>An eat is scored off the "Eat" menu click, once that item's stack is seen
 * shrinking in the inventory; hitpoints play no part, since dropping food on a natural
 * regen tick looks identical. Drinks and vial-smashing read off the chat box. Regen has
 * no signal of its own, so a +1 hitpoint step no recent Eat/Drink explains is regen.
 *
 * <p>A dose is priced off the potion the pack shows leaving, not the name the chat
 * gives it: the two disagree ("restore prayer potion" is a Prayer potion), and only the
 * item carries an id. The chat name is priced only when no pack change pairs with it.
 * The two halves are paired by name and only an item the pack lets you "Drink" is a
 * dose at all, so a teleport, a watering or a waterskin sip in the pairing window is
 * never taken for the potion.
 */
public class FoodStatTracker implements StatTracker
{
	// Consumables that heal exactly one hitpoint. A +1 tick straight after one of these
	// is that heal, not regen. Matched as a substring of the menu target.
	private static final List<String> SINGLE_HP_HEALS = List.of(
		"Anchovies",
		"Cabbage",
		"Chopped onion",
		"Equa leaves",
		"Fresh monkfish",
		"Nettle-water",
		"Onion",
		"Potato",
		"Pot of cream",
		"Asgarnian ale",
		"Axeman's folly",
		"Bandit's brew",
		"Beer",
		"Chef's delight",
		"Cider",
		"Dragon bitter",
		"Dwarven stout",
		"Elven dawn",
		"Greenman's ale",
		"Kovac's grog",
		"Slayer's respite",
		"Wizard's mind bomb");

	// How long a pending Eat waits for the item to leave the pack.
	private static final int EAT_CONFIRM_TICKS = 3;

	private final StatStore store;
	private final Client client;
	private final ItemManager itemManager;

	// Menu target of the latest Eat/Drink click; null once reconciled.
	private String lastConsumed;

	// Boosted hitpoints from the previous tick; -1 before the first reading.
	private int previousHitpoints = -1;

	// Foods clicked "Eat" but not yet seen leaving the pack. A queue because
	// combo-eating (food plus karambwan on one tick) puts several in flight at once.
	private final List<PendingEat> pendingEats = new ArrayList<>();

	// Cap so clicks that never resolve can't grow the queue unbounded.
	private static final int MAX_PENDING_EATS = 8;

	private static final class PendingEat
	{
		// Keyed by id, because noted and unnoted forms share a name.
		private final int itemId;
		private int ticksLeft;

		private PendingEat(int itemId, int ticksLeft)
		{
			this.itemId = itemId;
			this.ticksLeft = ticksLeft;
		}
	}

	// Previous inventory contents, for spotting the eaten stack shrink.
	private Map<Integer, Integer> inventorySnapshot;

	// A potion dose seen leaving the pack: which item shrank, its catalogue name with
	// the dose suffix off, and how many doses it held.
	private static final class DoseDrunk
	{
		private final int itemId;
		private final String base;
		private final int doses;
		private int ticksLeft;

		private DoseDrunk(int itemId, String base, int doses, int ticksLeft)
		{
			this.itemId = itemId;
			this.base = base;
			this.doses = doses;
			this.ticksLeft = ticksLeft;
		}
	}

	// A drink line the pack has not yet explained: the typed key it was tallied under,
	// and the name as drunk for the chat-name fallback should the pack never do so.
	private static final class ParkedDrink
	{
		private final String typed;
		private final String potion;
		private int ticksLeft;

		private ParkedDrink(String typed, String potion, int ticksLeft)
		{
			this.typed = typed;
			this.potion = potion;
			this.ticksLeft = ticksLeft;
		}
	}

	// The chat line and the pack change of one drink land in the same client cycle, in
	// either order. Each half waits this many ticks for the other. The potion delay is
	// three ticks, so a wait never survives into the next drink.
	private static final int DRINK_PAIR_TICKS = 2;

	private static final int MAX_PENDING_DRINKS = 4;

	// Doses seen leaving the pack with no drink line for them yet.
	private final List<DoseDrunk> pendingDoses = new ArrayList<>();

	// Drink lines seen with no pack change for them yet.
	private final List<ParkedDrink> parkedDrinks = new ArrayList<>();

	// "Prayer potion(3)" -> "Prayer potion", 3. The dose count is the bracketed digit
	// closing the name; "Overload (+)(4)" keeps its own brackets in the base.
	private static final Pattern DOSE_SUFFIX = Pattern.compile("^(.+?)\\s*\\((\\d)\\)$");

	// Dose price per catalogue base name ("Prayer potion"), from the item path, and per
	// name as drunk ("restore prayer potion"), from the chat-name fallback. Both held
	// for the session: the lookups behind them walk every tradeable item name on the
	// client thread, and a 4-dose price won't move underneath a session.
	private final Map<String, Integer> itemDosePrices = new HashMap<>();
	private final Map<String, Integer> dosePrices = new HashMap<>();

	// Journal sink for per-consumable gp (typed key -> price at use). Null in tests, and
	// until ChronicleCounters builds this tracker, which it defers until the plugin has
	// wired the sink.
	private final java.util.function.BiConsumer<String, Integer> consumableSink;

	public FoodStatTracker(StatStore statStore, Client client, ItemManager itemManager,
		java.util.function.BiConsumer<String, Integer> consumableSink)
	{
		this.itemManager = itemManager;
		this.store = statStore;
		this.client = client;
		this.consumableSink = consumableSink;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Stash what was just consumed so the tick handler can tell a 1 HP heal from a
		// regen tick. Eat targets arrive with colour tags; Drink targets are cleaned
		// later, only if we need to look at them.
		if ("Eat".equals(event.getMenuOption()))
		{
			lastConsumed = Text.removeTags(event.getMenuTarget());
			// Scored only once this exact item id leaves the pack, so an interrupted
			// click never counts.
			if (pendingEats.size() < MAX_PENDING_EATS)
			{
				pendingEats.add(new PendingEat(event.getItemId(), EAT_CONFIRM_TICKS));
			}
		}
		else if ("Drink".equals(event.getMenuOption()))
		{
			lastConsumed = event.getMenuTarget();
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);

		// Only a single-point rise can be regen. If it can't be pinned on a 1 HP food,
		// call it passive regeneration.
		//
		// Imperfection in the heuristic: eating a multi-point food while sitting one
		// below the HP cap gives a +1 step indistinguishable from regen. Rare enough to
		// leave alone.
		if (previousHitpoints != -1 && hitpoints == previousHitpoints + 1)
		{
			if (lastConsumed == null || !isSingleHpHeal(lastConsumed))
			{
				store.incrementStat(HITPOINTS_REGENERATED);
			}
			else
			{
				// The rise was the 1 HP food. Drop the reference so its heal swallows at
				// most one regen tick.
				lastConsumed = null;
			}
		}

		previousHitpoints = hitpoints;

		// Age the queue; a click that never produced a consumed item just expires.
		for (Iterator<PendingEat> it = pendingEats.iterator(); it.hasNext(); )
		{
			if (--it.next().ticksLeft <= 0)
			{
				it.remove();
			}
		}

		// The pairing windows. A dose the chat never claimed just expires; a drink line
		// the pack never explained is priced off its name as it goes.
		for (Iterator<DoseDrunk> it = pendingDoses.iterator(); it.hasNext(); )
		{
			if (--it.next().ticksLeft <= 0)
			{
				it.remove();
			}
		}
		for (Iterator<ParkedDrink> it = parkedDrinks.iterator(); it.hasNext(); )
		{
			ParkedDrink drink = it.next();
			if (--drink.ticksLeft <= 0)
			{
				it.remove();
				filePrice(drink.typed, dosePrice(drink.potion));
			}
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// Away from LOGGED_IN the pack changes unobserved and ticks stop firing. The
		// snapshot and the queue both go stale. Rebuild from the first inventory event
		// after we're back in-game.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			reset();
		}
		// Remembered dose prices are world-wide GE figures carrying nothing personal,
		// but the login screen is the one transition where nothing at all survives. A
		// region cross keeps them.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			itemDosePrices.clear();
			dosePrices.clear();
		}
	}

	// Safe to call any time; the next tick rebuilds.
	private void reset()
	{
		pendingEats.clear();
		pendingDoses.clear();
		parkedDrinks.clear();
		inventorySnapshot = null;
		lastConsumed = null;
		previousHitpoints = -1;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}

		Map<Integer, Integer> current = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item != null && item.getId() >= 0)
			{
				current.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}

		// Confirm a pending Eat: the clicked food's own stack must have shrunk. Multi
		// portion foods (pizzas, cakes) shrink the whole-item stack and count once per
		// bite.
		if (!pendingEats.isEmpty() && inventorySnapshot != null)
		{
			for (Map.Entry<Integer, Integer> before : inventorySnapshot.entrySet())
			{
				int consumed = before.getValue() - current.getOrDefault(before.getKey(), 0);
				if (consumed <= 0)
				{
					continue;   // that stack didn't shrink
				}
				// The eat delay is several ticks, so no eat removes two units of one item
				// id at once: a multi-unit shrink is a drop, a deposit, a trade or a
				// death. Don't break out of the loop either: a combo-eat shrinks two
				// stacks in one event.
				if (consumed != 1 || !takePendingEat(before.getKey()))
				{
					continue;
				}
				store.incrementStat(FOOD_EATEN);
				// Priced at the bite off the client's own GE feed, like drops at the kill.
				int price = itemManager.getItemPrice(itemManager.canonicalize(before.getKey()));
				if (price > 0)
				{
					store.incrementStatBy(CONSUMED_VALUE, price);
				}
				String typed = perFoodKey(itemName(before.getKey()));
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
					// Cost filed under the same typed key as the count.
					if (price > 0 && consumableSink != null)
					{
						consumableSink.accept(typed, price);
					}
				}
			}
		}

		// A potion dose leaving the pack. Paired with the drink line the chat carries,
		// whichever of the two arrived first.
		if (inventorySnapshot != null)
		{
			for (Map.Entry<Integer, Integer> before : inventorySnapshot.entrySet())
			{
				if (before.getValue() - current.getOrDefault(before.getKey(), 0) != 1)
				{
					continue;
				}
				DoseDrunk dose = doseDrunk(before.getKey(), current);
				if (dose == null)
				{
					continue;
				}
				ParkedDrink drink = takeParkedDrink(dose.base);
				if (drink != null)
				{
					filePrice(drink.typed, itemDosePrice(dose));
				}
				else if (pendingDoses.size() < MAX_PENDING_DRINKS)
				{
					pendingDoses.add(dose);
				}
			}
		}

		inventorySnapshot = current;
	}

	/**
	 * The dose this shrink was, or null. The item must carry a dose suffix and offer
	 * "Drink" in the pack, and the next dose down must have appeared in its place; the
	 * last dose leaves a vial, or nothing at all when the vial is smashed, so a (1) is
	 * taken on the shrink alone. Jewellery and watering cans count their charges down
	 * in the same brackets, and without the menu check a teleport or a watering in the
	 * pairing window would stand in for the sip and price the drink line.
	 */
	private DoseDrunk doseDrunk(int itemId, Map<Integer, Integer> current)
	{
		ItemComposition definition = client.getItemDefinition(itemId);
		if (definition == null || definition.getName() == null)
		{
			return null;
		}
		Matcher m = DOSE_SUFFIX.matcher(definition.getName());
		if (!m.matches() || !drinkable(definition))
		{
			return null;
		}
		String base = m.group(1);
		int doses = m.group(2).charAt(0) - '0';
		if (doses < 1)
		{
			return null;
		}
		if (doses > 1 && !appeared(base + "(" + (doses - 1) + ")", current))
		{
			return null;
		}
		return new DoseDrunk(itemId, base, doses, DRINK_PAIR_TICKS);
	}

	/** Whether a stack of this name grew by one in this pack change. */
	private boolean appeared(String name, Map<Integer, Integer> current)
	{
		for (Map.Entry<Integer, Integer> now : current.entrySet())
		{
			if (now.getValue() - inventorySnapshot.getOrDefault(now.getKey(), 0) == 1
				&& name.equals(itemName(now.getKey())))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether the item's pack menu offers "Drink", as every potion's does. */
	private static boolean drinkable(ItemComposition definition)
	{
		String[] actions = definition.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if (action != null && "drink".equalsIgnoreCase(action.trim()))
			{
				return true;
			}
		}
		return false;
	}

	/** The first dose waiting that names the potion this drink line does, or null. */
	private DoseDrunk takePendingDose(String potion)
	{
		for (Iterator<DoseDrunk> it = pendingDoses.iterator(); it.hasNext(); )
		{
			DoseDrunk dose = it.next();
			if (namesAgree(potion, dose.base))
			{
				it.remove();
				return dose;
			}
		}
		return null;
	}

	/** The first drink line waiting that names the potion this dose was, or null. */
	private ParkedDrink takeParkedDrink(String base)
	{
		for (Iterator<ParkedDrink> it = parkedDrinks.iterator(); it.hasNext(); )
		{
			ParkedDrink drink = it.next();
			if (namesAgree(drink.potion, base))
			{
				it.remove();
				return drink;
			}
		}
		return null;
	}

	/**
	 * Whether a drink line and a pack item name the same potion. The chat names it in
	 * its own words ("restore prayer potion"), the catalogue in its own ("Prayer
	 * potion"), and the two share a word, or one spelling runs on into the other
	 * ("superantipoison potion" beside "Superantipoison"). "potion" alone is no
	 * agreement, since every potion says it. A waterskin sip in the pairing window
	 * shares nothing with the drink line and is left waiting on a line of its own.
	 */
	static boolean namesAgree(String potion, String base)
	{
		String drunk = consumableKey(potion, "").toLowerCase(Locale.ROOT);
		String held = consumableKey(base, "").toLowerCase(Locale.ROOT);
		if (drunk.isEmpty() || held.isEmpty())
		{
			return false;
		}
		if (drunk.contains(held) || held.contains(drunk))
		{
			return true;
		}
		Set<String> shared = new HashSet<>(words(potion));
		shared.retainAll(words(base));
		shared.remove("potion");
		return !shared.isEmpty();
	}

	/**
	 * Price a drink line. The pack is the authority on which potion it was: a dose
	 * already seen leaving that names the same potion is taken now, otherwise the line
	 * waits its window for one. Only when there is no pack to wait on is the name as
	 * drunk priced on the spot.
	 */
	private void priceDrink(String typed, String potion)
	{
		DoseDrunk dose = takePendingDose(potion);
		if (dose != null)
		{
			filePrice(typed, itemDosePrice(dose));
		}
		else if (inventorySnapshot != null && parkedDrinks.size() < MAX_PENDING_DRINKS)
		{
			parkedDrinks.add(new ParkedDrink(typed, potion, DRINK_PAIR_TICKS));
		}
		else
		{
			filePrice(typed, dosePrice(potion));
		}
	}

	/** File a dose's worth under the aggregate and, when the potion has a key, its own. */
	private void filePrice(String typed, int perDose)
	{
		if (perDose <= 0)
		{
			return;
		}
		store.incrementStatBy(CONSUMED_VALUE, perDose);
		if (!typed.isEmpty() && consumableSink != null)
		{
			consumableSink.accept(typed, perDose);
		}
	}

	/**
	 * A dose's worth from the item that left the pack: the 4-dose form's GE price over
	 * four. The 4-dose is the traded form; the lower doses are thin markets whose prices
	 * wander, so pricing every sip off the one row keeps a potion's four doses worth the
	 * same, and matches how the chat-name path always priced.
	 *
	 * <p>Misses are not remembered: the catalogue cannot tell an unloaded price list
	 * from an untradeable potion, and a potion pinned at zero on login would stay
	 * unpriced all session. A repeat walk per unpriced dose is cheap beside that.
	 */
	private int itemDosePrice(DoseDrunk dose)
	{
		Integer known = itemDosePrices.get(dose.base);
		if (known != null)
		{
			return known;
		}
		try
		{
			// A (4) in the pack is the row itself; a lower dose finds its 4-dose sibling
			// by catalogue name, which the search then matches exactly.
			int fourDoseId = dose.doses == 4 ? dose.itemId : fourDoseId(dose.base);
			int perDose = fourDoseId >= 0 ? itemManager.getItemPrice(fourDoseId) / 4 : 0;
			if (perDose > 0)
			{
				itemDosePrices.put(dose.base, perDose);
			}
			return perDose;
		}
		catch (RuntimeException ignored)
		{
			// price cache unavailable; the dose goes unpriced rather than guessed
			return 0;
		}
	}

	/** The catalogue id of "<base>(4)", or -1 when the price list has no such row. */
	private int fourDoseId(String base)
	{
		String wanted = base + "(4)";
		for (ItemPrice row : itemManager.search(wanted))
		{
			if (row.getName().equalsIgnoreCase(wanted))
			{
				return row.getId();
			}
		}
		return -1;
	}

	/**
	 * The chat-name fallback: a dose's worth as the 4-dose item's GE price over four,
	 * found by the name as drunk, or 0 unknown. The pack path above is preferred; this
	 * runs only for a drink line no pack change explained.
	 */
	private int dosePrice(String potion)
	{
		if (potion == null || potion.isEmpty())
		{
			return 0;
		}
		Integer known = dosePrices.get(potion);
		if (known != null)
		{
			return known;
		}
		boolean catalogueAnswered = false;
		try
		{
			for (String fourDose : fourDoseNames(potion))
			{
				List<ItemPrice> matches = itemManager.search(fourDose);
				catalogueAnswered |= !matches.isEmpty();
				for (ItemPrice p : matches)
				{
					if (p.getName().equalsIgnoreCase(fourDose))
					{
						int dose = p.getPrice() / 4;
						dosePrices.put(potion, dose);
						return dose;
					}
				}
			}
			// An empty result can just mean the client's price list hasn't loaded, and
			// caching a zero from that would leave the potion unpriced all session. Only
			// a search that came back with something proves there's no 4-dose form.
			if (catalogueAnswered)
			{
				dosePrices.put(potion, 0);
			}
		}
		catch (RuntimeException ignored)
		{
			// price cache unavailable; the dose goes unpriced rather than guessed
		}
		return 0;
	}

	/**
	 * The 4-dose catalogue names a drink message can stand for, in the order to try
	 * them. Chat says "super restore potion" where the GE says "Super restore(4)", and
	 * the client's item search is a substring match, so the longer name finds nothing.
	 * The name as drunk goes first; its sibling with the trailing " potion" stripped
	 * (or, the other way about, appended) follows, exactly as the site registered both
	 * spellings against the one 4-dose item.
	 */
	static List<String> fourDoseNames(String potion)
	{
		String base = potion.trim();
		String sibling = base.toLowerCase(Locale.ROOT).endsWith(" potion")
			? base.substring(0, base.length() - " potion".length()).trim()
			: base + " potion";
		return List.of(base + "(4)", sibling + "(4)");
	}

	private String itemName(int itemId)
	{
		ItemComposition definition = client.getItemDefinition(itemId);
		return definition == null ? "" : definition.getName();
	}

	/** Remove one queued eat matching this item; false when none is waiting on it. */
	private boolean takePendingEat(int itemId)
	{
		for (Iterator<PendingEat> it = pendingEats.iterator(); it.hasNext(); )
		{
			if (it.next().itemId == itemId)
			{
				it.remove();
				return true;
			}
		}
		return false;
	}

	/**
	 * "Lobster" -&gt; "lobsterEaten". New foods need no config, and the historical
	 * troutEaten / cabbageEaten names fall out of the same rule.
	 */
	static String perFoodKey(String foodName)
	{
		return consumableKey(baseFoodName(foodName), "Eaten");
	}

	/**
	 * Camel-case a consumable's name and stamp the suffix on: "Prayer potion" + "Doses"
	 * -&gt; prayerPotionDoses. Empty in, empty out.
	 */
	private static String consumableKey(String name, String suffix)
	{
		StringBuilder key = new StringBuilder();
		for (String word : words(name))
		{
			if (key.length() == 0)
			{
				key.append(word);
			}
			else
			{
				key.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return key.length() == 0 ? "" : key.append(suffix).toString();
	}

	/**
	 * A consumable's name as lower-case words, letters and digits only. Apostrophes go
	 * before the split so a possessive collapses into its word: "Chef's delight" is
	 * chefs, delight, not chef, s, delight.
	 *
	 * <p>Locale.ROOT because the keys built from these words are written into the
	 * journal and read back on whatever machine opens it. A Turkish JVM lowercases I to
	 * a dotless i, which the a-z split then throws away, minting a key no other client
	 * would agree with.
	 */
	private static List<String> words(String name)
	{
		List<String> out = new ArrayList<>();
		String cleaned = name.trim().toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
		for (String word : cleaned.split("[^a-z0-9]+"))
		{
			if (!word.isEmpty())
			{
				out.add(word);
			}
		}
		return out;
	}

	/**
	 * Fold a part-eaten food back onto the whole item: every bite of one cake lands on
	 * cakeEaten. Digits survive the key builder: leave the portion prefix on and you
	 * get 23CakeEaten and friends.
	 */
	static String baseFoodName(String foodName)
	{
		String name = foodName.trim();
		// "1/2 plain pizza", "2/3 cake" -> drop the portion prefix.
		name = name.replaceFirst("^\\d+\\s*/\\s*\\d+\\s+", "");
		// "Half a pineapple pizza", "Half an admiral pie" -> drop the qualifier.
		name = name.replaceFirst("(?i)^half\\s+an?\\s+", "");
		// "Slice of cake" is the last bite of a Cake; "Part <x> pie" likewise.
		name = name.replaceFirst("(?i)^slice\\s+of\\s+", "");
		name = name.replaceFirst("(?i)^part\\s+", "");
		return name.isEmpty() ? foodName.trim() : name;
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.SPAM
			&& type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.MESBOX)
		{
			return;
		}

		String message = event.getMessage();

		// Eating isn't counted here. The "You eat the ..." line fires for only a handful
		// of foods; most meals never produced one. See onItemContainerChanged.
		if (message.contains("You drink"))
		{
			// Drinks read "You drink the <x>.", potions read "You drink some of
			// the/your <x>.".
			String drunk = consumableName(message);

			if (drunk.equals("beer"))
			{
				store.incrementStat(BEERS_DRUNK);
			}

			if (message.contains("You drink some of the") || message.contains("You drink some of your"))
			{
				store.incrementStat(POTION_DOSES);
				String potion = potionName(message);
				// Per-potion tally beside the aggregate, keyed by the name as drunk so the
				// history runs on: "restore prayer potion" -> restorePrayerPotionDoses.
				String typed = perPotionKey(potion);
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
				}
				// A quarter of the 4-dose price, off the item the pack shows leaving.
				// Anything unpriced errs low.
				priceDrink(typed, potion);

				if (message.contains("divine"))
				{
					// Divine potions always self-inflict a flat 10 damage.
					store.incrementStatBy(DIVINE_POTION_DAMAGE, 10);
				}
			}
		}

		if (message.contains("You quickly smash the empty vial"))
		{
			store.incrementStat(VIALS_SHATTERED);
		}
	}

	private static boolean isSingleHpHeal(String menuTarget)
	{
		String cleaned = Text.removeTags(menuTarget);
		for (String heal : SINGLE_HP_HEALS)
		{
			if (cleaned.contains(heal))
			{
				return true;
			}
		}
		return false;
	}

	/** The item name out of a consume line: the text between "the " and the trailing dot. */
	private static String consumableName(String message)
	{
		int from = message.indexOf("the ") + "the ".length();
		int to = message.indexOf(".");
		return message.substring(from, to).trim();
	}

	/**
	 * The potion name out of "You drink some of the/your &lt;x&gt;." Separate from
	 * {@link #consumableName} because the "your" form has no "the " to split on.
	 */
	static String potionName(String message)
	{
		int from;
		int the = message.indexOf(" of the ");
		int your = message.indexOf(" of your ");
		if (the >= 0)
		{
			from = the + " of the ".length();
		}
		else if (your >= 0)
		{
			from = your + " of your ".length();
		}
		else
		{
			return "";
		}
		int to = message.indexOf('.', from);
		return to > from ? message.substring(from, to).trim() : "";
	}

	/** "Prayer potion" -&gt; "prayerPotionDoses". No portion folding; a chat line names the whole potion. */
	static String perPotionKey(String potionName)
	{
		return consumableKey(potionName, "Doses");
	}
}
