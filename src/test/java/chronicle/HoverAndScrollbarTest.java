package chronicle;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two pieces of chrome the reader actually feels: a row that answers the
 * cursor, and a bar that is not there until it is needed.
 *
 * <p>Neither is reachable from the preview harness, which never has a mouse and
 * lays every board out at its full height so no bar is ever raised. Without
 * these, both could be broken for a release and the suite would stay green.
 */
public class HoverAndScrollbarTest
{
	private static MouseAdapter clicker() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("clicker", Runnable.class);
		m.setAccessible(true);
		return (MouseAdapter) m.invoke(null, (Runnable) () ->
		{
		});
	}

	/** A row as the panel builds one: transparent, over an opaque card. */
	private static JPanel rowOverCard(Color card)
	{
		JPanel holder = new JPanel();
		holder.setOpaque(true);
		holder.setBackground(card);
		JPanel row = new JPanel();
		row.setOpaque(false);
		holder.add(row);
		row.setSize(100, 20);
		return row;
	}

	private static MouseEvent at(JPanel c, int id, int x, int y)
	{
		return new MouseEvent(c, id, 0L, 0, x, y, 0, false);
	}

	@Test
	public void aRowLightsUnderTheCursorAndGoesBackAfterward() throws Exception
	{
		Color card = new Color(30, 30, 30);
		JPanel row = rowOverCard(card);
		MouseAdapter hover = clicker();
		row.addMouseListener(hover);

		assertFalse("a row paints nothing of its own at rest", row.isOpaque());

		hover.mouseEntered(at(row, MouseEvent.MOUSE_ENTERED, 5, 5));
		assertTrue("lit, so it has to paint", row.isOpaque());
		assertNotEquals("lit means brighter than the card behind it",
			card, row.getBackground());
		assertTrue(row.getBackground().getRed() > card.getRed());

		// Outside its own bounds: the pointer has really gone.
		hover.mouseExited(at(row, MouseEvent.MOUSE_EXITED, -5, -5));
		assertFalse("and the row is transparent again", row.isOpaque());
	}

	@Test
	public void crossingOntoAChildLeavesTheRowLit() throws Exception
	{
		JPanel row = rowOverCard(new Color(30, 30, 30));
		MouseAdapter hover = clicker();
		row.addMouseListener(hover);

		hover.mouseEntered(at(row, MouseEvent.MOUSE_ENTERED, 5, 5));
		// Swing fires an exit on the parent when the pointer crosses onto a child.
		// The point is still inside the row, and the row must stay lit.
		hover.mouseExited(at(row, MouseEvent.MOUSE_EXITED, 5, 5));
		assertTrue("a label inside the row is still the row", row.isOpaque());
	}

	@Test
	public void aRowThatIsNeverEnteredIsNeverTouched() throws Exception
	{
		JPanel row = rowOverCard(new Color(30, 30, 30));
		Color before = row.getBackground();
		MouseAdapter hover = clicker();
		row.addMouseListener(hover);

		// An exit with no entry: nothing to restore, and nothing to damage.
		hover.mouseExited(at(row, MouseEvent.MOUSE_EXITED, -5, -5));
		assertFalse(row.isOpaque());
		assertEquals(before, row.getBackground());
	}

	@Test
	public void theBarTakesTheGutterItsOwnArithmeticPromises() throws Exception
	{
		Method overlay = ChroniclePanel.class
			.getDeclaredMethod("overlayBar", JScrollPane.class);
		overlay.setAccessible(true);
		Method width = ChroniclePanel.class.getDeclaredMethod("scrollbarWidth");
		width.setAccessible(true);

		JScrollPane scroll = new JScrollPane(new JPanel());
		overlay.invoke(null, scroll);
		JScrollBar bar = scroll.getVerticalScrollBar();

		// chaseRoom() subtracts scrollbarWidth() from the room a name has. If the
		// bar is wider than that, a name is cut to a width the bar then covers.
		assertEquals("the bar takes exactly what the layout arithmetic says",
			((Integer) width.invoke(null)).intValue(),
			bar.getPreferredSize().width);
	}

	@Test
	public void theBarCarriesNoArrowsToPaint() throws Exception
	{
		Method overlay = ChroniclePanel.class
			.getDeclaredMethod("overlayBar", JScrollPane.class);
		overlay.setAccessible(true);
		JScrollPane scroll = new JScrollPane(new JPanel());
		overlay.invoke(null, scroll);
		JScrollBar bar = scroll.getVerticalScrollBar();

		assertFalse("the thumb is the only thing it paints", bar.isOpaque());
		for (java.awt.Component c : bar.getComponents())
		{
			Dimension d = c.getPreferredSize();
			assertEquals("an arrow button would give the gutter a width of its own",
				0, d.width);
			assertEquals(0, d.height);
		}
	}
}
