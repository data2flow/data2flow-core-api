package net.java21.data2flow.core.filestore.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Meta;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 파일 보관(첨부·사진·내보내기). 키는 {@code db:file_blobs/<id>}로 적어 두고, 오브젝트 저장소가 생기면 키만 바꿔 옮긴다.
 * 형식은 확장자가 아니라 내용(시그니처)으로 판정한다: 이미지(PNG·JPEG·WebP·HEIC)와 PDF만(작업 지시 첨부 UI-DEV-13 "이미지/PDF").
 */
@Service
public class StoredFiles {

    public static final String KEY_PREFIX = "db:file_blobs/";
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    private final FileBlobRepository blobs;
    private final Clock clock;

    public StoredFiles(FileBlobRepository blobs, Clock clock) {
        this.blobs = blobs;
        this.clock = clock;
    }

    /** 이미지·PDF를 검사해 저장하고 키를 돌려준다. 형식·크기가 틀리면 400 INVALID_REQUEST(field=file) */
    public Stored storeMedia(long organizationId, String purpose, String fileName, byte[] data, Long createdBy, boolean imagesOnly) {
        if (data == null || data.length == 0 || data.length > MAX_ATTACHMENT_BYTES) {
            throw invalid("Size");
        }
        String type = sniff(data);
        if (type == null || (imagesOnly && !type.startsWith("image/"))) {
            throw invalid("ContentType");
        }
        String name = fileName == null || fileName.isBlank() ? "file" : fileName.strip();
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        long id = blobs.insert(organizationId, purpose, name, type, data, createdBy, clock.instant());
        return new Stored(id, KEY_PREFIX + id, name, type, data.length);
    }

    /** 생성한 파일(내보내기 결과) 저장 */
    public Stored storeGenerated(long organizationId, String purpose, String fileName, String contentType, byte[] data, Long createdBy) {
        long id = blobs.insert(organizationId, purpose, fileName, contentType, data, createdBy, clock.instant());
        return new Stored(id, KEY_PREFIX + id, fileName, contentType, data.length);
    }

    public Optional<Blob> load(long organizationId, String key) {
        return idOf(key).flatMap(id -> blobs.find(organizationId, id));
    }

    public Optional<Meta> meta(long organizationId, String key) {
        return idOf(key).flatMap(id -> blobs.findMeta(organizationId, id));
    }

    public void delete(long organizationId, Collection<String> keys) {
        blobs.delete(organizationId, keys.stream().map(StoredFiles::idOf).flatMap(Optional::stream).toList());
    }

    public static Optional<Long> idOf(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(key.substring(KEY_PREFIX.length())));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** 내용으로 형식 판정. 모르는 형식이면 null */
    public static String sniff(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B'
                && d[11] == 'P') {
            return "image/webp";
        }
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p' && d[8] == 'h' && d[9] == 'e' && d[10] == 'i') {
            return "image/heic";
        }
        if (d.length >= 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F' && d[4] == '-') {
            return "application/pdf";
        }
        return null;
    }

    private static BusinessException invalid(String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("file", code, null)));
    }

    public record Stored(long id, String key, String fileName, String contentType, long sizeBytes) {
    }
}
