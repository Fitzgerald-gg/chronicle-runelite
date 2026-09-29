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
import chronicle.counters.ExperienceStatTracker.SkillGain;
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
import java.util.function.Supplier;
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
	static final int FEED_SCAN_DEEP = 4000;
	static final int JOURNAL_DEEP = 20_000;
	static final String UNFILED = "Everything else";
	static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";
	private static final int HISTORY_FEED_SCAN = 2000;
	private static final long EVER_FROM = Long.MIN_VALUE / 2;
	private static final long EVER_TO = Long.MAX_VALUE / 2;

	final ChroniclePlugin plugin;
	final Period period;
	private final Runnable onHistory;
	private final Map<String, Object> memo = new HashMap<>();
	boolean rollUsed;

	TreeMap<LocalDate, Baseline> historySpine;
	List<JsonObject> historyFeed = new ArrayList<>();
	SlayerJourney historyJourney;
	LocalDate historyDay;
	long historyFeedTs;
	boolean historyGathering;
	private int historyEpoch;
	private List<Object[]> searchFeed;
	private long searchFeedTs = -1;
	private Object searchFeedSpine;

	@RequiredArgsConstructor
	static final class Span
	{
		final Baseline opening;
		final Baseline earliest;
		final Baseline closing;
	}

	@RequiredArgsConstructor
	static final class SkillStand
	{
		final List<Skill> order;
		final List<String> keys;
		final Map<Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;
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

	static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	private static final class Days
	{
		final Map<LocalDate, long[]> played = new LinkedHashMap<>();
		final Map<LocalDate, Map<String, Long>> skills = new LinkedHashMap<>();
		final Set<LocalDate> crossed = new HashSet<>();
	}

	Board(ChroniclePlugin plugin, Period period, Runnable onHistory)
	{
		this.plugin = plugin;
		this.period = period;
		this.onHistory = onHistory;
	}

	void reset()
	{
		memo.clear();
		rollUsed = false;
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

	@SuppressWarnings("unchecked")
	private <T> T memo(String key, Supplier<T> make)
	{
		if (!memo.containsKey(key))
		{
			memo.put(key, make.get());
		}
		return (T) memo.get(key);
	}

	void gatherHistory()
	{
		if (historyGathering)
		{
			return;
		}
		historyGathering = true;
		final int epoch = historyEpoch;
		new SwingWorker<Object[], Void>()
		{
			@Override
			protected Object[] doInBackground()
			{
				return new Object[]{plugin.historyBaselines(), plugin.feedNewest(HISTORY_FEED_SCAN),
					plugin.slayerJourney(), LocalDate.now()};
			}

			@Override
			@SuppressWarnings("unchecked")
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					return;
				}
				historyGathering = false;
				Object[] d;
				try
				{
					d = get();
				}
				catch (Exception e)
				{
					return;
				}
				historySpine = (TreeMap<LocalDate, Baseline>) d[0];
				historyFeed = (List<JsonObject>) d[1];
				historyJourney = (SlayerJourney) d[2];
				historyDay = (LocalDate) d[3];
				historyFeedTs = newestTs(historyFeed);
				onHistory.run();
			}
		}.execute();
	}

	Window window()
	{
		return period.window(plugin.sessionStart(),
			historySpine == null || historySpine.isEmpty() ? null : historySpine.firstKey());
	}

	long[] windowMs()
	{
		if (period.whole())
		{
			return new long[]{EVER_FROM, EVER_TO};
		}
		if (period.session())
		{
			long began = plugin.sessionStart();
			return new long[]{began > 0 ? began : startMs(LocalDate.now()), System.currentTimeMillis()};
		}
		Window w = window();
		return new long[]{startMs(w.start), startMs(w.end.plusDays(1)) - 1};
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

	String inside(String said)
	{
		return said + " inside " + periodInSentence() + ".";
	}

	String periodInSentence()
	{
		String label = window().label;
		return label.startsWith("This ") ? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	boolean periodReachesToday()
	{
		return !window().end.isBefore(LocalDate.now());
	}

	boolean closesOnTheClient(Entry<LocalDate, Baseline> from, LocalDate start, LocalDate end)
	{
		return from != null && !end.isBefore(LocalDate.now())
			&& (period.session() || !from.getKey().isBefore(start.minusDays(1)));
	}

	Span span()
	{
		return memo("span", () ->
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
			Entry<LocalDate, Baseline> from = HistoryLog.windowStart(historySpine, w.start, w.end);
			Entry<LocalDate, Baseline> at = historySpine.floorEntry(w.end);
			if (at == null || from == null
				|| at.getKey().equals(from.getKey()) && !closesOnTheClient(from, w.start, w.end))
			{
				return null;
			}
			return new Span(HistoryLog.stateAt(historySpine, from.getKey()),
				HistoryLog.earliest(historySpine, at.getKey()),
				HistoryLog.stateAt(historySpine, at.getKey()));
		});
	}

	JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	String notCounting(boolean kills)
	{
		if (period.whole() || period.session() || historySpine == null)
		{
			return null;
		}
		for (Entry<LocalDate, Baseline> e : historySpine.entrySet())
		{
			if (!(kills ? e.getValue().kcs : e.getValue().counters).isEmpty())
			{
				return window().end.isBefore(e.getKey())
					? "The record keeps no " + (kills ? "kill counts" : "counters")
						+ " before " + e.getKey().format(FULL_DAY) + "."
					: null;
			}
		}
		return null;
	}

	Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !periodReachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new HashMap<>(closing);
		live.forEach((k, v) ->
		{
			if (v != null)
			{
				out.merge(k, v, Math::max);
			}
		});
		return out;
	}

	Map<String, Long> closingSkills(Map<String, Long> skills, boolean live)
	{
		Map<String, Long> close = new HashMap<>(skills);
		if (live)
		{
			plugin.skillSheet().forEach((k, v) ->
			{
				if (v != null && v.length > 1 && v[1] > 0)
				{
					close.merge(k, v[1], Math::max);
				}
			});
		}
		return close;
	}

	Map<String, Long> counters()
	{
		return plugin.lifetimeCounters();
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
			plugin.sessionView().forEach((k, v) ->
			{
				if (v != null && v != 0)
				{
					out.put(k, v.longValue());
				}
			});
			return out;
		}
		Span s = span();
		return s == null ? null : peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, closingNow(s.closing.counters, counters())), s);
	}

	Map<String, Long> periodCounters()
	{
		Map<String, Long> c = memo("periodCounters", this::countersForPeriod);
		return c == null ? Collections.emptyMap() : c;
	}

	static Map<String, Long> peaksNotDeltas(Map<String, Long> moved, Span s)
	{
		for (String key : StatRegistry.peakKeys())
		{
			if (!moved.containsKey(key))
			{
				continue;
			}
			long opened = s.opening.counters.getOrDefault(key, 0L);
			long shut = s.closing.counters.getOrDefault(key, 0L);
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

	Map<String, Long> withLedgerSpend(Map<String, Long> base)
	{
		Map<String, Long> out = new LinkedHashMap<>(base);
		long food = 0;
		long potions = 0;
		for (Entry<String, Long> e : plugin.consumableValues().entrySet())
		{
			long v = e.getValue() == null ? 0 : e.getValue();
			if (v > 0 && e.getKey().endsWith("Eaten"))
			{
				food += v;
			}
			else if (v > 0 && e.getKey().endsWith("Doses"))
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
		return s != null && s.opening.counters.keySet().stream().anyMatch(StatKeys::isTime);
	}

	long minutesAt(String name, Map<String, Long> counters)
	{
		long minutes = counters.getOrDefault(StatKeys.timeKey(name), 0L);
		for (String npc : FOUGHT_AS.getOrDefault(kindOf(name), List.of()))
		{
			minutes += counters.getOrDefault(StatKeys.timeKey(npc), 0L);
		}
		return minutes;
	}

	List<SourceRow> sources()
	{
		return memo("sources", plugin::dropSources);
	}

	JsonObject clogNow()
	{
		return memo("clog", plugin::clogSnapshot);
	}

	JsonObject achievements()
	{
		return memo("achievements", plugin::achievements);
	}

	Map<String, long[]> dayTotals()
	{
		return memo("dayTotals", plugin::dayTotals);
	}

	LocalStore.LootWindow lootWindow()
	{
		Window w = window();
		return period.session() ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}

	Map<String, List<BagItem>> periodItems()
	{
		Window w = window();
		return period.session() ? plugin.itemsBySource(null, null) : plugin.itemsBySource(w.start, w.end);
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

	Map<String, long[]> taskItemsEver()
	{
		return memo("taskItems", () -> plugin.onTaskItems(EVER_FROM, EVER_TO));
	}

	Map<String, Long> taskKillsEver()
	{
		return memo("taskKills", () -> plugin.onTaskKills(EVER_FROM, EVER_TO));
	}

	boolean everOnTask()
	{
		return !taskItemsEver().isEmpty();
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

	long[] taskTally()
	{
		long[] ms = windowMs();
		long[] tally = Arrays.copyOf(plugin.onTaskTally(ms[0], ms[1], null, period.whole()), 4);
		tally[3] = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, period.whole()))[1];
		return tally;
	}

	private Map<String, Long> chatKcByKind()
	{
		return memo("chatKc", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			plugin.killCounts().forEach((k, v) -> out.merge(LocalStore.chatKind(k), v, Math::max));
			return out;
		});
	}

	private Map<String, Long> kcByKind()
	{
		return memo("kcByKind", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>(chatKcByKind());
			for (SourceRow r : sources())
			{
				out.merge(kindOf(r.name), (long) r.kc, Math::max);
			}
			return out;
		});
	}

	long standingKills(SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		Long said = chatKcByKind().get(LocalStore.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	long bossKills(String name)
	{
		JsonObject cl = clogNow();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		Long byKind = kcByKind().get(kindOf(name));
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
			if (killLine(ln.getKey()))
			{
				return ln.getValue();
			}
		}
		return Math.max(0, lookup(cl, "kcs", LOG_PAGE_FOR.getOrDefault(name, name)));
	}

	private static boolean killLine(String label)
	{
		String said = low(label);
		return said.contains("kill") || said.contains("completion");
	}

	static long lookup(JsonObject clog, String map, String key)
	{
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
		Map<String, Long> moved = movedKcs(s);
		String key = keyOf(moved.keySet(), name);
		if (key != null)
		{
			return Math.max(0, moved.get(key));
		}
		Long rolled = rolledKills(name);
		if (rolled != null)
		{
			rollUsed = true;
			return rolled;
		}
		return -1;
	}

	Map<String, Long> movedKcs(Span s)
	{
		return memo("movedKcs", () -> HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			closingNow(s.closing.kcs, plugin.killCounts())));
	}

	long rolled(String name)
	{
		Long n = rolledKills(name);
		return n == null ? 0 : n;
	}

	private Long rolledKills(String name)
	{
		if (plugin.lootRollFrom() <= 0)
		{
			return null;
		}
		Map<String, Long> rolled = memo("rolled", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (String[] r : lootWindow().sources)
			{
				long n = safeParse(r[1]);
				if (n > 0)
				{
					out.put(kindOf(r[0]), n);
				}
			}
			return out;
		});
		return rolled.get(kindOf(name));
	}

	LocalDate rollShortOf()
	{
		long from = plugin.lootRollFrom();
		if (from <= 0)
		{
			return null;
		}
		LocalDate began = dayOf(from);
		return began.isAfter(window().start) ? began : null;
	}

	List<Entry<String, Long>> pageLines(String boss, String map)
	{
		List<Entry<String, Long>> out = new ArrayList<>();
		JsonElement found = getIgnoreCase(obj(clogNow(), map), LOG_PAGE_FOR.getOrDefault(boss, boss));
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Entry<String, JsonElement> ln : found.getAsJsonObject().entrySet())
		{
			long n = asLong(ln.getValue());
			if (n > 0 && lineBelongsTo(boss, ln.getKey()))
			{
				out.add(new AbstractMap.SimpleEntry<>(ln.getKey(), n));
			}
		}
		return out;
	}

	List<Entry<String, Long>> logLines(String boss)
	{
		List<Entry<String, Long>> out = pageLines(boss, "kc_lines");
		long kills = bossKills(boss);
		out.removeIf(ln -> ln.getValue() == kills && killLine(ln.getKey()));
		return out;
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

	private boolean lineBelongsTo(String boss, String label)
	{
		if (label == null || label.isEmpty() || Character.isDigit(label.charAt(label.length() - 1)))
		{
			return false;
		}
		String said = low(label);
		String mine = bare(boss);
		String best = null;
		for (Boss b : bossRoster(plugin.gson()))
		{
			String name = bare(b.name);
			if (!name.isEmpty() && said.contains(name) && (best == null || name.length() > best.length()))
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
			if (!other.name.equalsIgnoreCase(boss)
				&& LOG_PAGE_FOR.getOrDefault(other.name, other.name).equalsIgnoreCase(page)
				&& namesOneOf(said, words(bare(other.name), mine)))
			{
				return false;
			}
		}
		return true;
	}

	boolean isKillSource(String name)
	{
		Set<String> kinds = memo("killKinds", () ->
		{
			Set<String> out = new HashSet<>();
			bossRoster(plugin.gson()).forEach(b -> out.add(kindOf(b.name)));
			obj(clogNow(), "slayer_kcs").keySet().forEach(said -> out.add(kindOf(said)));
			return out;
		});
		return kinds.contains(kindOf(name)) || taskKillsEver().containsKey(name);
	}

	SourceRow clue(String tier)
	{
		return find(sources(), r -> r.name, "Clue Scroll (" + tier + ")", false);
	}

	String bossLootSource(Boss b)
	{
		String kind = kindOf(b.name);
		SourceRow best = null;
		for (SourceRow r : sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				return r.name;
			}
			if ((namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : b.name;
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
		Map<String, String> names = memo("ledgerNames", () ->
		{
			Map<String, String> index = new HashMap<>();
			sources().forEach(r -> index.putIfAbsent(low(r.name), r.name));
			return index;
		});
		String low = low(name);
		String hit = names.get(low);
		return hit == null && low.endsWith("s") ? names.get(low.substring(0, low.length() - 1)) : hit;
	}

	private String sourceKind(String name)
	{
		Map<String, String> kinds = memo("sourceKinds", HashMap::new);
		String known = kinds.get(name);
		if (known == null)
		{
			known = decideKind(name);
			kinds.put(name, known);
		}
		return known;
	}

	private String decideKind(String name)
	{
		if (PICKPOCKETED.contains(low(name)) || skilledKeys().contains(letters(name)))
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
		return containsAny(low, OPENED) ? KIND_ACTIVITY : containsAny(low, GATHERED) ? KIND_SKILLING : KIND_MONSTER;
	}

	private Set<String> skilledKeys()
	{
		return memo("skilled", () ->
		{
			Set<String> found = new HashSet<>();
			for (String key : counters().keySet())
			{
				for (String[] verb : SKILLED)
				{
					if (key.endsWith(verb[0]) && !key.endsWith("Failed" + verb[0]))
					{
						found.add(letters(key.substring(0, key.length() - verb[0].length())));
						break;
					}
				}
			}
			return found;
		});
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
			if (kindOf(b.name).equals(kind) || namesInBrackets(name, b.name) || paysOutThrough(b.name, name))
			{
				return false;
			}
		}
		return FOUGHT_AS.values().stream().flatMap(List::stream).noneMatch(npc -> kindOf(npc).equals(kind));
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
			Skill sk = skill(PAGE_SKILL.get(r.name));
			if (!ownTile.contains(low(r.name)) && sk != null && sk.name().equalsIgnoreCase(craft) && r.value > 0)
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
		for (SkillGain g : plugin.sessionSkillXp())
		{
			if (g.skill != null && g.xp > 0 && key.equalsIgnoreCase(g.skill.name()))
			{
				return g.xp;
			}
		}
		return 0;
	}

	SkillStand skillStand(Baseline closing, boolean live)
	{
		Map<String, long[]> sheet = live ? plugin.skillSheet() : Collections.emptyMap();
		List<Skill> order = skillOrder();
		List<String> keys = new ArrayList<>();
		order.forEach(sk -> keys.add(low(sk.name())));
		HistoryLog.Levels closed = HistoryLog.levels(closing, keys);
		Map<Skill, Long> levels = new EnumMap<>(Skill.class);
		long total = 0;
		for (Skill sk : order)
		{
			long[] cur = sheet.get(low(sk.name()));
			long level = cur != null && cur[0] > 0 ? cur[0] : closed.of.get(low(sk.name()));
			total += level;
			long shown = period.whole() && cur != null && cur.length > 1 && cur[1] > 0
				? PaceBook.virtualLevelAt(cur[1]) : level;
			levels.put(sk, Math.max(level, shown));
		}
		long[] ov = sheet.get("overall");
		return new SkillStand(order, keys, levels, ov != null && ov[0] > 0 ? ov[0] : total, closed);
	}

	static Baseline baselineAt(Map<String, Long> xp)
	{
		Baseline at = new Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	long stirred(String type)
	{
		Map<String, Long> moved = memo("stirred", () ->
		{
			Map<String, Long> out = new HashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (insideWindow(asLong(e.get("ts"))))
				{
					out.merge(typeOf(e), 1L, Long::sum);
				}
			}
			return out;
		});
		return moved.getOrDefault(type, 0L);
	}

	List<JsonObject> milestones()
	{
		return memo("milestones", () ->
		{
			List<JsonObject> out = new ArrayList<>();
			TreeMap<LocalDate, Baseline> spine = historySpine;
			if (spine == null || spine.size() < 2)
			{
				return out;
			}
			Map<String, Long> prev = null;
			for (Entry<LocalDate, Baseline> day : spine.entrySet())
			{
				Map<String, Long> now = standings(day.getValue(), SKILL_KEYS);
				if (prev != null)
				{
					crossings(prev, now, noon(day.getKey()), out);
				}
				Map<String, Long> carried = prev == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prev);
				carried.putAll(now);
				prev = carried;
			}
			Collections.reverse(out);
			return out;
		});
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
			while ((i > 0 || !liveHead) && m < marks.size() && asLong(marks.get(m).get("ts")) > ts)
			{
				out.add(marks.get(m++));
			}
			out.add(feed.get(i));
		}
		out.addAll(marks.subList(m, marks.size()));
		return out;
	}

	Map<String, Long> landedSlots()
	{
		return memo("landed", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				JsonObject d = obj(e, "data");
				if ("COLLECTION".equals(typeOf(e)) && has(d, "itemName"))
				{
					out.put(low(d.get("itemName").getAsString()), asLong(e.get("ts")));
				}
			}
			return out;
		});
	}

	Map<String, JsonObject> records()
	{
		return memo("records", () ->
		{
			Map<String, JsonObject> out = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				JsonObject d = obj(e, "data");
				if ("RECORD".equals(typeOf(e)) && has(d, "source"))
				{
					out.putIfAbsent(low(d.get("source").getAsString()), e);
				}
			}
			return out;
		});
	}

	private Days days()
	{
		return memo("days", () ->
		{
			Days days = new Days();
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
						days.crossed.add(on);
					}
				}
				long[] t = days.played.computeIfAbsent(day, k -> new long[8]);
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
				if (isObject(d, "skills"))
				{
					t[7]++;
					Map<String, Long> by = days.skills.computeIfAbsent(day, k -> new LinkedHashMap<>());
					d.getAsJsonObject("skills").entrySet().forEach(sk -> by.merge(sk.getKey(), asLong(sk.getValue()), Long::sum));
				}
			}
			return days;
		});
	}

	Map<LocalDate, long[]> daysPlayed()
	{
		return days().played;
	}

	Object[] dayXp(LocalDate day)
	{
		if (historySpine == null)
		{
			return null;
		}
		Baseline at = historySpine.get(day);
		Entry<LocalDate, Baseline> before = historySpine.lowerEntry(day);
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
			long gained = was == null || "overall".equals(e.getKey()) ? 0 : e.getValue() - was;
			if (gained > 0)
			{
				total += gained;
				if (gained > most)
				{
					most = gained;
					top = e.getKey();
				}
			}
		}
		return total > 0 ? new Object[]{total, prettify(top)} : null;
	}

	String dayEntry(LocalDate day)
	{
		Days days = days();
		List<String> clauses = new ArrayList<>();
		long[] sat = days.played.get(day);
		boolean sat1 = sat != null && sat[1] > 0;
		if (sat1)
		{
			clauses.add(count(sat[1], "sitting") + (sat[0] > 0 ? " · " + hoursMinutes(sat[0]) : ""));
		}
		boolean crossed = days.crossed.contains(day);
		boolean saysXp = crossed && sat1 && sat[3] == sat[1];
		if (saysXp && sat[2] > 0)
		{
			Map<String, Long> by = sat[7] == sat[1] ? days.skills.get(day) : null;
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
		else if (!saysXp)
		{
			Object[] xp = dayXp(day);
			if (xp != null)
			{
				clauses.add("+" + gp((Long) xp[0]) + " xp, most in " + xp[1]);
			}
		}
		long[] loot = crossed && sat1 && sat[6] == sat[1]
			? new long[]{sat[4], sat[5]} : dayTotals().get(ROLL_DAY.format(day));
		if (loot != null && loot[0] > 0)
		{
			clauses.add(count(loot[0], "drop") + tail(loot[1]));
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
			return sat;
		}
		for (Entry<LocalDate, long[]> d : daysPlayed().entrySet())
		{
			if (insideWindow(noon(d.getKey())))
			{
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

	Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		plugin.pets().forEach(r -> out.putIfAbsent(low(r.name), r));
		return out;
	}

	int[] clogStanding()
	{
		int avail = plugin.clogAvailable();
		return avail > 0 ? new int[]{plugin.clogFinished(), avail} : null;
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
			long here = tiers.entrySet().stream().filter(t -> t.getValue().getAsBoolean()).count();
			all += tiers.size();
			done += here;
			whole += here > 0 && here == tiers.size() ? 1 : 0;
		}
		return new long[]{done, all, whole, d.size()};
	}

	long[] combatStanding()
	{
		JsonObject c = obj(achievements(), "combat");
		long tiers = obj(c, "tiers").entrySet().stream().filter(t -> t.getValue().getAsLong() > 0).count();
		long possible = 0;
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			JsonObject data = obj(e, "data");
			if ("COMBAT_ACHIEVEMENT".equals(typeOf(e)) && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
				break;
			}
		}
		return new long[]{asLong(c.get("points")), possible == 0 ? CA_POINTS : possible, tiers};
	}

	Set<Integer> caDone()
	{
		Set<Integer> out = new HashSet<>();
		for (JsonElement e : arr(obj(achievements(), "combat"), "tasksDone"))
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

	long[] periodXp()
	{
		if (period.whole())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall.length > 1 ? new long[]{overall[1]} : null;
		}
		if (period.session())
		{
			return new long[]{plugin.sessionSkillXp().stream().mapToLong(g -> Math.max(0, g.xp)).sum()};
		}
		Map<String, Long> gains = periodSkillGains();
		return gains == null ? null : new long[]{gains.values().stream().mapToLong(g -> Math.max(0, g)).sum()};
	}

	String periodXpMost()
	{
		if (period.session())
		{
			SkillGain top = most(plugin.sessionSkillXp(), g -> g.xp);
			return top == null ? null : prettify(low(top.skill.name()));
		}
		Map<String, Long> by = new LinkedHashMap<>();
		if (period.whole())
		{
			plugin.skillSheet().forEach((k, v) ->
			{
				if (!"overall".equals(k) && v.length > 1)
				{
					by.put(k, v[1]);
				}
			});
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
		long[] overall = plugin.skillSheet().get("overall");
		if (period.whole())
		{
			return overall != null && overall[0] > 0 ? new long[]{overall[0], overall[0]} : null;
		}
		if (period.session())
		{
			long gained = stirred("LEVEL");
			return gained > 0 && overall != null ? new long[]{gained, overall[0]} : null;
		}
		Span s = span();
		if (s == null || !s.opening.complete || !s.closing.complete)
		{
			return null;
		}
		HistoryLog.Levels was = HistoryLog.levels(s.opening, SKILL_KEYS);
		HistoryLog.Levels now = HistoryLog.levels(baselineAt(closingSkills(s.closing.skills, periodReachesToday())), SKILL_KEYS);
		return now.total > was.total ? new long[]{now.total - was.total, now.total} : null;
	}

	Map<String, Long> periodSkillGains()
	{
		Span s = span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> out = new LinkedHashMap<>(HistoryLog.gained(s.opening.skills, s.earliest.skills,
			closingSkills(s.closing.skills, periodReachesToday()), s.opening.complete));
		out.remove("overall");
		return out;
	}

	long[] periodLoot()
	{
		if (!period.whole())
		{
			LocalStore.LootWindow win = lootWindow();
			return new long[]{win.loots, win.value, win.left, win.leftValue};
		}
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

	Map<String, Long> periodWorth(LocalDate from, LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (period.whole())
		{
			sources().forEach(r -> out.merge(r.name, r.value, Long::sum));
		}
		else
		{
			plugin.lootBetween(from, to).sources.forEach(r -> out.merge(r[0], safeParse(r[2]), Long::sum));
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
		worth.forEach((k, v) -> out.merge(kindOf(k), v, Long::sum));
		return out;
	}

	static long[] tallyOf(List<BagItem> bag)
	{
		return new long[]{bag.stream().mapToLong(b -> b.qty).sum(), bag.stream().mapToLong(b -> b.value).sum()};
	}

	static List<Kind> kindsOf(List<BagItem> bag)
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

	static Obtained obtained(JsonObject cl)
	{
		Obtained o = new Obtained();
		obj(cl, "clog_items").entrySet().forEach(e -> o.all.merge(low(e.getKey()), asLong(e.getValue()), Math::max));
		for (Entry<String, JsonObject> pg : objects(obj(cl, "by_cat")))
		{
			Map<String, Long> items = new LinkedHashMap<>();
			pg.getValue().entrySet().forEach(it -> items.merge(low(it.getKey()), asLong(it.getValue()), Math::max));
			o.byPage.put(low(pg.getKey()), items);
		}
		return o;
	}

	static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems, Map<String, Long> owned,
		Set<String> sharedNames)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new HashMap<>();
		slots.forEach(slot -> dupes.merge(low(slot), 1, Integer::sum));
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = low(slots.get(i));
			long onPage = pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L;
			boolean globalMaySpeak = pageItems == null || !sharedNames.contains(key);
			long have = Math.max(onPage, globalMaySpeak ? owned.getOrDefault(key, 0L) : 0L);
			lit[i] = dupes.get(key) > 1
				? seen.merge(key, 1, Integer::sum) - 1 < have
				: have > 0 || pageItems != null && pageItems.containsKey(key) || globalMaySpeak && owned.containsKey(key);
		}
		return lit;
	}

	static Map<String, Long> pageCounts(JsonObject cl)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		Set<String> lined = new HashSet<>();
		obj(cl, "kc_lines").keySet().forEach(page -> lined.add(low(page)));
		for (Entry<String, JsonElement> e : obj(cl, "kcs").entrySet())
		{
			if (!lined.contains(low(e.getKey())))
			{
				out.merge(low(e.getKey()), asLong(e.getValue()), Math::max);
			}
		}
		LocalStore.pageKillLines(cl).forEach((k, v) -> out.put(low(k), v));
		return out;
	}

	static String pageHeaderTip(JsonObject cl, String page)
	{
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

	static String tabStanding(JsonObject cl, String tab)
	{
		JsonObject counts = obj(cl, "cat_counts");
		long total = asLong(counts.get(low(tab) + "_total"));
		long got = asLong(counts.get(low(tab) + "_obtained"));
		return total <= 0 ? null : tip(tab, "Obtained", fmt(got), "Available", fmt(total), "Share", share(got, total));
	}

	static boolean journeyMoved(SlayerJourney was, SlayerJourney now)
	{
		return was == null || was.completedTasks != now.completedTasks || was.totalKills != now.totalKills
			|| was.tasks.size() != now.tasks.size()
			|| !was.tasks.isEmpty() && was.tasks.get(0).kills != now.tasks.get(0).kills;
	}
}
