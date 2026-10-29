"""The overhead text recipe (overhead.py) over a miniature of the client's: the actor step counting an actor's overhead
timer down and emptying its text at 0, the client cycle calling that step and incrementing a counter right after, and
the say block setting the text and the timer (a store to the timer that isn't a countdown)."""
import re
import unittest
from types import SimpleNamespace

from native_updater import overhead
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
EMPTY = 0xb96118
TIMER, TEXT, STEPS = 0x2f4, 0x2d8, 0x26d8


def assemble(entry, lines, labels):
    """4-byte instructions laid out from entry: a ':name' line marks a label and '@name' in a line is that label's
    address. Fills labels and returns the instruction lines."""
    out, rva = [], entry
    for line in lines:
        if line.startswith(":"):
            labels[line[1:]] = rva
        else:
            out.append(line)
            rva += 4
    return out


def resolve(lines, labels):
    return [re.sub(r"@(\w+)", lambda m: f"{IMAGE + labels[m.group(1)]:#x}", line) for line in lines]


class OverheadCountdownTests(unittest.TestCase):
    CYCLE, STEP, COUNTDOWN, ASSIGN, SAY, OTHER_STEP = 0x1000, 0x1400, 0x1800, 0x1c00, 0x2000, 0x2400

    @staticmethod
    def countdown(clears=True):
        """Counts the actor's timer down and, when it reaches 0, assigns the empty range to its text."""
        clear = [f"ADD RCX,{TEXT:#x}", f"LEA R8,[{IMAGE + EMPTY:#x}]", f"LEA RDX,[{IMAGE + EMPTY:#x}]",
                 "CALL @assign"] if clears else []
        return [f"MOV EAX,dword ptr [RCX + {TIMER:#x}]", "TEST EAX,EAX", "JLE @done", "SUB EAX,0x1",
                f"MOV dword ptr [RCX + {TIMER:#x}],EAX", "JNZ @done", *clear, ":done", "RET"]

    @staticmethod
    def step(counter=True):
        """The client's actor step: counts the overhead timers down, then increments the step counter."""
        inc = [f"INC dword ptr [RSI + {STEPS:#x}]"] if counter else []
        return ["MOV RSI,RCX", "MOV RCX,RSI", "CALL @countdown", *inc, "RET"]

    def program(self, clears=True, counter=True, from_cycle=True):
        labels = {}
        parts = {
            self.CYCLE: [f"CALL @{'step' if from_cycle else 'other_step'}", "RET"],
            self.STEP: self.step(counter),
            self.OTHER_STEP: ["RET"],
            self.COUNTDOWN: assemble(self.COUNTDOWN, self.countdown(clears), labels),
            self.ASSIGN: ["RET"],
            self.SAY: [f"MOV dword ptr [RCX + {TIMER:#x}],0x64", "RET"],
        }
        labels.update(step=self.STEP, other_step=self.OTHER_STEP, countdown=self.COUNTDOWN, assign=self.ASSIGN)
        return {entry: resolve(lines, labels) for entry, lines in parts.items()}

    def session(self, **kwargs):
        model = FakeModel(self.program(**kwargs))
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"Actor.overheadTimer": TIMER, "functions.clientCycle": self.CYCLE})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_the_text_comes_from_the_countdown_and_the_counter_from_the_step(self):
        s = self.session()
        out = {k: v[0] for k, v in overhead.overhead_countdown(s, {}).items()}
        self.assertEqual(out, {"Actor.overheadText": TEXT, "Client.actorSteps": STEPS})
        self.assertEqual(s.notes, [])

    def test_a_countdown_that_never_empties_a_string_gives_nothing(self):
        s = self.session(clears=False)
        self.assertEqual(overhead.overhead_countdown(s, {}), {})
        self.assertTrue(any("0 functions count Actor.overheadTimer down" in n for n in s.notes), s.notes)

    def test_a_step_without_the_increment_gives_only_the_text(self):
        s = self.session(counter=False)
        out = {k: v[0] for k, v in overhead.overhead_countdown(s, {}).items()}
        self.assertEqual(out, {"Actor.overheadText": TEXT})
        self.assertTrue(any("increment 0 fields" in n for n in s.notes), s.notes)

    def test_a_step_the_client_cycle_doesnt_call_isnt_the_counter(self):
        s = self.session(from_cycle=False)
        out = {k: v[0] for k, v in overhead.overhead_countdown(s, {}).items()}
        self.assertEqual(out, {"Actor.overheadText": TEXT})


if __name__ == "__main__":
    unittest.main()
