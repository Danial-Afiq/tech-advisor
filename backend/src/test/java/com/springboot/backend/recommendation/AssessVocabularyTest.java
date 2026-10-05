package com.springboot.backend.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.recommendation.classification.Factors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Java vocabularies must equal the tuples in {@code ai/app/factors.py}, or
 * {@code POST /assess} rejects requests the trigger believes are valid.
 * Reads the Python file straight from the repository checkout.
 */
class AssessVocabularyTest {

    private static final Path FACTORS_PY = Path.of("..", "ai", "app", "factors.py");

    @Test
    void javaVocabulariesMatchFactorsPy() throws IOException {
        String source = Files.readString(FACTORS_PY);

        assertEquals(tuple(source, "FACTORS"), Factors.ALL);
        assertEquals(tuple(source, "CONDITIONS"), AssessVocabulary.CONDITIONS);
        assertEquals(tuple(source, "UPGRADE_URGENCIES"), AssessVocabulary.UPGRADE_URGENCIES);
        assertEquals(tuple(source, "BRAND_FLEXIBILITIES"), AssessVocabulary.BRAND_FLEXIBILITIES);
    }

    private static List<String> tuple(String source, String name) {
        Matcher block = Pattern.compile("(?m)^" + name + "\\b[^=]*=\\s*\\(([^)]*)\\)").matcher(source);
        assertTrue(block.find(), name + " not found in factors.py");

        List<String> values = new ArrayList<>();
        Matcher literal = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
        while (literal.find()) {
            values.add(literal.group(1));
        }
        return values;
    }
}
