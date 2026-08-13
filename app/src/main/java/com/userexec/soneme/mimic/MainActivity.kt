package com.userexec.soneme.mimic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import java.util.UUID

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private enum class Screen { LIST, SCAN, GENERATE, CODE }
    private enum class Softkey { LEFT, CENTER, RIGHT }

    private lateinit var db: MimicDatabase
    private lateinit var root: LinearLayout
    private var screen = Screen.LIST
    private var activeCollection = CollectionKind.WALLET
    private var originCollection = CollectionKind.WALLET
    private var listView: ListView? = null
    private var listAdapter: CodeAdapter? = null
    private var scanner: CameraScannerView? = null
    private var barcodeView: BarcodeView? = null
    private var currentCode: CodeRecord? = null
    private var requestingCameraPermission = false
    private var scanInterrupted = false
    private var pendingFocusUid: String? = null
    private var lastSoftkeys: Triple<String, String, String>? = null

    // Generate/edit state.
    private var editingUid: String? = null
    private var draftName = ""
    private var draftCollection = CollectionKind.WALLET
    private var draftFormat = BarcodeFormat.QR_CODE
    private var draftConvention = QrConvention.TEXT
    private var originalConvention: QrConvention? = null
    private var originalPayload: String? = null
    private var qrFieldsDirty = true
    private var loadingForm = false
    private var nameInput: EditText? = null
    private var collectionSpinner: Spinner? = null
    private var formatSpinner: Spinner? = null
    private var conventionSpinner: Spinner? = null
    private var dynamicContainer: LinearLayout? = null
    private val fieldInputs = linkedMapOf<String, EditText>()
    private var pendingFieldValues = linkedMapOf<String, String>()

    private var oldBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var brightnessCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (screen == Screen.CODE) restoreBrightness()
    }

    override fun onResume() {
        super.onResume()
        if (scanInterrupted && screen == Screen.SCAN) {
            scanInterrupted = false
            showList(originCollection)
        } else if (screen == Screen.CODE) {
            applyCodeBrightness()
        }
        updateSoftkeys(force = true)
    }

    override fun onBackPressed() {
        when (screen) {
            Screen.LIST -> if (activeCollection == CollectionKind.TEMPORARY) showList(CollectionKind.WALLET) else super.onBackPressed()
            Screen.SCAN -> showList(originCollection)
            Screen.GENERATE -> showList(originCollection)
            Screen.CODE -> showList(originCollection)
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
                Softkey.CENTER -> {
                    val on = scanner?.toggleTorch() ?: false
                    Toast.makeText(this, if (on) "Torch on" else "Torch off", Toast.LENGTH_SHORT).show()
                }
                Softkey.RIGHT -> scanner?.toggleReticle()
            }
            Screen.GENERATE -> when (key) {
                Softkey.LEFT -> scanFromGenerate()
                Softkey.CENTER -> showList(originCollection)
                Softkey.RIGHT -> if (formValid()) saveGenerate()
            }
            Screen.CODE -> when (key) {
                Softkey.LEFT -> currentCode?.let { showGenerate(record = it) }
                Softkey.CENTER -> toggleCodeInvert()
                Softkey.RIGHT -> rotateCode()
            }
        }
    }

    private fun showList(collection: CollectionKind, focusUid: String? = pendingFocusUid) {
        scanner?.stop()
        scanner = null
        restoreBrightness()
        showStatusBar()
        screen = Screen.LIST
        activeCollection = collection
        originCollection = collection
        currentCode = null
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
                showCode(adapter.items[position])
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
            addView(
                message,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setView(messageContainer)
            .setPositiveButton("Delete") { _, _ ->
                db.delete(item.uid)
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
        preservedUid: String? = null
    ) {
        scanner?.stop()
        originCollection = activeCollection
        draftName = preservedName
        draftCollection = preservedCollection
        editingUid = preservedUid
        screen = Screen.SCAN
        hideStatusBar()
        root.removeAllViews()
        updateSoftkeys()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestingCameraPermission = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            attachScanner()
        }
    }

    private fun attachScanner() {
        requestingCameraPermission = false
        if (screen != Screen.SCAN) return
        val view = CameraScannerView(this,
            onResult = { result -> onScanResult(result) },
            onCameraError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                showList(originCollection)
            }
        )
        scanner = view
        root.removeAllViews()
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.requestFocus()
        view.start()
        updateSoftkeys()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        requestingCameraPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            attachScanner()
        } else {
            Toast.makeText(this, "Camera permission is required to scan codes.", Toast.LENGTH_LONG).show()
            showList(originCollection)
        }
    }

    private fun onScanResult(result: Result) {
        scanner?.stop()
        vibrateConfirmation()
        showGenerate(scanResult = result, preservedName = draftName, preservedCollection = draftCollection, preservedUid = editingUid)
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
        restoreBrightness()
        showStatusBar()
        screen = Screen.GENERATE
        loadingForm = true
        fieldInputs.clear()
        root.removeAllViews()

        if (record != null) {
            editingUid = record.uid
            draftName = record.name
            draftCollection = record.collection
            draftFormat = record.format
            draftConvention = record.convention ?: QrConvention.RAW
            originalConvention = record.convention
            originalPayload = record.payload
            qrFieldsDirty = false
            pendingFieldValues = if (record.format == BarcodeFormat.QR_CODE) {
                QrPayload.fieldsFromStored(draftConvention, record.payload)
            } else linkedMapOf(payloadLabel(record.format) to record.payload)
        } else if (scanResult != null) {
            editingUid = preservedUid
            draftName = preservedName.orEmpty()
            draftCollection = preservedCollection ?: activeCollection
            draftFormat = scanResult.getBarcodeFormat()
            if (draftFormat == BarcodeFormat.QR_CODE) {
                val parsed = QrPayload.parse(scanResult)
                draftConvention = parsed.convention
                originalConvention = parsed.convention
                originalPayload = scanResult.getText()
                qrFieldsDirty = false
                pendingFieldValues = parsed.values
            } else {
                draftConvention = QrConvention.TEXT
                originalConvention = null
                originalPayload = scanResult.getText()
                qrFieldsDirty = false
                pendingFieldValues = linkedMapOf(payloadLabel(draftFormat) to scanResult.getText())
            }
        } else {
            editingUid = null
            draftName = ""
            draftCollection = activeCollection
            draftFormat = BarcodeFormat.QR_CODE
            draftConvention = QrConvention.TEXT
            originalConvention = null
            originalPayload = null
            qrFieldsDirty = true
            pendingFieldValues = QrPayload.blank(draftConvention)
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
		scroll.addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        nameInput = addTextField(form, "Name", draftName, singleLine = true) { draftName = it; updateSoftkeys() }
        collectionSpinner = addSpinner(form, "Collection", CollectionKind.entries.map { it.label }, CollectionKind.entries.indexOf(draftCollection)) { index ->
            draftCollection = CollectionKind.entries[index]
            updateSoftkeys()
        }
        formatSpinner = addSpinner(form, "Code type", Formats.all.map { it.label }, Formats.all.indexOfFirst { it.format == draftFormat }.coerceAtLeast(0)) { index ->
            val next = Formats.all[index].format
            if (next != draftFormat) {
                draftFormat = next
                originalPayload = null
                originalConvention = null
                qrFieldsDirty = true
                draftConvention = QrConvention.TEXT
                pendingFieldValues = if (next == BarcodeFormat.QR_CODE) QrPayload.blank(draftConvention) else linkedMapOf(payloadLabel(next) to "")
                rebuildDynamicFields()
            }
            updateSoftkeys()
        }

        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dynamicContainer = dynamic
        form.addView(dynamic)
        rebuildDynamicFields()
        loadingForm = false
        nameInput?.requestFocus()
        updateSoftkeys()
    }

    private fun rebuildDynamicFields() {
        val container = dynamicContainer ?: return
        container.removeAllViews()
        fieldInputs.clear()
        conventionSpinner = null

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
            fieldInputs[label] = addTextField(container, label, value, singleLine = draftFormat !in setOf(BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC)) {
                originalPayload = null
                updateSoftkeys()
            }
        }
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

    private fun scanFromGenerate() {
        draftName = nameInput?.text?.toString().orEmpty()
        draftCollection = CollectionKind.entries.getOrElse(collectionSpinner?.selectedItemPosition ?: 0) { CollectionKind.WALLET }
        showScan(draftName, draftCollection, editingUid)
    }

    private fun formValid(): Boolean {
        if (screen != Screen.GENERATE) return false
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || !db.isNameAvailable(name, editingUid)) return false
        val payload = runCatching { currentFormPayload() }.getOrNull() ?: return false
        if (draftFormat == BarcodeFormat.QR_CODE) {
            val values = fieldInputs.mapValues { it.value.text.toString() }
            if (!QrPayload.requiredFieldsValid(draftConvention, values)) return false
        }
        return BarcodeCodec.canEncode(payload, draftFormat)
    }

    private fun currentFormPayload(): String {
        if (draftFormat != BarcodeFormat.QR_CODE) return fieldInputs.values.firstOrNull()?.text?.toString().orEmpty()
        if (originalPayload != null && !qrFieldsDirty && originalConvention == draftConvention) return originalPayload!!
        val values = fieldInputs.mapValues { it.value.text.toString() }
        return QrPayload.serialize(draftConvention, values)
    }

    private fun saveGenerate() {
        if (!formValid()) return
        val uid = editingUid ?: UUID.randomUUID().toString()
        val payload = currentFormPayload()
        val record = CodeRecord(
            uid = uid,
            name = nameInput?.text?.toString()?.trim().orEmpty(),
            collection = draftCollection,
            format = draftFormat,
            convention = if (draftFormat == BarcodeFormat.QR_CODE) draftConvention else null,
            payload = payload,
            sortOrder = 0,
            displayRotation = -1,
            displayInverted = false
        )
        val saved = db.save(record)
        activeCollection = saved.collection
        pendingFocusUid = saved.uid
        showList(saved.collection, saved.uid)
    }

    private fun showCode(record: CodeRecord) {
        scanner?.stop()
        scanner = null
        screen = Screen.CODE
        originCollection = activeCollection
        currentCode = record
        hideStatusBar()
        root.removeAllViews()
        val view = BarcodeView(this).apply {
            setCode(record.payload, record.format)
            rotation = record.displayRotation
            inverted = record.displayInverted
            isFocusable = true
        }
        barcodeView = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.requestFocus()
        applyCodeBrightness()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSoftkeys()
    }

    private fun toggleCodeInvert() {
        val record = currentCode ?: return
        val view = barcodeView ?: return
        view.inverted = !view.inverted
        val updated = record.copy(displayInverted = view.inverted)
        currentCode = updated
        db.updateDisplay(updated.uid, updated.displayRotation, updated.displayInverted)
    }

    private fun rotateCode() {
        val record = currentCode ?: return
        val view = barcodeView ?: return
        val current = view.effectiveRotation()
        val next = (current + 270) % 360
        view.rotation = next
        val updated = record.copy(displayRotation = next, displayInverted = view.inverted)
        currentCode = updated
        db.updateDisplay(updated.uid, next, updated.displayInverted)
    }

    private fun updateSoftkeys(force: Boolean = false) {
        val labels = when (screen) {
            Screen.LIST -> {
                val item = selectedItem()
                Triple(if (item == null) "" else "Delete", if (item != null && item.sortOrder > 0) "Move up" else "", "New")
            }
            Screen.SCAN -> Triple("", "Torch", "Reticle")
            Screen.GENERATE -> Triple("Scan", "Cancel", if (formValid()) "Save" else "")
            Screen.CODE -> Triple("Edit", "Invert", "Rotate")
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
