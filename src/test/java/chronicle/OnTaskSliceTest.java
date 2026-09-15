package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the record can and cannot say about the slayer half of a ledger.
 *
 * <p>A task carries ONE items map and a separate monsters map, and nothing
 * inside it links a drop to the thing that dropped it. So an item's on-task
 * total is exact, a monster's on-task kill count is exact, and a monster's
 * on-task LOOT does not exist at all. The journal below is built to make that
 * impossible to forget: its "Blue dragons" task is the one from the owner's
 * own record, where 97 of the 145 kills are Vorkath.
 */
public class OnTaskSliceTest
{
	private static final long LO = Long.MIN_VALUE / 2;
	private static final long HI = Long.MAX_VALUE / 2;
	private static LocalStore store;

	private static final String JOURNAL =
		"{\"drops\":{"
		+ "\"Blue dragon\":{\"kc\":300,\"value\":40,\"items\":{"
		+ "\"532\":{\"id\":532,\"name\":\"Big bones\",\"qty\":300,\"value\":40}}},"
		+ "\"Zulrah\":{\"kc\":7,\"value\":900,\"items\":{"
		+ "\"12934\":{\"id\":12934,\"name\":\"Zulrah's scales\",\"qty\":900,\"value\":900}}}"
		+ "},\"slayer\":{\"tasks\":["
		+ "{\"task\":\"Blue dragons\",\"ts\":1700000000,\"kills\":145,\"value\":15335312,"
		+ "\"monsters\":{\"Blue dragon\":45,\"Baby blue dragon\":3,\"Vorkath\":97},"
		+ "\"items\":{\"Fire rune\":{\"id\":554,\"qty\":500,\"value\":2500},"
		+ "\"Dragon bones\":{\"id\":536,\"qty\":97,\"value\":300000}}},"
		+ "{\"task\":\"Dust devils\",\"ts\":1700100000,\"kills\":206,\"value\":871255,"
		+ "\"monsters\":{\"Dust devil\":204,\"Choke devil\":2},"
		+ "\"items\":{\"Fire rune\":{\"id\":554,\"qty\":1500,\"value\":7500}}},"
		+ "{\"task\":\"Dust devils\",\"ts\":1700200000,\"kills\":100,\"value\":400000,"
		+ "\"monsters\":{\"Dust devil\":100},"
		+ "\"items\":{\"Chaos rune\":{\"id\":562,\"qty\":40,\"value\":4000}}}"
		+ "]}}";

	@BeforeClass
	public static void mount() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-on-task-slice");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "slicer.json")))
		{
			w.write(JOURNAL);
		}
		store = new LocalStore(null, new Gson());
		store.load(dir, "slicer");
	}

	/** An item's on-task total is exact, and so is the list of items that have one. */
	@Test
	public void anItemsOnTaskTotalIsExact()
	{
		Map<String, long[]> items = store.onTaskItems(LO, HI);
		assertEquals("three distinct items were paid by tasks", 3, items.size());
		assertEquals(2000, items.get("Fire rune")[0]);
		assertEquals(10000, items.get("Fire rune")[1]);
		// the filter must not be offered on an item no task ever paid
		assertFalse("Zulrah's scales never came off a task",
			items.containsKey("Zulrah's scales"));
	}

	/** A monster's on-task kills are exact, including a superior. */
	@Test
	public void aMonstersOnTaskKillsAreExact()
	{
		Map<String, Long> kills = store.onTaskKills(LO, HI);
		assertEquals(Long.valueOf(304), kills.get("Dust devil"));
		assertEquals(Long.valueOf(45), kills.get("Blue dragon"));
		assertEquals(Long.valueOf(97), kills.get("Vorkath"));
		assertEquals("a monster never assigned has no on-task side",
			null, kills.get("Zulrah"));
	}

	/** An item splits by the TASK that paid it, dearest first. */
	@Test
	public void anItemSplitsByTask()
	{
		List<Object[]> split = store.onTaskItemByTask("Fire rune", LO, HI);
		assertEquals(2, split.size());
		assertEquals("Dust devils", split.get(0)[0]);
		assertEquals(1500L, split.get(0)[1]);
		assertEquals(7500L, split.get(0)[2]);
		assertEquals("Blue dragons", split.get(1)[0]);
		assertEquals(500L, split.get(1)[1]);
		assertTrue("an item no task paid splits into nothing",
			store.onTaskItemByTask("Zulrah's scales", LO, HI).isEmpty());
	}

	/**
	 * A monster's page gets its ASSIGNMENTS, newest first: its own kills, and
	 * the task's worth, which is the task's and says so.
	 */
	@Test
	public void aMonsterGetsItsAssignments()
	{
		List<LocalStore.Assignment> a = store.onTaskAssignments("Dust devil", LO, HI);
		assertEquals(2, a.size());
		assertEquals("newest first", 1700200000000L, a.get(0).ts);
		assertEquals(100, a.get(0).killsHere);
		assertEquals(204, a.get(1).killsHere);
		assertEquals("the task's own kills, not this monster's", 206, a.get(1).kills);
		assertTrue("a monster never assigned has no assignments",
			store.onTaskAssignments("Zulrah", LO, HI).isEmpty());
	}

	/**
	 * THE TRAP. Vorkath sits inside a Blue dragons task. Its kills are its own
	 * and exact; the 15.3M is the whole assignment's and belongs to no single
	 * monster in it, which is why the row is labelled for the TASK.
	 */
	@Test
	public void aTaskTakeBelongsToTheTaskAndNotToOneMonsterInIt()
	{
		List<LocalStore.Assignment> vork = store.onTaskAssignments("Vorkath", LO, HI);
		List<LocalStore.Assignment> blue = store.onTaskAssignments("Blue dragon", LO, HI);
		assertEquals(1, vork.size());
		assertEquals(1, blue.size());
		assertEquals("Blue dragons", vork.get(0).task);
		assertEquals("Blue dragons", blue.get(0).task);
		assertEquals(97, vork.get(0).killsHere);
		assertEquals(45, blue.get(0).killsHere);
		assertEquals("the same assignment, so the same worth, which is the task's",
			vork.get(0).value, blue.get(0).value);
		assertEquals(15335312, vork.get(0).value);
	}

	/** A window the tasks fall outside of leaves nothing to filter. */
	@Test
	public void aWindowWithNoTasksInItIsEmpty()
	{
		long from = 1600000000000L;
		long to = 1600100000000L;
		assertTrue(store.onTaskItems(from, to).isEmpty());
		assertTrue(store.onTaskKills(from, to).isEmpty());
		assertTrue(store.onTaskAssignments("Dust devil", from, to).isEmpty());
		assertTrue(store.onTaskItemByTask("Fire rune", from, to).isEmpty());
	}

	/** Nothing asked of a null name throws. */
	@Test
	public void nullsAnswerEmpty()
	{
		assertTrue(store.onTaskItemByTask(null, LO, HI).isEmpty());
		assertTrue(store.onTaskAssignments(null, LO, HI).isEmpty());
	}
}
