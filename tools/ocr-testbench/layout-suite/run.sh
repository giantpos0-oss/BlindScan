set -e
java -Xmx3g -cp /home/claude/kc/package/lib/kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler /home/claude/blindscan/app/src/main/java/com/willwalsh/blindscan/ocr/Geometry.kt /home/claude/blindscan/app/src/main/java/com/willwalsh/blindscan/ocr/ReadingOrder.kt /home/claude/layout/Suite.kt -d /home/claude/layout/out -kotlin-home /home/claude/kc/package -no-reflect 2>&1 | grep -v JAVA_TOOL | grep -v warning || true
java -Xmx2g -cp /home/claude/layout/out:/home/claude/kc/package/lib/kotlin-stdlib.jar SuiteKt /home/claude/layout/suite.tsv /home/claude/scan/test/words_en.txt /home/claude/layout/pred.tsv 2>&1 | grep -v JAVA_TOOL
python3 /home/claude/layout/score.py /home/claude/layout/pred.tsv
