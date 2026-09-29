/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import static chronicle.Json.*;

final class Merge
{
	private Merge()
	{
	}

	static JsonObject mergeClog(JsonObject base, JsonObject inc)
	{
		JsonObject out = new JsonObject();
		out.add("by_cat", nested(base, inc, "by_cat", false));
		for (String key : new String[]{"kc_lines", "pb_lines"})
		{
			JsonObject lines = nested(base, inc, key, key.equals("pb_lines"));
			if (lines.size() > 0)
			{
				out.add(key, lines);
			}
		}
		for (String mapKey : new String[]{"kcs", "clog_items", "cat_counts", "slayer_kcs"})
		{
			JsonObject merged = new JsonObject();
			for (JsonObject src : new JsonObject[]{base, inc})
			{
				for (var e : obj(src, mapKey).entrySet())
				{
					raise(merged, e.getKey(), asLong(e.getValue()));
				}
			}
			if (merged.size() > 0)
			{
				out.add(mapKey, merged);
			}
		}
		for (String numKey : new String[]{"finished", "available"})
		{
			out.addProperty(numKey, Math.max(asLong(base.get(numKey)), asLong(inc.get(numKey))));
		}
		return out;
	}

	static JsonObject nested(JsonObject base, JsonObject inc, String key, boolean least)
	{
		JsonObject all = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			for (var pg : objects(obj(src, key)))
			{
				JsonObject tgt = sub(all, pg.getKey());
				for (var ln : pg.getValue().entrySet())
				{
					long n = asLong(ln.getValue());
					if (least ? n > 0 && (!tgt.has(ln.getKey()) || n < asLong(tgt.get(ln.getKey())))
						: n > asLong(tgt.get(ln.getKey())))
					{
						tgt.addProperty(ln.getKey(), n);
					}
				}
			}
		}
		return all;
	}

	static void importSource(JsonObject cur, JsonObject inc)
	{
		for (String k : new String[]{"kc", "loots", "value", "last_seen"})
		{
			floorNumber(cur, inc, k);
		}
		long incFirst = asLong(inc.get("first_seen"));
		long curFirst = asLong(cur.get("first_seen"));
		if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
		{
			cur.addProperty("first_seen", incFirst);
		}
		double incPb = asDouble(inc.get("pb"));
		if (incPb > 0 && (!cur.has("pb") || incPb < cur.get("pb").getAsDouble()))
		{
			cur.addProperty("pb", incPb);
		}
		if (!isObject(inc, "items"))
		{
			return;
		}
		JsonObject bag = sub(cur, "items");
		for (var ie : objects(inc.getAsJsonObject("items")))
		{
			JsonObject incItem = ie.getValue();
			String incName = str(incItem, "name", ie.getKey());
			String key = keyNamed(bag, incName);
			if (key == null)
			{
				key = incItem.has("id") && incItem.get("id").getAsInt() > 0
					? bagKey(incItem.get("id").getAsInt(), incName) : ie.getKey();
			}
			JsonObject curItem = sub(bag, key);
			floorNumber(curItem, incItem, "qty");
			floorNumber(curItem, incItem, "value");
			if (!curItem.has("name"))
			{
				curItem.addProperty("name", incName);
			}
			if (!curItem.has("id") && incItem.has("id"))
			{
				curItem.add("id", incItem.get("id"));
			}
		}
	}

	static JsonObject nearestSegment(JsonArray tasks, JsonObject inc)
	{
		String task = inc.has("task") ? inc.get("task").getAsString() : null;
		JsonObject best = null;
		long bestGap = SEGMENT_MATCH_SECONDS + 1;
		for (JsonElement e : tasks)
		{
			JsonObject seg = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
			if (task != null && seg.has("task") && task.equalsIgnoreCase(seg.get("task").getAsString())
				&& Math.abs(asLong(seg.get("ts")) - asLong(inc.get("ts"))) < bestGap)
			{
				bestGap = Math.abs(asLong(seg.get("ts")) - asLong(inc.get("ts")));
				best = seg;
			}
		}
		return best;
	}

	private static final long SEGMENT_MATCH_SECONDS = 60;

	static void mergeSegmentDetail(JsonObject seg, JsonObject inc, String key)
	{
		if (isObject(inc, key))
		{
			mergeRows(sub(seg, key), inc.getAsJsonObject(key), true);
		}
	}

	static void mergeRows(JsonObject cur, JsonObject inc, boolean keepValue)
	{
		for (var e : inc.entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				raise(cur, e.getKey(), asLong(e.getValue()));
				continue;
			}
			JsonObject incRow = e.getValue().getAsJsonObject();
			JsonObject curRow = sub(cur, e.getKey());
			floorNumber(curRow, incRow, "qty");
			if (!keepValue || asLong(curRow.get("value")) <= 0)
			{
				floorNumber(curRow, incRow, "value");
			}
			floorNumber(curRow, incRow, "kills");
			if (!curRow.has("id") && incRow.has("id"))
			{
				curRow.add("id", incRow.get("id"));
			}
		}
	}

	static String feedKey(JsonObject e)
	{
		long sec = asLong(e.get("ts")) / 1000L;
		String kind = str(e, "type", "");
		return kind + "|" + sec + "|" + feedSubject(e);
	}

	private static String feedSubject(JsonObject e)
	{
		if (!isObject(e, "data"))
		{
			return "";
		}
		JsonObject d = e.getAsJsonObject("data");
		for (String field : new String[]{"itemName", "petName", "questName",
			"killerName", "area", "skill", "task", "monster",
			"name", "quest", "diary", "achievement"})
		{
			if (present(d, field))
			{
				return d.get(field).getAsString().toLowerCase(Locale.ROOT);
			}
		}
		JsonObject bare = d.deepCopy();
		bare.remove("imported");
		bare.remove("type");
		return bare.toString();
	}

	private static void floorNumber(JsonObject cur, JsonObject inc, String key)
	{
		if (present(inc, key))
		{
			raise(cur, key, asLong(inc.get(key)));
		}
	}

	static String keyNamed(JsonObject bag, String name)
	{
		for (var e : objects(bag))
		{
			if (name.equalsIgnoreCase(str(e.getValue(), "name", null)))
			{
				return e.getKey();
			}
		}
		return null;
	}

	static String bagKey(int id, String name)
	{
		return id > 0 ? String.valueOf(id)
			: "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
	}
}
