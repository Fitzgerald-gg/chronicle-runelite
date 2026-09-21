/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

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
 * One page per boss: a source's page carries what its kills left on the floor,
 * and opens the twin page from there. The floor sat only under the other
 * lens, reached by going back and finding the same name in a different list.
 */
public class SourceFloorRowTest
{
	private static ChroniclePanel panel;
	private static JPanel page;

	@BeforeClass
	public static void build() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
				PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
				panel = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				page = (JPanel) m.invoke(panel, "Abyssal demons");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	@Test
	public void thePageSaysWhatWasLeftBehind() throws Exception
	{
		JPanel row = rowNamed(page, "Left behind");
		assertNotNull("no floor row on the source page", row);
		String figure = ((JLabel) ((BorderLayout) row.getLayout())
			.getLayoutComponent(BorderLayout.EAST)).getText();
		assertTrue(figure, figure.startsWith("412 · ") && figure.endsWith(" gp"));
	}

	@Test
	public void theRowOpensTheTwinPage() throws Exception
	{
		JPanel row = rowNamed(page, "Left behind");
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : row.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		Field f = ChroniclePanel.class.getDeclaredField("leftBehindSource");
		f.setAccessible(true);
		assertEquals("Abyssal demons", f.get(panel));
		Field d = ChroniclePanel.class.getDeclaredField("detailSource");
		d.setAccessible(true);
		assertNull("the received page is still standing under the floor's", d.get(panel));
	}

	private static JPanel rowNamed(Component c, String left)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout)
			{
				Component mid = ((BorderLayout) ((JPanel) k).getLayout())
					.getLayoutComponent(BorderLayout.CENTER);
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText()))
				{
					return (JPanel) k;
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
}
