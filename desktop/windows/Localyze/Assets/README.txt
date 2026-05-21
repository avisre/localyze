Localyze.ai — Windows MSIX assets
==================================

Drop the four PNGs listed below into this directory before packaging. The MSIX
manifest (../Package.appxmanifest) references them by these exact filenames;
missing files will cause `MakeAppx pack` to fail with APPX1205.

Until real artwork is available, blank/placeholder PNGs of the correct pixel
size are sufficient to build and sideload the package — the app will simply
show a default tile in Start Menu / Taskbar.

Required files
--------------

1. Square150x150Logo.png   — Medium Start Menu tile.
                              Recommended size: 150 x 150 px
                              (Windows will scale; ship 300 x 300 if you want
                              crisp 200% scale tiles. Background should be
                              transparent or match the manifest's BackgroundColor.)

2. Square44x44Logo.png     — Taskbar + Alt-Tab + window icon.
                              Recommended size: 44 x 44 px
                              (Ship 88 x 88 for 200%, or use a target-size
                              .targetsize-256 asset for the Store; the WinUI 3
                              toolchain will generate the scales on `dotnet publish`.)

3. StoreLogo.png            — Microsoft Store + Settings > Apps listing.
                              Recommended size: 50 x 50 px
                              (Doubles as the splash-screen image in our
                              manifest, so 50 x 50 is a hard minimum; 300 x 300
                              looks better on the splash. Transparent BG.)

4. Wide310x150Logo.png      — Wide Start Menu tile.
                              Recommended size: 310 x 150 px
                              (Optional in practice — Windows falls back to the
                              square tile if missing — but our manifest declares
                              it, so keep the file present even if it's blank.)

Style notes
-----------

- Background colour in the manifest is `transparent`, with the splash background
  set to `#1A1A1A`. Use transparent PNGs so the logo composites cleanly on both
  light and dark Start Menu themes.
- Avoid embedding text in the tiles; the manifest already sets ShortName
  ("Localyze") via uap:ShowNameOnTiles.
- The Store also accepts the modern "target-size" asset families
  (Square44x44Logo.targetsize-16.png, -24, -32, -48, -256). If you generate
  these via the Visual Studio Asset Generator or `MakeAppx` --makepri, drop
  them in this directory alongside the four required ones.
