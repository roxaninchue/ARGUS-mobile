import re
from collections import Counter


def top_words(text, k):
    if k <= 0:
        return []
    words = (w.strip("'") for w in re.findall(r"[a-z0-9']+", text.lower()))
    counts = Counter(w for w in words if w)
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:k]
