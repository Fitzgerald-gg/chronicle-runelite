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
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The imported past carries skills and nothing else. A board reading a window
 * inside it found no kill moved and said nothing was killed, which is a claim
 * about the account the record cannot make: it was not counting. It says so.
 */
public class NotCountingTest
{
	private static final LocalDate KEPT_FROM = LocalDate.of(2026, 8, 31);

	@BeforeClass
	public static void headless() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	private static PanelPreviewTest.StubPlugin record()
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		// the imported year: skills only
		for (int m = 1; m <= 12; m++)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			b.skills.put("woodcutting", 14_000_000L + m * 10_000L);
			b.skills.put("overall", 200_000_000L + m * 1_000_000L);
			s.history.put(LocalDate.of(2025, m, 1).withDayOfMonth(LocalDate.of(2025, m, 1).lengthOfMonth()), b);
		}
		// the plugin's own lines, which carry counters and kill counts
		for (int d = 0; d < 3; d++)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			b.skills.put("woodcutting", 14_600_000L + d * 1_000L);
			b.skills.put("overall", 250_000_000L + d * 1_000L);
			b.counters.put("logsChopped", 95L + d);
			b.kcs.put("Vorkath", 150L + d);
			s.history.put(KEPT_FROM.plusDays(d), b);
		}
		return s;
	}

	private static List<String> board(String method, Object arg, String granularity, LocalDate cursor,
		String family) throws Exception
	{
		PanelPreviewTest.StubPlugin s = record();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set(hold[0], "histGranularity", granularity);
				set(hold[0], "histCursor", cursor);
				if (family != null)
				{
					set(hold[0], "statsFamily", family);
				}
				Method m = arg == null ? ChroniclePanel.class.getDeclaredMethod(method)
					: ChroniclePanel.class.getDeclaredMethod(method, String.class);
				m.setAccessible(true);
				collect((Component) (arg == null ? m.invoke(hold[0]) : m.invoke(hold[0], arg)), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	private static void set(ChroniclePanel p, String name, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, v);
	}

	@Test
	public void aWindowBeforeKillCountsSaysTheRecordWasNotCounting() throws Exception
	{
		String said = String.join(" ", board("buildKills", null, "Year", LocalDate.of(2025, 6, 1), null));
		assertTrue(said, said.contains("The record keeps no kill counts before 31 Aug 2026."));
		assertFalse(said, said.contains("Nothing on the boss sheet was killed"));
	}

	@Test
	public void theCountersBoardsSayTheSame() throws Exception
	{
		String stats = String.join(" ", board("buildStats", null, "Year", LocalDate.of(2025, 6, 1),
			"Ledger & Roads"));
		assertTrue(stats, stats.contains("The record keeps no counters before 31 Aug 2026."));
		String skill = String.join(" ", board("buildSkillDetail", "Woodcutting", "Year",
			LocalDate.of(2025, 6, 1), null));
		assertTrue(skill, skill.contains("The record keeps no counters before 31 Aug 2026."));
		assertFalse(skill, skill.contains("Nothing is tracked under Woodcutting"));
	}

	/** Once it was counting, an empty window is an empty window again. */
	@Test
	public void aWindowAfterCountingBeganStillSaysNothingWhenNothingHappened() throws Exception
	{
		String said = String.join(" ", board("buildKills", null, "Day", KEPT_FROM, null));
		assertFalse(said, said.contains("The record keeps no"));
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null)
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
