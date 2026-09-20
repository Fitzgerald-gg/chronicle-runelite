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

/**
 * The things the Plugin Hub's packager rejects a plugin for.
 *
 * <p>Its check runs on the Hub's own runner, against a commit already pushed, and
 * its logs are not readable anonymously, so a rejection arrives as a failure with
 * nothing attached to it. Held here instead, where it costs a second.
 *
 * <p>This looks at the SOURCE rather than at the bytecode, because it is the
 * source that has to be fixed and because the test has to be readable by whoever
 * trips it. Only src/main is examined: the test tree is not packaged, and it
 * legitimately builds its own Gson.
 */
public class DisallowedApiTest
{
	/**
	 * A constructor call, whatever package qualification it is written with.
	 * "new Gson()", "new com.google.gson.Gson()" and "new
	 * com . google . gson . Gson ()" are the same call to the compiler and the
	 * same rejection from the packager.
	 */
	private static Pattern constructorOf(String simpleName)
	{
		return Pattern.compile("\\bnew\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*"
			+ Pattern.quote(simpleName) + "\\s*(?:<[^>]*>\\s*)?\\(");
	}

	private static final String[][] BANNED = {
		// A plugin must take RuneLite's Gson, which is configured for the client's
		// own conventions. This one has escaped twice: once written plainly, and
		// once fully qualified, where it answered no grep for "new Gson()".
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

	/** Strips line comments, block comments and string literals. */
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

	/**
	 * WidgetInfo is removed from the client, and java.awt.Desktop opens the user's
	 * browser or mail client from inside the game, which the Hub does not allow.
	 */
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

	/**
	 * Case folding without a locale.
	 *
	 * <p>This plugin lowercases game text and matches it with contains() against
	 * ASCII literals that are never folded themselves, which is an asymmetric
	 * compare: whether it holds depends on the machine's locale. On a Turkish or
	 * Azeri client String.toLowerCase() turns I into a dotless i, so "Lunar Isle",
	 * "Ice Plateau", "Harmony Island" and "Icy Basalt" stop matching their own
	 * destination keys and those teleports quietly stop being counted.
	 *
	 * <p>The same call also writes into the journal: a combat achievement tier
	 * upper-cased without a locale is persisted as "ELITE" with a dotted capital
	 * I, and stays in the feed for good.
	 *
	 * <p>Every one of these in this plugin is producing a key or a stored value
	 * rather than text for a reader, so Locale.ROOT is right for all of them. The
	 * test is here rather than a comment because it costs one line to reintroduce
	 * and the machine that reintroduces it will never be the machine that notices.
	 */
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
