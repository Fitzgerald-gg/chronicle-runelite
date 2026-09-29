/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

@RequiredArgsConstructor
final class LogQuery
{
	private final Board board;

	static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	@RequiredArgsConstructor
	static final class Diaries
	{
		final Fraction tiers;
		final Fraction regions;
	}

	@RequiredArgsConstructor
	static final class Combat
	{
		final Fraction points;
		final long tiers;
	}

	Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		board.store.pets().forEach(r -> out.putIfAbsent(low(r.name), r));
		return out;
	}

	Fraction clogStanding()
	{
		int avail = board.plugin.clogAvailable();
		return avail > 0 ? new Fraction(board.plugin.clogFinished(), avail) : null;
	}

	Diaries diaryStanding()
	{
		JsonObject d = obj(board.achievements(), "diaries");
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
		return new Diaries(new Fraction(done, all), new Fraction(whole, d.size()));
	}

	Combat combatStanding()
	{
		JsonObject c = obj(board.achievements(), "combat");
		long tiers = obj(c, "tiers").entrySet().stream().filter(t -> t.getValue().getAsLong() > 0).count();
		long possible = 0;
		for (JsonObject e : board.store.feedNewest(Board.FEED_SCAN_DEEP))
		{
			JsonObject data = obj(e, "data");
			if ("COMBAT_ACHIEVEMENT".equals(typeOf(e)) && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
				break;
			}
		}
		return new Combat(new Fraction(asLong(c.get("points")), possible == 0 ? CA_POINTS : possible), tiers);
	}

	Set<Integer> caDone()
	{
		Set<Integer> out = new HashSet<>();
		for (JsonElement e : arr(obj(board.achievements(), "combat"), "tasksDone"))
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

	static int lit(boolean[] slots)
	{
		int n = 0;
		for (boolean b : slots)
		{
			n += b ? 1 : 0;
		}
		return n;
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
			lit[i] = dupes.get(key) > 1 ? seen.merge(key, 1, Integer::sum) - 1 < have
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
		KillCounts.pageKillLines(cl).forEach((k, v) -> out.put(low(k), v));
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
			lines.addAll(List.of(ln.getKey(), fmt(asLong(ln.getValue()))));
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
}
