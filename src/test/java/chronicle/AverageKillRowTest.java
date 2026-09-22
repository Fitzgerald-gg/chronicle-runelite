/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A source's page carries the ordinary time beside the best, and the best
 * says when it was set and what it beat. The Journal names the record.
 */
public class AverageKillRowTest
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

	private static JsonObject record(long ts, String source, double time, double was)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "RECORD");
		JsonObject d = new JsonObject();
		d.addProperty("source", source);
		d.addProperty("time", time);
		if (was > 0)
		{
			d.addProperty("was", was);
		}
		e.add("data", d);
		return e;
	}

	@Test
	public void thePageReadsTheAverageAndTheDatedBest() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.sources.add(new LocalStore.SourceRow("Vorkath", 156, 156, 20_000_000L, 70.0,
			0, 0, java.util.Collections.emptySet(), 47, 47 * 72.0));
		stub.bags.put("Vorkath", new ArrayList<>());
		long set = System.currentTimeMillis() - 3L * 24 * 60 * 60_000L;
		stub.feed.add(0, record(set, "Vorkath", 70.0, 72.0));
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		final JPanel[] page = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				hold[0] = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				page[0] = (JPanel) m.invoke(hold[0], "Vorkath");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals("1:12 · 47 timed", beside(page[0], "Average kill"));
		String best = beside(page[0], "Personal best");
		assertNotNull(best);
		assertTrue(best, best.startsWith("1:10 · set "));
		assertEquals("Was 1:12", rowNamed(page[0], "Personal best").getToolTipText());
		// and the Journal names it
		Method fl = ChroniclePanel.class.getDeclaredMethod("feedLine", JsonObject.class);
		fl.setAccessible(true);
		assertEquals("Record: Vorkath 1:10, was 1:12", fl.invoke(null, record(set, "Vorkath", 70.0, 72.0)));
		assertEquals("Record: Vorkath 1:10", fl.invoke(null, record(set, "Vorkath", 70.0, 0)));
	}

	private static JPanel rowNamed(Component c, String left)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout)
			{
				Component mid = ((BorderLayout) ((JPanel) k).getLayout()).getLayoutComponent(BorderLayout.CENTER);
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText()))
				{
					return (JPanel) k;
				}
			}
		}
		return null;
	}

	private static String beside(Component c, String left)
	{
		JPanel r = rowNamed(c, left);
		if (r == null)
		{
			return null;
		}
		Component east = ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.EAST);
		return east instanceof JLabel ? ((JLabel) east).getText() : null;
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
