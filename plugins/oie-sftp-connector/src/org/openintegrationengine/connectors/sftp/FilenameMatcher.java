/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a filename is one this connector should take.
 *
 * <p>Deliberately the same grammar as the File Reader's filter, so a channel moved from
 * that connector keeps behaving the same way:
 *
 * <ul>
 *   <li>a space or comma separated list of wildcards, {@code *} and {@code ?}, any of
 *       which may match -- {@code *.hl7 *.txt};</li>
 *   <li>a leading {@code !} negating the whole list -- {@code !*.tmp, *.filepart} takes
 *       everything that is not a part file;</li>
 *   <li>or, when {@code regex} is set, a single regular expression matched against the
 *       whole name.</li>
 * </ul>
 *
 * <p>Matching is on the filename alone, never the path, and is case sensitive because
 * every SFTP server this can talk to is.
 */
public class FilenameMatcher implements Serializable {

    private final boolean negated;
    private final boolean ignoreDot;
    private final List<Pattern> patterns;
    private final boolean matchAll;

    /**
     * @param filter  the filter expression; blank or {@code *} matches everything
     * @param regex   treat the expression as one regular expression
     * @param ignoreDot skip names beginning with a dot, whatever the expression says
     * @throws IllegalArgumentException if {@code regex} is set and the expression will not compile
     */
    public FilenameMatcher(String filter, boolean regex, boolean ignoreDot) {
        this.ignoreDot = ignoreDot;
        this.patterns = new ArrayList<Pattern>();

        String expression = filter == null ? "" : filter.trim();

        if (!regex && expression.startsWith("!")) {
            negated = true;
            expression = expression.substring(1).trim();
        } else {
            negated = false;
        }

        if (expression.isEmpty() || (!regex && expression.equals("*"))) {
            matchAll = true;
            return;
        }
        matchAll = false;

        if (regex) {
            try {
                patterns.add(Pattern.compile(expression));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid file filter regular expression: " + e.getMessage(), e);
            }
        } else {
            for (String token : expression.split("[,;\\s]+")) {
                if (!token.isEmpty()) {
                    patterns.add(Pattern.compile(wildcardToRegex(token)));
                }
            }
            if (patterns.isEmpty()) {
                // A filter of only separators is a filter of nothing, not a filter of everything.
                patterns.add(Pattern.compile("(?!)"));
            }
        }
    }

    public boolean accept(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        if (ignoreDot && filename.charAt(0) == '.') {
            return false;
        }
        if (matchAll) {
            return true;
        }

        boolean matched = false;
        for (Pattern pattern : patterns) {
            if (pattern.matcher(filename).matches()) {
                matched = true;
                break;
            }
        }
        return negated != matched;
    }

    /**
     * A wildcard token as a regular expression. Everything outside {@code *} and {@code ?}
     * is quoted, so a filename filter containing a dot or a plus means those characters
     * and not their regex meanings.
     */
    static String wildcardToRegex(String token) {
        StringBuilder regex = new StringBuilder(token.length() + 8);
        StringBuilder literal = new StringBuilder();

        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }
}
