Weekly build of sdrtrunk with the Radio Console, rebuilt from the `console-ui` branch when it has changed.

Download the zip for your platform, unzip it and run `bin/sdr-trunk` (`bin\sdr-trunk.bat` on Windows).

### macOS

The release is not signed, so macOS blocks it until the quarantine flag is removed from the unzipped folder:

```
xattr -dr com.apple.quarantine ~/Downloads/sdr-trunk-osx-aarch64-vweekly
```

Use `sdr-trunk-osx-x86_64-vweekly` on Intel Macs, and adjust the path if you unzipped it somewhere else.
