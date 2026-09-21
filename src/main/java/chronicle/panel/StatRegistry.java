/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Presentation table for counter keys: which family a key belongs to, which
 * section inside it, and what to call it.
 *
 * <p>Four families: Living, Combat, Skilling, Ledger &amp; Roads. In Skilling a
 * craft claims a key explicitly first, then by typed suffix. Generic totals
 * ("floors" like logsChopped, bonesBuried) head their section instead of
 * appearing as rows, and whatever the rows leave over shows as an "Other" row.
 */
public final class StatRegistry
{
	// order here is the tab order
	public static final String[] FAMILIES = {
		"Living", "Combat", "Skilling", "Ledger & Roads"
	};

	private static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"damageDealt", "damageTaken", "highestHit", "highestHitTaken",
		"hitsMissed", "hitsBlocked", "deaths", "poisonDamageTaken", "venomDamageTaken",
		"specialAttacksUsed", "damageDealtMelee", "damageDealtRanged", "damageDealtMagic",
		"thrallsSummoned"));
	// the typed thrall keys (lesserGhostlyThrallsSummoned) are minted from the
	// chat line, so Combat claims them by suffix the way a craft does
	private static final String THRALL_SUFFIX = "ThrallsSummoned";
	private static final Set<String> LIVING_FLAT = new HashSet<>(Arrays.asList(
		"foodEaten", "potionDoses", "beersDrunk", "vialsShattered",
		"hitpointsRegenerated", "divinePotionDamage", "consumedValue",
		// this build writes consumedValue alone; the split pair only reaches the
		// panel through an imported journal, and files under Living when it does
		"potionsConsumedValue", "foodConsumedValue"));
	private static final Set<String> LEDGER = new HashSet<>(Arrays.asList(
		"resourcesGatheredValue", "coinsFromAlchemy", "itemsDroppedValue",
		"itemsDiscarded", "examines", "coinsSpentAtShops", "coinsEarnedAtShops",
		"untakenLootValue", "untakenLootCount", "distanceWalked", "distanceRan",
		"ammoConsumed", "offensiveSpellsCast", "cabbagesPicked", "flaxGathered",
		"animalsPetted", "patchesRaked"));
	// kept out of the rows. History owns xp, the offering-xp keys double-count
	// real Prayer xp, and resourcesDroppedValue is drawn as the margin on the
	// resourcesGatheredValue row instead of standing on its own.
	private static final Set<String> HIDE = new HashSet<>(Arrays.asList(
		"totalXpGained", "demonicOfferingXp", "sinisterOfferingXp",
		// bowsFletched predates bowsStrung and still sits in older journals;
		// nothing writes it now, but family() would give it a row if it showed up
		"bowsFletched",
		"resourcesDroppedValue"));
	// spine-only totals: the plugin derives them from the journal (loot events,
	// loot left on the floor and the kills that left it, kills, slayer tasks,
	// collection log slots) and writes them beside the counters on each history
	// line, never into the trackers. The History summary reads them by name; they
	// are hidden from every Stats family. The lootLeft keys are not the
	// untakenLoot pair: that one is a lifetime figure carried in from an older
	// record, and the spine copy must not step on it.
	private static final Set<String> SUMMARY = new HashSet<>(Arrays.asList(
		"dropsReceived", "lootValue", "lootLeftCount", "lootLeftValue", "lootLeftKills", "kills",
		"slayerTasksCompleted", "clogSlotsObtained"));
	// high-water counters (highest hit): a period delta of one means nothing.
	// LocalStore.MAX_KEYS holds the same names for the lifetime arithmetic.
	private static final Set<String> PEAK = new HashSet<>(Arrays.asList(
		"highestHit", "highestHitTaken"));

	// one craft's claim on the key space: named keys, floor totals, typed suffixes
	private static final class SkillSpec
	{
		final String name;
		final String[] suffixes;
		final String[] floors;
		final String[] keys;

		SkillSpec(String name, String[] suffixes, String[] floors, String[] keys)
		{
			this.name = name;
			this.suffixes = suffixes;
			this.floors = floors;
			this.keys = keys;
		}
	}

	private static final String[] NONE = {};
	// matchedSuffix takes the first hit: this order, and the order inside each
	// craft's suffix list, is load-bearing (FailedPickpockets before Pickpockets)
	private static final List<SkillSpec> SKILLS = Arrays.asList(
		// the two Vampyrium keys are chat-counted: a tap is a chop with a bucket
		new SkillSpec("Woodcutting", new String[]{"LogsChopped"}, new String[]{"logsChopped"},
			new String[]{"letveksShooed", "bloodwoodSapBucketsFilled"}),
		new SkillSpec("Fishing", new String[]{"Caught"}, new String[]{"fishCaught"},
			new String[]{"spiritPoolsHarpooned"}),
		// Firemaking sits above Cooking so "LogsBurned" is claimed before the
		// broader "Burned" sweep could take a log for a burnt meal
		new SkillSpec("Firemaking", new String[]{"LogsBurned"}, new String[]{"logsBurned"}, NONE),
		// a burn is typed by the food the chat line names, under its own floor
		new SkillSpec("Cooking", new String[]{"Cooked", "Burned"},
			new String[]{"foodCooked", "foodBurned"}, NONE),
		new SkillSpec("Mining", new String[]{"OreMined", "Mined"}, new String[]{"rocksMined"}, NONE),
		new SkillSpec("Smithing", new String[]{"BarsSmelted", "ItemsSmithed"}, NONE,
			new String[]{"itemsSmithed", "cannonballsSmithed"}),
		// "Sacked" is the herb sack, typed by herb off the chat line
		new SkillSpec("Herblore", new String[]{"Sacked"}, new String[]{"herbsSacked"},
			new String[]{"herbsCleaned", "unfinishedPotionsMade", "potionsMade",
				"herbTarsMade", "weaponPoisonsMade"}),
		// logsFletched is the total the typed logs reconcile to, not a row beside
		// them: it counts every log cut to a bow, and mapleLogsFletched and its
		// siblings are that same count typed by log
		new SkillSpec("Fletching", new String[]{"LogsFletched"},
			new String[]{"logsFletched"},
			new String[]{"dartsFletched", "arrowsFletched", "boltsFletched", "boltsUnfinished",
				"javelinsFletched", "boltTips", "crossbowsStrung", "crossbowsUnstrung",
				"crossbowStocksCut", "bowsStrung",
				"arrowShaftsFletched", "headlessArrowsFletched",
				"javelinShaftsFletched", "ballistaeFletched", "blowpipesFletched"}),
		// "Tanned" is the tanner's line, typed by hide; no xp behind it
		new SkillSpec("Crafting", new String[]{"Tanned"}, new String[]{"hidesTanned"},
			new String[]{"gemsCut", "glassBlown", "leatherCrafted", "dhideCrafted",
				"jewelleryCrafted", "potteryMade", "battlestavesCrafted", "itemsSpun",
				"moltenGlassMade", "snakeskinCrafted", "xericianCrafted", "silverCrafted",
				"amuletsStrung", "potteryFired", "birdHousesCrafted", "itemsWoven",
				"amethystCut"}),
		// essenceCrafted is claimed by name so the "Runecrafted" sweep leaves it
		// alone: it counts crafts, not runes, and can't reconcile against the floor
		new SkillSpec("Runecraft", new String[]{"Runecrafted"}, new String[]{"runesCrafted"},
			new String[]{"essenceCrafted"}),
		new SkillSpec("Agility", new String[]{"Laps", "Cleared"},
			new String[]{"agilityObstacles"},
			new String[]{"rooftopAgilityLaps", "normalAgilityLaps"}),
		new SkillSpec("Thieving",
			new String[]{"FailedPickpockets", "Pickpockets", "StallsThieved", "ChestsLooted"},
			new String[]{"pickPockets", "stallsThieved", "chestsLooted"},
			new String[]{"failedPickPockets", "safesCracked", "pyramidPlunderUrns"}),
		// "Planted" is one to a patch; "Harvested" is one to an item pulled out of it
		new SkillSpec("Farming", new String[]{"Harvested", "Checked", "Planted"},
			new String[]{"farmingActions"},
			new String[]{"seedsPlanted"}),
		new SkillSpec("Hunter", new String[]{"Trapped", "BirdhousesEmptied"},
			new String[]{"creaturesTrapped", "birdhousesEmptied"},
			// the explicit claim on herbiboarsHarvested keeps Farming's
			// "Harvested" sweep off it
			new String[]{"implingsCaught", "chompyBirdsPlucked", "herbiboarsHarvested"}),
		new SkillSpec("Prayer",
			new String[]{"BonesBuried", "AshesScattered", "HeadsReanimated",
				"BonesSacrificed", "AshesSacrificed", "BonesOffered"},
			new String[]{"bonesBuried", "ashesScattered", "headsReanimated",
				"bonesOffered", "bonesSacrificed", "ashesSacrificed"},
			NONE),
		new SkillSpec("Construction", NONE, NONE, new String[]{"constructionBuilds"}),
		new SkillSpec("Sailing",
			new String[]{"SalvagePulled", "SalvageSorted", "TrialsCompleted"},
			new String[]{"salvagePulled", "salvageSorted", "barracudaTrialsCompleted"},
			new String[]{"portTasksCompleted"}));

	// key -> craft, from the named keys and floors; consulted before the suffix
	// sweep so a broad suffix (Fishing's "Caught") can't take implingsCaught
	private static final Map<String, String> KEY_SKILL = new HashMap<>();
	private static final Set<String> FLOORS = new HashSet<>();

	static
	{
		for (SkillSpec s : SKILLS)
		{
			for (String k : s.keys)
			{
				KEY_SKILL.put(k, s.name);
			}
			for (String k : s.floors)
			{
				KEY_SKILL.put(k, s.name);
				FLOORS.add(k);
			}
		}
		FLOORS.add("teleportsTotal");
		FLOORS.add("teleports");
		FLOORS.add("thrallsSummoned");
	}

	private static final Map<String, String> LABELS = new HashMap<>();
	private static final Map<String, String> TELE_NAMES = new HashMap<>();

	static
	{
		LABELS.put("hitpointsRegenerated", "Hitpoints regained");
		LABELS.put("divinePotionDamage", "Divine potion self-damage");
		LABELS.put("distanceWalked", "Distance walked");
		LABELS.put("distanceRan", "Distance run");
		LABELS.put("tilesWalked", "Tiles walked");
		LABELS.put("tilesRan", "Tiles run");
		LABELS.put("coinsFromAlchemy", "Coins from alchemy");
		LABELS.put("itemsDroppedValue", "Value dropped");
		// short because this row carries two figures (gathered and dropped) and
		// they have to fit the 214px panel
		LABELS.put("resourcesGatheredValue", "Gathered");
		LABELS.put("untakenLootValue", "Uncollected loot");
		LABELS.put("untakenLootCount", "Loot left behind");
		LABELS.put("coinsSpentAtShops", "Spent at shops");
		LABELS.put("coinsEarnedAtShops", "Earned at shops");
		LABELS.put("offensiveSpellsCast", "Offensive casts");
		LABELS.put("ammoConsumed", "Ammunition spent");
		LABELS.put("foodEaten", "Meals eaten");
		LABELS.put("potionDoses", "Doses drunk");
		LABELS.put("highestHit", "Highest hit");
		LABELS.put("highestHitTaken", "Highest hit taken");
		LABELS.put("specialAttacksUsed", "Specials spent");
		LABELS.put("consumedValue", "Consumed value");
		LABELS.put("damageDealtMelee", "· by melee");
		LABELS.put("damageDealtRanged", "· by ranged");
		LABELS.put("damageDealtMagic", "· by magic");
		LABELS.put("teleportsFairyRing", "· by fairy ring");
		// courier and bounty tasks both land on this one key
		LABELS.put("portTasksCompleted", "Port tasks");
		LABELS.put("barracudaTrialsCompleted", "Barracuda trials");
		LABELS.put("teleportsSpiritTree", "· by spirit tree");
		// the essence spent, not the runes it came back as
		LABELS.put("essenceCrafted", "Essence crafted");
		LABELS.put("thrallsSummoned", "Thralls raised");
		LABELS.put("dropsReceived", "Drops received");
		LABELS.put("lootValue", "Loot value");
		LABELS.put("slayerTasksCompleted", "Slayer tasks completed");
		// read off the slayer journey on the History tab alone, never on the spine
		LABELS.put("slayerKills", "Slayer kills");
		// counted off the feed's dated entries on the History tab alone, never
		// on the spine
		LABELS.put("petsObtained", "Pets");
		LABELS.put("questsCompleted", "Quests completed");
		LABELS.put("diariesCompleted", "Diaries completed");
		LABELS.put("combatAchievements", "Combat achievements");
		LABELS.put("levelsGained", "Levels gained");
		LABELS.put("clogSlotsObtained", "Collection log slots");
		LABELS.put("lootLeftCount", "Left on the floor");
		LABELS.put("lootLeftValue", "Value left on the floor");
		// the kills that left at least one stack: never a line of its own, it is
		// what "Drops taken" subtracts
		LABELS.put("lootLeftKills", "Kills that left loot");
		LABELS.put("kills", "Kills");
		// derived on the History tab from lootValue and lootLeftValue; named here
		// so every figure on that tab reads off one table
		LABELS.put("lootKept", "Loot kept");
		// derived from dropsReceived and lootLeftKills, on the History tab and the
		// Home strip alike
		LABELS.put("dropsTaken", "Drops taken");

		// destinations whose real name the camelCase split can't get back to
		TELE_NAMES.put("teleportsSeersVillage", "Seers' Village");
		TELE_NAMES.put("teleportsFenkenstrain", "Fenkenstrain's Castle");
		TELE_NAMES.put("teleportsFortis", "Civitas illa Fortis");
		TELE_NAMES.put("teleportsGrandExchange", "Grand Exchange");
		TELE_NAMES.put("teleportsWarriorsGuild", "Warriors' Guild");
		TELE_NAMES.put("teleportsLegendsGuild", "Legends' Guild");
		TELE_NAMES.put("teleportsOttosGrotto", "Otto's Grotto");
		TELE_NAMES.put("teleportsDiaryRegion", "Achievement Diary");
		TELE_NAMES.put("teleportsFalo", "Falo the Bard");
		TELE_NAMES.put("teleportsPandemonium", "The Pandemonium");
		TELE_NAMES.put("teleportsEmirsArena", "Emir's Arena");
		TELE_NAMES.put("teleportsChampionsGuild", "Champions' Guild");
		TELE_NAMES.put("teleportsWizardsTower", "Wizards' Tower");
		TELE_NAMES.put("teleportsTearsOfGuthix", "Tears of Guthix");
		TELE_NAMES.put("teleportsTheOutpost", "The Outpost");
		TELE_NAMES.put("teleportsSlayerDungeons", "Slayer dungeons");
		TELE_NAMES.put("teleportsColosseum", "Fortis Colosseum");
		TELE_NAMES.put("teleportsDondakansRock", "Dondakan's Rock");
		TELE_NAMES.put("teleportsEaglesEyrie", "Eagle's Eyrie");
		TELE_NAMES.put("teleportsGiantsFoundry", "Giants' Foundry");
	}

	private StatRegistry()
	{
	}

	// a leading underscore marks an internal diagnostic counter. the spine-only
	// summary keys hide too: no Stats family lists them.
	public static boolean hidden(String key)
	{
		return key.startsWith("_") || HIDE.contains(key) || SUMMARY.contains(key);
	}

	// a spine-only total the History summary reads by name
	public static boolean isSummary(String key)
	{
		return SUMMARY.contains(key);
	}

	// the spine-only summary keys, for the writer that derives them
	public static Set<String> summaryKeys()
	{
		return Collections.unmodifiableSet(SUMMARY);
	}

	// a high-water counter whose period delta means nothing
	public static boolean isPeak(String key)
	{
		return PEAK.contains(key);
	}

	// the peak keys, for the test that holds them to LocalStore.MAX_KEYS
	public static Set<String> peakKeys()
	{
		return Collections.unmodifiableSet(PEAK);
	}

	// floors are the generic totals (logsChopped, teleportsTotal) that head a
	// section instead of being listed as a row
	public static boolean isFloor(String key)
	{
		return FLOORS.contains(key);
	}

	// a typed thrall row: lesserGhostlyThrallsSummoned, never the floor itself
	private static boolean thrallTyped(String key)
	{
		return key.endsWith(THRALL_SUFFIX) && !key.equals(THRALL_SUFFIX);
	}

	// the craft that owns a key, or null if no craft claims it
	public static String skillOf(String key)
	{
		String claimed = KEY_SKILL.get(key);
		if (claimed != null)
		{
			return claimed;
		}
		if (isGp(key) || COMBAT.contains(key))
		{
			return null;   // gp totals and damage aren't skilling actions
		}
		String[] hit = matchedSuffix(key);
		return hit != null ? hit[0] : null;
	}

	// the first craft and suffix a key matches, as {craft, suffix}, or null.
	// skillOf, rowLabel and suffixOf all read it.
	private static String[] matchedSuffix(String key)
	{
		for (SkillSpec s : SKILLS)
		{
			for (String suf : s.suffixes)
			{
				if (key.endsWith(suf) && !key.equals(suf))
				{
					return new String[]{s.name, suf};
				}
			}
		}
		return null;
	}

	// which family a key files under; always one of FAMILIES
	public static String family(String key)
	{
		if (LIVING_FLAT.contains(key))
		{
			return "Living";
		}
		if (COMBAT.contains(key) || thrallTyped(key))
		{
			return "Combat";
		}
		if (LEDGER.contains(key))
		{
			return "Ledger & Roads";
		}
		if (isGp(key))
		{
			return "Ledger & Roads";
		}
		if (skillOf(key) != null)
		{
			return "Skilling";
		}
		if (key.endsWith("Eaten") || key.endsWith("Doses") || key.contains("Drunk")
			|| key.startsWith("food") || key.startsWith("potion") || key.startsWith("vials"))
		{
			return "Living";
		}
		// teleports, tiles and distance land here with everything unclaimed,
		// rather than dropping out of the panel
		return "Ledger & Roads";
	}

	/**
	 * A craft's TOP-LEVEL counters, in one line each: logs chopped, fish caught,
	 * food cooked and food burned, pickpockets and stalls and the pickpockets
	 * that failed. Never a typed row - "Maple logs chopped", "Guard" - which is
	 * the drill-in's business and not a headline's.
	 *
	 * <p>The floors first, in the order the table names them, since they are the
	 * totals the typed rows reconcile to; then the named keys, which are the
	 * counters a craft keeps beside its totals rather than under them. Both are
	 * top level. The caller ranks and caps; this only says which keys qualify.
	 */
	public static List<String> headlines(String skill)
	{
		for (SkillSpec s : SKILLS)
		{
			if (!s.name.equalsIgnoreCase(skill))
			{
				continue;
			}
			List<String> out = new ArrayList<>(Arrays.asList(s.floors));
			out.addAll(Arrays.asList(s.keys));
			return out;
		}
		return Collections.emptyList();
	}

	// section within the family; "" means the family's flat top list
	public static String subgroup(String key)
	{
		String fam = family(key);
		if (fam.equals("Skilling"))
		{
			String skill = skillOf(key);
			return skill != null ? skill : "";
		}
		if (fam.equals("Combat"))
		{
			// the one fold in Combat: thralls reconcile to their floor like a craft
			return key.equals("thrallsSummoned") || thrallTyped(key) ? "Thralls" : "";
		}
		if (fam.equals("Living"))
		{
			if (LIVING_FLAT.contains(key))
			{
				return "";
			}
			if (key.endsWith("Eaten"))
			{
				return "Food";
			}
			if (key.endsWith("Doses"))
			{
				return "Potions";
			}
			return "";
		}
		if (fam.equals("Ledger & Roads"))
		{
			// fairy rings and spirit trees are means of travel, not places
			if (key.startsWith("teleportsVia") || key.equals("teleportsTotal")
				|| key.equals("teleports") || key.equals("teleportsFairyRing")
				|| key.equals("teleportsSpiritTree"))
			{
				return "Teleports";
			}
			if (key.startsWith("teleports"))
			{
				return "Destinations";
			}
			if (key.startsWith("tiles") || key.startsWith("distance"))
			{
				return "On foot";
			}
			if (isGp(key))
			{
				return "The purse";
			}
			return "Odds & ends";
		}
		return "";
	}

	// the floor keys whose sum heads a section; empty when it has none
	public static List<String> floorKeys(String subgroup)
	{
		for (SkillSpec s : SKILLS)
		{
			if (s.name.equals(subgroup))
			{
				return Arrays.asList(s.floors);
			}
		}
		switch (subgroup)
		{
			case "Teleports":
				return Arrays.asList("teleportsTotal", "teleports");
			case "Food":
				return Collections.singletonList("foodEaten");
			case "Potions":
				return Collections.singletonList("potionDoses");
			case "Thralls":
				return Collections.singletonList("thrallsSummoned");
			default:
				return Collections.emptyList();
		}
	}

	// display label for a key; anything unlisted falls through to prettify
	public static String label(String key)
	{
		String explicit = LABELS.get(key);
		if (explicit != null)
		{
			return explicit;
		}
		if (key.startsWith("teleportsVia"))
		{
			return "· by " + key.substring("teleportsVia".length()).toLowerCase(Locale.ROOT);
		}
		if (key.equals("teleportsTotal") || key.equals("teleports"))
		{
			return "Teleports";
		}
		if (key.startsWith("teleports") && key.length() > "teleports".length())
		{
			return teleName(key);
		}
		return prettify(key);
	}

	// label for a key shown as a row under its section heading. the whole suffix
	// goes, since the heading already names the craft: willowLogsChopped reads
	// "Willow" under Woodcutting.
	public static String rowLabel(String key)
	{
		String skill = skillOf(key);
		if (skill != null && !KEY_SKILL.containsKey(key))
		{
			String[] hit = matchedSuffix(key);
			if (hit != null)
			{
				return typedName(key, hit[1]);
			}
		}
		if (family(key).equals("Living") && !LIVING_FLAT.contains(key))
		{
			if (key.endsWith("Eaten"))
			{
				return typedName(key, "Eaten");
			}
			if (key.endsWith("Doses"))
			{
				return typedName(key, "Doses");
			}
		}
		if (thrallTyped(key))
		{
			return typedName(key, THRALL_SUFFIX);   // "Lesser ghostly" under Thralls
		}
		return label(key);
	}

	// a typed row's label with its verb spelled out: "Shark cooked" beside
	// "Shark burned" where a list holds more than one verb and the bare row
	// labels would repeat. Rows with no verb keep their row label.
	public static String rowLabelWithVerb(String key)
	{
		String verb = suffixOf(key);
		if (verb == null)
		{
			return rowLabel(key);
		}
		String phrase = prettify(Character.toLowerCase(verb.charAt(0)) + verb.substring(1));
		return rowLabel(key) + " " + phrase.toLowerCase(Locale.ROOT);
	}

	private static String teleName(String key)
	{
		String fixed = TELE_NAMES.get(key);
		if (fixed != null)
		{
			return fixed;
		}
		String s = prettify(key.substring("teleports".length()));
		// title case every word, they're proper nouns
		StringBuilder out = new StringBuilder(s.length());
		boolean cap = true;
		for (char c : s.toCharArray())
		{
			out.append(cap && Character.isLetter(c) ? Character.toUpperCase(c) : c);
			cap = c == ' ';
		}
		return out.toString();
	}

	// strip the matched suffix: "willowLogsChopped" -> "Willow". the regex takes
	// the level off NPC-name keys as well.
	private static String typedName(String key, String suffix)
	{
		String base = key.substring(0, key.length() - suffix.length());
		String s = prettify(base).replaceAll("(?i)\\(?level\\s*\\d+\\)?", " ")
			.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
		return s.isEmpty() ? label(key) : s;
	}

	// the suffix a key matched inside its craft, or null for named claims. the
	// panel groups on it to drill Prayer into buried, scattered, ensouled.
	public static String suffixOf(String key)
	{
		if (KEY_SKILL.containsKey(key) || skillOf(key) == null)
		{
			return null;
		}
		String[] hit = matchedSuffix(key);
		return hit != null ? hit[1] : null;
	}

	// heading for a suffix group: "BonesBuried" -> "Bones buried"
	public static String suffixLabel(String suffix)
	{
		switch (suffix)
		{
			case "HeadsReanimated":
				return "Ensouled heads";
			case "OreMined":
				return "Ores mined";
			case "Sacked":
				return "Herbs sacked";
			case "Tanned":
				return "Hides tanned";
			default:
				return prettify(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
		}
	}

	// floor key heading one suffix group, or null if the craft declares none.
	// the floor is usually the decapitalised suffix; irregulars are mapped below.
	public static String suffixFloor(String craft, String suffix)
	{
		String cand;
		switch (suffix)
		{
			case "Caught":
				cand = "fishCaught";
				break;
			case "Mined":
			case "OreMined":
				cand = "rocksMined";
				break;
			case "Runecrafted":
				cand = "runesCrafted";
				break;
			case "Trapped":
				cand = "creaturesTrapped";
				break;
			case "Pickpockets":
				cand = "pickPockets";
				break;
			case "Cooked":
				cand = "foodCooked";
				break;
			case "Burned":
				cand = "foodBurned";
				break;
			case "TrialsCompleted":
				cand = "barracudaTrialsCompleted";
				break;
			case "Sacked":
				cand = "herbsSacked";
				break;
			case "Tanned":
				cand = "hidesTanned";
				break;
			default:
				cand = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
		}
		for (SkillSpec s : SKILLS)
		{
			if (!s.name.equals(craft))
			{
				continue;
			}
			for (String f : s.floors)
			{
				if (f.equals(cand))
				{
					return f;
				}
			}
		}
		return null;
	}

	// typed keys are the per-resource ones matched by suffix (willowLogsChopped,
	// sharkEaten) that a floor reconciles against. named extras like foodBurned
	// still show in their section but stay out of the "Other" arithmetic.
	public static boolean typed(String key)
	{
		if (KEY_SKILL.containsKey(key))
		{
			return false;
		}
		if (skillOf(key) != null || thrallTyped(key))
		{
			return true;
		}
		return family(key).equals("Living") && !LIVING_FLAT.contains(key)
			&& (key.endsWith("Eaten") || key.endsWith("Doses"));
	}

	// whether the value renders as gp
	public static boolean isGp(String key)
	{
		return key.endsWith("Value") || key.startsWith("coins");
	}

	// camelCase to sentence case: "vialsShattered" -> "Vials shattered"
	public static String prettify(String key)
	{
		StringBuilder out = new StringBuilder(key.length() + 8);
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (i == 0)
			{
				out.append(Character.toUpperCase(c));
			}
			else if (Character.isUpperCase(c))
			{
				out.append(' ').append(Character.toLowerCase(c));
			}
			else
			{
				out.append(c);
			}
		}
		return polish(out.toString());
	}

	// tidy a prettified label: item-plus-action keys stutter ("Logs logs
	// chopped"), and NPC keys turn up with a bracketed level
	private static String polish(String label)
	{
		String s = label.replaceAll("(?<=\\S)\\(", " (")
			.replaceAll("\\(level ?(\\d+)\\)", "(lvl $1)");
		String[] words = s.split(" ");
		StringBuilder out = new StringBuilder(s.length());
		String prev = null;
		for (String w : words)
		{
			if (prev != null && w.equalsIgnoreCase(prev))
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(w);
			prev = w;
		}
		return out.toString();
	}

	// count descending, label breaking the tie so the order stays stable
	public static int compareRows(Map.Entry<String, Long> a, Map.Entry<String, Long> b)
	{
		int byValue = Long.compare(b.getValue(), a.getValue());
		return byValue != 0 ? byValue
			: rowLabel(a.getKey()).compareToIgnoreCase(rowLabel(b.getKey()));
	}

	// section order for the families that have a fixed one. Skilling isn't here:
	// the panel ranks its crafts by total at render.
	public static List<String> fixedSections(String family)
	{
		switch (family)
		{
			case "Living":
				return Arrays.asList("", "Food", "Potions");
			case "Combat":
				return Arrays.asList("", "Thralls");
			case "Ledger & Roads":
				return Arrays.asList("The purse", "On foot", "Teleports", "Destinations", "Odds & ends");
			default:
				return new ArrayList<>(Collections.singletonList(""));
		}
	}
}
