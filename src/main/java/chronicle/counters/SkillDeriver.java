/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
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
	private static final Map<String, String> OBJECT_SPECIES = Tables.map(TABLES, "objectSpecies");
	private static final JsonObject XP_LADDERS = Tables.load("osrs_skill_xp.json");
	private static final Map<String, List<Rule>> ITEM_RULES = rules(Tables.load("osrs_skill_item_rules.json"));

	private static final Pattern FAILED_PICKPOCKET =
		Pattern.compile("You fail to pick (?:the )?([\\w'. -]+?)'s pocket.*");
	private static final Pattern BURNED = Pattern.compile(
		"You accidentally burn (?:the |some |a |an )?([\\w' -]+?)(?: to ashes)?[.!]?\\s*$");
	private static final Pattern NPC_LEVEL = Pattern.compile(
		"\\s*\\(?\\s*level[\\s-]*\\d*\\s*\\)?\\s*$", Pattern.CASE_INSENSITIVE);
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

	private final ItemManager itemManager;
	private final StatStore statStore;
	private volatile GatheredLedger gatheredLedger;

	@AllArgsConstructor
	private static final class Rule
	{
		final Predicate<String> hit;
		final String key;
		final boolean qty;
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

	void apply(String skill, int xp, int obj, int item, int qty, String target, int consumed, int consumedQty)
	{
		try
		{
			List<Map.Entry<String, Integer>> pairs = derive(skill, xp, obj, item, qty, target, consumed, consumedQty);
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
			log.debug("local derive failed for {} {}", skill, xp, e);
		}
	}


	void applyChat(String msg, String objectTarget)
	{
		if (msg.contains("into your herb sack"))
		{
			statStore.incrementStat("herbsSacked");
			Matcher sacked = HERB_SACKED.matcher(msg);
			if (sacked.find())
			{
				countTyped(camel(sacked.group(1)), "Sacked", 1);
			}
		}
		else if (msg.contains("You gently shoo the letvek"))
		{
			statStore.incrementStat("letveksShooed");
		}
		else if (msg.contains("You fill the bucket with sap"))
		{
			if (objectTarget.toLowerCase(Locale.ROOT).contains("bloodwood"))
			{
				statStore.incrementStat("bloodwoodSapBucketsFilled");
			}
		}
		else if (msg.contains("The glowing fish scatter"))
		{
			statStore.incrementStat("spiritPoolsHarpooned");
		}
		else if (msg.contains("You resurrect "))
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
		}
		else if (msg.contains("The tanner tans"))
		{
			tanned(msg);
		}
		else if (msg.contains("You put the") && msg.contains("vial"))
		{
			statStore.incrementStat("unfinishedPotionsMade");
		}
		else if (msg.contains("You accidentally burn"))
		{
			statStore.incrementStat("foodBurned");
			Matcher burned = BURNED.matcher(msg);
			if (burned.find())
			{
				countTyped(stripCamel(burned.group(1).toLowerCase(Locale.ROOT), new String[0], ""), "Burned", 1);
			}
		}
		else if (msg.contains("You plant "))
		{
			statStore.incrementStat("seedsPlanted");
			Matcher planted = PLANTED.matcher(msg);
			if (planted.find())
			{
				countTyped(camel(planted.group(1)), "Planted", 1);
			}
		}
		else if (msg.contains("Rooftop lap"))
		{
			statStore.incrementStat("rooftopAgilityLaps");
		}
		else if (msg.contains("lap count"))
		{
			statStore.incrementStat("normalAgilityLaps");
		}
		else
		{
			Matcher m = FAILED_PICKPOCKET.matcher(msg);
			if (m.matches())
			{
				statStore.incrementStat("failedPickPockets");
				countTyped(camel(m.group(1)), "FailedPickpockets", 1);
			}
		}
	}

	private void tanned(String msg)
	{
		Matcher tanned = HIDES_TANNED.matcher(msg);
		if (!tanned.find())
		{
			return;
		}
		int n = tanned.group(1).equals("your") ? 1 : intOr(tanned.group(1), 0);
		if (n > 0)
		{
			statStore.incrementStatBy("hidesTanned", n);
			String hide = tanned.group(2).trim().toLowerCase(Locale.ROOT);
			if (n > 1 && hide.endsWith("s"))
			{
				hide = hide.substring(0, hide.length() - 1);
			}
			countTyped(camel(hide), "Tanned", n);
		}
	}

	private void countTyped(String token, String suffix, int n)
	{
		if (!token.isEmpty())
		{
			statStore.incrementStatBy(token + suffix, n);
		}
	}

	private List<Map.Entry<String, Integer>> derive(String tuple)
	{
		String[] p = tuple.split("\\|", -1);
		if (p.length < 4)
		{
			return null;
		}
		return derive(p[0], intOr(p[1], 0), intOr(p[2], 0), intOr(p[3], 0),
			p.length >= 5 ? Math.max(1, intOr(p[4], 1)) : 1, p.length >= 6 ? p[5] : "",
			p.length >= 7 ? intOr(p[6], 0) : 0, p.length >= 8 ? Math.max(1, intOr(p[7], 1)) : 1);
	}

	private List<Map.Entry<String, Integer>> derive(String skill, int xp, int obj, int item, int qty,
		String target, int consumed, int consumedQty)
	{
		if (consumed == 2528 || consumed == 13148 || consumed == 34057
			|| gauntlet(item) || gauntlet(consumed) || obj == 35969)
		{
			return null;
		}
		if (skill.equals("COOKING") && (consumed == 1995 || consumed == 1935 || consumed == 1937
			|| (consumed == 0 && item == 0)) && xp > 0 && xp % 200 == 0 && xp / 200 <= 56)
		{
			return pairs("foodCooked", xp / 200, "jugOfWineCooked", xp / 200);
		}
		String tl = target.toLowerCase(Locale.ROOT);
		switch (skill)
		{
			case "SMITHING":
				return smithing(name(item), qty);
			case "HUNTER":
				if (tl.contains("herbiboar"))
				{
					return pairs("herbiboarsHarvested", 1);
				}
				if (xp == 50 && (isTrailTarget(tl) || item == 21555 || item == 21562 || item == 21566
					|| (tl.isEmpty() && item == 0)))
				{
					return null;
				}
				if (tl.isEmpty() && xp >= 1950 && xp <= 2461
					&& (name(item).isEmpty() || name(item).startsWith("Grimy ")))
				{
					return pairs("herbiboarsHarvested", 1);
				}
				break;
			case "HERBLORE":
				if (item == 0 && (tl.contains("herbiboar") || isTrailTarget(tl)))
				{
					return null;
				}
				break;
			default:
				break;
		}
		if (PRODUCTION.contains(skill))
		{
			return production(skill, xp, item, qty, target, consumed);
		}
		switch (skill)
		{
			case "RUNECRAFT":
				return runecraft(item, qty, consumed, consumedQty);
			case "FIREMAKING":
			{
				String tok = itemToken("WOODCUTTING", name(consumed));
				return typed("logsBurned", 1, tok.isEmpty() ? ladder("FIREMAKING", xp) : tok, "LogsBurned");
			}
			case "PRAYER":
				return prayer(name(consumed), xp);
			case "THIEVING":
				return thieving(target, xp, item);
			case "AGILITY":
				return typed("agilityObstacles", 1, item == 0 ? ladder("AGILITY", xp) : "", "");
			case "CONSTRUCTION":
				return pairs("constructionBuilds", 1);
			case "SAILING":
				return sailing(xp, item, consumed);
			case "FARMING":
				return typed("farmingActions", 1,
					item == 0 ? ladder("FARMING", xp) : camelOrEmpty(name(item), "Harvested"), "");
			default:
				return gathering(skill, xp, obj, item, qty);
		}
	}

	private List<Map.Entry<String, Integer>> gathering(String skill, int xp, int obj, int item, int qty)
	{
		String floor = GATHERING_FLOOR.get(skill);
		if (floor == null)
		{
			return null;
		}
		String token = item == 0 ? "" : itemToken(skill, name(item));
		int n = token.isEmpty() ? 1 : qty;
		int gained = token.isEmpty() ? 0 : canonical(item);
		if (token.isEmpty() && obj != 0)
		{
			token = OBJECT_SPECIES.getOrDefault(String.valueOf(obj), "");
		}
		if (token.isEmpty())
		{
			token = ladder(skill, xp);
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

	private List<Map.Entry<String, Integer>> runecraft(int item, int qty, int consumed, int consumedQty)
	{
		String low = name(item).toLowerCase(Locale.ROOT);
		boolean rune = low.endsWith(" rune") || low.endsWith(" runes");
		if (!rune && item != 0)
		{
			return null;
		}
		List<Map.Entry<String, Integer>> out = typed("runesCrafted", qty,
			rune ? stripCamel(low, new String[]{" runes", " rune"}, "") : "", "Runecrafted");
		if (ALTAR_ESSENCE.contains(name(consumed).toLowerCase(Locale.ROOT)))
		{
			out.add(entry("essenceCrafted", consumedQty));
		}
		return out;
	}

	private int canonical(int id)
	{
		try
		{
			return id > 0 ? itemManager.canonicalize(id) : 0;
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private int valueOf(int canonicalId, int qty)
	{
		try
		{
			return StatTracker.worth(itemManager, canonicalId, qty);
		}
		catch (RuntimeException e)
		{
			return 0;
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
		return pairs(SMITH_METALS.contains(metal) ? camel(metal) + "ItemsSmithed" : "itemsSmithed", qty);
	}

	private List<Map.Entry<String, Integer>> production(String skill, int xp, int item, int qty,
		String target, int consumed)
	{
		String name = name(item);
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
						itemToken("WOODCUTTING", name(consumed)), "LogsFletched");
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
				List<Map.Entry<String, Integer>> caught = hunterItem(name, xp);
				if (caught != null)
				{
					return caught;
				}
			}
			String n = name.trim();
			for (Rule r : n.isEmpty() ? Collections.<Rule>emptyList()
				: ITEM_RULES.getOrDefault(skill, Collections.emptyList()))
			{
				if (r.hit.test(n))
				{
					return pairs(r.key, r.qty ? qty : 1);
				}
			}
		}
		return skill.equals("HUNTER") ? hunterTarget(target, xp, item, consumed) : null;
	}

	private List<Map.Entry<String, Integer>> hunterItem(String name, int xp)
	{
		String bh = ladder("HUNTER_BIRDHOUSES", xp);
		if (!bh.isEmpty() && (name.equals("Clockwork") || name.startsWith("Bird nest") || name.equals("Feather")))
		{
			return pairs("birdhousesEmptied", 1, bh, 1);
		}
		if (name.equals("Small fishing net") || name.equals("Rope"))
		{
			List<Map.Entry<String, Integer>> typed = netTrap(xp);
			return typed != null ? typed : pairs("creaturesTrapped", 1);
		}
		if (name.equals("Feather") || name.equals("Raw chompy"))
		{
			return pairs("chompyBirdsPlucked", xp > 0 && xp % 30 == 0 && xp / 30 <= 4 ? xp / 30 : 1);
		}
		String sp = HUNTER_ITEM_SPECIES.get(name);
		if (sp != null)
		{
			return pairs("creaturesTrapped", 1, sp, 1);
		}
		if (name.equals("Bones") || name.equals("Big bones")
			|| name.equals("Raw bird meat") || name.equals("Raw beast meat"))
		{
			return typed("creaturesTrapped", 1, ladder("HUNTER", xp), "");
		}
		return null;
	}

	private static List<Map.Entry<String, Integer>> hunterTarget(String target, int xp, int item, int consumed)
	{
		String tl = target.toLowerCase(Locale.ROOT).trim();
		if (item == 28893 || (item == 0 && tl.isEmpty() && xp == 84))
		{
			return pairs("creaturesTrapped", 1, "moonlightMothsTrapped", 1);
		}
		if (tl.endsWith(" moth"))
		{
			return typed("creaturesTrapped", 1, camel(tl.substring(0, tl.length() - 5).trim()), "MothsTrapped");
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
		if (item == 0 && tl.isEmpty() && xp == 74)
		{
			return pairs("creaturesTrapped", 1, "sunlightMothsTrapped", 1);
		}
		return consumed == 10012 ? pairs("creaturesTrapped", 1) : null;
	}

	private List<Map.Entry<String, Integer>> prayer(String consumedName, int xp)
	{
		String low = consumedName.trim().toLowerCase(Locale.ROOT);
		if (low.isEmpty())
		{
			String tok = ENSOULED_REANIM_XP.get(String.valueOf(xp));
			return tok == null ? null : pairs("headsReanimated", 1, tok + "HeadsReanimated", 1);
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
				return tok.isEmpty() ? new ArrayList<>() : pairs(tok + "AshesSacrificed", verb[1]);
			}
			return verb[0] == 1 || xp == 0 ? typed("ashesScattered", 1, tok, "AshesScattered") : null;
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
			return verb[0] == 1 || xp == 0 ? pairs("bonesBuried", 1, tok + "BonesBuried", 1) : null;
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
		if (near(xp, base))
		{
			return new int[]{1, 1};
		}
		for (int n = 1; n <= 3; n++)
		{
			if (near(xp, 3 * base * n))
			{
				return new int[]{2, n};
			}
		}
		return near(xp, 3.5 * base) ? new int[]{3, 1} : new int[]{0, 0};
	}

	private static boolean near(int xp, double v)
	{
		return Math.floor(v) <= xp && xp <= Math.ceil(v);
	}

	private List<Map.Entry<String, Integer>> thieving(String target, int xp, int item)
	{
		String low = target.trim().toLowerCase(Locale.ROOT);
		if (low.equals("urn") || (low.isEmpty() && (xp == 675 || xp == 825)))
		{
			return pairs("pyramidPlunderUrns", 1);
		}
		if (low.isEmpty())
		{
			String nm = name(item).toLowerCase(Locale.ROOT);
			if (nm.endsWith("cannonball"))
			{
				return pairs("stallsThieved", 1, "cannonballStallsThieved", 1);
			}
			if (nm.endsWith(" ore") || nm.equals("coal"))
			{
				return pairs("stallsThieved", 1, "oreStallsThieved", 1);
			}
			return null;
		}
		if (low.endsWith(" stall") || low.endsWith(" stalls"))
		{
			String base = low.substring(0, low.lastIndexOf("stall")).trim();
			return pairs("stallsThieved", 1, camel(base.isEmpty() ? "market" : base) + "StallsThieved", 1);
		}
		if (low.contains("chest"))
		{
			String base = low.replace("chest", "").trim();
			return pairs("chestsLooted", 1, camel(base.isEmpty() ? "normal" : base) + "ChestsLooted", 1);
		}
		if (low.contains("safe"))
		{
			return pairs("safesCracked", 1);
		}
		return typed("pickPockets", 1, camel(NPC_LEVEL.matcher(low).replaceFirst("").trim()), "Pickpockets");
	}

	private List<Map.Entry<String, Integer>> sailing(int xp, int item, int consumed)
	{
		String gained = name(item).toLowerCase(Locale.ROOT);
		if (gained.endsWith(" salvage"))
		{
			return typed("salvagePulled", 1, stripCamel(gained, new String[]{" salvage"}, ""), "SalvagePulled");
		}
		String used = name(consumed).toLowerCase(Locale.ROOT);
		if (used.endsWith(" salvage"))
		{
			return typed("salvageSorted", 1, stripCamel(used, new String[]{" salvage"}, ""), "SalvageSorted");
		}
		if (gained.contains("port coin bag") || gained.contains("port reward bag"))
		{
			return pairs("portTasksCompleted", 1);
		}
		String key = item == 0 && consumed == 0 ? ladder("SAILING", xp) : "";
		return key.isEmpty() ? null : pairs("barracudaTrialsCompleted", 1, key, 1);
	}

	private static List<Map.Entry<String, Integer>> netTrap(int xp)
	{
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

	private static String itemToken(String skill, String itemName)
	{
		String low = itemName.trim().toLowerCase(Locale.ROOT);
		String n;
		switch (skill)
		{
			case "WOODCUTTING":
				n = low.equals("logs") ? "normal"
					: !(low.endsWith(" logs") || low.endsWith(" log")) ? null
					: low.replace(" logs", "").replace(" log", "").trim();
				n = "".equals(n) ? "normal" : n;
				break;
			case "FISHING":
				n = low.startsWith("raw ") ? low.substring(4).trim()
					: FISH_NORAW.contains(low) || low.startsWith("leaping ") ? low : null;
				break;
			case "MINING":
				n = low.startsWith("granite") ? "granite"
					: low.startsWith("sandstone") ? "sandstone"
					: low.startsWith("uncut ") ? "gem rock"
					: low.endsWith(" ore") || MINING_ROCKS.contains(low) ? low : null;
				break;
			default:
				n = low.startsWith("cooked ") ? low.substring(7).trim() : low;
		}
		return low.isEmpty() || n == null ? "" : ITEM_ALIASES.getOrDefault(n, camel(n));
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
			return first.equals("crossbow") || low.contains("crossbow") ? "" : camel(first);
		}
		if (low.endsWith(" shield") && (low.contains("wooden") || low.split(" ").length <= 3))
		{
			String first = low.split(" ", 2)[0];
			return first.equals("wooden") ? "normal" : camel(first);
		}
		return "";
	}

	private String name(int id)
	{
		if (id <= 0)
		{
			return "";
		}
		try
		{
			String nm = itemManager.getItemComposition(itemManager.canonicalize(id)).getName();
			return nm == null ? "" : nm;
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private static String ladder(String skill, int xp)
	{
		JsonObject ladder = XP_LADDERS.getAsJsonObject(skill);
		JsonElement v = ladder == null ? null : ladder.get(String.valueOf(xp));
		return v == null ? "" : v.getAsString();
	}

	private static boolean gauntlet(int id)
	{
		return id >= 23824 && id <= 23858;
	}

	private static boolean isTrailTarget(String tl)
	{
		return tl.equals("muddy patch") || tl.equals("seaweed") || tl.equals("mushroom")
			|| tl.equals("smelly mushroom") || tl.equals("rock");
	}

	private static String camel(String token)
	{
		StringBuilder out = new StringBuilder();
		for (String w : token.trim().toLowerCase(Locale.ROOT).split("[\\s\\-]+"))
		{
			if (!w.isEmpty())
			{
				out.append(out.length() == 0 ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
			}
		}
		return out.toString();
	}

	private static String camelOrEmpty(String name, String suffix)
	{
		return name.isEmpty() ? "" : camel(name) + suffix;
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
		return ITEM_ALIASES.getOrDefault(low, camel(low));
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
		return new AbstractMap.SimpleEntry<>(k, v);
	}

	private static List<Map.Entry<String, Integer>> typed(String floor, int n, String tok, String suffix)
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

	private static Map<String, List<Rule>> rules(JsonObject file)
	{
		Map<String, List<Rule>> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> skill : file.entrySet())
		{
			if (!skill.getValue().isJsonArray())
			{
				continue;
			}
			List<Rule> rules = new ArrayList<>();
			for (JsonElement el : skill.getValue().getAsJsonArray())
			{
				JsonObject r = el.getAsJsonObject();
				rules.add(new Rule(test(r.get("match").getAsString(), r.get("value")),
					r.get("key").getAsString(), r.has("qty") && r.get("qty").getAsBoolean()));
			}
			out.put(skill.getKey(), rules);
		}
		return out;
	}

	private static Predicate<String> test(String match, JsonElement value)
	{
		switch (match)
		{
			case "in_set":
				return new HashSet<>(Arrays.asList(Tables.strings(value)))::contains;
			case "regex":
				Pattern regex = Pattern.compile(value.getAsString());
				return n -> regex.matcher(n).find();
			case "endswith":
				String end = value.getAsString().toLowerCase(Locale.ROOT);
				return n -> n.toLowerCase(Locale.ROOT).endsWith(end);
			default:
				String part = value.getAsString().toLowerCase(Locale.ROOT);
				return n -> n.toLowerCase(Locale.ROOT).contains(part);
		}
	}
}
