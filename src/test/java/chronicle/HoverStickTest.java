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

/**
 * A hover that comes off again, however the leaving is noticed.
 *
 * <p>mouseExited is not a promise. A tooltip drawn over a tile can leave
 * getMousePosition non-null, so the exit is declined as "still inside"; the
 * pointer then leaves while the tooltip has it, no second exit is ever
 * delivered, and the tile stays lit with nothing to put it back. It goes
 * missing again when a component is taken out of the hierarchy under the
 * cursor, which a redraw does constantly.
 *
 * <p>So the lighting does not depend on it: at most one tile is lit, and
 * lighting any tile puts the last one back.
 */
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

	/**
	 * TRAP: the one that was seen in the client. Tile A is entered, its exit is
	 * swallowed, and the reader moves to tile B. Without a registry A stays lit
	 * for as long as the panel lives, and nothing about B looks wrong.
	 */
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

		// A's exit never arrives - swallowed by its own tooltip - and the reader
		// is now over B.
		b.getMouseListeners()[0].mouseEntered(at(b, MouseEvent.MOUSE_ENTERED));
		assertEquals("the tile the reader left is still lit", ground, a.getBackground());
		assertNotEquals("and the one they moved to did not light", ground, b.getBackground());
	}

	/** And the ordinary path still works, or the registry has replaced it. */
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

	/**
	 * A redraw takes the lit tile out of the hierarchy, and a component removed
	 * under the cursor is never told it was exited.
	 */
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
