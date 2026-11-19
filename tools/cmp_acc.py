import re, sys
exp = open(sys.argv[2]).read().split()
s = open(sys.argv[1], encoding="utf-8").read()
got = []
for n in re.findall(r"<note>(.*?)</note>", s, re.S):
    if "<staff>1</staff>" not in n or "<pitch>" not in n:
        continue
    st = re.search(r"<step>(\w)</step>", n).group(1); o = re.search(r"<octave>(\d)</octave>", n).group(1)
    a = re.search(r"<alter>(-?\d)</alter>", n); got.append(f"{st}{o}:{a.group(1) if a else 0}")
ok = sum(1 for x, y in zip(exp, got) if x == y)
print(f"{ok}/{len(exp)} richtig (gelesen {len(got)})")
for i, (x, y) in enumerate(zip(exp, got)):
    if x != y: print("  Note", i + 1, "erwartet", x, "gelesen", y)
