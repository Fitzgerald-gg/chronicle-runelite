/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.panel.StatRegistry;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SkillHoverTest
{
	private static ChroniclePanel panel;

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
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		panel = hold[0];
		PanelPreviewTest.regatherHistory(panel);
		PanelPreviewTest.awaitHistory(panel);
	}

	private static void period(String g) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("histGranularity");
		f.setAccessible(true);
		f.set(panel, g);
		Field from = ChroniclePanel.class.getDeclaredField("histFrom");
		from.setAccessible(true);
		from.set(panel, null);
		for (String memo : new String[]{"buildPeriodCounters", "buildSpan", "movedTypes",
			"rolledKcs", "movedKcs"})
		{
			Field m = ChroniclePanel.class.getDeclaredField(memo);
			m.setAccessible(true);
			m.set(panel, null);
		}
		for (String flag : new String[]{"periodCountersAsked", "spanAsked"})
		{
			Field m = ChroniclePanel.class.getDeclaredField(flag);
			m.setAccessible(true);
			m.setBoolean(panel, false);
		}
	}

	private static String hover(String craft) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("skillTip", String.class);
		m.setAccessible(true);
		return ((String) m.invoke(panel, craft))
			.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
	}

	private static List<String> rows(String craft) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("skillTip", String.class);
		m.setAccessible(true);
		String html = (String) m.invoke(panel, craft);
		List<String> out = new ArrayList<>();
		java.util.regex.Matcher r = java.util.regex.Pattern
			.compile("<div>([^<:]+):").matcher(html);
		while (r.find())
		{
			out.add(r.group(1).trim());
		}
		return out;
	}

	@Test
	public void aHoverDoesNotRepeatTheCellItIsOver() throws Exception
	{
		period("Lifetime");
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			String craft = StatRegistry.prettify(sk.name().toLowerCase(java.util.Locale.ROOT));
			List<String> r = rows(craft);
			assertFalse(craft + " repeats the level the cell draws: " + r, r.contains("Level"));
			assertFalse(craft + " repeats the experience the cell draws: " + r,
				r.contains("Experience") || r.contains("Gained"));
		}
	}

	@Test
	public void everyRowIsATopLevelCounterOfItsCraft() throws Exception
	{
		period("Lifetime");
		int rowsSeen = 0;
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			String craft = StatRegistry.prettify(sk.name().toLowerCase(java.util.Locale.ROOT));
			java.util.Set<String> allowed = new java.util.HashSet<>();
			for (String key : StatRegistry.headlines(craft))
			{
				allowed.add(StatRegistry.rowLabel(key));
			}
			for (String label : rows(craft))
			{
				rowsSeen++;
				assertTrue(craft + " carries a row that is not one of its top-level "
					+ "counters, so it is a typed row leaking up: " + label,
					allowed.contains(label));
			}
		}
		assertTrue("no craft carried a single row, so this asserts nothing", rowsSeen > 0);
	}

	@Test
	public void prayerReadsAsTheOwnerSpelledIt() throws Exception
	{
		java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "chronicle-prayer-hover");
		dir.mkdirs();
		try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "monk.json")))
		{
			w.write("{\"schema\":1,\"rsn\":\"Monk\",\"drops\":{},"
				+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
				+ "\"trackers\":{\"bonesBuried\":23,\"ashesScattered\":4,"
				+ "\"bonesSacrificed\":931,\"ashesSacrificed\":1093,"
				+ "\"headsReanimated\":203,\"dragonBonesBuried\":4,"
				+ "\"demonHeadsReanimated\":26},"
				+ "\"skills\":{},\"feed\":[]}");
		}
		PanelPreviewTest.StubPlugin monk = PanelPreviewTest.journalStub(dir.getPath(), "Monk");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(monk));
		ChroniclePanel was = panel;
		panel = hold[0];
		try
		{
			period("Lifetime");
			List<String> r = rows("Prayer");
			for (String want : new String[]{"Bones buried", "Ashes scattered",
				"Bones sacrificed", "Ashes sacrificed", "Heads reanimated"})
			{
				assertTrue("Prayer's card is missing " + want + ": " + r, r.contains(want));
			}
			for (String label : r)
			{
				assertFalse("a typed row is on Prayer's card: " + label,
					label.startsWith("Dragon") || label.startsWith("Demon"));
			}
		}
		finally
		{
			panel = was;
		}
	}

	@Test
	public void aHoverAnswersForThePeriod() throws Exception
	{
		period("Lifetime");
		String whole = hover("Runecraft");
		period("Day");
		String day = hover("Runecraft");
		assertNotEquals("the hover said the same thing for a lifetime and for a "
			+ "day, so it is not reading the period at all", whole, day);
	}

	@Test
	public void anActivityTileStatesItsMovement() throws Exception
	{
		period("Day");
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("activitySheet");
				m.setAccessible(true);
				collect((Component) m.invoke(panel), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue("no tile stated what the day moved it by: " + said,
			said.contains("+1"));

		period("Lifetime");
		said.clear();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("activitySheet");
				m.setAccessible(true);
				collect((Component) m.invoke(panel), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		for (String s : said)
		{
			assertFalse("a lifetime claimed a movement, which is the whole of it: "
				+ said, s.startsWith("+"));
		}
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null
			&& !((JLabel) c).getText().isEmpty())
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
