package com.poso.qqbind.utils;

/**
 * Minimal JSONC support: strips // and /* *&#47; comments and trailing commas
 * outside strings, so the existing strict Gson/duplicate-key parser can still
 * validate the result. No secrets or parsed values are logged.
 */
public final class Jsonc {
    private Jsonc() { }

    /** @return JSON text accepted by the strict parser; never null for non-null input */
    public static String strip(String raw) {
        if (raw == null) return null;
        if (!raw.isEmpty() && raw.charAt(0) == '﻿') raw = raw.substring(1);
        StringBuilder out = new StringBuilder(raw.length());
        boolean inString = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '/') {
                    out.append("  ");
                    i += 2;
                    while (i < raw.length()) {
                        char value = raw.charAt(i);
                        if (value == '\n' || value == '\r') break;
                        out.append(' ');
                        i++;
                    }
                    i--;
                    continue;
                }
                if (next == '*') {
                    out.append("  ");
                    i += 2;
                    boolean closed = false;
                    while (i < raw.length()) {
                        char value = raw.charAt(i);
                        if (value == '*' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                            out.append("  ");
                            i += 2;
                            closed = true;
                            break;
                        }
                        out.append(value == '\n' || value == '\r' ? value : ' ');
                        i++;
                    }
                    if (!closed) throw new IllegalArgumentException("Unterminated block comment");
                    i--;
                    continue;
                }
            }
            out.append(c);
        }
        return removeTrailingCommas(out.toString());
    }

    private static String removeTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int next = i + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
                if (next < text.length() && (text.charAt(next) == '}' || text.charAt(next) == ']')) continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Replace a top-level boolean property without reformatting the rest of the
     * document. Returns null when the key is missing or its value is not a
     * literal boolean; callers can then fall back to a full rewrite.
     */
    public static String updateTopLevelBoolean(String raw, String key, boolean value) {
        if (raw == null || key == null) return null;
        int start = skipTrivia(raw, !raw.isEmpty() && raw.charAt(0) == '﻿' ? 1 : 0);
        if (start >= raw.length() || raw.charAt(start) != '{') return null;
        int end = matchingBrace(raw, start);
        if (end < 0) return null;

        int i = skipTrivia(raw, start + 1);
        int depth = 0;
        while (i < end) {
            i = skipTrivia(raw, i);
            if (i >= end) break;
            char c = raw.charAt(i);
            if (c == '"') {
                int stringEnd = stringEnd(raw, i);
                if (stringEnd < 0) return null;
                int colon = skipTrivia(raw, stringEnd);
                if (colon < end && raw.charAt(colon) == ':' && depth == 0) {
                    String name = raw.substring(i + 1, stringEnd - 1);
                    if (name.equals(key)) {
                        int valueStart = skipTrivia(raw, colon + 1);
                        if (raw.startsWith("true", valueStart)) {
                            return raw.substring(0, valueStart) + value + raw.substring(valueStart + 4);
                        }
                        if (raw.startsWith("false", valueStart)) {
                            return raw.substring(0, valueStart) + value + raw.substring(valueStart + 5);
                        }
                        return null;
                    }
                }
                i = stringEnd;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                if (depth == 0) break;
                depth--;
                i++;
                continue;
            }
            i++;
        }
        // Missing key: insert after the root opening brace. This avoids guessing
        // whether the last property already has a comma when trailing comments
        // or block comments sit before the closing brace.
        String cleaned;
        try {
            cleaned = strip(raw);
        } catch (RuntimeException e) {
            return null;
        }
        int cleanStart = skipTrivia(cleaned, !cleaned.isEmpty() && cleaned.charAt(0) == '﻿' ? 1 : 0);
        if (cleanStart >= cleaned.length() || cleaned.charAt(cleanStart) != '{') return null;
        int cleanEnd = matchingBrace(cleaned, cleanStart);
        if (cleanEnd < 0) return null;
        boolean empty = skipTrivia(cleaned, cleanStart + 1) >= cleanEnd;
        String newline = raw.contains("\r\n") ? "\r\n" : "\n";
        int insertAt = start + 1;
        String insertion = newline + "  \"" + key + "\": " + value + (empty ? "" : ",") + newline;
        return raw.substring(0, insertAt) + insertion + raw.substring(insertAt);
    }

    private static int skipTrivia(String raw, int index) {
        int i = index;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                i += 2;
                while (i < raw.length() && raw.charAt(i) != '\n' && raw.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < raw.length() && !(raw.charAt(i) == '*' && raw.charAt(i + 1) == '/')) i++;
                if (i + 1 >= raw.length()) return raw.length();
                i += 2;
                continue;
            }
            break;
        }
        return i;
    }

    private static int stringEnd(String raw, int quote) {
        boolean escaped = false;
        for (int i = quote + 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') return i + 1;
        }
        return -1;
    }

    private static int matchingBrace(String raw, int open) {
        boolean inString = false, escaped = false;
        int depth = 0;
        for (int i = open; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                i += 2;
                while (i < raw.length() && raw.charAt(i) != '\n' && raw.charAt(i) != '\r') i++;
                i--;
                continue;
            }
            if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < raw.length() && !(raw.charAt(i) == '*' && raw.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
