/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.game.SpriteManager;
import static chronicle.Json.*;
import static chronicle.Ui.link;
import static chronicle.Ui.named;

final class Art
{
	private final ChroniclePlugin plugin;
	private final Map<Integer, BufferedImage> sprites = new HashMap<>();
	private final Map<Integer, List<Object[]>> waiting = new HashMap<>();
	private final Map<String, ImageIcon> scaled = new HashMap<>();
	private final Map<Skill, BufferedImage> skills = new EnumMap<>(Skill.class);

	Art(ChroniclePlugin plugin)
	{
		this.plugin = plugin;
	}

	BufferedImage sprite(int id)
	{
		return sprites.get(id);
	}

	boolean has(int id)
	{
		return sprites.containsKey(id);
	}

	void wear(JLabel label, int id, int w, int h)
	{
		BufferedImage have = sprites.get(id);
		if (have != null)
		{
			dress(label, id, have, w, h);
			return;
		}
		boolean asked = waiting.containsKey(id);
		waiting.computeIfAbsent(id, k -> new ArrayList<>()).add(new Object[]{label, w, h});
		SpriteManager sm = plugin.sprites();
		if (asked || sm == null)
		{
			return;
		}
		try
		{
			sm.getSpriteAsync(id, 0, img -> SwingUtilities.invokeLater(() -> landed(id, img)));
		}
		catch (RuntimeException ignored)
		{
		}
	}

	void forget()
	{
		waiting.clear();
	}

	BufferedImage skill(Skill sk)
	{
		return skills.computeIfAbsent(sk, s ->
		{
			try
			{
				return plugin.skillIcons().getSkillImage(s, true);
			}
			catch (RuntimeException e)
			{
				return null;
			}
		});
	}

	JLabel item(int id, String name, long qty, Consumer<String> open)
	{
		JLabel slot = new JLabel();
		slot.setPreferredSize(new Dimension(36, 32));
		slot.setHorizontalAlignment(JLabel.CENTER);
		slot.setToolTipText(named(name, qty));
		link(slot, () -> open.accept(name));
		plugin.items().getImage(id, (int) Math.min(Integer.MAX_VALUE, qty), qty > 1).addTo(slot);
		return slot;
	}

	private void landed(int id, BufferedImage img)
	{
		if (img == null)
		{
			return;
		}
		sprites.put(id, img);
		for (Object[] want : waiting.getOrDefault(id, List.of()))
		{
			dress((JLabel) want[0], id, img, (Integer) want[1], (Integer) want[2]);
		}
		waiting.remove(id);
	}

	private void dress(JLabel label, int id, BufferedImage img, int w, int h)
	{
		label.setIcon(scaled.computeIfAbsent(id + "@" + w, k -> fit(img, w, h)));
		label.setText("");
	}

	private static ImageIcon fit(BufferedImage img, int w, int h)
	{
		double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
		return new ImageIcon(img.getScaledInstance(
			Math.max(1, (int) Math.round(img.getWidth() * scale)),
			Math.max(1, (int) Math.round(img.getHeight() * scale)),
			Image.SCALE_SMOOTH));
	}
}
