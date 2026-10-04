package net.java21.data2flow.core.bim;

import net.java21.data2flow.core.bim.domain.IfcFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** DSH-12.04 IFC 머리글·스키마·공간 요소 읽기(BR-DSH-21) */
class IfcFileTest {

    public static final String SAMPLE = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('ViewDefinition [CoordinationView]'),'2;1');
            FILE_NAME('lab.ifc','2026-10-04T00:00:00',(''),(''),'','','');
            FILE_SCHEMA(('IFC4'));
            ENDSEC;
            DATA;
            #1=IFCPROJECT('0YvctVUKr0kugbFTf53O9L',$,'실습동',$,$,$,$,$,$);
            #10=IFCSPACE('2O2Fr$t4X7Zf8NOew3FLOH',$,'301','실습실',$,$,$,$,.ELEMENT.,.INTERNAL.,$);
            #11=IFCSPACE('3hX1wFq5j9cR7m2kYp0aZb',$,'302',$,$,$,$,$,.ELEMENT.,.INTERNAL.,$);
            #12=IFCWALL('1kTvXnbbzCWw8lcMd1dR4o',$,'벽',$,$,$,$,$,$);
            ENDSEC;
            END-ISO-10303-21;
            """;

    @Test
    @DisplayName("[DSH-12.04] 머리글 ISO-10303-21 확인, FILE_SCHEMA IFC4, 요소 4개, IfcSpace 2개(GlobalId·이름)")
    void parse() {
        byte[] data = SAMPLE.getBytes(StandardCharsets.UTF_8);
        assertThat(IfcFile.looksLikeIfc(data)).isTrue();
        IfcFile.Parsed p = IfcFile.parse(data);
        assertThat(p.schema()).isEqualTo("IFC4");
        assertThat(p.elementCount()).isEqualTo(4);
        assertThat(p.spaces()).containsKeys("2O2Fr$t4X7Zf8NOew3FLOH", "3hX1wFq5j9cR7m2kYp0aZb");
        assertThat(p.spaces().get("2O2Fr$t4X7Zf8NOew3FLOH")).isEqualTo("301");
        assertThat(IfcFile.parse(SAMPLE.replace("'IFC4'", "'IFC4X3_ADD2'").getBytes(StandardCharsets.UTF_8)).schema()).isEqualTo("IFC4X3");
        assertThat(IfcFile.parse(SAMPLE.replace("'IFC4'", "'IFC2X3'").getBytes(StandardCharsets.UTF_8)).schema()).isEqualTo("IFC2X3");
        assertThat(IfcFile.parse(SAMPLE.replace("'IFC4'", "'CONFIG_CONTROL_DESIGN'").getBytes(StandardCharsets.UTF_8)).schema()).isNull();
        assertThat(IfcFile.looksLikeIfc("PK\u0003\u0004zip".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(IfcFile.looksLikeIfc(("﻿" + SAMPLE).getBytes(StandardCharsets.UTF_8))).isTrue();
    }
}
