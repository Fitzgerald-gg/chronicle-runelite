/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FoughtAsTest
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

	private static String timeRow(String source, Map<String, Long> minutes) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.sources.add(new LocalStore.SourceRow(source, 0, 0, 1_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		stub.bags.put(source, new ArrayList<>());
		java.time.LocalDate cursor = java.time.LocalDate.of(2026, 6, 15);
		stub.history.clear();
		HistoryLog.Baseline open = new HistoryLog.Baseline();
		HistoryLog.Baseline close = new HistoryLog.Baseline();
		open.counters.put("timeIdle", 0L);
		close.counters.put("timeIdle", 0L);
		for (Map.Entry<String, Long> e : minutes.entrySet())
		{
			open.counters.put(e.getKey(), 0L);
			close.counters.put(e.getKey(), e.getValue());
		}
		stub.history.put(cursor.withDayOfMonth(5), open);
		stub.history.put(cursor.withDayOfMonth(13), close);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		final JPanel[] page = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = hold[0];
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(p, "Month");
				Field c = ChroniclePanel.class.getDeclaredField("histCursor");
				c.setAccessible(true);
				c.set(p, cursor);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				page[0] = (JPanel) m.invoke(p, source);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return beside(page[0], "Time here");
	}

	@Test
	public void aRaidAddsUpTheRoomsItIsFoughtIn() throws Exception
	{
		Map<String, Long> minutes = new java.util.LinkedHashMap<>();
		minutes.put("timeGreatOlm", 40L);
		minutes.put("timeTekton", 25L);
		minutes.put("timeVespula", 15L);
		minutes.put("timeVorkath", 600L);
		assertEquals("1h 20m", timeRow("Chambers of Xeric", minutes));
	}

	@Test
	public void barrowsAddsUpItsBrothers() throws Exception
	{
		Map<String, Long> minutes = new java.util.LinkedHashMap<>();
		minutes.put("timeAhrimTheBlighted", 10L);
		minutes.put("timeDharokTheWretched", 10L);
		minutes.put("timeVeracTheDefiled", 10L);
		assertEquals("30m", timeRow("Barrows Chests", minutes));
	}

	@Test
	public void aBossKeepsItsOwnNameAndItsMinions() throws Exception
	{
		Map<String, Long> minutes = new java.util.LinkedHashMap<>();
		minutes.put("timeGeneralGraardor", 50L);
		minutes.put("timeSergeantSteelwill", 5L);
		minutes.put("timeSergeantGrimspike", 5L);
		assertEquals("1h 0m", timeRow("General Graardor", minutes));
	}

	@Test
	public void theGauntletIsFoughtAsItsHunllef() throws Exception
	{
		Map<String, Long> minutes = new java.util.LinkedHashMap<>();
		minutes.put("timeCrystallineHunllef", 45L);
		minutes.put("timeCorruptedHunllef", 30L);
		assertEquals("45m", timeRow("The Gauntlet", minutes));
		assertEquals("30m", timeRow("The Corrupted Gauntlet", minutes));
	}

	@Test
	public void aFightWithNoMinutesDrawsNoRow() throws Exception
	{
		assertNull(timeRow("Zulrah", new java.util.LinkedHashMap<>()));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void noNpcBelongsToTwoFights() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("FOUGHT_AS");
		f.setAccessible(true);
		Map<String, List<String>> table = (Map<String, List<String>>) f.get(null);
		assertTrue("the table is empty", table.size() >= 20);
		Map<String, String> owner = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, List<String>> e : table.entrySet())
		{
			for (String npc : e.getValue())
			{
				String had = owner.put(npc, e.getKey());
				if (had != null)
				{
					assertTrue(npc + " is fought as " + had + " and " + e.getKey(),
						sameFight(had, e.getKey()));
				}
			}
		}
	}

	private static boolean sameFight(String a, String b)
	{
		Set<String> pair = new HashSet<>(Arrays.asList(a, b));
		return pair.equals(new HashSet<>(Arrays.asList("callisto", "artio")))
			|| pair.equals(new HashSet<>(Arrays.asList("venenati", "spindel")))
			|| pair.equals(new HashSet<>(Arrays.asList("vet'ion", "calvar'ion")))
			|| a.startsWith(b) || b.startsWith(a);
	}

	private static String beside(Component c, String left)
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
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText())
					&& east instanceof JLabel)
				{
					return ((JLabel) east).getText();
				}
			}
		}
		return null;
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
}
