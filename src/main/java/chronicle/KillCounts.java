/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.SourceRow;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static chronicle.Json.*;

final class KillCounts
{
	private KillCounts()
	{
	}

	static Map<String, Long> reconciledKills(JsonObject clog,
		List<SourceRow> sources, Map<String, Long> chat,
		Map<String, Long> anchored)
	{
		Map<String, Long> out = clogKillCounts(clog);
		Map<String, Long> stated = killLogCounts(clog);
		foldChatCounts(stated, chat, out.keySet());
		placeByKind(out, stated, false);
		placeByKind(out, pageKillLines(clog), true);
		placeByKind(out, sourceKills(clog, sources, false), true);
		placeByKind(out, respelled(anchored, out.keySet()), false);
		return out;
	}

	static long spineKills(JsonObject clog, List<SourceRow> sources,
		Map<String, Long> reconciled)
	{
		return spineKillKeys(clog, sources, reconciled).stream().mapToLong(k -> reconciled.getOrDefault(k, 0L)).sum();
	}

	static Set<String> spineKillKeys(JsonObject clog, List<SourceRow> sources,
		Map<String, Long> reconciled)
	{
		Map<String, String> byKind = new HashMap<>();
		reconciled.keySet().forEach(key -> byKind.putIfAbsent(chatKind(key), key));
		Set<String> out = new LinkedHashSet<>();
		for (String name : sourceKills(clog, sources, true).keySet())
		{
			String key = reconciled.containsKey(name) ? name : byKind.get(chatKind(name));
			if (key != null)
			{
				out.add(key);
			}
		}
		return out;
	}


	private static Map<String, Long> sourceKills(JsonObject clog,
		List<SourceRow> sources, boolean raiseToPage)
	{
		Map<String, Long> paged = clogKillCounts(clog);
		Map<String, String> byKind = new HashMap<>();
		paged.keySet().forEach(name -> byKind.put(kindOf(name), name));
		Map<String, Long> stated = killLogCounts(clog);
		Map<String, Long> statedByKind = new HashMap<>();
		stated.entrySet().forEach(e -> statedByKind.putIfAbsent(kindOf(e.getKey()), e.getValue()));
		Map<String, Long> out = new LinkedHashMap<>();
		for (SourceRow r : sources)
		{
			long kills = Math.max(r.kc, r.loots);
			Long agreed = r.kc > 0 ? statedByKind.get(kindOf(r.name)) : null;
			if (agreed != null && agreed.longValue() == r.kc && r.loots > r.kc)
			{
				kills = r.kc;
			}
			String name = r.name;
			String known = byKind.get(kindOf(r.name));
			if (known != null)
			{
				if (raiseToPage)
				{
					kills = Math.max(kills, paged.get(known));
				}
				name = known;
			}
			if (kills > 0)
			{
				out.merge(name, kills, Math::max);
			}
		}
		return out;
	}

	static int anchorRank(String src)
	{
		return "chat".equals(src) ? 3 : "log".equals(src) ? 2 : 1;
	}

	static Map<String, Long> killLogCounts(JsonObject clog)
	{
		return positives(clog, "slayer_kcs");
	}

	static Map<String, Long> positives(JsonObject o, String key)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (var e : obj(o, key).entrySet())
		{
			long v = asLong(e.getValue());
			if (v > 0)
			{
				out.put(e.getKey(), v);
			}
		}
		return out;
	}

	static Map<String, Long> clogKillCounts(JsonObject clog)
	{
		Map<String, Long> out = positives(clog, "kcs");
		if (clog != null && isObject(clog, "kcs"))
		{
			out.putAll(pageKillLines(clog));
		}
		return out;
	}

	static Map<String, Long> pageKillLines(JsonObject clog)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (var pg : objects(obj(clog, "kc_lines")))
		{
			for (var ln : pg.getValue().entrySet())
			{
				String label = ln.getKey().toLowerCase(Locale.ROOT);
				if ((label.contains("kill") || label.contains("completion")) && asLong(ln.getValue()) > 0)
				{
					out.put(pg.getKey(), asLong(ln.getValue()));
					break;
				}
			}
		}
		return out;
	}

	static void foldChatCounts(Map<String, Long> out,
		Map<String, Long> chat, Set<String> vocabulary)
	{
		if (out == null || chat == null || chat.isEmpty())
		{
			return;
		}
		Map<String, String> byKind = new HashMap<>();
		out.keySet().forEach(name -> byKind.putIfAbsent(chatKind(name), name));
		vocabulary.forEach(name -> byKind.putIfAbsent(chatKind(name), name));
		chat.entrySet().forEach(e -> out.merge(spokenAs(byKind, e.getKey()), e.getValue(), Math::max));
	}

	private static String spokenAs(Map<String, String> byKind, String said)
	{
		String known = byKind.get(chatKind(said));
		int space = said.indexOf(' ');
		if (known == null && space > 0)
		{
			known = byKind.get(chatKind(said.substring(space + 1)));
		}
		if (known == null)
		{
			known = byKind.get(chatKind(said + " chests"));
		}
		return known != null ? known : said;
	}

	private static Map<String, Long> respelled(Map<String, Long> said, Set<String> names)
	{
		Map<String, String> byKind = new HashMap<>();
		names.forEach(name -> byKind.putIfAbsent(chatKind(name), name));
		Map<String, Long> out = new LinkedHashMap<>();
		said.entrySet().forEach(e -> out.merge(spokenAs(byKind, e.getKey()), e.getValue(), Math::max));
		return out;
	}

	private static void placeByKind(Map<String, Long> out,
		Map<String, Long> stated, boolean floor)
	{
		if (out == null || stated == null || stated.isEmpty())
		{
			return;
		}
		Map<String, String> byKind = new HashMap<>();
		out.keySet().forEach(name -> byKind.putIfAbsent(chatKind(name), name));
		for (var e : stated.entrySet())
		{
			String known = byKind.get(chatKind(e.getKey()));
			String name = known != null ? known : e.getKey();
			if (floor)
			{
				out.merge(name, e.getValue(), Math::max);
			}
			else
			{
				out.put(name, e.getValue());
			}
			byKind.putIfAbsent(chatKind(name), name);
		}
	}

	static String chatKind(String name)
	{
		String n = kindOf(name);
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	static String kindOf(String name)
	{
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		if (n.endsWith("ies"))
		{
			return n.substring(0, n.length() - 3) + "y";
		}
		return n.endsWith("s") ? n.substring(0, n.length() - 1) : n;
	}
}
