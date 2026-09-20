/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A slayer task is ONE bucket carrying ONE stamp, and what is in it can span
 * days. That is the whole difficulty with asking the journey about a window.
 *
 * <p>A closed task is stamped when it closed, so "the tasks that closed inside
 * this window, and what they paid" is a question the journey can answer exactly,
 * and it is the question the board's own heading asks.
 *
 * <p>An open task has no close to be stamped with. It carries the time of its
 * most recent kill instead, rewritten on every kill, so ANY window catching a
 * single kill of it caught the whole thing: an assignment handed out on Tuesday
 * and still running put all of Tuesday and Wednesday's loot under a heading
 * reading "This session". Its kills did the same to the count beside them.
 *
 * <p>So a bounded window takes closed tasks only, and an unbounded one takes
 * everything, because over the whole record an open task is simply part of it.
 */
public class OpenTaskWindowTest
{
	private static final long LO = Long.MIN_VALUE / 2;
	private static final long HI = Long.MAX_VALUE / 2;

	// Both tasks are stamped inside the window below. One closed there and one
	// is merely still running, which is the pair the filter has to tell apart.
	private static final long CLOSED_TS = 1_700_500_000L;
	private static final long OPEN_TS = 1_700_500_600L;
	private static final long FROM = (CLOSED_TS - 60) * 1000L;
	private static final long TO = (OPEN_TS + 60) * 1000L;

	private static LocalStore store;

	private static final String JOURNAL =
		"{\"drops\":{},\"slayer\":{\"tasks\":["
		// closed inside the window: 20 kills, one item worth 5,000
		+ "{\"task\":\"Nechryael\",\"ts\":" + CLOSED_TS + ",\"open\":false,"
		+ "\"kills\":20,\"value\":5000,\"monsters\":{\"Nechryael\":20},"
		+ "\"items\":{\"Rune bar\":{\"id\":2363,\"qty\":4,\"value\":5000}}},"
		// STILL RUNNING, stamped by its most recent kill. Its 500 kills and its
		// 900,000 were earned over days; only the tail of it is in the window.
		+ "{\"task\":\"Abyssal demons\",\"ts\":" + OPEN_TS + ",\"open\":true,"
		+ "\"kills\":500,\"value\":900000,\"monsters\":{\"Abyssal demon\":500},"
		+ "\"items\":{\"Abyssal whip\":{\"id\":4151,\"qty\":1,\"value\":900000}}}"
		+ "]}}";

	@BeforeClass
	public static void mount() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-open-task");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "runner.json")))
		{
			w.write(JOURNAL);
		}
		store = new LocalStore(null, new Gson());
		store.load(dir, "runner");
	}

	private static long worth(List<LocalStore.BagItem> bag)
	{
		long v = 0;
		for (LocalStore.BagItem b : bag)
		{
			v += b.value;
		}
		return v;
	}

	private static boolean holds(List<LocalStore.BagItem> bag, String name)
	{
		for (LocalStore.BagItem b : bag)
		{
			if (name.equals(b.name))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * TRAP: the stamp says the open task was active in the window, and the bag
	 * says what the task has paid SINCE IT BEGAN. Reading the first as licence
	 * to add the second is the defect.
	 */
	@Test
	public void aBoundedWindowTakesTheTasksThatClosedInIt()
	{
		List<LocalStore.BagItem> bag = store.onTaskLoot(FROM, TO, null, false);
		assertTrue("the task that closed inside the window was dropped",
			holds(bag, "Rune bar"));
		assertTrue("a task that has not closed was counted whole, so days of its"
			+ " loot landed inside this window", !holds(bag, "Abyssal whip"));
		assertEquals("and the worth came out as the open task's, not the window's",
			5000, worth(bag));
	}

	/** The counts beside that bag, off the same segments and the same trap. */
	@Test
	public void theTallyCountsThoseSameTasks()
	{
		long[] t = store.onTaskTally(FROM, TO, null, false);
		assertEquals("one task closed inside the window", 1, t[2]);
		assertEquals("five hundred kills from a task still running were counted"
			+ " into a window that saw a handful of them", 20, t[0]);
	}

	/**
	 * The other half of the rule, and the one that makes it a filter rather than
	 * a loss: over the whole record there is no window to fall outside of, and a
	 * task in progress is as much a part of the record as a finished one. An
	 * account mid-task would otherwise read as never having touched it.
	 */
	@Test
	public void theWholeRecordStillHoldsTheTaskYouAreOn()
	{
		List<LocalStore.BagItem> bag = store.onTaskLoot(LO, HI, null, true);
		assertTrue("the task in progress fell out of the lifetime",
			holds(bag, "Abyssal whip"));
		assertEquals(905000, worth(bag));
		long[] t = store.onTaskTally(LO, HI, null, true);
		assertEquals("the lifetime lost the kills of the task being worked on",
			520, t[0]);
	}

	/**
	 * The panel decides which of the two it is asking for, and it decides by
	 * period: only a lifetime is unbounded. A board that passed a bounded window
	 * and true would be back where it started, and the store above cannot catch
	 * that because the store would be doing exactly as it was told. This pins
	 * the call sites, which is the seam the defect actually lived on.
	 */
	@Test
	public void thePanelAsksForOpenTasksOnlyOnTheWholeRecord() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("plugin.onTaskLoot(ms[0], ms[1], lootTask");
		assertTrue("the slayer board no longer asks for on-task loot", at > 0);
		assertTrue("the slayer board asks for open tasks over a bounded window: "
			+ src.substring(at, Math.min(src.length(), at + 120)),
			src.substring(at, Math.min(src.length(), at + 120)).contains("wholeRecord()"));
		int kind = src.indexOf("plugin.onTaskLoot(w[0], w[1], null");
		assertTrue("the kind lens no longer asks for on-task loot", kind > 0);
		assertTrue("the kind lens asks for open tasks over a bounded window",
			src.substring(kind, Math.min(src.length(), kind + 90)).contains("wholeRecord()"));
	}
}
