package net.java21.data2flow.core.bim.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.bim.domain.IfcFile;
import net.java21.data2flow.core.bim.dto.BimDtos.FloorItem;
import net.java21.data2flow.core.bim.dto.BimDtos.Mapping;
import net.java21.data2flow.core.bim.dto.BimDtos.MappingRequest;
import net.java21.data2flow.core.bim.dto.BimDtos.MappingResult;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelCreated;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelDetail;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelSummary;
import net.java21.data2flow.core.bim.dto.BimDtos.SpaceElement;
import net.java21.data2flow.core.bim.repository.BuildingModelRepository;
import net.java21.data2flow.core.bim.repository.BuildingModelRepository.MapRow;
import net.java21.data2flow.core.bim.repository.BuildingModelRepository.ModelRow;
import net.java21.data2flow.core.bim.repository.BuildingModelRepository.SpaceRow;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 층 전환 평면도와 IFC 건물 모델(DSH-12.04, BR-DSH-21, API-DSH-24).
 * <ul>
 *   <li>조회: DASHBOARD_READ(VIEWER+) + 건물 공간이 범위 안. 범위 밖·없는 공간 404 SPACE_NOT_FOUND</li>
 *   <li>쓰기(올리기·연결·삭제): DEV_ADMIN(INTEGRATOR+), 그 밖 403</li>
 *   <li>올리기: 200MB 초과 413 MODEL_FILE_INVALID, {@code ISO-10303-21} 머리글이 아니면 400 MODEL_FILE_INVALID.
 *       형식 확인 뒤 요소 수·스키마·공간 요소를 읽어 READY(스키마를 못 읽으면 FAILED)로 바꾼다(문서는 비동기 변환이지만 서버가 하는 일이
 *       머리글·요소 읽기뿐이라 요청 안에서 끝낸다. 3D 변환은 브라우저 web-ifc가 한다)</li>
 *   <li>연결: IFC 공간 요소 GlobalId(22자) ↔ 건물 아래 공간. 연결 없는 요소 수를 돌려준다(화면은 회색 표시)</li>
 * </ul>
 */
@Service
public class BuildingModelService {

    static final Set<String> BUILDING_TYPES = Set.of("BUILDING", "SITE");

    private final RoleChecker roleChecker;
    private final BuildingModelRepository repository;
    private final Audits audits;
    private final Clock clock;

    public BuildingModelService(RoleChecker roleChecker, BuildingModelRepository repository, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.audits = audits;
        this.clock = clock;
    }

    /** 층 목록(층 전환). 건물 아래 직속 층만 */
    @Transactional(readOnly = true)
    public List<FloorItem> floors(long buildingId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        building(user.organizationId(), buildingId);
        var scope = roleChecker.spaceScope();
        return repository.findFloors(user.organizationId(), buildingId).stream()
                .filter(f -> scope.unrestricted() || scope.includes(f.spaceId()))
                .map(f -> new FloorItem(Long.toString(f.spaceId()), f.name(), f.code(), f.sortOrder(), f.floorplanId() != null,
                        f.floorplanId() == null ? null : "/api/v1/core/spaces/" + f.spaceId() + "/floorplan/image?v=" + f.floorplanVersion(),
                        f.width(), f.height()))
                .toList();
    }

