/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Recap as one picture: the whole period on a single sheet, no wider than
 * 1920 and no taller than 1080.
 *
 * <p>The panel's Recap is a dozen figures, each a door. A picture has no doors,
 * so this one carries what they open onto: every skill from where the period
 * found it to where it left it, every boss the same way, the monsters, the loot,
 * the counters and what the period achieved. The figures are gathered by the
 * panel, the way its own boards read them; this class only lays them out.
 *
 * <p>Nothing in a picture can be hovered or opened, so nothing on it may lean
 * on a hover to be understood, and a list that does not fit says how much of it
 * was left off rather than stopping short.
 */
final class RecapPicture
{
	static final int WIDTH = 1920;
	static final int MAX_HEIGHT = 1080;

	private static final int COLUMNS = 6;
	private static final int GAP = 16;
	private static final int COL = 290;
	private static final int LEFT = (WIDTH - COLUMNS * COL - (COLUMNS - 1) * GAP) / 2;
	private static final int TOP = 28;
	private static final int PAD = 10;
	private static final int ROW = 20;
	private static final int ICON_ROW = 24;
	private static final int HEAD = 22;
	private static final int TILE_H = 84;
	private static final int NOTE_ROW = 15;
	// the least a page is drawn at, so a quiet sitting is still a sheet and not a strip
	private static final int MIN_BODY = 260;

	private static final Color GROUND = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color TEXT = new Color(198, 198, 198);
	private static final Color VALUE = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color DIM = ColorScheme.LIGHT_GRAY_COLOR.darker();
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	// The game's font has no arrow; the sheet says "124 to 125" and so does this.
	private static final String ARROW = " to ";

	/** Everything the picture says, gathered by the panel. */
	static final class Facts
	{
		String title = "";
		boolean whole;
		boolean session;
		final List<Tile> tiles = new ArrayList<>();
		final List<SkillLine> skills = new ArrayList<>();
		// the total and combat rows under the skills: {start, end}, null where unknown
		Long[] totalLevel;
		Long[] totalXp;
		Long[] combat;
		String skillsNote;
		final List<BossLine> bosses = new ArrayList<>();
		String bossesNote;
		final List<Named> monsters = new ArrayList<>();
		String monstersNote;
		final List<Named> loot = new ArrayList<>();
		final List<Named> sources = new ArrayList<>();
		final List<Named> items = new ArrayList<>();
		String lootNote;
		final List<Named> slayer = new ArrayList<>();
		final List<Named> clues = new ArrayList<>();
		final Map<String, List<Named>> trackers = new LinkedHashMap<>();
		String trackersNote;
		final Map<String, List<String>> feats = new LinkedHashMap<>();
		final List<String> notes = new ArrayList<>();
		// rows the ceiling took off the ends of lists, each list saying so; set by paint
		int dropped;
	}

	/** One figure across the top: what it is, the figure, and a line under it. */
	@RequiredArgsConstructor
	static final class Tile
	{
		final String label;
		final String figure;
		final String under;
	}

	/** A skill's standing at either end of the period; start null where the record cannot say. */
	@RequiredArgsConstructor
	static final class SkillLine
	{
		final net.runelite.api.Skill skill;
		final String name;
		final Integer levelStart;
		final int levelEnd;
		final Long xpStart;
		final long xpEnd;

		long gained()
		{
			return xpStart == null ? 0 : Math.max(0, xpEnd - xpStart);
		}
	}

	/** A boss's kill count at either end, or only what moved where that is all that is known. */
	@RequiredArgsConstructor
	static final class BossLine
	{
		final String name;
		final int sprite;
		final Long start;
		final Long end;
		final long gained;
	}

	/** A plain line: a name, its figure, and a gp figure beside it where there is one. */
	@RequiredArgsConstructor
	static final class Named
	{
		final String name;
		final String figure;
		final String gp;
	}

	private RecapPicture()
	{
	}

	// ------------------------------------------------------------------
	// Pieces: a card is a caption over pieces, and pieces are what a column
	// breaks between
	// ------------------------------------------------------------------

	private interface Piece
	{
		int height();

		void draw(Graphics2D g, int x, int y, int w);

		// a subheading is never the last thing before a column breaks
		default boolean keepsWithNext()
		{
			return false;
		}
	}

	@RequiredArgsConstructor
	private static final class Block
	{
		final String title;
		final List<Piece> pieces = new ArrayList<>();
		// whether this block may lose rows off its end when the page is full
		final boolean trims;
		int dropped;
	}

