/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ExperienceStatTracker implements StatTracker
{
	static final long RATE_FLOOR_MS = 60_000L;

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class SkillGain
	{
		public final Skill skill;
		public final long xp;
		public final long perHour;
	}

	private final StatStore store;
	private final LongSupplier clock;
	private final XpSeen xpSeen = new XpSeen();
	private final Map<Skill, Long> sessionXp = new EnumMap<>(Skill.class);
	private long windowStartMs;

	public ExperienceStatTracker(StatStore store)
	{
		this(store, System::currentTimeMillis);
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		int gained = xpSeen.gain(event);
		if (gained > 0)
		{
			store.incrementStatBy("totalXpGained", gained);
			count(event.getSkill(), gained);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
			clearSession();
		}
	}

	public synchronized List<SkillGain> sessionGains()
	{
		long elapsed = windowStartMs > 0 ? clock.getAsLong() - windowStartMs : 0;
		boolean rateable = elapsed >= RATE_FLOOR_MS;
		List<SkillGain> out = new ArrayList<>(sessionXp.size());
		for (Map.Entry<Skill, Long> e : sessionXp.entrySet())
		{
			long xp = e.getValue();
			out.add(new SkillGain(e.getKey(), xp, rateable ? xp * 3_600_000L / elapsed : -1L));
		}
		out.sort(Comparator.comparingLong((SkillGain g) -> g.xp).reversed()
			.thenComparingInt(g -> g.skill.ordinal()));
		return out;
	}

	private synchronized void count(Skill skill, int gained)
	{
		if (sessionXp.isEmpty())
		{
			windowStartMs = clock.getAsLong();
		}
		sessionXp.merge(skill, (long) gained, Long::sum);
	}

	private synchronized void clearSession()
	{
		sessionXp.clear();
		windowStartMs = 0;
	}
}
