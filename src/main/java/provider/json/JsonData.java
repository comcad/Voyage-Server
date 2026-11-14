package provider.json;

import provider.Data;
import provider.DataEntity;
import provider.wz.DataType;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One node of an .img, read from the JSON the client's assets hold (assets/Map/Map/Map1/100000000.img.json), so the
 * server and the client can share one copy of the game data.
 *
 * The JSON keeps values, not their WZ types, so a number is an INT when it is whole and a FLOAT otherwise, whatever
 * it was (short, int, float, double): read numbers through DataTool, which takes any of them. Everything else maps
 * as the XML does: an object is a PROPERTY, one with "_canvas" a CANVAS, {"_uol"} a UOL, {"_sound"} a SOUND, [x, y] a
 * VECTOR, a list of points a shape whose points carry its name, null an IMG_0x00. "_order" gives the children's own
 * order where JSON couldn't keep it.
 *
 * A tree never changes once read, so any number of threads can read it at once.
 */
public final class JsonData implements Data {
    private final String name;
    private final JsonData parent;
    private DataType type;
    private Object value;
    private List<JsonData> children = List.of();
    private volatile Map<String, JsonData> index;
    private int width;
    private int height;

    private JsonData(String name, JsonData parent) {
        this.name = name;
        this.parent = parent;
    }

