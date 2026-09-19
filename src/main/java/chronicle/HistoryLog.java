/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The journal's calendar spine: one JSON line per day per account, appended to
 * {@code <slug>.history.jsonl} beside the journal. A line is
 * that day's closing baseline ({@code {"date","skills","counters","kcs"}}). A
 * period's gain is one baseline minus another. Login, rollover and logout all
 * append, and the later one replaces the day's earlier line rather than stacking
 * beside it, so a date appears once. Readers still take the last line for a date
 * and skip a torn one, which is what makes the replacement safe. The History tab
 * and PaceBook read it back.
 *
 * <p>A day's line is that day's close, the way the site's nightly snapshot was:
 * the first append after midnight first sets the previous day's line to the
 * state it finds (the nearest thing to a midnight close the plugin has), and
 * only then opens today's line from it. A session run past midnight therefore
 * leaves its evening on the day it was played rather than on the day after.
 */
@Slf4j
class HistoryLog
{
	// The spine sits beside the journal's own <slug>.json.
	static final String SPINE_SUFFIX = ".history.jsonl";

	private final Gson gson;

	HistoryLog(Gson gson)
	{
		this.gson = gson;
	}

	// Last date appended, per account, for this session; gates the rollover append
	// and names the day the first append after midnight closes.
	// Keyed per account: two characters played on the same day each still get a line.
	private final Map<String, String> lastAppendedDate = new java.util.concurrent.ConcurrentHashMap<>();

	/** Append today's closing baseline. Called at login-load, day rollover and logout. */
	synchronized void append(File dir, String rsn, Map<String, Long> skills,
		Map<String, Long> counters, Map<String, Long> kcs)
	{
		append(dir, rsn, skills, counters, kcs, LocalDate.now(ZoneId.systemDefault()));
	}

