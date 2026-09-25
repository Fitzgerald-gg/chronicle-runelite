/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Search, as one thing: the list is the resolver.
 *
 * <p>Enter opens the first row on screen, and nothing else. A second resolver
 * behind the key, with an order of its own, sent Enter somewhere the reader
 * could not see. A source named exactly stands before the items named after
 * it, a journal hit opens on its day, a miss names the nearest thing, and the
 * period strip says a search reads the whole record.
 */
public class SearchDoorsTest
{
	private PanelPreviewTest.StubPlugin stub;
	private ChroniclePanel panel;

	@BeforeClass
	public static void headless() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	@Before
	public void build() throws Exception
	{
		stub = PanelPreviewTest.fixtureStub();
		stub.sources.add(new LocalStore.SourceRow("Zulrah", 300, 300, 50_000_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		List<LocalStore.BagItem> scales = new ArrayList<>();
		scales.add(new LocalStore.BagItem(12934, "Zulrah's scales", 30_000, 6_000_000L));
		stub.bags.put("Zulrah", scales);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
	}

	private JPanel search(String q) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSearch", String.class);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(panel, q);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	private Object field(String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(panel);
	}

	/** The first row drawn with a hand on it, which is where Enter lands. */
	private static JPanel firstDoor(Component c)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& k.getCursor().getType() == Cursor.HAND_CURSOR)
			{
				return (JPanel) k;
			}
		}
		return null;
	}

	private static String left(JPanel row)
	{
		Component mid = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		return mid instanceof JLabel ? ((JLabel) mid).getText() : null;
	}

	private static String beside(Component c, String leftText)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout)
			{
				BorderLayout l = (BorderLayout) ((JPanel) k).getLayout();
				Component mid = l.getLayoutComponent(BorderLayout.CENTER);
				Component east = l.getLayoutComponent(BorderLayout.EAST);
				if (mid instanceof JLabel && leftText.equals(((JLabel) mid).getText())
					&& east instanceof JLabel)
				{
					return ((JLabel) east).getText();
				}
			}
		}
		return null;
	}

	private static void press(JPanel row) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : row.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
	}

	@Test
	public void enterOpensTheFirstRowOnScreen() throws Exception
	{
		JPanel board = search("nechryael");
		JPanel first = firstDoor(board);
		assertNotNull("nothing on the board opens", first);
		assertEquals("Nechryael", left(first));
		Runnable enter = (Runnable) field("searchFirst");
		assertNotNull("Enter has nowhere to go", enter);
		SwingUtilities.invokeAndWait(enter);
		assertEquals("Nechryael", field("detailSource"));
	}

	@Test
	public void anExactlyNamedSourceStandsBeforeTheItemsNamedAfterIt() throws Exception
	{
		assertEquals("Zulrah", left(firstDoor(search("zulrah"))));
		// and the singular of a plural name counts as exact
		assertEquals("Zulrah", left(firstDoor(search("zulrahs"))));
		// but a query the source does not match exactly ranks the items first
		assertTrue(left(firstDoor(search("zulrah's"))).startsWith("Zulrah's scales"));
	}

	@Test
	public void aMissNamesTheNearestThing() throws Exception
	{
		JPanel board = search("nechrael");
		assertEquals("Nechryael", beside(board, "Did you mean"));
		assertNull(beside(search("qqqqqqqq"), "Did you mean"));
	}

	@Test
	public void theStripSaysASearchReadsTheWholeRecord() throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field f = ChroniclePanel.class.getDeclaredField("searchField");
				f.setAccessible(true);
				((net.runelite.client.ui.components.IconTextField) f.get(panel)).setText("zulrah");
				Method m = ChroniclePanel.class.getDeclaredMethod("periodRow");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(panel), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel)
					{
						said.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue(said.toString(), said.contains("Whole record"));
		assertFalse("the strip offers arrows over a list they cannot change: " + said,
			said.contains("<") || said.contains(">"));
	}

	@Test
	public void aJournalHitOpensOnItsDay() throws Exception
	{
		long ts = System.currentTimeMillis() - 40L * 24 * 60 * 60_000L;
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "SLAYER");
		JsonObject d = new JsonObject();
		d.addProperty("slayerTask", "Bloodveld");
		d.addProperty("killCount", "120");
		e.add("data", d);
		stub.feed.add(0, e);
		JPanel board = search("bloodveld");
		JPanel hit = null;
		List<Component> flat = new ArrayList<>();
		flatten(board, flat);
		for (Component c : flat)
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout
				&& left((JPanel) c) != null && left((JPanel) c).contains("Bloodveld"))
			{
				hit = (JPanel) c;
			}
		}
		assertNotNull("the journal line is not on the board", hit);
		press(hit);
		assertEquals("Day", field("histGranularity"));
		assertEquals(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate(),
			field("histCursor"));
		assertNull(field("histFrom"));
	}

	/**
	 * A collection log hit opens the log on the page it names. It used to name
	 * the page and then open the sheet, and opening the sheet puts its page back
	 * to none: every log hit landed on the top of the skills.
	 */
	@Test
	public void aLogHitOpensTheLogOnItsPage() throws Exception
	{
		JPanel board = search("fire cape");
		JPanel hit = null;
		List<Component> flat = new ArrayList<>();
		flatten(board, flat);
		for (Component c : flat)
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout
				&& "Fire cape".equals(left((JPanel) c)))
			{
				hit = (JPanel) c;
			}
		}
		assertNotNull("the slot is not on the board", hit);
		press(hit);
		assertEquals("the hit did not open the log", "log", field("sheetPage"));
		assertEquals("Bosses", field("clogTab"));
		assertEquals("The Fight Caves", field("clogPageSel"));
	}

	/**
	 * Achievement and diary rows are doors like the rest. They listened for a
	 * click and never registered with Enter, so a search that found only them
	 * said "enter opens the first row" over a key that did nothing.
	 */
	@Test
	public void enterOpensAnAchievementOrADiaryHit() throws Exception
	{
		search("noxious foe");
		Runnable enter = (Runnable) field("searchFirst");
		assertNotNull("Enter has nowhere to go on a combat achievement", enter);
		SwingUtilities.invokeAndWait(enter);
		assertEquals("combat", field("sheetPage"));

		search("golden warbler");
		enter = (Runnable) field("searchFirst");
		assertNotNull("Enter has nowhere to go on a diary entry", enter);
		SwingUtilities.invokeAndWait(enter);
		assertEquals("diaries", field("sheetPage"));
	}

	private static boolean said(Component c, String text)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JLabel && text.equals(((JLabel) k).getText()))
			{
				return true;
			}
		}
		return false;
	}

	/** The rows' names under one group's heading, up to the next heading. */
	private static List<String> group(Component board, String title)
	{
		List<Component> flat = new ArrayList<>();
		flatten(board, flat);
		List<String> out = new ArrayList<>();
		boolean in = false;
		for (Component k : flat)
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null
				&& ((JLabel) k).getText().equals(((JLabel) k).getText().toUpperCase(java.util.Locale.ROOT))
				&& ((JLabel) k).getText().length() > 3 && !(k.getParent() instanceof JPanel
				&& ((JPanel) k.getParent()).getLayout() instanceof BorderLayout))
			{
				in = ((JLabel) k).getText().equals(title.toUpperCase(java.util.Locale.ROOT));
				continue;
			}
			if (in && k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& left((JPanel) k) != null)
			{
				out.add(left((JPanel) k));
			}
		}
		return out;
	}

	/** A skill answers to its own name, ahead of anything named after it. */
	@Test
	public void aSkillAnswersItsName() throws Exception
	{
		assertEquals("Hunter", left(firstDoor(search("hunter"))));
		SwingUtilities.invokeAndWait((Runnable) field("searchFirst"));
		assertEquals("Hunter", field("detailSkill"));
	}

	/**
	 * The fight named exactly comes first, whatever the bigger ones named like
	 * it paid: "kraken" drew Vampyre kraken and Armoured kraken, and never
	 * Kraken, and Enter opened the vampyre.
	 */
	@Test
	public void theFightNamedExactlyComesFirst() throws Exception
	{
		stub.sources.add(new LocalStore.SourceRow("Vampyre kraken", 0, 424, 19_800_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		stub.sources.add(new LocalStore.SourceRow("Kraken", 117, 117, 1_000_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		assertEquals("Kraken", left(firstDoor(search("kraken"))));
	}

	/** A boss the loot keeps under another name is still found by its own. */
	@Test
	public void aBossIsFoundByItsOwnName() throws Exception
	{
		List<String> fights = group(search("grotesque"), "Bosses and monsters");
		assertTrue(fights.toString(), fights.contains("Grotesque Guardians"));
	}

	/**
	 * The whole journal, not its newest five hundred lines: an account with an
	 * imported past found its first pets drop out of search after a few weeks.
	 */
	@Test
	public void theWholeJournalIsSearched() throws Exception
	{
		long now = System.currentTimeMillis();
		for (int i = 0; i < 700; i++)
		{
			JsonObject e = new JsonObject();
			e.addProperty("ts", now - i * 60_000L);
			e.addProperty("type", "LEVEL");
			JsonObject d = new JsonObject();
			d.addProperty("skill", "Attack");
			d.addProperty("level", 2);
			e.add("data", d);
			stub.feed.add(e);
		}
		JsonObject oldest = new JsonObject();
		oldest.addProperty("ts", now - 800 * 60_000L);
		oldest.addProperty("type", "PET");
		JsonObject d = new JsonObject();
		d.addProperty("petName", "Phoenix");
		oldest.add("data", d);
		stub.feed.add(oldest);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		List<String> journal = group(search("phoenix"), "Journal");
		assertTrue("the oldest line in the journal was out of reach: " + journal,
			journal.stream().anyMatch(l -> l.contains("Phoenix")));
	}

	/** A group longer than its first rows says how many more it holds, and opens them. */
	@Test
	public void aLongGroupSaysHowManyMore() throws Exception
	{
		for (int i = 1; i <= 10; i++)
		{
			stub.lifetime.put("zzqStep" + i, (long) i);
		}
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		JPanel board = search("zzq");
		assertTrue("ten trackers and no way to the other six", said(board, "Show 6 more"));
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

	/** The rows under one group's heading, as panels, to press. */
	private static List<JPanel> groupRows(Component board, String title)
	{
		List<Component> flat = new ArrayList<>();
		flatten(board, flat);
		List<JPanel> out = new ArrayList<>();
		boolean in = false;
		for (Component k : flat)
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null
				&& ((JLabel) k).getText().equals(((JLabel) k).getText().toUpperCase(java.util.Locale.ROOT))
				&& ((JLabel) k).getText().length() > 3 && !(k.getParent() instanceof JPanel
				&& ((JPanel) k.getParent()).getLayout() instanceof BorderLayout))
			{
				in = ((JLabel) k).getText().equals(title.toUpperCase(java.util.Locale.ROOT));
				continue;
			}
			if (in && k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& left((JPanel) k) != null)
			{
				out.add((JPanel) k);
			}
		}
		return out;
	}

	private static List<LocalStore.SlayerTask> nechryaelTwice()
	{
		double now = System.currentTimeMillis() / 1000.0;
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(new LocalStore.SlayerTask("Nechryael", 1, 200, 0, now, 0L, true));
		tasks.add(new LocalStore.SlayerTask("Abyssal demons", 150, 150, 0, now - 100_000, 0L, false));
		tasks.add(new LocalStore.SlayerTask("Nechryael", 135, 135, 0, now - 900_000, 0L, false));
		return tasks;
	}

	/**
	 * The slayer tasks are there without the Slayer board having been opened,
	 * and a task given more than once opens its newest assignment, as its hover
	 * says: it opened the oldest, a finished task from May, not the one in hand.
	 */
	@Test
	public void aTaskIsFoundUnvisitedAndOpensItsNewest() throws Exception
	{
		stub.journey = new LocalStore.SlayerJourney(2, 286, 0L, 0L, nechryaelTwice());
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		search("nechryael");
		SwingUtilities.invokeAndWait(() -> { });   // the journey read lands
		List<JPanel> tasks = groupRows(search("nechryael"), "Slayer tasks");
		assertEquals(1, tasks.size());
		assertEquals("Nechryael", left(tasks.get(0)));
		press(tasks.get(0));
		assertEquals(0, field("detailTask"));
	}

	/** The initials players type for a fight find it. */
	@Test
	public void initialsFindAFight()
	{
		assertEquals(2, ChroniclePanel.matchScore("cox", "Chambers of Xeric"));
		assertEquals(2, ChroniclePanel.matchScore("tob", "Theatre of Blood"));
		assertEquals(2, ChroniclePanel.matchScore("kbd", "King Black Dragon"));
		assertEquals(2, ChroniclePanel.matchScore("cg", "The Corrupted Gauntlet"));
		assertEquals(2, ChroniclePanel.matchScore("gg", "Grotesque Guardians"));
		assertEquals(-1, ChroniclePanel.matchScore("gg", "Zulrah"));
		// and apostrophes alone ask for nothing
		assertEquals(-1, ChroniclePanel.matchScore("''", "Zulrah"));
	}

	/**
	 * Go to keeps its close answers only: "king" found inside Cooking took Enter
	 * from the King Black Dragon, which it names outright.
	 */
	@Test
	public void aNameInsideASkillDoesNotTakeEnter() throws Exception
	{
		JPanel board = search("king");
		assertFalse(group(board, "Go to").contains("Cooking"));
		assertEquals("King Black Dragon", left(firstDoor(board)));
	}

	/** Another name for a page answers only as typed whole: "logs" is the item kind. */
	@Test
	public void aPagesNicknameIsNotPluralised() throws Exception
	{
		assertFalse(group(search("logs"), "Go to").contains("Collection log"));
		assertTrue(group(search("clog"), "Go to").contains("Collection log"));
	}

	/** A boss is listed once, not again under the reward it pays out through. */
	@Test
	public void aBossIsListedOnceNotAgainUnderItsReward() throws Exception
	{
		stub.sources.add(new LocalStore.SourceRow("Reward pool (Tempoross)", 0, 455, 3_000_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		List<String> fights = group(search("tempoross"), "Bosses and monsters");
		assertEquals(fights.toString(), "Tempoross", fights.get(0));
		assertFalse(fights.toString(), fights.contains("Reward pool (Tempoross)"));
	}

	/** Copies of one name on a page are held one by one, as the Log tab lights them. */
	@Test
	public void aPageOfOneNameCountsItsCopies() throws Exception
	{
		JsonObject notes = new JsonObject();
		notes.addProperty("Ancient page", 1);
		JsonObject byCat = new JsonObject();
		byCat.add("My Notes", notes);
		stub.clog.add("by_cat", byCat);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		assertEquals("1 / 26", beside(search("my notes"), "My Notes"));
	}

	/** A diary task's first sentence does not end at an abbreviation. */
	@Test
	public void aDiaryTaskIsNotCutAtAnAbbreviation()
	{
		assertEquals("Enter the Combat Training Camp north of W. Ardougne.",
			ChroniclePanel.firstSentence("Enter the Combat Training Camp north of W. Ardougne."));
		assertEquals("Enter the H.A.M. Hideout.", ChroniclePanel.firstSentence("Enter the H.A.M. Hideout."));
		assertEquals("Kill the Crazy Arc. [sic], Chaos Fanatic & Scorpia.",
			ChroniclePanel.firstSentence("Kill the Crazy Arc. [sic], Chaos Fanatic & Scorpia. "
				+ "Note: While these bosses can be killed in any order."));
		assertEquals("Mine some iron.", ChroniclePanel.firstSentence("Mine some iron. Then smelt it."));
	}

	/** A long hover wraps in a column instead of running off the screen. */
	@Test
	public void aLongHoverWraps()
	{
		assertEquals("Held", ChroniclePanel.wrappedTip("Held"));
		String task = "Ardougne hard: Craft some Death runes from Essence. Needs: 65 Runecraft & "
			+ "completion of Mourning's End Part II";
		String got = ChroniclePanel.wrappedTip(task);
		assertTrue(got, got.startsWith("<html><body style='width:"));
		assertTrue(got, got.contains("65 Runecraft &amp; completion"));
	}

	/** The journal forgives an apostrophe, as every other group does. */
	@Test
	public void theJournalForgivesAnApostrophe() throws Exception
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", System.currentTimeMillis() - 60_000L);
		e.addProperty("type", "DEATH");
		JsonObject d = new JsonObject();
		d.addProperty("killerName", "K'ril Tsutsaroth");
		e.add("data", d);
		stub.feed.add(0, e);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		for (String q : new String[]{"kril tsut", "k'ril tsut"})
		{
			List<String> journal = group(search(q), "Journal");
			assertTrue(q + ": " + journal, journal.stream().anyMatch(l -> l.contains("K'ril")));
		}
	}

	/** A journal hit opens its day with every kind of line, whatever lens was left on. */
	@Test
	public void aJournalHitOpensOnEveryLens() throws Exception
	{
		Field lens = ChroniclePanel.class.getDeclaredField("journalLens");
		lens.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				lens.set(panel, "Deaths");
				Method m = ChroniclePanel.class.getDeclaredMethod("openJournalOn", long.class);
				m.setAccessible(true);
				m.invoke(panel, System.currentTimeMillis());
			}
			catch (Exception ex)
			{
				throw new RuntimeException(ex);
			}
		});
		assertEquals("All", lens.get(panel));
	}

	/** A slip on any name search answers to is caught, not only on loot. */
	@Test
	public void aSlipOnABossNeverLootedIsCaught() throws Exception
	{
		assertEquals("Grotesque Guardians", beside(search("grotesqe guardians"), "Did you mean"));
		// the sheet's own bosses, where the log keeps no page of that name
		assertEquals("Dagannoth Supreme", beside(search("dagannoth supreem"), "Did you mean"));
	}

	/**
	 * A hit older than the Journal's newest four thousand lines still opens on
	 * a day that shows it: search reads the whole journal, and the board read
	 * only those, so such a hit opened on "Nothing in" its own day.
	 */
	@Test
	public void anOldHitOpensOnADayThatShowsIt() throws Exception
	{
		long now = System.currentTimeMillis();
		stub.feed.clear();
		for (int i = 0; i < 4_100; i++)
		{
			JsonObject e = new JsonObject();
			e.addProperty("ts", now - i * 60_000L);
			e.addProperty("type", "LEVEL");
			JsonObject d = new JsonObject();
			d.addProperty("skill", "Attack");
			d.addProperty("level", 2);
			e.add("data", d);
			stub.feed.add(e);
		}
		long ts = now - 30L * 24 * 60 * 60_000L;
		JsonObject pet = new JsonObject();
		pet.addProperty("ts", ts);
		pet.addProperty("type", "PET");
		JsonObject d = new JsonObject();
		d.addProperty("petName", "Phoenix");
		pet.add("data", d);
		stub.feed.add(pet);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
		final JPanel[] board = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method open = ChroniclePanel.class.getDeclaredMethod("openJournalOn", long.class);
				open.setAccessible(true);
				open.invoke(panel, ts);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				board[0] = (JPanel) m.invoke(panel);
			}
			catch (Exception ex)
			{
				throw new RuntimeException(ex);
			}
		});
		List<Component> flat = new ArrayList<>();
		flatten(board[0], flat);
		assertTrue("the day opened without its line", flat.stream().anyMatch(c ->
			c instanceof JLabel && ((JLabel) c).getText() != null
				&& ((JLabel) c).getText().contains("Phoenix")));
	}
}
