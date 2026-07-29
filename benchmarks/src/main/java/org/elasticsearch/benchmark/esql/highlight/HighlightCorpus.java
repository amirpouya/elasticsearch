/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.esql.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The documents both arms of {@link HighlightEsqlVsQueryDslBenchmark} highlight.
 * <p>
 * The generator is deliberately character-identical to the one in
 * {@code org.elasticsearch.benchmark.esql.HighlightAtRuntimeBenchmark} (same seed, same token lengths, same sentence
 * lengths, same term placement) so this benchmark's ES|QL numbers can be checked against that benchmark's published
 * baseline. It is duplicated rather than extracted because {@code HIGHLIGHT_PERF_PLAN.md} freezes that file so its
 * baseline stays comparable across the in-flight performance work.
 */
public final class HighlightCorpus {

    /** Planted in every document, so the {@code hit} scenario matches every row. */
    public static final String HIT_TERM = "fox";

    /** Absent from every document, so the {@code miss} scenario matches no row. */
    public static final String MISS_TERM = "cat";

    private HighlightCorpus() {}

    /**
     * Builds {@code docCount} documents of roughly {@code textTokens} tokens each, with {@link #HIT_TERM} planted
     * {@code occurrences} times per document.
     */
    public static List<String> documents(int docCount, int textTokens, int occurrences) {
        Random random = new Random(42L);
        List<String> documents = new ArrayList<>(docCount);
        for (int i = 0; i < docCount; i++) {
            documents.add(document(random, textTokens, occurrences));
        }
        return documents;
    }

    /**
     * Builds one document of random tokens with {@link #HIT_TERM} inserted at random positions. Sentences start with a
     * capital because the UAX#29 rules do not break before a lowercase letter, and without a break the sentence
     * scanner would return the whole document as a single fragment.
     */
    private static String document(Random random, int textTokens, int occurrences) {
        String[] tokens = new String[textTokens];
        for (int i = 0; i < textTokens; i++) {
            tokens[i] = randomToken(random);
        }
        for (int placed = 0; placed < occurrences;) {
            int position = random.nextInt(textTokens);
            if (HIT_TERM.equals(tokens[position]) == false) {
                tokens[position] = HIT_TERM;
                placed++;
            }
        }
        StringBuilder text = new StringBuilder();
        boolean sentenceStart = true;
        int remaining = sentenceLength(random);
        for (int i = 0; i < textTokens; i++) {
            if (i > 0) {
                text.append(' ');
            }
            String token = tokens[i];
            if (sentenceStart) {
                text.append(Character.toUpperCase(token.charAt(0))).append(token, 1, token.length());
                sentenceStart = false;
            } else {
                text.append(token);
            }
            if (--remaining == 0) {
                text.append('.');
                remaining = sentenceLength(random);
                sentenceStart = true;
            }
        }
        return text.toString();
    }

    private static int sentenceLength(Random random) {
        return 8 + random.nextInt(9);
    }

    private static String randomToken(Random random) {
        int length = 4 + random.nextInt(2);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
}
