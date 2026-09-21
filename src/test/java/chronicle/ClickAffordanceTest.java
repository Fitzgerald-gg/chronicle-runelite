/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * The two halves of a click, held together.
 *
 * <p>A control in this panel is a hand cursor AND a clicker() listener. Either
 * one alone is a defect that no structural test can see, because a row that does
 * nothing has exactly the shape of a row that does something:
 *
 * <ul>
 *   <li>A hand with no listener promises a click that never happens. The diary
 *       board carried one for every tier.
 *   <li>A listener with no hand is a control nobody can find. That is the
 *       complaint an actual user made: they did not know the rows were clickable.
 * </ul>
 *
 * <p>There is a third, subtler way to lose a click, and it is why this test looks
 * at the LABEL rather than only at the row. Calling setToolTipText registers a
 * component with the ToolTipManager, which attaches a mouse listener; from then
 * on that component is the deepest listening thing under the pointer, and Swing
 * delivers the press to it and does not pass it up to the parent. So a tipped
 * label inside a clickable row silently eats the row's click. The Slayer task
 * picker lost its whole right hand side that way.
 */
public class ClickAffordanceTest
{
	private static ChroniclePanel panel;

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
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
	}

	/** A listener this panel installed, as opposed to the ToolTipManager's. */
	private static boolean ours(MouseListener l)
	{
		for (Class<?> c = l.getClass(); c != null; c = c.getEnclosingClass())
		{
			if (c == ChroniclePanel.class)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean clickable(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			if (ours(l))
			{
				return true;
			}
		}
		return false;
	}

	/** True when this component or an ancestor carries a real click. */
	private static boolean clickableUp(Component c)
	{
		for (Component at = c; at != null; at = at.getParent())
		{
			if (clickable(at))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean handUp(Component c)
	{
		Cursor cur = c.getCursor();
		return cur != null && cur.getType() == Cursor.HAND_CURSOR;
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

	private static String describe(Component c)
	{
		StringBuilder sb = new StringBuilder(c.getClass().getSimpleName());
		if (c instanceof JLabel && ((JLabel) c).getText() != null)
		{
			sb.append(" \"").append(((JLabel) c).getText()).append('"');
		}
		for (Component at = c.getParent(); at != null; at = at.getParent())
		{
			if (at instanceof Container)
			{
				for (Component sib : ((Container) at).getComponents())
				{
					if (sib instanceof JLabel && sib != c
						&& ((JLabel) sib).getText() != null)
					{
						sb.append(" (beside \"").append(((JLabel) sib).getText())
							.append("\")");
						return sb.toString();
					}
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Puts back the navigation state a previous walk left behind, and cuts every
	 * capped list down to one row.
	 *
	 * <p>The cut is what makes the "this list goes on" control appear at all. The
	 * fixture's lists are shorter than the panel's own caps, so without it none of
	 * them truncates and the rule about how a truncated list is drawn is asserted
	 * over a set of boards containing no such control.
	 */
	private static void clearNav() throws Exception
	{
		for (String f : new String[]{"sheetPage"})
		{
			Field nf = ChroniclePanel.class.getDeclaredField(f);
			nf.setAccessible(true);
			nf.set(panel, null);
		}
		for (String f : new String[]{"dropsShown", "slayerShown", "journalShown"})
		{
			Field nf = ChroniclePanel.class.getDeclaredField(f);
			nf.setAccessible(true);
			nf.setInt(panel, 1);
		}
	}

	/**
	 * Every board, drawn, flattened into one list of components.
	 *
	 * <p>Each render clears the navigation state first. Without that, the sheet
	 * page this walk finishes on is still set when the next walk starts, and every
	 * board it then asks for is answered with that page instead - so the first
	 * caller in a JVM sees the panel and the rest see one screen over and over.
	 * That is not a hypothetical: it made this very test pass while the defect it
	 * was written for was sitting in the tree.
	 */
	private static List<Component> everySurface() throws Exception
	{
		Class<?> viewClass = Class.forName("chronicle.ChroniclePanel$View");
		Object[] views = viewClass.getEnumConstants();
		final List<Component> all = new ArrayList<>();
		for (Object v : views)
		{
			final Object view = v;
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					clearNav();
					Field vf = ChroniclePanel.class.getDeclaredField("view");
					vf.setAccessible(true);
					vf.set(panel, view);
					Method tabFor = ChroniclePanel.class.getDeclaredMethod("tabFor", viewClass);
					tabFor.setAccessible(true);
					Object owner = tabFor.invoke(panel, view);
					Field tf = ChroniclePanel.class.getDeclaredField("tab");
					tf.setAccessible(true);
					tf.set(panel, owner);
					Method subFor = ChroniclePanel.class.getDeclaredMethod("subFor", viewClass);
					subFor.setAccessible(true);
					Field sf = ChroniclePanel.class.getDeclaredField("subByTab");
					sf.setAccessible(true);
					@SuppressWarnings("unchecked")
					Map<Object, String> subs = (Map<Object, String>) sf.get(panel);
					subs.put(owner, (String) subFor.invoke(panel, view));
					Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
					rebuild.setAccessible(true);
					rebuild.invoke(panel);
					flatten(panel, all);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			});
		}
		// The lenses within a view. A board reached only by a lens is invisible to
		// a walk over the View enum, and the Slayer picker whose right hand side
		// was dead lived on exactly such a board.
		for (String lens : new String[]{"Tasks", "Monsters", "Drops"})
		{
			final String l = lens;
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					clearNav();
					setEnumField("view", "SLAYER");
					Field lf = ChroniclePanel.class.getDeclaredField("slayerLens");
					lf.setAccessible(true);
					lf.set(panel, l);
					Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
					rebuild.setAccessible(true);
					rebuild.invoke(panel);
					flatten(panel, all);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			});
		}
		// and the sheet's own pages, which are a page WITHIN a view
		for (String page : new String[]{"clues", "quests", "diaries", "combat"})
		{
			final String p = page;
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					clearNav();
					setEnumField("view", "SHEET");
					Field sp = ChroniclePanel.class.getDeclaredField("sheetPage");
					sp.setAccessible(true);
					sp.set(panel, p);
					Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
					rebuild.setAccessible(true);
					rebuild.invoke(panel);
					flatten(panel, all);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			});
		}
		return all;
	}

	private static void setEnumField(String field, String constant) throws Exception
	{
		Class<?> viewClass = Class.forName("chronicle.ChroniclePanel$View");
		for (Object k : viewClass.getEnumConstants())
		{
			if (((Enum<?>) k).name().equals(constant))
			{
				Field f = ChroniclePanel.class.getDeclaredField(field);
				f.setAccessible(true);
				f.set(panel, k);
				return;
			}
		}
	}

	@Test
	public void noHandCursorPromisesAClickThatDoesNotHappen() throws Exception
	{
		Set<String> bad = new LinkedHashSet<>();
		for (Component c : everySurface())
		{
			// isCursorSet, not getCursor: children inherit the row's hand, and a
			// label inside a clickable row is not itself expected to listen.
			if (c.isCursorSet() && handUp(c) && !clickableUp(c))
			{
				bad.add(describe(c));
			}
		}
		assertTrue("a hand cursor with no click behind it, anywhere up the tree:\n  "
			+ String.join("\n  ", bad), bad.isEmpty());
	}

	@Test
	public void noTippedLabelSwallowsItsRowsClick() throws Exception
	{
		Set<String> bad = new LinkedHashSet<>();
		for (Component c : everySurface())
		{
			if (!(c instanceof javax.swing.JComponent))
			{
				continue;
			}
			javax.swing.JComponent j = (javax.swing.JComponent) c;
			if (j.getToolTipText() == null || clickable(j))
			{
				continue;
			}
			// A tip registers a mouse listener, which makes this the deepest
			// listening component: any click meant for an ancestor dies here.
			Component parent = j.getParent();
			if (parent != null && clickableUp(parent))
			{
				bad.add(describe(c));
			}
		}
		assertTrue("a tooltip on a label inside a clickable row eats that row's"
			+ " click; give the label its own clicker or drop the tooltip:\n  "
			+ String.join("\n  ", bad), bad.isEmpty());
	}

	/**
	 * The walk has to actually reach the boards. A test that flattens an empty
	 * tree passes for the wrong reason, and every surface added after this one is
	 * written is a surface it silently stops covering.
	 */
	@Test
	public void theWalkReachesTheBoardsItClaimsTo() throws Exception
	{
		List<Component> all = everySurface();
		assertTrue("the walk found almost nothing: " + all.size(), all.size() > 500);
		Set<String> tips = new LinkedHashSet<>();
		int clickers = 0;
		for (Component c : all)
		{
			if (c instanceof javax.swing.JComponent)
			{
				String t = ((javax.swing.JComponent) c).getToolTipText();
				if (t != null)
				{
					tips.add(t);
				}
			}
			if (clickable(c))
			{
				clickers++;
			}
		}
		assertTrue("no clickable components found at all", clickers > 50);
		assertTrue("the Slayer task picker was never drawn, so the lens walk is not"
			+ " reaching its board: " + tips.size() + " tooltips seen",
			tips.contains("Narrow this board to one task"));

		// and a truncated list, or noBoardDrawsASwingButton is asserting over a
		// set of surfaces that contains no "this list goes on" control at all
		boolean sawMore = false;
		for (Component c : all)
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null
				&& ((JLabel) c).getText().startsWith("Show ")
				&& ((JLabel) c).getText().endsWith(" more"))
			{
				sawMore = true;
				break;
			}
		}
		assertTrue("no list in the walk was long enough to be truncated, so the"
			+ " button rule is not actually being exercised", sawMore);
	}

	/**
	 * The other half of the rule. A control that listens must also answer the
	 * cursor: this is the complaint an actual user made, that they did not know
	 * the rows could be clicked.
	 *
	 * <p>Five pill strips carried a listener and no cursor while a sixth, built
	 * identically, set one - so it was oversight rather than an exemption. They
	 * were never silent, since clicker() also paints a hover lift, but the panel
	 * states its own invariant as both tells and the strips were the only places
	 * carrying one.
	 */
	@Test
	public void everyControlAnswersTheCursor() throws Exception
	{
		Set<String> bad = new LinkedHashSet<>();
		for (Component c : everySurface())
		{
			if (clickable(c) && !handUp(c))
			{
				bad.add(describe(c));
			}
		}
		assertTrue("these listen for a click but do not answer the cursor:\n  "
			+ String.join("\n  ", bad), bad.isEmpty());
	}

	/**
	 * One language for "this list goes on".
	 *
	 * <p>There were three: a Swing JButton on some boards, a dim clickable row on
	 * others, and on one a dim row that was not clickable at all. The JButton is
	 * the only heavy chrome in a panel built entirely of rows, and it reads as a
	 * dialog control that wandered in. Every one of them is a row now.
	 *
	 * <p>The scrollbar's own increase and decrease buttons are exempt: they are
	 * required by BasicScrollBarUI and are stubbed to zero size precisely so they
	 * never draw.
	 */
	@Test
	public void noBoardDrawsASwingButton() throws Exception
	{
		Set<String> bad = new LinkedHashSet<>();
		for (Component c : everySurface())
		{
			if (!(c instanceof javax.swing.JButton))
			{
				continue;
			}
			// Buttons inside a component the CLIENT supplies are its own
			// machinery: the scroll bar's increase and decrease stubs, and the
			// clear button RuneLite puts inside its search field.
			boolean theirs = false;
			for (Component at = c.getParent(); at != null; at = at.getParent())
			{
				if (at instanceof javax.swing.JScrollBar
					|| at instanceof net.runelite.client.ui.components.IconTextField)
				{
					theirs = true;
					break;
				}
			}
			java.awt.Dimension d = c.getPreferredSize();
			if (!theirs && (d.width > 0 || d.height > 0))
			{
				bad.add(describe(c) + "  " + d.width + "x" + d.height);
			}
		}
		assertTrue("a Swing button in a panel made of rows; use moreRow() for a"
			+ " list that goes on, or actionRow() for something that acts:\n  "
			+ String.join("\n  ", bad), bad.isEmpty());
	}
}
