package dev.morph.contracts;

import java.util.List;

/**
 * Formats known by Morph out of the box. Being listed here means "can be recognised", never "can be converted":
 * conversions exist only where a provider declares a {@link Capability}.
 */
public final class StandardFormats {

    public static final FormatDescriptor PNG = new FormatDescriptor(
            FormatId.of("png"), "PNG", FormatCategory.IMAGE, List.of("png"), "image/png",
            List.of(Signature.hex("89504e470d0a1a0a")));

    public static final FormatDescriptor JPEG = new FormatDescriptor(
            FormatId.of("jpeg"), "JPEG", FormatCategory.IMAGE, List.of("jpg", "jpeg", "jpe", "jfif"), "image/jpeg",
            List.of(Signature.hex("ffd8ff")));

    public static final FormatDescriptor WEBP = new FormatDescriptor(
            FormatId.of("webp"), "WebP", FormatCategory.IMAGE, List.of("webp"), "image/webp",
            List.of(new Signature(List.of(
                    new Signature.Part(0, "52494646"), new Signature.Part(8, "57454250")), 0, false)));

    public static final FormatDescriptor PDF = new FormatDescriptor(
            FormatId.of("pdf"), "PDF", FormatCategory.DOCUMENT, List.of("pdf"), "application/pdf",
            List.of(new Signature(List.of(new Signature.Part(0, "255044462d")), 1024, false)));

    /** Recognised so that `docx -> pdf` fails with a precise message until the office provider exists. */
    public static final FormatDescriptor DOCX = new FormatDescriptor(
            FormatId.of("docx"), "DOCX", FormatCategory.DOCUMENT, List.of("docx"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            List.of(Signature.containerHex("504b0304")));

    private StandardFormats() {
    }

    public static List<FormatDescriptor> all() {
        return List.of(PNG, JPEG, WEBP, PDF, DOCX);
    }
}
