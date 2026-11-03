"""Executable reference scenarios for the selected G1 rename and undo policy.

This is an algorithm-level oracle; passing these tests does NOT validate RTL.
"""
import unittest
from tests_py.product_g1_rename_reference import RenameReference


def op(rd=0, rs1=0, rs2=0, memory=False):
    return dict(rd=rd, rs1=rs1, rs2=rs2, memory=memory)


class ProductG1RenameReferenceTest(unittest.TestCase):
    def test_initial_state_x0_is_reserved_and_architectural(self):
        r = RenameReference()
        self.assertEqual(r.spec_rat, list(range(32)))
        self.assertEqual(r.committed_rat, list(range(32)))
        self.assertEqual(r.free, set(range(32, 48)))
        e = r.allocate_group([op(rd=0, rs1=0)])[0]
        self.assertEqual(e.src[0], 0)
        self.assertIsNone(e.dst_tag)
        self.assertTrue(r.complete(e.token))
        self.assertEqual(len(r.retire()), 1)
        self.assertEqual(r.spec_rat[0], 0)

    def test_dual_lane_raw_waw_and_commit_reclaims_old_mapping(self):
        r = RenameReference()
        a, b = r.allocate_group([op(rd=5), op(rd=5, rs1=5)])
        self.assertEqual(b.src[0], a.dst_tag[0])
        self.assertEqual(b.old_phys, a.dst_tag[0])
        self.assertEqual(r.spec_rat[5], b.dst_tag[0])
        self.assertNotIn(5, r.free)
        self.assertNotIn(a.dst_tag[0], r.free)
        self.assertTrue(r.complete(a.token, a.dst_tag, 123))
        self.assertTrue(r.complete(b.token, b.dst_tag, 456))
        self.assertNotIn(5, r.free, "completion must not free stale physical register")
        self.assertEqual(len(r.retire(2)), 2)
        self.assertEqual(r.committed_rat[5], b.dst_tag[0])
        self.assertIn(5, r.free)
        self.assertIn(a.dst_tag[0], r.free)
        self.assertNotIn(b.dst_tag[0], r.free)

    def test_failed_group_allocation_has_zero_mutations(self):
        for kwargs in ({"iq_free": 1}, {"lsq_free": 0}, {"redirect": True}):
            with self.subTest(kwargs=kwargs):
                r = RenameReference()
                state = (list(r.spec_rat), set(r.free), list(r.rob_gen), r.sequence)
                self.assertIsNone(r.allocate_group(
                    [op(rd=5, memory=True), op(rd=6)], **kwargs))
                self.assertEqual(
                    (r.spec_rat, r.free, r.rob_gen, r.sequence), state)
                self.assertEqual(r.live, [])
        small = RenameReference(rob_entries=8, physical_int=33)
        self.assertIsNone(small.allocate_group([op(rd=5), op(rd=6)]))
        self.assertEqual(small.spec_rat, list(range(32)))

    def test_non_head_branch_recovery_preserves_older_and_restores_rat(self):
        r = RenameReference()
        older = r.allocate_group([op(rd=5)])[0]
        branch = r.allocate_group([op()])[0]
        younger1, younger2 = r.allocate_group([op(rd=5), op(rd=7) ])
        oldtag = older.dst_tag
        stale_tag = younger1.dst_tag
        self.assertEqual(r.spec_rat[5], stale_tag[0])
        killed = r.squash_after(branch.token)
        self.assertEqual([e.token for e in killed], [
            younger1.token, younger2.token])
        self.assertEqual([e.token for e in r.live], [older.token, branch.token])
        self.assertEqual(r.spec_rat[5], oldtag[0])
        self.assertEqual(r.spec_rat[7], 7)
        self.assertIn(stale_tag[0], r.free)
        self.assertFalse(r.complete(younger1.token, stale_tag, 99))
        self.assertTrue(r.complete(older.token, older.dst_tag, 42))
        self.assertTrue(r.complete(branch.token))
        self.assertEqual(len(r.retire(2)), 2)

    def test_recycled_rob_slot_and_prf_tag_reject_late_completion(self):
        r = RenameReference(rob_entries=2, physical_int=33)
        branch = r.allocate_group([op()])[0]
        old = r.allocate_group([op(rd=5)])[0]
        self.assertEqual(len(r.squash_after(branch.token)), 1)
        new = r.allocate_group([op(rd=6)])[0]
        self.assertEqual(old.token[0], new.token[0])
        self.assertNotEqual(old.token, new.token)
        self.assertEqual(old.dst_tag[0], new.dst_tag[0])
        self.assertNotEqual(old.dst_tag, new.dst_tag)
        self.assertFalse(r.complete(old.token, old.dst_tag, 999))
        self.assertFalse(new.complete)
        self.assertTrue(r.complete(new.token, new.dst_tag, 3))
        self.assertEqual(r.physical_values[new.dst_tag], 3)

    def test_fault_flush_restores_committed_mapping(self):
        r = RenameReference()
        older = r.allocate_group([op(rd=5)])[0]
        self.assertTrue(r.complete(older.token, older.dst_tag, 5))
        self.assertEqual(len(r.retire()), 1)
        younger = r.allocate_group([op(rd=5)])[0]
        fault = r.allocate_group([op(rd=8)])[0]
        self.assertTrue(r.complete(fault.token, fault.dst_tag, fault=True))
        before = dict(r.physical_values)
        self.assertEqual(len(r.flush_uncommitted()), 2)
        self.assertEqual(r.spec_rat, r.committed_rat)
        self.assertEqual(r.spec_rat[5], older.dst_tag[0])
        self.assertIn(younger.dst_tag[0], r.free)
        self.assertIn(fault.dst_tag[0], r.free)
        self.assertEqual(before, r.physical_values)

    def test_rob_and_prf_pressure_do_not_corrupt_prior_allocations(self):
        r = RenameReference(rob_entries=8, physical_int=48)
        allocated = []
        for _ in range(4):
            allocated += r.allocate_group([op(rd=5), op(rd=6)])
        self.assertEqual(len(allocated), 8)
        state = (list(r.spec_rat), set(r.free), r.sequence, len(r.live))
        self.assertIsNone(r.allocate_group([op(rd=7)]))
        self.assertEqual((r.spec_rat, r.free, r.sequence, len(r.live)), state)

    def test_bad_completion_tag_never_mutates_state(self):
        r = RenameReference()
        e = r.allocate_group([op(rd=4)])[0]
        self.assertFalse(r.complete(e.token, (e.dst_tag[0], e.dst_tag[1] + 1), 99))
        self.assertFalse(e.complete)
        self.assertEqual(r.physical_values, {})
        self.assertTrue(r.complete(e.token, e.dst_tag, 11))
        self.assertFalse(r.complete(e.token, e.dst_tag, 12))
        self.assertEqual(r.physical_values[e.dst_tag], 11)


if __name__ == "__main__":
    unittest.main()
