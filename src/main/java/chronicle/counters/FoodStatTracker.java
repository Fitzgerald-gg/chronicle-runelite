/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@RequiredArgsConstructor
public class FoodStatTracker implements StatTracker
{
	private static final String[] SINGLE_HP_HEALS =
		Tables.strings(Tables.load("counters_food.json").get("singleHpHeals"));
	private static final Pattern DOSE_SUFFIX = Pattern.compile("^(.+?)\\s*\\((\\d)\\)$");
	private static final int EAT_CONFIRM_TICKS = 3;
	private static final int MAX_PENDING_EATS = 8;
	private static final int DRINK_PAIR_TICKS = 2;
	private static final int MAX_PENDING_DRINKS = 4;
	private final StatStore store;
	private final Client client;
	private final ItemManager itemManager;
	private final BiConsumer<String, Integer> consumableSink;
	private String lastConsumed;
	private int previousHitpoints = -1;
	private Map<Integer, Integer> inventorySnapshot;
	private final List<PendingEat> pendingEats = new ArrayList<>();
	private final List<DoseDrunk> pendingDoses = new ArrayList<>();
	private final List<ParkedDrink> parkedDrinks = new ArrayList<>();
	private final Map<String, Integer> itemDosePrices = new HashMap<>();
	private final Map<String, Integer> dosePrices = new HashMap<>();

	@AllArgsConstructor
	private static final class PendingEat
	{
		private final int itemId;
		private int ticksLeft;
	}

	@AllArgsConstructor
	private static final class DoseDrunk
	{
		private final int itemId;
		private final String base;
		private final int doses;
		private int ticksLeft;
	}

	@AllArgsConstructor
	private static final class ParkedDrink
	{
		private final String typed;
		private final String potion;
		private int ticksLeft;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Eat".equals(event.getMenuOption()))
		{
			lastConsumed = event.getMenuTarget();
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
		if (previousHitpoints != -1 && hitpoints == previousHitpoints + 1)
		{
			if (lastConsumed == null || !isSingleHpHeal(lastConsumed))
			{
				store.incrementStat("hitpointsRegenerated");
			}
			else
			{
				lastConsumed = null;
			}
		}
		previousHitpoints = hitpoints;
		pendingEats.removeIf(p -> --p.ticksLeft <= 0);
		pendingDoses.removeIf(d -> --d.ticksLeft <= 0);
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
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			pendingEats.clear();
			pendingDoses.clear();
			parkedDrinks.clear();
			inventorySnapshot = null;
			lastConsumed = null;
			previousHitpoints = -1;
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			itemDosePrices.clear();
			dosePrices.clear();
		}
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		Map<Integer, Integer> current = StatTracker.inventory(client, event);
		if (current == null)
		{
			return;
		}
		if (inventorySnapshot != null)
		{
			StatTracker.rises(current, inventorySnapshot, (id, n) ->
			{
				if (n == 1 && take(pendingEats, p -> p.itemId == id) != null)
				{
					eaten(id);
				}
			});
			StatTracker.rises(current, inventorySnapshot, (id, n) ->
			{
				DoseDrunk dose = n == 1 ? doseDrunk(id, current) : null;
				if (dose == null)
				{
					return;
				}
				ParkedDrink drink = take(parkedDrinks, d -> namesAgree(d.potion, dose.base));
				if (drink != null)
				{
					filePrice(drink.typed, itemDosePrice(dose));
				}
				else if (pendingDoses.size() < MAX_PENDING_DRINKS)
				{
					pendingDoses.add(dose);
				}
			});
		}
		inventorySnapshot = current;
	}

