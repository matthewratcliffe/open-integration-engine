/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * The handful of primitives both halves of the generator draw on: the population builder in
 * {@link SyntheticPatient} and the {@code ${random.*}} placeholders in
 * {@link TemplateRenderer}.
 *
 * <p>Every one of them takes the {@link Random} to use rather than reaching for a shared
 * one, because the population must come out identical for a given seed no matter how many
 * messages have been generated since -- which is only true if the pool's randomness and the
 * per-message randomness never touch the same generator.
 */
final class RandomValues {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private RandomValues() {
    }

    /** Inclusive at both ends, and tolerant of the bounds arriving the wrong way round. */
    static int intBetween(Random random, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        if (low == high) {
            return low;
        }
        return low + random.nextInt(high - low + 1);
    }

    static String decimalBetween(Random random, double min, double max, int scale) {
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        double value = low + random.nextDouble() * (high - low);
        return BigDecimal.valueOf(value).setScale(Math.max(0, scale), RoundingMode.HALF_UP).toPlainString();
    }

    /** A fixed-length run of digits, leading zeros included -- an identifier, not a number. */
    static String digits(Random random, int length) {
        StringBuilder builder = new StringBuilder(Math.max(0, length));
        for (int i = 0; i < length; i++) {
            builder.append((char) ('0' + random.nextInt(10)));
        }
        return builder.toString();
    }

    static String alpha(Random random, int length) {
        StringBuilder builder = new StringBuilder(Math.max(0, length));
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }
}