    /** Reads an .img's JSON file. name is the .img's own name ("100000000.img"), which its root node carries. */
    public static JsonData read(Path file, String name) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), name);
    }

    public static JsonData parse(String json, String name) {
        return new Reader(json).root(name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataEntity getParent() {
        return parent;
    }

    @Override
    public DataType getType() {
        return type;
    }

    @Override
    public Object getData() {
        return value;
    }

    /** A picture's width; 0 for anything else. */
    public int getWidth() {
        return width;
    }

    /** A picture's height; 0 for anything else. */
    public int getHeight() {
        return height;
    }

    @Override
    public List<Data> getChildren() {
        return new ArrayList<>(children);
    }

    @Override
    public Iterator<Data> iterator() {
        return getChildren().iterator();
    }

    @Override
    public Data getChildByPath(String path) {
        String[] segments = path.split("/");
        if (segments[0].equals("..")) {
            return parent == null ? null : parent.getChildByPath(path.substring(path.indexOf("/") + 1));
        }
        JsonData node = this;
        for (String segment : segments) {
            node = node.child(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private JsonData child(String key) {
        List<JsonData> kids = children;
        if (kids.size() <= 12) {
            for (JsonData kid : kids) {
                if (kid.name.equals(key)) {
                    return kid;
                }
            }
            return null;
        }
        Map<String, JsonData> byName = index;
        if (byName == null) {
            byName = new HashMap<>(kids.size() * 2);
            for (JsonData kid : kids) {
                byName.putIfAbsent(kid.name, kid);
            }
            index = byName;
        }
        return byName.get(key);
    }

    /** A reader for the JSON the extractor writes: plain JSON, read straight into nodes. */
    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        JsonData root(String name) {
            JsonData node = new JsonData(name, null);
            space();
            value(node);
            return node;
        }

        private void space() {
            while (i < s.length() && s.charAt(i) <= ' ') {
                i++;
            }
        }

        private void value(JsonData node) {
            char c = s.charAt(i);
            if (c == '{') {
                object(node);
            } else if (c == '[') {
                list(node);
            } else if (c == '"') {
                node.type = DataType.STRING;
                node.value = string();
            } else if (c == 'n') {
                i += 4;
                node.type = DataType.IMG_0x00;
            } else if (c == 't') {
                i += 4;
                node.type = DataType.INT;
                node.value = 1;
            } else if (c == 'f') {
                i += 5;
                node.type = DataType.INT;
                node.value = 0;
            } else {
                number(node);
            }
        }

        private void object(JsonData node) {
            i++;
            List<JsonData> kids = new ArrayList<>();
            List<String> order = null;
            String canvas = null, uol = null, sound = null;
            space();
            if (s.charAt(i) == '}') {
                i++;
            } else {
                while (true) {
                    space();
                    String key = string();
                    space();
                    i++;
                    space();
                    switch (key) {
                        case "_order" -> order = strings();
                        case "_canvas" -> canvas = string();
                        case "_uol" -> uol = string();
                        case "_sound" -> sound = string();
                        case "_w" -> node.width = (int) plainNumber();
                        case "_h" -> node.height = (int) plainNumber();
                        case "_ms", "_fmt" -> plainNumber();
                        case "_err" -> string();
                        default -> {
                            JsonData kid = new JsonData(key, node);
                            value(kid);
                            kids.add(kid);
                        }
                    }
                    space();
                    if (s.charAt(i++) == '}') {
                        break;
                    }
                }
            }
            if (uol != null) {
                node.type = DataType.UOL;
                node.value = uol;
                return;
            }
            if (sound != null) {
                node.type = DataType.SOUND;
                return;
            }
            node.type = canvas != null ? DataType.CANVAS : DataType.PROPERTY;
            node.children = order != null ? inOrder(kids, order) : kids;
        }

        private static List<JsonData> inOrder(List<JsonData> kids, List<String> order) {
            Map<String, JsonData> byName = new HashMap<>(kids.size() * 2);
            for (JsonData kid : kids) {
                byName.putIfAbsent(kid.name, kid);
            }
            List<JsonData> out = new ArrayList<>(kids.size());
            for (String key : order) {
                JsonData kid = byName.remove(key);
                if (kid != null) {
                    out.add(kid);
                }
            }
            for (JsonData kid : kids) {
                if (byName.containsKey(kid.name)) {
                    out.add(kid);
                }
            }
            return out;
        }

        /** [x, y] is a point; any other list is a shape, whose members carry the shape's own name as the XML has them. */
        private void list(JsonData node) {
            i++;
            List<JsonData> kids = new ArrayList<>();
            space();
            if (s.charAt(i) == ']') {
                i++;
            } else {
                while (true) {
                    space();
                    JsonData kid = new JsonData(node.name, node);
                    value(kid);
                    kids.add(kid);
                    space();
                    if (s.charAt(i++) == ']') {
                        break;
                    }
                }
            }
            if (kids.size() == 2 && kids.get(0).value instanceof Number x && kids.get(1).value instanceof Number y) {
                node.type = DataType.VECTOR;
                node.value = new Point(x.intValue(), y.intValue());
                return;
            }
            node.children = kids;
        }

        private void number(JsonData node) {
            int start = i;
            boolean whole = true;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    whole = false;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                i++;
            }
            String text = s.substring(start, i);
            if (whole) {
                try {
                    long v = Long.parseLong(text);
                    node.type = DataType.INT;
                    if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                        node.value = Integer.valueOf((int) v);
                    } else {
                        node.value = Long.valueOf(v);
                    }
                    return;
                } catch (NumberFormatException tooLong) {
                    // falls through: a number too long for a long is a double
                }
            }
            node.type = DataType.FLOAT;
            node.value = Double.valueOf(text);
        }

        private double plainNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (!(c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9'))) {
                    break;
                }
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private List<String> strings() {
            i++;
            List<String> out = new ArrayList<>();
            space();
            if (s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                space();
                out.add(string());
                space();
                if (s.charAt(i++) == ']') {
                    return out;
                }
            }
        }

        private String string() {
            i++;
            int start = i;
            while (true) {
                char c = s.charAt(i);
                if (c == '"') {
                    String plain = s.substring(start, i);
                    i++;
                    return plain;
                }
                if (c == '\\') {
                    break;
                }
                i++;
            }
            StringBuilder out = new StringBuilder(s.substring(start, i));
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> out.append(e);
                }
            }
        }
    }
}
