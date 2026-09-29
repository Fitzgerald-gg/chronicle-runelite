/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.SourceRow;
import chronicle.counters.ExperienceStatTracker.SkillGain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

@RequiredArgsConstructor
final class SkillQuery
{
	private final Board board;

	@RequiredArgsConstructor
	static final class SkillStand
	{
		final List<Skill> order;
		final List<String> keys;
		final Map<Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;
	}

	@RequiredArgsConstructor
	static final class Climb
	{
		final long gained;
		final long now;
	}

	List<SourceRow> skillGround(String craft)
	{
		Set<String> ownTile = new HashSet<>();
		Arrays.stream(ACTIVITIES).filter(a -> !a[1].isEmpty()).forEach(a -> ownTile.add(low(a[1])));
		List<SourceRow> out = new ArrayList<>();
		for (SourceRow r : board.sources())
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
		SkillRow cur = board.plugin.skillSheet().get(key);
		return cur != null && cur.xp > 0 ? cur.xp : null;
	}

	long sessionXp(String key)
	{
		for (SkillGain g : board.plugin.sessionSkillXp())
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
		Map<String, SkillRow> sheet = live ? board.plugin.skillSheet() : Collections.emptyMap();
		List<Skill> order = skillOrder();
		List<String> keys = new ArrayList<>();
		order.forEach(sk -> keys.add(low(sk.name())));
		HistoryLog.Levels closed = HistoryLog.levels(closing, keys);
		Map<Skill, Long> levels = new EnumMap<>(Skill.class);
		long total = 0;
		for (Skill sk : order)
		{
			SkillRow cur = sheet.get(low(sk.name()));
			long level = cur != null && cur.level > 0 ? cur.level : closed.of.get(low(sk.name()));
			total += level;
			long shown = board.period.whole() && cur != null && cur.xp > 0 ? PaceBook.virtualLevelAt(cur.xp) : level;
			levels.put(sk, Math.max(level, shown));
		}
		SkillRow ov = sheet.get("overall");
		return new SkillStand(order, keys, levels, ov != null && ov.level > 0 ? ov.level : total, closed);
	}

	static Baseline baselineAt(Map<String, Long> xp)
	{
		Baseline at = new Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	private Map<String, Long> xpBySkill()
	{
		Map<String, Long> by = new LinkedHashMap<>();
		if (board.period.session())
		{
			board.plugin.sessionSkillXp().forEach(g -> by.put(low(g.skill.name()), Math.max(0, g.xp)));
		}
		else if (board.period.whole())
		{
			board.plugin.skillSheet().forEach((k, v) ->
			{
				if (!"overall".equals(k))
				{
					by.put(k, v.xp);
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
			gains.forEach((k, v) -> by.put(k, Math.max(0, v)));
		}
		return by;
	}

	Long periodXp()
	{
		if (board.period.whole())
		{
			SkillRow overall = board.plugin.skillSheet().get("overall");
			return overall != null ? Long.valueOf(overall.xp) : null;
		}
		Map<String, Long> by = xpBySkill();
		return by == null ? null : by.values().stream().mapToLong(Long::longValue).sum();
	}

	String periodXpMost()
	{
		Map<String, Long> by = xpBySkill();
		String top = by == null ? null : topOf(by);
		return top == null ? null : prettify(top);
	}

	Climb periodLevels()
	{
		SkillRow overall = board.plugin.skillSheet().get("overall");
		if (board.period.whole())
		{
			return overall != null && overall.level > 0 ? new Climb(overall.level, overall.level) : null;
		}
		if (board.period.session())
		{
			long gained = board.days.stirred("LEVEL");
			return gained > 0 && overall != null ? new Climb(gained, overall.level) : null;
		}
		Board.Span s = board.span();
		if (s == null || !s.opening.complete || !s.closing.complete)
		{
			return null;
		}
		HistoryLog.Levels was = HistoryLog.levels(s.opening, SKILL_KEYS);
		HistoryLog.Levels now = HistoryLog.levels(baselineAt(board.closingSkills(s.closing.skills, board.periodReachesToday())), SKILL_KEYS);
		return now.total > was.total ? new Climb(now.total - was.total, now.total) : null;
	}

	private Map<String, Long> periodSkillGains()
	{
		Board.Span s = board.span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> out = new LinkedHashMap<>(HistoryLog.gained(s.opening.skills, s.earliest.skills,
			board.closingSkills(s.closing.skills, board.periodReachesToday()), s.opening.complete));
		out.remove("overall");
		return out;
	}
}
