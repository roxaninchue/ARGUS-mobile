# P1-QA-01: Compose compile check of the screens and the Robolectric shots tests (no run: Robolectric can't run here).
# One kotlinc over full.sh's set + ChatDrawer / LifeMode / ThreadKit + every P1 *ShotsTest and the P1 previews, with the
# signature stubs in stubs/shots and the compile-only test jars below (fetched once into $J2). Prints errors, then the count.
#   R=/workspace/work/argus-mobile bash tools/jvmtest/shots.sh
R=${R:-/workspace/work/argus-mobile}; KT=/workspace/work/kt; L=$R/tools/jvmtest; OUT=${OUT:-$KT/run/shots}; J2=${J2:-$KT/qa-jars}
source $KT/env.sh
G=https://dl.google.com/android/maven2; C=https://repo1.maven.org/maven2; mkdir -p $J2
fetch() {   # $1 = maven path without extension, $2 = aar|jar, $3 = local name
  [ -f "$J2/$3.jar" ] && return
  if [ "$2" = aar ]; then curl -sSf -o "$J2/$3.aar" "$1.aar" && (cd $J2 && mkdir -p x && unzip -o -q $3.aar classes.jar -d x && mv x/classes.jar $3.jar && rm -rf x)
  else curl -sSf -o "$J2/$3.jar" "$1.jar"; fi
}
fetch $G/androidx/compose/ui/ui-test-android/1.7.5/ui-test-android-1.7.5 aar ui-test
fetch $G/androidx/compose/ui/ui-test-junit4-android/1.7.5/ui-test-junit4-android-1.7.5 aar ui-test-junit4
fetch $G/androidx/test/ext/junit/1.2.1/junit-1.2.1 aar ext-junit
fetch $G/androidx/test/core/1.6.1/core-1.6.1 aar test-core
fetch $G/androidx/test/monitor/1.7.1/monitor-1.7.1 aar test-monitor
fetch $G/androidx/core/core/1.13.1/core-1.13.1 aar core-1.13.1
fetch $G/androidx/lifecycle/lifecycle-viewmodel-android/2.8.6/lifecycle-viewmodel-android-2.8.6 aar lifecycle-viewmodel-android-2.8.6
fetch $G/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1 aar savedstate-1.2.1
fetch $C/org/robolectric/robolectric/4.13/robolectric-4.13 jar robolectric
fetch $C/org/robolectric/annotations/4.13/annotations-4.13 jar robo-annotations
fetch $C/org/robolectric/sandbox/4.13/sandbox-4.13 jar robo-sandbox
fetch $C/org/robolectric/pluginapi/4.13/pluginapi-4.13 jar robo-pluginapi
XJ=$(ls $J2/*.jar | tr '\n' ':')
cd $R/app/src; M=main/java/com/blaze/assistant; T=test/java/com/blaze/assistant
MAIN="$(sed "s|/workspace/work/argus-mobile|$R|g" $L/base.txt) $L/stubs/ui/*.kt $L/stubs/shots/*.kt $(cat $L/ui.txt) $M/ui/argus/*.kt $M/ui/argus/kit/*.kt $M/ui/argus/icons/*.kt $M/ui/argus/settings/*.kt $M/ui/team/TeamMode.kt $M/ui/team/BotEditor.kt $M/ui/team/PlanSheet.kt $M/ui/team/DeskMode.kt $M/ui/team/JobsPage.kt $M/ui/team/ChatDrawer.kt $M/ui/team/LifeMode.kt $M/ui/team/thread/ThreadKit.kt"
SHOTS="$T/ui/shots/P1Previews.kt $T/ui/shots/P1ShotsTest.kt $T/ui/team/TeamShotsTest.kt $T/ui/team/LifeShotsTest.kt $T/ui/team/thread/ThreadShotsTest.kt $T/ui/argus/kit/KitShotsTest.kt $T/ui/argus/OrbShotsTest.kt"
rm -rf $OUT; kotlinc -nowarn -jvm-target 17 -Xplugin=$KT/kotlin-compose-compiler-plugin-2.0.21.jar -cp $TCP:$CCP:$XJ$AJ $MAIN $SHOTS -d $OUT 2>&1 | grep -E "error:" | sed 's|^.*/assistant/||' | sort -u | head -${N:-40}
echo "shots compile: $(find $OUT -name '*.class' 2>/dev/null | wc -l) classes"
