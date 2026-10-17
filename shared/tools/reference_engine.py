#!/usr/bin/env python3
"""Reference implementation of the text algorithms in shared/SPEC.md section 5.

Every platform (Kotlin, Swift, C#) must produce the same results for the
cases in shared/tests/engine-cases.json. Run this file to (re)generate and
self-check that JSON:  python3 shared/tools/reference_engine.py
"""
import json
import os
import unicodedata

SENTENCE_END = set(".!?…。！？")
QUOTE_PAIRS = [('"', '"'), ("„", "“"), ("“", "”"), ("«", "»"), ("'", "'"), ("‘", "’")]
WORD_EXTRA = set("'’-_")
CLOSERS = set(")]}\"'»”’")


def is_ws(c):
    return c.isspace()


def is_letter_or_digit(c):
    cat = unicodedata.category(c)
    return cat[0] in ("L", "N")


def has_letter(s):
    return any(unicodedata.category(c)[0] == "L" for c in s)


def select_segment(before, explicit, pinyin, max_segment=400, max_context=600):
    p = before.rfind("\n") + 1
    end = len(before)
    trailing = ""
    in_progress = len(before) > 0 and is_letter_or_digit(before[-1])
    if not explicit and not pinyin and in_progress:
        k = -1
        for i in range(len(before) - 1, -1, -1):
            if is_ws(before[i]):
                k = i
                break
        if k < p:
            return None
        end = k + 1
        trailing = before[end:]
    if end - p > max_segment:
        window = end - max_segment
        seg_start = None
        for i in range(window, end):
            if is_ws(before[i]) and i - 1 >= 0 and before[i - 1] in SENTENCE_END:
                seg_start = i + 1
                break
        if seg_start is None:
            for i in range(window, end):
                if is_ws(before[i]):
                    seg_start = i + 1
                    break
        if seg_start is None:
            seg_start = window
    else:
        seg_start = p
    segment = before[seg_start:end]
    if not has_letter(segment):
        return None
    ctx_start = max(0, seg_start - max_context)
    if ctx_start > 0:
        for i in range(ctx_start, seg_start):
            if is_ws(before[i]):
                ctx_start = i + 1
                break
    context = before[ctx_start:seg_start]
    return {"context": context, "segment": segment, "trailing": trailing}


def leading_ws(s):
    i = 0
    while i < len(s) and is_ws(s[i]):
        i += 1
    return s[:i]


def trailing_ws(s):
    i = len(s)
    while i > 0 and is_ws(s[i - 1]):
        i -= 1
    return s[i:]


def clean_output(segment, output, pinyin):
    t = output
    if "<segment>" in t:
        t = t.split("<segment>", 1)[1]
    if "</segment>" in t:
        t = t.split("</segment>", 1)[0]
    t = t.strip()
    if t.startswith("```"):
        nl = t.find("\n")
        t = t[nl + 1:] if nl >= 0 else t[3:]
        if t.endswith("```"):
            t = t[:-3]
        t = t.strip()
    s_trim = segment.strip()
    for o, c in QUOTE_PAIRS:
        if len(t) >= 2 and t.startswith(o) and t.endswith(c) and not s_trim.startswith(o):
            t = t[1:-1].strip()
            break
    if not t:
        return None
    if "<context>" in t or "</context>" in t:
        return None
    if not pinyin and len(s_trim) >= 12:
        if len(t) > len(s_trim) * 1.5 + 10 or len(t) < len(s_trim) * 0.5 - 5:
            return None
    return leading_ws(segment) + t + trailing_ws(segment)


def compute_edit(segment, corrected, suffix):
    if segment == corrected:
        return None
    p = 0
    while p < len(segment) and p < len(corrected) and segment[p] == corrected[p]:
        p += 1
    return {"delete": segment[p:] + suffix, "insert": corrected[p:] + suffix}


def word_delete(before):
    n = len(before)
    if n == 0:
        return ""
    if before[-1] == "\n":
        return "\n"
    i = n
    while i > 0 and is_ws(before[i - 1]) and before[i - 1] != "\n":
        i -= 1
    if i == 0 or before[i - 1] == "\n":
        return before[i:]
    j = i
    if is_letter_or_digit(before[i - 1]) or before[i - 1] in WORD_EXTRA:
        while j > 0 and (is_letter_or_digit(before[j - 1]) or before[j - 1] in WORD_EXTRA):
            j -= 1
    else:
        while j > 0 and not is_ws(before[j - 1]) and not is_letter_or_digit(before[j - 1]):
            j -= 1
    return before[j:]


def auto_capitalize(before):
    if before == "":
        return True
    if before[-1] == "\n":
        return True
    if not is_ws(before[-1]):
        return False
    t = before.rstrip()
    if t == "":
        return True
    if t[-1] == "\n":
        return True
    return t[-1] in SENTENCE_END


def double_space_period(before):
    """Called when space is typed again within 400 ms. Returns True if the last space becomes '. '."""
    if len(before) < 2 or before[-1] != " ":
        return False
    c = before[-2]
    return is_letter_or_digit(c) or c in CLOSERS


