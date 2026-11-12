"""Small controls for complete-string parsing and the exemption-free welcome scan."""

import struct
import unittest

from dex_probe import WELCOME_PREFIXES, dex_strings, welcome_failures


class WelcomeDexProbeTest(unittest.TestCase):
    def test_complete_strings_preserve_line_endings_and_skip_uleb_length(self):
        values = [b"# Get started\n" + b"x" * 130, b"one\r\ntwo", b"bearingAccuracy"]
        dex = bytearray(112 + 4 * len(values))
        dex[:8] = b"dex\n035\x00"
        struct.pack_into("<II", dex, 56, len(values), 112)
        for index, value in enumerate(values):
            struct.pack_into("<I", dex, 112 + index * 4, len(dex))
            length = len(value)
            while length >= 0x80:
                dex.append((length & 0x7f) | 0x80)
                length >>= 7
            dex.append(length)
            dex.extend(value + b"\x00")
        self.assertEqual(values, dex_strings(bytes(dex)))
        with self.assertRaises(ValueError):
            dex_strings(b"not a dex")

    def test_each_body_is_required_and_branding_has_no_exemption(self):
        clean = [prefix + b"Duck text\n" for prefix in WELCOME_PREFIXES]
        self.assertEqual(4, len(clean))
        self.assertEqual([], welcome_failures(clean + [b"bearingAccuracy"]))
        for index in range(4):
            with self.subTest(body=index, control="missing"):
                self.assertTrue(welcome_failures(clean[:index] + clean[index + 1:]))
            for offender in (b"bear", b"BEAR", b"bearing", b"bear notes bearingAccuracy",
                             b"bearingAccuracy BEAR"):
                changed = clean.copy()
                changed[index] += offender
                with self.subTest(body=index, offender=offender):
                    self.assertTrue(any("forbidden branding" in failure
                                        for failure in welcome_failures(changed)))
            for ending in (b"\r\n", b"\r"):
                changed = clean.copy()
                changed[index] += ending
                with self.subTest(body=index, ending=ending):
                    self.assertTrue(any("contains CR" in failure
                                        for failure in welcome_failures(changed)))
        self.assertTrue(welcome_failures(clean + [clean[0]]))


if __name__ == "__main__":
    unittest.main()
