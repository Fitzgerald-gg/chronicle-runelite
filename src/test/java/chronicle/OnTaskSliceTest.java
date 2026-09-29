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

	@Test
	public void anItemsOnTaskTotalIsExact()
	{
		Map<String, long[]> items = store.tasks(LO, HI, null, true).items();
		assertEquals("three distinct items were paid by tasks", 3, items.size());
		assertEquals(2000, items.get("Fire rune")[0]);
		assertEquals(10000, items.get("Fire rune")[1]);
		assertFalse("Zulrah's scales never came off a task",
			items.containsKey("Zulrah's scales"));
	}

	@Test
	public void aMonstersOnTaskKillsAreExact()
	{
		Map<String, Long> kills = store.tasks(LO, HI, null, true).kills();
		assertEquals(Long.valueOf(304), kills.get("Dust devil"));
		assertEquals(Long.valueOf(45), kills.get("Blue dragon"));
		assertEquals(Long.valueOf(97), kills.get("Vorkath"));
		assertEquals("a monster never assigned has no on-task side",
			null, kills.get("Zulrah"));
	}

	@Test
	public void anItemSplitsByTask()
	{
		List<LocalStore.Row> split = store.tasks(LO, HI, null, true).byTask("Fire rune");
		assertEquals(2, split.size());
		assertEquals("Dust devils", split.get(0).name);
		assertEquals(1500L, split.get(0).qty);
		assertEquals(7500L, split.get(0).value);
		assertEquals("Blue dragons", split.get(1).name);
		assertEquals(500L, split.get(1).qty);
		assertTrue("an item no task paid splits into nothing",
			store.tasks(LO, HI, null, true).byTask("Zulrah's scales").isEmpty());
	}

	@Test
	public void aMonsterGetsItsAssignments()
	{
		List<LocalStore.Assignment> a = store.tasks(LO, HI, null, true).assignments("Dust devil");
		assertEquals(2, a.size());
		assertEquals("newest first", 1700200000000L, a.get(0).ts);
		assertEquals(100, a.get(0).killsHere);
		assertEquals(204, a.get(1).killsHere);
		assertEquals("the task's own kills, not this monster's", 206, a.get(1).kills);
		assertTrue("a monster never assigned has no assignments",
			store.tasks(LO, HI, null, true).assignments("Zulrah").isEmpty());
	}

	@Test
	public void aTaskTakeBelongsToTheTaskAndNotToOneMonsterInIt()
	{
		List<LocalStore.Assignment> vork = store.tasks(LO, HI, null, true).assignments("Vorkath");
		List<LocalStore.Assignment> blue = store.tasks(LO, HI, null, true).assignments("Blue dragon");
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

	@Test
	public void aWindowWithNoTasksInItIsEmpty()
	{
		long from = 1600000000000L;
		long to = 1600100000000L;
		assertTrue(store.tasks(from, to, null, true).items().isEmpty());
		assertTrue(store.tasks(from, to, null, true).kills().isEmpty());
		assertTrue(store.tasks(from, to, null, true).assignments("Dust devil").isEmpty());
		assertTrue(store.tasks(from, to, null, true).byTask("Fire rune").isEmpty());
	}

	@Test
	public void nullsAnswerEmpty()
	{
		assertTrue(store.tasks(LO, HI, null, true).byTask(null).isEmpty());
		assertTrue(store.tasks(LO, HI, null, true).assignments(null).isEmpty());
	}

	@Test
	public void anImportFloorsQuantityAndLeavesPriceAlone() throws Exception
	{
		java.lang.reflect.Method merge = LocalStore.class.getDeclaredMethod(
			"mergeSegmentDetail", com.google.gson.JsonObject.class,
			com.google.gson.JsonObject.class, String.class);
		merge.setAccessible(true);

		com.google.gson.JsonObject seg = new com.google.gson.JsonParser()
			.parse("{\"items\":{\"Mithril spear\":{\"id\":1243,\"qty\":2,\"value\":344}}}")
			.getAsJsonObject();
		com.google.gson.JsonObject inc = new com.google.gson.JsonParser()
			.parse("{\"items\":{\"Mithril spear\":{\"id\":1243,\"qty\":7,\"value\":49000},"
				+ "\"Bones\":{\"id\":526,\"qty\":3,\"value\":99}}}")
			.getAsJsonObject();
		merge.invoke(null, seg, inc, "items");

		com.google.gson.JsonObject rows = seg.getAsJsonObject("items");
		com.google.gson.JsonObject spear = rows.getAsJsonObject("Mithril spear");
		assertEquals("a higher count is a drop this journal had not seen",
			7, spear.get("qty").getAsLong());
		assertEquals("the incoming valuation raised a price that was already set",
			344, spear.get("value").getAsLong());
		assertEquals("a row with no price of its own still takes one",
			99, rows.getAsJsonObject("Bones").get("value").getAsLong());
	}
}
