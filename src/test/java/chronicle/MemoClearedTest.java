/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class MemoClearedTest
{
	private static final Set<String> OUTLIVES_A_BUILD = new LinkedHashSet<>(
		Arrays.asList("journeyFetching", "searchFirst", "lootTask", "journeyCache"));

	@Test
	public void everyPerBuildMemoIsForgottenAtTheTopOfTheBuild() throws Exception
	{
		String src = new String(Files.readAllBytes(
			Paths.get("src/main/java/chronicle/ChroniclePanel.java")), StandardCharsets.UTF_8);

		Set<String> fields = new LinkedHashSet<>();
		Matcher f = Pattern.compile("^\\tprivate (?:static )?(?:final )?[\\w\\.<>\\[\\],? ]+?\\s(\\w+);",
			Pattern.MULTILINE).matcher(src);
		while (f.find())
		{
			fields.add(f.group(1));
		}

		Set<String> memos = new LinkedHashSet<>();
		Matcher m = Pattern.compile("if \\((\\w+) == null\\)\\s*\\n\\s*\\{").matcher(src);
		while (m.find())
		{
			String name = m.group(1);
			String after = src.substring(m.end(), Math.min(src.length(), m.end() + 1200));
			if (fields.contains(name)
				&& Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=[^=]").matcher(after).find())
			{
				memos.add(name);
			}
		}
		Matcher b = Pattern.compile(
			"if \\((\\w+)\\)\\s*\\n\\s*\\{\\s*\\n\\s*return (\\w+);\\s*\\n\\s*\\}\\s*\\n\\s*\\1 = true;")
			.matcher(src);
		while (b.find())
		{
			for (String name : new String[]{b.group(1), b.group(2)})
			{
				if (fields.contains(name))
				{
					memos.add(name);
				}
			}
		}
		assertTrue("no memo was recognised at all, so this asserts nothing about "
			+ "any of them", memos.size() >= 8);

		int at = src.indexOf("private void rebuildNow()");
		assertTrue("rebuildNow is gone", at > 0);
		int until = src.indexOf("artWaiting.clear();", at);
		assertTrue("the clearing run no longer ends where this test looks for it",
			until > at);
		String head = src.substring(at, until);
		Set<String> cleared = new LinkedHashSet<>();
		Matcher c = Pattern.compile("^\\t\\t(\\w+) = (?:null|false|0);", Pattern.MULTILINE)
			.matcher(head);
		while (c.find())
		{
			cleared.add(c.group(1));
		}

		List<String> loose = new ArrayList<>();
		for (String memo : memos)
		{
			if (!cleared.contains(memo) && !OUTLIVES_A_BUILD.contains(memo))
			{
				loose.add(memo);
			}
		}
		assertTrue("a memo answered once per build is not forgotten at the top of "
			+ "the next one, so a board can answer from the period before it: "
			+ loose + ". Clear it in rebuildNow, or name it in OUTLIVES_A_BUILD "
			+ "with the reason it must survive.", loose.isEmpty());
	}

	@Test
	public void theExceptionsAreRealFields() throws Exception
	{
		String src = new String(Files.readAllBytes(
			Paths.get("src/main/java/chronicle/ChroniclePanel.java")), StandardCharsets.UTF_8);
		for (String name : OUTLIVES_A_BUILD)
		{
			assertTrue("OUTLIVES_A_BUILD names a field the panel no longer has: " + name,
				Pattern.compile("\\s" + Pattern.quote(name) + "\\s*;").matcher(src).find());
		}
	}
}
