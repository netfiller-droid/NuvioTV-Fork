import os, subprocess, sys
from pathlib import Path

if len(sys.argv) < 3:
    raise SystemExit('usage: repeat_test.py <test class> <runs>   (after run.py has compiled the tests)')
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as R

cp = os.pathsep.join(map(str, [R.WORK / 'out', R.ROOT / 'app/src/test/resources'] + R.classpath()))
fails = 0
for _ in range(int(sys.argv[2])):
    p = subprocess.run(['java', '-cp', cp, 'org.junit.runner.JUnitCore', sys.argv[1]], capture_output=True, text=True)
    if 'OK (' not in p.stdout:
        fails += 1
        print('\n'.join(l for l in p.stdout.splitlines() if 'Error' in l or 'Exception' in l or 'Test.kt:' in l)[:1500])
print('runs', sys.argv[2], 'fails', fails)
