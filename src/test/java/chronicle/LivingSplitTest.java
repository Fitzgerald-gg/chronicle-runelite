/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.BorderLayout;
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
import static org.junit.Assert.assertEquals;

/**
 * The Food and Potions folds carry the period's gp beside their counts, from
 * the split the trackers now write at the bite and the dose. A week's count
 * never sits beside a career's spend.
 */
public class LivingSplitTest
{
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

	private static HistoryLog.Baseline line(long food, long foodGp, long doses, long potionGp)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("attack", 1_000_000L);
		b.skills.put("overall", 1_000_000L);
		b.counters.put("foodEaten", food);
		b.counters.put("sharkEaten", food);
		b.counters.put("foodConsumedValue", foodGp);
		b.counters.put("potionDoses", doses);
		b.counters.put("prayerPotionDoses", doses);
		b.counters.put("potionsConsumedValue", potionGp);
		b.counters.put("consumedValue", foodGp + potionGp);
		b.complete = true;
		return b;
	}

	@Test
	public void aPeriodsFoldHeadReadsThePeriodsSpend() throws Exception
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.history.put(LocalDate.now().minusDays(10), line(100, 40_000L, 200, 20_000L));
		s.history.put(LocalDate.now().minusDays(2), line(312, 136_000L, 540, 61_000L));
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				for (String[] f : new String[][]{{"histGranularity", "Month"}, {"statsFamily", "Living"}})
				{
					Field fld = ChroniclePanel.class.getDeclaredField(f[0]);
					fld.setAccessible(true);
					fld.set(hold[0], f[1]);
				}
				Method m = ChroniclePanel.class.getDeclaredMethod("buildStats");
				m.setAccessible(true);
				collect((Component) m.invoke(hold[0]), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals(said.toString(), "212 · 96k gp", after(said, "FOOD"));
		assertEquals(said.toString(), "340 · 41k gp", after(said, "POTIONS"));
	}

	private static String after(List<String> said, String label)
	{
		int at = said.indexOf(label);
		return at >= 0 && at + 1 < said.size() ? said.get(at + 1) : null;
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
