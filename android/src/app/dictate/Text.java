package app.dictate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Чистка текста без сети — перенос из main.swift (Mac). Правила и их обоснования — в dictate/AGENTS.md, «Грабли». */
final class Text {
    private Text() {}

    /** НЕ добавлять UNICODE_CHARACTER_CLASS / (?U): на Android (ICU) флаг не поддерживается — Pattern.compile бросает
     *  исключение, статический инициализатор падает и клавиатура ломается целиком (11.10.2026). Регулярки Android и так
     *  на ICU: \s ловит NBSP, как на Mac */
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int U = 0;

    // MARK: галлюцинации / пустота

    private static final String[] HALLUCINATIONS = {
            "продолжение следует", "субтитры сделал", "субтитры создавал", "редактор субтитров",
            "спасибо за просмотр", "thank you for watching", "thanks for watching", "подписывайтесь на канал",
    };

    /** Пусто, типовая галлюцинация на тишине или после чистки не осталось ни букв, ни цифр («Эм.») */
    static boolean isEmptyOrHallucination(String raw) {
        String t = raw.toLowerCase().replaceAll("^[\\s\\p{P}]+|[\\s\\p{P}]+$", "");
        if (t.isEmpty()) return true;
        if (t.length() < 60) for (String h : HALLUCINATIONS) if (t.contains(h)) return true;
        String c = localClean(raw);
        for (int i = 0; i < c.length(); i++) if (Character.isLetterOrDigit(c.charAt(i))) return false;
        return true;
    }

    // MARK: «за г.» → «за год» (format=true сокращает «год» и без числа)

    private static final Pattern YEAR_END = Pattern.compile(
            "(?<![\\p{L}])([Чч]ерез|[Зз]а|[Нн]а|[Ээ]тот|[Пп]рошлый|[Сс]ледующий|[Кк]аждый|[Цц]елый|[Вв]есь)(\\s+)г\\.(?=\\s+\\p{Lu}|\\s*$)", U);
    private static final Pattern YEAR_MID = Pattern.compile(
            "(?<![\\p{L}\\d])(?<!\\d[ \\u00a0])г\\.(?=\\s+\\p{Ll}|\\s*[?!,;:])", U);

    static String expandYear(String t) {
        t = YEAR_END.matcher(t).replaceAll("$1$2год.");
        return YEAR_MID.matcher(t).replaceAll("год");
    }

    // MARK: адрес почты голосом («ivan собака example.com»)

