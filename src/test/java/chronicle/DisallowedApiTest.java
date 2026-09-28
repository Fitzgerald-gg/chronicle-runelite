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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class DisallowedApiTest
{
	private static Pattern constructorOf(String simpleName)
	{
		return Pattern.compile("\\bnew\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*"
			+ Pattern.quote(simpleName) + "\\s*(?:<[^>]*>\\s*)?\\(");
	}

	private static final String[][] BANNED = {
		{"Gson", "inject RuneLite's Gson and pass it in, as bossRoster and taxonomy do"},
		{"GsonBuilder", "inject RuneLite's Gson and reconfigure it with newBuilder()"},
		{"OkHttpClient", "inject RuneLite's OkHttpClient"},
	};

	private static List<Path> mainSources() throws IOException
	{
		try (Stream<Path> walk = Files.walk(Paths.get("src/main/java")))
		{
			List<Path> out = new ArrayList<>();
			walk.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
			return out;
		}
	}

	private static String code(String src)
	{
		return src.replaceAll("(?s)/\\*.*?\\*/", " ")
			.replaceAll("(?m)//.*$", " ")
			.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
	}

	@Test
	public void nothingInTheShippedSourceBuildsItsOwnGsonOrHttpClient() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (Path f : mainSources())
		{
			String src = code(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
			for (String[] ban : BANNED)
			{
				Matcher m = constructorOf(ban[0]).matcher(src);
				while (m.find())
				{
					int line = 1;
					for (int i = 0; i < m.start(); i++)
					{
						if (src.charAt(i) == '\n')
						{
							line++;
						}
					}
					bad.add(f + ":" + line + "  " + m.group().trim() + "  -> " + ban[1]);
				}
			}
		}
		assertTrue("the Plugin Hub packager rejects these:\n  "
			+ String.join("\n  ", bad), bad.isEmpty());
	}

	@Test
	public void theShippedSourceTouchesNoRemovedOrForbiddenClientApi() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (Path f : mainSources())
		{
			String src = code(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
			for (String api : new String[]{"WidgetInfo", "java.awt.Desktop"})
			{
				if (Pattern.compile("\\b" + Pattern.quote(api) + "\\b").matcher(src).find())
				{
					bad.add(f + " uses " + api);
				}
			}
		}
		assertTrue(String.join("\n  ", bad), bad.isEmpty());
	}

	@Test
	public void noCaseFoldingHappensInWhicheverLocaleTheClientRunsIn() throws Exception
	{
		Pattern bare = Pattern.compile("\\.to(?:Lower|Upper)Case\\s*\\(\\s*\\)");
		List<String> bad = new ArrayList<>();
		for (Path f : mainSources())
		{
			String src = code(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
			Matcher m = bare.matcher(src);
			while (m.find())
			{
				int line = 1;
				for (int i = 0; i < m.start(); i++)
				{
					if (src.charAt(i) == '\n')
					{
						line++;
					}
				}
				bad.add(f + ":" + line + "  " + m.group().trim());
			}
		}
		assertTrue("case folded in the client's locale, not the record's; pass"
			+ " Locale.ROOT:\n  " + String.join("\n  ", bad), bad.isEmpty());
	}
}
