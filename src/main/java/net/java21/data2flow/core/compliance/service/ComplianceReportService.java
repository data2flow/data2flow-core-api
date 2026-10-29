package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ComplianceReportPublished;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.domain.ReportHash;
import net.java21.data2flow.core.compliance.domain.ReportHash.Sealed;
import net.java21.data2flow.core.compliance.domain.ReportModels;
import net.java21.data2flow.core.compliance.domain.ReportModels.DataRange;
import net.java21.data2flow.core.compliance.domain.ReportModels.Meta;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportPdfRenderer;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.CreateReportRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.FileLink;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.ReportCreated;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.ReportView;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.RevisionView;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.VerifyResult;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.ReportRow;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.RevisionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.service.ComplianceReportGenerator.Target;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.filestore.service.StoredFiles.Stored;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 준수 리포트(CMP-02.02·02.04·02.06, API-CMP-16~20a).
 *
 * <ul>
 *   <li>요청(CMP_REPORT)은 판을 GENERATING으로 넣고 202. 생성은 {@link ComplianceReportGenerator}가 한다. 같은 조건(유형·공간·기간·시설 유형)이면 같은
 *       리포트에 판이 하나 늘고(BR-CMP-12), 만드는 중인 판이 있으면 그 판을 돌려준다</li>
 *   <li>지금은 KR_SELF_CHECK만 만든다(RESET·WELL은 CMP-02.03). 기간은 400일 이하, 끝일은 오늘(조직 시간대)까지</li>
 *   <li>대상 공간 중 하나라도 범위 밖이면 리포트는 없는 것과 같다(404 CMP_REPORT_NOT_FOUND, BR-CMP-23)</li>
 *   <li>발행은 저장된 모델로 워터마크 없이 다시 그려 새 해시를 넣는다. 초안 파일은 지운다(BR-CMP-15·16)</li>
 *   <li>파일 링크는 10분 서명 URL이고 발급을 감사에 남긴다(BR-CMP-17). 원자료 CSV는 아직 만들지 않는다(404)</li>
 * </ul>
 */
@Service
public class ComplianceReportService {

    static final int MAX_SPACES = 100;
    static final long MAX_VERIFY_BYTES = 50L * 1024 * 1024;
    static final Duration LINK_TTL = Duration.ofMinutes(10);
    static final List<String> FILE_KINDS = List.of("pdf", "csv", "preview");
    static final List<String> STATUSES = List.of("GENERATING", "DRAFT", "PUBLISHED", "FAILED");

    private final ComplianceReportRepository reports;
    private final ComplianceReportGenerator generator;
    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceStatusRepository status;
    private final StoredFiles files;
    private final ReportLinkSigner signer;
    private final CoreEventPublisher events;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ComplianceReportService(ComplianceReportRepository reports, ComplianceReportGenerator generator, ComplianceApplicationRepository applications,
                                   ComplianceProfileRepository profiles, ComplianceStatusRepository status, StoredFiles files, ReportLinkSigner signer,
                                   CoreEventPublisher events, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.reports = reports;
        this.generator = generator;
        this.applications = applications;
        this.profiles = profiles;
        this.status = status;
        this.files = files;
        this.signer = signer;
        this.events = events;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-CMP-16 */
    @Transactional
    public ReportCreated create(CreateReportRequest request) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        return create(Actor.of(user), request);
    }

    /**
     * 행위자: 화면 요청이면 그 사용자(공간 범위를 검사한다), 정기 발행이면 일정을 만든 사용자 대신 서비스가 한다(범위는 일정 저장 때 검사했다).
     * 감사 행위자는 사용자 또는 SERVICE {@value #SCHEDULE_ACTOR}다
     */
    public record Actor(long orgId, long userId, CurrentUser user) {

        static Actor of(CurrentUser user) {
            return new Actor(user.organizationId(), user.userId(), user);
        }

        public static Actor schedule(long orgId, long ownerUserId) {
            return new Actor(orgId, ownerUserId, null);
        }

        AuditEvent.Builder apply(AuditEvent.Builder b) {
            return user != null ? b.actor(user) : b.actor(AuditActorType.SERVICE, SCHEDULE_ACTOR, null).detail("onBehalfOf", Long.toString(userId));
        }
    }

