"""Check the actual APK for bundled OCR entry points and offline model resources."""

import struct
import sys
import zipfile


REQUIRED_CLASSES = {
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/chinese/ModuleDescriptor;",
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/common/ModuleDescriptor;",
    "Lcom/google/mlkit/vision/text/bundled/common/BundledTextRecognizerCreator;",
    "Lcom/google/android/libraries/vision/visionkit/pipeline/alt/NativePipelineImpl;",
}
CHINESE_MODEL = (
    "assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/"
    "Hani_ctc/optical/conv_model.fb"
)


def defined_classes(dex):
    """Read class definitions, not string references that can survive a removed class."""
    if not dex.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")
    string_offset = struct.unpack_from("<I", dex, 0x3C)[0]
    type_offset = struct.unpack_from("<I", dex, 0x44)[0]
    class_count, class_offset = struct.unpack_from("<II", dex, 0x60)
    result = set()
    for index in range(class_count):
        type_index = struct.unpack_from("<I", dex, class_offset + index * 32)[0]
        string_index = struct.unpack_from("<I", dex, type_offset + type_index * 4)[0]
        offset = struct.unpack_from("<I", dex, string_offset + string_index * 4)[0]
        # Skip the unsigned LEB128 UTF-16 length; descriptors here are ASCII.
        while dex[offset] & 0x80:
            offset += 1
        offset += 1
        result.add(dex[offset:dex.index(b"\0", offset)].decode("utf-8"))
    return result


def verify(archive):
    names = set(archive.namelist())
    classes = set()
    for name in sorted(names):
        if name.startswith("classes") and name.endswith(".dex"):
            classes.update(defined_classes(archive.read(name)))
    missing = sorted(REQUIRED_CLASSES - classes)
    if missing:
        raise ValueError("Missing bundled OCR classes after shrinking: " + ", ".join(missing))
    if CHINESE_MODEL not in names:
        raise ValueError("Missing bundled Chinese OCR model")
    abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
    if not abis:
        raise ValueError("APK has no native libraries")
    for abi in sorted(abis):
        if f"lib/{abi}/libmlkit_google_ocr_pipeline.so" not in names:
            raise ValueError(f"Missing native OCR pipeline for {abi}")
    return sorted(abis)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify_bundled_ocr.py APK [APK ...]")
    for path in sys.argv[1:]:
        with zipfile.ZipFile(path) as archive:
            abis = verify(archive)
        print(f"Bundled OCR packaging verified: {path} ({', '.join(abis)})")