	private static Font regular()
	{
		return FontManager.getRunescapeFont();
	}

	private static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	private static Font big()
	{
		return FontManager.getRunescapeBoldFont().deriveFont(32f);
	}

	// A measure off a scratch image, since the pieces are sized before there is
	// anything to draw on.
	private static final Graphics2D MEASURE = new BufferedImage(1, 1,
		BufferedImage.TYPE_INT_RGB).createGraphics();

	private static FontMetrics fm(Font f)
	{
		return MEASURE.getFontMetrics(f);
	}

	/** Text cut to a width with the font's own ellipsis, or whole where it fits. */
	static String cut(String s, Font f, int w)
	{
		FontMetrics m = fm(f);
		if (m.stringWidth(s) <= w)
		{
			return s;
		}
		for (int n = s.length() - 1; n > 0; n--)
		{
			String t = s.substring(0, n).trim() + "…";
			if (m.stringWidth(t) <= w)
			{
				return t;
			}
		}
		return "";
	}

	private static void text(Graphics2D g, String s, Font f, Color c, int x, int baseline)
	{
		g.setFont(f);
		g.setColor(c);
		g.drawString(s, x, baseline);
	}

	private static int rightText(Graphics2D g, String s, Font f, Color c, int right, int baseline)
	{
		g.setFont(f);
		g.setColor(c);
		int w = g.getFontMetrics().stringWidth(s);
		g.drawString(s, right - w, baseline);
		return right - w;
	}

	/** Each a name on the left, a figure on the right, a gp figure beside it in the accent. */
	private static List<Piece> lines(List<Named> all)
	{
		List<Piece> out = new ArrayList<>();
		for (Named n : all)
		{
			out.add(new Piece()
			{
				public int height()
				{
					return ROW;
				}

				public void draw(Graphics2D g, int x, int y, int w)
				{
					int base = y + 15;
					int right = x + w;
					if (n.gp != null && !n.gp.isEmpty())
					{
						right = rightText(g, n.gp, regular(), ACCENT, right, base) - 8;
					}
					if (n.figure != null && !n.figure.isEmpty())
					{
						right = rightText(g, n.figure, regular(), VALUE, right, base) - 8;
					}
					text(g, cut(n.name, regular(), right - x), regular(), TEXT, x, base);
				}
			});
		}
		return out;
	}

	/** Grey small text, wrapped to the card: an aside the reader should still see. */
	private static List<Piece> note(String s, int w)
	{
		return rows(wrap(s, small(), w, " "), small(), DIM, NOTE_ROW, 11);
	}

	/** Names run on as one paragraph, broken only between names. */
	private static List<Piece> names(List<String> all, int w)
	{
		return rows(wrap(String.join(" · ", all), regular(), w, " · "), regular(), TEXT, ROW - 2, 14);
	}

	private static List<Piece> rows(List<String> lines, Font f, Color c, int h, int base)
	{
		List<Piece> out = new ArrayList<>();
		for (String l : lines)
		{
			out.add(new Piece()
			{
				public int height()
				{
					return h;
				}

				public void draw(Graphics2D g, int x, int y, int w)
				{
					text(g, l, f, c, x, y + base);
				}
			});
		}
		return out;
	}

	/** Greedy wrap on a separator, a piece that is still too wide being cut rather than lost. */
	static List<String> wrap(String s, Font f, int w, String sep)
	{
		FontMetrics m = fm(f);
		List<String> out = new ArrayList<>();
		String cur = null;
		for (String part : s.split(java.util.regex.Pattern.quote(sep)))
		{
			String tried = cur == null ? part : cur + sep + part;
			if (cur != null && m.stringWidth(tried) > w)
			{
				out.add(cut(cur, f, w));
				cur = part;
			}
			else
			{
				cur = tried;
			}
		}
		if (cur != null && !cur.isEmpty())
		{
			out.add(cut(cur, f, w));
		}
		return out;
	}

	private static Piece subhead(String s)
	{
		return new Piece()
		{
			public int height()
			{
				return 20;
			}

			public boolean keepsWithNext()
			{
				return true;
			}

			public void draw(Graphics2D g, int x, int y, int w)
			{
				text(g, s.toUpperCase(java.util.Locale.ROOT), small(), DIM, x, y + 15);
			}
		};
	}

