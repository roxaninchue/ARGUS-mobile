# compose compile: cc.sh outdir files...
out=$1; shift
source /workspace/work/kt/env.sh
rm -rf $out; kotlinc -nowarn -jvm-target 17 -Xplugin=/workspace/work/kt/kotlin-compose-compiler-plugin-2.0.21.jar -cp $TCP:$CCP:$AJ "$@" -d $out 2>&1 | grep -E "error:" | sed 's|^.*/assistant/||' | sort | uniq | head -${N:-40}
