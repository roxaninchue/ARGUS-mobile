cd /workspace/work/argus-mobile/app/src; M=main/java/com/blaze/assistant; T=test/java/com/blaze/assistant
MAIN="$(cat /workspace/work/kt/base.txt) /workspace/work/argus-mobile/tools/jvmtest/stubs/ui/*.kt $(cat /workspace/work/kt/ui.txt) $M/ui/argus/*.kt $M/ui/argus/kit/*.kt $M/ui/argus/icons/*.kt $M/ui/argus/settings/*.kt $M/ui/team/TeamMode.kt $EXTRA"
TESTS="$T/agent/engines/LocalEngineTest.kt $T/agent/TeamHooksTest.kt $(ls $T/team/*Test.kt | grep -v TeamDbTest) $(cat /workspace/work/kt/ut.txt | sed 's|[^ ]*SpecialistStoreTest.kt||') $EXTRAT"
N=60 /workspace/work/kt/cc.sh /workspace/work/kt/run/full $MAIN $TESTS
CLS=$(for f in $TESTS; do echo $f | sed 's|test/java/||; s|\.kt$||; s|/|.|g'; done)
TL=${TL:-12} /workspace/work/kt/t.sh /workspace/work/kt/run/full:$CCP $CLS
