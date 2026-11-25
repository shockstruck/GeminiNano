# Installed-code DEX fixture

`classes.dex` is compiled from `InstalledCodeFixture.java` in this directory by the
Android SDK toolchain. It is committed so the JVM tests exercise a real DEX layout, not
only synthetic bytes. The class contains a static final field (an encoded static value),
a `switch`, an `if`, and two flag calls with literal defaults.

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

The output is DEX version 037. Its class data for `InstalledCodeFixture$FlagManager`
starts at an unaligned offset and the static values of `InstalledCodeFixture` start at
an unaligned offset; the test asserts both, because the format specifies those items as
byte-aligned.
