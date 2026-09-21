/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A lit log slot says when it landed. Every slot enters the feed dated, and
 * only the pets' pages were giving the date up; an ordinary slot answered
 * nothing when hovered.
 */
public class ClogSlotLandedTest
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

	private static JsonObject landed(long ts, String item)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "COLLECTION");
		JsonObject d = new JsonObject();
		d.addProperty("itemName", item);
		e.add("data", d);
		return e;
	}

	@Test
	public void aLitSlotSaysWhenItLanded() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		long first = System.currentTimeMillis() - 30L * 24 * 60 * 60_000L;
		long again = System.currentTimeMillis() - 2L * 24 * 60 * 60_000L;
		// the same slot twice: the FIRST landing is the date
		stub.feed.add(0, landed(again, "Abyssal whip"));
		stub.feed.add(landed(first, "Abyssal whip"));
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		final List<Component> flat = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				hold[0] = new ChroniclePanel(stub);
				Field sel = ChroniclePanel.class.getDeclaredField("clogPageSel");
				sel.setAccessible(true);
				sel.set(hold[0], "Abyssal Sire");
				Method m = ChroniclePanel.class.getDeclaredMethod("buildLog");
				m.setAccessible(true);
				flatten((Component) m.invoke(hold[0]), flat);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		JPanel whip = null;
		JPanel other = null;
		for (Component c : flat)
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof java.awt.BorderLayout)
			{
				Component mid = ((java.awt.BorderLayout) ((JPanel) c).getLayout())
					.getLayoutComponent(java.awt.BorderLayout.CENTER);
				if (mid instanceof JLabel && "Abyssal whip".equals(((JLabel) mid).getText()))
				{
					whip = (JPanel) c;
				}
				if (mid instanceof JLabel && "Abyssal orphan".equals(((JLabel) mid).getText()))
				{
					other = (JPanel) c;
				}
			}
		}
		assertNotNull("the page did not open on its slots", whip);
		String hover = whip.getToolTipText();
		assertNotNull("the slot has no date on hover", hover);
		String day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
			.format(Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()));
		assertTrue(hover, hover.contains("Landed") && hover.contains(day));
		// a slot the feed never dated says nothing rather than something wrong
		if (other != null)
		{
			assertNull(other.getToolTipText());
		}
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
