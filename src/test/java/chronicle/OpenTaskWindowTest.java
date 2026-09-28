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

public class OpenTaskWindowTest
{
	private static final long LO = Long.MIN_VALUE / 2;
	private static final long HI = Long.MAX_VALUE / 2;

	private static final long CLOSED_TS = 1_700_500_000L;
	private static final long OPEN_TS = 1_700_500_600L;
	private static final long FROM = (CLOSED_TS - 60) * 1000L;
	private static final long TO = (OPEN_TS + 60) * 1000L;

	private static LocalStore store;

	private static final String JOURNAL =
		"{\"drops\":{},\"slayer\":{\"tasks\":["
		+ "{\"task\":\"Nechryael\",\"ts\":" + CLOSED_TS + ",\"open\":false,"
		+ "\"kills\":20,\"value\":5000,\"monsters\":{\"Nechryael\":20},"
		+ "\"items\":{\"Rune bar\":{\"id\":2363,\"qty\":4,\"value\":5000}}},"
		+ "{\"task\":\"Abyssal demons\",\"ts\":" + OPEN_TS + ",\"open\":true,"
		+ "\"kills\":500,\"value\":900000,\"monsters\":{\"Abyssal demon\":500},"
		+ "\"items\":{\"Abyssal whip\":{\"id\":4151,\"qty\":1,\"value\":900000}}}"
		+ "]}}";

	@BeforeClass
	public static void mount() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-open-task");
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

	@Test
	public void theTallyCountsThoseSameTasks()
	{
		long[] t = store.onTaskTally(FROM, TO, null, false);
		assertEquals("one task closed inside the window", 1, t[2]);
		assertEquals("five hundred kills from a task still running were counted"
			+ " into a window that saw a handful of them", 20, t[0]);
	}

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
