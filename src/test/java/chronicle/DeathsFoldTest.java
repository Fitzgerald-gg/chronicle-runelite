/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Deaths row on Trackers opens to who dealt them, ranked, with the last
 * date each did. Deaths stays one row; the killers are behind it.
 */
public class DeathsFoldTest
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

	private static JsonObject death(long ts, String killer)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "DEATH");
		JsonObject d = new JsonObject();
		if (killer != null)
		{
			d.addProperty("killerName", killer);
		}
		e.add("data", d);
		return e;
	}

	@Test
	public void theRowOpensToTheKillers() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.lifetime.put("deaths", 4L);
		long now = System.currentTimeMillis();
		stub.feed.add(0, death(now - 1_000L, "Vorkath"));
		stub.feed.add(0, death(now - 2_000L, "Zulrah"));
		stub.feed.add(0, death(now - 3_000L, "Vorkath"));
		stub.feed.add(0, death(now - 4_000L, null));
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		ChroniclePanel p = hold[0];
		Field fam = ChroniclePanel.class.getDeclaredField("statsFamily");
		fam.setAccessible(true);
		fam.set(p, "Combat");
		JPanel shut = stats(p);
		JPanel deaths = rowNamed(shut, "Deaths");
		assertNotNull("no Deaths row", deaths);
		assertNull("the killers are open before the row is pressed", rowNamed(shut, "Vorkath"));
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : deaths.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(deaths, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		JPanel open = stats(p);
		String vorkath = beside(open, "Vorkath");
		assertNotNull("the killers did not open", vorkath);
		assertTrue(vorkath, vorkath.startsWith("2 · last "));
		assertTrue(beside(open, "Zulrah").startsWith("1 · last "));
		assertTrue(beside(open, "Unknown").startsWith("1 · last "));
		// ranked: the one who did it most first
		List<String> said = new ArrayList<>();
		collect(open, said);
		assertTrue(said.toString(), said.indexOf("Vorkath") < said.indexOf("Zulrah"));
	}

	private static JPanel stats(ChroniclePanel p) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildStats");
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
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
