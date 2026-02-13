package com.blockether.svar;

import java.util.*;
import java.util.regex.*;
import java.text.Normalizer;

/**
 * SAP-style JSON parser that handles malformed JSON from LLMs.
 * 
 * Implements BAML-style Schemaless Adaptive Parsing (SAP) with:
 * 
 * PARSING FEATURES:
 * - Multi-stage parsing cascade (strict → markdown → multi-object → fixes → string)
 * - Parse candidates system (AnyOf) for schema-guided selection
 * - Multi-JSON object extraction (finds all balanced {}/[] in text)
 * - Unquoted keys and string values
 * - Trailing/leading commas handling
 * - Comments (single-line // and multi-line /* *\/)
 * - Markdown code blocks (```json, ```python, etc.)
 * - Single-quoted strings ('...')
 * - Triple-quoted strings ("""...""")
 * - Backtick strings (`...` and ```...```)
 * - Streaming/partial parsing with completion state tracking
 * 
 * COERCION FEATURES:
 * - Fraction parsing (1/2 → 0.5)
 * - Comma-separated numbers (1,234.56 → 1234.56)
 * - Currency symbol stripping ($100 → 100)
 * - Fuzzy string matching (case-insensitive, accent removal)
 * - Substring matching for enum coercion
 * 
 * Returns standard Java types: Map, List, String, Number, Boolean, null.
 */
public class JsonishParser {
    
    // ==========================================================================
    // Completion State Tracking (for streaming support)
    // ==========================================================================
    
    /**
     * Tracks whether a parsed value is complete or still streaming.
     */
    public enum CompletionState {
        COMPLETE,    // Value is fully parsed
        INCOMPLETE,  // Value is partial (unclosed string, object, array)
        PENDING      // Value hasn't started yet (placeholder)
    }
    
    // ==========================================================================
    // Parse Candidate System (AnyOf)
    // ==========================================================================
    
    /**
     * Represents a single parse attempt result with metadata.
     */
    public static class ParseCandidate {
        public final Object value;
        public final CompletionState completionState;
        public final List<String> fixes;  // What fixes were applied
        public final String source;       // "strict", "markdown", "multi", "fixing"
        public final int score;           // Higher = more preferred
        
        public ParseCandidate(Object value, CompletionState completionState, 
                              List<String> fixes, String source, int score) {
            this.value = value;
            this.completionState = completionState;
            this.fixes = fixes != null ? fixes : new ArrayList<>();
            this.source = source;
            this.score = score;
        }
        
        @Override
        public String toString() {
            return String.format("ParseCandidate{source=%s, score=%d, state=%s, value=%s}", 
                                 source, score, completionState, value);
        }
    }
    
    // ==========================================================================
    // Parser State
    // ==========================================================================
    
    private String input;
    private int pos;
    private int length;
    private List<String> warnings;
    private CompletionState currentCompletionState;
    private int depth;  // Recursion depth limit
    private static final int MAX_DEPTH = 9999;
    
    public JsonishParser() {
        this.warnings = new ArrayList<>();
        this.currentCompletionState = CompletionState.COMPLETE;
        this.depth = 0;
    }
    
