/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import static chronicle.Reference.*;

final class Ui
{
	static final Color DARK = ColorScheme.DARK_GRAY_COLOR;
	static final Color DARKER = ColorScheme.DARKER_GRAY_COLOR;
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;
	static final Color GREEN = new Color(85, 163, 90);
	static final Color RED = new Color(196, 84, 74);
	static final Color LIT = new Color(198, 198, 198);
	static final Color DIM = ColorScheme.LIGHT_GRAY_COLOR.darker();

	private Ui()
	{
	}

	static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault());

	static final DateTimeFormatter CLOCK =
		DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());

	static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());

	static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());

	static final DateTimeFormatter ROLL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	static final int ROW_CAP = 30;

	static final int PANEL_INSET = 8;

	private static final int CARD_INSET = 8;

	private static final int ROW_INSET = 2;

	static final int ROW_GAP = 8;

	static String fmt(long n)
	{
		return String.format(Locale.UK, "%,d", n);
	}

	static String gp(long n)
	{
		if (Math.abs(n) >= 1_000_000_000L)
		{
			return String.format(Locale.UK, "%.2fB", n / 1_000_000_000.0);
		}
		if (Math.abs(n) >= 1_000_000L)
		{
			return String.format(Locale.UK, "%.1fM", n / 1_000_000.0);
		}
		if (Math.abs(n) >= 10_000L)
		{
			return String.format(Locale.UK, "%dk", n / 1_000);
		}
		return fmt(n);
	}

	static String gps(long n)
	{
		return gp(n) + " gp";
	}

	static String low(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}

	static String named(String name, long qty)
	{
		return name + (qty > 1 ? " \u00d7" + fmt(qty) : "");
	}

	static String share(long part, long whole)
	{
		return Math.round(part * 1000.0 / whole) / 10.0 + "%";
	}

	static String climb(long from, long to)
	{
		return fmt(from) + " to " + fmt(to);
	}

	static long sumOf(List<Entry<String, Long>> rows)
	{
		return rows.stream().mapToLong(Entry::getValue).sum();
	}

	static String qtyGp(long qty, long value)
	{
		return fmt(qty) + " · " + gps(value);
	}

	static String tail(long v)
	{
		return v > 0 ? " · " + gps(v) : "";
	}

	static String xpShort(long n)
	{
		long a = Math.abs(n);
		if (a >= 100_000L)
		{
			return gp(n);
		}
		if (a >= 1_000L)
		{
			String k = String.format(Locale.UK, "%.1f", n / 1_000.0);
			return (k.endsWith(".0") ? k.substring(0, k.length() - 2) : k) + "k";
		}
		return fmt(n);
	}

	static String pb(double seconds)
	{
		return clock(Math.round(seconds));
	}

	static String count(long n, String one)
	{
		return fmt(n) + " " + plural(n, one);
	}

	static String plural(long n, String one)
	{
		return n == 1 ? one : one + "s";
	}

	static String hoursMinutes(long minutes)
	{
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	static String rateText(double perHour)
	{
		return perHour >= 10 ? fmt(Math.round(perHour)) : String.format(Locale.UK, "%.1f", perHour);
	}

	static String clock(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.UK, "%d:%02d", m, s);
	}

	static String dated(long ts)
	{
		return FULL_DAY.format(Instant.ofEpochMilli(ts));
	}

	static String day(long ms)
	{
		return TASK_DAY.format(Instant.ofEpochMilli(ms));
	}

	static String prettyTier(String tier)
	{
		return tier == null || tier.isEmpty() ? ""
			: Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
	}

	static String bytes(long n)
	{
		if (n >= 1024 * 1024)
		{
			return String.format("%.1f MB", n / (1024.0 * 1024.0));
		}
		return n >= 1024 ? fmt(n / 1024) + " KB" : fmt(n) + " B";
	}

	static String ordinal(long n)
	{
		long last = n % 10;
		long tens = n % 100;
		String suffix = tens >= 11 && tens <= 13 ? "th"
			: last == 1 ? "st" : last == 2 ? "nd" : last == 3 ? "rd" : "th";
		return n + suffix;
	}

	static String firstSentence(String task)
	{
		int note = task.indexOf(" Note:");
		String s = note > 0 ? task.substring(0, note) : task;
		for (int stop = s.indexOf(". "); stop > 0; stop = s.indexOf(". ", stop + 1))
		{
			if (Character.isLowerCase(s.charAt(stop - 1)) && stop + 2 < s.length()
				&& Character.isUpperCase(s.charAt(stop + 2)))
			{
				return s.substring(0, stop + 1);
			}
		}
		return s;
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

	static String prettyPage(String key)
	{
		StringBuilder sb = new StringBuilder(key.length());
		boolean head = true;
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			sb.append(head ? Character.toUpperCase(c) : c);
			head = c == ' ' || c == '(';
		}
		return sb.toString();
	}

	static long safeParse(String s)
	{
		try
		{
			return Long.parseLong(s);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	static <T> List<T> firstN(List<T> l, int cap)
	{
		return l.subList(0, Math.min(cap, l.size()));
	}

	static <T> T most(Iterable<T> all, ToLongFunction<T> size)
	{
		T top = null;
		long most = 0;
		for (T t : all)
		{
			long n = size.applyAsLong(t);
			if (n > most)
			{
				most = n;
				top = t;
			}
		}
		return top;
	}

	static String topOf(Map<String, Long> by)
	{
		Entry<String, Long> top = most(by.entrySet(), Entry::getValue);
		return top == null ? null : top.getKey();
	}

	static <T> T find(List<T> rows, Function<T, String> named, String name,
		boolean exact)
	{
		T loose = null;
		for (T r : rows)
		{
			if (named.apply(r).equals(name))
			{
				return r;
			}
			loose = loose == null && !exact && named.apply(r).equalsIgnoreCase(name) ? r : loose;
		}
		return loose;
	}

	static String keyOf(Set<String> keys, String name)
	{
		if (keys.contains(name))
		{
			return name;
		}
		for (String k : keys)
		{
			if (k.equalsIgnoreCase(name))
			{
				return k;
			}
		}
		return null;
	}

	static JsonElement getIgnoreCase(JsonObject o, String key)
	{
		String k = keyOf(o.keySet(), key);
		return k == null ? null : o.get(k);
	}

	static final String TIP_OPEN = "<html><body style='padding:2px'>";

	static final String TIP_CLOSE = "</body></html>";

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

	static String dimLine(String s)
	{
		return "<div style='color:#8f8f8f'>" + s + "</div>";
	}

	static String clip(String s, int most)
	{
		return s.length() > most ? s.substring(0, most) + "..." : s;
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

	static JPanel card(String caption, String right)
	{
		JPanel c = cardPlain();
		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(DARKER);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), DIM);
		JLabel note = styled(new JLabel(right), small(), DIM);
		head.add(cap, BorderLayout.WEST);
		head.add(note, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		spaced(c, head, 3);
		return c;
	}

	static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), DIM);
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		spaced(c, cap, 3);
		return c;
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
				rightColor != null ? rightColor : DIM);
			r.add(v, BorderLayout.EAST);
		}
		return r;
	}

	static JPanel worthRow(long v)
	{
		return row("Worth", gps(v));
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
		inner.setBackground(ACCENT);
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
		JLabel g = styled(new JLabel(name.toUpperCase(Locale.ROOT)), small(), ACCENT);
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(pad(8, 2, 3, 0));
		return g;
	}

	private static final int NOTE_WIDTH = 190;

	static JPanel note(String text)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String l : wrap(text, " ", p.getFontMetrics(small()), NOTE_WIDTH))
		{
			JLabel lab = styled(new JLabel(l), small(), DIM);
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
		JLabel name = styled(new JLabel(title), small(), DIM);
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

	private static final int HOVER_LIFT = 15;

	private static Color behind(Component c)
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

	private static Color hoverOf(Color ground)
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

	static JPanel toggle(String reading, Runnable flip)
	{
		JPanel cell = new JPanel(new BorderLayout());
		cell.setBackground(DARKER);
		cell.setBorder(pad(2, 4, 2, 4));
		JLabel l = styled(new JLabel(reading, JLabel.CENTER), small(), ACCENT);
		cell.add(l, BorderLayout.CENTER);
		link(cell, flip);
		return cell;
	}

	private static boolean stillUnder(MouseEvent e)
	{
		try
		{
			return e.getComponent().getMousePosition() != null
				|| MouseInfo.getPointerInfo() == null && e.getComponent().contains(e.getPoint());
		}
		catch (RuntimeException ignored)
		{
			return e.getComponent().contains(e.getPoint());
		}
	}

	private static Runnable litNow;

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

	static <T extends Component> T link(T c, Runnable go)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(clicker(go));
		return c;
	}

	static JPanel ghostRow(String left, String right)
	{
		return ghostRow(left, right, null);
	}

	static JPanel ghostRow(String left, String right, Color rightColor)
	{
		JPanel r = row(left, right, rightColor);
		part(r, BorderLayout.CENTER)
			.setForeground(DIM.darker());
		return r;
	}

	static JPanel nested(JPanel r)
	{
		r.setBorder(pad(1, ROW_INSET + 12, 1, ROW_INSET));
		return r;
	}

	static JPanel tallyCard(String title, String lead, String figure, Color ink, long worth)
	{
		JPanel head = card(title);
		head.add(row(lead, figure, ink));
		head.add(worthRow(worth));
		return head;
	}

	static JLabel pill(String name, boolean on, int side, String tip, Runnable pick)
	{
		JLabel pill = new JLabel(name, JLabel.CENTER);
		pill.setOpaque(true);
		pill.setBorder(pad(2, side, 2, side));
		pill.setFont(small());
		pill.setBackground(DARKER);
		pill.setForeground(on ? ACCENT : DIM);
		pill.setToolTipText(tip);
		link(pill, pick);
		return pill;
	}

	static Color wash(Color c)
	{
		Color g = DARK;
		double a = 0.22;
		return new Color(
			(int) Math.round(g.getRed() + (c.getRed() - g.getRed()) * a),
			(int) Math.round(g.getGreen() + (c.getGreen() - g.getGreen()) * a),
			(int) Math.round(g.getBlue() + (c.getBlue() - g.getBlue()) * a));
	}

	static Color wash(Color c, float weight)
	{
		Color base = DARKER;
		return new Color(
			Math.round(base.getRed() + (c.getRed() - base.getRed()) * weight),
			Math.round(base.getGreen() + (c.getGreen() - base.getGreen()) * weight),
			Math.round(base.getBlue() + (c.getBlue() - base.getBlue()) * weight));
	}

	static JPanel stepStrip()
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(DARKER);
		r.setBorder(pad(3, 8, 3, 8));
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return r;
	}

	static JPanel fixedPeriod(JPanel r, String scope)
	{
		JLabel fixed = styled(new JLabel(scope, JLabel.CENTER), FontManager.getRunescapeFont(),
			DIM);
		r.add(fixed, BorderLayout.CENTER);
		return r;
	}

	static void listCard(JPanel p, JPanel head, String under, String right, Runnable go)
	{
		JPanel card = cardPlain();
		card.add(head);
		card.add(row(under, right));
		link(card, go);
		spaced(p, card, 4);
	}

	static String perOne(long value, long n, boolean killed)
	{
		return gp(value / Math.max(1, n)) + (killed ? " gp/drop" : " gp each");
	}

	static JPanel facts(JPanel c, Map<String, Long> f, Color lead, String... rows)
	{
		for (int i = 0; i < rows.length; i += 2)
		{
			c.add(row(rows[i], fmt(f.getOrDefault(rows[i + 1], 0L)), i == 0 ? lead : null));
		}
		return c;
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

	static List<String> wrapClauses(String text, int room)
	{
		return wrap(text, " · ", rowMetrics(), room);
	}

	static final JLabel MEASURE = new JLabel();

	static FontMetrics rowMetrics()
	{
		return MEASURE.getFontMetrics(FontManager.getRunescapeFont());
	}

	static int boardRowRoom()
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - OverlayScrollBarUI.WIDTH - 2 * ROW_INSET;
	}

	static int chaseRoom(String share, FontMetrics fm)
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - OverlayScrollBarUI.WIDTH - 2 * CARD_INSET
			- 2 * ROW_INSET - ROW_GAP - fm.stringWidth(share);
	}

	private static final String ELLIPSIS = "…";

	static final int NAME_FLOOR = 3;

	static long noon(LocalDate d)
	{
		return d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	static LocalDate dayOf(long ms)
	{
		return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	static long startMs(LocalDate d)
	{
		return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	static JPanel moreRow(String label, Runnable reveal)
	{
		JPanel more = ghostRow(label, "");
		link(more, reveal);
		return more;
	}

	static JPanel backRow(String label, String right, Runnable go)
	{
		JPanel r = row(label, right);
		styled(part(r, BorderLayout.CENTER), small(), ACCENT);
		link(r, go);
		return r;
	}

	static void menuItem(JPopupMenu menu, String text, boolean on, Runnable go)
	{
		JMenuItem item = new JMenuItem(text);
		item.setFont(small());
		if (on)
		{
			item.setForeground(ACCENT);
		}
		item.addActionListener(e -> go.run());
		menu.add(item);
	}

	static void arrows(JPanel r, Runnable back, boolean ahead, Runnable forward, JLabel title)
	{
		JLabel b = new JLabel("<");
		JLabel fwd = new JLabel(">");
		for (JLabel arrow : new JLabel[]{b, fwd})
		{
			arrow.setFont(FontManager.getRunescapeBoldFont());
			arrow.setBorder(pad(0, 6, 0, 6));
		}
		b.setForeground(ACCENT);
		link(b, back);
		fwd.setForeground(ahead ? ACCENT : DIM);
		if (ahead)
		{
			link(fwd, forward);
		}
		r.add(b, BorderLayout.WEST);
		if (title != null)
		{
			r.add(title, BorderLayout.CENTER);
		}
		r.add(fwd, BorderLayout.EAST);
	}
}
