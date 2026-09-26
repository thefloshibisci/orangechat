"""Check the actual APK for bundled OCR entry points and offline model resources."""

import struct
import sys
import zipfile


REQUIRED_CONSTRUCTORS = {
    "Lcom/google/mlkit/common/internal/CommonComponentRegistrar;",
    "Lcom/google/mlkit/vision/common/internal/VisionCommonRegistrar;",
    "Lcom/google/mlkit/vision/text/internal/TextRegistrar;",
    "Lcom/google/mlkit/vision/barcode/internal/BarcodeRegistrar;",
    "Lcom/google/mlkit/vision/text/bundled/common/BundledTextRecognizerCreator;",
}
REQUIRED_CLASSES = REQUIRED_CONSTRUCTORS | {
    "Lcom/google/mlkit/common/internal/MlKitInitProvider;",
    "Lcom/google/mlkit/common/internal/MlKitComponentDiscoveryService;",
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/chinese/ModuleDescriptor;",
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/common/ModuleDescriptor;",
    "Lcom/google/mlkit/vision/text/bundled/common/BundledTextRecognizerCreator;",
    "Lcom/google/android/libraries/vision/visionkit/pipeline/alt/NativePipelineImpl;",
}
CHINESE_MODEL = (
    "assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/"
    "Hani_ctc/optical/conv_model.fb"
)


class DexFile:
    """Read DEX definitions; references alone do not prove a member survived R8."""

    def __init__(self, data):
        if not data.startswith(b"dex\n"):
            raise ValueError("Unsupported DEX format")
        self.data = data
        self.string_offset = self.uint(0x3C)
        self.type_offset = self.uint(0x44)
        self.proto_offset = self.uint(0x4C)
        self.method_offset = self.uint(0x5C)
        self.classes = {}
        for index in range(self.uint(0x60)):
            offset = self.uint(0x64) + index * 32
            self.classes[self.type_name(self.uint(offset))] = offset

    def uint(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return value, offset
        raise ValueError("Invalid DEX unsigned LEB128")

    def string(self, index):
        offset = self.uint(self.string_offset + index * 4)
        _, offset = self.uleb(offset)  # UTF-16 length; names inspected here are ASCII.
        return self.data[offset:self.data.index(b"\0", offset)].decode("utf-8")

    def type_name(self, index):
        return self.string(self.uint(self.type_offset + index * 4))

    def public_no_arg_constructors(self, required):
        result = set()
        for name in required & self.classes.keys():
            definition = self.classes[name]
            flags = self.uint(definition + 4)
            offset = self.uint(definition + 24)
            if not offset or not flags & 1 or flags & 0x600:  # non-public/interface/abstract
                continue
            counts = []
            for _ in range(4):
                count, offset = self.uleb(offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):  # encoded fields precede methods
                _, offset = self.uleb(offset)
                _, offset = self.uleb(offset)
            method_index = 0
            for _ in range(counts[2]):  # constructors must be defined direct methods
                delta, offset = self.uleb(offset)
                access, offset = self.uleb(offset)
                code, offset = self.uleb(offset)
                method_index += delta
                owner, proto, method_name = struct.unpack_from(
                    "<HHI", self.data, self.method_offset + method_index * 8
                )
                if self.string(method_name) != "<init>" or self.type_name(owner) != name:
                    continue
                proto_offset = self.proto_offset + proto * 12
                parameters = self.uint(proto_offset + 8)
                no_arguments = not parameters or self.uint(parameters) == 0
                if (access & 1 and access & 0x10000 and not access & 0x508 and code
                        and no_arguments and self.type_name(self.uint(proto_offset + 4)) == "V"):
                    result.add(name)
        return result


def defined_classes(dex):
    return set(DexFile(dex).classes)


def verify(archive):
    names = set(archive.namelist())
    classes = set()
    constructors = set()
    for name in sorted(names):
        if name.startswith("classes") and name.endswith(".dex"):
            dex = DexFile(archive.read(name))
            classes.update(dex.classes)
            constructors.update(dex.public_no_arg_constructors(REQUIRED_CONSTRUCTORS))
    missing = sorted(REQUIRED_CLASSES - classes)
    if missing:
        raise ValueError("Missing bundled OCR classes after shrinking: " + ", ".join(missing))
    missing_constructors = sorted(REQUIRED_CONSTRUCTORS - constructors)
    if missing_constructors:
        raise ValueError("Missing public no-argument OCR constructors after shrinking: "
                         + ", ".join(missing_constructors))
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
