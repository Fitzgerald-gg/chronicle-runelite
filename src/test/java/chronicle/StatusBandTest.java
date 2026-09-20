/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Silence when nothing is wrong, and a band that fixes itself when it can.
 *
 * <p>The chrome used to carry a green pip reading "logging" on every board,
 * always. It carried no information - the plugin is always logging while the
 * panel is open - and its only content was the absence of gold or red, which
 * an absent row expresses better. So there is no row while nothing is wrong,
 * and when something is, the row IS the colour: a tinted band with the words
 * on it, amber for a plugin we capture through being off, red for the journal
 * not saving. The amber one is pressed to put right; the red one cannot be.
 */
public class StatusBandTest
{
	private static PanelPreviewTest.StubPlugin stub;
	private static ChroniclePanel panel;

	private static void build() throws Exception
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
		stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
	}

	private static JPanel band() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("band");
		f.setAccessible(true);
		return (JPanel) f.get(panel);
	}

	private static JLabel text() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("bandText");
		f.setAccessible(true);
		return (JLabel) f.get(panel);
	}

	private static void rebuild() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
		m.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				m.invoke(panel);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	/** TRAP: the state the panel is in almost all of the time. Nothing is drawn. */
	@Test
	public void nothingWrongMeansNoRowAtAll() throws Exception
	{
		build();
		rebuild();
		assertFalse("a row is on screen with nothing to say", band().isVisible());
	}

	@Test
	public void aPluginSwitchedOffIsAnAmberBandThatOffersTheFix() throws Exception
	{
		build();
		stub.captureWarning = "Loot Tracker off";
		stub.captureWarningWhy = "Turn on the Loot Tracker plugin.";
		rebuild();
		JPanel b = band();
		assertTrue("the band did not appear for a plugin being off", b.isVisible());
		assertTrue("the band does not say what to do: " + text().getText(),
			text().getText().contains("click to turn it on"));
		assertEquals("the band does not answer the cursor as something pressable",
			Cursor.getPredefinedCursor(Cursor.HAND_CURSOR), b.getCursor());
		assertNotEquals("the band is not tinted, so it is a row and not a hue",
			ColorScheme.DARK_GRAY_COLOR, b.getBackground());
		assertNotEquals("amber and red came out the same colour",
			ColorScheme.PROGRESS_ERROR_COLOR, text().getForeground());
		assertEquals("the sentence is not on the band", "Turn on the Loot Tracker plugin.",
			b.getToolTipText());
	}

	/** The click does the thing, and the band comes down on the same rebuild. */
	@Test
	public void pressingTheAmberBandTurnsThePluginOn() throws Exception
	{
		build();
		stub.captureWarning = "Slayer off";
		stub.captureWarningWhy = "Turn on the Slayer plugin.";
		rebuild();
		JPanel b = band();
		SwingUtilities.invokeAndWait(() ->
		{
			for (java.awt.event.MouseListener l : b.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(b, MouseEvent.MOUSE_PRESSED,
					System.currentTimeMillis(), 0, 2, 2, 1, false));
			}
		});
		assertEquals("the press did not ask the plugin to be switched on", 1, stub.fixesAsked);
		// the stub clears the warning the way the real check does; the rebuild the
		// press queued is on the EDT behind us
		SwingUtilities.invokeAndWait(() ->
		{
		});
		SwingUtilities.invokeAndWait(() ->
		{
		});
		assertFalse("the band stayed up after the thing it warned of was fixed",
			band().isVisible());
	}

	/**
	 * Red cannot be fixed from a side panel - a full disk is not emptied by a
	 * click - so it offers no press, and it is the more serious colour.
	 */
	@Test
	public void notSavingIsRedAndOffersNoPress() throws Exception
	{
		build();
		stub.journalWarning = "Could not write the journal to disk.";
		stub.captureWarning = "Slayer off";   // and the worse one wins
		rebuild();
		JPanel b = band();
		assertTrue(b.isVisible());
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, text().getForeground());
		assertEquals("a red band offered a press it cannot honour",
			Cursor.getDefaultCursor(), b.getCursor());
		assertFalse("the red band borrowed the amber band's words: " + text().getText(),
			text().getText().contains("click"));
		assertEquals("Could not write the journal to disk.", b.getToolTipText());
		SwingUtilities.invokeAndWait(() ->
		{
			for (java.awt.event.MouseListener l : b.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(b, MouseEvent.MOUSE_PRESSED,
					System.currentTimeMillis(), 0, 2, 2, 1, false));
			}
		});
		assertEquals("a press on the red band tried to switch a plugin on", 0, stub.fixesAsked);
	}

	/** The wash is the ground with the colour laid thinly over it, not the colour. */
	@Test
	public void theTintIsAWashAndNotTheFullColour() throws Exception
	{
		Method w = ChroniclePanel.class.getDeclaredMethod("wash", Color.class);
		w.setAccessible(true);
		Color red = (Color) w.invoke(null, ColorScheme.PROGRESS_ERROR_COLOR);
		Color g = ColorScheme.DARK_GRAY_COLOR;
		assertTrue("the wash is as loud as the colour itself",
			red.getRed() < ColorScheme.PROGRESS_ERROR_COLOR.getRed());
		assertTrue("the wash is not tinted at all", red.getRed() > g.getRed());
		assertTrue("a red wash came out greener than the ground",
			red.getGreen() <= g.getGreen() + 2);
	}
}
