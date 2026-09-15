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
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The All / On task filter: where it appears, what it narrows, and the two
 * things it must never do.
 *
 * <p>It must never appear where the record has no on-task side to show, and it
 * must never put a gp figure from the task bag beside one from the ledger. The
 * two bags are priced on different days, so on the owner's own record 19 items
 * are worth MORE on task than in the entire ledger while never exceeding it by
 * quantity. Counts cross the filter; money does not.
 */
@SuppressWarnings("unchecked")
public class OnTaskFilterTest
{
	private static final String JOURNAL =
		"{\"drops\":{"
		+ "\"Blue dragon\":{\"kc\":300,\"loots\":300,\"value\":600,"
		+ "\"first_seen\":1600000000000,\"items\":{"
		+ "\"536\":{\"id\":536,\"name\":\"Dragon bones\",\"qty\":300,\"value\":600},"
		+ "\"554\":{\"id\":554,\"name\":\"Fire rune\",\"qty\":900,\"value\":900}}},"
		+ "\"Zulrah\":{\"kc\":7,\"loots\":7,\"value\":900,\"items\":{"
		+ "\"12934\":{\"id\":12934,\"name\":\"Zulrah's scales\",\"qty\":900,\"value\":900}}}"
		+ "},\"collection_log\":{\"slayer_kcs\":{\"Blue dragons\":400}},"
		+ "\"slayer\":{\"tasks\":["
		+ "{\"task\":\"Blue dragons\",\"ts\":1700000000,\"kills\":145,\"value\":300000,"
		+ "\"monsters\":{\"Blue dragon\":45,\"Baby blue dragon\":3,\"Vorkath\":97},"
		+ "\"items\":{\"Fire rune\":{\"id\":554,\"qty\":500,\"value\":9999}}},"
		+ "{\"task\":\"Dust devils\",\"ts\":1700100000,\"kills\":206,\"value\":400000,"
		+ "\"monsters\":{\"Dust devil\":206},"
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

	/** Card titles and group headings are drawn uppercase, so match without case. */
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

	/** An item a task paid gets the filter. One no task paid does not. */
	@Test
	public void theFilterAppearsOnlyWhereThereIsSomethingToFilter() throws Exception
	{
		ChroniclePanel p = panel();
		assertTrue("an item tasks paid was offered no filter",
			has(say(p, "buildItemDetail", "Fire rune"), "On task"));
		assertFalse("an item no task ever paid was offered a filter",
			has(say(p, "buildItemDetail", "Zulrah's scales"), "On task"));
	}

	/** And a page with no filter on it ignores the setting entirely. */
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

