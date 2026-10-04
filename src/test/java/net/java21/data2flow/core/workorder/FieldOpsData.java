package net.java21.data2flow.core.workorder;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

/** 작업 지시·현장 설치 시험 데이터(그룹·이벤트 아웃박스·감사) */
public final class FieldOpsData {

    /** 가장 작은 PNG(1×1) */
    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0,
            0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89};
    public static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes();

    private final JdbcClient jdbc;

    public FieldOpsData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 정적 그룹과 멤버 */
    public long staticGroup(long orgId, String name, List<Long> deviceIds) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.device_groups (organization_id, name, type, member_count) VALUES (:org, :name, 'STATIC', :n)
                        RETURNING id""")
                .param("org", orgId).param("name", name).param("n", deviceIds.size()).query(Long.class).single();
        for (long d : deviceIds) {
            jdbc.sql("INSERT INTO data2flow_core.device_group_members (organization_id, group_id, device_id, source) VALUES (:org, :g, :d, 'STATIC')")
                    .param("org", orgId).param("g", id).param("d", d).update();
        }
        return id;
    }

    /** 신원 헤더를 붙인 multipart 업로드(file 파트) */
    public static MockMultipartHttpServletRequestBuilder upload(String path, long orgId, long userId, String name, String type, byte[] data,
                                                                String idempotencyKey) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart(path).file(new MockMultipartFile("file", name, type, data));
        b.header("X-USER-ID", Long.toString(userId)).header("X-ORG-ID", Long.toString(orgId)).header("Accept-Language", "ko");
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return b;
    }

    public List<String> outboxPayloads(long orgId, String routingKey) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key ORDER BY id")
                .param("org", orgId).param("key", routingKey).query(String.class).list();
    }

    public long count(String sql, long orgId) {
        return jdbc.sql(sql).param("org", orgId).query(Long.class).single();
    }
}
