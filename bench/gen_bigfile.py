#!/usr/bin/env python3
"""Generate a deterministic lorem-ipsum file of a target size.

    gen_bigfile.py <path> <target-mb>

Lines run ~100 characters. Every 50,000th line ends with a unique
findable token, zqx<lineno>; the first line ends with zqxfirst and
the last with zqxfinal, so an e2e suite can jump to a known line by
finding a token that occurs exactly once. Prints BYTES= and LINES=
for the caller.
"""
import sys

SENTENCES = [
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt.",
    "Ut labore et dolore magna aliqua enim ad minim veniam, quis nostrud exercitation ullamco.",
    "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla.",
    "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim.",
    "Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium.",
    "Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur.",
    "Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit.",
    "At vero eos et accusamus et iusto odio dignissimos ducimus qui blanditiis praesentium voluptatum.",
]


def main():
    path, target_mb = sys.argv[1], float(sys.argv[2])
    target = int(target_mb * 1024 * 1024)
    written = 0
    lineno = 0
    with open(path, "w", buffering=1024 * 1024) as f:
        while written < target:
            lineno += 1
            # build one line of ~100 chars from rotating sentences
            parts = []
            n = 0
            i = lineno
            while n < 92:
                s = SENTENCES[i % len(SENTENCES)]
                parts.append(s)
                n += len(s) + 1
                i += 1
            line = " ".join(parts)[:97].rstrip()
            if lineno == 1:
                line += " zqxfirst"
            elif lineno % 50000 == 0:
                line += " zqx%d" % lineno
            line += "\n"
            f.write(line)
            written += len(line)
        # final line carries the end marker
        lineno += 1
        line = SENTENCES[0][:80] + " zqxfinal\n"
        f.write(line)
        written += len(line)
    print("BYTES=%d" % written)
    print("LINES=%d" % lineno)


if __name__ == "__main__":
    main()
