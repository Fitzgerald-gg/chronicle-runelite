/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Window;
import chronicle.counters.ExperienceStatTracker;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.LocalStore.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class Board
{
	final ChroniclePlugin plugin;
	final Period period;
	private final Runnable onHistory;

	Board(ChroniclePlugin plugin, Period period, Runnable onHistory)
	{
		this.plugin = plugin;
		this.period = period;
		this.onHistory = onHistory;
	}

	void reset()
	{
		buildSources = null;
		buildClog = null;
		buildSpan = null;
		spanAsked = false;
		taskKillsEverCache = null;
		taskItemsEver = null;
		buildAchievements = null;
		milestones = null;
		landedSlots = null;
		records = null;
		daysPlayed = null;
		dayTotals = null;
		kcByKind = null;
		chatKcByKind = null;
		killKinds = null;
		movedTypes = null;
		buildPeriodCounters = null;
		periodCountersAsked = false;
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
		skilled = null;
		ledgerNames = null;
		sourceKinds.clear();
	}

	void forget()
	{
		searchFeed = null;
		searchFeedSpine = null;
		historySpine = null;
		historyFeed = new ArrayList<>();
		historyJourney = null;
		historyDay = null;
		historyFeedTs = 0;
		historyEpoch++;
		historyGathering = false;
	}

	Map<String, Long> kcByKind;

	Map<String, Long> kcByKind()
	{
		if (kcByKind != null)
		{
			return kcByKind;
		}
		Map<String, Long> out = new LinkedHashMap<>(chatKcByKind());
		for (SourceRow r : sources())
		{
			out.merge(kindOf(r.name), (long) r.kc, Math::max);
		}
		kcByKind = out;
		return out;
	}

	long bossKills(String name)
	{
		JsonObject cl = clogNow();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		String kind = kindOf(name);
		Long byKind = kcByKind().get(kind);
		if (byKind != null)
		{
			best = Math.max(best, byKind);
		}
		if (best > 0)
		{
			return best;
		}
		for (Entry<String, Long> ln : pageLines(name, "kc_lines"))
		{
			String said = low(ln.getKey());
			if (said.contains("kill") || said.contains("completion"))
			{
				return ln.getValue();
			}
		}
		String page = LOG_PAGE_FOR.getOrDefault(name, name);
		return Math.max(0, lookup(cl, "kcs", page));
	}

	static long lookup(JsonObject clog, String map, String key)
	{
		if (clog == null)
		{
			return -1;
		}
		JsonElement v = getIgnoreCase(obj(clog, map), key);
		return v == null ? -1 : asLong(v);
	}

	long bossKillsInWindow(String name)
	{
		if (period.whole())
		{
			return bossKills(name);
		}
		if (period.session())
		{
			rollUsed = true;
			return rolled(name);
		}
		Span s = span();
		if (s == null)
		{
			return -1;
		}
		if (movedKcs == null)
		{
			movedKcs = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				closingNow(s.closing.kcs, plugin.killCounts()));
		}
		String key = keyOf(movedKcs.keySet(), name);
		Long moved = key == null ? null : movedKcs.get(key);
		if (moved != null)
		{
			return Math.max(0, moved);
		}
		Long rolled = rolledKills(name);
		if (rolled != null)
		{
			rollUsed = true;
			return rolled;
		}
		return -1;
	}

	long rolled(String name)
	{
		Long n = rolledKills(name);
		return n == null ? 0 : n;
	}

	Long rolledKills(String name)
	{
		if (plugin.lootRollFrom() <= 0)
		{
			return null;
		}
		if (rolledKcs == null)
		{
			rolledKcs = new LinkedHashMap<>();
			for (String[] r : lootWindow().sources)
			{
				long n = safeParse(r[1]);
				if (n > 0)
				{
					rolledKcs.put(kindOf(r[0]), n);
				}
			}
		}
		return rolledKcs.get(kindOf(name));
	}

	LocalDate rollShortOf()
	{
		long from = plugin.lootRollFrom();
		if (from <= 0)
		{
			return null;
		}
		Window w = window();
		LocalDate began = dayOf(from);
		return began.isAfter(w.start) ? began : null;
	}

	List<Entry<String, Long>> logLines(String boss)
	{
		List<Entry<String, Long>> out = pageLines(boss, "kc_lines");
		long kills = bossKills(boss);
		out.removeIf(ln ->
		{
			String said = low(ln.getKey());
			return ln.getValue() == kills
				&& (said.contains("kill") || said.contains("completion"));
		});
		return out;
	}

	List<Entry<String, Long>> pageLines(String boss, String map)
	{
		List<Entry<String, Long>> out = new ArrayList<>();
		JsonObject cl = clogNow();
		if (cl == null)
		{
			return out;
		}
		JsonElement found = getIgnoreCase(obj(cl, map), LOG_PAGE_FOR.getOrDefault(boss, boss));
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Entry<String, JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			long n = asLong(ln.getValue());
			if (n > 0 && lineBelongsTo(boss, ln.getKey()))
			{
				out.add(new AbstractMap.SimpleEntry<>(ln.getKey(), n));
			}
		}
		return out;
	}

	boolean isKillSource(String name)
	{
		return killKinds().contains(kindOf(name))
			|| taskKillsEver().containsKey(name);
	}

	Set<String> killKinds;

	Set<String> killKinds()
	{
		if (killKinds != null)
		{
			return killKinds;
		}
		Set<String> out = new HashSet<>();
		for (Boss b : bossRoster(plugin.gson()))
		{
			out.add(kindOf(b.name));
		}
		JsonObject cl = clogNow();
		if (cl != null)
		{
			for (String said : obj(cl, "slayer_kcs").keySet())
			{
				out.add(kindOf(said));
			}
		}
		killKinds = out;
		return out;
	}

	boolean lineBelongsTo(String boss, String label)
	{
		if (label == null || label.isEmpty())
		{
			return false;
		}
		if (Character.isDigit(label.charAt(label.length() - 1)))
		{
			return false;
		}
		String said = low(label);
		String mine = bare(boss);
		String best = null;
		for (Boss b : bossRoster(plugin.gson()))
		{
			String name = bare(b.name);
			if (!name.isEmpty() && said.contains(name)
				&& (best == null || name.length() > best.length()))
			{
				best = name;
			}
		}
		if (best != null)
		{
			return best.equals(mine);
		}
		String page = LOG_PAGE_FOR.getOrDefault(boss, boss);
		if (!page.equalsIgnoreCase(boss))
		{
			return namesOneOf(said, words(mine, bare(page)));
		}
		for (Boss other : bossRoster(plugin.gson()))
		{
			String onPage = LOG_PAGE_FOR.getOrDefault(other.name, other.name);
			if (other.name.equalsIgnoreCase(boss) || !onPage.equalsIgnoreCase(page))
			{
				continue;
			}
			if (namesOneOf(said, words(bare(other.name), mine)))
			{
				return false;
			}
		}
		return true;
	}

	SourceRow clue(String tier)
	{
		return find(sources(), r -> r.name, "Clue Scroll (" + tier + ")", false);
	}

	String notCounting(boolean kills)
	{
		TreeMap<LocalDate, Baseline> spine = historySpine;
		Window w = window();
		if (period.whole() || period.session() || spine == null || w == null)
		{
			return null;
		}
		for (Entry<LocalDate, Baseline> e : spine.entrySet())
		{
			if (!(kills ? e.getValue().kcs : e.getValue().counters).isEmpty())
			{
				return w.end.isBefore(e.getKey())
					? "The record keeps no " + (kills ? "kill counts" : "counters")
					+ " before " + e.getKey().format(FULL_DAY) + "."
					: null;
			}
		}
		return null;
	}

	Map<String, Long> counters()
	{
		return plugin.lifetimeCounters();
	}

	long[] windowMs()
	{
		if (period.whole())
		{
			return new long[]{Long.MIN_VALUE / 2, Long.MAX_VALUE / 2};
		}
		if (period.session())
		{
			long began = plugin.sessionStart();
			return new long[]{began > 0 ? began
				: startMs(LocalDate.now()),
				System.currentTimeMillis()};
		}
		Window w = window();
		return new long[]{
			startMs(w.start),
			startMs(w.end.plusDays(1)) - 1};
	}

	boolean hasKindOnTask(String kind)
	{
		for (String name : taskItemsEver().keySet())
		{
			String k = ItemKinds.kindOf(name);
			if (UNFILED.equals(kind) ? k == null : kind.equals(k))
			{
				return true;
			}
		}
		return false;
	}

	boolean everOnTask()
	{
		return !taskItemsEver().isEmpty();
	}

	Map<String, long[]> taskItemsEver;

	Map<String, long[]> taskItemsEver()
	{
		if (taskItemsEver == null)
		{
			taskItemsEver = plugin.onTaskItems(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
		}
		return taskItemsEver;
	}

	double[] sourceTimesInWindow(String name)
	{
		Map<String, double[]> times = lootWindow().times;
		String key = keyOf(times.keySet(), name);
		return key == null ? new double[]{0, 0} : times.get(key);
	}

	long[] sourceInWindow(String name)
	{
		return rowOf(lootWindow().sources, name);
	}

	static long[] rowOf(List<String[]> rows, String name)
	{
		String[] r = rowFor(rows, name, false);
		return r == null ? new long[]{0, 0} : new long[]{safeParse(r[1]), safeParse(r[2])};
	}

	static String[] rowFor(List<String[]> rows, String name, boolean exact)
	{
		return find(rows, r -> r[0], name, exact);
	}

	String resolveSource(String name)
	{
		String named = resolveSourceNamed(name);
		if (named != null)
		{
			return named;
		}
		String kind = kindOf(name);
		SourceRow best = null;
		for (SourceRow r : sources())
		{
			if ((kindOf(r.name).equals(kind) || namesInBrackets(r.name, name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : name;
	}

	String resolveSourceNamed(String name)
	{
		if (ledgerNames == null)
		{
			Map<String, String> index = new HashMap<>();
			for (SourceRow r : sources())
			{
				index.putIfAbsent(low(r.name), r.name);
			}
			ledgerNames = index;
		}
		String low = low(name);
		String hit = ledgerNames.get(low);
		if (hit == null && low.endsWith("s"))
		{
			hit = ledgerNames.get(low.substring(0, low.length() - 1));
		}
		return hit;
	}

	Map<String, List<BagItem>> periodItems()
	{
		Window w = window();
		return period.session() ? plugin.itemsBySource(null, null) : plugin.itemsBySource(w.start, w.end);
	}

	long standingKills(SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		Long said = chatKcByKind().get(LocalStore.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	Map<String, Long> chatKcByKind;

	Map<String, Long> chatKcByKind()
	{
		if (chatKcByKind == null)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Entry<String, Long> e : plugin.killCounts().entrySet())
			{
				out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
			}
			chatKcByKind = out;
		}
		return chatKcByKind;
	}

	Map<String, Long> taskKillsEver()
	{
		if (taskKillsEverCache == null)
		{
			taskKillsEverCache = plugin.onTaskKills(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
		}
		return taskKillsEverCache;
	}

	boolean minutesCoverPeriod()
	{
		if (period.whole())
		{
			return false;
		}
		if (period.session())
		{
			return true;
		}
		Span s = span();
		if (s == null)
		{
			return false;
		}
		for (String key : s.opening.counters.keySet())
		{
			if (StatKeys.isTime(key))
			{
				return true;
			}
		}
		return false;
	}

	long minutesAt(String name, Map<String, Long> counters)
	{
		long minutes = counters.getOrDefault(StatKeys.timeKey(name), 0L);
		for (String npc : FOUGHT_AS.getOrDefault(kindOf(name),
			Collections.emptyList()))
		{
			minutes += counters.getOrDefault(StatKeys.timeKey(npc), 0L);
		}
		return minutes;
	}

	Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		for (LocalStore.PetRow r : plugin.pets())
		{
			out.putIfAbsent(low(r.name), r);
		}
		return out;
	}

	int[] clogStanding()
	{
		int avail = plugin.clogAvailable();
		return avail > 0 ? new int[]{plugin.clogFinished(), avail} : null;
	}

	List<SourceRow> sources()
	{
		if (buildSources == null)
		{
			buildSources = plugin.dropSources();
		}
		return buildSources;
	}

	JsonObject clogNow()
	{
		if (buildClog == null)
		{
			buildClog = plugin.clogSnapshot();
		}
		return buildClog;
	}

	Map<String, Long> withLedgerSpend(Map<String, Long> base)
	{
		Map<String, Long> out = new LinkedHashMap<>(base);
		long food = 0;
		long potions = 0;
		for (Entry<String, Long> e : plugin.consumableValues().entrySet())
		{
			long v = e.getValue() == null ? 0 : e.getValue();
			if (v <= 0)
			{
				continue;
			}
			if (e.getKey().endsWith("Eaten"))
			{
				food += v;
			}
			else if (e.getKey().endsWith("Doses"))
			{
				potions += v;
			}
		}
		if (food > 0)
		{
			out.merge("foodConsumedValue", food, Math::max);
		}
		if (potions > 0)
		{
			out.merge("potionsConsumedValue", potions, Math::max);
		}
		if (food + potions > 0)
		{
			out.merge("consumedValue", food + potions, Math::max);
		}
		return out;
	}

	void gatherHistory()
	{
		if (historyGathering)
		{
			return;
		}
		historyGathering = true;
		final int epoch = historyEpoch;
		new SwingWorker<HistoryData, Void>()
		{
			@Override
			protected HistoryData doInBackground()
			{
				return new HistoryData(plugin.historyBaselines(),
					plugin.feedNewest(HISTORY_FEED_SCAN), plugin.slayerJourney(),
					LocalDate.now());
			}

			@Override
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					return;
				}
				historyGathering = false;
				HistoryData d;
				try
				{
					d = get();
				}
				catch (InterruptedException | ExecutionException e)
				{
					return;
				}
				historySpine = d.spine;
				historyFeed = d.feed;
				historyJourney = d.journey;
				historyDay = d.day;
				historyFeedTs = newestTs(d.feed);
				onHistory.run();
			}
		}.execute();
	}

	Map<String, Long> periodWorth(LocalDate from, LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (period.whole())
		{
			for (SourceRow r : sources())
			{
				out.merge(r.name, r.value, Long::sum);
			}
		}
		else
		{
			for (String[] r : plugin.lootBetween(from, to).sources)
			{
				out.merge(r[0], safeParse(r[2]), Long::sum);
			}
		}
		return out;
	}

	static long paidFor(Map<String, Long> worth, Map<String, Long> loose, String name)
	{
		Long exact = worth.get(name);
		return exact != null ? exact : loose.getOrDefault(kindOf(name), 0L);
	}

	static Map<String, Long> loosely(Map<String, Long> worth)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (Entry<String, Long> e : worth.entrySet())
		{
			out.merge(kindOf(e.getKey()), e.getValue(), Long::sum);
		}
		return out;
	}

	Map<String, String> skilledKeys()
	{
		if (skilled == null)
		{
			Map<String, String> found = new LinkedHashMap<>();
			for (String key : counters().keySet())
			{
				for (String[] verb : SKILLED)
				{
					if (key.endsWith(verb[0]) && !key.endsWith("Failed" + verb[0]))
					{
						found.putIfAbsent(
							letters(key.substring(0, key.length() - verb[0].length())), verb[1]);
						break;
					}
				}
			}
			skilled = found;
		}
		return skilled;
	}

	String sourceKind(String name)
	{
		return sourceKinds.computeIfAbsent(name, this::decideKind);
	}

	String decideKind(String name)
	{
		if (PICKPOCKETED.contains(low(name))
			|| skilledKeys().containsKey(letters(name)))
		{
			return KIND_SKILLING;
		}
		for (Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			if (!tab.getValue().containsKey(name))
			{
				continue;
			}
			String t = low(tab.getKey());
			if (t.contains("boss") || t.contains("raid"))
			{
				return KIND_BOSS;
			}
			if (t.contains("clue") || t.contains("minigame"))
			{
				return KIND_ACTIVITY;
			}
			return MONSTER_PAGES.contains(name) ? KIND_MONSTER : KIND_SKILLING;
		}
		String low = low(name);
		return containsAny(low, OPENED) ? KIND_ACTIVITY
			: containsAny(low, GATHERED) ? KIND_SKILLING : KIND_MONSTER;
	}

	Skill skillOf(String name)
	{
		return skill(PAGE_SKILL.get(name));
	}

	static Baseline baselineAt(Map<String, Long> xp)
	{
		Baseline at = new Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	SkillStand skillStand(Baseline closing, boolean live)
	{
		Map<String, long[]> sheet = live ? plugin.skillSheet() : Collections.emptyMap();
		List<Skill> order = skillOrder();
		List<String> keys = new ArrayList<>();
		for (Skill sk : order)
		{
			keys.add(low(sk.name()));
		}
		HistoryLog.Levels closed = HistoryLog.levels(closing, keys);
		Map<Skill, Long> levels =
			new EnumMap<>(Skill.class);
		long total = 0;
		for (Skill sk : order)
		{
			String key = low(sk.name());
			long[] cur = sheet.get(key);
			long level = cur != null && cur[0] > 0 ? cur[0] : closed.of.get(key);
			total += level;
			long shown = period.whole() && cur != null && cur.length > 1 && cur[1] > 0
				? PaceBook.virtualLevelAt(cur[1]) : level;
			levels.put(sk, Math.max(level, shown));
		}
		long[] ov = sheet.get("overall");
		return new SkillStand(order, keys, levels,
			ov != null && ov[0] > 0 ? ov[0] : total, closed);
	}

	long stirred(String type)
	{
		if (movedTypes == null)
		{
			movedTypes = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (insideWindow(asLong(e.get("ts"))))
				{
					movedTypes.merge(typeOf(e), 1L, Long::sum);
				}
			}
		}
		Long n = movedTypes.get(type);
		return n == null ? 0 : n;
	}

	long namedLine(String source, String label)
	{
		for (Entry<String, Long> ln : pageLines(source, "kc_lines"))
		{
			if (ln.getKey().equalsIgnoreCase(label))
			{
				return ln.getValue();
			}
		}
		return 0;
	}

	Map<String, Long> periodCounters()
	{
		if (!periodCountersAsked)
		{
			periodCountersAsked = true;
			buildPeriodCounters = countersForPeriod();
		}
		return buildPeriodCounters == null ? Collections.emptyMap()
			: buildPeriodCounters;
	}

	long[] taskTally()
	{
		long[] ms = windowMs();
		long[] tally = Arrays.copyOf(plugin.onTaskTally(ms[0], ms[1], null, period.whole()), 4);
		tally[3] = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, period.whole()))[1];
		return tally;
	}

	JsonObject achievements()
	{
		if (buildAchievements == null)
		{
			buildAchievements = plugin.achievements();
		}
		return buildAchievements;
	}

	long[] diaryStanding()
	{
		JsonObject d = obj(achievements(), "diaries");
		long done = 0;
		long all = 0;
		long whole = 0;
		for (String region : d.keySet())
		{
			JsonObject tiers = d.getAsJsonObject(region);
			long here = 0;
			for (String tier : tiers.keySet())
			{
				all++;
				if (tiers.get(tier).getAsBoolean())
				{
					done++;
					here++;
				}
			}
			if (here > 0 && here == tiers.size())
			{
				whole++;
			}
		}
		return new long[]{done, all, whole, d.size()};
	}

	long[] combatStanding()
	{
		JsonObject c = obj(achievements(), "combat");
		long points = c.has("points") ? c.get("points").getAsLong() : 0;
		long tiers = 0;
		JsonObject t = obj(c, "tiers");
		for (String k : t.keySet())
		{
			if (t.get(k).getAsLong() > 0)
			{
				tiers++;
			}
		}
		long possible = 0;
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			JsonObject data = obj(e, "data");
			if (possible == 0 && "COMBAT_ACHIEVEMENT".equals(typeOf(e)) && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
			}
		}
		if (possible == 0)
		{
			possible = CA_POINTS;
		}
		return new long[]{points, possible, tiers};
	}

	Set<Integer> caDone()
	{
		Set<Integer> out = new HashSet<>();
		JsonObject c = obj(achievements(), "combat");
		if (!c.has("tasksDone") || !c.get("tasksDone").isJsonArray())
		{
			return out;
		}
		for (JsonElement e : c.getAsJsonArray("tasksDone"))
		{
			try
			{
				out.add(e.getAsInt());
			}
			catch (RuntimeException ignored)
			{
			}
		}
		return out;
	}

	LocalStore.LootWindow lootWindow()
	{
		Window w = window();
		return period.session() ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}

	Window window()
	{
		return period.window(plugin.sessionStart(),
			historySpine == null || historySpine.isEmpty() ? null : historySpine.firstKey());
	}

	Span span()
	{
		if (spanAsked)
		{
			return buildSpan;
		}
		spanAsked = true;
		buildSpan = foldSpan();
		return buildSpan;
	}

	boolean closesOnTheClient(Entry<LocalDate, Baseline> from,
		LocalDate start, LocalDate end)
	{
		if (from == null || end.isBefore(LocalDate.now()))
		{
			return false;
		}
		return period.session() || !from.getKey().isBefore(start.minusDays(1));
	}

	Span foldSpan()
	{
		if (historySpine == null)
		{
			gatherHistory();
			return null;
		}
		if (historySpine.isEmpty())
		{
			return null;
		}
		Window w = window();
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(historySpine, w.start, w.end);
		Entry<LocalDate, Baseline> at =
			historySpine.floorEntry(w.end);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !closesOnTheClient(from, w.start, w.end)))
		{
			return null;
		}
		return new Span(HistoryLog.stateAt(historySpine, from.getKey()),
			HistoryLog.earliest(historySpine, at.getKey()),
			HistoryLog.stateAt(historySpine, at.getKey()));
	}

	Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !periodReachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new HashMap<>(closing);
		for (Entry<String, Long> e : live.entrySet())
		{
			if (e.getValue() != null)
			{
				out.merge(e.getKey(), e.getValue(), Math::max);
			}
		}
		return out;
	}

	boolean periodReachesToday()
	{
		return !window().end.isBefore(LocalDate.now());
	}

	Map<String, Long> countersForPeriod()
	{
		if (period.whole())
		{
			return withLedgerSpend(counters());
		}
		if (period.session())
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Entry<String, Integer> e : plugin.sessionView().entrySet())
			{
				if (e.getValue() != null && e.getValue() != 0)
				{
					out.put(e.getKey(), e.getValue().longValue());
				}
			}
			return out;
		}
		Span s = span();
		if (s == null)
		{
			return null;
		}
		return peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, closingNow(s.closing.counters, counters())), s);
	}

	String inside(String said)
	{
		return said + " inside " + periodInSentence() + ".";
	}

	String periodInSentence()
	{
		String label = window().label;
		return label.startsWith("This ")
			? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	static Map<String, Long> peaksNotDeltas(Map<String, Long> moved, Span s)
	{
		for (String key : StatRegistry.peakKeys())
		{
			if (!moved.containsKey(key))
			{
				continue;
			}
			long opened = s.opening.counters == null ? 0
				: s.opening.counters.getOrDefault(key, 0L);
			long shut = s.closing.counters == null ? 0
				: s.closing.counters.getOrDefault(key, 0L);
			if (shut > opened)
			{
				moved.put(key, shut);
			}
			else
			{
				moved.remove(key);
			}
		}
		return moved;
	}

	boolean insideWindow(long ts)
	{
		if (period.whole())
		{
			return true;
		}
		if (ts <= 0)
		{
			return !period.session();
		}
		long[] ms = windowMs();
		return ts >= ms[0] && ts <= ms[1];
	}

	List<SourceRow> skillGround(String craft)
	{
		Set<String> ownTile = new HashSet<>();
		for (String[] a : ACTIVITIES)
		{
			if (!a[1].isEmpty())
			{
				ownTile.add(low(a[1]));
			}
		}
		List<SourceRow> out = new ArrayList<>();
		for (SourceRow r : sources())
		{
			if (ownTile.contains(low(r.name)))
			{
				continue;
			}
			Skill sk = skillOf(r.name);
			if (sk != null && sk.name().equalsIgnoreCase(craft) && r.value > 0)
			{
				out.add(r);
			}
		}
		out.sort((x, y) -> Long.compare(y.value, x.value));
		return out;
	}

	Long liveXp(String key)
	{
		long[] cur = plugin.skillSheet().get(key);
		return cur != null && cur.length > 1 && cur[1] > 0 ? cur[1] : null;
	}

	long sessionXp(String key)
	{
		for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
		{
			if (g.skill != null && g.xp > 0
				&& key.equalsIgnoreCase(g.skill.name()))
			{
				return g.xp;
			}
		}
		return 0;
	}

	List<JsonObject> milestones;

	List<JsonObject> milestones()
	{
		if (milestones == null)
		{
			milestones = new ArrayList<>();
			TreeMap<LocalDate, Baseline> spine = historySpine;
			if (spine != null && spine.size() > 1)
			{
				Map<String, Long> prev = null;
				for (Entry<LocalDate, Baseline> day : spine.entrySet())
				{
					Map<String, Long> now = standings(day.getValue(), SKILL_KEYS);
					if (prev != null)
					{
						long ts = noon(day.getKey());
						crossings(prev, now, ts, milestones);
					}
					Map<String, Long> carried = prev == null
						? new LinkedHashMap<>() : new LinkedHashMap<>(prev);
					carried.putAll(now);
					prev = carried;
				}
				Collections.reverse(milestones);
			}
		}
		return milestones;
	}

	List<JsonObject> withMilestones(List<JsonObject> feed)
	{
		List<JsonObject> marks = milestones();
		if (marks.isEmpty())
		{
			return feed;
		}
		List<JsonObject> out = new ArrayList<>(feed.size() + marks.size());
		int m = 0;
		boolean liveHead = !feed.isEmpty() && feed.get(0).has("live");
		for (int i = 0; i < feed.size(); i++)
		{
			long ts = asLong(feed.get(i).get("ts"));
			while ((i > 0 || !liveHead) && m < marks.size()
				&& asLong(marks.get(m).get("ts")) > ts)
			{
				out.add(marks.get(m++));
			}
			out.add(feed.get(i));
		}
		while (m < marks.size())
		{
			out.add(marks.get(m++));
		}
		return out;
	}

	Map<String, Long> landedSlots;

	Map<String, Long> landedSlots()
	{
		if (landedSlots == null)
		{
			landedSlots = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (!"COLLECTION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "itemName"))
				{
					landedSlots.put(low(d.get("itemName").getAsString()),
						asLong(e.get("ts")));
				}
			}
		}
		return landedSlots;
	}

	Map<String, JsonObject> records;

	Map<String, JsonObject> records()
	{
		if (records == null)
		{
			records = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (!"RECORD".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "source"))
				{
					records.putIfAbsent(low(d.get("source").getAsString()), e);
				}
			}
		}
		return records;
	}

	Map<LocalDate, long[]> daysPlayed;

	Map<LocalDate, long[]> daysPlayed()
	{
		if (daysPlayed == null)
		{
			daysPlayed = new LinkedHashMap<>();
			daySkills = new LinkedHashMap<>();
			crossedDays = new HashSet<>();
			for (JsonObject e : plugin.feedWithSitting(FEED_SCAN_DEEP))
			{
				if (!"SESSION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				LocalDate day = dayOf(sittingStart(e));
				long ended = asLong(e.get("ts"));
				if (ended > 0)
				{
					LocalDate last = dayOf(ended);
					for (LocalDate on = day; last.isAfter(day) && !on.isAfter(last); on = on.plusDays(1))
					{
						crossedDays.add(on);
					}
				}
				long[] t = daysPlayed.computeIfAbsent(day, k -> new long[8]);
				t[0] += sessionMinutes(e);
				t[1]++;
				if (d.has("xp"))
				{
					t[2] += asLong(d.get("xp"));
					t[3]++;
				}
				if (d.has("drops"))
				{
					t[4] += asLong(d.get("drops"));
					t[5] += asLong(d.get("dropsGp"));
					t[6]++;
				}
				if (d.has("xp") && asLong(d.get("xp")) == 0 && !d.has("skills"))
				{
					t[7]++;
				}
				if (d.has("skills") && d.get("skills").isJsonObject())
				{
					t[7]++;
					Map<String, Long> by = daySkills.computeIfAbsent(day, k -> new LinkedHashMap<>());
					for (Entry<String, JsonElement> sk : d.getAsJsonObject("skills").entrySet())
					{
						by.merge(sk.getKey(), asLong(sk.getValue()), Long::sum);
					}
				}
			}
		}
		return daysPlayed;
	}

	Map<String, long[]> dayTotals;

	Map<String, long[]> dayTotals()
	{
		if (dayTotals == null)
		{
			dayTotals = plugin.dayTotals();
		}
		return dayTotals;
	}

	Object[] dayXp(LocalDate day)
	{
		TreeMap<LocalDate, Baseline> spine = historySpine;
		if (spine == null)
		{
			return null;
		}
		Baseline at = spine.get(day);
		Entry<LocalDate, Baseline> before = spine.lowerEntry(day);
		if (at == null || before == null || !before.getKey().plusDays(1).equals(day))
		{
			return null;
		}
		long total = 0;
		long most = 0;
		String top = null;
		for (Entry<String, Long> e : at.skills.entrySet())
		{
			Long was = before.getValue().skills.get(e.getKey());
			if ("overall".equals(e.getKey()) || was == null)
			{
				continue;
			}
			long gained = e.getValue() - was;
			if (gained <= 0)
			{
				continue;
			}
			total += gained;
			if (gained > most)
			{
				most = gained;
				top = e.getKey();
			}
		}
		return total > 0 ? new Object[]{total, prettify(top)} : null;
	}

	String dayEntry(LocalDate day)
	{
		List<String> clauses = new ArrayList<>();
		long[] sat = daysPlayed().get(day);
		if (sat != null && sat[1] > 0)
		{
			clauses.add(count(sat[1], "sitting")
				+ (sat[0] > 0 ? " · " + hoursMinutes(sat[0]) : ""));
		}
		boolean crossed = crossedDays != null && crossedDays.contains(day);
		boolean saysXp = crossed && sat != null && sat[1] > 0 && sat[3] == sat[1];
		Object[] xp = saysXp ? null : dayXp(day);
		if (saysXp && sat[2] > 0)
		{
			Map<String, Long> by = sat[7] == sat[1] && daySkills != null ? daySkills.get(day) : null;
			String most = by == null ? null : topOf(by);
			if (most != null)
			{
				most = prettify(most);
			}
			else
			{
				Object[] spine = dayXp(day);
				most = spine != null ? (String) spine[1] : null;
			}
			clauses.add("+" + gp(sat[2]) + " xp" + (most != null ? ", most in " + most : ""));
		}
		else if (xp != null)
		{
			clauses.add("+" + gp((Long) xp[0]) + " xp, most in " + xp[1]);
		}
		long[] loot = crossed && sat != null && sat[1] > 0 && sat[6] == sat[1]
			? new long[]{sat[4], sat[5]} : dayTotals().get(ROLL_DAY.format(day));
		if (loot != null && loot[0] > 0)
		{
			clauses.add(count(loot[0], "drop")
				+ tail(loot[1]));
		}
		return clauses.isEmpty() ? null : String.join(" · ", clauses);
	}

	long[] sittingsInWindow(LocalDate[] busiest)
	{
		long[] sat = new long[3];
		if (period.session())
		{
			sat[0] = plugin.sessionElapsedMinutes();
			sat[1] = sat[0] > 0 ? 1 : 0;
		}
		else
		{
			for (Entry<LocalDate, long[]> d : daysPlayed().entrySet())
			{
				if (!insideWindow(noon(d.getKey())))
				{
					continue;
				}
				sat[0] += d.getValue()[0];
				sat[1] += d.getValue()[1];
				if (d.getValue()[0] > sat[2])
				{
					sat[2] = d.getValue()[0];
					busiest[0] = d.getKey();
				}
			}
		}
		return sat;
	}

	long[] periodXp()
	{
		if (period.whole())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall.length > 1 ? new long[]{overall[1]} : null;
		}
		if (period.session())
		{
			long xp = 0;
			for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
			{
				xp += Math.max(0, g.xp);
			}
			return new long[]{xp};
		}
		Map<String, Long> gains = periodSkillGains();
		if (gains == null)
		{
			return null;
		}
		long total = 0;
		for (long g : gains.values())
		{
			total += Math.max(0, g);
		}
		return new long[]{total};
	}

	String periodXpMost()
	{
		Map<String, Long> by = new LinkedHashMap<>();
		if (period.session())
		{
			ExperienceStatTracker.SkillGain top = most(plugin.sessionSkillXp(), g -> g.xp);
			return top == null ? null : prettify(low(top.skill.name()));
		}
		if (period.whole())
		{
			for (Entry<String, long[]> e : plugin.skillSheet().entrySet())
			{
				if (!"overall".equals(e.getKey()) && e.getValue().length > 1)
				{
					by.put(e.getKey(), e.getValue()[1]);
				}
			}
		}
		else
		{
			Map<String, Long> gains = periodSkillGains();
			if (gains == null)
			{
				return null;
			}
			by.putAll(gains);
		}
		String top = topOf(by);
		return top == null ? null : prettify(top);
	}

	long[] periodLevels()
	{
		if (period.whole())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall[0] > 0 ? new long[]{overall[0], overall[0]} : null;
		}
		if (period.session())
		{
			long[] overall = plugin.skillSheet().get("overall");
			long gained = stirred("LEVEL");
			return gained > 0 && overall != null ? new long[]{gained, overall[0]} : null;
		}
		Span s = span();
		if (s == null || !s.opening.complete || !s.closing.complete)
		{
			return null;
		}
		Baseline shut = new Baseline();
		shut.skills.putAll(closingSkills(s.closing.skills, periodReachesToday()));
		shut.complete = true;
		HistoryLog.Levels was = HistoryLog.levels(s.opening, SKILL_KEYS);
		HistoryLog.Levels now = HistoryLog.levels(shut, SKILL_KEYS);
		return now.total > was.total ? new long[]{now.total - was.total, now.total} : null;
	}

	Map<String, Long> periodSkillGains()
	{
		Span s = span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> out = new LinkedHashMap<>(HistoryLog.gained(s.opening.skills,
			s.earliest.skills, closingSkills(s.closing.skills, periodReachesToday()), s.opening.complete));
		out.remove("overall");
		return out;
	}

	Map<String, Long> closingSkills(Map<String, Long> skills, boolean live)
	{
		Map<String, Long> close = new HashMap<>(skills);
		if (live)
		{
			for (Entry<String, long[]> e : plugin.skillSheet().entrySet())
			{
				if (e.getValue() != null && e.getValue().length > 1 && e.getValue()[1] > 0)
				{
					close.merge(e.getKey(), e.getValue()[1], Math::max);
				}
			}
		}
		return close;
	}

	long[] periodLoot()
	{
		if (period.whole())
		{
			long[] out = new long[4];
			for (SourceRow r : sources())
			{
				out[0] += r.loots;
				out[1] += r.value;
			}
			for (UntakenRow u : plugin.untakenSources())
			{
				out[2] += u.qty;
				out[3] += u.value;
			}
			return out;
		}
		LocalStore.LootWindow win = lootWindow();
		return new long[]{win.loots, win.value, win.left, win.leftValue};
	}

	String[] periodDearest()
	{
		if (period.whole())
		{
			return null;
		}
		LocalStore.LootWindow win = lootWindow();
		return win.items.isEmpty() ? null : new String[]{win.items.get(0)[0], win.items.get(0)[2]};
	}

	String[] periodKilledMost()
	{
		if (period.whole())
		{
			SourceRow top = null;
			for (SourceRow r : sources())
			{
				if (isKillSource(r.name) && (top == null || standingKills(r) > standingKills(top)))
				{
					top = r;
				}
			}
			return top == null ? null : new String[]{top.name, fmt(standingKills(top))};
		}
		if (period.session())
		{
			String[] top = most(plugin.sessionLootWindow().sources, r -> safeParse(r[1]));
			return top == null ? null : new String[]{top[0], fmt(safeParse(top[1]))};
		}
		Span s = span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> moved = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			closingNow(s.closing.kcs, plugin.killCounts()));
		String top = topOf(moved);
		return top == null ? null : new String[]{top, fmt(moved.get(top))};
	}

	boolean recapMonster(String name)
	{
		if (!KIND_MONSTER.equals(sourceKind(name)))
		{
			return false;
		}
		String kind = kindOf(name);
		for (Boss b : bossRoster(plugin.gson()))
		{
			if (kindOf(b.name).equals(kind) || namesInBrackets(name, b.name)
				|| paysOutThrough(b.name, name))
			{
				return false;
			}
		}
		for (List<String> npcs : FOUGHT_AS.values())
		{
			for (String npc : npcs)
			{
				if (kindOf(npc).equals(kind))
				{
					return false;
				}
			}
		}
		return true;
	}

	List<Object[]> searchFeed;

	List<Object[]> searchFeed()
	{
		long newest = newestTs(plugin.feedNewest(1));
		if (searchFeed == null || newest != searchFeedTs || historySpine != searchFeedSpine)
		{
			List<Object[]> out = new ArrayList<>();
			List<JsonObject> all = new ArrayList<>(plugin.feedNewest(JOURNAL_DEEP));
			all.addAll(milestones());
			for (JsonObject e : all)
			{
				String line = feedLine(e);
				if (line != null && !line.isEmpty())
				{
					out.add(new Object[]{low(line).replace("'", ""), line, filedAt(e)});
				}
			}
			searchFeed = out;
			searchFeedTs = newest;
			searchFeedSpine = historySpine;
		}
		return searchFeed;
	}

	long[] tallyOf(List<BagItem> bag)
	{
		long q = 0;
		long v = 0;
		for (BagItem b : bag)
		{
			q += b.qty;
			v += b.value;
		}
		return new long[]{q, v};
	}

	List<Kind> kindsOf(List<BagItem> bag)
	{
		Map<String, Kind> by = new LinkedHashMap<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			Kind row = by.computeIfAbsent(k == null ? UNFILED : k, Kind::new);
			row.qty += b.qty;
			row.value += b.value;
			row.distinct++;
		}
		List<Kind> out = new ArrayList<>(by.values());
		out.sort(Comparator.comparingLong((Kind k) -> k.value).reversed());
		return out;
	}

	static final class Kind
	{
		final String name;
		long qty;
		long value;
		int distinct;

		Kind(String name)
		{
			this.name = name;
		}
	}

	static final String UNFILED = "Everything else";

	String bossLootSource(Boss b)
	{
		String kind = kindOf(b.name);
		for (SourceRow r : sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				return r.name;
			}
		}
		SourceRow best = null;
		for (SourceRow r : sources())
		{
			if ((namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : b.name;
	}

	static Obtained obtained(JsonObject cl)
	{
		Obtained o = new Obtained();
		for (Entry<String, JsonElement> e
			: obj(cl, "clog_items").entrySet())
		{
			o.all.merge(low(e.getKey()), asLong(e.getValue()), Math::max);
		}
		for (Entry<String, JsonElement> pg
			: obj(cl, "by_cat").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			Map<String, Long> items = new LinkedHashMap<>();
			for (Entry<String, JsonElement> it
				: pg.getValue().getAsJsonObject().entrySet())
			{
				items.merge(low(it.getKey()),
					asLong(it.getValue()), Math::max);
			}
			o.byPage.put(low(pg.getKey()), items);
		}
		return o;
	}

	static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems,
		Map<String, Long> owned, Set<String> sharedNames)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new LinkedHashMap<>();
		for (String slot : slots)
		{
			dupes.merge(low(slot), 1, Integer::sum);
		}
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = low(slots.get(i));
			long onPage = pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L;
			boolean globalMaySpeak = pageItems == null || !sharedNames.contains(key);
			long global = globalMaySpeak ? owned.getOrDefault(key, 0L) : 0L;
			long have = Math.max(onPage, global);
			if (dupes.get(key) > 1)
			{
				int idx = seen.merge(key, 1, Integer::sum) - 1;
				lit[i] = idx < have;
			}
			else
			{
				lit[i] = have > 0
					|| (pageItems != null && pageItems.containsKey(key))
					|| (globalMaySpeak && owned.containsKey(key));
			}
		}
		return lit;
	}

	static Map<String, Long> pageCounts(JsonObject cl)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (cl == null)
		{
			return out;
		}
		Set<String> lined = new HashSet<>();
		for (String pageName : obj(cl, "kc_lines").keySet())
		{
			lined.add(low(pageName));
		}
		for (Entry<String, JsonElement> e
			: obj(cl, "kcs").entrySet())
		{
			String key = low(e.getKey());
			if (!lined.contains(key))
			{
				out.merge(key, asLong(e.getValue()), Math::max);
			}
		}
		for (Entry<String, Long> e : LocalStore.pageKillLines(cl).entrySet())
		{
			out.put(low(e.getKey()), e.getValue());
		}
		return out;
	}

	static String pageHeaderTip(JsonObject cl, String page)
	{
		if (cl == null)
		{
			return null;
		}
		JsonElement found = getIgnoreCase(obj(cl, "kc_lines"), page);
		if (found == null || !found.isJsonObject() || found.getAsJsonObject().size() == 0)
		{
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (Entry<String, JsonElement> ln : found.getAsJsonObject().entrySet())
		{
			lines.add(ln.getKey());
			lines.add(fmt(asLong(ln.getValue())));
		}
		return tip(page, lines);
	}

	String tabStanding(JsonObject cl, String tab)
	{
		if (cl == null)
		{
			return null;
		}
		JsonObject counts = obj(cl, "cat_counts");
		String key = low(tab);
		long total = counts.has(key + "_total") ? asLong(counts.get(key + "_total")) : 0;
		if (total <= 0)
		{
			return null;
		}
		long got = counts.has(key + "_obtained")
			? asLong(counts.get(key + "_obtained")) : 0;
		return tip(tab,
			"Obtained", fmt(got),
			"Available", fmt(total),
			"Share", share(got, total));
	}

	static boolean journeyMoved(SlayerJourney was,
		SlayerJourney now)
	{
		if (was == null)
		{
			return true;
		}
		if (was.completedTasks != now.completedTasks
			|| was.totalKills != now.totalKills
			|| was.tasks.size() != now.tasks.size())
		{
			return true;
		}
		return !was.tasks.isEmpty()
			&& was.tasks.get(0).kills != now.tasks.get(0).kills;
	}

	Map<String, Long> movedKcs;

	Map<String, Long> rolledKcs;

	boolean rollUsed;

	Map<String, String> ledgerNames;

	Map<String, Long> taskKillsEverCache;

	List<SourceRow> buildSources;

	JsonObject buildClog;

	Span buildSpan;

	boolean spanAsked;

	TreeMap<LocalDate, Baseline> historySpine;

	List<JsonObject> historyFeed = new ArrayList<>();

	SlayerJourney historyJourney;

	LocalDate historyDay;

	long historyFeedTs;

	boolean historyGathering;

	int historyEpoch;

	@RequiredArgsConstructor
	static final class HistoryData
	{
		final TreeMap<LocalDate, Baseline> spine;
		final List<JsonObject> feed;
		final SlayerJourney journey;
		final LocalDate day;
	}

	static final int HISTORY_FEED_SCAN = 2000;

	static final int FEED_SCAN_DEEP = 4000;

	Map<String, String> skilled;

	final Map<String, String> sourceKinds = new LinkedHashMap<>();

	@RequiredArgsConstructor
	static final class SkillStand
	{
		final List<Skill> order;
		final List<String> keys;
		final Map<Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;
	}

	Map<String, Long> movedTypes;

	Map<String, Long> buildPeriodCounters;

	boolean periodCountersAsked;

	JsonObject buildAchievements;

	Map<LocalDate, Map<String, Long>> daySkills;

	Set<LocalDate> crossedDays;

	@RequiredArgsConstructor
	static final class Span
	{
		final Baseline opening;
		final Baseline earliest;
		final Baseline closing;
	}

	long searchFeedTs = -1;

	Object searchFeedSpine;

	static final int JOURNAL_DEEP = 20_000;

	static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

	JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}
}
