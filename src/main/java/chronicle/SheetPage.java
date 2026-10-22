/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.SourceRow;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.hiscore.HiscoreSkill;
import static chronicle.ChroniclePanel.ACTIVITIES;
import static chronicle.ChroniclePanel.ICON_H;
import static chronicle.ChroniclePanel.ICON_W;
import static chronicle.Feed.newestTs;
import static chronicle.Json.has;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.ACCENT_SESSION;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.TILE_LIT;
import static chronicle.Kit.TIP_CLOSE;
import static chronicle.Kit.TIP_OPEN;
import static chronicle.Kit.card;
import static chronicle.Kit.clip;
import static chronicle.Kit.column;
import static chronicle.Kit.dim;
import static chronicle.Kit.dimLine;
import static chronicle.Kit.figure;
import static chronicle.Kit.grid3;
import static chronicle.Kit.levelTile;
import static chronicle.Kit.link;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.tile;
import static chronicle.Kit.tip;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.FULL_DAY;
import static chronicle.Text.climb;
import static chronicle.Text.count;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.named;
import static chronicle.Text.pb;
import static chronicle.Text.qtyGp;
import static chronicle.Text.share;
import static chronicle.Text.tail;
import static chronicle.Text.xpShort;
import static chronicle.panel.StatRegistry.prettify;

final class SheetPage
{
	private final ChroniclePanel panel;

	SheetPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildSheet()
	{
		JPanel p = column();
		p.add(buildHistory());
		p.add(activitySheet());
		p.add(buildKills());
		return p;
	}

	private static int activitySprite(String label)
	{
		switch (label)
		{
			case "Clues":
				return HiscoreSkill.CLUE_SCROLL_ALL.getSpriteId();
			case "Rifts closed":
				return HiscoreSkill.RIFTS_CLOSED.getSpriteId();
			case "Soul Wars":
				return HiscoreSkill.SOUL_WARS_ZEAL.getSpriteId();
			case "Collections":
				return HiscoreSkill.COLLECTIONS_LOGGED.getSpriteId();
			case "Quests":
				return SpriteID.TAB_QUESTS;
			case "Diaries":
				return SpriteID.TAB_QUESTS_GREEN_ACHIEVEMENT_DIARIES;
			default:
				return 0;
		}
	}

	private JPanel activitySheet()
	{
		JPanel p = column();
		JPanel grid = grid3();
		for (String[] a : ACTIVITIES)
		{
			grid.add(activityCell(a[0], a[1], a[2]));
		}
		spaced(p, grid);
		return p;
	}

