/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ReadmeTest
{
	private static String readme() throws Exception
	{
		return new String(Files.readAllBytes(Paths.get("README.md")),
			StandardCharsets.UTF_8);
	}

	@Test
	public void everyPictureItPointsAtIsActuallyThere() throws Exception
	{
		Matcher m = Pattern.compile("src=\"([^\"]+)\"").matcher(readme());
		List<String> missing = new ArrayList<>();
		int found = 0;
		while (m.find())
		{
			String src = m.group(1);
			if (src.startsWith("http"))
			{
				continue;
			}
			found++;
			if (!Files.isRegularFile(Paths.get(src)))
			{
				missing.add(src);
			}
		}
		assertTrue("the README shows pictures at all", found > 0);
		assertTrue("the Hub would serve a broken image for: " + missing,
			missing.isEmpty());
	}

	@Test
	public void noPictureIsAGif() throws Exception
	{
		Matcher m = Pattern.compile("src=\"([^\"]+)\"").matcher(readme());
		while (m.find())
		{
			assertTrue("the Hub turns a gif into a link: " + m.group(1),
				!m.group(1).toLowerCase(java.util.Locale.ROOT).endsWith(".gif"));
		}
	}

	@Test
	public void itNamesTheTabsThePanelActuallyCarries() throws Exception
	{
		String text = readme();
		for (String tab : new String[]{"Record", "Standing", "Loot", "Trackers"})
		{
			assertTrue("the README never mentions the " + tab + " tab",
				text.contains(tab));
		}
		for (String gone : new String[]{"### PvM", "### Skilling", "### Collection log",
			"### Hiscores"})
		{
			assertTrue("the README still has a section for a tab that is gone: " + gone,
				!text.contains(gone));
		}
	}

	@Test
	public void theTwoVersionsAgree() throws Exception
	{
		java.util.Properties hub = new java.util.Properties();
		try (java.io.InputStream in = Files.newInputStream(
			Paths.get("runelite-plugin.properties")))
		{
			hub.load(in);
		}
		java.util.Properties build = new java.util.Properties();
		try (java.io.InputStream in = Files.newInputStream(Paths.get("gradle.properties")))
		{
			build.load(in);
		}
		String shown = hub.getProperty("version");
		String jar = build.getProperty("version");
		assertTrue("the Hub has no version to show", shown != null && !shown.isEmpty());
		assertTrue("the jar has no version", jar != null && !jar.isEmpty());
		assertTrue("the Hub shows " + shown + " and the jar is built as " + jar,
			jar.equals(shown) || jar.startsWith(shown + "."));
	}
}
