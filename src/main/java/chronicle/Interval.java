/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class Interval
{
	final long from;
	final long to;

	boolean holds(long ts)
	{
		return ts >= from && ts <= to;
	}
}
