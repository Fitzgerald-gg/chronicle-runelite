/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import static chronicle.Json.*;

@Slf4j
final class JournalFile
{
	private JournalFile()
	{
	}

	static boolean migrateJournalFiles(File dir, String oldName, String newName)
	{
		String oldSlug = slug(oldName);
		String newSlug = slug(newName);
		if (oldSlug.equals(newSlug))
		{
			return false;
		}
		File journal = new File(dir, oldSlug + ".json");
		if (!journal.isFile())
		{
			return false;
		}
		File target = new File(dir, newSlug + ".json");
		if (target.exists() && !setAside(target, "conflict"))
		{
			return false;
		}
		if (!journal.renameTo(target))
		{
			log.warn("journal rename failed: {} -> {}", journal, target);
			return false;
		}
		File history = new File(dir, oldSlug + HistoryLog.SPINE_SUFFIX);
		File historyTarget = new File(dir, newSlug + HistoryLog.SPINE_SUFFIX);
		if (history.isFile() && (!historyTarget.exists() || setAside(historyTarget, "conflict"))
			&& !history.renameTo(historyTarget))
		{
			log.warn("history rename failed: {} -> {}", history, historyTarget);
		}
		return true;
	}

	static boolean setAside(File f, String tag)
	{
		File aside = new File(f.getParentFile(), f.getName() + "." + tag + "-" + System.currentTimeMillis());
		try
		{
			Files.move(f.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log.warn("kept {} as {}", f.getName(), aside.getName());
			return true;
		}
		catch (Exception e)
		{
			log.warn("could not set aside {}", f, e);
			return false;
		}
	}

	static String slug(String rsn)
	{
		String s = rsn == null ? "" : rsn.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		s = s.replaceAll("(^-+|-+$)", "");
		return s.isEmpty() ? "profile" : s;
	}

	static File jsonPath(File dir, String rsn)
	{
		return new File(dir, slug(rsn) + ".json");
	}

	static void writeAtomic(File dest, String content) throws IOException
	{
		File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp))
		{
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.getFD().sync();
		}
		try
		{
			Files.move(tmp.toPath(), dest.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicUnsupported)
		{
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
