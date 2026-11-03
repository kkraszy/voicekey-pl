package dev.xdark.voicekey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A spoken command, said freely: "otwórz Lampę", "otwórz zwiastun spidermana na youtube",
 * "znajdź w kinopoisku Diuna", "znajdź w vk video recenzję", or just "interstellar".
 *
 * A leading verb is dropped; an app named anywhere ("na youtubie", "w Lampie", "w vk video") is
 * where to search; the rest is the query. An app named with nothing else is opened. With no app
 * named, the query goes to the default app. The recognizer garbles app names ("na jutubie"), so
 * they are matched loosely.
 */
final class Command {
    enum Action { OPEN, SEARCH, UNKNOWN }
    enum Target { YOUTUBE, LAMPA, KINOPOISK, VK, RUTUBE }

    final Action action;
    final Target target;
    final String query;
    /** The app wasn't named: the search goes to the default one. */
    final boolean guessed;

    private Command(Action action, Target target, String query, boolean guessed) {
        this.action = action;
        this.target = target;
        this.query = query;
        this.guessed = guessed;
    }

    private static final Set<String> VERBS = new HashSet<>(Arrays.asList(
            "otwórz", "otworz", "otworzyć", "otwórzcie", "uruchom", "uruchomić", "włącz", "wlacz", "włączyć",
            "puść", "pusc", "znajdź", "znajdz", "znaleźć", "wyszukaj", "szukaj", "poszukaj", "wyszukać",
            "pokaż", "pokaz", "pokazać", "chcę", "chce", "obejrzeć", "obejrzec", "zobaczyć", "oglądać"));
    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList("proszę", "prosze", "mi"));
    private static final Set<String> PREPOSITIONS = new HashSet<>(Arrays.asList("na", "w", "we"));

    /**
     * The best of the recognizer's alternatives: the likeliest that names an app, as names get
     * garbled ("na jutubie") more than the rest; else the likeliest of all.
     */
    static Command best(List<String> heard, Target fallback) {
        Command first = null;
        for (String phrase : heard) {
            Command c = parse(phrase, fallback);
            if (first == null) first = c;
            if (c.action != Action.UNKNOWN && !c.guessed) return c;
        }
        return first;
    }

    static Command parse(String phrase, Target fallback) {
        // The recognizer may punctuate: "Znajdź na YouTube: „Diuna”." Hyphens and dots inside words stay.
        String cleaned = phrase.replaceAll("[:;,!?«»„\"“”]", " ").trim()
                .replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        List<String> words = new ArrayList<>(Arrays.asList(cleaned.split("\\s+")));
        words.removeIf(String::isEmpty);
        List<String> low = new ArrayList<>();
        for (String w : words) low.add(normalize(w));
        joinSplitNames(words, low);

        // Leading verbs and fillers: "otwórz", "znajdź mi", "chcę obejrzeć".
        while (!low.isEmpty() && (VERBS.contains(low.get(0)) || FILLERS.contains(low.get(0)))) {
            words.remove(0);
            low.remove(0);
        }
        // "znajdź proszę na youtube …": fillers go from the middle too, from both lists alike.
        for (int i = low.size() - 1; i >= 0; i--) {
            if (FILLERS.contains(low.get(i))) {
                words.remove(i);
                low.remove(i);
            }
        }

        Target named = null;
        for (int i = 0; i < low.size(); i++) {
            Target t = appOf(low.get(i));
            if (t == null) continue;
            boolean afterPreposition = i > 0 && PREPOSITIONS.contains(low.get(i - 1));
            // An app right after the verb, or the whole phrase. Elsewhere only "na/w …", so
            // "Aladyn i czarodziejska lampa" stays a title.
            if (afterPreposition || i == 0) {
                named = t;
                int from = afterPreposition ? i - 1 : i;
                int to = i + 1;
                // "vk video", "wk wideo": the second word is part of the name.
                if (t == Target.VK && to < low.size() && (low.get(to).equals("wideo") || low.get(to).equals("video"))) to++;
                words.subList(from, to).clear();
                low.subList(from, to).clear();
                break;
            }
        }

        String query = String.join(" ", words).trim();
        if (named != null) {
            return query.isEmpty()
                    ? new Command(Action.OPEN, named, "", false)
                    : new Command(Action.SEARCH, named, query, false);
        }
        if (query.isEmpty()) return new Command(Action.UNKNOWN, null, cleaned, false);
        return new Command(Action.SEARCH, fallback, query, true);
    }

    /** Lower case. */
    private static String normalize(String word) {
        return word.toLowerCase(Locale.forLanguageTag("pl"));
    }

    /** "you tube", "ju tub", "kino poisk", "ru tube" spoken apart → one word. */
    private static void joinSplitNames(List<String> words, List<String> low) {
        for (int i = 0; i + 1 < low.size(); i++) {
            String a = low.get(i), b = low.get(i + 1);
            boolean join = ((a.equals("you") || a.equals("ju")) && (b.startsWith("tub") || b.startsWith("tjub")))
                    || (a.equals("kino") && (b.startsWith("poisk") || b.startsWith("pojsk")))
                    || (a.equals("ru") && (b.startsWith("tub") || b.startsWith("tjub")));
            if (join) {
                words.set(i, words.get(i) + words.get(i + 1));
                words.remove(i + 1);
                low.set(i, a + b);
                low.remove(i + 1);
            }
        }
    }

    static Target appOf(String word) {
        if (word.startsWith("youtub")) return Target.YOUTUBE;
        if (word.equals("lampa")) return Target.LAMPA;
        if (word.startsWith("rutub")) return Target.RUTUBE;
        if (word.startsWith("kinopoisk")) return Target.KINOPOISK;
        if (word.equals("vk") || word.equals("wk") || word.startsWith("vkontakt") || word.startsWith("wkontakt"))
            return Target.VK;
        // "youtubie", "lampie", "kinopoisku": Polish case endings off.
        String stem = word.replaceAll("[aeiouyąęó]+$", "");
        if (stem.length() >= 4 && (distance(stem, "jutub") <= 1 || distance(stem, "jutjub") <= 1)) return Target.YOUTUBE;
        if (stem.length() >= 4 && (distance(stem, "rutub") <= 1 || distance(stem, "rutjub") <= 1)) return Target.RUTUBE;
        if (stem.length() >= 7 && distance(stem, "kinopoisk") <= 2) return Target.KINOPOISK;
        if (stem.equals("lamp") || stem.equals("lampk")) return Target.LAMPA;
        return null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    @Override
    public String toString() {
        return action + " " + target + (guessed ? "?" : "") + " „" + query + "”";
    }
}
