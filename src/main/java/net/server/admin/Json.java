package net.server.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the admin page: text into maps, lists, strings, numbers, booleans and null, and back. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.space();
        Object value = p.value();
        p.space();
        if (p.i != p.s.length()) {
            throw p.bad();
        }
        return value;
    }

    private IllegalArgumentException bad() {
        return new IllegalArgumentException("That is not JSON.");
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private boolean at(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private Object value() {
        if (i >= s.length()) {
            throw bad();
        }
        return switch (s.charAt(i)) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Object word(String w, Object value) {
        if (!s.startsWith(w, i)) {
            throw bad();
        }
        i += w.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        space();
        if (at('}')) {
            i++;
            return map;
        }
        while (true) {
            space();
            String key = string();
            space();
            if (!at(':')) {
                throw bad();
            }
            i++;
            space();
            map.put(key, value());
            space();
            if (at(',')) {
                i++;
            } else if (at('}')) {
                i++;
                return map;
            } else {
                throw bad();
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        space();
        if (at(']')) {
            i++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (at(',')) {
                i++;
            } else if (at(']')) {
                i++;
                return list;
            } else {
                throw bad();
            }
        }
    }

    private String string() {
        if (!at('"')) {
            throw bad();
        }
        i++;
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw bad();
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw bad();
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
        throw bad();
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        try {
            if (n.matches("-?\\d{1,18}")) {
                return Long.parseLong(n);
            }
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw bad();
        }
    }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            b.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            b.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (v instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Collection<?> list) {
            b.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, o);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
