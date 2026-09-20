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

/**
 * What a hover is FOR.
 *
 * <p>These cards used to carry the level and the gain, which are the two things
 * the cell draws without being hovered: the reader moved the mouse and was told
 * what they could already see. They carry the counters that say how the craft is
 * going instead, and they answer for the period the strip is set to, like every
 * other figure on the sheet.
 */
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

	/** Set the period AND drop the per-build memos, the way a rebuild does. */
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
		Method m = ChroniclePanel.class.getDeclaredMethod("skillTip", String.class,
			long.class, Long.class);
		m.setAccessible(true);
		return ((String) m.invoke(panel, craft, 99L, null))
			.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
	}

	/**
	 * TRAP: a hover with nothing in it but the level passes any test that only
	 * asks whether it exists. So this finds a craft the record actually holds
	 * counters for and fails loudly if there is none.
	 */
	@Test
	public void aHoverSaysMoreThanTheCellAlreadyDoes() throws Exception
	{
		period("Lifetime");
		String found = null;
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			String craft = StatRegistry.prettify(sk.name().toLowerCase(java.util.Locale.ROOT));
			String tip = hover(craft);
			if (tip.replace("Level: 99", "").trim().length() > craft.length() + 2)
			{
				found = tip;
				break;
			}
		}
		assertTrue("no craft's hover carried a single counter, so this asserts "
			+ "nothing about hovers at all", found != null);
		assertFalse("the hover is still only the level, which the cell draws "
			+ "without being hovered: " + found,
			found.replace("Level:", "").matches(".*\\d.*99\\s*$"));
	}

	/**
	 * TRAP: the headline of a craft is its TOTAL - creatures trapped, food
	 * cooked - and a period spent on ONE thing need not touch it. A week of
	 * Herbiboar moves herbiboarsHarvested and leaves creaturesTrapped alone, so
	 * a hover that only ever read the headline said nothing on exactly the
	 * period a reader most wants it.
	 */
	@Test
	public void aHoverFallsThroughToWhateverThePeriodMoved() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("private String skillTip(");
		assertTrue("the hover builder is gone", at > 0);
		String body = src.substring(at, src.indexOf("\n\t}", at));
		assertTrue("the hover reads only the craft's headline, so a period that "
			+ "moved something else shows an empty card",
			body.contains("StatRegistry.subgroup("));
		assertTrue("and it no longer ranks what it found", body.contains("reversed()"));
	}

	/** It answers for the period, which is the rule everything on the sheet follows. */
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

	/**
	 * And the activity tiles beside them say BY HOW MUCH. Going bright said only
	 * that the period had moved the tile, and a reader had to hold two visits to
	 * the sheet in their head to work out the difference.
	 */
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
		// the fixture logged a collection slot two hours ago, so today moved it
		assertTrue("no tile stated what the day moved it by: " + said,
			said.contains("+1"));

		// and a lifetime states no movement: everything in it is the movement
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
