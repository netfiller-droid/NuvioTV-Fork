# IPTV host tests

Runs the IPTV core and data-layer JVM tests, and compile-checks the IPTV device
tests, on a machine without the Android SDK:

```
python3 tools/iptv-host-tests/run.py                 # core, data, androidtest
python3 tools/iptv-host-tests/run.py core
python3 tools/iptv-host-tests/run.py ui app/src/main/java/com/nuvio/tv/ui/screens/iptv/IptvLivePlayback.kt
```

The first run downloads pinned jars from Maven Central (including Robolectric's
android-all, about 200 MB) and the Media3 1.8.0 decoder and container sources from
GitHub into `build/iptv-host-tests` (override with `IPTV_HOST_WORK`). Later runs reuse
them.

`src/Mockable.java` turns android-all into a default-value `android.jar`, matching
`unitTests.isReturnDefaultValues`. `src/shims` provides pure-Java `SparseArray`,
`Pair` and `TextUtils` so Media3's TS extractor works on the JVM. `src/stubs` holds
annotation and test-runner stubs that are only needed to compile.

This is a supplement, not a replacement: the Gradle build, Compose/Hilt UI code and
device tests still need the Android SDK and the AM9.

## Other scripts

```
python3 tools/iptv-host-tests/check_translations.py      # every IPTV string in all 40 locales; prints "problems 0"
python3 tools/iptv-host-tests/repeat_test.py com.nuvio.tv.data.iptv.IptvCaptureHttpTest 40
python3 tools/iptv-host-tests/test_sheet.py validation/IPTV-DEVICE-TEST-CHECKLIST.md sheet.pdf \
    --build <commit> --run <CI run id> --date "<date>" --sections 7m,7l,7k
```

`repeat_test.py` runs one compiled test class again and again (after `run.py` has built
the tests with the same `IPTV_HOST_WORK`) to show a timing-dependent failure; running
several copies at once adds load. `test_sheet.py` turns the named checklist sections,
newest first, into a printable A4 sheet with Pass / Fail / Not tested boxes (needs
reportlab and the DejaVu fonts).