    /**
     * Parses JSON-ish string to Java objects.
     * 
     * @param input The JSON-ish string to parse
     * @return Map with "value" (parsed result) and "warnings" (list of warnings)
     */
    public Map<String, Object> parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Input cannot be null or empty");
        }
        
        // Extract from markdown if present
        String cleaned = extractFromMarkdown(input);
        
        this.input = cleaned;
        this.pos = 0;
        this.length = cleaned.length();
        this.warnings = new ArrayList<>();
        
        skipWhitespaceAndComments();
        Object value = parseValue();
        
        Map<String, Object> result = new HashMap<>();
        result.put("value", value);
        result.put("warnings", warnings);
        return result;
    }
    
    /**
     * Extracts JSON from markdown code blocks.
     */
    private String extractFromMarkdown(String input) {
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n(.+?)\\n```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            warnings.add("Extracted JSON from markdown code block");
            return matcher.group(1);
        }
        return input;
    }
    
    /**
     * Skips whitespace and comments.
     */
    private void skipWhitespaceAndComments() {
        while (pos < length) {
            char c = input.charAt(pos);
            
            // Skip whitespace
            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            
            // Skip // comments
            if (c == '/' && pos + 1 < length && input.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < length && input.charAt(pos) != '\n') {
                    pos++;
                }
                warnings.add("Skipped // comment");
                continue;
            }
            
            // Skip /* */ comments
            if (c == '/' && pos + 1 < length && input.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < length) {
                    if (input.charAt(pos) == '*' && input.charAt(pos + 1) == '/') {
                        pos += 2;
                        break;
                    }
                    pos++;
                }
                warnings.add("Skipped /* */ comment");
                continue;
            }
            
            break;
        }
    }
    
    /**
     * Parses any JSON value.
     */
    private Object parseValue() {
        skipWhitespaceAndComments();
        
        if (pos >= length) {
            throw new RuntimeException("Unexpected end of input");
        }
        
        char c = input.charAt(pos);
        
        if (c == '{') {
            return parseObject();
        } else if (c == '[') {
            return parseArray();
        } else if (c == '"') {
            return parseQuotedString();
        } else if (c == 't' || c == 'f') {
            return parseBoolean();
        } else if (c == 'n') {
            return parseNull();
        } else if (c == '-' || Character.isDigit(c)) {
            return parseNumber();
        } else {
            // Try to parse as unquoted string
            return parseUnquotedString();
        }
    }
    
    /**
     * Parses an object { ... }.
     */
    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        
        expect('{');
        skipWhitespaceAndComments();
        
        // Empty object
        if (pos < length && input.charAt(pos) == '}') {
            pos++;
            return map;
        }
        
        while (pos < length) {
            skipWhitespaceAndComments();
            
            // Parse key
            String key;
            if (input.charAt(pos) == '"') {
                key = parseQuotedString();
            } else {
                key = parseUnquotedKey();
                warnings.add("Unquoted key: " + key);
            }
            
            skipWhitespaceAndComments();
            expect(':');
            skipWhitespaceAndComments();
            
            // Parse value
            Object value = parseValue();
            map.put(key, value);
            
            skipWhitespaceAndComments();
            
            if (pos >= length) {
                throw new RuntimeException("Unexpected end of input in object");
            }
            
            char c = input.charAt(pos);
            if (c == ',') {
                pos++;
                skipWhitespaceAndComments();
                // Check for trailing comma
                if (pos < length && input.charAt(pos) == '}') {
                    warnings.add("Trailing comma in object");
                    pos++;
                    break;
                }
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw new RuntimeException("Expected ',' or '}' but got '" + c + "' at position " + pos);
            }
        }
        
        return map;
    }
    
    /**
     * Parses an array [ ... ].
     */
    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        
        expect('[');
        skipWhitespaceAndComments();
        
        // Empty array
        if (pos < length && input.charAt(pos) == ']') {
            pos++;
            return list;
        }
        
        while (pos < length) {
            skipWhitespaceAndComments();
            Object value = parseValue();
            list.add(value);
            
            skipWhitespaceAndComments();
            
            if (pos >= length) {
                throw new RuntimeException("Unexpected end of input in array");
            }
            
            char c = input.charAt(pos);
            if (c == ',') {
                pos++;
                skipWhitespaceAndComments();
                // Check for trailing comma
                if (pos < length && input.charAt(pos) == ']') {
                    warnings.add("Trailing comma in array");
                    pos++;
                    break;
                }
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw new RuntimeException("Expected ',' or ']' but got '" + c + "' at position " + pos);
            }
        }
        
        return list;
    }
    
    /**
     * Parses a quoted string "...".
     */
    private String parseQuotedString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            
            if (c == '"') {
                pos++;
                return sb.toString();
            } else if (c == '\\') {
                pos++;
                if (pos >= length) {
                    throw new RuntimeException("Unexpected end of input in string escape");
                }
                char escaped = input.charAt(pos);
                switch (escaped) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        // Unicode escape
                        pos++;
                        String hex = input.substring(pos, Math.min(pos + 4, length));
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 3; // Will be incremented at end of loop
                        break;
                    default:
                        sb.append(escaped);
                }
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        
        throw new RuntimeException("Unterminated string");
    }
    
    /**
     * Parses an unquoted key (stops at :).
     */
    private String parseUnquotedKey() {
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ':' || Character.isWhitespace(c)) {
                break;
            }
            sb.append(c);
            pos++;
        }
        
        return sb.toString();
    }
    
    /**
     * Parses an unquoted string value (stops at , } ] or newline).
     */
    private String parseUnquotedString() {
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ',' || c == '}' || c == ']' || c == '\n') {
                break;
            }
            sb.append(c);
            pos++;
        }
        
        String result = sb.toString().trim();
        warnings.add("Unquoted string value: " + result);
        return result;
    }
    
    /**
     * Parses a boolean (true or false).
     */
    private Boolean parseBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return true;
        } else if (input.startsWith("false", pos)) {
            pos += 5;
            return false;
        } else {
            throw new RuntimeException("Expected boolean at position " + pos);
        }
    }
    
    /**
     * Parses null.
     */
    private Object parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return null;
        } else {
            throw new RuntimeException("Expected null at position " + pos);
        }
    }
    
    /**
     * Parses a number (int or float).
     */
    private Number parseNumber() {
        int start = pos;
        
        // Optional minus
        if (pos < length && input.charAt(pos) == '-') {
            pos++;
        }
        
        // Digits
        while (pos < length && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        
        // Decimal point
        boolean isFloat = false;
        if (pos < length && input.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < length && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        
        // Exponent
        if (pos < length && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < length && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < length && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        
        String numStr = input.substring(start, pos);
        
        if (isFloat) {
            return Double.parseDouble(numStr);
        } else {
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return Double.parseDouble(numStr);
            }
        }
    }
    
    private void expect(char expected) {
        if (pos >= length) {
            throw new RuntimeException("Expected '" + expected + "' but reached end of input");
        }
        char actual = input.charAt(pos);
        if (actual != expected) {
            throw new RuntimeException("Expected '" + expected + "' but got '" + actual + "' at position " + pos);
        }
        pos++;
    }
    
    // ==========================================================================
    // Multi-Stage Parsing (BAML-style cascade)
    // ==========================================================================
    
    public List<ParseCandidate> parseWithCandidates(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Input cannot be null or empty");
        }
        
        List<ParseCandidate> candidates = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.depth = 0;
        
        // Stage 1: Try strict JSON parsing first
        try {
            ParseCandidate strict = tryStrictJsonParse(input);
            if (strict != null) {
                candidates.add(strict);
            }
        } catch (Exception e) {
            // Strict parsing failed, continue to next stage
        }
        
        // Stage 2: Try markdown extraction
        List<ParseCandidate> markdownCandidates = tryMarkdownParse(input);
        candidates.addAll(markdownCandidates);
        
        // Stage 3: Try multi-JSON object extraction
        List<ParseCandidate> multiCandidates = tryMultiJsonParse(input);
        candidates.addAll(multiCandidates);
        
        // Stage 4: Try fixing parser (handles malformed JSON)
        try {
            ParseCandidate fixed = tryFixingParse(input);
            if (fixed != null) {
                candidates.add(fixed);
            }
        } catch (Exception e) {
            // Fixing parse failed
        }
        
        // Stage 5: If no candidates, return as string
        if (candidates.isEmpty()) {
            candidates.add(new ParseCandidate(
                input.trim(),
                CompletionState.COMPLETE,
                Arrays.asList("FallbackToString"),
                "string",
                0
            ));
        }
        
        // Sort by score (highest first)
        candidates.sort((a, b) -> Integer.compare(b.score, a.score));
        
        return candidates;
    }
    
    private ParseCandidate tryStrictJsonParse(String input) {
        // Use a strict JSON approach - only valid JSON
        String trimmed = input.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && 
            !trimmed.startsWith("\"") && !trimmed.equals("true") && 
            !trimmed.equals("false") && !trimmed.equals("null") &&
            !trimmed.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            return null;
        }
        
        this.input = trimmed;
        this.pos = 0;
        this.length = trimmed.length();
        List<String> savedWarnings = new ArrayList<>();
        
        try {
            Object value = parseValue();
            // Verify we consumed all input
            skipWhitespaceAndComments();
            if (pos < length) {
                return null;  // Extra content = not strict JSON
            }
            if (warnings.isEmpty()) {
                return new ParseCandidate(value, CompletionState.COMPLETE, 
                                          new ArrayList<>(), "strict", 100);
            }
        } catch (Exception e) {
            // Not valid strict JSON
        }
        return null;
    }
    
    private List<ParseCandidate> tryMarkdownParse(String input) {
        List<ParseCandidate> candidates = new ArrayList<>();
        
        // Pattern for markdown code blocks (with optional language tag)
        Pattern pattern = Pattern.compile(
            "(?m)^[ \\t]*```([a-zA-Z0-9 ]*)\\s*\\n(.*?)\\n[ \\t]*```",
            Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(input);
        
        while (matcher.find()) {
            String lang = matcher.group(1).trim();
            String content = matcher.group(2).trim();
            
            if (!content.isEmpty()) {
                try {
                    this.input = content;
                    this.pos = 0;
                    this.length = content.length();
                    this.warnings = new ArrayList<>();
                    
                    Object value = parseValue();
                    
                    List<String> fixes = new ArrayList<>();
                    fixes.add("ExtractedFromMarkdown:" + (lang.isEmpty() ? "untagged" : lang));
                    fixes.addAll(warnings);
                    
                    candidates.add(new ParseCandidate(
                        value,
                        CompletionState.COMPLETE,
                        fixes,
                        "markdown",
                        90
                    ));
                } catch (Exception e) {
                    // This markdown block didn't contain valid JSON-ish
                }
            }
        }
        
        return candidates;
    }
    
    private List<ParseCandidate> tryMultiJsonParse(String input) {
        List<ParseCandidate> candidates = new ArrayList<>();
        List<Object> foundObjects = new ArrayList<>();
        
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            
            if (c == '{' || c == '[') {
                int start = i;
                int end = findMatchingBracket(input, i);
                
                if (end > start) {
                    String jsonCandidate = input.substring(start, end + 1);
                    try {
                        this.input = jsonCandidate;
                        this.pos = 0;
                        this.length = jsonCandidate.length();
                        this.warnings = new ArrayList<>();
                        
                        Object value = parseValue();
                        foundObjects.add(value);
                        i = end + 1;
                        continue;
                    } catch (Exception e) {
                        // Not valid JSON, move forward
                    }
                }
            }
            i++;
        }
        
        if (foundObjects.size() == 1) {
            candidates.add(new ParseCandidate(
                foundObjects.get(0),
                CompletionState.COMPLETE,
                Arrays.asList("GreppedForJSON"),
                "multi",
                80
            ));
        } else if (foundObjects.size() > 1) {
            // Add each object individually
            for (Object obj : foundObjects) {
                candidates.add(new ParseCandidate(
                    obj,
                    CompletionState.COMPLETE,
                    Arrays.asList("GreppedForJSON", "MultipleObjects"),
                    "multi",
                    70
                ));
            }
            // Also add as array
            candidates.add(new ParseCandidate(
                foundObjects,
                CompletionState.COMPLETE,
                Arrays.asList("GreppedForJSON", "InferredArray"),
                "multi",
                75
            ));
        }
        
        return candidates;
    }
    
    private int findMatchingBracket(String input, int start) {
        char openBracket = input.charAt(start);
        char closeBracket = (openBracket == '{') ? '}' : ']';
        
        int depth = 1;
        boolean inString = false;
        boolean escape = false;
        
        for (int i = start + 1; i < input.length(); i++) {
            char c = input.charAt(i);
            
            if (escape) {
                escape = false;
                continue;
            }
            
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            
            if (c == '"' && !escape) {
                inString = !inString;
                continue;
            }
            
            if (!inString) {
                if (c == openBracket) {
                    depth++;
                } else if (c == closeBracket) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        
        return -1;  // No matching bracket found
    }
    
    private ParseCandidate tryFixingParse(String input) {
        // First try extracting from markdown
        String cleaned = extractFromMarkdown(input);
        
        this.input = cleaned;
        this.pos = 0;
        this.length = cleaned.length();
        this.warnings = new ArrayList<>();
        
        skipWhitespaceAndComments();
        Object value = parseValue();
        
        // Calculate score based on number of fixes needed
        int score = 50 - (warnings.size() * 5);
        if (score < 10) score = 10;
        
        return new ParseCandidate(
            value,
            currentCompletionState,
            new ArrayList<>(warnings),
            "fixing",
            score
        );
    }
    
    // ==========================================================================
    // Single-Quoted and Backtick String Support
    // ==========================================================================
    
    private Object parseValueExtended() {
        skipWhitespaceAndComments();
        
        if (pos >= length) {
            currentCompletionState = CompletionState.INCOMPLETE;
            return null;
        }
        
        char c = input.charAt(pos);
        
        if (c == '{') {
            return parseObjectWithCompletion();
        } else if (c == '[') {
            return parseArrayWithCompletion();
        } else if (c == '"') {
            // Check for triple-quoted string
            if (pos + 2 < length && input.charAt(pos + 1) == '"' && input.charAt(pos + 2) == '"') {
                return parseTripleQuotedString();
            }
            return parseQuotedString();
        } else if (c == '\'') {
            return parseSingleQuotedString();
        } else if (c == '`') {
            // Check for triple backtick
            if (pos + 2 < length && input.charAt(pos + 1) == '`' && input.charAt(pos + 2) == '`') {
                return parseTripleBacktickString();
            }
            return parseBacktickString();
        } else if (c == 't' || c == 'f') {
            return parseBoolean();
        } else if (c == 'n') {
            return parseNull();
        } else if (c == '-' || Character.isDigit(c)) {
            return parseNumber();
        } else {
            return parseUnquotedString();
        }
    }
    
    private String parseSingleQuotedString() {
        pos++;  // Skip opening '
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            
            if (c == '\'') {
                pos++;
                warnings.add("Single-quoted string converted");
                return sb.toString();
            } else if (c == '\\') {
                pos++;
                if (pos >= length) {
                    currentCompletionState = CompletionState.INCOMPLETE;
                    return sb.toString();
                }
                sb.append(parseEscapeSequence());
            } else {
                sb.append(c);
                pos++;
            }
        }
        
        currentCompletionState = CompletionState.INCOMPLETE;
        warnings.add("Unterminated single-quoted string");
        return sb.toString();
    }
    
    private String parseBacktickString() {
        pos++;  // Skip opening `
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            
            if (c == '`') {
                pos++;
                warnings.add("Backtick string converted");
                return sb.toString();
            } else {
                sb.append(c);
                pos++;
            }
        }
        
        currentCompletionState = CompletionState.INCOMPLETE;
        warnings.add("Unterminated backtick string");
        return sb.toString();
    }
    
    private String parseTripleQuotedString() {
        pos += 3;  // Skip opening """
        StringBuilder sb = new StringBuilder();
        
        while (pos + 2 < length) {
            if (input.charAt(pos) == '"' && 
                input.charAt(pos + 1) == '"' && 
                input.charAt(pos + 2) == '"') {
                pos += 3;
                warnings.add("Triple-quoted string converted");
                return sb.toString();
            }
            sb.append(input.charAt(pos));
            pos++;
        }
        
        // Consume remaining characters
        while (pos < length) {
            sb.append(input.charAt(pos));
            pos++;
        }
        
        currentCompletionState = CompletionState.INCOMPLETE;
        warnings.add("Unterminated triple-quoted string");
        return sb.toString();
    }
    
    private String parseTripleBacktickString() {
        pos += 3;  // Skip opening ```
        StringBuilder sb = new StringBuilder();
        
        // Skip optional language tag until newline
        while (pos < length && input.charAt(pos) != '\n') {
            pos++;
        }
        if (pos < length) pos++;  // Skip newline
        
        while (pos + 2 < length) {
            if (input.charAt(pos) == '`' && 
                input.charAt(pos + 1) == '`' && 
                input.charAt(pos + 2) == '`') {
                pos += 3;
                warnings.add("Triple-backtick string converted");
                return sb.toString();
            }
            sb.append(input.charAt(pos));
            pos++;
        }
        
        // Consume remaining characters
        while (pos < length) {
            sb.append(input.charAt(pos));
            pos++;
        }
        
        currentCompletionState = CompletionState.INCOMPLETE;
        warnings.add("Unterminated triple-backtick string");
        return sb.toString();
    }
    
    private char parseEscapeSequence() {
        if (pos >= length) return '\\';
        
        char c = input.charAt(pos);
        pos++;
        
        switch (c) {
            case '"': return '"';
            case '\'': return '\'';
            case '\\': return '\\';
            case '/': return '/';
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u':
                if (pos + 4 <= length) {
                    String hex = input.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        return (char) Integer.parseInt(hex, 16);
                    } catch (NumberFormatException e) {
                        return 'u';
                    }
                }
                return 'u';
            default:
                return c;
        }
    }
    
    // ==========================================================================
    // Streaming/Partial Parsing Support
    // ==========================================================================
    
    private Map<String, Object> parseObjectWithCompletion() {
        Map<String, Object> map = new LinkedHashMap<>();
        CompletionState objState = CompletionState.INCOMPLETE;
        
        expect('{');
        skipWhitespaceAndComments();
        
        if (pos < length && input.charAt(pos) == '}') {
            pos++;
            return map;
        }
        
        while (pos < length) {
            skipWhitespaceAndComments();
            
            if (pos >= length) {
                break;  // Incomplete - no more input
            }
            
            // Parse key
            String key;
            char c = input.charAt(pos);
            if (c == '"') {
                key = parseQuotedString();
            } else if (c == '\'') {
                key = parseSingleQuotedString();
            } else if (c == '}') {
                pos++;
                objState = CompletionState.COMPLETE;
                break;
            } else {
                key = parseUnquotedKey();
                if (!key.isEmpty()) {
                    warnings.add("Unquoted key: " + key);
                }
            }
            
            if (key.isEmpty()) break;
            
            skipWhitespaceAndComments();
            
            if (pos >= length || input.charAt(pos) != ':') {
                break;  // Incomplete
            }
            pos++;  // Skip ':'
            
            skipWhitespaceAndComments();
            
            if (pos >= length) {
                map.put(key, null);  // Key with no value yet
                break;
            }
            
            Object value = parseValueExtended();
            map.put(key, value);
            
            skipWhitespaceAndComments();
            
            if (pos >= length) break;
            
            c = input.charAt(pos);
            if (c == ',') {
                pos++;
                skipWhitespaceAndComments();
                if (pos < length && input.charAt(pos) == '}') {
                    warnings.add("Trailing comma in object");
                    pos++;
                    objState = CompletionState.COMPLETE;
                    break;
                }
            } else if (c == '}') {
                pos++;
                objState = CompletionState.COMPLETE;
                break;
            } else {
                // Try to recover - maybe missing comma
                warnings.add("Missing comma between object entries");
            }
        }
        
        if (objState == CompletionState.INCOMPLETE) {
            currentCompletionState = CompletionState.INCOMPLETE;
            warnings.add("Incomplete object");
        }
        
        return map;
    }
    
    private List<Object> parseArrayWithCompletion() {
        List<Object> list = new ArrayList<>();
        CompletionState arrState = CompletionState.INCOMPLETE;
        
        expect('[');
        skipWhitespaceAndComments();
        
        if (pos < length && input.charAt(pos) == ']') {
            pos++;
            return list;
        }
        
        while (pos < length) {
            skipWhitespaceAndComments();
            
            if (pos >= length) break;
            
            char c = input.charAt(pos);
            if (c == ']') {
                pos++;
                arrState = CompletionState.COMPLETE;
                break;
            }
            
            Object value = parseValueExtended();
            if (value != null || currentCompletionState == CompletionState.INCOMPLETE) {
                list.add(value);
            }
            
            skipWhitespaceAndComments();
            
            if (pos >= length) break;
            
            c = input.charAt(pos);
            if (c == ',') {
                pos++;
                skipWhitespaceAndComments();
                if (pos < length && input.charAt(pos) == ']') {
                    warnings.add("Trailing comma in array");
                    pos++;
                    arrState = CompletionState.COMPLETE;
                    break;
                }
            } else if (c == ']') {
                pos++;
                arrState = CompletionState.COMPLETE;
                break;
            } else {
                warnings.add("Missing comma between array elements");
            }
        }
        
        if (arrState == CompletionState.INCOMPLETE) {
            currentCompletionState = CompletionState.INCOMPLETE;
            warnings.add("Incomplete array");
        }
        
        return list;
    }
    
    // ==========================================================================
    // Fuzzy String Matching (for enum coercion)
    // ==========================================================================
    
    public static String removeAccents(String input) {
        if (input == null) return null;
        
        String result = input
            .replace("ß", "ss")
            .replace("æ", "ae").replace("Æ", "AE")
            .replace("ø", "o").replace("Ø", "O")
            .replace("œ", "oe").replace("Œ", "OE");
        
        result = Normalizer.normalize(result, Normalizer.Form.NFKD);
        return result.replaceAll("\\p{M}", "");
    }
    
    public static String stripPunctuation(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    public static class StringMatch {
        public final String matched;
        public final String original;
        public final int score;
        public final List<String> flags;
        
        public StringMatch(String matched, String original, int score, List<String> flags) {
            this.matched = matched;
            this.original = original;
            this.score = score;
            this.flags = flags;
        }
    }
    
    public static StringMatch matchString(String input, List<String[]> candidates, 
                                           boolean allowSubstring) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = input.trim();
        List<String> flags = new ArrayList<>();
        
        for (String[] candidate : candidates) {
            String name = candidate[0];
            for (int i = 0; i < candidate.length; i++) {
                if (candidate[i].equals(trimmed)) {
                    return new StringMatch(name, trimmed, 100, flags);
                }
            }
        }
        
        String unaccented = removeAccents(trimmed);
        for (String[] candidate : candidates) {
            String name = candidate[0];
            for (int i = 0; i < candidate.length; i++) {
                if (removeAccents(candidate[i]).equals(unaccented)) {
                    flags.add("AccentRemoved");
                    return new StringMatch(name, trimmed, 90, flags);
                }
            }
        }
        
        String lower = trimmed.toLowerCase();
        for (String[] candidate : candidates) {
            String name = candidate[0];
            for (int i = 0; i < candidate.length; i++) {
                if (candidate[i].toLowerCase().equals(lower)) {
                    flags.add("CaseInsensitive");
                    return new StringMatch(name, trimmed, 80, flags);
                }
            }
        }
        
        String stripped = stripPunctuation(lower);
        for (String[] candidate : candidates) {
            String name = candidate[0];
            for (int i = 0; i < candidate.length; i++) {
                if (stripPunctuation(candidate[i].toLowerCase()).equals(stripped)) {
                    flags.add("PunctuationStripped");
                    return new StringMatch(name, trimmed, 70, flags);
                }
            }
        }
        
        if (!allowSubstring) {
            return null;
        }
        
        Map<String, Integer> counts = new HashMap<>();
        for (String[] candidate : candidates) {
            String name = candidate[0];
            for (int i = 0; i < candidate.length; i++) {
                String variant = candidate[i].toLowerCase();
                if (lower.contains(variant)) {
                    counts.put(name, counts.getOrDefault(name, 0) + 1);
                }
            }
        }
        
        if (!counts.isEmpty()) {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            if (best != null) {
                flags.add("SubstringMatch");
                return new StringMatch(best, trimmed, 50, flags);
            }
        }
        
        return null;
    }
    
    // ==========================================================================
    // Number Coercion Utilities
    // ==========================================================================
    
    public static Double parseFlexibleNumber(String input) {
        if (input == null) return null;
        
        String s = input.trim();
        
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length == 2) {
                try {
                    double num = Double.parseDouble(parts[0].trim());
                    double denom = Double.parseDouble(parts[1].trim());
                    if (denom != 0) {
                        return num / denom;
                    }
                } catch (NumberFormatException e) {
                }
            }
        }
        
        String cleaned = s.replaceAll("[\\p{Sc},\\s]", "").trim();
        
        StringBuilder numPart = new StringBuilder();
        boolean foundNumber = false;
        for (char c : cleaned.toCharArray()) {
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || 
                c == 'e' || c == 'E') {
                numPart.append(c);
                foundNumber = true;
            } else if (foundNumber) {
                break;
            }
        }
        
        if (numPart.length() > 0) {
            try {
                return Double.parseDouble(numPart.toString());
            } catch (NumberFormatException e) {
            }
        }
        
        return null;
    }
}
