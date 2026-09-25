/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;

/**
 * Pet-petting, counted off the "Pet" menu click and settled a tick later.
 */
@RequiredArgsConstructor
public class NPCStatTracker implements StatTracker
{
	private final StatStore statStore;

	private boolean evenTick = false;
	private boolean pendingPet = false;

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Pet".equals(event.getMenuOption()))
		{
			pendingPet = true;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		// the pet animation runs over two ticks, so bank on the even one or a held click credits twice
		if (pendingPet && evenTick)
		{
			statStore.incrementStat("animalsPetted");
			pendingPet = false;
		}

		evenTick = !evenTick;
	}
}
