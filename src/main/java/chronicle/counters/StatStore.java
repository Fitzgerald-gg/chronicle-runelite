/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;
import lombok.Getter;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class StatStore
{
	private final Map<String, Integer> totals = new ConcurrentHashMap<>();

	private final AtomicLong revision = new AtomicLong();

	public void clear()
	{
		totals.clear();
		revision.incrementAndGet();
	}

	public int getStat(String key)
	{
		return totals.getOrDefault(key, 0);
	}

	public void incrementStat(String key)
	{
		incrementStatBy(key, 1);
	}

	public void incrementStatBy(String key, int amount)
	{
		totals.merge(key, amount, (a, b) -> (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) a + b)));
		revision.incrementAndGet();
	}

	public void setStat(String key, int value)
	{
		totals.put(key, value);
		revision.incrementAndGet();
	}

	public Map<String, Integer> snapshotAll()
	{
		return new HashMap<>(totals);
	}

	public long revision()
	{
		return revision.get();
	}
}
