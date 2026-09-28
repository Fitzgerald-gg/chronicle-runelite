/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class SkillDeriver
{
	private static final JsonObject TABLES = Tables.load("counters_skill_tables.json");
	private static final Map<String, String> SMITH_ORE_ALIAS = Tables.map(TABLES, "smithOreAlias");
	private static final Set<String> SMITH_METALS = Tables.set(TABLES, "smithMetals");
	private static final Set<String> MINING_ROCKS = Tables.set(TABLES, "miningRocks");
	private static final Set<String> FISH_NORAW = Tables.set(TABLES, "fishNoRaw");
	private static final Map<String, String> ITEM_ALIASES = Tables.map(TABLES, "itemAliases");
	private static final Set<String> PRODUCTION = Tables.set(TABLES, "production");
	private static final Map<String, String> GATHERING_FLOOR = Tables.map(TABLES, "gatheringFloor");
	private static final Map<String, String> GATHERING_SUFFIX = Tables.map(TABLES, "gatheringSuffix");
	private static final Set<String> VALUED_GATHERING = Tables.set(TABLES, "valuedGathering");
	private static final Map<String, String> NET_TRAP = Tables.map(TABLES, "netTrap");
	private static final int NET_TRAP_MAX = 6;
	private static final Map<String, String> ENSOULED_REANIM_XP = Tables.map(TABLES, "reanimXp");
	private static final Map<String, String> PRAYER_BASE_XP = Tables.map(TABLES, "prayerBaseXp");
	private static final Map<String, String> HUNTER_ITEM_SPECIES = Tables.map(TABLES, "hunterItemSpecies");
	private static final Map<String, String> BUTTERFLY_TARGETS = Tables.map(TABLES, "butterflyTargets");
	private static final Set<String> ALTAR_ESSENCE = Tables.set(TABLES, "altarEssence");

	private final ItemManager itemManager;
	private final StatStore statStore;

	private volatile GatheredLedger gatheredLedger;

	private Map<String, Map<String, String>> xpTable;
	private Map<String, List<Rule>> itemRules;
	private Map<String, String> objTable;

	private static final class Rule
	{
		String match;
		String value;
		Set<String> valueSet;
		Pattern regex;
		String key;
		boolean qty;
	}

	@Inject
	SkillDeriver(ItemManager itemManager, StatStore statStore)
	{
		this.itemManager = itemManager;
		this.statStore = statStore;
	}

	void setGatheredLedger(GatheredLedger ledger)
	{
		this.gatheredLedger = ledger;
	}

	void apply(String tuple)
	{
		try
		{
			List<Map.Entry<String, Integer>> pairs = derive(tuple);
			if (pairs != null)
			{
				for (Map.Entry<String, Integer> p : pairs)
				{
					if (p.getValue() > 0)
					{
						statStore.incrementStatBy(p.getKey(), p.getValue());
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("local derive failed for {}", tuple, e);
		}
	}

	private static final Pattern FAILED_PICKPOCKET =
		Pattern.compile("You fail to pick (?:the )?([\\w'. -]+?)'s pocket.*");

	private static final Pattern BURNED = Pattern.compile(
		"You accidentally burn (?:the |some |a |an )?([\\w' -]+?)(?: to ashes)?[.!]?\\s*$");

	private static final Pattern NPC_LEVEL = Pattern.compile(
		"\\s*\\(?\\s*level[\\s-]*\\d*\\s*\\)?\\s*$", Pattern.CASE_INSENSITIVE);

	static String npcName(String target)
	{
		return NPC_LEVEL.matcher(target).replaceFirst("").trim();
	}

	private static final Pattern PLANTED = Pattern.compile(
		"You plant (?:\\d+ )?(?:a |an |the |some )?([\\w'-]+(?: [\\w'-]+)*?) "
			+ "(?:seed|seeds|spore|spores|sapling|saplings|seedling|seedlings)\\b");

	private static final Pattern THRALL_RAISED = Pattern.compile(
		"You resurrect (?:a |an |the |your )?((?:lesser|superior|greater) "
			+ "(?:ghostly|skeletal|zombified))", Pattern.CASE_INSENSITIVE);

	private static final Pattern HIDES_TANNED = Pattern.compile(
		"The tanner tans (your|\\d+) ([\\w' -]+?)(?: for you)?\\.");

	private static final Pattern HERB_SACKED = Pattern.compile(
		"You put the (?:grimy )?([\\w' -]+?)(?: herb)? into your herb sack",
		Pattern.CASE_INSENSITIVE);

	void applyChat(String msg, String objectTarget)
	{
		if (msg == null || msg.isEmpty())
		{
			return;
		}
		if (chatLine(msg, objectTarget == null ? "" : objectTarget))
		{
			return;
		}
		if (msg.contains("You accidentally burn"))
		{
			statStore.incrementStat("foodBurned");
			Matcher burned = BURNED.matcher(msg);
			if (burned.find())
			{
				String food = stripCamel(burned.group(1).toLowerCase(Locale.ROOT),
					new String[0], "");
				if (!food.isEmpty())
				{
					statStore.incrementStat(food + "Burned");
				}
			}
			return;
		}
		if (msg.contains("You plant "))
		{
			statStore.incrementStat("seedsPlanted");
			Matcher planted = PLANTED.matcher(msg);
			if (planted.find())
			{
				String crop = camel(planted.group(1));
				if (!crop.isEmpty())
				{
					statStore.incrementStat(crop + "Planted");
				}
			}
			return;
		}
		if (msg.contains("Rooftop lap"))
		{
			statStore.incrementStat("rooftopAgilityLaps");
			return;
		}
		if (msg.contains("lap count"))
		{
			statStore.incrementStat("normalAgilityLaps");
			return;
		}
		Matcher m = FAILED_PICKPOCKET.matcher(msg);
		if (m.matches())
		{
			statStore.incrementStat("failedPickPockets");
			String typed = camel(m.group(1));
			if (!typed.isEmpty())
			{
				statStore.incrementStat(typed + "FailedPickpockets");
			}
		}
	}

	private boolean chatLine(String msg, String objectTarget)
	{
		if (msg.contains("into your herb sack"))
		{
			statStore.incrementStat("herbsSacked");
			Matcher sacked = HERB_SACKED.matcher(msg);
			if (sacked.find())
			{
				String herb = camel(sacked.group(1));
				if (!herb.isEmpty())
				{
					statStore.incrementStat(herb + "Sacked");
				}
			}
			return true;
		}
		if (msg.contains("You gently shoo the letvek"))
		{
			statStore.incrementStat("letveksShooed");
			return true;
		}
		if (msg.contains("You fill the bucket with sap"))
		{
			if (objectTarget.toLowerCase(Locale.ROOT).contains("bloodwood"))
			{
				statStore.incrementStat("bloodwoodSapBucketsFilled");
			}
			return true;
		}
		if (msg.contains("The glowing fish scatter"))
		{
			statStore.incrementStat("spiritPoolsHarpooned");
			return true;
		}
		if (msg.contains("You resurrect "))
		{
			Matcher raised = THRALL_RAISED.matcher(msg);
			if (raised.find())
			{
				statStore.incrementStat("thrallsSummoned");
				statStore.incrementStat(camel(raised.group(1)) + "ThrallsSummoned");
			}
			else if (msg.contains("thrall"))
			{
				statStore.incrementStat("thrallsSummoned");
			}
			return true;
		}
		if (msg.contains("The tanner tans"))
		{
			Matcher tanned = HIDES_TANNED.matcher(msg);
			if (tanned.find())
			{
				int n = tanned.group(1).equals("your") ? 1 : intOr(tanned.group(1), 0);
				if (n > 0)
				{
					statStore.incrementStatBy("hidesTanned", n);
					String hide = tanned.group(2).trim().toLowerCase(Locale.ROOT);
					if (n > 1 && hide.endsWith("s"))
					{
						hide = hide.substring(0, hide.length() - 1);
					}
					String typed = camel(hide);
					if (!typed.isEmpty())
					{
						statStore.incrementStatBy(typed + "Tanned", n);
					}
				}
			}
			return true;
		}
		if (msg.contains("You put the") && msg.contains("vial"))
		{
			statStore.incrementStat("unfinishedPotionsMade");
			return true;
		}
		return false;
	}

	List<Map.Entry<String, Integer>> derive(String tuple)
	{
		String[] parts = (tuple == null ? "" : tuple).split("\\|", -1);
		if (parts.length < 4)
		{
			return null;
		}
		String skill = parts[0];
		String xpStr = parts[1];
		String objId = parts[2];
		String itemId = parts[3];
		int qty = 1;
		if (parts.length >= 5 && !parts[4].isEmpty())
		{
			qty = Math.max(1, intOr(parts[4], 1));
		}
		String target = parts.length >= 6 ? parts[5] : "";
		String consumedId = parts.length >= 7 ? parts[6] : "";
		int consumedQty = 1;
		if (parts.length >= 8 && !parts[7].isEmpty())
		{
			consumedQty = Math.max(1, intOr(parts[7], 1));
		}

		if (consumedId.equals("2528") || consumedId.equals("13148") || consumedId.equals("34057"))
		{
			return null;
		}
		if (gauntletId(itemId) || gauntletId(consumedId) || "35969".equals(objId))
		{
			return null;
		}

		if (skill.equals("COOKING") && (consumedId.equals("1995") || consumedId.equals("1935")
			|| consumedId.equals("1937") || (consumedId.isEmpty() && itemId.isEmpty())))
		{
			int xp = intOr(xpStr, 0);
			if (xp > 0 && xp % 200 == 0 && xp / 200 <= 56)
			{
				int n = xp / 200;
				return pairs("foodCooked", n, "jugOfWineCooked", n);
			}
		}

		if (skill.equals("SMITHING"))
		{
			return smithing(name(itemId), qty);
		}

		if (skill.equals("HUNTER"))
		{
			String tl = target.toLowerCase(Locale.ROOT);
			if (tl.contains("herbiboar"))
			{
				return pairs("herbiboarsHarvested", 1);
			}
			if ("50".equals(xpStr) && (isTrailTarget(tl)
				|| itemId.equals("21555") || itemId.equals("21562") || itemId.equals("21566")
				|| (tl.isEmpty() && itemId.isEmpty())))
			{
				return null;
			}
			if (tl.isEmpty())
			{
				int xp = intOr(xpStr, 0);
				if (xp >= 1950 && xp <= 2461)
				{
					String gained = name(itemId);
					if (gained.isEmpty() || gained.startsWith("Grimy "))
					{
						return pairs("herbiboarsHarvested", 1);
					}
				}
			}
		}
		if (skill.equals("HERBLORE") && itemId.isEmpty())
		{
			String tl = target.toLowerCase(Locale.ROOT);
			if (tl.contains("herbiboar") || isTrailTarget(tl))
			{
				return null;
			}
		}

		if (PRODUCTION.contains(skill))
		{
			return production(skill, xpStr, itemId, qty, target, consumedId);
		}

		if (skill.equals("RUNECRAFT"))
		{
			String low = name(itemId).toLowerCase(Locale.ROOT);
			boolean rune = low.endsWith(" rune") || low.endsWith(" runes");
			if (!rune && !itemId.isEmpty())
			{
				return null;
			}
			List<Map.Entry<String, Integer>> out = typed("runesCrafted", qty,
				rune ? stripCamel(low, new String[]{" runes", " rune"}, "") : "", "Runecrafted");
			essenceSpent(out, consumedId, consumedQty);
			return out;
		}

		if (skill.equals("FIREMAKING"))
		{
			String tok = consumedId.isEmpty() ? "" : itemToken("WOODCUTTING", name(consumedId));
			if (tok.isEmpty() && !xpStr.isEmpty())
			{
				tok = ladder("FIREMAKING", xpStr);
			}
			return typed("logsBurned", 1, tok, "LogsBurned");
		}

		if (skill.equals("PRAYER"))
		{
			return prayer(consumedId.isEmpty() ? "" : name(consumedId), xpStr);
		}

		if (skill.equals("THIEVING"))
		{
			return thieving(target, xpStr, itemId);
		}

		if (skill.equals("AGILITY"))
		{
			return typed("agilityObstacles", 1, itemId.isEmpty() ? ladder("AGILITY", xpStr) : "", "");
		}
		if (skill.equals("CONSTRUCTION"))
		{
			return pairs("constructionBuilds", 1);
		}
		if (skill.equals("SAILING"))
		{
			return sailing(xpStr, itemId, consumedId);
		}
		if (skill.equals("FARMING"))
		{
			List<Map.Entry<String, Integer>> out = pairs("farmingActions", 1);
			if (!itemId.isEmpty())
			{
				String nm = name(itemId);
				if (!nm.isEmpty())
				{
					out.add(entry(camel(nm) + "Harvested", 1));
				}
			}
			else
			{
				String key = ladder("FARMING", xpStr);
				if (!key.isEmpty())
				{
					out.add(entry(key, 1));
				}
			}
			return out;
		}

		String floor = GATHERING_FLOOR.get(skill);
		if (floor == null)
		{
			return null;
		}
		String token = "";
		int n = 1;
		int gained = 0;
		if (!itemId.isEmpty())
		{
			token = itemToken(skill, name(itemId));
			if (!token.isEmpty())
			{
				n = qty;
				gained = canonical(itemId);
			}
		}
		if (token.isEmpty() && !objId.isEmpty())
		{
			token = objTable().getOrDefault(objId, "");
		}
		if (token.isEmpty() && !xpStr.isEmpty())
		{
			token = ladder(skill, xpStr);
		}
		List<Map.Entry<String, Integer>> out = typed(floor, n, token, GATHERING_SUFFIX.get(skill));
		if (gained > 0 && VALUED_GATHERING.contains(skill))
		{
			int gp = valueOf(gained, n);
			if (gp > 0)
			{
				out.add(entry("resourcesGatheredValue", gp));
			}
			GatheredLedger ledger = gatheredLedger;
			if (ledger != null)
			{
				ledger.noteGathered(gained);
			}
		}
		return out;
	}

	private int canonical(String itemId)
	{
		try
		{
			int id = Integer.parseInt(itemId);
			return id > 0 ? itemManager.canonicalize(id) : 0;
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private int valueOf(int canonicalId, int qty)
	{
		long each;
		try
		{
			each = itemManager.getItemPrice(canonicalId);
		}
		catch (RuntimeException e)
		{
			return 0;
		}
		if (each <= 0)
		{
			return 0;
		}
		long value = each * Math.max(1, qty);
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}

	private void essenceSpent(List<Map.Entry<String, Integer>> out, String consumedId,
		int consumedQty)
	{
		if (consumedId.isEmpty())
		{
			return;
		}
		String consumed = name(consumedId).toLowerCase(Locale.ROOT);
		if (ALTAR_ESSENCE.contains(consumed))
		{
			out.add(entry("essenceCrafted", consumedQty));
		}
	}

	private List<Map.Entry<String, Integer>> smithing(String itemName, int qty)
	{
		String low = itemName.toLowerCase(Locale.ROOT).trim();
		if (low.isEmpty())
		{
			return null;
		}
		if (low.endsWith(" bar"))
		{
			String metal = low.substring(0, low.length() - 4).trim();
			metal = SMITH_ORE_ALIAS.getOrDefault(metal, metal);
			return metal.isEmpty() ? null : pairs(camel(metal) + "BarsSmelted", qty);
		}
		if (low.equals("cannonball"))
		{
			return pairs("cannonballsSmithed", qty);
		}
		String first = low.split(" ", 2)[0];
		String metal = SMITH_ORE_ALIAS.getOrDefault(first, first);
		if (SMITH_METALS.contains(metal))
		{
			return pairs(camel(metal) + "ItemsSmithed", qty);
		}
		return pairs("itemsSmithed", qty);
	}

	private List<Map.Entry<String, Integer>> production(String skill, String xpStr,
		String itemId, int qty, String target, String consumedId)
	{
		String name = name(itemId);
		if (!name.isEmpty())
		{
			if (skill.equals("FLETCHING"))
			{
				String wood = fletchLogToken(name);
				if (!wood.isEmpty())
				{
					return pairs("logsFletched", 1, wood + "LogsFletched", 1);
				}
				if (name.equals("Arrow shaft"))
				{
					List<Map.Entry<String, Integer>> out = typed("logsFletched", 1,
						consumedId.isEmpty() ? "" : itemToken("WOODCUTTING", name(consumedId)), "LogsFletched");
					out.add(entry("arrowShaftsFletched", qty));
					return out;
				}
				if (name.equals("Headless arrow"))
				{
					return pairs("headlessArrowsFletched", qty);
				}
			}
			if (skill.equals("HUNTER"))
			{
				String bh = ladder("HUNTER_BIRDHOUSES", xpStr);
				if (!bh.isEmpty() && (name.equals("Clockwork")
					|| name.startsWith("Bird nest") || name.equals("Feather")))
				{
					return pairs("birdhousesEmptied", 1, bh, 1);
				}
				if (name.equals("Small fishing net") || name.equals("Rope"))
				{
					List<Map.Entry<String, Integer>> typed = netTrap(xpStr);
					return typed != null ? typed : pairs("creaturesTrapped", 1);
				}
				if (name.equals("Feather") || name.equals("Raw chompy"))
				{
					int xp = intOr(xpStr, 0);
					if (xp > 0 && xp % 30 == 0 && xp / 30 <= 4)
					{
						return pairs("chompyBirdsPlucked", xp / 30);
					}
					return pairs("chompyBirdsPlucked", 1);
				}
				String sp = HUNTER_ITEM_SPECIES.get(name);
				if (sp != null)
				{
					return pairs("creaturesTrapped", 1, sp, 1);
				}
				if (name.equals("Bones") || name.equals("Big bones")
					|| name.equals("Raw bird meat") || name.equals("Raw beast meat"))
				{
					return typed("creaturesTrapped", 1, ladder("HUNTER", xpStr), "");
				}
			}
			Rule match = matchProduction(skill, name);
			if (match != null)
			{
				return match.key == null ? null
					: pairs(match.key, match.qty ? qty : 1);
			}
		}
		if (skill.equals("HUNTER"))
		{
			String tl = target.toLowerCase(Locale.ROOT).trim();
			if (itemId.equals("28893"))
			{
				return pairs("creaturesTrapped", 1, "moonlightMothsTrapped", 1);
			}
			if (tl.endsWith(" moth"))
			{
				return typed("creaturesTrapped", 1, camel(tl.substring(0, tl.length() - 5).trim()),
					"MothsTrapped");
			}
			String bf = BUTTERFLY_TARGETS.get(tl);
			if (bf != null)
			{
				return pairs("creaturesTrapped", 1, bf, 1);
			}
			if (tl.contains("impling"))
			{
				return pairs("implingsCaught", 1);
			}
			if (itemId.isEmpty() && tl.isEmpty())
			{
				int xp = intOr(xpStr, 0);
				if (xp == 84)
				{
					return pairs("creaturesTrapped", 1, "moonlightMothsTrapped", 1);
				}
				if (xp == 74)
				{
					return pairs("creaturesTrapped", 1, "sunlightMothsTrapped", 1);
				}
			}
			if (consumedId.equals("10012"))
			{
				return pairs("creaturesTrapped", 1);
			}
		}
		return null;
	}

	private List<Map.Entry<String, Integer>> prayer(String consumedName, String xpStr)
	{
		String low = consumedName.trim().toLowerCase(Locale.ROOT);
		int xp = intOr(xpStr, 0);
		if (low.isEmpty())
		{
			String tok = ENSOULED_REANIM_XP.get(String.valueOf(xp));
			if (tok != null)
			{
				return pairs("headsReanimated", 1, tok + "HeadsReanimated", 1);
			}
			return null;
		}
		if (low.endsWith(" rune") || low.endsWith(" runes") || low.equals("bird's egg"))
		{
			return null;
		}
		if (low.startsWith("ensouled ") && low.endsWith(" head"))
		{
			String tok = camel(low.substring(9, low.length() - 5).trim());
			return pairs("headsReanimated", 1, tok + "HeadsReanimated", 1);
		}
		if (low.endsWith(" ashes") || low.equals("ashes"))
		{
			String tok = stripCamel(low, new String[]{" ashes"}, "");
			int[] verb = prayerVerb(xp, PRAYER_BASE_XP.get(tok));
			if (verb[0] == 2)
			{
				return tok.isEmpty() ? new ArrayList<>()
					: pairs(tok + "AshesSacrificed", verb[1]);
			}
			if (verb[0] == 1 || xp == 0)
			{
				return typed("ashesScattered", 1, tok, "AshesScattered");
			}
			return null;
		}
		if (low.endsWith(" bones") || low.equals("bones"))
		{
			String tok = stripCamel(low, new String[]{" bones"}, "normal");
			int[] verb = prayerVerb(xp, PRAYER_BASE_XP.get(tok));
			if (verb[0] == 2)
			{
				return pairs(tok + "BonesSacrificed", verb[1]);
			}
			if (verb[0] == 3)
			{
				return pairs("bonesOffered", 1, tok + "BonesOffered", 1);
			}
			if (verb[0] == 1 || xp == 0)
			{
				return pairs("bonesBuried", 1, tok + "BonesBuried", 1);
			}
			return null;
		}
		return null;
	}

	private static int[] prayerVerb(int xp, String baseXp)
	{
		if (baseXp == null || xp <= 0)
		{
			return new int[]{0, 0};
		}
		double base = Double.parseDouble(baseXp);
		if (Math.floor(base) <= xp && xp <= Math.ceil(base))
		{
			return new int[]{1, 1};
		}
		for (int n = 1; n <= 3; n++)
		{
			double v = 3 * base * n;
			if (Math.floor(v) <= xp && xp <= Math.ceil(v))
			{
				return new int[]{2, n};
			}
		}
		double v = 3.5 * base;
		if (Math.floor(v) <= xp && xp <= Math.ceil(v))
		{
			return new int[]{3, 1};
		}
		return new int[]{0, 0};
	}

	private List<Map.Entry<String, Integer>> thieving(String target, String xpStr, String itemId)
	{
		String low = target.trim().toLowerCase(Locale.ROOT);
		if (low.equals("urn"))
		{
			return pairs("pyramidPlunderUrns", 1);
		}
		if (low.isEmpty())
		{
			if ("675".equals(xpStr) || "825".equals(xpStr))
			{
				return pairs("pyramidPlunderUrns", 1);
			}
			if (!itemId.isEmpty())
			{
				String nm = name(itemId).toLowerCase(Locale.ROOT);
				if (nm.endsWith("cannonball"))
				{
					return pairs("stallsThieved", 1, "cannonballStallsThieved", 1);
				}
				if (nm.endsWith(" ore") || nm.equals("coal"))
				{
					return pairs("stallsThieved", 1, "oreStallsThieved", 1);
				}
			}
			return null;
		}
		if (low.endsWith(" stall") || low.endsWith(" stalls"))
		{
			int i = low.lastIndexOf("stall");
			String base = low.substring(0, i).trim();
			if (base.isEmpty())
			{
				base = "market";
			}
			return pairs("stallsThieved", 1, camel(base) + "StallsThieved", 1);
		}
		if (low.contains("chest"))
		{
			String base = low.replace("chest", "").trim();
			if (base.isEmpty())
			{
				base = "normal";
			}
			return pairs("chestsLooted", 1, camel(base) + "ChestsLooted", 1);
		}
		if (low.contains("safe"))
		{
			return pairs("safesCracked", 1);
		}
		return typed("pickPockets", 1, camel(npcName(low)), "Pickpockets");
	}

	private List<Map.Entry<String, Integer>> sailing(String xpStr, String itemId,
		String consumedId)
	{
		String gained = name(itemId).toLowerCase(Locale.ROOT);
		if (gained.endsWith(" salvage"))
		{
			return typed("salvagePulled", 1, stripCamel(gained, new String[]{" salvage"}, ""),
				"SalvagePulled");
		}
		String used = name(consumedId).toLowerCase(Locale.ROOT);
		if (used.endsWith(" salvage"))
		{
			return typed("salvageSorted", 1, stripCamel(used, new String[]{" salvage"}, ""),
				"SalvageSorted");
		}
		if (gained.contains("port coin bag") || gained.contains("port reward bag"))
		{
			return pairs("portTasksCompleted", 1);
		}
		if (itemId.isEmpty() && consumedId.isEmpty())
		{
			String key = ladder("SAILING", xpStr);
			if (!key.isEmpty())
			{
				return pairs("barracudaTrialsCompleted", 1, key, 1);
			}
		}
		return null;
	}

	private List<Map.Entry<String, Integer>> netTrap(String xpStr)
	{
		double xp = intOr(xpStr, 0);
		if (xp <= 0)
		{
			return null;
		}
		for (Map.Entry<String, String> row : NET_TRAP.entrySet())
		{
			double base = Double.parseDouble(row.getKey());
			int n = (int) Math.round(xp / base);
			if (n >= 1 && n <= NET_TRAP_MAX && Math.abs(xp - n * base) < 1.0)
			{
				return pairs("creaturesTrapped", n, row.getValue(), n);
			}
		}
		return null;
	}

	private String itemToken(String skill, String itemName)
	{
		String low = itemName.trim().toLowerCase(Locale.ROOT);
		if (low.isEmpty())
		{
			return "";
		}
		String n;
		switch (skill)
		{
			case "WOODCUTTING":
				if (!(low.endsWith(" logs") || low.equals("logs") || low.endsWith(" log")))
				{
					return "";
				}
				n = low.equals("logs") || low.equals("log") ? "normal"
					: low.replace(" logs", "").replace(" log", "").trim();
				if (n.isEmpty())
				{
					n = "normal";
				}
				break;
			case "FISHING":
				if (low.startsWith("raw "))
				{
					n = low.substring(4).trim();
				}
				else if (FISH_NORAW.contains(low) || low.startsWith("leaping "))
				{
					n = low;
				}
				else
				{
					return "";
				}
				break;
			case "MINING":
				if (low.startsWith("granite"))
				{
					n = "granite";
				}
				else if (low.startsWith("sandstone"))
				{
					n = "sandstone";
				}
				else if (low.startsWith("uncut "))
				{
					n = "gem rock";
				}
				else if (low.endsWith(" ore") || MINING_ROCKS.contains(low))
				{
					n = low;
				}
				else
				{
					return "";
				}
				break;
			default:
				n = low.startsWith("cooked ") ? low.substring(7).trim() : low;
		}
		String alias = ITEM_ALIASES.get(n);
		return alias != null ? alias : camel(n);
	}

	private static String fletchLogToken(String name)
	{
		String low = name.trim().toLowerCase(Locale.ROOT);
		if (low.endsWith(" (u)"))
		{
			String first = low.substring(0, low.length() - 4).trim().split(" ", 2)[0];
			if (first.equals("shortbow") || first.equals("longbow") || first.equals("bow"))
			{
				return "normal";
			}
			if (first.equals("crossbow") || low.contains("crossbow"))
			{
				return "";
			}
			return camel(first);
		}
		if (low.endsWith(" shield") && (low.contains("wooden") || low.split(" ").length <= 3))
		{
			String first = low.split(" ", 2)[0];
			if (!first.equals("wooden"))
			{
				return camel(first);
			}
			return "normal";
		}
		return "";
	}

	private Rule matchProduction(String skill, String itemName)
	{
		String n = itemName.trim();
		if (n.isEmpty())
		{
			return null;
		}
		String low = n.toLowerCase(Locale.ROOT);
		for (Rule r : itemRules().getOrDefault(skill, Collections.emptyList()))
		{
			boolean hit = false;
			switch (r.match == null ? "" : r.match)
			{
				case "default":
					hit = true;
					break;
				case "endswith":
					hit = low.endsWith(r.value.toLowerCase(Locale.ROOT));
					break;
				case "contains":
					hit = low.contains(r.value.toLowerCase(Locale.ROOT));
					break;
				case "regex":
					hit = r.regex != null && r.regex.matcher(n).find();
					break;
				case "in_set":
					hit = r.valueSet != null && r.valueSet.contains(n);
					break;
				default:
					break;
			}
			if (hit)
			{
				return r;
			}
		}
		return null;
	}

	private String name(String itemId)
	{
		if (itemId == null || itemId.isEmpty())
		{
			return "";
		}
		try
		{
			int id = Integer.parseInt(itemId);
			if (id <= 0)
			{
				return "";
			}
			String nm = itemManager.getItemComposition(itemManager.canonicalize(id)).getName();
			return nm == null ? "" : nm;
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private String ladder(String skill, String xpStr)
	{
		String v = xpTable().getOrDefault(skill, Collections.emptyMap()).get(xpStr);
		return v == null ? "" : v;
	}

	private static boolean gauntletId(String v)
	{
		try
		{
			int id = Integer.parseInt(v);
			return id >= 23824 && id <= 23858;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private static boolean isTrailTarget(String tl)
	{
		return tl.equals("muddy patch") || tl.equals("seaweed") || tl.equals("mushroom")
			|| tl.equals("smelly mushroom") || tl.equals("rock");
	}

	static String camel(String token)
	{
		String[] words = token.trim().toLowerCase(Locale.ROOT).split("[\\s\\-]+");
		StringBuilder out = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			if (out.length() == 0)
			{
				out.append(w);
			}
			else
			{
				out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return out.toString();
	}

	private static String stripCamel(String nameLow, String[] strips, String def)
	{
		String low = nameLow.trim();
		for (String s : strips)
		{
			if (low.equals(s.trim()))
			{
				low = "";
				break;
			}
			if (low.endsWith(s))
			{
				low = low.substring(0, low.length() - s.length()).trim();
				break;
			}
		}
		if (low.isEmpty())
		{
			low = def;
		}
		String alias = ITEM_ALIASES.get(low);
		return alias != null ? alias : camel(low);
	}

	private static List<Map.Entry<String, Integer>> pairs(Object... kv)
	{
		List<Map.Entry<String, Integer>> out = new ArrayList<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			out.add(entry((String) kv[i], (Integer) kv[i + 1]));
		}
		return out;
	}

	private static Map.Entry<String, Integer> entry(String k, int v)
	{
		return new java.util.AbstractMap.SimpleEntry<>(k, v);
	}

	private static List<Map.Entry<String, Integer>> typed(String floor, int n, String tok,
		String suffix)
	{
		List<Map.Entry<String, Integer>> out = pairs(floor, n);
		if (!tok.isEmpty())
		{
			out.add(entry(tok + suffix, n));
		}
		return out;
	}

	private static int intOr(String s, int def)
	{
		try
		{
			return Integer.parseInt(s);
		}
		catch (RuntimeException e)
		{
			return def;
		}
	}

	private synchronized Map<String, Map<String, String>> xpTable()
	{
		if (xpTable == null)
		{
			xpTable = new HashMap<>();
			for (Map.Entry<String, JsonElement> skill : Tables.load("osrs_skill_xp.json").entrySet())
			{
				if (!skill.getValue().isJsonObject())
				{
					continue;
				}
				Map<String, String> ladder = new HashMap<>();
				for (Map.Entry<String, JsonElement> e
					: skill.getValue().getAsJsonObject().entrySet())
				{
					if (e.getValue().isJsonPrimitive()
						&& e.getValue().getAsJsonPrimitive().isString())
					{
						ladder.put(e.getKey(), e.getValue().getAsString());
					}
				}
				xpTable.put(skill.getKey(), ladder);
			}
		}
		return xpTable;
	}

	private synchronized Map<String, List<Rule>> itemRules()
	{
		if (itemRules == null)
		{
			itemRules = new HashMap<>();
			for (Map.Entry<String, JsonElement> skill
				: Tables.load("osrs_skill_item_rules.json").entrySet())
			{
				if (!skill.getValue().isJsonArray())
				{
					continue;
				}
				List<Rule> rules = new ArrayList<>();
				for (JsonElement el : skill.getValue().getAsJsonArray())
				{
					if (!el.isJsonObject())
					{
						continue;
					}
					JsonObject ro = el.getAsJsonObject();
					Rule r = new Rule();
					r.match = ro.has("match") ? ro.get("match").getAsString() : null;
					r.key = ro.has("key") && !ro.get("key").isJsonNull()
						? ro.get("key").getAsString() : null;
					r.qty = ro.has("qty") && ro.get("qty").getAsBoolean();
					if (ro.has("value"))
					{
						JsonElement v = ro.get("value");
						if (v.isJsonArray())
						{
							r.valueSet = new HashSet<>();
							for (JsonElement item : v.getAsJsonArray())
							{
								r.valueSet.add(item.getAsString());
							}
						}
						else
						{
							r.value = v.getAsString();
							if ("regex".equals(r.match))
							{
								try
								{
									r.regex = Pattern.compile(r.value);
								}
								catch (RuntimeException e)
								{
									r.match = "";
								}
							}
						}
					}
					if (r.match != null)
					{
						rules.add(r);
					}
				}
				itemRules.put(skill.getKey(), rules);
			}
		}
		return itemRules;
	}

	private synchronized Map<String, String> objTable()
	{
		if (objTable == null)
		{
			objTable = new HashMap<>();
			JsonObject o = Tables.load("osrs_object_species.json");
			if (o.has("objects") && o.get("objects").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e
					: o.getAsJsonObject("objects").entrySet())
				{
					objTable.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}
		return objTable;
	}
}
