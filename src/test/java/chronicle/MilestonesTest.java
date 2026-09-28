/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MilestonesTest
{
	private static final LocalDate DAY1 = LocalDate.now().minusDays(40);
	private static final LocalDate DAY2 = LocalDate.now().minusDays(20);
	private static final LocalDate DAY3 = LocalDate.now().minusDays(5);

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

	private static HistoryLog.Baseline line(long xpEach, long fishing, long slots)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		long overall = 0;
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk == net.runelite.api.Skill.OVERALL)
			{
				continue;
			}
			long xp = sk == net.runelite.api.Skill.FISHING ? fishing : xpEach;
			b.skills.put(sk.name().toLowerCase(java.util.Locale.ROOT), xp);
			overall += xp;
		}
		b.skills.put("overall", overall);
		b.counters.put("clogSlotsObtained", slots);
		b.complete = true;
		return b;
	}

	private static PanelPreviewTest.StubPlugin stub()
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.history.put(DAY1, line(1_200_000L, 9_000_000L, 480));
		s.history.put(DAY2, line(1_200_000L, 10_500_000L, 512));
		s.history.put(DAY3, line(13_100_000L, 13_100_000L, 520));
		return s;
	}

	private static List<String> journal(PanelPreviewTest.StubPlugin s, String lens) throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field f = ChroniclePanel.class.getDeclaredField("journalLens");
				f.setAccessible(true);
				f.set(hold[0], lens);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				collect((Component) m.invoke(hold[0]), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	@Test
	public void theCrossingsAreDatedToTheDayTheSpineStoodPastThem() throws Exception
	{
		List<String> said = journal(stub(), "Feats");
		String text = String.join(" | ", said);
		assertTrue(text, said.contains("Milestone: 10M xp in Fishing"));
		assertTrue(text, said.contains("Milestone: 500 collection log slots"));
		assertTrue(text, said.contains("Milestone: Total level 2,000"));
		assertTrue(text, said.contains("Milestone: Total level 2,376"));
		assertTrue(text, said.contains("Milestone: 5th 99"));
		assertTrue(text, said.contains("Milestone: 20th 99"));
		assertTrue(text, said.contains("Milestone: Combat level 126"));
		int fishing = said.indexOf("Milestone: 10M xp in Fishing");
		int nines = said.indexOf("Milestone: 20th 99");
		assertTrue("the newer milestone is not above the older", nines < fishing);
		java.time.format.DateTimeFormatter head =
			java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.UK);
		int day3 = said.indexOf(head.format(DAY3).toUpperCase(java.util.Locale.ROOT));
		int day2 = said.indexOf(head.format(DAY2).toUpperCase(java.util.Locale.ROOT));
		assertTrue(text, day3 >= 0 && day2 > day3);
		assertTrue("the 99s are not under the day they fell: " + text, nines > day3 && nines < day2);
		assertTrue("fishing is not under the day it crossed: " + text, fishing > day2);
	}

	@Test
	public void aPartialLineBetweenTwoCompleteOnesLosesNothing() throws Exception
	{
		PanelPreviewTest.StubPlugin s = stub();
		HistoryLog.Baseline partial = new HistoryLog.Baseline();
		partial.skills.put("fishing", 11_000_000L);
		s.history.put(DAY2.plusDays(1), partial);
		List<String> said = journal(s, "Feats");
		assertTrue(said.toString(), said.contains("Milestone: Total level 2,376"));
		assertTrue(said.toString(), said.contains("Milestone: 20th 99"));
	}

	@Test
	public void whatTheRecordAlreadyStoodPastIsNotACrossing() throws Exception
	{
		List<String> said = journal(stub(), "Feats");
		assertFalse(said.toString(), said.contains("Milestone: Total level 1,000"));
		assertFalse(said.toString(), said.contains("Milestone: Total level 1,500"));
	}

	@Test
	public void theFrontispieceNamesTheLatest() throws Exception
	{
		List<String> said = journal(stub(), "All");
		int at = said.indexOf("Last milestone");
		assertTrue(said.toString(), at >= 0);
		String figure = said.get(at + 1);
		assertTrue(figure, figure.contains(" · ") && !figure.contains("Fishing"));
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}
}
