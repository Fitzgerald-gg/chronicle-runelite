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

		hover.mouseExited(at(row, MouseEvent.MOUSE_EXITED, -5, -5));
		assertFalse("and the row is transparent again", row.isOpaque());
	}

	@Test
	public void anExitIsJudgedByWhereThePointerActuallyIs() throws Exception
	{
		Method rule = ChroniclePanel.class.getDeclaredMethod("stillUnder",
			boolean.class, boolean.class, boolean.class);
		rule.setAccessible(true);

		assertTrue((Boolean) rule.invoke(null, true, true, false));

		assertFalse("an exit stamped inside was believed over the pointer itself",
			(Boolean) rule.invoke(null, false, true, true));

		assertTrue((Boolean) rule.invoke(null, false, false, true));
		assertFalse((Boolean) rule.invoke(null, false, false, false));
	}

	@Test
	public void theHoverColourIsTheClientsOwn() throws Exception
	{
		Method hoverOf = ChroniclePanel.class
			.getDeclaredMethod("hoverOf", Color.class);
		hoverOf.setAccessible(true);
		assertEquals(net.runelite.client.ui.ColorScheme.DARKER_GRAY_HOVER_COLOR,
			hoverOf.invoke(null, net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR));
		assertEquals(net.runelite.client.ui.ColorScheme.DARK_GRAY_HOVER_COLOR,
			hoverOf.invoke(null, net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR));
	}

	@Test
	public void aTilePaintingItsOwnGroundIsHoveredAsItself() throws Exception
	{
		JPanel grid = new JPanel();
		grid.setOpaque(true);
		grid.setBackground(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
		JPanel tile = new JPanel();
		tile.setOpaque(true);
		tile.setBackground(net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR);
		grid.add(tile);
		tile.setSize(40, 20);

		MouseAdapter hover = clicker();
		tile.addMouseListener(hover);
		hover.mouseEntered(at(tile, MouseEvent.MOUSE_ENTERED, 5, 5));
		assertEquals("a tile darker than its grid was hovered as though it were"
				+ " the grid", net.runelite.client.ui.ColorScheme.DARKER_GRAY_HOVER_COLOR,
			tile.getBackground());
	}

	@Test
	public void aRowThatIsNeverEnteredIsNeverTouched() throws Exception
	{
		JPanel row = rowOverCard(new Color(30, 30, 30));
		Color before = row.getBackground();
		MouseAdapter hover = clicker();
		row.addMouseListener(hover);

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

	@Test
	public void theWorkedExampleAboveChaseRoomAddsUp() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("chaseRoom", String.class, java.awt.FontMetrics.class);
		m.setAccessible(true);
		java.awt.FontMetrics zero = new java.awt.FontMetrics(
			javax.swing.UIManager.getFont("Label.font") != null
				? javax.swing.UIManager.getFont("Label.font")
				: new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12))
		{
			@Override
			public int stringWidth(String s)
			{
				return 0;
			}
		};
		assertEquals("the itemised terms in the comment above chaseRoom come to 193",
			193, ((Integer) m.invoke(null, "", zero)).intValue());
	}
}
