import argparse
import os
import sys

from .store import Store


def _parser():
    p = argparse.ArgumentParser(prog="todo")
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("title", nargs="+")
    sub.add_parser("list")
    d = sub.add_parser("done")
    d.add_argument("id", type=int)
    return p


def main(argv=None):
    args = _parser().parse_args(argv)
    store = Store(os.environ.get("TODO_FILE", os.path.expanduser("~/.todo.json")))
    items = store.load()
    if args.cmd == "add":
        item = {"id": max([i["id"] for i in items], default=0) + 1, "title": " ".join(args.title), "done": False}
        items.append(item)
        store.save(items)
        print(f"added {item['id']}: {item['title']}")
    elif args.cmd == "list":
        for i in items:
            print(f"{i['id']}. [{'x' if i['done'] else ' '}] {i['title']}")
    else:
        match = [i for i in items if i["id"] == args.id]
        if not match:
            print(f"no todo {args.id}", file=sys.stderr)
            return 1
        match[0]["done"] = True
        store.save(items)
        print(f"done {args.id}")
    return 0
