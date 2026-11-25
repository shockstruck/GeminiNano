# Installed-code DEX fixture

`classes.dex` is compiled from `InstalledCodeFixture.java` in this directory by the
Android SDK toolchain. It is committed so the JVM tests exercise a real DEX layout, not
only synthetic bytes. The class contains a static final field (an encoded static value),
a `switch`, an `if`, two flag calls with literal defaults, boxed-default flag
constructors `(String, Integer)` and `(String, Boolean)`, one `static final` flag object,
and one method that composes the build-id key at run time with
`"AicDataRelease__build_id_".concat(String.valueOf(client))` and passes a boxed `0`.

Build (from this directory; the committed file is the d8 output):

```sh
ANDROID_SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

mkdir -p build/classes build/dex
javac -source 8 -target 8 -d build/classes InstalledCodeFixture.java
"$ANDROID_SDK/build-tools/37.0.0/d8" --min-api 24 --output build/dex \
  build/classes/dev/shockstruck/gemininano/installedcodefixture/*.class
cp build/dex/classes.dex ./classes.dex
```

Toolchain used for the committed file:

- `javac 11.0.32.1` with `-source 8 -target 8`
- `d8` from Android SDK build-tools 37.0.0: `D8 9.2.4-dev`

The output is DEX version 037. Its class data starts at unaligned offsets (2729, 2747 and
2765; class `InstalledCodeFixture` itself at 2783) and the static values of
`InstalledCodeFixture` start at 2829, which is also not four-byte aligned; the test
asserts both, because the format specifies those items as byte-aligned.
