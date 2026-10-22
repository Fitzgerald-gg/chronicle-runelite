/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.OSType;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.column;
import static chronicle.Kit.small;
import static chronicle.Text.fmt;

final class Copy
{
	private static final DataFlavor PNG_BYTES = pngFlavor();
	private static boolean pngNativeMapped;
	private static final int COPY_MAX_HEIGHT = 20000;
	private static final int COPY_WIDTH = 340;
	private static final int COPY_ROWS = 60;
	private static final int COPY_COLUMNS = 6;
	private static final int COPY_GAP = 10;
	static final int COPY_MOST = COPY_COLUMNS * 200;

	private Copy()
	{
	}

	private static Transferable transferable(DataFlavor flavor, Supplier<Object> data)
	{
		return new Transferable()
		{
			@Override
			public DataFlavor[] getTransferDataFlavors()
			{
				return new DataFlavor[]{flavor};
			}

			@Override
			public boolean isDataFlavorSupported(DataFlavor f)
			{
				return flavor.equals(f);
			}

			@Override
			public Object getTransferData(DataFlavor f)
				throws UnsupportedFlavorException
			{
				if (flavor.equals(f))
				{
					return data.get();
				}
				throw new UnsupportedFlavorException(f);
			}
		};
	}

	static boolean toClipboard(Image image)
	{
		if (image == null)
		{
			return false;
		}
		try
		{
			Transferable payload = pngPayload(image);
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(payload != null ? payload : transferable(DataFlavor.imageFlavor, () -> image), null);
			return true;
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	private static DataFlavor pngFlavor()
	{
		try
		{
			return new DataFlavor("image/png;class=java.io.InputStream");
		}
		catch (ClassNotFoundException ignored)
		{
			return null;
		}
	}

	private static Transferable pngPayload(Image image)
	{
		if (PNG_BYTES == null || OSType.getOSType() != OSType.MacOS
			|| !(image instanceof RenderedImage))
		{
			return null;
		}
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			MemoryCacheImageOutputStream ios =
				new MemoryCacheImageOutputStream(out);
			if (!ImageIO.write((RenderedImage) image, "png", ios))
			{
				return null;
			}
			ios.flush();
			final byte[] png = out.toByteArray();
			if (png.length == 0)
			{
				return null;
			}
			mapPngNative();
			return transferable(PNG_BYTES, () -> new ByteArrayInputStream(png));
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static synchronized void mapPngNative()
	{
		if (pngNativeMapped)
		{
			return;
		}
		((SystemFlavorMap)
			SystemFlavorMap.getDefaultFlavorMap())
			.addUnencodedNativeForFlavor(PNG_BYTES, "public.png");
		pngNativeMapped = true;
	}

	private static Image pageImage(JPanel page, int width)
	{
		try
		{
			int w = width;
			page.setSize(w, COPY_MAX_HEIGHT);
			layOut(page);
			int full = Math.max(1, page.getPreferredSize().height);
			page.setSize(w, full);
			layOut(page);

			int h = Math.min(full, COPY_MAX_HEIGHT);
			int lost = h < full ? countCut(page, h) : 0;
			BufferedImage img = new BufferedImage(
				w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(DARK);
			g.fillRect(0, 0, w, h);
			page.printAll(g);
			if (lost > 0)
			{
				drawCutNote(g, w, h, lost);
			}
			g.dispose();
			return img;
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static int countCut(JPanel page, int cut)
	{
		int n = 0;
		for (Component k : page.getComponents())
		{
			if (k.getY() >= cut)
			{
				n++;
			}
		}
		return n;
	}

	private static void drawCutNote(Graphics2D g, int w, int h, int lost)
	{
		int band = 20;
		g.setColor(DARKER);
		g.fillRect(0, h - band, w, band);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setFont(small());
		String said = fmt(lost) + " more did not fit";
		g.drawString(said, 6, h - 6);
	}

	private static void layOut(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				layOut(k);
			}
		}
	}

	static boolean copyPicture(JPanel page)
	{
		return copyPicture(page, false);
	}

	static boolean copyPicture(JPanel page, boolean tall)
	{
		return toClipboard(copyImage(page, tall));
	}

	private static Image copyImage(JPanel page)
	{
		return copyImage(page, false);
	}

	private static Image copyImage(JPanel page, boolean tall)
	{
		int cols = tall ? 1 : copyColumns(page.getComponentCount());
		return pageImage(reflowed(page, cols), COPY_WIDTH * cols + COPY_GAP * (cols - 1));
	}

	private static int copyColumns(int rows)
	{
		int held = Math.max(0, Math.min(rows, COPY_MOST));
		return Math.max(1, Math.min(COPY_COLUMNS, (held + COPY_ROWS - 1) / COPY_ROWS));
	}

	private static JPanel reflowed(JPanel page, int cols)
	{
		if (cols <= 1)
		{
			return page;
		}
		Component[] kids = page.getComponents();
		page.removeAll();
		int per = (kids.length + cols - 1) / cols;
		JPanel grid = new JPanel(new GridLayout(1, cols, COPY_GAP, 0));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int c = 0; c < cols; c++)
		{
			JPanel col = column();
			for (int i = c * per; i < Math.min(kids.length, (c + 1) * per); i++)
			{
				col.add(kids[i]);
			}
			JPanel cell = new JPanel(new BorderLayout());
			cell.setBackground(DARK);
			cell.add(col, BorderLayout.NORTH);
			grid.add(cell);
		}
		JPanel out = column();
		out.add(grid);
		return out;
	}

	static JPanel stripChrome(JPanel page)
	{
		if (page.getComponentCount() > 2)
		{
			page.remove(1);
			page.remove(0);
		}
		return page;
	}
}
