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

/**
 * A memo answered once per build has to be forgotten once per build.
 *
 * <p>The panel answers a handful of expensive questions once and keeps the
 * answer for the rest of the pass: the sources, the collection log, the spine's
 * span, the per-source kill counts. Every one of them depends on the PERIOD, so
 * an answer surviving into the next build is not a stale number, it is a wrong
 * one - and it reads as a board quietly showing another period's figures, which
 * is the hardest kind of wrong to notice.
 *
 * <p>This has bitten twice. rolledKcs was cleared inside buildKills, which draws
 * AFTER the activity tiles that also ask it, so on a period change those tiles
 * answered from the last window. And a probe that cleared every memo but
 * buildSpan printed a lifetime figure under a week's heading, which is exactly
 * what the panel would do if the clearing were ever dropped.
 *
 * <p>So: every memo is cleared at the top of rebuildNow, and anything that is
 * deliberately not has to say why here.
 */
public class MemoClearedTest
{
	/**
	 * State that outlives a build ON PURPOSE, and would be a defect if cleared.
	 *
	 * <p>journeyFetching guards a fetch that is still in the air; cleared on a
	 * rebuild it would launch a second one over the first. searchFirst is the
	 * door Enter opens, set while the results are built and read when the key
	 * is pressed, which is a later pass by definition.
	 */
	private static final Set<String> OUTLIVES_A_BUILD = new LinkedHashSet<>(
		Arrays.asList("journeyFetching", "searchFirst", "lootTask"));

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

		// a memo: a field filled in behind its own null (or false) check
		Set<String> memos = new LinkedHashSet<>();
		// A field filled in behind its own null check.
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
		// And the other shape, which the first pass missed and which is the one
		// that misled a probe into printing a lifetime under a week's heading:
		// a boolean that guards an early return, with the answer beside it.
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
		// To the end of the clearing run, not a count of characters: the block
		// grows every time a memo is added, which is exactly when this test is
		// needed, and a fixed window would quietly stop covering the new lines.
		int until = src.indexOf("facetWaiting.clear();", at);
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

	/** And the exceptions have to still exist, or the list is a comment. */
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
