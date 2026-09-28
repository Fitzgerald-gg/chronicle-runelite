/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import javax.swing.JPanel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class HoverStickTest
{
	private static java.awt.event.MouseAdapter clicker(Runnable r) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("clicker", Runnable.class);
		m.setAccessible(true);
		return (java.awt.event.MouseAdapter) m.invoke(null, r);
	}

	private static MouseEvent at(JPanel c, int id)
	{
		return new MouseEvent(c, id, System.currentTimeMillis(), 0, 1, 1, 0, false);
	}

	private static JPanel tile(Color ground)
	{
		JPanel p = new JPanel();
		p.setOpaque(true);
		p.setBackground(ground);
		return p;
	}

	@Test
	public void lightingOneTilePutsTheLastOneBack() throws Exception
	{
		Color ground = new Color(40, 40, 40);
		JPanel a = tile(ground);
		JPanel b = tile(ground);
		a.addMouseListener(clicker(() ->
		{
		}));
		b.addMouseListener(clicker(() ->
		{
		}));

		a.getMouseListeners()[0].mouseEntered(at(a, MouseEvent.MOUSE_ENTERED));
		assertNotEquals("the tile did not light at all", ground, a.getBackground());

		b.getMouseListeners()[0].mouseEntered(at(b, MouseEvent.MOUSE_ENTERED));
		assertEquals("the tile the reader left is still lit", ground, a.getBackground());
		assertNotEquals("and the one they moved to did not light", ground, b.getBackground());
	}

	@Test
	public void anExitStillPutsItBack() throws Exception
	{
		Color ground = new Color(40, 40, 40);
		JPanel a = tile(ground);
		a.addMouseListener(clicker(() ->
		{
		}));
		a.getMouseListeners()[0].mouseEntered(at(a, MouseEvent.MOUSE_ENTERED));
		assertNotEquals(ground, a.getBackground());
		a.getMouseListeners()[0].mouseExited(at(a, MouseEvent.MOUSE_EXITED));
		assertEquals("a plain exit no longer unlights", ground, a.getBackground());
	}

	@Test
	public void aRedrawSweepsWhateverWasLit() throws Exception
	{
		Color ground = new Color(40, 40, 40);
		JPanel a = tile(ground);
		a.addMouseListener(clicker(() ->
		{
		}));
		a.getMouseListeners()[0].mouseEntered(at(a, MouseEvent.MOUSE_ENTERED));
		assertNotEquals(ground, a.getBackground());

		Method m = ChroniclePanel.class.getDeclaredMethod("unlight");
		m.setAccessible(true);
		m.invoke(null);
		assertEquals("a redraw left a tile lit that it had just thrown away",
			ground, a.getBackground());
	}
}
