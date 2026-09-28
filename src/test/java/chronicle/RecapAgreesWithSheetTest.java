/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RecapAgreesWithSheetTest
{
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

	@Test
	public void aPartialOpeningLineDoesNotLoseASkill() throws Exception
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		HistoryLog.Baseline eve = new HistoryLog.Baseline();
		eve.skills.put("attack", 1_000_000L);
		s.history.put(LocalDate.of(2024, 12, 31), eve);
		HistoryLog.Baseline mid = new HistoryLog.Baseline();
		mid.skills.put("attack", 1_500_000L);
		mid.skills.put("fishing", 2_000_000L);
		s.history.put(LocalDate.of(2025, 6, 30), mid);
		HistoryLog.Baseline close = new HistoryLog.Baseline();
		close.skills.put("attack", 2_000_000L);
		close.skills.put("fishing", 6_000_000L);
		s.history.put(LocalDate.of(2025, 12, 31), close);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final long[] recap = new long[1];
		final long[] sheet = new long[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(hold[0], "Year");
				Field c = ChroniclePanel.class.getDeclaredField("histCursor");
				c.setAccessible(true);
				c.set(hold[0], LocalDate.of(2025, 6, 1));
				Method xp = ChroniclePanel.class.getDeclaredMethod("periodXp");
				xp.setAccessible(true);
				recap[0] = ((long[]) xp.invoke(hold[0]))[0];
				Method gains = ChroniclePanel.class.getDeclaredMethod("periodSkillGains");
				gains.setAccessible(true);
				long total = 0;
				for (Object v : ((java.util.Map<?, ?>) gains.invoke(hold[0])).values())
				{
					total += (Long) v;
				}
				sheet[0] = total;
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals(5_000_000L, recap[0]);
		assertEquals(sheet[0], recap[0]);
	}
}
