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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import static chronicle.Ui.fmt;
import static chronicle.Ui.small;

final class RecapPicture
{
	static final int WIDTH = 1920;
	private static final int MAX_HEIGHT = 1080;

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
	private static final int MIN_BODY = 260;

	private static final Color GROUND = Ui.DARK;
	private static final Color CARD = Ui.DARKER;
	private static final Color TEXT = Ui.LIT;
	private static final Color VALUE = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color DIM = Ui.DIM;
	private static final Color ACCENT = Ui.ACCENT;

	private static final String ARROW = " to ";

	static final class Facts
	{
		String title = "";
		boolean whole;
		boolean session;
		final List<Tile> tiles = new ArrayList<>();
		final List<SkillLine> skills = new ArrayList<>();
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
		int dropped;
	}

	@RequiredArgsConstructor
	static final class Tile
	{
		final String label;
		final String figure;
		final String under;
	}

	@RequiredArgsConstructor
	static final class SkillLine
	{
		final Skill skill;
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

	@RequiredArgsConstructor
	static final class BossLine
	{
		final String name;
		final int sprite;
		final Long start;
		final Long end;
		final long gained;
	}

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

	private interface Draw
	{
		void at(Graphics2D g, int x, int y, int w);
	}

	@RequiredArgsConstructor
	private static final class Piece
	{
		final int height;
		final boolean keepsWithNext;
		final Draw draw;
	}

	@RequiredArgsConstructor
	private static final class Block
	{
		final String title;
		final List<Piece> pieces = new ArrayList<>();
		final boolean trims;
		int dropped;
	}

	private static Font regular()
	{
		return FontManager.getRunescapeFont();
	}

	private static Font big()
	{
		return FontManager.getRunescapeBoldFont().deriveFont(32f);
	}

	private static final Graphics2D MEASURE = new BufferedImage(1, 1,
		BufferedImage.TYPE_INT_RGB).createGraphics();

	private static FontMetrics fm(Font f)
	{
		return MEASURE.getFontMetrics(f);
	}

	private static String cut(String s, Font f, int w)
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

	private static List<Piece> lines(List<Named> all)
	{
		List<Piece> out = new ArrayList<>();
		for (Named n : all)
		{
			out.add(new Piece(ROW, false, (g, x, y, w) ->
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
			}));
		}
		return out;
	}

	private static List<Piece> note(String s, int w)
	{
		return rows(wrap(s, small(), w, " "), small(), DIM, NOTE_ROW, 11);
	}

	private static List<Piece> names(List<String> all, int w)
	{
		return rows(wrap(String.join(" · ", all), regular(), w, " · "), regular(), TEXT, ROW - 2, 14);
	}

	private static List<Piece> rows(List<String> lines, Font f, Color c, int h, int base)
	{
		List<Piece> out = new ArrayList<>();
		for (String l : lines)
		{
			out.add(new Piece(h, false, (g, x, y, w) -> text(g, l, f, c, x, y + base)));
		}
		return out;
	}

	static List<String> wrap(String s, Font f, int w, String sep)
	{
		FontMetrics m = fm(f);
		List<String> out = new ArrayList<>();
		String cur = null;
		for (String part : s.split(Pattern.quote(sep)))
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
		return new Piece(20, true, (g, x, y, w) -> text(g, s.toUpperCase(Locale.ROOT), small(), DIM, x, y + 15));
	}

