package net.java21.data2flow.core.dataexchange.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;

/**
 * SFTP 전달 대상(TSD-07.02, Apache MINA SSHD, Apache-2.0). 비밀번호 또는 개인 키(PEM/OpenSSH)로 로그인한다.
 * {@code hostKeySha256}(예: {@code SHA256:…})을 주면 서버 호스트 키 지문을 확인하고, 다르면 접속하지 않는다. 주지 않으면 처음 보는 키를 받는다
 * (연결 테스트 결과에 지문을 보여 주어 설정하게 한다).
 */
public final class SftpDeliveryTarget implements DeliveryTarget {

    /** 설정: host, port(기본 22), username, directory(선택), hostKeySha256(선택) */
    public record Settings(String host, int port, String username, String directory, String hostKeySha256, String password,
                           String privateKey) {
    }

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;
    private final String directory;
    private volatile String seenFingerprint;

    public SftpDeliveryTarget(Settings settings, Duration timeout) throws IOException {
        this.directory = settings.directory() == null ? "" : settings.directory().replaceAll("/+$", "");
        this.client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, address, key) -> {
            seenFingerprint = KeyUtils.getFingerPrint(key);
            return settings.hostKeySha256() == null || settings.hostKeySha256().isBlank()
                    || settings.hostKeySha256().strip().equals(seenFingerprint);
        });
        client.start();
        try {
            this.session = client.connect(settings.username(), settings.host(), settings.port() <= 0 ? 22 : settings.port())
                    .verify(timeout).getSession();
            if (settings.password() != null && !settings.password().isEmpty()) {
                session.addPasswordIdentity(settings.password());
            }
            if (settings.privateKey() != null && !settings.privateKey().isBlank()) {
                try (InputStream in = new ByteArrayInputStream(settings.privateKey().getBytes(StandardCharsets.UTF_8))) {
                    Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("export"), in, null);
                    if (keys != null) {
                        keys.forEach(session::addPublicKeyIdentity);
                    }
                } catch (GeneralSecurityException ex) {
                    throw new IOException("개인 키를 읽을 수 없습니다");
                }
            }
            session.auth().verify(timeout);
            this.sftp = SftpClientFactory.instance().createSftpClient(session);
        } catch (IOException | RuntimeException ex) {
            client.stop();
            throw ex instanceof IOException io ? io : new IOException(ex.getMessage(), ex);
        }
    }

    /** 접속 때 본 서버 호스트 키 지문 */
    public String fingerprint() {
        return seenFingerprint;
    }

    @Override
    public void put(String name, Path file, String contentType) throws IOException {
        try (OutputStream out = sftp.write(path(name))) {
            Files.copy(file, out);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        sftp.remove(path(name));
    }

    private String path(String name) {
        return directory.isEmpty() ? name : directory + "/" + name;
    }

    @Override
    public void close() {
        try {
            sftp.close();
        } catch (IOException ignored) {
            // 닫는 중 실패는 무시
        }
        try {
            session.close();
        } catch (IOException ignored) {
            // 닫는 중 실패는 무시
        }
        client.stop();
    }
}
