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
		// A fixed month, not "ten days ago": on the 1st and 2nd of a month that
		// fell in the month before and the window held one line, not two.
		LocalDate cursor = LocalDate.of(2026, 6, 15);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.history.put(cursor.withDayOfMonth(5), line(100, 40_000L, 200, 20_000L));
		s.history.put(cursor.withDayOfMonth(13), line(312, 136_000L, 540, 61_000L));
		List<String> said = living(s, cursor);
		assertEquals(said.toString(), "212 · 96k gp", after(said, "FOOD"));
		assertEquals(said.toString(), "340 · 41k gp", after(said, "POTIONS"));
	}

	/**
	 * And a window that opened before the split was being written gets the
	 * count alone: a whole window's meals beside part of its spend is one
	 * figure pretending to account for the other.
	 */
	@Test
	public void aWindowOpeningBeforeTheSplitGetsTheCountAlone() throws Exception
	{
		LocalDate cursor = LocalDate.of(2026, 6, 15);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		// the window opens on a line written before the split existed
		HistoryLog.Baseline before = line(100, 0L, 200, 0L);
		before.counters.remove("foodConsumedValue");
		before.counters.remove("potionsConsumedValue");
		s.history.put(cursor.minusMonths(1).withDayOfMonth(25), before);
		// and the split begins part way through it, so the spend it can measure
		// covers part of a count that covers the whole window
		s.history.put(cursor.withDayOfMonth(5), line(180, 50_000L, 320, 22_000L));
		s.history.put(cursor.withDayOfMonth(13), line(312, 136_000L, 540, 61_000L));
		List<String> said = living(s, cursor);
		assertEquals(said.toString(), "212", after(said, "FOOD"));
		assertEquals(said.toString(), "340", after(said, "POTIONS"));
	}

	/**
	 * The whole record says one spend. The trackers' own spend began when the
	 * plugin started writing it; the ledger also prices every meal and dose
	 * eaten before. The board said "Consumed value 632k" over Food and Potions
	 * heads adding to 2.16M, so the whole record reads the ledger throughout.
	 * And meals and doses head their sections, what no item row can claim
	 * being the heads' own "Other" rather than a second row beside them.
	 */
	@Test
	public void theWholeRecordSaysOneSpend() throws Exception
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.lifetime.put("foodEaten", 2_420L);
		s.lifetime.put("sharkEaten", 2_410L);
		s.lifetime.put("potionDoses", 1_479L);
		s.lifetime.put("prayerPotionDoses", 747L);
		s.lifetime.put("consumedValue", 638_206L);
		s.consumVals.put("sharkEaten", 1_299_422L);
		s.consumVals.put("prayerPotionDoses", 861_495L);
		List<String> said = living(s, null);
		assertEquals(said.toString(), "2.2M gp", after(said, "Consumed value"));
		assertEquals(said.toString(), "2,420 · 1.3M gp", after(said, "FOOD"));
		assertEquals(said.toString(), "1,479 · 861k gp", after(said, "POTIONS"));
		assertEquals("meals stand beside the head that counts them: " + said,
			-1, said.indexOf("Meals eaten"));
	}

	/** The Living board for one month, as its labels and figures. */
	private static List<String> living(PanelPreviewTest.StubPlugin s, LocalDate cursor)
		throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				for (String[] f : new String[][]{{"histGranularity", cursor == null ? "Lifetime" : "Month"},
					{"statsFamily", "Living"}})
				{
					Field fld = ChroniclePanel.class.getDeclaredField(f[0]);
					fld.setAccessible(true);
					fld.set(hold[0], f[1]);
				}
				if (cursor != null)
				{
					Field cur = ChroniclePanel.class.getDeclaredField("histCursor");
					cur.setAccessible(true);
					cur.set(hold[0], cursor);
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
		return said;
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
