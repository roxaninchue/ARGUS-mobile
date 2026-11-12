# usage: t.sh outdir Class...
out=$1; shift
source /workspace/work/kt/env.sh
cd /workspace/work/argus-mobile/app
java -cp $out:$TCP:/workspace/work/kt/kotlinc/lib/kotlin-stdlib.jar:/workspace/work/argus-mobile/app/src/test/resources org.junit.runner.JUnitCore "$@" 2>&1 | grep -vE "^\s+at " | tail -${TL:-25}
