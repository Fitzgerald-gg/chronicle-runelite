/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;

final class OverlayScrollBarUI extends BasicScrollBarUI
{
	static final int WIDTH = 5;
	private static final int IDLE_MS = 700;
	private static final int STEP_MS = 40;
	private static final float STEP = 0.12f;
	private static final Color THUMB = new Color(0xB0, 0xB0, 0xB0);

	private float alpha;
	private long lastMove;
	private Timer fader;

	static void install(JScrollPane scroll)
	{
		JScrollBar bar = scroll.getVerticalScrollBar();
		OverlayScrollBarUI ui = new OverlayScrollBarUI();
		bar.setUI(ui);
		scroll.addMouseWheelListener(e -> ui.wake());
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(WIDTH, 0));
	}

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
		int h = Math.max(WIDTH * 2, t.height - 4);
		g2.fillRoundRect(t.x, t.y + 2, WIDTH, h,
			WIDTH, WIDTH);
		g2.dispose();
	}

	private void wake()
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