	private static Piece bossLine(BossLine b, boolean whole, Function<Integer, BufferedImage> sprites)
	{
		return new Piece()
		{
			public int height()
			{
				return ICON_ROW;
			}

			public void draw(Graphics2D g, int x, int y, int w)
			{
				BufferedImage icon = b.sprite > 0 && sprites != null ? sprites.apply(b.sprite) : null;
				if (icon != null)
				{
					drawIcon(g, icon, x, y + 1, 22, 22);
				}
				int base = y + 17;
				int right = x + w;
				if (whole)
				{
					right = rightText(g, fmtN(b.end == null ? b.gained : b.end), regular(), VALUE,
						right, base) - 8;
				}
				else
				{
					right = rightText(g, "+" + fmtN(b.gained), regular(), ACCENT, right, base) - 8;
					if (b.start != null && b.end != null)
					{
						right = rightText(g, fmtN(b.start) + ARROW + fmtN(b.end), regular(), DIM,
							right, base) - 8;
					}
				}
				text(g, cut(b.name, regular(), right - x - 28), regular(), TEXT, x + 28, base);
			}
		};
	}

	private static void drawIcon(Graphics2D g, BufferedImage img, int x, int y, int w, int h)
	{
		double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
		scale = Math.min(scale, 1.0);
		int dw = (int) Math.round(img.getWidth() * scale);
		int dh = (int) Math.round(img.getHeight() * scale);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
	}

	static String fmtN(long n)
	{
		return String.format(java.util.Locale.UK, "%,d", n);
	}

	// ------------------------------------------------------------------
	// The sheet
	// ------------------------------------------------------------------

	/**
	 * Draw the facts. The icons are asked for by id and may answer null, which
	 * leaves a name standing on its own rather than holding the picture up.
	 */
	static BufferedImage paint(Facts f, Function<net.runelite.api.Skill, BufferedImage> skillIcons,
		Function<Integer, BufferedImage> sprites)
	{
		int contentW = COLUMNS * COL + (COLUMNS - 1) * GAP;
		int inner = COL - 2 * PAD;
		int wideInner = 2 * COL + GAP - 2 * PAD;

		// the head: the period, then the figures across
		int headH = TOP + 12 + 4 + 34 + 16 + (f.tiles.isEmpty() ? 0 : TILE_H + 18);
		int footNotes = f.notes.size();
		int footH = 14 + footNotes * NOTE_ROW + 20;
		int maxBody = MAX_HEIGHT - headH - footH;

		// the skills table sits in the first two columns, whole
		int tableH = skillsTableHeight(f);

		// the left two columns carry the period's achievements under the table;
		// the other four carry everything else, in this order
		List<Block> left = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : f.feats.entrySet())
		{
			Block b = new Block(e.getKey(), true);
			b.pieces.addAll(names(e.getValue(), inner));
			left.add(b);
		}
		List<Block> right = new ArrayList<>();
		if (!f.bosses.isEmpty() || f.bossesNote != null)
		{
			Block b = new Block("Bosses", true);
			for (BossLine l : f.bosses)
			{
				b.pieces.add(bossLine(l, f.whole, sprites));
			}
			if (f.bossesNote != null)
			{
				b.pieces.addAll(note(f.bossesNote, inner));
			}
			right.add(b);
		}
		if (!f.monsters.isEmpty() || f.monstersNote != null)
		{
			Block b = new Block("Monsters", true);
			b.pieces.addAll(lines(f.monsters));
			if (f.monstersNote != null)
			{
				b.pieces.addAll(note(f.monstersNote, inner));
			}
			right.add(b);
		}
		if (!f.loot.isEmpty() || f.lootNote != null)
		{
			Block b = new Block("Loot", true);
			b.pieces.addAll(lines(f.loot));
			if (!f.sources.isEmpty())
			{
				b.pieces.add(subhead("Where it came from"));
				b.pieces.addAll(lines(f.sources));
			}
			if (!f.items.isEmpty())
			{
				b.pieces.add(subhead("Dearest"));
				b.pieces.addAll(lines(f.items));
			}
			if (f.lootNote != null)
			{
				b.pieces.addAll(note(f.lootNote, inner));
			}
			right.add(b);
		}
		if (!f.slayer.isEmpty() || !f.clues.isEmpty())
		{
			Block b = new Block(f.slayer.isEmpty() ? "Clues" : "Slayer", true);
			b.pieces.addAll(lines(f.slayer));
			if (!f.clues.isEmpty())
			{
				if (!f.slayer.isEmpty())
				{
					b.pieces.add(subhead("Clues"));
				}
				b.pieces.addAll(lines(f.clues));
			}
			right.add(b);
		}
		for (Map.Entry<String, List<Named>> e : f.trackers.entrySet())
		{
			Block b = new Block(e.getKey(), true);
			b.pieces.addAll(lines(e.getValue()));
			right.add(b);
		}
		if (f.trackersNote != null)
		{
			Block b = new Block("Trackers", false);
			b.pieces.addAll(note(f.trackersNote, inner));
			right.add(b);
		}

