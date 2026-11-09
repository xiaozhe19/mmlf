package dev.mmlf.launcher.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DevShaderStagingTest {
    @Test void stagesOnlyIntoTheSpecifiedGameDirectory(@TempDir Path temp) throws Exception {
        Path source=temp.resolve("test pack.zip"),game=temp.resolve("session/remote");
        Files.write(source,new byte[]{1,2,3});
        DevRunSpecs.stageTestShaderpack(game,source);
        assertArrayEquals(Files.readAllBytes(source),Files.readAllBytes(game.resolve("shaderpacks/test pack.zip")));
        Properties properties=new Properties();
        try(var reader=Files.newBufferedReader(game.resolve("config/iris.properties"),StandardCharsets.UTF_8)) { properties.load(reader); }
        assertEquals("true",properties.getProperty("enableShaders"));
        assertEquals("test pack.zip",properties.getProperty("shaderPack"));
        assertFalse(Files.exists(temp.resolve("config/iris.properties")));
    }
}
