/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@AllArgsConstructor
final class Tally
{
	final String name;
	long qty;
	long value;

	Tally add(long moreQty, long moreValue)
	{
		qty += moreQty;
		value += moreValue;
		return this;
	}

	static Tally of(List<BagItem> bag)
	{
		Tally t = new Tally(null);
		bag.forEach(b -> t.add(b.qty, b.value));
		return t;
	}

	static void add(Map<String, Tally> into, String name, long qty, long value)
	{
		into.computeIfAbsent(name, Tally::new).add(qty, value);
	}

	static List<Tally> ranked(Map<String, Tally> by)
	{
		List<Tally> out = new ArrayList<>(by.values());
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	static Tally find(List<Tally> rows, String name, boolean exact)
	{
		return Ui.find(rows, t -> t.name, name, exact);
	}
}
