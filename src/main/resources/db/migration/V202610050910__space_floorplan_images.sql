-- =====================================================================
-- M2 공간(WP-A): 평면도 이미지 저장, 형제 공간 이름의 대소문자 무시 고유(DEV-01.01·01.03)
-- 문서와 다른 점:
--   * core에 오브젝트 저장소(S3 API) 클라이언트가 아직 없어 평면도 원본을 DB(bytea, ≤10MB)에 둔다.
--     floorplans.object_key에는 'db:floorplan_images/<floorplan_id>'를 적는다. 저장소가 생기면 키만 바꿔 옮긴다.
--   * 같은 부모 아래 이름은 대소문자·앞뒤 공백을 무시하고 고유하다(domain-model §2.1). 이름은 앞뒤 공백을 지워 저장한다.
-- expand 마이그레이션(추가만, ADR-030)
-- =====================================================================

CREATE TABLE data2flow_core.floorplan_images (
    floorplan_id    bigint       NOT NULL,
    organization_id bigint       NOT NULL,
    content_type    varchar(40)  NOT NULL,
    size_bytes      integer      NOT NULL,
    sha256          char(64)     NOT NULL,
    data            bytea        NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_floorplan_images PRIMARY KEY (floorplan_id),
    CONSTRAINT fk_floorplan_images_floorplan_id FOREIGN KEY (floorplan_id) REFERENCES data2flow_core.floorplans (id) ON DELETE CASCADE,
    CONSTRAINT ck_floorplan_images_content_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/svg+xml')),
    CONSTRAINT ck_floorplan_images_size CHECK (size_bytes BETWEEN 1 AND 10485760)
);

CREATE INDEX ix_floorplan_images_organization_id ON data2flow_core.floorplan_images (organization_id);

COMMENT ON TABLE data2flow_core.floorplan_images IS '평면도 원본 이미지(DEV-01.03). 오브젝트 저장소 도입 전 임시 보관(PNG·JPG·SVG ≤10MB, SVG는 스크립트 없는 것만)';

CREATE UNIQUE INDEX uq_spaces_organization_id_parent_id_lower_name
    ON data2flow_core.spaces (organization_id, coalesce(parent_id, 0), lower(name)) WHERE status = 'ACTIVE';
