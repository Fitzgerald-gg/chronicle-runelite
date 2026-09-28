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

	@Test
	public void aCounterUntouchedThisSittingIsNotCarried() throws Exception
	{
		period("Session");
		for (Map.Entry<String, Long> e : countersNow().entrySet())
		{
			assertTrue(e.getKey() + " was carried at zero", e.getValue() != 0);
		}
	}

	@Test
	public void itIsNotMistakenForLifetime() throws Exception
	{
		period("Session");
		Method whole = ChroniclePanel.class.getDeclaredMethod("wholeRecord");
		whole.setAccessible(true);
		assertFalse((Boolean) whole.invoke(panel));
	}

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

	@Test
	public void theSittingOpensWhereTheSittingBeganNotWhereTheDayDid() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("baselineAt", Map.class);
		m.setAccessible(true);

		Map<String, Long> now = new java.util.HashMap<>();
		now.put("hunter", 6_600_000L);

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

		Map<String, Long> openXp = new java.util.HashMap<>();
		openXp.put("hunter", 6_600_000L - 300_000L);
		Object climbed = levels.invoke(null, m.invoke(null, openXp),
			Arrays.asList("hunter"));
		@SuppressWarnings("unchecked")
		Map<String, Integer> climbedLevels = (Map<String, Integer>) of.get(climbed);
		assertEquals("the sitting's own gain is what moved it",
			Integer.valueOf(91), climbedLevels.get("hunter"));
	}

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

	@Test
	public void theSittingBeginsWhenTheClientDidAndNotAtMidnight() throws Exception
	{
		period("Session");
		began(90 * 60_000L);
		long[] ms = windowMs();
		long now = System.currentTimeMillis();
		assertTrue("the sitting did not begin when the client did",
			Math.abs(ms[0] - (now - 90 * 60_000L)) < 5_000L);
		long dayStart = java.time.Instant.ofEpochMilli(ms[0])
			.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
			.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		assertTrue("the sitting was rounded back to a midnight, which is Day's answer"
			+ " and not the sitting's", ms[0] != dayStart);
		assertTrue("the sitting runs past now", ms[1] >= now - 5_000L);
	}

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

	@Test
	public void theMoreButtonCountsTheWindowsTasksAndNotTheJourneys() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("more(p, shown.size(), slayerShown");
		assertTrue("the journey board no longer offers to show more", at > 0);
		String around = src.substring(Math.max(0, at - 320), at);
		assertTrue("the button is sized off j.tasks, which is the whole journey "
			+ "however the period is set: " + around,
			around.contains("shown.size() > slayerShown"));
		assertFalse("and the lifetime list is still what it reads",
			around.contains("j.tasks.size() > slayerShown"));
	}

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

	@Test
	public void theSittingIsNamedInLowerCaseInsideASentence() throws Exception
	{
		period("Session");
		assertEquals("the strip lost its capital", "This session", label());
		Method m = ChroniclePanel.class.getDeclaredMethod("periodInSentence");
		m.setAccessible(true);
		assertEquals("a capital lands in the middle of every sentence naming it",
			"this session", m.invoke(panel));
		period("Lifetime");
		assertEquals("Lifetime", m.invoke(panel));
	}

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
			java.util.Arrays.asList("Nechryael", "Other", "\u00d74 \u00b7 101k gp"),
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