	private static Piece bossLine(BossLine b, boolean whole, Function<Integer, BufferedImage> sprites)
	{
		return new Piece(ICON_ROW, false, (g, x, y, w) ->
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
				right = rightText(g, fmt(b.end == null ? b.gained : b.end), regular(), VALUE, right, base) - 8;
			}
			else
			{
				right = rightText(g, "+" + fmt(b.gained), regular(), ACCENT, right, base) - 8;
				if (b.start != null && b.end != null)
				{
					right = rightText(g, climb(b.start, b.end), regular(), DIM, right, base) - 8;
				}
			}
			text(g, cut(b.name, regular(), right - x - 28), regular(), TEXT, x + 28, base);
		});
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

	private static String climb(long from, long to)
	{
		return fmt(from) + ARROW + fmt(to);
	}

	private static List<Block> blocks(Facts f, int inner, Function<Integer, BufferedImage> sprites)
	{
		List<Block> run = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : f.feats.entrySet())
		{
			Block b = new Block(e.getKey(), true);
			b.pieces.addAll(names(e.getValue(), inner));
			run.add(b);
		}
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
			run.add(b);
		}
		if (!f.monsters.isEmpty() || f.monstersNote != null)
		{
			Block b = new Block("Monsters", true);
			b.pieces.addAll(lines(f.monsters));
			if (f.monstersNote != null)
			{
				b.pieces.addAll(note(f.monstersNote, inner));
			}
			run.add(b);
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
			run.add(b);
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
			run.add(b);
		}
		for (Map.Entry<String, List<Named>> e : f.trackers.entrySet())
		{
			Block b = new Block(e.getKey(), true);
			b.pieces.addAll(lines(e.getValue()));
			run.add(b);
		}
		if (f.trackersNote != null)
		{
			Block b = new Block("Trackers", false);
			b.pieces.addAll(note(f.trackersNote, inner));
			run.add(b);
		}

		return run;
	}

	static BufferedImage paint(Facts f, Function<Skill, BufferedImage> skillIcons,
		Function<Integer, BufferedImage> sprites)
	{
		int contentW = COLUMNS * COL + (COLUMNS - 1) * GAP;
		int inner = COL - 2 * PAD;
		int wideInner = 2 * COL + GAP - 2 * PAD;

		int headH = TOP + 12 + 4 + 34 + 16 + (f.tiles.isEmpty() ? 0 : TILE_H + 18);
		int footNotes = f.notes.size();
		int footH = 14 + footNotes * NOTE_ROW + 20;
		int maxBody = MAX_HEIGHT - headH - footH;

		int tableH = skillsTableHeight(f);

		List<Block> run = blocks(f, inner, sprites);
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
		}
		f.dropped = 0;
		for (Block b : run)
		{
			f.dropped += b.dropped;
		}
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
		text(g, t.label.toUpperCase(Locale.ROOT), small(), DIM, x + PAD, y + PAD + 11);
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
		Function<Skill, BufferedImage> icons, int x, int y, int w, int h, int inner)
	{
		g.setColor(CARD);
		g.fillRect(x, y, w, h);
		int ix = x + PAD;
		int cy = y + PAD;
		text(g, "SKILLS", small(), ACCENT, ix, cy + 13);
		cy += HEAD;
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
			String level = rose ? climb(s.levelStart, s.levelEnd) : String.valueOf(s.levelEnd);
			rightText(g, level, regular(), rose ? Color.WHITE : c, levelR, base);
			if (ends)
			{
				rightText(g, s.xpStart == null ? "-" : fmt(s.xpStart), regular(), DIM, startR, base);
				rightText(g, fmt(s.xpEnd), regular(), moved ? VALUE : DIM, endR, base);
				rightText(g, moved ? "+" + fmt(s.gained()) : "-", regular(), moved ? ACCENT : DIM,
					gainedR, base);
			}
			else
			{
				rightText(g, fmt(s.xpEnd), regular(), VALUE, gainedR, base);
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
				String level = ends && a != null && b != null && a < b ? climb(a, b)
					: b == null ? "-" : fmt(b);
				rightText(g, level, regular(), Color.WHITE, levelR, base);
			}
			if (f.totalXp != null)
			{
				Long a = f.totalXp[0];
				Long b = f.totalXp[1];
				if (ends)
				{
					rightText(g, a == null ? "-" : fmt(a), regular(), DIM, startR, base);
					rightText(g, b == null ? "-" : fmt(b), regular(), VALUE, endR, base);
					rightText(g, a != null && b != null && b > a ? "+" + fmt(b - a) : "-",
						regular(), ACCENT, gainedR, base);
				}
				else if (b != null)
				{
					rightText(g, fmt(b), regular(), VALUE, gainedR, base);
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
			String level = ends && a != null && b != null && a < b ? climb(a, b)
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
			h += b.pieces.get(i).height;
		}
		return h;
	}

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
				while (j < size && h + b.pieces.get(j).height <= room)
				{
					h += b.pieces.get(j).height;
					j++;
				}
				while (j < size && j > i + 1 && b.pieces.get(j - 1).keepsWithNext)
				{
					j--;
					h -= b.pieces.get(j).height;
				}
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
		Arrays.fill(b, bottom);
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
			text(g, b.title.toUpperCase(Locale.ROOT), small(), ACCENT, x + PAD, cy + 13);
			cy += HEAD;
		}
		for (int i = from; i < to; i++)
		{
			Piece p = b.pieces.get(i);
			p.draw.at(g, x + PAD, cy, COL - 2 * PAD);
			cy += p.height;
		}
	}

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
