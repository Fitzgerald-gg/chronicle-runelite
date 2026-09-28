/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class HistoryLog
{
	static final String SPINE_SUFFIX = ".history.jsonl";

	private final Gson gson;

	HistoryLog(Gson gson)
	{
		this.gson = gson;
	}

	private final Map<String, String> lastAppendedDate = new ConcurrentHashMap<>();

	synchronized Adjust append(File dir, String rsn, Map<String, Long> skills,
		Map<String, Long> counters, Map<String, Long> kcs, int kv, Adjust adj, LocalDate today)
	{
		Adjust unwritten = new Adjust();
		unwritten.add(adj);
		if (rsn == null || rsn.isEmpty() || today == null)
		{
			return unwritten;
		}
		String slug = LocalStore.slug(rsn);
		String date = today.toString();
		JsonObject state = new JsonObject();
		state.add("skills", tree(skills));
		state.add("counters", tree(counters));
		state.add("kcs", tree(kcs));
		state.addProperty("kv", kv);
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create history dir {}", dir);
				return unwritten;
			}
			File f = new File(dir, slug + SPINE_SUFFIX);
			String previous = lastAppendedDate.get(slug);
			if (previous != null && previous.compareTo(date) < 0)
			{
				writeLine(f, previous, state, adj);
				unwritten = new Adjust();
				writeLine(f, date, state, null);
			}
			else
			{
				writeLine(f, date, state, adj);
			}
			lastAppendedDate.put(slug, date);
			return null;
		}
		catch (Unwritten e)
		{
			log.debug("history append failed", e);
			return e.adj;
		}
		catch (Exception e)
		{
			log.debug("history append failed", e);
			return unwritten;
		}
	}

	private static final class Unwritten extends IOException
	{
		final transient Adjust adj;

		Unwritten(Adjust adj, IOException cause)
		{
			super(cause);
			this.adj = adj;
		}
	}

	private void writeLine(File f, String date, JsonObject state, Adjust adj) throws IOException
	{
		JsonObject line = new JsonObject();
		line.addProperty("date", date);
		for (var e : state.entrySet())
		{
			line.add(e.getKey(), e.getValue());
		}
		Adjust carried = dropTrailingDate(f, date);
		carried.add(adj);
		if (!carried.isEmpty())
		{
			line.add("adj", carried.toJson());
		}
		try
		{
			boolean open = endsMidLine(f);
			try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
			{
				if (open)
				{
					w.write('\n');
				}
				w.write(gson.toJson(line));
				w.write('\n');
			}
		}
		catch (IOException e)
		{
			throw new Unwritten(carried, e);
		}
	}

	private static boolean endsMidLine(File f) throws IOException
	{
		if (!f.isFile() || f.length() == 0)
		{
			return false;
		}
		try (RandomAccessFile raf = new RandomAccessFile(f, "r"))
		{
			raf.seek(raf.length() - 1);
			return raf.read() != '\n';
		}
	}

	static final class Baseline
	{
		final Map<String, Long> skills = new HashMap<>();
		final Map<String, Long> counters = new HashMap<>();
		final Map<String, Long> kcs = new HashMap<>();
		boolean complete;
		int kv = -1;
		final Adjust adj = new Adjust();
	}

	static final class Adjust
	{
		final Map<String, Long> kcs = new HashMap<>();
		final Map<String, Long> counters = new HashMap<>();

		void add(Adjust other)
		{
			if (other == null)
			{
				return;
			}
			other.kcs.forEach((k, v) -> kcs.merge(k, v, Long::sum));
			other.counters.forEach((k, v) -> counters.merge(k, v, Long::sum));
			kcs.values().removeIf(v -> v == 0);
			counters.values().removeIf(v -> v == 0);
		}

		boolean isEmpty()
		{
			return kcs.isEmpty() && counters.isEmpty();
		}

		JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			if (!kcs.isEmpty())
			{
				o.add("kcs", tree(kcs));
			}
			if (!counters.isEmpty())
			{
				o.add("counters", tree(counters));
			}
			return o;
		}

		static Adjust from(JsonElement e)
		{
			Adjust a = new Adjust();
			if (e != null && e.isJsonObject())
			{
				fill(e.getAsJsonObject(), "kcs", a.kcs);
				fill(e.getAsJsonObject(), "counters", a.counters);
			}
			return a;
		}
	}

	private static JsonObject tree(Map<String, Long> m)
	{
		JsonObject o = new JsonObject();
		if (m != null)
		{
			m.forEach(o::addProperty);
		}
		return o;
	}

	private static boolean whole(Map<String, Long> skills)
	{
		Long overall = skills.get("overall");
		if (overall == null)
		{
			return false;
		}
		long sum = 0;
		for (var e : skills.entrySet())
		{
			if (!"overall".equals(e.getKey()) && e.getValue() != null)
			{
				sum += e.getValue();
			}
		}
		return sum == overall;
	}

	static Baseline stateAt(TreeMap<LocalDate, Baseline> spine, LocalDate on)
	{
		Baseline out = new Baseline();
		if (spine == null || on == null)
		{
			return out;
		}
		for (Baseline b : spine.headMap(on, true).values())
		{
			if (b == null)
			{
				continue;
			}
			if (b.complete)
			{
				out.skills.replaceAll((key, value) -> 0L);
				out.complete = true;
			}
			out.skills.putAll(b.skills);
			out.counters.putAll(b.counters);
			out.kcs.putAll(b.kcs);
		}
		return out;
	}

	static Map.Entry<LocalDate, Baseline> windowStart(
		TreeMap<LocalDate, Baseline> spine, LocalDate start, LocalDate end)
	{
		if (spine == null || spine.isEmpty() || start == null || end == null)
		{
			return null;
		}
		Map.Entry<LocalDate, Baseline> before = spine.floorEntry(start.minusDays(1));
		if (before != null)
		{
			return before;
		}
		Map.Entry<LocalDate, Baseline> first = spine.firstEntry();
		return first.getKey().isAfter(end) ? null : first;
	}

	static Baseline earliest(TreeMap<LocalDate, Baseline> spine, LocalDate upTo)
	{
		Baseline out = new Baseline();
		if (spine == null || upTo == null)
		{
			return out;
		}
		for (Baseline b : spine.headMap(upTo, true).values())
		{
			if (b == null)
			{
				continue;
			}
			b.skills.forEach(out.skills::putIfAbsent);
			b.counters.forEach(out.counters::putIfAbsent);
			b.kcs.forEach(out.kcs::putIfAbsent);
		}
		return out;
	}

	static LocalDate firstCarrying(SortedMap<LocalDate, Baseline> spine, String key)
	{
		if (spine == null)
		{
			return null;
		}
		for (var e : spine.entrySet())
		{
			Baseline b = e.getValue();
			if (b == null)
			{
				continue;
			}
			if (key == null ? !b.counters.isEmpty() : b.counters.containsKey(key))
			{
				return e.getKey();
			}
		}
		return null;
	}

	static Map<String, Long> gained(Map<String, Long> start, Map<String, Long> earliest,
		Map<String, Long> end)
	{
		return gained(start, earliest, end, false);
	}

	static Map<String, Long> gained(Map<String, Long> start, Map<String, Long> earliest,
		Map<String, Long> end, boolean startComplete)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (end == null)
		{
			return out;
		}
		for (var e : end.entrySet())
		{
			if (e.getValue() == null)
			{
				continue;
			}
			Long base = start != null ? start.get(e.getKey()) : null;
			if (base == null && startComplete)
			{
				base = 0L;
			}
			if (base == null && earliest != null)
			{
				base = earliest.get(e.getKey());
			}
			if (base == null)
			{
				continue;
			}
			long d = e.getValue() - base;
			if (d > 0)
			{
				out.put(e.getKey(), d);
			}
		}
		return out;
	}

	static final class Levels
	{
		final Map<String, Integer> of = new LinkedHashMap<>();
		final Map<String, Integer> virtual = new LinkedHashMap<>();
		int total;
		int drawn;
		int nines;
	}

	private static final String HITPOINTS = "hitpoints";
	private static final int HITPOINTS_FLOOR = 10;

	static Levels levels(Baseline state, List<String> skills)
	{
		Levels out = new Levels();
		if (state == null || skills == null)
		{
			return out;
		}
		for (String key : skills)
		{
			Long xp = state.skills.get(key);
			int level = xp != null ? PaceBook.levelAt(xp) : state.complete ? 1 : 0;
			if (HITPOINTS.equals(key) && level > 0)
			{
				level = Math.max(HITPOINTS_FLOOR, level);
			}
			out.of.put(key, level);
			int past = xp != null ? PaceBook.virtualLevelAt(xp) : level;
			if (HITPOINTS.equals(key) && past > 0)
			{
				past = Math.max(HITPOINTS_FLOOR, past);
			}
			out.virtual.put(key, past);
			out.total += level;
			if (level > 0)
			{
				out.drawn++;
			}
			if (level >= 99)
			{
				out.nines++;
			}
		}
		return out;
	}

	private Adjust dropTrailingDate(File f, String date)
	{
		Adjust carried = new Adjust();
		if (!f.isFile())
		{
			return carried;
		}
		String needle = "\"date\":\"" + date + "\"";
		boolean found = false;
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw"))
		{
			long keep = raf.length();
			while (keep > 0)
			{
				long start = lineStart(raf, keep);
				raf.seek(start);
				byte[] buf = new byte[(int) (keep - start)];
				raf.readFully(buf);
				String line = new String(buf, StandardCharsets.UTF_8).trim();
				if (!line.isEmpty() && !line.contains(needle))
				{
					break;
				}
				if (!line.isEmpty() && !found)
				{
					JsonObject o = parseLine(line, needle);
					if (o != null)
					{
						carried.add(Adjust.from(o.get("adj")));
						found = true;
					}
				}
				keep = start;
			}
			if (keep < raf.length())
			{
				raf.setLength(keep);
			}
		}
		catch (Exception e)
		{
			log.debug("history tail trim failed", e);
		}
		return carried;
	}

	private JsonObject parseLine(String line, String needle)
	{
		for (String text : new String[]{line, line.substring(Math.max(0, line.lastIndexOf("{" + needle)))})
		{
			try
			{
				JsonObject o = gson.fromJson(text, JsonObject.class);
				if (o != null)
				{
					return o;
				}
			}
			catch (RuntimeException torn)
			{
			}
		}
		return null;
	}

	private static long lineStart(RandomAccessFile raf, long end) throws IOException
	{
		long i = end - 1;
		while (i > 0)
		{
			raf.seek(i - 1);
			if (raf.read() == '\n')
			{
				return i;
			}
			i--;
		}
		return 0;
	}

	static Integer newestKv(Gson gson, File dir, String rsn)
	{
		File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
		if (!f.isFile())
		{
			return null;
		}
		try (RandomAccessFile raf = new RandomAccessFile(f, "r"))
		{
			long end = raf.length();
			while (end > 0)
			{
				long start = lineStart(raf, end);
				raf.seek(start);
				byte[] buf = new byte[(int) (end - start)];
				raf.readFully(buf);
				end = start;
				String line = new String(buf, StandardCharsets.UTF_8).trim();
				if (line.isEmpty())
				{
					continue;
				}
				try
				{
					JsonObject o = gson.fromJson(line, JsonObject.class);
					if (o != null && o.has("date"))
					{
						return o.has("kv") ? o.get("kv").getAsInt() : 0;
					}
				}
				catch (RuntimeException torn)
				{
				}
			}
		}
		catch (IOException e)
		{
			log.debug("history tail unreadable", e);
		}
		return null;
	}
	synchronized int compact(File dir, String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return 0;
		}
		File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
		if (!f.isFile())
		{
			return 0;
		}
		TreeMap<String, String> keep = new TreeMap<>();
		int seen = 0;
		int unreadable = 0;
		String previous = null;
		boolean ordered = true;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			new FileInputStream(f), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				String date = dateOf(line);
				if (date == null)
				{
					unreadable++;
					continue;
				}
				seen++;
				if (previous != null && date.compareTo(previous) < 0)
				{
					ordered = false;
				}
				previous = date;
				keep.put(date, line);
			}
		}
		catch (Exception e)
		{
			log.debug("history compaction read failed", e);
			return 0;
		}
		int dropped = seen - keep.size();
		if (dropped <= 0 && ordered)
		{
			return 0;
		}
		if (unreadable > 0)
		{
			log.debug("history spine: {} lines this cannot parse, left as it stands", unreadable);
			return 0;
		}
		File tmp = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX + ".compact");
		try (FileOutputStream out = new FileOutputStream(tmp);
			Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8))
		{
			for (String line : keep.values())
			{
				w.write(line);
				w.write('\n');
			}
			w.flush();
			out.getFD().sync();
		}
		catch (Exception e)
		{
			log.debug("history compaction write failed", e);
			return 0;
		}
		try
		{
			Files.move(tmp.toPath(), f.toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception e)
		{
			log.debug("history compaction rename failed", e);
			return 0;
		}
		log.debug("history spine: dropped {} repeated day lines, {} dates in order",
			dropped, keep.size());
		return dropped;
	}

	private String dateOf(String line)
	{
		try
		{
			JsonObject o = gson.fromJson(line, JsonObject.class);
			return o != null && o.has("date") ? o.get("date").getAsString() : null;
		}
		catch (RuntimeException torn)
		{
			return null;
		}
	}

	TreeMap<LocalDate, Baseline> read(File dir, String rsn)
	{
		TreeMap<LocalDate, Baseline> out = new TreeMap<>();
		File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
		if (!f.isFile())
		{
			return out;
		}
		try (BufferedReader r = new BufferedReader(
			new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				try
				{
					JsonObject o = gson.fromJson(line, JsonObject.class);
					if (o == null || !o.has("date"))
					{
						continue;
					}
					LocalDate date = LocalDate.parse(o.get("date").getAsString());
					Baseline b = new Baseline();
					fill(o, "skills", b.skills);
					fill(o, "counters", b.counters);
					fill(o, "kcs", b.kcs);
					b.complete = whole(b.skills);
					if (o.has("kv"))
					{
						b.kv = o.get("kv").getAsInt();
					}
					b.adj.add(Adjust.from(o.get("adj")));
					out.put(date, b);
				}
				catch (RuntimeException torn)
				{
				}
			}
		}
		catch (Exception e)
		{
			log.debug("history read failed", e);
		}
		settle(out);
		return out;
	}

	static void settle(TreeMap<LocalDate, Baseline> spine)
	{
		Map<String, Long> kcs = new HashMap<>();
		Map<String, Long> counters = new HashMap<>();
		for (Baseline b : spine.descendingMap().values())
		{
			if (b == null)
			{
				continue;
			}
			kcs.forEach((k, d) -> b.kcs.computeIfPresent(k, (key, v) -> v + d));
			counters.forEach((k, d) -> b.counters.computeIfPresent(k, (key, v) -> v + d));
			b.adj.kcs.forEach((k, d) -> kcs.merge(k, d, Long::sum));
			b.adj.counters.forEach((k, d) -> counters.merge(k, d, Long::sum));
		}
	}

	static void fill(JsonObject o, String key, Map<String, Long> into)
	{
		if (o.has(key) && o.get(key).isJsonObject())
		{
			for (var e : o.getAsJsonObject(key).entrySet())
			{
				try
				{
					into.put(e.getKey(), e.getValue().getAsLong());
				}
				catch (RuntimeException ignored)
				{
				}
			}
		}
	}

	synchronized int importSpine(File dir, String rsn, File source)
	{
		if (rsn == null || rsn.isEmpty() || source == null || !source.isFile())
		{
			return 0;
		}
		Set<String> have = read(dir, rsn).keySet().stream()
			.map(LocalDate::toString)
			.collect(Collectors.toSet());
		int added = 0;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			new FileInputStream(source), StandardCharsets.UTF_8)))
		{
			File out = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				return 0;
			}
			try (Writer w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					String date = dateOf(line);
					if (date == null || !have.add(date))
					{
						continue;
					}
					w.write(line);
					w.write('\n');
					added++;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("history import failed", e);
		}
		if (added > 0)
		{
			lastAppendedDate.remove(LocalStore.slug(rsn));
		}
		return added;
	}

	boolean dayRolledOver(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return false;
		}
		String today = LocalDate.now(ZoneId.systemDefault()).toString();
		return !today.equals(lastAppendedDate.get(LocalStore.slug(rsn)));
	}

	boolean dayTurned(String rsn)
	{
		String previous = rsn == null ? null : lastAppendedDate.get(LocalStore.slug(rsn));
		return previous != null
			&& previous.compareTo(LocalDate.now(ZoneId.systemDefault()).toString()) < 0;
	}
}
