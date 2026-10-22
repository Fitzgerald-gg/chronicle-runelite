/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

final class LootingBagDiamonds
{
	private long known = -1;
	private long filed;
	private int openings;
	private int step;

	void forget()
	{
		known = -1;
		filed = 0;
		openings = 0;
		step = 0;
	}

	void opened(long qty, int bigStep)
	{
		if (known < 0)
		{
			return;
		}
		step = openings == 0 || step == bigStep ? bigStep : 0;
		filed += qty;
		openings++;
	}

	long read(long count)
	{
		long extra = count - known - filed;
		boolean fits = known >= 0 && step > 0 && extra > 0
			&& extra % step == 0 && extra <= (long) step * openings;
		known = count;
		filed = 0;
		openings = 0;
		step = 0;
		return fits ? extra : 0;
	}
}
