# AssetDesk Mobile

Native Android Unity Asset & AssetBundle Editor built with Kotlin, Jetpack Compose, and Material 3. All binary parsing, TypeTree decoding, decompression, asset plugin transforms, and bundle serialization run **100% locally on-device** using Android Storage Access Framework (SAF) — zero storage permissions and zero server uploads.

## Supported Unity Formats & Capabilities

### Binary Formats & AssetBundles
- **SerializedFile (`.assets`, `.asset`)**: Header, metadata, `TypeTree` (embedded + built-in fallback schemas for Unity 3.4+ / Unity 4 / Unity 5+ / v9..v22+), `Object Table` (`File ID`, `Path ID`, `byteStart`, `byteSize`, `typeId`, `classId`), `ScriptTypes`, and `Externals` dependencies.
- **AssetBundle Containers**:
  - `UnityFS`: Block info & block decompression (`None`, `LZ4`, `LZ4HC`, `LZMA`), internal directory listing, opening internal `SerializedFile`s, extracting internal files, and rebuilding modified `UnityFS` bundles.
  - `UnityRaw` & `UnityWeb`: Legacy stream/LZMA extraction and read-only inspection.
- **Companion Streaming Files (`.resS`, `.resource`)**: Openable as companion binary streams for metadata cross-checking.
- **Big-Endian Safeguard**: Big-endian files can be parsed in read-only mode; write operations block unsafe saves to avoid corrupting binaries.

### Asset Inspector & Editor
- Search, filter by Unity Class type, and sort by `Path ID`, `Name`, `Type`, or `Size`.
- Recursive `View Data` **Tree View**, `TypeTree` schema viewer, `PPtr` internal & external dependency navigation, and decoded-size boundary verification.
- `Export Raw` (`.dat`), `Export Dump` (`.txt`), `Import Raw`, `Import Dump` (file or inline editor), plus safe **Save As...** (`SerializedFileWriter` & `AssetBundleWriter`) that writes to a temp file and re-parses to validate before writing to the SAF destination URI.

### Modular Asset Plugins (`:asset-plugins`)
- **Texture2D (`ClassID 28`)**: Decodes `Alpha8`, `ARGB4444`, `RGB24`, `RGBA32`, `ARGB32`, `RGB565`, `RGBA4444`, `BGRA32`, `DXT1`, and `DXT5` for on-device preview, PNG export, and validated image import.
- **TextAsset (`ClassID 49`)**: Encoding-safe UTF-8 / UTF-16 inspection with binary fallback, inline editing, `.txt`/`.bytes` export, and replacement.
- **AudioClip (`ClassID 83`)**: Header, channel/sample rate/duration inspection, Ogg/WAV/FSB5/MP3 container detection, and WAV/encoded stream export.
- **Mesh (`ClassID 43`)**: Vertex/index/submesh/bounds inspection and Wavefront `.obj` geometry export.
- **MovieTexture (`ClassID 152`), TerrainData (`ClassID 156`), SubstanceArchive (`ClassID 148`/`146`), UMAMesh (`ClassID 137`/`114`)**: Structural metadata & stream diagnostics.

### Installer Packages (`.adpkg`)
- Create and apply Android-safe ZIP patch packages containing `manifest.json` + SHA-256 verified binary payloads, with target compatibility preview before applying changes.

## Module Architecture

- `:core-binary` — Bounds-checked `SeekableByteSource`, `BinaryReader`, `BinaryWriter`, pure-Kotlin `Lz4Codec`, and `LzmaCodec` (`org.tukaani:xz`).
- `:asset-model` — `TypeTreeNode`, `UnityObjectInfo`, `PPtrReference`, `AssetFieldNode`, and `UnityClassRegistry`.
- `:unity-format` — `UnityHeaderParser`, `CommonStringTable`, `SerializedFileParser`, `TypeTreeValueParser`, and `AssetBundleParser`.
- `:asset-plugins` — `AssetPluginRegistry` and specialized handlers (`Texture2D`, `AudioClip`, `Mesh`, `TextAsset`, `MovieTexture`, `TerrainData`, `SubstanceArchive`, `UMAMesh`).
- `:asset-export` — `AssetDumpCodec` and `InstallerPackageManager` (`.adpkg`).
- `:bundle-writer` — Validated `SerializedFileWriter` and `AssetBundleWriter`.
- `:app` — Jetpack Compose Material 3 UI, SAF repository, DataStore preferences, and Robolectric tests.

## Building & Testing Locally

```bash
gradle :core-binary:testDebugUnitTest :asset-model:testDebugUnitTest :unity-format:testDebugUnitTest :asset-plugins:testDebugUnitTest :asset-export:testDebugUnitTest :bundle-writer:testDebugUnitTest :app:testDebugUnitTest
gradle :app:assembleDebug
```

## License

MIT License — see [LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
