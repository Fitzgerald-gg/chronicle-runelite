/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.Kit.ScrollColumn;
import chronicle.panel.HistoryProgress;
import chronicle.panel.StatRegistry;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicScrollBarUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import static chronicle.Feed.most;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.PANEL_INSET;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.card;
import static chronicle.Kit.clicker;
import static chronicle.Kit.column;
import static chronicle.Kit.copyLabel;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.nested;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.overlayBar;
import static chronicle.Kit.pad;
import static chronicle.Kit.part;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.sumOf;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.tip;
import static chronicle.Kit.unlight;
import static chronicle.Kit.value;
import static chronicle.Kit.wash;
import static chronicle.Period.TASK_DAY;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.share;

final class Kit
{
	private Kit()
	{
	}

	static final Color DARK = ColorScheme.DARK_GRAY_COLOR;

	static final Color DARKER = ColorScheme.DARKER_GRAY_COLOR;

	static final Color ACCENT_LIFETIME = ColorScheme.BRAND_ORANGE;

	static final Color ACCENT_SESSION = new Color(85, 163, 90);

	static final Color ACCENT_RED = new Color(196, 84, 74);

	static final Color TILE_LIT = new Color(198, 198, 198);

	static final int ROW_CAP = 30;

	static final int PANEL_INSET = 8;

	static final int CARD_INSET = 8;

	static final int ROW_INSET = 2;

	static final int ROW_GAP = 8;

	static Color wash(Color c)
	{
		Color g = DARK;
		double a = 0.22;
		return new Color(
			(int) Math.round(g.getRed() + (c.getRed() - g.getRed()) * a),
			(int) Math.round(g.getGreen() + (c.getGreen() - g.getGreen()) * a),
			(int) Math.round(g.getBlue() + (c.getBlue() - g.getBlue()) * a));
	}

	static void listCard(JPanel p, JPanel head, String under, String right, Runnable go)
	{
		JPanel card = cardPlain();
		card.add(head);
		card.add(row(under, right));
		link(card, go);
		spaced(p, card, 4);
	}

	static JPanel tallyCard(String title, String lead, String figure, Color ink, long worth)
	{
		JPanel head = card(title);
		head.add(row(lead, figure, ink));
		head.add(worthRow(worth));
		return head;
	}

	static JLabel copyLabel(JPanel r, String tip)
	{
		JLabel take = part(r, BorderLayout.EAST);
		if (take != null)
		{
			styled(take, small(), dim());
			take.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			take.setToolTipText(tip);
		}
		return take;
	}

	static JPanel facts(JPanel c, Map<String, Long> f, Color lead, String... rows)
	{
		for (int i = 0; i < rows.length; i += 2)
		{
			c.add(row(rows[i], fmt(f.getOrDefault(rows[i + 1], 0L)), i == 0 ? lead : null));
		}
		return c;
	}

	static final String ELLIPSIS = "…";

	static final int NAME_FLOOR = 3;

	static final JLabel MEASURE = new JLabel();

	static FontMetrics rowMetrics()
	{
		return MEASURE.getFontMetrics(FontManager.getRunescapeFont());
	}

	static int scrollbarWidth()
	{
		return OVERLAY_BAR_W;
	}

	static final int OVERLAY_BAR_W = 5;