		// One run of cards over all six columns, the first two starting under
		// the skills: what the period achieved, then everything else. Run to
		// the least height that holds all of it, so the columns come out even
		// rather than the first ones full and the last ones empty; never past
		// the ceiling, where the longest lists give up their tails and each
		// says how many it gave up.
		List<Block> run = new ArrayList<>(left);
		run.addAll(right);
		int[] tops = {tableH + GAP, tableH + GAP, 0, 0, 0, 0};
		int[] cols = {0, 1, 2, 3, 4, 5};
		int body = Math.max(MIN_BODY, tableH);
		while (body < maxBody && !place(null, run, cols, tops, even(body), false))
		{
			body += 10;
		}
		body = Math.min(body, maxBody);
		while (!place(null, run, cols, tops, even(body), false) && trimOne(run, inner))
		{
			// trimmed one row; try again
		}
		f.dropped = 0;
		for (Block b : run)
		{
			f.dropped += b.dropped;
		}
		// The four columns beside the table stop at the least height that
		// still holds everything, so a short period is a row of cards across
		// the page and not one tall column beside three empty ones.
		int beside = 0;
		for (Block b : run)
		{
			if (b.pieces.size() <= 12)
			{
				beside = Math.max(beside, blockHeight(b, 0, b.pieces.size()));
			}
		}
		int[] bottoms = even(body);
		while (beside < body)
		{
			int[] tried = {body, body, beside, beside, beside, beside};
			if (place(null, run, cols, tops, tried, false))
			{
				bottoms = tried;
				break;
			}
			beside += 10;
		}

		int height = headH + body + footH;
		BufferedImage img = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(GROUND);
		g.fillRect(0, 0, WIDTH, height);

		// the head
		int y = TOP;
		text(g, "RECAP", small(), ACCENT, LEFT, y + 11);
		y += 16;
		text(g, f.title, big(), Color.WHITE, LEFT, y + 28);
		y += 34 + 16;
		if (!f.tiles.isEmpty())
		{
			int n = Math.max(f.tiles.size(), 6);
			int tw = (contentW - (n - 1) * GAP) / n;
			int tx = LEFT;
			for (Tile t : f.tiles)
			{
				drawTile(g, t, tx, y, tw);
				tx += tw + GAP;
			}
			y += TILE_H + 18;
		}

		// the body
		int bodyTop = y;
		drawSkillsTable(g, f, skillIcons, LEFT, bodyTop, 2 * COL + GAP, tableH, wideInner);
		int[] colX = new int[COLUMNS];
		for (int c = 0; c < COLUMNS; c++)
		{
			colX[c] = LEFT + c * (COL + GAP);
		}
		int[] at = new int[COLUMNS];
		int[] foot = new int[COLUMNS];
		for (int c = 0; c < COLUMNS; c++)
		{
			at[c] = bodyTop + tops[c];
			foot[c] = bodyTop + bottoms[c];
		}
		place(g, run, colX, at, foot, true);