	/**
	 * Both readings carry the same rows. The money on each is true of a
	 * different day, and the on-task row says so rather than inviting a
	 * subtraction: 19 of the owner's 287 on-task items price HIGHER than the
	 * same item does across his whole ledger.
	 */
	@Test
	public void bothReadingsCarryAWorthAndSayWhichDayItIsFrom() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		List<String> said = say(p, "buildItemDetail", "Fire rune");
		assertEquals("×600", after(said, "Obtained on task"));
		assertEquals("×900", after(said, "All sources"));
		// Both readings carry a Worth, and this journal is built so they
		// disagree the way the owner's does: the tasks logged 10,009 gp for six
		// hundred fire runes while the ledger holds 900 for nine hundred of
		// them, because the two bags froze their prices on different days. The
		// row has to say which day it is quoting.
		assertEquals("10k gp", after(said, "Worth"));
	}

	/** The split is by task, because the record cannot say which monster. */
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

	/**
	 * A monster's page is never filtered. It states its on-task kills, which are
	 * exact, and lists the assignments it turned up in, labelled for the task,
	 * because a task's take belongs to the task.
	 */
	@Test
	public void aMonsterStatesItsKillsAndNamesTheTask() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "onTaskOnly", true);
		// the on-task figure sits with the other counts of the same fight, under
		// a fold that opens on a click
		Field folds = ChroniclePanel.class.getDeclaredField("openFolds");
		folds.setAccessible(true);
		((java.util.Set<String>) folds.get(p)).add("kcsrc:Blue dragon");
		List<String> said = say(p, "buildSourceDetail", "Blue dragon");
		assertEquals("the game's own count, not the ledger's", "400", after(said, "Kills"));
		assertTrue(has(said, "What says so"));
		// 45 DROPPED on task, which is not the same as 45 killed: the journal
		// counts a monster on a task when it pays, and 271 of my own on-task
		// kills paid nothing at all.
		assertEquals("400", after(said, "Kill Log"));
		assertEquals("300", after(said, "Drops logged"));
		assertEquals("45", after(said, "Dropped on task"));
		assertTrue(has(said, "Killed on task"));
		assertEquals("45", after(said, "Task: Blue dragons"));
		assertFalse("a monster page grew a filter it cannot honour",
			has(said, "All"));
		// the whole ledger bag is still there, uncut
		assertTrue(has(said, "Dragon bones"));
	}

	/** A monster never assigned says nothing about tasks at all. */
	@Test
	public void aMonsterNeverAssignedSaysNothing() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildSourceDetail", "Zulrah");
		assertFalse(has(said, "On task"));
		assertFalse(has(said, "Killed on task"));
	}

	/** The loot board offers it over kinds, and never over sources. */
	@Test
	public void theLootBoardOffersItOverKindsOnly() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "dropsByKind", false);
		assertFalse("By source cannot answer it and must not offer it",
			has(say(p, "buildDrops"), "On task"));
		set(p, "dropsByKind", true);
		assertTrue(has(say(p, "buildDrops"), "On task"));
	}

	/** On task, the board reads the tasks rather than the ledger. */
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

	/**
	 * A period that closed no task says so and keeps the control, rather than
	 * dropping the reader onto the ledger's own note with no way back.
	 */
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

	/**
	 * A picture carries less than the board does. Two lines are the reader's
	 * own bookkeeping rather than anything about the fight, and one of them can
	 * name a day before the account existed on a ledger holding imported rows.
	 */
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

	/**
	 * The card in the list and the page it opens carry the same two figures.
	 * They used to disagree: the card took the ledger's own kill count and the
	 * page worked out another, so Nechryael read 686 on one and 1,236 on the
	 * other, one click apart.
	 */
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
		// and the rate is over drops on both, because the worth accrues per
		// drop and dividing it by kills was reading high
		assertTrue(has(card, "gp/drop"));
		assertTrue(has(page, "gp/drop"));
	}

	/**
	 * Searching a kind opens the LOOT TRACKER, not the slayer board.
	 *
	 * <p>It used to hard-code PvM's Slayer tab with its Drops lens up, so a
	 * reader who typed "runes" wanting every rune they had ever been given got
	 * only the ones tasks paid, with nothing on screen saying so. Both readings
	 * are offered now, as two rows, and the slayer one only where the tasks
	 * actually paid some of that kind.
	 */
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

	/** And a kind no task ever paid is offered once, not twice. */
	@Test
	public void aKindWithNoSlayerSideIsOfferedOnce() throws Exception
	{
		ChroniclePanel p = panel();
		// the journal's only on-task item is a Fire rune, so Hides has none
		List<String> said = say(p, "buildSearch", "hides");
		assertTrue(has(said, "every one you have had"));
		assertFalse("a slayer row was offered for a kind no task paid",
			has(said, "from slayer tasks"));
	}

	/** Both rows land on the loot board, one on each reading. */
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
		// the ledger reading holds 900 fire runes, the slayer one 600 of them
		Field lens = ChroniclePanel.class.getDeclaredField("onTaskOnly");
		lens.setAccessible(true);
		lens.setBoolean(p, false);
		assertEquals("900", after(say(p, "buildDrops"), "Items"));
		lens.setBoolean(p, true);
		assertEquals("600", after(say(p, "buildDrops"), "Items"));
	}
}
