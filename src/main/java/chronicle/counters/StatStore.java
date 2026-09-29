/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

@Singleton
public class StatStore
{
	private final Map<String, Integer> totals = new ConcurrentHashMap<>();

	private volatile long revision;

	public long revision()
	{
		return revision;
	}

	public void clear()
	{
		totals.clear();
		revision++;
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
		totals.merge(key, amount, StatStore::saturatingSum);
		revision++;
	}

	public void setStat(String key, int value)
	{
		totals.put(key, value);
		revision++;
	}

	public Map<String, Integer> snapshotAll()
	{
		return new HashMap<>(totals);
	}

	private static int saturatingSum(int current, int addend)
	{
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) current + addend));
	}
}
