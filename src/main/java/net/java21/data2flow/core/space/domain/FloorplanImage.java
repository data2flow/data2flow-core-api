package net.java21.data2flow.core.space.domain;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 평면도 이미지 검사(DEV-01.03, API-DEV-09): 파일 이름·Content-Type이 아니라 내용(시그니처)으로 PNG·JPG·SVG를 가리고 크기를 읽는다.
 * 10MB 이하, PNG·JPG는 400×300 이상(UI-DEV-03 검증과 같음). SVG는 스크립트·이벤트 속성·foreignObject·외부 참조가 있으면 거부한다
 * (정화해서 바꾸지 않고 거부 — 저장한 원본을 그대로 내려 주므로 판정이 단순하고 우회 여지가 적다). DTD도 거부한다(XXE 방지).
 */
public final class FloorplanImage {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MIN_WIDTH = 400;
    public static final int MIN_HEIGHT = 300;
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String SVG = "image/svg+xml";

    private static final Pattern NUMBER = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(px)?\\s*$");

    private FloorplanImage() {
    }

    /** 검사를 통과한 이미지 정보 */
    public record Info(String contentType, int width, int height) {
    }

    /** 맞지 않으면 비어 있다(호출 쪽이 FLOORPLAN_IMAGE_INVALID) */
    public static Optional<Info> inspect(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            return Optional.empty();
        }
        Optional<Info> info;
        if (isPng(data)) {
            info = png(data);
        } else if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            info = jpeg(data);
        } else {
            return svg(data);
        }
        return info.filter(i -> i.width() >= MIN_WIDTH && i.height() >= MIN_HEIGHT);
    }

    private static boolean isPng(byte[] d) {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (d.length < 24) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (d[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Info> png(byte[] d) {
        if (d[12] != 'I' || d[13] != 'H' || d[14] != 'D' || d[15] != 'R') {
            return Optional.empty();
        }
        return Optional.of(new Info(PNG, int32(d, 16), int32(d, 20)));
    }

    private static Optional<Info> jpeg(byte[] d) {
        int i = 2;
        while (i + 3 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                return Optional.empty();
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty();
            }
            int length = int16(d, i + 2);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= d.length) {
                    return Optional.empty();
                }
                return Optional.of(new Info(JPEG, int16(d, i + 7), int16(d, i + 5)));
            }
            i += 2 + length;
        }
        return Optional.empty();
    }

    private static Optional<Info> svg(byte[] d) {
        String text = new String(d, StandardCharsets.UTF_8);
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("<svg") || lower.contains("<!doctype") || lower.contains("<!entity") || lower.contains("javascript:")) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Element root = builder.parse(new ByteArrayInputStream(d)).getDocumentElement();
            if (!"svg".equals(localName(root)) || !safe(root)) {
                return Optional.empty();
            }
            Integer width = length(root.getAttribute("width"));
            Integer height = length(root.getAttribute("height"));
            if (width == null || height == null) {
                String[] box = root.getAttribute("viewBox").trim().split("[\\s,]+");
                if (box.length == 4) {
                    width = width == null ? length(box[2]) : width;
                    height = height == null ? length(box[3]) : height;
                }
            }
            if (width == null || height == null || width <= 0 || height <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Info(SVG, width, height));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** 스크립트·이벤트 속성·foreignObject·외부 참조가 없는가 */
    private static boolean safe(Element element) {
        String name = localName(element).toLowerCase(Locale.ROOT);
        if (name.equals("script") || name.equals("foreignobject") || name.equals("iframe") || name.equals("embed")
                || name.equals("object")) {
            return false;
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String attrName = localName(attribute).toLowerCase(Locale.ROOT);
            String value = attribute.getNodeValue() == null ? "" : attribute.getNodeValue().strip().toLowerCase(Locale.ROOT);
            if (attrName.startsWith("on")) {
                return false;
            }
            if (attrName.equals("href") && !value.isEmpty() && !value.startsWith("#") && !value.startsWith("data:image/png")
                    && !value.startsWith("data:image/jpeg")) {
                return false;
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                return false;
            }
            if (child instanceof Element e && !safe(e)) {
                return false;
            }
        }
        return true;
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private static Integer length(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = NUMBER.matcher(raw);
        if (!m.matches()) {
            return null;
        }
        double value = Double.parseDouble(m.group(1));
        return value > 100_000 ? null : (int) Math.round(value);
    }

    private static int int32(byte[] d, int offset) {
        return ((d[offset] & 0xFF) << 24) | ((d[offset + 1] & 0xFF) << 16) | ((d[offset + 2] & 0xFF) << 8) | (d[offset + 3] & 0xFF);
    }

    private static int int16(byte[] d, int offset) {
        return ((d[offset] & 0xFF) << 8) | (d[offset + 1] & 0xFF);
    }
}
