package net.java21.data2flow.core.device.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @Test
    @DisplayName("[DEV-02.04] CSV 읽기: BOM·따옴표·칸 안 쉼표·\"\"·CRLF, 빈 줄 건너뛰고 줄 번호 유지")
    void parses() {
        List<Csv.Line> lines = Csv.parse("﻿a,b,c\r\n1,\"x,y\",\"say \"\"hi\"\"\"\r\n\r\n2,,last");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).cells()).containsExactly("a", "b", "c");
        assertThat(lines.get(1).cells()).containsExactly("1", "x,y", "say \"hi\"");
        assertThat(lines.get(2).number()).isEqualTo(4);
        assertThat(lines.get(2).cells()).containsExactly("2", "", "last");
        assertThat(Csv.parse("q\n\"multi\nline\",z\n").get(1).cells()).containsExactly("multi\nline", "z");
    }

    @Test
    @DisplayName("[DEV-02.04] CSV 쓰기: 특수 문자는 따옴표, 수식 주입(=,+,-,@) 방지")
    void writes() {
        assertThat(Csv.row(List.of("a", "b,c", "=SUM(A1)", "q\"t"))).isEqualTo("a,\"b,c\",'=SUM(A1),\"q\"\"t\"\r\n");
        assertThat(Csv.cell(null)).isEmpty();
    }
}
