/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.SkillStand;
import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.SourceRow;
import chronicle.Period.Window;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.hiscore.HiscoreSkill;
import static chronicle.Feed.*;
import static chronicle.Json.obj;
import static chronicle.KillCounts.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class StandingScreen extends Screen
{
	private static final int ICON_W = 22;
	private static final int ICON_H = 18;

	private String periodTip;

	JPanel buildSheet()
	{
		JPanel p = column();
		p.add(history());
		JPanel activities = column();
		JPanel grid = grid3();
		for (String[] a : ACTIVITIES)
		{
			grid.add(activityCell(a[0], a[1], a[2]));
		}
		spaced(activities, grid);
		p.add(activities);
		p.add(bosses());
		return p;
	}

	private JPanel history()
	{
		JPanel p = column();
		Window w = board.window();
		boolean live = !w.end.isBefore(LocalDate.now());
		if (board.historySpine == null || !LocalDate.now().equals(board.historyDay)
			|| newestTs(store.feedNewest(1)) != board.historyFeedTs)
		{
			board.gatherHistory();
		}
		TreeMap<LocalDate, Baseline> hist = board.historySpine;
		if (hist == null)
		{
			return noted(p, "Reading your history…");
		}
		Entry<LocalDate, Baseline> before = hist.floorEntry(w.start.minusDays(1));
		Entry<LocalDate, Baseline> from = HistoryLog.windowStart(hist, w.start, w.end);
		Entry<LocalDate, Baseline> at = hist.floorEntry(w.end);
		if (at == null || from == null || at.getKey().equals(from.getKey()) && !board.closesOnTheClient(from, w.start, w.end))
		{
			return noted(p, hist.isEmpty()
				? "The record starts today: baselines close at each login, day rollover and logout, and a period is the distance between two of them."
				: hist.firstKey().isBefore(w.start) && ("Day".equals(period.granularity) || "Week".equals(period.granularity))
				? "The imported past resolves by month. Switch to Month or Year to read this era. Daily detail begins with the plugin."
				: "Nothing recorded in this period.");
		}
		if (before == null)
		{
			ui.measuredSince = "Measured since " + from.getKey().format(FULL_DAY) + ", the earliest baseline on record.";
		}
		else if (before.getKey().isBefore(w.start.minusDays(1)))
		{
			ui.measuredSince = "Measured since " + before.getKey().format(FULL_DAY) + ", the nearest earlier baseline.";
		}
		Baseline closing = HistoryLog.stateAt(hist, at.getKey());
		Baseline opening = HistoryLog.stateAt(hist, from.getKey());
		Map<String, Long> closesOn = live ? board.closingSkills(closing.skills, true) : closing.skills;
		Map<String, Long> gains = gains(opening, HistoryLog.earliest(hist, at.getKey()), closesOn);
		if (period.session())
		{
			Map<String, Long> openXp = new HashMap<>(closesOn);
			gains.forEach((key, xp) -> openXp.computeIfPresent(key, (k, had) -> Math.max(0, had - xp)));
			opening = Board.baselineAt(openXp);
			closing = Board.baselineAt(closesOn);
		}
		SkillStand stand = board.skillStand(closing, live);
		HistoryLog.Levels opened = HistoryLog.levels(opening, stand.keys);
		long[] played = played(w);
		periodTip = tip(period.session() ? "This sitting" : period.whole() ? "Lifetime" : "The period",
			"Time played", hoursMinutes(played[0]),
			"Sessions", fmt(played[1]),
			"Experience", "+" + gp(gains.values().stream().mapToLong(Long::longValue).sum()));
		long lootFrom = earliestDatedLoot(board.historyFeed, store.loot.lootRollFrom());
		LocalDate lootSince = lootFrom > 0 && dayOf(lootFrom).isAfter(w.start) ? dayOf(lootFrom) : null;
		String since = period.whole() ? null : countersSince(hist.headMap(at.getKey(), true), from.getKey(), lootSince, lootFrom > 0);
		if (since != null)
		{
			spaced(p, note(since), 5);
		}
		addSkillGrid(p, gains, stand, opened);
		return p;
	}

	private Map<String, Long> gains(Baseline opening, Baseline earliest, Map<String, Long> closesOn)
	{
		List<Entry<String, Long>> list = new ArrayList<>();
		if (period.session())
		{
			plugin.sessionSkillXp().stream().filter(g -> g.skill != null && g.xp > 0)
				.forEach(g -> list.add(Map.entry(low(g.skill.name()), (long) g.xp)));
		}
		else
		{
			HistoryLog.gained(opening.skills, earliest.skills, closesOn, opening.complete).forEach((k, v) ->
			{
				if (!"overall".equals(k))
				{
					list.add(Map.entry(k, v));
				}
			});
		}
		list.sort(Entry.<String, Long>comparingByValue().reversed());
		Map<String, Long> out = new LinkedHashMap<>();
		list.forEach(e -> out.put(e.getKey(), e.getValue()));
		return out;
	}

	private long[] played(Window w)
	{
		long[] ms = board.windowMs();
		long fromMs = period.session() ? ms[0] : startMs(w.start);
		long toMs = period.session() ? ms[1] : startMs(w.end.plusDays(1));
		long[] played = {0, 0};
		long oldest = oldestTs(board.historyFeed, false);
		if (oldest > 0 && oldest < fromMs || period.whole())
		{
			for (JsonObject e : board.historyFeed)
			{
				long filed = filedAt(e);
				if (filed >= fromMs && filed < toMs && "SESSION".equals(typeOf(e)))
				{
					played[0] += sessionMinutes(e);
					played[1]++;
				}
			}
		}
		long began = plugin.sessionStart();
		long running = plugin.sessionElapsedMinutes();
		boolean counts = period.session() ? !w.end.isBefore(LocalDate.now()) : began > 0 && began >= fromMs && began < toMs;
		if (counts && running > 0)
		{
			played[0] += running;
			played[1]++;
		}
		if (period.whole())
		{
			played[0] = Math.max(played[0], plugin.gamePlaytimeMinutes());
		}
		return played;
	}

	private static String countersSince(SortedMap<LocalDate, Baseline> spine, LocalDate startLine, LocalDate lootFrom,
		boolean lootFromSittings)
	{
		LocalDate counters = HistoryLog.firstCarrying(spine, null);
		LocalDate loot = lootFromSittings ? lootFrom : HistoryLog.firstCarrying(spine, "dropsReceived");
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
			note.append(note.length() == 0 ? prettyTier(what) + " since " : " · " + what + " since ").append(loot.format(FULL_DAY));
		}
		return note.length() == 0 ? null : note.toString();
	}

	private void addSkillGrid(JPanel p, Map<String, Long> gains, SkillStand stand, HistoryLog.Levels opened)
	{
		JPanel grid = grid3();
		for (Skill sk : stand.order)
		{
			String key = low(sk.name());
			Integer was = opened.virtual.getOrDefault(key, opened.of.get(key));
			grid.add(skillCell(sk, stand.levels.get(sk), gains.get(key), was == null ? null : was.longValue()));
		}
		spaced(p, grid, 3);
		JPanel combat = combatTile(gains, opened);
		JPanel total = totalTile(stand, opened);
		total.setToolTipText(periodTip.replace("</body>", dimLine("Opens the records") + "</body>"));
		link(total, ui::openRecords);
		if (period.whole())
		{
			JPanel both = new JPanel(new GridLayout(1, 2, 2, 2));
			both.setBackground(DARK);
			both.setAlignmentX(Component.LEFT_ALIGNMENT);
			both.add(combat);
			both.add(total);
			p.add(both);
		}
		else
		{
			spaced(p, combat, 2);
			p.add(total);
		}
		p.add(vgap(6));
	}

	private JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		String craft = prettify(low(sk.name()));
		boolean slayer = Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip() : skillTip(craft));
		link(cell, slayer ? () -> ui.openSlayer("Tasks") : () -> ui.openSkill(craft));
		JLabel icon = new JLabel();
		BufferedImage img = ui.art.skill(sk);
		if (img != null)
		{
			icon.setIcon(new ImageIcon(img));
		}
		else
		{
			styled(icon, small(), DIM).setText(sk.name().substring(0, Math.min(3, sk.name().length())));
		}
		cell.add(icon, BorderLayout.WEST);
		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		boolean climbed = from != null && level > from && !period.whole();
		text.add(styled(new JLabel(level <= 0 ? "-" : climbed ? climb(from, level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : DIM));
		if (gained != null)
		{
			text.add(styled(new JLabel((period.whole() ? "" : "+") + xpShort(gained)), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private String skillTip(String craft)
	{
		Map<String, Long> now = board.periodCounters();
		List<String> lines = new ArrayList<>();
		List<Entry<String, Long>> named = new ArrayList<>();
		for (String key : StatRegistry.headlines(craft))
		{
			long v = now.getOrDefault(key, 0L);
			if (v > 0 && StatRegistry.isFloor(key))
			{
				lines.addAll(List.of(StatRegistry.rowLabel(key), fmt(v)));
			}
			else if (v > 0)
			{
				named.add(Map.entry(key, v));
			}
		}
		named.sort(Entry.<String, Long>comparingByValue().reversed());
		for (Entry<String, Long> e : named)
		{
			if (lines.size() >= 12)
			{
				break;
			}
			lines.addAll(List.of(StatRegistry.rowLabel(e.getKey()), fmt(e.getValue())));
		}
		return tip(craft, lines);
	}

	private String slayerTip()
	{
		long[] tally = board.taskTally();
		return tip("Slayer", "Tasks tracked", fmt(tally[2]), "Kills on task", fmt(tally[0]), "On-task loot", gps(tally[3]));
	}

	private JPanel combatTile(Map<String, Long> gains, HistoryLog.Levels opened)
	{
		JPanel cell = levelTile("Combat");
		int cb = store.combatLevel();
		Integer was = openingCombat(opened);
		boolean climbed = !period.whole() && was != null && cb > was;
		boolean moved = Arrays.stream(COMBAT_SKILLS).anyMatch(sk -> gains.getOrDefault(low(sk.name()), 0L) > 0);
		cell.add(styled(new JLabel(cb > 0 ? climbed ? climb(was, cb) : fmt(cb) : "-", JLabel.RIGHT), small(),
			cb > 0 && (period.whole() || moved) ? LIT : DIM), BorderLayout.EAST);
		Map<String, Long> c = board.counters();
		long[] ca = board.combatStanding();
		cell.setToolTipText(tip("Combat",
			"Achievement points", ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
			"Tiers unlocked", fmt(ca[2]) + " / 6",
			"Damage dealt", fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
			"Highest hit", fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))));
		return link(cell, () -> ui.open(ChroniclePanel.Page.SHEET, "combat"));
	}

	private JPanel totalTile(SkillStand stand, HistoryLog.Levels opened)
	{
		HistoryLog.Levels shut = stand.closed;
		long levels = !period.whole() && shut.drawn == opened.drawn ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total ? climb(opened.total, stand.standing) : figure) + " · +" + fmt(levels);
		}
		JPanel cell = levelTile("Total level");
		cell.add(styled(new JLabel(figure, JLabel.RIGHT), small(), levels > 0 ? ACCENT : period.whole() ? Color.WHITE : DIM),
			BorderLayout.EAST);
		return cell;
	}

	private JPanel activityCell(String label, String source, String page)
	{
		JPanel cell = tile(3, 3);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 24));
		long figure;
		String hover;
		switch (label)
		{
			case "Clues":
			{
				List<String> lines = new ArrayList<>();
				long all = 0;
				long worth = 0;
				for (String tier : CLUE_TIERS)
				{
					SourceRow r = board.clue(tier);
					long n = r == null ? 0 : Math.max(r.kc, r.loots);
					all += n;
					worth += r == null ? 0 : r.value;
					lines.addAll(List.of(tier, n == 0 ? "0" : fmt(n) + tail(r.value)));
				}
				lines.addAll(0, List.of("All", fmt(all) + tail(worth)));
				figure = all;
				hover = tip("Clues", lines);
				break;
			}
			case "Collections":
			{
				figure = plugin.clogFinished();
				int[] log = board.clogStanding();
				hover = tip("Collection log", "Obtained", fmt(figure), "Available", log != null ? fmt(log[1]) : "not yet",
					"Share", log != null ? share(log[0], log[1]) : "-");
				break;
			}
			case "Quests":
			{
				JsonObject q = obj(board.achievements(), "quests");
				long done = q.entrySet().stream().filter(e -> "FINISHED".equals(e.getValue().getAsString())).count();
				long started = q.entrySet().stream().filter(e -> "IN_PROGRESS".equals(e.getValue().getAsString())).count();
				figure = done;
				hover = tip("Quests", "Complete", fmt(done), "In progress", fmt(started), "Known", fmt(q.size()));
				break;
			}
			case "Diaries":
			{
				long[] d = board.diaryStanding();
				figure = d[0];
				hover = tip("Achievement diaries", "Tiers done", d[0] + " / " + d[1], "Regions finished", fmt(d[2]), "Regions", fmt(d[3]));
				break;
			}
			default:
			{
				long named = board.namedLine(source, label);
				figure = named > 0 ? named : board.bossKills(source);
				hover = tip(label, "Count", fmt(figure));
			}
		}
		ui.art.wear(icon, activitySprite(label), ICON_W, ICON_H);
		cell.setToolTipText(hover);
		if (!page.isEmpty())
		{
			link(cell, () -> ui.open(ChroniclePanel.Page.SHEET, page));
		}
		else if (!source.isEmpty())
		{
			link(cell, () -> ui.openActivity(source));
		}
		cell.add(icon, BorderLayout.WEST);
		long moved = activityMoved(label, source);
		JPanel text = new JPanel(new GridLayout(moved > 0 ? 2 : 1, 1));
		text.setBackground(DARKER);
		text.add(styled(new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT), small(), figure > 0 && moved != 0 ? LIT : DIM));
		if (moved > 0)
		{
			text.add(styled(new JLabel("+" + fmt(moved), JLabel.RIGHT), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
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

	private long activityMoved(String label, String source)
	{
		if (period.whole())
		{
			return -1;
		}
		switch (label)
		{
			case "Collections":
				return board.stirred("COLLECTION");
			case "Quests":
				return board.stirred("QUEST");
			case "Diaries":
				return board.stirred("DIARY");
			case "Clues":
				return Arrays.stream(CLUE_TIERS).mapToLong(t -> board.rolled("Clue Scroll (" + t + ")")).sum();
			default:
				long rolled = source.isEmpty() ? 0 : board.rolled(source);
				return rolled > 0 && board.namedLine(source, label) > 0 ? -1 : rolled;
		}
	}

	private JPanel bosses()
	{
		JPanel p = column();
		board.rollUsed = false;
		List<Boss> roster = new ArrayList<>(bossRoster(plugin.gson()));
		if (roster.isEmpty())
		{
			return noted(p, "The boss roster did not load.");
		}
		if (!period.whole() && !period.session() && board.span() == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		if (!period.whole())
		{
			roster.removeIf(b -> board.bossKillsInWindow(b.name) <= 0);
			if (roster.isEmpty())
			{
				String unkept = board.notCounting(true);
				return noted(p, unkept != null ? unkept : board.inside("Nothing on the boss sheet was killed"));
			}
		}
		JPanel grid = grid3();
		roster.forEach(b -> grid.add(bossCell(b)));
		LocalDate shortFrom = board.rollUsed ? board.rollShortOf() : null;
		if (shortFrom != null)
		{
			spaced(p, note("Kills the journal cannot date are counted from loot instead, which reaches back only to "
				+ shortFrom.format(FULL_DAY) + " and sees a kill only where it dropped something."), 4);
		}
		spaced(p, grid);
		return p;
	}

	private JPanel bossCell(Boss b)
	{
		long kc = board.bossKillsInWindow(b.name);
		JPanel cell = tile(3, 3);
		cell.setToolTipText(bossTip(b));
		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			ui.art.wear(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);
		cell.add(styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(), kc > 0 ? LIT : DIM), BorderLayout.EAST);
		String open = board.bossLootSource(b);
		return link(cell, () -> ui.openSourceLoose(open));
	}

	private String bossTip(Boss b)
	{
		String kind = kindOf(b.name);
		SourceRow src = null;
		List<SourceRow> paidOut = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				src = src == null ? r : src;
			}
			else if (namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name))
			{
				paidOut.add(r);
			}
		}
		List<String> lines = new ArrayList<>();
		if (!period.whole())
		{
			long inWin = board.bossKillsInWindow(b.name);
			lines.addAll(List.of(board.window().label, (inWin < 0 ? "-" : count(inWin, "kill")) + tail(board.sourceInWindow(b.name)[1])));
		}
		long known = board.bossKills(b.name);
		lines.addAll(List.of("Kills tracked", known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-"));
		board.pageLines(b.name, "pb_lines").forEach(ln -> lines.addAll(List.of(ln.getKey(), clock(ln.getValue()))));
		double[] timed = period.whole() && src != null ? new double[]{src.timed, src.timeSum} : board.sourceTimesInWindow(b.name);
		if (timed[0] > 0)
		{
			lines.addAll(List.of("Average kill", pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed"));
		}
		long here = board.minutesAt(b.name, period.whole() ? board.counters() : board.periodCounters());
		if (here > 0 && board.minutesCoverPeriod())
		{
			lines.addAll(List.of("Time here", hoursMinutes(here)));
		}
		board.logLines(b.name).forEach(ln -> lines.addAll(List.of(ln.getKey(), fmt(ln.getValue()))));
		if (src != null)
		{
			lines.addAll(List.of("Drops", paidFigure(src)));
		}
		for (SourceRow r : paidOut)
		{
			lines.addAll(List.of(beforeBracket(r.name), paidFigure(r)));
		}
		if (src == null && paidOut.isEmpty())
		{
			lines.addAll(List.of("Loot", "none yet"));
		}
		return tip(b.name, lines);
	}

	private String paidFigure(SourceRow r)
	{
		return qtyGp(Board.tallyOf(store.sourceItems(r.name))[0], r.value);
	}
}