	static void overlayBar(JScrollPane scroll)
	{
		JScrollBar bar = scroll.getVerticalScrollBar();
		OverlayScrollBarUI ui = new OverlayScrollBarUI();
		bar.setUI(ui);
		scroll.addMouseWheelListener(e -> ui.wake());
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(OVERLAY_BAR_W, 0));
	}

	static final class OverlayScrollBarUI
		extends BasicScrollBarUI
	{
		private static final int IDLE_MS = 700;
		private static final int STEP_MS = 40;
		private static final float STEP = 0.12f;
		private static final Color THUMB = new Color(0xB0, 0xB0, 0xB0);

		private float alpha;
		private long lastMove;
		private Timer fader;

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return nothing();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return nothing();
		}

		private static JButton nothing()
		{
			JButton b = new JButton();
			Dimension none = new Dimension(0, 0);
			b.setPreferredSize(none);
			b.setMinimumSize(none);
			b.setMaximumSize(none);
			b.setFocusable(false);
			return b;
		}

		@Override
		protected void installListeners()
		{
			super.installListeners();
			scrollbar.addAdjustmentListener(e ->
			{
				if (e.getValueIsAdjusting())
				{
					wake();
				}
			});
		}

		@Override
		protected void paintTrack(Graphics g, JComponent c,
			Rectangle bounds)
		{
		}

		@Override
		protected void paintThumb(Graphics g, JComponent c,
			Rectangle t)
		{
			if (alpha <= 0.02f || t.isEmpty())
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setComposite(AlphaComposite.getInstance(
				AlphaComposite.SRC_OVER, Math.min(1f, alpha)));
			g2.setColor(THUMB);
			int h = Math.max(OVERLAY_BAR_W * 2, t.height - 4);
			g2.fillRoundRect(t.x, t.y + 2, OVERLAY_BAR_W, h,
				OVERLAY_BAR_W, OVERLAY_BAR_W);
			g2.dispose();
		}

		void wake()
		{
			lastMove = System.currentTimeMillis();
			alpha = 1f;
			scrollbar.repaint();
			if (fader == null)
			{
				fader = new Timer(STEP_MS, e -> tick());
			}
			if (!fader.isRunning())
			{
				fader.start();
			}
		}

		private void tick()
		{
			if (scrollbar == null || !scrollbar.isShowing())
			{
				alpha = 0f;
				fader.stop();
				return;
			}
			if (System.currentTimeMillis() - lastMove < IDLE_MS)
			{
				return;
			}
			alpha -= STEP;
			if (alpha <= 0f)
			{
				alpha = 0f;
				fader.stop();
			}
			scrollbar.repaint();
		}

		@Override
		public void uninstallUI(JComponent c)
		{
			if (fader != null)
			{
				fader.stop();
			}
			super.uninstallUI(c);
		}
	}

	static int chaseRoom(String share, FontMetrics fm)
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * CARD_INSET
			- 2 * ROW_INSET - ROW_GAP - fm.stringWidth(share);
	}

	static final class Line
	{
		final List<String> pieces = new ArrayList<>();
		final List<Integer> names = new ArrayList<>();

		void fixed(String s)
		{
			pieces.add(s);
		}

		void name(String s)
		{
			names.add(pieces.size());
			pieces.add(s);
		}

		String whole()
		{
			StringBuilder sb = new StringBuilder();
			for (String s : pieces)
			{
				sb.append(s);
			}
			return sb.toString();
		}
	}

	static String stub(String name, int keep)
	{
		if (name.length() <= keep)
		{
			return name;
		}
		int end = keep;
		while (end > 0 && name.charAt(end - 1) == ' ')
		{
			end--;
		}
		return name.substring(0, end) + ELLIPSIS;
	}

	static String fitLine(Line line, List<Integer> order, int floor,
		FontMetrics fm, int avail)
	{
		Line work = new Line();
		work.pieces.addAll(line.pieces);
		if (fm.stringWidth(work.whole()) <= avail)
		{
			return work.whole();
		}
		for (int idx : order)
		{
			String name = line.pieces.get(idx);
			for (int keep = name.length() - 1; keep >= floor; keep--)
			{
				work.pieces.set(idx, stub(name, keep));
				String s = work.whole();
				if (fm.stringWidth(s) <= avail)
				{
					return s;
				}
			}
			String shortest = stub(name, floor);
			work.pieces.set(idx,
				fm.stringWidth(shortest) < fm.stringWidth(name) ? shortest : name);
		}
		return null;
	}

	static String value(Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue());
	}

	static JPanel ghostRow(String left, String right)
	{
		return ghostRow(left, right, null);
	}

	static JPanel ghostRow(String left, String right, Color rightColor)
	{
		JPanel r = row(left, right, rightColor);
		part(r, BorderLayout.CENTER)
			.setForeground(dim().darker());
		return r;
	}

	static JPanel nested(JPanel r)
	{
		r.setBorder(pad(1, ROW_INSET + 12, 1, ROW_INSET));
		return r;
	}

	static String figure(HistoryProgress.Row r)
	{
		String base = r.gp() ? gps(r.value()) : fmt(r.value());
		return r.gpNote() > 0 ? base + " · " + gp(r.gpNote()) + " " + r.gpNoteWord() : base;
	}

	static String tip(String title, String... lines)
	{
		return tip(title, Arrays.asList(lines));
	}

	static String tip(String title, List<String> lines)
	{
		StringBuilder sb = new StringBuilder(TIP_OPEN).append(dimLine(title));
		for (int i = 0; i + 1 < lines.size(); i += 2)
		{
			sb.append("<div>").append(lines.get(i)).append(": <span style='color:#c8a25a'>")
				.append(clip(lines.get(i + 1), 78)).append("</span></div>");
		}
		return sb.append(TIP_CLOSE).toString();
	}

	static final String TIP_OPEN = "<html><body style='padding:2px'>";

	static final String TIP_CLOSE = "</body></html>";

	static String dimLine(String s)
	{
		return "<div style='color:#8f8f8f'>" + s + "</div>";
	}

	static String clip(String s, int most)
	{
		return s.length() > most ? s.substring(0, most) + "..." : s;
	}

	static int boardRowRoom()
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * ROW_INSET;
	}

	static List<String> wrapClauses(String text, int room)
	{
		return wrap(text, " · ", rowMetrics(), room);
	}

	static List<String> wrap(String text, String sep, FontMetrics fm, int room)
	{
		List<String> out = new ArrayList<>();
		String line = null;
		for (String part : text.split(Pattern.quote(sep)))
		{
			String tried = line == null ? part : line + sep + part;
			if (line != null && fm.stringWidth(tried) > room)
			{
				out.add(line);
				line = part;
			}
			else
			{
				line = tried;
			}
		}
		if (line != null && !line.isEmpty())
		{
			out.add(line);
		}
		return out;
	}

	static String day(long ms)
	{
		return TASK_DAY.format(Instant.ofEpochMilli(ms));
	}

	static Color wash(Color c, float weight)
	{
		Color base = DARKER;
		return new Color(
			Math.round(base.getRed() + (c.getRed() - base.getRed()) * weight),
			Math.round(base.getGreen() + (c.getGreen() - base.getGreen()) * weight),
			Math.round(base.getBlue() + (c.getBlue() - base.getBlue()) * weight));
	}

	static String wrappedTip(String text)
	{
		if (text == null || text.length() <= 60 || text.startsWith("<html>"))
		{
			return text;
		}
		String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:220px'>" + safe + "</body></html>";
	}

	static JPanel column()
	{
		JPanel p = new JPanel(new GridBagLayout())
		{
			private final GridBagConstraints gbc = new GridBagConstraints();

			{
				gbc.gridx = 0;
				gbc.gridwidth = GridBagConstraints.REMAINDER;
				gbc.weightx = 1;
				gbc.fill = GridBagConstraints.HORIZONTAL;
			}

			@Override
			protected void addImpl(Component comp, Object constraints, int index)
			{
				super.addImpl(comp, constraints == null ? gbc : constraints, index);
			}
		};
		p.setBackground(DARK);
		return p;
	}

	static final class ScrollColumn extends JPanel implements Scrollable
	{
		ScrollColumn()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle r, int o, int d)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle r, int o, int d)
		{
			return 80;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	static JPanel card(String caption, String right)
	{
		JPanel c = cardPlain();
		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(DARKER);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), dim());
		JLabel note = styled(new JLabel(right), small(), dim());
		head.add(cap, BorderLayout.WEST);
		head.add(note, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		spaced(c, head, 3);
		return c;
	}

	static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), dim());
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		spaced(c, cap, 3);
		return c;
	}

	static JLabel styled(JLabel l, Font f, Color c)
	{
		l.setFont(f);
		l.setForeground(c);
		return l;
	}

	static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	static Color dim()
	{
		return ColorScheme.LIGHT_GRAY_COLOR.darker();
	}

	static JPanel cardPlain()
	{
		JPanel c = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBackground(DARKER);
		c.setBorder(pad(6, CARD_INSET, 6, CARD_INSET));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	static JPanel row(String left, String right, Color color, boolean colorName)
	{
		JPanel r = row(left, right, color);
		if (colorName && color != null)
		{
			part(r, BorderLayout.CENTER).setForeground(color);
		}
		return r;
	}

	static JPanel row(String left, String right)
	{
		return row(left, right, null);
	}

	static JPanel row(String left, String right, Color rightColor)
	{
		JPanel r = new JPanel(new BorderLayout(ROW_GAP, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(pad(1, ROW_INSET, 1, ROW_INSET));
		JLabel l = new JLabel(left);
		l.setFont(FontManager.getRunescapeFont());
		r.add(l, BorderLayout.CENTER);
		if (right != null && !right.isEmpty())
		{
			JLabel v = styled(new JLabel(right), FontManager.getRunescapeFont(),
				rightColor != null ? rightColor : dim());
			r.add(v, BorderLayout.EAST);
		}
		return r;
	}

	static JPanel worthRow(long v)
	{
		return row("Worth", gps(v));
	}

	static final int NOTE_WIDTH = 190;

	static JPanel note(String text)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String l : wrap(text, " ", p.getFontMetrics(small()), NOTE_WIDTH))
		{
			JLabel lab = styled(new JLabel(l), small(), dim());
			lab.setAlignmentX(Component.LEFT_ALIGNMENT);
			p.add(lab);
		}
		return p;
	}

	static Border pad(int t, int l, int b, int r)
	{
		return BorderFactory.createEmptyBorder(t, l, b, r);
	}

	static JPanel grid3()
	{
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	static JPanel levelTile(String title)
	{
		JPanel cell = tile(4, 6);
		cell.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		JLabel name = styled(new JLabel(title), small(), dim());
		cell.add(name, BorderLayout.CENTER);
		return cell;
	}

	static JPanel tile(int v, int h)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(DARKER);
		cell.setBorder(pad(v, h, v, h));
		return cell;
	}

	static JLabel part(JComponent row, String where)
	{
		return (JLabel) ((BorderLayout) row.getLayout()).getLayoutComponent(where);
	}

	static void spaced(JComponent p, Component c)
	{
		spaced(p, c, 6);
	}

	static void spaced(JComponent p, Component c, int gap)
	{
		p.add(c);
		p.add(vgap(gap));
	}

	static JPanel noted(JPanel p, String text)
	{
		p.add(note(text));
		return p;
	}

	static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		p.setMinimumSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	static final int HOVER_LIFT = 15;

	static Color behind(Component c)
	{
		for (Component p = c.getParent(); p != null; p = p.getParent())
		{
			if (p.isOpaque() && p.getBackground() != null)
			{
				return p.getBackground();
			}
		}
		return DARKER;
	}

	static Color hoverOf(Color ground)
	{
		if (DARKER.equals(ground))
		{
			return ColorScheme.DARKER_GRAY_HOVER_COLOR;
		}
		if (DARK.equals(ground))
		{
			return ColorScheme.DARK_GRAY_HOVER_COLOR;
		}
		return new Color(
			Math.min(255, ground.getRed() + HOVER_LIFT),
			Math.min(255, ground.getGreen() + HOVER_LIFT),
			Math.min(255, ground.getBlue() + HOVER_LIFT));
	}

	static boolean stillUnder(MouseEvent e)
	{
		boolean over = false;
		boolean pointerKnown = false;
		try
		{
			over = e.getComponent().getMousePosition() != null;
			pointerKnown = MouseInfo.getPointerInfo() != null;
		}
		catch (RuntimeException ignored)
		{
		}
		return stillUnder(over, pointerKnown, e.getComponent().contains(e.getPoint()));
	}

	static boolean stillUnder(boolean overComponent, boolean pointerKnown,
		boolean eventSaysInside)
	{
		if (overComponent)
		{
			return true;
		}
		if (pointerKnown)
		{
			return false;
		}
		return eventSaysInside;
	}

	static Runnable litNow;

	static void unlight()
	{
		Runnable was = litNow;
		litNow = null;
		if (was != null)
		{
			was.run();
		}
	}

	static MouseAdapter clicker(Runnable r)
	{
		return new MouseAdapter()
		{
			private boolean lit;
			private boolean wasOpaque;
			private Color wasBackground;
			private JComponent target;

			@Override
			public void mousePressed(MouseEvent e)
			{
				r.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (lit || !(e.getComponent() instanceof JComponent))
				{
					return;
				}
				unlight();
				JComponent c = (JComponent) e.getComponent();
				target = c;
				wasOpaque = c.isOpaque();
				wasBackground = c.getBackground();
				Color ground = wasOpaque && wasBackground != null
					? wasBackground : behind(c);
				c.setBackground(hoverOf(ground));
				c.setOpaque(true);
				c.repaint();
				lit = true;
				litNow = this::putBack;
			}

			private void putBack()
			{
				if (!lit || target == null)
				{
					return;
				}
				target.setOpaque(wasOpaque);
				target.setBackground(wasBackground);
				target.repaint();
				lit = false;
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!lit || !(e.getComponent() instanceof JComponent))
				{
					return;
				}
				if (stillUnder(e))
				{
					return;
				}
				litNow = null;
				putBack();
			}
		};
	}

	static void link(Component c, Runnable go)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(clicker(go));
	}

	static long sumOf(List<Entry<String, Long>> rows)
	{
		long sum = 0;
		for (Entry<String, Long> e : rows)
		{
			sum += e.getValue();
		}
		return sum;
	}

	static JPanel moreRow(long remaining, Runnable reveal)
	{
		return moreRow("Show " + fmt(remaining) + " more", reveal);
	}

	static JPanel moreRow(String label, Runnable reveal)
	{
		JPanel more = ghostRow(label, "");
		link(more, reveal);
		return more;
	}

	static void reportCopy(JLabel take, boolean ok)
	{
		take.setText(ok ? "copied" : "cannot copy");
		take.setForeground(ok ? ACCENT_LIFETIME : ColorScheme.PROGRESS_ERROR_COLOR);
	}

	static JPanel copyHeader(String title,
		LinkedHashMap<String, BooleanSupplier> choices)
	{
		return copyHeaderLater(title, take ->
		{
			JPopupMenu menu = new JPopupMenu();
			for (Entry<String, BooleanSupplier> e
				: choices.entrySet())
			{
				menuItem(menu, e.getKey(), false, () -> reportCopy(take, e.getValue().getAsBoolean()));
			}
			menu.show(take, 0, take.getHeight());
		});
	}

	static JPanel copyHeaderLater(String title, Consumer<JLabel> copy)
	{
		JPanel r = row(title, "copy");
		styled(part(r, BorderLayout.CENTER), small(), ACCENT_LIFETIME);
		JLabel take = copyLabel(r, "Copy this board as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> copy.accept(take)));
		}
		return r;
	}

	static JPanel copyHeader(String title, BooleanSupplier copy)
	{
		return copyHeaderLater(title, take -> reportCopy(take, copy.getAsBoolean()));
	}

	static JPanel linkRow(String label, String right, Runnable go)
	{
		JPanel r = row(label, right);
		styled(part(r, BorderLayout.CENTER), small(), ACCENT_LIFETIME);
		link(r, go);
		return r;
	}

	static void menuItem(JPopupMenu menu, String text, boolean on, Runnable go)
	{
		JMenuItem item = new JMenuItem(text);
		item.setFont(small());
		if (on)
		{
			item.setForeground(ACCENT_LIFETIME);
		}
		item.addActionListener(e -> go.run());
		menu.add(item);
	}

	static JPanel progress(float frac)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		outer.setPreferredSize(new Dimension(10, 4));
		outer.setMinimumSize(new Dimension(10, 4));
		outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel inner = new JPanel();
		inner.setBackground(ACCENT_LIFETIME);
		inner.setPreferredSize(new Dimension(
			Math.max(1, Math.round(frac * (PluginPanel.PANEL_WIDTH - 40))), 4));
		JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		holder.setOpaque(false);
		holder.add(inner);
		outer.add(holder, BorderLayout.WEST);
		return outer;
	}

	static JLabel group(String name)
	{
		JLabel g = styled(new JLabel(name.toUpperCase(Locale.ROOT)), small(), ACCENT_LIFETIME);
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(pad(8, 2, 3, 0));
		return g;
	}

	static JPanel toggle(String reading, Runnable flip)
	{
		JPanel cell = new JPanel(new BorderLayout());
		cell.setBackground(DARKER);
		cell.setBorder(pad(2, 4, 2, 4));
		JLabel l = styled(new JLabel(reading, JLabel.CENTER), small(), ACCENT_LIFETIME);
		cell.add(l, BorderLayout.CENTER);
		link(cell, flip);
		return cell;
	}
}
