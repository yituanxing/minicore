"""Executable G1 rename/rollback *specification*, not Chisel RTL.

Models physical mappings, commit-time stale register release and squash.
Does not model issue timing, memory ordering, interrupts or FPGA PPA.
"""
from dataclasses import dataclass


@dataclass
class RenameEntry:
    seq: int
    token: tuple
    rd: int
    src: tuple
    old_phys: int | None
    dst_tag: tuple | None
    complete: bool = False
    fault: bool = False


class RenameReference:
    def __init__(self, rob_entries=8, physical_int=48):
        assert rob_entries >= 2 and physical_int >= 32
        self.rob_entries = rob_entries
        self.physical_int = physical_int
        self.spec_rat = list(range(32))
        self.committed_rat = list(range(32))
        self.free = set(range(32, physical_int))
        self.rob_gen = [0] * rob_entries
        self.phys_gen = [0] * physical_int
        self.live = []
        self.sequence = 0
        self.physical_values = {}

    def allocate_group(self, operations, *, iq_free=32, lsq_free=32, redirect=False):
        """Atomic two-lane proposal. Returns None without changing any state on failure.

        Each operation specifies rd, rs1, rs2 and optionally memory=True.
        x0 is physically pinned at zero, never allocated.
        """
        assert 1 <= len(operations) <= 2
        count = len(operations)
        writes = sum(1 for op in operations if op["rd"] != 0)
        memory = sum(1 for op in operations if op.get("memory", False))
        available_slots = sorted(
            set(range(self.rob_entries)) - {e.token[0] for e in self.live})
        if (redirect or len(available_slots) < count or iq_free < count
                or lsq_free < memory or len(self.free) < writes):
            return None

        entries = []
        for op, slot in zip(operations, available_slots):
            for register in ("rd", "rs1", "rs2"):
                assert 0 <= op[register] < 32
            src = (self.spec_rat[op["rs1"]], self.spec_rat[op["rs2"]])
            rd = op["rd"]
            old = None
            tag = None
            if rd:
                old = self.spec_rat[rd]
                physical = min(self.free)
                self.free.remove(physical)
                self.phys_gen[physical] += 1
                tag = (physical, self.phys_gen[physical])
                self.spec_rat[rd] = physical
            token = (slot, self.rob_gen[slot])
            entry = RenameEntry(self.sequence, token, rd, src, old, tag)
            self.sequence += 1
            self.live.append(entry)
            entries.append(entry)
        self.check()
        return entries

    def complete(self, token, dst_tag=None, value=0, fault=False):
        """Return False for killed/reused lifetime; never wake or write on stale tag."""
        entry = next((x for x in self.live if x.token == token), None)
        if entry is None or entry.complete or entry.dst_tag != dst_tag:
            return False
        entry.complete = True
        entry.fault = fault
        if dst_tag is not None and not fault:
            self.physical_values[dst_tag] = value
        return True

    def retire(self, width=1):
        """Retire a ready, normal oldest prefix; trap servicing belongs to Commit."""
        retired = []
        for _ in range(min(width, len(self.live))):
            entry = self.live[0]
            if not entry.complete or entry.fault:
                break
            self.live.pop(0)
            if entry.rd:
                self.committed_rat[entry.rd] = entry.dst_tag[0]
                # The old mapping becomes obsolete only at retirement.
                # Old committed architectural ids (1..31) are reclaimable,
                # not permanently reserved. Only physical zero is pinned.
                assert entry.old_phys != 0
                assert entry.old_phys not in self.free
                self.free.add(entry.old_phys)
            self.rob_gen[entry.token[0]] += 1
            retired.append(entry)
        self.check()
        return retired

    def _undo(self, entry):
        if entry.rd:
            assert self.spec_rat[entry.rd] == entry.dst_tag[0]
            self.spec_rat[entry.rd] = entry.old_phys
            assert entry.dst_tag[0] not in self.free
            self.free.add(entry.dst_tag[0])
        self.rob_gen[entry.token[0]] += 1

    def squash_after(self, token):
        """Non-head redirect: preserve branch and every older instruction."""
        matching = [i for i, item in enumerate(self.live) if item.token == token]
        if not matching:
            return None
        survivor_index = matching[0]
        killed = self.live[survivor_index + 1:]
        for entry in reversed(killed):
            self._undo(entry)
        del self.live[survivor_index + 1:]
        self.check()
        return killed

    def flush_uncommitted(self):
        """Trap: caller commits permitted older prefix, then flushes remaining work."""
        killed = list(self.live)
        for entry in reversed(killed):
            self._undo(entry)
        self.live.clear()
        assert self.spec_rat == self.committed_rat
        self.check()
        return killed

    def check(self):
        assert self.spec_rat[0] == self.committed_rat[0] == 0
        assert len(self.live) <= self.rob_entries
        assert len({e.token[0] for e in self.live}) == len(self.live)
        assert len({e.token for e in self.live}) == len(self.live)
        assert not self.free.intersection(self.spec_rat)
        assert not self.free.intersection(self.committed_rat)
        for entry in self.live:
            if entry.dst_tag:
                assert entry.dst_tag[0] not in self.free
        assert 0 not in self.free
        assert all(1 <= x < self.physical_int for x in self.free)