	private JPanel activityCell(String label, String source, String page)
	{
		JPanel cell = tile(3, 3);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 24));
		long figure;
		String hover;
		if ("Clues".equals(label))
		{
			long[] each = new long[Standings.CLUE_TIERS.length];
			long[] worth = new long[Standings.CLUE_TIERS.length];
			long all = 0;
			long allWorth = 0;
			for (int i = 0; i < Standings.CLUE_TIERS.length; i++)
			{
				SourceRow r = panel.build.standings.clue(Standings.CLUE_TIERS[i]);
				if (r != null)
				{
					each[i] = Standings.caskets(r);
					worth[i] = r.value;
					all += each[i];
					allWorth += r.value;
				}
			}
			figure = all;
			List<String> lines = new ArrayList<>(Arrays.asList("All", fmt(all) + tail(allWorth)));
			for (int i = 0; i < Standings.CLUE_TIERS.length; i++)
			{
				lines.add(Standings.CLUE_TIERS[i]);
				lines.add(each[i] == 0 ? "0" : fmt(each[i]) + tail(worth[i]));
			}
			hover = tip("Clues", lines);
		}
		else if ("Collections".equals(label))
		{
			figure = panel.live.clogFinished();
			int[] logStanding = panel.clogStanding();
			hover = tip("Collection log",
				"Obtained", fmt(figure),
				"Available", logStanding != null ? fmt(logStanding[1]) : "not yet",
				"Share", logStanding != null ? share(logStanding[0], logStanding[1]) : "-");
		}
		else if ("Quests".equals(label))
		{
			Standings.Quests q = panel.build.standings.quests();
			figure = q.done.size();
			hover = tip("Quests",
				"Complete", fmt(q.done.size()),
				"In progress", fmt(q.going.size()),
				"Known", fmt(q.known));
		}
		else if ("Diaries".equals(label))
		{
			long[] d = panel.build.standings.diaries();
			figure = d[0];
			hover = tip("Achievement diaries",
				"Tiers done", d[0] + " / " + d[1],
				"Regions finished", fmt(d[2]),
				"Regions", fmt(d[3]));
		}
		else
		{
			long named = panel.build.kills.namedLine(source, label);
			figure = named > 0 ? named : panel.build.kills.bossKills(source);
			hover = tip(label, "Count", fmt(figure));
		}
		panel.art.wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		cell.setToolTipText(hover);
		if (!page.isEmpty())
		{
			final String to = page;
			link(cell, () ->
			{
				panel.sheetPage = to;
				panel.rebuild();
			});
		}
		else if (!source.isEmpty())
		{
			link(cell, () -> panel.openActivity(source));
		}
		cell.add(icon, BorderLayout.WEST);
		long moved = activityMoved(label, source);
		boolean lit = figure > 0 && moved != 0;
		JLabel fig = styled(new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT), small(),
			lit ? TILE_LIT : dim());
		JPanel text = new JPanel(new GridLayout(moved > 0 ? 2 : 1, 1));
		text.setBackground(DARKER);
		text.add(fig);
		if (moved > 0)
		{
			text.add(styled(new JLabel("+" + fmt(moved), JLabel.RIGHT), small(), panel.accent()));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private JPanel buildKills()
	{
		JPanel p = column();
		panel.build.kills.forgetWindow();
		List<Kills.Boss> roster = Kills.bossRoster(panel.gson);
		if (roster.isEmpty())
		{
			return noted(p, "The boss roster did not load.");
		}
		if (!panel.wholeRecord() && !panel.sessionPeriod() && panel.build.span() == null)
		{
			p.add(panel.noPeriod());
			return p;
		}
		if (!panel.wholeRecord())
		{
			List<Kills.Boss> had = new ArrayList<>();
			for (Kills.Boss b : roster)
			{
				if (panel.build.kills.bossKillsInWindow(b.name) > 0)
				{
					had.add(b);
				}
			}
			if (had.isEmpty())
			{
				String unkept = panel.build.notCounting(true);
				return noted(p, unkept != null ? unkept : panel.window().phrase("Nothing on the boss sheet was killed"));
			}
			roster = had;
		}
		JPanel grid = grid3();
		for (Kills.Boss b : roster)
		{
			grid.add(bossCell(b));
		}
		LocalDate shortFrom = panel.build.kills.rollUsed() ? panel.build.kills.rollShortOf() : null;
		if (shortFrom != null)
		{
			spaced(p, note("Kills the journal cannot date are counted from loot "
				+ "instead, which reaches back only to " + shortFrom.format(FULL_DAY)
				+ " and sees a kill only where it dropped something."), 4);
		}
		spaced(p, grid);
		return p;
	}

	private JPanel bossCell(Kills.Boss b)
	{
		final long kc = panel.build.kills.bossKillsInWindow(b.name);
		JPanel cell = tile(3, 3);
		cell.setToolTipText(bossTip(b));

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			panel.art.wearSprite(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(),
			kc > 0 ? TILE_LIT : dim());
		cell.add(fig, BorderLayout.EAST);
		final String open = panel.build.kills.bossLootSource(b);
		link(cell, () -> panel.openSourceLoose(open));
		return cell;
	}

	private String bossTip(Kills.Boss b)
	{
		List<String> lines = new ArrayList<>();
		String kind = kindOf(b.name);
		SourceRow src = null;
		List<SourceRow> paidOut = new ArrayList<>();
		for (SourceRow r : panel.build.sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				if (src == null)
				{
					src = r;
				}
				continue;
			}
			if (Kills.namesInBrackets(r.name, b.name)
				|| Kills.paysOutThrough(b.name, r.name))
			{
				paidOut.add(r);
			}
		}
		LocalStore.Row dated = panel.build.summary.source(b.name);
		if (!panel.wholeRecord())
		{
			long inWin = panel.build.kills.bossKillsInWindow(b.name);
			lines.add(panel.window().label);
			lines.add((inWin < 0 ? "-" : count(inWin, "kill"))
				+ tail(dated.value));
		}
		long known = panel.build.kills.bossKills(b.name);
		lines.add("Kills tracked");
		lines.add(known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-");
		for (Entry<String, Long> pb : panel.build.kills.pageLines(b.name, "pb_lines"))
		{
			lines.add(pb.getKey());
			lines.add(pb(pb.getValue()));
		}
		double[] timed = panel.wholeRecord() && src != null ? new double[]{src.timed, src.timeSum}
			: new double[]{dated.timed, dated.timeSum};
		if (timed[0] > 0)
		{
			lines.add("Average kill");
			lines.add(pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed");
		}
		long here = panel.build.kills.minutesAt(b.name, panel.wholeRecord() ? panel.build.lifetime() : panel.build.periodCounters());
		if (here > 0 && panel.build.kills.minutesCoverPeriod())
		{
			lines.add("Time here");
			lines.add(hoursMinutes(here));
		}
		for (Entry<String, Long> ln : panel.build.kills.logLines(b.name))
		{
			lines.add(ln.getKey());
			lines.add(fmt(ln.getValue()));
		}
		if (src != null)
		{
			lines.add("Drops");
			lines.add(paidFigure(src));
		}
		for (SourceRow r : paidOut)
		{
			lines.add(Kills.beforeBracket(r.name));
			lines.add(paidFigure(r));
		}
		if (src == null && paidOut.isEmpty())
		{
			lines.add("Loot");
			lines.add("none yet");
		}
		return tip(b.name, lines);
	}

	private String paidFigure(SourceRow r)
	{
		return qtyGp(LocalStore.tallyOf(panel.store.sourceItems(r.name))[0], r.value);
	}

	private void addSkillGrid(JPanel p, List<Entry<String, Long>> gains, Summary.Stand stand,
		HistoryLog.Levels opened)
	{
		Map<String, Long> gain = new LinkedHashMap<>();
		for (Entry<String, Long> g : gains)
		{
			gain.put(g.getKey(), g.getValue());
		}
		List<Skill> order = stand.order;
		Map<Skill, Long> levels = stand.levels;

		JPanel grid = grid3();
		for (Skill sk : order)
		{
			String key = low(sk.name());
			Integer was = opened == null ? null
				: opened.virtual.getOrDefault(key, opened.of.get(key));
			Long from = was == null ? null : Long.valueOf(was.longValue());
			grid.add(skillCell(sk, levels.get(sk), gain.get(key), from));
		}
		spaced(p, grid, 3);
		JPanel combat = combatLevelTile(gain, opened);
		JPanel total = totalLevelTile(stand, opened);
		total.setToolTipText(panel.periodTip != null ? panel.periodTip.replace("</body>", dimLine("Opens the records") + "</body>")
			: tip("Total level", "Opens", "the records"));
		link(total, panel::openRecords);
		if (panel.wholeRecord())
		{
			JPanel levels2 = new JPanel(new GridLayout(1, 2, 2, 2));
			levels2.setBackground(DARK);
			levels2.setAlignmentX(Component.LEFT_ALIGNMENT);
			levels2.add(combat);
			levels2.add(total);
			p.add(levels2);
		}
		else
		{
			spaced(p, combat, 2);
			p.add(total);
		}
		p.add(vgap(6));
	}

	private JPanel combatLevelTile(Map<String, Long> gain, HistoryLog.Levels opened)
	{
		JPanel cell = levelTile("Combat");
		int cb = panel.store.combatLevel();
		Integer was = HistoryLog.combat(opened);
		boolean climbed = !panel.wholeRecord() && was != null && cb > was;
		JLabel fig = new JLabel(cb > 0 ? (climbed ? climb(was, cb) : fmt(cb)) : "-",
			JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(cb > 0 && (panel.wholeRecord() || combatSkillsMoved(gain))
			? TILE_LIT : dim());
		cell.add(fig, BorderLayout.EAST);
		Map<String, Long> c = panel.build.lifetime();
		long[] ca = panel.build.standings.combat();
		cell.setToolTipText(tip("Combat",
			"Achievement points", ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
			"Tiers unlocked", fmt(ca[2]) + " / 6",
			"Damage dealt", fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
			"Highest hit", fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))));
		link(cell, () ->
		{
			panel.sheetPage = "combat";
			panel.rebuild();
		});
		return cell;
	}

	private long activityMoved(String label, String source)
	{
		if (panel.wholeRecord())
		{
			return -1;
		}
		if ("Collections".equals(label))
		{
			return panel.build.feed.inWindow("COLLECTION");
		}
		if ("Quests".equals(label))
		{
			return panel.build.feed.inWindow("QUEST");
		}
		if ("Diaries".equals(label))
		{
			return panel.build.feed.inWindow("DIARY");
		}
		if ("Clues".equals(label))
		{
			long all = 0;
			for (String tier : Standings.CLUE_TIERS)
			{
				all += panel.build.kills.rolled("Clue Scroll (" + tier + ")");
			}
			return all;
		}
		if (!source.isEmpty())
		{
			long rolled = panel.build.kills.rolled(source);
			return rolled > 0 && panel.build.kills.namedLine(source, label) > 0 ? -1 : rolled;
		}
		return 0;
	}

	private String skillTip(String craft)
	{
		Map<String, Long> now = panel.build.periodCounters();
		List<String> floors = new ArrayList<>();
		List<Entry<String, Long>> named = new ArrayList<>();
		for (String key : StatRegistry.headlines(craft))
		{
			Long v = now.get(key);
			if (v == null || v <= 0)
			{
				continue;
			}
			if (StatRegistry.isFloor(key))
			{
				floors.add(key);
			}
			else
			{
				named.add(new AbstractMap.SimpleEntry<>(key, v));
			}
		}
		named.sort(Entry.<String, Long>comparingByValue().reversed());
		List<String> lines = new ArrayList<>();
		for (String key : floors)
		{
			lines.add(StatRegistry.rowLabel(key));
			lines.add(fmt(now.get(key)));
		}
		for (Entry<String, Long> e : named)
		{
			if (lines.size() >= 12)
			{
				break;
			}
			lines.add(StatRegistry.rowLabel(e.getKey()));
			lines.add(fmt(e.getValue()));
		}
		return tip(craft, lines);
	}

	private String slayerTip()
	{
		long[] tally = panel.build.taskTally();
		return tip("Slayer",
			"Tasks tracked", fmt(tally[2]),
			"Kills on task", fmt(tally[0]),
			"On-task loot", gps(tally[3]));
	}

	private static boolean combatSkillsMoved(Map<String, Long> gain)
	{
		for (Skill sk : HistoryLog.COMBAT_SKILLS)
		{
			Long g = gain.get(low(sk.name()));
			if (g != null && g > 0)
			{
				return true;
			}
		}
		return false;
	}

	JPanel buildSheetPage()
	{
		JPanel p = panel.backPage();
		if ("log".equals(panel.sheetPage))
		{
			p.add(panel.clog.buildLog());
		}
		else if ("clues".equals(panel.sheetPage))
		{
			buildClues(p);
		}
		else if ("quests".equals(panel.sheetPage))
		{
			buildQuests(p);
		}
		else if ("diaries".equals(panel.sheetPage))
		{
			buildDiaries(p);
		}
		else
		{
			buildCombatAchievements(p);
		}
		return p;
	}

	private void buildClues(JPanel p)
	{
		long all = 0;
		long allWorth = 0;
		List<SourceRow> mine = new ArrayList<>();
		for (String tier : Standings.CLUE_TIERS)
		{
			SourceRow r = panel.build.standings.clue(tier);
			if (r != null)
			{
				mine.add(r);
				all += Standings.caskets(r);
				allWorth += r.value;
			}
		}
		JPanel head = card("Clues");
		head.add(row("Caskets opened", fmt(all), panel.accent()));
		head.add(row("Worth", gps(allWorth), panel.accent()));
		head.add(row("Tiers seen", fmt(mine.size()) + " / " + Standings.CLUE_TIERS.length));
		spaced(p, head);
		if (mine.isEmpty())
		{
			p.add(note("No clue casket has been opened while Chronicle was watching."));
			return;
		}
		p.add(panel.group("BY TIER"));
		for (String tier : Standings.CLUE_TIERS)
		{
			SourceRow r = panel.build.standings.clue(tier);
			if (r == null)
			{
				p.add(row(tier, "-", dim(), true));
				continue;
			}
			long n = Standings.caskets(r);
			JPanel line = row(tier, qtyGp(n, r.value), panel.accent());
			final String open = r.name;
			link(line, () -> panel.openSource(open));
			line.setToolTipText(tip(tier + " clues",
				"Caskets", fmt(n),
				"Worth", gps(r.value),
				"Each", n > 0 ? gps(r.value / n) : "-"));
			p.add(line);
		}
	}

	private void buildQuests(JPanel p)
	{
		Standings.Quests q = panel.build.standings.quests();
		if (q.known == 0)
		{
			p.add(note("The quest list arrives when you next log in."));
			return;
		}
		JPanel head = card("Quests");
		head.add(row("Complete", fmt(q.done.size()) + " / " + fmt(q.known), panel.accent()));
		head.add(row("In progress", fmt(q.going.size())));
		head.add(row("Not started", fmt(q.not.size())));
		spaced(p, head);
		addNames(p, "IN PROGRESS", q.going, true, true);
		addNames(p, "COMPLETE", q.done, true, false);
		addNames(p, "NOT STARTED", q.not, false, false);
	}

	private void addNames(JPanel p, String heading, List<String> names, boolean held,
		boolean openByDefault)
	{
		if (names.isEmpty())
		{
			return;
		}
		Collections.sort(names);
		String foldKey = "quests:" + heading;
		boolean open = panel.foldOpen(foldKey, openByDefault);
		p.add(panel.quietHead(heading, fmt(names.size()), foldKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		for (String n : names)
		{
			p.add(row(n, "", held ? null : dim(), !held));
		}
		p.add(vgap(4));
	}

	private void buildDiaries(JPanel p)
	{
		JsonObject tasks = Standings.DIARY_TASKS;
		JsonObject mine = obj(panel.build.standings.achievements(), "diaries");
		boolean known = mine.size() > 0;
		JPanel head = card("Achievement diaries");
		if (known)
		{
			long[] d = panel.build.standings.diaries();
			head.add(row("Tiers done", d[0] + " / " + d[1], panel.accent()));
			head.add(row("Regions finished", fmt(d[2]) + " / " + fmt(d[3])));
		}
		spaced(p, head);
		if (!known)
		{
			spaced(p, note("Which tiers you have finished arrives when you next log in. "
				+ "Until then this is what each one asks for."), 4);
		}
		for (String region : tasks.keySet())
		{
			JsonObject tiers = tasks.getAsJsonObject(region);
			JsonObject held = panel.build.standings.diaryHeld(region);
			p.add(panel.group(region.toUpperCase(Locale.ROOT)));
			for (String tier : new String[]{"easy", "medium", "hard", "elite"})
			{
				if (!tiers.has(tier))
				{
					continue;
				}
				int n = tiers.getAsJsonArray(tier).size();
				boolean got = held != null && held.has(tier) && held.get(tier).getAsBoolean();
				JPanel line = row(Text.capital(tier),
					fmt(n) + " tasks",
					known && !got ? dim() : null,
					known && !got);
				line.setToolTipText(taskTip(region + " " + tier,
					tiers.getAsJsonArray(tier)));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String taskTip(String title, JsonArray tasks)
	{
		final int cap = 8;
		StringBuilder sb = new StringBuilder(TIP_OPEN).append(dimLine(title));
		for (int i = 0; i < tasks.size() && i < cap; i++)
		{
			JsonObject t = tasks.get(i).getAsJsonObject();
			sb.append("<div>").append(clip(t.get("task").getAsString(), 78)).append("</div>");
			String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
			if (!needs.isEmpty())
			{
				sb.append(dimLine("&nbsp;&nbsp;" + clip(needs, 70)));
			}
		}
		if (tasks.size() > cap)
		{
			sb.append(dimLine("and " + (tasks.size() - cap) + " more"));
		}
		return sb.append(TIP_CLOSE).toString();
	}

	private void buildCombatAchievements(JPanel p)
	{
		JsonObject all = Standings.CA_TASKS;
		long[] c = panel.build.standings.combat();
		JPanel head = card("Combat achievements");
		head.add(row("Points", c[1] > 0 ? fmt(c[0]) + " / " + fmt(c[1]) : fmt(c[0]),
			panel.accent()));
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6"));
		Set<Integer> headDone = panel.build.standings.caDone();
		long named = 0;
		for (int id : headDone)
		{
			if (all.has(String.valueOf(id)))
			{
				named++;
			}
		}
		long unnamed = headDone.size() - named;
		if (!headDone.isEmpty())
		{
			head.add(row("Tasks done", fmt(named) + " / " + fmt(all.size()), panel.accent()));
		}
		spaced(p, head);
		if (unnamed > 0)
		{
			spaced(p, note("You have also done " + count(unnamed, "combat achievement")
				+ " added to the game since this copy of"
				+ " Chronicle was built. They are counted by the game, not named"
				+ " here, until the plugin updates."), 4);
		}
		Set<Integer> done = headDone;
		boolean known = !done.isEmpty();
		if (!known)
		{
			spaced(p, note("Which tasks you have done arrives when you next log in. "
				+ "Until then this is what each tier asks for."), 4);
		}
		for (Entry<String, List<JsonObject>> e : Standings.caBySource().entrySet())
		{
			long got = 0;
			for (JsonObject task : e.getValue())
			{
				if (done.contains(task.get("id").getAsInt()))
				{
					got++;
				}
			}
			String foldKey = "ca:" + e.getKey();
			boolean open = panel.foldOpen(foldKey);
			int n = e.getValue().size();
			p.add(panel.quietHead(e.getKey(), known
				? fmt(got) + " / " + fmt(n)
				: count(n, "task"), foldKey));
			if (!open)
			{
				continue;
			}
			for (JsonObject task : e.getValue())
			{
				boolean has = known && done.contains(task.get("id").getAsInt());
				JPanel line = row(withoutSource(task.get("name").getAsString(), e.getKey()),
					Text.capital(task.get("tier").getAsString()),
					known ? (has ? ACCENT_SESSION : ACCENT_RED) : null, known);
				line.setToolTipText(tip(task.get("name").getAsString(),
					"Tier", task.get("tier").getAsString(),
					"Where", Standings.caSource(task.get("monster").getAsString()),
					"Task", task.get("task").getAsString()));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String withoutSource(String name, String source)
	{
		if (name == null || source == null || name.length() <= source.length()
			|| !name.regionMatches(true, 0, source, 0, source.length()))
		{
			return name;
		}
		String rest = name.substring(source.length()).trim();
		return rest.isEmpty() ? name : rest;
	}

	private JPanel totalLevelTile(Summary.Stand stand, HistoryLog.Levels opened)
	{
		HistoryLog.Levels shut = stand.closed;
		boolean paired = !panel.wholeRecord() && opened != null && shut.drawn == opened.drawn;
		long levels = paired ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total ? climb(opened.total, stand.standing) : figure)
				+ " · +" + fmt(levels);
		}
		JPanel cell = levelTile("Total level");
		if (panel.periodTip != null)
		{
			cell.setToolTipText(panel.periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(levels > 0 ? panel.accent()
			: panel.wholeRecord() ? Color.WHITE : dim());
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	private JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		final String craft = prettify(low(sk.name()));
		boolean slayer = Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip() : skillTip(craft));
		link(cell, slayer ? () -> panel.openSlayer("Tasks") : () -> panel.openSkill(craft));

		JLabel icon = new JLabel();
		BufferedImage img = panel.art.skillIcon(sk);
		if (img != null)
		{
			icon.setIcon(new ImageIcon(img));
		}
		else
		{
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			styled(icon, small(), dim());
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		boolean climbed = from != null && level > from && !panel.wholeRecord();
		JLabel lvl = styled(new JLabel(level <= 0 ? "-"
			: climbed ? climb(from, level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : dim());
		text.add(lvl);

		if (gained != null)
		{
			text.add(styled(new JLabel((panel.wholeRecord() ? "" : "+") + xpShort(gained)), small(), panel.accent()));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		if (panel.historySpine == null || !LocalDate.now().equals(panel.historyDay)
			|| newestTs(panel.store.feedNewest(1)) != panel.historyFeedTs)
		{
			panel.gatherHistory();
		}
		if (panel.historySpine == null)
		{
			return noted(p, "Reading your history…");
		}
		Summary.Board b = panel.build.summary.board();
		if (b.empty != null)
		{
			return noted(p, b.empty);
		}
		if (b.measuredSince != null)
		{
			panel.measuredSince = b.measuredSince;
		}
		panel.periodTip = panel.periodTip(b.played, b.gains);
		if (b.since != null)
		{
			spaced(p, note(b.since), 5);
		}
		addSkillGrid(p, b.gains, b.stand, b.opened);
		return p;
	}
}
