/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.mockito.Mockito;

public class PanelPreviewTest
{
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
		s.untaken = store.untaken().sources;
		s.untakenItems = store.untaken().items;
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
		java.util.List<LocalStore.BagItem> allLoot()
		{
			return store != null ? store.allLoot() : new java.util.ArrayList<>();
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
		LocalStore.Tasks tasks(long fromMs, long toMs, String onlyTask, boolean includeOpen)
		{
			return store != null ? store.tasks(fromMs, toMs, onlyTask, includeOpen)
				: new LocalStore.Tasks(new ArrayList<>());
		}

		@Override
		LocalStore.Tasks allTasks()
		{
			return tasks(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, null, true);
		}

		@Override
		LocalStore.Untaken untaken()
		{
			LocalStore.Untaken u = store != null ? store.untaken() : new LocalStore.Untaken();
			u.sources.clear();
			u.sources.addAll(untaken);
			u.items.clear();
			u.items.addAll(untakenItems);
			return u;
		}

		@Override
		LocalStore.LootWindow loot(java.time.LocalDate from, java.time.LocalDate to)
		{
			LocalStore.LootWindow held = from == null ? sessionWindow : lootWindow;
			LocalStore.LootWindow real = store != null ? store.loot(from, to) : new LocalStore.LootWindow();
			if (store == null && from == null)
			{
				periodBags.forEach((k, v) -> real.bySource.put(k, new ArrayList<>(v)));
			}
			if (held == null)
			{
				return real;
			}
			LocalStore.LootWindow w = new LocalStore.LootWindow();
			w.loots = held.loots;
			w.value = held.value;
			w.left = held.left;
			w.leftValue = held.leftValue;
			w.leftKills = held.leftKills;
			w.items.addAll(held.items);
			w.sources.addAll(held.sources);
			w.leftItems.addAll(held.leftItems);
			w.bySource.putAll(real.bySource);
			w.unfiled.addAll(real.unfiled);
			return w;
		}

		@Override
		LocalStore.LootDays lootDays()
		{
			LocalStore.LootDays real = store != null ? store.lootDays() : null;
			return new LocalStore.LootDays()
			{
				@Override
				long rollFrom()
				{
					if (lootRollDay != null)
					{
						return lootRollDay.atStartOfDay(java.time.ZoneId.systemDefault())
							.toInstant().toEpochMilli();
					}
					return real != null ? real.rollFrom() : 0;
				}

				@Override
				long detailFrom()
				{
					return real != null && lootRollDay == null ? real.detailFrom() : rollFrom();
				}

				@Override
				Map<String, long[]> totals()
				{
					return real != null ? real.totals() : dayTotals;
				}

				@Override
				long[] itemDays(String name)
				{
					long[] said = StubPlugin.this.itemDays.get(name);
					return said != null ? said : real != null ? real.itemDays(name) : new long[4];
				}
			};
		}

		Map<String, long[]> itemDays = new LinkedHashMap<>();
		Map<String, long[]> dayTotals = new java.util.TreeMap<>();

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
		JsonObject liveSessionLine()
		{
			return liveSitting;
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

}