	/**
	 * The dated form: {@code today} is the day this state belongs to. When this
	 * process last appended under an earlier date, nothing has closed that day
	 * since, so the state is written under it first (its close) and then under
	 * {@code today} (today's opening baseline, to be replaced as the day goes).
	 */
	synchronized void append(File dir, String rsn, Map<String, Long> skills,
		Map<String, Long> counters, Map<String, Long> kcs, LocalDate today)
	{
		if (rsn == null || rsn.isEmpty() || today == null)
		{
			return;
		}
		String slug = LocalStore.slug(rsn);
		String date = today.toString();
		JsonObject state = new JsonObject();
		JsonObject sk = new JsonObject();
		if (skills != null)
		{
			skills.forEach(sk::addProperty);
		}
		state.add("skills", sk);
		JsonObject ct = new JsonObject();
		if (counters != null)
		{
			counters.forEach(ct::addProperty);
		}
		state.add("counters", ct);
		// kcs arrived after the rest. Older lines on the stream carry none.
		JsonObject kc = new JsonObject();
		if (kcs != null)
		{
			kcs.forEach(kc::addProperty);
		}
		state.add("kcs", kc);
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create history dir {}", dir);
				return;
			}
			File f = new File(dir, slug + SPINE_SUFFIX);
			// The day turned since this process last wrote: that day's line still
			// holds its login-time state, so close it at the state in hand before
			// today's line starts from the same point.
			String previous = lastAppendedDate.get(slug);
			if (previous != null && previous.compareTo(date) < 0)
			{
				writeLine(f, previous, state);
			}
			writeLine(f, date, state);
			lastAppendedDate.put(slug, date);
		}
		catch (Exception e)   // best-effort; the next append retries
		{
			log.debug("history append failed", e);
		}
	}

	// One line for `date` carrying `state`, in place of any line the tail already
	// holds for that date.
	private void writeLine(File f, String date, JsonObject state) throws java.io.IOException
	{
		JsonObject line = new JsonObject();
		line.addProperty("date", date);
		for (Map.Entry<String, com.google.gson.JsonElement> e : state.entrySet())
		{
			line.add(e.getKey(), e.getValue());
		}
		// Appends are chronological, so any line already bearing this date is at
		// the tail. Cut it and let this one stand in its place: the reader would
		// have taken the last of them anyway.
		dropTrailingDate(f, date);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
		{
			w.write(gson.toJson(line));
			w.write('\n');
		}
	}

	static final class Baseline
	{
		final Map<String, Long> skills = new java.util.HashMap<>();
		final Map<String, Long> counters = new java.util.HashMap<>();
		final Map<String, Long> kcs = new java.util.HashMap<>();
		/**
		 * A complete snapshot: the line carries "overall" and the skills it
		 * lists sum exactly to it, so a skill it does not list stood at zero
		 * that day. A line with no overall, or one whose parts do not add up to
		 * the overall it carries, speaks only for the skills it names. Derived
		 * as the line is parsed; the stored format carries no such field.
		 */
		boolean complete;
	}

	// Whether the skills a line lists account for the overall it carries.
	private static boolean whole(Map<String, Long> skills)
	{
		Long overall = skills.get("overall");
		if (overall == null)
		{
			return false;
		}
		long sum = 0;
		for (Map.Entry<String, Long> e : skills.entrySet())
		{
			if (!"overall".equals(e.getKey()) && e.getValue() != null)
			{
				sum += e.getValue();
			}
		}
		return sum == overall;
	}

	/**
	 * The state standing on {@code on}: every line dated up to and including it,
	 * applied in date order. A complete snapshot replaces the skills wholly, a
	 * skill it does not list having been at zero that day, so every skill the
	 * record had carried by then is written down at zero and the line's own
	 * figures stand over them. A partial line merges instead: a skill it does not
	 * list keeps the value carried to it. Counters and kill counts always merge,
	 * being cumulative. A key no line has recorded by then stays absent, and the
	 * state says whether it rests on a complete snapshot, which is what makes an
	 * absent skill readable as zero rather than as silence.
	 *
	 * <p>A fresh state, never one of the spine's own baselines: the caller may
	 * hold it as long as it likes.
	 */
	static Baseline stateAt(java.util.TreeMap<LocalDate, Baseline> spine, LocalDate on)
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

	/**
	 * The baseline a period is measured from: the last line closed before
	 * {@code start}, or, when nothing predates the window, the earliest line on
	 * record up to {@code end}. The site measured the same way, from its first
	 * snapshot when none came before the window, so a fresh record's first week
	 * reads from its first day rather than as nothing. Null when no line is dated
	 * on or before {@code end}.
	 */
	static Map.Entry<LocalDate, Baseline> windowStart(
		java.util.TreeMap<LocalDate, Baseline> spine, LocalDate start, LocalDate end)
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

	/**
	 * Each key's earliest recorded value on any line dated up to {@code upTo},
	 * inclusive. The base for a key the start line does not carry: a counter or
	 * kill count first minted inside the window, or a skill the imported past
	 * predates. A recorded value, never absence read as zero.
	 */
	static Baseline earliest(java.util.TreeMap<LocalDate, Baseline> spine, LocalDate upTo)
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

	/**
	 * The date of the earliest line whose counters carry {@code key}, or carry
	 * any counter at all when {@code key} is null; null when no line does. What
	 * the History tab says a period's counters measure from: imported baselines
	 * predate the counters, and the journal-derived totals (dropsReceived and
	 * the rest) joined the line later than the trackers.
	 */
	static LocalDate firstCarrying(java.util.SortedMap<LocalDate, Baseline> spine, String key)
	{
		if (spine == null)
		{
			return null;
		}
		for (Map.Entry<LocalDate, Baseline> e : spine.entrySet())
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

	/**
	 * Positive gains from {@code start} to {@code end}, per key, in {@code end}'s
	 * order. A key the start side lacks measures from its earliest recorded value
	 * instead; a key recorded nowhere before the end line has no gain to show.
	 */
	static Map<String, Long> gained(Map<String, Long> start, Map<String, Long> earliest,
		Map<String, Long> end)
	{
		return gained(start, earliest, end, false);
	}

	/**
	 * The same, measured from a state that rests on a complete snapshot: a key
	 * the start side lacks was at zero there, so it measures its whole standing
	 * figure. Only skills are read this way. A counter or a kill count absent
	 * from a complete line was never recorded rather than zero, and keeps the
	 * earliest-recorded base.
	 */
	static Map<String, Long> gained(Map<String, Long> start, Map<String, Long> earliest,
		Map<String, Long> end, boolean startComplete)
	{
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (end == null)
		{
			return out;
		}
		for (Map.Entry<String, Long> e : end.entrySet())
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

	/**
	 * The levels a state draws: one per skill asked for, the total they sum to,
	 * how many drew a level at all and how many stand at 99. A level of 0 is a
	 * skill the state cannot speak for.
	 */
	static final class Levels
	{
		final Map<String, Integer> of = new java.util.LinkedHashMap<>();
		int total;
		int drawn;
		int nines;
	}

	// The one skill no account can stand below: a new character is made at
	// 1,154 hitpoints xp, level 10. PaceBook.levelAt stays the pure curve,
	// since its own next-level arithmetic needs it unclamped.
	private static final String HITPOINTS = "hitpoints";
	private static final int HITPOINTS_FLOOR = 10;

	/**
	 * Read {@code skills} off {@code state}, in the order asked for. A skill the
	 * state carries draws the level its xp has reached. A skill it does not
	 * carry draws level 1 when the state rests on a complete snapshot: a
	 * complete line lists every skill that had any xp at all, so one no line
	 * ever listed stood at zero, and zero xp is level 1. The total then counts
	 * every skill in the game, which is how the site reads it. On a record of
	 * partial lines alone the skill draws nothing, absence there being silence
	 * rather than zero.
	 *
	 * <p>Hitpoints alone has a floor. Every account is made at 1,154 hitpoints
	 * xp, which is level 10, so a state reading below that is reading below the
	 * game's own minimum: an imported line carrying a rounded figure, or a
	 * complete line omitting hitpoints, which says zero xp and is the same
	 * impossibility. Wherever the state speaks for hitpoints at all it draws
	 * 10 at the least, which is what the site publishes.
	 */
	static Levels levels(Baseline state, java.util.List<String> skills)
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

	/**
	 * Cut any trailing lines already carrying {@code date}, so the caller's line
	 * becomes the only one for that day. Walks back from the end because appends
	 * are in date order. Leaves the file untouched if the tail is a different day,
	 * and gives up quietly on anything it cannot read.
	 */
	private static void dropTrailingDate(File f, String date)
	{
		if (!f.isFile())
		{
			return;
		}
		String needle = "\"date\":\"" + date + "\"";
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw"))
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
				keep = start;
			}
			if (keep < raf.length())
			{
				raf.setLength(keep);
			}
		}
		catch (Exception e)   // best-effort; a plain append still reads correctly
		{
			log.debug("history tail trim failed", e);
		}
	}

	// Offset just past the newline before `end`, i.e. where that last line begins.
	private static long lineStart(java.io.RandomAccessFile raf, long end) throws java.io.IOException
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

	/**
	 * Rewrite the spine keeping one line per date, the last, in date order. Only
	 * touches a file that repeats a date, which older builds produced by
	 * appending at login, rollover and logout, or one whose dates are out of
	 * order, which an import leaves behind. Writes a sibling first and renames
	 * over the top, so an interrupted compaction leaves the original standing.
	 * A line's own content is never touched, only its place in the file.
	 * Returns the number of lines dropped.
	 *
	 * <p>A rewrite can only write back what it could read, so a file holding a
	 * line this cannot parse is left standing: a torn write or a hand-edited
	 * line would be destroyed by the rename, and the spine is the only copy of
	 * the record. The reader skips such a line and the file still reads; the
	 * repeated dates it also holds cost nothing but their bytes.
	 */
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
		java.util.TreeMap<String, String> keep = new java.util.TreeMap<>();
		int seen = 0;
		int unreadable = 0;
		// ISO dates sort as text, so the map's own order is the calendar's.
		String previous = null;
		boolean ordered = true;
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
			new java.io.FileInputStream(f), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				String date;
				try
				{
					JsonObject o = gson.fromJson(line, JsonObject.class);
					date = o != null && o.has("date") ? o.get("date").getAsString() : null;
				}
				catch (RuntimeException torn)
				{
					unreadable++;   // same torn line the reader skips
					continue;
				}
				if (date == null)
				{
					unreadable++;   // a line with no day has no place to be put
					continue;
				}
				seen++;
				if (previous != null && date.compareTo(previous) < 0)
				{
					ordered = false;
				}
				previous = date;
				keep.put(date, line);   // last for a date wins, as the reader has it
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
			// on the platter before the rename, or a crash between the two
			// leaves the record standing as an empty file
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
			java.nio.file.Files.move(tmp.toPath(), f.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

	java.util.TreeMap<LocalDate, Baseline> read(File dir, String rsn)
	{
		java.util.TreeMap<LocalDate, Baseline> out = new java.util.TreeMap<>();
		File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
		if (!f.isFile())
		{
			return out;
		}
		try (java.io.BufferedReader r = new java.io.BufferedReader(
			new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8)))
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
					out.put(date, b);   // later lines for a date overwrite: last wins
				}
				catch (RuntimeException torn)
				{
					// torn tail from a crash, skip it
				}
			}
		}
		catch (Exception e)
		{
			log.debug("history read failed", e);
		}
		return out;
	}

	private static void fill(JsonObject o, String key, Map<String, Long> into)
	{
		if (o.has(key) && o.get(key).isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: o.getAsJsonObject(key).entrySet())
			{
				try
				{
					into.put(e.getKey(), e.getValue().getAsLong());
				}
				catch (RuntimeException ignored)
				{
					// non-numeric value, skip
				}
			}
		}
	}

	/**
	 * Fold another spine file into this account's, keeping only dates not already
	 * on record. Readers take the last line for a date, and an import must never
	 * sit on top of a day this client measured itself.
	 *
	 * @return how many days came across.
	 */
	synchronized int importSpine(File dir, String rsn, File source)
	{
		if (rsn == null || rsn.isEmpty() || source == null || !source.isFile())
		{
			return 0;
		}
		java.util.Set<String> have = read(dir, rsn).keySet().stream()
			.map(java.time.LocalDate::toString)
			.collect(java.util.stream.Collectors.toSet());
		int added = 0;
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
			new java.io.FileInputStream(source), StandardCharsets.UTF_8)))
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
					String date;
					try
					{
						JsonObject o = gson.fromJson(line, JsonObject.class);
						date = o != null && o.has("date") ? o.get("date").getAsString() : null;
					}
					catch (RuntimeException torn)
					{
						continue;   // half-written source line, skipped as on read
					}
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

	/**
	 * True while no baseline has been appended for this account today. A predicate,
	 * not a latch: it keeps saying true until append() records today's date. Two
	 * callers ahead of an append both see it.
	 */
	boolean dayRolledOver(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return false;   // nothing to key on; append() refuses a nameless account too
		}
		String today = LocalDate.now(ZoneId.systemDefault()).toString();
		return !today.equals(lastAppendedDate.get(LocalStore.slug(rsn)));
	}
}