def can_apply(before_now, segment, trailing, typed_since):
    return before_now.endswith(segment + trailing + typed_since)


SELECT = [
    ("ich bin hir ", False, False),
    ("ich bin hir", False, False),
    ("hallo", False, False),
    ("hallo", True, False),
    ("Das ist gut. ich komm morgn und bring dsa", False, False),
    ("Erste Zeile.\nzweite zeil mit fehlr ", False, False),
    ("Erste Zeile.\nzweite", False, False),
    ("nihaowoshi", False, True),
    ("123 456 ", False, False),
    ("😀😀 ", False, False),
    ("Satz eins ist da. Satz zwei ist auch da. " * 12 + "letzter satz hir ", False, False),
    ("wort " * 150 + "ende ", False, False),
    ("x" * 700 + " ganz am ende ", False, False),
]

CLEAN = [
    ("ich bin hir ", "Ich bin hier", False),
    ("ich bin hir ", "Ich bin hier ", False),
    (" wie gehts", "Wie geht's?", False),
    ("hallo wie gehts ", "\"Hallo, wie geht's?\"", False),
    ("hallo wie gehts ", "```\nHallo, wie geht's?\n```", False),
    ("hallo wie gehts ", "<segment>Hallo, wie geht's?</segment>", False),
    ("„zitat“ ist da ", "„Zitat“ ist da", False),
    ("was ist die hauptstadt von frankreich ", "Die Hauptstadt von Frankreich ist Paris. Paris ist eine wunderschöne Stadt mit vielen Sehenswürdigkeiten.", False),
    ("ok ", "", False),
    ("nihao wo shi tippli ", "你好，我是Tippli", True),
    ("nihao wo shi tippli ", "你好，我是Tippli", False),
    ("text ", "<context>foo</context> Text", False),
]

EDITS = [
    ("ich bin hir ", "Ich bin hier ", "un"),
    ("Das ist gut. ich komme morgn ", "Das ist gut. Ich komme morgen ", ""),
    ("hallo ", "hallo ", ""),
    ("ich gehe ", "ich gehe, ", "w"),
    ("grüezi mitenand ", "Grüezi mitenand ", ""),
    ("hallo 😀 wi gehts ", "hallo 😀 wie geht's ", ""),
]

WORD_DELETE = [
    "", "hallo welt", "hallo welt ", "hallo welt   ", "hallo welt.", "hallo welt!!! ",
    "ok 😀😀", "zeile1\n", "zeile1\n  ", "   ", "ich bin's", "e-mail", "Grüezi mitenand", "你好 世界",
]

AUTOCAP = ["", "Hallo. ", "Hallo.", "Hallo ", "Wie gehts? ", "Super! ", "\n", "abc\n", "   ", "Hallo,  ", "Gut… ", "好。"]

DOUBLE_SPACE = ["hallo ", "hallo. ", "hallo  ", "(ok) ", "1 ", " ", "😀 "]

APPLY = [
    ("Hey ich bin hir unterwegs", "ich bin hir ", "", "unterwegs"),
    ("Hey ich bin hir unterwegs", "ich bin hir ", "un", "terwegs"),
    ("Hey ich bin hi", "ich bin hir ", "", ""),
    ("Hey ich bin hir ", "ich bin hir ", "", ""),
]


def main():
    cases = {
        "about": "Shared test vectors for SPEC section 5. Generated by shared/tools/reference_engine.py - do not edit by hand.",
        "selectSegment": [],
        "cleanOutput": [],
        "computeEdit": [],
        "wordDelete": [],
        "autoCapitalize": [],
        "doubleSpacePeriod": [],
        "canApply": [],
    }
    for before, explicit, pinyin in SELECT:
        cases["selectSegment"].append({"before": before, "explicit": explicit, "pinyin": pinyin,
                                       "expected": select_segment(before, explicit, pinyin)})
    for seg, out, pinyin in CLEAN:
        cases["cleanOutput"].append({"segment": seg, "output": out, "pinyin": pinyin,
                                     "expected": clean_output(seg, out, pinyin)})
    for seg, cor, suf in EDITS:
        cases["computeEdit"].append({"segment": seg, "corrected": cor, "suffix": suf,
                                     "expected": compute_edit(seg, cor, suf)})
    for b in WORD_DELETE:
        cases["wordDelete"].append({"before": b, "expected": word_delete(b)})
    for b in AUTOCAP:
        cases["autoCapitalize"].append({"before": b, "expected": auto_capitalize(b)})
    for b in DOUBLE_SPACE:
        cases["doubleSpacePeriod"].append({"before": b, "expected": double_space_period(b)})
    for now, seg, trailing, typed in APPLY:
        cases["canApply"].append({"beforeNow": now, "segment": seg, "trailing": trailing, "typedSince": typed,
                                  "expected": can_apply(now, seg, trailing, typed)})
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "tests", "engine-cases.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cases, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
