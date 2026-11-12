import sys


def fizzbuzz(n):
    out = []
    for i in range(1, n + 1):
        s = ("Fizz" if i % 3 == 0 else "") + ("Buzz" if i % 5 == 0 else "")
        out.append(s or str(i))
    return out


def main(argv):
    if len(argv) != 1 or not argv[0].isdigit():
        print("usage: fizzbuzz.py N", file=sys.stderr)
        return 2
    print("\n".join(fizzbuzz(int(argv[0]))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
