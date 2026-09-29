/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Span;
import chronicle.counters.ExperienceStatTracker;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import java.util.TreeMap;
import java.util.SortedMap;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.AbstractMap;
import java.time.Instant;
import chronicle.panel.HistoryProgress;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.SlayerJourney;
import net.runelite.api.Skill;
import static chronicle.Feed.feedName;
import static chronicle.Text.tail;
import static chronicle.Period.startMs;
import static chronicle.Period.dayOf;
import static chronicle.Period.FULL_DAY;
import static chronicle.Period.DAY;
import static chronicle.Json.strMap;
import static chronicle.Feed.sittingsCover;
import static chronicle.Feed.sessionMinutes;
import static chronicle.Feed.oldestTs;
import static chronicle.Feed.filedAt;
import static chronicle.Feed.earliestDatedLoot;
import static chronicle.Feed.most;
import static chronicle.Feed.topOf;
import static chronicle.Feed.typeOf;
import static chronicle.Json.keyOf;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.noon;
import static chronicle.Text.climb;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.pb;
import static chronicle.panel.StatRegistry.prettify;

@RequiredArgsConstructor
final class Summary
{
	private final Build build;

	RecapPicture.Facts facts()
	{
		RecapPicture.Facts f = new RecapPicture.Facts();
		f.whole = build.period().whole;
		f.session = build.period().session;
		f.title = f.whole ? "The whole record" : build.period().label;
		Span s = f.whole || f.session ? null : build.span();
		recapSkills(f, s);
		recapBosses(f, s);
		recapMonsters(f, s);
		recapLoot(f);
		recapSlayerAndClues(f);
		recapTrackers(f);
		recapFeats(f);
		recapTiles(f);
		return f;
	}

	private void recapSkills(RecapPicture.Facts f, Span s)
	{
		Map<String, long[]> sheet = build.plugin.skillSheet();
		Map<String, Long> closing = null;
		if (!f.whole && !f.session)
		{
			if (s == null)
			{
				f.skillsNote = build.notCounting(false) != null
					? "The record keeps no experience this far back."
					: "Nothing closed inside " + build.period().inSentence()
						+ ": a period is the distance between two baselines, and this one holds fewer than two.";
				return;
			}
			closing = build.closingSkills(s.closing.skills, build.period().reachesToday());
		}
		Map<String, Integer> startLevels = new LinkedHashMap<>();
		Map<String, Integer> endLevels = new LinkedHashMap<>();
		long startXp = 0;
		long endXp = 0;
		boolean startsKnown = true;
		for (Skill sk : Skill.values())
		{
			String key = low(sk.name());
			Long end;
			Long start = null;
			if (closing != null)
			{
				end = closing.get(key);
				Long base = s.opening.skills.get(key);
				if (base == null)
				{
					base = s.opening.complete ? Long.valueOf(0L) : s.earliest.skills.get(key);
				}
				start = base == null || end == null ? null : Math.min(base, end);
			}
			else
			{
				long[] cur = sheet.get(key);
				end = cur != null && cur.length > 1 ? cur[1] : null;
				if (f.session && end != null)
				{
					start = Math.max(0, end - sessionXp(key));
				}
			}
			if (end == null)
			{
				continue;
			}
			int lEnd = f.whole ? PaceBook.virtualLevelAt(end) : PaceBook.levelAt(end);
			Integer lStart = start == null ? null : PaceBook.levelAt(start);
			if (sk == Skill.HITPOINTS)
			{
				lEnd = Math.max(10, lEnd);
				lStart = lStart == null ? null : Math.max(10, lStart);
			}
			f.skills.add(new RecapPicture.SkillLine(sk, prettify(key), lStart, lEnd,
				start, end));
			endLevels.put(key, Math.min(99, lEnd));
			endXp += end;
			if (start == null)
			{
				startsKnown = false;
			}
			else
			{
				startLevels.put(key, lStart);
				startXp += start;
			}
		}
		if (f.skills.isEmpty())
		{
			return;
		}
		long[] overall = sheet.get("overall");
		long[] total = levels();
		f.totalLevel = total == null ? null : new Long[]{total[0] < 0 ? null : total[0], total[1]};
		f.totalXp = new Long[]{startsKnown ? startXp : null,
			f.whole && overall != null && overall.length > 1 && overall[1] > 0 ? overall[1] : endXp};
		Integer cEnd = combatOf(endLevels);
		if (f.whole && build.plugin.combatLevel() > 0)
		{
			cEnd = build.plugin.combatLevel();
		}
		Integer cStart = startsKnown ? combatOf(startLevels) : null;
		f.combat = cEnd == null ? null : new Long[]{cStart == null ? null : (long) cStart, (long) cEnd};
	}

	private static long sum(Map<String, Integer> levels)
	{
		long t = 0;
		for (int l : levels.values())
		{
			t += l;
		}
		return t;
	}

