/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.SkillStand;
import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.SourceRow;
import chronicle.Period.Window;
import chronicle.counters.ExperienceStatTracker;
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
import java.util.AbstractMap;
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
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class StandingScreen extends Screen
{
	StandingScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	JPanel buildSheet()
	{
		JPanel p = column();
		p.add(buildHistory());
		p.add(activitySheet());
		p.add(buildKills());
		return p;
	}

	static int activitySprite(String label)
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

	JPanel activitySheet()
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

	JPanel activityCell(String label, String source, String page)
	{
		JPanel cell = tile(3, 3);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 24));
		long figure;
		String hover;
		if ("Clues".equals(label))
		{
			long[] each = new long[CLUE_TIERS.length];
			long[] worth = new long[CLUE_TIERS.length];
			long all = 0;
			long allWorth = 0;
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				SourceRow r = board.clue(CLUE_TIERS[i]);
				if (r != null)
				{
					each[i] = Math.max(r.kc, r.loots);
					worth[i] = r.value;
					all += each[i];
					allWorth += r.value;
				}
			}
			figure = all;
			List<String> lines = new ArrayList<>(Arrays.asList("All", fmt(all) + tail(allWorth)));
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				lines.add(CLUE_TIERS[i]);
				lines.add(each[i] == 0 ? "0" : fmt(each[i]) + tail(worth[i]));
			}
			hover = tip("Clues", lines);
		}
		else if ("Collections".equals(label))
		{
			figure = plugin.clogFinished();
			int[] logStanding = board.clogStanding();
			hover = tip("Collection log",
				"Obtained", fmt(figure),
				"Available", logStanding != null ? fmt(logStanding[1]) : "not yet",
				"Share", logStanding != null ? share(logStanding[0], logStanding[1]) : "-");
		}
		else if ("Quests".equals(label))
		{
			JsonObject q = obj(board.achievements(), "quests");
			long done = 0;
			long started = 0;
			for (String k : q.keySet())
			{
				String state = q.get(k).getAsString();
				if ("FINISHED".equals(state))
				{
					done++;
				}
				else if ("IN_PROGRESS".equals(state))
				{
					started++;
				}
			}
			figure = done;
			hover = tip("Quests",
				"Complete", fmt(done),
				"In progress", fmt(started),
				"Known", fmt(q.size()));
		}
		else if ("Diaries".equals(label))
		{
			long[] d = board.diaryStanding();
			figure = d[0];
			hover = tip("Achievement diaries",
				"Tiers done", d[0] + " / " + d[1],
				"Regions finished", fmt(d[2]),
				"Regions", fmt(d[3]));
		}
		else
		{
			long named = board.namedLine(source, label);
			figure = named > 0 ? named : board.bossKills(source);
			hover = tip(label, "Count", fmt(figure));
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
		boolean lit = figure > 0 && moved != 0;
		JLabel fig = styled(new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT), small(),
			lit ? LIT : DIM);
		JPanel text = new JPanel(new GridLayout(moved > 0 ? 2 : 1, 1));
		text.setBackground(DARKER);
		text.add(fig);
		if (moved > 0)
		{
			text.add(styled(new JLabel("+" + fmt(moved), JLabel.RIGHT), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	JPanel buildKills()
	{
		JPanel p = column();
		board.rollUsed = false;
		List<Boss> roster = bossRoster(plugin.gson());
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
			List<Boss> had = new ArrayList<>();
			for (Boss b : roster)
			{
				if (board.bossKillsInWindow(b.name) > 0)
				{
					had.add(b);
				}
			}
			if (had.isEmpty())
			{
				String unkept = board.notCounting(true);
				return noted(p, unkept != null ? unkept : board.inside("Nothing on the boss sheet was killed"));
			}
			roster = had;
		}
		JPanel grid = grid3();
		for (Boss b : roster)
		{
			grid.add(bossCell(b));
		}
		LocalDate shortFrom = board.rollUsed ? board.rollShortOf() : null;
		if (shortFrom != null)
		{
			spaced(p, note("Kills the journal cannot date are counted from loot "
				+ "instead, which reaches back only to " + shortFrom.format(FULL_DAY)
				+ " and sees a kill only where it dropped something."), 4);
		}
		spaced(p, grid);
		return p;
	}

	JPanel bossCell(Boss b)
	{
		final long kc = board.bossKillsInWindow(b.name);
		JPanel cell = tile(3, 3);
		cell.setToolTipText(bossTip(b));

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			ui.art.wear(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(),
			kc > 0 ? LIT : DIM);
		cell.add(fig, BorderLayout.EAST);
		final String open = board.bossLootSource(b);
		link(cell, () -> ui.openSourceLoose(open));
		return cell;
	}

	String bossTip(Boss b)
	{
		List<String> lines = new ArrayList<>();
		String kind = kindOf(b.name);
		SourceRow src = null;
		List<SourceRow> paidOut = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				if (src == null)
				{
					src = r;
				}
				continue;
			}
			if (namesInBrackets(r.name, b.name)
				|| paysOutThrough(b.name, r.name))
			{
				paidOut.add(r);
			}
		}
		if (!period.whole())
		{
			long inWin = board.bossKillsInWindow(b.name);
			long[] paid = board.sourceInWindow(b.name);
			lines.add(board.window().label);
			lines.add((inWin < 0 ? "-" : count(inWin, "kill"))
				+ tail(paid[1]));
		}
		long known = board.bossKills(b.name);
		lines.add("Kills tracked");
		lines.add(known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-");
		for (Entry<String, Long> pb : board.pageLines(b.name, "pb_lines"))
		{
			lines.add(pb.getKey());
			lines.add(clock(pb.getValue()));
		}
		double[] timed = period.whole() && src != null ? new double[]{src.timed, src.timeSum}
			: board.sourceTimesInWindow(b.name);
		if (timed[0] > 0)
		{
			lines.add("Average kill");
			lines.add(pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed");
		}
		long here = board.minutesAt(b.name, period.whole() ? board.counters() : board.periodCounters());
		if (here > 0 && board.minutesCoverPeriod())
		{
			lines.add("Time here");
			lines.add(hoursMinutes(here));
		}
		for (Entry<String, Long> ln : board.logLines(b.name))
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
			lines.add(beforeBracket(r.name));
			lines.add(paidFigure(r));
		}
		if (src == null && paidOut.isEmpty())
		{
			lines.add("Loot");
			lines.add("none yet");
		}
		return tip(b.name, lines);
	}

	String paidFigure(SourceRow r)
	{
		return qtyGp(board.tallyOf(store.sourceItems(r.name))[0], r.value);
	}

	JPanel buildHistory()
	{
		JPanel p = column();
		Window periodWin = board.window();
		final LocalDate pStart = periodWin.start;
		final LocalDate pEnd = periodWin.end;
		final boolean live = !pEnd.isBefore(LocalDate.now());

		if (board.historySpine == null || !LocalDate.now().equals(board.historyDay)
			|| newestTs(store.feedNewest(1)) != board.historyFeedTs)
		{
			board.gatherHistory();
		}
		if (board.historySpine == null)
		{
			return noted(p, "Reading your history…");
		}
		TreeMap<LocalDate, Baseline> hist = board.historySpine;

		Entry<LocalDate, Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(hist, pStart, pEnd);
		Entry<LocalDate, Baseline> at = hist.floorEntry(pEnd);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !board.closesOnTheClient(from, pStart, pEnd)))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (hist.firstKey().isBefore(pStart)
				&& ("Day".equals(period.granularity) || "Week".equals(period.granularity)))
			{
				empty = "The imported past resolves by month. Switch to Month "
					+ "or Year to read this era. Daily detail begins with the plugin.";
			}
			else
			{
				empty = "Nothing recorded in this period.";
			}
			p.add(note(empty));
		}
		else
		{
			if (before == null)
			{
				ui.measuredSince = "Measured since " + from.getKey().format(FULL_DAY)
					+ ", the earliest baseline on record.";
			}
			else if (before.getKey().isBefore(pStart.minusDays(1)))
			{
				ui.measuredSince = "Measured since " + before.getKey().format(FULL_DAY)
					+ ", the nearest earlier baseline.";
			}
			Baseline earliest = HistoryLog.earliest(hist, at.getKey());
			Baseline closing = HistoryLog.stateAt(hist, at.getKey());
			Baseline opening = HistoryLog.stateAt(hist, from.getKey());
			Map<String, Long> closesOn = live ? board.closingSkills(closing.skills, true) : closing.skills;
			List<Entry<String, Long>> gains = new ArrayList<>();
			for (Entry<String, Long> e : HistoryLog.gained(opening.skills,
				earliest.skills, closesOn, opening.complete).entrySet())
			{
				if (!"overall".equals(e.getKey()))
				{
					gains.add(e);
				}
			}
			if (period.session())
			{
				gains.clear();
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
				{
					if (g.skill != null && g.xp > 0)
					{
						gains.add(new AbstractMap.SimpleEntry<>(
							low(g.skill.name()), g.xp));
					}
				}
			}
			gains.sort(Entry.<String, Long>comparingByValue().reversed());

			long fromMs = period.session() ? board.windowMs()[0]
				: startMs(pStart);
			long toMs = period.session() ? board.windowMs()[1]
				: startMs(pEnd.plusDays(1));
			long[] played = {0, 0};
			long oldest = oldestTs(board.historyFeed, false);
			if ((oldest > 0 && oldest < fromMs) || period.whole())
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
			if (period.session() ? live : (began > 0 && began >= fromMs && began < toMs))
			{
				long running = plugin.sessionElapsedMinutes();
				if (running > 0)
				{
					played[0] += running;
					played[1]++;
				}
			}
			if (period.whole())
			{
				played[0] = Math.max(played[0], plugin.gamePlaytimeMinutes());
			}

			Baseline sittingOpen = null;
			if (period.session())
			{
				Map<String, Long> openXp = new HashMap<>(closesOn);
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
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
				sittingOpen = Board.baselineAt(openXp);
				closing = Board.baselineAt(closesOn);
			}
			SkillStand stand = board.skillStand(closing, live);
			HistoryLog.Levels opened = sittingOpen != null
				? HistoryLog.levels(sittingOpen, stand.keys)
				: HistoryLog.levels(opening, stand.keys);
			periodTip = periodTip(played, gains);
			LocalDate lootSince = null;
			long lootFromTs = earliestDatedLoot(board.historyFeed, store.loot.lootRollFrom());
			if (lootFromTs > 0)
			{
				LocalDate sat = dayOf(lootFromTs);
				if (sat.isAfter(pStart))
				{
					lootSince = sat;
				}
			}
			String since = period.whole() ? null
				: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
					lootSince, lootFromTs > 0);
			if (since != null)
			{
				spaced(p, note(since), 5);
			}

			addSkillGrid(p, gains, stand, opened);
		}

		return p;
	}

	void addSkillGrid(JPanel p, List<Entry<String, Long>> gains, SkillStand stand,
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
		total.setToolTipText(periodTip != null ? periodTip.replace("</body>", dimLine("Opens the records") + "</body>")
			: tip("Total level", "Opens", "the records"));
		link(total, ui::openRecords);
		if (period.whole())
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

	JPanel combatLevelTile(Map<String, Long> gain, HistoryLog.Levels opened)
	{
		JPanel cell = levelTile("Combat");
		int cb = store.combatLevel();
		Integer was = openingCombat(opened);
		boolean climbed = !period.whole() && was != null && cb > was;
		JLabel fig = new JLabel(cb > 0 ? (climbed ? climb(was, cb) : fmt(cb)) : "-",
			JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(cb > 0 && (period.whole() || combatSkillsMoved(gain))
			? LIT : DIM);
		cell.add(fig, BorderLayout.EAST);
		Map<String, Long> c = board.counters();
		long[] ca = board.combatStanding();
		cell.setToolTipText(tip("Combat",
			"Achievement points", ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
			"Tiers unlocked", fmt(ca[2]) + " / 6",
			"Damage dealt", fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
			"Highest hit", fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))));
		link(cell, () -> ui.open(ChroniclePanel.Page.SHEET, "combat"));
		return cell;
	}

	long activityMoved(String label, String source)
	{
		if (period.whole())
		{
			return -1;
		}
		if ("Collections".equals(label))
		{
			return board.stirred("COLLECTION");
		}
		if ("Quests".equals(label))
		{
			return board.stirred("QUEST");
		}
		if ("Diaries".equals(label))
		{
			return board.stirred("DIARY");
		}
		if ("Clues".equals(label))
		{
			long all = 0;
			for (String tier : CLUE_TIERS)
			{
				all += board.rolled("Clue Scroll (" + tier + ")");
			}
			return all;
		}
		if (!source.isEmpty())
		{
			long rolled = board.rolled(source);
			return rolled > 0 && board.namedLine(source, label) > 0 ? -1 : rolled;
		}
		return 0;
	}

	String skillTip(String craft)
	{
		Map<String, Long> now = board.periodCounters();
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

	String slayerTip()
	{
		long[] tally = board.taskTally();
		return tip("Slayer",
			"Tasks tracked", fmt(tally[2]),
			"Kills on task", fmt(tally[0]),
			"On-task loot", gps(tally[3]));
	}

	static boolean combatSkillsMoved(Map<String, Long> gain)
	{
		for (Skill sk : COMBAT_SKILLS)
		{
			Long g = gain.get(low(sk.name()));
			if (g != null && g > 0)
			{
				return true;
			}
		}
		return false;
	}

	String periodTip(long[] played, List<Entry<String, Long>> gains)
	{
		long xp = sumOf(gains);
		return tip(period.session() ? "This sitting"
			: period.whole() ? "Lifetime" : "The period",
			"Time played", hoursMinutes(played[0]),
			"Sessions", fmt(played[1]),
			"Experience", "+" + gp(xp));
	}

	String periodTip;

	JPanel totalLevelTile(SkillStand stand, HistoryLog.Levels opened)
	{
		HistoryLog.Levels shut = stand.closed;
		boolean paired = !period.whole() && opened != null && shut.drawn == opened.drawn;
		long levels = paired ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total ? climb(opened.total, stand.standing) : figure)
				+ " · +" + fmt(levels);
		}
		JPanel cell = levelTile("Total level");
		if (periodTip != null)
		{
			cell.setToolTipText(periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(levels > 0 ? ACCENT
			: period.whole() ? Color.WHITE : DIM);
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		final String craft = prettify(low(sk.name()));
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
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			styled(icon, small(), DIM);
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		boolean climbed = from != null && level > from && !period.whole();
		JLabel lvl = styled(new JLabel(level <= 0 ? "-"
			: climbed ? climb(from, level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : DIM);
		text.add(lvl);

		if (gained != null)
		{
			text.add(styled(new JLabel((period.whole() ? "" : "+") + xpShort(gained)), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
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
				? prettyTier(what) + " since "
				: " · " + what + " since ")
				.append(loot.format(FULL_DAY));
		}
		return note.length() == 0 ? null : note.toString();
	}

	private static final int ICON_W = 22;
	private static final int ICON_H = 18;
}
