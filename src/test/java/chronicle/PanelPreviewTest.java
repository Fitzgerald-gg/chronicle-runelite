/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

public class PanelPreviewTest
{
	private static final int PANEL_W = 242;
	private static final int MAX_H = 2600;

	@Test
	public void renderAllSurfaces() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		edt(() -> javax.swing.UIManager.setLookAndFeel(
			new net.runelite.client.ui.laf.RuneLiteLAF()));
		File out = new File("build/panel-preview");
		out.mkdirs();
		File[] stale = out.listFiles((d, n) -> n.endsWith(".png"));
		if (stale != null)
		{
			for (File f : stale)
			{
				f.delete();
			}
		}

		renderSet(out, "fix", fixtureStub());

		StubPlugin real = System.getProperty("chronicle.realJournal") != null
			? realJournalPlugin() : null;
		if (real != null)
		{
			renderSet(out, "real", real);
		}
	}

	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	private static chronicle.counters.ExperienceStatTracker.SkillGain gain(
		net.runelite.api.Skill skill, long xp, long perHour) throws Exception
	{
		java.lang.reflect.Constructor<chronicle.counters.ExperienceStatTracker.SkillGain> c =
			chronicle.counters.ExperienceStatTracker.SkillGain.class.getDeclaredConstructor(
				net.runelite.api.Skill.class, long.class, long.class);
		c.setAccessible(true);
		return c.newInstance(skill, xp, perHour);
	}

	private void renderSet(File out, String prefix, StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel panel = holder[0];

		shoot(panel, out, prefix + "-home", "HOME");
		expandSection(panel, "home:xp");
		shoot(panel, out, prefix + "-home-xp", "HOME");
		collapseAll(panel);
		shoot(panel, out, prefix + "-kills", "KILLS");
		regatherHistory(panel);
		awaitHistory(panel);
		shoot(panel, out, prefix + "-sheet", "SHEET");
		for (String page : new String[]{"clues", "quests", "diaries", "combat"})
		{
			set(panel, "sheetPage", page);
			shoot(panel, out, prefix + "-page-" + page, "SHEET");
		}
		set(panel, "sheetPage", "combat");
		expandSection(panel, "ca:easy");
		shoot(panel, out, prefix + "-page-combat-open", "SHEET");
		collapseAll(panel);
		set(panel, "sheetPage", null);
		shoot(panel, out, prefix + "-drops", "DROPS");
		set(panel, "histGranularity", "Month");
		shoot(panel, out, prefix + "-log-month", "LOG");
		set(panel, "histGranularity", "Lifetime");

		set(panel, "histGranularity", "Month");
		shoot(panel, out, prefix + "-drops-month", "DROPS");
		set(panel, "histGranularity", "Lifetime");
		shoot(panel, out, prefix + "-slayer", "SLAYER");
		shoot(panel, out, prefix + "-slayer", "SLAYER");

		set(panel, "detailSkill", "Woodcutting");
		shoot(panel, out, prefix + "-skill-detail", "HISTORY");
		set(panel, "detailSkill", "Fishing");
		shoot(panel, out, prefix + "-skill-ground", "HISTORY");
		set(panel, "detailSkill", null);

		List<LocalStore.SourceRow> src = stub.dropSources();
		if (!src.isEmpty())
		{
			src.sort((a, b) -> Long.compare(b.value, a.value));
			set(panel, "detailSource", src.get(0).name);
			shoot(panel, out, prefix + "-source-detail", "DROPS");
			set(panel, "detailSource", null);
		}

		if (!src.isEmpty())
		{
			final String top = src.get(0).name;
			edt(() ->
			{
				Field ds = ChroniclePanel.class.getDeclaredField("drillShown");
				ds.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<String, Integer> shown = (Map<String, Integer>) ds.get(panel);
				shown.put(top, Integer.MAX_VALUE);
				Method bd = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail",
					String.class);
				bd.setAccessible(true);
				javax.swing.JPanel page = (javax.swing.JPanel) bd.invoke(panel, top);
				if (page.getComponentCount() > 2)
				{
					page.remove(1);
					page.remove(0);
				}
				Method pi = ChroniclePanel.class.getDeclaredMethod("copyImage",
					javax.swing.JPanel.class);
				pi.setAccessible(true);
				Object img = pi.invoke(null, page);
				if (img != null)
				{
					ImageIO.write((BufferedImage) img, "png",
						new File(out, prefix + "-copy-image.png"));
				}
				shown.remove(top);

				List<LocalStore.BagItem> topBag = stub.sourceItems(top);
				if (!topBag.isEmpty())
				{
					Method bi = ChroniclePanel.class.getDeclaredMethod("buildItemDetail",
						String.class);
					bi.setAccessible(true);
					javax.swing.JPanel ip = (javax.swing.JPanel) bi.invoke(panel,
						topBag.get(0).name);
					if (ip.getComponentCount() > 2)
					{
						ip.remove(1);
						ip.remove(0);
					}
					Object iimg = pi.invoke(null, ip);
					if (iimg != null)
					{
						ImageIO.write((BufferedImage) iimg, "png",
							new File(out, prefix + "-copy-item-image.png"));
					}
				}
			});
		}

		for (LocalStore.SourceRow sr : src)
		{
			List<LocalStore.BagItem> bag = stub.sourceItems(sr.name);
			if (!bag.isEmpty())
			{
				set(panel, "detailItem", bag.get(0).name);
				shoot(panel, out, prefix + "-item-detail", "DROPS");
				set(panel, "detailItem", null);
				break;
			}
		}

		set(panel, "detailTask", 0);
		shoot(panel, out, prefix + "-slayer-task", "SLAYER");
		set(panel, "detailTask", -1);
		List<LocalStore.UntakenRow> un = stub.untakenSources();
		if (!un.isEmpty())
		{
			set(panel, "leftBehindSource", un.get(0).name);
			shoot(panel, out, prefix + "-leftbehind-source", "DROPS");
			set(panel, "leftBehindSource", null);
		}
		List<LocalStore.UntakenRow> ui = stub.untakenItems();
		if (!ui.isEmpty())
		{
			set(panel, "leftBehindItem", ui.get(0).name);
			shoot(panel, out, prefix + "-leftbehind-item", "DROPS");
			set(panel, "leftBehindItem", null);
		}
		set(panel, "allTrackers", true);
		shoot(panel, out, prefix + "-all-trackers", "HOME");
		set(panel, "allTrackers", false);

		set(panel, "journalLens", "Slayer");
		shoot(panel, out, prefix + "-journal-slayer", "JOURNAL");
		set(panel, "journalLens", "All");

		set(panel, "clogTab", "Other");
		set(panel, "clogPageSel", "All Pets");
		shoot(panel, out, prefix + "-log-pets", "LOG");
		expandSection(panel, "pets:All Pets:" + firstFoldablePet(stub, "All Pets"));
		expandSection(panel, "pets:All Pets:tiny tempor");
		shoot(panel, out, prefix + "-log-pets-open", "LOG");
		collapseAll(panel);
		set(panel, "clogPageSel", "Skilling Pets");
		shoot(panel, out, prefix + "-log-pets-skilling", "LOG");
		expandSection(panel, "pets:Skilling Pets:"
			+ firstFoldablePet(stub, "Skilling Pets"));
		shoot(panel, out, prefix + "-log-pets-skilling-open", "LOG");
		collapseAll(panel);
		set(panel, "clogPageSel", null);
		set(panel, "clogTab", "Bosses");

		set(panel, "dropsLeftBehind", true);
		shoot(panel, out, prefix + "-drops-leftbehind", "DROPS");
		set(panel, "dropsLeftBehind", false);

		set(panel, "slayerLens", "Monsters");
		shoot(panel, out, prefix + "-slayer-monsters", "SLAYER");
		set(panel, "slayerLens", "Drops");
		shoot(panel, out, prefix + "-slayer-drops", "SLAYER");
		set(panel, "slayerLens", "Tasks");

		shoot(panel, out, prefix + "-log", "LOG");
		set(panel, "clogPageSel", firstClogPage(panel));
		shoot(panel, out, prefix + "-log-drill", "LOG");
		set(panel, "clogPageSel", null);

		for (String fam : chronicle.panel.StatRegistry.FAMILIES)
		{
			set(panel, "statsFamily", fam);
			String slug = fam.toLowerCase().replaceAll("[^a-z]+", "-");
			shoot(panel, out, prefix + "-stats-" + slug, "STATS");
		}
		set(panel, "statsFamily", "Skilling");
		expandSection(panel, "Skilling:Cooking");
		expandSection(panel, "Skilling:Prayer");
		expandSection(panel, "Skilling:Prayer:AshesScattered");
		expandSection(panel, "Skilling:Prayer:BonesBuried");
		shoot(panel, out, prefix + "-stats-skilling-open", "STATS");
		collapseAll(panel);
		set(panel, "statsFamily", "Ledger & Roads");
		expandSection(panel, "Ledger & Roads:Teleports");
		expandSection(panel, "Ledger & Roads:Destinations");
		shoot(panel, out, prefix + "-stats-roads-open", "STATS");
		collapseAll(panel);
		set(panel, "statsFamily", chronicle.panel.StatRegistry.FAMILIES[0]);

		LocalStore.SlayerJourney journey = stub.journey;
		List<JsonObject> feed = stub.feed;
		boolean grown = stub.historyJourney != null || stub.historyFeed != null;
		if (grown)
		{
			stub.journey = stub.historyJourney != null ? stub.historyJourney : journey;
			stub.feed = stub.historyFeed != null ? stub.historyFeed : feed;
			regatherHistory(panel);
		}
		set(panel, "histFacet", "PvM");
		shoot(panel, out, prefix + "-history-bosses", "HISTORY");
		set(panel, "histFacet", "Activities");
		shoot(panel, out, prefix + "-history-activities", "HISTORY");
		set(panel, "histFacet", "Skills");
		for (String g : new String[]{"Day", "Week", "Month", "Year"})
		{
			set(panel, "histGranularity", g);
			if ("Year".equals(g))
			{
				set(panel, "histCursor", LocalDate.now().withDayOfYear(1).minusDays(1));
			}
			shoot(panel, out, prefix + "-history-" + g.toLowerCase(), "HISTORY");
		}
		set(panel, "histCursor", LocalDate.now());
		set(panel, "histGranularity", "Week");
		expandSection(panel, "history:Experience");
		expandSection(panel, "history:Combat");
		expandSection(panel, "history:list:slayerTasksCompleted");
		expandSection(panel, "history:Loot");
		expandSection(panel, "history:list:clogSlotsObtained");
		shoot(panel, out, prefix + "-history-progress-open", "HISTORY");
		collapseAll(panel);
		expandSection(panel, "history:Skilling");
		expandSection(panel, "history:Skilling:Cooking");
		expandSection(panel, "history:Upkeep");
		expandSection(panel, "history:Living:Potions");
		expandSection(panel, "history:Travel");
		expandSection(panel, "history:Ledger & Roads:Teleports");
		expandSection(panel, "history:Achievement");
		expandSection(panel, "history:The rest");
		shoot(panel, out, prefix + "-history-groups-open", "HISTORY");
		collapseAll(panel);
		set(panel, "histGranularity", "Year");
		set(panel, "histCursor", LocalDate.now().withDayOfYear(1).minusDays(1));
		expandSection(panel, "history:Experience");
		expandSection(panel, "history:list:levelsGained");
		expandSection(panel, "history:Loot");
		expandSection(panel, "history:list:clogSlotsObtained");
		shoot(panel, out, prefix + "-history-year-open", "HISTORY");
		collapseAll(panel);
		set(panel, "histCursor", LocalDate.now());
		set(panel, "histGranularity", "Week");
		if (grown)
		{
			stub.journey = journey;
			stub.feed = feed;
			regatherHistory(panel);
		}

		shoot(panel, out, prefix + "-journal", "JOURNAL");

		set(panel, "histGranularity", "Session");
		shoot(panel, out, prefix + "-session-sheet", "SHEET");
		shoot(panel, out, prefix + "-session-stats", "STATS");
		set(panel, "histGranularity", "Lifetime");

		setSearch(panel, "dragon");
		shoot(panel, out, prefix + "-search", "HOME");
		setSearch(panel, "barrows");
		shoot(panel, out, prefix + "-search-achv", "HOME");
		setSearch(panel, "");
	}

	static StubPlugin fixtureStub() throws Exception
	{
		StubPlugin s = new StubPlugin(mockItems());
		s.spriteManager = mockSprites();
		s.rsn = "Fixture";
		s.slayer = new ChronicleEventCapture.SlayerView("Abyssal demons", 63, 184);

		s.lifetime.put("damageDealt", 1_842_337L);
		s.lifetime.put("damageDealtMelee", 1_204_818L);
		s.lifetime.put("damageDealtRanged", 402_113L);
		s.lifetime.put("damageDealtMagic", 235_406L);
		s.lifetime.put("highestHit", 73L);
		s.lifetime.put("tilesWalked", 402_551L);
		s.lifetime.put("tilesRan", 1_113_207L);
		s.lifetime.put("teleportsTotal", 4_882L);
		s.lifetime.put("teleportsViaJewellery", 1_212L);
		s.lifetime.put("teleportsViaSpell", 2_105L);
		s.lifetime.put("teleportsVarrock", 311L);
		s.lifetime.put("teleportsGrandExchange", 899L);
		s.lifetime.put("coinsFromAlchemy", 12_400_310L);
		s.lifetime.put("coinsSpentAtShops", 1_002_113L);
		s.lifetime.put("itemsDroppedValue", 1_488_120L);
		s.lifetime.put("resourcesGatheredValue", 4_233_800L);
		s.lifetime.put("resourcesDroppedValue", 1_142_600L);
		s.lifetime.put("consumedValue", 3_204_112L);
		s.lifetime.put("sharkEaten", 2_113L);
		s.lifetime.put("potionDoses", 8_442L);
		s.lifetime.put("vialsShattered", 1_204L);
		s.lifetime.put("bonesBuried", 3_112L);
		s.lifetime.put("bonesOffered", 12_078L);
		s.lifetime.put("prayersActivated", 44_120L);
		s.lifetime.put("logsChopped", 22_501L);
		s.lifetime.put("fishCaught", 18_112L);
		s.lifetime.put("oresMined", 9_313L);
		s.lifetime.put("chompyBirdsPlucked", 302L);
		s.lifetime.put("implingsCaught", 511L);
		s.lifetime.put("pickpockets", 12_113L);
		s.lifetime.put("essenceCrafted", 30_112L);
		s.lifetime.put("clueScrollsCompleted", 213L);
		s.lifetime.put("deaths", 148L);

		s.skills.put("woodcutting", new long[]{92, 6_517_253L});
		s.skills.put("mining", new long[]{85, 3_258_594L});
		s.skills.put("thieving", new long[]{78, 1_629_200L});
		s.skills.put("agility", new long[]{88, 4_470_000L});
		s.skills.put("hunter", new long[]{80, 1_986_068L});
		s.skills.put("runecraft", new long[]{91, 5_902_831L});
		s.skills.put("farming", new long[]{84, 3_000_000L});
		s.lifetime.put("yewLogsChopped", 14_204L);
		s.lifetime.put("willowLogsChopped", 5_185L);
		s.lifetime.put("magicLogsChopped", 3_112L);
		s.lifetime.put("bloodwoodLogsChopped", 4_002L);
		s.lifetime.put("coalMined", 6_204L);
		s.lifetime.put("ironOreMined", 2_113L);
		s.lifetime.put("amethystMined", 1_204L);
		s.lifetime.put("runiteOreMined", 402L);
		s.lifetime.put("masterFarmerPickpockets", 9_204L);
		s.lifetime.put("masterFarmerFailedPickpockets", 4_112L);
		s.lifetime.put("elfPickpockets", 2_100L);
		s.lifetime.put("gemStallsThieved", 3_012L);
		s.lifetime.put("seersLaps", 2_204L);
		s.lifetime.put("ardougneLaps", 1_113L);
		s.lifetime.put("canifisLaps", 402L);
		s.lifetime.put("agilityObstacles", 41_002L);
		s.lifetime.put("blackChinchompasTrapped", 8_204L);
		s.lifetime.put("redChinchompasTrapped", 1_112L);
		s.lifetime.put("herbiboarsHarvested", 812L);
		s.lifetime.put("bloodRunecrafted", 12_004L);
		s.lifetime.put("soulRunecrafted", 2_100L);
		s.lifetime.put("ranarrPlanted", 1_204L);
		s.lifetime.put("guamPlanted", 402L);
		s.lifetime.put("torstolPlanted", 120L);
		s.lifetime.put("oakPlanted", 88L);
		s.lifetime.put("yewPlanted", 44L);
		s.skills.put("fishing", new long[]{96, 10_692_629L});
		s.lifetime.put("sharkCaught", 21_204L);
		s.lifetime.put("anglerfishCaught", 8_112L);
		s.lifetime.put("minnowCaught", 4_002L);
		s.lifetime.put("harpoonFishCaught", 9_000L);
		s.lifetime.put("opulentSalvagePulled", 1_204L);
		s.lifetime.put("smallSalvagePulled", 4_002L);
		s.lifetime.put("salvagePulled", 5_206L);
		s.lifetime.put("salvageSorted", 3_112L);
		s.lifetime.put("portTasksCompleted", 802L);
		s.lifetime.put("barracudaTrialsCompleted", 220L);

		s.session.put("damageDealt", 24_113);
		s.session.put("tilesRan", 8_442);
		s.session.put("consumedValue", 112_400);
		s.session.put("sharkEaten", 42);
		s.session.put("teleportsTotal", 12);
		s.session.put("totalXpGained", 533_100);
		s.skillXp.add(gain(net.runelite.api.Skill.RUNECRAFT, 400_000, 250_000));
		s.skillXp.add(gain(net.runelite.api.Skill.SLAYER, 96_400, 60_250));
		s.skillXp.add(gain(net.runelite.api.Skill.HITPOINTS, 32_100, 20_060));
		s.skillXp.add(gain(net.runelite.api.Skill.FLETCHING, 4_600, 2_875));
		s.session.put("headlessArrowsFletched", 26_955);
		s.session.put("distanceRan", 5_739);
		s.session.put("distanceWalked", 3_786);
		s.session.put("hitsBlocked", 135);
		s.session.put("cabbagesPicked", 83);
		s.session.put("flaxGathered", 61);
		s.session.put("examines", 44);
		s.session.put("animalsPetted", 19);
		s.session.put("patchesRaked", 17);
		s.session.put("itemsDiscarded", 14);
		s.session.put("coinsSpentAtShops", 9_100);
		s.session.put("highestHit", 71);

		s.sessionLoots = 37;
		s.sessionLootValue = 1_204_113L;
		s.sessionUntaken = new long[]{9, 44_120L};
		s.sessionUntakenKills = 6;
		s.sessionWindow = new LocalStore.LootWindow();
		s.sessionWindow.loots = 37;
		s.sessionWindow.value = 1_204_113L;
		s.sessionWindow.left = 9;
		s.sessionWindow.leftValue = 44_120L;
		s.sessionWindow.leftKills = 6;
		s.sessionWindow.sources.add(new String[]{"Abyssal demons", "24", "812400"});
		s.sessionWindow.sources.add(new String[]{"Nechryael", "13", "391713"});
		s.sessionWindow.items.add(new String[]{"Abyssal whip", "1", "900000"});
		s.sessionWindow.items.add(new String[]{"Rune bar", "12", "304113"});
		s.sessionWindow.leftItems.add(new String[]{"Bones", "9", "44120"});

		s.sources.add(new LocalStore.SourceRow("Abyssal demons", 4_112, 3_890, 61_204_113L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Nechryael", 2_204, 2_090, 24_113_005L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Commander Zilyana", 214, 214, 88_204_113L, 74.2, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Crazy archaeologist", 88, 88, 1_204_113L, 31.8, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Thermonuclear smoke devil", 1_402, 1_390, 19_113_205L, 22.2, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Brutal black dragon", 950, 921, 15_204_113L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Reward pool (Tempoross)", 114, 114, 4_112_005L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.sources.add(new LocalStore.SourceRow("Casket (Tempoross)", 25, 25, 812_400L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));

		List<LocalStore.BagItem> bag = new ArrayList<>();
		bag.add(new LocalStore.BagItem(4151, "Abyssal whip", 3, 5_406_000L));
		bag.add(new LocalStore.BagItem(592, "Ashes", 3_881, 8_412L));
		bag.add(new LocalStore.BagItem(1747, "Black dragonhide", 1_204, 3_204_113L));
		bag.add(new LocalStore.BagItem(560, "Death rune", 8_112, 1_402_113L));
		s.bags.put("Abyssal demons", bag);

		s.untaken.add(new LocalStore.UntakenRow("Abyssal demons", 412, 512_113L));
		s.untaken.add(new LocalStore.UntakenRow("Nechryael", 1_204, 204_113L));
		s.untaken.add(new LocalStore.UntakenRow("Thermonuclear smoke devil", 88, 41_205L));
		s.untakenItems.add(new LocalStore.UntakenRow("Bones", 3_121, 97_435L));
		s.untakenItems.add(new LocalStore.UntakenRow("Rune javelin heads", 44, 38_210L));
		s.untakenItems.add(new LocalStore.UntakenRow("Air rune", 8_350, 41_750L));

		for (int i = 0; i < 8; i++)
		{
			s.recent.add(new LocalStore.RecentDrop(4151 + i, i == 2 ? 340 : 1, "Drop " + i));
		}

		JsonObject clog = new JsonObject();
		clog.addProperty("finished", 412);
		clog.addProperty("available", 1_568);
		JsonObject items = new JsonObject();
		items.addProperty("abyssal whip", 3);
		items.addProperty("abyssal head", 1);
		items.addProperty("pet kraken", 1);
		clog.add("clog_items", items);
		JsonObject kcs = new JsonObject();
		kcs.addProperty("abyssal sire", 214);
		kcs.addProperty("zulrah", 502);
		kcs.addProperty("zalcano", 2_023);
		kcs.addProperty("callisto", 1_500);
		kcs.addProperty("artio", 900);
		kcs.addProperty("giant mole", 12_000);
		kcs.addProperty("kraken", 3_000);
		kcs.addProperty("hespori", 61);
		kcs.addProperty("guardians of the rift", 5_218);
		kcs.addProperty("soul wars", 346);
		kcs.addProperty("clue scroll (master)", 300);
		kcs.addProperty("barbarian assault high gamble", 611);
		kcs.addProperty("hunters' loot sack (expert)", 502);
		kcs.addProperty("hunters' loot sack (master)", 110);
		kcs.addProperty("hunter guild", 4_000);
		kcs.addProperty("chompy bird", 295);
		kcs.addProperty("yama", 1_204);
		kcs.addProperty("shellbane gryphon", 214);
		kcs.addProperty("brutus", 75);
		kcs.addProperty("the mad angel", 124);
		kcs.addProperty("fishing trawler", 410);
		kcs.addProperty("tempoross", 46);
		clog.add("kcs", kcs);
		JsonObject diaries = new JsonObject();
		JsonObject western = new JsonObject();
		western.addProperty("easy", true);
		western.addProperty("medium", true);
		western.addProperty("hard", true);
		western.addProperty("elite", true);
		diaries.add("western", western);
		s.achievements.add("diaries", diaries);
		JsonObject skcs = new JsonObject();
		skcs.addProperty("Abyssal demon", 4112);
		skcs.addProperty("Nechryael", 2204);
		skcs.addProperty("Dust devil", 928);
		skcs.addProperty("Gargoyle", 661);
		clog.add("slayer_kcs", skcs);
		s.clog = clog;

		s.cloud = true;
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(new LocalStore.SlayerTask("Abyssal demons", 121, 184, 4,
			System.currentTimeMillis() / 1000.0, 1_112_400L, true));
		tasks.add(new LocalStore.SlayerTask("Nechryael", 167, 0, 0,
			System.currentTimeMillis() / 1000.0 - 400_000, 812_113L, false));
		tasks.add(new LocalStore.SlayerTask("Thermonuclear smoke devils", 233, 0, 12,
			System.currentTimeMillis() / 1000.0 - 900_000, 2_012_113L, false));
		s.journey = new LocalStore.SlayerJourney(214, 48_231, 61_204_113L,
			8_204_113L, tasks);
		s.consumVals.put("sharkEaten", 1_985_000L);
		s.consumVals.put("potionDoses", 3_204_000L);
		s.petRows.add(new LocalStore.PetRow("Pet kraken", "Kraken", 2_147,
			java.time.Instant.parse("2024-11-08T20:14:00Z").toEpochMilli()));
		s.grinds.add(new GrindBook.GrindRow("Abyssal demons", "Abyssal head",
			4_112, 6_000, 51.0));
		s.clogFinished = 412;
		s.clogAvailable = 1_568;

		s.kcs.put("Abyssal demons", 4_425L);
		s.kcs.put("Zulrah", 552L);
		s.kcs.put("Nechryael", 2_204L);
		s.ledgerKcs.put("Nechryael", 2_204L);

		long now = System.currentTimeMillis();
		s.feed.add(feedEntry(now - 3_600_000L, "PET", "petName", "Abyssal orphan"));
		s.feed.add(feedEntry(Math.max(now - 7_200_000L, java.time.LocalDate.now()
			.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
			+ 60_000L), "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(feedEntry(now - 90_000_000L, "QUEST", "questName", "Dragon Slayer II"));
		s.feed.add(feedEntry(now - 95_000_000L, "DIARY", "area", "Karamja"));
		s.feed.add(feedEntry(now - 180_000_000L, "COMBAT_ACHIEVEMENT", "task", "Perfect Zulrah"));
		s.feed.add(feedEntry(now - 190_000_000L, "DEATH", "killerName", "Commander Zilyana"));

		LocalDate day = LocalDate.now();
		LocalDate priorYear = day.minusYears(1);
		List<LocalStore.SlayerTask> grown = new ArrayList<>(tasks);
		grown.add(1, new LocalStore.SlayerTask("Gargoyles", 152, 0, 3,
			System.currentTimeMillis() / 1000.0 - 150_000, 612_113L, false));
		grown.add(2, new LocalStore.SlayerTask("Bloodvelds", 188, 0, 0,
			System.currentTimeMillis() / 1000.0 - 300_000, 402_113L, false));
		grown.add(1, new LocalStore.SlayerTask("Dust devils", 174, 0, 2,
			noon(day) / 1000.0, 512_004L, false));
		grown.add(2, new LocalStore.SlayerTask("Kalphites", 141, 0, 0,
			(noon(day) - 5_400_000L) / 1000.0, 204_113L, false));
		String[] older = {"Aberrant spectres", "Black demons", "Dagannoth", "Fire giants",
			"Greater demons", "Hellhounds", "Kurask", "Smoke devils"};
		for (int i = 0; i < older.length; i++)
		{
			grown.add(new LocalStore.SlayerTask(older[i], 120 + i * 13L, 0, 0,
				noon(priorYear.withMonth(2 + i).withDayOfMonth(9 + i)) / 1000.0,
				180_000L + i * 41_000L, false));
		}
		s.historyJourney = new LocalStore.SlayerJourney(214, 48_231, 61_204_113L,
			8_204_113L, grown);

		s.historyFeed = new ArrayList<>(s.feed);
		s.historyFeed.add(feedEntry(noon(day.minusYears(2).minusMonths(6)), "COLLECTION",
			"itemName", "Rune platebody"));
		s.historyFeed.add(session(noon(day) - 3_600_000L, 95, 412_004, 31, 2_204_113L));
		s.historyFeed.add(session(noon(day) - 18_000_000L, 42, 96_400, 12, 812_400L));
		s.historyFeed.add(feedEntry(noon(day), "COLLECTION", "itemName", "Kraken tentacle"));
		s.historyFeed.add(feedEntry(noon(day) - 1_800_000L, "LEVEL", "skill", "Slayer", "level", "92"));
		s.historyFeed.add(feedEntry(noon(day) - 5_400_000L, "PET", "petName", "Pet kraken"));
		s.historyFeed.add(feedEntry(noon(day) - 7_200_000L, "QUEST", "questName", "Dragon Slayer II"));
		s.historyFeed.add(feedEntry(noon(day) - 9_000_000L, "DIARY", "area", "Karamja",
			"difficulty", "Elite"));
		s.historyFeed.add(feedEntry(noon(day) - 10_800_000L, "COMBAT_ACHIEVEMENT", "task",
			"Vorkath Speed-Chaser", "tier", "MASTER"));
		s.historyFeed.add(feedEntry(noon(day) - 12_600_000L, "DEATH", "killerName", "Vorkath"));
		s.historyFeed.add(session(noon(day.minusDays(2)), 110, 604_113, 48, 3_112_400L));
		s.historyFeed.add(session(noon(day.minusDays(4)), 65, 188_204, 19, 904_113L));
		s.historyFeed.add(feedEntry(noon(day.minusDays(2)), "COLLECTION", "itemName",
			"Abyssal dagger"));
		s.historyFeed.add(feedEntry(noon(day.minusDays(3)), "LEVEL", "skill", "Runecraft",
			"level", "88"));
		s.historyFeed.add(feedEntry(noon(day.minusDays(3)), "COMBAT_ACHIEVEMENT", "task",
			"Perfect Zulrah", "tier", "ELITE"));
		s.historyFeed.add(feedEntry(noon(day.minusDays(5)), "PET", "petName", "Vorki"));
		s.historyFeed.add(feedEntry(noon(day.minusDays(5)), "QUEST", "questName",
			"Desert Treasure II"));
		s.historyFeed.add(feedEntry(noon(day.minusDays(6)), "DEATH", "killerName", "Zulrah"));
		s.historyFeed.add(session(noon(day.withDayOfMonth(1)), 140, 812_004, 61, 4_112_005L));
		s.historyFeed.add(feedEntry(noon(day.withDayOfMonth(1)), "COLLECTION", "itemName",
			"Dragon pickaxe"));
		s.historyFeed.add(feedEntry(noon(day.withDayOfMonth(1)), "DIARY", "area", "Kandarin",
			"difficulty", "Hard"));
		String[] logSlots = {"Bandos chestplate", "Armadyl helmet", "Zamorakian spear",
			"Saradomin sword", "Dragon warhammer", "Kraken tentacle", "Occult necklace"};
		String[] levelled = {"Attack", "Hitpoints", "Mining", "Slayer", "Farming", "Herblore",
			"Fletching", "Construction"};
		for (int m = 1; m <= 12; m++)
		{
			LocalDate on = priorYear.withMonth(m).withDayOfMonth(14);
			s.historyFeed.add(session(noon(on), 40 + m * 11L, 120_000L + m * 31_000L,
				9 + m * 3L, 204_113L + m * 61_000L));
			if (m <= logSlots.length)
			{
				s.historyFeed.add(feedEntry(noon(on) + 3_600_000L, "COLLECTION", "itemName",
					logSlots[m - 1]));
			}
			if (m <= levelled.length)
			{
				s.historyFeed.add(feedEntry(noon(on) + 7_200_000L, "LEVEL", "skill", levelled[m - 1],
					"level", String.valueOf(70 + m)));
			}
		}
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(4).withDayOfMonth(2)), "PET",
			"petName", "Tiny tempor"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(9).withDayOfMonth(19)), "PET",
			"petName", "Baby mole"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(3).withDayOfMonth(5)), "QUEST",
			"questName", "Song of the Elves"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(6).withDayOfMonth(21)), "QUEST",
			"questName", "Sins of the Father"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(8).withDayOfMonth(8)), "DIARY",
			"area", "Western Provinces", "difficulty", "Elite"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(11).withDayOfMonth(3)), "DIARY",
			"area", "Wilderness", "difficulty", "Hard"));
		String[] cas = {"Zulrah Speed-Chaser", "Perfect Vorkath", "Kree'arra Adept",
			"Hunllef Master"};
		for (int i = 0; i < cas.length; i++)
		{
			s.historyFeed.add(feedEntry(noon(priorYear.withMonth(2 + i * 3).withDayOfMonth(17)),
				"COMBAT_ACHIEVEMENT", "task", cas[i], "tier",
				new String[]{"HARD", "ELITE", "MASTER", "GRANDMASTER"}[i]));
		}
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(5).withDayOfMonth(26)), "DEATH",
			"killerName", "Commander Zilyana"));
		s.historyFeed.add(feedEntry(noon(priorYear.withMonth(10).withDayOfMonth(12)), "DEATH",
			"killerName", "Cerberus"));
		s.historyFeed.sort((a, b) -> Long.compare(b.get("ts").getAsLong(), a.get("ts").getAsLong()));

		LocalDate d = LocalDate.now();
		LocalDate lastYear = d.minusYears(1);
		Map<String, Long> yearEnd = new LinkedHashMap<>();
		yearEnd.put("attack", 12_500_000L);
		yearEnd.put("hitpoints", 12_000_000L);
		yearEnd.put("mining", 2_900_000L);
		yearEnd.put("strength", 10_000_000L);
		yearEnd.put("agility", 4_000_000L);
		yearEnd.put("smithing", 1_800_000L);
		yearEnd.put("defence", 9_000_000L);
		yearEnd.put("herblore", 3_000_000L);
		yearEnd.put("fishing", 9_500_000L);
		yearEnd.put("ranged", 11_000_000L);
		yearEnd.put("thieving", 1_300_000L);
		yearEnd.put("cooking", 13_100_000L);
		yearEnd.put("prayer", 1_500_000L);
		yearEnd.put("crafting", 2_500_000L);
		yearEnd.put("firemaking", 4_500_000L);
		yearEnd.put("magic", 8_000_000L);
		yearEnd.put("fletching", 6_000_000L);
		yearEnd.put("woodcutting", 5_500_000L);
		yearEnd.put("runecraft", 1_100_000L);
		yearEnd.put("slayer", 6_000_000L);
		yearEnd.put("farming", 2_700_000L);
		yearEnd.put("construction", 1_200_000L);
		yearEnd.put("hunter", 1_500_000L);
		Map<String, Long> quarterStep = new LinkedHashMap<>();
		quarterStep.put("attack", 200_000L);
		quarterStep.put("slayer", 150_000L);
		quarterStep.put("fishing", 150_000L);
		quarterStep.put("mining", 60_000L);
		quarterStep.put("woodcutting", 40_000L);
		quarterStep.put("runecraft", 30_000L);
		for (int q = 0; q < 4; q++)
		{
			HistoryLog.Baseline imported = new HistoryLog.Baseline();
			for (Map.Entry<String, Long> e : yearEnd.entrySet())
			{
				imported.skills.put(e.getKey(),
					e.getValue() - (3 - q) * quarterStep.getOrDefault(e.getKey(), 0L));
			}
			LocalDate quarterEnd = lastYear.withMonth(3 * (q + 1));
			s.history.put(quarterEnd.withDayOfMonth(quarterEnd.lengthOfMonth()), imported);
		}
		for (int m = 4; m <= 12; m++)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			long step = m - 4;
			b.counters.put("damageDealt", 900_000L + step * 61_000L);
			b.counters.put("damageDealtMelee", 600_000L + step * 40_000L);
			b.counters.put("damageDealtMagic", 200_000L + step * 14_000L);
			b.counters.put("damageTaken", 400_000L + step * 28_000L);
			b.counters.put("hitsMissed", 22_000L + step * 1_400L);
			b.counters.put("deaths", 40L + step);
			b.counters.put("kills", 40_000L + step * 1_900L);
			b.counters.put("dropsReceived", 5_000L + step * 280L);
			b.counters.put("lootValue", 38_000_000L + step * 2_100_000L);
			b.counters.put("lootLeftCount", 70L + step * 6L);
			b.counters.put("lootLeftValue", 400_000L + step * 41_000L);
			b.counters.put("lootLeftKills", 55L + step * 4L);
			b.counters.put("clogSlotsObtained", 360L + step * 2L);
			b.counters.put("slayerTasksCompleted", 160L + step * 3L);
			b.counters.put("fishCaught", 2_400L + step * 140L);
			b.counters.put("sharkCaught", 1_500L + step * 90L);
			b.counters.put("logsChopped", 5_600L + step * 310L);
			b.counters.put("yewLogsChopped", 4_100L + step * 240L);
			b.counters.put("foodEaten", 3_100L + step * 160L);
			b.counters.put("sharkEaten", 2_400L + step * 130L);
			b.counters.put("potionDoses", 3_800L + step * 210L);
			b.counters.put("prayerDoses", 2_100L + step * 120L);
			b.counters.put("vialsShattered", 600L + step * 24L);
			b.counters.put("consumedValue", 1_100_000L + step * 92_000L);
			b.counters.put("teleportsTotal", 700L + step * 38L);
			b.counters.put("teleportsViaJewellery", 300L + step * 17L);
			b.counters.put("teleportsCastleWars", 240L + step * 13L);
			b.counters.put("distanceRan", 240_000L + step * 14_000L);
			b.counters.put("distanceWalked", 120_000L + step * 7_000L);
			b.counters.put("tilesRan", 560_000L + step * 31_000L);
			b.counters.put("resourcesGatheredValue", 5_200_000L + step * 320_000L);
			b.counters.put("resourcesDroppedValue", 410_000L + step * 22_000L);
			b.counters.put("coinsFromAlchemy", 4_800_000L + step * 290_000L);
			b.counters.put("coinsSpentAtShops", 520_000L + step * 38_000L);
			b.counters.put("itemsDroppedValue", 700_000L + step * 44_000L);
			b.counters.put("examines", 400L + step * 22L);
			b.counters.put("cabbagesPicked", 30L + step * 2L);
			b.kcs.put("Abyssal demons", 2_800L + step * 90L);
			b.kcs.put("Zulrah", 380L + step * 8L);
			s.history.put(lastYear.withMonth(m).withDayOfMonth(14), b);
		}
		long base = 13_204_113L;
		for (int i = 35; i >= 0; i--)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			b.skills.put("attack", base + (35 - i) * 21_204L);
			b.skills.put("slayer", base / 2 + (35 - i) * 44_113L);
			b.skills.put("runecraft", 1_204_113L + (35 - i) * 8_402L);
			b.kcs.put("Abyssal demons", 4_000L + (35 - i) * 12L);
			b.kcs.put("Zulrah", 480L + (35 - i) * 2L);
			s.history.put(d.minusDays(i), b);
			if (i > 30)
			{
				continue;
			}
			b.counters.put("damageDealt", 1_500_000L + (35 - i) * 9_113L);
			b.counters.put("damageDealtMelee", 900_000L + (35 - i) * 6_000L);
			b.counters.put("damageDealtRanged", 400_000L + (35 - i) * 3_113L);
			b.counters.put("tilesRan", 900_000L + (35 - i) * 5_204L);
			b.counters.put("resourcesGatheredValue", 9_000_000L + (35 - i) * 61_000L);
			b.counters.put("resourcesDroppedValue", 700_000L + (35 - i) * 4_100L);
			b.counters.put("fishCaught", 4_000L + (35 - i) * 61L);
			b.counters.put("sharkCaught", 2_500L + (35 - i) * 40L);
			b.counters.put("logsChopped", 9_000L + (35 - i) * 120L);
			b.counters.put("yewLogsChopped", 7_000L + (35 - i) * 95L);
			b.counters.put("magicLogsChopped", 1_200L + (35 - i) * 15L);
			b.counters.put("pickPockets", 3_000L + (35 - i) * 70L);
			b.counters.put("guardPickpockets", 1_800L + (35 - i) * 52L);
			b.counters.put("foodCooked", 3_000L + (35 - i) * 33L);
			b.counters.put("sharkCooked", 2_000L + (35 - i) * 25L);
			b.counters.put("foodBurned", 300L + (35 - i) * 4L);
			b.counters.put("sharkBurned", 200L + (35 - i) * 3L);
			b.counters.put("foodEaten", 5_000L + (35 - i) * 30L);
			b.counters.put("sharkEaten", 4_000L + (35 - i) * 28L);
			b.counters.put("potionDoses", 6_000L + (35 - i) * 24L);
			b.counters.put("prayerDoses", 3_500L + (35 - i) * 20L);
			b.counters.put("vialsShattered", 900L + (35 - i) * 3L);
			b.counters.put("damageTaken", 700_000L + (35 - i) * 4_200L);
			b.counters.put("hitsMissed", 40_000L + (35 - i) * 150L);
			b.counters.put("teleportsTotal", 1_100L + (35 - i) * 9L);
			b.counters.put("teleportsViaJewellery", 500L + (35 - i) * 5L);
			b.counters.put("teleportsCastleWars", 400L + (35 - i) * 4L);
			b.counters.put("distanceRan", 400_000L + (35 - i) * 3_000L);
			b.counters.put("distanceWalked", 200_000L + (35 - i) * 1_500L);
			b.counters.put("consumedValue", 2_000_000L + (35 - i) * 18_000L);
			b.counters.put("coinsFromAlchemy", 8_000_000L + (35 - i) * 120_000L);
			b.counters.put("coinsSpentAtShops", 900_000L + (35 - i) * 11_000L);
			b.counters.put("itemsDroppedValue", 1_200_000L + (35 - i) * 9_000L);
			b.counters.put("specialAttacksUsed", 2_000L + (35 - i) * 12L);
			b.counters.put("examines", 800L + (35 - i) * 3L);
			b.counters.put("cabbagesPicked", 60L + (35 - i));
			if (i > 20)
			{
				continue;
			}
			b.counters.put("dropsReceived", 8_000L + (35 - i) * 41L);
			b.counters.put("lootValue", 61_000_000L + (35 - i) * 412_000L);
			b.counters.put("lootLeftCount", 120L + (35 - i) * 2L);
			b.counters.put("lootLeftValue", 800_000L + (35 - i) * 12_500L);
			b.counters.put("lootLeftKills", 90L + (35 - i));
			b.counters.put("kills", 60_000L + (35 - i) * 14L);
			b.counters.put("slayerTasksCompleted", 200L + (35 - i) / 3);
			b.counters.put("clogSlotsObtained", 400L + (35 - i) / 5);
		}
		return s;
	}

	static JsonObject feedEntry(long ts, String type, String key, String val)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", type);
		JsonObject data = new JsonObject();
		data.addProperty(key, val);
		e.add("data", data);
		return e;
	}

	private static JsonObject feedEntry(long ts, String type, String key, String val,
		String key2, String val2)
	{
		JsonObject e = feedEntry(ts, type, key, val);
		e.getAsJsonObject("data").addProperty(key2, val2);
		return e;
	}

	private static JsonObject session(long ts, long minutes, long xp, long drops, long dropsGp)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "SESSION");
		JsonObject data = new JsonObject();
		data.addProperty("minutes", minutes);
		data.addProperty("xp", xp);
		data.addProperty("drops", drops);
		data.addProperty("dropsGp", dropsGp);
		e.add("data", data);
		return e;
	}

	private static long noon(LocalDate d)
	{
		return d.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	static StubPlugin journalStub(String dirPath, String rsn) throws Exception
	{
		File dir = new File(dirPath);
		ItemManager im = mockItems();
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, rsn);

		StubPlugin s = new StubPlugin(im);
		s.spriteManager = mockSprites();
		s.rsn = rsn;
		s.sources = store.dropSources();
		s.untaken = store.untakenSources();
		s.untakenItems = store.untakenItems();
		s.recent = store.recentDrops();
		s.clog = store.clogSnapshot();
		s.clogFinished = store.clogFraction()[0];
		s.clogAvailable = store.clogFraction()[1];
		s.lifetime = store.trackersSnapshot();
		s.feed = store.feedNewest(2000);
		s.store = store;
		s.history = new HistoryLog(new Gson()).read(dir, rsn);
		s.journey = store.slayerJourney();
		s.consumVals = store.consumableValues();
		s.grinds = new GrindBook(new Gson()).grinds(store.clogSnapshot(), store.dropSources());
		JsonObject clKc = store.clogSnapshot();
		s.kcs.putAll(LocalStore.clogKillCounts(clKc));
		for (Map.Entry<String, Long> e
			: LocalStore.sourceKills(clKc, store.dropSources()).entrySet())
		{
			if (!s.kcs.containsKey(e.getKey()))
			{
				s.ledgerKcs.put(e.getKey(), e.getValue());
			}
		}
		return s;
	}

	private StubPlugin realJournalPlugin()
	{
		String at = System.getProperty("chronicle.realJournal");
		File dir = at != null && new File(at).isDirectory()
			? new File(at)
			: new File(System.getProperty("user.home"), ".runelite/chronicle");
		File journal = newestJournal(dir);
		if (journal == null)
		{
			return null;
		}
		String rsn = journalRsn(journal);
		String shown = System.getProperty("chronicle.asName");
		ItemManager im = mockItems();
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, rsn);

		StubPlugin s = new StubPlugin(im);
		s.spriteManager = mockSprites();
		s.rsn = shown != null && !shown.isEmpty() ? shown : rsn;
		s.sources = store.dropSources();
		s.untaken = store.untakenSources();
		s.untakenItems = store.untakenItems();
		s.recent = store.recentDrops();
		s.clog = store.clogSnapshot();
		s.clogFinished = store.clogFraction()[0];
		s.clogAvailable = store.clogFraction()[1];
		s.lifetime = store.trackersSnapshot();
		s.feed = store.feedNewest(2000);
		s.store = store;
		s.history = new HistoryLog(new Gson()).read(dir, rsn);
		s.journey = store.slayerJourney();
		s.consumVals = store.consumableValues();
		s.grinds = new GrindBook(new Gson()).grinds(store.clogSnapshot(), store.dropSources());
		JsonObject clKc = store.clogSnapshot();
		s.kcs.putAll(LocalStore.clogKillCounts(clKc));
		for (Map.Entry<String, Long> e
			: LocalStore.sourceKills(clKc, store.dropSources()).entrySet())
		{
			if (!s.kcs.containsKey(e.getKey()))
			{
				s.ledgerKcs.put(e.getKey(), e.getValue());
			}
		}
		return s;
	}

	private static File newestJournal(File dir)
	{
		File[] found = dir.listFiles((d, n) -> n.endsWith(".json"));
		File newest = null;
		if (found != null)
		{
			for (File f : found)
			{
				if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified()))
				{
					newest = f;
				}
			}
		}
		return newest;
	}

	private static String journalRsn(File journal)
	{
		String name = journal.getName();
		String stem = name.substring(0, name.length() - ".json".length());
		try
		{
			String txt = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
			JsonObject o = new Gson().fromJson(txt, JsonObject.class);
			if (o != null && o.has("rsn") && o.get("rsn").isJsonPrimitive())
			{
				String rsn = o.get("rsn").getAsString();
				if (LocalStore.slug(rsn).equals(stem))
				{
					return rsn;
				}
			}
		}
		catch (Exception ignored)
		{
		}
		return stem;
	}

	private static net.runelite.client.game.SpriteManager mockSprites()
	{
		String dir = System.getProperty("chronicle.exampleSprites");
		File art = dir == null ? null : new File(dir);
		net.runelite.client.game.SpriteManager sm =
			Mockito.mock(net.runelite.client.game.SpriteManager.class);
		Mockito.doAnswer(inv ->
		{
			int id = inv.getArgument(0);
			int frame = inv.getArgument(1);
			BufferedImage img = null;
			if (art != null)
			{
				File f = new File(art, "sprite-" + id + "-" + frame + ".png");
				if (f.isFile())
				{
					try
					{
						img = ImageIO.read(f);
					}
					catch (Exception ignored)
					{
						img = null;
					}
				}
			}
			if (img == null)
			{
				img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.setColor(new java.awt.Color(140, 120, 70));
				g.fillOval(3, 3, 26, 26);
				g.dispose();
			}
			((java.util.function.Consumer<BufferedImage>) inv.getArgument(2)).accept(img);
			return null;
		}).when(sm).getSpriteAsync(Mockito.anyInt(), Mockito.anyInt(),
			Mockito.any(java.util.function.Consumer.class));
		return sm;
	}

	private static ItemManager mockItems()
	{
		ClientThread ct = Mockito.mock(ClientThread.class);
		Mockito.doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenAnswer(inv ->
			{
				AsyncBufferedImage img =
					new AsyncBufferedImage(ct, 36, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.setColor(new java.awt.Color(96, 88, 68));
				g.fillRoundRect(6, 4, 24, 24, 6, 6);
				g.dispose();
				img.loaded();
				return img;
			});
		return im;
	}

	static class StubPlugin extends ChroniclePlugin
	{
		String rsn;
		ChronicleEventCapture.SlayerView slayer;
		Map<String, Long> lifetime = new LinkedHashMap<>();
		Map<String, Integer> session = new LinkedHashMap<>();
		Map<String, long[]> skills = new LinkedHashMap<>();
		final java.util.List<chronicle.counters.ExperienceStatTracker.SkillGain> skillXp =
			new java.util.ArrayList<>();
		int sessionLoots;
		long sessionLootValue;
		long[] sessionUntaken = {0, 0};
		int sessionUntakenKills;
		LocalStore.LootWindow sessionWindow;
		String journalWarning;
		String captureWarning;
		String captureWarningWhy;
		int fixesAsked;
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		Map<String, List<LocalStore.BagItem>> bags = new LinkedHashMap<>();
		List<LocalStore.UntakenRow> untaken = new ArrayList<>();
		List<LocalStore.UntakenRow> untakenItems = new ArrayList<>();
		List<LocalStore.RecentDrop> recent = new ArrayList<>();
		List<JsonObject> feed = new ArrayList<>();
		JsonObject clog = new JsonObject();
		JsonObject achievements = new JsonObject();
		int clogFinished;
		int clogAvailable;
		TreeMap<LocalDate, HistoryLog.Baseline> history = new TreeMap<>();
		LocalStore store;
		boolean cloud;
		LocalStore.SlayerJourney journey;
		LocalStore.SlayerJourney historyJourney;
		List<JsonObject> historyFeed;
		Map<String, Long> consumVals = new LinkedHashMap<>();
		List<GrindBook.GrindRow> grinds = new ArrayList<>();
		List<LocalStore.PetRow> petRows = new ArrayList<>();
		ItemManager itemManager;

		StubPlugin(ItemManager im)
		{
			this.itemManager = im;
		}

		long sessionStartMs;
		long sessionElapsed;

		@Override
		long sessionStart()
		{
			return sessionStartMs != 0 ? sessionStartMs : super.sessionStart();
		}

		@Override
		long sessionElapsedMinutes()
		{
			return sessionElapsed != 0 ? sessionElapsed : super.sessionElapsedMinutes();
		}

		@Override
		String displayRsn()
		{
			return rsn;
		}

		@Override
		ChronicleEventCapture.SlayerView slayerView()
		{
			return slayer;
		}

		@Override
		Map<String, Long> lifetimeCounters()
		{
			return lifetime;
		}

		java.time.LocalDate lootRollDay;
		LocalStore.LootWindow lootWindow;

		@Override
		long lootRollFrom()
		{
			if (lootRollDay != null)
			{
				return lootRollDay.atStartOfDay(java.time.ZoneId.systemDefault())
					.toInstant().toEpochMilli();
			}
			return store != null ? store.lootRollFrom() : 0;
		}

		@Override
		java.util.List<LocalStore.BagItem> onTaskLoot(long fromMs, long toMs, String task,
			boolean includeOpen)
		{
			return store != null ? store.onTaskLoot(fromMs, toMs, task, includeOpen)
				: new java.util.ArrayList<>();
		}

		@Override
		java.util.List<LocalStore.BagItem> allLoot()
		{
			return store != null ? store.allLoot() : new java.util.ArrayList<>();
		}

		@Override
		java.util.List<String> taskNames()
		{
			return store != null ? store.taskNames() : new java.util.ArrayList<>();
		}

		@Override
		long[] onTaskTally(long fromMs, long toMs, String onlyTask, boolean includeOpen)
		{
			return store != null ? store.onTaskTally(fromMs, toMs, onlyTask, includeOpen)
				: new long[]{0, 0, 0};
		}

		@Override
		java.util.Map<String, Long> journalFacts()
		{
			return store != null ? store.journalFacts() : new java.util.LinkedHashMap<>();
		}

		@Override
		java.util.Map<String, Long> chatKills()
		{
			return store != null ? store.chatKillCounts() : new java.util.LinkedHashMap<>();
		}

		@Override
		java.util.Map<String, Long> anchoredKills()
		{
			return store != null ? store.anchoredKills() : new java.util.LinkedHashMap<>();
		}

		@Override
		java.util.Map<String, long[]> onTaskItems(long fromMs, long toMs)
		{
			return store != null ? store.onTaskItems(fromMs, toMs)
				: new java.util.LinkedHashMap<>();
		}

		@Override
		java.util.Map<String, Long> onTaskKills(long fromMs, long toMs)
		{
			return store != null ? store.onTaskKills(fromMs, toMs)
				: new java.util.LinkedHashMap<>();
		}

		@Override
		java.util.List<Object[]> onTaskItemByTask(String itemName, long fromMs, long toMs)
		{
			return store != null ? store.onTaskItemByTask(itemName, fromMs, toMs)
				: new java.util.ArrayList<>();
		}

		@Override
		java.util.List<LocalStore.Assignment> onTaskAssignments(String npc, long fromMs, long toMs)
		{
			return store != null ? store.onTaskAssignments(npc, fromMs, toMs)
				: new java.util.ArrayList<>();
		}

		Map<String, long[]> itemDays = new LinkedHashMap<>();
		Map<String, long[]> dayTotals = new java.util.TreeMap<>();

		@Override
		Map<String, long[]> dayTotals()
		{
			return store != null ? store.dayTotals() : dayTotals;
		}

		@Override
		long[] itemDays(String name)
		{
			long[] said = itemDays.get(name);
			return said != null ? said : store != null ? store.itemDays(name) : new long[4];
		}

		@Override
		LocalStore.LootWindow lootBetween(java.time.LocalDate from, java.time.LocalDate to)
		{
			if (lootWindow != null)
			{
				return lootWindow;
			}
			return store != null ? store.lootBetween(from, to) : new LocalStore.LootWindow();
		}

		@Override
		Map<String, Integer> sessionView()
		{
			return session;
		}

		@Override
		Map<String, Integer> sessionDisplayCounters()
		{
			return session;
		}

		@Override
		java.util.List<chronicle.counters.ExperienceStatTracker.SkillGain> sessionSkillXp()
		{
			return skillXp;
		}

		@Override
		int sessionLoots()
		{
			return sessionLoots;
		}

		@Override
		long sessionLootValue()
		{
			return sessionLootValue;
		}

		@Override
		String captureWarning()
		{
			return captureWarning;
		}

		@Override
		String captureWarningWhy()
		{
			return captureWarningWhy;
		}

		@Override
		void turnOnMissingCapture()
		{
			fixesAsked++;
			captureWarning = null;
			captureWarningWhy = null;
		}

		@Override
		LocalStore.LootWindow sessionLootWindow()
		{
			if (sessionWindow != null)
			{
				return sessionWindow;
			}
			return store != null ? store.sessionLootWindow() : new LocalStore.LootWindow();
		}

		@Override
		long[] sessionUntakenTally()
		{
			return sessionUntaken;
		}

		@Override
		int sessionUntakenKills()
		{
			return sessionUntakenKills;
		}

		@Override
		java.util.List<LocalStore.SourceRow> dropSources()
		{
			return new ArrayList<>(sources);
		}

		@Override
		java.util.List<LocalStore.BagItem> sourceItems(String source)
		{
			if (store != null)
			{
				return store.sourceItems(source);
			}
			return new ArrayList<>(bags.getOrDefault(source, new ArrayList<>()));
		}

		final java.util.Map<String, java.util.List<LocalStore.BagItem>> periodBags =
			new java.util.LinkedHashMap<>();

		@Override
		java.util.Map<String, java.util.List<LocalStore.BagItem>> itemsBySource(
			java.time.LocalDate from, java.time.LocalDate to)
		{
			if (store != null)
			{
				return store.itemsBySource(from, to);
			}
			java.util.Map<String, java.util.List<LocalStore.BagItem>> out = new java.util.LinkedHashMap<>();
			if (from == null)
			{
				periodBags.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
			return out;
		}

		@Override
		java.util.Set<String> unfiledSources(java.time.LocalDate from, java.time.LocalDate to)
		{
			return store != null ? store.unfiledSources(from, to) : new java.util.HashSet<>();
		}

		@Override
		long lootDetailFrom()
		{
			return store != null && lootRollDay == null ? store.lootDetailFrom() : lootRollFrom();
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenSources()
		{
			return new ArrayList<>(untaken);
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenItems()
		{
			return new ArrayList<>(untakenItems);
		}

		@Override
		java.util.List<LocalStore.RecentDrop> recentDrops()
		{
			return new ArrayList<>(recent);
		}

		@Override
		java.util.List<JsonObject> feedNewest(int n)
		{
			return feed.subList(0, Math.min(n, feed.size()));
		}

		JsonObject liveSitting;

		@Override
		java.util.List<JsonObject> feedWithSitting(int n)
		{
			if (liveSitting == null)
			{
				return feedNewest(n);
			}
			java.util.List<JsonObject> out = new java.util.ArrayList<>();
			out.add(liveSitting);
			out.addAll(feedNewest(n));
			return out;
		}

		@Override
		JsonObject clogSnapshot()
		{
			return clog;
		}

		@Override
		int clogFinished()
		{
			return clogFinished;
		}

		@Override
		int clogAvailable()
		{
			return clogAvailable;
		}

		@Override
		TreeMap<LocalDate, HistoryLog.Baseline> historyBaselines()
		{
			return history;
		}

		@Override
		boolean cloudActive()
		{
			return cloud;
		}

		@Override
		void fetchSlayerJourney(
			java.util.function.Consumer<LocalStore.SlayerJourney> onDone)
		{
			onDone.accept(journey);
		}

		@Override
		LocalStore.SlayerJourney slayerJourney()
		{
			return journey;
		}

		@Override
		boolean slayerSeenThisSession()
		{
			return slayer != null;
		}

		@Override
		java.util.Map<String, Long> consumableValues()
		{
			return new LinkedHashMap<>(consumVals);
		}

		@Override
		void fetchGrinds(
			java.util.function.Consumer<java.util.List<GrindBook.GrindRow>> onDone)
		{
			onDone.accept(new ArrayList<>(grinds));
		}

		@Override
		java.util.Map<String, GrindBook.PetChase> petChases(java.util.Collection<String> pets)
		{
			return new GrindBook(new Gson()).petChases(clog, sources, lifetime,
				skillSheet(), store != null ? store.achievements() : achievements, pets);
		}

		@Override
		com.google.gson.JsonObject achievements()
		{
			return store != null ? store.achievements() : new com.google.gson.JsonObject();
		}

		@Override
		java.util.List<LocalStore.PetRow> pets()
		{
			return store != null ? store.pets() : new ArrayList<>(petRows);
		}

		@Override
		java.util.List<LocalStore.BagItem> slayerTaskItems(int index)
		{
			return store != null ? store.slayerTaskItems(index) : new ArrayList<>();
		}

		@Override
		java.util.List<LocalStore.UntakenRow> slayerTaskMonsters(int index)
		{
			return store != null ? store.slayerTaskMonsters(index) : taskMonsters;
		}

		@Override
		java.util.List<LocalStore.BagItem> untakenItemsOf(String source)
		{
			return store != null ? store.untakenItemsOf(source) : untakenBag;
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenSourcesOf(String item)
		{
			return store != null ? store.untakenSourcesOf(item) : new ArrayList<>();
		}

		@Override
		PaceBook.Pace pace(String skill)
		{
			long xp = 0;
			if (!history.isEmpty())
			{
				Long v = history.lastEntry().getValue().skills.get(skill.toLowerCase(Locale.ROOT));
				xp = v != null ? v : 0;
			}
			return PaceBook.forSkill(history, skill.toLowerCase(Locale.ROOT), xp, java.time.LocalDate.now());
		}

		java.util.List<LocalStore.UntakenRow> taskMonsters = new ArrayList<>();
		java.util.List<LocalStore.BagItem> untakenBag = new ArrayList<>();

		@Override
		String journalWarning()
		{
			return journalWarning;
		}

		@Override
		long keptSince()
		{
			long earliest = Long.MAX_VALUE;
			for (LocalStore.SourceRow r : sources)
			{
				if (r.firstMs > 0)
				{
					earliest = Math.min(earliest, r.firstMs);
				}
			}
			for (JsonObject e : feed)
			{
				if (e.has("ts") && e.get("ts").getAsLong() > 0)
				{
					earliest = Math.min(earliest, e.get("ts").getAsLong());
				}
			}
			return earliest == Long.MAX_VALUE ? 0 : earliest;
		}

		@Override
		int combatLevel()
		{
			return 125;
		}

		@Override
		java.util.Map<String, Long> killCounts()
		{
			if (store != null)
			{
				return LocalStore.reconciledKills(store.clogSnapshot(),
					store.dropSources(), store.chatKillCounts(),
					store.anchoredKills());
			}
			return kcs;
		}

		final Map<String, Long> ledgerKcs = new LinkedHashMap<>();

		final Map<String, Long> kcs = new LinkedHashMap<>();

		boolean spritesThrow;
		net.runelite.client.game.SpriteManager spriteManager;
		final java.util.List<Integer> spriteAsks = new java.util.ArrayList<>();

		@Override
		net.runelite.client.game.SpriteManager sprites()
		{
			if (spritesThrow)
			{
				throw new AssertionError();
			}
			return spriteManager;
		}

		net.runelite.client.game.SkillIconManager skillIconManager;

		@Override
		net.runelite.client.game.SkillIconManager skillIcons()
		{
			return skillIconManager;
		}

		@Override
		java.util.Map<String, long[]> skillSheet()
		{
			return store != null ? store.skillSheet() : skills;
		}

		@Override
		com.google.gson.Gson gson()
		{
			return new Gson();
		}

		@Override
		net.runelite.client.game.ItemManager items()
		{
			return itemManager;
		}

		@Override
		void actionPushNow()
		{
		}
	}

	private String firstFoldablePet(StubPlugin stub, String page) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("taxonomy", com.google.gson.Gson.class);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<String, List<String>>> tax =
			(Map<String, Map<String, List<String>>>) m.invoke(null, new Gson());
		List<String> slots = tax.get("Other").get(page);
		for (String slot : slots)
		{
			if (foldablePet(stub, slot))
			{
				return slot.toLowerCase(Locale.ROOT);
			}
		}
		return slots.get(0).toLowerCase(Locale.ROOT);
	}

	private boolean foldablePet(StubPlugin stub, String slot) throws Exception
	{
		LocalStore.PetRow own = null;
		for (LocalStore.PetRow r : stub.pets())
		{
			if (r.name.equalsIgnoreCase(slot))
			{
				own = r;
				break;
			}
		}
		Map<String, GrindBook.PetChase> chases =
			stub.petChases(java.util.Collections.singletonList(slot));
		Method det = ChroniclePanel.class.getDeclaredMethod("petDetail", boolean.class,
			LocalStore.PetRow.class, GrindBook.PetChase.class);
		det.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<javax.swing.JPanel> d = (List<javax.swing.JPanel>) det.invoke(null, false, own,
			chases.get(slot.toLowerCase(Locale.ROOT)));
		return !d.isEmpty();
	}

	private String firstClogPage(ChroniclePanel panel) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("taxonomy", com.google.gson.Gson.class);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<String, List<String>>> tax =
			(Map<String, Map<String, List<String>>>) m.invoke(null, new Gson());
		Map<String, List<String>> bosses = tax.get("Bosses");
		return bosses == null || bosses.isEmpty() ? null : bosses.keySet().iterator().next();
	}

	private void shoot(ChroniclePanel panel, File out, String name, String view)
		throws Exception
	{
		edt(() ->
		{
			setEnum(panel, "view", "chronicle.ChroniclePanel$View", view);
			Object v = get(panel, "view");
			Method tabFor = ChroniclePanel.class.getDeclaredMethod("tabFor",
				Class.forName("chronicle.ChroniclePanel$View"));
			tabFor.setAccessible(true);
			Object owner = tabFor.invoke(panel, v);
			Field tf = ChroniclePanel.class.getDeclaredField("tab");
			tf.setAccessible(true);
			tf.set(panel, owner);
			Method subFor = ChroniclePanel.class.getDeclaredMethod("subFor",
				Class.forName("chronicle.ChroniclePanel$View"));
			subFor.setAccessible(true);
			Field sf = ChroniclePanel.class.getDeclaredField("subByTab");
			sf.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Object, String> subs = (Map<Object, String>) sf.get(panel);
			subs.put(owner, (String) subFor.invoke(panel, v));
			Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
			rebuild.setAccessible(true);
			rebuild.invoke(panel);
		});
		edt(() ->
		{
		});
		edt(() ->
		{
			panel.setSize(PANEL_W, 1200);
			layoutTree(panel);
			int h = Math.min(MAX_H, panel.getPreferredSize().height + 44);
			panel.setSize(PANEL_W, Math.max(h, 300));
			layoutTree(panel);

			BufferedImage img = new BufferedImage(PANEL_W, panel.getHeight(),
				BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
			panel.paint(g);
			g.dispose();
			ImageIO.write(img, "png", new File(out, name + ".png"));
		});
	}

	private static void setSearch(ChroniclePanel panel, String q) throws Exception
	{
		edt(() ->
		{
			Field f = ChroniclePanel.class.getDeclaredField("searchField");
			f.setAccessible(true);
			((net.runelite.client.ui.components.IconTextField) f.get(panel)).setText(q);
		});
	}

	private static void set(ChroniclePanel panel, String field, Object val) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, val);
	}

	private static Object get(ChroniclePanel panel, String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	static void regatherHistory(ChroniclePanel panel) throws Exception
	{
		awaitHistory(panel);
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("gatherHistory");
			m.setAccessible(true);
			m.invoke(panel);
		});
		awaitHistory(panel);
	}

	static void awaitHistory(ChroniclePanel panel) throws Exception
	{
		long deadline = System.currentTimeMillis() + 10_000;
		while (true)
		{
			final boolean[] landed = new boolean[1];
			edt(() -> landed[0] = !(Boolean) get(panel, "historyGathering"));
			if (landed[0])
			{
				return;
			}
			if (System.currentTimeMillis() > deadline)
			{
				throw new AssertionError("the History tab's read never landed");
			}
			Thread.sleep(20);
		}
	}

	@SuppressWarnings("unchecked")
	private static java.util.Set<String> openFolds(ChroniclePanel panel) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		return (java.util.Set<String>) f.get(panel);
	}

	private static void expandSection(ChroniclePanel panel, String key) throws Exception
	{
		openFolds(panel).add(key);
	}

	private static void collapseAll(ChroniclePanel panel) throws Exception
	{
		openFolds(panel).clear();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setEnum(ChroniclePanel panel, String field, String enumClass, String constant)
		throws Exception
	{
		Class<?> cls = Class.forName(enumClass);
		set(panel, field, Enum.valueOf((Class<Enum>) cls, constant));
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				layoutTree(k);
			}
		}
	}
}
