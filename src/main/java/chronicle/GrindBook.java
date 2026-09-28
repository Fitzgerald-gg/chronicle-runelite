/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class GrindBook
{
	private static final int MIN_DRY_RATE = 100;
	private static final int MAX_ROWS = 20;

	private final Gson gson;

	private volatile Map<String, Map<String, Integer>> drops;

	private Map<String, Map<String, Integer>> book()
	{
		Map<String, Map<String, Integer>> loaded = drops;
		if (loaded != null)
		{
			return loaded;
		}
		Map<String, Map<String, Integer>> out = new HashMap<>();
		try (InputStream in = GrindBook.class.getResourceAsStream("/chronicle/osrs_clog_rates.json"))
		{
			if (in != null)
			{
				JsonObject root = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
				JsonObject bosses = root.has("drops") && root.get("drops").isJsonObject()
					? root.getAsJsonObject("drops") : new JsonObject();
				for (Map.Entry<String, JsonElement> b : bosses.entrySet())
				{
					if (!b.getValue().isJsonObject())
					{
						continue;
					}
					Map<String, Integer> items = new HashMap<>();
					for (Map.Entry<String, JsonElement> it : b.getValue().getAsJsonObject().entrySet())
					{
						try
						{
							items.put(it.getKey(), it.getValue().getAsInt());
						}
						catch (RuntimeException ignored)
						{
						}
					}
					out.put(b.getKey(), items);
				}
			}
		}
		catch (Exception e)
		{
			log.debug("rate book load failed", e);
		}
		drops = out;
		return out;
	}

	private static String norm(String s)
	{
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static Set<String> pageFor(String key, Map<String, Set<String>> pageItems)
	{
		Set<String> page = pageItems.get(norm(key));
		if (page != null)
		{
			return page;
		}
		int open = key.indexOf('(');
		int close = key.lastIndexOf(')');
		if (open >= 0 && close > open + 1)
		{
			return pageItems.get(norm(key.substring(open + 1, close)));
		}
		return null;
	}

	List<GrindRow> grinds(JsonObject clog,
		List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Map<String, Integer>> rates = book();
		if (rates.isEmpty())
		{
			return new ArrayList<>();
		}
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
		Set<String> obtained = clogItems(clog);
		obtained.addAll(looted(dropSources));
		Map<String, Set<String>> pageItems = new HashMap<>();
		JsonObject byCat = obj(clog, "by_cat");
		if (byCat != null)
		{
			for (Map.Entry<String, JsonElement> e : byCat.entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				Set<String> names = new HashSet<>();
				for (Map.Entry<String, JsonElement> it : e.getValue().getAsJsonObject().entrySet())
				{
					names.add(it.getKey().toLowerCase(Locale.ROOT));
				}
				pageItems.put(norm(e.getKey()), names);
			}
		}

		List<GrindRow> out = new ArrayList<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			Set<String> page = pageFor(boss.getKey(), pageItems);
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				int rate = item.getValue() != null ? item.getValue() : 0;
				String li = item.getKey().toLowerCase(Locale.ROOT);
				boolean got = obtained.contains(li) || (page != null && page.contains(li));
				if (got || rate < MIN_DRY_RATE)
				{
					continue;
				}
				double pct = (1.0 - Math.pow(1.0 - 1.0 / rate, kc)) * 100.0;
				out.add(new GrindRow(boss.getKey(), item.getKey(),
					kc, rate, Math.round(pct * 10.0) / 10.0));
			}
		}
		out.sort((a, b) ->
		{
			int byDry = Double.compare(b.percentileDry, a.percentileDry);
			return byDry != 0 ? byDry : Long.compare(b.rate, a.rate);
		});
		return out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out;
	}

	private static Map<String, Long> killCounts(JsonObject clog,
		List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Long> kcByNorm = new HashMap<>();
		JsonObject kcs = obj(clog, "kcs");
		if (kcs != null)
		{
			for (Map.Entry<String, JsonElement> e : kcs.entrySet())
			{
				long v = safeLong(e.getValue());
				if (v > 0)
				{
					kcByNorm.merge(norm(e.getKey()), v, Math::max);
				}
			}
		}
		if (dropSources != null)
		{
			for (LocalStore.SourceRow sr : dropSources)
			{
				if (sr.kc > 0)
				{
					kcByNorm.merge(norm(sr.name), (long) sr.kc, Math::max);
				}
			}
		}
		return kcByNorm;
	}

	private static final long MAX_KC = 100_000_000L;

	private static final int LEVEL_CAP = 99;
	private static final long MIN_RATE = 2;

	@RequiredArgsConstructor
	static final class PetSource
	{
		final String boss;
		final long kc;
		final long rate;
	}

	@RequiredArgsConstructor
	static final class PetChase
	{
		final String pet;
		final long kc;
		final double percentileDry;
		final List<PetSource> sources;
		final String activity;
		final String unit;
		final long level;

		PetChase(String pet, long kc, double percentileDry, List<PetSource> sources)
		{
			this(pet, kc, percentileDry, sources, null, null, 0);
		}
	}

	Map<String, PetChase> petChases(JsonObject clog, List<LocalStore.SourceRow> dropSources,
		Map<String, Long> counters, Map<String, long[]> skills, JsonObject achievements,
		Collection<String> pets)
	{
		Map<String, PetChase> out = new LinkedHashMap<>();
		if (pets == null || pets.isEmpty())
		{
			return out;
		}
		Map<String, Map<String, Integer>> rates = book();
		Map<String, SkillPet> skilling = skillBook();
		if (rates.isEmpty() && skilling.isEmpty())
		{
			return out;
		}
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
		Set<String> obtained = allObtained(clog);
		obtained.addAll(looted(dropSources));
		Map<String, List<PetSource>> bySource = new HashMap<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				long rate = item.getValue() != null ? item.getValue() : 0;
				if (rate <= 0)
				{
					continue;
				}
				bySource.computeIfAbsent(item.getKey().toLowerCase(Locale.ROOT),
					k -> new ArrayList<>())
					.add(new PetSource(boss.getKey(), Math.min(kc, MAX_KC), rate));
			}
		}
		for (String pet : pets)
		{
			if (pet == null)
			{
				continue;
			}
			String key = pet.toLowerCase(Locale.ROOT);
			if (obtained.contains(key))
			{
				continue;
			}
			SkillPet spec = skilling.get(key);
			if (spec != null)
			{
				if (spec.requires != null && !spec.requires.met(achievements))
				{
					continue;
				}
				PetChase chase = skillingChase(pet, spec, counters, skills, kcByNorm);
				if (chase != null)
				{
					out.put(key, chase);
				}
				continue;
			}
			List<PetSource> src = bySource.get(key);
			if (src == null)
			{
				continue;
			}
			List<PetSource> sorted = new ArrayList<>(src);
			sorted.sort((a, b) -> Long.compare(b.kc, a.kc));
			out.put(key, chase(pet, sorted, null, null, 0));
		}
		return out;
	}

	private static PetChase chase(String pet, List<PetSource> sources, String activity,
		String unit, long level)
	{
		double miss = 1.0;
		long kc = 0;
		for (PetSource s : sources)
		{
			miss *= Math.pow(1.0 - 1.0 / s.rate, s.kc);
			kc += s.kc;
		}
		sources.sort((a, b) -> Long.compare(b.kc, a.kc));
		double pct = Math.max(0.0, Math.min(100.0, (1.0 - miss) * 100.0));
		return new PetChase(pet, kc, Math.round(pct * 10.0) / 10.0, sources, activity, unit,
			level);
	}

	@RequiredArgsConstructor
	private static final class SkillSource
	{
		final String counter;
		final String suffix;
		final String notSuffix;
		final List<String> minus;
		final String name;
		final long base;
	}

	@RequiredArgsConstructor
	private static final class SkillKill
	{
		final String kc;
		final List<String> orKc;
		final String name;
		final long base;
		final boolean flat;
	}

	@RequiredArgsConstructor
	private static final class Requirement
	{
		final String diaryRegion;
		final String diaryTier;
		final String quest;
		final String combatTier;

		boolean met(JsonObject achievements)
		{
			if (achievements == null)
			{
				return false;
			}
			if (diaryRegion != null
				&& !flag(obj(obj(achievements, "diaries"), diaryRegion), diaryTier))
			{
				return false;
			}
			if (quest != null
				&& !"FINISHED".equals(text(obj(achievements, "quests"), quest)))
			{
				return false;
			}
			return combatTier == null
				|| safeLong(field(obj(obj(achievements, "combat"), "tiers"), combatTier)) > 0;
		}

		private static JsonElement field(JsonObject o, String key)
		{
			return o != null && key != null && o.has(key) ? o.get(key) : null;
		}

		private static boolean flag(JsonObject o, String key)
		{
			JsonElement el = field(o, key);
			try
			{
				return el != null && el.isJsonPrimitive() && el.getAsBoolean();
			}
			catch (RuntimeException ignored)
			{
				return false;
			}
		}

		private static String text(JsonObject o, String key)
		{
			JsonElement el = field(o, key);
			return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
		}
	}

	private static final class SkillPet
	{
		final String skill;
		final String activity;
		final String unit;
		final boolean levelScaled;
		final List<SkillSource> sources;
		final List<SkillKill> kills;
		final Requirement requires;
		final Set<String> named;

		SkillPet(String skill, String activity, String unit, boolean levelScaled,
			List<SkillSource> sources, List<SkillKill> kills, Requirement requires)
		{
			this.skill = skill;
			this.activity = activity;
			this.unit = unit;
			this.levelScaled = levelScaled;
			this.sources = sources;
			this.kills = kills;
			this.requires = requires;
			this.named = new HashSet<>();
			for (SkillSource s : sources)
			{
				if (s.counter != null)
				{
					this.named.add(s.counter);
				}
			}
		}
	}

	private volatile Map<String, SkillPet> skillPets;

	private Map<String, SkillPet> skillBook()
	{
		Map<String, SkillPet> loaded = skillPets;
		if (loaded != null)
		{
			return loaded;
		}
		Map<String, SkillPet> out = new HashMap<>();
		try (InputStream in = GrindBook.class.getResourceAsStream(
			"/chronicle/osrs_skilling_pet_rates.json"))
		{
			if (in != null)
			{
				JsonObject root = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
				JsonObject pets = obj(root, "pets");
				if (pets != null)
				{
					for (Map.Entry<String, JsonElement> e : pets.entrySet())
					{
						if (!e.getValue().isJsonObject())
						{
							continue;
						}
						SkillPet pet = readSkillPet(e.getValue().getAsJsonObject());
						if (pet != null)
						{
							out.put(e.getKey().toLowerCase(Locale.ROOT), pet);
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("skilling pet rate book load failed", e);
		}
		skillPets = out;
		return out;
	}

	private static SkillPet readSkillPet(JsonObject o)
	{
		List<SkillSource> sources = new ArrayList<>();
		if (o.has("sources") && o.get("sources").isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray("sources"))
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject s = el.getAsJsonObject();
				long base = safeLong(s.get("base"));
				if (base <= 0)
				{
					continue;
				}
				sources.add(new SkillSource(str(s, "counter"), str(s, "suffix"),
					str(s, "notSuffix"), strings(s, "minus"), str(s, "name"), base));
			}
		}
		List<SkillKill> kills = new ArrayList<>();
		if (o.has("kills") && o.get("kills").isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray("kills"))
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject k = el.getAsJsonObject();
				long base = safeLong(k.get("base"));
				if (base <= 0 || str(k, "kc") == null)
				{
					continue;
				}
				kills.add(new SkillKill(str(k, "kc"), strings(k, "orKc"), str(k, "name"), base,
					k.has("flat") && k.get("flat").getAsBoolean()));
			}
		}
		if (sources.isEmpty() && kills.isEmpty())
		{
			return null;
		}
		return new SkillPet(str(o, "skill"), str(o, "activity"), str(o, "unit"),
			o.has("levelScaled") && o.get("levelScaled").getAsBoolean(), sources, kills,
			readRequirement(o));
	}

	private static Requirement readRequirement(JsonObject o)
	{
		JsonObject r = obj(o, "requires");
		if (r == null)
		{
			return null;
		}
		JsonObject diary = obj(r, "diary");
		JsonObject quest = obj(r, "quest");
		JsonObject combat = obj(r, "combat");
		String region = diary != null ? nz(str(diary, "region")) : null;
		String tier = diary != null ? nz(str(diary, "tier")) : null;
		String name = quest != null ? nz(str(quest, "name")) : null;
		String caTier = combat != null ? nz(str(combat, "tier")) : null;
		if (region == null && name == null && caTier == null)
		{
			return null;
		}
		return new Requirement(region, tier, name, caTier);
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o != null && key != null && o.has(key) && o.get(key).isJsonObject()
			? o.getAsJsonObject(key) : null;
	}

	private static String nz(String s)
	{
		return s != null ? s : "";
	}

	private static List<String> strings(JsonObject o, String key)
	{
		List<String> out = new ArrayList<>();
		if (o.has(key) && o.get(key).isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray(key))
			{
				out.add(el.getAsString());
			}
		}
		return out;
	}

	private static String str(JsonObject o, String field)
	{
		return o.has(field) && o.get(field).isJsonPrimitive()
			? o.get(field).getAsString() : null;
	}

	private PetChase skillingChase(String pet, SkillPet spec, Map<String, Long> counters,
		Map<String, long[]> skills, Map<String, Long> kcByNorm)
	{
		long level = 0;
		if (spec.levelScaled)
		{
			long[] sheet = skills != null && spec.skill != null
				? skills.get(spec.skill) : null;
			level = sheet != null && sheet.length > 0 ? sheet[0] : 0;
			if (level <= 0)
			{
				return null;
			}
			level = Math.min(level, LEVEL_CAP);
		}
		List<PetSource> sources = new ArrayList<>();
		for (SkillSource s : spec.sources)
		{
			source(sources, s.name, Math.min(count(s, spec, counters), MAX_KC),
				spec.levelScaled ? s.base - 25L * level : s.base);
		}
		for (SkillKill k : spec.kills)
		{
			source(sources, k.name, Math.min(killCount(k, kcByNorm), MAX_KC),
				spec.levelScaled && !k.flat ? k.base - 25L * level : k.base);
		}
		return sources.isEmpty() ? null : chase(pet, sources, spec.activity, spec.unit, level);
	}

	private static void source(List<PetSource> out, String name, long n, long rate)
	{
		if (n > 0 && rate >= MIN_RATE)
		{
			out.add(new PetSource(name, n, rate));
		}
	}

	private static long killCount(SkillKill k, Map<String, Long> kcByNorm)
	{
		Long kc = kcByNorm.get(norm(k.kc));
		long n = kc != null ? kc : 0;
		for (String alt : k.orKc)
		{
			Long other = kcByNorm.get(norm(alt));
			if (other != null)
			{
				n = Math.max(n, other);
			}
		}
		return n;
	}

	private static long count(SkillSource s, SkillPet spec, Map<String, Long> counters)
	{
		if (counters == null || counters.isEmpty())
		{
			return 0;
		}
		if (s.suffix != null)
		{
			long n = 0;
			for (Map.Entry<String, Long> e : counters.entrySet())
			{
				String k = e.getKey();
				if (!k.endsWith(s.suffix)
					|| (s.notSuffix != null && k.endsWith(s.notSuffix))
					|| spec.named.contains(k))
				{
					continue;
				}
				n += Math.max(0L, e.getValue() != null ? e.getValue() : 0L);
			}
			return n;
		}
		if (s.counter == null)
		{
			return 0;
		}
		long n = counters.getOrDefault(s.counter, 0L);
		for (String m : s.minus)
		{
			n -= counters.getOrDefault(m, 0L);
		}
		return Math.max(0L, n);
	}

	private static Set<String> clogItems(JsonObject clog)
	{
		Set<String> out = new HashSet<>();
		JsonObject items = obj(clog, "clog_items");
		if (items != null)
		{
			for (Map.Entry<String, JsonElement> e : items.entrySet())
			{
				out.add(e.getKey().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	private static Set<String> allObtained(JsonObject clog)
	{
		Set<String> out = clogItems(clog);
		JsonObject byCat = obj(clog, "by_cat");
		if (byCat != null)
		{
			for (Map.Entry<String, JsonElement> cat : byCat.entrySet())
			{
				if (!cat.getValue().isJsonObject())
				{
					continue;
				}
				for (Map.Entry<String, JsonElement> it : cat.getValue().getAsJsonObject().entrySet())
				{
					out.add(it.getKey().toLowerCase(Locale.ROOT));
				}
			}
		}
		return out;
	}

	private static Set<String> looted(List<LocalStore.SourceRow> dropSources)
	{
		Set<String> out = new HashSet<>();
		if (dropSources != null)
		{
			for (LocalStore.SourceRow sr : dropSources)
			{
				if (sr.looted != null)
				{
					out.addAll(sr.looted);
				}
			}
		}
		return out;
	}

	private static long safeLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	@RequiredArgsConstructor
	public static final class GrindRow
	{
		public final String boss;
		public final String item;
		public final long kc;
		public final long rate;
		public final double percentileDry;
	}
}
