/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillGroundLabelTest
{
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Angler\",\"drops\":{"
		+ "\"Fishing Trawler\":{\"kc\":40,\"loots\":40,\"value\":1200000,"
		+ "\"items\":{\"5\":{\"id\":5,\"name\":\"Raw shark\",\"qty\":40,"
		+ "\"value\":1200000}}}},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-skill-ground");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "angler.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Angler");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	private static List<String> drill(ChroniclePanel p, String period) throws Exception
	{
		Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
		g.setAccessible(true);
		g.set(p, period);
		Field from = ChroniclePanel.class.getDeclaredField("histFrom");
		from.setAccessible(true);
		from.set(p, null);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class
					.getDeclaredMethod("buildSkillDetail", String.class);
				m.setAccessible(true);
				collect((Component) m.invoke(p, "Fishing"), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
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

	@Test
	public void theBlockNamesItselfAsTheLifetimeAtEveryPeriod() throws Exception
	{
		ChroniclePanel p = panel();
		int covered = 0;
		for (String period : ChroniclePanel.PERIODS)
		{
			List<String> said = drill(p, period);
			boolean noPeriod = false;
			for (String line : said)
			{
				if (line.startsWith("Nothing closed inside") || line.startsWith("Reading your history"))
				{
					noPeriod = true;
				}
			}
			if (noPeriod)
			{
				continue;
			}
			covered++;
			assertTrue("the skill's own ground stopped being listed at " + period
				+ ": " + said, said.contains("Fishing Trawler"));
			assertFalse("a figure no period touches is headed as though the period"
				+ " produced it, at " + period + ": " + said,
				said.contains("WHAT IT PAID"));
			assertTrue("the block lost its heading at " + period + ": " + said,
				said.contains("WHAT IT HAS EVER PAID"));
		}
		assertTrue("every period skipped, so this asserted nothing at all",
			covered >= 2);
	}

	@Test
	public void andTheFigureReallyIsTheSameAtEveryPeriod() throws Exception
	{
		ChroniclePanel p = panel();
		String lifetime = figureFor(drill(p, "Lifetime"));
		String sitting = figureFor(drill(p, ChroniclePanel.SESSION));
		assertTrue("the fixture drew no figure to compare", lifetime != null);
		assertTrue("the block now answers the period, so its heading must stop "
			+ "claiming to be the lifetime: " + lifetime + " vs " + sitting,
			lifetime.equals(sitting));
	}

	private static String figureFor(List<String> said)
	{
		int at = said.indexOf("Fishing Trawler");
		return at >= 0 && at + 1 < said.size() ? said.get(at + 1) : null;
	}
}
