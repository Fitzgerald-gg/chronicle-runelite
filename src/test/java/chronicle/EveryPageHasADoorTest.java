/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
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

/**
 * The two pages that were reachable only by typing their names into the
 * search box have doors: Info from the Journal's nameplate, All trackers
 * from the Trackers head.
 */
public class EveryPageHasADoorTest
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

	private static void pressDoor(ChroniclePanel p, String builder, String left, String field) throws Exception
	{
		final JPanel[] door = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod(builder);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p), flat);
				for (Component c : flat)
				{
					if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout)
					{
						Component mid = ((BorderLayout) ((JPanel) c).getLayout()).getLayoutComponent(BorderLayout.CENTER);
						if (mid instanceof JLabel && left.equals(((JLabel) mid).getText())
							&& c.getCursor().getType() == Cursor.HAND_CURSOR)
						{
							door[0] = (JPanel) c;
						}
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertNotNull("no door named " + left, door[0]);
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : door[0].getMouseListeners())
			{
				l.mousePressed(new MouseEvent(door[0], MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		assertEquals(true, f.get(p));
	}

	@Test
	public void infoOpensFromTheNameplate() throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		pressDoor(hold[0], "buildJournal", "what the journal holds", "showInfo");
	}

	@Test
	public void allTrackersOpensFromTheTrackersHead() throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		Field tab = ChroniclePanel.class.getDeclaredField("tab");
		tab.setAccessible(true);
		tab.set(hold[0], Enum.valueOf((Class) Class.forName("chronicle.ChroniclePanel$Tab"), "TRACKERS"));
		pressDoor(hold[0], "buildStats", "every counter in one place", "allTrackers");
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
