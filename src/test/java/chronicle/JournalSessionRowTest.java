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
 * A closed sitting's line on the Journal is drawn as two columns, like every
 * other row on the panel, with the whole sentence on hover.
 *
 * <p>As one sentence it ran past the column on any longer sitting and cut at
 * "75 drops (", which was the one line the Journal had for what a sitting
 * amounted to.
 */
public class JournalSessionRowTest
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

	@Test
	public void theSittingReadsFiguresRightAndTheSentenceOnHover() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		JsonObject e = new JsonObject();
		e.addProperty("ts", System.currentTimeMillis() - 60 * 60_000L);
		e.addProperty("type", "SESSION");
		JsonObject d = new JsonObject();
		d.addProperty("minutes", 82);
		d.addProperty("xp", 207_000);
		d.addProperty("drops", 75);
		d.addProperty("dropsGp", 1_200_000);
		e.add("data", d);
		stub.feed.add(0, e);
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p);
			}
			catch (Exception ex)
			{
				throw new RuntimeException(ex);
			}
		});
		JPanel row = null;
		List<Component> flat = new ArrayList<>();
		flatten(out[0], flat);
		for (Component c : flat)
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout)
			{
				Component mid = ((BorderLayout) ((JPanel) c).getLayout())
					.getLayoutComponent(BorderLayout.CENTER);
				if (mid instanceof JLabel && "Session · 1h 22m".equals(((JLabel) mid).getText()))
				{
					row = (JPanel) c;
				}
			}
		}
		assertNotNull("no two-column session row", row);
		Component east = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.EAST);
		assertEquals("+207k xp · 75 drops", ((JLabel) east).getText());
		String hover = row.getToolTipText();
		assertNotNull("the sentence is not on hover", hover);
		assertTrue(hover, hover.startsWith("Session: 1h 22m") && hover.contains("gp"));
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
