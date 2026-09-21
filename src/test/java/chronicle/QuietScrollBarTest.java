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

/**
 * The scrollbar answers the reader, not the record.
 *
 * <p>The thumb is drawn over the board and fades out when nothing is moving, so
 * it is only ever on screen because somebody is scrolling. It used to be woken
 * by ANY adjustment of the bar, and a rebuild is several: the pane was thrown
 * away and hung again, its bar born at a fresh zero, and the reader's position
 * put back by hand in two passes. So the thumb flashed to full brightness on
 * every push, which during a grind is most ticks.
 *
 * <p>Two things fix it and both are held here. The pane is hung once and kept,
 * so the viewport holds the reader's place by never being told to move; and the
 * thumb wakes on a wheel turn or a drag of itself, which are the two ways a
 * person moves it, rather than on the board changing underneath them.
 */
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

	/**
	 * TRAP: a thumb at full alpha is a thumb on screen. Nothing about the board
	 * looks wrong in a structural test while it flashes on every redraw, because
	 * a lit bar and a faded one hold exactly the same components.
	 */
	@Test
	public void aRedrawDoesNotLightTheThumb() throws Exception
	{
		ChroniclePanel p = panel();
		JScrollPane pane = pane(p);
		assertEquals("the bar was born lit", 0f, alpha(pane), 0.001f);

		// Laid out at a real size, or the bar has no extent and no model to
		// change, and nothing below would adjust it whatever the rule did.
		SwingUtilities.invokeAndWait(() ->
		{
			p.setSize(242, 400);
			layoutAll(p);
		});

		// A tall board, scrolled down, and then a SHORT one. The reader's
		// position no longer exists on the new board, so the bar clamps it - a
		// value change nobody asked for, which is the one redraw-driven
		// adjustment that survives the pane being kept. Anything waking on a
		// bare adjustment lights the thumb here.
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
		// as though the reader had stopped and the thumb had faded on its own
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

	/** Draw one board and answer the bar's range afterwards. */
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

	/** Put the thumb back to invisible, the way seven hundred idle ms would. */
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

	/**
	 * And the other half, or the test above is satisfied by a bar that never
	 * appears at all: a wheel turn over the board still brings it up.
	 */
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

	/**
	 * The pane itself is the same object across a redraw. That is what lets the
	 * viewport hold the reader's place without anything putting it back, and it
	 * is the difference between a board that changes under them and a board that
	 * is replaced under them.
	 */
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

	/**
	 * TRAP: the viewport now holds the reader's place through every redraw, which
	 * is the whole point - and would be exactly wrong for NAVIGATION. A tab, a
	 * search, an opened detail is a different board, and arriving halfway down
	 * one is arriving lost. The keep is what marks the difference, so both halves
	 * are held here: without the second assertion, a build that simply never
	 * scrolled to the top would pass.
	 */
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

		// a redraw of the board they are already on
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

		// and navigating away
		rebuild(p);
		SwingUtilities.invokeAndWait(() -> after[0] = pane.getVerticalScrollBar().getValue());
		assertEquals("navigation landed halfway down a board the reader has not "
			+ "seen before", 0, after[0]);
	}
}
