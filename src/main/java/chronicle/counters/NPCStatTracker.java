/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;

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
		if (pendingPet && evenTick)
		{
			statStore.incrementStat("animalsPetted");
			pendingPet = false;
		}

		evenTick = !evenTick;
	}
}
