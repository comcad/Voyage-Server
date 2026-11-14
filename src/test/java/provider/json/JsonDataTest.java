package provider.json;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataTool;
import provider.wz.DataType;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JsonDataTest {
    private static final String IMG = """
            {"info":{"version":10,"mobRate":1.5,"fs":1,"bgm":"Bgm00/FloralLife","link":"100000000","none":null},
             "miniMap":{"canvas":{"z":0,"_w":179,"_h":321,"_canvas":"Map1/x.img/miniMap/canvas.png"},"width":1024},
             "portal":{"10":{"pn":"a"},"2":{"pn":"b"},"_order":["10","2"]},
             "seat":{"0":[12,-34]},
             "shape":[[1,2],[3,4],[5,6]],
             "ref":{"_uol":"../info/version"},
             "bgs":{"_sound":"x.mp3","_ms":120},
             "text":"line\\r\\nnext \\"quoted\\" \\u00e9"}""";

    private final Data img = JsonData.parse(IMG, "100000000.img");

    @Test
    void rootCarriesTheImgName() {
        assertEquals("100000000.img", img.getName());
        assertNull(img.getParent());
        assertEquals(DataType.PROPERTY, img.getType());
    }

    @Test
    void numbersReadAsAnyNumber() {
        assertEquals(10, DataTool.getInt("info/version", img));
        assertEquals(1.5, ((Number) img.getChildByPath("info/mobRate").getData()).doubleValue());
        assertEquals(DataType.FLOAT, img.getChildByPath("info/mobRate").getType());
        // read as the XML always was: with the unit-price locale on, a float loses its fraction
        assertEquals(config.YamlConfig.config.server.USE_UNITPRICE_WITH_COMMA ? 1.0f : 1.5f, DataTool.getFloat(img.getChildByPath("info/mobRate")));
        // a float that happens to be whole is written without a decimal point
        assertEquals(1.0f, DataTool.getFloat(img.getChildByPath("info/fs")));
        assertEquals(1.0, DataTool.getDouble(img.getChildByPath("info/fs")));
        assertEquals(100000000, DataTool.getIntConvert("info/link", img));
        assertEquals(7, DataTool.getInt("info/missing", img, 7));
    }

    @Test
    void stringsKeepTheirEscapes() {
        assertEquals("line\r\nnext \"quoted\" é", DataTool.getString("text", img));
        assertEquals(DataType.STRING, img.getChildByPath("text").getType());
        assertEquals("Bgm00/FloralLife", DataTool.getString("info/bgm", img, ""));
    }

    @Test
    void picturesHoldTheirOwnValuesOnly() {
        Data canvas = img.getChildByPath("miniMap/canvas");
        assertEquals(DataType.CANVAS, canvas.getType());
        assertEquals(List.of("z"), canvas.getChildren().stream().map(Data::getName).toList());
        assertEquals(179, ((JsonData) canvas).getWidth());
        assertEquals(321, ((JsonData) canvas).getHeight());
        assertNull(canvas.getData());
    }

    @Test
    void childrenKeepTheFilesOrder() {
        assertEquals(List.of("10", "2"), img.getChildByPath("portal").getChildren().stream().map(Data::getName).toList());
    }

    @Test
    void pointsShapesLinksSoundsAndNulls() {
        assertEquals(new Point(12, -34), DataTool.getPoint("seat/0", img));
        assertEquals(DataType.VECTOR, img.getChildByPath("seat/0").getType());
        Data shape = img.getChildByPath("shape");
        assertEquals(3, shape.getChildren().size());
        assertEquals("shape", shape.getChildren().get(1).getName());
        assertEquals(new Point(3, 4), shape.getChildren().get(1).getData());
        assertEquals(DataType.UOL, img.getChildByPath("ref").getType());
        assertEquals("../info/version", img.getChildByPath("ref").getData());
        assertEquals(DataType.SOUND, img.getChildByPath("bgs").getType());
        assertEquals(DataType.IMG_0x00, img.getChildByPath("info/none").getType());
        assertNull(img.getChildByPath("info/none").getData());
    }

    @Test
    void pathsWalkUpAndDown() {
        Data info = img.getChildByPath("info");
        assertSame(info, info.getChildByPath("version").getParent());
        assertEquals(1024, DataTool.getInt(info.getChildByPath("../miniMap/width")));
        assertNull(img.getChildByPath("info/nothing/here"));
    }
}
