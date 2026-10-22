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
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;

final class Art
{
	private static final Map<Skill, BufferedImage> SKILL_ICONS = new EnumMap<>(Skill.class);

	private final ChroniclePanel panel;
	private final Map<String, BufferedImage> images = new HashMap<>();
	private final Set<String> asked = new HashSet<>();
	private final Map<String, List<Waiting>> waiting = new LinkedHashMap<>();
	private final Map<String, ImageIcon> scaled = new LinkedHashMap<>();

	Art(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	BufferedImage skillIcon(Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s -> panel.skillIcons.getSkillImage(s, true));
	}

	void wearSprite(JLabel label, int spriteId, int w, int h)
	{
		String key = "sprite:" + spriteId;
		BufferedImage have = images.get(key);
		if (have != null)
		{
			dress(label, key + "@" + w, have, w, h);
			return;
		}
		waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(new Waiting(label, w, h));
		if (asked.add(key))
		{
			panel.sprites.getSpriteAsync(spriteId, 0, img -> SwingUtilities.invokeLater(() -> landed(key, img)));
		}
	}

	private void landed(String key, BufferedImage img)
	{
		if (img == null)
		{
			return;
		}
		images.put(key, img);
		for (Waiting want : waiting.getOrDefault(key, Collections.emptyList()))
		{
			dress(want.label, key + "@" + want.w, img, want.w, want.h);
		}
		waiting.remove(key);
	}

	private void dress(JLabel label, String key, BufferedImage img, int w, int h)
	{
		label.setIcon(scaled.computeIfAbsent(key, k -> fit(img, w, h)));
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
		waiting.clear();
	}

	void forgetScaled()
	{
		scaled.clear();
	}

	@RequiredArgsConstructor
	private static final class Waiting
	{
		final JLabel label;
		final int w;
		final int h;
	}
}