	private static Integer combatOf(Map<String, Integer> levels)
	{
		HistoryLog.Levels l = new HistoryLog.Levels();
		l.of.putAll(levels);
		return HistoryLog.combat(l);
	}

	private void recapBosses(RecapPicture.Facts f, Span s)
	{
		List<Kills.Boss> roster = Kills.bossRoster(build.plugin.gson());
		Map<String, Long> closing = s == null ? null : build.period().closingNow(s.closing.kcs, build.plugin.killCounts());
		for (Kills.Boss b : roster)
		{
			if (f.whole)
			{
				long k = build.kills.bossKills(b.name);
				if (k > 0)
				{
					f.bosses.add(new RecapPicture.BossLine(b.name, b.sprite, null, k, k));
				}
				continue;
			}
			long moved = build.kills.bossKillsInWindow(b.name);
			if (moved <= 0)
			{
				continue;
			}
			Long start = null;
			Long end = null;
			Map<String, Long> movedKcs = build.kills.movedKcs();
			String key = closing == null || movedKcs == null ? null : keyOf(movedKcs.keySet(), b.name);
			if (key != null)
			{
				end = closing.get(key);
				start = s.opening.kcs.containsKey(key) ? s.opening.kcs.get(key) : s.earliest.kcs.get(key);
				if (start == null || end == null || end - start != moved)
				{
					start = null;
					end = null;
				}
			}
			f.bosses.add(new RecapPicture.BossLine(b.name, b.sprite, start, end, moved));
		}
		if (!f.whole)
		{
			f.bosses.sort((a, b) -> Long.compare(b.gained, a.gained));
		}
		if (build.kills.rollUsed())
		{
			f.notes.add("Kill counts without a start and an end are read from loot, and count only the kills that dropped something.");
		}
		if (f.bosses.isEmpty() && !f.whole)
		{
			f.bossesNote = build.notCounting(true) != null
				? "The record keeps no kill counts this far back."
				: build.period().phrase("No boss was killed");
		}
	}

