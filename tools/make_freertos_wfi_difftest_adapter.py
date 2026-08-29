#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: expected exactly one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def adapt(source: str) -> str:
    text = source
    text = replace_once(
        text,
        "constexpr std::uint32_t kEcall = 0x00000073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "constexpr std::uint32_t kEcall = 0x00000073U;\n"
        "constexpr std::uint32_t kFenceIorw = 0x0ff0000fU;\n"
        "constexpr std::uint32_t kWfi = 0x10500073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "tickless instruction constants",
    )
    text = replace_once(
        text,
        "    const bool ecallTrapStep = commit.exception;\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (ecallTrapStep) {\n"
        "      after = executeEcallTrap(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++trapShadowSteps_;\n"
        "    } else if (mretStep) {\n",
        "    const bool ecallTrapStep = commit.exception;\n"
        "    const bool fenceStep = commit.inst == kFenceIorw;\n"
        "    const bool wfiStep = commit.inst == kWfi;\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (ecallTrapStep) {\n"
        "      after = executeEcallTrap(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++trapShadowSteps_;\n"
        "    } else if (fenceStep) {\n"
        "      after = executeFence(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++fenceShadowSteps_;\n"
        "    } else if (wfiStep) {\n"
        "      after = executeWfi(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++wfiShadowSteps_;\n"
        "    } else if (mretStep) {\n",
        "tickless reference dispatch",
    )
    text = replace_once(
        text,
        "    if (mtimeLoadStep) line << \" reference=mtime-load-shadow\";\n"
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n",
        "    if (mtimeLoadStep) line << \" reference=mtime-load-shadow\";\n"
        "    if (fenceStep) line << \" reference=fence-shadow\";\n"
        "    if (wfiStep) line << \" reference=wfi-shadow\";\n"
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n",
        "tickless trace labels",
    )
    text = replace_once(
        text,
        "  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }\n"
        "  std::uint64_t mretShadowSteps() const { return mretShadowSteps_; }\n",
        "  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }\n"
        "  std::uint64_t fenceShadowSteps() const { return fenceShadowSteps_; }\n"
        "  std::uint64_t wfiShadowSteps() const { return wfiShadowSteps_; }\n"
        "  std::uint64_t mretShadowSteps() const { return mretShadowSteps_; }\n",
        "tickless counter accessors",
    )
    method = r'''  NemuState32 executeFence(const NemuState32& before,
                           const DifftestCommit& commit) {
    if (commit.inst != kFenceIorw || commit.rdWrite || commit.memValid ||
        commit.exception) {
      fail("FreeRTOS tickless fence shadow received an invalid architectural event");
    }

    // The pinned historical NEMU does not decode FENCE IORW, IORW. In this
    // single-hart, strongly ordered simulation it has no architectural state
    // effect beyond retiring at PC+4. A possible interrupt after retirement is
    // still handled by the generic precise-interrupt shadow below.
    NemuState32 after = before;
    after.pc = before.pc + 4U;
    after.gpr[0] = 0;
    return after;
  }

  NemuState32 executeWfi(const NemuState32& before,
                         const DifftestCommit& commit) {
    if (commit.inst != kWfi || commit.rdWrite || commit.memValid || commit.exception) {
      fail("FreeRTOS WFI shadow received an invalid architectural wake event");
    }

    if (commit.interrupt) {
      const auto interruptPc = checkedAddress(commit.interruptPc, "WFI interrupt PC");
      if (interruptPc != before.pc + 4U) {
        fail("FreeRTOS interrupting WFI wake did not preserve PC+4");
      }
    }

    // A masked tickless WFI retires without trap entry when raw MTIP becomes
    // pending. The later mstatus.MIE restore is the precise interrupt boundary.
    NemuState32 after = before;
    after.pc = before.pc + 4U;
    after.gpr[0] = 0;
    return after;
  }

'''
    text = replace_once(
        text,
        "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        method
        + "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        "MRET method",
    )
    text = replace_once(
        text,
        "  std::uint64_t trapShadowSteps_ = 0;\n"
        "  std::uint64_t mretShadowSteps_ = 0;\n",
        "  std::uint64_t trapShadowSteps_ = 0;\n"
        "  std::uint64_t fenceShadowSteps_ = 0;\n"
        "  std::uint64_t wfiShadowSteps_ = 0;\n"
        "  std::uint64_t mretShadowSteps_ = 0;\n",
        "tickless counter storage",
    )
    text = replace_once(
        text,
        "std::uint64_t NemuDifftest::trapShadowSteps() const { return impl_->trapShadowSteps(); }\n"
        "std::uint64_t NemuDifftest::mretShadowSteps() const { return impl_->mretShadowSteps(); }\n",
        "std::uint64_t NemuDifftest::trapShadowSteps() const { return impl_->trapShadowSteps(); }\n"
        "std::uint64_t NemuDifftest::fenceShadowSteps() const { return impl_->fenceShadowSteps(); }\n"
        "std::uint64_t NemuDifftest::wfiShadowSteps() const { return impl_->wfiShadowSteps(); }\n"
        "std::uint64_t NemuDifftest::mretShadowSteps() const { return impl_->mretShadowSteps(); }\n",
        "tickless public counter wrappers",
    )
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()

    source = arguments.source.read_text(encoding="utf-8")
    output = adapt(source)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(output, encoding="utf-8")
    print(f"wrote FreeRTOS WFI/FENCE DiffTest adapter: {arguments.output}")


if __name__ == "__main__":
    main()
