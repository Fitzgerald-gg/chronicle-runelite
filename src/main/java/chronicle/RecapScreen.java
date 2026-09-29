/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Span;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.Period.Window;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.api.Skill;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class RecapScreen extends Screen
{
	private static final String[] FAMILIES = {"Combat", "Skilling", "Living", "Ledger & Roads"};
	private static final int[] ROWS = {8, 10, 6, 10};

	RecapScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	JPanel build()
	{
		JPanel p = column();
		p.add(copyHeaderLater("Recap", take ->
		{
			take.setText("copying");
			copy(take);
		}));
		p.add(plate());
		return p;
	}

	private void copy(JLabel take)
	{
		RecapPicture.Facts facts;
		try
		{
			facts = facts();
		}
		catch (Throwable t)
		{
			reportCopy(take, false);
			return;
		}
		Set<Integer> want = new LinkedHashSet<>();
		facts.bosses.stream().filter(b -> b.sprite > 0 && !ui.art.has(b.sprite)).forEach(b -> want.add(b.sprite));
		want.forEach(id -> ui.art.wear(new JLabel(), id, 22, 22));
		long deadline = System.currentTimeMillis() + 1500;
		Timer wait = new Timer(100, null);
		wait.addActionListener(e ->
		{
			boolean all = true;
			for (int id : want)
			{
				all &= ui.art.has(id);
			}
			if (all || System.currentTimeMillis() > deadline)
			{
				wait.stop();
				reportCopy(take, toClipboard(picture(facts)));
			}
		});
		wait.setInitialDelay(want.isEmpty() ? 0 : 100);
		wait.start();
	}

	private RecapPicture.Facts facts()
	{
		RecapPicture.Facts f = new RecapPicture.Facts();
		f.whole = period.whole();
		f.session = period.session();
		f.title = f.whole ? "The whole record" : board.window().label;
		Span s = f.whole || f.session ? null : board.span();
		skills(f, s);
		bosses(f, s);
		monsters(f, s);
		loot(f);
		slayerAndClues(f);
		trackers(f);
		feats(f);
		tiles(f);
		return f;
	}

	private void skills(RecapPicture.Facts f, Span s)
	{
		Map<String, SkillRow> sheet = plugin.skillSheet();
		Map<String, Long> closing = null;
		if (!f.whole && !f.session)
		{
			if (s == null)
			{
				f.skillsNote = board.notCounting(false) != null ? "The record keeps no experience this far back."
					: "Nothing closed inside " + board.periodInSentence()
						+ ": a period is the distance between two baselines, and this one holds fewer than two.";
				return;
			}
			closing = board.closingSkills(s.closing.skills, board.periodReachesToday());
		}
		Map<String, Integer> startLevels = new LinkedHashMap<>();
		Map<String, Integer> endLevels = new LinkedHashMap<>();
		long startXp = 0;
		long endXp = 0;
		boolean startsKnown = true;
		for (Skill sk : SKILLS)
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
				SkillRow cur = sheet.get(key);
				end = cur != null ? Long.valueOf(cur.xp) : null;
				if (f.session && end != null)
				{
					start = Math.max(0, end - board.skill.sessionXp(key));
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
			f.skills.add(new RecapPicture.SkillLine(sk, prettify(key), lStart, lEnd, start, end));
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
		SkillRow overall = sheet.get("overall");
		long totalEnd = f.whole && overall != null && overall.level > 0 ? overall.level : sum(endLevels);
		f.totalLevel = new Long[]{startsKnown ? sum(startLevels) : null, totalEnd};
		f.totalXp = new Long[]{startsKnown ? startXp : null,
			f.whole && overall != null && overall.xp > 0 ? overall.xp : endXp};
		Integer cEnd = combatOf(endLevels);
		if (f.whole && store.combatLevel() > 0)
		{
			cEnd = store.combatLevel();
		}
		Integer cStart = startsKnown ? combatOf(startLevels) : null;
		f.combat = cEnd == null ? null : new Long[]{cStart == null ? null : (long) cStart, (long) cEnd};
	}

	private static long sum(Map<String, Integer> levels)
	{
		return levels.values().stream().mapToLong(Integer::longValue).sum();
	}

	private BufferedImage picture(RecapPicture.Facts facts)
	{
		try
		{
			return RecapPicture.paint(facts, ui.art::skill, ui.art::sprite);
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	private void bosses(RecapPicture.Facts f, Span s)
	{
		List<Boss> roster = bossRoster(plugin.gson());
		Map<String, Long> closing = s == null ? null : board.closingNow(s.closing.kcs, plugin.killCounts());
		for (Boss b : roster)
		{
			if (f.whole)
			{
				long k = board.kills.bossKills(b.name);
				if (k > 0)
				{
					f.bosses.add(new RecapPicture.BossLine(b.name, b.sprite, null, k, k));
				}
				continue;
			}
			long moved = board.kills.bossKillsInWindow(b.name);
			if (moved <= 0)
			{
				continue;
			}
			Long start = null;
			Long end = null;
			String key = closing == null ? null : keyOf(board.kills.movedKcs(s).keySet(), b.name);
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
		if (board.rollUsed)
		{
			f.notes.add("Kill counts without a start and an end are read from loot, and count only the kills that dropped something.");
		}
		if (f.bosses.isEmpty() && !f.whole)
		{
			f.bossesNote = board.notCounting(true) != null ? "The record keeps no kill counts this far back."
				: board.inside("No boss was killed");
		}
	}

	private void monsters(RecapPicture.Facts f, Span s)
	{
		Map<String, Long> by = new LinkedHashMap<>();
		Map<String, Long> worth = new LinkedHashMap<>();
		if (f.whole)
		{
			by.putAll(plugin.killCounts());
			board.sources().forEach(r -> worth.merge(r.name, r.value, Long::sum));
		}
		else if (f.session)
		{
			for (Tally r : store.loot.sessionLootWindow().sources)
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
				board.closingNow(s.closing.kcs, plugin.killCounts())));
			Window w = board.window();
			worth.putAll(board.loot.periodWorth(w.start, w.end));
		}
		Map<String, Long> loose = LootQuery.loosely(worth);
		List<Entry<String, Long>> kept = new ArrayList<>();
		by.entrySet().stream().filter(e -> e.getValue() > 0 && board.kills.recapMonster(e.getKey())).forEach(kept::add);
		kept.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (Entry<String, Long> e : kept.subList(0, Math.min(10, kept.size())))
		{
			long paid = LootQuery.paidFor(worth, loose, e.getKey());
			f.monsters.add(new RecapPicture.Named(e.getKey(), fmt(e.getValue()), paid > 0 ? gps(paid) : null));
		}
		if (f.session && !f.monsters.isEmpty())
		{
			f.monstersNote = "A sitting counts the kills that dropped something.";
		}
	}

	private void loot(RecapPicture.Facts f)
	{
		if (!f.whole && !f.session)
		{
			long from = store.loot.lootRollFrom();
			if (from <= 0 || from > board.range().from)
			{
				f.lootNote = "Loot is not dated this far back, so this period's cannot be told from the rest.";
				return;
			}
		}
		LootDays.LootWindow loot = board.loot.periodLoot();
		if (loot.loots > 0)
		{
			f.loot.add(new RecapPicture.Named("Drops", fmt(loot.loots), gps(loot.value)));
		}
		if (loot.left > 0)
		{
			f.loot.add(new RecapPicture.Named("Left behind", fmt(loot.left), gps(loot.leftValue)));
		}
		if (f.whole)
		{
			List<SourceRow> rows = new ArrayList<>(board.sources());
			rows.sort((a, b) -> Long.compare(b.value, a.value));
			for (SourceRow r : firstN(rows, 8))
			{
				if (r.value > 0)
				{
					f.sources.add(new RecapPicture.Named(r.name, null, gps(r.value)));
				}
			}
			List<BagItem> bag = new ArrayList<>(store.allLoot());
			bag.sort((a, b) -> Long.compare(b.value, a.value));
			for (BagItem b : firstN(bag, 6))
			{
				if (b.value > 0)
				{
					f.items.add(new RecapPicture.Named(b.name + " ×" + fmt(b.qty), null, gps(b.value)));
				}
			}
			return;
		}
		LootDays.LootWindow win = board.loot.lootWindow();
		firstN(win.sources, 8).stream().filter(r -> r.value > 0)
			.forEach(r -> f.sources.add(new RecapPicture.Named(r.name, null, gps(r.value))));
		firstN(win.items, 6).stream().filter(r -> r.value > 0)
			.forEach(r -> f.items.add(new RecapPicture.Named(r.name + " ×" + fmt(r.qty), null, gps(r.value))));
		if (f.loot.isEmpty())
		{
			f.lootNote = board.inside("Nothing dropped");
		}
	}

	private void slayerAndClues(RecapPicture.Facts f)
	{
		SlayerLog.TaskTally tally = board.loot.taskTally();
		long paid = tally.loot;
		if (tally.tasks > 0)
		{
			f.slayer.add(new RecapPicture.Named("Tasks", fmt(tally.tasks), null));
			if (tally.superiors > 0)
			{
				f.slayer.add(new RecapPicture.Named("Superiors", fmt(tally.superiors), null));
			}
			if (paid > 0)
			{
				f.slayer.add(new RecapPicture.Named("On-task loot", null, gps(paid)));
			}
		}
		for (String tier : CLUE_TIERS)
		{
			String source = "Clue Scroll (" + tier + ")";
			long n = 0;
			long v = 0;
			SourceRow r = board.kills.clue(tier);
			if (f.whole && r != null)
			{
				n = Math.max(r.kc, r.loots);
				v = r.value;
			}
			else if (!f.whole)
			{
				n = board.loot.rolled(source);
				v = board.loot.sourceInWindow(source).value;
			}
			if (n > 0)
			{
				f.clues.add(new RecapPicture.Named(tier, fmt(n), v > 0 ? gps(v) : null));
			}
		}
	}

	private void trackers(RecapPicture.Facts f)
	{
		Map<String, Long> counters = board.counts.countersForPeriod();
		if (counters == null)
		{
			f.trackersNote = board.notCounting(false) != null ? "The record keeps no counters this far back."
				: board.inside("Nothing closed");
			return;
		}
		for (int fi = 0; fi < FAMILIES.length; fi++)
		{
			String family = FAMILIES[fi];
			List<String> order = StatRegistry.fixedSections(family);
			List<Entry<String, Long>> rows = new ArrayList<>();
			for (Entry<String, Long> e : counters.entrySet())
			{
				String key = e.getKey();
				if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(key)
					|| !family.equals(StatRegistry.family(key)) || StatRegistry.typed(key)
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
			for (Entry<String, Long> e : rows.subList(0, Math.min(ROWS[fi], rows.size())))
			{
				String key = e.getKey();
				boolean money = StatRegistry.isGp(key);
				out.add(new RecapPicture.Named(StatRegistry.label(key),
					money ? null : fmt(e.getValue()), money ? gps(e.getValue()) : null));
			}
			if (!out.isEmpty())
			{
				f.trackers.put(family, out);
			}
		}
	}

	private void feats(RecapPicture.Facts f)
	{
		Map<String, List<String>> named = new LinkedHashMap<>();
		for (String k : new String[]{"Milestones", "Collection log", "Pets", "Personal bests",
			"Quests", "Diaries", "Combat achievements", "Deaths"})
		{
			named.put(k, new ArrayList<>());
		}
		for (JsonObject m : board.days.milestones())
		{
			if (board.insideWindow(asLong(m.get("ts"))) && m.has("data"))
			{
				named.get("Milestones").add(m.getAsJsonObject("data").get("text").getAsString());
			}
		}
		Set<String> bests = new HashSet<>();
		for (JsonObject e : store.feedNewest(20_000))
		{
			if (!board.insideWindow(asLong(e.get("ts"))))
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
			f.skills.stream().filter(l -> l.levelStart != null && l.levelStart < l.levelEnd)
				.forEach(l -> out.add(l.name + " " + l.levelEnd));
			if (!out.isEmpty())
			{
				f.feats.put("Levels", out);
			}
		}
		if (f.whole && plugin.clogAvailable() > 0)
		{
			f.feats.put("Collection log", Collections.singletonList(
				fmt(plugin.clogFinished()) + " of " + fmt(plugin.clogAvailable()) + " slots"));
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

	private void tiles(RecapPicture.Facts f)
	{
		DayQuery.Sittings sat = board.days.sittingsInWindow();
		long minutes = sat.minutes;
		int sittings = (int) sat.count;
		long games = f.whole ? plugin.gamePlaytimeMinutes() : 0;
		if (games > minutes)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(games), "the game's own count"));
		}
		else if (sittings > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(minutes), count(sittings, "sitting")));
		}
		Long xp = board.skill.periodXp();
		if (xp != null && xp > 0)
		{
			String most = board.skill.periodXpMost();
			f.tiles.add(new RecapPicture.Tile("Experience", (f.whole ? "" : "+") + gp(xp),
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
				f.tiles.add(new RecapPicture.Tile("Levels", "+" + fmt(b - a), climb(a, b) + " total"));
			}
		}
		for (RecapPicture.Named n : f.loot)
		{
			if ("Drops".equals(n.name))
			{
				f.tiles.add(new RecapPicture.Tile("Loot", n.gp, n.figure + " drops"));
			}
		}
		long bossKills = f.bosses.stream().mapToLong(b -> b.gained).sum();
		RecapPicture.BossLine top = most(f.bosses, b -> b.gained);
		if (bossKills > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Boss kills", (f.whole ? "" : "+") + fmt(bossKills), "most " + top.name));
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
		if (f.whole && plugin.clogAvailable() > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Collection log", fmt(plugin.clogFinished()),
				"of " + fmt(plugin.clogAvailable())));
		}
		else if (!f.whole && slots != null)
		{
			f.tiles.add(new RecapPicture.Tile("Log slots", "+" + fmt(slots.size()), "latest " + slots.get(0)));
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

	private JPanel plate()
	{
		JPanel plate = card(period.whole() ? "The whole record" : board.window().label);
		int held = plate.getComponentCount();

		DayQuery.Sittings sat = board.days.sittingsInWindow();
		long minutes = sat.minutes;
		int sittings = (int) sat.count;
		if (sittings > 0)
		{
			line(plate, "Played", hoursMinutes(minutes) + " · " + count(sittings, "sitting"),
				() -> ui.openJournal("Sessions"));
		}
		if (sat.busiest != null && sittings > 1)
		{
			LocalDate day = sat.busiest;
			line(plate, "Busiest day", TASK_DAY.format(day.atStartOfDay(
				ZoneId.systemDefault()).toInstant()) + " · " + hoursMinutes(sat.busiestMinutes),
				() -> ui.openJournalOn(noon(day)));
		}

		Long xp = board.skill.periodXp();
		if (xp != null && xp > 0)
		{
			String most = board.skill.periodXpMost();
			line(plate, period.whole() ? "Xp" : "Xp gained", (period.whole() ? "" : "+") + gp(xp) + " xp"
					+ (most != null ? ", most in " + most : ""),
				() -> ui.show(ChroniclePanel.View.STANDING));
		}
		SkillQuery.Climb levels = board.skill.periodLevels();
		if (levels != null)
		{
			line(plate, period.whole() ? "Total level" : "Levels",
				period.whole() ? fmt(levels.gained) : "+" + fmt(levels.gained) + " · " + fmt(levels.now) + " now",
				() -> ui.show(ChroniclePanel.View.STANDING));
		}

		LootDays.LootWindow loot = board.loot.periodLoot();
		if (loot.loots > 0)
		{
			line(plate, "Drops", qtyGp(loot.loots, loot.value), () -> ui.show(ChroniclePanel.View.LOOT));
		}
		Tally dearest = board.loot.periodDearest();
		if (dearest != null)
		{
			Line said = new Line();
			said.name(dearest.name);
			said.fixed(" · " + gps(dearest.value));
			line(plate, "Dearest drop", said, () -> ui.openItem(dearest.name));
		}
		if (loot.left > 0)
		{
			line(plate, "Left behind", qtyGp(loot.left, loot.leftValue), () -> ui.openLeftBehind(null));
		}

		Map<String, Long> counters = period.whole() ? board.counts.withLedgerSpend(board.counts.counters()) : board.counts.periodCounters();
		Runnable toLiving = () -> ui.openLedger("Living");
		long food = counters.getOrDefault("foodEaten", 0L);
		if (food > 0)
		{
			line(plate, "Food", fmt(food)
				+ spend("foodConsumedValue", counters), toLiving);
		}
		long doses = counters.getOrDefault("potionDoses", 0L);
		if (doses > 0)
		{
			line(plate, "Potions", count(doses, "dose")
				+ spend("potionsConsumedValue", counters), toLiving);
		}

		Tally killed = board.kills.periodKilledMost();
		if (killed != null)
		{
			Line said = new Line();
			said.name(killed.name);
			said.fixed(" · " + fmt(killed.qty));
			line(plate, "Killed most", said, () -> ui.openSourceLoose(killed.name));
		}
		SlayerLog.TaskTally tally = board.loot.taskTally();
		if (tally.tasks > 0)
		{
			line(plate, "Tasks", fmt(tally.tasks) + tail(tally.loot), () -> ui.openSlayer("Tasks"));
		}

		Map<String, String> firstNamed = new LinkedHashMap<>();
		for (JsonObject e : store.feedNewest(Board.FEED_SCAN_DEEP))
		{
			String name = feedName(e);
			if (name != null && board.insideWindow(asLong(e.get("ts"))))
			{
				firstNamed.putIfAbsent(typeOf(e), name);
			}
		}
		feedLine(plate, firstNamed, "COLLECTION", "Log slot", "Log slots", "Log");
		feedLine(plate, firstNamed, "PET", "Pet", "Pets", "Feats");
		feedLine(plate, firstNamed, "QUEST", "Quest", "Quests", "Feats");
		feedLine(plate, firstNamed, "DIARY", "Diary", "Diaries", "Feats");
		feedLine(plate, firstNamed, "COMBAT_ACHIEVEMENT", "Combat achievement", "Combat achievements", "Feats");
		feedLine(plate, firstNamed, "DEATH", "Death", "Deaths", "Deaths");

		if (plate.getComponentCount() == held)
		{
			plate.add(note(period.whole() ? "Nothing on the record yet." : board.inside("Nothing")));
		}
		return plate;
	}

	private String spend(String key, Map<String, Long> counters)
	{
		Span s = board.span();
		if (!period.whole() && !period.session() && (s == null || !s.opening.counters.containsKey(key)))
		{
			return "";
		}
		long spend = counters.getOrDefault(key, 0L);
		return tail(spend);
	}

	private void line(JPanel plate, String left, String right, Runnable go)
	{
		JPanel r = row(left, right);
		link(r, go);
		plate.add(r);
	}

	private void line(JPanel plate, String left, Line right, Runnable go)
	{
		String whole = right.whole();
		String fitted = fitLine(right, right.names, NAME_FLOOR, rowMetrics(), chaseRoom(left, rowMetrics()));
		line(plate, left, fitted != null ? fitted : whole, go);
		if (fitted != null && !fitted.equals(whole))
		{
			((JPanel) plate.getComponent(plate.getComponentCount() - 1))
				.setToolTipText(left + ": " + whole);
		}
	}

	private void feedLine(JPanel plate, Map<String, String> named, String type, String one, String many, String lens)
	{
		long n = board.days.stirred(type);
		if (n == 0)
		{
			return;
		}
		String name = named.get(type);
		Line right = new Line();
		right.fixed(fmt(n));
		if (name != null)
		{
			right.fixed(" · ");
			right.name(name);
		}
		line(plate, n == 1 ? one : many, right, () -> ui.openJournal(lens));
	}
}
