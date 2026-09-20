/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * A doc block describes the thing under it.
 *
 * <p>Two doc blocks stacked mean the first one describes the second, which is
 * not a thing, and the method it was written for is now some distance below
 * with somebody else's explanation over it. It happens whenever a helper is
 * added just above an existing method rather than just below it, which is the
 * natural place to put one, and it is invisible: both blocks read perfectly
 * well and neither is attached to what it talks about.
 *
 * <p>Four of these had accumulated. Two were written while fixing the other
 * two, in the same sitting, by exactly that mechanism.
 */
public class JavadocAnchoredTest
{
	private static List<Path> sources() throws IOException
	{
		List<Path> out = new ArrayList<>();
		for (String root : new String[]{"src/main/java", "src/test/java"})
		{
			try (Stream<Path> walk = Files.walk(Paths.get(root)))
			{
				walk.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
			}
		}
		return out;
	}

	@Test
	public void noDocBlockDescribesAnotherDocBlock() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (Path f : sources())
		{
			List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			for (int i = 0; i + 1 < lines.size(); i++)
			{
				if (!lines.get(i).trim().equals("*/")
					|| !lines.get(i + 1).trim().startsWith("/**"))
				{
					continue;
				}
				int open = i;
				while (open > 0 && !lines.get(open).trim().startsWith("/**"))
				{
					open--;
				}
				String first = open + 1 < lines.size()
					? lines.get(open + 1).trim() : "";
				bad.add(f + ":" + (open + 1) + "  " + first);
			}
		}
		assertTrue("a doc block sits directly on another doc block, so it documents"
			+ " nothing and the method it was written for has somebody else's"
			+ " explanation over it:\n  " + String.join("\n  ", bad), bad.isEmpty());
	}
}
