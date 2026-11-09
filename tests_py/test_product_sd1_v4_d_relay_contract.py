"""D 8612M→8712M source-pinned RTL continuation, static gate only."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-d-8612-to-8712.yml"


class ProductSd1V4DRelayContract(unittest.TestCase):
    def test_exact_c_terminal_identity_and_debian_no_pass_boundary(self):
        text = WORKFLOW.read_text()
        for clause in (
            "product-sd1-v4-reviewed-8612-to-8712-five-stages",
            "C_RUN: '42549868862'",
            "C_HEAD: b410cae45bf924256ffcdffe3e54e76fbca8f155",
            "C_8612_ID: '13644949634'",
            "C_8612_ZIP: e6e7e30303f7ae6396d3a9cc9738652431845759bccd318e6f3f0dc2353aeea5",
            "AETHERCORE_SD1_V4_D_8612_HOST_SHA: 4aaafcfed2fb0f93e245cc4027bb31d1fdeef48d46f26de04c1b258446507071",
            "AETHERCORE_SD1_V4_D_8612_RTL_SHA: 7b43c292604073d4e9f26fc243e7da4f840878676a03449c0e09edf636310bf4",
            "AETHERCORE_SD1_V4_D_8612_CARD_SHA: 9bc9d9fab00a3daf605da5416d99367ef85ee9295a94716e3a98a36411c5a4a3",
            "source_cycles=8592000000",
            "debian_login_claim=0",
            ".status==\"completed\" and .conclusion==\"success\"",
            ".run_attempt==1",
            ".github/workflows/product-sd1-v4-c-8512-to-8612.yml",
            "v4_postcross_five_true_rtl_stages",
            "PRODUCT_SD1_V4_D_REAL_RTL_8712M_POSTCROSS_SAVED_NOT_DEBIAN_PASS",
        ):
            self.assertIn(clause, text)

    def test_five_contiguous_stages_upload_before_next_guest_cycle(self):
        text = WORKFLOW.read_text()
        previous_upload = text.index("V4_8612M_D_POSTCROSS_INPUT_AUTHENTICATED")
        for start, end in ((8612, 8632), (8632, 8652), (8652, 8672),
                           (8672, 8692), (8692, 8712)):
            advance = f"- name: Advance genuine v4 Product RTL {start}M to {end}M postcross"
            upload = f"- name: Upload complete v4 {end}M before any further guest RTL cycle"
            self.assertEqual(text.count(advance), 1)
            self.assertEqual(text.count(upload), 1)
            self.assertLess(previous_upload, text.index(advance))
            self.assertLess(text.index(advance), text.index(upload))
            previous_upload = text.index(upload)
        self.assertIn("for m in 8632 8652 8672 8692 8712", text)
        self.assertIn("prev=8612", text)


if __name__ == "__main__":
    unittest.main()
