/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.AsyncBufferedImage;
import static chronicle.ChroniclePanel.ICON_H;
import static chronicle.ChroniclePanel.ICON_W;

final class Art
{
	private static final Map<Skill, BufferedImage> SKILL_ICONS =
		new EnumMap<>(Skill.class);

	private final ChroniclePanel panel;
	private final Map<String, BufferedImage> images = new HashMap<>();
	private final Set<String> artAsked = new HashSet<>();
	private final Map<String, List<Object[]>> artWaiting = new LinkedHashMap<>();
	private final Map<String, ImageIcon> scaledIcons = new LinkedHashMap<>();

	Art(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	void mountKindIcon(JLabel label, String name)
	{
		int item = panel.itemNames.signature(name);
		if (item > 0)
		{
			mountItem(label, item);
			return;
		}
		Skill skill = Sources.skillOf(name);
		if (skill != null)
		{
			BufferedImage img = skillIcon(skill);
			if (img != null)
			{
				dress(label, "skill:" + skill.name(), img, ICON_W, ICON_H);
				return;
			}
		}
		wearSprite(label, kindSprite(panel.build.names().kind(name)), ICON_W, ICON_H);
	}

	private void mountItem(JLabel label, int itemId)
	{
		wear(label, "item:" + itemId, ICON_W, ICON_H, done ->
		{
			AsyncBufferedImage img = panel.store.items().getImage(itemId, 1, false);
			if (img != null)
			{
				img.onLoaded(() -> done.accept(img));
			}
			return img != null;
		});
	}

	private static int kindSprite(String kind)
	{
		if (Sources.ACTIVITY.equals(kind))
		{
			return 1053;
		}
		return Sources.SKILLING.equals(kind) ? 775 : 774;
	}

	BufferedImage skillIcon(Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s ->
		{
			try
			{
				return panel.skillIcons.getSkillImage(s, true);
			}
			catch (Throwable e)
			{
				return null;
			}
		});
	}

	void wearSprite(JLabel label, int spriteId, int w, int h)
	{
		wear(label, "sprite:" + spriteId, w, h, done ->
		{
			SpriteManager sm = panel.sprites;
			if (sm != null)
			{
				sm.getSpriteAsync(spriteId, 0, done);
			}
			return sm != null;
		});
	}

	private void wear(JLabel label, String key, int w, int h,
		Predicate<Consumer<BufferedImage>> fetch)
	{
		BufferedImage have = images.get(key);
		if (have != null)
		{
			dress(label, key + "@" + w, have, w, h);
			return;
		}
		artWaiting.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{label, w, h});
		if (!artAsked.add(key))
		{
			return;
		}
		try
		{
			if (!fetch.test(img -> SwingUtilities.invokeLater(() -> landed(key, img))))
			{
				artAsked.remove(key);
			}
		}
		catch (Throwable ignored)
		{
		}
	}

	private void landed(String key, BufferedImage img)
	{
		if (img == null)
		{
			return;
		}
		images.put(key, img);
		for (Object[] want : artWaiting.getOrDefault(key, Collections.emptyList()))
		{
			dress((JLabel) want[0], key + "@" + want[1], img, (Integer) want[1], (Integer) want[2]);
		}
		artWaiting.remove(key);
	}

	private void dress(JLabel label, String key, BufferedImage img, int w, int h)
	{
		ImageIcon icon = scaledIcons.get(key);
		if (icon == null)
		{
			icon = w <= 0 || h <= 0 ? new ImageIcon(img) : fit(img, w, h);
			scaledIcons.put(key, icon);
		}
		label.setIcon(icon);
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

	BufferedImage sprite(int id)
	{
		return images.get("sprite:" + id);
	}

	void forgetWaiting()
	{
		artWaiting.clear();
	}

	void forgetScaled()
	{
		scaledIcons.clear();
	}
}