    /** 올리기 — 201 */
    @Transactional
    public ModelCreated upload(long buildingId, String nameParam, String fileName, long size, byte[] data) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = building(user.organizationId(), buildingId);
        roleChecker.require(Permission.DEV_ADMIN, building.id(), BoardErrorCode.SPACE_NOT_FOUND);
        if (size > IfcFile.MAX_BYTES) {
            throw new BusinessException(BoardErrorCode.MODEL_FILE_TOO_LARGE, List.of(new FieldErrorDetail("file", "TOO_LARGE", "200MB")));
        }
        if (data == null || data.length == 0 || !IfcFile.looksLikeIfc(data)) {
            throw new BusinessException(BoardErrorCode.MODEL_FILE_INVALID, List.of(new FieldErrorDetail("file", "NOT_IFC", null)));
        }
        String name = nameParam != null && !nameParam.isBlank() ? nameParam.strip() : fileName != null && !fileName.isBlank() ? fileName : "model.ifc";
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        Instant now = clock.instant();
        long id = repository.insert(user.organizationId(), building.id(), name, data.length, user.userId(), now);
        repository.insertFile(user.organizationId(), id, sha256(data), data);
        IfcFile.Parsed parsed = IfcFile.parse(data);
        if (parsed.schema() == null) {
            repository.finish(user.organizationId(), id, "FAILED", null, parsed.elementCount(), "FILE_SCHEMA가 없거나 지원하지 않는 IFC 스키마입니다", now);
        } else {
            repository.finish(user.organizationId(), id, "READY", parsed.schema(), parsed.elementCount(), null, now);
        }
        audits.record(audits.event(user.organizationId(), "BUILDING_MODEL_UPLOADED").actor(user).target("BUILDING_MODEL", Long.toString(id))
                .detail("buildingId", Long.toString(building.id())).detail("sizeBytes", data.length).detail("schema", parsed.schema()));
        ModelRow row = repository.find(user.organizationId(), building.id(), id).orElseThrow();
        return new ModelCreated(Long.toString(id), row.name(), row.sizeBytes(), row.status(), row.version());
    }

    @Transactional(readOnly = true)
    public List<ModelSummary> list(long buildingId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = visibleBuilding(user.organizationId(), buildingId);
        return repository.list(user.organizationId(), building.id()).stream()
                .map(m -> new ModelSummary(Long.toString(m.id()), m.name(), m.sizeBytes(), m.ifcSchema(), m.status(), m.elementCount(), m.version(),
                        m.createdAt())).toList();
    }

    @Transactional(readOnly = true)
    public ModelDetail get(long buildingId, long modelId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = visibleBuilding(user.organizationId(), buildingId);
        ModelRow m = model(user.organizationId(), building.id(), modelId);
        List<Mapping> mappings = repository.findMappings(user.organizationId(), m.id()).stream()
                .map(r -> new Mapping(r.ifcGlobalId(), Long.toString(r.spaceId()))).toList();
        List<SpaceElement> elements = new ArrayList<>();
        if ("READY".equals(m.status())) {
            repository.findFile(user.organizationId(), m.id()).ifPresent(d ->
                    IfcFile.parse(d).spaces().forEach((gid, name) -> elements.add(new SpaceElement(gid, name))));
        }
        return new ModelDetail(Long.toString(m.id()), m.name(), m.ifcSchema(), m.status(), m.error(), m.elementCount(),
                "/api/v1/core/buildings/" + building.id() + "/models/" + m.id() + "/file", mappings, elements, m.version());
    }

    /** 원본 내려받기(브라우저 IFC 뷰어) */
    @Transactional(readOnly = true)
    public byte[] file(long buildingId, long modelId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = visibleBuilding(user.organizationId(), buildingId);
        ModelRow m = model(user.organizationId(), building.id(), modelId);
        return repository.findFile(user.organizationId(), m.id()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 공간 연결 바꾸기 */
    @Transactional
    public MappingResult map(long buildingId, long modelId, MappingRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = visibleBuilding(user.organizationId(), buildingId);
        ModelRow m = model(user.organizationId(), building.id(), modelId);
        if (!"READY".equals(m.status())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("status", "NOT_READY", m.status())));
        }
        Map<String, String> elements = repository.findFile(user.organizationId(), m.id()).map(d -> IfcFile.parse(d).spaces()).orElse(Map.of());
        List<FieldErrorDetail> errors = new ArrayList<>();
        Map<String, MapRow> rows = new LinkedHashMap<>();
        List<Mapping> mappings = req == null || req.mappings() == null ? List.of() : req.mappings();
        int i = 0;
        for (Mapping mp : mappings) {
            String f = "mappings[" + i++ + "]";
            String gid = mp.ifcGlobalId() == null ? "" : mp.ifcGlobalId();
            if (!gid.matches("[0-9A-Za-z_$]{22}") || !elements.isEmpty() && !elements.containsKey(gid)) {
                errors.add(new FieldErrorDetail(f + ".ifcGlobalId", "INVALID", gid));
                continue;
            }
            String sid = mp.spaceId() == null ? "" : mp.spaceId();
            if (!sid.matches("\\d{1,18}")) {
                errors.add(new FieldErrorDetail(f + ".spaceId", "INVALID", sid));
                continue;
            }
            SpaceRow space = repository.findSpace(user.organizationId(), Long.parseLong(sid)).orElse(null);
            if (space == null || !space.path().startsWith(building.path()) || !roleChecker.spaceScope().includes(space.id())) {
                errors.add(new FieldErrorDetail(f + ".spaceId", "NOT_FOUND", sid));
                continue;
            }
            rows.put(gid, new MapRow(gid, space.id()));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        repository.replaceMappings(user.organizationId(), m.id(), List.copyOf(rows.values()), clock.instant());
        audits.record(audits.event(user.organizationId(), "BUILDING_MODEL_MAPPED").actor(user).target("BUILDING_MODEL", Long.toString(m.id()))
                .detail("mapped", rows.size()));
        int unmapped = (int) elements.keySet().stream().filter(g -> !rows.containsKey(g)).count();
        return new MappingResult(Long.toString(m.id()), rows.size(), unmapped);
    }

    /** 지우기 — 204 */
    @Transactional
    public void delete(long buildingId, long modelId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        SpaceRow building = visibleBuilding(user.organizationId(), buildingId);
        ModelRow m = model(user.organizationId(), building.id(), modelId);
        repository.delete(user.organizationId(), m.id());
        audits.record(audits.event(user.organizationId(), "BUILDING_MODEL_DELETED").actor(user).target("BUILDING_MODEL", Long.toString(m.id())));
    }

    private SpaceRow visibleBuilding(long org, long buildingId) {
        SpaceRow b = building(org, buildingId);
        roleChecker.requireSpace(b.id(), BoardErrorCode.SPACE_NOT_FOUND);
        return b;
    }

    private SpaceRow building(long org, long buildingId) {
        SpaceRow b = repository.findSpace(org, buildingId).orElseThrow(() -> new BusinessException(BoardErrorCode.SPACE_NOT_FOUND));
        if (!roleChecker.spaceScope().includes(b.id())) {
            throw new BusinessException(BoardErrorCode.SPACE_NOT_FOUND);
        }
        if (!BUILDING_TYPES.contains(b.type())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("buildingId", "NOT_BUILDING", b.type())));
        }
        return b;
    }

    private ModelRow model(long org, long buildingId, long modelId) {
        return repository.find(org, buildingId, modelId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", ex);
        }
    }
}
