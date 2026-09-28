package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("unchecked")
public class OnTaskFilterTest
{
	private static final String JOURNAL =
		"{\"drops\":{"
		+ "\"Blue dragon\":{\"kc\":300,\"loots\":300,\"value\":600,"
		+ "\"first_seen\":1600000000000,\"items\":{"
		+ "\"536\":{\"id\":536,\"name\":\"Dragon bones\",\"qty\":300,\"value\":600},"
		+ "\"554\":{\"id\":554,\"name\":\"Fire rune\",\"qty\":900,\"value\":900}}},"
		+ "\"Jelly\":{\"kc\":141,\"loots\":252,\"value\":1000,\"items\":{"
		+ "\"1\":{\"id\":1,\"name\":\"Chaos rune\",\"qty\":10,\"value\":1000}}},"
		+ "\"Choke devil\":{\"kc\":11,\"loots\":13,\"value\":500,\"items\":{"
		+ "\"2\":{\"id\":2,\"name\":\"Coins\",\"qty\":500,\"value\":500}}},"
		+ "\"Zulrah\":{\"kc\":7,\"loots\":7,\"value\":900,\"items\":{"
		+ "\"12934\":{\"id\":12934,\"name\":\"Zulrah's scales\",\"qty\":900,\"value\":900}}}"
		+ "},\"collection_log\":{\"slayer_kcs\":{\"Blue dragons\":400,\"Jellies\":979}},"
		+ "\"slayer\":{\"tasks\":["
		+ "{\"task\":\"Blue dragons\",\"ts\":1700000000,\"kills\":145,\"value\":300000,"
		+ "\"monsters\":{\"Blue dragon\":45,\"Baby blue dragon\":3,\"Vorkath\":97},"
		+ "\"items\":{\"Fire rune\":{\"id\":554,\"qty\":500,\"value\":9999}}},"
		+ "{\"task\":\"Dust devils\",\"ts\":1700100000,\"kills\":206,\"value\":400000,"
		+ "\"monsters\":{\"Dust devil\":206,\"Choke devil\":13},"
		+ "\"items\":{\"Fire rune\":{\"id\":554,\"qty\":100,\"value\":10}}}"
		+ "]}}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-on-task-filter");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "filtery.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "filtery");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		ChroniclePanel p = hold[0];
		set(p, "histGranularity", "Lifetime");
		return p;
	}

	private static void set(ChroniclePanel p, String n, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(n);
		f.setAccessible(true);
		f.set(p, v);
	}

	private static List<String> say(ChroniclePanel p, String method, Object... args)
		throws Exception
	{
		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = args.length == 0
					? ChroniclePanel.class.getDeclaredMethod(method)
					: ChroniclePanel.class.getDeclaredMethod(method, String.class);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) (args.length == 0 ? m.invoke(p) : m.invoke(p, args)), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						out.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out;
	}

	private static void flatten(Component c, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}

	private static boolean has(List<String> said, String what)
	{
		for (String s : said)
		{
			if (s.toLowerCase(java.util.Locale.ROOT)
				.contains(what.toLowerCase(java.util.Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	private static String after(List<String> said, String label)
	{
		for (int i = 0; i < said.size() - 1; i++)
		{
			if (label.equals(said.get(i)))
			{
				return said.get(i + 1);
			}
		}
		return null;
	}

	@Test
	public void theFilterAppearsOnlyWhereThereIsSomethingToFilter() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> paid = say(p, "buildItemDetail", "Fire rune");
		assertTrue("an item tasks paid was offered no filter",
			has(paid, "On task") || has(paid, "All"));
		List<String> never = say(p, "buildItemDetail", "Zulrah's scales");
		assertFalse("an item no task ever paid was offered a filter",
			has(never, "On task") || has(never, "All"));
	}

	@Test
	public void thePageAndTheBoardDrawTheSameControl() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", false);
		List<String> off = say(p, "buildItemDetail", "Fire rune");
		assertTrue("the toggle should show the reading it is on: " + off,
			exactly(off, "All"));
		assertFalse("a pill pair naming the reading NOT chosen is the old design: "
			+ off, exactly(off, "On task"));

		set(p, "onTaskOnly", true);
		List<String> on = say(p, "buildItemDetail", "Fire rune");
		assertTrue(on.toString(), exactly(on, "On task"));
		assertFalse(on.toString(), exactly(on, "All"));
	}

	private static boolean exactly(List<String> said, String label)
	{
		for (String s : said)
		{
			if (label.equals(s))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void aPageWithNoFilterIsNotGovernedByOne() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		List<String> said = say(p, "buildItemDetail", "Zulrah's scales");
		assertEquals("the ledger figure changed under a control that is not there",
			"×900", after(said, "Obtained"));
		assertFalse(has(said, "On task"));
	}

	@Test
	public void bothReadingsCarryAWorthAndSayWhichDayItIsFrom() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		List<String> said = say(p, "buildItemDetail", "Fire rune");
		assertEquals("×600", after(said, "Obtained on task"));
		assertEquals("×900", after(said, "All sources"));
		assertEquals("10k gp", after(said, "Worth"));
	}

	@Test
	public void anOnTaskHeadReadsItsPeriod() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		set(p, "histGranularity", "Day");
		List<String> said = say(p, "buildItemDetail", "Fire rune");
		assertEquals(said.toString(), "×0", after(said, "Obtained on task"));
	}

	@Test
	public void theItemSplitsByTaskNotByMonster() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		List<String> said = say(p, "buildItemDetail", "Fire rune");
		assertTrue(has(said, "By task"));
		assertEquals("×500 · 9,999 gp", after(said, "Task: Blue dragons"));
		assertEquals("×100 · 10 gp", after(said, "Task: Dust devils"));
		assertFalse("a monster was named as if it had paid", has(said, "Blue dragon ×"));
	}

	@Test
	public void aMonsterStatesItsKillsAndNamesTheTask() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		Field folds = ChroniclePanel.class.getDeclaredField("openFolds");
		folds.setAccessible(true);
		((java.util.Set<String>) folds.get(p)).add("kcsrc:Blue dragon");
		List<String> said = say(p, "buildSourceDetail", "Blue dragon");
		assertEquals("the game's own count, not the ledger's", "400", after(said, "Kills"));
		assertTrue(has(said, "What says so"));
		assertEquals("400", after(said, "Kill Log"));
		assertEquals("300", after(said, "Drops logged"));
		assertEquals("45", after(said, "Dropped on task"));
		assertTrue(has(said, "Killed on task"));
		assertEquals("45", after(said, "Task: Blue dragons"));
		assertFalse("a monster page grew a filter it cannot honour",
			has(said, "All"));
		assertTrue(has(said, "Dragon bones"));
	}

	@Test
	public void aMonsterNeverAssignedSaysNothing() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildSourceDetail", "Zulrah");
		assertFalse(has(said, "On task"));
		assertFalse(has(said, "Killed on task"));
	}

	@Test
	public void theLootBoardOffersItOverKindsOnly() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "dropsByKind", false);
		List<String> bySource = say(p, "buildDrops");
		assertFalse("By source cannot answer it and must not offer it",
			has(bySource, "On task") || has(bySource, "All"));
		set(p, "dropsByKind", true);
		List<String> byKind = say(p, "buildDrops");
		assertTrue(has(byKind, "On task") || has(byKind, "All"));
	}

	@Test
	public void theLootBoardNarrowsToTheTasks() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "dropsByKind", true);
		set(p, "onTaskOnly", true);
		List<String> said = say(p, "buildDrops");
		assertTrue(has(said, "On-task loot"));
		assertEquals("600 fire runes across two tasks", "600", after(said, "Items"));
	}

	@Test
	public void aPeriodWithNoTaskInItSaysSo() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "dropsByKind", true);
		set(p, "onTaskOnly", true);
		set(p, "histGranularity", "Day");
		List<String> said = say(p, "buildDrops");
		assertTrue("the reader was left with no way back to All", has(said, "On task"));
		assertTrue(has(said, "No task closed inside"));
	}

	@Test
	public void aPictureLeavesTheReadersOwnBookkeepingBehind() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> onScreen = say(p, "buildSourceDetail", "Blue dragon");
		assertTrue("the board should still say it", has(onScreen, "Tracked since"));

		final List<String> shared = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field f = ChroniclePanel.class.getDeclaredField("drawingCopy");
				f.setAccessible(true);
				f.setBoolean(p, true);
				Method m = ChroniclePanel.class.getDeclaredMethod(
					"buildSourceDetail", String.class);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p, "Blue dragon"), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						shared.add(((JLabel) c).getText());
					}
				}
				f.setBoolean(p, false);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertFalse("a shared picture named the day tracking began",
			has(shared, "Tracked since"));
		assertFalse("a shared picture said how dry the reader is running",
			has(shared, "Chasing"));
		assertTrue("the picture lost the fight itself", has(shared, "Kills"));
	}

	@Test
	public void theListCardAgreesWithThePageItOpens() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> card = say(p, "buildDrops");
		List<String> page = say(p, "buildSourceDetail", "Blue dragon");
		assertEquals("the page does not lead with the reconciled count",
			"400", after(page, "Kills"));
		assertTrue("the card leads with a different number than the page: " + card,
			has(card, "400 kc"));
		assertTrue(has(card, "gp/drop"));
		assertTrue(has(page, "gp/drop"));
	}

	@Test
	public void searchingAKindOpensTheLootTracker() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildSearch", "runes");
		assertTrue("the ledger reading was not offered",
			has(said, "every one you have had"));
		assertTrue("the slayer reading was not offered",
			has(said, "from slayer tasks"));
		assertFalse("the old on-task-only caption survived", has(said, "on-task loot"));
	}

	@Test
	public void aKindWithNoSlayerSideIsOfferedOnce() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildSearch", "hides");
		assertTrue(has(said, "every one you have had"));
		assertFalse("a slayer row was offered for a kind no task paid",
			has(said, "from slayer tasks"));
	}

	@Test
	public void bothRowsLandOnTheLootBoard() throws Exception
	{
		ChroniclePanel p = panel();
		Method open = ChroniclePanel.class.getDeclaredMethod(
			"openLootKind", String.class, boolean.class);
		open.setAccessible(true);
		for (boolean onTask : new boolean[]{false, true})
		{
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					open.invoke(p, "Runes", onTask);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			});
			Field tab = ChroniclePanel.class.getDeclaredField("subByTab");
			tab.setAccessible(true);
			assertTrue("a kind search left the loot tracker: " + tab.get(p),
				tab.get(p).toString().contains("Loot"));
			Field lens = ChroniclePanel.class.getDeclaredField("onTaskOnly");
			lens.setAccessible(true);
			assertEquals("the row did not set the reading it names",
				onTask, lens.getBoolean(p));
			Field kind = ChroniclePanel.class.getDeclaredField("lootKind");
			kind.setAccessible(true);
			assertEquals("Runes", kind.get(p));
		}
		Field lens = ChroniclePanel.class.getDeclaredField("onTaskOnly");
		lens.setAccessible(true);
		lens.setBoolean(p, false);
		assertEquals("910", after(say(p, "buildDrops"), "Items"));
		lens.setBoolean(p, true);
		assertEquals("600", after(say(p, "buildDrops"), "Items"));
	}

	@Test
	public void aKilledThingSaysKillsHoweverItIsSpelled() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> jelly = say(p, "buildSourceDetail", "Jelly");
		assertEquals("the Kill Log's plural never reached the ledger's singular",
			"979", after(jelly, "Kills"));

		List<String> choke = say(p, "buildSourceDetail", "Choke devil");
		assertTrue("a superior killed on a task was called looted",
			has(choke, "Kills"));
		assertFalse(has(choke, "Times looted"));
	}
}