    private static final Map<String, String> EMAIL_DOMAIN = new HashMap<>();
    private static final Map<String, String> EMAIL_TLD = new HashMap<>();
    /** Зона своих доменов, если её не произнесли («info собака example» → info@example.com) */
    private static final Map<String, String> EMAIL_DEFAULT_TLD = new HashMap<>();
    private static final Map<Character, String> TRANSLIT = new HashMap<>();
    static {
        // Свои домены: как их произносят → как пишутся ({"экзампл", "example"}); см. handoff/AGENTS.md, «E-mail by voice»
        String[][] d = {{"gmail", "gmail"}, {"джимейл", "gmail"}, {"гмейл", "gmail"}};
        for (String[] x : d) EMAIL_DOMAIN.put(x[0], x[1]);
        String[][] t = {{"ком", "com"}, {"нет", "net"}, {"ру", "ru"}, {"орг", "org"}, {"тр", "tr"}, {"ио", "io"}};
        for (String[] x : t) EMAIL_TLD.put(x[0], x[1]);
        String[][] z = {{"gmail", "com"}};
        for (String[] x : z) EMAIL_DEFAULT_TLD.put(x[0], x[1]);
        String from = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] to = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
                "f", "kh", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};
        for (int i = 0; i < from.length(); i++) TRANSLIT.put(from.charAt(i), to[i]);
    }

    static String translit(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) { String r = TRANSLIT.get(c); b.append(r != null ? r : String.valueOf(c)); }
        return b.toString();
    }

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\d@.])(\\d+(?:[\\s-]+\\d+)+|[\\p{L}\\d][\\p{L}\\d._-]*?)(?:\\s*,?\\s*(?:собака|sobaka)(?![\\p{L}])\\s*|sobaka)[.,\\s]*([\\p{L}\\d][\\p{L}\\d-]*)((?:(?:\\s+точка\\s+|\\.)[\\p{L}\\d-]+)*)", CI);
    private static final Pattern TLD_AFTER = Pattern.compile("^\\s+(ком|нет|ру|com|net|ru)(?![\\p{L}])", CI);

    /** Цифры + gmail без «собака» и «точка»: «123 4567 Gmail com» → 1234567@gmail.com. Только цифры (≥ 5) и только gmail */
    private static final Pattern EMAIL_DIGITS_GMAIL = Pattern.compile(
            "(?<![\\p{L}\\d@.])(\\d+(?:[\\s-]+\\d+)*)[\\s,]+(?:gmail|джимейл|гмейл)(?:(?:\\s*(?:\\.|точка)\\s*|\\s+)(?:com|ком))?(?![\\p{L}\\d@]|\\.[\\p{L}\\d])", CI);

    static String fixEmails(String text) {
        Matcher g = EMAIL_DIGITS_GMAIL.matcher(text);
        StringBuilder pre = new StringBuilder();
        int from = 0;
        while (g.find()) {
            String digits = g.group(1).replaceAll("\\D", "");
            if (digits.length() < 5) continue;
            pre.append(text, from, g.start()).append(digits).append("@gmail.com");
            from = g.end();
        }
        text = pre.append(text.substring(from)).toString();
        StringBuilder out = new StringBuilder();
        Matcher m = EMAIL.matcher(text);
        int last = 0;
        while (m.find()) {
            String local = translit(m.group(1)).replaceAll("(?<=\\d)[\\s-]+(?=\\d)", ""); // «123 4567» — склеить
            String first = m.group(2).toLowerCase();
            if (first.equals("точка")) continue; // «на сайте собака точка ру» — не адрес
            boolean knownDomain = EMAIL_DOMAIN.containsKey(first);
            List<String> parts = new ArrayList<>();
            parts.add(EMAIL_DOMAIN.containsKey(first) ? EMAIL_DOMAIN.get(first) : translit(first));
            for (String piece : m.group(3).replaceAll("(?iu)\\s+точка\\s+", ".").split("\\.")) {
                if (piece.isEmpty()) continue;
                String w = piece.toLowerCase();
                parts.add(EMAIL_TLD.containsKey(w) ? EMAIL_TLD.get(w) : EMAIL_DOMAIN.containsKey(w) ? EMAIL_DOMAIN.get(w) : translit(w));
            }
            String domain = String.join(".", parts);
            int end = m.end();
            // Зона следующим словом без «точка» — только для своих доменов («купил собака корм ру» — не адрес)
            if (!domain.contains(".") && knownDomain) {
                Matcher t = TLD_AFTER.matcher(text.substring(end));
                if (t.find()) {
                    String tld = t.group(1).toLowerCase();
                    domain += "." + (EMAIL_TLD.containsKey(tld) ? EMAIL_TLD.get(tld) : tld);
                    end += t.end();
                }
            }
            if (!domain.contains(".") && EMAIL_DEFAULT_TLD.containsKey(domain)) domain += "." + EMAIL_DEFAULT_TLD.get(domain);
            if (!domain.contains(".")) continue;
            out.append(text, last, m.start()).append(local).append('@').append(domain);
            last = end;
        }
        return out.append(text.substring(last)).toString();
    }

    // MARK: паразиты

    /** Перед ними запятая нужна и после удаления паразита: «Не понял, а, но…» → «Не понял, но…» */
    private static final Set<String> COMMA_CONJ = new HashSet<>(Arrays.asList(
            "но", "а", "что", "чтобы", "потому", "поэтому", "если", "когда", "который", "которая", "которое", "которые",
            "где", "куда", "как", "хотя", "пока", "чем", "ведь", "однако", "зато", "but", "that", "because", "if", "when"));

    /** «э», «эм», «ммм», uh/um — всегда; «а», «у», «м» — только с запятой/многоточием после (союз и предлог её не имеют) */
    private static final Pattern FILLER = Pattern.compile(
            ",?\\s*(?<![\\p{L}\\d'’\\-])(?:э+(?:-э+)*м*|м{2,}|uh+|um+|erm|(?:а+|у+|м)(?![\\p{L}])(?=\\s*(?:,|\\.{2,}|…)))(?![\\p{L}])(?:\\.{2,}|[,…])*\\s*",
            CI);

    /** «...», «…» посреди речи — раздумье, «т.е.» — сокращение: не конец предложения */
    static boolean endsSentence(String t) {
        String s = t.trim();
        if (s.isEmpty()) return true;
        char last = s.charAt(s.length() - 1);
        if (last == '!' || last == '?') return true;
        if (last != '.' || s.endsWith("..")) return false;
        String[] parts = s.split("\\s+");
        String word = parts[parts.length - 1];
        word = word.substring(0, word.length() - 1);
        return word.length() > 1 && !word.contains(".");
    }

    static String localClean(String text) {
        String src = fixEmails(expandYear(text));
        StringBuilder t = new StringBuilder();
        Matcher m = FILLER.matcher(src);
        int last = 0;
        boolean capNext = false;
        while (m.find()) {
            capNext = add(t, src.substring(last, m.start()), capNext);
            last = m.end();
            int e = m.end();
            while (e < src.length() && Character.isLetter(src.charAt(e))) e++;
            String next = src.substring(m.end(), e).toLowerCase();
            if (endsSentence(t.toString())) {
                t.append(' ');
                capNext = true;
            } else if (m.group().contains(",") && COMMA_CONJ.contains(next)) {
                t.append(", ");
            } else {
                t.append(' ');
            }
        }
        add(t, src.substring(last), capNext);
        String r = t.toString()
                .replaceAll("\\s+([,.!?])", "$1")
                .replaceAll(",\\s*,", ",")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("^[\\s,]+|[\\s,]+$", "");
        r = dropStutter(r);
        // Адрес почты в начале фразы с заглавной не пишем
        if (!r.isEmpty() && Character.isLowerCase(r.charAt(0)) && !r.split(" ")[0].contains("@"))
            r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
        return fixEllipsis(fixSlash(r));
    }

    // MARK: запинки, «слэш», «многоточие» — как в main.swift (16.10.2026), менять в обоих местах

    /** «правильной правильной работы» → «правильной работы», «из каких из каких» → «из каких»: группа 1–3 слов подряд без
     *  знака между. Через запятую не трогаем (ломало смысл: «не графика, графика понятна»), это делает LLM. Три раза и
     *  больше — нажим; «да да», «так так» — тоже (STUTTER_KEEP). Убирается ПЕРВАЯ группа: «и всё все хвосты» → «все хвосты» */
    private static final java.util.Set<String> STUTTER_KEEP = new java.util.HashSet<>(java.util.Arrays.asList(
            "да", "нет", "так", "ну", "ага", "угу", "давай", "иди", "хорошо", "ладно", "очень", "много", "еще", "стоп", "тихо",
            "быстрее", "скорее", "давно", "долго", "вот", "там", "тут", "пока", "тук", "bye", "ой", "ох", "ах", "ха", "that", "had"));
    private static final java.util.Set<String> STUTTER_SHORT = new java.util.HashSet<>(java.util.Arrays.asList(
            "я", "он", "мы", "ты", "вы", "на", "по", "за", "из", "но"));
    private static final Pattern STUTTER_WORD = Pattern.compile("\\p{L}+(?:-\\p{L}+)*");

    static String dropStutter(String text) {
        List<int[]> ms = new ArrayList<>();
        List<String> w = new ArrayList<>();
        Matcher m = STUTTER_WORD.matcher(text);
        while (m.find()) { ms.add(new int[]{m.start(), m.end()}); w.add(m.group().toLowerCase().replace('ё', 'е')); }
        List<int[]> cuts = new ArrayList<>();
        int i = 0;
        scan:
        while (i < w.size()) {
            // Серия из 3+ одинаковых групп (любой длины) — нажим, пропускаем целиком: «да да да да» не станет «да да»
            for (int n = 1; n <= 3 && i + 3 * n <= w.size(); n++) {
                int k = 1;
                while (i + (k + 1) * n <= w.size() && w.subList(i + k * n, i + (k + 1) * n).equals(w.subList(i, i + n))) k++;
                if (k >= 3) { i += k * n; continue scan; }
            }
            for (int n = 3; n >= 1; n--) {
                if (i + 2 * n > w.size()) continue;
                if (STUTTER_KEEP.containsAll(w.subList(i, i + n))) continue;
                if (!w.subList(i, i + n).equals(w.subList(i + n, i + 2 * n))) continue;
                if (i >= n && w.subList(i - n, i).equals(w.subList(i, i + n))) continue;
                if (i + 3 * n <= w.size() && w.subList(i + 2 * n, i + 3 * n).equals(w.subList(i, i + n))) continue;
                boolean plain = true;
                for (int k = i; k < i + 2 * n - 1; k++) {
                    String g = text.substring(ms.get(k)[1], ms.get(k + 1)[0]);
                    if (g.isEmpty() || !g.chars().allMatch(c -> c == ' ')) { plain = false; break; }
                }
                if (!plain) continue;
                if (n == 1) {
                    String word = w.get(i);
                    if (STUTTER_KEEP.contains(word) || (word.codePointCount(0, word.length()) < 3 && !STUTTER_SHORT.contains(word))) continue;
                }
                cuts.add(new int[]{ms.get(i)[0], ms.get(i + n)[0]});
                i += 2 * n;
                continue scan;
            }
            i++;
        }
        StringBuilder out = new StringBuilder(text);
        for (int k = cuts.size() - 1; k >= 0; k--) {
            int[] r = cuts.get(k);
            boolean upper = Character.isUpperCase(out.charAt(r[0]));
            out.delete(r[0], r[1]);
            if (upper && r[0] < out.length()) out.setCharAt(r[0], Character.toUpperCase(out.charAt(r[0])));
        }
        return out.toString();
    }

    /** «слэш exit» → «/exit», одно «Слэш» → «/»; «слэш-команда», «слэшем» не трогаем */
    private static final Pattern SLASH = Pattern.compile(
            "(?<![\\p{L}\\d])(?:сл[еэ]ш|slash)(?![\\p{L}])(?![\\s-]+команд)(?:[\\s-]+([\\p{L}\\d][\\p{L}\\d_-]*))?", CI);

    static String fixSlash(String text) {
        Matcher m = SLASH.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String w = m.group(1) == null ? "" : m.group(1);
            boolean ascii = w.chars().allMatch(c -> c < 128);
            m.appendReplacement(out, Matcher.quoteReplacement("/" + (ascii ? w.toLowerCase() : w)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** «Жду многоточие ответа» → «Жду... ответа», «Многоточие, потом» → «... Потом»; знаки вокруг слова съедаются */
    private static final Pattern ELLIPSIS = Pattern.compile("[\\s,.]*(?<![\\p{L}\\d])многоточи[ея](?![\\p{L}])[,.]*\\s*", CI);

    static String fixEllipsis(String text) {
        Matcher m = ELLIPSIS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, m.start() == 0 ? "... " : m.end() >= text.length() ? "..." : "... ");
        m.appendTail(out);
        String r = out.toString();
        if (r.startsWith("... ") && r.length() > 4 && Character.isLowerCase(r.charAt(4)))
            r = r.substring(0, 4) + Character.toUpperCase(r.charAt(4)) + r.substring(5);
        return r.trim();
    }

    /** Возвращает, остался ли флаг «следующее слово с заглавной» невыполненным */
    private static boolean add(StringBuilder t, String piece, boolean capNext) {
        if (capNext) {
            for (int i = 0; i < piece.length(); i++) {
                if (Character.isLetter(piece.charAt(i))) {
                    if (piece.substring(i).split("\\s+")[0].contains("@")) { capNext = false; break; } // адрес почты
                    piece = piece.substring(0, i) + Character.toUpperCase(piece.charAt(i)) + piece.substring(i + 1);
                    capNext = false;
                    break;
                }
            }
        }
        t.append(piece);
        return capNext;
    }

    // MARK: «делаю» → «делай» (пользователь в основном отдаёт распоряжения нейронкам)

    private static final Map<String, String> IMPERATIVES = new HashMap<>();
    static {
        String[][] p = {{"делаю", "делай"}, {"сделаю", "сделай"}, {"переделаю", "переделай"}, {"доделаю", "доделай"},
                {"проверю", "проверь"}, {"посмотрю", "посмотри"}, {"запущу", "запусти"}, {"исправлю", "исправь"},
                {"продолжаю", "продолжай"}, {"начинаю", "начинай"}};
        for (String[] x : p) IMPERATIVES.put(x[0], x[1]);
    }
    private static final Pattern CLAUSE = Pattern.compile("[^.!?;:,\\n]+");
    private static final Pattern WORD = Pattern.compile("(?<![\\p{L}])\\p{L}+(?![\\p{L}])");
    private static final Pattern QUESTION = Pattern.compile("[^.!?]+\\?");
    /** Союзы придаточных: часть фразы, начатая с них, — не команда */
    private static final java.util.Set<String> SUBORDINATORS = new java.util.HashSet<>(java.util.Arrays.asList(
            "что", "чтобы", "как", "когда", "если", "пока", "раз", "хотя", "потому", "поскольку", "где", "куда",
            "откуда", "ведь", "чем", "будто", "словно", "который", "которая", "которое", "которые", "кто", "ли"));

    /** Первое лицо без «я» в той же части фразы → повелительное; вопросы («Начинаю…?») не трогаем */
    static String fixImperatives(String text) {
        List<int[]> questions = new ArrayList<>();
        Matcher q = QUESTION.matcher(text);
        while (q.find()) questions.add(new int[]{q.start(), q.end()});
        StringBuilder out = new StringBuilder();
        Matcher c = CLAUSE.matcher(text);
        int last = 0;
        while (c.find()) {
            out.append(text, last, c.start());
            last = c.end();
            String part = c.group();
            boolean inQuestion = false;
            for (int[] r : questions) if (r[0] < c.end() && c.start() < r[1]) { inQuestion = true; break; }
            boolean hasI = false;
            Matcher w = WORD.matcher(part);
            while (w.find()) if (w.group().equalsIgnoreCase("я")) { hasI = true; break; }
            if (inQuestion || hasI) { out.append(part); continue; }
            // Придаточное («я говорил тебе, что делаю то же самое») — не приказ: 16.10.2026 «делаю» → «делай» перевернуло смысл перевода
            Matcher f = WORD.matcher(part);
            if (f.find() && SUBORDINATORS.contains(f.group().toLowerCase())) { out.append(part); continue; }
            StringBuilder fixed = new StringBuilder();
            w = WORD.matcher(part);
            int pl = 0;
            while (w.find()) {
                String to = IMPERATIVES.get(w.group().toLowerCase());
                if (to == null) continue;
                fixed.append(part, pl, w.start());
                fixed.append(Character.isUpperCase(w.group().charAt(0)) ? Character.toUpperCase(to.charAt(0)) + to.substring(1) : to);
                pl = w.end();
            }
            fixed.append(part.substring(pl));
            out.append(fixed);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    // MARK: замены из config.json

    static String applyReplacements(String text, Store.Config cfg) {
        String r = text;
        for (Map.Entry<String, String> e : cfg.replacements.entrySet()) {
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}])", CI);
            r = p.matcher(r).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return r;
    }

    /** «вот, держи ссылку на MyShop» → «…ссылку на MyShop: https://…» — только по явному сигналу и только для
     *  проектов из links (config.json); язык ссылки — по режиму (RU→EN → en). Как insertLinks в main.swift */
    static String insertLinks(String text, boolean english, Store.Config cfg) {
        String out = text;
        List<Map.Entry<String, Map<String, String>>> entries = new ArrayList<>(cfg.links.entrySet());
        // Длинные имена первыми и в одном порядке с Mac («MyShop Pro» раньше «MyShop»)
        entries.sort((a, b) -> a.getKey().length() != b.getKey().length() ? b.getKey().length() - a.getKey().length() : a.getKey().compareTo(b.getKey()));
        for (Map.Entry<String, Map<String, String>> e : entries) {
            Map<String, String> urls = e.getValue();
            String url = urls.get(english ? "en" : "ru");
            if (url == null || url.isEmpty()) url = urls.get(english ? "ru" : "en");
            if (url == null || url.isEmpty() || out.contains(url)) continue;
            Pattern p = Pattern.compile("(?<![\\p{L}])(?:вот|держи|лови|кидаю|скидываю|отправляю|прикладываю)(?:[\\s,]+[\\p{L}-]+){0,3}?[\\s,]+(?:ссылк[аиуойе]*|линк)[^.!?]*?(?<![\\p{L}])на\\s+(?:сайт\\s+|проект\\s+)?"
                    + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}])", CI);
            Matcher m = p.matcher(out);
            if (m.find()) out = out.substring(0, m.end()) + ": " + url + out.substring(m.end());
        }
        return out;
    }

    private static final Pattern URL_OR_EMAIL = Pattern.compile("(?:https?://|www\\.)\\S+|[\\p{L}\\d._%+-]+@[\\p{L}\\d.-]+\\.\\p{L}{2,}", CI);
    private static final String[] COMMANDS = {"`", "$(", "curl ", "wget ", "sudo ", "rm -rf", "| sh", "|sh", "| bash", "chmod ", "eval "};

    // MARK: оформление нейронкой (13.10.2026, пользователь) — копия main.swift

    private static final Pattern SELF_CORRECTION = Pattern.compile("(?<![\\p{L}])(?:точнее|вернее|то есть нет|ой|стоп|в смысле|i mean|no wait|sorry|scratch that|actually no)(?![\\p{L}])", CI);
    // «нет, не» и «я имею в виду» убраны 17.10.2026 (решение, как в main.swift): чаще не поправка, а +~1 с
    static boolean hasSelfCorrection(String t) { return SELF_CORRECTION.matcher(t).find(); }

    /** Конец предложения внутри текста: знак, пробел, дальше ещё текст. «1.5», почта и URL пробела после точки не имеют */
    private static final Pattern SENTENCE_END = Pattern.compile("([\\p{L}\\p{N}]*)[»\")]?(?:[?!]+[.…]*|[.…]+)(?=\\s+[«\"(]?([\\p{L}\\p{N}]))");
    private static final java.util.Set<String> ABBR = new java.util.HashSet<>(java.util.Arrays.asList(
            "др", "пр", "см", "ср", "напр", "стр", "руб", "тыс", "млн", "млрд", "ул", "им", "кв", "мин", "сек", "etc", "vs", "e.g", "i.e", "mr", "mrs", "dr"));

    static int sentenceCount(String t) {
        int ends = 0;
        Matcher m = SENTENCE_END.matcher(t);
        while (m.find()) {
            String g = m.group();
            if (g.indexOf('?') >= 0 || g.indexOf('!') >= 0) { ends++; continue; }
            String word = m.group(1).toLowerCase();
            if (word.length() == 1 && Character.isLetter(word.charAt(0)) || ABBR.contains(word)) continue;
            char next = m.group(2).charAt(0);
            if (Character.isUpperCase(next) || Character.isDigit(next)) ends++;
        }
        return ends + 1;
    }

    /** Нейронка нужна: самоисправления; 2+ предложения или ≥ 150 символов. Короткая одиночная фраза — без неё (скорость) */
    static boolean needsLLM(String t) {
        return t.codePointCount(0, t.length()) >= 150 || hasSelfCorrection(t) || sentenceCount(t) >= 2;
    }

    static java.util.List<String> comparableWords(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : s.toLowerCase().replace('ё', 'е').split("[^\\p{L}\\p{N}]+"))
            if (!w.isEmpty() && w.codePoints().anyMatch(Character::isLetter)) out.add(w);
        return out;
    }

    private static final java.util.Set<String> CLEANUP_ALLOWED = new java.util.HashSet<>(java.util.Arrays.asList(
            "то", "есть", "так", "далее", "и", "тому", "подобное", "прочее", "что", "чем", "сейчас"));

    /** Нейронка не переписала текст? Оформлять можно, менять слова — нет (пороги — как на Mac, по history.jsonl 13.10). null — ок */
    static String cleanupRewriteProblem(String input, String output, Store.Config cfg, Store.Memory mem) {
        java.util.List<String> in = comparableWords(input), out = comparableWords(output);
        if (in.size() < 5 || out.isEmpty()) return null;
        java.util.Set<String> known = new java.util.HashSet<>(in);
        java.util.List<String> extra = new java.util.ArrayList<>(cfg.dictionary);
        extra.addAll(cfg.replacements.values());
        extra.addAll(mem.terms);
        for (String e : extra) known.addAll(comparableWords(e));
        java.util.Set<String> stems = new java.util.HashSet<>();
        for (String k : known) if (k.length() >= 5) stems.add(k.substring(0, 5));
        int foreign = 0;
        for (String w : out)
            if (!known.contains(w) && !CLEANUP_ALLOWED.contains(w) && !(w.length() >= 5 && stems.contains(w.substring(0, 5)))) foreign++;
        double share = (double) foreign / out.size();
        if (share > 0.12) return String.format(java.util.Locale.US, "переписано: %.0f%% новых слов", share * 100);
        double ratio = (double) out.size() / in.size();
        if (ratio < 0.6 && !hasSelfCorrection(input)) return String.format(java.util.Locale.US, "сокращено: %.0f%% слов", ratio * 100);
        return null;
    }

    /** Как llmOutputProblem в main.swift: новый адрес/почта, команда терминала, раздутый ответ → не вставлять. null — ок */
    static String llmOutputProblem(String input, String output, Store.Config cfg) {
        String in = input.toLowerCase();
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Map<String, String> m : cfg.links.values()) allowed.addAll(m.values());
        Matcher m = URL_OR_EMAIL.matcher(output);
        while (m.find()) {
            String u = m.group().replaceAll("[.,;:!?)\\]»\"']+$", "");
            boolean ok = in.contains(u.toLowerCase());
            for (String a : allowed) if (a.startsWith(u) || u.startsWith(a)) ok = true;
            if (!ok) return "новый адрес «" + (u.length() > 40 ? u.substring(0, 40) : u) + "»";
        }
        String out = output.toLowerCase();
        for (String c : COMMANDS) if (out.contains(c) && !in.contains(c)) return "команда «" + c.trim() + "»";
        if (output.length() > Math.max(input.length() * 5 / 2, input.length() + 200)) return "ответ слишком длинный";
        return null;
    }

    /** Разговор: говорил собеседник, а не пользователь? Только если в распознанном нет НИ ОДНОЙ русской буквы и буквы вообще есть.
     *  Порог долей не годится: «Deploy на prod, check logs» — 9.5 % кириллицы, а это русская речь (ревью 13.10);
     *  «42.» (пользователь сказал «сорок два») — букв нет, это пользователь */
    static boolean foreignSpeech(String s) {
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetter(ch)) continue;
            if (ch >= 0x0400 && ch <= 0x04FF) return false;
            letters++;
        }
        return letters > 0;
    }

    /** Стрим вернул ≤ 4 слов целиком латиницей при русской речи — не верим (как suspiciousLatin в main.swift): на коротком
     *  звуке стрим «слышит» английский. Термины словаря/памяти и значения замен латиницей — не в счёт */
    static boolean suspiciousLatin(String text, Store.Config cfg, Store.Memory mem) {
        if (!"ru".equals(cfg.language)) return false;
        List<String> words = new ArrayList<>();
        for (String w : text.split("[^\\p{L}\\p{N}]+")) if (w.matches(".*\\p{L}.*")) words.add(w);
        int spoken = 0;
        for (String w : text.trim().split("\\s+")) if (w.matches(".*\\p{L}.*")) spoken++;
        if (words.isEmpty() || spoken > 4) return false;
        for (String w : words) for (int i = 0; i < w.length(); i++) { char c = w.charAt(i); if (c >= 0x0400 && c <= 0x04FF) return false; }
        Set<String> known = new HashSet<>(java.util.Arrays.asList("ok", "okay")); // «окей» стрим пишет «Okay.» — это и сказано
        List<String> src = new ArrayList<>(cfg.dictionary);
        src.addAll(cfg.replacements.values());
        src.addAll(mem.terms);
        for (String k : src) for (String p : k.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!p.isEmpty()) known.add(p);
        for (String w : words) if (!known.contains(w.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    /** Короткие ответы — таблицей, без нейросети (17.10.2026, пользователь, «нет», «окей»»; перевод
     *  тратил на них 3–9 с). Только фраза целиком; «?»/«!» — из сказанного. Та же таблица — quickPhrases в main.swift */
    private static final java.util.Map<String, String[]> QUICK = new java.util.HashMap<>();
    static {
        String[][] t = {
            // ключ, en, vi, tr, fr (FR — классический, без разговорных «Ouais», «Salut»; 17.10.2026)
            {"да", "Yes", "Vâng", "Evet", "Oui"}, {"нет", "No", "Không", "Hayır", "Non"},
            {"ок", "OK", "OK", "Tamam", "D’accord"}, {"окей", "OK", "OK", "Tamam", "D’accord"}, {"okay", "OK", "OK", "Tamam", "D’accord"}, {"ok", "OK", "OK", "Tamam", "D’accord"},
            {"хорошо", "OK", "Được", "Tamam", "Très bien"},
            {"спасибо", "Thank you", "Cảm ơn", "Teşekkürler", "Merci"},
            {"спасибо большое", "Thank you very much", "Cảm ơn nhiều", "Çok teşekkürler", "Merci beaucoup"},
            {"большое спасибо", "Thank you very much", "Cảm ơn nhiều", "Çok teşekkürler", "Merci beaucoup"},
            {"привет", "Hi", "Xin chào", "Merhaba", "Bonjour"}, {"пока", "Bye", "Tạm biệt", "Görüşürüz", "À bientôt"},
            {"понял", "Got it", "Hiểu rồi", "Anladım", "Compris"}, {"поняла", "Got it", "Hiểu rồi", "Anladım", "Compris"}, {"понятно", "Got it", "Hiểu rồi", "Anladım", "Je comprends"},
            {"конечно", "Of course", "Tất nhiên", "Tabii ki", "Bien sûr"}, {"да конечно", "Yes, of course", "Vâng, tất nhiên", "Evet, tabii ki", "Oui, bien sûr"},
            {"отлично", "Great", "Tuyệt", "Harika", "Parfait"}, {"супер", "Great", "Tuyệt", "Süper", "Formidable"},
            {"договорились", "Agreed", "Đồng ý", "Anlaştık", "Entendu"},
        };
        for (String[] r : t) QUICK.put(r[0], new String[]{r[1], r[2], r[3], r[4]});
    }

    /** null — не короткий ответ из таблицы, переводит нейросеть */
    static String quickTranslation(String text, String lang) {
        int i = lang.equals("en") ? 0 : lang.equals("vi") ? 1 : lang.equals("tr") ? 2 : lang.equals("fr") ? 3 : -1;
        if (i < 0) return null;
        String t = text.trim();
        // Цифры и символы («Да, 15%», «Нет :(») — уже не короткий ответ, таблица потеряла бы их (ревью 17.10)
        // Вопрос («Да?», «Понятно?») — не ответ: таблица дала бы «Vâng?», «Got it?» (ревью 17.10) — переводит LLM
        if (!t.matches("[\\p{L} .,!…—-]+")) return null;
        String key = t.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L} ]", " ").trim().replaceAll("\\s+", " ");
        String[] row = QUICK.get(key);
        if (row == null) return null;
        char last = t.isEmpty() ? '.' : t.charAt(t.length() - 1);
        // Французская типографика: неразрывный пробел перед «!»
        return row[i] + (last == '!' ? (i == 3 ? "\u00A0!" : "!") : ".");
    }

    static boolean mostlyCyrillic(String s) {
        int letters = 0, cyr = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            if (ch >= 0x0400 && ch <= 0x04FF) cyr++;
        }
        return letters > 0 && cyr * 10 >= letters;
    }
}