	private void eaten(int itemId)
	{
		store.incrementStat("foodEaten");
		int price = StatTracker.worth(itemManager, itemManager.canonicalize(itemId), 1);
		if (price > 0)
		{
			store.incrementStatBy("consumedValue", price);
			store.incrementStatBy("foodConsumedValue", price);
		}
		String typed = consumableKey(baseFoodName(itemName(itemId)), "Eaten");
		if (!typed.isEmpty())
		{
			store.incrementStat(typed);
			if (price > 0 && consumableSink != null)
			{
				consumableSink.accept(typed, price);
			}
		}
	}

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
		if (doses < 1 || (doses > 1 && !appeared(base + "(" + (doses - 1) + ")", current)))
		{
			return null;
		}
		return new DoseDrunk(itemId, base, doses, DRINK_PAIR_TICKS);
	}

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

	private static boolean drinkable(ItemComposition definition)
	{
		String[] actions = definition.getInventoryActions();
		return actions != null && Arrays.stream(actions).anyMatch(a -> a != null && "drink".equalsIgnoreCase(a.trim()));
	}

	private static boolean namesAgree(String potion, String base)
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

	private void priceDrink(String typed, String potion)
	{
		DoseDrunk dose = take(pendingDoses, d -> namesAgree(potion, d.base));
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

	private void filePrice(String typed, int perDose)
	{
		if (perDose <= 0)
		{
			return;
		}
		store.incrementStatBy("consumedValue", perDose);
		store.incrementStatBy("potionsConsumedValue", perDose);
		if (!typed.isEmpty() && consumableSink != null)
		{
			consumableSink.accept(typed, perDose);
		}
	}

	private int itemDosePrice(DoseDrunk dose)
	{
		Integer known = itemDosePrices.get(dose.base);
		if (known != null)
		{
			return known;
		}
		try
		{
			int fourDoseId = dose.doses == 4 ? dose.itemId : fourDoseId(dose.base);
			int perDose = fourDoseId >= 0
				? (int) Math.min(itemManager.getItemPrice(fourDoseId) / 4, Integer.MAX_VALUE) : 0;
			if (perDose > 0)
			{
				itemDosePrices.put(dose.base, perDose);
			}
			return perDose;
		}
		catch (RuntimeException ignored)
		{
			return 0;
		}
	}

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
						int dose = (int) Math.min(p.getPrice() / 4, Integer.MAX_VALUE);
						dosePrices.put(potion, dose);
						return dose;
					}
				}
			}
			if (catalogueAnswered)
			{
				dosePrices.put(potion, 0);
			}
		}
		catch (RuntimeException ignored)
		{
		}
		return 0;
	}

	private static List<String> fourDoseNames(String potion)
	{
		String base = potion.trim();
		String sibling = base.toLowerCase(Locale.ROOT).endsWith(" potion")
			? base.substring(0, base.length() - " potion".length()).trim()
			: base + " potion";
		return List.of(base + "(4)", sibling + "(4)");
	}

	private String itemName(int itemId)
	{
		return client.getItemDefinition(itemId).getName();
	}

	private static <T> T take(List<T> waiting, Predicate<T> match)
	{
		for (Iterator<T> it = waiting.iterator(); it.hasNext(); )
		{
			T t = it.next();
			if (match.test(t))
			{
				it.remove();
				return t;
			}
		}
		return null;
	}



	private static String consumableKey(String name, String suffix)
	{
		StringBuilder key = new StringBuilder();
		words(name).forEach(word ->
			key.append(key.length() == 0 ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1)));
		return key.length() == 0 ? "" : key.append(suffix).toString();
	}

	private static List<String> words(String name)
	{
		List<String> out = new ArrayList<>();
		String cleaned = name.trim().toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
		Arrays.stream(cleaned.split("[^a-z0-9]+")).filter(word -> !word.isEmpty()).forEach(out::add);
		return out;
	}

	private static String baseFoodName(String foodName)
	{
		String name = foodName.trim()
			.replaceFirst("^\\d+\\s*/\\s*\\d+\\s+", "")
			.replaceFirst("(?i)^half\\s+an?\\s+", "")
			.replaceFirst("(?i)^slice\\s+of\\s+", "")
			.replaceFirst("(?i)^part\\s+", "");
		return name.isEmpty() ? foodName.trim() : name;
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (!StatTracker.gameChat(event))
		{
			return;
		}
		String message = event.getMessage();
		if (message.contains("You drink"))
		{
			if (consumableName(message).equals("beer"))
			{
				store.incrementStat("beersDrunk");
			}
			if (message.contains("You drink some of the") || message.contains("You drink some of your"))
			{
				store.incrementStat("potionDoses");
				String potion = potionName(message);
				String typed = consumableKey(potion, "Doses");
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
				}
				priceDrink(typed, potion);
				if (message.contains("divine"))
				{
					store.incrementStatBy("divinePotionDamage", 10);
				}
			}
		}
		if (message.contains("You quickly smash the empty vial"))
		{
			store.incrementStat("vialsShattered");
		}
	}

	private static boolean isSingleHpHeal(String menuTarget)
	{
		String cleaned = Text.removeTags(menuTarget);
		return Arrays.stream(SINGLE_HP_HEALS).anyMatch(cleaned::contains);
	}

	private static String consumableName(String message)
	{
		int from = message.indexOf("the ") + "the ".length();
		return message.substring(from, message.indexOf(".")).trim();
	}

	private static String potionName(String message)
	{
		int the = message.indexOf(" of the ");
		int your = message.indexOf(" of your ");
		int from = the >= 0 ? the + " of the ".length() : your >= 0 ? your + " of your ".length() : -1;
		if (from < 0)
		{
			return "";
		}
		int to = message.indexOf('.', from);
		return to > from ? message.substring(from, to).trim() : "";
	}
}
