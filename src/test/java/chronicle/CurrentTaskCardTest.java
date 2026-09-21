/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The live task card is the open segment on disk, and opens it. Everything a
 * reader mid-task came for already sat on that segment's page; the card was
 * inert and the only way in was the Tasks list.
 */
public class CurrentTaskCardTest
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

	private static Object field(ChroniclePanel p, String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(p);
	}

	private static void opens(String builder) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		final JPanel[] card = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				hold[0] = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod(builder);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(hold[0]), flat);
				for (Component c : flat)
				{
					if (c instanceof JPanel
						&& "Open this task's page".equals(((JPanel) c).getToolTipText()))
					{
						card[0] = (JPanel) c;
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertNotNull("the task card does not open", card[0]);
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : card[0].getMouseListeners())
			{
				l.mousePressed(new MouseEvent(card[0], MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		// the journey is read and the page opened on the next pass of the queue
		SwingUtilities.invokeAndWait(() -> { });
		// the fixture's open task is the first in its journey
		assertEquals(0, field(hold[0], "detailTask"));
		assertEquals("SLAYER", String.valueOf(field(hold[0], "view")));
	}

	@Test
	public void theCardOnNowOpensTheOpenSegment() throws Exception
	{
		opens("buildHome");
	}

	@Test
	public void theCardOnTheSlayerTabOpensItToo() throws Exception
	{
		opens("buildSlayer");
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