	private void recapMonsters(RecapPicture.Facts f, Span s)
	{
		Map<String, Long> by = new LinkedHashMap<>();
		Map<String, Long> worth = new LinkedHashMap<>();
		if (f.whole)
		{
			by.putAll(build.plugin.killCounts());
			for (SourceRow r : build.sources())
			{
				worth.merge(r.name, r.value, Long::sum);
			}
		}
		else if (f.session)
		{
			for (LocalStore.Row r : build.plugin.loot(null, null).sources)
			{
				by.merge(r.name, r.qty, Long::sum);
				worth.merge(r.name, r.value, Long::sum);
			}
		}
		else
		{
			if (s == null)
			{
				return;
			}
			by.putAll(HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				build.period().closingNow(s.closing.kcs, build.plugin.killCounts())));
			Period w = build.period();
			worth.putAll(worth(w.start, w.end));
		}
		Map<String, Long> loose = loosely(worth);
		List<Entry<String, Long>> kept = new ArrayList<>();
		for (Entry<String, Long> e : by.entrySet())
		{
			if (e.getValue() > 0 && recapMonster(e.getKey()))
			{
				kept.add(e);
			}
		}
		kept.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (Entry<String, Long> e : kept.subList(0, Math.min(10, kept.size())))
		{
			long paid = paidFor(worth, loose, e.getKey());
			f.monsters.add(new RecapPicture.Named(e.getKey(), fmt(e.getValue()),
				paid > 0 ? gps(paid) : null));
		}
		if (f.session && !f.monsters.isEmpty())
		{
			f.monstersNote = "A sitting counts the kills that dropped something.";
		}
	}

	private boolean recapMonster(String name)
	{
		if (!Sources.MONSTER.equals(build.names().kind(name)))
		{
			return false;
		}
		String kind = kindOf(name);
		for (Kills.Boss b : Kills.bossRoster(build.plugin.gson()))
		{
			if (kindOf(b.name).equals(kind) || Kills.namesInBrackets(name, b.name)
				|| Kills.paysOutThrough(b.name, name))
			{
				return false;
			}
		}
		for (List<String> npcs : Kills.FOUGHT_AS.values())
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

	private void recapLoot(RecapPicture.Facts f)
	{
		if (!f.whole && !f.session)
		{
			long from = build.lootDays().rollFrom();
			if (from <= 0 || from > build.period().ms()[0])
			{
				f.lootNote = "Loot is not dated this far back, so this period's cannot be told from the rest.";
				return;
			}
		}
		long[] loot = loot();
		if (loot[0] > 0)
		{
			f.loot.add(new RecapPicture.Named("Drops", fmt(loot[0]), gps(loot[1])));
		}
		if (loot[2] > 0)
		{
			f.loot.add(new RecapPicture.Named("Left behind", fmt(loot[2]), gps(loot[3])));
		}
		if (f.whole)
		{
			List<SourceRow> rows = new ArrayList<>(build.sources());
			rows.sort((a, b) -> Long.compare(b.value, a.value));
			for (SourceRow r : Sources.firstN(rows, 8))
			{
				if (r.value > 0)
				{
					f.sources.add(new RecapPicture.Named(r.name, null, gps(r.value)));
				}
			}
			List<BagItem> bag = new ArrayList<>(build.plugin.allLoot());
			bag.sort((a, b) -> Long.compare(b.value, a.value));
			for (BagItem b : Sources.firstN(bag, 6))
			{
				if (b.value > 0)
				{
					f.items.add(new RecapPicture.Named(b.name + " ×" + fmt(b.qty), null, gps(b.value)));
				}
			}
			return;
		}
		LocalStore.LootWindow win = build.lootWindow();
		for (LocalStore.Row r : Sources.firstN(win.sources, 8))
		{
			if (r.value > 0)
			{
				f.sources.add(new RecapPicture.Named(r.name, null, gps(r.value)));
			}
		}
		for (LocalStore.Row r : Sources.firstN(win.items, 6))
		{
			if (r.value > 0)
			{
				f.items.add(new RecapPicture.Named(r.name + " ×" + fmt(r.qty), null, gps(r.value)));
			}
		}
		if (f.loot.isEmpty())
		{
			f.lootNote = build.period().phrase("Nothing dropped");
		}
	}

	private void recapSlayerAndClues(RecapPicture.Facts f)
	{
		long[] tally = build.taskTally();
		long paid = tally[3];
		if (tally[2] > 0)
		{
			f.slayer.add(new RecapPicture.Named("Tasks", fmt(tally[2]), null));
			if (tally[1] > 0)
			{
				f.slayer.add(new RecapPicture.Named("Superiors", fmt(tally[1]), null));
			}
			if (paid > 0)
			{
				f.slayer.add(new RecapPicture.Named("On-task loot", null, gps(paid)));
			}
		}
		for (String tier : Standings.CLUE_TIERS)
		{
			String source = "Clue Scroll (" + tier + ")";
			long n = 0;
			long v = 0;
			SourceRow r = build.standings.clue(tier);
			if (f.whole && r != null)
			{
				n = Standings.caskets(r);
				v = r.value;
			}
			else if (!f.whole)
			{
				n = build.kills.rolled(source);
				v = source(source).value;
			}
			if (n > 0)
			{
				f.clues.add(new RecapPicture.Named(tier, fmt(n), v > 0 ? gps(v) : null));
			}
		}
	}

	private static final Map<String, Integer> RECAP_TRACKER_ROWS = new LinkedHashMap<>();

	static
	{
		RECAP_TRACKER_ROWS.put("Combat", 8);
		RECAP_TRACKER_ROWS.put("Skilling", 10);
		RECAP_TRACKER_ROWS.put("Living", 6);
		RECAP_TRACKER_ROWS.put("Ledger & Roads", 10);
	}

	private void recapTrackers(RecapPicture.Facts f)
	{
		Map<String, Long> counters = build.countersForPeriod();
		if (counters == null)
		{
			f.trackersNote = build.notCounting(false) != null
				? "The record keeps no counters this far back."
				: build.period().phrase("Nothing closed");
			return;
		}
		for (Entry<String, Integer> fam : RECAP_TRACKER_ROWS.entrySet())
		{
			List<String> order = StatRegistry.fixedSections(fam.getKey());
			List<Entry<String, Long>> rows = new ArrayList<>();
			for (Entry<String, Long> e : counters.entrySet())
			{
				String key = e.getKey();
				if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(key)
					|| !fam.getKey().equals(StatRegistry.family(key)) || StatRegistry.typed(key)
					|| "Destinations".equals(StatRegistry.subgroup(key))
					|| StatRegistry.label(key).startsWith("·"))
				{
					continue;
				}
				rows.add(e);
			}
			rows.sort((a, b) ->
			{
				int sa = order.indexOf(StatRegistry.subgroup(a.getKey()));
				int sb = order.indexOf(StatRegistry.subgroup(b.getKey()));
				if (sa != sb)
				{
					return Integer.compare(sa < 0 ? order.size() : sa, sb < 0 ? order.size() : sb);
				}
				return Long.compare(b.getValue(), a.getValue());
			});
			List<RecapPicture.Named> out = new ArrayList<>();
			for (Entry<String, Long> e : rows.subList(0, Math.min(fam.getValue(), rows.size())))
			{
				String key = e.getKey();
				boolean money = StatRegistry.isGp(key);
				out.add(new RecapPicture.Named(StatRegistry.label(key),
					money ? null : fmt(e.getValue()), money ? gps(e.getValue()) : null));
			}
			if (!out.isEmpty())
			{
				f.trackers.put(fam.getKey(), out);
			}
		}
	}

	private void recapFeats(RecapPicture.Facts f)
	{
		Map<String, List<String>> named = new LinkedHashMap<>();
		for (String k : new String[]{"Milestones", "Collection log", "Pets", "Personal bests",
			"Quests", "Diaries", "Combat achievements", "Deaths"})
		{
			named.put(k, new ArrayList<>());
		}
		for (JsonObject m : build.feed.milestones())
		{
			if (build.period().inside(safeLong(m.get("ts"))) && m.has("data"))
			{
				named.get("Milestones").add(m.getAsJsonObject("data").get("text").getAsString());
			}
		}
		Set<String> bests = new HashSet<>();
		for (JsonObject e : build.feed.all())
		{
			if (!build.period().inside(safeLong(e.get("ts"))))
			{
				continue;
			}
			String type = typeOf(e);
			JsonObject d = obj(e, "data");
			switch (type)
			{
				case "COMBAT_ACHIEVEMENT":
					if (!f.whole && d.has("task"))
					{
						named.get("Combat achievements").add(d.get("task").getAsString());
					}
					break;
				case "COLLECTION":
				case "QUEST":
				case "DIARY":
					if (!f.whole)
					{
						String n = feedName(e);
						if (n != null)
						{
							named.get(featOf(type)).add(n);
						}
					}
					break;
				case "PET":
					if (d.has("petName"))
					{
						named.get("Pets").add(d.get("petName").getAsString());
					}
					break;
				case "RECORD":
					if (d.has("source") && d.has("time")
						&& bests.add(low(d.get("source").getAsString())))
					{
						named.get("Personal bests").add(d.get("source").getAsString() + " "
							+ pb(d.get("time").getAsDouble()));
					}
					break;
				case "DEATH":
					if (!f.whole)
					{
						named.get("Deaths").add(d.has("killerName")
							? d.get("killerName").getAsString() : "Unknown");
					}
					break;
				default:
					break;
			}
		}
		if (!f.whole)
		{
			List<String> out = new ArrayList<>();
			for (RecapPicture.SkillLine l : f.skills)
			{
				if (l.levelStart != null && l.levelStart < l.levelEnd)
				{
					out.add(l.name + " " + l.levelEnd);
				}
			}
			if (!out.isEmpty())
			{
				f.feats.put("Levels", out);
			}
		}
		if (f.whole && build.plugin.clogAvailable() > 0)
		{
			f.feats.put("Collection log", Collections.singletonList(
				fmt(build.plugin.clogFinished()) + " of " + fmt(build.plugin.clogAvailable()) + " slots"));
			named.remove("Collection log");
		}
		for (Entry<String, List<String>> e : named.entrySet())
		{
			if (!e.getValue().isEmpty())
			{
				f.feats.put(e.getKey(), counted(e.getValue()));
			}
		}
	}

	private static List<String> counted(List<String> names)
	{
		Map<String, Integer> n = new LinkedHashMap<>();
		for (String s : names)
		{
			n.merge(s, 1, Integer::sum);
		}
		List<String> out = new ArrayList<>();
		for (Entry<String, Integer> e : n.entrySet())
		{
			out.add(e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : ""));
		}
		return out;
	}

	private static String featOf(String type)
	{
		switch (type)
		{
			case "COLLECTION":
				return "Collection log";
			case "QUEST":
				return "Quests";
			case "DIARY":
				return "Diaries";
			default:
				return "Combat achievements";
		}
	}

	private void recapTiles(RecapPicture.Facts f)
	{
		long[] sat = played();
		if (sat[2] == 1)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(sat[0]), "the game's own count"));
		}
		else if (sat[1] > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(sat[0]), count(sat[1], "sitting")));
		}
		long[] xp = xp();
		if (xp != null && xp[0] > 0)
		{
			String most = xpMost();
			f.tiles.add(new RecapPicture.Tile("Experience", (f.whole ? "" : "+") + gp(xp[0]),
				most == null ? null : "most in " + most));
		}
		if (f.totalLevel != null && f.totalLevel[1] != null)
		{
			Long a = f.totalLevel[0];
			long b = f.totalLevel[1];
			if (f.whole)
			{
				f.tiles.add(new RecapPicture.Tile("Total level", fmt(b),
					f.combat != null && f.combat[1] != null ? "combat " + f.combat[1] : null));
			}
			else if (a != null && b > a)
			{
				f.tiles.add(new RecapPicture.Tile("Levels", "+" + fmt(b - a),
					climb(a, b) + " total"));
			}
		}
		for (RecapPicture.Named n : f.loot)
		{
			if ("Drops".equals(n.name))
			{
				f.tiles.add(new RecapPicture.Tile("Loot", n.gp, n.figure + " drops"));
			}
		}
		long bossKills = 0;
		for (RecapPicture.BossLine b : f.bosses)
		{
			bossKills += b.gained;
		}
		RecapPicture.BossLine top = most(f.bosses, b -> b.gained);
		if (bossKills > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Boss kills", (f.whole ? "" : "+") + fmt(bossKills),
				"most " + top.name));
		}
		for (RecapPicture.Named n : f.slayer)
		{
			if ("Tasks".equals(n.name))
			{
				String paid = null;
				for (RecapPicture.Named m : f.slayer)
				{
					if (m.gp != null)
					{
						paid = m.gp + " on task";
					}
				}
				f.tiles.add(new RecapPicture.Tile("Slayer tasks", n.figure, paid));
			}
		}
		List<String> slots = f.feats.get("Collection log");
		if (f.whole && build.plugin.clogAvailable() > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Collection log", fmt(build.plugin.clogFinished()),
				"of " + fmt(build.plugin.clogAvailable())));
		}
		else if (!f.whole && slots != null)
		{
			f.tiles.add(new RecapPicture.Tile("Log slots", "+" + fmt(slots.size()),
				"latest " + slots.get(0)));
		}
		List<String> pets = f.feats.get("Pets");
		if (pets != null && f.tiles.size() < 8)
		{
			f.tiles.add(new RecapPicture.Tile("Pets", (f.whole ? "" : "+") + fmt(pets.size()),
				"latest " + pets.get(0)));
		}
		while (f.tiles.size() > 8)
		{
			f.tiles.remove(f.tiles.size() - 1);
		}
	}

	long[] played()
	{
		Period w = build.period();
		long fromMs = w.session ? w.ms()[0] : startMs(w.start);
		long toMs = w.session ? w.ms()[1] : startMs(w.end.plusDays(1));
		long[] out = new long[3];
		List<JsonObject> feed = build.feed.all();
		long oldest = oldestTs(feed, false);
		if (w.whole || oldest > 0 && oldest < fromMs)
		{
			for (JsonObject e : feed)
			{
				long filed = filedAt(e);
				if ("SESSION".equals(typeOf(e)) && filed >= fromMs && filed < toMs)
				{
					out[0] += sessionMinutes(e);
					out[1]++;
				}
			}
		}
		long began = build.plugin.sessionStart();
		if (w.session ? w.reachesToday() : began > 0 && began >= fromMs && began < toMs)
		{
			long running = build.plugin.sessionElapsedMinutes();
			if (running > 0)
			{
				out[0] += running;
				out[1]++;
			}
		}
		long theirs = w.whole ? build.plugin.gamePlaytimeMinutes() : 0;
		if (theirs > out[0])
		{
			out[0] = theirs;
			out[2] = 1;
		}
		return out;
	}

	Entry<LocalDate, Long> busiest()
	{
		Entry<LocalDate, Long> top = null;
		for (Entry<LocalDate, long[]> d : build.feed.days().entrySet())
		{
			if (build.period().inside(noon(d.getKey())) && d.getValue()[0] > (top == null ? 0 : top.getValue()))
			{
				top = new AbstractMap.SimpleEntry<>(d.getKey(), d.getValue()[0]);
			}
		}
		return top;
	}

	long[] xp()
	{
		if (build.period().whole)
		{
			long[] overall = build.plugin.skillSheet().get("overall");
			return overall != null && overall.length > 1 ? new long[]{overall[1]} : null;
		}
		if (build.period().session)
		{
			long xp = 0;
			for (ExperienceStatTracker.SkillGain g : build.plugin.sessionSkillXp())
			{
				xp += Math.max(0, g.xp);
			}
			return new long[]{xp};
		}
		Map<String, Long> gains = skillGains();
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

	String xpMost()
	{
		Map<String, Long> by = new LinkedHashMap<>();
		if (build.period().session)
		{
			ExperienceStatTracker.SkillGain top = most(build.plugin.sessionSkillXp(), g -> g.xp);
			return top == null ? null : prettify(low(top.skill.name()));
		}
		if (build.period().whole)
		{
			for (Entry<String, long[]> e : build.plugin.skillSheet().entrySet())
			{
				if (!"overall".equals(e.getKey()) && e.getValue().length > 1)
				{
					by.put(e.getKey(), e.getValue()[1]);
				}
			}
		}
		else
		{
			Map<String, Long> gains = skillGains();
			if (gains == null)
			{
				return null;
			}
			by.putAll(gains);
		}
		String top = topOf(by);
		return top == null ? null : prettify(top);
	}

	long[] levels()
	{
		Period w = build.period();
		if (w.whole)
		{
			long[] overall = build.plugin.skillSheet().get("overall");
			return overall != null && overall[0] > 0 ? new long[]{-1, overall[0]} : null;
		}
		Span s = build.span();
		if (s == null || !s.opening.complete || !s.closing.complete)
		{
			return null;
		}
		Map<String, Long> shut = build.closingSkills(s.closing.skills, w.reachesToday());
		Map<String, Long> open = new HashMap<>(s.opening.skills);
		if (w.session)
		{
			open = new HashMap<>(shut);
			for (Map.Entry<String, Long> e : open.entrySet())
			{
				e.setValue(Math.max(0, e.getValue() - sessionXp(e.getKey())));
			}
		}
		return new long[]{HistoryLog.levels(baselineAt(open), HistoryLog.SKILL_KEYS).total,
			HistoryLog.levels(baselineAt(shut), HistoryLog.SKILL_KEYS).total};
	}

	Map<String, Long> skillGains()
	{
		Span s = build.span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> out = new LinkedHashMap<>(HistoryLog.gained(s.opening.skills,
			s.earliest.skills, build.closingSkills(s.closing.skills, build.period().reachesToday()), s.opening.complete));
		out.remove("overall");
		return out;
	}

	long[] loot()
	{
		if (build.period().whole)
		{
			long[] out = new long[4];
			for (SourceRow r : build.sources())
			{
				out[0] += r.loots;
				out[1] += r.value;
			}
			for (UntakenRow u : build.plugin.untaken().sources)
			{
				out[2] += u.qty;
				out[3] += u.value;
			}
			return out;
		}
		LocalStore.LootWindow win = build.lootWindow();
		return new long[]{win.loots, win.value, win.left, win.leftValue};
	}

	LocalStore.Row dearest()
	{
		if (build.period().whole)
		{
			return null;
		}
		LocalStore.LootWindow win = build.lootWindow();
		return win.items.isEmpty() ? null : win.items.get(0);
	}

	String[] killedMost()
	{
		if (build.period().whole)
		{
			SourceRow top = null;
			for (SourceRow r : build.sources())
			{
				if (build.kills.isKillSource(r.name) && (top == null || build.kills.standingKills(r) > build.kills.standingKills(top)))
				{
					top = r;
				}
			}
			return top == null ? null : new String[]{top.name, fmt(build.kills.standingKills(top))};
		}
		if (build.period().session)
		{
			LocalStore.Row top = most(build.plugin.loot(null, null).sources, r -> r.qty);
			return top == null ? null : new String[]{top.name, fmt(top.qty)};
		}
		Span s = build.span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> moved = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			build.period().closingNow(s.closing.kcs, build.plugin.killCounts()));
		String top = topOf(moved);
		return top == null ? null : new String[]{top, fmt(moved.get(top))};
	}

	Map<String, Long> worth(LocalDate from, LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (build.period().whole)
		{
			for (SourceRow r : build.sources())
			{
				out.merge(r.name, r.value, Long::sum);
			}
		}
		else
		{
			for (LocalStore.Row r : build.plugin.loot(from, to).sources)
			{
				out.merge(r.name, r.value, Long::sum);
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

	long sessionXp(String key)
	{
		for (ExperienceStatTracker.SkillGain g : build.plugin.sessionSkillXp())
		{
			if (g.skill != null && g.xp > 0
				&& key.equalsIgnoreCase(g.skill.name()))
			{
				return g.xp;
			}
		}
		return 0;
	}

	LocalStore.Row source(String name)
	{
		LocalStore.Row r = build.lootWindow().source(name, false);
		return r != null ? r : new LocalStore.Row(name, 0, 0);
	}

	static final class Board
	{
		String empty;
		String measuredSince;
		final List<Entry<String, Long>> gains = new ArrayList<>();
		final Map<String, List<String[]>> named = new LinkedHashMap<>();
		final long[] played = {0, 0};
		HistoryProgress progress;
		Baseline opening;
		Baseline earliest;
		Baseline closing;
		Stand stand;
		HistoryLog.Levels opened;
		boolean live;
		String since;
	}

	Board board()
	{
		Board b = new Board();
		Period w = build.period();
		LocalDate pStart = w.start;
		LocalDate pEnd = w.end;
		boolean live = !pEnd.isBefore(LocalDate.now());
		b.live = live;
		TreeMap<LocalDate, Baseline> hist = build.spine();
		List<JsonObject> feed = build.feed.all();
		SlayerJourney journey = build.journey();

		Entry<LocalDate, Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(hist, pStart, pEnd);
		Entry<LocalDate, Baseline> at = hist.floorEntry(pEnd);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !w.closesOnTheClient(from)))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (hist.firstKey().isBefore(pStart)
				&& ("Day".equals(w.granularity) || "Week".equals(w.granularity)))
			{
				empty = "The imported past resolves by month. Switch to Month "
					+ "or Year to read this era. Daily detail begins with the plugin.";
			}
			else
			{
				empty = "Nothing recorded in this period.";
			}
			b.empty = empty;
			return b;
		}
		if (before == null)
		{
			b.measuredSince = "Measured since " + from.getKey().format(FULL_DAY)
				+ ", the earliest baseline on record.";
		}
		else if (before.getKey().isBefore(pStart.minusDays(1)))
		{
			b.measuredSince = "Measured since " + before.getKey().format(FULL_DAY)
				+ ", the nearest earlier baseline.";
		}
		Baseline earliest = HistoryLog.earliest(hist, at.getKey());
		Baseline closing = HistoryLog.stateAt(hist, at.getKey());
		Baseline opening = HistoryLog.stateAt(hist, from.getKey());
		Map<String, Long> closesOn = live ? build.closingSkills(closing.skills, true) : closing.skills;
		List<Entry<String, Long>> gains = b.gains;
		for (Entry<String, Long> e : HistoryLog.gained(opening.skills,
			earliest.skills, closesOn, opening.complete).entrySet())
		{
			if (!"overall".equals(e.getKey()))
			{
				gains.add(e);
			}
		}
		if (w.session)
		{
			gains.clear();
			for (ExperienceStatTracker.SkillGain g : build.plugin.sessionSkillXp())
			{
				if (g.skill != null && g.xp > 0)
				{
					gains.add(new AbstractMap.SimpleEntry<>(
						low(g.skill.name()), g.xp));
				}
			}
		}
		gains.sort(Entry.<String, Long>comparingByValue().reversed());

		long fromMs = w.session ? w.ms()[0]
			: startMs(pStart);
		long toMs = w.session ? w.ms()[1]
			: startMs(pEnd.plusDays(1));
		Map<String, Long> fromFeed = new HashMap<>();
		Map<String, List<String[]>> named = b.named;
		long sittings = 0;
		long[] took = {0, 0, 0, 0, 0, 0};
		for (JsonObject e : feed)
		{
			long ts = safeLong(e.get("ts"));
			long filed = filedAt(e);
			if (filed >= fromMs && filed < toMs)
			{
				String type = typeOf(e);
				String key = FEED_SUMMARY_KEYS.get(type);
				if (key != null)
				{
					fromFeed.merge(key, 1L, Long::sum);
					String line = feedName(e);
					if (line != null)
					{
						named.computeIfAbsent(key, k -> new ArrayList<>())
							.add(new String[]{line, DAY.format(Instant.ofEpochMilli(ts))});
					}
				}
				if ("SESSION".equals(type))
				{
					sittings++;
					JsonObject d = obj(e, "data");
					took[0] += safeLong(d.get("drops"));
					took[1] += safeLong(d.get("dropsGp"));
					took[2] += safeLong(d.get("left"));
					took[3] += safeLong(d.get("leftGp"));
					took[4] += safeLong(d.get("leftKills"));
					if (d.has("leftKills"))
					{
						took[5]++;
					}
				}
			}
		}
		Map<String, Long> retro = new HashMap<>();
		boolean sessionsHoldTheFloor = false;
		boolean sessionsSpeak = false;
		if (journey != null)
		{
			long closedN = 0;
			long closedKills = 0;
			for (SlayerTask t : journey.tasks)
			{
				if (closedInside(t, fromMs, toMs))
				{
					closedN++;
					closedKills += t.kills;
				}
			}
			retro.put("slayerTasksCompleted", closedN);
			retro.put("slayerKills", closedKills);
			List<String[]> tasks = closedTaskNames(journey, fromMs, toMs);
			if (!tasks.isEmpty())
			{
				named.put("slayerTasksCompleted", tasks);
			}
		}
		long oldest = oldestTs(feed, false);
		boolean reachesBack = oldest > 0 && oldest < fromMs;
		if (reachesBack)
		{
			for (String key : FEED_SUMMARY_KEYS.values())
			{
				retro.put(key, fromFeed.getOrDefault(key, 0L));
			}
			long rollFrom = build.lootDays().rollFrom();
			LocalStore.LootWindow dated = w.session || rollFrom > 0 && rollFrom <= fromMs
				? build.lootWindow() : null;
			if (dated != null)
			{
				sessionsSpeak = true;
				sessionsHoldTheFloor = true;
				retro.put("dropsReceived", dated.loots);
				retro.put("lootValue", dated.value);
				retro.put("lootLeftCount", dated.left);
				retro.put("lootLeftValue", dated.leftValue);
				retro.put("lootLeftKills", dated.leftKills);
				if (!dated.items.isEmpty())
				{
					named.put("lootValue", itemLines(dated.items, false));
				}
				if (!dated.leftItems.isEmpty())
				{
					named.put("lootLeftCount", itemLines(dated.leftItems, false));
				}
				if (!dated.sources.isEmpty())
				{
					named.put("dropsReceived", itemLines(dated.sources, true));
				}
			}
			else if (sittingsCover(feed, fromMs) && sittings > 0 && took[0] > 0)
			{
				sessionsSpeak = true;
				retro.put("dropsReceived", took[0]);
				retro.put("lootValue", took[1]);
				retro.put("lootLeftCount", took[2]);
				retro.put("lootLeftValue", took[3]);
				retro.put("lootLeftKills", took[4]);
				sessionsHoldTheFloor = took[5] == sittings;
			}
		}
		else
		{
			if (!w.whole)
			{
				for (String key : FEED_SUMMARY_KEYS.values())
				{
					named.remove(key);
				}
			}
		}

		System.arraycopy(played(), 0, b.played, 0, 2);

		LocalDate leftFrom = HistoryLog.firstCarrying(
			hist.headMap(at.getKey(), true), "lootLeftKills");
		boolean leftDated = sessionsSpeak
			? sessionsHoldTheFloor
			: (leftFrom != null && !leftFrom.isAfter(from.getKey()));
		boolean whole = w.whole;
		HistoryProgress progress = HistoryProgress.of(
			whole ? build.withLedgerSpend(closing.counters)
				: HistoryLog.gained(opening.counters, earliest.counters,
					closing.counters),
			null, whole ? new HashMap<>() : retro, leftDated || whole);
		Baseline sittingOpen = null;
		if (w.session)
		{
			Map<String, Long> openXp = new HashMap<>(closesOn);
			for (ExperienceStatTracker.SkillGain g : build.plugin.sessionSkillXp())
			{
				if (g.skill == null || g.xp <= 0)
				{
					continue;
				}
				String key = low(g.skill.name());
				Long had = openXp.get(key);
				if (had != null)
				{
					openXp.put(key, Math.max(0, had - g.xp));
				}
			}
			sittingOpen = baselineAt(openXp);
			closing = baselineAt(closesOn);
		}
		Stand stand = stand(closing, live);
		HistoryLog.Levels opened = sittingOpen != null
			? HistoryLog.levels(sittingOpen, stand.keys)
			: HistoryLog.levels(opening, stand.keys);
		LocalDate lootSince = null;
		long lootFromTs = earliestDatedLoot(feed, build.lootDays().rollFrom());
		if (lootFromTs > 0)
		{
			LocalDate sat = dayOf(lootFromTs);
			if (sat.isAfter(pStart))
			{
				lootSince = sat;
			}
		}
		b.since = build.period().whole ? null
			: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
				lootSince, lootFromTs > 0);
		b.progress = progress;
		b.opening = opening;
		b.earliest = earliest;
		b.closing = closing;
		b.stand = stand;
		b.opened = opened;
		return b;
	}

	private static String countersSince(
		SortedMap<LocalDate, Baseline> spine,
		LocalDate startLine, LocalDate lootFrom, boolean lootFromSittings)
	{
		LocalDate counters = HistoryLog.firstCarrying(spine, null);
		LocalDate loot = lootFromSittings
			? lootFrom : HistoryLog.firstCarrying(spine, "dropsReceived");
		StringBuilder note = new StringBuilder();
		LocalDate since = startLine;
		if (counters != null && (since == null || counters.isAfter(since)))
		{
			note.append("Counters since ").append(counters.format(FULL_DAY));
			since = counters;
		}
		if (loot != null && (lootFromSittings || since == null || loot.isAfter(since)))
		{
			String what = lootFromSittings ? "loot" : "loot and kills";
			note.append(note.length() == 0
				? Text.capital(what) + " since "
				: " · " + what + " since ")
				.append(loot.format(FULL_DAY));
		}
		return note.length() == 0 ? null : note.toString();
	}

	private static boolean closedInside(SlayerTask t, long fromMs, long toMs)
	{
		long ms = (long) (t.ts * 1000);
		return !t.inProgress && ms >= fromMs && ms < toMs;
	}

	private static List<String[]> closedTaskNames(SlayerJourney j,
		long fromMs, long toMs)
	{
		List<SlayerTask> closed = new ArrayList<>();
		for (SlayerTask t : j.tasks)
		{
			if (closedInside(t, fromMs, toMs))
			{
				closed.add(t);
			}
		}
		closed.sort((a, b) -> Double.compare(b.ts, a.ts));
		List<String[]> out = new ArrayList<>(closed.size());
		for (SlayerTask t : closed)
		{
			long ms = (long) (t.ts * 1000);
			out.add(new String[]{t.task, fmt(t.kills) + " · "
				+ DAY.format(Instant.ofEpochMilli(ms))});
		}
		return out;
	}

	private static final Map<String, String> FEED_SUMMARY_KEYS = strMap(Sources.KINDS, "feedSummaryKeys");

	private static List<String[]> itemLines(List<LocalStore.Row> ranked, boolean always)
	{
		List<String[]> out = new ArrayList<>();
		for (LocalStore.Row r : ranked)
		{
			out.add(new String[]{r.name, fmt(r.qty) + (always ? " · " + gps(r.value) : tail(r.value))});
		}
		return out;
	}

	@RequiredArgsConstructor
	static final class Stand
	{
		final List<Skill> order;
		final List<String> keys;
		final Map<Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;
	}

	static Baseline baselineAt(Map<String, Long> xp)
	{
		Baseline at = new Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	Stand stand(Baseline closing, boolean live)
	{
		Map<String, long[]> sheet = live ? build.plugin.skillSheet() : Collections.emptyMap();
		List<Skill> order = Sources.skillOrder();
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
			long shown = build.period().whole && cur != null && cur.length > 1 && cur[1] > 0
				? PaceBook.virtualLevelAt(cur[1]) : level;
			levels.put(sk, Math.max(level, shown));
		}
		long[] ov = sheet.get("overall");
		return new Stand(order, keys, levels,
			ov != null && ov[0] > 0 ? ov[0] : total, closed);
	}
}
