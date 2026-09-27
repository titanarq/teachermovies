# `breakdance-blocks.bin`

The first and the last 64 KiB of `breakdance.avi`, the test file OpenSubtitles publishes so that an
implementation of its moviehash can be checked against a known answer. Those are the only bytes the
algorithm reads, so a file of the published size carrying them reproduces the published hash without
the repository holding the 12 MB movie itself. `OpenSubtitlesHashTest` rebuilds it that way.

- Published vector: hash `8e245d9679d31e12`, size 12,909,756 bytes.
- Algorithm and the two published test files: the `HashSourceCodes` page of the OpenSubtitles wiki,
  archived at
  <https://web.archive.org/web/20201116034138/https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes>.
- The file itself: <http://www.opensubtitles.org/addons/avi/breakdance.avi>, reachable through the
  same archive snapshot
  (`https://web.archive.org/web/20201203003116/http://www.opensubtitles.org/addons/avi/breakdance.avi`).

To regenerate this fixture from a downloaded copy of the movie:

```sh
python3 -c 'd=open("breakdance.avi","rb").read(); open("breakdance-blocks.bin","wb").write(d[:65536]+d[-65536:])'
```

The second published vector is not reproduced here. The page also offers `dummy.rar` (2,565,922 bytes
packed, 4,295,033,890 once unpacked) and says to test the **unpacked** file, whose hash is
`61f7751fc2a72bfb`; its Lua sample notes that tools pointed at the archive as shipped report
`2a527d74d45f5b1b` instead. Getting the published answer needs a RAR unpack of a 4 GB file, which
neither this repository nor a unit test should carry.
