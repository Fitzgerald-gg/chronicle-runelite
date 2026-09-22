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
import static org.junit.Assert.assertNull;

/**
 * Now, the board named for the sitting, says when it began and how long it
 * has run, and what it produced that a player would tell a friend.
 *
 * <p>The duration was reachable only on the Journal's live line or by hovering
 * a Hiscores tile under the Session period; a level gained this sitting was
 * not on Now at all, and the reader went to the Journal for it.
 */
public class SittingFeatsTest
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

	private static JPanel home(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildHome");
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

	private static JsonObject entry(long ts, String type, String key, String val)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", type);
		JsonObject d = new JsonObject();
		d.addProperty(key, val);
		e.add("data", d);
		return e;
	}

	/** The EAST label of the row whose CENTER label reads {@code left}. */
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
				Component west = l.getLayoutComponent(BorderLayout.WEST);
				Component east = l.getLayoutComponent(BorderLayout.EAST);
				for (Component name : new Component[]{mid, west})
				{
					if (name instanceof JLabel && left.equals(((JLabel) name).getText())
						&& east instanceof JLabel)
					{
						return ((JLabel) east).getText();
					}
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

	@Test
	public void theCaptionSaysWhenTheSittingBeganAndHowLongItHasRun() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.sessionStartMs = System.currentTimeMillis() - 125 * 60_000L;
		stub.sessionElapsed = 125;
		String note = beside(home(stub), "THIS SESSION");
		assertNotNull("the caption carries no note", note);
		assertEquals(note, true, note.matches("since \\d\\d:\\d\\d · 2h 5m"));
	}

	@Test
	public void withNoSittingTheCaptionStandsAlone() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.sessionStartMs = 0;
		assertNull(beside(home(stub), "THIS SESSION"));
	}

	@Test
	public void theSittingsLevelsAreOneRow() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		long now = System.currentTimeMillis();
		stub.sessionStartMs = now - 60 * 60_000L;
		stub.sessionElapsed = 60;
		// The fixture's own feed has a log slot kept on today, which between
		// midnight and two in the morning falls inside this sitting.
		stub.feed.clear();
		JsonObject hunter = entry(now - 30 * 60_000L, "LEVEL", "skill", "hunter");
		hunter.getAsJsonObject("data").addProperty("level", "90");
		JsonObject fletch = entry(now - 20 * 60_000L, "LEVEL", "skill", "fletching");
		fletch.getAsJsonObject("data").addProperty("level", "87");
		// the same skill again: the row names the level it reached, once
		JsonObject hunter2 = entry(now - 10 * 60_000L, "LEVEL", "skill", "hunter");
		hunter2.getAsJsonObject("data").addProperty("level", "91");
		// before the sitting began: not this sitting's
		JsonObject old = entry(now - 3 * 60 * 60_000L, "LEVEL", "skill", "magic");
		old.getAsJsonObject("data").addProperty("level", "80");
		stub.feed.add(0, hunter2);
		stub.feed.add(1, fletch);
		stub.feed.add(2, hunter);
		stub.feed.add(3, old);
		stub.feed.add(4, entry(now - 5 * 60_000L, "COLLECTION", "itemName", "Pristine spider silk"));
		JPanel home = home(stub);
		assertEquals("Hunter 91 · Fletching 87", beside(home, "Levels"));
		assertEquals("Pristine spider silk", beside(home, "Log slot"));
	}

	@Test
	public void threeLevelsCountAndNameThemselvesOnHover() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		long now = System.currentTimeMillis();
		stub.sessionStartMs = now - 60 * 60_000L;
		stub.sessionElapsed = 60;
		for (String[] s : new String[][]{{"hunter", "90"}, {"fletching", "87"}, {"magic", "80"}})
		{
			JsonObject e = entry(now - 30 * 60_000L, "LEVEL", "skill", s[0]);
			e.getAsJsonObject("data").addProperty("level", s[1]);
			stub.feed.add(0, e);
		}
		assertEquals("+3", beside(home(stub), "Levels"));
	}
}
