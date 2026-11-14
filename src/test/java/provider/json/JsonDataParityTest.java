package provider.json;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataTool;
import provider.wz.DataType;
import provider.wz.WZFiles;
import provider.wz.XMLWZFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads every .img the server reads both ways, from its XML folder and from the shared assets' JSON, and holds the
 * two to the same names, order and values. Runs only when both folders are given:
 * -Dparity-wz=<the server's wz folder> -Dparity-assets=<the assets folder>.
 */
class JsonDataParityTest {
    private final List<String> wrong = new ArrayList<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private long nodes, files, lastDigit, lineBreaks;

    @Test
    void jsonSaysWhatTheXmlSays() {
        String wz = System.getProperty("parity-wz"), assets = System.getProperty("parity-assets");
        Assumptions.assumeTrue(wz != null && assets != null, "needs -Dparity-wz and -Dparity-assets");
        for (WZFiles file : WZFiles.values()) {
            Path xml = Path.of(wz, file.getName() + ".wz"), json = Path.of(assets, file.getName());
            if (!Files.isDirectory(xml) && !Files.isDirectory(json)) {
                continue;   // List.wz: neither has it
            }
            DataProvider xs = new XMLWZFile(xml), js = new JsonWZFile(json);
            folder(file.getName(), xs, js, xs.getRoot(), js.getRoot(), "");
        }
        System.out.printf("[json parity] %d files, %d nodes; floats the XML reading gives another value than the real one: %d; texts whose line breaks the XML reader turns into spaces: %d; differences: %d %s%n",
                files, nodes, lastDigit, lineBreaks, wrong.size(), counts);
        Map<String, Integer> shown = new TreeMap<>();
        for (String line : wrong) {
            String kind = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
            if (shown.merge(kind, 1, Integer::sum) <= 12) System.out.println("[json parity]   " + (line.length() > 230 ? line.substring(0, 230) : line));
        }
        assertEquals(0, wrong.size(), "values that differ between the XML and the JSON");
    }

    private void miss(String kind, String at, Object xml, Object json) {
        counts.merge(kind, 1, Integer::sum);
        if (wrong.size() < 5000) {
            wrong.add(kind + " " + at + " | xml: " + xml + " | json: " + json);
        } else {
            wrong.add(kind);
        }
    }

    private void folder(String wz, DataProvider xs, DataProvider js, DataDirectoryEntry x, DataDirectoryEntry j, String at) {
        TreeSet<String> xf = new TreeSet<>(), jf = new TreeSet<>(), xd = new TreeSet<>(), jd = new TreeSet<>();
        for (DataFileEntry f : x.getFiles()) xf.add(f.getName());
        for (DataFileEntry f : j.getFiles()) jf.add(f.getName());
        for (DataDirectoryEntry d : x.getSubdirectories()) xd.add(d.getName());
        for (DataDirectoryEntry d : j.getSubdirectories()) jd.add(d.getName());
        if (!xf.equals(jf)) miss("files", wz + "/" + at, xf.size(), jf.size());
        if (!xd.equals(jd)) miss("folders", wz + "/" + at, xd, jd);
        for (String name : xf) {
            if (!jf.contains(name)) continue;
            files++;
            String path = at.isEmpty() ? name : at + "/" + name;
            node(xs.getData(path), js.getData(path), wz + "/" + path);
        }
        for (String name : xd) {
            if (!jd.contains(name)) continue;
            folder(wz, xs, js, (DataDirectoryEntry) x.getEntry(name), (DataDirectoryEntry) j.getEntry(name), at.isEmpty() ? name : at + "/" + name);
        }
    }

    private void kids(Data x, Data j, String at) {
        List<Data> xc = x.getChildren(), jc = j.getChildren();
        if (xc.size() != jc.size()) {
            miss("children", at, xc.size(), jc.size());
            return;
        }
        for (int i = 0; i < xc.size(); i++) {
            node(xc.get(i), jc.get(i), at + ":" + xc.get(i).getName());
        }
    }

    private void node(Data x, Data j, String at) {
        nodes++;
        if (!x.getName().equals(j.getName())) {
            miss("name", at, x.getName(), j.getName());
            return;
        }
        DataType t = x.getType();
        if (t == null) {   // a shape
            if (j.getType() != null) miss("type", at, null, j.getType()); else kids(x, j, at);
            return;
        }
        switch (t) {
            case PROPERTY, CANVAS -> {
                if (j.getType() != t) miss("type", at, t, j.getType()); else kids(x, j, at);
            }
            case INT, SHORT -> {
                if (!(j.getData() instanceof Integer || j.getData() instanceof Long) || ((Number) x.getData()).intValue() != ((Number) j.getData()).intValue())
                    miss("int", at, x.getData(), j.getData());
            }
            case FLOAT -> {
                if (!(j.getData() instanceof Number n)) { miss("float", at, x.getData(), j.getData()); break; }
                // what the server gets from each (DataTool), then how far the XML's own number is from the real one
                float a = DataTool.getFloat(x), b = DataTool.getFloat(j);
                if (a != b) miss("float", at, a, b);
                if ((Float) x.getData() != n.floatValue()) lastDigit++;
            }
            case DOUBLE -> {
                if (!(j.getData() instanceof Number n) || ((Double) x.getData()).doubleValue() != n.doubleValue()) miss("double", at, x.getData(), j.getData());
            }
            case STRING, UOL -> {
                if (j.getType() != t || !(j.getData() instanceof String text)) { miss(t == DataType.UOL ? "uol" : "string", at, x.getData(), j.getData()); break; }
                if (x.getData().equals(text)) break;
                // an XML reader hands an attribute's line breaks and tabs over as spaces; the JSON keeps them
                if (x.getData().equals(text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' '))) lineBreaks++;
                else miss(t == DataType.UOL ? "uol" : "string", at, x.getData(), text);
            }
            case VECTOR -> {
                if (!x.getData().equals(j.getData())) miss("vector", at, x.getData(), j.getData());
            }
            case SOUND, IMG_0x00 -> {
                if (j.getType() != t) miss("type", at, t, j.getType());
            }
            default -> miss("type", at, t, j.getType());
        }
    }
}
