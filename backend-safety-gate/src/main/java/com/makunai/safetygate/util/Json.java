package com.makunai.safetygate.util;

import java.util.*;

/**
 * Minimal, dependency-free JSON parser and writer.
 *
 * The project intentionally avoids Jackson/Gson so the gate can be built and
 * run in network-restricted CI environments (no Maven Central pull required
 * beyond the JDK itself). Values are represented as:
 *   - Map<String,Object>  for JSON objects (LinkedHashMap, insertion-ordered)
 *   - List<Object>        for JSON arrays
 *   - String, Double, Boolean, or null for scalars
 */
public final class Json {

    private Json() {
    }

    // ---------- Parsing ----------

    public static Object parse(String input) {
        Parser p = new Parser(input);
        p.skipWhitespace();
        Object result = p.parseValue();
        p.skipWhitespace();
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object result = parse(input);
        if (!(result instanceof Map)) {
            throw new JsonException("Expected JSON object at document root");
        }
        return (Map<String, Object>) result;
    }

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (i >= s.length()) {
                throw new JsonException("Unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{': return parseObjectInternal();
                case '[': return parseArray();
                case '"': return parseString();
                case 't':
                case 'f': return parseBoolean();
                case 'n': return parseNull();
                default: return parseNumber();
            }
        }

        Map<String, Object> parseObjectInternal() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char n = peek();
                if (n == ',') {
                    i++;
                } else if (n == '}') {
                    i++;
                    break;
                } else {
                    throw new JsonException("Expected ',' or '}' at position " + i);
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char n = peek();
                if (n == ',') {
                    i++;
                } else if (n == ']') {
                    i++;
                    break;
                } else {
                    throw new JsonException("Expected ',' or ']' at position " + i);
                }
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default:
                            throw new JsonException("Invalid escape sequence: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            } else if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Invalid literal at position " + i);
        }

        Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw new JsonException("Invalid literal at position " + i);
        }

        Double parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || num.equals("-")) {
                throw new JsonException("Invalid number at position " + start);
            }
            return Double.parseDouble(num);
        }

        char peek() {
            if (i >= s.length()) {
                throw new JsonException("Unexpected end of input");
            }
            return s.charAt(i);
        }

        void expect(char c) {
            skipWhitespace();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new JsonException("Expected '" + c + "' at position " + i);
            }
            i++;
        }
    }

    // ---------- Writing ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 0, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb, int indent, boolean pretty) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            writeObject((Map<String, Object>) value, sb, indent, pretty);
        } else if (value instanceof List) {
            writeArray((List<Object>) value, sb, indent, pretty);
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void writeObject(Map<String, Object> map, StringBuilder sb, int indent, boolean pretty) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{");
        if (pretty) sb.append('\n');
        int count = 0;
        int size = map.size();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (pretty) indent(sb, indent + 1);
            writeString(e.getKey(), sb);
            sb.append(pretty ? ": " : ":");
            writeValue(e.getValue(), sb, indent + 1, pretty);
            count++;
            if (count < size) sb.append(",");
            if (pretty) sb.append('\n');
        }
        if (pretty) indent(sb, indent);
        sb.append("}");
    }

    private static void writeArray(List<Object> list, StringBuilder sb, int indent, boolean pretty) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[");
        if (pretty) sb.append('\n');
        for (int idx = 0; idx < list.size(); idx++) {
            if (pretty) indent(sb, indent + 1);
            writeValue(list.get(idx), sb, indent + 1, pretty);
            if (idx < list.size() - 1) sb.append(",");
            if (pretty) sb.append('\n');
        }
        if (pretty) indent(sb, indent);
        sb.append("]");
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
