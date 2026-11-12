# usage: c.sh outdir files...
out=$1; shift
source /workspace/work/kt/env.sh
rm -rf $out; kotlinc -nowarn -jvm-target 17 -cp $TCP:$AJ "$@" -d $out 2>&1 | grep -E "error:" | sed 's|^.*/assistant/||' | sort | uniq | head -${N:-40}
