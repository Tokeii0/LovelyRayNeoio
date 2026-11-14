package com.rayneo.customai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight zero-dependency RFC-8259 JSON parser and serializer.
 * Works identically on Android ART and desktop JDK for unit tests.
 */
public final class JsonMini {

    private JsonMini() {}

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        Parser p = new Parser(json);
        Object val = p.parseValue();
        p.skipWs();
        if (p.pos < p.len) {
            throw new IllegalArgumentException("Trailing JSON content at pos " + p.pos);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        throw new IllegalArgumentException("Expected JSON object");
    }

    public static String stringify(Object val) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, val);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    public static String getString(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static Map<String, Object> getMap(Map<String, Object> m, String key) {
        if (m == null) return null;
        return asMap(m.get(key));
    }

    public static List<Object> getList(Map<String, Object> m, String key) {
        if (m == null) return null;
        return asList(m.get(key));
    }

    private static void writeValue(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("null");
        } else if (val instanceof Boolean) {
            sb.append(((Boolean) val) ? "true" : "false");
        } else if (val instanceof Number) {
            if (val instanceof Double) {
                double d = (Double) val;
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(val);
                }
            } else {
                sb.append(val);
            }
        } else if (val instanceof String) {
            writeString(sb, (String) val);
        } else if (val instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) val).entrySet()) {
                if (e.getKey() == null) continue;
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (val instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) val) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(val));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
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

    private static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
            this.pos = 0;
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expectLiteral("true"); return Boolean.TRUE;
                case 'f': expectLiteral("false"); return Boolean.FALSE;
                case 'n': expectLiteral("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at " + pos);
            }
        }

        Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= len || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at " + pos);
                }
                String key = parseString();
                skipWs();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + pos);
                }
                pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + (pos - 1));
                }
            }
        }

        List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + (pos - 1));
                }
            }
        }

        String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        void expectLiteral(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected " + lit + " at " + pos);
            }
            pos += lit.length();
        }

        Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean isFloat = false;
            while (pos < len) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '-' || c == '+') {
                        char prev = s.charAt(pos - 1);
                        if (prev != 'e' && prev != 'E') break;
                    }
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (isFloat) {
                return Double.valueOf(num);
            }
            try {
                return Long.valueOf(num);
            } catch (NumberFormatException e) {
                return Double.valueOf(num);
            }
        }
    }
}