    public static final String SCHEDULE_ACTOR = "compliance-report-schedule";

    /** API-CMP-16 본체(정기 발행도 쓴다) */
    @Transactional
    public ReportCreated create(Actor actor, CreateReportRequest request) {
        long orgId = actor.orgId();
        if (request == null) {
            throw ProfileBodyReader.invalid("type", "NotNull");
        }
        String type = request.type() == null || request.type().isBlank() ? null : request.type().strip().toUpperCase(Locale.ROOT);
        if (type == null) {
            throw ProfileBodyReader.invalid("type", "NotNull");
        }
        if (!ReportModels.TYPES.contains(type)) {
            throw ProfileBodyReader.invalid("type", "Enum");
        }
        if (!ReportModels.KR_SELF_CHECK.equals(type)) {
            throw ProfileBodyReader.invalid("type", "Unsupported");
        }
        List<String> rawIds = request.spaceIds();
        if (rawIds == null || rawIds.isEmpty()) {
            throw ProfileBodyReader.invalid("spaceIds", "NotEmpty");
        }
        if (rawIds.size() > MAX_SPACES) {
            throw ProfileBodyReader.invalid("spaceIds", "Size");
        }
        TreeSet<Long> spaceIds = new TreeSet<>();
        for (String raw : rawIds) {
            Long id = id(raw, "spaceIds");
            if (id == null) {
                throw ProfileBodyReader.invalid("spaceIds", "NotNull");
            }
            applications.space(orgId, id).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
            if (actor.user() != null) {
                roleChecker.require(Permission.CMP_REPORT, id, ComplianceErrorCode.SPACE_NOT_FOUND);
            }
            spaceIds.add(id);
        }
        if (request.periodFrom() == null || request.periodFrom().isBlank()) {
            throw ProfileBodyReader.invalid("periodFrom", "NotBlank");
        }
        if (request.periodTo() == null || request.periodTo().isBlank()) {
            throw ProfileBodyReader.invalid("periodTo", "NotBlank");
        }
        LocalDate[] period = ComplianceStatusService.period(request.periodFrom(), request.periodTo());
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        if (period[1].isAfter(LocalDate.now(clock.withZone(zone)))) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_PERIOD_INVALID);
        }
        String title = request.title() == null || request.title().isBlank()
                ? "실내공기질 자가 점검 리포트 " + period[0] + " ~ " + period[1] : request.title().strip();
        if (title.length() > 200) {
            throw ProfileBodyReader.invalid("title", "Size");
        }
        JsonNode options = request.options();
        if (options != null && !options.isNull() && !options.isObject()) {
            throw ProfileBodyReader.invalid("options", "Type");
        }
        String optionsJson = options == null || options.isNull() ? "{}" : json.writeValueAsString(options);
        String facility = request.facilityType() == null || request.facilityType().isBlank() ? null
                : request.facilityType().strip().toUpperCase(Locale.ROOT);
        Target target = generator.target(orgId, spaceIds, facility);
        int[] devices = reports.deviceCounts(orgId, target.spaceIds());
        if (devices[0] == 0 && devices[1] > 0) {
            throw new BusinessException(ComplianceErrorCode.CMP_VIRTUAL_DATA_NOT_ALLOWED);
        }
        String key = dedupKey(type, spaceIds, period[0], period[1], target.facilityType());
        Instant now = clock.instant();
        ReportRow report = reports.findByDedup(orgId, key).orElse(null);
        if (report == null) {
            long id = reports.insertReport(orgId, type, title, spaceIds, target.facilityType(), period[0], period[1], optionsJson, key,
                    actor.userId(), now);
            report = id < 0 ? reports.findByDedup(orgId, key).orElseThrow() : reports.find(orgId, id).orElseThrow();
        } else {
            reports.touchReport(orgId, report.id(), title, optionsJson, now);
        }
        long reportId = report.id();
        RevisionRow generating = reports.revisions(orgId, List.of(reportId)).stream().filter(r -> "GENERATING".equals(r.status())).findFirst()
                .orElse(null);
        if (generating != null) {
            return new ReportCreated(Long.toString(reportId), Long.toString(generating.id()), generating.revisionNo(), generating.status());
        }
        RevisionRow revision = reports.insertRevision(orgId, reportId, actor.userId(), now);
        for (ApplicationRow a : target.applications()) {
            profiles.lockVersion(a.profileId(), a.effectiveVersionNo());
        }
        audits.record(actor.apply(audits.event(orgId, ComplianceAuditCodes.CMP_REPORT_REQUESTED)).target("COMPLIANCE_REPORT", Long.toString(reportId))
                .detail("revisionNo", revision.revisionNo()).detail("type", type).detail("periodFrom", period[0].toString())
                .detail("periodTo", period[1].toString()).detail("spaceIds", spaceIds.stream().map(String::valueOf).toList())
                .detail("facilityType", target.facilityType()));
        return new ReportCreated(Long.toString(reportId), Long.toString(revision.id()), revision.revisionNo(), revision.status());
    }

    /** API-CMP-17 목록(최근 수정 순). 마지막 판을 싣는다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ReportView> list(String type, String statusFilter, String spaceId, String from, String to, Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String t = type == null || type.isBlank() ? null : type.strip().toUpperCase(Locale.ROOT);
        if (t != null && !ReportModels.TYPES.contains(t)) {
            throw ProfileBodyReader.invalid("type", "Enum");
        }
        String s = statusFilter == null || statusFilter.isBlank() ? null : statusFilter.strip().toUpperCase(Locale.ROOT);
        if (s != null && !STATUSES.contains(s)) {
            throw ProfileBodyReader.invalid("status", "Enum");
        }
        LocalDate f = date(from, "from");
        LocalDate e = date(to, "to");
        SpaceScope scope = roleChecker.spaceScope();
        List<ReportRow> rows = reports.list(orgId, t, s, id(spaceId, "spaceId"), f, e, 2000, 0).stream().filter(r -> visible(scope, r)).toList();
        PageParams params = PageParams.of(page, size);
        int start = (int) Math.min(params.offset(), rows.size());
        int end = Math.min(start + params.size(), rows.size());
        List<ReportRow> slice = rows.subList(start, end);
        Map<Long, List<RevisionRow>> revisions = byReport(orgId, slice);
        List<ReportView> views = slice.stream().map(r -> view(r, revisions.getOrDefault(r.id(), List.of()), false)).toList();
        return ListApiResponse.of(params, views, rows.size());
    }

    /** API-CMP-17 상세(판 모두) */
    @Transactional(readOnly = true)
    public ReportView get(long reportId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        ReportRow report = report(orgId, reportId);
        return view(report, byReport(orgId, List.of(report)).getOrDefault(reportId, List.of()), true);
    }

    /** API-CMP-17 판 하나 */
    @Transactional(readOnly = true)
    public RevisionView revision(long reportId, int revisionNo) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        report(orgId, reportId);
        return revisionView(revision(orgId, reportId, revisionNo));
    }

    /** API-CMP-18 발행. DRAFT만 */
    @Transactional
    public RevisionView publish(long reportId, int revisionNo) {
        roleChecker.require(Permission.CMP_REPORT);
        return publish(Actor.of(roleChecker.currentUser()), reportId, revisionNo, null);
    }

    /** API-CMP-18 본체. recipients는 정기 발행 수신자(EVT-CMP-03, 화면 발행이면 null) */
    @Transactional
    public RevisionView publish(Actor actor, long reportId, int revisionNo, ComplianceReportPublished.Recipients recipients) {
        long orgId = actor.orgId();
        ReportRow report = actor.user() != null ? report(orgId, reportId)
                : reports.find(orgId, reportId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND));
        RevisionRow revision = revision(orgId, reportId, revisionNo);
        if (!"DRAFT".equals(revision.status())) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_STATE_CONFLICT);
        }
        Model model = json.readValue(reports.model(orgId, revision.id())
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_REPORT_STATE_CONFLICT)), Model.class);
        Instant now = clock.instant();
        String publisher = reports.userName(orgId, actor.userId()).orElse(null);
        byte[] pdf = ReportPdfRenderer.render(model, new Meta(reportId, revisionNo, revision.updatedAt(), revision.generatedByName(), now, publisher,
                false));
        Sealed sealed = ReportHash.seal(pdf);
        Stored stored = files.storeGenerated(orgId, ComplianceReportGenerator.PURPOSE, ComplianceReportGenerator.fileName(reportId, revisionNo),
                "application/pdf", sealed.pdf(), actor.userId());
        if (!reports.publish(orgId, revision.id(), stored.key(), sealed.sha256(), stored.sizeBytes(), actor.userId(), now)) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_STATE_CONFLICT);
        }
        if (revision.pdfObjectKey() != null) {
            files.delete(orgId, List.of(revision.pdfObjectKey()));
        }
        reports.touchReportUpdated(orgId, reportId, now);
        audits.record(actor.apply(audits.event(orgId, ComplianceAuditCodes.CMP_REPORT_PUBLISHED)).target("COMPLIANCE_REPORT", Long.toString(reportId))
                .detail("revisionNo", revisionNo).detail("sha256", sealed.sha256()));
        events.event(EventType.COMPLIANCE_REPORT_PUBLISHED, orgId, new ComplianceReportPublished(reportId, revisionNo,
                ComplianceReportPublished.Type.valueOf(report.type()), report.title(), report.periodFrom(), report.periodTo(), sealed.sha256(), recipients));
        return revisionView(revision(orgId, reportId, revisionNo));
    }

    /** API-CMP-19 파일 서명 링크(10분). pdf·preview는 CMP_READ, csv는 CMP_REPORT */
    @Transactional
    public FileLink file(long reportId, int revisionNo, String kind) {
        String k = kind(kind);
        roleChecker.require("csv".equals(k) ? Permission.CMP_REPORT : Permission.CMP_READ);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        report(orgId, reportId);
        RevisionRow revision = revision(orgId, reportId, revisionNo);
        String key = key(revision, k);
        Instant expires = clock.instant().plus(LINK_TTL);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_REPORT_DOWNLOADED).actor(user).target("COMPLIANCE_REPORT", Long.toString(reportId))
                .detail("revisionNo", revisionNo).detail("kind", k).detail("status", revision.status()));
        return new FileLink(signer.url(orgId, reportId, revisionNo, revision.id(), k, expires), "csv".equals(k) ? null : revision.sha256(),
                files.meta(orgId, key).map(m -> m.sizeBytes()).orElse(revision.sizeBytes()), expires);
    }

    /** 서명 링크로 받은 파일 내용 */
    public record Content(String fileName, String contentType, byte[] data, boolean inline) {
    }

    @Transactional(readOnly = true)
    public Content content(long reportId, int revisionNo, String kind, Long expires, String signature) {
        String k = kind(kind);
        roleChecker.require("csv".equals(k) ? Permission.CMP_REPORT : Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        report(orgId, reportId);
        RevisionRow revision = revision(orgId, reportId, revisionNo);
        if (expires == null || !signer.verify(orgId, revision.id(), k, expires, signature)
                || Instant.ofEpochSecond(expires).isBefore(clock.instant())) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        Blob blob = files.load(orgId, key(revision, k)).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND));
        return new Content(blob.fileName(), blob.contentType(), blob.data(), "preview".equals(k));
    }

    /** API-CMP-20 해시 검증. 파일 안 해시가 맞고 그 해시의 판이 이 조직(범위 안)에 있으면 matched */
    @Transactional(readOnly = true)
    public VerifyResult verifyHash(MultipartFile file) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (file == null || file.isEmpty()) {
            throw ProfileBodyReader.invalid("file", "NotNull");
        }
        if (file.getSize() > MAX_VERIFY_BYTES) {
            throw ProfileBodyReader.invalid("file", "Size");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (!"application/pdf".equals(StoredFiles.sniff(data))) {
            throw ProfileBodyReader.invalid("file", "ContentType");
        }
        VerifyResult none = new VerifyResult(false, null, null, null, null, null);
        String sha = ReportHash.verify(data).orElse(null);
        if (sha == null) {
            return none;
        }
        SpaceScope scope = roleChecker.spaceScope();
        return reports.findBySha(orgId, sha)
                .filter(r -> reports.find(orgId, r.reportId()).filter(rep -> visible(scope, rep)).isPresent())
                .map(r -> new VerifyResult(true, Long.toString(r.reportId()), r.revisionNo(), r.status(), r.publishedAt(), sha))
                .orElse(none);
    }

    /** API-CMP-20a 판 지우기. DRAFT·FAILED만, 판이 남지 않으면 리포트도 지운다 */
    @Transactional
    public void delete(long reportId, int revisionNo) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        report(orgId, reportId);
        RevisionRow revision = revision(orgId, reportId, revisionNo);
        if (!reports.deleteRevision(orgId, revision.id())) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_STATE_CONFLICT);
        }
        List<String> keys = new ArrayList<>();
        if (revision.pdfObjectKey() != null) {
            keys.add(revision.pdfObjectKey());
        }
        if (revision.csvObjectKey() != null) {
            keys.add(revision.csvObjectKey());
        }
        files.delete(orgId, keys);
        reports.deleteIfEmpty(orgId, reportId);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_REPORT_DELETED).actor(user).target("COMPLIANCE_REPORT", Long.toString(reportId))
                .detail("revisionNo", revisionNo).detail("status", revision.status()));
    }

    // ---- 내부

    /** 같은 조건이면 같은 키: sha256(유형|공간 ID 오름차순|기간|시설 유형) */
    static String dedupKey(String type, java.util.SortedSet<Long> spaceIds, LocalDate from, LocalDate to, String facility) {
        String ids = spaceIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return Tokens.sha256Hex(type + "|" + ids + "|" + from + "|" + to + "|" + (facility == null ? "" : facility));
    }

    private ReportRow report(long orgId, long reportId) {
        ReportRow r = reports.find(orgId, reportId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND));
        if (!visible(roleChecker.spaceScope(), r)) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND);
        }
        return r;
    }

    private RevisionRow revision(long orgId, long reportId, int revisionNo) {
        return reports.revision(orgId, reportId, revisionNo).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND));
    }

    /** BR-CMP-23: 대상 공간이 모두 범위 안이어야 보인다 */
    static boolean visible(SpaceScope scope, ReportRow r) {
        return scope.unrestricted() || r.spaceIds().stream().allMatch(scope::includes);
    }

    private Map<Long, List<RevisionRow>> byReport(long orgId, List<ReportRow> rows) {
        Map<Long, List<RevisionRow>> out = new LinkedHashMap<>();
        reports.revisions(orgId, rows.stream().map(ReportRow::id).toList())
                .forEach(r -> out.computeIfAbsent(r.reportId(), k -> new ArrayList<>()).add(r));
        return out;
    }

    private ReportView view(ReportRow r, List<RevisionRow> revisions, boolean all) {
        List<RevisionView> views = revisions.stream().map(this::revisionView).toList();
        return new ReportView(Long.toString(r.id()), r.type(), r.title(), r.spaceIds().stream().map(String::valueOf).toList(), r.facilityType(),
                r.periodFrom(), r.periodTo(), r.optionsJson() == null ? null : json.readTree(r.optionsJson()), r.createdAt(), r.updatedAt(),
                views.isEmpty() ? null : views.getFirst(), all ? views : null);
    }

    private RevisionView revisionView(RevisionRow r) {
        DataRange range = r.dataRangeJson() == null ? null : json.readValue(r.dataRangeJson(), DataRange.class);
        return new RevisionView(Long.toString(r.reportId()), r.revisionNo(), r.status(), r.progress(), r.sha256(), r.sizeBytes(), range,
                r.profileVersionIds().stream().map(String::valueOf).toList(),
                r.generatedBy() == null ? null : new UserRef(Long.toString(r.generatedBy()), r.generatedByName()),
                r.publishedBy() == null ? null : new UserRef(Long.toString(r.publishedBy()), r.publishedByName()), r.publishedAt(),
                r.failureReason(), r.createdAt(), r.updatedAt());
    }

    private static String kind(String raw) {
        String k = raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
        if (k == null || !FILE_KINDS.contains(k)) {
            throw ProfileBodyReader.invalid("kind", "Enum");
        }
        return k;
    }

    private static String key(RevisionRow r, String kind) {
        String key = "csv".equals(kind) ? r.csvObjectKey() : r.pdfObjectKey();
        if (key == null) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_NOT_FOUND);
        }
        return key;
    }

    private static LocalDate date(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException ex) {
            throw ProfileBodyReader.invalid(field, "Pattern");
        }
    }

    private static Long id(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw ProfileBodyReader.invalid(field, "Pattern");
        }
    }
}
