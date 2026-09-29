/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

@RequiredArgsConstructor
final class DayQuery
{
	private final Board board;

	List<FeedLine> searchFeed;

	private long searchFeedTs = -1;

	TreeMap<LocalDate, Baseline> searchFeedSpine;

	static final class DayPlay
	{
		long minutes;
		long sittings;
		long xp;
		long xpSittings;
		long drops;
		long dropsGp;
		long dropSittings;
		long skillSittings;
	}

	@RequiredArgsConstructor
	static final class XpGain
	{
		final long total;
		final String top;
	}

	static final class Sittings
	{
		long minutes;
		long count;
		long busiestMinutes;
		LocalDate busiest;
	}

	static final class Days
	{
		final Map<LocalDate, DayPlay> played = new LinkedHashMap<>();
		final Map<LocalDate, Map<String, Long>> skills = new LinkedHashMap<>();
		final Set<LocalDate> crossed = new HashSet<>();
	}

	long stirred(String type)
	{
		Map<String, Long> moved = board.memo("stirred", () ->
		{
			Map<String, Long> out = new HashMap<>();
			for (JsonObject e : board.store.feedNewest(Board.FEED_SCAN_DEEP))
			{
				if (board.insideWindow(asLong(e.get("ts"))))
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
		return board.memo("milestones", () ->
		{
			List<JsonObject> out = new ArrayList<>();
			TreeMap<LocalDate, Baseline> spine = board.historySpine;
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
		return board.memo("landed", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (JsonObject e : board.store.feedNewest(Board.FEED_SCAN_DEEP))
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
		return board.memo("records", () ->
		{
			Map<String, JsonObject> out = new LinkedHashMap<>();
			for (JsonObject e : board.store.feedNewest(Board.FEED_SCAN_DEEP))
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

	private Days byDay()
	{
		return board.memo("days", () ->
		{
			Days days = new Days();
			for (JsonObject e : board.plugin.feedWithSitting(Board.FEED_SCAN_DEEP))
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
				DayPlay t = days.played.computeIfAbsent(day, k -> new DayPlay());
				t.minutes += sessionMinutes(e);
				t.sittings++;
				if (d.has("xp"))
				{
					t.xp += asLong(d.get("xp"));
					t.xpSittings++;
				}
				if (d.has("drops"))
				{
					t.drops += asLong(d.get("drops"));
					t.dropsGp += asLong(d.get("dropsGp"));
					t.dropSittings++;
				}
				if (d.has("xp") && asLong(d.get("xp")) == 0 && !d.has("skills"))
				{
					t.skillSittings++;
				}
				if (isObject(d, "skills"))
				{
					t.skillSittings++;
					Map<String, Long> by = days.skills.computeIfAbsent(day, k -> new LinkedHashMap<>());
					d.getAsJsonObject("skills").entrySet().forEach(sk -> by.merge(sk.getKey(), asLong(sk.getValue()), Long::sum));
				}
			}
			return days;
		});
	}

	Map<LocalDate, DayPlay> daysPlayed()
	{
		return byDay().played;
	}

	XpGain dayXp(LocalDate day)
	{
		if (board.historySpine == null)
		{
			return null;
		}
		Baseline at = board.historySpine.get(day);
		Entry<LocalDate, Baseline> before = board.historySpine.lowerEntry(day);
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
		return total > 0 ? new XpGain(total, prettify(top)) : null;
	}

	String dayEntry(LocalDate day)
	{
		Days days = byDay();
		List<String> clauses = new ArrayList<>();
		DayPlay sat = days.played.get(day);
		boolean sat1 = sat != null && sat.sittings > 0;
		if (sat1)
		{
			clauses.add(count(sat.sittings, "sitting") + (sat.minutes > 0 ? " · " + hoursMinutes(sat.minutes) : ""));
		}
		boolean crossed = days.crossed.contains(day);
		boolean saysXp = crossed && sat1 && sat.xpSittings == sat.sittings;
		if (saysXp && sat.xp > 0)
		{
			Map<String, Long> by = sat.skillSittings == sat.sittings ? days.skills.get(day) : null;
			String most = by == null ? null : topOf(by);
			if (most != null)
			{
				most = prettify(most);
			}
			else
			{
				XpGain spine = dayXp(day);
				most = spine != null ? spine.top : null;
			}
			clauses.add("+" + gp(sat.xp) + " xp" + (most != null ? ", most in " + most : ""));
		}
		else if (!saysXp)
		{
			XpGain xp = dayXp(day);
			if (xp != null)
			{
				clauses.add("+" + gp(xp.total) + " xp, most in " + xp.top);
			}
		}
		Tally loot = crossed && sat1 && sat.dropSittings == sat.sittings ? new Tally(null, sat.drops, sat.dropsGp)
			: board.loot.dayTotals().get(ROLL_DAY.format(day));
		if (loot != null && loot.qty > 0)
		{
			clauses.add(count(loot.qty, "drop") + tail(loot.value));
		}
		return clauses.isEmpty() ? null : String.join(" · ", clauses);
	}

	Sittings sittingsInWindow()
	{
		Sittings sat = new Sittings();
		if (board.period.session())
		{
			sat.minutes = board.plugin.sessionElapsedMinutes();
			sat.count = sat.minutes > 0 ? 1 : 0;
			return sat;
		}
		for (Entry<LocalDate, DayPlay> d : daysPlayed().entrySet())
		{
			if (board.insideWindow(noon(d.getKey())))
			{
				sat.minutes += d.getValue().minutes;
				sat.count += d.getValue().sittings;
				if (d.getValue().minutes > sat.busiestMinutes)
				{
					sat.busiestMinutes = d.getValue().minutes;
					sat.busiest = d.getKey();
				}
			}
		}
		return sat;
	}

	@RequiredArgsConstructor
	static final class FeedLine
	{
		final String key;
		final String text;
		final long ts;
	}

	List<FeedLine> searchFeed()
	{
		long newest = newestTs(board.store.feedNewest(1));
		if (searchFeed == null || newest != searchFeedTs || board.historySpine != searchFeedSpine)
		{
			List<FeedLine> out = new ArrayList<>();
			List<JsonObject> all = new ArrayList<>(board.store.feedNewest(Board.JOURNAL_DEEP));
			all.addAll(milestones());
			for (JsonObject e : all)
			{
				String line = feedLine(e);
				if (line != null && !line.isEmpty())
				{
					out.add(new FeedLine(low(line).replace("'", ""), line, filedAt(e)));
				}
			}
			searchFeed = out;
			searchFeedTs = newest;
			searchFeedSpine = board.historySpine;
		}
		return searchFeed;
	}
}
