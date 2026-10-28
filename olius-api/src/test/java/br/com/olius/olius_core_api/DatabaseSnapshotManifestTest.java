package br.com.olius.olius_core_api;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSnapshotManifestTest {
    @Test
    void everyRecordedSnapshotHashMatchesItsResource() throws Exception {
        var manifest=JsonMapper.builder().build().readTree(new ClassPathResource(
                "db/olius/snapshot-manifest.json").getContentAsByteArray());
        assertThat(manifest.path("files").size()).isGreaterThanOrEqualTo(14);
        for(var file:manifest.path("files")) {
            String name=file.path("file").asText();
            byte[] contents=new ClassPathResource("db/olius/"+name).getContentAsByteArray();
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
            assertThat(actual).as("SHA-256 de %s",name).isEqualTo(file.path("sha256").asText());
        }
    }
}
