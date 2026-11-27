package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ReadabilityStatus
import com.assetdesk.unity.UnityFileKind
import com.assetdesk.writer.BundleCompressionMode
import com.example.data.SafFileRepository
import com.example.ui.theme.AssetDeskTheme
import com.example.ui.viewmodel.AssetDeskViewModel
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `app name matches AssetDesk Mobile`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AssetDesk Mobile", appName)
  }

  @Test
  fun `navigation shell switches across all five screens and displays active plugins`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = AssetDeskViewModel(app)

    composeTestRule.setContent {
      AssetDeskTheme {
        AssetDeskAppShell(viewModel = viewModel)
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("workspace_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_file_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_bundle_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_files").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("files_screen").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_inspector").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("inspector_screen").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_plugins").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("plugins_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("plugin_card_texture2d").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()

    composeTestRule.onNodeWithTag("settings_screen")
      .performScrollToNode(hasTestTag("open_source_licenses_button"))
    composeTestRule.onNodeWithTag("open_source_licenses_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("AssetDesk Mobile is built with open-source components:")
      .assertIsDisplayed()
  }

  @Test
  fun `end to end SerializedFile inspection, search, TypeTree view, and TextAsset edit in UI`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = AssetDeskViewModel(app)
    val assetFile: File = tempFolder.newFile("test_v15.assets")
    assetFile.writeBytes(buildSampleSerializedFileV15())

    composeTestRule.setContent {
      AssetDeskTheme {
        AssetDeskAppShell(viewModel = viewModel)
      }
    }

    composeTestRule.runOnUiThread {
      viewModel.openUris(listOf(Uri.fromFile(assetFile)))
    }

    composeTestRule.waitUntil(timeoutMillis = 10_000) {
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      val state = viewModel.uiState.value
      check(state.errorMessage == null) { "Unexpected parse error: ${state.errorMessage}" }
      state.activeFile?.parsedSerializedFile != null
    }

    composeTestRule.onNodeWithTag("nav_inspector").performClick()
    composeTestRule.waitForIdle()

    // Asset List displays parsed TextAsset object
    composeTestRule.onNodeWithTag("asset_list_tab").assertIsDisplayed()
    composeTestRule.onNodeWithTag("asset_list_tab")
      .performScrollToNode(hasTestTag("asset_row_101"))
    composeTestRule.onNodeWithTag("asset_row_101").assertIsDisplayed()

    // Search filter works
    composeTestRule.onNodeWithTag("asset_search_input").performTextInput("readme")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("asset_list_tab")
      .performScrollToNode(hasTestTag("asset_row_101"))
    composeTestRule.onNodeWithTag("asset_row_101").assertIsDisplayed()

    // Open Object Inspector detail pane
    composeTestRule.onNodeWithTag("asset_row_101").performClick()
    composeTestRule.waitUntil(timeoutMillis = 10_000) {
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      viewModel.uiState.value.selectedObjectDetail != null
    }
    composeTestRule.onNodeWithTag("object_detail_pane").assertIsDisplayed()

    // Switch to TypeTree schema tab
    composeTestRule.onNodeWithTag("object_tab_typetree").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("TypeTree Schema Info", ignoreCase = true).assertIsDisplayed()

    // Switch to Plugin tab -> TextAsset preview + inline apply
    composeTestRule.onNodeWithTag("object_tab_plugin").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("plugin_textasset_preview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("apply_textasset_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Switch to Raw / Dump tab
    composeTestRule.onNodeWithTag("object_tab_raw_dump").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("export_raw_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("export_dump_button").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `SafFileRepository opens UnityFS AssetBundle and inspects internal SerializedFile`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val innerSfBytes = buildSampleSerializedFileV15()
    val bundleBytes = buildSampleUnityFsBundle("CAB-test123", innerSfBytes)
    val bundleFile = tempFolder.newFile("sample.bundle")
    bundleFile.writeBytes(bundleBytes)

    val repository = SafFileRepository(context)
    val result = repository.openAndParseUri(Uri.fromFile(bundleFile), memoryLimitMb = 32)
    assertTrue(result.isSuccess)

    val opened = result.getOrThrow()
    assertEquals(UnityFileKind.ASSET_BUNDLE_FS, opened.kind)
    val header = opened.parseResult as HeaderParseResult.AssetBundleHeaderInfo
    assertEquals("UnityFS", header.signature)
    assertEquals(ReadabilityStatus.SUPPORTED_READ_WRITE, header.readability)
    val bundle = opened.parsedAssetBundle
    assertNotNull(bundle)
    assertEquals(1, bundle!!.directoryEntries.size)
    assertEquals("CAB-test123", bundle.directoryEntries.first().path)
    val sf = opened.parsedSerializedFile
    assertNotNull(sf)
    assertEquals(1, sf!!.objects.size)
    assertEquals("readme", sf.objects.first().assetName)

    // Verify editing dump + staging + rebuilding UnityFS preserves LZ4 compression and succeeds without bounds error
    val modifiedSf = sf.copy(
      objects = sf.objects.map { obj ->
        val writer = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
        writer.writeLengthPrefixedString("readme", alignAfter = true)
        writer.writeLengthPrefixedString("updated_short", alignAfter = true)
        obj.copy(modifiedRawBytes = writer.toByteArray(), status = com.assetdesk.model.ObjectStatus.MODIFIED)
      },
    )
    val stagedBytes = repository.serializeActiveSerializedFileBytes(
      sourceUri = Uri.fromFile(bundleFile),
      memoryLimitMb = 32,
      parsedSf = modifiedSf,
      bundle = bundle,
      bundleEntryPath = "CAB-test123",
    ).getOrThrow()

    // Inspecting / re-serializing again even after modifiedBundleEntries exists must still use original bundle source cleanly
    val outBundleFile = tempFolder.newFile("rebuilt_sample.bundle")
    val usedCodec = repository.saveRebuiltBundleToUri(
      sourceUri = Uri.fromFile(bundleFile),
      targetUri = Uri.fromFile(outBundleFile),
      memoryLimitMb = 32,
      bundle = bundle,
      replacedEntries = mapOf("CAB-test123" to stagedBytes),
      stagedSerializedFiles = mapOf("CAB-test123" to modifiedSf),
    ).getOrThrow()
    assertEquals(BundleCompressionMode.LZ4, usedCodec)

    val reopened = repository.openAndParseUri(Uri.fromFile(outBundleFile), memoryLimitMb = 32).getOrThrow()
    assertEquals(1, reopened.parsedSerializedFile!!.objects.size)
  }

  private fun buildSampleSerializedFileV15(): ByteArray {
    val body = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    // Object payload: TextAsset (ClassID 49) -> m_Name = "readme", m_Script = "hello assetdesk"
    val objWriter = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    objWriter.writeLengthPrefixedString("readme", alignAfter = true)
    objWriter.writeLengthPrefixedString("hello assetdesk", alignAfter = true)
    val objBytes = objWriter.toByteArray()

    // Metadata section (LE)
    val meta = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    meta.writeNullTerminatedString("5.6.7f1") // unityVersion
    meta.writeInt32(5) // targetPlatform
    meta.writeBoolean(true) // enableTypeTree
    meta.writeInt32(1) // typeCount
    // Type 0: classId=49 (TextAsset)
    meta.writeInt32(49)
    meta.writeBytes(ByteArray(16)) // oldTypeHash
    // Embedded TypeTree nodes (v15 flat node count + string buffer)
    val stringBuf = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    val nodes = listOf(
      TypeTreeNode(1, 0, 0, "TextAsset", "Base", -1, 0, 0),
      TypeTreeNode(1, 1, 0, "string", "m_Name", -1, 1, TypeTreeNode.ALIGN_BYTES_FLAG),
      TypeTreeNode(1, 2, 1, "Array", "Array", -1, 2, TypeTreeNode.ALIGN_BYTES_FLAG),
      TypeTreeNode(1, 3, 0, "int", "size", 4, 3, 0),
      TypeTreeNode(1, 3, 0, "char", "data", 1, 4, 0),
      TypeTreeNode(1, 1, 0, "string", "m_Script", -1, 5, TypeTreeNode.ALIGN_BYTES_FLAG),
      TypeTreeNode(1, 2, 1, "Array", "Array", -1, 6, TypeTreeNode.ALIGN_BYTES_FLAG),
      TypeTreeNode(1, 3, 0, "int", "size", 4, 7, 0),
      TypeTreeNode(1, 3, 0, "char", "data", 1, 8, 0),
    )
    meta.writeInt32(nodes.size)
    // Add custom strings to stringBuf
    val strOffsets = mutableMapOf<String, Int>()
    fun putStr(s: String): Int {
      strOffsets[s]?.let { return it }
      val off = stringBuf.position.toInt()
      stringBuf.writeNullTerminatedString(s)
      strOffsets[s] = off
      return off
    }
    val typeOff = nodes.map { putStr(it.typeName) to putStr(it.name) }
    val sbBytes = stringBuf.toByteArray()
    meta.writeInt32(sbBytes.size)
    nodes.forEachIndexed { idx, n ->
      meta.writeUInt16(n.version)
      meta.writeUInt8(n.level)
      meta.writeUInt8(n.typeFlags)
      meta.writeInt32(typeOff[idx].first)
      meta.writeInt32(typeOff[idx].second)
      meta.writeInt32(n.byteSize)
      meta.writeInt32(n.index)
      meta.writeInt32(n.metaFlag)
    }
    meta.writeBytes(sbBytes)

    // Object count = 1
    meta.writeInt32(1)
    meta.align(4)
    // PathID (64-bit in v14+)
    meta.writeInt64(101L)
    meta.writeInt32(0) // byteStart relative to dataOffset
    meta.writeInt32(objBytes.size) // byteSize
    meta.writeInt32(49) // typeId
    meta.writeInt16(49) // classId
    meta.writeInt16(0) // isDestroyed
    meta.writeUInt8(0) // stripped

    // scriptTypes = 0
    meta.writeInt32(0)
    // externals = 0
    meta.writeInt32(0)

    val metaBytes = meta.toByteArray()
    val dataOffset = ((20 + metaBytes.size + 15) / 16) * 16
    val totalFileSize = dataOffset + objBytes.size

    val header = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    header.writeInt32(metaBytes.size)
    header.writeInt32(totalFileSize)
    header.writeInt32(15)
    header.writeInt32(dataOffset)
    header.writeUInt8(0) // little endian
    header.writeBytes(ByteArray(3))

    body.writeBytes(header.toByteArray())
    body.writeBytes(metaBytes)
    val pad = dataOffset - body.position.toInt()
    if (pad > 0) body.writeBytes(ByteArray(pad))
    body.writeBytes(objBytes)
    return body.toByteArray()
  }

  private fun buildSampleUnityFsBundle(entryPath: String, entryBytes: ByteArray): ByteArray {
    val compressedData = Lz4Codec.compressBlock(entryBytes)
    // BlocksInfo layout (Big Endian)
    val blocksInfo = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    blocksInfo.writeBytes(ByteArray(16)) // hash
    blocksInfo.writeInt32(1) // 1 block
    blocksInfo.writeInt32(entryBytes.size) // uncompressedSize
    blocksInfo.writeInt32(compressedData.size) // compressedSize
    blocksInfo.writeUInt16(2) // compression=LZ4
    blocksInfo.writeInt32(1) // 1 entry
    blocksInfo.writeInt64(0L) // offset
    blocksInfo.writeInt64(entryBytes.size.toLong()) // size
    blocksInfo.writeInt32(4) // flags (SerializedFile)
    blocksInfo.writeNullTerminatedString(entryPath)

    val rawBlocksInfo = blocksInfo.toByteArray()
    val compBlocksInfo = Lz4Codec.compressBlock(rawBlocksInfo)

    // Pre-calculate header size
    val headerPrefix = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    headerPrefix.writeNullTerminatedString("UnityFS")
    headerPrefix.writeInt32(6)
    headerPrefix.writeNullTerminatedString("5.x.x")
    headerPrefix.writeNullTerminatedString("2021.3.0f1")
    // size (8) + compBlocksInfoSize (4) + uncompBlocksInfoSize (4) + flags (4)
    val headerLen = headerPrefix.position + 20L
    val totalSize = headerLen + compBlocksInfo.size + compressedData.size

    val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    out.writeBytes(headerPrefix.toByteArray())
    out.writeInt64(totalSize)
    out.writeInt32(compBlocksInfo.size)
    out.writeInt32(rawBlocksInfo.size)
    out.writeInt32(0x42) // compressionType=LZ4 (2) | BLOCKS_AND_DIRECTORY_COMBINED (0x40)
    out.writeBytes(compBlocksInfo)
    out.writeBytes(compressedData)
    return out.toByteArray()
  }
}