		// the foot
		y = bodyTop + body + 14;
		for (String n : f.notes)
		{
			text(g, n, small(), DIM, LEFT, y + 11);
			y += NOTE_ROW;
		}
		g.dispose();
		return img;
	}

	private static void drawTile(Graphics2D g, Tile t, int x, int y, int w)
	{
		g.setColor(CARD);
		g.fillRect(x, y, w, TILE_H);
		text(g, t.label.toUpperCase(java.util.Locale.ROOT), small(), DIM, x + PAD, y + PAD + 11);
		text(g, cut(t.figure, big(), w - 2 * PAD), big(), Color.WHITE, x + PAD, y + PAD + 16 + 30);
		if (t.under != null && !t.under.isEmpty())
		{
			text(g, cut(t.under, small(), w - 2 * PAD), small(), DIM, x + PAD, y + TILE_H - PAD);
		}
	}

	private static int skillsTableHeight(Facts f)
	{
		int rows = f.skills.size() + (f.totalLevel != null || f.totalXp != null ? 1 : 0)
			+ (f.combat != null ? 1 : 0);
		int h = 2 * PAD + HEAD + 18 + rows * ROW + 6;
		if (f.skillsNote != null)
		{
			h += 3 * NOTE_ROW;
		}
		return h;
	}

	private static void drawSkillsTable(Graphics2D g, Facts f,
		Function<net.runelite.api.Skill, BufferedImage> icons, int x, int y, int w, int h, int inner)
	{
		g.setColor(CARD);
		g.fillRect(x, y, w, h);
		int ix = x + PAD;
		int cy = y + PAD;
		text(g, "SKILLS", small(), ACCENT, ix, cy + 13);
		cy += HEAD;
		// the columns, right edges: level, then xp at the start, at the end, and gained
		int right = ix + inner;
		int gainedR = right;
		int endR = right - 100;
		int startR = right - 210;
		int levelR = right - 320;
		boolean ends = !f.whole;
		text(g, "LEVEL", small(), DIM, levelR - fm(small()).stringWidth("LEVEL"), cy + 11);
		if (ends)
		{
			rightText(g, "START XP", small(), DIM, startR, cy + 11);
			rightText(g, "END XP", small(), DIM, endR, cy + 11);
			rightText(g, "GAINED", small(), DIM, gainedR, cy + 11);
		}
		else
		{
			rightText(g, "EXPERIENCE", small(), DIM, gainedR, cy + 11);
		}
		cy += 18;
		for (SkillLine s : f.skills)
		{
			boolean moved = s.gained() > 0;
			Color c = ends && !moved ? DIM : TEXT;
			int base = cy + 15;
			BufferedImage icon = icons != null ? icons.apply(s.skill) : null;
			if (icon != null)
			{
				drawIcon(g, icon, ix, cy + 1, 18, 18);
			}
			text(g, s.name, regular(), c, ix + 26, base);
			boolean rose = ends && s.levelStart != null && s.levelStart < s.levelEnd;
			String level = rose ? s.levelStart + ARROW + s.levelEnd : String.valueOf(s.levelEnd);
			rightText(g, level, regular(), rose ? Color.WHITE : c, levelR, base);
			if (ends)
			{
				rightText(g, s.xpStart == null ? "-" : fmtN(s.xpStart), regular(), DIM, startR, base);
				rightText(g, fmtN(s.xpEnd), regular(), moved ? VALUE : DIM, endR, base);
				rightText(g, moved ? "+" + fmtN(s.gained()) : "-", regular(), moved ? ACCENT : DIM,
					gainedR, base);
			}
			else
			{
				rightText(g, fmtN(s.xpEnd), regular(), VALUE, gainedR, base);
			}
			cy += ROW;
		}
		cy += 6;
		if (f.totalLevel != null || f.totalXp != null)
		{
			int base = cy + 15;
			text(g, "Total", regular(), ACCENT, ix + 26, base);
			if (f.totalLevel != null)
			{
				Long a = f.totalLevel[0];
				Long b = f.totalLevel[1];
				String level = ends && a != null && b != null && a < b ? fmtN(a) + ARROW + fmtN(b)
					: b == null ? "-" : fmtN(b);
				rightText(g, level, regular(), Color.WHITE, levelR, base);
			}
			if (f.totalXp != null)
			{
				Long a = f.totalXp[0];
				Long b = f.totalXp[1];
				if (ends)
				{
					rightText(g, a == null ? "-" : fmtN(a), regular(), DIM, startR, base);
					rightText(g, b == null ? "-" : fmtN(b), regular(), VALUE, endR, base);
					rightText(g, a != null && b != null && b > a ? "+" + fmtN(b - a) : "-",
						regular(), ACCENT, gainedR, base);
				}
				else if (b != null)
				{
					rightText(g, fmtN(b), regular(), VALUE, gainedR, base);
				}
			}
			cy += ROW;
		}
		if (f.combat != null)
		{
			int base = cy + 15;
			text(g, "Combat", regular(), ACCENT, ix + 26, base);
			Long a = f.combat[0];
			Long b = f.combat[1];
			String level = ends && a != null && b != null && a < b ? a + ARROW + b
				: b == null ? "-" : String.valueOf(b);
			rightText(g, level, regular(), Color.WHITE, levelR, base);
			cy += ROW;
		}
		if (f.skillsNote != null)
		{
			int ny = cy + 4;
			for (String l : wrap(f.skillsNote, small(), inner, " "))
			{
				text(g, l, small(), DIM, ix, ny + 11);
				ny += NOTE_ROW;
			}
		}
	}

	private static int blockHeight(Block b, int from, int to)
	{
		int h = 2 * PAD + (from == 0 ? HEAD : 0);
		for (int i = from; i < to; i++)
		{
			h += b.pieces.get(i).height();
		}
		return h;
	}

	/**
	 * Lay the cards into columns: each whole into the first column with room
	 * for it, so a short card fills a gap an earlier column left, and a list
	 * too long for that running on from the first column that takes a fair
	 * start of it into the columns after, never leaving fewer than three rows
	 * on either side of a break nor a subheading at the foot of one. False
	 * when they do not all fit. Draws as it goes when asked to.
	 */
	private static boolean place(Graphics2D g, List<Block> blocks, int[] xs, int[] tops, int[] bottoms,
		boolean draw)
	{
		int[] y = tops.clone();
		int roomiest = tallest(tops, bottoms);
		for (Block b : blocks)
		{
			int size = b.pieces.size();
			int whole = blockHeight(b, 0, size);
			boolean placed = false;
			for (int c = 0; c < xs.length && !placed; c++)
			{
				if (whole <= bottoms[c] - y[c])
				{
					if (draw && g != null)
					{
						drawBlock(g, b, 0, size, xs[c], y[c]);
					}
					y[c] += whole + GAP;
					placed = true;
				}
			}
			if (placed)
			{
				continue;
			}
			if (size <= 12 && whole <= roomiest)
			{
				return false;
			}
			int i = 0;
			for (int c = 0; c < xs.length && i < size; c++)
			{
				int room = bottoms[c] - y[c];
				int j = i;
				int h = 2 * PAD + (i == 0 ? HEAD : 0);
				while (j < size && h + b.pieces.get(j).height() <= room)
				{
					h += b.pieces.get(j).height();
					j++;
				}
				while (j < size && j > i + 1 && b.pieces.get(j - 1).keepsWithNext())
				{
					j--;
					h -= b.pieces.get(j).height();
				}
				// a list opens with a fair share of itself, not five rows at the
				// foot of a column it then has to be followed out of
				boolean last = j == size;
				boolean fair = (j - i >= (i == 0 ? Math.min(8, size) : 3) || (last && j > i))
					&& (last || size - j >= 3);
				if (!fair)
				{
					continue;
				}
				if (draw && g != null)
				{
					drawBlock(g, b, i, j, xs[c], y[c]);
				}
				y[c] += blockHeight(b, i, j) + GAP;
				i = j;
			}
			if (i < size)
			{
				return false;
			}
		}
		return true;
	}

	private static int[] even(int bottom)
	{
		int[] b = new int[COLUMNS];
		java.util.Arrays.fill(b, bottom);
		return b;
	}

	private static int tallest(int[] tops, int[] bottoms)
	{
		int most = 0;
		for (int c = 0; c < tops.length; c++)
		{
			most = Math.max(most, bottoms[c] - tops[c]);
		}
		return most;
	}

	private static void drawBlock(Graphics2D g, Block b, int from, int to, int x, int y)
	{
		int h = blockHeight(b, from, to);
		g.setColor(CARD);
		g.fillRect(x, y, COL, h);
		int cy = y + PAD;
		if (from == 0)
		{
			text(g, b.title.toUpperCase(java.util.Locale.ROOT), small(), ACCENT, x + PAD, cy + 13);
			cy += HEAD;
		}
		for (int i = from; i < to; i++)
		{
			Piece p = b.pieces.get(i);
			p.draw(g, x + PAD, cy, COL - 2 * PAD);
			cy += p.height();
		}
	}

	/**
	 * Take one row off the longest list that may lose some, and say so at its
	 * foot; false when there is nothing left that may give.
	 */
	private static boolean trimOne(List<Block> run, int inner)
	{
		Block longest = null;
		for (Block b : run)
		{
			if (b.trims && b.pieces.size() > 1 && (longest == null
				|| b.pieces.size() > longest.pieces.size()))
			{
				longest = b;
			}
		}
		if (longest == null)
		{
			return false;
		}
		// the tail line saying how many were left off is itself a piece: drop it
		// before counting, put it back after
		if (longest.dropped > 0)
		{
			longest.pieces.remove(longest.pieces.size() - 1);
		}
		longest.pieces.remove(longest.pieces.size() - 1);
		longest.dropped++;
		longest.pieces.addAll(note("+ " + longest.dropped + " more", inner));
		return true;
	}
}
