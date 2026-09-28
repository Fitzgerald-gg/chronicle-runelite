/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class QuietScrollBarTest
{
	private static ChroniclePanel panel() throws Exception
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
		return hold[0];
	}

	private static JScrollPane pane(ChroniclePanel p) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("scrollPane");
		f.setAccessible(true);
		return (JScrollPane) f.get(p);
	}

	private static void rebuild(ChroniclePanel p) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
		m.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				m.invoke(p);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	private static float alpha(JScrollPane pane) throws Exception
	{
		Object ui = pane.getVerticalScrollBar().getUI();
		Field a = ui.getClass().getDeclaredField("alpha");
		a.setAccessible(true);
		return a.getFloat(ui);
	}

	@Test
	public void aRedrawDoesNotLightTheThumb() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane pane = pane(p);
		assertEquals("the bar was born lit", 0f, alpha(pane), 0.001f);

		SwingUtilities.invokeAndWait(() ->
		{
			p.setSize(242, 400);
			layoutAll(p);
		});

		draw(p, pane, "SHEET");
		final int[] parked = {0};
		SwingUtilities.invokeAndWait(() ->
		{
			pane.getVerticalScrollBar().setValue(
				pane.getVerticalScrollBar().getMaximum());
			parked[0] = pane.getVerticalScrollBar().getValue();
		});
		assertTrue("the tall board did not scroll, so nothing can be clamped and "
			+ "this asserts nothing", parked[0] > 0);
		settle(pane);

		draw(p, pane, "HOME");
		final int[] after = {0};
		SwingUtilities.invokeAndWait(() ->
			after[0] = pane.getVerticalScrollBar().getValue());
		assertTrue("the short board did not clamp the reader's position, so the "
			+ "bar was never adjusted: " + parked[0] + " -> " + after[0],
			after[0] < parked[0]);

		assertEquals("a redraw lit the thumb, which is the flash on every push",
			0f, alpha(pane), 0.001f);
	}

	@Test
	public void theHistoryReadLandingKeepsThePlace() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane pane = pane(p);
		SwingUtilities.invokeAndWait(() ->
		{
			p.setSize(242, 400);
			layoutAll(p);
		});
		PanelPreviewTest.awaitHistory(p);
		draw(p, pane, "SHEET");
		final int[] parked = {0};
		SwingUtilities.invokeAndWait(() ->
		{
			pane.getVerticalScrollBar().setValue(120);
			parked[0] = pane.getVerticalScrollBar().getValue();
		});
		assertTrue("the board does not scroll at this size", parked[0] > 0);
		PanelPreviewTest.regatherHistory(p);
		final int[] after = {0};
		SwingUtilities.invokeAndWait(() ->
		{
			layoutAll(p);
			after[0] = pane.getVerticalScrollBar().getValue();
		});
		assertEquals("the history read sent the reader back to the top", parked[0], after[0]);
	}

	@SuppressWarnings("unchecked")
	private static int draw(ChroniclePanel p, JScrollPane pane, String view)
		throws Exception
	{
		Field vf = ChroniclePanel.class.getDeclaredField("view");
		vf.setAccessible(true);
		vf.set(p, Enum.valueOf((Class) Class.forName("chronicle.ChroniclePanel$View"), view));
		rebuild(p);
		final int[] max = {0};
		SwingUtilities.invokeAndWait(() ->
		{
			layoutAll(p);
			max[0] = pane.getVerticalScrollBar().getMaximum();
		});
		return max[0];
	}

	private static void settle(JScrollPane pane) throws Exception
	{
		Object ui = pane.getVerticalScrollBar().getUI();
		Field a = ui.getClass().getDeclaredField("alpha");
		a.setAccessible(true);
		a.setFloat(ui, 0f);
	}

	private static void layoutAll(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				layoutAll(k);
			}
		}
	}

	@Test
	public void aWheelTurnStillBringsItUp() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane pane = pane(p);
		SwingUtilities.invokeAndWait(() -> pane.dispatchEvent(
			new java.awt.event.MouseWheelEvent(pane, java.awt.event.MouseEvent.MOUSE_WHEEL,
				System.currentTimeMillis(), 0, 4, 4, 0, false,
				java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1)));
		assertTrue("the reader turned the wheel and the thumb stayed hidden",
			alpha(pane) > 0.9f);
	}

	@Test
	public void theReaderKeepsOneScrollPaneForever() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane first = pane(p);
		rebuild(p);
		rebuild(p);
		assertSame("the scroll pane was thrown away and hung again", first, pane(p));
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);
		javax.swing.JPanel display = (javax.swing.JPanel) d.get(p);
		int panes = 0;
		for (java.awt.Component c : display.getComponents())
		{
			if (c instanceof JScrollPane)
			{
				panes++;
			}
		}
		assertEquals("the board is hung in more than one pane", 1, panes);
	}

	@Test
	public void aRedrawHoldsThePlaceAndNavigationDoesNot() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane pane = pane(p);
		SwingUtilities.invokeAndWait(() ->
		{
			p.setSize(242, 400);
			layoutAll(p);
		});
		draw(p, pane, "SHEET");

		final int[] parked = {0};
		SwingUtilities.invokeAndWait(() ->
		{
			pane.getVerticalScrollBar().setValue(120);
			parked[0] = pane.getVerticalScrollBar().getValue();
		});
		assertTrue("the board does not scroll at this size, so nothing below "
			+ "asserts anything", parked[0] > 0);

		Field k = ChroniclePanel.class.getDeclaredField("keepScroll");
		k.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				k.setBoolean(p, true);
				Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
				m.setAccessible(true);
				m.invoke(p);
				k.setBoolean(p, false);
				layoutAll(p);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		final int[] after = {0};
		SwingUtilities.invokeAndWait(() -> after[0] = pane.getVerticalScrollBar().getValue());
		assertEquals("a redraw took the reader back to the top of the board they "
			+ "were in the middle of", parked[0], after[0]);

		rebuild(p);
		SwingUtilities.invokeAndWait(() -> after[0] = pane.getVerticalScrollBar().getValue());
		assertEquals("navigation landed halfway down a board the reader has not "
			+ "seen before", 0, after[0]);
	}
}
