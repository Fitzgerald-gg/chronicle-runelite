/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sitting, as a period the whole panel can be read through.
 *
 * <p>Every other period is a date range, measured as the distance between two
 * of the history spine's closed baselines. The sitting has no closing baseline,
 * because it has not closed, and it needs none: the counters ARE the session,
 * exactly, with nothing subtracted from anything.
 */
public class SessionPeriodTest
{
	private static ChroniclePanel panel;
	private static PanelPreviewTest.StubPlugin stub;

	@BeforeClass
	public static void build() throws Exception
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
		stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
	}

	private static void period(String g) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("histGranularity");
		f.setAccessible(true);
		f.set(panel, g);
		Field from = ChroniclePanel.class.getDeclaredField("histFrom");
		from.setAccessible(true);
		from.set(panel, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Long> countersNow() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("countersForPeriod");
		m.setAccessible(true);
		return (Map<String, Long>) m.invoke(panel);
	}

	private static String label() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("window");
		m.setAccessible(true);
		Object w = m.invoke(panel);
		Field l = w.getClass().getDeclaredField("label");
		l.setAccessible(true);
		return (String) l.get(w);
	}

	@Test
	public void itIsOfferedAlongsideTheOthers()
	{
		assertEquals(Arrays.asList("Lifetime", "Year", "Month", "Week", "Day", "Session"),
			Arrays.asList(ChroniclePanel.PERIODS));
	}

	@Test
	public void itNamesItselfInPlainWords() throws Exception
	{
		period("Session");
		assertEquals("This session", label());
	}

	/**
	 * The figures come straight off the session counters, so they are exact and
	 * need no baseline. A spine-derived period returns null when it has fewer
	 * than two baselines to measure between; this one never can.
	 */
	@Test
	public void itsFiguresAreTheSessionItselfNotADifferenceOfBaselines() throws Exception
	{
		period("Session");
		Map<String, Long> said = countersNow();
		assertTrue("the sitting answered with nothing at all", said != null);

		Map<String, Integer> raw = PanelPreviewTest.fixtureStub().sessionView();
		for (Map.Entry<String, Integer> e : raw.entrySet())
		{
			if (e.getValue() != null && e.getValue() != 0)
			{
				assertEquals("counter " + e.getKey() + " is not the session's own",
					Long.valueOf(e.getValue().longValue()), said.get(e.getKey()));
			}
		}
	}

	/** A counter at zero this sitting is absent, not a row reading nought. */
	@Test
	public void aCounterUntouchedThisSittingIsNotCarried() throws Exception
	{
		period("Session");
		for (Map.Entry<String, Long> e : countersNow().entrySet())
		{
			assertTrue(e.getKey() + " was carried at zero", e.getValue() != 0);
		}
	}

	/** And it is not the whole record, however short the sitting has been. */
	@Test
	public void itIsNotMistakenForLifetime() throws Exception
	{
		period("Session");
		Method whole = ChroniclePanel.class.getDeclaredMethod("wholeRecord");
		whole.setAccessible(true);
		assertFalse((Boolean) whole.invoke(panel));
	}

	/**
	 * No arrows. There is one sitting and it is this one; stepping the cursor off
	 * it would name another day and go on calling it "This session".
	 */
	@Test
	public void thereIsNoSteppingToAPreviousSitting() throws Exception
	{
		period("Session");
		final List<String> labels = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("periodRow");
				m.setAccessible(true);
				java.awt.Component row = (java.awt.Component) m.invoke(panel);
				java.util.List<java.awt.Component> flat = new java.util.ArrayList<>();
				flatten(row, flat);
				for (java.awt.Component c : flat)
				{
					if (c instanceof javax.swing.JLabel)
					{
						labels.add(((javax.swing.JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertFalse("the sitting offered a step backwards: " + labels,
			labels.contains("<"));
		assertFalse(labels.contains(">"));
		assertTrue(labels.contains("This session"));
	}

	private static void flatten(java.awt.Component c, List<java.awt.Component> out)
	{
		out.add(c);
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}

	/**
	 * TRAP: a level gained EARLIER TODAY, in a sitting that has since ended.
	 *
	 * <p>The spine is written once a day, so the nearest line before a sitting
	 * that began this evening is the eve of today. Measured from that, everything
	 * done this morning falls inside the sitting: Hunter reads "91 to 92" and the
	 * total level reads "+1" under a heading saying "This session", for something
	 * that happened hours ago in a different sitting.
	 *
	 * <p>Where the sitting began is derivable exactly and needs no baseline: the
	 * experience each skill has now, less what THIS sitting earned.
	 */
	@Test
	public void theSittingOpensWhereTheSittingBeganNotWhereTheDayDid() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("baselineAt", Map.class);
		m.setAccessible(true);

		// 6,517,253 is exactly level 92; 5,902,831 is 91
		Map<String, Long> now = new java.util.HashMap<>();
		now.put("hunter", 6_600_000L);

		// nothing gained this sitting: the sitting opened where it stands, so
		// there is no range to draw and no level to claim
		Object openedQuiet = m.invoke(null, new java.util.HashMap<>(now));
		java.lang.reflect.Method levels = HistoryLog.class.getDeclaredMethod(
			"levels", Class.forName("chronicle.HistoryLog$Baseline"), List.class);
		levels.setAccessible(true);
		Object quiet = levels.invoke(null, openedQuiet, Arrays.asList("hunter"));
		java.lang.reflect.Field of = quiet.getClass().getDeclaredField("of");
		of.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Integer> quietLevels = (Map<String, Integer>) of.get(quiet);
		assertEquals("a skill untouched this sitting opened where it stands",
			Integer.valueOf(92), quietLevels.get("hunter"));

		// and with 300k earned this sitting, it opened at 91 and really did climb
		Map<String, Long> openXp = new java.util.HashMap<>();
		openXp.put("hunter", 6_600_000L - 300_000L);
		Object climbed = levels.invoke(null, m.invoke(null, openXp),
			Arrays.asList("hunter"));
		@SuppressWarnings("unchecked")
		Map<String, Integer> climbedLevels = (Map<String, Integer>) of.get(climbed);
		assertEquals("the sitting's own gain is what moved it",
			Integer.valueOf(91), climbedLevels.get("hunter"));
	}

	/**
	 * A baseline built this way has to be complete, or the total tile stops
	 * naming an opening: it refuses to pair two ends that disagree about how many
	 * skills were drawn, and an incomplete baseline reads an absent skill as
	 * undrawn rather than as level one.
	 */
	@Test
	public void theDerivedOpeningCountsAsACompleteSnapshot() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("baselineAt", Map.class);
		m.setAccessible(true);
		Object at = m.invoke(null, new java.util.HashMap<String, Long>());
		java.lang.reflect.Field complete = at.getClass().getDeclaredField("complete");
		complete.setAccessible(true);
		assertTrue("an incomplete opening cannot be paired with its close",
			complete.getBoolean(at));
	}

	/**
	 * Virtual levels are a statement about where an account STANDS, so they
	 * belong to the whole record. A period reports what moved, and a level past
	 * 99 cannot move.
	 */
	@Test
	public void virtualLevelsAreForTheWholeRecordOnly() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("PaceBook.virtualLevelAt(cur[1])");
		assertTrue("the skill grid no longer reads a virtual level at all", at > 0);
		String around = src.substring(Math.max(0, at - 200), at);
		assertTrue("a virtual level is drawn on a period, where it says nothing"
			+ " about that period", around.contains("wholeRecord()"));
	}

	/** Pretend the client started {@code agoMs} ago. */
	private static void began(long agoMs) throws Exception
	{
		Field pf = ChroniclePanel.class.getDeclaredField("plugin");
		pf.setAccessible(true);
		Object plug = pf.get(panel);
		Field sf = ChroniclePlugin.class.getDeclaredField("sessionStartMs");
		sf.setAccessible(true);
		sf.setLong(plug, System.currentTimeMillis() - agoMs);
	}

	private static long[] windowMs() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("windowMs");
		m.setAccessible(true);
		return (long[]) m.invoke(panel);
	}

	private static boolean inside(long ts) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("insideWindow", long.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(panel, ts);
	}

	/**
	 * TRAP: the one that made every dated board wrong under this period.
	 *
	 * <p>Every other period is a run of whole days, so the code that turns one
	 * into a pair of timestamps rounded to midnight, which loses nothing. The
	 * sitting is hours inside a day, and rounded the same way it swallows
	 * everything since midnight: a level earned at breakfast was reported under
	 * "This session" at teatime, which is what was seen in the client.
	 */
	@Test
	public void theSittingBeginsWhenTheClientDidAndNotAtMidnight() throws Exception
	{
		period("Session");
		began(90 * 60_000L);
		long[] ms = windowMs();
		long now = System.currentTimeMillis();
		// Not "after midnight": a suite run at half past midnight has a sitting
		// that honestly began yesterday evening. The rounding this guards
		// against would put the start on a day boundary, and a start ninety
		// minutes ago is on one only by coincidence.
		assertTrue("the sitting did not begin when the client did",
			Math.abs(ms[0] - (now - 90 * 60_000L)) < 5_000L);
		long dayStart = java.time.Instant.ofEpochMilli(ms[0])
			.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
			.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		assertTrue("the sitting was rounded back to a midnight, which is Day's answer"
			+ " and not the sitting's", ms[0] != dayStart);
		assertTrue("the sitting runs past now", ms[1] >= now - 5_000L);
	}

	/** The same bound, applied to the stamps every dated board files a line by. */
	@Test
	public void aLineFromEarlierTodayIsNotThisSitting() throws Exception
	{
		period("Session");
		began(60 * 60_000L);
		long now = System.currentTimeMillis();
		assertFalse("a line from two hours ago was filed under a sitting an hour old",
			inside(now - 2 * 60 * 60_000L));
		assertTrue("a line from ten minutes ago is this sitting and was dropped",
			inside(now - 10 * 60_000L));
	}

	/**
	 * TRAP: an undated line, which the journal wrote before it carried stamps.
	 * Any period measured in days admits it and files it a little loosely. A
	 * sitting cannot: a line that cannot say when it happened is not evidence
	 * that it happened in the last hour.
	 */
	@Test
	public void anUndatedLineIsNotClaimedByTheSitting() throws Exception
	{
		period("Day");
		assertTrue("a dated period stopped admitting its undated lines", inside(0));
		period("Session");
		assertFalse("an undated line was counted as part of this sitting", inside(0));
		period("Lifetime");
		assertTrue("a lifetime stopped admitting its undated lines", inside(0));
	}

	private static void collect(java.awt.Component c, java.util.List<String> out)
	{
		if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getText() != null)
		{
			out.add(((javax.swing.JLabel) c).getText());
		}
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	/** Every word one skill's drill says. */
	private static java.util.List<String> drill(String craft) throws Exception
	{
		final java.util.List<String> said = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class
					.getDeclaredMethod("buildSkillDetail", String.class);
				m.setAccessible(true);
				collect((java.awt.Component) m.invoke(panel, craft), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	/**
	 * TRAP: the skill drill had BOTH of the flaws the sheet grid above it was
	 * fixed for, and neither was visible from the grid.
	 *
	 * <p>It read its standing off the spine, which is written once a day, so it
	 * stood still through an hour of training. And it measured its gain between
	 * two of the spine's lines, which under the sitting are both today's, so it
	 * reported the whole day. The fixture's sitting holds four skills' gains and
	 * Runecraft is the largest of them.
	 */
	@Test
	public void theSkillDrillMeasuresTheSittingAndNotTheDay() throws Exception
	{
		period("Session");
		began(45 * 60_000L);
		java.util.List<String> said = drill("Runecraft");
		int at = said.indexOf("Gained");
		assertTrue("the drill named no gain at all under the sitting: " + said, at >= 0);
		assertEquals("the drill reported something other than what this sitting"
			+ " earned in Runecraft: " + said, "+400k", said.get(at + 1));
	}

	/**
	 * The drill's other half, and the one the grid above it was fixed for first:
	 * where the skill STANDS is what the client says now, not what the spine's
	 * newest line said whenever it was last written.
	 *
	 * <p>Held on a skill the sitting has moved, so the two answers differ: the
	 * spine's copy of Runecraft is the fixture's journal, and the live sheet is
	 * the client's, four hundred thousand ahead of it.
	 */
	@Test
	public void theDrillStandsWhereTheClientSaysAndNotWhereTheSpineDoes() throws Exception
	{
		period("Session");
		began(45 * 60_000L);
		Field pf = ChroniclePanel.class.getDeclaredField("plugin");
		pf.setAccessible(true);
		long[] live = ((ChroniclePlugin) pf.get(panel)).skillSheet().get("runecraft");
		assertTrue("the fixture's client says nothing about Runecraft, so this"
			+ " asserts nothing", live != null && live.length > 1 && live[1] > 0);

		java.util.List<String> said = drill("Runecraft");
		int at = said.indexOf("Experience");
		assertTrue("the drill named no standing figure: " + said, at >= 0);
		Method gp = ChroniclePanel.class.getDeclaredMethod("gp", long.class);
		gp.setAccessible(true);
		assertEquals("the drill reported the spine's stale copy rather than what"
			+ " the client says the skill stands at: " + said,
			gp.invoke(null, live[1]), said.get(at + 1));
	}

	/**
	 * TRAP: the cards above the button are the WINDOW's tasks, and the button
	 * counted the whole journey. A sitting holding two tasks offered to show
	 * three hundred and ten more, and clicking it mounted a lifetime of cards
	 * under a heading reading "This session". True at every narrowed period, not
	 * only this one.
	 */
	@Test
	public void theMoreButtonCountsTheWindowsTasksAndNotTheJourneys() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("moreRow(every - slayerShown");
		assertTrue("the journey board no longer offers to show more", at > 0);
		String around = src.substring(Math.max(0, at - 320), at);
		assertTrue("the button is sized off j.tasks, which is the whole journey "
			+ "however the period is set: " + around,
			around.contains("shown.size() > slayerShown")
				&& around.contains("every = shown.size()"));
		assertFalse("and the lifetime list is still what it reads",
			around.contains("j.tasks.size() > slayerShown"));
	}

	/**
	 * The sitting ranks its own take, and that is the whole point of it.
	 *
	 * <p>The dated roll keeps ONE entry a day, by design: it is what lets a year
	 * of drops be summed without holding a year of drops. So it could never be
	 * asked what a sitting took, and this board used to say so out loud. The
	 * sitting now keeps its OWN entry in the roll's shape, written as the drops
	 * land, and every board that reads a window reads it without knowing which
	 * of the two it got.
	 */
	@Test
	public void theLootBoardRanksTheSittingsOwnTake() throws Exception
	{
		period("Session");
		began(30 * 60_000L);
		String all = String.join(" | ", lootBoard());
		assertTrue("the sitting's sources are not ranked: " + all,
			all.contains("Abyssal demons"));
		assertTrue("the head card lost the sitting's count: " + all,
			all.contains("Drops"));
		assertFalse("the board still says it cannot answer for a sitting: " + all,
			all.contains("counted as the drops landed"));
		assertFalse("the board answered ABOUT the dated roll, which the sitting is"
			+ " not read from: " + all, all.contains("dated loot roll begins"));
	}

	/**
	 * TRAP: the entry read must be the SITTING's and not the day's. The stub
	 * answers those two with different figures on purpose, so a board that
	 * quietly went back to lootBetween draws the day here and looks fine.
	 */
	@Test
	public void andItIsTheSittingsEntryAndNotTheDays() throws Exception
	{
		period("Session");
		began(30 * 60_000L);
		String sitting = String.join(" | ", lootBoard());
		period("Day");
		String day = String.join(" | ", lootBoard());
		assertFalse("the sitting and the day drew the same board, so one of them "
			+ "is reading the other's entry: " + sitting, sitting.equals(day));
	}

	/**
	 * The counted-things bands take BOTH halves off the sitting's own entry. The
	 * count used to be a delta between two of the spine's lines, and the spine is
	 * written once a day, so under a sitting both ends were today's; the gp
	 * beside it came off the dated roll, which is the day for the same reason.
	 */
	@Test
	public void theCountedThingsBandCountsTheSitting() throws Exception
	{
		period("Session");
		began(20 * 60_000L);
		final java.util.List<String> said = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("addKinds",
					javax.swing.JPanel.class, Map.class, Map.class, Map.class,
					boolean.class, java.time.LocalDate.class, java.time.LocalDate.class,
					String.class, String.class);
				m.setAccessible(true);
				javax.swing.JPanel into = new javax.swing.JPanel();
				into.setLayout(new javax.swing.BoxLayout(into, javax.swing.BoxLayout.Y_AXIS));
				// a spine delta that is NOT the sitting: a band reading this
				// instead of the sitting's entry shows Vorkath and no demons
				java.util.Map<String, Long> spine = new java.util.HashMap<>();
				spine.put("vorkath", 40L);
				m.invoke(panel, into, spine, spine, spine, false,
					java.time.LocalDate.now(), java.time.LocalDate.now(),
					ChroniclePanel.KIND_BOSS, ChroniclePanel.KIND_MONSTER);
				collect(into, said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		String all = String.join(" | ", said);
		assertFalse("the band read the spine, whose two ends are both today's: "
			+ all, all.contains("Vorkath"));
		assertTrue("the band did not count the sitting's own kills: " + all,
			all.contains("Abyssal demons"));
	}

	private static java.util.List<String> lootBoard() throws Exception
	{
		final java.util.List<String> said = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class
					.getDeclaredMethod("dropsInWindow", javax.swing.JPanel.class);
				m.setAccessible(true);
				collect((javax.swing.JPanel) m.invoke(panel, new javax.swing.JPanel()), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	/**
	 * The strip says "This session" because there it opens its own line. Inside a
	 * sentence it is a phrase like any other, and a capital in the middle of one
	 * reads as a typo in the plugin.
	 */
	@Test
	public void theSittingIsNamedInLowerCaseInsideASentence() throws Exception
	{
		period("Session");
		assertEquals("the strip lost its capital", "This session", label());
		Method m = ChroniclePanel.class.getDeclaredMethod("periodInSentence");
		m.setAccessible(true);
		assertEquals("a capital lands in the middle of every sentence naming it",
			"this session", m.invoke(panel));
		// and the periods whose names ARE proper nouns keep theirs
		period("Lifetime");
		assertEquals("Lifetime", m.invoke(panel));
	}

	/**
	 * The item page reads the period, the way the source page does: its head off
	 * the sitting's own entry, and the sources beneath off what each of them
	 * dropped of it in the sitting, not every source that has ever dropped it.
	 * What the heap holds beyond the sources' own rows is Other, so the list
	 * still comes to the head.
	 */
	@Test
	public void theItemPageReadsThePeriod() throws Exception
	{
		stub.periodBags.put("Nechryael", new java.util.ArrayList<>(java.util.Collections.singletonList(
			new LocalStore.BagItem(2363, "Rune bar", 8, 202_742L))));
		period("Session");
		began(30 * 60_000L);
		java.util.List<String> sitting = itemPage("Rune bar");
		stub.periodBags.clear();
		int at = sitting.indexOf("Obtained");
		assertTrue("the sitting's page drew no Obtained row: " + sitting, at >= 0);
		assertEquals("the sitting's page did not read the sitting's own entry: "
			+ sitting, "\u00d712", sitting.get(at + 1));
		int from = sitting.indexOf("FROM");
		assertTrue("no source list: " + sitting, from >= 0);
		java.util.List<String> rows = sitting.subList(from + 1, sitting.size());
		assertEquals("the sitting's sources, then what no source kept: " + sitting,
			java.util.Arrays.asList("Nechryael", "Other", "\u00d74"),
			java.util.Arrays.asList(rows.get(0), rows.get(2), rows.get(3)));
		assertEquals("a source the sitting did not see listed under it: " + sitting,
			4, rows.size());

		period("Lifetime");
		java.util.List<String> whole = itemPage("Rune bar");
		assertFalse("the lifetime page carried an Other row: " + whole, whole.contains("Other"));
	}

	private static java.util.List<String> itemPage(String item) throws Exception
	{
		final java.util.List<String> said = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class
					.getDeclaredMethod("buildItemDetail", String.class);
				m.setAccessible(true);
				collect((javax.swing.JPanel) m.invoke(panel, item), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}
}
