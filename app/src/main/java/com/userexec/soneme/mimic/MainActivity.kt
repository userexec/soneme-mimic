package com.userexec.soneme.mimic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private enum class Screen { LIST, SCAN, GENERATE, CODE, TEXT, PHOTO_VIEWER, PHOTO_CAPTURE }
    private enum class Softkey { LEFT, CENTER, RIGHT }
    private enum class CameraPurpose { SCAN, PHOTO }

    private data class DraftPhoto(
        val id: String,
        val file: File,
        val fileName: String?,
        val isNew: Boolean
    )

    private lateinit var db: MimicDatabase
    private lateinit var root: LinearLayout
    private var screen = Screen.LIST
    private var activeCollection = CollectionKind.WALLET
    private var originCollection = CollectionKind.WALLET
    private var listView: ListView? = null
    private var listAdapter: CodeAdapter? = null
    private var scanner: CameraScannerView? = null
    private var photoCapture: PhotoCaptureView? = null
    private var barcodeView: BarcodeView? = null
    private var currentCode: CodeRecord? = null
    private var requestingCameraPermission = false
    private var pendingCameraPurpose: CameraPurpose? = null
    private var scanInterrupted = false
    private var photoCaptureInterrupted = false
    private var scanReturnToGenerate = false
    private var pendingFocusUid: String? = null
    private var lastSoftkeys: Triple<String, String, String>? = null

    // Generate/edit state.
    private var editingUid: String? = null
    private var draftName = ""
    private var draftCollection = CollectionKind.WALLET
    private var draftKind = ItemKind.CODE
    private var draftFormat = BarcodeFormat.QR_CODE
    private var draftConvention = QrConvention.TEXT
    private var originalConvention: QrConvention? = null
    private var originalPayload: String? = null
    private var qrFieldsDirty = true
    private var loadingForm = false
    private var suppressFormatSelection = false
    private var nameInput: EditText? = null
    private var collectionSpinner: Spinner? = null
    private var formatSpinner: Spinner? = null
    private var conventionSpinner: Spinner? = null
    private var dynamicContainer: LinearLayout? = null
    private var photoStrip: LinearLayout? = null
    private var generateContentView: View? = null
    private val fieldInputs = linkedMapOf<String, EditText>()
    private var pendingFieldValues = linkedMapOf<String, String>()
    private val draftTextFields = mutableListOf<TextFieldRecord>()
    private val draftPhotos = mutableListOf<DraftPhoto>()

    // Photo viewer/capture state.
    private var photoViewerImage: ImageView? = null
    private var photoViewerIndex = 0
    private var photoViewerFromGenerate = false
    private var pendingPhotoCaptureFile: File? = null

    private var oldBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var brightnessCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stagingDir().deleteRecursively()
        db = MimicDatabase(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        setContentView(root)
        showList(CollectionKind.WALLET)
    }

    override fun onDestroy() {
        scanner?.stop()
        photoCapture?.stop()
        discardNewDraftPhotos()
        restoreBrightness()
        db.close()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (screen == Screen.SCAN && !requestingCameraPermission) {
            scanner?.stop()
            scanInterrupted = true
        }
        if (screen == Screen.PHOTO_CAPTURE && !requestingCameraPermission) {
            photoCapture?.stop()
            photoCaptureInterrupted = true
        }
        if (screen == Screen.CODE) restoreBrightness()
    }

    override fun onResume() {
        super.onResume()
        when {
            scanInterrupted && screen == Screen.SCAN -> {
                scanInterrupted = false
                if (scanReturnToGenerate) returnToGenerateFromOverlay() else showList(originCollection)
            }
            photoCaptureInterrupted && screen == Screen.PHOTO_CAPTURE -> {
                photoCaptureInterrupted = false
                cancelPhotoCapture()
            }
            screen == Screen.CODE -> applyCodeBrightness()
        }
        updateSoftkeys(force = true)
    }

    override fun onBackPressed() {
        when (screen) {
            Screen.LIST -> if (activeCollection == CollectionKind.TEMPORARY) showList(CollectionKind.WALLET) else super.onBackPressed()
            Screen.SCAN -> if (scanReturnToGenerate) returnToGenerateFromOverlay() else showList(originCollection)
            Screen.GENERATE -> cancelGenerate()
            Screen.CODE, Screen.TEXT -> showList(originCollection)
            Screen.PHOTO_VIEWER -> if (photoViewerFromGenerate) returnToGenerateFromOverlay() else currentCode?.let { showItem(it) }
            Screen.PHOTO_CAPTURE -> cancelPhotoCapture()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        if (keyName == "KEYCODE_MULTIFUNC_LEFT") return true // X320 duplicate of synthesized MENU.

        if (event.action == KeyEvent.ACTION_UP) {
            val softkey = when {
                event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT -> Softkey.LEFT
                keyName == "KEYCODE_MULTIFUNC_CENTER" -> Softkey.CENTER
                keyName == "KEYCODE_MULTIFUNC_RIGHT" -> Softkey.RIGHT
                else -> null
            }
            if (softkey != null) {
                handleSoftkey(softkey)
                return true
            }
        }

        if (screen == Screen.LIST && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { showList(CollectionKind.WALLET); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showList(CollectionKind.TEMPORARY); return true }
            }
        }

        if (screen == Screen.SCAN && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.action == KeyEvent.ACTION_UP) {
                scanner?.adjustZoom(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
            }
            return true
        }

        if (screen == Screen.PHOTO_VIEWER && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_UP && currentPhotoFiles().size > 1) {
                changePhoto(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            }
            return true
        }

        if (screen == Screen.PHOTO_CAPTURE && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN
            )) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> photoCapture?.capture()
                    KeyEvent.KEYCODE_DPAD_UP -> adjustPhotoCaptureZoom(1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> adjustPhotoCaptureZoom(-1)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleSoftkey(key: Softkey) {
        when (screen) {
            Screen.LIST -> when (key) {
                Softkey.LEFT -> deleteSelected()
                Softkey.CENTER -> moveSelectedUp()
                Softkey.RIGHT -> showNewDialog()
            }
            Screen.SCAN -> when (key) {
                Softkey.LEFT -> Unit
                Softkey.CENTER -> scanner?.toggleTorch()
                Softkey.RIGHT -> scanner?.toggleReticle()
            }
            Screen.GENERATE -> when (key) {
                Softkey.LEFT -> scanFromGenerate()
                Softkey.CENTER -> cancelGenerate()
                Softkey.RIGHT -> if (formValid()) saveGenerate()
            }
            Screen.CODE -> when (key) {
                Softkey.LEFT -> currentCode?.let { showGenerate(record = it) }
                Softkey.CENTER -> if (currentItemHasPhotos()) showCurrentItemPhotos()
                Softkey.RIGHT -> rotateCode()
            }
            Screen.TEXT -> when (key) {
                Softkey.LEFT -> currentCode?.let { showGenerate(record = it) }
                Softkey.CENTER -> if (currentItemHasPhotos()) showCurrentItemPhotos()
                Softkey.RIGHT -> Unit
            }
            Screen.PHOTO_VIEWER -> when (key) {
                Softkey.LEFT -> if (currentPhotoFiles().size > 1) changePhoto(-1)
                Softkey.CENTER -> if (photoViewerFromGenerate) deleteDraftPhotoInViewer()
                Softkey.RIGHT -> if (currentPhotoFiles().size > 1) changePhoto(1)
            }
            Screen.PHOTO_CAPTURE -> when (key) {
                Softkey.LEFT -> cancelPhotoCapture()
                Softkey.CENTER -> togglePhotoCaptureTorch()
                Softkey.RIGHT -> photoCapture?.capture()
            }
        }
    }

    private fun showList(collection: CollectionKind, focusUid: String? = pendingFocusUid) {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        restoreBrightness()
        showStatusBar()
        screen = Screen.LIST
        activeCollection = collection
        originCollection = collection
        currentCode = null
        scanReturnToGenerate = false
        generateContentView = null
        root.removeAllViews()

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabLabel("Wallet", collection == CollectionKind.WALLET), LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(tabLabel("Temporary", collection == CollectionKind.TEMPORARY), LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        root.addView(tabs)

        val adapter = CodeAdapter(this)
        adapter.items = db.list(collection)
        listAdapter = adapter
        val list = ListView(this).apply {
            dividerHeight = 1
            choiceMode = ListView.CHOICE_MODE_SINGLE
            this.adapter = adapter
            isFocusable = true
            isFocusableInTouchMode = true
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                showItem(adapter.items[position])
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateSoftkeys() }
                override fun onNothingSelected(parent: AdapterView<*>?) { updateSoftkeys() }
            }
        }
        listView = list
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val focusIndex = focusUid?.let { uid -> adapter.items.indexOfFirst { it.uid == uid } } ?: -1
        if (adapter.items.isNotEmpty()) {
            list.setSelection(if (focusIndex >= 0) focusIndex else 0)
            list.post { list.requestFocus(); updateSoftkeys() }
        } else {
            list.requestFocus()
        }
        pendingFocusUid = null
        updateSoftkeys()
    }

    private fun tabLabel(text: String, selected: Boolean) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(if (selected) Color.WHITE else Color.rgb(26, 26, 26))
        isFocusable = false
        background = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(79, 111, 143) else Color.rgb(217, 222, 227))
        }
    }

    private fun selectedItem(): CodeRecord? {
        val adapter = listAdapter ?: return null
        if (adapter.items.isEmpty()) return null
        val p = listView?.selectedItemPosition ?: -1
        return adapter.items.getOrNull(if (p >= 0) p else 0)
    }

    private fun deleteSelected() {
        val item = selectedItem() ?: return
        val message = TextView(this).apply {
            text = "Delete ${item.name}?"
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        val messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setView(messageContainer)
            .setPositiveButton("Delete") { _, _ ->
                val files = db.photos(item.uid).map { storedPhotoFile(it.fileName) }
                db.delete(item.uid)
                files.forEach { it.delete() }
                showList(activeCollection)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveSelectedUp() {
        val item = selectedItem() ?: return
        if (item.sortOrder <= 0) return
        db.moveUp(item.uid)
        showList(activeCollection, item.uid)
    }

    private fun showNewDialog() {
        val options = arrayOf("Scan", "Generate")
        lateinit var dialog: AlertDialog
        fun activate(position: Int) {
            dialog.dismiss()
            if (position == 1) showGenerate() else showScan()
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("New")
            .setItems(options) { _, which -> activate(which) }
            .setPositiveButton("Select", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val list = dialog.listView
            list.choiceMode = ListView.CHOICE_MODE_NONE
            list.setSelection(0)
            list.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                activate(list.selectedItemPosition.coerceAtLeast(0))
            }
        }
        dialog.show()
    }

    private fun showScan(
        preservedName: String = "",
        preservedCollection: CollectionKind = activeCollection,
        preservedUid: String? = null,
        returnToGenerate: Boolean = false
    ) {
        scanner?.stop()
        originCollection = activeCollection
        draftName = preservedName
        draftCollection = preservedCollection
        editingUid = preservedUid
        scanReturnToGenerate = returnToGenerate
        screen = Screen.SCAN
        hideStatusBar()
        if (returnToGenerate) {
            generateContentView?.visibility = View.GONE
            removeTransientViews()
        } else {
            root.removeAllViews()
            generateContentView = null
        }
        updateSoftkeys()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestingCameraPermission = true
            pendingCameraPurpose = CameraPurpose.SCAN
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            attachScanner()
        }
    }

    private fun attachScanner() {
        requestingCameraPermission = false
        pendingCameraPurpose = null
        if (screen != Screen.SCAN) return
        val view = CameraScannerView(this,
            onResult = { result -> onScanResult(result) },
            onCameraError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (scanReturnToGenerate) returnToGenerateFromOverlay() else showList(originCollection)
            }
        )
        scanner = view
        removeTransientViews()
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.requestFocus()
        view.start()
        updateSoftkeys()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        requestingCameraPermission = false
        val purpose = pendingCameraPurpose
        pendingCameraPurpose = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            when (purpose) {
                CameraPurpose.SCAN -> attachScanner()
                CameraPurpose.PHOTO -> launchPhotoCapture()
                null -> Unit
            }
        } else {
            Toast.makeText(this, "Camera permission is required to use the camera.", Toast.LENGTH_LONG).show()
            when (purpose) {
                CameraPurpose.SCAN -> if (scanReturnToGenerate) returnToGenerateFromOverlay() else showList(originCollection)
                CameraPurpose.PHOTO -> returnToGenerateFromOverlay()
                null -> Unit
            }
        }
    }

    private fun onScanResult(result: Result) {
        scanner?.stop()
        vibrateConfirmation()
        if (scanReturnToGenerate) {
            applyScanToDraft(result)
            buildGenerateForm()
        } else {
            showGenerate(scanResult = result, preservedName = draftName, preservedCollection = draftCollection, preservedUid = editingUid)
        }
    }

    private fun applyScanToDraft(result: Result) {
        scanner = null
        scanReturnToGenerate = false
        draftKind = ItemKind.CODE
        draftFormat = result.barcodeFormat
        draftTextFields.clear()
        if (draftFormat == BarcodeFormat.QR_CODE) {
            val parsed = QrPayload.parse(result)
            draftConvention = parsed.convention
            originalConvention = parsed.convention
            originalPayload = result.text
            qrFieldsDirty = false
            pendingFieldValues = parsed.values
        } else {
            draftConvention = QrConvention.TEXT
            originalConvention = null
            originalPayload = result.text
            qrFieldsDirty = false
            pendingFieldValues = linkedMapOf(payloadLabel(draftFormat) to result.text)
        }
    }

    private fun showGenerate(
        record: CodeRecord? = null,
        scanResult: Result? = null,
        preservedName: String? = null,
        preservedCollection: CollectionKind? = null,
        preservedUid: String? = null
    ) {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        discardNewDraftPhotos()
        draftPhotos.clear()
        draftTextFields.clear()
        restoreBrightness()
        showStatusBar()

        if (record != null) {
            editingUid = record.uid
            draftName = record.name
            draftCollection = record.collection
            draftKind = record.kind
            draftFormat = record.format
            draftConvention = record.convention ?: QrConvention.RAW
            originalConvention = record.convention
            originalPayload = record.payload
            qrFieldsDirty = false
            if (record.kind == ItemKind.PLAIN_TEXT) {
                draftTextFields += db.textFields(record.uid).ifEmpty { listOf(TextFieldRecord("", "")) }
                pendingFieldValues.clear()
            } else {
                pendingFieldValues = if (record.format == BarcodeFormat.QR_CODE) {
                    QrPayload.fieldsFromStored(draftConvention, record.payload)
                } else linkedMapOf(payloadLabel(record.format) to record.payload)
            }
            draftPhotos += db.photos(record.uid).mapNotNull { photo ->
                val file = storedPhotoFile(photo.fileName)
                if (file.exists()) DraftPhoto(photo.id, file, photo.fileName, false) else null
            }
        } else if (scanResult != null) {
            editingUid = preservedUid
            draftName = preservedName.orEmpty()
            draftCollection = preservedCollection ?: activeCollection
            draftKind = ItemKind.CODE
            draftFormat = scanResult.barcodeFormat
            if (draftFormat == BarcodeFormat.QR_CODE) {
                val parsed = QrPayload.parse(scanResult)
                draftConvention = parsed.convention
                originalConvention = parsed.convention
                originalPayload = scanResult.text
                qrFieldsDirty = false
                pendingFieldValues = parsed.values
            } else {
                draftConvention = QrConvention.TEXT
                originalConvention = null
                originalPayload = scanResult.text
                qrFieldsDirty = false
                pendingFieldValues = linkedMapOf(payloadLabel(draftFormat) to scanResult.text)
            }
            if (preservedUid != null) {
                draftPhotos += db.photos(preservedUid).mapNotNull { photo ->
                    val file = storedPhotoFile(photo.fileName)
                    if (file.exists()) DraftPhoto(photo.id, file, photo.fileName, false) else null
                }
            }
        } else {
            editingUid = null
            draftName = ""
            draftCollection = activeCollection
            draftKind = ItemKind.PLAIN_TEXT
            draftFormat = BarcodeFormat.QR_CODE
            draftConvention = QrConvention.TEXT
            originalConvention = null
            originalPayload = null
            qrFieldsDirty = true
            pendingFieldValues.clear()
            draftTextFields += TextFieldRecord("", "")
        }
        buildGenerateForm()
    }

    private fun buildGenerateForm() {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        restoreBrightness()
        showStatusBar()
        screen = Screen.GENERATE
        scanReturnToGenerate = false
        loadingForm = true
        fieldInputs.clear()
        root.removeAllViews()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        scroll.addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        generateContentView = scroll

        nameInput = addTextField(form, "Name", draftName, singleLine = true) { draftName = it; updateSoftkeys() }
        collectionSpinner = addSpinner(form, "Collection", CollectionKind.entries.map { it.label }, CollectionKind.entries.indexOf(draftCollection)) { index ->
            draftCollection = CollectionKind.entries[index]
            updateSoftkeys()
        }
        formatSpinner = addSpinner(
            form,
            "Format",
            Formats.generateChoices.map { it.label },
            Formats.generateIndex(draftKind, draftFormat)
        ) { index -> onGenerateFormatSelected(index) }

        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dynamicContainer = dynamic
        form.addView(dynamic)
        rebuildDynamicFields()
        addPhotosSection(form)
        loadingForm = false
        nameInput?.requestFocus()
        updateSoftkeys()
    }

    private fun onGenerateFormatSelected(index: Int) {
        if (loadingForm || suppressFormatSelection) return
        val choice = Formats.generateChoices.getOrNull(index) ?: return
        val same = choice.kind == draftKind && (choice.kind == ItemKind.PLAIN_TEXT || choice.format == draftFormat)
        if (same) return

        snapshotGenerateInputs()
        val crossesKind = choice.kind != draftKind
        if (crossesKind && draftContentIsNonBlank()) {
            AlertDialog.Builder(this)
                .setMessage("Changing format will discard the current ${if (draftKind == ItemKind.PLAIN_TEXT) "text fields" else "code data"}.")
                .setPositiveButton("Change") { _, _ -> applyGenerateFormatChoice(choice) }
                .setNegativeButton("Cancel") { _, _ -> restoreFormatSpinnerSelection() }
                .setOnCancelListener { restoreFormatSpinnerSelection() }
                .show()
        } else {
            applyGenerateFormatChoice(choice)
        }
    }

    private fun restoreFormatSpinnerSelection() {
        val spinner = formatSpinner ?: return
        suppressFormatSelection = true
        spinner.setSelection(Formats.generateIndex(draftKind, draftFormat), false)
        suppressFormatSelection = false
    }

    private fun applyGenerateFormatChoice(choice: GenerateFormatChoice) {
        draftKind = choice.kind
        originalPayload = null
        originalConvention = null
        qrFieldsDirty = true
        pendingFieldValues.clear()
        if (choice.kind == ItemKind.PLAIN_TEXT) {
            draftTextFields.clear()
            draftTextFields += TextFieldRecord("", "")
        } else {
            draftFormat = choice.format ?: BarcodeFormat.QR_CODE
            draftConvention = QrConvention.TEXT
            pendingFieldValues = if (draftFormat == BarcodeFormat.QR_CODE) {
                QrPayload.blank(draftConvention)
            } else {
                linkedMapOf(payloadLabel(draftFormat) to "")
            }
            draftTextFields.clear()
        }
        restoreFormatSpinnerSelection()
        rebuildDynamicFields()
        updateSoftkeys()
    }

    private fun rebuildDynamicFields() {
        val container = dynamicContainer ?: return
        container.removeAllViews()
        fieldInputs.clear()
        conventionSpinner = null

        if (draftKind == ItemKind.PLAIN_TEXT) {
            rebuildPlainTextFields(container)
            return
        }

        if (draftFormat == BarcodeFormat.QR_CODE) {
            conventionSpinner = addSpinner(
                container, "Content type", QrConvention.entries.map { it.label },
                QrConvention.entries.indexOf(draftConvention)
            ) { index ->
                val next = QrConvention.entries[index]
                if (!loadingForm && next != draftConvention) {
                    draftConvention = next
                    originalPayload = null
                    originalConvention = null
                    qrFieldsDirty = true
                    pendingFieldValues = QrPayload.blank(next)
                    rebuildDynamicFields()
                }
                updateSoftkeys()
            }
            val values = if (pendingFieldValues.isNotEmpty()) pendingFieldValues else QrPayload.blank(draftConvention)
            values.forEach { (label, value) ->
                fieldInputs[label] = addTextField(
                    container, label + fieldHint(label), value,
                    singleLine = label !in setOf("Body", "Address", "Description", "Content", "Payload", "Message")
                ) {
                    if (!loadingForm) qrFieldsDirty = true
                    updateSoftkeys()
                }
            }
        } else {
            val label = payloadLabel(draftFormat)
            val value = pendingFieldValues[label] ?: originalPayload.orEmpty()
            fieldInputs[label] = addTextField(
                container,
                label,
                value,
                singleLine = draftFormat !in setOf(BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC)
            ) {
                originalPayload = null
                updateSoftkeys()
            }
        }
    }

    private fun rebuildPlainTextFields(container: LinearLayout) {
        if (draftTextFields.isEmpty()) draftTextFields += TextFieldRecord("", "")
        draftTextFields.toList().forEachIndexed { index, field ->
            addTextField(container, "Heading ${index + 1}", field.heading, singleLine = true) { value ->
                if (index < draftTextFields.size) draftTextFields[index] = draftTextFields[index].copy(heading = value)
                updateSoftkeys()
            }
            addTextField(container, "Text ${index + 1}", field.text, singleLine = false) { value ->
                if (index < draftTextFields.size) draftTextFields[index] = draftTextFields[index].copy(text = value)
                updateSoftkeys()
            }
            if (draftTextFields.size > 1) {
                container.addView(actionButton("Remove pair") {
                    if (index < draftTextFields.size) {
                        draftTextFields.removeAt(index)
                        rebuildDynamicFields()
                        updateSoftkeys()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                })
            }
        }
        container.addView(actionButton("Add heading and text") {
            draftTextFields += TextFieldRecord("", "")
            rebuildDynamicFields()
            updateSoftkeys()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }

    private fun addPhotosSection(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "Photos"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(2))
        })
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        photoStrip = strip
        horizontal.addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(60)))
        parent.addView(horizontal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        rebuildPhotoStrip()
    }

    private fun rebuildPhotoStrip() {
        val strip = photoStrip ?: return
        strip.removeAllViews()
        draftPhotos.forEachIndexed { index, photo ->
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = focusableTileBackground()
                isFocusable = true
                isClickable = true
                contentDescription = "Photo ${index + 1}"
                setImageBitmap(PhotoImages.decodeSampled(photo.file, dp(48), dp(48)))
                setOnClickListener { showDraftPhotoViewer(index) }
            }
            strip.addView(thumb, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(4) })
        }
        strip.addView(TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = focusableTileBackground()
            isFocusable = true
            isClickable = true
            contentDescription = "Add photo"
            setOnClickListener { beginAddPhoto() }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
    }

    private fun focusableTileBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(Color.rgb(217, 222, 227))
            setStroke(dp(2), Color.rgb(79, 111, 143))
        }
        val normal = GradientDrawable().apply {
            setColor(Color.rgb(238, 240, 242))
            setStroke(dp(1), Color.rgb(160, 166, 172))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.BLACK)
        background = focusableTileBackground()
        isFocusable = true
        isClickable = true
        setOnClickListener { action() }
    }

    private fun addTextField(parent: LinearLayout, label: String, value: String, singleLine: Boolean, changed: (String) -> Unit): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(2), 0, 0)
        })
        val edit = EditText(this).apply {
            setText(value)
            textSize = 15f
            isSingleLine = singleLine
            if (!singleLine) {
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
            }
            inputType = InputType.TYPE_CLASS_TEXT or if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSelectAllOnFocus(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        parent.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return edit
    }

    private fun addSpinner(parent: LinearLayout, label: String, items: List<String>, selection: Int, selected: (Int) -> Unit): Spinner {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(2), 0, 0)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selection, false)
            isFocusable = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(position) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        parent.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return spinner
    }

    private fun snapshotGenerateInputs() {
        draftName = nameInput?.text?.toString().orEmpty()
        draftCollection = CollectionKind.entries.getOrElse(collectionSpinner?.selectedItemPosition ?: 0) { CollectionKind.WALLET }
        if (draftKind == ItemKind.CODE) {
            pendingFieldValues = linkedMapOf<String, String>().apply {
                fieldInputs.forEach { (key, edit) -> put(key, edit.text.toString()) }
            }
        }
    }

    private fun draftContentIsNonBlank(): Boolean = if (draftKind == ItemKind.PLAIN_TEXT) {
        draftTextFields.any { it.heading.isNotBlank() || it.text.isNotBlank() }
    } else {
        originalPayload?.isNotBlank() == true || fieldInputs.values.any { it.text?.isNotBlank() == true }
    }

    private fun scanFromGenerate() {
        snapshotGenerateInputs()
        showScan(draftName, draftCollection, editingUid, returnToGenerate = true)
    }

    private fun formValid(): Boolean {
        if (screen != Screen.GENERATE) return false
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || !db.isNameAvailable(name, editingUid)) return false

        if (draftKind == ItemKind.PLAIN_TEXT) {
            return draftTextFields.isNotEmpty() && draftTextFields.all { it.heading.isNotBlank() && it.text.isNotBlank() }
        }

        val payload = runCatching { currentFormPayload() }.getOrNull() ?: return false
        if (draftFormat == BarcodeFormat.QR_CODE) {
            val values = fieldInputs.mapValues { it.value.text.toString() }
            if (!QrPayload.requiredFieldsValid(draftConvention, values)) return false
        }
        return BarcodeCodec.canEncode(payload, draftFormat)
    }

    private fun currentFormPayload(): String {
        if (draftKind == ItemKind.PLAIN_TEXT) return ""
        if (draftFormat != BarcodeFormat.QR_CODE) return fieldInputs.values.firstOrNull()?.text?.toString().orEmpty()
        if (originalPayload != null && !qrFieldsDirty && originalConvention == draftConvention) return originalPayload!!
        val values = fieldInputs.mapValues { it.value.text.toString() }
        return QrPayload.serialize(draftConvention, values)
    }

    private fun saveGenerate() {
        if (!formValid()) return
        snapshotGenerateInputs()
        val uid = editingUid ?: UUID.randomUUID().toString()
        val payload = if (draftKind == ItemKind.CODE) currentFormPayload() else ""
        val record = CodeRecord(
            uid = uid,
            name = draftName.trim(),
            collection = draftCollection,
            kind = draftKind,
            format = if (draftKind == ItemKind.CODE) draftFormat else BarcodeFormat.QR_CODE,
            convention = if (draftKind == ItemKind.CODE && draftFormat == BarcodeFormat.QR_CODE) draftConvention else null,
            payload = payload,
            sortOrder = 0,
            displayRotation = -1,
            displayInverted = false
        )
        try {
            val fields = if (draftKind == ItemKind.PLAIN_TEXT) {
                draftTextFields.mapIndexed { index, field ->
                    TextFieldRecord(field.heading.trim(), field.text.trim(), index)
                }
            } else emptyList()
            val saved = db.save(record, fields)
            commitDraftPhotos(saved.uid)
            activeCollection = saved.collection
            pendingFocusUid = saved.uid
            draftPhotos.clear()
            showList(saved.collection, saved.uid)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Item could not be saved.", Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelGenerate() {
        discardNewDraftPhotos()
        draftPhotos.clear()
        showList(originCollection)
    }

    private fun showItem(record: CodeRecord) {
        if (record.kind == ItemKind.PLAIN_TEXT) showPlainText(record) else showCode(record)
    }

    private fun showCode(record: CodeRecord) {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        screen = Screen.CODE
        originCollection = activeCollection
        currentCode = record
        hideStatusBar()
        root.removeAllViews()
        generateContentView = null
        val view = BarcodeView(this).apply {
            setCode(record.payload, record.format)
            rotation = record.displayRotation
            inverted = false
            isFocusable = true
        }
        barcodeView = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.requestFocus()
        applyCodeBrightness()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSoftkeys()
    }

    private fun showPlainText(record: CodeRecord) {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        restoreBrightness()
        screen = Screen.TEXT
        originCollection = activeCollection
        currentCode = record
        hideStatusBar()
        root.removeAllViews()
        generateContentView = null

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        val fields = db.textFields(record.uid)
        fields.forEach { field ->
            content.addView(TextView(this).apply {
                text = field.heading
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, 0)
            })
            content.addView(TextView(this).apply {
                text = field.text
                textSize = 24f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.requestFocus()
        updateSoftkeys()
    }

    private fun rotateCode() {
        val record = currentCode ?: return
        val view = barcodeView ?: return
        val current = view.effectiveRotation()
        val next = (current + 270) % 360
        view.rotation = next
        val updated = record.copy(displayRotation = next, displayInverted = false)
        currentCode = updated
        db.updateDisplay(updated.uid, next)
    }

    private fun currentItemHasPhotos(): Boolean {
        val item = currentCode ?: return false
        return db.photos(item.uid).any { storedPhotoFile(it.fileName).exists() }
    }

    private fun showCurrentItemPhotos() {
        val item = currentCode ?: return
        val photos = db.photos(item.uid).filter { storedPhotoFile(it.fileName).exists() }
        if (photos.isEmpty()) return
        restoreBrightness()
        photoViewerFromGenerate = false
        photoViewerIndex = 0
        screen = Screen.PHOTO_VIEWER
        hideStatusBar()
        root.removeAllViews()
        showPhotoViewerImage()
        updateSoftkeys()
    }

    private fun showDraftPhotoViewer(index: Int) {
        if (draftPhotos.isEmpty()) return
        snapshotGenerateInputs()
        photoViewerFromGenerate = true
        photoViewerIndex = index.coerceIn(0, draftPhotos.lastIndex)
        screen = Screen.PHOTO_VIEWER
        hideStatusBar()
        generateContentView?.visibility = View.GONE
        removeTransientViews()
        showPhotoViewerImage()
        updateSoftkeys()
    }

    private fun showPhotoViewerImage() {
        photoViewerImage?.let { root.removeView(it) }
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isFocusableInTouchMode = true
        }
        photoViewerImage = image
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        image.post { loadCurrentPhoto() }
        image.requestFocus()
    }

    private fun currentPhotoFiles(): List<File> = if (photoViewerFromGenerate) {
        draftPhotos.map { it.file }
    } else {
        val uid = currentCode?.uid ?: return emptyList()
        db.photos(uid).map { storedPhotoFile(it.fileName) }.filter { it.exists() }
    }

    private fun loadCurrentPhoto() {
        val image = photoViewerImage ?: return
        val files = currentPhotoFiles()
        if (files.isEmpty()) return
        photoViewerIndex = photoViewerIndex.coerceIn(0, files.lastIndex)
        image.setImageBitmap(PhotoImages.decodeSampled(files[photoViewerIndex], image.width.coerceAtLeast(240), image.height.coerceAtLeast(240)))
        image.contentDescription = "Photo ${photoViewerIndex + 1} of ${files.size}"
    }

    private fun changePhoto(delta: Int) {
        val files = currentPhotoFiles()
        if (files.isEmpty()) return
        photoViewerIndex = (photoViewerIndex + delta + files.size) % files.size
        loadCurrentPhoto()
    }

    private fun deleteDraftPhotoInViewer() {
        if (!photoViewerFromGenerate || draftPhotos.isEmpty()) return
        val index = photoViewerIndex.coerceIn(0, draftPhotos.lastIndex)
        val photo = draftPhotos.removeAt(index)
        if (photo.isNew) photo.file.delete()
        if (draftPhotos.isEmpty()) {
            returnToGenerateFromOverlay()
        } else {
            photoViewerIndex = index.coerceAtMost(draftPhotos.lastIndex)
            loadCurrentPhoto()
            updateSoftkeys()
        }
    }

    private fun beginAddPhoto() {
        snapshotGenerateInputs()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestingCameraPermission = true
            pendingCameraPurpose = CameraPurpose.PHOTO
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            launchPhotoCapture()
        }
    }

    private fun launchPhotoCapture() {
        requestingCameraPermission = false
        pendingCameraPurpose = null
        screen = Screen.PHOTO_CAPTURE
        hideStatusBar()
        generateContentView?.visibility = View.GONE
        removeTransientViews()

        val id = UUID.randomUUID().toString()
        val file = File(stagingDir(), "$id.jpg")
        pendingPhotoCaptureFile = file
        val view = PhotoCaptureView(
            this,
            file,
            onCaptured = { captured ->
                pendingPhotoCaptureFile = null
                draftPhotos += DraftPhoto(id, captured, null, true)
                returnToGenerateFromOverlay()
            },
            onCameraError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                cancelPhotoCapture()
            }
        )
        photoCapture = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.requestFocus()
        view.start()
        updateSoftkeys()
    }

    private fun cancelPhotoCapture() {
        photoCapture?.stop()
        photoCapture = null
        pendingPhotoCaptureFile?.delete()
        pendingPhotoCaptureFile = null
        returnToGenerateFromOverlay()
    }

    private fun togglePhotoCaptureTorch() {
        photoCapture?.toggleTorch()
    }

    private fun adjustPhotoCaptureZoom(direction: Int) {
        photoCapture?.adjustZoom(direction)
    }

    private fun returnToGenerateFromOverlay() {
        scanner?.stop()
        scanner = null
        photoCapture?.stop()
        photoCapture = null
        pendingPhotoCaptureFile?.let { if (!draftPhotos.any { photo -> photo.file == it }) it.delete() }
        pendingPhotoCaptureFile = null
        photoViewerImage = null
        scanReturnToGenerate = false
        removeTransientViews()
        showStatusBar()
        screen = Screen.GENERATE
        generateContentView?.visibility = View.VISIBLE
        rebuildPhotoStrip()
        generateContentView?.requestFocus()
        updateSoftkeys(force = true)
    }

    private fun removeTransientViews() {
        val keep = generateContentView
        val toRemove = mutableListOf<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child !== keep) toRemove += child
        }
        toRemove.forEach { root.removeView(it) }
    }

    private fun commitDraftPhotos(uid: String) {
        val previous = db.photos(uid)
        val previousById = previous.associateBy { it.id }
        val destinationDir = photoStorageDir().apply { mkdirs() }
        val desired = mutableListOf<PhotoRecord>()

        draftPhotos.forEachIndexed { index, draft ->
            val fileName = if (draft.isNew) "${draft.id}.jpg" else draft.fileName ?: "${draft.id}.jpg"
            val destination = File(destinationDir, fileName)
            if (draft.isNew) {
                if (!draft.file.renameTo(destination)) {
                    draft.file.copyTo(destination, overwrite = true)
                    draft.file.delete()
                }
            }
            desired += PhotoRecord(draft.id, uid, fileName, index)
        }

        db.replacePhotos(uid, desired)
        val desiredIds = desired.mapTo(hashSetOf()) { it.id }
        previousById.values.filter { it.id !in desiredIds }.forEach { storedPhotoFile(it.fileName).delete() }
    }

    private fun discardNewDraftPhotos() {
        draftPhotos.filter { it.isNew }.forEach { it.file.delete() }
        pendingPhotoCaptureFile?.delete()
        pendingPhotoCaptureFile = null
    }

    private fun photoStorageDir() = File(filesDir, "photos")
    private fun storedPhotoFile(fileName: String) = File(photoStorageDir(), fileName)
    private fun stagingDir() = File(cacheDir, "mimic-photo-staging").apply { mkdirs() }

    private fun updateSoftkeys(force: Boolean = false) {
        val labels = when (screen) {
            Screen.LIST -> {
                val item = selectedItem()
                Triple(if (item == null) "" else "Delete", if (item != null && item.sortOrder > 0) "Move up" else "", "New")
            }
            Screen.SCAN -> Triple("", "Torch", "Reticle")
            Screen.GENERATE -> Triple("Scan", "Cancel", if (formValid()) "Save" else "")
            Screen.CODE -> Triple("Edit", if (currentItemHasPhotos()) "Photos" else "", "Rotate")
            Screen.TEXT -> Triple("Edit", if (currentItemHasPhotos()) "Photos" else "", "")
            Screen.PHOTO_VIEWER -> {
                val hasMultiple = currentPhotoFiles().size > 1
                Triple(if (hasMultiple) "Previous" else "", if (photoViewerFromGenerate) "Delete" else "", if (hasMultiple) "Next" else "")
            }
            Screen.PHOTO_CAPTURE -> Triple("Cancel", "Torch", "Capture")
        }
        if (!force && labels == lastSoftkeys) return
        lastSoftkeys = labels
        sendBroadcast(Intent(SONIM_SOFTKEY_ACTION).apply {
            putExtra("left", labels.first)
            putExtra("center", labels.second)
            putExtra("right", labels.third)
            putExtra("from_package", packageName)
        })
    }

    private fun payloadLabel(format: BarcodeFormat) = when (format) {
        BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC -> "Content"
        else -> "Value"
    }

    private fun fieldHint(label: String): String = when (label) {
        "Start", "End" -> " (YYYY-MM-DD or YYYY-MM-DD HH:MM)"
        "Hidden" -> " (true/false)"
        else -> ""
    }

    private fun applyCodeBrightness() {
        if (!brightnessCaptured) {
            oldBrightness = window.attributes.screenBrightness
            brightnessCaptured = true
        }
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun restoreBrightness() {
        if (brightnessCaptured) {
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            brightnessCaptured = false
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideStatusBar() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun showStatusBar() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun vibrateConfirmation() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(55L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val CAMERA_REQUEST = 1001
        private const val SONIM_SOFTKEY_ACTION = "android.intent.action.CHANGE_NAV_BAR"
    }
}
