/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

public interface GatheredLedger
{
	void noteGathered(int itemId);

	boolean wasGathered(int itemId);
}
