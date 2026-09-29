/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.SourceRow;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

@RequiredArgsConstructor
final class KillQuery
{
	private final Board board;

	Map<String, Long> taskKillsEver()
	{
		return board.memo("taskKills", () -> board.store.slayer.onTaskKills(Board.EVER_FROM, Board.EVER_TO));
	}

	private Map<String, Long> chatKcByKind()
	{
		return board.memo("chatKc", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			board.plugin.killCounts().forEach((k, v) -> out.merge(KillCounts.chatKind(k), v, Math::max));
			return out;
		});
	}

	private Map<String, Long> kcByKind()
	{
		return board.memo("kcByKind", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>(chatKcByKind());
			board.sources().forEach(r -> out.merge(kindOf(r.name), (long) r.kc, Math::max));
			return out;
		});
	}

	long standingKills(SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		Long said = chatKcByKind().get(KillCounts.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	long bossKills(String name)
	{
		JsonObject cl = board.clogNow();
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

	private static long lookup(JsonObject clog, String map, String key)
	{
		JsonElement v = getIgnoreCase(obj(clog, map), key);
		return v == null ? -1 : asLong(v);
	}

	long bossKillsInWindow(String name)
	{
		if (board.period.whole())
		{
			return bossKills(name);
		}
		if (board.period.session())
		{
			board.rollUsed = true;
			return board.loot.rolled(name);
		}
		Board.Span s = board.span();
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
		Long rolled = board.loot.rolledKills(name);
		if (rolled != null)
		{
			board.rollUsed = true;
			return rolled;
		}
		return -1;
	}

	Map<String, Long> movedKcs(Board.Span s)
	{
		return board.memo("movedKcs", () -> HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			board.closingNow(s.closing.kcs, board.plugin.killCounts())));
	}

	List<Entry<String, Long>> pageLines(String boss, String map)
	{
		List<Entry<String, Long>> out = new ArrayList<>();
		JsonElement found = getIgnoreCase(obj(board.clogNow(), map), LOG_PAGE_FOR.getOrDefault(boss, boss));
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
		for (Boss b : bossRoster())
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
		for (Boss other : bossRoster())
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
		Set<String> kinds = board.memo("killKinds", () ->
		{
			Set<String> out = new HashSet<>();
			bossRoster().forEach(b -> out.add(kindOf(b.name)));
			obj(board.clogNow(), "slayer_kcs").keySet().forEach(said -> out.add(kindOf(said)));
			return out;
		});
		return kinds.contains(kindOf(name)) || taskKillsEver().containsKey(name);
	}

	SourceRow clue(String tier)
	{
		return find(board.sources(), r -> r.name, "Clue Scroll (" + tier + ")", false);
	}

	String bossLootSource(Boss b)
	{
		String kind = kindOf(b.name);
		SourceRow same = find(board.sources(), r -> kindOf(r.name), kind, true);
		return same != null ? same.name
			: richest(r -> namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name), b.name);
	}

	String resolveSource(String name)
	{
		String named = resolveSourceNamed(name);
		String kind = kindOf(name);
		return named != null ? named : richest(r -> kindOf(r.name).equals(kind) || namesInBrackets(r.name, name), name);
	}

	private String richest(Predicate<SourceRow> fits, String otherwise)
	{
		SourceRow best = null;
		for (SourceRow r : board.sources())
		{
			if (fits.test(r) && (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : otherwise;
	}

	String resolveSourceNamed(String name)
	{
		Map<String, String> names = board.memo("ledgerNames", () ->
		{
			Map<String, String> index = new HashMap<>();
			board.sources().forEach(r -> index.putIfAbsent(low(r.name), r.name));
			return index;
		});
		String low = low(name);
		String hit = names.get(low);
		return hit == null && low.endsWith("s") ? names.get(low.substring(0, low.length() - 1)) : hit;
	}

	private String sourceKind(String name)
	{
		Map<String, String> kinds = board.memo("sourceKinds", HashMap::new);
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
		for (Entry<String, Map<String, List<String>>> tab : taxonomy().entrySet())
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
		return board.memo("skilled", () ->
		{
			Set<String> found = new HashSet<>();
			for (String key : board.counts.counters().keySet())
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
		for (Boss b : bossRoster())
		{
			if (kindOf(b.name).equals(kind) || namesInBrackets(name, b.name) || paysOutThrough(b.name, name))
			{
				return false;
			}
		}
		return FOUGHT_AS.values().stream().flatMap(List::stream).noneMatch(npc -> kindOf(npc).equals(kind));
	}

	Tally periodKilledMost()
	{
		if (board.period.whole())
		{
			SourceRow top = null;
			for (SourceRow r : board.sources())
			{
				if (isKillSource(r.name) && (top == null || standingKills(r) > standingKills(top)))
				{
					top = r;
				}
			}
			return top == null ? null : new Tally(top.name, standingKills(top), 0);
		}
		if (board.period.session())
		{
			return most(board.store.loot.sessionLootWindow().sources, r -> r.qty);
		}
		Board.Span s = board.span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> moved = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			board.closingNow(s.closing.kcs, board.plugin.killCounts()));
		String top = topOf(moved);
		return top == null ? null : new Tally(top, moved.get(top), 0);
	}
}
