"""Regression tests for reflected constructors that survive only as DEX references."""

import struct
import unittest

from verify_bundled_ocr import DexFile


NAME = "Ltest/Registrar;"


def dex_fixture(*, defined=True, access=0x10001, arguments=False, class_access=1,
                code=True, fields=False):
    """Minimal DEX sections, including a constructor method_id even when undefined.

    The removed-constructor case matches the old release: a surviving class or
    method reference must not be mistaken for an instantiable registrar.
    """
    data = bytearray(0x70)
    data[:8] = b"dex\n035\0"

    def append(value):
        offset = len(data)
        data.extend(value)
        return offset

    def uint(offset, value):
        struct.pack_into("<I", data, offset, value)

    def uleb(value):
        encoded = bytearray()
        while value >= 128:
            encoded.append((value & 127) | 128)
            value >>= 7
        encoded.append(value)
        return bytes(encoded)

    strings = [NAME, "V", "<init>", "unused"]
    string_ids = append(bytes(len(strings) * 4))
    uint(0x38, len(strings))
    uint(0x3C, string_ids)
    uint(0x40, 2)
    uint(0x44, append(struct.pack("<II", 0, 1)))
    uint(0x48, 1)
    proto = append(struct.pack("<III", 1, 1, 0))
    uint(0x4C, proto)
    uint(0x58, 2)
    # First method is unrelated: direct_method indices are cumulative deltas.
    uint(0x5C, append(struct.pack("<HHIHHI", 0, 0, 3, 0, 0, 2)))
    uint(0x60, 1)
    definition = append(struct.pack("<8I", 0, class_access, 0xFFFFFFFF, 0, 0, 0, 0, 0))
    uint(0x64, definition)
    for index, value in enumerate(strings):
        uint(string_ids + index * 4, append(uleb(len(value)) + value.encode() + b"\0"))
    if arguments:
        uint(proto + 8, append(struct.pack("<IH", 1, 0)))
    while len(data) % 4:
        data.append(0)
    # A code_item with return-void. The checker verifies definition/access, not bytecode.
    code_offset = append(struct.pack("<HHHHIIH", 1, 1, 0, 0, 0, 1, 0x0E)) if code else 0
    members = uleb(int(fields)) + uleb(0) + uleb(int(defined)) + uleb(0)
    if fields:
        members += uleb(0) + uleb(1)
    if defined:
        members += uleb(1) + uleb(access) + uleb(code_offset)
    uint(definition + 24, append(members))
    uint(0x20, len(data))
    uint(0x24, 0x70)
    uint(0x28, 0x12345678)
    return bytes(data)


class ReflectedConstructorTest(unittest.TestCase):
    def constructors(self, **options):
        dex = DexFile(dex_fixture(**options))
        self.assertIn(NAME, dex.classes)
        return dex.public_no_arg_constructors({NAME})

    def test_public_default_constructor_survives(self):
        self.assertEqual({NAME}, self.constructors())

    def test_class_and_method_reference_without_definition_do_not_pass(self):
        self.assertEqual(set(), self.constructors(defined=False))

    def test_constructor_must_be_public_and_instance(self):
        for access in (0x10002, 0x10004, 0x10000, 0x10009, 0x10401):
            with self.subTest(access=access):
                self.assertEqual(set(), self.constructors(access=access))

    def test_only_parameterized_constructor_does_not_pass(self):
        self.assertEqual(set(), self.constructors(arguments=True))

    def test_abstract_or_nonpublic_class_cannot_be_instantiated(self):
        for access in (0, 0x401, 0x601):
            with self.subTest(access=access):
                self.assertEqual(set(), self.constructors(class_access=access))

    def test_constructor_without_implementation_does_not_pass(self):
        self.assertEqual(set(), self.constructors(code=False))

    def test_encoded_fields_do_not_hide_constructor(self):
        self.assertEqual({NAME}, self.constructors(fields=True))


if __name__ == "__main__":
    unittest.main()
