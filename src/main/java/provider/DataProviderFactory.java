/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package provider;

import config.YamlConfig;
import provider.json.JsonWZFile;
import provider.wz.WZFiles;
import provider.wz.XMLWZFile;

import java.nio.file.Files;
import java.nio.file.Path;

public class DataProviderFactory {
    /**
     * The assets folder the Voyage extractor made (one JSON file per .img, beside the pictures the client loads):
     * ASSETS_PATH in config.yaml, or the ASSETS_PATH environment variable, which wins. The game data is read from it,
     * and a file in its customs folder (assets/customs, laid out the same way) is read in place of the one of the same
     * name. Without it, the wz folder's XML as upstream.
     */
    private static final Path ASSETS = assetsDirectory();

    private static Path assetsDirectory() {
        String path = System.getenv("ASSETS_PATH");
        if ((path == null || path.isBlank()) && System.getProperty("wz-path") != null) {
            return null;   // a wz folder named outright (-Dwz-path, as the tests do): its XML
        }
        if (path == null || path.isBlank()) {
            path = YamlConfig.config.server.ASSETS_PATH;
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("ASSETS_PATH is " + dir + ", which is not a folder. Point it at the assets folder the Voyage extractor made.");
        }
        return dir;
    }

    /** The assets folder, or null when the game data comes from the wz folder. */
    public static Path assetsFolder() {
        return ASSETS;
    }

    /** The customs folder inside the assets folder, or null when the game data comes from the wz folder. */
    public static Path customsFolder() {
        return ASSETS != null ? ASSETS.resolve("customs") : null;
    }

    public static DataProvider getDataProvider(WZFiles in) {
        if (ASSETS != null) {
            return new JsonWZFile(ASSETS.resolve(in.getName()), customsFolder().resolve(in.getName()));
        }
        return new XMLWZFile(in.getFile());
    }
}
