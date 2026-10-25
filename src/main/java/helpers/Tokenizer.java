package helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Tokenizer {

    // Longest word we store (matches terms.term VARCHAR(100) in the schema)
    private static final int MAX_TOKEN_LENGTH = 100;

    // Words too common to help ranking. Extend this list as you like.
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "am",
        "of", "to", "in", "on", "at", "for", "from", "by", "with", "as",
        "and", "or", "but", "if", "so", "than", "that", "this", "these", "those",
        "it", "its", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "do", "does", "did", "can", "could", "will", "would", "should", "has", "have", "had",
        "how", "what", "why", "when", "where", "which", "who"
    );

    private Tokenizer() { }  // utility class, no instances

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) return tokens;

        // One stemmer per call keeps this method thread-safe
        PorterStemmer stemmer = new PorterStemmer();

        // Split on anything that is not a letter or digit (any language)
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() < 2 || word.length() > MAX_TOKEN_LENGTH) continue;
            if (STOP_WORDS.contains(word)) continue;       // stop-word check BEFORE stemming

            // Porter stemmer only understands plain English letters a-z
            if (word.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
                word = stemmer.stem(word);
            }
            tokens.add(word);
        }
        return tokens;
    }

    
    public static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String token : tokenize(text)) {
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }

    /** Total number of tokens in a text (this is what goes into documents.doc_length). */
    public static int length(Map<String, Integer> freq) {
        int total = 0;
        for (int count : freq.values()) total += count;
        return total;
    }

    static final class PorterStemmer {

        private char[] b;   // the word being stemmed
        private int k;      // index of the last character of the word
        private int j;      // index of the last character of the stem (set by ends())

        String stem(String word) {
            if (word.length() <= 2) return word;   // too short to stem
            b = word.toCharArray();
            k = b.length - 1;
            step1ab();
            if (k > 0) {
                step1c();
                step2();
                step3();
                step4();
                step5();
            }
            return new String(b, 0, k + 1);
        }

        // true if b[i] is a consonant ('y' counts as a vowel after a consonant)
        private boolean cons(int i) {
            switch (b[i]) {
                case 'a': case 'e': case 'i': case 'o': case 'u': return false;
                case 'y': return i == 0 || !cons(i - 1);
                default:  return true;
            }
        }

        // m() = number of consonant-vowel sequences in b[0..j]; the "measure"
        // m=0: tr, ee | m=1: trouble, oats | m=2: troubles, private
        private int m() {
            int n = 0, i = 0;
            while (true) {                       // skip leading consonants
                if (i > j) return n;
                if (!cons(i)) break;
                i++;
            }
            i++;
            while (true) {
                while (true) {                   // skip vowels
                    if (i > j) return n;
                    if (cons(i)) break;
                    i++;
                }
                i++;
                n++;
                while (true) {                   // skip consonants
                    if (i > j) return n;
                    if (!cons(i)) break;
                    i++;
                }
                i++;
            }
        }

        // true if the stem b[0..j] contains a vowel
        private boolean vowelInStem() {
            for (int i = 0; i <= j; i++) if (!cons(i)) return true;
            return false;
        }

        // true if b[i-1..i] is a double consonant (e.g. "tt")
        private boolean doubleC(int i) {
            if (i < 1 || b[i] != b[i - 1]) return false;
            return cons(i);
        }

        // true if b[i-2..i] is consonant-vowel-consonant and the last is not w, x or y
        private boolean cvc(int i) {
            if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false;
            char ch = b[i];
            return ch != 'w' && ch != 'x' && ch != 'y';
        }

        // true if the word ends with s; on success j points just before the suffix
        private boolean ends(String s) {
            int len = s.length();
            int start = k - len + 1;
            if (start < 0) return false;
            for (int i = 0; i < len; i++) {
                if (b[start + i] != s.charAt(i)) return false;
            }
            j = k - len;
            return true;
        }

        // replaces everything after b[j] with s
        private void setTo(String s) {
            int len = s.length();
            if (j + 1 + len > b.length) b = Arrays.copyOf(b, j + 1 + len);
            for (int i = 0; i < len; i++) b[j + 1 + i] = s.charAt(i);
            k = j + len;
        }

        // replace the suffix with s only if the stem is long enough (m > 0)
        private void r(String s) {
            if (m() > 0) setTo(s);
        }

        // Step 1a/1b: plurals and -ed / -ing
        //   caresses -> caress, ponies -> poni, cats -> cat
        //   agreed -> agree, plastered -> plaster, motoring -> motor
        private void step1ab() {
            if (b[k] == 's') {
                if (ends("sses")) k -= 2;
                else if (ends("ies")) setTo("i");
                else if (b[k - 1] != 's') k--;
            }
            if (ends("eed")) {
                if (m() > 0) k--;
            } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
                k = j;
                if (ends("at")) setTo("ate");
                else if (ends("bl")) setTo("ble");
                else if (ends("iz")) setTo("ize");
                else if (doubleC(k)) {
                    k--;
                    char ch = b[k];
                    if (ch == 'l' || ch == 's' || ch == 'z') k++;
                } else if (m() == 1 && cvc(k)) setTo("e");
            }
        }

        // Step 1c: terminal y -> i when there is another vowel (happy -> happi)
        private void step1c() {
            if (ends("y") && vowelInStem()) b[k] = 'i';
        }

        // Step 2: double suffixes -> single (relational -> relate)
        private void step2() {
            switch (b[k - 1]) {
                case 'a':
                    if (ends("ational")) { r("ate"); break; }
                    if (ends("tional"))  { r("tion"); break; }
                    break;
                case 'c':
                    if (ends("enci")) { r("ence"); break; }
                    if (ends("anci")) { r("ance"); break; }
                    break;
                case 'e':
                    if (ends("izer")) { r("ize"); break; }
                    break;
                case 'l':
                    if (ends("bli"))   { r("ble"); break; }
                    if (ends("alli"))  { r("al");  break; }
                    if (ends("entli")) { r("ent"); break; }
                    if (ends("eli"))   { r("e");   break; }
                    if (ends("ousli")) { r("ous"); break; }
                    break;
                case 'o':
                    if (ends("ization")) { r("ize"); break; }
                    if (ends("ation"))   { r("ate"); break; }
                    if (ends("ator"))    { r("ate"); break; }
                    break;
                case 's':
                    if (ends("alism"))   { r("al");  break; }
                    if (ends("iveness")) { r("ive"); break; }
                    if (ends("fulness")) { r("ful"); break; }
                    if (ends("ousness")) { r("ous"); break; }
                    break;
                case 't':
                    if (ends("aliti"))  { r("al");  break; }
                    if (ends("iviti"))  { r("ive"); break; }
                    if (ends("biliti")) { r("ble"); break; }
                    break;
                case 'g':
                    if (ends("logi")) { r("log"); break; }
                    break;
                default:
                    break;
            }
        }

        // Step 3: -ic-, -full, -ness etc. (formative -> form, hopeful -> hope)
        private void step3() {
            switch (b[k]) {
                case 'e':
                    if (ends("icate")) { r("ic"); break; }
                    if (ends("ative")) { r("");   break; }
                    if (ends("alize")) { r("al"); break; }
                    break;
                case 'i':
                    if (ends("iciti")) { r("ic"); break; }
                    break;
                case 'l':
                    if (ends("ical")) { r("ic"); break; }
                    if (ends("ful"))  { r("");   break; }
                    break;
                case 's':
                    if (ends("ness")) { r(""); break; }
                    break;
                default:
                    break;
            }
        }

        // Step 4: remove -ant, -ence, -ment, ... when the stem is long enough (m > 1)
        private static final String[] STEP4_SUFFIXES = {
            "al", "ance", "ence", "er", "ic", "able", "ible", "ant",
            "ement", "ment", "ent", "ion", "ou", "ism", "ate", "iti",
            "ous", "ive", "ize"
        };

        private void step4() {
            if (k < 1) return;
            boolean matched = false;
            for (String suffix : STEP4_SUFFIXES) {
                if (ends(suffix)) {
                    // "-ion" is only removed after s or t (adoption -> adopt)
                    if (suffix.equals("ion") && !(j >= 0 && (b[j] == 's' || b[j] == 't'))) return;
                    matched = true;
                    break;
                }
            }
            if (matched && m() > 1) k = j;
        }

        // Step 5: drop final -e if m > 1 (or m = 1 and not cvc), and -ll -> -l
        private void step5() {
            j = k;
            if (b[k] == 'e') {
                int a = m();
                if (a > 1 || (a == 1 && !cvc(k - 1))) k--;
            }
            if (b[k] == 'l' && doubleC(k) && m() > 1) k--;
        }
    }
}